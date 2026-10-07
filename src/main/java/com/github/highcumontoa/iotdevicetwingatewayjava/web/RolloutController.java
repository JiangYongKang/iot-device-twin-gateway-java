package com.github.highcumontoa.iotdevicetwingatewayjava.web;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.highcumontoa.iotdevicetwingatewayjava.domain.RolloutDeviceOutcome;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.RolloutPlan;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.RolloutSummary;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.WaveSpec;
import com.github.highcumontoa.iotdevicetwingatewayjava.service.RolloutService;

/** 分批灰度发布的本地验证入口。 */
@RestController
@RequestMapping("/api/rollouts")
public class RolloutController {

    private final RolloutService rolloutService;

    public RolloutController(RolloutService rolloutService) {
        this.rolloutService = rolloutService;
    }

    /** {"rolloutId":"r1","targetVersion":"2.0.0","waves":[{"deviceIds":["d1"],"minSuccess":1}]} */
    public record CreateRolloutRequest(String rolloutId, String targetVersion, List<WaveSpec> waves) {}
    public record AbortRequest(String reason) {}
    public record ConfirmRequest(String version) {}
    public record FailureRequest(String reason) {}
    public record DeviceOutcomeView(String rolloutId, String deviceId, RolloutDeviceOutcome outcome) {}

    @PostMapping
    public RolloutPlan create(@RequestBody CreateRolloutRequest request) {
        return rolloutService.createRollout(request.rolloutId(), request.targetVersion(), request.waves());
    }

    @PostMapping("/{rolloutId}/start")
    public boolean start(@PathVariable String rolloutId) {
        return rolloutService.start(rolloutId);
    }

    @PostMapping("/{rolloutId}/abort")
    public boolean abort(@PathVariable String rolloutId, @RequestBody AbortRequest request) {
        return rolloutService.abort(rolloutId, request.reason());
    }

    @PostMapping("/sweep")
    public int sweep() {
        return rolloutService.sweepTimeouts();
    }

    @PostMapping("/devices/{deviceId}/confirm")
    public boolean confirm(@PathVariable String deviceId, @RequestBody ConfirmRequest request) {
        return rolloutService.confirmSuccess(deviceId, request.version());
    }

    @PostMapping("/devices/{deviceId}/fail")
    public boolean fail(@PathVariable String deviceId, @RequestBody FailureRequest request) {
        return rolloutService.reportFailure(deviceId, request.reason());
    }

    @GetMapping("/{rolloutId}")
    public RolloutPlan plan(@PathVariable String rolloutId) {
        return rolloutService.plan(rolloutId).orElseThrow();
    }

    @GetMapping("/{rolloutId}/summary")
    public RolloutSummary summary(@PathVariable String rolloutId) {
        return rolloutService.summary(rolloutId);
    }

    @GetMapping("/{rolloutId}/devices/{deviceId}")
    public ResponseEntity<DeviceOutcomeView> deviceOutcome(@PathVariable String rolloutId,
                                                           @PathVariable String deviceId) {
        return rolloutService.plan(rolloutId)
                .map(p -> ResponseEntity.ok(new DeviceOutcomeView(
                        rolloutId, deviceId, rolloutService.deviceOutcome(rolloutId, deviceId))))
                .orElse(ResponseEntity.notFound().build());
    }
}
