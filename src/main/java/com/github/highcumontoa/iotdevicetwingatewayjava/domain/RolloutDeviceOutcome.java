package com.github.highcumontoa.iotdevicetwingatewayjava.domain;

/** 灰度发布中每台设备的汇总口径结论（互斥、可与设备状态一一对应）。 */
public enum RolloutDeviceOutcome {
    /** 升级成功（保持目标版本）。 */
    SUCCESS,
    /** 设备自己上报失败，已回滚。 */
    FAILED,
    /** 超时未确认，已回滚。 */
    TIMEOUT,
    /** 前置条件不满足被隔离，从未下发。 */
    ISOLATED,
    /** 批次中途叫停（在途回滚或待命取消），区别于设备失败与隔离。 */
    ABORTED,
    /** 本波进行中，尚未有结论。 */
    IN_PROGRESS,
    /** 波次未轮到，设备待命中。 */
    WAITING
}
