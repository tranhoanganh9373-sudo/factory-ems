# Plan 1.3 Final Verification — 2026-04-25

## Scope
Plan 1.3 子项目 1 最终验收：电价 / 生产 / 平面图模块 + 看板面板 ⑥⑦⑧⑨ + 全格式报表 + E2E + 性能。
共 23 个 Phase（A–W），全部完成。

## Test Results

| 模块 | 命令 | 结果 |
|---|---|---|
| 全后端 | `./mvnw -q test` | ✅ exit 0 |
| ems-report | `./mvnw -pl ems-report test` | ✅ all green |
| 前端 | `cd frontend && npm run build` | ✅ exit 0（仅 chunk-size 警告） |

## Completed Phases (Plan 1.3)

- A — Plan 1.2 baseline merged
- B — `ems-tariff` 模块（电价方案 + 时段 + resolvePrice 跨零点）
- C — `ems-production` 模块（班次 + 日产量）
- D — `ems-floorplan` 模块（图与点位）
- E — 看板面板 ⑥ 尖峰平谷分布 API
- F — 看板面板 ⑦ 单位产量能耗 API
- G — 看板面板 ⑧ Sankey 能流图 API
- H — 看板面板 ⑨ 平面图实时 API
- I — `ReportMatrix` 统一报表内核
- J — ExcelExporter（POI SXSSF 流式）
- K — PdfExporter（OpenHTMLToPDF）
- L — 报表预设（日/月/年/班次）+ ReportPresetController（REST 暴露）
- M — 异步报表导出（CSV/Excel/PDF + fileToken + ThreadPoolTaskExecutor）
- N — 前端 `/tariff` 方案 CRUD + 24h 时段可视化
- O — 前端 `/production` 班次 CRUD（跨零点橙色 Tag）+ 日产量录入 + CSV 导入
- P — 前端 `/floorplan` 列表 + react-konva 编辑器
- Q — 看板 9 宫格（新增 4 个面板）
- R — 前端 `/report` 日/月/年/班次预设 + 异步导出页
- S — Nginx 平面图直出（`deploy/nginx/floorplan.conf`）
- T — E2E 4 条新增保命用例（floorplan / report-export / viewer-scope / sankey）
- U — k6 压测脚本（dashboard / report-monthly / auth-login）
- V — 1.3 运维手册（`docs/ops/runbook-1.3.md`）
- W — 最终验收 + 打 tag `v1.0.0` ← 本文档

## Architecture Highlights

- **Pivot 报表内核**：`ReportMatrix` (RowDimension × ColumnDimension) 由 4 种预设
  共享，3 种 exporter（CSV / Excel SXSSF / PDF openhtmltopdf）独立写出，互不耦合。
- **跨零点时段**：电价时段、班次都使用 `if (!start.isBefore(end)) endDt = endDt.plusDays(1)`
  作为统一规则。
- **异步导出**：`ThreadPoolTaskExecutor (core=2,max=4,queue=50)` + `TaskDecorator`
  透传 SecurityContext；30 分钟 TTL 内存令牌；`@PreDestroy` 清理临时文件。
- **路由分离**：`/api/v1/report` (单数, legacy CSV) 与 `/api/v1/reports/export`
  (复数, Plan 1.3 异步) 分别由 `ReportController` / `ReportExportController` 持有，
  避免 Spring 路径前缀冲突。

## Worktree-based Subagent Strategy

Phases M / N–R / S+V+T+U 由 3 个并发 subagent 在隔离 worktree 内独立交付，
通过文件区域分割（backend ems-report / frontend / infra+test+perf）避免合并冲突。
合并顺序：backend M → infra → frontend → Phase L wiring（修补 frontend 发现的
controller 缺口）。零冲突合并。

## Next Tag
- `v1.3.0-plan1.3` — Plan 1.3 检查点（沿用既有 plan tag pattern）
- `v1.0.0` — 子项目 1（基础能源监控平台）正式发布
