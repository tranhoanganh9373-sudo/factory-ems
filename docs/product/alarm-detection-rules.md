# 采集中断告警 · 检测规则

> **更新于**：2026-04-29（Phase D 完成时）
> **撰写依据**：spec §3 + AlarmDetectorImpl 实际实现 + Phase D 集成测试结论

---

## 1. 触发条件总览

告警检测引擎对每台设备支持两种触发条件，二者为 **OR 关系**：任意一个条件命中即触发告警。同一轮扫描中，若两条件同时命中，则 **静默超时（SILENT_TIMEOUT）优先**，连续失败（CONSECUTIVE_FAIL）在该轮被忽略。

下图展示每轮检测对单台设备的完整决策树：

```
收 snapshots（CollectorService.snapshots()）
       │
       ▼
  对每台设备并行处理
       │
       ├─ meters.findByCode(meterCode) 找不到？──→ skip（不记录任何内容）
       │
       ├─ maintenanceMode = true？────────────────→ skip（不检测，不恢复）
       │
       ▼
  计算两个命中标志
       │
       ├─ silentHit?  lastReadAt != null
       │              AND (now - lastReadAt) > silentTimeoutSeconds
       │
       └─ failHit?   consecutiveErrors >= consecutiveFailCount
       │
       ▼
  确定 primaryType（优先级：silentHit > failHit > 无）
       │
       ├─ primaryType != null（有命中）
       │       │
       │       ├─ alarms.findActive(meterId, type) 已有 ACTIVE/ACKED？
       │       │       └─ 是 → skip（不重复创建）
       │       │
       │       └─ 否 → 创建 ACTIVE 告警行 + dispatcher.dispatch 发送通知
       │
       └─ primaryType = null（两条件都未命中）
               │
               ├─ tryAutoResolve(SILENT_TIMEOUT)
               │       └─ 找到活动告警 AND (now - triggeredAt) > suppressionWindowSeconds
               │               └─ 是 → sm.resolve(AUTO) + dispatcher.dispatchResolved
               │
               └─ tryAutoResolve(CONSECUTIVE_FAIL)
                       └─ 同上逻辑
```

检测节奏：**默认每 60 秒一轮**（可通过 `ems.alarm.poll-interval-seconds` 调整）。单设备处理异常会被 `catch + log.warn`，**不影响其他设备的扫描**。

---

## 2. 静默超时（SILENT_TIMEOUT）完整定义

### 2.1 判定公式

```
silentHit = (snap.lastReadAt != null)
            AND (now - snap.lastReadAt) > silent_timeout_seconds
```

两个子条件必须**同时成立**才触发：
- `lastReadAt` 不为 null（设备历史上至少成功上报过一次）
- 距离最后一次成功上报已超过阈值时间

### 2.2 特殊边界：lastReadAt 为 NULL

当 `snap.lastReadAt IS NULL` 时，**不触发** SILENT_TIMEOUT。

适用场景：新接入设备尚未完成第一次采集，此时没有"最后心跳"基准，不应视为掉线。若该设备采集持续失败，仍可通过 CONSECUTIVE_FAIL 触发告警（详见 §3）。

### 2.3 阈值配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `ems.alarm.default-silent-timeout-seconds` | `600`（10 分钟） | 全局默认阈值 |
| 设备级覆盖 `silent_timeout_seconds` | 继承全局 | 在 `alarm_rules_override` 表设置，优先于全局 |

设备级覆盖优先于全局配置（`thresholds.resolve(meterId)` 的查找顺序：设备覆盖 → 全局默认）。

### 2.4 调优建议

| 采集周期 | 推荐阈值 | 触发延迟 | 适用场景 |
|----------|----------|----------|----------|
| 5 秒 | 60 ～ 120 秒 | 1 ～ 2 分钟 | 高频实时（电力/水务仪表） |
| 10 ～ 30 秒 | 300 ～ 600 秒 | 5 ～ 10 分钟 | 中等频率工业设备 |
| 60 秒 | 600 ～ 1800 秒 | 10 ～ 30 分钟 | 标准工厂设备监控 |
| 5 分钟 | 1800 ～ 3600 秒 | 30 ～ 60 分钟 | 低频仪表/水表抄表 |

**经验法则：阈值 = 采集周期 × 5 ～ 10**

- **为什么不能太接近采集周期**：网络抖动、设备临时负载高都会造成偶尔丢失 1 ～ 2 个采集点，这是正常现象，不代表设备真的掉线。若阈值 = 1 ～ 2 个周期，每次普通抖动都会产生误报，削弱告警的可信度。
- **为什么不能太大**：阈值设置 24 小时意味着设备掉线长达 23 小时才会触发告警，完全丧失故障预警价值。阈值应当让运维人员在问题真正影响业务前得到通知。

---

## 3. 连续失败（CONSECUTIVE_FAIL）完整定义

### 3.1 判定公式

```
failHit = snap.consecutiveErrors >= consecutive_fail_count
```

当快照中的连续错误计数达到或超过阈值时触发。

### 3.2 数据来源

`snap.consecutiveErrors` 来源于 collector 模块内 `DevicePoller` 维护的 **per-device 内存计数器** `consecutiveCycleErrors`：

- 每次采集**成功**：`consecutiveCycleErrors` 重置为 `0`
- 每次采集**失败**：`consecutiveCycleErrors` 递增 `+1`
- collector 进程**重启**：所有设备的计数归零（详见 §6）

该计数按设备独立维护，设备 A 的失败不影响设备 B 的计数。

### 3.3 阈值配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `ems.alarm.default-consecutive-fail-count` | `3` | 全局默认阈值 |
| 设备级覆盖 `consecutive_fail_count` | 继承全局 | 在 `alarm_rules_override` 表设置，优先于全局 |

### 3.4 调优建议

| 网络环境 | 推荐阈值 | 说明 |
|----------|----------|------|
| 有线以太网、网络稳定 | 2 ～ 3 | 单次失败极可能是真实故障，快速告警 |
| 工业现场、网络偶有抖动 | 5 ～ 10 | 容忍短暂干扰，避免误报 |
| 无线/4G 连接 | 5 ～ 8 | 信号波动频繁，适当放宽 |

**重要边界**：collector 重启后内存计数清零，需要重新积累 N 个失败周期才会再次触发。若设备在 collector 重启期间持续处于故障状态，会出现"重启 → 计数从 0 开始 → 第 N 轮后才再次触发"的延迟。详见 §6。

---

## 4. 检测节奏

### 4.1 默认配置

```
@Scheduled(fixedDelayString = "${ems.alarm.poll-interval-seconds:60} * 1000")
```

- 默认每 **60 秒**一轮，`fixedDelay` 语义（上一轮完成后再计时，非固定时钟触发）
- 可通过环境变量或配置文件调整：`ems.alarm.poll-interval-seconds=30`

### 4.2 多实例部署注意

首版**不引入** ShedLock 或分布式锁。若部署多个 ems-app 实例，每个实例都会独立执行检测，可能在同一周期内重复创建告警。**多实例部署需自行在数据库层面加锁**（已列入 ops backlog，待后续 sprint 处理）。

当前生产建议：**单实例运行** alarm 检测模块。

### 4.3 性能指标

- 单设备处理耗时：< 5ms（DB 查询均走索引）
- 1000 台设备一轮扫描：< 5 秒
- 设备处理异常被 catch 隔离，不会导致整轮扫描中断

### 4.4 周期调整参考

| 设备规模 | 推荐检测周期 | 数据库压力 |
|----------|--------------|------------|
| < 100 台 | 30 ～ 60 秒 | 极低 |
| 100 ～ 1000 台 | 60 秒（默认） | 低 |
| > 1000 台 | 120 ～ 300 秒 | 中（建议同时优化 `alarms` 表索引） |

---

## 5. 不会触发告警的场景

以下场景是运维人员最常误以为"应该触发但没触发"的情况，每条均有明确的设计原因：

### 5.1 设备从未上报过数据（`lastReadAt` 为 NULL）

新接入设备尚未完成首次采集时，`snap.lastReadAt` 为 null，SILENT_TIMEOUT 检测逻辑会直接跳过，**不会触发**。

这是有意设计：新设备完成第一次成功采集前没有基准时间点，无法判断"沉默了多久"，强行触发会造成大量误报。注意：若该设备采集一直失败，CONSECUTIVE_FAIL 条件仍可命中，因为 CONSECUTIVE_FAIL 不依赖 `lastReadAt`。

### 5.2 维护模式开启（`maintenanceMode = true`）

当设备对应的阈值配置中 `maintenance_mode=true` 时，检测引擎在读取阈值后立即 `return`，**完全跳过该设备的所有检测逻辑**。

这意味着：维护模式期间不会新建告警，**同时也不会自动恢复已有的 ACTIVE/ACKED 告警**。维护结束后关闭维护模式，设备重新进入检测循环，已有告警如满足自动恢复条件（§1 tryAutoResolve）才会被关闭。

### 5.3 同设备同类型已有 ACTIVE 或 ACKED 告警

`alarms.findActive(meterId, type)` 若查到该设备该类型已存在状态为 `ACTIVE` 或 `ACKED` 的告警，则**不重复创建**，直接跳过本轮触发。

告警详情字段（`detail` JSONB）不会在此时更新（首版行为）。已有告警的生命周期由状态机管理，避免重复告警刷屏。

### 5.4 刚恢复后立即又中断（抑制窗口防抖）

若设备刚刚完成自动恢复（`RESOLVED`），在 `suppression-window-seconds`（默认 300 秒，即 5 分钟）内又再次中断，此轮检测会**命中触发条件**，但 `tryAutoResolve` 逻辑不会在窗口期内关闭这个刚创建的告警。

实际效果：快速抖动（恢复后 5 分钟内再次失联）不会在同一分钟内创建—恢复—再创建，避免告警风暴。

### 5.5 告警刚触发后立即恢复（抑制窗口防抖，反向）

`tryAutoResolve` 的触发条件是 `(now - triggeredAt) > suppressionWindowSeconds`。刚触发的告警（triggeredAt 距今 < 5 分钟），即使设备此时已恢复正常，**本轮不会被自动关闭**。

这保证告警有足够曝光时间让运维人员看到，而不是被瞬间"闪烁"后自动消失。

### 5.6 设备在 collector 配置中不存在

没有 collector 采集的设备，`CollectorService.snapshots()` 的返回列表中不包含该设备的快照，alarm 模块**完全不知道该设备的存在**，不会触发任何告警。

常见原因：设备在 `meters` 表有记录，但对应的 collector 配置文件（YAML/DB 配置）中未加载该设备。验证方法：`curl http://localhost:8080/api/v1/collector/status` 查看返回设备列表。

### 5.7 阈值设置过于宽松

若 `silent_timeout_seconds=86400`（24 小时），即使设备掉线 23 小时也不会触发；若 `consecutive_fail_count=100`，需要连续失败 100 次才触发。

这类情况不是系统 bug，而是配置问题。告警沉默时，检查 `alarm_rules_override` 表中各设备的实际阈值是否合理（参见 §2.4 和 §3.4 调优建议）。

---

## 6. collector 重启后的检测行为

collector 进程重启会**清空所有设备的内存状态**（包括 `consecutiveCycleErrors` 计数）。告警检测模块对两类告警的应对方式不同：

| 告警类型 | 重启后行为 | 原因 |
|----------|------------|------|
| **SILENT_TIMEOUT** | **不受影响** — 检测逻辑依赖 `snap.lastReadAt`，该字段通过持久化存储维护（DB 或 collector 内存快照均有记录）。设备重新连接 collector 后，若正常上报，`lastReadAt` 会更新；若持续中断，`lastReadAt` 不更新，阈值超时后仍会触发告警。 | `lastReadAt` 是时间戳，不依赖进程内存 |
| **CONSECUTIVE_FAIL** | **会出现延迟** — `consecutiveCycleErrors` 是 collector 进程内存中的计数器，重启后归零。设备若在 collector 重启时处于持续失败状态，需要重新积累 N 个失败周期，才能再次触发告警。 | 计数依赖进程内存，重启即清零 |

### 6.1 延迟时间估算（CONSECUTIVE_FAIL）

以默认配置为例：

- `consecutive-fail-count = 3`（需连续失败 3 次）
- `poll-interval-seconds = 60`（每轮 60 秒）

collector 重启后，**最长需要等待约 3 × 60 = 180 秒（3 分钟）**，才能重新触发 CONSECUTIVE_FAIL 告警。

若自定义阈值 N 更大（如 `consecutive-fail-count=10`），则延迟 = N × poll-interval 秒（最长 10 分钟）。

### 6.2 实践建议

- collector 重启后，**5 分钟内不要判定告警系统失灵**，这是 CONSECUTIVE_FAIL 正常重新积累计数的时间窗口。
- 可通过 Prometheus 指标 `collector_consecutive_errors{device_id="..."}` 观察各设备的实时失败计数，确认 collector 已重新开始统计。
- SILENT_TIMEOUT 类告警在 collector 重启后应在下一个检测周期（最长 60 秒）内正常恢复工作，若未见告警应检查 collector 是否成功启动并完成首轮采集。

---

## 7. 监控覆盖范围

### 7.1 首版仅监控的设备

alarm 检测模块**仅**监控 `CollectorService.snapshots()` 返回列表中的设备。该列表由 collector 模块根据实际配置文件动态维护，**只包含当前已加载并正在采集的设备**。

验证当前被监控的设备列表：
```bash
curl http://localhost:8080/api/v1/collector/status
```

返回的 `devices` 数组即为告警监控的完整设备集合。**不在此列表中的设备，无论状态如何，alarm 模块都不会产生任何告警。**

### 7.2 不在监控范围内的设备类别

| 类别 | 原因 |
|------|------|
| **mock-data CLI 工具直接写入 InfluxDB 的虚拟设备** | 该工具绕过 collector，直接写时序数据，collector 内存中无对应快照 |
| **通过 REST API 直接写入 ts_rollups 的历史数据补录** | 历史补录不经过 collector 采集流程，不更新快照状态 |
| **在 meters 表存在但未被任何 collector 配置加载的"孤儿"设备** | 设备存在于 DB，但 collector 未知该设备，不采集也不产生快照 |

### 7.3 设计原则说明

alarm 模块的设计原则是监控**"采集中断"**，即 collector 采集行为本身是否正常。告警链路刻意**不查询 InfluxDB**，原因：

1. **避免引入额外查询负担**：每轮扫描若要查 InfluxDB，在设备数量多时会大幅增加延迟
2. **时序数据存在延迟**：InfluxDB 写入与查询之间存在几秒到几十秒的延迟，用于实时告警判断不可靠
3. **职责分离**：collector 负责"采集是否正常"，InfluxDB 负责"历史数据存储"，两者不应耦合

若需监控历史数据质量或补录数据完整性，应在独立的数据质量检测模块中实现，不属于 alarm 检测引擎的范畴。

---

## 8. 故障排查决策树

### 8.1 "设备掉线了但没收到告警"

**现象**：运维人员发现某设备停止上报数据，但告警中心未产生任何告警记录。

**排查步骤（按顺序逐项检查，排查到原因即可停止）：**

1. **确认设备是否在 collector 监控列表中**
   ```bash
   curl http://localhost:8080/api/v1/collector/status | jq '.devices[].meterCode'
   ```
   若目标设备的 `meterCode` 不在列表中，则 alarm 模块永远不会知道该设备的状态。修复：在 collector 配置文件中添加该设备并重启 collector。

2. **检查设备是否处于维护模式**
   ```sql
   SELECT device_id, maintenance_mode, silent_timeout_seconds, consecutive_fail_count
   FROM alarm_rules_override
   WHERE device_id = :device_id;
   ```
   若 `maintenance_mode = true`，检测完全跳过。修复：将 `maintenance_mode` 改为 `false`。

3. **检查 `silent_timeout_seconds` 是否配置过长**
   ```sql
   -- 查看设备级覆盖（或全局默认）
   SELECT silent_timeout_seconds FROM alarm_rules_override WHERE device_id = :device_id;
   -- 若无设备级覆盖，看全局配置
   -- 对应 application.yml: ems.alarm.default-silent-timeout-seconds
   ```
   计算：设备掉线时长 < `silent_timeout_seconds`，则尚未触发。参见 §2.4 调优建议调整阈值。

4. **检查同类型告警是否已存在**
   ```sql
   SELECT id, alarm_type, status, triggered_at
   FROM alarms
   WHERE meter_id = (SELECT id FROM meters WHERE code = :meter_code)
     AND alarm_type = 'SILENT_TIMEOUT'
     AND status IN ('ACTIVE', 'ACKED')
   ORDER BY triggered_at DESC
   LIMIT 5;
   ```
   若已有 ACTIVE/ACKED 告警，系统不重复创建。历史告警可能是之前未处理的遗留记录。

5. **检查是否在抑制窗口内（刚 RESOLVED < 5 分钟）**
   ```sql
   SELECT id, status, triggered_at, resolved_at
   FROM alarms
   WHERE meter_id = (SELECT id FROM meters WHERE code = :meter_code)
     AND alarm_type = 'SILENT_TIMEOUT'
     AND status = 'RESOLVED'
   ORDER BY resolved_at DESC
   LIMIT 1;
   ```
   若 `resolved_at` 距现在 < `suppression-window-seconds`（默认 300 秒），系统处于抑制期，等待窗口过期后下一轮检测会重新触发。

6. **确认告警检测调度器是否正常运行**
   ```bash
   grep "AlarmDetector" /var/log/ems-app/ems-app.log | tail -20
   ```
   正常情况下每 60 秒应有一条 scan 日志输出。若无输出，检查 ems-app 进程是否正常、调度线程是否卡死。

---

### 8.2 "告警刚自动恢复，又立刻重新触发"

**现象**：某设备的 SILENT_TIMEOUT 或 CONSECUTIVE_FAIL 告警反复在短时间内创建和恢复，产生大量重复告警记录。

**排查步骤：**

1. **确认是否网络抖动**
   ```
   # 查看 Prometheus 指标
   collector_consecutive_errors{device_id="<设备ID>"}
   # 或看告警历史时间线
   ```
   ```sql
   SELECT triggered_at, resolved_at,
          EXTRACT(EPOCH FROM (resolved_at - triggered_at)) AS duration_seconds
   FROM alarms
   WHERE meter_id = :meter_id
   ORDER BY triggered_at DESC
   LIMIT 10;
   ```
   若告警持续时间均在 1 ～ 5 分钟内反复循环，极可能是网络抖动，建议增大 `consecutive_fail_count` 阈值。

2. **检查 `suppression-window-seconds` 是否过短**
   当前默认值为 300 秒（5 分钟）。若设备恢复不稳定（如每隔 3 分钟连接/断连一次），应将抑制窗口调大：
   ```yaml
   # application.yml
   ems:
     alarm:
       suppression-window-seconds: 900  # 改为 15 分钟
   ```

3. **评估设备阈值是否过严**
   - SILENT_TIMEOUT 阈值过小（如仅 1～2 个采集周期）会使正常抖动频繁触发，参见 §2.4 调整
   - CONSECUTIVE_FAIL 阈值过小（如 1～2 次）会使单次失败就触发，参见 §3.4 调整

**可能根因及修复**：
- 网络抖动 → 增大 `consecutive_fail_count` 至 5 ～ 10，提高容错
- 设备心跳不稳定 → 增大 `silent_timeout_seconds` 至 3 ～ 5 倍采集周期
- 抑制窗口太短 → 增大 `suppression-window-seconds` 至 600 ～ 1800 秒

---

### 8.3 "维护模式期间还在产生告警"

**现象**：运维人员已将设备设为维护模式，但仍收到来自该设备的告警通知。

**排查步骤：**

1. **确认告警的触发时间戳**
   ```sql
   SELECT id, alarm_type, status, triggered_at
   FROM alarms
   WHERE meter_id = :meter_id
   ORDER BY triggered_at DESC
   LIMIT 5;
   ```
   查看 `triggered_at`：若告警在维护模式**生效前**已创建，则属于历史遗留记录，不是维护模式失效。维护模式只阻止**新告警的创建**，不会自动关闭历史活动告警。

2. **确认维护模式覆盖记录真实存在且生效**
   ```sql
   SELECT device_id, maintenance_mode, updated_at
   FROM alarm_rules_override
   WHERE device_id = :device_id
     AND maintenance_mode = true;
   ```
   若无记录，说明维护模式从未设置成功，检查 UI 操作是否完成提交。

3. **确认未误解"全局维护模式"**
   系统**不支持全局默认开启维护模式**，只能按设备单独设置。若需要批量维护，需逐台设备更新 `alarm_rules_override` 表。

4. **检查是否是检测周期延迟导致**
   维护模式的配置读取发生在**每一轮检测开始时**（`thresholds.resolve(meterId)` 在每轮扫描中实时查询），因此维护模式最长会延迟一个检测周期（默认 60 秒）才生效。设置后请等待最多 60 秒再观察。

**可能根因及修复**：
- 告警在维护模式生效前已存在 → 手动将告警 ACKED 或关闭，历史告警不会自动消失
- 覆盖记录未写入数据库 → 检查 `alarm_rules_override` 表记录，必要时手动 INSERT
- 60 秒内还未生效 → 属正常延迟，等待下一轮扫描

---

## 更多资源

- [alarm-config-reference.md](./alarm-config-reference.md) — 完整配置项参考（默认值、有效范围、示例）
- [alarm-business-rules.md](./alarm-business-rules.md) — 告警业务规则（状态机、通知策略、ACK 流程）
- [alarm-data-model.md](./alarm-data-model.md) — 告警数据模型（表结构、JSONB 字段说明、索引设计）
- spec §3 — 原始需求规格（触发条件、验收标准）
