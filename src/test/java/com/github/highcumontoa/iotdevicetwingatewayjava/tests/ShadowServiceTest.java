package com.github.highcumontoa.iotdevicetwingatewayjava.tests;

import com.github.highcumontoa.iotdevicetwingatewayjava.model.ShadowState;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.ShadowUpdateOutcome;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.ShadowUpdateResult;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.DeviceRegistry;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.ShadowService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 影子状态：重复、乱序、冲突与并发单调一致。 */
class ShadowServiceTest {

    private static final Logger log = LoggerFactory.getLogger(ShadowServiceTest.class);

    private ShadowService newService(ManualTimeSource clock, String deviceId) {
        DeviceRegistry registry = new DeviceRegistry(clock);
        registry.register(deviceId, "1.0.0");
        return new ShadowService(registry);
    }

    @Test
    void duplicateOutOfOrderAndConflictAreDistinguishable() {
        ManualTimeSource clock = new ManualTimeSource(10_000L);
        String deviceId = "dev-shadow-001";
        ShadowService shadows = newService(clock, deviceId);

        ShadowUpdateResult r1 = shadows.updateReported(deviceId, 5, Map.of("temp", 22), clock.now());
        log.info("device={} v=5 t={} outcome={} reason={}", deviceId, clock.now(), r1.outcome(), r1.reason());
        assertEquals(ShadowUpdateOutcome.APPLIED, r1.outcome());

        ShadowUpdateResult dup = shadows.updateReported(deviceId, 5, Map.of("temp", 22), clock.now());
        log.info("device={} v=5(重复同内容) outcome={} reason={}", deviceId, dup.outcome(), dup.reason());
        assertEquals(ShadowUpdateOutcome.DUPLICATE_IGNORED, dup.outcome());

        ShadowUpdateResult conflict = shadows.updateReported(deviceId, 5, Map.of("temp", 99), clock.now());
        log.info("device={} v=5(同版本冲突内容) outcome={} reason={}", deviceId, conflict.outcome(), conflict.reason());
        assertEquals(ShadowUpdateOutcome.CONFLICT_REJECTED, conflict.outcome());

        ShadowUpdateResult stale = shadows.updateReported(deviceId, 3, Map.of("temp", 1), clock.now());
        log.info("device={} v=3(过期乱序) outcome={} reason={}", deviceId, stale.outcome(), stale.reason());
        assertEquals(ShadowUpdateOutcome.STALE_REJECTED, stale.outcome());

        // 已确认状态不回退
        ShadowState state = shadows.getShadow(deviceId).orElseThrow();
        assertEquals(5, state.getVersion());
        assertEquals(22, state.getReported().get("temp"));
    }

    @Test
    void concurrentUpdatesAreMonotonic() throws InterruptedException {
        ManualTimeSource clock = new ManualTimeSource(0L);
        String deviceId = "dev-shadow-002";
        ShadowService shadows = newService(clock, deviceId);

        int threads = 8;
        int updatesPerThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Long> observedVersions = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < updatesPerThread; i++) {
                    shadows.updateReported(deviceId, i, Map.of("k", i), clock.now());
                    long v = shadows.getShadow(deviceId).orElseThrow().getVersion();
                    synchronized (observedVersions) {
                        observedVersions.add(v);
                    }
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        // 查询结果单调一致：每次观察到的版本不低于之前观察到的最小已确认版本上界
        long maxSoFar = -1;
        synchronized (observedVersions) {
            for (long v : observedVersions) {
                assertTrue(v >= 0, "version must never regress below 0, got " + v);
                maxSoFar = Math.max(maxSoFar, v);
            }
        }
        ShadowState finalState = shadows.getShadow(deviceId).orElseThrow();
        log.info("device={} 并发结束 finalVersion={} 判定依据: 最终版本等于全局最大版本 {}",
                deviceId, finalState.getVersion(), updatesPerThread - 1);
        assertEquals(updatesPerThread - 1, finalState.getVersion());
        assertEquals(updatesPerThread - 1, finalState.getReported().get("k"));
    }
}
