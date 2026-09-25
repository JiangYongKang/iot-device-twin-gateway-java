package com.github.highcumontoa.iotdevicetwingatewayjava.tests;

import com.github.highcumontoa.iotdevicetwingatewayjava.model.Campaign;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.DeviceStatus;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.UpgradeState;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.UpgradeTask;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.DeviceRegistry;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.FirmwareService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 固件升级：分批、并发去重、失败回滚、超时回滚、前置条件隔离。 */
class FirmwareServiceTest {

    private static final Logger log = LoggerFactory.getLogger(FirmwareServiceTest.class);

    private record Fixture(DeviceRegistry registry, FirmwareService firmware, ManualTimeSource clock) {}

    private Fixture newFixture() {
        ManualTimeSource clock = new ManualTimeSource(0L);
        DeviceRegistry registry = new DeviceRegistry(clock);
        return new Fixture(registry, new FirmwareService(registry, clock), clock);
    }

    @Test
    void batchUpgradeSuccessAndPreconditionIsolation() {
        Fixture f = newFixture();
        f.registry().register("dev-fw-ok", "1.0.0");
        f.registry().register("dev-fw-bad", "2.0.0"); // 已在目标版本，前置条件不满足

        Campaign campaign = f.firmware().startCampaign(List.of("dev-fw-ok", "dev-fw-bad"), "2.0.0");
        UpgradeTask ok = campaign.getTasks().get(0);
        UpgradeTask bad = campaign.getTasks().get(1);

        log.info("campaign={} device=dev-fw-bad state={} reason={}",
                campaign.getCampaignId(), bad.getState(), bad.getReason());
        assertEquals(UpgradeState.REJECTED_PRECONDITION, bad.getState());
        assertEquals(DeviceStatus.QUARANTINED,
                f.registry().find("dev-fw-bad").orElseThrow().getStatus());

        // 被隔离设备不影响同批其他设备
        assertTrue(f.firmware().markInProgress(ok.getTaskId()));
        assertTrue(f.firmware().markSucceeded(ok.getTaskId()));
        log.info("device=dev-fw-ok state={} version={}", ok.getState(),
                f.registry().find("dev-fw-ok").orElseThrow().getFirmwareVersion());
        assertEquals(UpgradeState.SUCCEEDED, ok.getState());
        assertEquals("2.0.0", f.registry().find("dev-fw-ok").orElseThrow().getFirmwareVersion());
    }

    @Test
    void concurrentUpgradeRequestsForSameDeviceTakeEffectOnce() throws InterruptedException {
        Fixture f = newFixture();
        f.registry().register("dev-fw-race", "1.0.0");

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger pendingCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                Campaign c = f.firmware().startCampaign(List.of("dev-fw-race"), "2.0.0");
                UpgradeState s = c.getTasks().get(0).getState();
                if (s == UpgradeState.PENDING) {
                    pendingCount.incrementAndGet();
                } else if (s == UpgradeState.REJECTED_PRECONDITION) {
                    rejectedCount.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        log.info("device=dev-fw-race 并发请求={} 生效(PENDING)={} 拒绝(REJECTED)={} 判定依据: 生效数必须恰好为1",
                threads, pendingCount.get(), rejectedCount.get());
        assertEquals(1, pendingCount.get());
        assertEquals(threads - 1, rejectedCount.get());
    }

    @Test
    void failedUpgradeRollsBackToPreviousVersion() {
        Fixture f = newFixture();
        f.registry().register("dev-fw-fail", "1.0.0");
        Campaign c = f.firmware().startCampaign(List.of("dev-fw-fail"), "2.0.0");
        UpgradeTask task = c.getTasks().get(0);

        assertTrue(f.firmware().markInProgress(task.getTaskId()));
        f.clock().advance(2_000);
        assertTrue(f.firmware().markFailed(task.getTaskId(), "checksum mismatch"));

        log.info("device=dev-fw-fail t={} state={} reason={} version={}",
                f.clock().now(), task.getState(), task.getReason(),
                f.registry().find("dev-fw-fail").orElseThrow().getFirmwareVersion());
        assertEquals(UpgradeState.FAILED, task.getState());
        assertEquals("1.0.0", f.registry().find("dev-fw-fail").orElseThrow().getFirmwareVersion());

        // 失败后槽位释放，可重新发起升级
        Campaign retry = f.firmware().startCampaign(List.of("dev-fw-fail"), "2.0.0");
        assertEquals(UpgradeState.PENDING, retry.getTasks().get(0).getState());
    }

    @Test
    void timedOutUpgradeIsSweptAndRolledBack() {
        Fixture f = newFixture();
        f.registry().register("dev-fw-timeout", "1.0.0");
        Campaign c = f.firmware().startCampaign(List.of("dev-fw-timeout"), "2.0.0");
        UpgradeTask task = c.getTasks().get(0);
        assertTrue(f.firmware().markInProgress(task.getTaskId()));

        // 未超时前扫描不处理
        f.clock().advance(FirmwareService.UPGRADE_TIMEOUT_MILLIS - 1);
        assertEquals(0, f.firmware().sweepTimeouts());
        assertEquals(UpgradeState.IN_PROGRESS, task.getState());

        // 超过超时时间后被标记 TIMED_OUT 并回滚
        f.clock().advance(2);
        int swept = f.firmware().sweepTimeouts();
        log.info("device=dev-fw-timeout t={} swept={} state={} reason={} version={}",
                f.clock().now(), swept, task.getState(), task.getReason(),
                f.registry().find("dev-fw-timeout").orElseThrow().getFirmwareVersion());
        assertEquals(1, swept);
        assertEquals(UpgradeState.TIMED_OUT, task.getState());
        assertEquals("1.0.0", f.registry().find("dev-fw-timeout").orElseThrow().getFirmwareVersion());
    }

    @Test
    void succeededUpgradeCanBeRolledBackExplicitly() {
        Fixture f = newFixture();
        f.registry().register("dev-fw-rb", "1.0.0");
        Campaign c = f.firmware().startCampaign(List.of("dev-fw-rb"), "2.0.0");
        UpgradeTask task = c.getTasks().get(0);
        f.firmware().markInProgress(task.getTaskId());
        f.firmware().markSucceeded(task.getTaskId());
        assertEquals("2.0.0", f.registry().find("dev-fw-rb").orElseThrow().getFirmwareVersion());

        assertTrue(f.firmware().rollback(task.getTaskId()));
        log.info("device=dev-fw-rb state={} reason={} version={}", task.getState(), task.getReason(),
                f.registry().find("dev-fw-rb").orElseThrow().getFirmwareVersion());
        assertEquals(UpgradeState.ROLLED_BACK, task.getState());
        assertEquals("1.0.0", f.registry().find("dev-fw-rb").orElseThrow().getFirmwareVersion());
    }
}
