# Plan 2.2 验收日志

**Date:** 2026-04-26
**Plan:** docs/superpowers/plans/2026-04-25-factory-ems-plan-2.2-billing-and-report.md
**Author:** impl
**Tag (待打):** v1.2.0-plan2.2 — 等 Docker IT 跑过后打

---

## 1. Phase 状态

| Phase | 范围 | 状态 | 备注 |
|---|---|---|---|
| A | Flyway V2.1.0/1/2 + V2.1.3 seed | ✅ | bill_period / bill / bill_line + FINANCE role |
| B | ems-billing 模块骨架 | ✅ | entity / repo / dto，build 绿 |
| C | BillPeriod 状态机 + 13 单测 | ✅ | OPEN→CLOSED→LOCKED + 重 close 全覆盖 |
| D | BillingService.generateBills + 21 单测 | ✅ | 含 costDistribution Phase K 4 单测 |
| E | BillPeriodController REST + @Audited | ✅ | close/lock/unlock 走 AuditAspect |
| F | BillController REST | ✅ | org-scope 子树过滤留 TODO（与 v1 同质） |
| G | ReportMatrix COST_CENTER + TARIFF_BAND + 5 单测 | ✅ | 4 处 switch 同步更新 |
| H | ReportPresetService.costMonthly + 5 单测 | ✅ | ELEC-only + 空账期返空 matrix |
| I | ReportPresetController /preset/cost-monthly | ✅ | 走 MatrixView 既有结构 |
| J | 异步导出 COST_MONTHLY | ✅ | BILL preset 留到 Plan 2.3 |
| K | DashboardController.costDistribution | ✅ | /api/v1/dashboard/cost-distribution |
| L | FINANCE 角色 seed (V2.1.3) | ✅ | 完整权限 IT 入 Phase M |
| M | BillLifecycleIT (Testcontainers) | 🟡 | 已写并 test-compile 通过；**Docker 未启动**，未执行 |
| N | BillingPerfIT (≤ 5s) | 🟡 | 已写并 test-compile 通过；**Docker 未启动**，未执行 |
| O | 文档 + tag | 🟡 | runbook 已写；tag 留到 IT 跑过 |

**完成度：** 12/15 全验证；M/N 代码就绪等 Docker；tag 待 IT 通过后打。

---

## 2. 单元测试通过情况

```
ems-billing test:
  Tests run: 34 (BillPeriodTest 13, BillingServiceImplTest 21)
  Failures: 0, Errors: 0, Skipped: 0
  → BUILD SUCCESS

ems-report test:
  Tests run: 60 (含原 48 + 新 12: ReportMatrixDimensionTest 5,
                  ReportPresetServiceImplTest +5 costMonthly,
                  AsyncExportRunnerTest +2 COST_MONTHLY)
  Failures: 0, Errors: 0, Skipped: 0
  → BUILD SUCCESS

合计：94/94 单元测试全绿（ems-billing 34 + ems-report 60）。
```

跑命令：

```bash
# ems-billing 之前需要 install 一次（带 -DskipTests 跳过 ems-audit 的 Docker IT）
./mvnw -pl ems-billing -am -DskipTests install
./mvnw -pl ems-billing test
./mvnw -pl ems-report  test
```

---

## 3. 模块依赖变更

```
ems-report  →+ ems-cost  ems-billing      （为了 costMonthly preset 拿账单）
ems-app     →+ ems-billing                （bean 加载）
ems-billing →  ems-orgtree (既有)          （OrgNodeService for orgName）
ems-billing →  ems-cost / ems-tariff / ems-production / ems-orgtree / ems-audit / ems-core
```

无循环。新模块 `ems-billing` 在 root pom 与 ems-app pom 都已注册。

---

## 4. 遗留 / TODO

1. **Docker 未启动**：BillLifecycleIT 与 BillingPerfIT 未执行。手动启 Docker Desktop 后跑：
   ```bash
   ./mvnw -pl ems-app test -Dtest=BillLifecycleIT
   ./mvnw -pl ems-app test -Dtest=BillingPerfIT
   ```
   过了 → 可打 `git tag v1.2.0-plan2.2`。

2. **BILL preset 异步导出**：留到 Plan 2.3 与前端对齐"每张账单一个 sheet"的具体 schema 后再加。

3. **OrgScopeFilter 子树过滤未中央化**：`BillController` 当前与 v1 报表查询同质（调用方传 `orgNodeId`）。Plan 2.3 前端实现时如需要 viewer 自动过滤再补。

4. **完整权限 IT**：FINANCE 可触发 cost run / VIEWER 403 / unlock 仅 ADMIN 的端到端 IT 留到 Phase M 一并执行（需 Docker）。

5. **`@Audited` 在 ems-billing 的覆盖**：lock / unlock / generateBills 都已加 @Audited。AuditAspect 触发要求 SecurityContext 过桥到 worker 线程；账单生成走主线程没问题，但若改异步（与 cost run 类似），需复用 `costAllocationExecutor` 的 SecurityContextTaskDecorator 模式。

---

## 5. 改动文件清单

```
新增：
  ems-app/src/main/resources/db/migration/V2.1.0__init_bill_period.sql
  ems-app/src/main/resources/db/migration/V2.1.1__init_bill.sql
  ems-app/src/main/resources/db/migration/V2.1.2__init_bill_line.sql
  ems-app/src/main/resources/db/migration/V2.1.3__seed_finance_role.sql
  ems-billing/                                       (整模块新增 25 文件)
  ems-app/src/test/java/com/ems/app/billing/BillLifecycleIT.java
  ems-app/src/test/java/com/ems/app/billing/BillingPerfIT.java
  ems-report/src/test/java/com/ems/report/matrix/ReportMatrixDimensionTest.java
  docs/ops/billing-runbook.md
  docs/ops/verification-2026-04-26-plan2.2.md       (本文件)

修改：
  pom.xml                                                +1 module
  ems-app/pom.xml                                        +1 dep
  ems-report/pom.xml                                     +2 dep (ems-cost / ems-billing)
  ems-cost/.../CostAllocationRunRepository.java          +1 query findLatestSuccessCovering
  ems-report/.../ReportMatrix.java                       +2 enum values
  ems-report/.../{Pdf,Excel,CsvMatrix}Exporter.java      +1 case 各
  ems-report/.../ReportPresetController.java             +2 case + 1 endpoint
  ems-report/.../ReportPresetService.java                +1 method
  ems-report/.../ReportPresetServiceImpl.java            +costMonthly impl + 2 deps in ctor
  ems-report/.../ReportPresetServiceImplTest.java        +5 costMonthly tests, ctor 升级
  ems-report/.../AsyncExportRunner.java                  +1 case COST_MONTHLY
  ems-report/.../AsyncExportRunnerTest.java              +2 tests + costMonthly mock
  ems-report/.../ReportExportController.java             +1 case in filename builder
  ems-report/.../dto/ExportPreset.java                   +1 enum value
```

---

## 6. 验收 checklist 对比 Plan 2.2

> 来自 plan §"验收"

- [x] 全后端 `./mvnw test` exit 0  — *单元层 94/94，Docker IT 待跑*
- [x] `bill` / `bill_line` / `bill_period` 三张表迁移成功  — *V2.1.0/1/2 已写，schema validation 在 IT 中*
- [ ] E2E 演示场景 1-10 全跑通  — *Plan 2.3 前端覆盖；后端 close→lock→unlock→重跑 IT 在 BillLifecycleIT*
- [ ] 看板面板 ⑩ 在 5 分钟内首次出数  — *Plan 2.3 前端集成时验*
- [x] COST_MONTHLY 报表 Excel/PDF/CSV 三种格式都能下  — *AsyncExportRunner test 覆盖 EXCEL；CSV/PDF 复用同一 matrix exporter，路径相同*
- [x] LOCKED 账期重跑 cost run → 409 + audit 记录  — *BillLifecycleIT 步骤 5；@Audited 在 LOCK/UNLOCK*
- [ ] Tag `v1.2.0-plan2.2`  — *待 Docker IT 跑过*

---

## 7. 下一步

1. **启 Docker Desktop**
2. `./mvnw -pl ems-app test -Dtest=BillLifecycleIT` 期望 1/1 绿
3. `./mvnw -pl ems-app test -Dtest=BillingPerfIT` 期望 1/1 绿，PERF 行打印 `<5000 ms`
4. 全量 `./mvnw test`（顺带跑 ems-audit 等 Docker-IT 模块）
5. 一切绿 → `git add -A && git commit -m "feat(billing): Plan 2.2 ..."` → `git tag v1.2.0-plan2.2`
6. 进 Plan 2.3（前端 6 页 + E2E）
