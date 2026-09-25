package com.github.highcumontoa.iotdevicetwingatewayjava.service;

import com.github.highcumontoa.iotdevicetwingatewayjava.model.ShadowState;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.ShadowUpdateOutcome;
import com.github.highcumontoa.iotdevicetwingatewayjava.model.ShadowUpdateResult;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 影子状态服务。
 * 不变量：version 单调递增；重复上报幂等忽略；乱序/过期版本拒绝；
 * 同版本不同内容判定为冲突并拒绝，已确认状态永不回退。
 * 并发下对同一设备的更新在设备级锁内串行化，查询结果单调一致。
 */
@Service
public class ShadowService {

    private final DeviceRegistry registry;
    private final Map<String, ShadowState> shadows = new ConcurrentHashMap<>();

    public ShadowService(DeviceRegistry registry) {
        this.registry = registry;
    }

    public ShadowUpdateResult updateReported(String deviceId, long version,
                                             Map<String, Object> state, long timestamp) {
        if (registry.find(deviceId).isEmpty()) {
            return new ShadowUpdateResult(ShadowUpdateOutcome.STALE_REJECTED, -1,
                    "unknown device: " + deviceId);
        }
        ShadowState shadow = shadows.computeIfAbsent(deviceId, id -> new ShadowState());
        synchronized (shadow) {
            long confirmed = shadow.getVersion();
            if (version < confirmed) {
                return new ShadowUpdateResult(ShadowUpdateOutcome.STALE_REJECTED, confirmed,
                        "stale version=" + version + " < confirmed=" + confirmed);
            }
            if (version == confirmed) {
                if (Objects.equals(shadow.getReported(), state)) {
                    return new ShadowUpdateResult(ShadowUpdateOutcome.DUPLICATE_IGNORED, confirmed,
                            "duplicate update version=" + version);
                }
                return new ShadowUpdateResult(ShadowUpdateOutcome.CONFLICT_REJECTED, confirmed,
                        "conflicting content at version=" + version
                                + ", confirmed state preserved");
            }
            shadow.getReported().clear();
            shadow.getReported().putAll(state);
            shadow.setVersion(version);
            shadow.setLastUpdatedAt(timestamp);
            return new ShadowUpdateResult(ShadowUpdateOutcome.APPLIED, version,
                    "applied version=" + version);
        }
    }

    public Optional<ShadowState> getShadow(String deviceId) {
        return Optional.ofNullable(shadows.get(deviceId));
    }
}
