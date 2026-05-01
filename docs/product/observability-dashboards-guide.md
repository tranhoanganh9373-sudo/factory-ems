# 可观测性 · 7 Dashboard 使用指南

> **更新于**：2026-04-29（Phase E 完成时）
> **撰写依据**：spec §10（Grafana Dashboards 章）+ Phase E 实际落地 JSON 文件
> **受众**：客户管理 / 工程值班（on-call）/ 业务运维
> **文档关系**：
> - [observability-config-reference.md](./observability-config-reference.md) — 环境变量 / 启停 / 资源预算
> - [observability-slo-rules.md](./observability-slo-rules.md) — 4 SLO + 16 条告警规则详解
> - [observability-metrics-dictionary.md](./observability-metrics-dictionary.md) — 17 个业务指标字典

---

## 1. 概述、受众与文档关系

本文档面向**客户管理人员、工程值班（on-call）与业务运维**，说明 factory-ems 可观测性栈提供的 7 个 Grafana Dashboard 的使用方法，包括每个 dashboard 的受众与用途、面板列表与阅读方式、告警关联，以及跨 dashboard 的下钻路径。

**不在本文档范围内**：指标采集原理、告警规则表达式、部署与运维操作——这些分别由 [observability-metrics-dictionary.md](./observability-metrics-dictionary.md)、[observability-slo-rules.md](./observability-slo-rules.md) 与 [observability-deployment.md](../ops/observability-deployment.md) 覆盖。

**访问入口**：Grafana 部署在 `http://<host>:3000`，首次登录密码见 `ops/observability/scripts/grafana-init.sh` 生成的随机密码。所有 dashboard 通过 Grafana provisioning 自动注册，无需手工导入。

---

## 2. 7 Dashboard 总览

| ID | 名称 | URL Path | 受众 | 主要面板 | Phase |
|----|------|----------|------|---------|-------|
| D1 | factory-ems · SLO Overview | `/d/slo-overview/` | 工程值班、客户管理 | 4 SLO stat + 错误预算 gauge + 可用性趋势 + 燃烧率 + firing 告警表 | E1 ✅ |
| D2 | factory-ems · 基础设施 | `/d/infra-overview/` | 工程运维 | CPU/内存/容器数/重启数 stat + CPU/内存/磁盘/网络趋势 + 容器 CPU/内存 Top 10 + 容器列表 | E2 ✅ |
| D3 | factory-ems · JVM | `/d/jvm-overview/` | 工程运维 | Heap 使用率/活跃线程/已加载类/GC 停顿率 stat + Heap/Non-heap/GC/线程/连接池/会话/日志趋势 | E2 ✅ |
| D4 | factory-ems · HTTP | `/d/http-overview/` | 工程运维 | RPS/p95/p99/5xx 错误率 stat + 延迟趋势 + RPS by URI + 状态码饼图 + 错误率趋势 + 慢端点/高错误端点表 | E2 ✅ |
| D5 | factory-ems · Collector | `/d/ems-collector/` | 工程值班、业务运维 | 在线/离线设备 stat + 失败率/Poll 耗时/失败原因/在线趋势 + Top 10 失败设备表 | E3 ✅ |
| D6 | factory-ems · Alarm | `/d/ems-alarm/` | 工程值班、业务运维 | 活跃告警/24h 触发/自动恢复 stat + 告警 by type + Detector 耗时 + Webhook 投递 + 触发 vs 恢复 + Top 5 type 表 | E3 ✅ |
| D7 | factory-ems · Meter | `/d/ems-meter/` | 工程值班、业务运维 | 最大 lag/入库速率/24h 丢弃 stat + Lag vs SLO gauge + 入库 by 能源类型 + 丢弃 by reason + Lag 趋势 + 能源类型入库量表 | E3 ✅ |

> 所有 dashboard 均为只读（`editable: false`），刷新间隔 30 秒，默认时间窗口 6 小时。如需自定义，请参见 [第 12 节](#12-自定义-dashboard)。

---

## 3. D1 · SLO Overview

**URL**：`/d/slo-overview/`

### 3.1 受众与用途场景

- **工程值班**：每次当班开始时快速确认系统整体健康状态；收到告警通知时第一落点。
- **客户管理**：向客户展示服务质量，确认 SLO 合规状态；与客户开月度质量回顾会。

### 3.2 面板列表

| 顺序 | 面板名称 | 类型 | 一句话含义 |
|------|---------|------|-----------|
| 1 | 可用性 (30d) | stat | 过去 30 天 factory-ems 平均在线时间占比（SLO 目标：99.5%） |
| 2 | API p99 (5m) | stat | 最近 5 分钟 HTTP 请求 p99 延迟（SLO 目标：< 1s） |
| 3 | 数据新鲜度 (max lag) | stat | 所有能源计量数据中最大写入延迟（SLO 目标：< 300s） |
| 4 | 调度漂移 (max abs) | stat | 采集任务实际执行时间与计划时间的最大偏差（SLO 目标：< 60s，v1 占位） |
| 5 | 可用性错误预算剩余 | gauge | 本月可用性 SLO 错误预算剩余比例（0 = 本月已违约） |
| 6 | 可用性 30 天趋势 | timeseries | 30 天滚动窗口可用率历史曲线 |
| 7 | 可用性燃烧率 (1h vs 6h) | timeseries | 1 小时与 6 小时燃烧率，以及快速/慢速告警阈值参考线 |
| 8 | 当前 firing 告警 | table | Prometheus ALERTS 中当前处于 firing 状态的告警列表（alertname / severity / team / instance） |

### 3.3 关键面板细节

**4 个 SLO stat 面板的颜色含义**

| 面板 | 绿色（正常） | 黄色（警告） | 红色（违约风险） |
|------|------------|------------|----------------|
| 可用性 (30d) | ≥ 99.5% | 99.0% ~ 99.5% | < 99.0% |
| API p99 (5m) | < 500ms | 500ms ~ 1s | ≥ 1s |
| 数据新鲜度 (max lag) | < 180s | 180s ~ 300s | ≥ 300s |
| 调度漂移 (max abs) | < 30s | 30s ~ 60s | ≥ 60s |

**错误预算 gauge（面板 5）**

量规表盘范围 0 ~ 100%：
- **绿色（≥ 50%）**：错误预算充裕，可正常迭代发布。
- **黄色（25% ~ 50%）**：预算消耗过半，应减慢变更节奏，关注是否有持续小故障。
- **红色（< 25%）**：预算即将耗尽；若降至 0，本月 SLO 违约，应冻结非紧急发布。

30 天允许不可用总时间约为 **216 分钟（约 3.6 小时）**（基于 99.5% 目标）。

**燃烧率趋势（面板 7）**

图中有 4 条线：
- **1h burn**（短窗口快速燃烧率）：若超过 **14.4×**（图中参考线），触发快速响应告警，意味着在 1 小时内已消耗约 1/20 的月度错误预算。
- **6h burn**（长窗口慢速燃烧率）：若超过 **6×**（图中参考线），触发慢速响应告警，意味着若持续下去将在约 5 天内耗尽月度预算。
- 两条参考线均为常数，帮助直观对比当前燃烧率是否已触发告警条件。

**firing 告警表（面板 8）**

列出当前所有处于触发（firing）状态的告警，字段：
- **alertname**：告警名称，与 [observability-slo-rules.md](./observability-slo-rules.md) 中告警列表对应。
- **severity**：严重级别（`critical` / `warning`）。
- **team**：负责团队。
- **instance**：触发告警的服务实例。

### 3.4 关联告警

与本 dashboard 直接关联的告警（详见 [observability-slo-rules.md](./observability-slo-rules.md)）：

- **EmsAvailabilityBurnRateFast**（critical）：1h 燃烧率 > 14.4×
- **EmsAvailabilityBurnRateSlow**（warning）：6h 燃烧率 > 6×
- **EmsApiLatencyHigh**（critical）：p99 延迟持续 ≥ 1s
- **EmsDataFreshnessViolation**（critical）：max lag ≥ 300s

### 3.5 下钻路径

- **可用性红了** → 跳 D2 基础设施 dashboard，确认宿主机是否 CPU/内存耗尽或容器重启。
- **数据新鲜度红了** → 跳 D7 Meter dashboard，查哪个能源类型入库速率下降；再跳 D5 Collector dashboard，查失败设备。
- **firing 告警表** → 点击 alertname 超链接（若已配置 Alertmanager 联动）可跳至告警详情。

---

## 4. D2 · 基础设施

**URL**：`/d/infra-overview/`

### 4.1 受众与用途场景

- **工程运维**：宿主机资源巡检、容器异常排查、扩容决策依据。

### 4.2 面板列表

| 顺序 | 面板名称 | 类型 | 一句话含义 |
|------|---------|------|-----------|
| 1 | 主机 CPU 使用率 | stat | 所有 CPU 核心平均使用率（5m 窗口） |
| 2 | 主机内存使用率 | stat | 已用内存占总内存百分比 |
| 3 | 运行中容器数 | stat | 当前存活容器总数 |
| 4 | 近 5 分钟容器重启数 | stat | 5 分钟内容器重启次数（连续重启意味着服务崩溃循环） |
| 5 | 主机 CPU 趋势 | timeseries | 按实例分色展示 CPU 使用率历史 |
| 6 | 主机内存趋势 | timeseries | 按实例分色展示内存使用率历史 |
| 7 | 磁盘使用率 (按挂载点) | timeseries | 各挂载点（排除 tmpfs/overlay）磁盘占用历史 |
| 8 | 网络 in / out (bytes/s) | timeseries | 主网卡（排除 loopback/docker 内部网卡）收发速率 |
| 9 | 容器 CPU Top 10 | timeseries | CPU 消耗最高的 10 个容器趋势 |
| 10 | 容器内存 Top 10 | timeseries | 内存占用最高的 10 个容器趋势 |
| 11 | 容器列表 (含 CPU / 内存) | table | 按 CPU 排名前 20 容器的 CPU（核）与内存（bytes）快照 |

### 4.3 关键面板细节

**stat 阈值**

| 指标 | 绿色 | 黄色 | 红色 |
|------|------|------|------|
| CPU 使用率 | < 70% | 70% ~ 90% | ≥ 90% |
| 内存使用率 | < 70% | 70% ~ 90% | ≥ 90% |
| 容器重启数（5m） | 0 | 1 ~ 2 | ≥ 3 |

**容器列表（面板 11）**：表格合并了 CPU 与内存两列，按 CPU 降序排列。`CPU (cores)` 是每秒消耗 CPU 秒数的速率（< 1 表示不足一核），`Memory (bytes)` 是实时内存占用字节数。

### 4.4 关联告警

- **EmsInstanceDown**（critical）：factory-ems 实例连续 1 分钟 down，与容器重启计数器联动。

### 4.5 下钻路径

- **容器重启数 ≥ 3** → 跳 D3 JVM dashboard，查 Heap 使用率是否触顶（OOM 重启）或 GC 停顿是否过长。
- **CPU 持续 > 90%** → 跳 D4 HTTP dashboard，查 RPS 是否异常激增；查 Top 10 慢端点。

---

## 5. D3 · JVM

**URL**：`/d/jvm-overview/`

### 5.1 受众与用途场景

- **工程运维**：诊断内存溢出（OOM）、线程泄漏、GC 过频、数据库连接池耗尽等运行时问题。

### 5.2 面板列表

| 顺序 | 面板名称 | 类型 | 一句话含义 |
|------|---------|------|-----------|
| 1 | Heap 使用率 | stat | Heap 已用 / Heap 最大（百分比） |
| 2 | 活跃线程数 | stat | 当前存活线程总数 |
| 3 | 已加载类数 | stat | 当前加载进内存的类数量（正常情况下稳定不变） |
| 4 | GC 停顿率 (s/s) | stat | 每秒 GC 停顿时间（应接近 0） |
| 5 | Heap used vs max | timeseries | Heap 已用与最大值历史，按实例分色 |
| 6 | Non-heap used vs max | timeseries | Non-heap（Metaspace 等）已用与最大值历史 |
| 7 | GC 停顿时间 (按 gc) | timeseries | 分 GC 类型展示每秒停顿时间 |
| 8 | GC 次数速率 (按 gc) | timeseries | 分 GC 类型展示每秒 GC 触发次数 |
| 9 | 线程数 (live / daemon / peak) | timeseries | 存活线程、守护线程与峰值线程历史 |
| 10 | Hikari 连接池 (active / idle / max) | timeseries | 数据库连接池 active/idle/max 历史，按连接池名分色 |
| 11 | Tomcat 活跃会话 | timeseries | Tomcat HTTP 会话数历史 |
| 12 | Logback 日志事件 (按级别) | timeseries | 分日志级别（error/warn/info）事件速率，error 激增通常与业务异常同步 |

### 5.3 关键面板细节

**stat 阈值**

| 指标 | 绿色 | 黄色 | 红色 |
|------|------|------|------|
| Heap 使用率 | < 70% | 70% ~ 90% | ≥ 90% |
| 活跃线程数 | < 200 | 200 ~ 500 | ≥ 500 |
| GC 停顿率 | < 50ms/s | 50 ~ 200ms/s | ≥ 200ms/s |

**连接池（面板 10）**：`active` 接近 `max` 时表示连接池接近耗尽，请求将开始排队等待连接。通常 `active ≤ max × 0.8` 属于健康区间。

### 5.4 关联告警

- **EmsHighHeapUsage**（warning）：Heap 使用率持续 > 85%，详见 [observability-slo-rules.md](./observability-slo-rules.md)。

### 5.5 下钻路径

- **Heap 红了** → 查面板 12（Logback 日志），确认 error 是否激增 → Loki Explore 过滤 `level=error` 查 OOM 堆栈。
- **GC 停顿过高** → 查面板 8（GC 次数速率），若 G1GC 触发频繁，通常与 Heap 使用率 > 90% 同时出现。

---

## 6. D4 · HTTP

**URL**：`/d/http-overview/`

### 6.1 受众与用途场景

- **工程运维**：排查 API 响应慢、错误率上升、某接口压力异常集中等问题。

### 6.2 面板列表

| 顺序 | 面板名称 | 类型 | 一句话含义 |
|------|---------|------|-----------|
| 1 | RPS (5m) | stat | 最近 5 分钟总请求速率（req/s） |
| 2 | p95 延迟 (5m) | stat | 最近 5 分钟 95 百分位响应时间 |
| 3 | p99 延迟 (5m) | stat | 最近 5 分钟 99 百分位响应时间（SLO 关联） |
| 4 | 5xx 错误率 | stat | 5xx 服务端错误占全部请求的比例 |
| 5 | p50 / p95 / p99 延迟趋势 | timeseries | 三分位数延迟历史曲线 |
| 6 | RPS by URI Top 10 | timeseries | 流量最高的 10 个 URI 趋势 |
| 7 | 状态码分布 (5m) | piechart | 最近 5 分钟各 HTTP 状态码请求量占比（甜甜圈图） |
| 8 | 错误率趋势 (4xx / 5xx) | timeseries | 客户端错误（4xx）与服务端错误（5xx）历史趋势 |
| 9 | Top 10 慢端点 (p95) | table | 响应最慢的 10 个 URI（p95 延迟，单位 s） |
| 10 | Top 10 高错误率端点 | table | 5xx 错误率最高的 10 个 URI |

### 6.3 关键面板细节

**stat 阈值**

| 指标 | 绿色 | 黄色 | 红色 |
|------|------|------|------|
| p95 延迟 | < 500ms | 500ms ~ 1s | ≥ 1s |
| p99 延迟 | < 1s | 1s ~ 2s | ≥ 2s |
| 5xx 错误率 | < 1% | 1% ~ 5% | ≥ 5% |

**状态码分布（面板 7）**：正常情况下圆圈主体为绿色（2xx）。若出现大面积橙/红（4xx/5xx），表示有批量请求出错。悬停各扇形可看具体状态码计数。

### 6.4 关联告警

- **EmsApiLatencyHigh**（critical）：p99 延迟持续 ≥ 1s，详见 [observability-slo-rules.md](./observability-slo-rules.md)。

### 6.5 下钻路径

- **p99 延迟红了** → 查面板 9（Top 10 慢端点），定位受影响 URI → 跳 D3 JVM，查连接池是否耗尽。
- **5xx 错误率升高** → 查面板 10（Top 10 高错误率端点），确认受影响范围 → Loki Explore 过滤对应 URI 查错误堆栈。

---

## 7. D5 · Collector

**URL**：`/d/ems-collector/`

### 7.1 受众与用途场景

- **工程值班**：设备大量离线或采集失败告警触发后，快速定位问题设备与适配器。
- **业务运维**：每日巡检设备在线状态，确认新接入设备正常上线。

### 7.2 面板列表

| 顺序 | 面板名称 | 类型 | 一句话含义 |
|------|---------|------|-----------|
| 1 | 在线设备数 | stat | 当前采集器识别为在线的设备总数 |
| 2 | 离线设备数 | stat | 当前采集器识别为离线的设备总数 |
| 3 | 离线占比 | stat | 离线设备占全部设备（在线 + 离线）的百分比 |
| 4 | 失败率 by adapter | timeseries | 按通信协议适配器分色的读取失败率历史（如 modbus_tcp / modbus_rtu） |
| 5 | Poll 耗时 p95 by adapter | timeseries | 按适配器分色的设备轮询 p95 延迟历史 |
| 6 | 失败原因分布 | timeseries（堆叠） | 按失败原因（reason 标签）分色堆叠展示失败速率 |
| 7 | 在线/离线 30 天趋势 | timeseries | 在线与离线设备数的长期历史曲线 |
| 8 | Top 10 失败设备 | table | 失败速率最高的 10 个设备（device_id + fail_rate/s），含 Loki 跳转链接 |

### 7.3 关键面板细节

**stat 阈值**

| 指标 | 绿色 | 黄色 | 红色 |
|------|------|------|------|
| 在线设备数 | ≥ 1 | — | 0（无在线设备） |
| 离线设备数 | 0 | 1 ~ 4 | ≥ 5 |
| 离线占比 | < 5% | 5% ~ 20% | ≥ 20% |

**Top 10 失败设备表（面板 8）**

这是本 dashboard 最重要的排查起点：

- `device_id` 列：设备唯一标识符，可与运维台账对应，找到物理设备位置。
- `fail_rate (per s)` 列：该设备每秒平均读取失败次数，越高越紧急。
- **Loki 跳转链接**：点击每行右侧的"查看 device 日志 (Loki)"链接，自动跳至 Loki Explore，并以 `device_id=<值>` 预填过滤条件，无需手工输入。

**失败率 by adapter（面板 4）**

按通信协议适配器（如 `modbus_tcp`、`modbus_rtu`）分色展示失败率。若某一适配器失败率突然升高但其他适配器正常，通常是该协议类型的设备组出现批量故障或网络隔离，而非整个采集服务异常。

**失败原因分布（面板 6）**

堆叠图按 `reason` 标签分类（如 `timeout`、`connection_refused`、`parse_error`、`other`）。v1 阶段大多数失败原因会归入 `other`，这是预期行为，后续版本会细化分类。`timeout` 比例高时应重点检查设备网络延迟或设备固件是否卡死。

### 7.4 关联告警

- **EmsCollectorPollSlow**（warning）：采集器 poll p95 延迟持续过高，详见 [observability-slo-rules.md](./observability-slo-rules.md)。
- **EmsCollectorOfflineDevices**（critical）：离线设备占比持续 ≥ 20%。

### 7.5 下钻路径

- **Top 10 失败设备表** → 点"查看 device 日志 (Loki)"→ Loki Explore（预填 `device_id` 过滤）→ 查 timeout / 错误堆栈。
- **Loki 日志中出现 traceId** → 点击 traceId → Tempo 追踪详情 → 定位是哪个 modbus 适配器卡住、哪一步调用超时。
- **失败率 by adapter 中某适配器突增** → 对照 D1 SLO Overview 的"数据新鲜度" stat，判断是否已影响 SLO。

---

## 8. D6 · Alarm

**URL**：`/d/ems-alarm/`

### 8.1 受众与用途场景

- **工程值班**：确认告警检测引擎运行正常，Webhook 投递无积压，告警自动恢复比例健康。
- **业务运维**：监控设备告警活跃数量，判断是否需要人工干预。

### 8.2 面板列表

| 顺序 | 面板名称 | 类型 | 一句话含义 |
|------|---------|------|-----------|
| 1 | 活跃 silent_timeout | stat | 当前处于"静默超时"告警状态的设备数（设备长时间无数据上报） |
| 2 | 活跃 consecutive_fail | stat | 当前处于"连续采集失败"告警状态的设备数 |
| 3 | 24h 触发总数 | stat | 过去 24 小时内新产生的告警数量 |
| 4 | 24h 自动恢复占比 | stat | 过去 24 小时内，告警中自动恢复（无需人工干预）的占比 |
| 5 | 活跃告警 by type | timeseries（堆叠） | 按告警类型（silent_timeout / consecutive_fail）分色堆叠展示活跃数量历史 |
| 6 | Detector 扫描耗时 p95 | timeseries | 告警检测引擎每轮扫描的 p95 耗时历史 |
| 7 | Webhook 投递速率 by outcome / attempt | timeseries | 按投递结果（success/failure）和重试次数分色展示投递速率 |
| 8 | 触发 vs 恢复速率 | timeseries | 告警触发速率与恢复速率历史（两线平衡说明系统自愈能力良好） |
| 9 | Top 5 type 触发 24h | table | 过去 24 小时内触发数量最多的 5 个告警类型 |

### 8.3 关键面板细节

**24h 自动恢复占比（面板 4）**

这是衡量系统自愈能力的核心指标：

- **绿色（≥ 80%）**：系统自愈能力强，大多数告警在问题解决后自动关闭，人工负担轻。
- **黄色（50% ~ 80%）**：部分告警需要人工关闭，运维需定期清理。
- **红色（< 50%）**：告警大量积压未自动恢复，意味着设备处于持续异常状态，或自动恢复逻辑存在问题，需要重点排查。

**活跃告警 stat 阈值（面板 1 / 2）**

| 指标 | 绿色 | 黄色 | 红色 |
|------|------|------|------|
| 活跃 silent_timeout | 0 | 1 ~ 4 | ≥ 5 |
| 活跃 consecutive_fail | 0 | 1 ~ 4 | ≥ 5 |

**Webhook 投递速率（面板 7）**

图例格式为 `<outcome> · attempt=<n>`，例如：
- `success · attempt=1`：首次投递即成功（理想状态）。
- `failure · attempt=3`：重试 3 次仍失败（需检查接收方 URL 是否可达）。

若 `failure` 系列出现且持续存在，应立即检查 Webhook 接收端，否则客户将无法收到告警通知。

### 8.4 关联告警

- **EmsWebhookFailureRate**（warning）：Webhook 投递失败率持续过高，详见 [observability-slo-rules.md](./observability-slo-rules.md)。

### 8.5 下钻路径

- **Webhook 投递持续失败** → 查 webhook-bridge 服务日志（Loki：过滤 `compose_service=webhook-bridge`）→ 确认钉钉/企微接收 URL 是否可达。
- **活跃告警数突增** → 跳 D5 Collector dashboard，查是否有大批设备离线导致批量触发告警。
- **Detector 扫描耗时持续升高** → 若 p95 > 数秒，表明设备数量已接近当前检测容量，需评估扩容或调整扫描间隔。

---

## 9. D7 · Meter

**URL**：`/d/ems-meter/`

### 9.1 受众与用途场景

- **工程值班**：能源计量数据入库延迟告警触发后，定位是哪种能源类型写入堆积。
- **业务运维**：每日确认各能源类型（电、水、气、热）入库速率正常，监控数据丢弃情况。

### 9.2 面板列表

| 顺序 | 面板名称 | 类型 | 一句话含义 |
|------|---------|------|-----------|
| 1 | 当前最大 lag | stat | 所有能源类型中最大的数据写入延迟（秒）（SLO 目标：< 300s） |
| 2 | 入库速率 (5m) | stat | 最近 5 分钟能源计量数据写入数据库的速率（rows/s） |
| 3 | 24h 丢弃总数 | stat | 过去 24 小时被系统丢弃的计量数据条数 |
| 4 | Lag vs SLO (max 300s) | gauge | 最大 lag 与 SLO 目标（300s）的可视量规对比 |
| 5 | 入库速率 by energy_type | timeseries（堆叠） | 按能源类型分色堆叠展示入库速率历史 |
| 6 | 丢弃 by reason | timeseries（堆叠） | 按丢弃原因分色堆叠展示丢弃速率历史 |
| 7 | Lag 30 天趋势 | timeseries | max lag 与 SLO 目标（300s）参考线的长期历史 |
| 8 | Top 10 能源类型入库 24h | table | 过去 24 小时各能源类型（energy_type）入库总条数排名 |

### 9.3 关键面板细节

**Lag vs SLO gauge（面板 4）**

量规表盘范围 0 ~ 300 秒：
- **绿色（< 180s）**：数据新鲜度良好，满足 SLO。
- **黄色（180s ~ 300s）**：接近 SLO 边界，需关注趋势。
- **红色（= 300s）**：已触及 SLO 上限，对应 `EmsDataFreshnessViolation` 告警触发条件。

这与 D1 SLO Overview 中"数据新鲜度"stat 面板使用相同阈值（180s / 300s），两处颜色含义完全一致。

**stat 阈值**

| 指标 | 绿色 | 黄色 | 红色 |
|------|------|------|------|
| 当前最大 lag | < 180s | 180s ~ 300s | ≥ 300s |
| 24h 丢弃总数 | 0 | 1 ~ 99 | ≥ 100 |

**丢弃 by reason（面板 6）**

v1 阶段大多数丢弃原因会显示为 `other`，这是预期行为（v1 丢弃原因分类尚未细化）。若 `other` 以外的类别出现且持续增长，则需排查对应业务逻辑。

**入库速率 by energy_type（面板 5）**

堆叠图中各能源类型各占一层颜色。若总入库速率不变但某一类型层消失，表明该类型的采集设备全部离线，应立即查对应设备状态（跳 D5 Collector）。

### 9.4 关联告警

- **EmsDataFreshnessViolation**（critical）：max lag ≥ 300s 持续 5 分钟，详见 [observability-slo-rules.md](./observability-slo-rules.md)。

### 9.5 下钻路径

- **Lag 红了** → 查面板 5（入库速率 by energy_type），定位哪种能源类型入库速率降低 → 跳 D5 Collector，查该类型设备的失败率与 Top 10 失败设备。
- **丢弃总数持续升高** → 查面板 6（丢弃 by reason），确认丢弃原因类别 → Loki Explore 过滤 `"dropped"` 关键字查具体日志。

---

## 10. 模板变量使用

### $instance 变量

每个 dashboard 顶部均有 **$instance** 下拉变量，动态枚举所有向 Prometheus 注册的 `factory-ems` 实例：

- **默认值：All**（同时展示所有实例的聚合数据）。
- **多服务器场景**：若部署了多个 factory-ems 节点（主备或分地域），可在下拉中选择具体实例，过滤出单节点视图，便于对比或独立排查。
- **URL 自动更新**：切换 $instance 后，浏览器 URL 的查询参数会同步更新，可直接将该 URL 分享给同事，收到链接的人打开即是相同过滤视角。

### 时间窗口（Time Picker）

所有 dashboard 默认时间窗口为 **过去 6 小时**，刷新间隔 30 秒。

常用场景参考：

| 场景 | 推荐时间窗口 |
|------|------------|
| 值班实时巡检 | 1h ~ 6h |
| 排查今日故障 | 6h ~ 24h |
| 月度 SLO 报告 | 30d（SLO Overview） |
| 趋势分析 / 容量规划 | 7d ~ 30d |

D1 SLO Overview 中的"可用性 (30d)" stat 面板内部计算窗口固定为 30 天，不受 time picker 影响；其他面板随 time picker 变化。

---

## 11. 下钻路径示例

以下以"**数据新鲜度告警触发**"为例，走完整故障排查链路。

### 场景背景

凌晨 3:20，on-call 工程师收到 `EmsDataFreshnessViolation` 告警通知：max lag ≥ 300s 已持续 5 分钟。

### 第 1 步：SLO Overview 确认告警范围

打开 D1 SLO Overview（`/d/slo-overview/`）。

- "数据新鲜度 (max lag)" stat 面板显示红色，当前值约 320s。
- "可用性 (30d)" stat 仍为绿色，说明服务实例本身未宕机。
- "firing 告警表"中确认只有 `EmsDataFreshnessViolation` 一条记录，范围有限。

[截图：SLO Overview — 数据新鲜度面板红色，其他面板绿色，firing 告警表有一行]

### 第 2 步：Meter Dashboard 定位能源类型

跳转 D7 Meter（`/d/ems-meter/`）。

- "Lag vs SLO gauge" 显示量规指针在红区（接近 300s）。
- "入库速率 by energy_type"堆叠图中，`electricity`（电）层几乎归零，其他层（`water`、`gas`）正常。
- 确认：问题集中在**电力数据**入库停滞。

[截图：Meter Dashboard — 入库速率堆叠图，electricity 层消失]

### 第 3 步：Collector Dashboard 查失败设备

跳转 D5 Collector（`/d/ems-collector/`）。

- "离线设备数" stat 显示 8（黄色），"离线占比"约 15%（黄色）。
- "失败率 by adapter"图中，`modbus_tcp` 适配器失败率从 0 突增至约 60%。
- "Top 10 失败设备"表显示 8 台设备的 `device_id`，均为电表类型。

[截图：Collector Dashboard — Top 10 失败设备表，8 行均为电表设备 ID]

### 第 4 步：Loki Explore 查日志

点击"Top 10 失败设备"表中第一行的"查看 device 日志 (Loki)"链接，浏览器跳至 Loki Explore，自动预填：

```
{compose_service="factory-ems"} |= "device_id=METER-E-001"
```

日志显示：

```
ERROR CollectorService - poll failed: device_id=METER-E-001, adapter=modbus_tcp,
  reason=timeout, duration=30005ms, traceId=4bf92f3577b34da6
```

确认：设备的 modbus TCP 连接持续超时（30s），是电力读数停滞的直接原因。

[截图：Loki Explore — 过滤后的 timeout 错误日志，traceId 高亮可点击]

### 第 5 步：Tempo Trace 定位适配器卡点

点击日志行中的 traceId，跳至 Tempo 追踪详情。

Trace 树状图显示调用链：采集服务 → Modbus TCP 适配器 → 等待 30s → 超时异常。阻塞点在适配器读取步骤，其余 span 均正常。根据 trace 关联的实例信息，确认该节点上的 modbus TCP 连接已阻塞，需要重启对应采集线程或检查网络交换机到电表的物理连通性。

[截图：Tempo Trace — ModbusTcpAdapter.read span 标红，显示 30s timeout]

---

## 12. 自定义 Dashboard

### v1 原则

v1 提供的 7 个 dashboard 均设置为 **只读**（`editable: false`），由 Grafana provisioning 管理。这是为了保证团队共享的视图定义与 Git 仓库完全一致，避免本地修改被下次部署覆盖。

**不建议直接修改这 7 个 dashboard**——不同客户场景差异较大，建议 fork 一份再改。

### 推荐做法：Fork 后提交

1. **导出 JSON**：在 Grafana 中打开任意 dashboard，点击右上角齿轮图标 → "JSON Model"，复制全部 JSON 内容。
2. **修改 JSON**：将 `uid` 改为新的唯一 ID（如 `my-collector-v2`），将 `title` 改为新名称，按需调整面板。
3. **提交到仓库**：将修改后的 JSON 文件保存到 `ops/observability/grafana/dashboards/<新文件名>.json`，提交 PR。
4. **生效方式**：Grafana 重启后（`docker compose restart grafana`）自动通过 provisioning 加载新文件，无需手工导入。

### 权限说明

v1 Grafana 使用自建用户体系（spec §10.6 + §15）：
- **Admin / Editor 角色**：可在界面创建和编辑自建 dashboard（非 provisioned）。
- **Viewer 角色**：只读，无法编辑任何 dashboard。
- Provisioned dashboard（本文档中的 7 个）对任何角色均为只读，界面编辑入口被禁用。

---

## 13. 故障定位与常见问题

### Dashboard 加载慢

**症状**：打开 dashboard 后，面板长时间显示转圈或加载超时。

**排查步骤**：
1. 检查 Prometheus 抓取间隔配置（应为 15s）：`curl http://<prometheus>:9090/api/v1/targets` 确认 target 状态为 `up`。
2. 检查 Grafana 数据源超时设置：Grafana → Configuration → Data Sources → Prometheus → Timeout，建议 ≥ 60s。
3. 若 Prometheus 自身响应慢，检查 Prometheus 容器内存是否受限：`docker stats prometheus`。

### Panel 显示 No Data

**症状**：面板提示"No data"或空白，但时间范围和实例选择无误。

**排查步骤**：
1. 确认指标是否真的被采集，在 factory-ems 所在服务器运行：
   ```bash
   curl http://localhost:8080/actuator/prometheus | grep <metric_name>
   ```
   若无输出，说明该指标未暴露，可能是服务未正常启动或配置问题。
2. 在 Grafana Explore → Prometheus 中直接输入 PromQL 表达式查询，确认是查询问题还是数据源连接问题。
3. 检查 Prometheus 抓取日志中是否有该 target 的 scrape 错误。

### 时区不一致

**症状**：Grafana 显示的时间与日志/告警通知中的时间相差数小时。

**原因与处理**：
- Prometheus 内部所有时间戳均为 **UTC**。
- Grafana 默认按**浏览器本地时区**显示（如 UTC+8）。
- 告警通知（Alertmanager）默认也使用 UTC。

若时区展示与预期不符，可在 Grafana 用户设置（Profile → Preferences → Timezone）中统一设置为 `Asia/Shanghai`。

---

## 14. 路线图

| 版本 | 状态 | Dashboard 相关计划 |
|------|------|-------------------|
| **v1**（当前） | 已完成（Phase E） | 7 个只读 dashboard，自动 provisioning |
| **v2**（Plan #4 容错强化阶段） | 规划中 | 客户自定义 dashboard 编辑器（Editor 角色功能完整化） |
| **v3**（Plan #6 安全加固） | 规划中 | SSO 接入 → 客户运维人员统一登录，无需维护独立 Grafana 账号 |

> v2 / v3 特性尚未开发，本文档仅描述 v1 已交付内容。
