package com.github.highcumontoa.iotdevicetwingatewayjava.model;

import java.util.Map;

/** 一条遥测数据。messageId 用于去重，timestamp 为设备侧采集时间。 */
public record TelemetryPoint(String messageId, long timestamp, Map<String, Object> metrics) {
}
