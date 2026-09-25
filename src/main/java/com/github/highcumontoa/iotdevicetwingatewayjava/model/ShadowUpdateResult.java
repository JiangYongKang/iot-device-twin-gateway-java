package com.github.highcumontoa.iotdevicetwingatewayjava.model;

/** 影子更新结果。 */
public record ShadowUpdateResult(ShadowUpdateOutcome outcome, long appliedVersion, String reason) {
    public boolean applied() { return outcome == ShadowUpdateOutcome.APPLIED; }
}
