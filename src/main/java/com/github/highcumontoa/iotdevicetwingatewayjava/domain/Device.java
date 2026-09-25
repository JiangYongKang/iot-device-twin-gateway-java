package com.github.highcumontoa.iotdevicetwingatewayjava.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 设备聚合：注册信息 + 影子状态（reported/desired）。 */
public class Device {
    private final String deviceId;
    private String firmwareVersion;
    private DeviceState state = DeviceState.OFFLINE;
    private long lastHeartbeatSeq;
    private Instant lastHeartbeatAt;
    private final Map<String, Object> reported = new LinkedHashMap<>();
    private long reportedVersion;
    private Instant reportedAt;
    private final Map<String, Object> desired = new LinkedHashMap<>();
    private long desiredVersion;

    public Device(String deviceId, String firmwareVersion) {
        this.deviceId = deviceId;
        this.firmwareVersion = firmwareVersion;
    }

    public String getDeviceId() { return deviceId; }
    public String getFirmwareVersion() { return firmwareVersion; }
    public void setFirmwareVersion(String firmwareVersion) { this.firmwareVersion = firmwareVersion; }
    public DeviceState getState() { return state; }
    public void setState(DeviceState state) { this.state = state; }
    public long getLastHeartbeatSeq() { return lastHeartbeatSeq; }
    public void setLastHeartbeatSeq(long seq) { this.lastHeartbeatSeq = seq; }
    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(Instant at) { this.lastHeartbeatAt = at; }
    public Map<String, Object> getReported() { return reported; }
    public long getReportedVersion() { return reportedVersion; }
    public void setReportedVersion(long v) { this.reportedVersion = v; }
    public Instant getReportedAt() { return reportedAt; }
    public void setReportedAt(Instant at) { this.reportedAt = at; }
    public Map<String, Object> getDesired() { return desired; }
    public long getDesiredVersion() { return desiredVersion; }
    public void setDesiredVersion(long v) { this.desiredVersion = v; }
}
