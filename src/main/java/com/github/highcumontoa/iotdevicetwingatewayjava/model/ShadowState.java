package com.github.highcumontoa.iotdevicetwingatewayjava.model;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 设备影子状态（reported 视图），version 单调递增。 */
public class ShadowState {
    private final Map<String, Object> reported = new ConcurrentHashMap<>();
    private long version = 0L;
    private long lastUpdatedAt = -1L;

    public Map<String, Object> getReported() { return reported; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public long getLastUpdatedAt() { return lastUpdatedAt; }
    public void setLastUpdatedAt(long lastUpdatedAt) { this.lastUpdatedAt = lastUpdatedAt; }
}
