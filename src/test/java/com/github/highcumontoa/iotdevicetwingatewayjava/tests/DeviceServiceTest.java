package com.github.highcumontoa.iotdevicetwingatewayjava.tests;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.highcumontoa.iotdevicetwingatewayjava.domain.Device;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.DeviceState;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.ShadowUpdateResult;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.UpdateOutcome;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.DeviceService;
import com.github.highcumontoa.iotdevicetwingatewayjava.support.MutableClock;

import static org.junit.jupiter.api.Assertions.*;

/** 重复上报、乱序时间戳、过期数据与并发单调性。 */
class DeviceServiceTest {

    private static final Logger log = LoggerFactory.getLogger(DeviceServiceTest.class);
    private static final Instant T0 = Instant.parse("2026-09-26T00:00:00Z");

    private MutableClock clock;
    private DeviceService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        clock.set(T0);
        service = new DeviceService(clock);
        service.register("dev-1", "1.0.0");
    }

    @Test
    void duplicateReportIsIgnoredWithoutRegression() {
        ShadowUpdateResult first = service.updateReported("dev-1", 5, T0.plusSeconds(10), Map.of("temp", 22));
        ShadowUpdateResult dup = service.updateReported("dev-1", 5, T0.plusSeconds(10), Map.of("temp", 22));
        log.info("device=dev-1 case=duplicate first={} dup={} 判定依据: 相同(version,at)幂等", first.outcome(), dup.outcome());
        assertEquals(UpdateOutcome.APPLIED, first.outcome());
        assertEquals(UpdateOutcome.DUPLICATE, dup.outcome());
        assertEquals(5, service.find("dev-1").orElseThrow().getReportedVersion());
        assertEquals(22, service.find("dev-1").orElseThrow().getReported().get("temp"));
    }

    @Test
    void outOfOrderAndExpiredReportsAreRejected() {
        service.updateReported("dev-1", 5, T0.plusSeconds(10), Map.of("temp", 22));
        ShadowUpdateResult staleVersion = service.updateReported("dev-1", 4, T0.plusSeconds(20), Map.of("temp", 99));
        ShadowUpdateResult staleTime = service.updateReported("dev-1", 5, T0.plusSeconds(5), Map.of("temp", 99));
        log.info("device=dev-1 case=out-of-order staleVersion={} staleTime={} 判定依据: (version,at)字典序不回退",
                staleVersion.outcome(), staleTime.outcome());
        assertEquals(UpdateOutcome.STALE, staleVersion.outcome());
        assertEquals(UpdateOutcome.STALE, staleTime.outcome());
        Device device = service.find("dev-1").orElseThrow();
        assertEquals(5, device.getReportedVersion());
        assertEquals(22, device.getReported().get("temp"), "已确认状态不得回退");
    }

    @Test
    void heartbeatDuplicateAndStaleAreDistinguishable() {
        ShadowUpdateResult h1 = service.heartbeat("dev-1", 1, T0);
        ShadowUpdateResult dup = service.heartbeat("dev-1", 1, T0);
        ShadowUpdateResult stale = service.heartbeat("dev-1", 0, T0);
        ShadowUpdateResult unknown = service.heartbeat("ghost", 1, T0);
        log.info("device=dev-1 case=heartbeat h1={} dup={} stale={} unknown={}",
                h1.outcome(), dup.outcome(), stale.outcome(), unknown.outcome());
        assertEquals(UpdateOutcome.APPLIED, h1.outcome());
        assertEquals(UpdateOutcome.DUPLICATE, dup.outcome());
        assertEquals(UpdateOutcome.STALE, stale.outcome());
        assertEquals(UpdateOutcome.REJECTED, unknown.outcome());
        assertEquals(DeviceState.ONLINE, service.find("dev-1").orElseThrow().getState());
    }

    @Test
    void concurrentReportsConvergeToHighestVersion() throws InterruptedException {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<ShadowUpdateResult> results = new ArrayList<>();
        for (int i = 1; i <= threads; i++) {
            final long version = i;
            pool.submit(() -> {
                try {
                    start.await();
                    ShadowUpdateResult r = service.updateReported("dev-1", version,
                            T0.plusSeconds(version), Map.of("v", version));
                    synchronized (results) {
                        results.add(r);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        Device device = service.find("dev-1").orElseThrow();
        log.info("device=dev-1 case=concurrent finalVersion={} outcomes={} 判定依据: 单调推进到最高版本",
                device.getReportedVersion(),
                results.stream().map(r -> r.outcome().name()).sorted().toList());
        assertEquals(threads, device.getReportedVersion(), "并发下必须收敛到最高版本");
        long applied = results.stream().filter(r -> r.outcome() == UpdateOutcome.APPLIED).count();
        assertTrue(applied >= 1, "至少一条上报被接受");
        assertTrue(results.stream().allMatch(r -> r.outcome() == UpdateOutcome.APPLIED
                || r.outcome() == UpdateOutcome.STALE), "并发下结论只能是 APPLIED 或 STALE");
    }
}
