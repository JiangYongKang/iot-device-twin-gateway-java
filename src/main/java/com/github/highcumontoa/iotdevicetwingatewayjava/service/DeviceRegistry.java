package com.github.highcumontoa.iotdevicetwingatewayjava.service;

import com.github.highcumontoa.iotdevicetwingatewayjava.config.TimeSource;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.Device;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.DeviceStatus;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.ShadowUpdateOutcome;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.ShadowUpdateResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 设备注册与心跳。心跳 seq 单调递增，重复/乱序心跳不得回退已确认状态。 */
@Service
public class DeviceRegistry {

    private final Map<String, Device> devices = new ConcurrentHashMap<>();
    private final TimeSource timeSource;

    public DeviceRegistry(TimeSource timeSource) {
        this.timeSource = timeSource;
    }

    public Device register(String deviceId, String firmwareVersion) {
        Device device = new Device(deviceId, firmwareVersion);
        device.setStatus(DeviceStatus.ONLINE);
        device.setLastHeartbeatAt(timeSource.now());
        Device existing = devices.putIfAbsent(deviceId, device);
        return existing != null ? existing : device;
    }

    public Optional<Device> find(String deviceId) {
        return Optional.ofNullable(devices.get(deviceId));
    }

    public List<Device> list() {
        return List.copyOf(devices.values());
    }

    /**
     * 心跳：seq 必须严格大于已确认 seq 才生效；
     * 相同 seq 视为重复上报（幂等忽略），更小 seq 视为乱序/过期（拒绝）。
     */
    public ShadowUpdateResult heartbeat(String deviceId, long seq) {
        Device device = devices.get(deviceId);
        if (device == null) {
            return new ShadowUpdateResult(ShadowUpdateOutcome.STALE_REJECTED, -1,
                    "unknown device: " + deviceId);
        }
        synchronized (device) {
            long confirmed = device.getLastHeartbeatSeq();
            if (seq < confirmed) {
                return new ShadowUpdateResult(ShadowUpdateOutcome.STALE_REJECTED, confirmed,
                        "stale heartbeat seq=" + seq + " < confirmed=" + confirmed);
            }
            if (seq == confirmed) {
                return new ShadowUpdateResult(ShadowUpdateOutcome.DUPLICATE_IGNORED, confirmed,
                        "duplicate heartbeat seq=" + seq);
            }
            device.setLastHeartbeatSeq(seq);
            device.setLastHeartbeatAt(timeSource.now());
            if (device.getStatus() == DeviceStatus.OFFLINE) {
                device.setStatus(DeviceStatus.ONLINE);
            }
            return new ShadowUpdateResult(ShadowUpdateOutcome.APPLIED, seq,
                    "heartbeat applied seq=" + seq);
        }
    }

    public void markOffline(String deviceId) {
        Device device = devices.get(deviceId);
        if (device != null) {
            synchronized (device) {
                if (device.getStatus() != DeviceStatus.QUARANTINED) {
                    device.setStatus(DeviceStatus.OFFLINE);
                }
            }
        }
    }

    public void quarantine(String deviceId, String reason) {
        Device device = devices.get(deviceId);
        if (device != null) {
            synchronized (device) {
                device.setStatus(DeviceStatus.QUARANTINED);
            }
        }
    }

    public void updateFirmwareVersion(String deviceId, String version) {
        Device device = devices.get(deviceId);
        if (device != null) {
            synchronized (device) {
                device.setFirmwareVersion(version);
            }
        }
    }
}
