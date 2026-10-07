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

## 分批灰度发布（Canary Waves）

在整批升级之上叠加一层顺序推进的灰度编排：一次批次拆成多个波次，一波达标才推下一波，
出问题自动收手（也可人工随时叫停）。入口为 `RolloutService`（REST：`/api/rollouts/**`）。

### 编排与推进规则

```
createRollout（全部设备 WAITING 待命，不下发）
        │ start
        ▼
  wave1 IN_PROGRESS ──全部设备出结论──► 成功数 >= minSuccess ?
        │ 是                                 │ 否
        ▼                                    ▼
  wave2 IN_PROGRESS（上一波保留）      本波 REJECTED，整批 REJECTED 叫停
        ...                             在途回滚、后续波次 CANCELLED
        ▼
 最后一波达标 ──► 整批 COMPLETED

任意时刻 abort ──► 整批 ABORTED：已通过波次原样保留，
                   当前波在途设备回滚，其余待命设备取消
```

- **顺序闸门**：只有前一波达到该波配置的通过条件才下发下一波；未轮到的波次状态为
  `PENDING`，设备口径为 `WAITING`（待命、可查询、绝不下发，固件版本不变）。
- **通过条件按波次配置**：每波独立设置 `minSuccess`（至少多少台成功才算达标，可按批次单独设）。
  校验规则：`0 <= minSuccess <= 本波设备数`；波次不能为空；同一设备不能出现在同一批次的多个波次。
- **收口时机**：波次必须等本波**全部设备都有终态结论**后才判定，避免“看似达标”提前推进；
  极端情况下整波都被前置隔离（无在途任务）也会立即收口判定。
- **自动推进/收手**：达标自动下发下一波，最后一波达标则整批 `COMPLETED`；不达标则当前波
  `REJECTED`、整批 `REJECTED` 停止，后续波次 `CANCELLED`，不再下发任何设备。
- **人工中止**：`abort(rolloutId, reason)` 语义与自动叫停一致；对终态批次重复调用返回 `false`。

### 设备结论口径（互斥、不重不漏）

每台设备在任意时刻只有一个口径结论（`RolloutDeviceOutcome`），汇总各口径之和恒等于设备总数：

| 口径 | 含义 | 叫停后固件版本 |
|---|---|---|
| `SUCCESS` | 升级成功并确认 | 保持目标版本 |
| `FAILED` | **设备自己**上报失败（任务 ROLLED_BACK，成因 DEVICE_REPORTED_FAILURE） | 回滚到升级前版本 |
| `TIMEOUT` | 超过 deadline 未确认（任务 ROLLED_BACK，成因 DEADLINE） | 回滚到升级前版本 |
| `ISOLATED` | 前置条件不满足被隔离，**从未下发** | 原样不变 |
| `ABORTED` | **批次中途叫停**：在途设备任务置 ABORTED 并回滚；待命设备取消下发 | 在途回滚 / 待命不变 |
| `IN_PROGRESS` | 当前波进行中、尚无结论 | —— |
| `WAITING` | 波次未轮到，待命中 | 原样不变 |

被叫停设备统一为 `ABORTED`，与“设备自己升级失败”（`FAILED`）和“前置隔离”（`ISOLATED`）
明确区分。`UpgradeTask` 新增 `endCause`（DEVICE_CONFIRMED / DEVICE_REPORTED_FAILURE /
DEADLINE / ABORTED）记录终态成因，FAILED 与 TIMEOUT 都落到 ROLLED_BACK 但成因不同。

### 中途叫停后的兼容范围

- **已成功的设备与已 `PASSED` 波次结果原样保留**，不被覆盖或抹掉，固件保持目标版本。
- 当前波中仍在途（IN_PROGRESS）的设备：任务置 `ABORTED`，固件回滚到**各自升级前版本**。
- 当前波中已经成功/失败/超时/隔离的设备，结论与固件状态保持叫停前不变。
- 尚未轮到的波次：状态 `CANCELLED`，设备从 `WAITING` 变为 `ABORTED`（从未下发）。
- 叫停是终态：之后迟到的成功确认、失败上报、再次叫停都不会生效，批次不会继续推进。
- 不达标自动叫停（`REJECTED`）与人工叫停（`ABORTED`）的设备处理规则相同，仅批次/波次状态名不同。

### 并发与一致性

- 同一台设备同一时刻最多一个进行中升级：沿用 `FirmwareService` 的设备锁 + activeTask 检查；
  同一设备也不能同时属于两个进行中的灰度批次（全局创建锁 + 设备占用表，终态批次后设备可复用）。
- 波次推进、结果确认、超时扫描、人工中止都在**批次锁**内串行，锁顺序固定为
  “批次锁 → 设备锁”，并发交错下结论稳定确定，不存在重复下发、重复计数或状态来回跳。
- 汇总（`summary`）整批与各波计数都由同一套“每设备唯一口径”现场归并得出，
  各口径之和 = 各波之和 = 设备总数；上百台（测试覆盖 200 台 / 5 波）同样成立。

## 本地验证

```bash
# 运行全部测试（28 个用例，日志含设备标识、时序与判定依据）
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

# 分批灰度：2 波，每波通过条件分别为 1、2 台成功
for d in g1 g2a g2b; do
  curl -X POST localhost:8080/api/devices -H 'Content-Type: application/json' \
       -d "{\"deviceId\":\"$d\",\"firmwareVersion\":\"1.0.0\"}"
  curl -X POST localhost:8080/api/devices/$d/heartbeat -H 'Content-Type: application/json' \
       -d '{"seq":1,"at":"2026-10-08T00:00:00Z"}'
done
curl -X POST localhost:8080/api/rollouts -H 'Content-Type: application/json' \
     -d '{"rolloutId":"r1","targetVersion":"2.0.0","waves":[{"deviceIds":["g1"],"minSuccess":1},{"deviceIds":["g2a","g2b"],"minSuccess":2}]}'
curl -X POST localhost:8080/api/rollouts/r1/start
curl localhost:8080/api/rollouts/r1/summary     # 第一波 IN_PROGRESS，第二波 WAITING
curl -X POST localhost:8080/api/rollouts/devices/g1/confirm -H 'Content-Type: application/json' -d '{"version":"2.0.0"}'
curl localhost:8080/api/rollouts/r1/summary     # 第一波 PASSED，第二波自动 IN_PROGRESS
curl -X POST localhost:8080/api/rollouts/r1/abort -H 'Content-Type: application/json' -d '{"reason":"manual stop"}'
curl localhost:8080/api/rollouts/r1/summary     # ABORTED：g1 SUCCESS 保留，g2a/g2b ABORTED 回滚
```

## 测试覆盖

| 测试类 | 场景 |
|---|---|
| `DeviceServiceTest` | 重复上报、乱序/过期时间戳、心跳去重、并发影子更新单调收敛 |
| `TelemetryServiceTest` | 离线暂存与按序补传、重复补传不重复计量、容量拒绝、限流拒绝与恢复、未知设备 |
| `FirmwareServiceTest` | 分批隔离互不影响、失败回滚、超时回滚、并发升级只生效一次、版本不匹配拒绝 |
| `RolloutServiceTest` | 待命不下发、达标顺序推进/完成、未达标自动叫停回滚、人工中止保留成功波、超时叫停、隔离单独计数、汇总口径守恒、200 台大规模、并发 start/确认/叫停稳定、跨批次设备占用互斥、终态后设备复用 |
