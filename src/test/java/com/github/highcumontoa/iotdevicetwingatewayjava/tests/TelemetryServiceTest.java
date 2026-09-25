package com.github.highcumontoa.iotdevicetwingatewayjava.tests;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.highcumontoa.iotdevicetwingatewayjava.domain.TelemetryMessage;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.TelemetryOutcome;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.TelemetryResult;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.DeviceService;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.TelemetryService;
import com.github.highcumontoa.iotdevicetwingatewayjava.support.MutableClock;

import static org.junit.jupiter.api.Assertions.*;

/** 遥测：离线暂存、按序补传、容量与速率拒绝、幂等计量。 */
class TelemetryServiceTest {

    private static final Logger log = LoggerFactory.getLogger(TelemetryServiceTest.class);
    private static final Instant T0 = Instant.parse("2026-09-26T00:00:00Z");

    private MutableClock clock;
    private DeviceService deviceService;
    private TelemetryService telemetryService;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        clock.set(T0);
        deviceService = new DeviceService(clock);
        telemetryService = new TelemetryService(deviceService, clock, 3, 5, Duration.ofSeconds(1));
        deviceService.register("dev-1", "1.0.0");
    }

    private TelemetryMessage msg(String id, long seq) {
        return new TelemetryMessage(id, "dev-1", seq, T0.plusSeconds(seq), Map.of("kwh", seq));
    }

    @Test
    void offlineMessagesAreBufferedAndReplayedInOrder() {
        deviceService.heartbeat("dev-1", 1, T0);
        deviceService.markOffline("dev-1");
        assertEquals(TelemetryOutcome.BUFFERED, telemetryService.ingest(msg("m2", 2)).outcome());
        assertEquals(TelemetryOutcome.BUFFERED, telemetryService.ingest(msg("m1", 1)).outcome());
        assertEquals(TelemetryOutcome.BUFFERED, telemetryService.ingest(msg("m3", 3)).outcome());
        assertEquals(3, telemetryService.bufferedCount("dev-1"));
        assertEquals(0, telemetryService.meteredCount("dev-1"), "离线期间不计量");

        deviceService.heartbeat("dev-1", 2, T0.plusSeconds(60));
        List<TelemetryResult> results = telemetryService.flushOffline("dev-1");
        log.info("device=dev-1 case=offline-replay results={} metered={} 判定依据: 按seq升序补传且只计量一次",
                results.stream().map(TelemetryResult::outcome).toList(), telemetryService.meteredCount("dev-1"));
        assertEquals(3, results.size());
        assertTrue(results.stream().allMatch(r -> r.outcome() == TelemetryOutcome.ACCEPTED));
        assertEquals(3, telemetryService.meteredCount("dev-1"));
        assertEquals(0, telemetryService.bufferedCount("dev-1"));
    }

    @Test
    void duplicateReplayDoesNotDoubleMeter() {
        deviceService.heartbeat("dev-1", 1, T0);
        deviceService.markOffline("dev-1");
        telemetryService.ingest(msg("m1", 1));
        TelemetryResult dupWhileOffline = telemetryService.ingest(msg("m1", 1));
        deviceService.heartbeat("dev-1", 2, T0.plusSeconds(60));
        telemetryService.flushOffline("dev-1");
        TelemetryResult dupAfterReconnect = telemetryService.ingest(msg("m1", 1));
        log.info("device=dev-1 case=duplicate-replay offlineDup={} reconnectDup={} metered={}",
                dupWhileOffline.outcome(), dupAfterReconnect.outcome(), telemetryService.meteredCount("dev-1"));
        assertEquals(TelemetryOutcome.DUPLICATE, dupWhileOffline.outcome());
        assertEquals(TelemetryOutcome.DUPLICATE, dupAfterReconnect.outcome());
        assertEquals(1, telemetryService.meteredCount("dev-1"), "重复补传不得重复计量");
    }

    @Test
    void bufferOverflowIsExplicitlyRejected() {
        deviceService.markOffline("dev-1");
        telemetryService.ingest(msg("m1", 1));
        telemetryService.ingest(msg("m2", 2));
        telemetryService.ingest(msg("m3", 3));
        TelemetryResult overflow = telemetryService.ingest(msg("m4", 4));
        log.info("device=dev-1 case=capacity outcome={} reason={} 判定依据: 容量=3",
                overflow.outcome(), overflow.reason());
        assertEquals(TelemetryOutcome.REJECTED_CAPACITY, overflow.outcome());
        assertEquals(3, telemetryService.bufferedCount("dev-1"), "超限消息不得静默入队");
    }

    @Test
    void rateLimitIsExplicitlyRejected() {
        deviceService.heartbeat("dev-1", 1, T0);
        for (int i = 1; i <= 5; i++) {
            assertEquals(TelemetryOutcome.ACCEPTED, telemetryService.ingest(msg("m" + i, i)).outcome());
        }
        TelemetryResult limited = telemetryService.ingest(msg("m6", 6));
        log.info("device=dev-1 case=rate-limit outcome={} reason={} 判定依据: 窗口内上限=5",
                limited.outcome(), limited.reason());
        assertEquals(TelemetryOutcome.REJECTED_RATE, limited.outcome());
        assertEquals(5, telemetryService.meteredCount("dev-1"), "被限流的消息不计量");

        clock.advance(Duration.ofSeconds(2));
        TelemetryResult afterWindow = telemetryService.ingest(msg("m6", 6));
        assertEquals(TelemetryOutcome.ACCEPTED, afterWindow.outcome(), "窗口推进后应恢复接收");
        assertEquals(6, telemetryService.meteredCount("dev-1"));
    }

    @Test
    void unknownDeviceIsRejected() {
        TelemetryResult result = telemetryService.ingest(
                new TelemetryMessage("x", "ghost", 1, T0, Map.of()));
        log.info("device=ghost case=unknown outcome={}", result.outcome());
        assertEquals(TelemetryOutcome.REJECTED_UNKNOWN_DEVICE, result.outcome());
    }
}
