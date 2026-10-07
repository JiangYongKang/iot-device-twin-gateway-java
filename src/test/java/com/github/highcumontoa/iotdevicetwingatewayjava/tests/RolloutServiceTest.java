package com.github.highcumontoa.iotdevicetwingatewayjava.tests;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.highcumontoa.iotdevicetwingatewayjava.domain.RolloutDeviceOutcome;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.RolloutPlan;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.RolloutStatus;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.RolloutSummary;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.WaveSpec;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.WaveStatus;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.WaveSummary;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.DeviceService;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.FirmwareService;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.RolloutService;
import com.github.highcumontoa.iotdevicetwingatewayjava.support.MutableClock;

import static org.junit.jupiter.api.Assertions.*;

/** 分批灰度：波次顺序推进、待命不下发、达标自动推进/完成。 */
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
        rolloutService = new RolloutService(firmwareService);
    }

    private String online(String id, String version) {
        deviceService.register(id, version);
        deviceService.heartbeat(id, 1, T0);
        return id;
    }

    private RolloutPlan threeWaveRollout() {
        // wave1: w1-a,w1-b (minSuccess 2)；wave2: w2-a (minSuccess 1)；wave3: w3-a,w3-b (minSuccess 1)
        online("w1-a", "1.0.0");
        online("w1-b", "1.0.0");
        online("w2-a", "1.0.0");
        online("w3-a", "1.0.0");
        online("w3-b", "1.0.0");
        return rolloutService.createRollout("r1", "2.0.0", List.of(
                new WaveSpec(List.of("w1-a", "w1-b"), 2),
                new WaveSpec(List.of("w2-a"), 1),
                new WaveSpec(List.of("w3-a", "w3-b"), 1)));
    }

    @Test
    void pendingWavesStandByAndAreNotDispatchedAhead() {
        RolloutPlan plan = threeWaveRollout();
        RolloutSummary before = rolloutService.summary("r1");
        log.info("rollout=r1 case=before-start summary total={} waiting={} statuses={}",
                before.total(), before.waiting(),
                before.waves().stream().map(w -> w.index() + ":" + w.status()).toList());

        assertEquals(RolloutStatus.PENDING, plan.getStatus());
        assertEquals(5, before.waiting());
        assertEquals(0, before.inProgress());
        assertTrue(before.waves().stream().allMatch(w -> w.status() == WaveStatus.PENDING));
        // 待命设备没有任何升级任务，固件版本不变
        assertTrue(firmwareService.taskOf("w2-a").isEmpty());
        assertTrue(firmwareService.taskFor("r1", "w3-a").isEmpty());
        assertEquals("1.0.0", deviceService.find("w2-a").orElseThrow().getFirmwareVersion());

        rolloutService.start("r1");
        RolloutSummary afterStart = rolloutService.summary("r1");
        log.info("rollout=r1 case=after-start inProgress={} waiting={} waveStatuses={}",
                afterStart.inProgress(), afterStart.waiting(),
                afterStart.waves().stream().map(w -> w.index() + ":" + w.status()).toList());

        assertEquals(RolloutStatus.IN_PROGRESS, plan.getStatus());
        assertEquals(2, afterStart.inProgress(), "只有第一波设备被下发");
        assertEquals(3, afterStart.waiting(), "后续波次设备继续待命");
        assertEquals(WaveStatus.IN_PROGRESS, afterStart.waves().get(0).status());
        assertEquals(WaveStatus.PENDING, afterStart.waves().get(1).status());
        assertEquals(WaveStatus.PENDING, afterStart.waves().get(2).status());
        assertTrue(firmwareService.taskFor("r1", "w2-a").isEmpty(), "第二波绝不能提前下发");

        // 第一波只成功一台，未达标前不得推进第二波
        assertTrue(rolloutService.confirmSuccess("w1-a", "2.0.0"));
        RolloutSummary mid = rolloutService.summary("r1");
        assertEquals(WaveStatus.IN_PROGRESS, mid.waves().get(0).status());
        assertEquals(WaveStatus.PENDING, mid.waves().get(1).status(), "第一波未收口，第二波仍待命");
        assertEquals(1, mid.success());
        assertEquals(1, mid.inProgress());
        assertEquals(3, mid.waiting());
        log.info("rollout=r1 case=wave1-half success={} inProgress={} waiting={}",
                mid.success(), mid.inProgress(), mid.waiting());
    }

    @Test
    void passingWavesAdvanceSequentiallyAndCompleteBatch() {
        threeWaveRollout();
        rolloutService.start("r1");

        rolloutService.confirmSuccess("w1-a", "2.0.0");
        rolloutService.confirmSuccess("w1-b", "2.0.0");
        RolloutSummary afterW1 = rolloutService.summary("r1");
        assertEquals(WaveStatus.PASSED, afterW1.waves().get(0).status());
        assertEquals(WaveStatus.IN_PROGRESS, afterW1.waves().get(1).status(), "达标后自动推进第二波");
        assertEquals(WaveStatus.PENDING, afterW1.waves().get(2).status());
        assertEquals(RolloutDeviceOutcome.IN_PROGRESS, rolloutService.deviceOutcome("r1", "w2-a"));
        assertEquals(RolloutDeviceOutcome.WAITING, rolloutService.deviceOutcome("r1", "w3-a"));
        log.info("rollout=r1 case=wave1-passed waveStatuses={}",
                afterW1.waves().stream().map(w -> w.index() + ":" + w.status()).toList());

        // 第二波成功（minSuccess=1），自动推进第三波
        rolloutService.confirmSuccess("w2-a", "2.0.0");
        RolloutSummary afterW2 = rolloutService.summary("r1");
        assertEquals(WaveStatus.PASSED, afterW2.waves().get(1).status());
        assertEquals(WaveStatus.IN_PROGRESS, afterW2.waves().get(2).status());

        // 第三波只需 1 台成功即达标；另一台仍在途，但波次满足通过条件后按“全部出结论才收口”
        rolloutService.confirmSuccess("w3-a", "2.0.0");
        RolloutSummary beforeLast = rolloutService.summary("r1");
        assertEquals(WaveStatus.IN_PROGRESS, beforeLast.waves().get(2).status(), "还有设备在途，本波不能提前收口");
        assertEquals(RolloutStatus.IN_PROGRESS, rolloutService.plan("r1").orElseThrow().getStatus());

        rolloutService.confirmSuccess("w3-b", "2.0.0");
        RolloutSummary done = rolloutService.summary("r1");
        log.info("rollout=r1 case=completed status={} success={}/5 waveStatuses={}",
                done.status(), done.success(),
                done.waves().stream().map(w -> w.index() + ":" + w.status()).toList());
        assertEquals(RolloutStatus.COMPLETED, done.status());
        assertEquals(5, done.success());
        assertTrue(done.waves().stream().allMatch(w -> w.status() == WaveStatus.PASSED));
    }

    @Test
    void waveBelowThresholdHaltsBatchAndRollsBackInFlightDevices() {
        // wave1: 两台，minSuccess=2；wave2: 两台，minSuccess=2；wave3: 一台
        online("h1-a", "1.0.0");
        online("h1-b", "1.0.0");
        online("h2-a", "1.0.0");
        online("h2-b", "1.0.0");
        online("h3-a", "1.0.0");
        rolloutService.createRollout("rh", "2.0.0", List.of(
                new WaveSpec(List.of("h1-a", "h1-b"), 2),
                new WaveSpec(List.of("h2-a", "h2-b"), 2),
                new WaveSpec(List.of("h3-a"), 1)));
        rolloutService.start("rh");
        rolloutService.confirmSuccess("h1-a", "2.0.0");
        rolloutService.confirmSuccess("h1-b", "2.0.0");

        // 第二波：一台设备自身失败（回滚），一台仍在途；此时成功=0 < 2，要等在途收口
        assertTrue(rolloutService.reportFailure("h2-a", "checksum mismatch"));
        assertEquals(RolloutStatus.IN_PROGRESS, rolloutService.plan("rh").orElseThrow().getStatus(),
                "还有在途设备，不能提前判定");
        assertEquals(RolloutDeviceOutcome.FAILED, rolloutService.deviceOutcome("rh", "h2-a"));
        assertEquals("1.0.0", deviceService.find("h2-a").orElseThrow().getFirmwareVersion(),
                "设备自身失败必须回滚到升级前版本");

        // 第二波最后一台也失败 -> 成功 0 < 2 -> 整波 REJECTED、整批叫停
        assertTrue(rolloutService.reportFailure("h2-b", "boot loop"));
        RolloutSummary halt = rolloutService.summary("rh");
        log.info("rollout=rh case=rejected status={} success={} failed={} aborted={} waveStatuses={}",
                halt.status(), halt.success(), halt.failed(), halt.aborted(),
                halt.waves().stream().map(w -> w.index() + ":" + w.status()).toList());

        assertEquals(RolloutStatus.REJECTED, halt.status());
        assertEquals(WaveStatus.PASSED, halt.waves().get(0).status(), "已成功波次结果必须原样保留");
        assertEquals(WaveStatus.REJECTED, halt.waves().get(1).status());
        assertEquals(WaveStatus.CANCELLED, halt.waves().get(2).status(), "后续波次取消，不再下发");
        assertEquals(2, halt.success());
        assertEquals(2, halt.failed(), "设备自身失败计入 failed");
        assertEquals(1, halt.aborted(), "待命设备被叫停计入 aborted");
        assertEquals(RolloutDeviceOutcome.ABORTED, rolloutService.deviceOutcome("rh", "h3-a"),
                "叫停结论必须能与设备失败、前置隔离区分");
        assertEquals(RolloutDeviceOutcome.FAILED, rolloutService.deviceOutcome("rh", "h2-a"));
        assertEquals("1.0.0", deviceService.find("h3-a").orElseThrow().getFirmwareVersion(), "待命设备从未升级");
        // 叫停后再确认无效，批次不会继续推进
        assertFalse(rolloutService.confirmSuccess("h3-a", "2.0.0"));
    }

    @Test
    void manualAbortRollsBackInFlightAndCancelsPendingWavesButKeepsPassedWaves() {
        online("m1-a", "1.0.0");
        online("m2-a", "1.0.0");
        online("m2-b", "1.0.0");
        online("m3-a", "1.0.0");
        rolloutService.createRollout("rm", "2.0.0", List.of(
                new WaveSpec(List.of("m1-a"), 1),
                new WaveSpec(List.of("m2-a", "m2-b"), 2),
                new WaveSpec(List.of("m3-a"), 1)));
        rolloutService.start("rm");
        rolloutService.confirmSuccess("m1-a", "2.0.0"); // 第一波通过，第二波下发

        assertTrue(rolloutService.abort("rm", "operator kill switch"));
        RolloutSummary s = rolloutService.summary("rm");
        log.info("rollout=rm case=manual-abort status={} success={} aborted={} inProgress={} waiting={} waves={}",
                s.status(), s.success(), s.aborted(), s.inProgress(), s.waiting(),
                s.waves().stream().map(w -> w.index() + ":" + w.status()).toList());

        assertEquals(RolloutStatus.ABORTED, s.status());
        assertEquals(WaveStatus.PASSED, s.waves().get(0).status(), "已成功波次原样保留");
        assertEquals(WaveStatus.CANCELLED, s.waves().get(1).status());
        assertEquals(WaveStatus.CANCELLED, s.waves().get(2).status());
        assertEquals(1, s.success());
        assertEquals(3, s.aborted(), "第二波在途两台回滚 + 第三波待命一台取消");
        assertEquals(0, s.inProgress());
        assertEquals(0, s.waiting(), "叫停后不存在进行中或待命设备");
        assertEquals("2.0.0", deviceService.find("m1-a").orElseThrow().getFirmwareVersion(), "成功设备保持目标版本");
        assertEquals("1.0.0", deviceService.find("m2-a").orElseThrow().getFirmwareVersion(), "在途叫停设备回滚");
        assertEquals("1.0.0", deviceService.find("m2-b").orElseThrow().getFirmwareVersion());
        assertEquals("1.0.0", deviceService.find("m3-a").orElseThrow().getFirmwareVersion());
        assertEquals(RolloutDeviceOutcome.ABORTED, rolloutService.deviceOutcome("rm", "m2-a"));
        assertEquals(RolloutDeviceOutcome.SUCCESS, rolloutService.deviceOutcome("rm", "m1-a"));
        // 叫停是终态，重复叫停与迟到确认均不生效
        assertFalse(rolloutService.abort("rm", "again"));
        assertFalse(rolloutService.confirmSuccess("m2-a", "2.0.0"));
    }

    @Test
    void timedOutDeviceIsCountedAsTimeoutAndCanRejectWave() {
        online("t1-a", "1.0.0");
        online("t1-b", "1.0.0");
        rolloutService.createRollout("rt", "2.0.0", List.of(
                new WaveSpec(List.of("t1-a", "t1-b"), 2)));
        rolloutService.start("rt");
        rolloutService.confirmSuccess("t1-a", "2.0.0");

        clock.advance(Duration.ofSeconds(31));
        int swept = rolloutService.sweepTimeouts();
        RolloutSummary s = rolloutService.summary("rt");
        log.info("rollout=rt case=timeout swept={} status={} success={} timeout={} 判定依据: 超过30s未确认, success=1<minSuccess=2",
                swept, s.status(), s.success(), s.timeout());

        assertEquals(1, swept);
        assertEquals(1, s.timeout(), "超时单独计数，口径与失败、叫停区分");
        assertEquals(RolloutDeviceOutcome.TIMEOUT, rolloutService.deviceOutcome("rt", "t1-b"));
        assertEquals("1.0.0", deviceService.find("t1-b").orElseThrow().getFirmwareVersion(), "超时回滚");
        assertEquals(RolloutStatus.REJECTED, s.status(), "成功 1 < 阈值 2，整批叫停");
        assertEquals(WaveStatus.REJECTED, s.waves().get(0).status());
        assertEquals("2.0.0", deviceService.find("t1-a").orElseThrow().getFirmwareVersion(), "成功设备不受叫停影响");
    }

    @Test
    void isolatedDevicesCountSeparatelyAndDoNotBlockOthers() {
        online("i-ok", "1.0.0");
        online("i-same", "2.0.0"); // 已在目标版本 -> 隔离
        deviceService.register("i-off", "1.0.0"); // 离线 -> 隔离
        // minSuccess=1，单波 3 台
        rolloutService.createRollout("ri", "2.0.0", List.of(
                new WaveSpec(List.of("i-ok", "i-same", "i-off"), 1)));
        rolloutService.start("ri");
        rolloutService.confirmSuccess("i-ok", "2.0.0");

        RolloutSummary s = rolloutService.summary("ri");
        log.info("rollout=ri case=isolated status={} success={} isolated={}", s.status(), s.success(), s.isolated());
        assertEquals(RolloutStatus.COMPLETED, s.status(), "隔离不影响达标（只要求 1 台成功）");
        assertEquals(1, s.success());
        assertEquals(2, s.isolated());
        assertEquals(RolloutDeviceOutcome.ISOLATED, rolloutService.deviceOutcome("ri", "i-same"));
        assertEquals(RolloutDeviceOutcome.ISOLATED, rolloutService.deviceOutcome("ri", "i-off"));
        assertEquals("1.0.0", deviceService.find("i-off").orElseThrow().getFirmwareVersion());
    }

    @Test
    void summaryBucketsAreDisjointAndAlwaysSumToTotal() {
        online("p1", "1.0.0");
        online("p2", "1.0.0");
        online("p3", "1.0.0");
        online("p4", "1.0.0");
        rolloutService.createRollout("rp", "2.0.0", List.of(
                new WaveSpec(List.of("p1", "p2"), 2),
                new WaveSpec(List.of("p3", "p4"), 2)));
        rolloutService.start("rp");
        assertConservation();
        rolloutService.confirmSuccess("p1", "2.0.0");
        assertConservation();
        rolloutService.reportFailure("p2", "bad image");
        assertConservation(); // 第一波 1 成功 < 2 -> 叫停；p3/p4 待命转 aborted
        RolloutSummary s = rolloutService.summary("rp");
        assertEquals(RolloutStatus.REJECTED, s.status());
        assertEquals(1, s.success());
        assertEquals(1, s.failed());
        assertEquals(2, s.aborted());
        log.info("rollout=rp case=conservation success={} failed={} aborted={} total={}",
                s.success(), s.failed(), s.aborted(), s.total());
    }

    private void assertConservation() {
        RolloutSummary s = rolloutService.summary("rp");
        int sum = s.success() + s.failed() + s.timeout() + s.isolated()
                + s.aborted() + s.inProgress() + s.waiting();
        assertEquals(s.total(), sum, "各口径计数之和必须等于设备总数，不重不漏");
        int waveSum = s.waves().stream().mapToInt(w -> w.success() + w.failed() + w.timeout()
                + w.isolated() + w.aborted() + w.inProgress() + w.waiting()).sum();
        assertEquals(s.total(), waveSum, "各波计数之和必须等于整批总数");
        int waveTotals = s.waves().stream().mapToInt(WaveSummary::total).sum();
        assertEquals(s.total(), waveTotals);
    }

    @Test
    void largeBatchOfHundredsOfDevicesAdvancesAndSummarizesConsistently() {
        int waves = 5;
        int perWave = 40; // 共 200 台
        List<WaveSpec> specs = new java.util.ArrayList<>();
        for (int w = 0; w < waves; w++) {
            List<String> ids = new java.util.ArrayList<>();
            for (int d = 0; d < perWave; d++) {
                String id = "big-" + w + "-" + d;
                online(id, "1.0.0");
                ids.add(id);
            }
            specs.add(new WaveSpec(ids, perWave)); // 全部成功才达标
        }
        rolloutService.createRollout("rbig", "2.0.0", specs);
        rolloutService.start("rbig");

        for (int w = 0; w < waves; w++) {
            for (int d = 0; d < perWave; d++) {
                String id = "big-" + w + "-" + d;
                assertEquals(RolloutDeviceOutcome.IN_PROGRESS, rolloutService.deviceOutcome("rbig", id));
                assertTrue(rolloutService.confirmSuccess(id, "2.0.0"));
            }
            RolloutSummary s = rolloutService.summary("rbig");
            int expectedInProgress = w == waves - 1 ? 0 : perWave;
            int expectedWaiting = (waves - w - 2) * perWave < 0 ? 0 : (waves - w - 2) * perWave;
            assertEquals((w + 1) * perWave, s.success());
            assertEquals(expectedInProgress, s.inProgress(), "仅当前波在途");
            assertEquals(Math.max(0, expectedWaiting), s.waiting());
            int sum = s.success() + s.failed() + s.timeout() + s.isolated()
                    + s.aborted() + s.inProgress() + s.waiting();
            assertEquals(waves * perWave, sum, "上百台规模下计数仍守恒");
        }
        RolloutSummary done = rolloutService.summary("rbig");
        assertEquals(RolloutStatus.COMPLETED, done.status());
        assertEquals(waves * perWave, done.success());
        assertEquals(0, done.inProgress() + done.waiting());
        log.info("rollout=rbig case=large devices={} success={} status={}",
                done.total(), done.success(), done.status());
    }

    @Test
    void concurrentStartConfirmAndAbortYieldsStableDeterministicOutcomes() throws Exception {
        int n = 20;
        List<String> wave1 = new java.util.ArrayList<>();
        List<String> wave2 = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            String a = "c1-" + i;
            String b = "c2-" + i;
            online(a, "1.0.0");
            online(b, "1.0.0");
            wave1.add(a);
            wave2.add(b);
        }
        rolloutService.createRollout("rc", "2.0.0", List.of(
                new WaveSpec(wave1, n), new WaveSpec(wave2, n)));

        int threads = 8;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger starts = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean abortDone = new java.util.concurrent.atomic.AtomicBoolean();

        // 多线程并发 start，只有一个能生效
        for (int t = 0; t < 4; t++) {
            pool.submit(() -> {
                try {
                    gate.await();
                    if (rolloutService.start("rc")) {
                        starts.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        // 多线程并发确认第一波设备；每台设备由其专属线程确认，但与叫停/启动并发交错
        for (int i = 0; i < n; i++) {
            final String id = wave1.get(i);
            pool.submit(() -> {
                try {
                    gate.await();
                    Thread.yield();
                    rolloutService.confirmSuccess(id, "2.0.0");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        // 一个线程在交错过程中尝试叫停（可能在第一波进行中或刚好通过后）
        pool.submit(() -> {
            try {
                gate.await();
                Thread.yield();
                if (rolloutService.abort("rc", "concurrent kill switch")) {
                    abortDone.set(true);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        gate.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));

        RolloutSummary s = rolloutService.summary("rc");
        int sum = s.success() + s.failed() + s.timeout() + s.isolated()
                + s.aborted() + s.inProgress() + s.waiting();
        log.info("rollout=rc case=concurrent starts={} aborted={} status={} success={} abortedCount={} inProgress={} waiting={} sum={}",
                starts.get(), abortDone.get(), s.status(), s.success(), s.aborted(), s.inProgress(), s.waiting(), sum);
        assertEquals(1, starts.get(), "并发 start 只能生效一次，不能重复下发第一波");
        assertEquals(2 * n, sum, "并发交错下每台设备仍只被计入一个口径，不重不漏");
        assertEquals(0, s.inProgress() + s.waiting(), "终态批次不存在在途/待命设备");
        if (s.status() == RolloutStatus.ABORTED) {
            // 叫停生效：成功设备保留，其余全部 aborted
            assertTrue(abortDone.get());
            assertEquals(2 * n, s.success() + s.aborted());
            for (String id : wave1) {
                RolloutDeviceOutcome o = rolloutService.deviceOutcome("rc", id);
                assertTrue(o == RolloutDeviceOutcome.SUCCESS || o == RolloutDeviceOutcome.ABORTED);
            }
        } else {
            assertEquals(RolloutStatus.COMPLETED, s.status());
            assertEquals(2 * n, s.success());
        }
    }

    @Test
    void sameDeviceCannotBelongToTwoConcurrentRollouts() throws Exception {
        online("shared", "1.0.0");
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        java.util.List<String> outcomes = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        for (String rid : List.of("rx", "ry")) {
            pool.submit(() -> {
                try {
                    gate.await();
                    rolloutService.createRollout(rid, "2.0.0",
                            List.of(new WaveSpec(List.of("shared"), 1)));
                    outcomes.add("ok-" + rid);
                } catch (IllegalStateException e) {
                    outcomes.add("rejected-" + rid);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        gate.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        long ok = outcomes.stream().filter(o -> o.startsWith("ok")).count();
        long rejected = outcomes.stream().filter(o -> o.startsWith("rejected")).count();
        log.info("case=cross-rollout-device-guard outcomes={} ok={} rejected={} 判定依据: 全局创建锁+占用表",
                outcomes, ok, rejected);
        assertEquals(1, ok, "同一设备只能被一个进行中批次占用");
        assertEquals(1, rejected);
    }

    @Test
    void deviceReleasedAfterRolloutFinishesCanJoinNewRollout() {
        online("reuse-1", "1.0.0");
        rolloutService.createRollout("rr1", "2.0.0", List.of(new WaveSpec(List.of("reuse-1"), 1)));
        rolloutService.start("rr1");
        rolloutService.confirmSuccess("reuse-1", "2.0.0");
        assertEquals(RolloutStatus.COMPLETED, rolloutService.plan("rr1").orElseThrow().getStatus());
        // 上一批已完成，设备已在 2.0.0；再建一个目标 3.0.0 的批次必须被允许
        rolloutService.createRollout("rr2", "3.0.0", List.of(new WaveSpec(List.of("reuse-1"), 1)));
        rolloutService.start("rr2");
        assertEquals(RolloutDeviceOutcome.IN_PROGRESS, rolloutService.deviceOutcome("rr2", "reuse-1"));
        rolloutService.confirmSuccess("reuse-1", "3.0.0");
        assertEquals("3.0.0", deviceService.find("reuse-1").orElseThrow().getFirmwareVersion());
        log.info("case=device-reuse-after-complete device=reuse-1 previous=COMPLETED next=3.0.0 outcome=allowed");
    }

    @Test
    void abortAfterDeadlineClassifiesExpiredDeviceAsTimeoutNotAborted() {
        online("d-ok", "1.0.0");
        online("d-late", "1.0.0");
        rolloutService.createRollout("rd", "2.0.0", List.of(
                new WaveSpec(List.of("d-ok", "d-late"), 2)));
        rolloutService.start("rd");
        rolloutService.confirmSuccess("d-ok", "2.0.0");
        // 不主动扫超时，直接把时间推过 deadline 后人工叫停
        clock.advance(Duration.ofSeconds(31));
        // 叫停前先补扫超时：成功=1<2 会先自动判定 REJECTED（终态），人工 abort 尊重该自动结论并返回 false
        boolean aborted = rolloutService.abort("rd", "operator stop after deadline");
        assertFalse(aborted, "补扫已使批次进入 REJECTED 终态，人工叫停不再重复执行");

        RolloutSummary s = rolloutService.summary("rd");
        log.info("rollout=rd case=abort-after-deadline status={} success={} timeout={} aborted={} 判定依据: 叫停前先补扫超时",
                s.status(), s.success(), s.timeout(), s.aborted());
        assertEquals(RolloutDeviceOutcome.TIMEOUT, rolloutService.deviceOutcome("rd", "d-late"),
                "已过截止时间的设备应算超时，而不是被错算成叫停");
        assertEquals(RolloutDeviceOutcome.SUCCESS, rolloutService.deviceOutcome("rd", "d-ok"));
        assertEquals(1, s.timeout());
        assertEquals(0, s.aborted(), "本波两台此时都有结论，没有待命设备被取消");
        // 补扫超时使成功=1<2，自动判定即为 REJECTED；人工 abort 尊重该终态
        assertEquals(RolloutStatus.REJECTED, s.status());
    }

    @Test
    void abortAfterDeadlineStillAbortsWhenWaveWouldPassAndKeepsTimeoutBucketing() {
        // 两台设备，minSuccess=1；第一波成功，第二台超时但无人扫，随后还有一个待命波
        online("e-ok", "1.0.0");
        online("e-late", "1.0.0");
        online("e-next", "1.0.0");
        rolloutService.createRollout("re", "2.0.0", List.of(
                new WaveSpec(List.of("e-ok", "e-late"), 1),
                new WaveSpec(List.of("e-next"), 1)));
        rolloutService.start("re");
        rolloutService.confirmSuccess("e-ok", "2.0.0"); // 已达标但 e-late 在途，波次不收口
        clock.advance(Duration.ofSeconds(31));

        assertTrue(rolloutService.abort("re", "stop while expired device in flight"));
        RolloutSummary s = rolloutService.summary("re");
        log.info("rollout=re case=abort-deadline-pass status={} success={} timeout={} aborted={}",
                s.status(), s.success(), s.timeout(), s.aborted());
        assertEquals(RolloutStatus.ABORTED, s.status(), "成功已达标，补扫不会自动收口判定，人工叫停生效");
        assertEquals(RolloutDeviceOutcome.TIMEOUT, rolloutService.deviceOutcome("re", "e-late"),
                "过 deadline 设备按超时口径并已回滚");
        assertEquals("1.0.0", deviceService.find("e-late").orElseThrow().getFirmwareVersion());
        assertEquals(RolloutDeviceOutcome.SUCCESS, rolloutService.deviceOutcome("re", "e-ok"));
        assertEquals(RolloutDeviceOutcome.ABORTED, rolloutService.deviceOutcome("re", "e-next"),
                "待命波次设备被取消");
        assertEquals(1, s.timeout());
        assertEquals(1, s.aborted());
    }
}
