# 平台底座（ems-core + ems-timeseries）· 功能概览

> 适用版本：v1.3.0+ ｜ 最近更新：2026-05-01
> 受众：架构师 / 后端工程师 / 实施工程师 / 集成方

---

## §1 一句话价值

ems-core 是所有业务模块的公共件库：统一响应包装、分页 DTO、异常处理、日志 trace、通用工具。ems-timeseries 是所有时序数据的读取统一接口加预聚合层：InfluxDB 客户端封装、5min / 1h / 1d 三级 rollup 加速查询。两个模块本身没有 API，但是 16 个业务模块共同依赖的基础设施。

---

## §2 解决什么问题

- **业务模块各写一套响应格式**：A 模块返回 `{code, msg, data}`、B 模块返回 `{success, error, payload}`，前端要适配多套，维护痛苦。统一响应加异常处理可以消除这类碎片化。
- **InfluxDB 直查 1 年明细数据慢得离谱**：1 块表 96 时段 × 365 天 = 35040 行；20 块表就是 70 万行；月度报表如果每次查原始数据，秒级超时。需要按时间窗预聚合（5min / 1h / 1d）。
- **每个业务模块各自写 InfluxDB 查询**：仪表盘要查、报表要查、报警要查、成本要查，重复代码且容易引入错误的 Flux 语法。
- **时序数据的语义要统一**：什么是"能耗"（累计差值）、什么是"功率"（瞬时值）、不同单位之间不能搞混。一处建模，多处复用。

---

## §3 核心功能（ems-core）

### §3.1 统一响应（`Result<T>`）

所有 API 返回一致的 envelope：

- `success: bool` — 是否成功
- `data: T?` — 业务数据（成功时存在）
- `errorMessage: string?` — 失败原因（失败时存在）
- `errorCode: string?` — 业务错误码

前端只用一套解析代码即可适配所有接口。

### §3.2 分页（`PageDTO<T>`）

列表类接口统一返回：`{items, total, page, size}`。前端表格组件按此接管。

### §3.3 统一异常处理（`@RestControllerAdvice`）

所有业务异常 → 拦截 → 转 `Result.error(code, message)`。
所有未捕获异常 → 兜底 500 + 写错误日志 + 返回中性"系统异常"。

### §3.4 日志 trace（`TraceIdHolder`）

每个请求生成 trace ID，写到 MDC，所有日志带 trace ID。便于按请求贯穿排查（结合 ems-app 的 Filter Chain）。

### §3.5 通用工具

- 时间窗解析（"今日 / 本周 / 上月"等）。
- 日期格式（统一 ISO-8601 / RFC3339）。
- 节点子树工具（基于 `path` 字段的 LIKE 前缀匹配）。
- 校验工具（参数非空、范围、枚举）。

---

## §4 核心功能（ems-timeseries）

### §4.1 InfluxDB 客户端封装（`InfluxConfig`）

- 单例 InfluxDB Client，所有业务模块共享。
- 配置：URL、token、org、bucket、超时。
- 健康检查：周期性 ping。

### §4.2 时序数据模型

- **MeterPoint**：单个仪表的某一时刻读数。
- **TimePoint**：通用时间点 + 值。
- **TimeRange**：时间窗（含起止 + 时区）。
- **Granularity**：粒度枚举（`RAW` / `MIN5` / `HOUR1` / `DAY1`）。

### §4.3 三级 rollup 预聚合

- **5min rollup**：原始数据按 5 分钟窗口聚合（avg / sum / min / max），Influx Task 自动跑。
- **1h rollup**：5min → 1h（再聚合一层）。
- **1d rollup**：1h → 1d。

查询时根据时间窗自动选粒度（如查 1 年数据 → 用 1d；查 1 周数据 → 用 1h；查当天 → 用 5min 或原始）。`RollupReaderImpl` 负责路由。

### §4.4 Rollup 回填（`RollupBackfillService`）

历史数据没跑 rollup（如新部署后第一次开 rollup）时，可手工触发回填。按时间窗扫原始数据，逐窗写 rollup。

### §4.5 统一查询服务（`TimeSeriesQueryService`）

业务模块不直接写 Flux 查询，而是调 `TimeSeriesQueryService.query(meters, timeRange, granularity, agg)` —— 内部组装 Flux + 选合适的 rollup measurement + 返回结构化结果。

### §4.6 仪表元数据端口（`MeterCatalogPort`）

不绑定 ems-meter 模块（避免循环依赖），通过 SPI 接口让 meter 实现注入。timeseries 通过此接口获取"哪些仪表存在、measurement / tag 是什么"。

---

## §5 适用场景

### 场景 A：业务模块写新接口 — 用 Result 包裹返回

新写一个仪表盘组件接口，开发只需：

```java
@GetMapping("/some-endpoint")
public Result<MyData> get() {
    return Result.success(service.compute());
}
```

异常自动被全局处理器转成 `Result.error(...)`，无需 try-catch。

### 场景 B：写一份月度报表 — 用 TimeSeriesQueryService

月度报表要查"5 月一二车间的电量按日聚合"：

```java
TimeRange range = TimeRange.of("2026-05-01T00:00:00Z", "2026-06-01T00:00:00Z");
List<TimePoint> result = timeSeriesQueryService.query(
    meterIds, range, Granularity.DAY1, AggregateFunc.SUM
);
```

底层自动用 `rollup_1d` measurement，单次 < 200ms 出结果。

### 场景 C：新部署 — 历史数据回填

客户旧系统已运行 2 年，迁移到 EMS 后导入了 2 年原始数据。但 rollup 是新部署后才开的，2 年 rollup 数据是空的。运维：

1. 调 `RollupBackfillService.backfillAll(from=2024-01-01, to=2026-04-30, granularity=DAY1)`。
2. 服务按时间窗逐天扫原始数据 → 写 1d rollup。
3. 跑完后历史报表可用。

### 场景 D：排查某个慢查询

报表说"5 月月报跑了 30s"，运维进 Influx UI 看 Flux 查询日志：

1. 看到该报表用的是 `rollup_5min` measurement —— 太细粒度。
2. 查 `RollupReaderImpl` 路由逻辑 → 阈值（< 7 天用 5min、< 90 天用 1h）。
3. 5 月查 30 天 → 应该用 1h 而不是 5min。
4. 调阈值（30 天 < 90 天 → 1h）后下次 < 500 ms。

---

## §6 不在范围（首版）

- **不做时序数据 ETL 工具**：原始数据怎么进 InfluxDB 由 ems-collector 负责，timeseries 只读不写原始。
- **不做 Flux 自定义编辑器**：业务侧通过 `TimeSeriesQueryService` 调，不暴露 Flux 给业务层。
- **不做时序数据备份 / 归档**：数据保留策略由 InfluxDB bucket 配置（`retention`）控制，不在系统里管。
- **不做跨 InfluxDB 集群联邦**：单实例部署，一个 bucket。
- **不做 ems-core 共用代码热更新**：core 是编译期依赖，更新需重启业务模块。
- **不做时序数据可视化**：原始 Influx 数据需可视化请用 InfluxDB UI 或 Grafana；业务可视化在仪表盘 / 报表层。

---

## §7 与其他模块的关系

```
ems-collector ──写─→ InfluxDB ←─读── ems-timeseries
                                          │
                                          ├──< ems-meter（提供 catalog 端口实现）
                                          ├──< ems-dashboard
                                          ├──< ems-report
                                          ├──< ems-alarm
                                          └──< ems-cost / ems-billing

ems-core ←──── 所有业务模块（编译期依赖）
```

ems-core：横切公共件，被 16 个业务模块依赖，自身无业务。
ems-timeseries：时序数据读取层，被 6+ 业务模块依赖（凡是要查能耗 / 功率的都走这里）。

---

## §8 接口入口

ems-core 和 ems-timeseries 本身不暴露 HTTP 接口，它们是供其他模块编程引用的内部库。

**配置项**（在 `ems-app/application.yml` 中）：

| 配置 | 默认值 | 说明 |
|---|---|---|
| `influx.url` | `http://influxdb:8086` | InfluxDB 端点 |
| `influx.token` | `${INFLUX_TOKEN}` | API token |
| `influx.org` | `ems` | 组织 |
| `influx.bucket.raw` | `ems_raw` | 原始数据 bucket |
| `influx.bucket.rollup` | `ems_rollup` | rollup 数据 bucket |
| `timeseries.rollup.threshold-5min` | `7` 天 | < 阈值用 5min |
| `timeseries.rollup.threshold-1h` | `90` 天 | < 阈值用 1h，≥ 用 1d |

---

## §9 关键类型

### `Result<T>`（响应 envelope）

```json
{
  "success": true,
  "data": { /* 业务数据 */ },
  "errorMessage": null,
  "errorCode": null
}
```

错误时：

```json
{
  "success": false,
  "data": null,
  "errorMessage": "节点不存在",
  "errorCode": "ORG_NODE_NOT_FOUND"
}
```

### `PageDTO<T>`

```json
{
  "items": [/* T 数组 */],
  "total": 123,
  "page": 1,
  "size": 20
}
```

### InfluxDB 数据布局（`ems_raw` bucket）

| 元素 | 示例 |
|---|---|
| measurement | `meter_elec`、`meter_water`、`meter_steam` |
| tag `meter_code` | `EM-001-A1` |
| tag `org_node_id` | `42` |
| field `kw` | 瞬时功率 |
| field `kwh_cum` | 累计电量 |
| field `quality` | 质量码（`OK`/`UNCERTAIN`/`BAD`）|
| time | RFC3339（如 `2026-05-01T00:00:00Z`）|

### Rollup measurement 命名规则（`ems_rollup` bucket）

| 粒度 | measurement |
|---|---|
| 5min | `rollup_5min_{原 measurement}` |
| 1h | `rollup_1h_{原 measurement}` |
| 1d | `rollup_1d_{原 measurement}` |

---

**相关文档**

- 仪表与计量（catalog 端口实现方）：[meter-feature-overview.md](./meter-feature-overview.md)
- 采集协议（写入 Influx 的源头）：[collector-protocols-user-guide.md](./collector-protocols-user-guide.md)
- 仪表盘（消费方）：[dashboard-feature-overview.md](./dashboard-feature-overview.md)
- 报表（消费方）：[report-feature-overview.md](./report-feature-overview.md)
- 报警（消费方）：[alarm-feature-overview.md](./alarm-feature-overview.md)
- 平台总览：[product-overview.md](./product-overview.md)
- 可观测性（含 Influx 监控）：[observability-feature-overview.md](./observability-feature-overview.md)
