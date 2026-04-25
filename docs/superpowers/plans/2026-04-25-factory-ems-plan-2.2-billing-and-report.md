# Factory EMS · Plan 2.2 · 账期账单 + 报表 + 看板

**Goal:** 落地子项目 2 第二波：`ems-billing` 模块（账期状态机 + 账单生成 + 锁定审计）+ `ems-report` 新增 `COST_MONTHLY` 预设 + 看板面板 ⑩。完成后能"关账期 → 看到账单 → 看板出成本饼图 → 报表导 Excel"。

**Architecture:** 在 Plan 2.1 的 `ems-cost` 上叠 `ems-billing`。账单 = (`cost_allocation_run.SUCCESS`) + (`production_entry` 同期产量) → `bill` + `bill_line`。看板 ⑩ 直接读最近一次 SUCCESS run。

**Tech Stack (增量):** 沿用 v1.0.0；不引入新依赖。复用 `ReportMatrix` 内核（新增 `ColumnDimension.TARIFF_BAND` 枚举值）。

**Spec reference:** `docs/superpowers/specs/2026-04-25-factory-ems-subproject-2-cost-allocation.md`

---

## 依赖前提

- Plan 2.1 已完成、tag `v1.1.0-plan2.1`
- 模拟数据 ≥ 2 个完整月（让账期管理有得跑）
- 至少 1 次 `cost_allocation_run.SUCCESS` 已写入

---

## 范围边界

### 本 Plan 交付

| 后端模块 | 职责 |
|---|---|
| `ems-billing`（新） | `BillPeriod` 状态机 (OPEN→CLOSED→LOCKED) + `BillingService.generateBills(periodId)` + `lockPeriod` / `unlockPeriod` 审计 |
| `ems-report`（扩） | `RowDimension.COST_CENTER` + `ColumnDimension.TARIFF_BAND` + 预设 `COST_MONTHLY`（行=组织节点，列=4 段电价 + 合计） |
| `ems-dashboard`（扩） | 面板 ⑩ 当月成本分布 API（`GET /api/v1/dashboard/cost-distribution?period=YYYY-MM`） |
| `ems-report`（扩） | 异步导出新增 `preset=COST_MONTHLY` 与 `preset=BILL` |

### 不在本 Plan 内

- 前端 6 页（Plan 2.3）
- E2E 全套（Plan 2.3）
- 阶梯电价 / 容量电费（推到 v2）
- 多币种（推到 v2）

### 交付演示场景

1. 已经有 `2026-03` 的 SUCCESS run
2. `PUT /api/v1/bills/periods/2026-03/close` → `bill_period.status=CLOSED`，`bill` + `bill_line` 已写
3. `GET /api/v1/bills?period=2026-03&orgNodeId=10` → 返回一车间各能源品类账单
4. `GET /api/v1/bills/{id}/lines` → 返回 bill_line（"这 ¥1234 是怎么来的"）
5. `PUT /api/v1/bills/periods/2026-03/lock` → `LOCKED`，`audit_log` 写一行
6. 用 `FINANCE` 账号重跑 cost run → API 返回 409，提示账期已锁
7. ADMIN `PUT /api/v1/bills/periods/2026-03/unlock` → 200，`audit_log` 再写一行
8. `POST /api/v1/reports/export body {preset: "COST_MONTHLY", period: "2026-03"}` → 异步生成，下载 Excel
9. `GET /api/v1/dashboard/cost-distribution?period=2026-03` → 饼图数据
10. 全后端 `./mvnw test` exit 0

---

## 关键架构决策

1. **账期状态机用枚举 + 显式转换方法**：`BillPeriod.close()` / `lock()` / `unlock()`，禁止外部直接改 status 字段。
2. **lock / unlock 必走 audit**：复用子项目 1 的 `AuditLogService.record(actor, action, before, after)`。
3. **账单聚合一次成型**：`BillingService.generateBills(periodId)` 在事务内：(1) 取最新 SUCCESS run、(2) GROUP BY (org, energy) 聚合、(3) JOIN `production_entry` 算 unit_cost、(4) `bill` saveAll、(5) `bill_line` 按 rule_id 分组 saveAll、(6) `BillPeriod.status=CLOSED`。中途失败整体回滚。
4. **覆盖检查**：`generateBills` 启动时校验 `cost_allocation_run.period_start <= bill_period.period_start AND run.period_end >= bill_period.period_end`，否则拒绝（避免把"前 15 天的 run"当成"整月账单"）。
5. **重新关账期**：CLOSED 可以再 close（重新生成）—— 删旧 `bill` + `bill_line` 后重写。LOCKED 不行。
6. **看板 ⑩ 读最近 SUCCESS run**：`SELECT FROM cost_allocation_line WHERE run_id = (SELECT id FROM cost_allocation_run WHERE status='SUCCESS' ORDER BY finished_at DESC LIMIT 1)`，与 bill 表解耦。账单还没生成时也能看。
7. **报表 `COST_MONTHLY` 预设走既有内核**：`ReportPresetService.costMonthly(YearMonth, orgNodeId?)` 返回 `ReportMatrix`，行=组织节点（COST_CENTER），列=尖/峰/平/谷/合计 5 段（TARIFF_BAND）。三个 exporter 都吃这个 matrix——禁止单独写"成本专用 exporter"。
8. **预设 `BILL` 导 Excel**：行=能源品类，列=quantity / 4 段 amount / total / production / unit_cost。每张账单一个 sheet（按 `period_id` 分）。
9. **`unit_cost` 缺失策略**：`production_qty=0 OR NULL` 时 `unit_cost = NULL`。前端显示 "—" 而不是 ∞。
10. **org-scope 过滤**：账单查询沿用 `OrgScopeFilter`；FINANCE 默认 viewable=全部。
11. **Flyway 编号 V250__**：避开 cost 模块的 V200 段。
12. **不动 cost_allocation_***：本 Plan 只读不写——账单生成失败也不删 cost line。

---

## Phase 索引（估算 Task 数）

| Phase | 范围 | 估 Tasks |
|---|---|---|
| A | Flyway 迁移：`V250__bill_period.sql` / `V251__bill.sql` / `V252__bill_line.sql` + index + audit_log 不动 | 3 |
| B | `ems-billing` 模块骨架（pom / entity / repo / DTO） | 5 |
| C | `BillPeriod` 状态机 + close/lock/unlock 单测 | 6 |
| D | `BillingService.generateBills(periodId)` + 覆盖检查 + 重写策略 + 单测 | 8 |
| E | `BillPeriodController` REST（list / close / lock / unlock，含 audit）+ 单测 | 5 |
| F | `BillController` REST（list / detail / lines，含 org-scope）+ 单测 | 5 |
| G | `ReportMatrix` 增加 `RowDimension.COST_CENTER` + `ColumnDimension.TARIFF_BAND` + 单测 | 3 |
| H | `ReportPresetService.costMonthly()` + 单测 | 5 |
| I | `ReportPresetController` 暴露 `/preset/cost-monthly` | 2 |
| J | 异步导出器扩展：`preset=COST_MONTHLY` + `preset=BILL`（每张账单一个 sheet） | 5 |
| K | 看板面板 ⑩：`DashboardController.costDistribution()` + 单测 | 5 |
| L | `FINANCE` 角色 seed + role-based access 单测 | 3 |
| M | 集成测试：种数据 → run → close → lock → unlock → 重跑被拒 | 6 |
| N | 性能：账单聚合 + 写库 ≤ 5s（200 org × 5 energy） | 2 |
| O | 文档：`docs/ops/billing-runbook.md` + 验收日志 + tag `v1.2.0-plan2.2` | 3 |

**合计估算：~66 tasks，4-6 工作日。**

---

## 验收

- 全后端 `./mvnw test` exit 0
- `bill` / `bill_line` / `bill_period` 三张表迁移成功
- E2E 演示场景 1-10 全跑通
- 看板面板 ⑩ 在 5 分钟内首次出数（≤ 1s p95）
- COST_MONTHLY 报表 Excel/PDF/CSV 三种格式都能下
- LOCKED 账期重跑 cost run → 409 + audit 记录
- Tag `v1.2.0-plan2.2`

## 不变量（防回归）

- `bill_period.status` 流转只允许：OPEN→CLOSED、CLOSED→LOCKED、CLOSED→CLOSED（重写）、LOCKED→CLOSED（unlock）
- LOCKED 期间任何 `bill` / `bill_line` UPDATE 必须被 service 层拒绝
- 看板 ⑩ 永远读最近 SUCCESS 的 cost_allocation_run，不读 bill（保证未关账期也能看）
- COST_MONTHLY 报表与 BILL 报表都通过 `ReportMatrix` 内核走，禁止单独写新内核
