package com.github.highcumontoa.iotdevicetwingatewayjava.tests;

import com.github.highcumontoa.iotdevicetwingatewayjava.model.TelemetryOutcome;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.TelemetryPoint;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.TelemetryResult;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.DeviceRegistry;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.TelemetryService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 遥测：离线暂存、按序补传、去重、容量与限流拒绝。 */
class TelemetryServiceTest {

    private static final Logger log = LoggerFactory.getLogger(TelemetryServiceTest.class);

    private record Fixture(DeviceRegistry registry, TelemetryService telemetry, ManualTimeSource clock) {}

    private Fixture newFixture(String deviceId) {
        ManualTimeSource clock = new ManualTimeSource(0L);
        DeviceRegistry registry = new DeviceRegistry(clock);
        registry.register(deviceId, "1.0.0");
        return new Fixture(registry, new TelemetryService(registry, clock), clock);
    }

    private static TelemetryPoint point(String id, long ts) {
        return new TelemetryPoint(id, ts, Map.of("temp", ts));
    }

    @Test
    void offlineDataIsBufferedInOrderAndFlushedOnReconnect() {
        String deviceId = "dev-tel-001";
        Fixture f = newFixture(deviceId);
        f.registry().heartbeat(deviceId, 1);
        f.registry().markOffline(deviceId);

        for (int i = 1; i <= 3; i++) {
            TelemetryResult r = f.telemetry().ingest(deviceId, point("m" + i, i * 1000L));
            log.info("device={} msg=m{} t={} outcome={} reason={}", deviceId, i, f.clock().now(), r.outcome(), r.reason());
            assertEquals(TelemetryOutcome.BUFFERED, r.outcome());
        }
        assertEquals(3, f.telemetry().bufferedCount(deviceId));

        // 重连后按序补传
        f.registry().heartbeat(deviceId, 2);
        List<TelemetryPoint> flushed = f.telemetry().flushOffline(deviceId);
        log.info("device={} 重连补传顺序={}", deviceId, flushed.stream().map(TelemetryPoint::messageId).toList());
        assertEquals(List.of("m1", "m2", "m3"), flushed.stream().map(TelemetryPoint::messageId).toList());
        assertEquals(0, f.telemetry().bufferedCount(deviceId));

        // 重复补传不重复计量
        TelemetryResult dup = f.telemetry().ingest(deviceId, point("m2", 2000L));
        log.info("device={} msg=m2(重复补传) outcome={} reason={} acceptedCount={}",
                deviceId, dup.outcome(), dup.reason(), f.telemetry().acceptedCount(deviceId));
        assertEquals(TelemetryOutcome.DUPLICATE_IGNORED, dup.outcome());
        assertEquals(3, f.telemetry().acceptedCount(deviceId));
    }

    @Test
    void bufferOverflowIsRejectedNotDropped() {
        String deviceId = "dev-tel-002";
        Fixture f = newFixture(deviceId);
        f.registry().heartbeat(deviceId, 1);
        f.registry().markOffline(deviceId);

        // 令牌桶只有 10 个令牌，先推进时钟补充并分批发送，绕开限流专注测试容量
        int accepted = 0;
        for (int i = 0; i < TelemetryService.BUFFER_CAPACITY; i++) {
            f.clock().advance(200); // 每条间隔 200ms，限流 10/s 不会触发
            TelemetryResult r = f.telemetry().ingest(deviceId, point("b" + i, i));
            assertEquals(TelemetryOutcome.BUFFERED, r.outcome());
            accepted++;
        }
        f.clock().advance(200);
        TelemetryResult overflow = f.telemetry().ingest(deviceId, point("overflow", 99999L));
        log.info("device={} 缓冲满 accepted={} outcome={} reason={}", deviceId, accepted, overflow.outcome(), overflow.reason());
        assertEquals(TelemetryOutcome.REJECTED_BUFFER_FULL, overflow.outcome());
        assertEquals(TelemetryService.BUFFER_CAPACITY, f.telemetry().bufferedCount(deviceId));
    }

    @Test
    void rateLimitExcessIsRejectedExplicitly() {
        String deviceId = "dev-tel-003";
        Fixture f = newFixture(deviceId);
        f.registry().heartbeat(deviceId, 1);

        // 令牌桶容量 10，瞬时连发 11 条，第 11 条必须被明确拒绝
        for (int i = 0; i < (int) TelemetryService.BUCKET_CAPACITY; i++) {
            TelemetryResult r = f.telemetry().ingest(deviceId, point("r" + i, i));
            assertEquals(TelemetryOutcome.ACCEPTED, r.outcome());
        }
        TelemetryResult limited = f.telemetry().ingest(deviceId, point("r-overflow", 100L));
        log.info("device={} 超限 outcome={} reason={}", deviceId, limited.outcome(), limited.reason());
        assertEquals(TelemetryOutcome.REJECTED_RATE_LIMITED, limited.outcome());

        // 时钟推进后令牌补充，可重试成功（被拒绝的消息未计入去重集合）
        f.clock().advance(1_000);
        TelemetryResult retry = f.telemetry().ingest(deviceId, point("r-overflow", 100L));
        log.info("device={} 1s后重试 outcome={} reason={}", deviceId, retry.outcome(), retry.reason());
        assertEquals(TelemetryOutcome.ACCEPTED, retry.outcome());
    }

    @Test
    void unknownDeviceIsRejected() {
        Fixture f = newFixture("dev-tel-004");
        TelemetryResult r = f.telemetry().ingest("ghost-device", point("g1", 1L));
        log.info("device=ghost-device outcome={} reason={}", r.outcome(), r.reason());
        assertEquals(TelemetryOutcome.REJECTED_UNKNOWN_DEVICE, r.outcome());
    }
}
