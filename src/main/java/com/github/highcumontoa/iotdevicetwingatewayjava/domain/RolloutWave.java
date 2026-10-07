package com.github.highcumontoa.iotdevicetwingatewayjava.domain;

import java.util.List;

/** 灰度批次中的一个波次：配置 + 运行态状态。 */
public class RolloutWave {
    private final int index;
    private final WaveSpec spec;
    private WaveStatus status = WaveStatus.WAITING;
    private String detail = "waiting for previous wave";

    public RolloutWave(int index, WaveSpec spec) {
        this.index = index;
        this.spec = spec;
    }

    public int getIndex() { return index; }
    public WaveSpec getSpec() { return spec; }
    public List<String> getDeviceIds() { return spec.deviceIds(); }
    public int getRequiredSuccesses() { return spec.requiredSuccesses(); }
    public WaveStatus getStatus() { return status; }
    public void setStatus(WaveStatus status) { this.status = status; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
