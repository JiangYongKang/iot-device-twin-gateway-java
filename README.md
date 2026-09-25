# IoT 设备接入与影子状态管理服务（本地模拟）

基于 Spring Boot 的进程内物联网设备网关：设备注册、心跳、影子（Device Twin）状态、
遥测接收与离线补传、固件分批升级与回滚。全部状态保存在进程内存中，时间由
`MutableClock` 控制，不依赖外部消息中间件、真实设备或付费服务，可完全本地复现。

## 设备状态模型

```
Device
├── deviceId / firmwareVersion
├── 连接状态: OFFLINE --heartbeat--> ONLINE --markOffline--> OFFLINE
├── 心跳: lastHeartbeatSeq / lastHeartbeatAt（seq 单调递增）
└── 影子:
    ├── reported（设备上报）: (reportedVersion, reportedAt) + KV 状态
    └── desired（云端期望）: desiredVersion + KV 状态
```

**单调性规则**：reported 影子按 `(version, at)` 字典序推进，已确认状态绝不回退。
每次更新返回可区分的结论（`UpdateOutcome`）：

| 结论 | 含义 |
|---|---|
| `APPLIED` | 接受并推进已确认状态 |
| `DUPLICATE` | 完全相同的 (version, at)，幂等忽略 |
| `STALE` | 版本或时间戳更旧（乱序/过期），拒绝 |
| `REJECTED` | 设备不存在等硬性拒绝 |

每个设备一把锁，判定与写入原子完成，并发上报下查询结果单调一致，
并发结论只会是 `APPLIED` 或 `STALE`。

## 遥测：限流与重传策略

- **幂等**：按 `messageId` 去重；重复上报、重复补传结论为 `DUPLICATE`，不重复计量。
- **离线暂存**：设备离线时消息进入每设备有界队列（默认容量 100），重连后
  `flushOffline` 按 `seq` 升序补传并计量。
- **容量拒绝**：队列满时返回 `REJECTED_CAPACITY`，明确拒绝而非静默丢弃。
- **速率限制**：每设备固定窗口限流（默认 10 条/秒，基于 `MutableClock`），
  超限返回 `REJECTED_RATE`，窗口推进后恢复。
- 结论枚举 `TelemetryOutcome`：`ACCEPTED / BUFFERED / DUPLICATE /
  REJECTED_CAPACITY / REJECTED_RATE / REJECTED_UNKNOWN_DEVICE`。

## 固件升级状态机

```
                 startBatch
                     │
        ┌────────────┼─────────────────────┐
        ▼            ▼                     ▼
   IN_PROGRESS   ISOLATED（前置条件不满足，   每台设备独立判定，
      │           不影响同批其他设备）        隔离原因写入 detail
      ├─ confirmSuccess(版本匹配) ──► SUCCESS（固件推进到目标版本）
      ├─ reportFailure ──► FAILED ──► ROLLED_BACK（恢复升级前版本）
      └─ sweepTimeouts 超过 deadline ──► TIMEOUT ──► ROLLED_BACK
```

- 前置条件：设备已注册、在线、当前版本 ≠ 目标版本、无进行中任务；不满足即 `ISOLATED`。
- 同一设备的并发升级请求通过设备锁 + activeTask 检查**只生效一次**，其余隔离。
- 回滚后迟到的成功确认被拒绝；确认版本与目标版本不匹配被拒绝。
- 超时由 `MutableClock` 推进时间后调用 `sweepTimeouts()` 触发，时序完全可控。

## 本地验证

```bash
# 运行全部测试（15 个用例，日志含设备标识、时序与判定依据）
mvn test

# 启动服务，用 REST 接口手工验证
mvn spring-boot:run
curl -X POST localhost:8080/api/devices -H 'Content-Type: application/json' \
     -d '{"deviceId":"dev-1","firmwareVersion":"1.0.0"}'
curl -X POST localhost:8080/api/devices/dev-1/heartbeat -H 'Content-Type: application/json' \
     -d '{"seq":1,"at":"2026-09-26T00:00:00Z"}'
curl -X POST localhost:8080/api/devices/dev-1/shadow/reported -H 'Content-Type: application/json' \
     -d '{"version":1,"at":"2026-09-26T00:00:01Z","state":{"temp":22}}'
curl -X POST localhost:8080/api/upgrades -H 'Content-Type: application/json' \
     -d '{"batchId":"b1","deviceIds":["dev-1"],"targetVersion":"2.0.0"}'
```

## 测试覆盖

| 测试类 | 场景 |
|---|---|
| `DeviceServiceTest` | 重复上报、乱序/过期时间戳、心跳去重、并发影子更新单调收敛 |
| `TelemetryServiceTest` | 离线暂存与按序补传、重复补传不重复计量、容量拒绝、限流拒绝与恢复、未知设备 |
| `FirmwareServiceTest` | 分批隔离互不影响、失败回滚、超时回滚、并发升级只生效一次、版本不匹配拒绝 |
