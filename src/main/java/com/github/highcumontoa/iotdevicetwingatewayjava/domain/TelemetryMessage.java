package com.github.highcumontoa.iotdevicetwingatewayjava.domain;

import java.time.Instant;
import java.util.Map;

/** 一条遥测消息。messageId 用于幂等去重，seq 用于离线期间按序暂存。 */
public record TelemetryMessage(String messageId, String deviceId, long seq, Instant occurredAt,
                               Map<String, Object> payload) {
}
