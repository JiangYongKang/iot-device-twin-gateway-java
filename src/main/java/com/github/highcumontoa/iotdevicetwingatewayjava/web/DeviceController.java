package com.github.highcumontoa.iotdevicetwingatewayjava.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.github.highcumontoa.iotdevicetwingatewayjava.domain.*;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.DeviceService;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.FirmwareService;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.RolloutService;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.TelemetryService;

/** 本地验证用 REST 入口。 */
@RestController
@RequestMapping("/api")
public class DeviceController {

    private final DeviceService deviceService;
    private final TelemetryService telemetryService;
    private final FirmwareService firmwareService;
    private final RolloutService rolloutService;

    public DeviceController(DeviceService deviceService, TelemetryService telemetryService,
                            FirmwareService firmwareService, RolloutService rolloutService) {
        this.deviceService = deviceService;
        this.telemetryService = telemetryService;
        this.firmwareService = firmwareService;
        this.rolloutService = rolloutService;
    }

    public record RegisterRequest(String deviceId, String firmwareVersion) {}
    public record HeartbeatRequest(long seq, Instant at) {}
    public record ShadowRequest(long version, Instant at, Map<String, Object> state) {}
    public record TelemetryRequest(String messageId, long seq, Instant occurredAt, Map<String, Object> payload) {}
    public record BatchRequest(String batchId, List<String> deviceIds, String targetVersion) {}
    public record ConfirmRequest(String version) {}
    public record FailureRequest(String reason) {}
    public record WaveRequest(List<String> deviceIds, int requiredSuccesses) {}
    public record CreateRolloutRequest(String batchId, String targetVersion, List<WaveRequest> waves) {}
    public record RolloutConfirmRequest(String batchId, String version) {}
    public record RolloutFailureRequest(String batchId, String reason) {}
    public record AbortRequest(String reason) {}

    @PostMapping("/devices")
    public Device register(@RequestBody RegisterRequest request) {
        return deviceService.register(request.deviceId(), request.firmwareVersion());
    }

    @GetMapping("/devices/{deviceId}")
    public ResponseEntity<Device> get(@PathVariable String deviceId) {
        return deviceService.find(deviceId).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/devices/{deviceId}/heartbeat")
    public ShadowUpdateResult heartbeat(@PathVariable String deviceId, @RequestBody HeartbeatRequest request) {
        return deviceService.heartbeat(deviceId, request.seq(), request.at());
    }

    @PostMapping("/devices/{deviceId}/shadow/reported")
    public ShadowUpdateResult reported(@PathVariable String deviceId, @RequestBody ShadowRequest request) {
        return deviceService.updateReported(deviceId, request.version(), request.at(), request.state());
    }

    @PostMapping("/devices/{deviceId}/shadow/desired")
    public ShadowUpdateResult desired(@PathVariable String deviceId, @RequestBody ShadowRequest request) {
        return deviceService.updateDesired(deviceId, request.version(), request.state());
    }

    @PostMapping("/devices/{deviceId}/offline")
    public void offline(@PathVariable String deviceId) {
        deviceService.markOffline(deviceId);
    }

    @PostMapping("/devices/{deviceId}/telemetry")
    public TelemetryResult telemetry(@PathVariable String deviceId, @RequestBody TelemetryRequest request) {
        return telemetryService.ingest(new TelemetryMessage(request.messageId(), deviceId, request.seq(),
                request.occurredAt(), request.payload()));
    }

    @PostMapping("/devices/{deviceId}/telemetry/flush")
    public List<TelemetryResult> flush(@PathVariable String deviceId) {
        return telemetryService.flushOffline(deviceId);
    }

    @PostMapping("/upgrades")
    public List<UpgradeTask> startBatch(@RequestBody BatchRequest request) {
        return firmwareService.startBatch(request.batchId(), request.deviceIds(), request.targetVersion());
    }

    @PostMapping("/devices/{deviceId}/upgrade/confirm")
    public boolean confirm(@PathVariable String deviceId, @RequestBody ConfirmRequest request) {
        return firmwareService.confirmSuccess(deviceId, request.version());
    }

    @PostMapping("/devices/{deviceId}/upgrade/fail")
    public boolean fail(@PathVariable String deviceId, @RequestBody FailureRequest request) {
        return firmwareService.reportFailure(deviceId, request.reason());
    }

    @PostMapping("/upgrades/sweep")
    public List<UpgradeTask> sweep() {
        return firmwareService.sweepTimeouts();
    }

    // ---------------- 分批灰度发布 ----------------

    @PostMapping("/rollouts")
    public RolloutBatch createRollout(@RequestBody CreateRolloutRequest request) {
        List<WaveSpec> specs = request.waves().stream()
                .map(w -> new WaveSpec(w.deviceIds(), w.requiredSuccesses()))
                .toList();
        return rolloutService.createRollout(request.batchId(), request.targetVersion(), specs);
    }

    @PostMapping("/rollouts/{batchId}/launch")
    public ResponseEntity<RolloutWave> launchWave(@PathVariable String batchId) {
        return rolloutService.launchNextWave(batchId).map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(409).build());
    }

    @GetMapping("/rollouts/{batchId}")
    public ResponseEntity<RolloutBatch> getRollout(@PathVariable String batchId) {
        return rolloutService.getBatch(batchId).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/rollouts/{batchId}/summary")
    public RolloutSummary rolloutSummary(@PathVariable String batchId) {
        return rolloutService.summary(batchId);
    }

    @PostMapping("/rollouts/{batchId}/devices/{deviceId}/confirm")
    public ResponseEntity<Boolean> rolloutConfirm(@PathVariable String batchId, @PathVariable String deviceId,
                                                  @RequestBody RolloutConfirmRequest request) {
        boolean accepted = rolloutService.confirmSuccess(batchId, deviceId, request.version());
        return accepted ? ResponseEntity.ok(true) : ResponseEntity.status(409).body(false);
    }

    @PostMapping("/rollouts/{batchId}/devices/{deviceId}/fail")
    public ResponseEntity<Boolean> rolloutFail(@PathVariable String batchId, @PathVariable String deviceId,
                                               @RequestBody RolloutFailureRequest request) {
        boolean accepted = rolloutService.reportFailure(batchId, deviceId, request.reason());
        return accepted ? ResponseEntity.ok(true) : ResponseEntity.status(409).body(false);
    }

    @PostMapping("/rollouts/{batchId}/sweep")
    public List<UpgradeTask> rolloutSweep(@PathVariable String batchId) {
        return rolloutService.sweepTimeouts(batchId);
    }

    @PostMapping("/rollouts/{batchId}/abort")
    public List<UpgradeTask> rolloutAbort(@PathVariable String batchId, @RequestBody(required = false) AbortRequest request) {
        return rolloutService.abort(batchId, request == null ? "manual abort" : request.reason());
    }
}
