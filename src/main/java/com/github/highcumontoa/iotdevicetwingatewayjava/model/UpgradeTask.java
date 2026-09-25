package com.github.highcumontoa.iotdevicetwingatewayjava.model;

/** 单设备升级任务。 */
public class UpgradeTask {
    private final String taskId;
    private final String campaignId;
    private final String deviceId;
    private final String targetVersion;
    private final String previousVersion;
    private volatile UpgradeState state = UpgradeState.PENDING;
    private volatile long stateChangedAt;
    private volatile String reason = "";

    public UpgradeTask(String taskId, String campaignId, String deviceId,
                       String targetVersion, String previousVersion, long createdAt) {
        this.taskId = taskId;
        this.campaignId = campaignId;
        this.deviceId = deviceId;
        this.targetVersion = targetVersion;
        this.previousVersion = previousVersion;
        this.stateChangedAt = createdAt;
    }

    public String getTaskId() { return taskId; }
    public String getCampaignId() { return campaignId; }
    public String getDeviceId() { return deviceId; }
    public String getTargetVersion() { return targetVersion; }
    public String getPreviousVersion() { return previousVersion; }
    public UpgradeState getState() { return state; }
    public long getStateChangedAt() { return stateChangedAt; }
    public String getReason() { return reason; }

    public void transition(UpgradeState next, long at, String why) {
        this.state = next;
        this.stateChangedAt = at;
        this.reason = why;
    }
}
