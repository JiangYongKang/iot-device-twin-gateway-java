package com.github.highcumontoa.iotdevicetwingatewayjava.tests;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.highcumontoa.iotdevicetwingatewayjava.domain.RolloutBatch;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.RolloutStatus;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.RolloutSummary;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.UpgradeStatus;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.UpgradeTask;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.WaveSpec;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.WaveStatus;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.DeviceService;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.FirmwareService;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.RolloutService;
import com.github.highcumontoa.iotdevicetwingatewayjava.support.MutableClock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分批灰度发布：波次推进、达标/不达标自动判定、中途叫停与回滚、待命隔离、
 * 汇总互斥对账、并发确定、百台规模。
 */
class RolloutServiceTest {

    private static final Logger log = LoggerFactory.getLogger(RolloutServiceTest.class);
    private static final Instant T0 = Instant.parse("2026-10-08T00:00:00Z");

    private MutableClock clock;
    private DeviceService deviceService;
    private FirmwareService firmwareService;
    private RolloutService rolloutService;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        clock.set(T0);
        deviceService = new DeviceService(clock);
        firmwareService = new FirmwareService(deviceService, clock, Duration.ofSeconds(30));
        rolloutService = new RolloutService(deviceService, firmwareService, clock);
    }

    private String online(String id, String fw) {
        deviceService.register(id, fw);
        deviceService.heartbeat(id, 1, T0);
        return id;
    }

    /** 汇总恒等式：total 等于各桶之和，且批次桶等于各波次桶之和。 */
    private void assertCountsReconcile(RolloutSummary s) {
        assertEquals(s.total(), s.success() + s.failed() + s.timeout() + s.isolated()
                + s.aborted() + s.inProgress() + s.waiting(), "批次各桶之和必须等于 total");
        int[] sums = new int[7];
        int waveTotal = 0;
        for (RolloutSummary.WaveCount w : s.waves()) {
            sums[0] += w.success();
            sums[1] += w.failed();
            sums[2] += w.timeout();
            sums[3] += w.isolated();
            sums[4] += w.aborted();
            sums[5] += w.inProgress();
            sums[6] += w.waiting();
            waveTotal += w.total();
            assertEquals(w.total(), w.success() + w.failed() + w.timeout() + w.isolated()
                    + w.aborted() + w.inProgress() + w.waiting(), "波次各桶之和必须等于波次 total");
        }
        assertEquals(waveTotal, s.total());
        assertEquals(s.success(), sums[0]);
        assertEquals(s.failed(), sums[1]);
        assertEquals(s.timeout(), sums[2]);
        assertEquals(s.isolated(), sums[3]);
        assertEquals(s.aborted(), sums[4]);
        assertEquals(s.inProgress(), sums[5]);
        assertEquals(s.waiting(), sums[6]);
        // 与设备实际任务逐条对账
        Map<String, AtomicInteger> actual = new HashMap<>();
        for (UpgradeTask t : firmwareService.tasksOfBatch(s.batchId())) {
            String bucket = bucketOf(t);
            actual.computeIfAbsent(bucket, k -> new AtomicInteger()).incrementAndGet();
        }
        assertEquals(s.success(), actual.getOrDefault("success", new AtomicInteger()).get());
        assertEquals(s.failed(), actual.getOrDefault("failed", new AtomicInteger()).get());
        assertEquals(s.timeout(), actual.getOrDefault("timeout", new AtomicInteger()).get());
        assertEquals(s.isolated(), actual.getOrDefault("isolated", new AtomicInteger()).get());
        assertEquals(s.aborted(), actual.getOrDefault("aborted", new AtomicInteger()).get());
        int dispatched = s.success() + s.failed() + s.timeout() + s.isolated() + s.aborted() + s.inProgress();
        assertEquals(s.waiting(), s.total() - dispatched, "waiting 必须是尚未下发的设备数");
    }

    private String bucketOf(UpgradeTask t) {
        return switch (t.getStatus()) {
            case SUCCESS -> "success";
            case IN_PROGRESS, PENDING -> "inProgress";
            case ISOLATED -> "isolated";
            case ABORTED -> "aborted";
            case ROLLED_BACK -> t.getRollbackCause() == UpgradeStatus.TIMEOUT ? "timeout"
                    : t.getRollbackCause() == UpgradeStatus.ABORTED ? "aborted" : "failed";
            default -> "failed";
        };
    }

    @Test
    void wavesAreWaitingUntilPreviousPassesAndNeverDispatchedEarly() {
        online("w0", "1.0.0");
        online("w1", "1.0.0");
        online("w2", "1.0.0");
        RolloutBatch b = rolloutService.createRollout("b-wait", "2.0.0", List.of(
                new WaveSpec(List.of("w0"), 1),
                new WaveSpec(List.of("w1"), 1),
                new WaveSpec(List.of("w2"), 1)));

        assertEquals(WaveStatus.WAITING, b.getWaves().get(0).getStatus());
        RolloutSummary s0 = rolloutService.summary("b-wait");
        assertEquals(3, s0.waiting());
        assertTrue(firmwareService.tasksOfBatch("b-wait").isEmpty(), "创建后不得提前下发任何设备");

        rolloutService.launchNextWave("b-wait");
        assertEquals(WaveStatus.RUNNING, b.getWaves().get(0).getStatus());
        assertEquals(WaveStatus.WAITING, b.getWaves().get(1).getStatus());
        assertEquals(WaveStatus.WAITING, b.getWaves().get(2).getStatus());
        // 第一波尚未达标，重复请求下发下一波不生效
        assertTrue(rolloutService.launchNextWave("b-wait").isEmpty(), "前一波未达标不得提前推进");
        assertEquals(1, firmwareService.tasksOfBatch("b-wait").size(), "后续波次设备绝不能提前下发");
        log.info("batch=b-wait case=waiting-guard tasks={} statuses={}",
                firmwareService.tasksOfBatch("b-wait").size(),
                firmwareService.tasksOfBatch("b-wait").stream().map(t -> t.getDeviceId() + ":" + t.getStatus()).toList());
        assertCountsReconcile(rolloutService.summary("b-wait"));
    }

    @Test
    void wavePassesWhenSuccessMeetsThresholdThenAutoDispatchesNext() {
        // 3 台一波，要求 2 台成功；1 台失败仍可达标
        List<String> devs = new ArrayList<>();
        for (int i = 0; i < 3; i++) devs.add(online("p-" + i, "1.0.0"));
        online("next-1", "1.0.0");
        RolloutBatch b = rolloutService.createRollout("b-pass", "2.0.0", List.of(
                new WaveSpec(devs, 2),
                new WaveSpec(List.of("next-1"), 1)));
        rolloutService.launchNextWave("b-pass");

        assertTrue(rolloutService.confirmSuccess("b-pass", "p-0", "2.0.0"));
        assertTrue(rolloutService.reportFailure("b-pass", "p-1", "checksum mismatch"));
        assertEquals(WaveStatus.RUNNING, b.getWaves().get(0).getStatus(), "1 成功尚未达标，继续等待");
        assertEquals(WaveStatus.WAITING, b.getWaves().get(1).getStatus());
        assertEquals("1.0.0", deviceService.find("p-1").orElseThrow().getFirmwareVersion(), "失败设备回滚到原版本");
        UpgradeTask failedTask = firmwareService.tasksOfBatch("b-pass").stream()
                .filter(t -> t.getDeviceId().equals("p-1")).findFirst().orElseThrow();
        assertEquals(UpgradeStatus.ROLLED_BACK, failedTask.getStatus());
        assertEquals(UpgradeStatus.FAILED, failedTask.getRollbackCause(), "失败回滚诱因须为 FAILED");

        assertTrue(rolloutService.confirmSuccess("b-pass", "p-2", "2.0.0"));
        assertEquals(WaveStatus.PASSED, b.getWaves().get(0).getStatus());
        assertEquals(WaveStatus.RUNNING, b.getWaves().get(1).getStatus(), "达标即自动下发下一波");
        log.info("batch=b-pass case=pass-then-auto wave0={} wave1={} 判定依据: success>=required",
                b.getWaves().get(0).getDetail(), b.getWaves().get(1).getStatus());
        assertCountsReconcile(rolloutService.summary("b-pass"));
    }

    @Test
    void waveFailingThresholdHaltsBatchAndAbortsInProgressWithRollback() {
        // 3 台要求 2 成功；两台相继失败后，剩余 1 台进行中也无法达标 -> 整批停止，进行中设备被中止
        for (int i = 0; i < 3; i++) online("h-" + i, "1.0.0");
        online("later", "1.0.0");
        RolloutBatch b = rolloutService.createRollout("b-halt", "2.0.0", List.of(
                new WaveSpec(List.of("h-0", "h-1", "h-2"), 2),
                new WaveSpec(List.of("later"), 1)));
        rolloutService.launchNextWave("b-halt");

        rolloutService.reportFailure("b-halt", "h-0", "brick");
        assertEquals(RolloutStatus.IN_PROGRESS, b.getStatus(), "1 台失败但仍有 2 台进行中，尚有达标可能");
        UpgradeTask h0 = firmwareService.tasksOfBatch("b-halt").stream()
                .filter(t -> t.getDeviceId().equals("h-0")).findFirst().orElseThrow();
        assertEquals(UpgradeStatus.FAILED, h0.getRollbackCause(), "设备自报失败的诱因保持 FAILED");

        rolloutService.reportFailure("b-halt", "h-1", "brick too");
        // h-1 失败后 success=0, inProgress=1(h-2) < 2，判定不可能达标
        assertEquals(RolloutStatus.HALTED, b.getStatus(), "不达标必须整批停止");
        assertEquals(WaveStatus.FAILED, b.getWaves().get(0).getStatus());
        assertEquals(WaveStatus.WAITING, b.getWaves().get(1).getStatus(), "后续波保持待命不再下发");

        UpgradeTask h1 = firmwareService.tasksOfBatch("b-halt").stream()
                .filter(t -> t.getDeviceId().equals("h-1")).findFirst().orElseThrow();
        assertEquals(UpgradeStatus.FAILED, h1.getRollbackCause(), "设备自己失败必须是 FAILED，不是 ABORTED");
        assertEquals("1.0.0", deviceService.find("h-1").orElseThrow().getFirmwareVersion());

        UpgradeTask h2 = firmwareService.tasksOfBatch("b-halt").stream()
                .filter(t -> t.getDeviceId().equals("h-2")).findFirst().orElseThrow();
        assertEquals(UpgradeStatus.ROLLED_BACK, h2.getStatus());
        assertEquals(UpgradeStatus.ABORTED, h2.getRollbackCause(),
                "被中途叫停的设备诱因必须是 ABORTED，与 FAILED/TIMEOUT/ISOLATED 可区分");
        assertEquals("1.0.0", deviceService.find("h-2").orElseThrow().getFirmwareVersion(), "叫停设备回滚到升级前版本");
        assertTrue(h2.getDetail().contains("batch halted"));
        assertNotNull(b.getHaltReason());
        log.info("batch=b-halt case=auto-halt status={} reason={} 判定依据: {}", b.getStatus(), b.getHaltReason(),
                b.getWaves().get(0).getDetail());
        assertCountsReconcile(rolloutService.summary("b-halt"));
    }

    @Test
    void timeoutCanAlsoTriggerHaltAndTimeoutsAreNotCountedAsAborted() {
        // 4 台要求 2 成功：1 台成功后推进时间，其余 3 台全部超时 -> HALTED，timeout=3 aborted=0
        for (int i = 0; i < 4; i++) online("u-" + i, "1.0.0");
        RolloutBatch b = rolloutService.createRollout("b-halt-timeout", "2.0.0", List.of(
                new WaveSpec(List.of("u-0", "u-1", "u-2", "u-3"), 2)));
        rolloutService.launchNextWave("b-halt-timeout");
        rolloutService.confirmSuccess("b-halt-timeout", "u-0", "2.0.0");
        clock.advance(Duration.ofSeconds(31));
        List<UpgradeTask> swept = rolloutService.sweepTimeouts("b-halt-timeout");
        assertEquals(3, swept.size());
        assertEquals(RolloutStatus.HALTED, b.getStatus());
        RolloutSummary s = rolloutService.summary("b-halt-timeout");
        assertEquals(1, s.success());
        assertEquals(3, s.timeout());
        assertEquals(0, s.aborted(), "超时回滚不得计入中途叫停");
        for (String id : List.of("u-1", "u-2", "u-3")) {
            assertEquals("1.0.0", deviceService.find(id).orElseThrow().getFirmwareVersion());
        }
        assertCountsReconcile(s);
    }

    @Test
    void manualAbortRollsBackInProgressKeepsSuccessesAndLeavesWaitingUntouched() {
        for (int i = 0; i < 3; i++) online("a-" + i, "1.0.0");
        online("a-next", "1.0.0");
        RolloutBatch b = rolloutService.createRollout("b-abort", "2.0.0", List.of(
                new WaveSpec(List.of("a-0", "a-1", "a-2"), 2),
                new WaveSpec(List.of("a-next"), 1)));
        rolloutService.launchNextWave("b-abort");
        rolloutService.confirmSuccess("b-abort", "a-0", "2.0.0");

        List<UpgradeTask> aborted = rolloutService.abort("b-abort", "operator stop");
        assertEquals(2, aborted.size(), "两台进行中设备被中止");
        assertEquals(RolloutStatus.ABORTED, b.getStatus());
        assertEquals(WaveStatus.ABORTED, b.getWaves().get(0).getStatus());
        assertEquals(WaveStatus.ABORTED, b.getWaves().get(1).getStatus());

        UpgradeTask a0 = firmwareService.tasksOfBatch("b-abort").stream()
                .filter(t -> t.getDeviceId().equals("a-0")).findFirst().orElseThrow();
        assertEquals(UpgradeStatus.SUCCESS, a0.getStatus(), "已成功结果必须原样保留，不得被叫停覆盖");
        assertEquals("2.0.0", deviceService.find("a-0").orElseThrow().getFirmwareVersion(), "成功设备固件不回退");
        for (String id : List.of("a-1", "a-2")) {
            UpgradeTask t = firmwareService.tasksOfBatch("b-abort").stream()
                    .filter(x -> x.getDeviceId().equals(id)).findFirst().orElseThrow();
            assertEquals(UpgradeStatus.ABORTED, t.getRollbackCause());
            assertEquals("1.0.0", deviceService.find(id).orElseThrow().getFirmwareVersion());
        }
        // 叫停后任何结果确认/推进都被拒绝
        assertFalse(rolloutService.confirmSuccess("b-abort", "a-1", "2.0.0"));
        assertTrue(rolloutService.launchNextWave("b-abort").isEmpty());
        log.info("batch=b-abort case=manual-abort aborted={} successKept=a-0:2.0.0", aborted.size());
        assertCountsReconcile(rolloutService.summary("b-abort"));
    }

    @Test
    void isolatedDevicesCountSeparatelyAndDoNotBlockOthers() {
        online("ok-1", "1.0.0");
        deviceService.register("off-1", "1.0.0"); // 离线
        online("same-1", "2.0.0"); // 已在目标版本
        RolloutBatch b = rolloutService.createRollout("b-iso", "2.0.0", List.of(
                new WaveSpec(List.of("ok-1", "off-1", "same-1", "ghost"), 1)));
        rolloutService.launchNextWave("b-iso");

        RolloutSummary s = rolloutService.summary("b-iso");
        assertEquals(3, s.isolated(), "离线/同版本/未知设备均隔离");
        assertEquals(1, s.inProgress());
        rolloutService.confirmSuccess("b-iso", "ok-1", "2.0.0");
        assertEquals(RolloutStatus.COMPLETED, b.getStatus(), "隔离不影响达标判定");
        assertCountsReconcile(rolloutService.summary("b-iso"));
    }

    @Test
    void timeoutIsSweptAndClassifiedAsTimeoutNotAbort() {
        online("t-0", "1.0.0");
        online("t-1", "1.0.0");
        RolloutBatch b = rolloutService.createRollout("b-timeout", "2.0.0", List.of(
                new WaveSpec(List.of("t-0", "t-1"), 2)));
        rolloutService.launchNextWave("b-timeout");
        clock.advance(Duration.ofSeconds(31));
        List<UpgradeTask> swept = rolloutService.sweepTimeouts("b-timeout");
        assertEquals(2, swept.size());
        assertEquals(RolloutStatus.HALTED, b.getStatus());
        RolloutSummary s = rolloutService.summary("b-timeout");
        assertEquals(2, s.timeout());
        assertEquals(0, s.aborted(), "超时与中途叫停必须分开计数");
        log.info("batch=b-timeout case=timeout swept={} timeoutCount={}",
                swept.stream().map(UpgradeTask::getDeviceId).toList(), s.timeout());
        assertCountsReconcile(s);
    }

    @Test
    void rejectsDeviceThatAlreadyHasActiveUpgradeFromAnotherBatch() {
        online("shared", "1.0.0");
        online("other", "1.0.0");
        // 旧的整批直推占用了 shared
        firmwareService.startBatch("legacy", List.of("shared"), "2.0.0");
        RolloutBatch b = rolloutService.createRollout("b-cross", "2.0.0", List.of(
                new WaveSpec(List.of("shared", "other"), 1)));
        rolloutService.launchNextWave("b-cross");
        UpgradeTask sharedTask = firmwareService.tasksOfBatch("b-cross").stream()
                .filter(t -> t.getDeviceId().equals("shared")).findFirst().orElseThrow();
        assertEquals(UpgradeStatus.ISOLATED, sharedTask.getStatus(), "同一设备同一时刻只能有一个进行中升级");
        assertTrue(sharedTask.getDetail().contains("active task"));
        assertCountsReconcile(rolloutService.summary("b-cross"));
    }

    @Test
    void concurrentResultConfirmationAndAbortProduceStableOutcome() throws InterruptedException {
        int n = 20;
        List<String> devs = new ArrayList<>();
        for (int i = 0; i < n; i++) devs.add(online("c-" + i, "1.0.0"));
        RolloutBatch b = rolloutService.createRollout("b-race", "2.0.0", List.of(
                new WaveSpec(devs, n))); // 必须全部成功才达标，abort 与确认充分竞争
        rolloutService.launchNextWave("b-race");

        int threads = n;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger confirmed = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            final String dev = "c-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    if (rolloutService.confirmSuccess("b-race", dev, "2.0.0")) {
                        confirmed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        // 与确认并发地发起中止
        List<UpgradeTask> aborted = rolloutService.abort("b-race", "race stop");
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        // 每台设备恰好一种稳定结论：成功（保留）或被中止回滚，不存在重复计数
        RolloutSummary s = rolloutService.summary("b-race");
        log.info("batch=b-race case=concurrent confirmed={} abortedByCall={} summary(success={},aborted={},inProgress={})",
                confirmed.get(), aborted.size(), s.success(), s.aborted(), s.inProgress());
        assertEquals(n, s.success() + s.aborted(), "每台设备恰好计入一次");
        assertEquals(0, s.inProgress(), "竞争结束后不得残留进行中任务");
        assertEquals(s.success(), confirmed.get(), "汇总成功数必须与被接受的确认数一致");
        for (String dev : devs) {
            String fw = deviceService.find(dev).orElseThrow().getFirmwareVersion();
            UpgradeTask t = firmwareService.tasksOfBatch("b-race").stream()
                    .filter(x -> x.getDeviceId().equals(dev)).findFirst().orElseThrow();
            if (t.getStatus() == UpgradeStatus.SUCCESS) {
                assertEquals("2.0.0", fw);
            } else {
                assertEquals(UpgradeStatus.ABORTED, t.getRollbackCause());
                assertEquals("1.0.0", fw);
            }
        }
        assertCountsReconcile(s);
    }

    @Test
    void hundredDevicesAcrossWavesReconcileAtEveryStage() {
        int waveCount = 4;
        int perWave = 25;
        List<WaveSpec> specs = new ArrayList<>();
        List<List<String>> all = new ArrayList<>();
        for (int w = 0; w < waveCount; w++) {
            List<String> devs = new ArrayList<>();
            for (int i = 0; i < perWave; i++) devs.add(online("d-" + w + "-" + i, "1.0.0"));
            all.add(devs);
            // 每波允许 2 台失败，要求 23 台成功
            specs.add(new WaveSpec(devs, perWave - 2));
        }
        RolloutBatch b = rolloutService.createRollout("b-big", "2.0.0", specs);
        assertEquals(100, rolloutService.summary("b-big").waiting());

        rolloutService.launchNextWave("b-big");
        for (int w = 0; w < waveCount; w++) {
            List<String> devs = all.get(w);
            // 前两台失败，其余 23 台成功 -> 恰好达标
            rolloutService.reportFailure("b-big", devs.get(0), "fail-1");
            rolloutService.reportFailure("b-big", devs.get(1), "fail-2");
            for (int i = 2; i < perWave; i++) {
                assertTrue(rolloutService.confirmSuccess("b-big", devs.get(i), "2.0.0"));
            }
            RolloutSummary mid = rolloutService.summary("b-big");
            assertCountsReconcile(mid);
            if (w < waveCount - 1) {
                assertEquals(WaveStatus.RUNNING, b.getWaves().get(w + 1).getStatus(), "达标自动推进下一波");
                // 已处理 (w+1) 波 + 下一波已在进行中，剩余波次设备仍在待命
                assertEquals((waveCount - w - 2) * perWave, mid.waiting(), "后续波次设备必须仍在待命");
            }
        }
        RolloutSummary end = rolloutService.summary("b-big");
        assertEquals(RolloutStatus.COMPLETED, b.getStatus());
        assertEquals(92, end.success());
        assertEquals(8, end.failed());
        assertEquals(0, end.waiting() + end.inProgress() + end.aborted() + end.timeout() + end.isolated());
        log.info("batch=b-big case=hundred-scale total={} success={} failed={}", end.total(), end.success(), end.failed());
        assertCountsReconcile(end);
    }

    @Test
    void invalidWaveConfigurationsAreRejected() {
        online("x-1", "1.0.0");
        assertThrows(IllegalArgumentException.class, () -> rolloutService.createRollout("e1", "2.0.0", List.of()));
        assertThrows(IllegalArgumentException.class, () -> rolloutService.createRollout("e2", "2.0.0",
                List.of(new WaveSpec(List.of("x-1"), 0))), "requiredSuccesses 至少为 1");
        assertThrows(IllegalArgumentException.class, () -> rolloutService.createRollout("e3", "2.0.0",
                List.of(new WaveSpec(List.of("x-1"), 2))), "requiredSuccesses 不得超过波次设备数");
        assertThrows(IllegalArgumentException.class, () -> rolloutService.createRollout("e4", "2.0.0",
                List.of(new WaveSpec(List.of("x-1"), 1), new WaveSpec(List.of("x-1"), 1))),
                "同一设备不得跨波重复");
        rolloutService.createRollout("e5", "2.0.0", List.of(new WaveSpec(List.of("x-1"), 1)));
        assertThrows(IllegalStateException.class, () -> rolloutService.createRollout("e5", "2.0.0",
                List.of(new WaveSpec(List.of("x-1"), 1))), "批次号必须唯一");
    }
}
