package com.github.highcumontoa.iotdevicetwingatewayjava.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
            task.setDetail("device reported failure: " + reason);
            log.info("device={} task={} action=fail outcome=FAILED reason={}", deviceId, task.getTaskId(), reason);
            rollback(deviceId, task);
            return true;
        }
    }

    /** 扫描超时任务并回滚。 */
    public List<UpgradeTask> sweepTimeouts() {
        List<UpgradeTask> timedOut = new ArrayList<>();
        Instant now = clock.now();
        for (Map.Entry<String, UpgradeTask> entry : activeByDevice.entrySet()) {
            String deviceId = entry.getKey();
            synchronized (deviceService.lockOf(deviceId)) {
                UpgradeTask task = entry.getValue();
                if (task.getStatus() == UpgradeStatus.IN_PROGRESS && now.isAfter(task.getDeadline())) {
                    task.setStatus(UpgradeStatus.TIMEOUT);
                    task.setDetail("no confirmation before deadline " + task.getDeadline());
                    log.info("device={} task={} action=sweep outcome=TIMEOUT now={} deadline={}",
                            deviceId, task.getTaskId(), now, task.getDeadline());
                    rollback(deviceId, task);
                    timedOut.add(task);
                }
            }
        }
        return timedOut;
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
