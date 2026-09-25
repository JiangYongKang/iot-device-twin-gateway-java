package com.github.highcumontoa.iotdevicetwingatewayjava.tests;

import com.github.highcumontoa.iotdevicetwingatewayjava.model.DeviceStatus;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.ShadowUpdateOutcome;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.ShadowUpdateResult;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.DeviceRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 心跳：重复上报与乱序到达不得使已确认状态回退。 */
class DeviceRegistryTest {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistryTest.class);

    @Test
    void duplicateAndOutOfOrderHeartbeatDoNotRegress() {
        ManualTimeSource clock = new ManualTimeSource(1_000L);
        DeviceRegistry registry = new DeviceRegistry(clock);
        String deviceId = "dev-hb-001";
        registry.register(deviceId, "1.0.0");

        ShadowUpdateResult r1 = registry.heartbeat(deviceId, 10);
        log.info("device={} seq=10 t={} outcome={} reason={}", deviceId, clock.now(), r1.outcome(), r1.reason());
        assertEquals(ShadowUpdateOutcome.APPLIED, r1.outcome());

        ShadowUpdateResult dup = registry.heartbeat(deviceId, 10);
        log.info("device={} seq=10(重复) t={} outcome={} reason={}", deviceId, clock.now(), dup.outcome(), dup.reason());
        assertEquals(ShadowUpdateOutcome.DUPLICATE_IGNORED, dup.outcome());

        ShadowUpdateResult stale = registry.heartbeat(deviceId, 7);
        log.info("device={} seq=7(乱序) t={} outcome={} reason={}", deviceId, clock.now(), stale.outcome(), stale.reason());
        assertEquals(ShadowUpdateOutcome.STALE_REJECTED, stale.outcome());

        // 已确认状态未回退
        assertEquals(10, registry.find(deviceId).orElseThrow().getLastHeartbeatSeq());

        ShadowUpdateResult r2 = registry.heartbeat(deviceId, 11);
        log.info("device={} seq=11 t={} outcome={} reason={}", deviceId, clock.now(), r2.outcome(), r2.reason());
        assertEquals(ShadowUpdateOutcome.APPLIED, r2.outcome());
    }

    @Test
    void heartbeatReconnectsOfflineDevice() {
        ManualTimeSource clock = new ManualTimeSource(0L);
        DeviceRegistry registry = new DeviceRegistry(clock);
        String deviceId = "dev-hb-002";
        registry.register(deviceId, "1.0.0");
        registry.heartbeat(deviceId, 1);
        registry.markOffline(deviceId);
        assertEquals(DeviceStatus.OFFLINE, registry.find(deviceId).orElseThrow().getStatus());

        clock.advance(5_000);
        ShadowUpdateResult r = registry.heartbeat(deviceId, 2);
        log.info("device={} 重连心跳 seq=2 t={} outcome={} status={}", deviceId, clock.now(),
                r.outcome(), registry.find(deviceId).orElseThrow().getStatus());
        assertEquals(ShadowUpdateOutcome.APPLIED, r.outcome());
        assertEquals(DeviceStatus.ONLINE, registry.find(deviceId).orElseThrow().getStatus());
    }
}
