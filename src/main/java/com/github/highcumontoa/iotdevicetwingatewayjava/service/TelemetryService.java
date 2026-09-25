package com.github.highcumontoa.iotdevicetwingatewayjava.service;

import com.github.highcumontoa.iotdevicetwingatewayjava.config.TimeSource;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.Device;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.DeviceStatus;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.TelemetryOutcome;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.TelemetryPoint;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.TelemetryResult;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 遥测接收服务。
 * - 每个唯一 messageId 只计量一次（去重），重复补传幂等忽略；
 * - 设备离线时数据按到达顺序暂存于有界缓冲，缓冲满明确拒绝；
 * - 每设备令牌桶限流，超限明确拒绝（不记录 messageId，允许稍后重试）；
 * - 重连后 flushOffline 按序补传，不重复计量。
 */
@Service
public class TelemetryService {

    /** 离线缓冲容量（每设备）。 */
    public static final int BUFFER_CAPACITY = 100;
    /** 令牌桶容量。 */
    public static final double BUCKET_CAPACITY = 10.0;
    /** 令牌补充速率（个/秒）。 */
    public static final double REFILL_PER_SECOND = 10.0;

    private final DeviceRegistry registry;
    private final TimeSource timeSource;

    private static final class DeviceTelemetry {
        final Set<String> seenMessageIds = new HashSet<>();
        final Deque<TelemetryPoint> offlineBuffer = new ArrayDeque<>();
        long acceptedCount = 0L;
        double tokens = BUCKET_CAPACITY;
        long lastRefillAt = -1L;
    }

    private final Map<String, DeviceTelemetry> telemetry = new ConcurrentHashMap<>();

    public TelemetryService(DeviceRegistry registry, TimeSource timeSource) {
        this.registry = registry;
        this.timeSource = timeSource;
    }

    public TelemetryResult ingest(String deviceId, TelemetryPoint point) {
        Device device = registry.find(deviceId).orElse(null);
        if (device == null) {
            return new TelemetryResult(TelemetryOutcome.REJECTED_UNKNOWN_DEVICE,
                    "unknown device: " + deviceId);
        }
        DeviceTelemetry dt = telemetry.computeIfAbsent(deviceId, id -> new DeviceTelemetry());
        synchronized (dt) {
            if (dt.seenMessageIds.contains(point.messageId())) {
                return new TelemetryResult(TelemetryOutcome.DUPLICATE_IGNORED,
                        "duplicate messageId=" + point.messageId());
            }
            if (!tryConsumeToken(dt)) {
                return new TelemetryResult(TelemetryOutcome.REJECTED_RATE_LIMITED,
                        "rate limit exceeded for device=" + deviceId);
            }
            DeviceStatus status = device.getStatus();
            if (status == DeviceStatus.OFFLINE) {
                if (dt.offlineBuffer.size() >= BUFFER_CAPACITY) {
                    return new TelemetryResult(TelemetryOutcome.REJECTED_BUFFER_FULL,
                            "offline buffer full (" + BUFFER_CAPACITY + ") for device=" + deviceId);
                }
                dt.offlineBuffer.addLast(point);
                dt.seenMessageIds.add(point.messageId());
                dt.acceptedCount++;
                return new TelemetryResult(TelemetryOutcome.BUFFERED,
                        "buffered at position=" + dt.offlineBuffer.size());
            }
            dt.seenMessageIds.add(point.messageId());
            dt.acceptedCount++;
            return new TelemetryResult(TelemetryOutcome.ACCEPTED,
                    "accepted messageId=" + point.messageId());
        }
    }

    /** 重连后按序补传离线缓冲；缓冲清空，计量在 ingest 时已完成，此处不重复计量。 */
    public List<TelemetryPoint> flushOffline(String deviceId) {
        DeviceTelemetry dt = telemetry.get(deviceId);
        if (dt == null) {
            return List.of();
        }
        synchronized (dt) {
            List<TelemetryPoint> drained = new ArrayList<>(dt.offlineBuffer);
            dt.offlineBuffer.clear();
            return drained;
        }
    }

    public long acceptedCount(String deviceId) {
        DeviceTelemetry dt = telemetry.get(deviceId);
        if (dt == null) {
            return 0L;
        }
        synchronized (dt) {
            return dt.acceptedCount;
        }
    }

    public int bufferedCount(String deviceId) {
        DeviceTelemetry dt = telemetry.get(deviceId);
        if (dt == null) {
            return 0;
        }
        synchronized (dt) {
            return dt.offlineBuffer.size();
        }
    }

    private boolean tryConsumeToken(DeviceTelemetry dt) {
        long now = timeSource.now();
        if (dt.lastRefillAt < 0) {
            dt.lastRefillAt = now;
        }
        long elapsedMillis = now - dt.lastRefillAt;
        if (elapsedMillis > 0) {
            dt.tokens = Math.min(BUCKET_CAPACITY,
                    dt.tokens + elapsedMillis * REFILL_PER_SECOND / 1000.0);
            dt.lastRefillAt = now;
        }
        if (dt.tokens >= 1.0) {
            dt.tokens -= 1.0;
            return true;
        }
        return false;
    }
}
