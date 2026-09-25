package com.github.highcumontoa.iotdevicetwingatewayjava.domain;

import java.time.Instant;

/** 一次影子/心跳更新的判定结果，携带判定依据。 */
public record ShadowUpdateResult(UpdateOutcome outcome, long appliedVersion, Instant appliedAt, String reason) {
    public static ShadowUpdateResult of(UpdateOutcome outcome, long version, Instant at, String reason) {
        return new ShadowUpdateResult(outcome, version, at, reason);
    }
}
