package com.github.highcumontoa.iotdevicetwingatewayjava.model;

/** 影子更新结论，冲突场景必须可区分。 */
public enum ShadowUpdateOutcome {
    /** 新数据，已应用。 */
    APPLIED,
    /** 重复上报（同版本同内容），幂等忽略。 */
    DUPLICATE_IGNORED,
    /** 过期/乱序数据（版本或时间戳回退），拒绝。 */
    STALE_REJECTED,
    /** 同版本但内容冲突，拒绝并保留已确认状态。 */
    CONFLICT_REJECTED
}
