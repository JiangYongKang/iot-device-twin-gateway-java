package com.github.highcumontoa.iotdevicetwingatewayjava.domain;

import java.util.List;

/** 灰度发布批次：目标版本 + 有序波次 + 停止原因等运行态。 */
public class RolloutBatch {
    private final String batchId;
    private final String targetVersion;
    private final List<RolloutWave> waves;
    private RolloutStatus status = RolloutStatus.CREATED;
    private int currentWave = -1;
    private String haltReason;

    public RolloutBatch(String batchId, String targetVersion, List<RolloutWave> waves) {
        this.batchId = batchId;
        this.targetVersion = targetVersion;
        this.waves = waves;
    }

    public String getBatchId() { return batchId; }
    public String getTargetVersion() { return targetVersion; }
    public List<RolloutWave> getWaves() { return waves; }
    public RolloutStatus getStatus() { return status; }
    public void setStatus(RolloutStatus status) { this.status = status; }
    public int getCurrentWave() { return currentWave; }
    public void setCurrentWave(int currentWave) { this.currentWave = currentWave; }
    public String getHaltReason() { return haltReason; }
    public void setHaltReason(String haltReason) { this.haltReason = haltReason; }
}
