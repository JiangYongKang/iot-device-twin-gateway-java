package com.github.highcumontoa.iotdevicetwingatewayjava.domain;

import java.util.List;

/** 波次编排：本波设备与通过条件。 */
public record WaveSpec(List<String> deviceIds, int minSuccess) {
}
