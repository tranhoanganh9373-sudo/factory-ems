# 可观测性 · SLO 规则与告警参考

> **更新于**：2026-04-29（Phase D 完成时）
> **撰写依据**：spec §9（SLO + 告警设计）+ Phase D 实际落地规则文件
> **受众**：客户管理 / 运维工程师（SRE）
> **文档关系**：
> - [observability-config-reference.md](./observability-config-reference.md) — 环境变量 / 启停 / 资源预算
> - [observability-metrics-dictionary.md](./observability-metrics-dictionary.md) — 17 个业务指标字典
> - [observability-deployment.md](../ops/observability-deployment.md) — 部署流程

---

## 1. 概述

本文档定义 factory-ems 可观测性栈的 **4 个服务级别目标（SLO）**、**16 条告警规则**（5 critical + 9 warning + 2 burn-rate）、**Alertmanager 路由策略**与**客户合同对应关系**，面向负责保障系统运行质量的运维工程师与客户管理人员。

架构一览：

```
Prometheus 抓取 → 录制规则（SLI 计算）→ 告警规则（阈值判断）→ Alertmanager（路由 + 抑制）→ 通知渠道
```

---

## 2. 四大 SLO

### 2.1 可用性 SLO（Availability）

| 项目 | 值 |
|------|----|
| **目标** | 99.5%（30 天滚动窗口） |
| **文件** | `ops/observability/prometheus/rules/slo-availability.yml` |
| **SLI 含义** | 过去 30 天 factory-ems 实例的平均在线时间占比 |
| **v1 状态** | 已上线 |

**关键 PromQL**（来自 D1 录制规则）：

```promql
# SLI — 30 天平均可用率
ems:slo:availability:sli_30d
  = avg_over_time(up{job="factory-ems"}[30d])

# 目标值（常量）
ems:slo:availability:objective
  = vector(0.995)

# 错误预算剩余（0..1，0 = 预算耗尽）
ems:slo:availability:error_budget_remaining
  = clamp_min(
      (ems:slo:availability:sli_30d - ems:slo:availability:objective)
      / (1 - ems:slo:availability:objective),
      0
    )
```

**错误预算解释**：
- 30 天允许不可用时间 = 30 × 24 × 60 × (1 - 0.995) ≈ **216 分钟（约 3.6 小时）**
- `error_budget_remaining = 1.0` 表示预算完整未用
- `error_budget_remaining = 0.5` 表示已用一半（约 1.8 小时不可用）
- `error_budget_remaining = 0.0` 表示本月 SLO 已违约

---

### 2.2 API 延迟 SLO（Latency）

| 项目 | 值 |
|------|----|
| **目标** | p99 ≤ 1 秒（成功请求，5 分钟滚动窗口） |
| **文件** | `ops/observability/prometheus/rules/slo-latency.yml` |
| **SLI 含义** | 99% 的成功 HTTP 请求响应时间不超过 1 秒 |
| **v1 状态** | 已上线 |

**关键 PromQL**（来自 D1 录制规则）：

```promql
# SLI — 5 分钟窗口 p99 延迟（秒）
ems:slo:latency:p99_5m
  = histogram_quantile(0.99,
      sum by (le) (
        rate(http_server_requests_seconds_bucket{job="factory-ems",outcome="SUCCESS"}[5m])
      )
    )

# 目标值（常量，单位：秒）
ems:slo:latency:objective_seconds
  = vector(1.0)

# 错误预算剩余（p99 越低，预算越充裕）
ems:slo:latency:error_budget_remaining
  = clamp_min(
      (ems:slo:latency:objective_seconds - ems:slo:latency:p99_5m)
      / ems:slo:latency:objective_seconds,
      0
    )
```

**错误预算解释**：
- 预算衡量的是"当前 p99 距离上限还有多远"
- p99 = 0.5s → 剩余 50% 预算；p99 = 1s → 预算归零；p99 > 1s → 已违约
- v1 延迟 SLO 的 burn-rate 告警列入路线图（§10）

---

### 2.3 数据新鲜度 SLO（Freshness）

| 项目 | 值 |
|------|----|
| **目标** | 最大读数延迟 ≤ 5 分钟（300 秒） |
| **文件** | `ops/observability/prometheus/rules/slo-freshness.yml` |
| **SLI 含义** | 所有电表中最大的"当前时刻与最新读数时间戳之差" |
| **v1 状态** | 已上线 |

**关键 PromQL**（来自 D1 录制规则）：

```promql
# SLI — 当前最大读数延迟（秒）
ems:slo:freshness:max_lag_seconds
  = max(ems_meter_reading_lag_seconds)

# 目标值（常量，单位：秒）
ems:slo:freshness:objective_seconds
  = vector(300)

# 错误预算剩余
ems:slo:freshness:error_budget_remaining
  = clamp_min(
      (ems:slo:freshness:objective_seconds - ems:slo:freshness:max_lag_seconds)
      / ems:slo:freshness:objective_seconds,
      0
    )
```

**错误预算解释**：
- lag = 0s → 预算 100%；lag = 150s → 预算 50%；lag ≥ 300s → 预算归零
- `ems_meter_reading_lag_seconds` 由 CollectorService 每 30 秒更新一次
- Critical 告警在 lag > 600s（10 分钟）时触发（见 §3）

---

### 2.4 调度漂移 SLO（Scheduler Drift）

| 项目 | 值 |
|------|----|
| **目标** | 实际触发时间与预期触发时间之差 ≤ 60 秒 |
| **文件** | `ops/observability/prometheus/rules/slo-scheduler-drift.yml` |
| **SLI 含义** | 所有调度任务中最大的触发偏差（秒） |
| **v1 状态** | **占位（Placeholder）** — 见下方说明 |

> **v1 重要说明**：应用尚未发出 `ems_app_scheduled_drift_seconds` 指标（Phase B4 已标记为延期）。
> 当前 SLI 记录规则返回常量 `0`，SLO 面板可正常渲染但不反映真实值。
> 对应的 warning 告警 `EmsSchedulerDrift` 在 v1 不会触发。
> Phase F 将在真实 drift 信号接入后重新评估此 SLO。

**关键 PromQL**（来自 D1 录制规则）：

```promql
# SLI — v1 占位：常量 0（待 ems_app_scheduled_drift_seconds 接入后替换）
ems:slo:scheduler_drift:max_seconds
  = vector(0)

# 目标值（常量，单位：秒）
ems:slo:scheduler_drift:objective_seconds
  = vector(60)

# 错误预算剩余（v1 恒为 1.0）
ems:slo:scheduler_drift:error_budget_remaining
  = clamp_min(
      (ems:slo:scheduler_drift:objective_seconds - ems:slo:scheduler_drift:max_seconds)
      / ems:slo:scheduler_drift:objective_seconds,
      0
    )
```

---

## 3. Critical 告警（5 条）

> **路由**：`severity="critical"` → multi-channel receiver（邮件 + 钉钉 + 企微 + 通用 webhook）
> **预期响应时间**：5 分钟内开始处理

---

### 3.1 EmsAppDown

| 项目 | 值 |
|------|----|
| **触发条件** | `up{job="factory-ems"} == 0` |
| **`for:` 时间窗** | 2 分钟 |
| **severity** | critical |
| **team** | ops |

**含义**：factory-ems 实例的 Actuator 健康端点连续 2 分钟抓取失败，判定为实例不可达。

**处置思路**：
1. 检查容器/进程是否在线（`docker ps`、`systemctl status factory-ems`）
2. 查看最近应用日志（OOM、启动失败、端口冲突等）
3. 确认 Prometheus 到实例的网络连通性
4. 参考 runbook → [`#emsappdown`](https://internal/docs/ops/observability-runbook.md#emsappdown)

**影响范围**：**业务全停**。所有数据采集、告警检测、计费计算均依赖应用在线。抑制规则激活（详见 §6）。

---

### 3.2 EmsAppHighErrorRate

| 项目 | 值 |
|------|----|
| **触发条件** | `rate(ems_app_exception_total[5m]) > 1` |
| **`for:` 时间窗** | 5 分钟 |
| **severity** | critical |
| **team** | backend |

**含义**：应用抛出未捕获异常的速率持续 5 分钟超过 1 次/秒，表明系统存在大量错误。

**处置思路**：
1. 查看应用日志中的异常堆栈（`type` 标签区分异常类型）
2. 检查最近部署或配置变更
3. 确认数据库、外部依赖的连接状态
4. 参考 runbook → [`#emsapphigherrorrate`](https://internal/docs/ops/observability-runbook.md#emsapphigherrorrate)

**影响范围**：高频异常通常伴随 API 响应失败，影响前端用户体验与数据采集可靠性。

---

### 3.3 EmsDataFreshnessCritical

| 项目 | 值 |
|------|----|
| **触发条件** | `max(ems_meter_reading_lag_seconds) > 600` |
| **`for:` 时间窗** | 2 分钟 |
| **severity** | critical |
| **team** | data |

**含义**：至少一台电表的最新读数时间戳超过 10 分钟前，数据严重过期（SLO freshness 目标为 5 分钟，此处为 2× 阈值触发 critical）。

**处置思路**：
1. 在 Grafana 的 Collector Dashboard 定位滞后的电表（按 `tenant`/`device` 筛选）
2. 检查对应采集器的连接状态与 poll 日志
3. 确认设备是否离线或网络中断
4. 参考 runbook → [`#emsdatafreshnesscritical`](https://internal/docs/ops/observability-runbook.md#emsdatafreshnesscritical)

**影响范围**：客户报表与计费数据依赖实时读数，延迟超过 10 分钟将直接影响当期账单准确性。

---

### 3.4 EmsDbConnectionPoolExhausted

| 项目 | 值 |
|------|----|
| **触发条件** | `hikaricp_connections_active / hikaricp_connections_max > 0.95` |
| **`for:` 时间窗** | 3 分钟 |
| **severity** | critical |
| **team** | backend |

**含义**：HikariCP 连接池的活跃连接数超过总量的 95%，持续 3 分钟，新请求即将开始等待或超时。

**处置思路**：
1. 检查是否存在慢查询或长事务持有连接（`SHOW PROCESSLIST`）
2. 确认连接池大小配置（`spring.datasource.hikari.maximum-pool-size`）是否合理
3. 必要时临时扩大连接池上限
4. 参考 runbook → [`#emsdbconnectionpoolexhausted`](https://internal/docs/ops/observability-runbook.md#emsdbconnectionpoolexhausted)

**影响范围**：连接池耗尽后所有数据库操作将超时，导致 API 全面失败。

---

### 3.5 EmsDiskSpaceCritical

| 项目 | 值 |
|------|----|
| **触发条件** | `node_filesystem_avail_bytes{fstype!~"tmpfs\|overlay\|squashfs"} / node_filesystem_size_bytes < 0.10` |
| **`for:` 时间窗** | 5 分钟 |
| **severity** | critical |
| **team** | ops |

**含义**：节点上任意持久化文件系统的可用空间低于 10%，存在数据写入失败风险（排除 tmpfs/overlay/squashfs 等内存/容器层）。

**处置思路**：
1. 定位磁盘占用最大的目录（`du -sh /var/lib/*`）
2. 清理过期日志、旧备份文件
3. 确认 Prometheus/Loki 的数据保留策略是否需要调整
4. 参考 runbook → [`#emsdiskspacecritical`](https://internal/docs/ops/observability-runbook.md#emsdiskspacecritical)

**影响范围**：磁盘写满将导致 Prometheus 无法写入时序数据、应用日志丢失、数据库事务回滚。

---

## 4. Warning 告警（9 条）

> **路由**：`severity="warning"` → default-email receiver（仅邮件）
> **预期响应时间**：24 小时内处理

---

### 4.1 EmsAppLatencyHigh

| 项目 | 值 |
|------|----|
| **触发条件** | `histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{job="factory-ems",outcome="SUCCESS"}[5m]))) > 1` |
| **`for:` 时间窗** | 10 分钟 |
| **severity** | warning |
| **team** | backend |

**含义**：HTTP API 成功请求的 p99 延迟超过 1 秒（SLO latency 目标）持续 10 分钟。

**处置思路**：查找慢接口（按路径分组）、排查数据库慢查询、检查 GC 压力。
参考 runbook → [`#emsapplatencyhigh`](https://internal/docs/ops/observability-runbook.md#emsapplatencyhigh)

---

### 4.2 EmsCollectorPollSlow

| 项目 | 值 |
|------|----|
| **触发条件** | `histogram_quantile(0.95, sum by (le, adapter) (rate(ems_collector_poll_duration_seconds_bucket[10m]))) > 30` |
| **`for:` 时间窗** | 15 分钟 |
| **severity** | warning |
| **team** | collector |

**含义**：某个采集适配器（`adapter` 标签区分）的 poll 操作 p95 耗时超过 30 秒，持续 15 分钟，采集可能开始积压。

**处置思路**：检查对应 adapter 的设备响应时间、网络延迟、并发设置。
参考 runbook → [`#emscollectorpollslow`](https://internal/docs/ops/observability-runbook.md#emscollectorpollslow)

---

### 4.3 EmsAlarmDetectorSlow

| 项目 | 值 |
|------|----|
| **触发条件** | `histogram_quantile(0.95, sum by (le) (rate(ems_alarm_detector_duration_seconds_bucket[10m]))) > 10` |
| **`for:` 时间窗** | 15 分钟 |
| **severity** | warning |
| **team** | alarm |

**含义**：告警检测扫描的 p95 执行时间超过 10 秒，持续 15 分钟，可能导致告警检测延迟。

**处置思路**：检查检测规则数量、数据库查询性能、是否存在积压的待处理读数。
参考 runbook → [`#emsalarmdetectorslow`](https://internal/docs/ops/observability-runbook.md#emsalarmdetectorslow)

---

### 4.4 EmsWebhookFailureRate

| 项目 | 值 |
|------|----|
| **触发条件** | `sum(rate(ems_alarm_webhook_delivery_duration_seconds_count{outcome="failure"}[5m])) / sum(rate(ems_alarm_webhook_delivery_duration_seconds_count[5m])) > 0.20` |
| **`for:` 时间窗** | 15 分钟 |
| **severity** | warning |
| **team** | alarm |

**含义**：告警 webhook 投递失败率超过 20%，持续 15 分钟，客户的钉钉/企微/自定义通知可能大量丢失。

**处置思路**：检查目标 webhook 端点的响应码、网络连通性、签名配置是否有变更。
参考 runbook → [`#emswebhookfailurerate`](https://internal/docs/ops/observability-runbook.md#emswebhookfailurerate)

---

### 4.5 EmsSchedulerDrift

| 项目 | 值 |
|------|----|
| **触发条件** | `ems:slo:scheduler_drift:max_seconds > 60` |
| **`for:` 时间窗** | 5 分钟 |
| **severity** | warning |
| **team** | backend |

> **v1 说明**：此告警在 v1 **不会触发**。`ems:slo:scheduler_drift:max_seconds` 当前返回常量 `0`（Phase B4 drift 埋点延期）。规则已声明保持规格对齐；待 Phase F 接入真实信号后自动激活。

**含义**：调度任务的触发时间偏差超过 60 秒（SLO scheduler-drift 目标），可能表明定时器或 JVM GC 存在问题。

参考 runbook → [`#emsschedulerdrift`](https://internal/docs/ops/observability-runbook.md#emsschedulerdrift)

---

### 4.6 EmsJvmMemoryHigh

| 项目 | 值 |
|------|----|
| **触发条件** | `sum by (instance) (jvm_memory_used_bytes{area="heap"}) / sum by (instance) (jvm_memory_max_bytes{area="heap"}) > 0.85` |
| **`for:` 时间窗** | 15 分钟 |
| **severity** | warning |
| **team** | backend |

**含义**：JVM 堆内存使用率超过 85%，持续 15 分钟，接近 OOM 风险区。

**处置思路**：检查内存泄漏（heap dump 分析）、调整 JVM `-Xmx` 参数、确认是否有大批量数据操作触发。
参考 runbook → [`#emsjvmmemoryhigh`](https://internal/docs/ops/observability-runbook.md#emsjvmmemoryhigh)

---

### 4.7 EmsJvmGcPressure

| 项目 | 值 |
|------|----|
| **触发条件** | `rate(jvm_gc_pause_seconds_sum[5m]) > 0.5` |
| **`for:` 时间窗** | 10 分钟 |
| **severity** | warning |
| **team** | backend |

**含义**：GC 暂停时间速率超过 0.5 s/s（5 分钟内 GC 暂停累计超过总时间的 50%），持续 10 分钟，应用明显受 GC 拖累。

**处置思路**：确认 GC 类型（G1/ZGC）、分析 GC 日志、检查是否存在大对象分配或内存碎片。
参考 runbook → [`#emsjvmgcpressure`](https://internal/docs/ops/observability-runbook.md#emsjvmgcpressure)

---

### 4.8 EmsCollectorOfflineDevices

| 项目 | 值 |
|------|----|
| **触发条件** | `ems_collector_devices_offline / (ems_collector_devices_online + ems_collector_devices_offline) > 0.10` |
| **`for:` 时间窗** | 10 分钟 |
| **severity** | warning |
| **team** | collector |

**含义**：离线设备占总设备数超过 10%，持续 10 分钟，表明存在批量设备断连问题。

**处置思路**：确认是否为网络故障、设备维护还是采集器配置问题；检查最近批量变更。
参考 runbook → [`#emscollectorofflinedevices`](https://internal/docs/ops/observability-runbook.md#emscollectorofflinedevices)

---

### 4.9 EmsAlarmBacklog

| 项目 | 值 |
|------|----|
| **触发条件** | `ems_alarm_active_count{type="silent_timeout"} > 50` |
| **`for:` 时间窗** | 30 分钟 |
| **severity** | warning |
| **team** | alarm |

**含义**：`silent_timeout` 类型的活跃告警积压超过 50 条，持续 30 分钟，表明设备静默超时规则可能触发条件异常或客户运营侧存在大量未处理告警。

**处置思路**：检查静默超时阈值配置是否合理、确认是否存在批量设备网络问题、联系客户运营人员处理积压告警。
参考 runbook → [`#emsalarmbacklog`](https://internal/docs/ops/observability-runbook.md#emsalarmbacklog)

---

## 5. 燃烧率告警（2 条）

### 5.1 原理：双窗口错误预算燃烧率

燃烧率（Burn Rate）是 Google SRE Workbook 推荐的告警策略，解决传统阈值告警"过去好不代表未来没问题"的问题。

**核心思路**：
- SLO 允许在 30 天内有 0.5% 的不可用时间（即"错误预算"= 0.005）
- 如果过去 1 小时的不可用率是正常预算消耗速度的 **14.4 倍**，那么按此速率继续下去，**约 2 天就会耗尽整月预算** → 必须立刻响应（critical）
- 如果过去 6 小时的不可用率是正常速度的 **6 倍**，那么约 **5 天耗尽** → 需要关注但不紧急（warning）

**两个窗口的作用**：
- **短窗口（1h）**：灵敏，捕捉突发故障
- **长窗口（6h）**：稳定，过滤噪声、捕捉持续性慢速恶化

**v1 覆盖范围**：仅可用性 SLO 接入燃烧率告警；延迟/新鲜度/调度漂移的 burn-rate 告警列入 Plan #4 路线图。

---

### 5.2 EmsBudgetBurnFastAvailability（快速燃烧）

| 项目 | 值 |
|------|----|
| **触发条件** | `(1 - avg_over_time(up{job="factory-ems"}[1h])) > (14.4 * (1 - 0.995))` |
| **`for:` 时间窗** | 5 分钟 |
| **severity** | critical |
| **team** | ops |
| **slo 标签** | availability |

**阈值推导**：`14.4 × 0.005 = 0.072`，即过去 1 小时内不可用时间超过 4.3 分钟（72% 的小时预算）。

**含义**：可用性 SLO 错误预算正在以 14.4 倍速度消耗，约 2 天内将耗尽整月预算。需立即响应。

参考 runbook → [`#emsbudgetburnfast`](https://internal/docs/ops/observability-runbook.md#emsbudgetburnfast)

---

### 5.3 EmsBudgetBurnSlowAvailability（慢速燃烧）

| 项目 | 值 |
|------|----|
| **触发条件** | `(1 - avg_over_time(up{job="factory-ems"}[6h])) > (6 * (1 - 0.995))` |
| **`for:` 时间窗** | 30 分钟 |
| **severity** | warning |
| **team** | ops |
| **slo 标签** | availability |

**阈值推导**：`6 × 0.005 = 0.03`，即过去 6 小时内不可用时间超过 10.8 分钟（30% 的 6h 预算）。

**含义**：可用性 SLO 错误预算正在以 6 倍速度慢速消耗，约 5 天内将耗尽整月预算。应在当天内排查。

参考 runbook → [`#emsbudgetburnslow`](https://internal/docs/ops/observability-runbook.md#emsbudgetburnslow)

---

### 5.4 燃烧率与 4 个 SLO 的对应关系

| SLO | v1 燃烧率告警 | 路线图 |
|-----|--------------|--------|
| 可用性 | EmsBudgetBurnFastAvailability (critical) + EmsBudgetBurnSlowAvailability (warning) | 已上线 |
| 延迟 | 暂无 | Plan #4 — 容错强化阶段 |
| 数据新鲜度 | 暂无 | Plan #4 — 容错强化阶段 |
| 调度漂移 | 暂无（v1 SLI 为占位） | Plan #4（需先完成 B4 埋点） |

---

## 6. Alertmanager 路由

### 6.1 路由逻辑

来自 `ops/observability/alertmanager/alertmanager.yml`：

```
所有告警
├── severity="critical"  → multi-channel（邮件 + 钉钉 + 企微 + 通用 webhook）
└── severity="warning"   → default-email（仅邮件）
    （默认 receiver：default-email）
```

**分组策略**（`group_by: [alertname, severity]`）：
- 同一告警名 + 相同 severity 的告警合并为一条通知
- `group_wait: 30s` — 等待 30 秒聚合同批到来的告警
- `group_interval: 5m` — 同组新告警到来后，至少等 5 分钟再发送更新
- `repeat_interval: 4h` — 已激活的告警每 4 小时重复提醒一次

### 6.2 通知渠道配置

| Receiver | 通道 | 环境变量 |
|----------|------|----------|
| `default-email` | 邮件 | `OBS_ALERT_RECEIVER_EMAIL`、`OBS_SMTP_HOST`、`OBS_SMTP_USER`、`OBS_SMTP_PASSWORD` |
| `multi-channel` | 邮件（同上） | 同上 |
| `multi-channel` | 钉钉 | 内部 webhook-bridge 服务（`http://obs-webhook-bridge:9094/dingtalk`） |
| `multi-channel` | 企微 | 内部 webhook-bridge 服务（`http://obs-webhook-bridge:9094/wechat`） |
| `multi-channel` | 通用 webhook | `OBS_GENERIC_WEBHOOK`（客户 IT 工单系统等） |

> **重要**：若某个环境变量未设置，对应渠道的 URL 为空，Alertmanager 将静默跳过该 receiver，不影响其他渠道。例如，不配置 `OBS_GENERIC_WEBHOOK` 时，邮件/钉钉/企微仍可正常收到通知。

### 6.3 抑制规则（Inhibition）

```yaml
inhibit_rules:
  - source_matchers:
      - alertname="EmsAppDown"
    target_matchers:
      - alertname=~"Ems.*"
    equal: [instance]
```

**效果**：当 `EmsAppDown` 对某个实例触发时，**同一实例**上的所有其他 `Ems.*` 告警将被自动抑制，不发送通知。

**原因**：应用实例宕机后，延迟升高、连接池耗尽、数据新鲜度劣化等所有下游告警都是宕机的直接后果。同时发出这些告警只会造成通知洪泛（alert spam），干扰故障响应。

---

## 7. 静默与抑制（运维操作）

### 7.1 维护期静默

在计划维护期间（重启、升级），建议提前创建静默，避免误报。

**命令行方式（amtool）**：

```bash
# 对所有 factory-ems 实例创建 2 小时静默
amtool silence add \
  --alertmanager.url=http://localhost:9093 \
  --duration=2h \
  --comment="计划维护：升级 v1.7.0" \
  job="factory-ems"

# 仅对特定实例静默
amtool silence add \
  --alertmanager.url=http://localhost:9093 \
  --duration=1h \
  --comment="prod-02 硬件更换" \
  instance="prod-02"

# 查看当前静默列表
amtool silence query --alertmanager.url=http://localhost:9093

# 提前结束静默（使用 silence ID）
amtool silence expire --alertmanager.url=http://localhost:9093 <silence-id>
```

**Grafana UI 方式**：
1. 进入 Alerting → Silences → Add Silence
2. 填写匹配标签（如 `job=factory-ems`）
3. 设置开始/结束时间
4. 填写维护说明（Comment 字段）
5. 点击 Submit 保存

> **建议**：静默时长不超过计划维护窗口 + 30 分钟缓冲，维护结束后及时手动结束静默，避免真实故障被掩盖。

### 7.2 抑制规则的工作方式

抑制规则（Inhibition）与静默的区别：

| 特性 | 静默（Silence） | 抑制（Inhibition） |
|------|-----------------|-------------------|
| 触发方式 | 人工设置 | 自动（由另一告警触发） |
| 持续时间 | 人工控制 | 与 source 告警共存亡 |
| 用途 | 维护期 | 根因/派生告警去重 |
| 配置位置 | Alertmanager UI / amtool | `alertmanager.yml` |

当前 factory-ems 的抑制规则（§6.3）仅有一条：`EmsAppDown` 触发时抑制同实例所有 `Ems.*` 告警。一旦 `EmsAppDown` 恢复，抑制自动解除，被抑制的告警若仍满足触发条件则会重新发出通知。

### 7.3 默认 Receiver 行为

未被特定路由匹配的告警（理论上在当前规则集中不存在）会落到 `default-email` receiver。这是 Alertmanager 的安全兜底——所有告警最少会有一个邮件通知渠道。

---

## 8. 客户视角：为什么这些数字是合同里写的？

### 8.1 99.5% 可用性

**数学含义**：允许每月不可用 **约 3.6 小时**（= 30 × 24 × 60 × 0.005 分钟）。

对客户的实际意义：
- 计划维护可在凌晨 2-4 点执行（2 小时），仍有 1.6 小时余量应对突发故障
- 非计划故障响应目标：30 分钟内恢复（单次故障消耗约 14% 的月度预算）
- 监控证明：`ems:slo:availability:sli_30d` 指标实时可查，无需依赖供应商声明

### 8.2 p99 ≤ 1 秒

**数学含义**：最慢的 1% 请求在 1 秒内完成。平均响应时间通常远低于此（典型值 p50 < 100ms）。

对客户的实际意义：
- 操作员查询实时数据时不会因页面加载缓慢而影响工作效率
- 1 秒上限是行业认可的"可接受延迟"边界（p99 = 1s 是保守上限，日常 p50 通常远低于此）
- 高峰期（批量报表导出、采集并发高峰）下仍能保障 p99

### 8.3 5 分钟数据新鲜度

**数学含义**：任意电表的最新读数不超过 5 分钟前，客户看到的数据最多延迟 300 秒。

对客户的实际意义：
- 支持近实时决策（如发现用电异常后 5 分钟内可看到数据变化）
- 计费周期内每小时最多影响 1 个采样点的准确性
- 超过 10 分钟（2× SLO）触发 critical 告警，运维介入

### 8.4 错误预算的策略价值

错误预算不仅是"允许出错多少"，更是运维团队的**行动指引**：

- **预算充裕（>70%）**：可以安排升级、变更，甚至接受一定风险的功能发布
- **预算中等（30-70%）**：需谨慎评估每次变更的影响，限制高风险操作窗口
- **预算告急（<30%）**：冻结非紧急变更，专注稳定性改进；触发回顾会
- **预算耗尽（0%）**：当月 SLO 已违约，需完整故障复盘 + 客户沟通

---

## 9. 测试与验证

### 9.1 promtool 测试用例

所有告警规则均配有 `promtool test rules` 单元测试，位于：

```
ops/observability/prometheus/rules/_tests/
├── critical-alerts_test.yml    # 5 条 critical 告警 × 2 cases = 10 个测试
├── warning-alerts_test.yml     # 9 条 warning 告警 × 2 cases = 18 个测试
└── burn-rate_test.yml          # 2 条 burn-rate 告警 × 2 cases = 4 个测试
```

**总计：32 个测试用例**

每条告警均有：
- **正例（Positive）**：注入超过阈值的时间序列，验证告警在预期 `eval_time` 内触发，并检查 `severity`、`team` 等关键标签
- **负例（Negative）**：注入正常数据，验证告警不触发（`exp_alerts: []`）

**本地运行**：

```bash
# 运行所有告警规则测试
promtool test rules \
  ops/observability/prometheus/rules/_tests/critical-alerts_test.yml \
  ops/observability/prometheus/rules/_tests/warning-alerts_test.yml \
  ops/observability/prometheus/rules/_tests/burn-rate_test.yml

# 仅运行 critical 告警测试
promtool test rules ops/observability/prometheus/rules/_tests/critical-alerts_test.yml
```

### 9.2 测试策略说明

- 测试验证的是**告警是否触发**（标签断言）和**是否静默**（空 exp_alerts）
- annotation 模板渲染（`humanizePercentage`、`humanizeDuration` 等）不在单元测试中断言，由 F2 smoke 测试（端到端告警注入）覆盖
- `EmsSchedulerDrift` 的正例测试暂缓，待 Phase F 接入真实 drift 信号后补充

### 9.3 CI 集成（v1 待办）

CI 集成计划在 **Phase F1** 实现。v1 阶段需手动执行上述命令验证规则变更。

Phase F1 目标：
- 每次 PR 自动运行 `promtool test rules`（32 个用例）
- 失败时阻断合并
- 测试报告输出到 PR Check

### 9.4 客户验收标准

每条告警在交付验收时需满足：
- 至少通过一次 **synthetic 告警注入**（人工制造满足触发条件的场景）
- 在 Alertmanager UI 确认告警路由到正确的 receiver
- 在对应通知渠道（邮件/钉钉/企微）收到通知样例

具体注入方法参见 Phase F2 `obs-smoke` 端到端脚本（F2 完成后补充链接）。

---

## 10. 路线图与扩展

### Phase F（近期）

| 任务 | 内容 |
|------|------|
| F1 CI 集成 | 将 32 条 promtool 测试集成到 CI，阻断不合格的规则变更 |
| F2 obs-smoke | 端到端告警注入脚本：每条告警至少触发一次，验证全链路通知 |
| F3 runbook | 每条告警的完整操作手册（`docs/ops/observability-runbook.md`） |

### Plan #4 容错强化阶段（中期）

- 为延迟 SLO（latency）、新鲜度 SLO（freshness）补充燃烧率告警
- 调度漂移 SLO：待 Phase B4 `ems_app_scheduled_drift_seconds` 埋点完成后，替换 v1 占位规则，并补充 burn-rate 告警

### Plan #6 SSO 接入 Grafana（中期）

- 企业 SSO（LDAP/OIDC）集成，为客户提供基于角色的 Grafana 访问控制
- 客户管理员可直接查看 SLO Dashboard，无需运维开放账号

### 客户合同写法建议

在 SLA 附件中引用 SLO 时，建议：

```
可用性：factory-ems 核心服务月度可用率 ≥ 99.5%，
        以 Prometheus 指标 ems:slo:availability:sli_30d 为计量依据，
        客户可通过 Grafana SLO Overview Dashboard 实时查询。

响应时间：API p99 延迟 ≤ 1 秒（基于成功请求 5 分钟滚动均值）。

数据新鲜度：电表读数延迟 ≤ 5 分钟（基于 ems_meter_reading_lag_seconds 最大值）。
```

这种写法将 SLO 与可观测性指标直接绑定，避免了"SLA 违约认定困难"的常见合同纠纷。

---

## 附录：告警快速索引

| 告警名 | 严重性 | Team | `for:` | 关键阈值 |
|--------|--------|------|--------|----------|
| EmsAppDown | critical | ops | 2m | up == 0 |
| EmsAppHighErrorRate | critical | backend | 5m | exception rate > 1/s |
| EmsDataFreshnessCritical | critical | data | 2m | max lag > 600s |
| EmsDbConnectionPoolExhausted | critical | backend | 3m | active/max > 95% |
| EmsDiskSpaceCritical | critical | ops | 5m | avail < 10% |
| EmsAppLatencyHigh | warning | backend | 10m | p99 > 1s |
| EmsCollectorPollSlow | warning | collector | 15m | poll p95 > 30s |
| EmsAlarmDetectorSlow | warning | alarm | 15m | detector p95 > 10s |
| EmsWebhookFailureRate | warning | alarm | 15m | failure rate > 20% |
| EmsSchedulerDrift | warning | backend | 5m | drift > 60s（v1 占位） |
| EmsJvmMemoryHigh | warning | backend | 15m | heap > 85% |
| EmsJvmGcPressure | warning | backend | 10m | GC pause rate > 0.5 s/s |
| EmsCollectorOfflineDevices | warning | collector | 10m | offline > 10% |
| EmsAlarmBacklog | warning | alarm | 30m | silent_timeout > 50 |
| EmsBudgetBurnFastAvailability | critical | ops | 5m | 1h burn rate 14.4× |
| EmsBudgetBurnSlowAvailability | warning | ops | 30m | 6h burn rate 6× |
