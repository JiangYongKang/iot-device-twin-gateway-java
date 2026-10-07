package com.github.highcumontoa.iotdevicetwingatewayjava.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.github.highcumontoa.iotdevicetwingatewayjava.domain.Device;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.DeviceState;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.UpgradeStatus;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.UpgradeEndCause;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.UpgradeTask;
import com.github.highcumontoa.iotdevicetwingatewayjava.support.MutableClock;

/**
 * 固件分批升级与回滚服务。
 *
 * 状态机：PENDING -> IN_PROGRESS -> SUCCESS | FAILED -> ROLLED_BACK | TIMEOUT -> ROLLED_BACK。
 * 前置条件不满足（设备不存在、离线、已在目标版本、已有进行中任务）的设备被隔离为 ISOLATED，
 * 不影响同批其他设备。同一设备的并发升级请求通过设备锁 + activeTask 检查只生效一次。
 */
@Service
public class FirmwareService {

    private static final Logger log = LoggerFactory.getLogger(FirmwareService.class);

    private final DeviceService deviceService;
    private final MutableClock clock;
    private final Duration upgradeTimeout;

    private final Map<String, UpgradeTask> activeByDevice = new ConcurrentHashMap<>();
    private final List<UpgradeTask> history = new ArrayList<>();
    private final AtomicLong taskSeq = new AtomicLong();

    @org.springframework.beans.factory.annotation.Autowired
    public FirmwareService(DeviceService deviceService, MutableClock clock) {
        this(deviceService, clock, Duration.ofSeconds(30));
    }

    public FirmwareService(DeviceService deviceService, MutableClock clock, Duration upgradeTimeout) {
        this.deviceService = deviceService;
        this.clock = clock;
        this.upgradeTimeout = upgradeTimeout;
    }

    /** 发起一批升级；不满足前置条件的设备被隔离，返回每台设备的任务（含被隔离的）。 */
    public List<UpgradeTask> startBatch(String batchId, List<String> deviceIds, String targetVersion) {
        List<UpgradeTask> tasks = new ArrayList<>();
        for (String deviceId : deviceIds) {
            tasks.add(startOne(batchId, deviceId, targetVersion));
        }
        return tasks;
    }

    private UpgradeTask startOne(String batchId, String deviceId, String targetVersion) {
        synchronized (deviceService.lockOf(deviceId)) {
            Optional<Device> deviceOpt = deviceService.find(deviceId);
            UpgradeTask existing = activeByDevice.get(deviceId);
            if (existing != null && isActive(existing)) {
                log.info("device={} batch={} action=upgrade outcome=ISOLATED reason=upgrade already in progress task={}",
                        deviceId, batchId, existing.getTaskId());
                return isolate(batchId, deviceId, targetVersion,
                        deviceOpt.map(Device::getFirmwareVersion).orElse("unknown"),
                        "concurrent upgrade rejected, active task " + existing.getTaskId());
            }
            if (deviceOpt.isEmpty()) {
                log.info("device={} batch={} action=upgrade outcome=ISOLATED reason=unknown device", deviceId, batchId);
                return isolate(batchId, deviceId, targetVersion, "unknown", "device not registered");
            }
            Device device = deviceOpt.get();
            if (device.getState() != DeviceState.ONLINE) {
                log.info("device={} batch={} action=upgrade outcome=ISOLATED reason=device offline", deviceId, batchId);
                return isolate(batchId, deviceId, targetVersion, device.getFirmwareVersion(), "device offline");
            }
            if (device.getFirmwareVersion().equals(targetVersion)) {
                log.info("device={} batch={} action=upgrade outcome=ISOLATED reason=already at target {}", 
                        deviceId, batchId, targetVersion);
                return isolate(batchId, deviceId, targetVersion, device.getFirmwareVersion(),
                        "already at target version");
            }
            UpgradeTask task = new UpgradeTask("task-" + taskSeq.incrementAndGet(), batchId, deviceId,
                    targetVersion, device.getFirmwareVersion());
            task.setStatus(UpgradeStatus.IN_PROGRESS);
            task.setDeadline(clock.now().plus(upgradeTimeout));
            task.setDetail("upgrade started");
            activeByDevice.put(deviceId, task);
            synchronized (history) {
                history.add(task);
            }
            log.info("device={} batch={} task={} action=upgrade outcome=IN_PROGRESS from={} to={} deadline={}",
                    deviceId, batchId, task.getTaskId(), task.getFromVersion(), targetVersion, task.getDeadline());
            return task;
        }
    }

    /** 设备确认升级成功。返回是否被接受（仅进行中的任务可确认）。 */
    public boolean confirmSuccess(String deviceId, String version) {
        synchronized (deviceService.lockOf(deviceId)) {
            UpgradeTask task = activeByDevice.get(deviceId);
            if (task == null || task.getStatus() != UpgradeStatus.IN_PROGRESS) {
                log.info("device={} action=confirm outcome=REJECTED reason=no in-progress task", deviceId);
                return false;
            }
            if (!task.getTargetVersion().equals(version)) {
                log.info("device={} task={} action=confirm outcome=REJECTED reason=version mismatch reported={} expected={}",
                        deviceId, task.getTaskId(), version, task.getTargetVersion());
                return false;
            }
            task.setStatus(UpgradeStatus.SUCCESS);
            task.setEndCause(UpgradeEndCause.DEVICE_CONFIRMED);
            task.setDetail("device confirmed " + version);
            deviceService.find(deviceId).ifPresent(d -> d.setFirmwareVersion(version));
            activeByDevice.remove(deviceId);
            log.info("device={} task={} action=confirm outcome=SUCCESS version={}", deviceId, task.getTaskId(), version);
            return true;
        }
    }

    /** 设备上报升级失败，触发回滚到升级前版本。 */
    public boolean reportFailure(String deviceId, String reason) {
        synchronized (deviceService.lockOf(deviceId)) {
            UpgradeTask task = activeByDevice.get(deviceId);
            if (task == null || task.getStatus() != UpgradeStatus.IN_PROGRESS) {
                log.info("device={} action=fail outcome=REJECTED reason=no in-progress task", deviceId);
                return false;
            }
            task.setStatus(UpgradeStatus.FAILED);
            task.setEndCause(UpgradeEndCause.DEVICE_REPORTED_FAILURE);
            task.setDetail("device reported failure: " + reason);
            log.info("device={} task={} action=fail outcome=FAILED reason={}", deviceId, task.getTaskId(), reason);
            rollback(deviceId, task);
            return true;
        }
    }

    /** 扫描超时任务并回滚。 */
    public List<UpgradeTask> sweepTimeouts() {
        return sweepTimeouts(activeByDevice.keySet());
    }

    /** 只扫描给定设备范围内的超时任务并回滚（灰度推进时按批次精确扫描）。 */
    public List<UpgradeTask> sweepTimeouts(Collection<String> deviceIds) {
        List<UpgradeTask> timedOut = new ArrayList<>();
        Instant now = clock.now();
        for (String deviceId : deviceIds) {
            UpgradeTask task = activeByDevice.get(deviceId);
            if (task == null) {
                continue;
            }
            synchronized (deviceService.lockOf(deviceId)) {
                UpgradeTask current = activeByDevice.get(deviceId);
                if (current != null && current == task
                        && current.getStatus() == UpgradeStatus.IN_PROGRESS && now.isAfter(current.getDeadline())) {
                    current.setStatus(UpgradeStatus.TIMEOUT);
                    current.setEndCause(UpgradeEndCause.DEADLINE);
                    current.setDetail("no confirmation before deadline " + current.getDeadline());
                    log.info("device={} task={} action=sweep outcome=TIMEOUT now={} deadline={}",
                            deviceId, current.getTaskId(), now, current.getDeadline());
                    rollback(deviceId, current);
                    timedOut.add(current);
                }
            }
        }
        return timedOut;
    }

    /**
     * 叫停一台仍在途的升级任务：状态置为 ABORTED 并回滚到升级前版本。
     * 仅进行中任务可叫停；已成功/失败/隔离的设备原样保留。返回是否执行了叫停。
     */
    public boolean abortInFlight(String deviceId, String reason) {
        synchronized (deviceService.lockOf(deviceId)) {
            UpgradeTask task = activeByDevice.get(deviceId);
            if (task == null || task.getStatus() != UpgradeStatus.IN_PROGRESS) {
                return false;
            }
            task.setStatus(UpgradeStatus.ABORTED);
            task.setEndCause(UpgradeEndCause.ABORTED);
            task.setDetail("rollout aborted: " + reason);
            log.info("device={} task={} action=abort outcome=ABORTED reason={}",
                    deviceId, task.getTaskId(), reason);
            rollbackAfterAbort(deviceId, task);
            return true;
        }
    }

    /** 查询某设备在指定批次中的最新任务（无任务返回空，待命设备即无任务）。 */
    public Optional<UpgradeTask> taskFor(String batchId, String deviceId) {
        synchronized (history) {
            UpgradeTask found = null;
            for (UpgradeTask task : history) {
                if (task.getBatchId().equals(batchId) && task.getDeviceId().equals(deviceId)) {
                    found = task;
                }
            }
            return Optional.ofNullable(found);
        }
    }

    /** 当前仍有在途任务的设备快照（任务终态即从 activeByDevice 移除）。 */
    public java.util.Set<String> activeDeviceIds() {
        return new java.util.HashSet<>(activeByDevice.keySet());
    }

    public Optional<UpgradeTask> taskOf(String deviceId) {
        return Optional.ofNullable(activeByDevice.get(deviceId));
    }

    public List<UpgradeTask> history() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    private void rollback(String deviceId, UpgradeTask task) {
        deviceService.find(deviceId).ifPresent(d -> d.setFirmwareVersion(task.getFromVersion()));
        task.setStatus(UpgradeStatus.ROLLED_BACK);
        task.setDetail(task.getDetail() + " -> rolled back to " + task.getFromVersion());
        activeByDevice.remove(deviceId);
        log.info("device={} task={} action=rollback outcome=ROLLED_BACK restored={}",
                deviceId, task.getTaskId(), task.getFromVersion());
    }

    /** 叫停回滚：固件恢复到升级前版本，但任务结论保留为 ABORTED（与设备失败的 ROLLED_BACK 区分）。 */
    private void rollbackAfterAbort(String deviceId, UpgradeTask task) {
        deviceService.find(deviceId).ifPresent(d -> d.setFirmwareVersion(task.getFromVersion()));
        task.setDetail(task.getDetail() + " -> rolled back to " + task.getFromVersion());
        activeByDevice.remove(deviceId);
        log.info("device={} task={} action=rollback outcome=ABORTED restored={}",
                deviceId, task.getTaskId(), task.getFromVersion());
    }

    private UpgradeTask isolate(String batchId, String deviceId, String targetVersion, String fromVersion, String reason) {
        UpgradeTask task = new UpgradeTask("task-" + taskSeq.incrementAndGet(), batchId, deviceId,
                targetVersion, fromVersion);
        task.setStatus(UpgradeStatus.ISOLATED);
        task.setDetail(reason);
        synchronized (history) {
            history.add(task);
        }
        return task;
    }

    private boolean isActive(UpgradeTask task) {
        return task.getStatus() == UpgradeStatus.PENDING || task.getStatus() == UpgradeStatus.IN_PROGRESS;
    }
}
