# Observability Stack — 可观测性栈 设计规格

> **日期**：2026-04-29
> **作者**：brainstorming session（superpowers）
> **范围**：factory-ems 商用化加固第 1 个 sub-project
> **目标**：基础设施级别的 metrics + logs + traces + alerting 完整栈，独立 docker-compose，不耦合产品发版
> **非目标**：业务告警（已由 ems-alarm 提供）、客户多租户隔离、k8s 化、客户自定义 dashboard 编辑器

---

## 1. 一句话价值

为 factory-ems on-prem 单服务器部署提供**统一观测平面**：JVM / HTTP / DB / 业务模块的 metrics + 结构化日志 + 分布式追踪 + 多通道告警，让"线上跑得怎么样"从黑箱变成图表，从猜测变成证据。

## 2. 解决什么问题

- **看不见**：现在出问题只能 SSH 进去看日志、grep 异常；没有趋势、没有 SLO、没有跨模块视角
- **响应慢**：故障被客户先发现而不是工程师；没有自动告警通道
- **改不动**：要做容错/性能优化但没有 baseline；改完不知道是真好还是假好
- **客户问不出来**："系统稳不稳定？" "上个月可用性多少？" — 现在给不出数据
- **远程支持难**：装到客户场地后，每次有问题都得现场上人；没有标准 dashboard 给客户运维看

## 3. 核心功能

- **3 信号统一收口**：Metrics（Prometheus）+ Logs（Loki）+ Traces（Tempo），单 Grafana UI 统一查询和联动
- **17 个业务 metrics**：覆盖 collector / alarm / meter / app 跨模块指标，与 Spring Boot 默认 metrics 互补
- **4 个 SLO + 16 条告警规则**：可用性 99.5%、API p99 1s、数据新鲜度 5min、调度漂移 60s；critical/warning 两级
- **多通道告警分发**：邮件 + 钉钉 + 企微 + 通用 webhook，按需启用，配置文件驱动
- **7 个预置 dashboard**：SLO 总览 / 基础设施 / JVM / HTTP / 三个业务模块（collector/alarm/meter）
- **零应用层改造**：所有改动在配置 + 新增独立 docker-compose；产品栈仅加 3 个依赖、1 个新配置类

## 4. 适用场景

**场景 A — 工程值班远程排障**
凌晨 3 点客户反馈"曲线断了"。工程师打开 Grafana SLO Overview，发现"数据新鲜度"红了，下钻到 ems-meter dashboard 看到能源类型 elec 入库速率从 100/min 降到 0；点 Top 设备表的某行，跳到 Loki 过滤该 device_id，看到 timeout 错误堆栈，再跳到 Tempo trace，确认是 modbus-tcp adapter 在某 IP 上卡住。整个流程不需要 SSH。

**场景 B — 容量评估 / 客户合同准备**
工厂打算从 200 台仪表扩到 500 台。看 SLO Overview 30 天历史，可用性 99.62%、p99 870ms（都比目标好），但调度漂移在 200 台时已经接近 60s 警戒线；得出结论：扩到 500 台需要先做调度优化。给客户的合同里 SLO 写 99.5% 有底气。

**场景 C — 故障复盘**
某次告警全压在凌晨 2 点 — 24 小时内回看：Grafana 圈定时间窗，看到 GC pause spike + heap 涨到 90% + 多条 webhook 失败连环出现。复盘报告直接截图 dashboard。

## 5. 不在范围（v1）

| 不做项 | 何时再做 |
|---|---|
| 多实例 Prometheus / HA 观测栈 | 客户多机房，Plan #4 容错强化阶段 |
| VictoriaMetrics / Thanos / Cortex 长期归档 | 30d 保留够用；合规要 1y 时再加 |
| 完整 SRE Workbook 8 燃烧率窗口 | 只做 1h+6h；商业化阶段补 |
| 客户自定义 Dashboard 编辑器 | viewer 角色只读够用 |
| APM agent（Pinpoint / SkyWalking） | 留到性能压测 sub-project (#5) |
| k8s Operator | 暂不上 k8s |
| 多租户隔离 | 单客户一套 |
| 客户自助接收方接入向导 UI | 改文件重启即可 |
| i18n 告警模板 | 默认中文 |
| 高级抖动消除 | Alertmanager 内置 group_wait 够用 |

---

## 6. 架构概览

### 6.1 顶层拓扑

两个 docker-compose 共享同一个 docker network `ems-net`，生命周期完全独立：

```
┌─────────────────────── 客户机房（一台/两台机器） ────────────────────────┐
│                                                                          │
│  ┌────────── docker-compose.yml（产品栈）────────────────────┐          │
│  │   nginx (8888)  ──► factory-ems (Spring Boot, JVM)        │          │
│  │                          │                                │          │
│  │   postgres ◄─────────────┤                                │          │
│  │   influxdb ◄─────────────┤                                │          │
│  └──────────────────────────┼────────────────────────────────┘          │
│                              │                                           │
│              暴露：actuator/prometheus  +  stdout JSON logs              │
│              暴露：OTLP HTTP 4318（traces）                              │
│                              │                                           │
│  ┌────────── docker-compose.obs.yml（观测栈，新增）────────┐            │
│  │   prometheus (9090) ──┐                                 │            │
│  │   loki (3100)         ├──► grafana (3000, web UI)       │            │
│  │   tempo (3200)        ┘                                 │            │
│  │   alertmanager (9093) ──► obs-webhook-bridge (9094)     │            │
│  │                                │                         │            │
│  │                                ├──► email (SMTP)         │            │
│  │                                ├──► 钉钉 webhook          │            │
│  │                                ├──► 企微 webhook          │            │
│  │                                └──► generic webhook      │            │
│  │   promtail（边车）                                       │            │
│  └─────────────────────────────────────────────────────────┘            │
└──────────────────────────────────────────────────────────────────────────┘
```

### 6.2 边界与职责

| Service | 职责 | 默认端口 | 镜像 |
|---|---|---|---|
| Prometheus | 抓取 actuator/prometheus + cadvisor metrics；评估 alert rules（每 60s） | 9090 (lo) | `prom/prometheus:v2.54.x` |
| Loki | 接收应用日志（promtail 推送）；按 label 检索 | 3100 (内网) | `grafana/loki:3.x` |
| Tempo | 接收 OpenTelemetry traces (OTLP HTTP/gRPC) | 3200 (lo), 4318 | `grafana/tempo:2.x` |
| Grafana | 唯一 UI；统一 datasource；预置 7 个 dashboard | 3000 | `grafana/grafana:11.x` |
| Alertmanager | Alert 路由 + 分组 + 抑制 + 通道分发 | 9093 (lo) | `prom/alertmanager:v0.27.x` |
| obs-webhook-bridge | Alertmanager → 钉钉/企微 格式适配（含加签） | 9094 (内网) | 自构建（distroless + Go binary） |
| promtail | 抓 docker container stdout → 推送 loki | — | `grafana/promtail:3.x` |

### 6.3 三大解耦原则

1. **生命周期解耦**：obs 栈崩溃不影响产品栈，反之亦然
2. **发版解耦**：ems-app 升级不需要碰 obs；obs 升级不需要碰 ems-app
3. **替换解耦**：客户日后可以把 obs 栈整套换成 Zabbix / 自有 ELK，产品栈零改动

---

## 7. 应用栈改造点

### 7.1 改造清单

| 改造项 | 方式 | 影响范围 |
|---|---|---|
| Micrometer Prometheus | 已通过 actuator 自带；prod profile 暴露 `/actuator/prometheus` | 0 改动 |
| 业务 metrics 埋点 | `MeterRegistry` 注入 + 17 个 Timer/Counter/Gauge | 新建 `ObservabilityConfig` + 各 service 加 2-3 行 |
| OpenTelemetry traces | 加 deps + application.yml 配 OTLP endpoint + 采样率 | 仅 ems-app pom + yaml |
| 日志 → Loki | promtail 抓 docker container stdout（已 JSON） | 0 改动应用 |
| traceId 关联 | logback + MDC 已有；OTel 自动写入 | 0 改动 |
| 健康检查 | 现有 `/actuator/health` readiness/liveness 已通 | 0 改动 |

### 7.2 新增依赖

`ems-app/pom.xml`：
```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

第三个 deps（OpenTelemetry Spring Boot starter）保持可选，**v1 不引入**（auto-instrument 启动时间影响 +2-5s，先用手动埋点）。

### 7.3 新增类

```
ems-app/src/main/java/com/ems/app/observability/
├── ObservabilityConfig.java                # MeterRegistryCustomizer + 公共 labels
├── SchedulerInstrumentationAspect.java     # @Around @Scheduled 统一 timer + drift gauge
├── CollectorMetrics.java                   # 注册 ems.collector.* 5 个指标 Bean
├── AlarmMetrics.java                       # 注册 ems.alarm.* 5 个指标 Bean
├── MeterMetrics.java                       # 注册 ems.meter.* 3 个指标 Bean
└── AppMetrics.java                         # 注册 ems.app.* 4 个指标 Bean（不含 scheduler）
```

服务层埋点风格：
```java
@Service
class AlarmDetectorImpl {
    private final Timer scanTimer;
    AlarmDetectorImpl(MeterRegistry registry, ...) {
        this.scanTimer = Timer.builder("ems.alarm.detector.duration")
            .description("Alarm 检测一轮耗时")
            .register(registry);
    }
    public void run() { scanTimer.record(() -> doScan()); }
}
```

---

## 8. 业务 Metrics 详细字典

> 此节是产品文档/API 参考的事实来源。每个指标含名称、类型、单位、labels、含义、典型查询。

### 8.1 Common labels（所有指标自动带）

| Label | 来源 | 示例 |
|---|---|---|
| `application` | `MeterRegistryCustomizer` 静态注入 | `factory-ems` |
| `instance` | hostname | `prod-ems-01` |
| `module` | 包名派生（`com.ems.alarm` → `alarm`） | `collector\|alarm\|meter\|app` |

### 8.2 ems-collector 指标（5 个）

| 名称 | 类型 | 单位 | 描述 | 额外 labels | 典型查询 |
|---|---|---|---|---|---|
| `ems.collector.poll.duration` | Timer (histogram) | seconds | 一轮设备采集耗时分布 | `adapter` | `histogram_quantile(0.95, rate(ems_collector_poll_duration_seconds_bucket[10m]))` |
| `ems.collector.devices.online` | Gauge | count | 当前在线设备数 | — | `ems_collector_devices_online` |
| `ems.collector.devices.offline` | Gauge | count | 当前离线设备数 | — | `ems_collector_devices_offline` |
| `ems.collector.read.success.total` | Counter | count | 单次寄存器读成功累计 | `device_id` | `rate(ems_collector_read_success_total[5m])` |
| `ems.collector.read.failure.total` | Counter | count | 单次寄存器读失败累计 | `device_id`, `reason` | `rate(ems_collector_read_failure_total{reason="timeout"}[5m])` |

`reason` 取值：`timeout` / `crc` / `format` / `disconnected` / `other`

### 8.3 ems-alarm 指标（5 个）

| 名称 | 类型 | 单位 | 描述 | 额外 labels |
|---|---|---|---|---|
| `ems.alarm.detector.duration` | Timer | seconds | 一轮 alarm 检测扫描耗时 | — |
| `ems.alarm.active.count` | Gauge | count | 当前 ACTIVE+ACKED 告警数 | `type` |
| `ems.alarm.created.total` | Counter | count | 累计触发告警数 | `type` |
| `ems.alarm.resolved.total` | Counter | count | 累计恢复告警数 | `reason` |
| `ems.alarm.webhook.delivery.duration` | Timer | seconds | webhook 单次调用耗时 | `outcome`, `attempt` |

`type`：`silent_timeout` / `consecutive_fail`（与 ems-alarm 模块一致）
`reason`：`auto` / `manual`
`outcome`：`success` / `failure`
`attempt`：`1` / `2` / `3`

### 8.4 ems-meter 指标（3 个）

| 名称 | 类型 | 单位 | 描述 | 额外 labels |
|---|---|---|---|---|
| `ems.meter.reading.lag.seconds` | Gauge | seconds | 最新读数与当前时间差（按设备聚合最大值） | — |
| `ems.meter.reading.insert.rate` | Counter | count | 累计入库读数行数（用于 rate 计算） | `energy_type` |
| `ems.meter.reading.dropped.total` | Counter | count | 因校验失败被丢弃的读数 | `reason` |

`energy_type`：`elec` / `water` / `gas` / `steam`
`reason`：`duplicate` / `out_of_range` / `format_error` / `other`

### 8.5 ems-app 跨模块指标（4 个）

| 名称 | 类型 | 单位 | 描述 | 额外 labels |
|---|---|---|---|---|
| `ems.app.scheduled.duration` | Timer | seconds | 所有 `@Scheduled` 任务耗时（统一 AOP） | `task` |
| `ems.app.scheduled.drift.seconds` | Gauge | seconds | 任务实际触发时间与期望时间偏差 | `task` |
| `ems.app.audit.write.total` | Counter | count | audit_log 写入累计 | `action` |
| `ems.app.exception.total` | Counter | count | GlobalExceptionHandler 兜底捕获次数 | `type` |

### 8.6 Cardinality 控制规则

- `device_id` 仅在 `ems.collector.read.failure.total`、`ems.collector.read.success.total` 上出现 — 实际 device_id 在 stage 3 期望 < 5000，可控
- `task` 取值跟随 `@Scheduled` 注解扫描结果，期望 < 50
- 其他 label 都是低基数枚举
- 所有 label value 在埋点时做转义，禁止外部输入直接拼

### 8.7 Spring Boot Actuator 已有指标（不重新发明）

- JVM：`jvm_memory_used_bytes`, `jvm_gc_pause_seconds`, `jvm_threads_live_threads`...
- HTTP：`http_server_requests_seconds`...
- HikariCP：`hikaricp_connections_*`
- Logback：`logback_events_total`
- Tomcat：`tomcat_sessions_*`

**直接在 dashboard 用，不重复埋点。**

---

## 9. SLO 规则与告警

### 9.1 四大 SLO

| SLO | 目标 | SLI（可观测） | PromQL |
|---|---|---|---|
| 可用性 | 99.5% / 30d | `up{job="factory-ems"} == 1` | `1 - sum_over_time(up[30d]==0) / count_over_time(up[30d])` |
| API 延迟 | p99 ≤ 1s | `http_server_requests_seconds_bucket{outcome="SUCCESS"}` | `histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket[5m])) by (le))` |
| 数据新鲜度 | max ≤ 5min | `ems_meter_reading_lag_seconds` | `max(ems_meter_reading_lag_seconds)` |
| 调度漂移 | abs ≤ 60s | `ems_app_scheduled_drift_seconds` | `max(abs(ems_app_scheduled_drift_seconds))` |

### 9.2 Critical 告警（5 个）

5 分钟内必须响应，多通道（email + 钉钉 + 企微 + webhook）。

| Alert | 表达 | for |
|---|---|---|
| `EmsAppDown` | `up{job="factory-ems"} == 0` | 2m |
| `EmsAppHighErrorRate` | `rate(ems_app_exception_total[5m]) > 1` | 5m |
| `EmsDataFreshnessCritical` | `max(ems_meter_reading_lag_seconds) > 600` | 2m |
| `EmsDbConnectionPoolExhausted` | `hikaricp_connections_active / hikaricp_connections_max > 0.95` | 3m |
| `EmsDiskSpaceCritical` | `node_filesystem_avail_bytes / node_filesystem_size_bytes < 0.10` | 5m |

### 9.3 Warning 告警（11 条）

24 小时内排查即可，仅邮件。

| Alert | 表达 | for |
|---|---|---|
| `EmsAppLatencyHigh` | `histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket[5m])) by (le)) > 1` | 10m |
| `EmsCollectorPollSlow` | `histogram_quantile(0.95, rate(ems_collector_poll_duration_seconds_bucket[10m])) > 30` | 15m |
| `EmsAlarmDetectorSlow` | `histogram_quantile(0.95, rate(ems_alarm_detector_duration_seconds_bucket[10m])) > 10` | 15m |
| `EmsWebhookFailureRate` | failure / total > 0.20 | 15m |
| `EmsSchedulerDrift` | `max(abs(ems_app_scheduled_drift_seconds)) > 60` | 5m |
| `EmsJvmMemoryHigh` | heap used / max > 0.85 | 15m |
| `EmsJvmGcPressure` | `rate(jvm_gc_pause_seconds_sum[5m]) > 0.5` | 10m |
| `EmsCollectorOfflineDevices` | offline / total > 0.10 | 10m |
| `EmsAlarmBacklog` | `ems_alarm_active_count{type="silent_timeout"} > 50` | 30m |
| `EmsBudgetBurnFastAvailability` | 1h 燃烧 14.4× 阈值 | 5m |
| `EmsBudgetBurnSlowAvailability` | 6h 燃烧 6× 阈值 | 30m |

### 9.4 Alertmanager 路由

```yaml
route:
  receiver: default-email
  group_by: [alertname, severity]
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 4h
  routes:
    - matchers: [severity="critical"]
      receiver: multi-channel
    - matchers: [severity="warning"]
      receiver: default-email

inhibit_rules:
  - source_match: { alertname: 'EmsAppDown' }
    target_match_re: { alertname: 'Ems.*' }
    equal: [instance]
```

`multi-channel` receiver 启用渠道由 `.env.obs` 控制，空值跳过。

### 9.5 静默与抑制

- **维护期**：`amtool silence add severity=warning` 或 Grafana UI 内操作
- **上下游抑制**：`EmsAppDown` 触发时抑制其余 `Ems.*` alert（避免 spam）

---

## 10. Grafana Dashboards

### 10.1 7 个仪表盘清单

| ID | 名称 | 受众 | 核心面板 |
|---|---|---|---|
| D1 | `slo-overview` | 工程值班、客户管理 | 4 SLO 实时百分比 + 错误预算 + 30d 趋势 + 燃烧率 |
| D2 | `infra-overview` | 工程运维 | CPU / RAM / Disk / Network / Container |
| D3 | `jvm-overview` | 后端工程 | Heap / Non-heap / GC pause / 线程 / 类加载 |
| D4 | `http-overview` | 后端工程 | RPS / latency p50/p95/p99 / 状态码 / Top10 端点 |
| D5 | `ems-collector` | 业务运维 | 在线/离线 / 采集耗时 / 失败率 by adapter / Top 失败设备 |
| D6 | `ems-alarm` | 业务运维 | 活跃告警 by type / 检测耗时 / Webhook 成功率 |
| D7 | `ems-meter` | 数据/业务运维 | 数据新鲜度 by 设备 / 入库速率 by 能源类型 / 丢弃率 |

### 10.2 D1 (SLO Overview) 布局

```
Row 1: 4 张 SLO 大数字 stat panel
┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
│可用性     │ │API p99   │ │数据新鲜度 │ │调度漂移   │
│99.62%    │ │ 870ms   │ │ 245s    │ │ 22s     │
│目标99.5% │ │目标1s   │ │目标300s │ │目标60s  │
│ 🟢       │ │ 🟢      │ │ 🟢      │ │ 🟢      │
└──────────┘ └──────────┘ └──────────┘ └──────────┘

Row 2: 错误预算剩余（半月环 gauge）+ 30d 趋势 time-series

Row 3: 燃烧率（1h / 6h 双窗口）+ 当前 firing alerts 表
```

### 10.3 业务面板共同套路（D5–D7）

四象限：
- 上左：当前关键 Gauge
- 上右：分类柱状图（按维度 group by）
- 下左：30d 趋势 time-series
- 下右：Top N 表（点行下钻到 logs/traces）

### 10.4 模板变量

每 Dashboard 顶部 3 个 dropdown：
- `instance` — 多服务器场景过滤（v1 单机，预留）
- `module` — 跨模块对比
- 时间范围默认 6h，可选 1h / 24h / 7d / 30d

### 10.5 Provisioning

```
ops/observability/grafana/provisioning/
├── datasources/datasources.yml
└── dashboards/dashboards.yml
ops/observability/grafana/dashboards/
├── slo-overview.json
├── infra-overview.json
├── jvm-overview.json
├── http-overview.json
├── ems-collector.json
├── ems-alarm.json
└── ems-meter.json
```

启动自动加载，无需手工导入。

### 10.6 访问控制

- 用户名固定 `admin`；密码由首次启动 init 脚本生成强随机值写入 `.env.obs`，并 console 打印一次（之后不再打印）；docker 镜像自带的 admin/admin 默认值在 init 脚本完成前即被覆盖，不会暴露
- `viewer` 角色：只读所有 dashboard，不能改
- `editor` 角色：仅工程团队
- Anonymous 关闭

---

## 11. 配置参考

### 11.1 文件结构

```
ops/observability/
├── docker-compose.obs.yml
├── .env.obs.example
├── prometheus/
│   ├── prometheus.yml
│   └── rules/
│       ├── slo-availability.yml
│       ├── slo-latency.yml
│       ├── slo-freshness.yml
│       ├── slo-scheduler-drift.yml
│       └── _tests/                    # promtool test rules 用例
├── alertmanager/
│   └── alertmanager.yml
├── loki/
│   └── loki-config.yml
├── promtail/
│   └── promtail-config.yml
├── tempo/
│   └── tempo.yml
├── grafana/
│   ├── provisioning/
│   │   ├── datasources/datasources.yml
│   │   └── dashboards/dashboards.yml
│   └── dashboards/
│       └── *.json (7 dashboards)
└── webhook-bridge/
    ├── Dockerfile
    └── main.go
```

### 11.2 环境变量（`.env.obs`）

| 变量 | 必填 | 说明 | 示例 |
|---|---|---|---|
| `OBS_GRAFANA_ADMIN_USER` | 是 | Grafana 管理员账号 | `admin` |
| `OBS_GRAFANA_ADMIN_PASSWORD` | 是 | Grafana 管理员密码（首次启动随机生成） | `XK8h3jq2...` |
| `OBS_PROMETHEUS_RETENTION` | 否 | metrics 保留期 | `30d` |
| `OBS_LOKI_RETENTION` | 否 | logs 保留期 | `336h`（14d） |
| `OBS_TEMPO_RETENTION` | 否 | traces 保留期 | `72h`（3d） |
| `OBS_SMTP_HOST` | 否 | SMTP 服务器地址 | `smtp.example.com:587` |
| `OBS_SMTP_USER` | 否 | SMTP 用户 | `alerts@example.com` |
| `OBS_SMTP_PASSWORD` | 否 | SMTP 密码 | — |
| `OBS_ALERT_RECEIVER_EMAIL` | 否 | 告警邮件接收者（逗号分隔） | `oncall@x.com,ops@x.com` |
| `OBS_DINGTALK_WEBHOOK` | 否 | 钉钉机器人 webhook URL | `https://oapi.dingtalk.com/...` |
| `OBS_DINGTALK_SECRET` | 否 | 钉钉加签密钥 | — |
| `OBS_WECHAT_WEBHOOK` | 否 | 企微机器人 webhook URL | `https://qyapi.weixin.qq.com/...` |
| `OBS_GENERIC_WEBHOOK` | 否 | 通用 webhook（接客户 IT 工单） | — |
| `OBS_NETWORK_NAME` | 否 | docker network 名称 | `ems-net` |

任一通道环境变量为空 → Alertmanager 该 receiver 静默跳过。

### 11.3 ems-app 配置（application-prod.yml 增量）

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,info,metrics
  metrics:
    tags:
      application: factory-ems
      instance: ${HOSTNAME:unknown}
  tracing:
    sampling:
      probability: 0.1                 # 10% 采样，prod 平衡成本
  otlp:
    tracing:
      endpoint: http://tempo:4318/v1/traces
```

---

## 12. 测试策略

### 12.1 四层测试

**第 1 层 — Unit：业务 metrics 埋点**
- 用 `SimpleMeterRegistry` 验证每个 Timer/Counter/Gauge 注册成功 + 至少 1 次记录
- 17 指标 × 1-2 测试 = 约 25 unit tests
- 文件：`ems-app/src/test/java/com/ems/app/observability/*Test.java`

**第 2 层 — Promtool：alert rule 单元测试**
- `promtool test rules ops/observability/prometheus/rules/_tests/*.yml`
- 16 alert × 2（正反例）= 32 测试用例
- 在 GitHub Actions CI 跑

**第 3 层 — Smoke：obs compose 启动健康**
- `make obs-up && sleep 30 && make obs-smoke`
- 5 服务 `/ready` + 1 条 critical alert 端到端到达 mock receiver

**第 4 层 — E2E（v1 不做）**
- 启 obs + mock factory-ems → Grafana dashboard 渲染对比
- 工作量大，留路线图

### 12.2 CI 集成

`.github/workflows/ci.yml` 增加：

```yaml
observability:
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v4
    - name: Install promtool / amtool
      run: ...
    - name: Validate prom rules
      run: promtool check rules ops/observability/prometheus/rules/*.yml
    - name: Test prom rules
      run: promtool test rules ops/observability/prometheus/rules/_tests/*.yml
    - name: Validate alertmanager config
      run: amtool check-config ops/observability/alertmanager/alertmanager.yml
    - name: Validate grafana JSON
      run: jq empty ops/observability/grafana/dashboards/*.json
```

### 12.3 覆盖目标

| 类型 | 目标 |
|---|---|
| Unit (业务 metrics 埋点) | 17 指标 100% 注册 + 记录验证 |
| Promtool rules | 32 用例（每条 alert 1 正 1 反） |
| Smoke (compose) | 5 服务 ready + 1 端到端 alert |

---

## 13. 风险与未决

| 项 | 风险 | 缓解 |
|---|---|---|
| Promtail 抓 docker container logs 需挂 `/var/lib/docker/containers` | 客户运维若不允许挂载，promtail 起不来 | 备选：Logback Loki appender；但应用层依赖增加 |
| OpenTelemetry agent 自动 instrument 影响启动时间 | +2-5s | v1 不引入 starter，只用手动埋点 |
| 钉钉/企微 webhook 在客户内网可能不通 | 客户工厂内网无公网 | 提供"仅邮件"降级；保留 generic webhook 接客户内部 IT 工单 |
| Grafana 默认密码 admin/admin | 客户忘记改 | 启动脚本生成随机密码并打印一次到 console |
| Prometheus Cardinality 爆炸 | `device_id` 在 1 万设备时 series 过亿 | 高 cardinality label 仅放 Counter；其他用桶聚合 |
| docker-compose 网络外部依赖 | `ems-net` external network 必须先创建 | obs-up 脚本里 `docker network inspect \|\| docker network create` 幂等 |
| 业务 metrics 跟产品代码耦合 | 改业务时忘了改埋点 | 在每个 service 单元测试里加"指标注册存在"断言 |

---

## 14. 与既有架构对齐点

- 模块命名：`ops/observability/`（与 `ops/` 既有目录一致）
- ems-app 包结构：`com.ems.app.observability.*`（沿用 com.ems.app 既有约定）
- 异常处理：观测代码不抛业务异常；MeterRegistry 故障时降级（micrometer 内置 fail-safe）
- 鉴权：obs 端口默认 loopback；远程访问走 nginx 反代 + Basic Auth（Plan #6 安全加固再升级）
- Migration：本 spec 不涉及数据库变更
- JaCoCo：观测代码归入 ems-app 模块覆盖率统计；70% 阈值不变
- 时区：alert 时间戳走 UTC（Prometheus 默认）；Grafana UI 显示按浏览器时区

---

## 15. 用户角色权限矩阵

沿用 `ems-auth` 既有角色，但观测栈访问权限走 Grafana 自身 RBAC：

| 操作 | ADMIN | OPERATOR | VIEWER | 工程团队 |
|---|:---:|:---:|:---:|:---:|
| 看 Grafana SLO Overview | ✅ | ✅ | ✅ | ✅ |
| 看业务 dashboard（D5-D7） | ✅ | ✅ | ✅ | ✅ |
| 看 Infra/JVM/HTTP dashboard | ❌ | ❌ | ❌ | ✅ |
| 编辑 dashboard | ❌ | ❌ | ❌ | ✅（Editor） |
| Alertmanager silence | ❌ | ❌ | ❌ | ✅ |
| Grafana 用户管理 | ❌ | ❌ | ❌ | ✅（Admin） |

> 注：v1 Grafana 自建用户体系，不与 ems-auth 打通；客户 IT/运维拿 viewer 账号即可。商业化阶段（Plan #6）再做 SSO 联通。

---

## 16. 部署要求

### 16.1 资源预算

| 项 | 内存 | 磁盘 | CPU |
|---|---|---|---|
| Prometheus | 512 MB | 5 GB（30d retention） | 0.2 vCPU |
| Loki | 512 MB | 8 GB（14d retention） | 0.2 vCPU |
| Tempo | 256 MB | 2 GB（3d retention） | 0.1 vCPU |
| Grafana | 256 MB | 100 MB | 0.1 vCPU |
| Alertmanager | 128 MB | 50 MB | 0.1 vCPU |
| webhook-bridge | 32 MB | — | 0.05 vCPU |
| promtail | 128 MB | — | 0.1 vCPU |
| **合计** | **~1.8 GB** | **~15 GB** | **~0.85 vCPU** |

加上产品栈（ems-app + nginx + postgres + influx ~ 4-6 GB），客户最低服务器配置 8 GB RAM / 50 GB SSD / 4 vCPU 可行。

### 16.2 启停命令

```bash
# 创建 docker network（首次）
docker network create ems-net 2>/dev/null || true

# 启动观测栈
docker compose -f docker-compose.obs.yml up -d

# 启动产品栈（已有）
docker compose -f docker-compose.yml up -d

# 健康检查
make obs-smoke
```

### 16.3 升级路径

观测栈与产品栈各自独立升级，互不影响：

```bash
# 仅升级观测栈
docker compose -f docker-compose.obs.yml pull
docker compose -f docker-compose.obs.yml up -d

# 仅升级产品栈
docker compose -f docker-compose.yml pull
docker compose -f docker-compose.yml up -d
```

---

## 17. 错误码与日志规范

### 17.1 业务模块统一错误日志格式

JSON Logback 已经实现，确保以下字段在 prod profile 必出：

| 字段 | 含义 | 示例 |
|---|---|---|
| `ts` | 时间戳（ISO8601 含时区） | `2026-04-29T08:15:30.123+08:00` |
| `level` | 日志级别 | `ERROR` / `WARN` / `INFO` |
| `logger` | logger 名 | `com.ems.alarm.service.AlarmDetectorImpl` |
| `msg` | 消息 | `Alarm detection cycle failed` |
| `traceId` | OTel trace id（自动 MDC） | `abc123def456...` |
| `userId` | 当前请求用户 id（如适用） | `42` |
| `exception` | 异常堆栈（如有） | `java.lang.NullPointerException at ...` |

### 17.2 已知关注的日志关键词

供 Loki 检索 + Grafana 关键字告警建模：

- `level=ERROR` — 所有 ERROR 都关注
- `level=WARN AND logger=com.ems.alarm.*` — alarm 模块 WARN
- `msg=~"connection refused|timeout|circuit open"` — 网络相关
- `msg=~"OutOfMemory|GC overhead"` — JVM 异常

---

## 18. 文档落实要求（产品手册可生成性）

> 本 spec 设计时已考虑后续 AI 自动生成产品文档。下列产物在 Plan 实施阶段同步落地：

| 产物路径 | 内容来源 | 受众 |
|---|---|---|
| `docs/product/observability-feature-overview.md` | 第 1-4 章 + 价值主张改写 | 销售/客户 |
| `docs/product/observability-config-reference.md` | 第 11 章配置参考 | 实施工程师 |
| `docs/product/observability-metrics-dictionary.md` | 第 8 章指标字典 | 数据/集成工程师 |
| `docs/product/observability-slo-rules.md` | 第 9 章 SLO + 告警 | 客户管理/运维 |
| `docs/product/observability-dashboards-guide.md` | 第 10 章 dashboard 说明 | 客户/工程值班 |
| `docs/product/observability-user-guide.md` | "如何看 dashboard / 如何处理告警" 操作手册 | 客户运维 |
| `docs/api/observability-metrics-api.md` | 第 8 章 + Prometheus 抓取协议 | 第三方集成 |
| `docs/ops/observability-runbook.md` | 故障排查 / 启停 / 升级 / 备份 | 运维 |
| `docs/ops/observability-deployment.md` | 第 16 章 + 详细安装步骤 | 装机工程师 |
| `docs/ops/verification-YYYY-MM-DD-observability.md` | 验收日志 | 内部 |

每个产物都从本 spec 对应章节派生，确保"代码 + 文档 + 客户体验"一致。

---

## 19. 路线图（与商用化整体脉络对齐）

| 时间窗 | Sub-project | 与本 spec 的关系 |
|---|---|---|
| **接下来 1.5–2 周** | 本 spec：可观测性栈 v1 | — |
| 之后 2 周 | #2 备份与灾难恢复 | 用 obs 监控备份成功率；备份失败 → critical alert |
| 之后 2 周 | #3 CI/CD 增强 | 复用 alertmanager；CI 失败 → 钉钉 |
| 之后 1.5 周 | #4 容错强化 | 用本 spec 指标做容错改造前后对比 |
| 之后 2 周 | #5 性能压测基线 | 用本 spec metrics 做基线记录 |
| 之后 1.5 周 | #6 安全加固 | obs 端口反代 + SSO + JWT 轮换 |
| 之后 1.5 周 | #7 装机交付 v2 | 提供"obs 一键开关"；客户首次部署可暂缓 |

总周期 12-14 周走完 7 个 sub-project，达到"商用化基础设施完备"。

---

## 20. 关键不变量（防回归 / 写给后续 plan）

- **观测栈与产品栈生命周期独立**：互相崩溃不互相牵连
- **业务 metrics 埋点不抛异常**：MeterRegistry 故障时静默降级，绝不阻塞业务
- **alert 渠道环境变量为空 → 该渠道跳过**：禁止启动失败
- **Cardinality 控制**：高基数 label（如 device_id）只允许出现在低基数指标
- **Grafana 默认 admin/admin 启动脚本必须强制改密**：禁止留口
- **观测栈 UI 端口默认 loopback**：禁止默认对外暴露
- **business alert（ems-alarm）与 infrastructure alert（本 spec）走不同通道**：避免 ems-alarm 自身故障无法通知
