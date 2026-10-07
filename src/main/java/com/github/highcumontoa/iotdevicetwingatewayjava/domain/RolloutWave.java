package com.github.highcumontoa.iotdevicetwingatewayjava.domain;

import java.util.List;

/** 运行时波次：编排规格 + 可变推进状态。 */
public class RolloutWave {
    private final int index;
    private final List<String> deviceIds;
    private final int minSuccess;
    private WaveStatus status = WaveStatus.PENDING;
    private String detail = "waiting for previous waves";

    public RolloutWave(int index, List<String> deviceIds, int minSuccess) {
        this.index = index;
        this.deviceIds = List.copyOf(deviceIds);
        this.minSuccess = minSuccess;
    }

    public int getIndex() { return index; }
    public List<String> getDeviceIds() { return deviceIds; }
    public int getMinSuccess() { return minSuccess; }
    public WaveStatus getStatus() { return status; }
    public void setStatus(WaveStatus status) { this.status = status; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
