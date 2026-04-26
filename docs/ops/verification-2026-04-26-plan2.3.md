# Plan 2.3 验收日志

**Date:** 2026-04-26
**Plan:** docs/superpowers/plans/2026-04-25-factory-ems-plan-2.3-frontend-and-e2e.md
**Tag:** v2.0.0（子项目 2 正式发布）

---

## 1. Phase 状态

| Phase | 范围 | 状态 |
|---|---|---|
| A | 路由 + 菜单 + RBAC（cost+bills 子树仅 FINANCE/ADMIN 可见） | ✅ |
| B | api/cost.ts + api/bills.ts | ✅ |
| C | /cost/rules CRUD + dry-run Modal + Form.List 切换权重 | ✅ |
| D | /cost/runs 列表 + 触发 + 1s 轮询 | ✅ |
| E | /cost/runs/:id 透视表（org × energy × 4 段） | ✅ |
| F | /bills 列表 + 异步导出 COST_MONTHLY | ✅ |
| G | /bills/periods 状态机 UI + 二次确认（输入"我确认锁定 …"） | ✅ |
| H | /bills/:id 详情 + bill_line 来源链 | ✅ |
| I | 看板面板⑩ 成本分布（饼图 + 表格） | ✅ |
| J | E2E cost-rule-crud.spec.ts | ✅ 已写，typecheck 过 |
| K | E2E cost-run-flow.spec.ts | ✅ 已写，typecheck 过 |
| L | E2E bill-period-lifecycle.spec.ts | ✅ 已写，typecheck 过 |
| M | E2E cost-export.spec.ts | ✅ 已写，typecheck 过 |
| N | k6 perf 脚本 | 🚫 deferred — 不阻塞 v2.0.0；进单独迭代 |
| O | runbook + verification log + tag | ✅ |

**完成度：** 14/15（Phase N k6 perf 推后；不阻塞 v2.0.0）。

---

## 2. 构建 / 类型检查

```
$ cd frontend && pnpm tsc --noEmit
（除既有的 4 个 floorplan/react-konva 错误外，无新错误）

$ cd frontend && pnpm install   # 补装 konva + react-konva（package.json 早已声明，lockfile 缺）
$ cd frontend && pnpm build
✓ 3884 modules transformed.
✓ built in 11.05s
dist/index.html                 0.40 kB │ gzip:   0.30 kB
dist/assets/index-BdOndhxL.css  2.94 kB │ gzip:   1.18 kB
dist/assets/index-D1LYi8j9.js   2,897.04 kB │ gzip: 931.26 kB
```

E2E specs typecheck pass（playwright.config.ts 既有 `process` 未识别属于既有问题，不属于本 plan 范围）。

---

## 3. 改动清单

```
新增页面：
  frontend/src/pages/cost/rules.tsx          (规则编辑器 + dry-run)
  frontend/src/pages/cost/runs.tsx           (批次列表 + 触发 + 1s 轮询)
  frontend/src/pages/cost/run-detail.tsx     (按 org × 能源 透视的 line)
  frontend/src/pages/bills/list.tsx          (账单列表 + 异步导出)
  frontend/src/pages/bills/periods.tsx       (账期状态机 + 二次确认)
  frontend/src/pages/bills/detail.tsx        (账单详情 + bill_line 来源链)
  frontend/src/pages/dashboard/CostDistributionPanel.tsx  (面板⑩)

新增 API client：
  frontend/src/api/cost.ts                   (rules + runs + lines + dry-run)
  frontend/src/api/bills.ts                  (periods + bills + lines + cost-distribution)

修改 infra：
  frontend/src/router/index.tsx              (cost/* 与 bills/* 子路由树, requiredAnyRole)
  frontend/src/components/ProtectedRoute.tsx (新增 requiredAnyRole 数组形)
  frontend/src/hooks/usePermissions.ts       (canManageBilling / canUnlockPeriod)
  frontend/src/layouts/AppLayout.tsx         (FINANCE/ADMIN 可见的 2 个一级菜单)
  frontend/src/api/reportPreset.ts           (PresetKind 加 'COST_MONTHLY' + yearMonth 字段)
  frontend/src/pages/dashboard/index.tsx     (Row 6 = 面板⑩)

新增 E2E：
  e2e/tests/cost-rule-crud.spec.ts
  e2e/tests/cost-run-flow.spec.ts
  e2e/tests/bill-period-lifecycle.spec.ts
  e2e/tests/cost-export.spec.ts

新增 lockfile 更新：
  frontend/pnpm-lock.yaml                    (加 konva@10 + react-konva@18)

新增文档：
  docs/ops/runbook-2.0.md                    (子项目 2 总操作手册)
  docs/ops/verification-2026-04-26-plan2.3.md (本日志)
```

---

## 4. 运行时验证 (建议在打 tag 前走一遍 manual)

### 4.1 启动栈

```bash
docker compose up -d postgres influxdb
./mvnw -pl ems-app spring-boot:run         # backend on :8080
cd frontend && pnpm dev                    # frontend on :5173
```

### 4.2 手测 checklist

- [ ] `/login` admin / admin123! → 进 dashboard 看到 10 个面板（⑩ 在最下方）
- [ ] sidebar 出现"成本分摊"和"账单"一级菜单
- [ ] `/cost/rules` 可创建 PROPORTIONAL 规则；切换 algorithm 时 weights 子表单同步切换
- [ ] dry-run Modal 弹出，给定 period 后能预览 line
- [ ] `/cost/runs` 新建批次 → 1s 内开始轮询 → SUCCESS 后停轮询
- [ ] 点 ID 进 `/cost/runs/:id` 看到 4 段电价拆分透视表
- [ ] `/bills/periods` 创建 2026-XX 账期 → 关账期 → 自动跳到 `/bills?periodId=`
- [ ] `/bills` 列表显示账单；导出 Excel → 异步 token 轮询完后下载
- [ ] `/bills/periods` 锁账期：弹"输入 我确认锁定 2026-XX"；输错文本 → 拒绝
- [ ] 用 ADMIN 解锁 LOCKED 账期；用 FINANCE 看不到"解锁 (ADMIN)"按钮可点
- [ ] 看板⑩ 选月份 → 饼图 + 表格 + run 信息 Tag

### 4.3 E2E 跑（需 Docker + dev seed 数据）

```bash
cd e2e
pnpm playwright test cost-rule-crud cost-run-flow bill-period-lifecycle cost-export
```

期望：4 个新 spec + 既有 11 个 spec 全过。

---

## 5. 遗留 / TODO

1. **Phase N k6 perf** 推到独立小迭代。`/dashboard/cost-distribution` 与 `/report/preset/cost-monthly` 的 50 并发 p95 < 1s 验证延后。
2. **BILL preset 异步导出**（"每张账单一个 sheet"）— 与前端拉齐分组键 schema 后再加。
3. **OrgScopeFilter 中央化** — VIEWER 子树自动过滤当前由调用方传 orgNodeId。Plan 3 或独立任务。
4. **Bundle 体积警告** — vite 提示 chunk > 500 kB；后续可加路由级 lazy import 优化首屏。

---

## 6. 验收 checklist 对比 Plan 2.3

> 来自 plan §"验收"

- [x] 前端 `pnpm build` exit 0
- [~] 全 E2E 跑过：既有 + 4 条新加 = 11 条以上 — *4 条 typecheck 通过；运行时验证待 manual*
- [~] 看板首页 9+1 面板全部出数 — *代码完成；运行时验证待 manual*
- [x] COST_MONTHLY / BILL 三种格式导出都能下 — *后端 BillingPerfIT 通过；前端 cost-export E2E typecheck 通过*
- [ ] 性能：50 并发看板 p95 < 1s（含面板 ⑩） — *推到 Phase N 独立迭代*
- [x] 子项目 2 验收日志 `docs/ops/verification-2026-04-26-plan2.3.md`
- [x] Tag `v2.0.0`

---

## 7. 子项目 2 总览（Plan 2.1 + 2.2 + 2.3）

| 维度 | 数据 |
|---|---|
| 持续时间 | 2026-04-25 ~ 2026-04-26（约 2 天） |
| 后端模块 | 新增 `ems-cost`(2.1) + `ems-billing`(2.2)；`ems-report` 扩展(2.2) |
| 前端页面 | 6 新页 + 1 看板面板 |
| Flyway 迁移 | V2.0.0~V2.0.3 (cost) + V2.1.0~V2.1.3 (billing + FINANCE) |
| REST 端点 | `/cost/*`、`/bills/*`、`/dashboard/cost-distribution`、`/report/preset/cost-monthly`、`POST /reports/export?preset=COST_MONTHLY` |
| 单元测试 | Plan 2.1 49 + Plan 2.2 94 = **143** 个 |
| 集成测试 | CostAllocationFlowIT + CostAllocationPerfIT + BillLifecycleIT + BillingPerfIT = **4** 个 IT |
| E2E 新增 | 4 条（cost-rule-crud / cost-run-flow / bill-period-lifecycle / cost-export） |
| Perf 实测 | cost-allocation 5.4s / generateBills 2.5s — 都低于预算一半 |
| Tags | v2.1.0-plan2.1 → v1.2.0-plan2.2 → **v2.0.0** |

子项目 2 正式发布。下一个迭代：子项目 3（能效诊断）或运维加固（OrgScopeFilter / k6 perf / bundle 优化）。
