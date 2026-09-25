package com.github.highcumontoa.iotdevicetwingatewayjava.model;

/** 遥测接收结果。 */
public record TelemetryResult(TelemetryOutcome outcome, String reason) {
    public boolean counted() {
        return outcome == TelemetryOutcome.ACCEPTED || outcome == TelemetryOutcome.BUFFERED;
    }
}
