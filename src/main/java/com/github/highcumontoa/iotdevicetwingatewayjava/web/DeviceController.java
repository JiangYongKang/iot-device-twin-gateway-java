package com.github.highcumontoa.iotdevicetwingatewayjava.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.github.highcumontoa.iotdevicetwingatewayjava.domain.*;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.DeviceService;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.FirmwareService;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.TelemetryService;

/** 本地验证用 REST 入口。 */
@RestController
@RequestMapping("/api")
public class DeviceController {

    private final DeviceService deviceService;
    private final TelemetryService telemetryService;
    private final FirmwareService firmwareService;

    public DeviceController(DeviceService deviceService, TelemetryService telemetryService,
                            FirmwareService firmwareService) {
        this.deviceService = deviceService;
        this.telemetryService = telemetryService;
        this.firmwareService = firmwareService;
    }

    public record RegisterRequest(String deviceId, String firmwareVersion) {}
    public record HeartbeatRequest(long seq, Instant at) {}
    public record ShadowRequest(long version, Instant at, Map<String, Object> state) {}
    public record TelemetryRequest(String messageId, long seq, Instant occurredAt, Map<String, Object> payload) {}
    public record BatchRequest(String batchId, List<String> deviceIds, String targetVersion) {}
    public record ConfirmRequest(String version) {}
    public record FailureRequest(String reason) {}

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
}
