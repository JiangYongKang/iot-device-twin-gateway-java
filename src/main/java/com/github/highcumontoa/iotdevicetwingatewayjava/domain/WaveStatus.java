package com.github.highcumontoa.iotdevicetwingatewayjava.domain;

/** 灰度波次状态。 */
public enum WaveStatus {
    /** 已编排，尚未轮到（设备待命，不得下发）。 */
    PENDING,
    /** 进行中：本波设备已下发，等待结果。 */
    IN_PROGRESS,
    /** 达标通过。 */
    PASSED,
    /** 未达标：整批在此叫停，后续波次不再推进。 */
    REJECTED,
    /** 被人工中止（本波在途设备叫停回滚，待命波次取消）。 */
    CANCELLED
}
