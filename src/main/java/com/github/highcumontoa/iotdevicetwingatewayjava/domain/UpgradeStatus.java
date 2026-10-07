package com.github.highcumontoa.iotdevicetwingatewayjava.domain;

/** 固件升级状态机。 */
public enum UpgradeStatus {
    /** 已创建，等待下发。 */
    PENDING,
    /** 升级进行中。 */
    IN_PROGRESS,
    /** 升级成功，设备已确认新版本。 */
    SUCCESS,
    /** 设备上报失败，等待回滚。 */
    FAILED,
    /** 超时未确认，等待回滚。 */
    TIMEOUT,
    /** 已回滚到升级前版本（诱因见 {@link UpgradeTask#getRollbackCause()}）。 */
    ROLLED_BACK,
    /** 版本或前置条件不满足，设备被隔离（不影响同批其他设备）。 */
    ISOLATED,
    /** 灰度批次中途叫停：设备自身既未失败也未被隔离，由批次判定/人工中止并回滚。 */
    ABORTED
}
