package com.github.highcumontoa.iotdevicetwingatewayjava.model;

/** 固件升级任务状态机。 */
public enum UpgradeState {
    PENDING,
    IN_PROGRESS,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    ROLLED_BACK,
    /** 版本或前置条件不满足，被隔离。 */
    REJECTED_PRECONDITION
}
