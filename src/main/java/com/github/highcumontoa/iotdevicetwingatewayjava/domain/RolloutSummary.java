package com.github.highcumontoa.iotdevicetwingatewayjava.domain;

import java.util.List;

/**
 * 灰度进度汇总。计数口径互斥（每台设备恰好计入一个桶）：
 * success 成功 / failed 设备自报失败后回滚 / timeout 超时回滚 /
 * isolated 前置条件不满足被隔离 / aborted 批次中途叫停并回滚 /
 * inProgress 进行中 / waiting 待命波次中的设备。
 */
public record RolloutSummary(String batchId, RolloutStatus status, int currentWave, String haltReason,
                             int total, int success, int failed, int timeout,
                             int isolated, int aborted, int inProgress, int waiting,
                             List<WaveCount> waves) {

    /** 单个波次的互斥计数。 */
    public record WaveCount(int waveIndex, WaveStatus status, int total, int success, int failed, int timeout,
                            int isolated, int aborted, int inProgress, int waiting) {
    }
}
