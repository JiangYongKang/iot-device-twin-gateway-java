package com.github.highcumontoa.iotdevicetwingatewayjava.domain;

/** 波次状态机：WAITING 待命 -> RUNNING 进行中 -> PASSED 达标 / FAILED 未达标 / ABORTED 整批中止。 */
public enum WaveStatus {
    /** 待命：前序波次尚未达标，本波设备绝不提前下发。 */
    WAITING,
    /** 进行中：本波设备已下发，等待结果。 */
    RUNNING,
    /** 达标：成功台数达到本波通过条件，允许推进下一波。 */
    PASSED,
    /** 未达标：本波结束时成功台数不足，整批停止。 */
    FAILED,
    /** 中止：本波进行中时整批被人工叫停。 */
    ABORTED
}
