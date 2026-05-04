# 可观测性栈 · 业务 Metrics 字典

> **更新于**：2026-04-29（Phase B 完成时）
> **撰写依据**：[spec §8](../superpowers/specs/2026-04-29-observability-stack-design.md)（§8.1–§8.7）+ Phase B1–B4 实际源码
> **受众**：数据/集成工程师、Dashboard 开发者、Prometheus 报警规则作者
> **关联文档**：[observability-config-reference.md](./observability-config-reference.md)（env 参数 + 启停）

---

## 1. 概述

本字典描述 factory-ems 应用层注册的 17 个业务 metrics（Phase B），分为采集、报警、计量、应用跨模块四类。

**怎么用这份文档：**

- 按模块查 → 看"模块清单"表（第 3 节）
- 按指标名搜 → Ctrl+F 搜名称（如 `ems.collector.poll.duration`）
- 写 PromQL → 复制"典型 PromQL"列的示例，替换 label 值
- 加新指标 → 看第 8 节"新增指标 checklist"

> **Prometheus 命名惯例**：Prometheus 抓取时，指标名称的 `.` 自动转换为 `_`。
> 例如 `ems.collector.poll.duration` 在 PromQL 中写作 `ems_collector_poll_duration_seconds_*`（Timer 自动展开 `_count` / `_sum` / `_bucket`）。

---

## 2. 公共标签（Common Labels）

所有业务指标和 Spring Boot Actuator 默认指标会自动带上下面两个公共标签，由 `ObservabilityConfig` 通过 `MeterRegistryCustomizer` 在注册表级别注入：

| Label | 值来源 | 示例值 | 说明 |
|-------|--------|--------|------|
| `application` | `spring.application.name`（默认 `factory-ems`） | `factory-ems` | 在同一 Prometheus 中部署多套实例时区分应用 |
| `instance` | 环境变量 `HOSTNAME`（容器/主机自动填充） | `prod-ems-01` | 区分多实例；单机部署时通常为主机名 |

**实施差异（spec §8.1）**：spec 提到了 `module` 标签（从包名派生），但 v1 实现中 `ObservabilityConfig` 只注入 `application` 和 `instance`，`module` 标签未注册。要区分模块，在 PromQL 中按指标名前缀过滤（`ems_collector_*`、`ems_alarm_*` 等）。

---

## 3. 模块清单

| 模块 | Java 源文件 | 指标数量 | 状态 |
|------|------------|---------|------|
| ems-collector（采集） | `CollectorMetrics.java` | 5 | Phase B1 完成 |
| ems-alarm（报警） | `AlarmMetrics.java` | 5 | Phase B2 完成 |
| ems-meter（计量） | `MeterMetrics.java` | 3 | Phase B3 完成 |
| ems-app（应用跨模块） | `AppMetrics.java` + `SchedulerInstrumentationAspect.java` | 2 active + 1 AOP + 1 deferred = 4 | Phase B4 完成 |
| **合计** | | **17（含 1 个 v1 deferred）** | |

---

## 4. 指标详表

### 4.1 ems-collector 采集模块（5 个）

| # | 指标名称 | 类型 | 单位 | Labels | 含义 | 数据更新方式 |
|---|---------|------|------|--------|------|------------|
| 1 | `ems.collector.poll.duration` | Timer (histogram) | seconds | `adapter` | 一轮设备轮询（poll cycle）耗时分布 | 事件驱动：每次 poll cycle 结束后记录 |
| 2 | `ems.collector.devices.online` | Gauge | count（台） | — | 当前在线设备数（状态为 HEALTHY 或 DEGRADED 的设备之和） | 调度刷新：每 30s 由 `CollectorService.refreshDeviceGauges` 更新 |
| 3 | `ems.collector.devices.offline` | Gauge | count（台） | — | 当前离线设备数（状态为 UNREACHABLE 的设备数） | 调度刷新：同上，与 online 同步刷新 |
| 4 | `ems.collector.read.success.total` | Counter | count（次） | `device_id` | 设备读取成功累计次数 | 事件驱动：每个 poll cycle 全部成功后 +1 |
| 5 | `ems.collector.read.failure.total` | Counter | count（次） | `device_id`, `reason` | 设备读取失败累计次数 | 事件驱动：每个 poll cycle 失败后 +1 |

**Label 枚举值：**

`adapter`（派生自 Protocol 枚举小写）：

| 值 | 含义 |
|----|------|
| `modbus-tcp` | Modbus TCP 协议 |
| `modbus-rtu` | Modbus RTU 协议 |

`reason`（`KNOWN_REASONS` set 归一化，未匹配归为 `other`）：

| 值 | 含义 |
|----|------|
| `timeout` | 连接或读取超时 |
| `crc` | CRC 校验失败 |
| `format` | 响应格式错误 |
| `disconnected` | 设备断开连接 |
| `other` | 其他 / 未分类异常 |

**实施差异（粒度说明）**：spec §8.2 写的是"单次寄存器读"，但 v1 `DevicePoller` 的 poll 循环是"一次 cycle = 多寄存器顺序读"。实际粒度为 cycle 级：一次 cycle 所有寄存器全部成功 → `read.success.total +1`；cycle 失败（重试耗尽）→ `read.failure.total +1`。这不影响 `rate()` 报警规则的语义（详见 `CollectorMetrics.java` javadoc）。

**典型 PromQL：**

```promql
# 采集 p95 延迟（按 adapter 分，10 分钟窗口）
histogram_quantile(0.95,
  rate(ems_collector_poll_duration_seconds_bucket[10m])
)

# 各设备读取失败率（按 reason 分，5 分钟）
rate(ems_collector_read_failure_total[5m])

# 在线设备占比（用于 SLO / dashboard）
ems_collector_devices_online
  / (ems_collector_devices_online + ems_collector_devices_offline)
```

**报警覆盖**：`EmsCollectorPollSlow`（poll p95 > 30s）、`EmsCollectorOfflineDevices`（offline 占比 > 10%）— 详见 `docs/product/observability-slo-rules.md`（Phase D）。

---

### 4.2 ems-alarm 报警模块（5 个）

| # | 指标名称 | 类型 | 单位 | Labels | 含义 | 数据更新方式 |
|---|---------|------|------|--------|------|------------|
| 6 | `ems.alarm.detector.duration` | Timer (histogram) | seconds | — | 报警检测器一轮扫描耗时 | 事件驱动：`AlarmDetectorImpl.scan()` finally 块记录 |
| 7 | `ems.alarm.active.count` | Gauge | count（条） | `type` | 当前处于 ACTIVE 或 ACKED 状态的报警数 | 混合：detector scan 完成后刷新；`AlarmServiceImpl.resolve()` 手动恢复后同步更新 |
| 8 | `ems.alarm.created.total` | Counter | count（条） | `type` | 累计触发（创建）报警次数 | 事件驱动：`AlarmDetectorImpl.fire()` 中递增 |
| 9 | `ems.alarm.resolved.total` | Counter | count（条） | `reason` | 累计恢复（resolve）报警次数 | 事件驱动：`tryAutoResolve`（自动）或 `AlarmServiceImpl.resolve()`（手动）后递增 |
| 10 | `ems.alarm.webhook.delivery.duration` | Timer (histogram) | seconds | `outcome`, `attempt` | webhook 单次调用耗时（含重试区分） | 事件驱动：`WebhookChannelImpl` 每次投递后记录 |

**Label 枚举值：**

`type`（报警类型，`KNOWN_TYPES` set 归一化）：

| 值 | 含义 |
|----|------|
| `silent_timeout` | 静默超时报警 |
| `consecutive_fail` | 连续失败报警 |
| `other` | 其他 / 未分类类型 |

`reason`（恢复原因，`KNOWN_REASONS` set 归一化）：

| 值 | 含义 |
|----|------|
| `auto` | 检测器自动恢复（设备恢复正常） |
| `manual` | 用户手动恢复 |
| `other` | 其他原因 |

`outcome` / `attempt`（webhook 投递结果）：

| 标签 | 取值 | 说明 |
|------|------|------|
| `outcome` | `success` / `failure` | 投递是否成功；未知值归为 `failure`（保守策略） |
| `attempt` | `1` / `2` / `3` | 第几次尝试；超出范围自动钳到 [1, 3] |

**典型 PromQL：**

```promql
# 检测器 p95 扫描耗时
histogram_quantile(0.95,
  rate(ems_alarm_detector_duration_seconds_bucket[10m])
)

# 当前各类型活跃报警数
ems_alarm_active_count

# webhook 失败率（所有 attempt 汇总）
sum(rate(ems_alarm_webhook_delivery_duration_seconds_count{outcome="failure"}[5m]))
  / sum(rate(ems_alarm_webhook_delivery_duration_seconds_count[5m]))

# 某类型报警创建速率（近 1 小时）
rate(ems_alarm_created_total{type="consecutive_fail"}[1h])
```

**报警覆盖**：`EmsAlarmDetectorSlow`（p95 > 10s）、`EmsWebhookFailureRate`（失败率 > 20%）、`EmsAlarmBacklog`（静默超时积压 > 50 条）— 详见 `docs/product/observability-slo-rules.md`（Phase D）。

---

### 4.3 ems-meter 计量模块（3 个）

| # | 指标名称 | 类型 | 单位 | Labels | 含义 | 数据更新方式 |
|---|---------|------|------|--------|------|------------|
| 11 | `ems.meter.reading.lag.seconds` | Gauge | seconds（秒） | — | 所有仪表中最晚一条读数与当前时刻的时间差（最大值） | 调度刷新：每 30s 由 `CollectorService.refreshDeviceGauges` 按 `now - max(lastReadAt)` 计算后写入 |
| 12 | `ems.meter.reading.insert.rate` | Counter | count（条） | `energy_type` | 累计成功入库（写入 InfluxDB）的读数行数 | 事件驱动：`InfluxReadingSink` 每次 Influx 写成功后递增 |
| 13 | `ems.meter.reading.dropped.total` | Counter | count（条） | `reason` | 因校验失败被丢弃的读数累计数 | 事件驱动：`InfluxReadingSink` 丢弃读数时递增 |

**Label 枚举值：**

`energy_type`（能源类型，`KNOWN_ENERGY_TYPES` set 归一化；由调用方从 `EnergyTypeRepository` 解析后传入）：

| 值 | 含义 |
|----|------|
| `elec` | 电力 |
| `water` | 水 |
| `gas` | 气体（天然气等） |
| `steam` | 蒸汽 |
| `other` | 其他 / 未识别能源类型 |

`reason`（丢弃原因，`KNOWN_DROP_REASONS` set 归一化）：

| 值 | 含义 | v1 状态 |
|----|------|---------|
| `duplicate` | 重复读数 | 预留，v1 暂未触发 |
| `out_of_range` | 超出合理范围 | 预留，v1 暂未触发 |
| `format_error` | 格式错误 | 预留，v1 暂未触发 |
| `other` | 其他（含仪表未找到路径） | **v1 唯一实际触发路径**（meter == null） |

**实施差异（dropped reason）**：v1 `InfluxReadingSink` 只在"仪表未找到（meter == null）"时记录 `reason=other`。`duplicate` / `out_of_range` / `format_error` 路径要等校验链路完善后（Phase D+）才会触发，枚举值已预留。

**典型 PromQL：**

```promql
# 数据新鲜度（SLO：max lag <= 5min = 300s）
max(ems_meter_reading_lag_seconds)

# 各能源类型入库速率（条/分）
sum by (energy_type) (rate(ems_meter_reading_insert_rate_total[5m])) * 60

# 数据丢弃速率（实时监控）
rate(ems_meter_reading_dropped_total[5m])
```

**报警覆盖**：`EmsDataFreshnessCritical`（lag > 600s = 10min）— 详见 `docs/product/observability-slo-rules.md`（Phase D）。

---

### 4.4 ems-app 应用跨模块（4 个，含 1 个 deferred）

| # | 指标名称 | 类型 | 单位 | Labels | 含义 | 数据更新方式 | v1 状态 |
|---|---------|------|------|--------|------|------------|---------|
| 14 | `ems.app.audit.write.total` | Counter | count（条） | `action` | 审计日志写入意图次数 | 事件驱动：`AuditEventCountingListener` 监听 `AuditEvent` 后递增 | 已注册 |
| 15 | `ems.app.exception.total` | Counter | count（次） | `type` | `GlobalExceptionHandler` 兜底捕获的异常次数 | 事件驱动：`@ExceptionHandler(Exception.class)` 兜底分支递增 | 已注册 |
| 16 | `ems.app.scheduled.duration` | Timer (histogram) | seconds | `task` | 所有 `@Scheduled` 任务执行耗时 | 事件驱动：`SchedulerInstrumentationAspect` AOP `@Around` 自动拦截，无需手动埋点 | 已注册（AOP） |
| 17 | `ems.app.scheduled.drift.seconds` | Gauge | seconds | `task` | 调度任务实际触发时间与期望触发时刻的偏差 | — | **v1 deferred**（见第 7 节） |

**Label 枚举值：**

`action`（审计操作类型）：来自 `AuditEvent.action()` 的字符串（如 `CREATE_ALARM`、`RESOLVE_ALARM`、`LOGIN`）；空值归一化为 `other`。非固定枚举，随业务扩展增长，cardinality 受业务操作类型数量控制（预计 < 30）。

`type`（异常类型）：来自 `ex.getClass().getSimpleName()`（如 `NullPointerException`、`IllegalArgumentException`）。只计 unhandled 异常——`BusinessException`、`AccessDeniedException` 等业务预期异常在专属 handler 处理，不进入兜底分支。空值归一化为 `other`。

`task`（调度任务标识）：由 `SchedulerInstrumentationAspect` 从 AOP 切入点自动派生，格式为 `SimpleClassName.methodName`。当前已知任务：

| task 值 | 所属类 | 功能 |
|---------|--------|------|
| `AlarmDetectorImpl.run` | `AlarmDetectorImpl` | 报警检测扫描 |
| `CollectorService.refreshDeviceGauges` | `CollectorService` | 刷新设备在线/离线 gauge |
| `RollupJob.*`（4 个） | `ems-timeseries` 中的汇总任务 | 历史数据汇总 |

新增 `@Scheduled` 方法被 AOP 自动捕获，不用手动注册；`task` 值随之增长，预计 < 50。

**典型 PromQL：**

```promql
# 各调度任务 p95 耗时
histogram_quantile(0.95,
  sum by (task, le) (
    rate(ems_app_scheduled_duration_seconds_bucket[10m])
  )
)

# 未处理异常速率（用于报警 EmsAppHighErrorRate）
rate(ems_app_exception_total[5m])

# 审计写入速率（按操作类型）
sum by (action) (rate(ems_app_audit_write_total_total[5m]))
```

**报警覆盖**：`EmsAppHighErrorRate`（exception rate > 1/s）、`EmsSchedulerDrift`（调度漂移 > 60s，Phase D PromQL 推导）— 详见 `docs/product/observability-slo-rules.md`（Phase D）。

---

## 5. Cardinality 控制规则

高基数 label 会显著增加 Prometheus 资源占用。factory-ems 遵循以下规则（参见 spec §8.6）：

### 5.1 高基数 label 收口

`device_id` 是唯一高基数 label，仅在以下两个指标上出现：

| 指标 | label |
|------|-------|
| `ems.collector.read.success.total` | `device_id` |
| `ems.collector.read.failure.total` | `device_id` |

v1 阶段工厂设备规模预计 < 5000 台，cardinality 可控（success：5000 序列；failure：5000 × 5 reason = 25000 序列）。设备规模增长后，再评估是否需要降采样或聚合录制规则。

### 5.2 其余 label 均为低基数枚举

| label | 最大基数 | 控制方式 |
|-------|---------|---------|
| `adapter` | 2 | Protocol 枚举派生 |
| `reason`（collector） | 5 | `KNOWN_REASONS` set 归一化 |
| `type`（alarm） | 3 | `KNOWN_TYPES` set 归一化 |
| `reason`（alarm resolved） | 3 | `KNOWN_REASONS` set 归一化 |
| `outcome` | 2 | `KNOWN_OUTCOMES` set 归一化 |
| `attempt` | 3 | `Math.max(1, Math.min(3, attempt))` 钳位 |
| `energy_type` | 5 | `KNOWN_ENERGY_TYPES` set 归一化 |
| `reason`（meter dropped） | 4 | `KNOWN_DROP_REASONS` set 归一化 |
| `action`（audit） | < 30 | 业务层定义，无外部用户输入 |
| `type`（exception） | < 50 | `ex.getClass().getSimpleName()`，来自代码路径 |
| `task` | < 50 | AOP 自动派生，随代码变更而非用户输入 |

### 5.3 禁止外部输入直接拼 label

所有 label 值在埋点处统一做归一化（`KNOWN_*` set 检查 + `other` 兜底）。禁止把用户输入、API 参数或不可控外部字段直接用作 label value，防止 cardinality 爆炸（详见 spec §8.6 cardinality 控制）。

---

## 6. Spring Boot Actuator 默认指标

下列指标由 Spring Boot Actuator + Micrometer 自动注册，不用重复埋点，在 Grafana dashboard 中直接用（参见 spec §8.7）：

| 指标前缀 | 类型 | 用途 |
|---------|------|------|
| `jvm_memory_used_bytes` | Gauge | JVM 内存使用量（heap + non-heap，按 area/id） |
| `jvm_gc_pause_seconds` | Timer | GC 暂停耗时分布 |
| `jvm_threads_live_threads` | Gauge | JVM 当前活跃线程数 |
| `http_server_requests_seconds` | Timer (histogram) | HTTP 请求耗时（含 uri、method、status、outcome） |
| `hikaricp_connections_*` | Gauge/Counter | 连接池状态（active/idle/pending/max） |
| `logback_events_total` | Counter | Logback 日志事件数（按 level 分） |
| `tomcat_sessions_*` | Gauge/Counter | Tomcat Session 状态（已嵌入容器） |

这些指标覆盖 JVM 健康、HTTP SLO、数据库连接池、日志异常率四个维度，与业务指标互补。

**SLO 关联**：`http_server_requests_seconds_bucket` 用于 API 延迟 SLO（p99 ≤ 1s）；`hikaricp_connections_active / hikaricp_connections_max` 用于连接池耗尽报警（`EmsDbConnectionPoolExhausted`）。

---

## 7. `ems.app.scheduled.drift.seconds` — v1 状态说明

### 7.1 背景

spec §8.5 定义了 `ems.app.scheduled.drift.seconds`（Gauge，`{task}`），用来追踪调度任务实际触发时间和期望触发时间之间的绝对偏差。

### 7.2 v1 现状（deferred）

v1 应用层不注册此 Gauge。`AppMetrics.java` 中有注释说明。

原因：

1. `SchedulerInstrumentationAspect` 在 `@Around` 中只能拿到任务执行的实际开始时刻，拿不到 Spring Scheduler 的期望触发时刻（`@Scheduled(fixedRate=...)` 的下次期望时间未在 Spring Context 公开 API 中暴露）
2. 对单机 on-prem 部署，Phase D 通过 PromQL 基于 `ems_app_scheduled_duration` timer 的时间序列推导漂移，不需要应用侧 Gauge

### 7.3 Phase D PromQL 推导方法

调度漂移通过以下方式近似计算（Phase D 实施，详见 `docs/product/observability-slo-rules.md`）：

```promql
# 以 AlarmDetectorImpl.run（30s 周期任务）为例
# 取最近一次记录时间戳，与当前时间差减去期望间隔，得到漂移近似值
abs(
  time() - timestamp(
    max_over_time(
      ems_app_scheduled_duration_seconds_count{task="AlarmDetectorImpl.run"}[2m]
    )
  ) - 30
)
```

**报警规则**：`EmsSchedulerDrift`（漂移 > 60s，for 5m）在 Phase D 通过此推导实现。

### 7.4 未来启用方法（如需应用侧 Gauge）

如果后续需要应用层精确漂移 Gauge，在 `SchedulerInstrumentationAspect` 中扩展：

1. 在类初始化时记录每个 `task` 对应的期望 `fixedRate`/`fixedDelay` 配置值
2. 在 `@Around` 切入点记录 `actualStart = Instant.now()`
3. 通过上次触发时刻加期望间隔计算 `expectedStart`
4. 写入：`registry.gauge("ems.app.scheduled.drift.seconds", tags, abs(drift.getSeconds()))`

> Spring 的 `TaskScheduler` 不暴露"期望下次触发时刻"，需要在注册时持有固定 rate/delay 配置自行计算。

---

## 8. 新增指标 Checklist

在 factory-ems 中新增业务指标，按以下 7 步走：

1. **命名约定**：使用 `ems.<module>.<concept>.<unit>` 格式
   - 正例：`ems.collector.poll.duration`、`ems.alarm.created.total`
   - 不要重名；不要在名称中包含 label 值（不写 `ems.alarm.timeout.count`，用 `reason=timeout` label 代替）

2. **Label 上限**：每个指标的 label 数量 ≤ 5；高基数 label（如设备 ID 类）要提前评估最大基数

3. **注册时机**：
   - Counter / Timer：lazy 注册（`Counter.builder(...).register(registry)` 首次调用时自动创建）
   - Gauge：在构造函数中用 `AtomicLong` holder 注册（eager），避免 lazy 注册导致首次抓取无值

4. **归一化未知值**：为每个枚举型 label 维护 `static final Set<String> KNOWN_*`，未匹配的值归为 `other`

5. **加单元测试**：至少验证：正常路径递增、未知 label 归一化为 `other`、`NOOP` 构造函数不抛异常

6. **更新本字典**：在第 4 节对应模块中加一行，填写名称、类型、单位、Labels、含义、数据更新方式

7. **评估 cardinality**：估算新 label 的最大基数，与现有序列数叠加后确认总量在 Prometheus 服务器资源预算内（参见 spec §8.6 cardinality 控制）

---

## 9. 常用 PromQL 模板

下面这些模板可以直接在 Grafana Explore 或报警规则里用：

```promql
# 模板 1：Counter 增长率（events/秒）
rate(<metric_name>_total[<range>])
# 示例：读取失败率（按 reason）
rate(ems_collector_read_failure_total[5m])

# 模板 2：Timer histogram 分位数（按维度分组）
histogram_quantile(<quantile>,
  sum by (<groupby_label>, le) (
    rate(<metric_name>_seconds_bucket[<range>])
  )
)
# 示例：采集 p99 延迟（按 adapter 分组）
histogram_quantile(0.99,
  sum by (adapter, le) (
    rate(ems_collector_poll_duration_seconds_bucket[10m])
  )
)

# 模板 3：Gauge 最大值（跨实例聚合）
max(ems_meter_reading_lag_seconds)

# 模板 4：Counter 比率（失败 / 总量）
sum(rate(ems_alarm_webhook_delivery_duration_seconds_count{outcome="failure"}[5m]))
  / sum(rate(ems_alarm_webhook_delivery_duration_seconds_count[5m]))

# 模板 5：Counter 时间窗内总量
increase(ems_alarm_created_total[1h])

# 模板 6：Gauge + 阈值比较（用于报警 expr）
max(ems_meter_reading_lag_seconds) > 300

# 模板 7：多维度 sum by（Dashboard 数据聚合）
sum by (energy_type) (
  rate(ems_meter_reading_insert_rate_total[5m])
) * 60

# 模板 8：调度任务 p95 耗时（Dashboard 表格视图）
histogram_quantile(0.95,
  sum by (task, le) (
    rate(ems_app_scheduled_duration_seconds_bucket[5m])
  )
)
```

> **Prometheus 命名转换提醒**：metric 名中的 `.` 变为 `_`；Timer 自动展开 `_count` / `_sum` / `_bucket` / `_max`；Counter 名称以 `_total` 结尾。

---

## 10. 附录 — 抓取协议

### 10.1 暴露端点

factory-ems 通过 Spring Boot Actuator 暴露 Prometheus 格式指标：

```
GET http://<host>:8080/actuator/prometheus
```

端点在 `application-prod.yml` 中配置（`management.endpoints.web.exposure.include=prometheus,health`），详见 [observability-config-reference.md](./observability-config-reference.md)。

### 10.2 Prometheus exposition format

响应为纯文本，格式示例：

```
# HELP ems_alarm_created_total Total alarms created
# TYPE ems_alarm_created_total counter
ems_alarm_created_total{application="factory-ems",instance="prod-ems-01",type="consecutive_fail"} 42.0

# HELP ems_collector_poll_duration_seconds 一轮设备采集耗时分布
# TYPE ems_collector_poll_duration_seconds histogram
ems_collector_poll_duration_seconds_bucket{adapter="modbus-tcp",le="0.005"} 12.0
ems_collector_poll_duration_seconds_bucket{adapter="modbus-tcp",le="+Inf"} 150.0
ems_collector_poll_duration_seconds_count{adapter="modbus-tcp"} 150.0
ems_collector_poll_duration_seconds_sum{adapter="modbus-tcp"} 37.42
```

### 10.3 Prometheus 抓取配置

Prometheus 通过 `ops/observability/prometheus/prometheus.yml` 配置抓取（Phase C 实施）：

```yaml
scrape_configs:
  - job_name: factory-ems
    scrape_interval: 15s
    static_configs:
      - targets: ['factory-ems:8080']
    metrics_path: /actuator/prometheus
```

### 10.4 Metrics API（Phase G）

如需通过 REST API 而非 Prometheus 直接查询指标数据，参见 `docs/product/observability-metrics-api.md`（Phase G3，待实施）。
