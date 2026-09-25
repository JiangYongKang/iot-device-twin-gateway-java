package com.github.highcumontoa.iotdevicetwingatewayjava.domain;

/** 影子/心跳更新的可区分处理结论。 */
public enum UpdateOutcome {
    /** 已接受并推进已确认状态。 */
    APPLIED,
    /** 完全重复的上报，幂等忽略。 */
    DUPLICATE,
    /** 时间戳或版本乱序/过旧，拒绝以免状态回退。 */
    STALE,
    /** 设备不存在等硬性拒绝。 */
    REJECTED
}
