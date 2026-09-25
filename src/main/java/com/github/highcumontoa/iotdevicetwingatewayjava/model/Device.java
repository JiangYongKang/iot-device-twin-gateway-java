package com.github.highcumontoa.iotdevicetwingatewayjava.model;

/** 已注册设备。 */
public class Device {
    private final String deviceId;
    private String firmwareVersion;
    private DeviceStatus status = DeviceStatus.OFFLINE;
    private long lastHeartbeatAt = -1L;
    private long lastHeartbeatSeq = -1L;

    public Device(String deviceId, String firmwareVersion) {
        this.deviceId = deviceId;
        this.firmwareVersion = firmwareVersion;
    }

    public String getDeviceId() { return deviceId; }
    public String getFirmwareVersion() { return firmwareVersion; }
    public void setFirmwareVersion(String firmwareVersion) { this.firmwareVersion = firmwareVersion; }
    public DeviceStatus getStatus() { return status; }
    public void setStatus(DeviceStatus status) { this.status = status; }
    public long getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(long lastHeartbeatAt) { this.lastHeartbeatAt = lastHeartbeatAt; }
    public long getLastHeartbeatSeq() { return lastHeartbeatSeq; }
    public void setLastHeartbeatSeq(long lastHeartbeatSeq) { this.lastHeartbeatSeq = lastHeartbeatSeq; }
}
