package com.github.highcumontoa.iotdevicetwingatewayjava.domain;

import java.util.List;

/** 整批灰度的进度汇总；totals 为各波之和，与各设备实际状态一致。 */
public record RolloutSummary(
        String rolloutId,
        String targetVersion,
        RolloutStatus status,
        int total,
        int success,
        int failed,
        int timeout,
        int isolated,
        int aborted,
        int inProgress,
        int waiting,
        List<WaveSummary> waves) {
}
