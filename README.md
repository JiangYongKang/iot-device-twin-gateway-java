# IoT 设备接入与影子状态管理服务（本地模拟）

基于 Spring Boot 的进程内物联网设备网关：设备注册、心跳、影子（Device Twin）状态、
遥测接收与离线补传、固件整批升级与回滚，以及**分批灰度发布（波次推进、达标自动下推、
不达标自动收手）**。全部状态保存在进程内存中，时间由 `MutableClock` 控制，不依赖外部
消息中间件、真实设备或付费服务，可完全本地复现。

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
- 回滚类结论通过 `UpgradeTask.rollbackCause` 记录诱因：`FAILED`（设备自报失败）、
  `TIMEOUT`（超时）、`ABORTED`（灰度批次中途叫停），三者在汇总中分桶互斥计数。

## 分批灰度发布（Rollout）

在整批升级之上叠加一层波次（Wave）编排，由 `RolloutService` 负责，
底层下发/确认/回滚仍复用 `FirmwareService`。

### 规则

1. **按配置拆波、顺序推进**：一个批次包含一个**有序波次列表**，每波独立配置
   `deviceIds` 与通过条件 `requiredSuccesses`（本波至少多少台成功）。创建批次后
   所有波次为 `WAITING`（待命），不向任何设备下发；调用一次 `launch` 才下发第 0 波。
2. **只有前一波达标才允许下一波**：某波设备全部见分晓且 `成功台数 >= requiredSuccesses`
   时本波置 `PASSED`，并**立即自动下发下一波**；最后一波达标则批次 `COMPLETED`。
   前一波未达标/未结束前，任何提前下发下一波的请求都被拒绝（返回 409），后续设备
   绝不可能提前进入升级。
3. **自动判定收手**：每收到一次成功/失败/超时事件都会重新判定。一旦
   `成功数 + 进行中数 < requiredSuccesses`（数学上已不可能达标），本波立即判 `FAILED`、
   整批置 `HALTED`：仍在进行中的设备被**中途叫停**——结论 `ABORTED`（任务回滚后
   `rollbackCause=ABORTED`）并回滚到各自升级前版本；后续波次保持 `WAITING`，不再下发。
   即使某波设备下发瞬间全部被隔离（无进行中设备），也会立即判停，不会挂起。
4. **人工中止**：`abort` 将批次置 `ABORTED`，进行中设备同样 `ABORTED` + 回滚，
   未开始的波次置 `ABORTED` 且不再下发。

### 三种“没成功”的结论如何区分

| 结论 | 触发方 | 是否回滚 | 计数桶 |
|---|---|---|---|
| `FAILED`（`ROLLED_BACK` + cause=FAILED） | 设备自己上报失败 | 回滚到升级前版本 | `failed` |
| `TIMEOUT`（`ROLLED_BACK` + cause=TIMEOUT） | 超过 deadline 未确认被扫描 | 回滚 | `timeout` |
| `ABORTED`（`ROLLED_BACK` + cause=ABORTED） | 批次判停/人工中止，设备自身无过错 | 回滚 | `aborted` |
| `ISOLATED` | 前置条件不满足（离线/未知/同版本/已有进行中任务） | 不动固件 | `isolated` |

### 叫停后的兼容范围

- **已成功设备结果原样保留**：`SUCCESS` 状态与目标版本固件不被叫停覆盖或回退，
  已 `PASSED` 的历史波次结论不变；被叫停/判停只影响当时仍 `IN_PROGRESS` 的设备
  以及尚未开始的波次。
- 终态批次（`COMPLETED / HALTED / ABORTED`）上再调确认、失败上报、下发均被拒绝；
  重复 `abort` 为空操作。
- 同一台设备同一时刻仍只能有一个进行中升级（设备锁 + activeTask）；它若已被另一个
  批次（含旧的整批直推接口）占用，在灰度波次中按 `ISOLATED` 处理。

### 进度汇总（互斥、可对账）

`GET /api/rollouts/{batchId}/summary` 返回批次总数与每波计数，桶位互斥：

```
total = success + failed + timeout + isolated + aborted + inProgress + waiting
批次各桶 = 各波次同桶之和；waiting = 尚未下发（待命波次）的设备
```

汇总数由 `FirmwareService.tasksOfBatch(batchId)` 的每台设备实际任务状态逐条归类得出，
不存在重复计数或漏计；测试 `assertCountsReconcile` 在每个阶段强制校验上述恒等式。

### 并发与规模

- 每个批次一把锁，串行化“下发 / 结果确认 / 判定推进 / 中止”；设备自身状态判定仍走
  设备锁。锁序固定为**设备锁 → 批次锁**（设备操作完成后再进批次锁判定），无死锁。
- 并发地确认结果与中止时，每台设备结论确定：要么 `SUCCESS` 保留，要么 `ABORTED` 回滚，
  状态不回跳、不重复计量（见 `concurrentResultConfirmationAndAbortProduceStableOutcome`）。
- 已验证 4 波 × 25 台 = 100 台规模下逐阶段对账一致。

### REST 接口

| 方法与路径 | 说明 |
|---|---|
| `POST /api/rollouts` | 创建灰度批次（不立即下发） |
| `POST /api/rollouts/{batchId}/launch` | 显式下发下一波（首波）；提前/重复推进返回 409 |
| `GET  /api/rollouts/{batchId}` | 查批次与各波状态（CREATED/IN_PROGRESS/COMPLETED/HALTED/ABORTED） |
| `GET  /api/rollouts/{batchId}/summary` | 批次 + 各波互斥计数汇总 |
| `POST /api/rollouts/{batchId}/devices/{deviceId}/confirm` | 设备确认成功（达标自动推下一波） |
| `POST /api/rollouts/{batchId}/devices/{deviceId}/fail` | 设备上报失败（自动回滚 + 判定） |
| `POST /api/rollouts/{batchId}/sweep` | 扫描本批超时设备并回滚 + 判定 |
| `POST /api/rollouts/{batchId}/abort` | 人工中止，body：`{"reason":"..."}` |

创建请求体示例（通过条件按批次/波次单独设置；同一设备不得跨波重复；
`requiredSuccesses` 取值范围 `[1, 本波设备数]`）：

```json
{
  "batchId": "canary-1",
  "targetVersion": "2.0.0",
  "waves": [
    {"deviceIds": ["dev-1", "dev-2"], "requiredSuccesses": 2},
    {"deviceIds": ["dev-3", "dev-4", "dev-5"], "requiredSuccesses": 2},
    {"deviceIds": ["dev-6"], "requiredSuccesses": 1}
  ]
}
```

## 本地验证

```bash
# 运行全部测试（26 个用例，日志含设备标识、波次时序与判定依据）
mvn test

# 只跑灰度发布用例（11 个）
mvn -Dtest=RolloutServiceTest test

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

# 分批灰度：两波各 1 台、每波至少 1 台成功
for d in canary-a canary-b; do
  curl -X POST localhost:8080/api/devices -H 'Content-Type: application/json' \
       -d "{\"deviceId\":\"$d\",\"firmwareVersion\":\"1.0.0\"}"
  curl -X POST localhost:8080/api/devices/$d/heartbeat -H 'Content-Type: application/json' \
       -d '{"seq":1,"at":"2026-10-08T00:00:00Z"}'
done
curl -X POST localhost:8080/api/rollouts -H 'Content-Type: application/json' \
     -d '{"batchId":"c1","targetVersion":"2.0.0","waves":[{"deviceIds":["canary-a"],"requiredSuccesses":1},{"deviceIds":["canary-b"],"requiredSuccesses":1}]}'
curl -X POST localhost:8080/api/rollouts/c1/launch                 # 下发第 0 波
curl -X POST localhost:8080/api/rollouts/c1/devices/canary-a/confirm \
     -H 'Content-Type: application/json' -d '{"batchId":"c1","version":"2.0.0"}'  # 达标，自动下发第 1 波
curl -X POST localhost:8080/api/rollouts/c1/devices/canary-b/confirm \
     -H 'Content-Type: application/json' -d '{"batchId":"c1","version":"2.0.0"}'
curl localhost:8080/api/rollouts/c1/summary                       # 预期 success=2
# 随时中止（进行中设备回滚，已成功设备保留）：
curl -X POST localhost:8080/api/rollouts/c1/abort -H 'Content-Type: application/json' \
     -d '{"reason":"operator stop"}'
```

灰度判定与超时的时序在单测中完全由 `MutableClock` 驱动（推进 31 秒后调用
`/api/rollouts/{batchId}/sweep` 触发超时判停），无需等待真实时间。

## 测试覆盖

| 测试类 | 场景 |
|---|---|
| `DeviceServiceTest` | 重复上报、乱序/过期时间戳、心跳去重、并发影子更新单调收敛 |
| `TelemetryServiceTest` | 离线暂存与按序补传、重复补传不重复计量、容量拒绝、限流拒绝与恢复、未知设备 |
| `FirmwareServiceTest` | 分批隔离互不影响、失败回滚、超时回滚、并发升级只生效一次、版本不匹配拒绝 |
| `RolloutServiceTest`（新增 11 个） | 待命波次绝不提前下发；达标自动推下一波；不达标自动 HALTED 且进行中设备 `ABORTED`+回滚；超时也可触发判停且与叫停分桶；人工中止保留已成功结果；隔离单独计数；跨批次占用隔离；并发确认/中止结论稳定确定（重复 20 次）；100 台 4 波逐阶段汇总对账；非法波次配置拒绝 |
