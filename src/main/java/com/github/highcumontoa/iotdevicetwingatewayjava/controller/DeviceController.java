package com.github.highcumontoa.iotdevicetwingatewayjava.controller;

import com.github.highcumontoa.iotdevicetwingatewayjava.model.Campaign;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.Device;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.ShadowState;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.ShadowUpdateResult;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.TelemetryPoint;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.TelemetryResult;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.UpgradeTask;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.DeviceRegistry;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.FirmwareService;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.ShadowService;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.TelemetryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 设备接入 REST 入口。 */
@RestController
@RequestMapping("/api")
public class DeviceController {

    private final DeviceRegistry registry;
    private final ShadowService shadowService;
    private final TelemetryService telemetryService;
    private final FirmwareService firmwareService;

    public DeviceController(DeviceRegistry registry, ShadowService shadowService,
                            TelemetryService telemetryService, FirmwareService firmwareService) {
        this.registry = registry;
        this.shadowService = shadowService;
        this.telemetryService = telemetryService;
        this.firmwareService = firmwareService;
    }

    public record RegisterRequest(String deviceId, String firmwareVersion) {}
    public record HeartbeatRequest(long seq) {}
    public record ShadowRequest(long version, Map<String, Object> state, long timestamp) {}
    public record CampaignRequest(List<String> deviceIds, String targetVersion) {}
    public record FailRequest(String reason) {}

    @PostMapping("/devices")
    public Device register(@RequestBody RegisterRequest req) {
        return registry.register(req.deviceId(), req.firmwareVersion());
    }

    @GetMapping("/devices")
    public List<Device> list() {
        return registry.list();
    }

    @PostMapping("/devices/{id}/heartbeat")
    public ResponseEntity<ShadowUpdateResult> heartbeat(@PathVariable String id,
                                                        @RequestBody HeartbeatRequest req) {
        return ResponseEntity.ok(registry.heartbeat(id, req.seq()));
    }

    @PostMapping("/devices/{id}/offline")
    public ResponseEntity<Void> offline(@PathVariable String id) {
        registry.markOffline(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/devices/{id}/shadow")
    public ShadowUpdateResult updateShadow(@PathVariable String id,
                                           @RequestBody ShadowRequest req) {
        return shadowService.updateReported(id, req.version(), req.state(), req.timestamp());
    }

    @GetMapping("/devices/{id}/shadow")
    public ResponseEntity<ShadowState> getShadow(@PathVariable String id) {
        return shadowService.getShadow(id).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/devices/{id}/telemetry")
    public TelemetryResult ingest(@PathVariable String id, @RequestBody TelemetryPoint point) {
        return telemetryService.ingest(id, point);
    }

    @PostMapping("/devices/{id}/telemetry/flush")
    public List<TelemetryPoint> flush(@PathVariable String id) {
        return telemetryService.flushOffline(id);
    }

    @PostMapping("/campaigns")
    public Campaign startCampaign(@RequestBody CampaignRequest req) {
        return firmwareService.startCampaign(req.deviceIds(), req.targetVersion());
    }

    @GetMapping("/campaigns/{id}")
    public ResponseEntity<Campaign> getCampaign(@PathVariable String id) {
        return firmwareService.findCampaign(id).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/tasks/{id}/start")
    public ResponseEntity<Void> startTask(@PathVariable String id) {
        return firmwareService.markInProgress(id) ? ResponseEntity.ok().build()
                : ResponseEntity.status(409).build();
    }

    @PostMapping("/tasks/{id}/succeed")
    public ResponseEntity<Void> succeedTask(@PathVariable String id) {
        return firmwareService.markSucceeded(id) ? ResponseEntity.ok().build()
                : ResponseEntity.status(409).build();
    }

    @PostMapping("/tasks/{id}/fail")
    public ResponseEntity<Void> failTask(@PathVariable String id, @RequestBody FailRequest req) {
        return firmwareService.markFailed(id, req.reason()) ? ResponseEntity.ok().build()
                : ResponseEntity.status(409).build();
    }

    @PostMapping("/tasks/{id}/rollback")
    public ResponseEntity<Void> rollbackTask(@PathVariable String id) {
        return firmwareService.rollback(id) ? ResponseEntity.ok().build()
                : ResponseEntity.status(409).build();
    }

    @PostMapping("/tasks/sweep-timeouts")
    public Map<String, Integer> sweepTimeouts() {
        return Map.of("swept", firmwareService.sweepTimeouts());
    }

    @GetMapping("/tasks/{id}")
    public ResponseEntity<UpgradeTask> getTask(@PathVariable String id) {
        return firmwareService.findTask(id).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
