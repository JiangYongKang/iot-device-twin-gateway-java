package com.github.highcumontoa.iotdevicetwingatewayjava.domain;

/** 灰度批次状态。 */
public enum RolloutStatus {
    /** 已创建，第一波尚未开始。 */
    PENDING,
    /** 推进中。 */
    IN_PROGRESS,
    /** 所有波次达标完成。 */
    COMPLETED,
    /** 某波未达标，自动叫停。 */
    REJECTED,
    /** 被人工中止。 */
    ABORTED
}
