package com.github.highcumontoa.iotdevicetwingatewayjava.domain;

import java.util.ArrayList;
import java.util.List;

/** 运行时灰度批次：目标版本、波次列表与可变批次状态。 */
public class RolloutPlan {
    private final String rolloutId;
    private final String targetVersion;
    private final List<RolloutWave> waves = new ArrayList<>();
    private RolloutStatus status = RolloutStatus.PENDING;
    private String detail = "created";

    public RolloutPlan(String rolloutId, String targetVersion) {
        this.rolloutId = rolloutId;
        this.targetVersion = targetVersion;
    }

    public String getRolloutId() { return rolloutId; }
    public String getTargetVersion() { return targetVersion; }
    public List<RolloutWave> getWaves() { return waves; }
    public RolloutStatus getStatus() { return status; }
    public void setStatus(RolloutStatus status) { this.status = status; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
