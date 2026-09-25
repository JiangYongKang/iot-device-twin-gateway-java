package com.github.highcumontoa.iotdevicetwingatewayjava.service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.github.highcumontoa.iotdevicetwingatewayjava.domain.Device;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.DeviceState;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.ShadowUpdateResult;
import com.github.highcumontoa.iotdevicetwingatewayjava.domain.UpdateOutcome;
import com.github.highcumontoa.iotdevicetwingatewayjava.support.MutableClock;

/**
 * 设备注册、心跳与影子状态服务。
 *
 * 单调性规则：reported 影子按 (version, at) 字典序推进，版本相同则要求时间戳更新；
 * 完全相同的 (version, at) 视为重复上报幂等忽略；更旧的视为乱序/过期拒绝，状态不回退。
 * 每个设备一把锁，保证并发上报下判定与写入原子，查询结果单调一致。
 */
@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final Map<String, Device> devices = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final MutableClock clock;

    public DeviceService(MutableClock clock) {
        this.clock = clock;
    }

    public Device register(String deviceId, String firmwareVersion) {
        Device device = new Device(deviceId, firmwareVersion);
        Device existing = devices.putIfAbsent(deviceId, device);
        locks.putIfAbsent(deviceId, new Object());
        Device result = existing == null ? device : existing;
        log.info("device={} action=register firmware={} reused={}", deviceId, firmwareVersion, existing != null);
        return result;
    }

    public Optional<Device> find(String deviceId) {
        return Optional.ofNullable(devices.get(deviceId));
    }

    public ShadowUpdateResult heartbeat(String deviceId, long seq, Instant at) {
        Device device = devices.get(deviceId);
        if (device == null) {
            return ShadowUpdateResult.of(UpdateOutcome.REJECTED, -1, null, "unknown device");
        }
        synchronized (lockOf(deviceId)) {
            if (seq < device.getLastHeartbeatSeq()
                    || (seq == device.getLastHeartbeatSeq() && device.getLastHeartbeatAt() != null)) {
                UpdateOutcome outcome = seq == device.getLastHeartbeatSeq() ? UpdateOutcome.DUPLICATE : UpdateOutcome.STALE;
                log.info("device={} action=heartbeat seq={} outcome={} lastSeq={}", deviceId, seq, outcome, device.getLastHeartbeatSeq());
                return ShadowUpdateResult.of(outcome, device.getLastHeartbeatSeq(), device.getLastHeartbeatAt(),
                        "heartbeat seq not advancing");
            }
            device.setLastHeartbeatSeq(seq);
            device.setLastHeartbeatAt(at);
            device.setState(DeviceState.ONLINE);
            log.info("device={} action=heartbeat seq={} outcome=APPLIED at={}", deviceId, seq, at);
            return ShadowUpdateResult.of(UpdateOutcome.APPLIED, seq, at, "heartbeat accepted");
        }
    }

    public ShadowUpdateResult updateReported(String deviceId, long version, Instant at, Map<String, Object> state) {
        Device device = devices.get(deviceId);
        if (device == null) {
            return ShadowUpdateResult.of(UpdateOutcome.REJECTED, -1, null, "unknown device");
        }
        synchronized (lockOf(deviceId)) {
            long curVersion = device.getReportedVersion();
            Instant curAt = device.getReportedAt();
            if (version < curVersion || (version == curVersion && curAt != null && at.isBefore(curAt))) {
                log.info("device={} action=reported version={} at={} outcome=STALE current=({}, {})",
                        deviceId, version, at, curVersion, curAt);
                return ShadowUpdateResult.of(UpdateOutcome.STALE, curVersion, curAt,
                        "incoming (version,at) older than confirmed");
            }
            if (version == curVersion && curAt != null && at.equals(curAt)) {
                log.info("device={} action=reported version={} at={} outcome=DUPLICATE", deviceId, version, at);
                return ShadowUpdateResult.of(UpdateOutcome.DUPLICATE, curVersion, curAt, "identical report ignored");
            }
            device.getReported().clear();
            device.getReported().putAll(state);
            device.setReportedVersion(version);
            device.setReportedAt(at);
            log.info("device={} action=reported version={} at={} outcome=APPLIED keys={}",
                    deviceId, version, at, state.keySet());
            return ShadowUpdateResult.of(UpdateOutcome.APPLIED, version, at, "reported state advanced");
        }
    }

    public ShadowUpdateResult updateDesired(String deviceId, long version, Map<String, Object> state) {
        Device device = devices.get(deviceId);
        if (device == null) {
            return ShadowUpdateResult.of(UpdateOutcome.REJECTED, -1, null, "unknown device");
        }
        synchronized (lockOf(deviceId)) {
            if (version < device.getDesiredVersion()) {
                return ShadowUpdateResult.of(UpdateOutcome.STALE, device.getDesiredVersion(), null,
                        "desired version older than current");
            }
            if (version == device.getDesiredVersion() && device.getDesiredVersion() != 0) {
                return ShadowUpdateResult.of(UpdateOutcome.DUPLICATE, device.getDesiredVersion(), null,
                        "identical desired version ignored");
            }
            device.getDesired().clear();
            device.getDesired().putAll(state);
            device.setDesiredVersion(version);
            log.info("device={} action=desired version={} outcome=APPLIED", deviceId, version);
            return ShadowUpdateResult.of(UpdateOutcome.APPLIED, version, clock.now(), "desired state advanced");
        }
    }

    public void markOffline(String deviceId) {
        Device device = devices.get(deviceId);
        if (device != null) {
            synchronized (lockOf(deviceId)) {
                device.setState(DeviceState.OFFLINE);
            }
            log.info("device={} action=offline", deviceId);
        }
    }

    Object lockOf(String deviceId) {
        return locks.computeIfAbsent(deviceId, k -> new Object());
    }
}
