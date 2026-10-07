package com.github.highcumontoa.iotdevicetwingatewayjava.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.github.highcumontoa.iotdevicetwingatewayjava.domain.RolloutBatch;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.RolloutStatus;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.RolloutSummary;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.RolloutWave;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.UpgradeStatus;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.UpgradeTask;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.WaveSpec;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.WaveStatus;
import com.github.highcumontoa.iotdevicetwingatewayjava.support.MutableClock;

/**
 * 分批灰度发布服务。
 *
 * 波次按序推进：仅当前一波达标（成功台数 >= requiredSuccesses）才下发下一波；
 * 未达标自动整批停止（HALTED），进行中设备回滚并标记 ABORTED，后续波次保持 WAITING；
 * 人工中止（ABORTED）语义相同但原因可区分。已成功波次的设备结果原样保留。
 *
 * 并发：每个批次一把锁串行化“下发 / 结果确认 / 判定推进 / 中止”，
 * 设备自身状态判定沿用 FirmwareService 的设备锁；锁序固定为 设备锁 -> 批次锁，
 * 设备操作在设备锁内完成后释放，再在批次锁内做波次判定，无死锁、无重复计数。
 */
@Service
public class RolloutService {

    private static final Logger log = LoggerFactory.getLogger(RolloutService.class);

    private final DeviceService deviceService;
    private final FirmwareService firmwareService;
    private final MutableClock clock;
    private final Map<String, RolloutBatch> batches = new ConcurrentHashMap<>();
    private final Map<String, Object> batchLocks = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public RolloutService(DeviceService deviceService, FirmwareService firmwareService, MutableClock clock) {
        this.deviceService = deviceService;
        this.firmwareService = firmwareService;
        this.clock = clock;
    }

    /**
     * 创建灰度批次。所有波次初始 WAITING，批次 CREATED，不立即下发任何设备。
     * 校验：批次号唯一、波次非空、每波至少一台设备、requiredSuccesses 在 [1, 设备数] 内、
     * 同一设备不得跨波重复。
     */
    public RolloutBatch createRollout(String batchId, String targetVersion, List<WaveSpec> waves) {
        if (batchId == null || batchId.isBlank()) {
            throw new IllegalArgumentException("batchId must not be blank");
        }
        if (targetVersion == null || targetVersion.isBlank()) {
            throw new IllegalArgumentException("targetVersion must not be blank");
        }
        if (waves == null || waves.isEmpty()) {
            throw new IllegalArgumentException("at least one wave is required");
        }
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < waves.size(); i++) {
            WaveSpec spec = waves.get(i);
            if (spec == null || spec.deviceIds() == null || spec.deviceIds().isEmpty()) {
                throw new IllegalArgumentException("wave " + i + " must contain devices");
            }
            if (spec.requiredSuccesses() < 1 || spec.requiredSuccesses() > spec.deviceIds().size()) {
                throw new IllegalArgumentException("wave " + i + " requiredSuccesses "
                        + spec.requiredSuccesses() + " out of range [1," + spec.deviceIds().size() + "]");
            }
            for (String deviceId : spec.deviceIds()) {
                if (seen.contains(deviceId)) {
                    throw new IllegalArgumentException("device " + deviceId + " appears in multiple waves");
                }
                seen.add(deviceId);
            }
        }
        if (batches.containsKey(batchId)) {
            throw new IllegalStateException("rollout batch " + batchId + " already exists");
        }
        List<RolloutWave> rolloutWaves = new ArrayList<>();
        for (int i = 0; i < waves.size(); i++) {
            rolloutWaves.add(new RolloutWave(i, waves.get(i)));
        }
        RolloutBatch batch = new RolloutBatch(batchId, targetVersion, List.copyOf(rolloutWaves));
        batches.put(batchId, batch);
        batchLocks.computeIfAbsent(batchId, k -> new Object());
        log.info("batch={} action=create-rollout target={} waves={} sizes={} required={} outcome=CREATED",
                batchId, targetVersion, waves.size(),
                waves.stream().map(w -> String.valueOf(w.deviceIds().size())).toList(),
                waves.stream().map(WaveSpec::requiredSuccesses).toList());
        return batch;
    }

    public Optional<RolloutBatch> getBatch(String batchId) {
        return Optional.ofNullable(batches.get(batchId));
    }

    /**
     * 显式下发下一波：CREATED 时下发第 0 波；其余情况由判定自动推进，重复/提前调用不生效。
     * 待命波次在前置波次达标前绝不下发。
     */
    public Optional<RolloutWave> launchNextWave(String batchId) {
        RolloutBatch batch = requireBatch(batchId);
        synchronized (lockOf(batchId)) {
            int nextIndex = batch.getCurrentWave() + 1;
            if (batch.getStatus() != RolloutStatus.CREATED
                    && !(batch.getStatus() == RolloutStatus.IN_PROGRESS && nextIndex < batch.getWaves().size()
                            && batch.getWaves().get(batch.getCurrentWave()).getStatus() == WaveStatus.PASSED)) {
                log.info("batch={} action=launch outcome=REJECTED status={} currentWave={}",
                        batchId, batch.getStatus(), batch.getCurrentWave());
                return Optional.empty();
            }
            if (nextIndex >= batch.getWaves().size()) {
                return Optional.empty();
            }
            dispatchWave(batch, nextIndex);
            return Optional.of(batch.getWaves().get(nextIndex));
        }
    }

    /** 在已持有批次锁的前提下下发指定波次：逐台调用 FirmwareService，不合格设备立即隔离。 */
    private void dispatchWave(RolloutBatch batch, int waveIndex) {
        RolloutWave wave = batch.getWaves().get(waveIndex);
        wave.setStatus(WaveStatus.RUNNING);
        wave.setDetail("wave dispatched at " + clock.now());
        batch.setCurrentWave(waveIndex);
        batch.setStatus(RolloutStatus.IN_PROGRESS);
        int inProgress = 0;
        int isolated = 0;
        for (String deviceId : wave.getDeviceIds()) {
            UpgradeTask task = firmwareService.startOne(batch.getBatchId(), deviceId,
                    batch.getTargetVersion(), waveIndex);
            if (task.getStatus() == com.github.highcumontoa.iotdevicetwingatewayjava.domain.UpgradeStatus.IN_PROGRESS) {
                inProgress++;
            } else {
                isolated++;
            }
        }
        log.info("batch={} wave={} action=dispatch devices={} inProgress={} isolated={} requiredSuccesses={}",
                batch.getBatchId(), waveIndex, wave.getDeviceIds().size(), inProgress, isolated,
                wave.getRequiredSuccesses());
        evaluateAfterEvent(batch);
    }

    /** 确认某设备升级成功，并在本波设备全部终态后自动判定/推进。 */
    public boolean confirmSuccess(String batchId, String deviceId, String version) {
        RolloutBatch batch = requireBatch(batchId);
        synchronized (lockOf(batchId)) {
            if (batch.getStatus() != RolloutStatus.IN_PROGRESS) {
                log.info("batch={} device={} action=confirm outcome=REJECTED reason=batch not in progress status={}",
                        batchId, deviceId, batch.getStatus());
                return false;
            }
            if (!belongsToCurrentWave(batch, deviceId)) {
                log.info("batch={} device={} action=confirm outcome=REJECTED reason=device not in current wave={}",
                        batchId, deviceId, batch.getCurrentWave());
                return false;
            }
            boolean accepted = firmwareService.confirmSuccess(deviceId, version);
            log.info("batch={} device={} wave={} action=confirm accepted={} version={}",
                    batchId, deviceId, batch.getCurrentWave(), accepted, version);
            if (accepted) {
                evaluateAfterEvent(batch);
            }
            return accepted;
        }
    }

    /** 上报某设备升级失败（FirmwareService 负责回滚到升级前版本），随后自动判定/推进。 */
    public boolean reportFailure(String batchId, String deviceId, String reason) {
        RolloutBatch batch = requireBatch(batchId);
        synchronized (lockOf(batchId)) {
            if (batch.getStatus() != RolloutStatus.IN_PROGRESS
                    || !belongsToCurrentWave(batch, deviceId)) {
                log.info("batch={} device={} action=fail outcome=REJECTED reason=not current wave of in-progress batch",
                        batchId, deviceId);
                return false;
            }
            boolean accepted = firmwareService.reportFailure(deviceId, reason);
            log.info("batch={} device={} wave={} action=fail accepted={} reason={}",
                    batchId, deviceId, batch.getCurrentWave(), accepted, reason);
            if (accepted) {
                evaluateAfterEvent(batch);
            }
            return accepted;
        }
    }

    /** 扫描批次内超时设备并回滚，随后自动判定/推进。 */
    public List<UpgradeTask> sweepTimeouts(String batchId) {
        RolloutBatch batch = requireBatch(batchId);
        synchronized (lockOf(batchId)) {
            List<UpgradeTask> timedOut = firmwareService.sweepTimeouts(batchId);
            if (!timedOut.isEmpty()) {
                log.info("batch={} action=sweep timedOut={} devices={}",
                        batchId, timedOut.size(),
                        timedOut.stream().map(UpgradeTask::getDeviceId).toList());
                evaluateAfterEvent(batch);
            }
            return timedOut;
        }
    }

    /**
     * 人工中止批次：进行中设备回滚标记 ABORTED，待命/未完成波次标记 ABORTED 且不再下发，
     * 当前波标记 ABORTED；已成功设备结果原样保留。
     */
    public List<UpgradeTask> abort(String batchId, String reason) {
        RolloutBatch batch = requireBatch(batchId);
        synchronized (lockOf(batchId)) {
            if (batch.getStatus() == RolloutStatus.COMPLETED
                    || batch.getStatus() == RolloutStatus.HALTED
                    || batch.getStatus() == RolloutStatus.ABORTED) {
                log.info("batch={} action=abort outcome=REJECTED reason=already terminal status={}",
                        batchId, batch.getStatus());
                return List.of();
            }
            List<UpgradeTask> aborted = stopInProgressDevices(batch, reason);
            for (RolloutWave wave : batch.getWaves()) {
                if (wave.getStatus() == WaveStatus.RUNNING || wave.getStatus() == WaveStatus.WAITING) {
                    wave.setStatus(WaveStatus.ABORTED);
                    wave.setDetail("manual abort: " + reason);
                }
            }
            batch.setStatus(RolloutStatus.ABORTED);
            batch.setHaltReason("manual abort: " + reason);
            log.info("batch={} action=abort outcome=ABORTED abortedDevices={} reason={}",
                    batchId, aborted.size(), reason);
            return aborted;
        }
    }

    // ---------------------------------------------------------------------
    // 判定与推进（调用方必须持有批次锁）
    // ---------------------------------------------------------------------

    private static final class Counts {
        int success, failed, timeout, isolated, aborted, inProgress, waiting;
    }

    /**
     * 事件后判定当前波次：
     * 1) 成功数达标：本波 PASSED；有下一波则立即自动下发，否则批次 COMPLETED。
     * 2) 成功已不可能达标（success + inProgress < required）：整批 HALTED，
     *    仍在进行中的设备中止并回滚（ABORTED），后续波保持 WAITING。
     * 3) 尚未见分晓：保持 RUNNING，等待更多结果。
     */
    private void evaluateAfterEvent(RolloutBatch batch) {
        int idx = batch.getCurrentWave();
        if (idx < 0 || batch.getStatus() != RolloutStatus.IN_PROGRESS) {
            return;
        }
        RolloutWave wave = batch.getWaves().get(idx);
        if (wave.getStatus() != WaveStatus.RUNNING) {
            return;
        }
        Counts c = countWave(batch, wave);
        int required = wave.getRequiredSuccesses();
        if (c.success >= required) {
            wave.setStatus(WaveStatus.PASSED);
            wave.setDetail(String.format("passed: success=%d >= required=%d (failed=%d timeout=%d isolated=%d)",
                    c.success, required, c.failed, c.timeout, c.isolated));
            log.info("batch={} wave={} action=judge outcome=PASSED success={} required={} failed={} timeout={} isolated={}",
                    batch.getBatchId(), idx, c.success, required, c.failed, c.timeout, c.isolated);
            if (idx + 1 < batch.getWaves().size()) {
                dispatchWave(batch, idx + 1);
            } else {
                batch.setStatus(RolloutStatus.COMPLETED);
                batch.setHaltReason(null);
                log.info("batch={} action=judge outcome=COMPLETED allWavesPassed={}",
                        batch.getBatchId(), batch.getWaves().size());
            }
            return;
        }
        if (c.success + c.inProgress < required) {
            String reason = String.format("wave %d cannot reach required successes: success=%d inProgress=%d required=%d",
                    idx, c.success, c.inProgress, required);
            List<UpgradeTask> aborted = stopInProgressDevices(batch, reason);
            wave.setStatus(WaveStatus.FAILED);
            wave.setDetail(String.format("failed: success=%d < required=%d (failed=%d timeout=%d isolated=%d aborted=%d)",
                    c.success, required, c.failed, c.timeout, c.isolated, aborted.size()));
            batch.setStatus(RolloutStatus.HALTED);
            batch.setHaltReason(reason);
            log.info("batch={} wave={} action=judge outcome=FAILED batch=HALTED success={} required={} abortedDevices={} 判定依据: 成功+进行中<达标线",
                    batch.getBatchId(), idx, c.success, required, aborted.size());
        }
    }

    /** 中止当前批次所有仍在进行中的设备并回滚，返回被中止的任务（成功/失败等终态设备不受影响）。 */
    private List<UpgradeTask> stopInProgressDevices(RolloutBatch batch, String reason) {
        List<UpgradeTask> aborted = new ArrayList<>();
        for (UpgradeTask task : firmwareService.tasksOfBatch(batch.getBatchId())) {
            if (task.getStatus() == UpgradeStatus.IN_PROGRESS) {
                firmwareService.abortActive(task.getDeviceId(), reason).ifPresent(t -> {
                    aborted.add(t);
                    log.info("batch={} device={} wave={} action=halt-device outcome=ABORTED restored={}",
                            batch.getBatchId(), t.getDeviceId(), t.getWaveIndex(), t.getFromVersion());
                });
            }
        }
        return aborted;
    }

    private Counts countWave(RolloutBatch batch, RolloutWave wave) {
        Counts c = new Counts();
        for (String deviceId : wave.getDeviceIds()) {
            classify(latestTask(batch.getBatchId(), deviceId), c);
        }
        return c;
    }

    /** 取设备在本批次最近一条任务（每设备每批至多一条有效任务，取末尾记录即可）。 */
    private UpgradeTask latestTask(String batchId, String deviceId) {
        UpgradeTask found = null;
        for (UpgradeTask task : firmwareService.tasksOfBatch(batchId)) {
            if (task.getDeviceId().equals(deviceId)) {
                found = task;
            }
        }
        return found;
    }

    /** 互斥归类：一台设备恰好落入一个桶；尚无任务（待命波次）计入 waiting。 */
    private void classify(UpgradeTask task, Counts c) {
        if (task == null) {
            c.waiting++;
            return;
        }
        switch (task.getStatus()) {
            case SUCCESS -> c.success++;
            case IN_PROGRESS, PENDING -> c.inProgress++;
            case ISOLATED -> c.isolated++;
            case ABORTED -> c.aborted++;
            case ROLLED_BACK -> {
                UpgradeStatus cause = task.getRollbackCause();
                if (cause == UpgradeStatus.TIMEOUT) {
                    c.timeout++;
                } else if (cause == UpgradeStatus.ABORTED) {
                    c.aborted++;
                } else {
                    c.failed++;
                }
            }
            default -> {
                // FAILED/TIMEOUT 为回滚前的瞬态，正常不会持久落库；保守计入失败桶。
                c.failed++;
            }
        }
    }

    private boolean belongsToCurrentWave(RolloutBatch batch, String deviceId) {
        int idx = batch.getCurrentWave();
        return idx >= 0 && batch.getWaves().get(idx).getDeviceIds().contains(deviceId);
    }

    private RolloutBatch requireBatch(String batchId) {
        RolloutBatch batch = batches.get(batchId);
        if (batch == null) {
            throw new IllegalArgumentException("rollout batch not found: " + batchId);
        }
        return batch;
    }

    private Object lockOf(String batchId) {
        return batchLocks.computeIfAbsent(batchId, k -> new Object());
    }

    /**
     * 批次与各波次的互斥进度汇总。总数恒等：
     * total = success + failed + timeout + isolated + aborted + inProgress + waiting，
     * 批次各桶 = 各波次同桶之和，与 {@link FirmwareService#tasksOfBatch(String)} 的实际任务逐条对账。
     */
    public RolloutSummary summary(String batchId) {
        RolloutBatch batch = requireBatch(batchId);
        synchronized (lockOf(batchId)) {
            List<RolloutSummary.WaveCount> waveCounts = new ArrayList<>();
            Counts total = new Counts();
            for (RolloutWave wave : batch.getWaves()) {
                Counts c = countWave(batch, wave);
                addInto(total, c);
                waveCounts.add(new RolloutSummary.WaveCount(wave.getIndex(), wave.getStatus(),
                        wave.getDeviceIds().size(), c.success, c.failed, c.timeout, c.isolated,
                        c.aborted, c.inProgress, c.waiting));
            }
            return new RolloutSummary(batch.getBatchId(), batch.getStatus(), batch.getCurrentWave(),
                    batch.getHaltReason(),
                    total.success + total.failed + total.timeout + total.isolated
                            + total.aborted + total.inProgress + total.waiting,
                    total.success, total.failed, total.timeout, total.isolated, total.aborted,
                    total.inProgress, total.waiting, waveCounts);
        }
    }

    private void addInto(Counts dst, Counts src) {
        dst.success += src.success;
        dst.failed += src.failed;
        dst.timeout += src.timeout;
        dst.isolated += src.isolated;
        dst.aborted += src.aborted;
        dst.inProgress += src.inProgress;
        dst.waiting += src.waiting;
    }
}
