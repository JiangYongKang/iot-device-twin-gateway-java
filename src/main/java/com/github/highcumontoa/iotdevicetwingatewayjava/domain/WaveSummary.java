package com.github.highcumontoa.iotdevicetwingatewayjava.domain;

/** 单个波次的进度汇总（各口径互斥，计数之和=本波设备数）。 */
public record WaveSummary(
        int index,
        WaveStatus status,
        int total,
        int success,
        int failed,
        int timeout,
        int isolated,
        int aborted,
        int inProgress,
        int waiting,
        int minSuccess) {
}
