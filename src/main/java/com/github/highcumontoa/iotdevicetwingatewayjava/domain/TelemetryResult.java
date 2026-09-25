package com.github.highcumontoa.iotdevicetwingatewayjava.domain;

/** 遥测接收结果，携带判定依据。 */
public record TelemetryResult(TelemetryOutcome outcome, String reason) {
    public static TelemetryResult of(TelemetryOutcome outcome, String reason) {
        return new TelemetryResult(outcome, reason);
    }
}
