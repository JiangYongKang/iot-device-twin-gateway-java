package com.github.highcumontoa.iotdevicetwingatewayjava.domain;

/** 升级任务终态成因，用于区分 ROLLED_BACK / ABORTED 是怎么来的。 */
public enum UpgradeEndCause {
    /** 设备确认成功。 */
    DEVICE_CONFIRMED,
    /** 设备自己上报失败。 */
    DEVICE_REPORTED_FAILURE,
    /** 超过截止时间未确认。 */
    DEADLINE,
    /** 批次中途叫停。 */
    ABORTED
}
