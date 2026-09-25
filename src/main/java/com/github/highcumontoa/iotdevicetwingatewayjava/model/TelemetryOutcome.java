package com.github.highcumontoa.iotdevicetwingatewayjava.model;

/** 遥测接收结论。 */
public enum TelemetryOutcome {
    /** 已接收并计量。 */
    ACCEPTED,
    /** 设备离线，已按序暂存。 */
    BUFFERED,
    /** 重复 messageId，幂等忽略，不重复计量。 */
    DUPLICATE_IGNORED,
    /** 离线缓冲已满，明确拒绝。 */
    REJECTED_BUFFER_FULL,
    /** 超过速率限制，明确拒绝。 */
    REJECTED_RATE_LIMITED,
    /** 设备未注册。 */
    REJECTED_UNKNOWN_DEVICE
}
