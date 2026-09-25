package com.github.highcumontoa.iotdevicetwingatewayjava.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.github.highcumontoa.iotdevicetwingatewayjava.domain.DeviceState;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.TelemetryMessage;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.TelemetryOutcome;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.TelemetryResult;
import com.github.highcumontoa.iotdevicetwingatewayjava.support.MutableClock;

/**
 * 遥测接收、离线暂存与补传服务。
 *
 * - 幂等：messageId 去重，重复上报/重复补传不重复计量。
 * - 离线：按 seq 排序暂存有界队列，满则明确拒绝（REJECTED_CAPACITY）。
 * - 限流：每设备固定窗口速率限制，超限明确拒绝（REJECTED_RATE）。
 */
@Service
public class TelemetryService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);

    private final DeviceService deviceService;
    private final MutableClock clock;

    private final int offlineCapacity;
    private final int rateLimitPerWindow;
    private final Duration rateWindow;

    private final Map<String, Deque<TelemetryMessage>> offlineBuffers = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> seenMessageIds = new ConcurrentHashMap<>();
    private final Map<String, Long> meteredCounts = new ConcurrentHashMap<>();
    private final Map<String, RateWindow> rateWindows = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public TelemetryService(DeviceService deviceService, MutableClock clock) {
        this(deviceService, clock, 100, 10, Duration.ofSeconds(1));
    }

    public TelemetryService(DeviceService deviceService, MutableClock clock,
                            int offlineCapacity, int rateLimitPerWindow, Duration rateWindow) {
        this.deviceService = deviceService;
        this.clock = clock;
        this.offlineCapacity = offlineCapacity;
        this.rateLimitPerWindow = rateLimitPerWindow;
        this.rateWindow = rateWindow;
    }

    public TelemetryResult ingest(TelemetryMessage message) {
        String deviceId = message.deviceId();
        var device = deviceService.find(deviceId);
        if (device.isEmpty()) {
            log.info("device={} action=telemetry msg={} outcome=REJECTED_UNKNOWN_DEVICE", deviceId, message.messageId());
            return TelemetryResult.of(TelemetryOutcome.REJECTED_UNKNOWN_DEVICE, "device not registered");
        }
        synchronized (deviceService.lockOf(deviceId)) {
            if (seenMessageIds.computeIfAbsent(deviceId, k -> new HashSet<>()).contains(message.messageId())) {
                log.info("device={} action=telemetry msg={} outcome=DUPLICATE", deviceId, message.messageId());
                return TelemetryResult.of(TelemetryOutcome.DUPLICATE, "messageId already processed");
            }
            if (device.get().getState() == DeviceState.OFFLINE) {
                Deque<TelemetryMessage> buffer = offlineBuffers.computeIfAbsent(deviceId, k -> new ArrayDeque<>());
                if (buffer.size() >= offlineCapacity) {
                    log.info("device={} action=telemetry msg={} outcome=REJECTED_CAPACITY buffered={}",
                            deviceId, message.messageId(), buffer.size());
                    return TelemetryResult.of(TelemetryOutcome.REJECTED_CAPACITY,
                            "offline buffer full at " + offlineCapacity);
                }
                buffer.add(message);
                seenMessageIds.get(deviceId).add(message.messageId());
                log.info("device={} action=telemetry msg={} seq={} outcome=BUFFERED buffered={}",
                        deviceId, message.messageId(), message.seq(), buffer.size());
                return TelemetryResult.of(TelemetryOutcome.BUFFERED, "device offline, buffered for replay");
            }
            if (!allowRate(deviceId)) {
                log.info("device={} action=telemetry msg={} outcome=REJECTED_RATE", deviceId, message.messageId());
                return TelemetryResult.of(TelemetryOutcome.REJECTED_RATE,
                        "rate limit " + rateLimitPerWindow + " per " + rateWindow);
            }
            accept(deviceId, message);
            log.info("device={} action=telemetry msg={} seq={} outcome=ACCEPTED metered={}",
                    deviceId, message.messageId(), message.seq(), meteredCounts.get(deviceId));
            return TelemetryResult.of(TelemetryOutcome.ACCEPTED, "metered");
        }
    }

    /** 重连后按 seq 顺序补传，已计量消息不重复计量。 */
    public List<TelemetryResult> flushOffline(String deviceId) {
        List<TelemetryResult> results = new ArrayList<>();
        synchronized (deviceService.lockOf(deviceId)) {
            Deque<TelemetryMessage> buffer = offlineBuffers.get(deviceId);
            if (buffer == null || buffer.isEmpty()) {
                return results;
            }
            List<TelemetryMessage> ordered = new ArrayList<>(buffer);
            ordered.sort((a, b) -> Long.compare(a.seq(), b.seq()));
            for (TelemetryMessage message : ordered) {
                if (!allowRate(deviceId)) {
                    log.info("device={} action=flush msg={} outcome=REJECTED_RATE remaining={}",
                            deviceId, message.messageId(), buffer.size());
                    results.add(TelemetryResult.of(TelemetryOutcome.REJECTED_RATE, "rate limited during replay"));
                    break;
                }
                accept(deviceId, message);
                buffer.remove(message);
                log.info("device={} action=flush msg={} seq={} outcome=ACCEPTED metered={}",
                        deviceId, message.messageId(), message.seq(), meteredCounts.get(deviceId));
                results.add(TelemetryResult.of(TelemetryOutcome.ACCEPTED, "replayed and metered"));
            }
        }
        return results;
    }

    public long meteredCount(String deviceId) {
        return meteredCounts.getOrDefault(deviceId, 0L);
    }

    public int bufferedCount(String deviceId) {
        Deque<TelemetryMessage> buffer = offlineBuffers.get(deviceId);
        return buffer == null ? 0 : buffer.size();
    }

    private void accept(String deviceId, TelemetryMessage message) {
        seenMessageIds.computeIfAbsent(deviceId, k -> new HashSet<>()).add(message.messageId());
        meteredCounts.merge(deviceId, 1L, Long::sum);
    }

    private boolean allowRate(String deviceId) {
        Instant now = clock.now();
        RateWindow window = rateWindows.computeIfAbsent(deviceId, k -> new RateWindow(now));
        if (!now.isBefore(window.start.plus(rateWindow))) {
            window.start = now;
            window.count = 0;
        }
        if (window.count >= rateLimitPerWindow) {
            return false;
        }
        window.count++;
        return true;
    }

    private static final class RateWindow {
        Instant start;
        int count;

        RateWindow(Instant start) {
            this.start = start;
        }
    }
}
