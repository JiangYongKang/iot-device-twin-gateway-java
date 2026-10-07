package com.github.highcumontoa.iotdevicetwingatewayjava.domain;

import java.util.List;

/** 单个波次配置：本波设备清单 + 本波通过条件（至少成功台数）。 */
public record WaveSpec(List<String> deviceIds, int requiredSuccesses) {
}
