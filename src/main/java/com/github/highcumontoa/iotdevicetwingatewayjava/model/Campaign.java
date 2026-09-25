package com.github.highcumontoa.iotdevicetwingatewayjava.model;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 一批次固件升级。 */
public class Campaign {
    private final String campaignId;
    private final String targetVersion;
    private final List<UpgradeTask> tasks = new CopyOnWriteArrayList<>();
    private final long createdAt;

    public Campaign(String campaignId, String targetVersion, long createdAt) {
        this.campaignId = campaignId;
        this.targetVersion = targetVersion;
        this.createdAt = createdAt;
    }

    public String getCampaignId() { return campaignId; }
    public String getTargetVersion() { return targetVersion; }
    public List<UpgradeTask> getTasks() { return tasks; }
    public long getCreatedAt() { return createdAt; }
}
