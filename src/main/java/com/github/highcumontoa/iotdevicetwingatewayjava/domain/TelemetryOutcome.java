package com.github.highcumontoa.iotdevicetwingatewayjava.domain;

/** 遥测接收结论，拒绝原因可区分。 */
public enum TelemetryOutcome {
    /** 在线直接接收并计量。 */
    ACCEPTED,
    /** 设备离线，已按序暂存待补传。 */
    BUFFERED,
    /** 重复 messageId，幂等忽略，不重复计量。 */
    DUPLICATE,
    /** 离线缓冲已满，明确拒绝。 */
    REJECTED_CAPACITY,
    /** 超过速率限制，明确拒绝。 */
    REJECTED_RATE,
    /** 设备不存在。 */
    REJECTED_UNKNOWN_DEVICE
}
