# Factory EMS · Plan 2.1 · 成本分摊后端（cost engine）

**Goal:** 落地子项目 2 第一波：`ems-cost` 模块 + 4 种分摊算法（DIRECT/PROPORTIONAL/RESIDUAL/COMPOSITE）+ 分时电价拆分 + 异步 run + dry-run 预览。完成后能在数据库里看到 `cost_allocation_run.SUCCESS` 和拆好 4 段的 `cost_allocation_line`。

**Architecture:** 在 v1.0.0 模块化单体上新增 `ems-cost` 模块。复用 Plan 1.3 的 `ThreadPoolTaskExecutor + TaskDecorator`、`tariff` 模块的 4 段时段、`org_node` 树。不动既有 v1 表与 API。

**Tech Stack (增量):** Jackson 处理 `weights JSONB`（Hibernate `@JdbcTypeCode(SqlTypes.JSON)`）· `BigDecimal` 4 位小数运算 · `BigDecimal.divide(..., RoundingMode.HALF_UP)` 防尾差。

**Spec reference:** `docs/superpowers/specs/2026-04-25-factory-ems-subproject-2-cost-allocation.md`

---

## 依赖前提

- 子项目 1 v1.0.0 已上线
- 模拟数据已经入库（详见 `docs/superpowers/plans/2026-04-25-factory-ems-mock-data-plan.md`）：≥ 1 个完整月的 `ts_rollup_hourly` + 完整 org_node + tariff_plan + production_entry
- Plan 1.3 异步导出框架可用

---

## 范围边界

### 本 Plan 交付

| 后端模块 | 职责 |
|---|---|
| `ems-cost`（新） | `CostAllocationRule` CRUD、4 种算法实现、`CostAllocationService.run()` 异步、`dryRun()` 同步预览、4 段电价拆分 |
| `ems-tariff`（扩） | 暴露 `TariffPriceLookupService.batch(meterId, periodStart, periodEnd)` 返回每小时所属段 + 单价（避免逐点循环 N 次 query） |
| `ems-production`（扩） | 暴露 `ProductionService.sumByOrg(orgId, periodStart, periodEnd)` 给 Plan 2.2 用（本 Plan 内只写接口，不接） |

### 不在本 Plan 内

- 账单 / 账期（Plan 2.2）
- 报表预设 `COST_MONTHLY` / 看板面板 ⑩（Plan 2.2）
- 前端 cost 页面（Plan 2.3）
- E2E（Plan 2.3）
- 真实数据接入（推到子项目 3 之后）

### 交付演示场景（curl + DB 查询）

1. `POST /api/v1/cost/rules` 创建 1 条 PROPORTIONAL 规则（"1#变压器总表按车间面积分给一/二/三车间"）
2. `POST /api/v1/cost/rules/{id}/dry-run?period=2026-03` 同步返回预览 JSON（`{lines: [...]}`，不落库）
3. `POST /api/v1/cost/runs` body `{periodStart, periodEnd}` → 返回 `{runId}` + 状态 PENDING
4. 轮询 `GET /api/v1/cost/runs/{id}` → 看到 RUNNING → SUCCESS，`total_amount` 与 dry-run 一致
5. `GET /api/v1/cost/runs/{id}/lines?orgNodeId=10` → 返回该组织节点的 4 段拆分（sharp/peak/flat/valley quantity & amount）
6. 重跑同账期 → 旧 run 自动 SUPERSEDED
7. PG: `SELECT status, count(*) FROM cost_allocation_run GROUP BY 1` → `SUCCESS=1, SUPERSEDED=N`
8. 单元 + 集成测试全绿；mvn `-pl ems-cost test` 0 失败

---

## 关键架构决策

1. **算法分发用接口 + 工厂**：`AllocationAlgorithm` 接口 + `AllocationAlgorithmFactory.of(rule.algorithm)`，新增算法不改 Service。
2. **JSONB 用 Hibernate 6 原生支持**：`@JdbcTypeCode(SqlTypes.JSON)` + `Map<String, Object>` 字段，避免引入 hypersistence-utils。
3. **4 段拆分发生在算法内部**：`PROPORTIONAL.allocate()` 内部先调 `TariffPriceLookupService.batch()` 拿"每小时段属"，再把每小时分给各 org 后按段累计金额。**不要**先汇总成 total 再拆段——会丢精度。
4. **重跑 = 新 run + 老 run SUPERSEDED**：在写入 SUCCESS 时，事务内 `UPDATE cost_allocation_run SET status='SUPERSEDED' WHERE period_start=? AND period_end=? AND status='SUCCESS' AND id<>?`。
5. **异步用专用 Executor**：`costAllocationExecutor` core=1 max=2 queue=20，与 Plan 1.3 的 `reportExportExecutor` 隔离。一次 run 跑 30s 不能挤掉报表导出。
6. **TaskDecorator 透传 SecurityContext**：复用 Plan 1.3 的 `SecurityContextTaskDecorator`。
7. **dry-run 不落库**：`AllocationAlgorithm.allocate()` 返回 `List<AllocationLine>` 流，由 caller 决定是否 persist。`dryRun()` 不创建 run、不调 saveAll。
8. **负残差策略**：`RESIDUAL` 算法发现 `residual < 0` 时 clamp 到 0、写 warn 日志、写 `cost_allocation_run.error_message` 但 status 仍 SUCCESS（带告警标记），不阻塞。MVP 后续再做"按比例缩 deductMeter"。
9. **币种和精度**：所有金额 `NUMERIC(18, 4)`、Java 用 `BigDecimal`、scale=4、`RoundingMode.HALF_UP`。`quantity` 同样 4 位。**禁止用 double**。
10. **测点用量来源**：本 Plan 直接读子项目 1 的 `ts_rollup_hourly`（已经按测点 × 小时 SUM），不要读 Influx raw 1 分钟数据——量太大。
11. **跨零点沿用 v1 规则**：`if (!start.isBefore(end)) endDt = endDt.plusDays(1)`；`tariff` 时段命中复用 `TariffPriceLookupService` 实现，不重写。
12. **dry-run 的"如果分摊"路径**：`POST /api/v1/cost/rules/{id}/dry-run?period=2026-03` 只跑该 1 条规则；`POST /api/v1/cost/runs body {ruleIds: [...]}` 也支持子集 run，方便排查。

---

## Phase 索引（估算 Task 数）

| Phase | 范围 | 估 Tasks |
|---|---|---|
| A | Flyway 迁移：`V200__cost_allocation_rule.sql` / `V201__cost_allocation_run.sql` / `V202__cost_allocation_line.sql` + index | 4 |
| B | `ems-cost` 模块骨架：pom + 实体 + Repository + DTO record | 6 |
| C | `TariffPriceLookupService.batch()` 在 `ems-tariff` 扩展 + 单测（跨零点 + 4 段命中） | 5 |
| D | `AllocationAlgorithm` 接口 + `DirectAlgorithm` + 单测 | 4 |
| E | `ProportionalAlgorithm`（FIXED/AREA/HEADCOUNT/PRODUCTION 4 种 basis） + 单测 | 8 |
| F | `ResidualAlgorithm`（含负残差 clamp）+ 单测 | 6 |
| G | `CompositeAlgorithm`（链式调用 sub-rule） + 单测 | 4 |
| H | `TariffCostCalculator` 4 段拆分 + 单测 | 5 |
| I | `CostAllocationService.dryRun()` 同步实现 + 单测 | 4 |
| J | `CostAllocationService.run()` 异步实现（专用 Executor + SUPERSEDED 事务）+ 单测 | 6 |
| K | `CostRuleController` REST CRUD + 权限（FINANCE 角色） | 5 |
| L | `CostAllocationController` REST（runs / dry-run / runs/{id}/lines） | 5 |
| M | 集成测试（Testcontainers PG + Influx）：种 1 个月数据 → 跑 → 校验明细 | 6 |
| N | 性能基线：50 规则 × 200 组织 × 1 月 ≤ 30s（k6 或 JUnit @Timeout） | 3 |
| O | 文档：`docs/ops/cost-engine-runbook.md` + 验收日志 + tag `v1.1.0-plan2.1` | 3 |

**合计估算：~75 tasks，按照 Plan 1.3 的并发节奏 5-7 工作日。**

---

## 验收

- 全后端 `./mvnw test` exit 0
- `./mvnw -pl ems-cost test` 0 失败
- 集成测试：固定 fixture 输入 → 期望输出 line by line
- 性能：50 规则 × 200 组织 × 1 月 ≤ 30s
- 重跑同账期 → 老 run 自动 SUPERSEDED，新 run 落 SUCCESS
- 文档：`docs/ops/cost-engine-runbook.md` 描述算法语义 / 重跑 / 排查步骤
- Tag `v1.1.0-plan2.1`

## 不变量（防回归）

- `cost_allocation_*` 三张表只 INSERT 不 UPDATE（除 status），UPDATE 只允许 `cost_allocation_run.status` 流转
- 金额一律 `BigDecimal scale=4 HALF_UP`
- 算法实现互不引用，统一通过 `AllocationAlgorithmFactory.of()` 分发
- `cost_allocation_run` 写 SUCCESS 必须事务内同时 SUPERSEDED 老 run
