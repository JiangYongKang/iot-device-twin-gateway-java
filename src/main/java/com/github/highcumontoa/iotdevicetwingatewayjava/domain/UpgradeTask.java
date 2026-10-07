package com.github.highcumontoa.iotdevicetwingatewayjava.domain;

import java.time.Instant;

/** 单设备升级任务，记录状态机迁移轨迹。 */
public class UpgradeTask {
    private final String taskId;
    private final String batchId;
    private final String deviceId;
    private final String targetVersion;
    private final String fromVersion;
    private UpgradeStatus status = UpgradeStatus.PENDING;
    private UpgradeEndCause endCause;
    private Instant deadline;
    private String detail = "created";

    public UpgradeTask(String taskId, String batchId, String deviceId, String targetVersion, String fromVersion) {
        this.taskId = taskId;
        this.batchId = batchId;
        this.deviceId = deviceId;
        this.targetVersion = targetVersion;
        this.fromVersion = fromVersion;
    }

    public String getTaskId() { return taskId; }
    public String getBatchId() { return batchId; }
    public String getDeviceId() { return deviceId; }
    public String getTargetVersion() { return targetVersion; }
    public String getFromVersion() { return fromVersion; }
    public UpgradeStatus getStatus() { return status; }
    public void setStatus(UpgradeStatus status) { this.status = status; }
    public UpgradeEndCause getEndCause() { return endCause; }
    public void setEndCause(UpgradeEndCause endCause) { this.endCause = endCause; }
    public Instant getDeadline() { return deadline; }
    public void setDeadline(Instant deadline) { this.deadline = deadline; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
