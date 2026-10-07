package com.github.highcumontoa.iotdevicetwingatewayjava.domain;

/** 灰度批次整体状态机。 */
public enum RolloutStatus {
    /** 已创建，第一波尚未下发。 */
    CREATED,
    /** 波次推进中。 */
    IN_PROGRESS,
    /** 所有波次均达标完成。 */
    COMPLETED,
    /** 某波未达标，自动停止，不再下推。 */
    HALTED,
    /** 人工中止，不再下推。 */
    ABORTED
}
