# IoT 设备接入与影子状态管理服务（本地模拟）

基于 Spring Boot 的进程内物联网设备接入网关，用内存存储与可控时钟模拟设备注册、
心跳、影子状态、遥测上报（含离线补传）与固件分批升级。不依赖外部消息中间件、
真实设备或付费服务，所有行为可本地复现、时序可控、结论可预期。

## 设备状态模型

### 设备在线状态（`DeviceStatus`）

```
OFFLINE --心跳(seq 严格递增)--> ONLINE
ONLINE  --markOffline---------> OFFLINE
任意    --前置条件不满足/并发冲突--> QUARANTINED（隔离）
```

- 心跳携带单调递增的 `seq`：`seq > 已确认` 才生效（`APPLIED`）；
  `seq == 已确认` 为重复上报（`DUPLICATE_IGNORED`，幂等忽略）；
  `seq < 已确认` 为乱序/过期（`STALE_REJECTED`，拒绝）。
  已确认状态在任何情况下不回退。

### 影子状态（`ShadowState`）

- reported 视图带单调递增 `version`，设备级锁串行化并发更新，查询结果单调一致。
- 更新结论（`ShadowUpdateOutcome`）可区分：
  - `APPLIED`：更高版本，已应用；
  - `DUPLICATE_IGNORED`：同版本同内容，幂等忽略；
  - `CONFLICT_REJECTED`：同版本不同内容，拒绝并保留已确认状态；
  - `STALE_REJECTED`：版本回退（乱序/过期），拒绝。

## 遥测：限流与重传策略

- **去重**：按 `messageId` 去重，每个唯一消息只计量一次；重复补传返回
  `DUPLICATE_IGNORED`，不重复计量。
- **限流**：每设备令牌桶（容量 10，补充速率 10 个/秒）。超限返回
  `REJECTED_RATE_LIMITED`，**明确拒绝而非静默丢弃**；被拒绝的消息不进入去重集合，
  时钟推进后可重试。
- **离线暂存**：设备离线时数据按到达顺序进入有界缓冲（容量 100/设备）。
  缓冲满返回 `REJECTED_BUFFER_FULL`，明确拒绝。
- **补传**：重连后调用 `flushOffline` 按序取回缓冲数据并清空；
  计量在首次接收时已完成，补传与重复补传均不重复计量。

## 固件升级状态机

```
                 startCampaign
                      |
                   PENDING --markInProgress--> IN_PROGRESS
                      |                          |
                      |                          +--markSucceeded--> SUCCEEDED --rollback--> ROLLED_BACK
                      |                          |
                      |                          +--markFailed-----> FAILED（自动回滚到升级前版本）
                      |                          |
                      +----------超时(30s)---------> TIMED_OUT（自动回滚到升级前版本）

前置条件不满足（未知设备 / 已在目标版本 / 该设备已有进行中升级）
                      |
                      +--> REJECTED_PRECONDITION（设备被隔离为 QUARANTINED，不影响同批其他设备）
```

- **并发去重**：同一设备同一时刻只允许一个进行中任务（`activeTaskByDevice` 占槽），
  并发升级请求只生效一次，其余被拒绝并隔离；任务进入终态后槽位释放，可重新升级。
- **超时**：`sweepTimeouts` 扫描超过 30s 未推进的任务，置 `TIMED_OUT` 并回滚固件版本。
- **回滚**：`FAILED` / `TIMED_OUT` 自动回滚到升级前版本；`SUCCEEDED` 可显式回滚为
  `ROLLED_BACK`。

## 可控时序

服务依赖 `TimeSource` 接口取时。生产环境注入 `SystemTimeSource`；单测使用
`ManualTimeSource` 手动推进时钟，精确控制限流窗口、离线时长与升级超时。

## REST API 摘要

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/devices` | 注册设备 |
| POST | `/api/devices/{id}/heartbeat` | 心跳（body: `{"seq": n}`） |
| POST | `/api/devices/{id}/offline` | 置离线 |
| POST/GET | `/api/devices/{id}/shadow` | 更新 / 查询影子 |
| POST | `/api/devices/{id}/telemetry` | 遥测上报 |
| POST | `/api/devices/{id}/telemetry/flush` | 重连补传 |
| POST | `/api/campaigns` | 发起分批升级 |
| POST | `/api/tasks/{id}/start|succeed|fail|rollback` | 推进升级状态机 |
| POST | `/api/tasks/sweep-timeouts` | 扫描超时任务 |

## 本地验证

```bash
# 运行全部测试（14 个，覆盖重复上报、乱序到达、离线重连、
# 并发重复升级、升级失败回滚、超时回滚、限流与缓冲容量）
mvn test

# 启动服务（随机端口，见日志）
mvn spring-boot:run
```

单测日志会打印设备标识、时序（手动时钟时刻）与判定依据（每次状态迁移的
outcome/state 与 reason），可直接核对结论。
