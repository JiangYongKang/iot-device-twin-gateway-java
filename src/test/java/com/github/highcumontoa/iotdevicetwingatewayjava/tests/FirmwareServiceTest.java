package com.github.highcumontoa.iotdevicetwingatewayjava.tests;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.highcumontoa.iotdevicetwingatewayjava.domain.UpgradeStatus;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.UpgradeTask;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.DeviceService;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.FirmwareService;
import com.github.highcumontoa.iotdevicetwingatewayjava.support.MutableClock;

import static org.junit.jupiter.api.Assertions.*;

/** 固件升级：分批、隔离、成功、失败回滚、超时回滚、并发去重。 */
class FirmwareServiceTest {

    private static final Logger log = LoggerFactory.getLogger(FirmwareServiceTest.class);
    private static final Instant T0 = Instant.parse("2026-09-26T00:00:00Z");

    private MutableClock clock;
    private DeviceService deviceService;
    private FirmwareService firmwareService;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        clock.set(T0);
        deviceService = new DeviceService(clock);
        firmwareService = new FirmwareService(deviceService, clock, Duration.ofSeconds(30));
    }

    private String onlineDevice(String id, String version) {
        deviceService.register(id, version);
        deviceService.heartbeat(id, 1, T0);
        return id;
    }

    @Test
    void batchIsolatesUnqualifiedDevicesWithoutAffectingOthers() {
        onlineDevice("dev-ok", "1.0.0");
        onlineDevice("dev-same", "2.0.0");
        deviceService.register("dev-off", "1.0.0");

        List<UpgradeTask> tasks = firmwareService.startBatch("batch-1",
                List.of("dev-ok", "dev-same", "dev-off", "ghost"), "2.0.0");
        log.info("batch=batch-1 case=isolation statuses={}",
                tasks.stream().map(t -> t.getDeviceId() + ":" + t.getStatus()).toList());

        assertEquals(UpgradeStatus.IN_PROGRESS, tasks.get(0).getStatus());
        assertEquals(UpgradeStatus.ISOLATED, tasks.get(1).getStatus(), "已在目标版本应隔离");
        assertEquals(UpgradeStatus.ISOLATED, tasks.get(2).getStatus(), "离线设备应隔离");
        assertEquals(UpgradeStatus.ISOLATED, tasks.get(3).getStatus(), "未知设备应隔离");
        assertTrue(tasks.subList(1, 4).stream().allMatch(t -> t.getDetail() != null && !t.getDetail().isBlank()),
                "隔离原因必须可区分");

        assertTrue(firmwareService.confirmSuccess("dev-ok", "2.0.0"));
        assertEquals(UpgradeStatus.SUCCESS, tasks.get(0).getStatus());
        assertEquals("2.0.0", deviceService.find("dev-ok").orElseThrow().getFirmwareVersion());
        assertEquals("1.0.0", deviceService.find("dev-off").orElseThrow().getFirmwareVersion(),
                "被隔离设备固件不受影响");
    }

    @Test
    void failureTriggersRollbackToPreviousVersion() {
        onlineDevice("dev-1", "1.0.0");
        UpgradeTask task = firmwareService.startBatch("batch-2", List.of("dev-1"), "2.0.0").get(0);
        assertEquals(UpgradeStatus.IN_PROGRESS, task.getStatus());

        boolean accepted = firmwareService.reportFailure("dev-1", "image checksum mismatch");
        log.info("device=dev-1 task={} case=failure-rollback status={} detail={}",
                task.getTaskId(), task.getStatus(), task.getDetail());
        assertTrue(accepted);
        assertEquals(UpgradeStatus.ROLLED_BACK, task.getStatus());
        assertEquals("1.0.0", deviceService.find("dev-1").orElseThrow().getFirmwareVersion(), "失败后必须回滚");
        assertFalse(firmwareService.confirmSuccess("dev-1", "2.0.0"), "回滚后迟到的确认不得生效");
    }

    @Test
    void timeoutIsSweptAndRolledBack() {
        onlineDevice("dev-1", "1.0.0");
        UpgradeTask task = firmwareService.startBatch("batch-3", List.of("dev-1"), "2.0.0").get(0);

        clock.advance(Duration.ofSeconds(29));
        assertTrue(firmwareService.sweepTimeouts().isEmpty(), "未到截止时间不得超时");
        clock.advance(Duration.ofSeconds(2));
        List<UpgradeTask> timedOut = firmwareService.sweepTimeouts();
        log.info("device=dev-1 task={} case=timeout status={} now={} deadline={} 判定依据: 超时30s未确认",
                task.getTaskId(), task.getStatus(), clock.now(), task.getDeadline());
        assertEquals(1, timedOut.size());
        assertEquals(UpgradeStatus.ROLLED_BACK, task.getStatus(), "超时后进入回滚终态");
        assertTrue(task.getDetail().contains("rolled back"));
        assertEquals("1.0.0", deviceService.find("dev-1").orElseThrow().getFirmwareVersion());
    }

    @Test
    void concurrentUpgradeRequestsTakeEffectOnlyOnce() throws InterruptedException {
        onlineDevice("dev-1", "1.0.0");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<UpgradeTask> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    UpgradeTask t = firmwareService.startBatch("batch-race", List.of("dev-1"), "2.0.0").get(0);
                    synchronized (tasks) {
                        tasks.add(t);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        long inProgress = tasks.stream().filter(t -> t.getStatus() == UpgradeStatus.IN_PROGRESS).count();
        long isolated = tasks.stream().filter(t -> t.getStatus() == UpgradeStatus.ISOLATED).count();
        log.info("device=dev-1 case=concurrent-upgrade inProgress={} isolated={} 判定依据: 设备锁+activeTask只生效一次",
                inProgress, isolated);
        assertEquals(1, inProgress, "并发升级请求只能生效一次");
        assertEquals(threads - 1, isolated);
    }

    @Test
    void confirmWithWrongVersionIsRejected() {
        onlineDevice("dev-1", "1.0.0");
        firmwareService.startBatch("batch-4", List.of("dev-1"), "2.0.0");
        boolean accepted = firmwareService.confirmSuccess("dev-1", "9.9.9");
        log.info("device=dev-1 case=version-mismatch accepted={} 判定依据: 上报版本须等于目标版本", accepted);
        assertFalse(accepted);
        assertEquals(UpgradeStatus.IN_PROGRESS, firmwareService.taskOf("dev-1").orElseThrow().getStatus());
        assertEquals("1.0.0", deviceService.find("dev-1").orElseThrow().getFirmwareVersion());
    }
}
