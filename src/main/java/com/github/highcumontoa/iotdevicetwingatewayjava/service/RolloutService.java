package com.github.highcumontoa.iotdevicetwingatewayjava.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.github.highcumontoa.iotdevicetwingatewayjava.domain.RolloutDeviceOutcome;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.RolloutPlan;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.RolloutStatus;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.RolloutSummary;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.UpgradeStatus;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.UpgradeTask;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.WaveSpec;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.WaveStatus;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.WaveSummary;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.RolloutWave;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.UpgradeEndCause;

/**
 * 分批灰度发布服务。
 *
 * 一个灰度批次（{@link RolloutPlan}）由多个波次顺序组成：只有前一波达到该波配置的通过条件
 * （minSuccess 至少成功台数），才会开始下一波；未轮到的波次为 PENDING，其设备处于待命，
 * 绝不提前下发。每波全部设备出结论后自动判定：达标自动推进下一波，不达标整批 REJECTED 叫停
 * （在途设备回滚、待命设备取消）。人工 {@link #abort} 与自动叫停语义一致。
 *
 * 结论口径（每台设备唯一）：SUCCESS / FAILED / TIMEOUT / ISOLATED / ABORTED / IN_PROGRESS /
 * WAITING。被叫停的设备为 {@code ABORTED}，区别于设备自身失败（FAILED->ROLLED_BACK）与
 * 前置隔离（ISOLATED）；在途叫停设备回滚到各自升级前版本，已成功设备（含已通过波次）原样保留。
 *
 * 并发：同一台设备同一时刻最多一个进行中升级（{@link FirmwareService} 设备锁保证），波次推进、
 * 结果确认、叫停均在批次锁内串行，锁顺序固定为“批次锁 -> 设备锁”，结论稳定确定，不重复计数。
 */
@Service
public class RolloutService {

    private static final Logger log = LoggerFactory.getLogger(RolloutService.class);

    private final FirmwareService firmwareService;

    private final Map<String, RolloutPlan> rollouts = new ConcurrentHashMap<>();
    private final Map<String, Object> rolloutLocks = new ConcurrentHashMap<>();
    /** deviceId -> rolloutId：设备当前归属的（含历史）灰度批次。 */
    private final Map<String, String> assignment = new ConcurrentHashMap<>();
    /** 全局创建锁：保证跨批次的设备占用检查与登记原子，杜绝并发创建重复占用设备。 */
    private final Object createLock = new Object();

    public RolloutService(FirmwareService firmwareService) {
        this.firmwareService = firmwareService;
    }

    /** 创建灰度批次：校验波次/通过条件，设备全部待命（不下发）。 */
    public RolloutPlan createRollout(String rolloutId, String targetVersion, List<WaveSpec> waves) {
        Objects.requireNonNull(rolloutId, "rolloutId");
        Objects.requireNonNull(targetVersion, "targetVersion");
        if (waves == null || waves.isEmpty()) {
            throw new IllegalArgumentException("rollout " + rolloutId + " must contain at least one wave");
        }
        Set<String> batchDevices = new HashSet<>();
        for (int i = 0; i < waves.size(); i++) {
            WaveSpec spec = waves.get(i);
            if (spec == null || spec.deviceIds() == null || spec.deviceIds().isEmpty()) {
                throw new IllegalArgumentException("wave " + i + " must contain at least one device");
            }
            if (spec.minSuccess() < 0 || spec.minSuccess() > spec.deviceIds().size()) {
                throw new IllegalArgumentException("wave " + i + " minSuccess " + spec.minSuccess()
                        + " out of range [0," + spec.deviceIds().size() + "]");
            }
            for (String deviceId : spec.deviceIds()) {
                if (!batchDevices.add(deviceId)) {
                    throw new IllegalArgumentException(
                            "device " + deviceId + " appears in more than one wave of rollout " + rolloutId);
                }
            }
        }
        RolloutPlan plan = new RolloutPlan(rolloutId, targetVersion);
        for (int i = 0; i < waves.size(); i++) {
            WaveSpec spec = waves.get(i);
            plan.getWaves().add(new RolloutWave(i, spec.deviceIds(), spec.minSuccess()));
        }
        synchronized (createLock) {
            if (rollouts.containsKey(rolloutId)) {
                throw new IllegalArgumentException("rollout " + rolloutId + " already exists");
            }
            // 跨批次并发检查：一台设备同时只能属于一个进行中的灰度批次。
            for (String deviceId : batchDevices) {
                String other = assignment.get(deviceId);
                if (other != null && !isTerminal(rollouts.get(other))) {
                    throw new IllegalStateException(
                            "device " + deviceId + " already belongs to active rollout " + other);
                }
            }
            for (RolloutWave wave : plan.getWaves()) {
                for (String deviceId : wave.getDeviceIds()) {
                    assignment.put(deviceId, rolloutId);
                }
            }
            rollouts.put(rolloutId, plan);
        }
        rolloutLocks.put(rolloutId, new Object());
        log.info("rollout={} action=create target={} waves={} devices={} outcome=PENDING all devices standing by",
                rolloutId, targetVersion, waves.size(), batchDevices.size());
        return plan;
    }

    /** 启动灰度（第一波）；已开始/终态批次重复调用返回 false。 */
    public boolean start(String rolloutId) {
        RolloutPlan plan = require(rolloutId);
        synchronized (lock(rolloutId)) {
            if (plan.getStatus() != RolloutStatus.PENDING) {
                log.info("rollout={} action=start outcome=REJECTED reason=status={}", rolloutId, plan.getStatus());
                return false;
            }
            dispatchWave(plan, 0);
            return true;
        }
    }

    /** 设备确认成功，若本波全部出结论则自动达标判定并推进。 */
    public boolean confirmSuccess(String deviceId, String version) {
        String rolloutId = assignment.get(deviceId);
        if (rolloutId == null) {
            return firmwareService.confirmSuccess(deviceId, version);
        }
        RolloutPlan plan = require(rolloutId);
        synchronized (lock(rolloutId)) {
            boolean accepted = firmwareService.confirmSuccess(deviceId, version);
            log.info("rollout={} device={} action=confirm accepted={} version={}",
                    rolloutId, deviceId, accepted, version);
            if (accepted) {
                evaluateAfterDevice(plan, deviceId);
            }
            return accepted;
        }
    }

    /** 设备上报失败（触发回滚），若本波全部出结论则自动判定。 */
    public boolean reportFailure(String deviceId, String reason) {
        String rolloutId = assignment.get(deviceId);
        if (rolloutId == null) {
            return firmwareService.reportFailure(deviceId, reason);
        }
        RolloutPlan plan = require(rolloutId);
        synchronized (lock(rolloutId)) {
            boolean accepted = firmwareService.reportFailure(deviceId, reason);
            log.info("rollout={} device={} action=fail accepted={} reason={}", rolloutId, deviceId, accepted, reason);
            if (accepted) {
                evaluateAfterDevice(plan, deviceId);
            }
            return accepted;
        }
    }

    /**
     * 推进时间：扫描所有进行中批次的在途设备超时，并对受影响批次自动判定/推进；
     * 再兜底扫描其余在途升级（非灰度裸批次）。返回超时设备数。
     */
    public int sweepTimeouts() {
        int totalTimedOut = 0;
        Set<String> covered = new HashSet<>();
        for (RolloutPlan plan : new ArrayList<>(rollouts.values())) {
            if (isTerminal(plan)) {
                continue;
            }
            String rolloutId = plan.getRolloutId();
            synchronized (lock(rolloutId)) {
                List<String> candidates = inFlightDevices(plan);
                covered.addAll(candidates);
                List<UpgradeTask> timedOut = firmwareService.sweepTimeouts(candidates);
                if (!timedOut.isEmpty()) {
                    totalTimedOut += timedOut.size();
                    log.info("rollout={} action=sweep timedOut={} devices={}",
                            rolloutId, timedOut.size(),
                            timedOut.stream().map(UpgradeTask::getDeviceId).toList());
                    for (UpgradeTask task : timedOut) {
                        evaluateAfterDevice(plan, task.getDeviceId());
                    }
                }
            }
        }
        // 兜底：只扫不属于进行中灰度批次的在途设备（非灰度裸升级），避免重复处理。
        List<String> orphans = new ArrayList<>();
        for (String deviceId : firmwareService.activeDeviceIds()) {
            if (!covered.contains(deviceId)) {
                orphans.add(deviceId);
            }
        }
        totalTimedOut += firmwareService.sweepTimeouts(orphans).size();
        return totalTimedOut;
    }

    /** 人工中止整批：在途设备叫停并回滚，待命/未出结论设备标记 ABORTED；成功设备与已通过波次原样保留。 */
    public boolean abort(String rolloutId, String reason) {
        RolloutPlan plan = require(rolloutId);
        synchronized (lock(rolloutId)) {
            if (isTerminal(plan)) {
                log.info("rollout={} action=abort outcome=REJECTED reason=already terminal status={}",
                        rolloutId, plan.getStatus());
                return false;
            }
            log.info("rollout={} action=abort begin status={} reason={}", rolloutId, plan.getStatus(), reason);
            // 叫停前先把已过 deadline 的在途设备按超时收口，避免“超时”被错算成“叫停”。
            settleDeadlines(plan);
            // 补扫超时可能恰好触发最后一台收口、自动判定为达标/不达标，此时批次已终态，尊重自动结论。
            if (isTerminal(plan)) {
                log.info("rollout={} action=abort outcome=SKIP reason=became terminal during pre-abort sweep status={}",
                        rolloutId, plan.getStatus());
                return false;
            }
            for (RolloutWave wave : plan.getWaves()) {
                switch (wave.getStatus()) {
                    case PASSED -> {
                        // 已达标波次结果原样保留，不覆盖、不抹掉。
                        log.info("rollout={} wave={} action=abort keep=PASSED devices={}",
                                rolloutId, wave.getIndex(), wave.getDeviceIds());
                    }
                    case IN_PROGRESS -> {
                        wave.setStatus(WaveStatus.CANCELLED);
                        wave.setDetail("cancelled by abort: " + reason);
                        stopWaveDevices(plan, wave, reason);
                    }
                    case PENDING, REJECTED, CANCELLED -> {
                        if (wave.getStatus() == WaveStatus.PENDING) {
                            wave.setStatus(WaveStatus.CANCELLED);
                            wave.setDetail("cancelled before dispatch: " + reason);
                            log.info("rollout={} wave={} action=abort outcome=CANCELLED standingBy={}",
                                    rolloutId, wave.getIndex(), wave.getDeviceIds().size());
                        }
                    }
                }
            }
            plan.setStatus(RolloutStatus.ABORTED);
            plan.setDetail("aborted: " + reason);
            log.info("rollout={} action=abort outcome=ABORTED reason={}", rolloutId, reason);
            return true;
        }
    }

    public Optional<RolloutPlan> plan(String rolloutId) {
        return Optional.ofNullable(rollouts.get(rolloutId));
    }

    /** 单台设备在灰度中的结论口径（互斥）。 */
    public RolloutDeviceOutcome deviceOutcome(String rolloutId, String deviceId) {
        RolloutPlan plan = require(rolloutId);
        synchronized (lock(rolloutId)) {
            return classify(plan, deviceId);
        }
    }

    /** 整批进度汇总：各口径计数之和=设备总数，与每台设备实际状态一一对应。 */
    public RolloutSummary summary(String rolloutId) {
        RolloutPlan plan = require(rolloutId);
        synchronized (lock(rolloutId)) {
            List<WaveSummary> waveSummaries = new ArrayList<>();
            int success = 0, failed = 0, timeout = 0, isolated = 0, aborted = 0, inProgress = 0, waiting = 0;
            for (RolloutWave wave : plan.getWaves()) {
                int wSuccess = 0, wFailed = 0, wTimeout = 0, wIsolated = 0, wAborted = 0,
                        wInProgress = 0, wWaiting = 0;
                for (String deviceId : wave.getDeviceIds()) {
                    switch (classify(plan, deviceId)) {
                        case SUCCESS -> wSuccess++;
                        case FAILED -> wFailed++;
                        case TIMEOUT -> wTimeout++;
                        case ISOLATED -> wIsolated++;
                        case ABORTED -> wAborted++;
                        case IN_PROGRESS -> wInProgress++;
                        case WAITING -> wWaiting++;
                    }
                }
                waveSummaries.add(new WaveSummary(wave.getIndex(), wave.getStatus(),
                        wave.getDeviceIds().size(), wSuccess, wFailed, wTimeout, wIsolated, wAborted,
                        wInProgress, wWaiting, wave.getMinSuccess()));
                success += wSuccess;
                failed += wFailed;
                timeout += wTimeout;
                isolated += wIsolated;
                aborted += wAborted;
                inProgress += wInProgress;
                waiting += wWaiting;
            }
            int total = success + failed + timeout + isolated + aborted + inProgress + waiting;
            return new RolloutSummary(rolloutId, plan.getTargetVersion(), plan.getStatus(), total,
                    success, failed, timeout, isolated, aborted, inProgress, waiting, waveSummaries);
        }
    }

    // ---- 内部实现（调用方必须已持有批次锁）----

    private void dispatchWave(RolloutPlan plan, int index) {
        RolloutWave wave = plan.getWaves().get(index);
        wave.setStatus(WaveStatus.IN_PROGRESS);
        wave.setDetail("dispatched");
        plan.setStatus(RolloutStatus.IN_PROGRESS);
        List<UpgradeTask> tasks = firmwareService.startBatch(
                plan.getRolloutId(), wave.getDeviceIds(), plan.getTargetVersion());
        long inProgress = tasks.stream().filter(t -> t.getStatus() == UpgradeStatus.IN_PROGRESS).count();
        long isolated = tasks.stream().filter(t -> t.getStatus() == UpgradeStatus.ISOLATED).count();
        log.info("rollout={} wave={} action=dispatch outcome=IN_PROGRESS devices={} inProgress={} isolated={}",
                plan.getRolloutId(), index, wave.getDeviceIds().size(), inProgress, isolated);
        // 极端情况下本波全部设备被前置隔离：没有在途任务，立即触发达标判定。
        if (inProgress == 0) {
            settleWave(plan, wave);
        }
    }

    /** 某台设备出结论后：若其所在波次已全部结束，则判定达标与否并推进/叫停。 */
    private void evaluateAfterDevice(RolloutPlan plan, String deviceId) {
        RolloutWave wave = waveOf(plan, deviceId);
        if (wave == null || wave.getStatus() != WaveStatus.IN_PROGRESS) {
            return;
        }
        for (String id : wave.getDeviceIds()) {
            if (classify(plan, id) == RolloutDeviceOutcome.IN_PROGRESS) {
                return;
            }
        }
        settleWave(plan, wave);
    }

    /** 波次收口：统计成功数，达标推进下一波/完成；不达标整批叫停。 */
    private void settleWave(RolloutPlan plan, RolloutWave wave) {
        int success = 0;
        for (String deviceId : wave.getDeviceIds()) {
            if (classify(plan, deviceId) == RolloutDeviceOutcome.SUCCESS) {
                success++;
            }
        }
        if (success >= wave.getMinSuccess()) {
            wave.setStatus(WaveStatus.PASSED);
            wave.setDetail("passed with " + success + "/" + wave.getDeviceIds().size()
                    + " success, threshold " + wave.getMinSuccess());
            log.info("rollout={} wave={} action=evaluate outcome=PASSED success={} threshold={} 判定依据: success>=minSuccess",
                    plan.getRolloutId(), wave.getIndex(), success, wave.getMinSuccess());
            int next = wave.getIndex() + 1;
            if (next < plan.getWaves().size()) {
                dispatchWave(plan, next);
            } else {
                plan.setStatus(RolloutStatus.COMPLETED);
                plan.setDetail("all " + plan.getWaves().size() + " waves passed");
                log.info("rollout={} action=evaluate outcome=COMPLETED waves={}",
                        plan.getRolloutId(), plan.getWaves().size());
            }
        } else {
            wave.setStatus(WaveStatus.REJECTED);
            wave.setDetail("rejected with " + success + "/" + wave.getDeviceIds().size()
                    + " success below threshold " + wave.getMinSuccess());
            log.info("rollout={} wave={} action=evaluate outcome=REJECTED success={} threshold={} 判定依据: success<minSuccess",
                    plan.getRolloutId(), wave.getIndex(), success, wave.getMinSuccess());
            haltAfterRejection(plan, wave);
        }
    }

    /** 不达标叫停：本波在途设备回滚，后续待命波次取消，已通过波次保留。 */
    private void haltAfterRejection(RolloutPlan plan, RolloutWave rejectedWave) {
        stopWaveDevices(plan, rejectedWave, "wave " + rejectedWave.getIndex() + " success below threshold");
        for (RolloutWave later : plan.getWaves()) {
            if (later.getIndex() > rejectedWave.getIndex() && later.getStatus() == WaveStatus.PENDING) {
                later.setStatus(WaveStatus.CANCELLED);
                later.setDetail("cancelled: previous wave rejected");
                log.info("rollout={} wave={} action=halt outcome=CANCELLED standingBy={} 后续波次不再下发",
                        plan.getRolloutId(), later.getIndex(), later.getDeviceIds().size());
            }
        }
        plan.setStatus(RolloutStatus.REJECTED);
        plan.setDetail("halted: wave " + rejectedWave.getIndex() + " below threshold");
        log.info("rollout={} action=halt outcome=REJECTED atWave={}",
                plan.getRolloutId(), rejectedWave.getIndex());
    }

    /** 补扫当前进行中波次的超时设备并逐台收口（判定/推进）；用于叫停前把超时口径算准。 */
    private void settleDeadlines(RolloutPlan plan) {
        List<UpgradeTask> timedOut = firmwareService.sweepTimeouts(inFlightDevices(plan));
        if (!timedOut.isEmpty()) {
            log.info("rollout={} action=pre-abort-sweep timedOut={} devices={}",
                    plan.getRolloutId(), timedOut.size(),
                    timedOut.stream().map(UpgradeTask::getDeviceId).toList());
        }
        for (UpgradeTask task : timedOut) {
            evaluateAfterDevice(plan, task.getDeviceId());
        }
    }

    /** 叫停一波中仍在途的设备（回滚）；已出结论设备原样保留。 */
    private void stopWaveDevices(RolloutPlan plan, RolloutWave wave, String reason) {        for (String deviceId : wave.getDeviceIds()) {
            boolean aborted = firmwareService.abortInFlight(deviceId, reason);
            log.info("rollout={} wave={} device={} action=stop aborted={}",
                    plan.getRolloutId(), wave.getIndex(), deviceId, aborted);
        }
    }

    /** 设备 -> 唯一结论口径。 */
    private RolloutDeviceOutcome classify(RolloutPlan plan, String deviceId) {
        RolloutWave wave = waveOf(plan, deviceId);
        if (wave == null) {
            throw new IllegalArgumentException(
                    "device " + deviceId + " not in rollout " + plan.getRolloutId());
        }
        Optional<UpgradeTask> taskOpt = firmwareService.taskFor(plan.getRolloutId(), deviceId);
        if (taskOpt.isPresent()) {
            UpgradeTask task = taskOpt.get();
            return switch (task.getStatus()) {
                case SUCCESS -> RolloutDeviceOutcome.SUCCESS;
                case FAILED -> RolloutDeviceOutcome.FAILED;
                case TIMEOUT -> RolloutDeviceOutcome.TIMEOUT;
                case ROLLED_BACK ->
                    // 设备自身失败/超时进入 ROLLED_BACK，按成因细分 FAILED/TIMEOUT。
                        task.getEndCause() == UpgradeEndCause.DEADLINE
                                ? RolloutDeviceOutcome.TIMEOUT : RolloutDeviceOutcome.FAILED;
                case ISOLATED -> RolloutDeviceOutcome.ISOLATED;
                case ABORTED -> RolloutDeviceOutcome.ABORTED;
                case PENDING, IN_PROGRESS -> RolloutDeviceOutcome.IN_PROGRESS;
            };
        }
        // 从未下发：已取消/已驳回的波次为叫停，其余为待命。
        if (wave.getStatus() == WaveStatus.PENDING || wave.getStatus() == WaveStatus.IN_PROGRESS) {
            return RolloutDeviceOutcome.WAITING;
        }
        return RolloutDeviceOutcome.ABORTED;
    }

    private RolloutWave waveOf(RolloutPlan plan, String deviceId) {
        for (RolloutWave wave : plan.getWaves()) {
            if (wave.getDeviceIds().contains(deviceId)) {
                return wave;
            }
        }
        return null;
    }

    private List<String> inFlightDevices(RolloutPlan plan) {
        List<String> ids = new ArrayList<>();
        for (RolloutWave wave : plan.getWaves()) {
            if (wave.getStatus() == WaveStatus.IN_PROGRESS) {
                ids.addAll(wave.getDeviceIds());
            }
        }
        return ids;
    }

    private boolean isTerminal(RolloutPlan plan) {
        return plan != null && (plan.getStatus() == RolloutStatus.COMPLETED
                || plan.getStatus() == RolloutStatus.REJECTED
                || plan.getStatus() == RolloutStatus.ABORTED);
    }

    private RolloutPlan require(String rolloutId) {
        RolloutPlan plan = rollouts.get(rolloutId);
        if (plan == null) {
            throw new IllegalArgumentException("rollout " + rolloutId + " not found");
        }
        return plan;
    }

    private Object lock(String rolloutId) {
        return rolloutLocks.get(rolloutId);
    }
}
