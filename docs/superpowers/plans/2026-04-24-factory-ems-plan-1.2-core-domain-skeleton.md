# Factory EMS · Plan 1.2 · 核心领域 + 基础看板（骨架）

> **Status:** Skeleton only. Full plan to be written after Plan 1.1 execution completes.

**Goal:** 交付"建测点 → 数据入库 → 看到真实能耗曲线"的闭环：用户在 Plan 1.1 基础上建测点 → 系统从 InfluxDB 拉数据 → 看板 5 个基础面板可用 → 能按日/月导 CSV 报表。

**Architecture:** 在 Plan 1.1 的 Modular Monolith 基础上新增 `ems-meter`、`ems-timeseries`、`ems-dashboard`、`ems-report` 四个模块；新增 InfluxDB 容器；预聚合 Job 运行在 Spring `@Scheduled`。

**Tech Stack (增量):** `influxdb-client-java` 6.x · Apache POI 5.x（CSV 用 commons-csv，Excel 留到 Plan 1.3）· ECharts 5 · TanStack Query 30s 轮询。

**Spec reference:** `docs/superpowers/specs/2026-04-24-factory-ems-foundation-design.md`

---

## 依赖前提

- **Plan 1.1 必须已全部完成并演示通过**（admin + orgtree + auth + audit 可用）
- Plan 1.1 打的 tag `v1.1.0-plan1.1` 作为起点

---

## 范围边界

### 本 Plan 交付

| 后端模块 | 职责 |
|---|---|
| `ems-meter` | 能源品类字典（3 条）、测点 CRUD、计量拓扑（表间父子关系） |
| `ems-timeseries` | InfluxDB 查询封装、hourly/daily/monthly Rollup Job、混合查询（rollup + raw 现算） |
| `ems-dashboard`（子集） | 面板 ①②③④⑤ 的 REST API |
| `ems-report`（基础） | 自定义时段查询 + CSV 导出（日/月/年报表留 Plan 1.3） |

| 前端页面 | 职责 |
|---|---|
| `/` 首页看板 | 9 面板中的 ①②③④⑤ |
| `/meter/list` | 测点管理（含计量拓扑编辑） |
| `/report/ad-hoc` | 自定义时段查询 + CSV 下载 |
| 全局组件 | `OrgTreeSelector` 升级为全局筛选器（上层订阅 `appStore.currentOrgNodeId`） |

### 本 Plan 不做

- 面板 ⑥（尖峰平谷）→ Plan 1.3（依赖 tariff）
- 面板 ⑦（单位产量）→ Plan 1.3（依赖 production）
- 面板 ⑧（Sankey）→ Plan 1.3（需要 UI 配计量拓扑以后展现）
- 面板 ⑨（平面图）→ Plan 1.3
- 日/月/年/班次报表 → Plan 1.3
- Excel/PDF 导出 → Plan 1.3（此 Plan 只做 CSV）

### 交付演示场景

1. Admin 在组织树下建测点 `ELEC-WS1-M01`（挂在"一车间"）
2. 配置 meter 的 `influx_tag_value = "ELEC-WS1-M01"`
3. 外部采集软件（模拟）往 InfluxDB 写 1 分钟粒度数据
4. 打开首页看板，看到：
   - KPI 卡：今日电 / 水 / 蒸汽总量
   - 24h 实时曲线
   - Top-N 排行
5. 点击该测点进详情页，看它自己的实时值 + 历史曲线（最近 30 天）
6. 左侧切换到"一车间"节点，所有面板自动重算
7. VIEWER 用户登录，看板数据只包含其可见节点子树
8. 进 `/report/ad-hoc`，选时段 2026-04-01 ~ 2026-04-24，按天粒度查电能耗，下载 CSV
9. 补跑 API：`POST /api/v1/ops/rollup/rebuild?granularity=HOURLY&from=...&to=...` 返回成功

---

## 关键架构决策

1. **InfluxDB 查询隔离**：唯一入口 `TimeSeriesQueryService`。其他模块不接 InfluxDB Client。
2. **混合查询**：`query(meters, range, granularity)` 根据 granularity 和时间范围自动分派：
   - `MINUTE` 且跨度 ≤ 24h → 直查 InfluxDB
   - `HOUR/DAY/MONTH` 已 rollup 部分查 PG，未 rollup 桶查 InfluxDB 现算，结果合并
3. **Rollup 写冗余 `org_node_id`**：便于报表直接 `GROUP BY org_node_id`，避免 join meters 表
4. **Rollup 幂等**：`ON CONFLICT (meter_id, bucket) DO UPDATE`；补跑 API 随调随用
5. **失败重试**：`rollup_job_failures` 表 + 指数退避（5min → 30min → 2h），3 次后停并报警
6. **权限强制过滤**：Controller 层调 `PermissionResolver.visibleNodeIds()`，传入 Service；Service 对登录态无感
7. **看板刷新**：30s TanStack Query 轮询；不用 WebSocket
8. **CSV 编码**：UTF-8 with BOM（Excel 中文兼容）；流式写出，不全量加载到内存
9. **InfluxDB 种子数据**：`seed/influx-seeder.py` 按正弦 + 噪声生成符合工厂作息曲线的 30 天模拟数据，本地 dev 用
10. **meter_topology 与 org_nodes 独立**：组织归属（orgtree）≠ 计量层级（meter_topology）；同一层级关系不能用 orgtree 复用

---

## Phase 索引（估算 Task 数）

| Phase | 范围 | 估 Tasks |
|---|---|---|
| A | Flyway 迁移：`meters` / `meter_topology` / `ts_rollup_*` / `rollup_job_failures`；能源品类种子数据 | 4 |
| B | `ems-meter` 模块：实体 / repo / service / controller / 审计 / 单元 & 集成测试 | 18 |
| C | `ems-timeseries` 模块：InfluxDB 客户端封装 + Flux 查询构造器 + 契约测试 | 10 |
| D | Rollup 流水线：3 个 Job + 幂等 upsert + 失败重试 + 补跑 API + 集成测试 | 14 |
| E | `ems-dashboard` 模块：5 个面板 API + 权限过滤 + 集成测试 | 12 |
| F | `ems-report` 模块（基础）：ad-hoc 查询 + CSV 流式导出 + fileToken 异步模式 | 8 |
| G | 前端 meter 管理页（CRUD + 拓扑编辑） | 10 |
| H | 前端看板页（5 面板 + ECharts + 30s 轮询 + 全局 orgNode 订阅） | 14 |
| I | 前端 ad-hoc 报表页 | 4 |
| J | InfluxDB 种子脚本 + Docker Compose 接入 InfluxDB 容器 | 5 |
| K | E2E 追加用例（建测点、看板出数、CSV 导出、权限过滤看板） | 5 |
| L | 性能验证（50 并发看板 p95<1s；1000 点月报<5s） | 3 |
| **合计** | | **约 107** |

---

## 目录增量（相对 Plan 1.1）

```
ems-meter/                               (新)
ems-timeseries/                          (新)
ems-dashboard/                           (新)
ems-report/                              (新)

ems-app/src/main/resources/db/migration/
  V1.0.2__init_meter.sql                 (新)
  V1.0.3__init_rollup.sql                (新)
  V1.0.9__seed_energy_types.sql          (新)

frontend/src/pages/
  dashboard/                             (替换 Plan 1.1 占位首页)
    index.tsx
    components/
      KpiCards.tsx, RealtimeChart.tsx,
      TopNRank.tsx, MeterDetailDrawer.tsx
  meter/                                 (新)
    list.tsx, topology.tsx
  report/                                (新)
    ad-hoc.tsx

seed/influx-seeder.py                    (新，本地 dev)
docker-compose.yml                       (追加 influxdb 服务)
docker-compose.dev.yml                   (追加 influxdb 服务)
```

---

## 数据流补充（Plan 1.2 特有）

### 看板 API 典型调用

```
GET /api/v1/dashboard/kpi?range=TODAY&orgNodeId=42
  → DashboardController
      → visibleNodeIds = PermissionResolver.visibleNodeIds(userId)
      → 校验 orgNodeId 在 visibleNodeIds 内，否则 ForbiddenException
      → nodeIds = OrgNodeService.findDescendantIds(orgNodeId) ∩ visibleNodeIds
      → meters = MeterService.findByOrgNodeIn(nodeIds, energyType?)
      → results = TimeSeriesQueryService.sumByEnergyType(meters, today)
          ├─ 今天已 rollup 的小时 → ts_rollup_hourly
          └─ 当前未 rollup 的小时 → InfluxDB Flux 现算
      → 组装 [{energyType, total, unit, mom, yoy}]
  ← Result<List<KpiDTO>>
```

### Rollup Job 幂等 Upsert 示例

```sql
INSERT INTO ts_rollup_hourly (meter_id, org_node_id, hour_ts, sum_value, ...)
VALUES (?, ?, ?, ?, ...)
ON CONFLICT (meter_id, hour_ts) DO UPDATE SET
  sum_value = EXCLUDED.sum_value,
  avg_value = EXCLUDED.avg_value,
  ... ;
```

---

## 风险与待验证点

1. **InfluxDB 2.x 的 Flux 查询语法对 aggregateWindow 的行为** — 在契约测试里锁死，避免开发中出现"查询返回行数与预期不一致"的诡异 bug
2. **混合查询的合并点** — rollup 边界（比如此刻 12:34，12:00-12:34 还没 rollup）与已 rollup 的 `11:00` 桶拼接时，时间戳对齐要明确："当前小时" 算到"最新采集点"还是"整点前"
3. **冗余 `org_node_id` 同步** — `meters.org_node_id` 修改时需同步 3 张 rollup 表；方案选 "DB trigger" 还是 "应用层事务更新"（倾向应用层更新，Service 方法里显式 update，trigger 不透明）
4. **CSV 导出流式** — 1000 测点 × 1 年 ≈ 36.5 万行，别一次性加载到内存；用 JPA 游标或 JDBC ResultSet + 流式写响应
5. **看板性能** — 30s 轮询 × 50 并发用户 = 1.67 req/s 看板（其实不高），但每个看板 5 个面板 = 8.3 req/s 后端；加 Caffeine 本地缓存（TTL 10s）可以降到可忽略

---

## 执行前置 Checklist（启动 Plan 1.2 时逐项确认）

- [ ] Plan 1.1 已完成并打 tag `v1.1.0-plan1.1`
- [ ] 已在演示环境手工跑通 Plan 1.1 的 8 个演示步骤
- [ ] Spec 未变更（如有变更需先改 spec 并复审）
- [ ] InfluxDB 2.x 是否可访问（开发环境能启 docker 容器，生产可对接外部已有实例）
- [ ] 外部采集软件已确认 InfluxDB measurement / tag schema 与 spec §4.2 一致

---

## 下一步

Plan 1.2 完整版在 Plan 1.1 执行完毕后写。写之前重新确认：
- 范围是否仍合理（有没有因 Plan 1.1 实施暴露出的新约束需要调整）
- Spec 是否需要微调
- 本骨架列出的 107 个任务是否需要增删
