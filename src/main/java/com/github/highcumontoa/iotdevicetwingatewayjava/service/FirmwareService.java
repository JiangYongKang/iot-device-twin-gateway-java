package com.github.highcumontoa.iotdevicetwingatewayjava.service;

import com.github.highcumontoa.iotdevicetwingatewayjava.config.TimeSource;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.Campaign;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.Device;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.UpgradeState;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.UpgradeTask;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 固件分批升级服务。
 * 状态机：PENDING → IN_PROGRESS → SUCCEEDED / FAILED / TIMED_OUT；
 * FAILED 与 TIMED_OUT 自动回滚到升级前版本；SUCCEEDED 可显式回滚为 ROLLED_BACK。
 * 同一设备同一时刻只允许一个进行中的升级任务（并发请求只生效一次）；
 * 版本或前置条件不满足的设备被隔离（QUARANTINED），不影响同批其他设备。
 */
@Service
public class FirmwareService {

    /** 升级超时时间（毫秒）。 */
    public static final long UPGRADE_TIMEOUT_MILLIS = 30_000L;

    private final DeviceRegistry registry;
    private final TimeSource timeSource;

    private final Map<String, UpgradeTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, Campaign> campaigns = new ConcurrentHashMap<>();
    /** deviceId → 进行中 taskId，保证同一设备并发升级只生效一次。 */
    private final Map<String, String> activeTaskByDevice = new ConcurrentHashMap<>();
    private final AtomicInteger taskSeq = new AtomicInteger();

    public FirmwareService(DeviceRegistry registry, TimeSource timeSource) {
        this.registry = registry;
        this.timeSource = timeSource;
    }

    public Campaign startCampaign(List<String> deviceIds, String targetVersion) {
        long now = timeSource.now();
        Campaign campaign = new Campaign(UUID.randomUUID().toString(), targetVersion, now);
        campaigns.put(campaign.getCampaignId(), campaign);
        for (String deviceId : deviceIds) {
            campaign.getTasks().add(createTask(campaign, deviceId, targetVersion, now));
        }
        return campaign;
    }

    private UpgradeTask createTask(Campaign campaign, String deviceId,
                                   String targetVersion, long now) {
        String taskId = campaign.getCampaignId() + "-" + taskSeq.incrementAndGet();
        Device device = registry.find(deviceId).orElse(null);
        if (device == null) {
            UpgradeTask task = new UpgradeTask(taskId, campaign.getCampaignId(), deviceId,
                    targetVersion, null, now);
            task.transition(UpgradeState.REJECTED_PRECONDITION, now,
                    "unknown device: " + deviceId);
            tasks.put(taskId, task);
            return task;
        }
        String previous = device.getFirmwareVersion();
        UpgradeTask task = new UpgradeTask(taskId, campaign.getCampaignId(), deviceId,
                targetVersion, previous, now);
        tasks.put(taskId, task);
        if (previous != null && previous.equals(targetVersion)) {
            task.transition(UpgradeState.REJECTED_PRECONDITION, now,
                    "already at target version " + targetVersion);
            registry.quarantine(deviceId, "precondition failed: already at target version");
            return task;
        }
        // 同一设备并发升级只生效一次：占不到槽位的请求被拒绝并隔离。
        if (activeTaskByDevice.putIfAbsent(deviceId, taskId) != null) {
            task.transition(UpgradeState.REJECTED_PRECONDITION, now,
                    "another upgrade already in progress for device " + deviceId);
            registry.quarantine(deviceId, "concurrent upgrade rejected");
            return task;
        }
        return task;
    }

    public boolean markInProgress(String taskId) {
        UpgradeTask task = tasks.get(taskId);
        if (task == null) {
            return false;
        }
        synchronized (task) {
            if (task.getState() != UpgradeState.PENDING) {
                return false;
            }
            task.transition(UpgradeState.IN_PROGRESS, timeSource.now(), "download started");
            return true;
        }
    }

    public boolean markSucceeded(String taskId) {
        UpgradeTask task = tasks.get(taskId);
        if (task == null) {
            return false;
        }
        synchronized (task) {
            if (task.getState() != UpgradeState.IN_PROGRESS
                    && task.getState() != UpgradeState.PENDING) {
                return false;
            }
            task.transition(UpgradeState.SUCCEEDED, timeSource.now(),
                    "upgraded to " + task.getTargetVersion());
            registry.updateFirmwareVersion(task.getDeviceId(), task.getTargetVersion());
            releaseSlot(task);
            return true;
        }
    }

    /** 升级失败：状态置 FAILED，设备固件回滚到升级前版本。 */
    public boolean markFailed(String taskId, String reason) {
        UpgradeTask task = tasks.get(taskId);
        if (task == null) {
            return false;
        }
        synchronized (task) {
            if (task.getState() != UpgradeState.IN_PROGRESS
                    && task.getState() != UpgradeState.PENDING) {
                return false;
            }
            task.transition(UpgradeState.FAILED, timeSource.now(),
                    "failed: " + reason + "; rolled back to " + task.getPreviousVersion());
            registry.updateFirmwareVersion(task.getDeviceId(), task.getPreviousVersion());
            releaseSlot(task);
            return true;
        }
    }

    /** 显式回滚已成功升级的设备。 */
    public boolean rollback(String taskId) {
        UpgradeTask task = tasks.get(taskId);
        if (task == null) {
            return false;
        }
        synchronized (task) {
            if (task.getState() != UpgradeState.SUCCEEDED) {
                return false;
            }
            task.transition(UpgradeState.ROLLED_BACK, timeSource.now(),
                    "rolled back to " + task.getPreviousVersion());
            registry.updateFirmwareVersion(task.getDeviceId(), task.getPreviousVersion());
            return true;
        }
    }

    /** 扫描超时任务：置 TIMED_OUT 并回滚。返回本次处理的任务数。 */
    public int sweepTimeouts() {
        long now = timeSource.now();
        int swept = 0;
        for (UpgradeTask task : tasks.values()) {
            synchronized (task) {
                UpgradeState state = task.getState();
                if ((state == UpgradeState.PENDING || state == UpgradeState.IN_PROGRESS)
                        && now - task.getStateChangedAt() > UPGRADE_TIMEOUT_MILLIS) {
                    task.transition(UpgradeState.TIMED_OUT, now,
                            "timed out after " + UPGRADE_TIMEOUT_MILLIS
                                    + "ms; rolled back to " + task.getPreviousVersion());
                    registry.updateFirmwareVersion(task.getDeviceId(), task.getPreviousVersion());
                    releaseSlot(task);
                    swept++;
                }
            }
        }
        return swept;
    }

    private void releaseSlot(UpgradeTask task) {
        activeTaskByDevice.remove(task.getDeviceId(), task.getTaskId());
    }

    public Optional<UpgradeTask> findTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    public Optional<Campaign> findCampaign(String campaignId) {
        return Optional.ofNullable(campaigns.get(campaignId));
    }
}
