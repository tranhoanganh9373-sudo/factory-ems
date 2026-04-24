# Factory EMS · Plan 1.3 · 扩展配置 + 完整看板 + 报表 + E2E（骨架）

> **Status:** Skeleton only. Full plan to be written after Plan 1.2 execution completes.

**Goal:** 补齐子项目 1 剩余能力——电价分时、产量填报、平面图点位编辑、看板面板 ⑥⑦⑧⑨、完整报表（日/月/年/班次 + Excel/PDF/CSV）、E2E 保命用例 6 条全绿、性能达标。执行完毕即"子项目 1 正式验收"，具备上线条件。

**Architecture:** 在 Plan 1.2 基础上新增 `ems-tariff`、`ems-production`、`ems-floorplan` 三个领域模块 + 扩展 `ems-dashboard` 和 `ems-report`。前端引入 `react-konva` 做平面图点位编辑器。Nginx 做平面图图片直出。

**Tech Stack (增量):** Apache POI 5.x（Excel）· JasperReports 6.x 或 OpenHTMLToPDF（PDF）· react-konva · ECharts Sankey。

**Spec reference:** `docs/superpowers/specs/2026-04-24-factory-ems-foundation-design.md`

---

## 依赖前提

- **Plan 1.2 必须已完成**（基础看板 + CSV 报表可用）
- Plan 1.2 打的 tag 作为起点

---

## 范围边界

### 本 Plan 交付

| 后端模块 | 职责 |
|---|---|
| `ems-tariff` | 电价方案、尖/峰/平/谷分时规则、`resolvePrice(at)` |
| `ems-production` | 班次字典（全工厂统一）、产量人工填报（组织节点 × 班次 × 日期 × 产品 × 数量） |
| `ems-floorplan` | 平面图上传（`ems_uploads/floorplans/`）、点位 CRUD、读取接口返回图片 URL + 测点坐标 + 实时值 |
| `ems-dashboard`（补齐） | 面板 ⑥（尖峰平谷分布）、⑦（单位产量能耗）、⑧（Sankey）、⑨（平面图热力图）的 REST API |
| `ems-report`（补齐） | 日 / 月 / 年 / 班次报表 + Excel / PDF 导出；组织节点 × 能源品类双维汇总 |

| 前端页面 | 职责 |
|---|---|
| `/` 首页看板 | 9 面板全部可用 |
| `/tariff` | 电价方案与时段配置 |
| `/production/entry` | 产量填报表单（含批量导入） |
| `/production/shifts` | 班次配置 |
| `/floorplan/list` | 平面图列表与上传 |
| `/floorplan/editor/:id` | 平面图点位编辑器（react-konva） |
| `/report/daily`、`/report/monthly`、`/report/yearly`、`/report/shift` | 固化报表 |
| `/report/export` | 选报表 + 格式（Excel/PDF/CSV）的统一导出入口 |

### 交付演示场景

1. Admin 配置电价方案：尖峰平谷时段 + 各时段电价
2. Admin 配置班次（早/中/晚，含跨零点）
3. Admin 在"一车间"填报今日产量（500 件，早班）
4. Admin 上传"一车间"的平面图 → 进编辑器 → 拖拽图钉标注 5 个测点位置 → 保存
5. Admin 进入首页看板，9 个面板全部出数：
   - ⑥ 尖峰平谷：今日电耗按时段分布的饼图
   - ⑦ 单位产量能耗：今日电耗 / 今日产量 的强度曲线
   - ⑧ Sankey：总表 → 分表 → 子表的能流图
   - ⑨ 平面图：车间平面上 5 个测点按实时值着色
6. Admin 进入 `/report/monthly`，选 2026-04 一车间，下载 Excel（带同比环比）和 PDF
7. Admin 进入 `/report/shift`，按班次查昨天电耗
8. VIEWER 以 `/report/*` 查只能看到自己子树的数据
9. E2E 6 个保命用例全绿（在 Plan 1.2 基础上新增 4 条）
10. 性能验证：50 并发看板 p95 < 1s；1000 测点 × 1 年月报 < 5s

---

## 关键架构决策

1. **Tariff 时段跨零点**：`tariff_periods.time_start > time_end` 表跨零点（例 22:00 → 06:00）。`resolvePrice(at)` 的命中算法必须显式处理，单元测试覆盖边界。
2. **Shift 跨零点**：同上逻辑；班次报表用"起始时刻所在 shift"归属（早班 22:00 开始的夜班数据归夜班）。
3. **产量数据维度**：`production_entries (org_node_id, shift_id, entry_date, product_code, quantity, unit)`。MVP 不接 MES，人工填。支持 CSV 批量导入。
4. **Sankey 数据源**：`meter_topology` 表（Plan 1.2 已建）+ Rollup 数据。节点为 meter，边为"父表 → 子表"的能量流，流量用 rollup 数据。
5. **平面图文件存储**：Spring Boot 只负责上传接收、校验、落盘和元数据入库；读取图片走 Nginx `alias` 直出（`/api/v1/floorplans/{id}/image` → `/var/www/uploads/floorplans/{path}`）。已知权衡：图片不做细粒度权限（接受所有登录用户可看）。
6. **点位编辑器**：react-konva Stage + Image + 可拖拽 Circle；保存时提交 `[{meterId, xPx, yPx}, ...]`。
7. **热力图着色**：前端拿实时值 + 阈值配置（上/下限）渲染颜色。阈值 Plan 1.3 先在 meter 表加两个字段 `warning_upper` / `warning_lower`；无阈值的按中性色。
8. **报表导出三格式的共享内核**：
   - 内核 `ReportQueryService` 返回 `ReportMatrix`（行=节点，列=时间桶/能源品类，单元=数值）
   - 三个 exporter：`CsvExporter`、`ExcelExporter`（POI SXSSF 流式）、`PdfExporter`（JasperReports 编译模板）
   - 都消费同一 `ReportMatrix`，避免逻辑分叉
9. **班次报表**：按 `production_entries.shift_id` + `shifts.time_start/end` 聚合对应时段的能耗。实现复杂度：需要按班次时段对 raw / rollup 数据做二次切片；建议 1 分钟原始数据 + 内存聚合（班次报表粒度不会太细）。
10. **导出异步化**：请求 `/reports/export` 返回 `fileToken`；后端放 ThreadPoolTaskExecutor 执行；前端轮询 `/reports/export/{token}` 直到 200 + Content-Disposition 流式文件。
11. **Excel 行数上限**：POI SXSSF 内存安全至百万行；超过需要分 sheet。MVP 限制"单个报表不超过 50 万行"，超过报错让用户拆范围。
12. **PDF 模板**：JasperReports `.jrxml` 模板文件放 `ems-report/src/main/resources/reports/`，Maven 插件编译成 `.jasper`。维护 2 个模板：日报、月报（年报复用月报）。

---

## Phase 索引（估算 Task 数）

| Phase | 范围 | 估 Tasks |
|---|---|---|
| A | Flyway 迁移：`tariff_plans` / `tariff_periods` / `shifts` / `production_entries` / `floorplans` / `floorplan_points` | 6 |
| B | `ems-tariff` 模块：实体 / CRUD / `resolvePrice(at)` + 跨零点测试 | 10 |
| C | `ems-production` 模块：班次 + 产量填报 + 按日期/班次汇总 + CSV 导入 | 12 |
| D | `ems-floorplan` 模块：文件上传（校验类型/大小 MIME）+ 点位 CRUD | 10 |
| E | `ems-dashboard` 扩展：面板 ⑥ 尖峰平谷 API | 4 |
| F | `ems-dashboard` 扩展：面板 ⑦ 能耗强度 API | 4 |
| G | `ems-dashboard` 扩展：面板 ⑧ Sankey API | 5 |
| H | `ems-dashboard` 扩展：面板 ⑨ 平面图实时 API | 4 |
| I | `ems-report` 扩展：`ReportQueryService` 统一内核 + `ReportMatrix` | 6 |
| J | `ems-report` 扩展：`ExcelExporter`（POI SXSSF） | 6 |
| K | `ems-report` 扩展：`PdfExporter`（JasperReports，2 个模板） | 8 |
| L | `ems-report` 扩展：日 / 月 / 年 / 班次报表预设 | 6 |
| M | `ems-report` 扩展：导出异步化（fileToken + 轮询下载） | 4 |
| N | 前端：`/tariff` 电价配置页 | 6 |
| O | 前端：`/production/shifts` 班次配置 + `/production/entry` 产量填报（含 CSV 导入） | 10 |
| P | 前端：`/floorplan/list` 上传 + 列表；`/floorplan/editor/:id` 点位编辑（react-konva） | 14 |
| Q | 前端：看板面板 ⑥⑦⑧⑨ 组件（ECharts Pie / Sankey / Line + Konva 平面图） | 12 |
| R | 前端：`/report/daily|monthly|yearly|shift` 固化报表页 + 统一导出入口 | 12 |
| S | Nginx 平面图直出规则 + `alias` 调优 | 2 |
| T | E2E 6 保命用例补齐（平面图上传/点位、月报导出、VIEWER 报表权限过滤、Sankey 显示） | 6 |
| U | 性能验证（50 并发看板 p95<1s；1000 点月报<5s） + `k6`/`jmeter` 脚本 | 4 |
| V | 运维手册补充：平面图存储、PDF 模板修改方法、CSV 批量导入格式说明 | 3 |
| W | 子项目 1 最终验收 + 打 tag `v1.0.0` | 2 |
| **合计** | | **约 156** |

---

## 目录增量（相对 Plan 1.2）

```
ems-tariff/                              (新)
ems-production/                          (新)
ems-floorplan/                           (新)

ems-app/src/main/resources/db/migration/
  V1.0.3__init_tariff.sql                (新)
  V1.0.4__init_production.sql            (新)
  V1.0.5__init_floorplan.sql             (新)
  V1.0.6__alter_meters_add_thresholds.sql (新)  # warning_upper/lower

ems-report/src/main/resources/reports/
  daily-report.jrxml                     (新)
  monthly-report.jrxml                   (新)

frontend/src/pages/
  dashboard/components/
    TariffDistribution.tsx               (新)
    EnergyIntensity.tsx                  (新)
    SankeyFlow.tsx                       (新)
    FloorplanHeatmap.tsx                 (新)
  tariff/                                (新)
  production/
    shifts.tsx                           (新)
    entry.tsx                            (新)
  floorplan/
    list.tsx                             (新)
    editor.tsx                           (新, react-konva)
  report/
    daily.tsx, monthly.tsx,
    yearly.tsx, shift.tsx                (新)

nginx/conf.d/factory-ems.conf            (修改：加 floorplan alias 规则)
perf/                                    (新, k6 脚本)
```

---

## 关键新增数据流

### 面板 ⑥ 尖峰平谷调用

```
GET /api/v1/dashboard/tariff-distribution?range=TODAY&orgNodeId=42
  → DashboardController
      → visibleNodeIds + nodeIds 处理同前
      → meters = MeterService.findByOrgNodeIn(nodeIds, energyType=ELEC)
      → 按分钟拉今日 InfluxDB 数据 (range=today, granularity=MINUTE)
      → 对每个数据点调 TariffService.resolvePeriodType(timestamp) 归类到 SHARP/PEAK/FLAT/VALLEY
      → 累加 kWh
  ← Result<{sharp, peak, flat, valley, total}>
```

### 面板 ⑧ Sankey 调用

```
GET /api/v1/dashboard/sankey?range=TODAY&orgNodeId=42&energyType=ELEC
  → 1. 取所有 node 下 meters
    2. 读 meter_topology → 构造 (parent_meter_id, child_meter_id) 边集
    3. 对每个 meter 查今日总能耗（rollup + 现算）
    4. 转换为 ECharts Sankey 数据结构：
       { nodes: [{name}], links: [{source, target, value}] }
  ← Result<SankeyDTO>
```

### 报表异步导出

```
POST /api/v1/reports/export
   body: { type: "MONTHLY", month: "2026-04", orgNodeId: 42, format: "EXCEL" }
  → ReportController.export()
      → 校验 format ∈ {EXCEL, PDF, CSV}
      → 生成 fileToken (UUID)
      → 提交任务到 reportExecutor 线程池
      → 返回 { fileToken }

[后台]
  ReportExporter.run(fileToken, params)
      → ReportQueryService.query(params) → ReportMatrix
      → chosenExporter.export(matrix) → file bytes
      → FileStore.put(fileToken, bytes, contentType, filename)

GET /api/v1/reports/export/{fileToken}
  → 如果 FileStore 有：返回流 + Content-Disposition
  → 否则返回 202 Accepted（仍在生成中）
```

---

## 风险与待验证点

1. **PDF 模板维护成本** — JasperReports `.jrxml` 编辑需要 Jaspersoft Studio（IDE），非开发者改样式成本高。MVP 阶段接受"模板少、样式固定"，更复杂的可视化留后续版本。
2. **Sankey 数据稀疏性** — 如果 `meter_topology` 不完整（只连了一部分表），Sankey 会出现"孤立节点"。UI 需要处理此情况（要么隐藏，要么用虚线）。
3. **平面图坐标系** — 平面图图片尺寸可能被用户换成高清版本，已有点位的 `x_px, y_px` 会错位。方案：`floorplans` 表记图片 `width_px, height_px`，点位按比例存储（即存 `x_ratio = x_px/width_px`），渲染时乘以当前图片尺寸。
4. **班次跨零点归属** — 夜班 22:00-06:00，产量归属今日还是明日？按行业惯例归属班次起始日（22:00 入班的那天）。把这写进业务文档避免扯皮。
5. **Excel 导出大报表性能** — 1000 测点 × 1 年月报 ≈ 1000 × 12 = 1.2 万行，POI SXSSF 完全搞得定；年报按日粒度 ≈ 1000 × 365 = 36.5 万行，要测试打开耗时。
6. **VIEWER 看到 Sankey 时的过滤** — Sankey 跨了权限边界的表（父表在上级节点、子表在用户可见节点），怎么处理？方案：只显示"两端都在 visibleNodeIds 内的边"；跨边界的边隐藏（带提示"数据不全"）。
7. **产量 CSV 批量导入的失败处理** — 整批失败 vs 部分成功 vs 全部跳过校验失败行？默认"部分成功"，返回失败行号 + 原因列表给前端显示。

---

## 执行前置 Checklist（启动 Plan 1.3 时逐项确认）

- [ ] Plan 1.2 已完成并打 tag
- [ ] 已在演示环境手工跑通 Plan 1.2 的 9 个演示步骤
- [ ] InfluxDB 有至少 30 天的历史数据（靠 seed 或真实采集）
- [ ] 至少 3 个组织节点 + 10 个测点的真实或模拟配置已存在，供 Sankey 和报表有数据可算
- [ ] 用户提供至少 1 张平面图图片（CAD 或 PNG/JPG）作为 Demo 用

---

## 子项目 1 验收标准（Plan 1.3 完成时的 Definition of Done）

- [ ] 9 个看板面板全部出数
- [ ] 4 类报表（日/月/年/班次）+ ad-hoc 查询均可用
- [ ] 3 种导出格式（CSV / Excel / PDF）均可下载
- [ ] 性能指标达标：50 并发看板 p95 < 1s；1000 测点月报 < 5s
- [ ] E2E 6 个保命用例全绿
- [ ] 覆盖率：后端整体 ≥ 70%，`ems-auth` / `ems-timeseries` / `ems-orgtree` ≥ 80%
- [ ] Docker Compose 一键启动，在干净机器上 ≤ 15 分钟可部署
- [ ] 运维文档（dev-setup / deployment / runbook）可用
- [ ] 审计日志覆盖所有写操作 + 登录事件
- [ ] 打 tag `v1.0.0`，子项目 1 正式可上线

---

## 下一步

Plan 1.3 完整版在 Plan 1.2 执行完毕后写。写之前重新确认：
- 产量填报是否要对接 MES（用户可能中途改变主意，如果真接了 MES 就不需要填报表）
- 平面图数量 / 图片大小的预期（影响存储方案）
- PDF 模板样式要求（企业 LOGO、抬头、页脚等——可能需要一次模板 review）
- Plan 1.2 执行过程中对 TimeSeriesQueryService 接口的实际形态有没有改动（Plan 1.3 的面板全依赖它）
