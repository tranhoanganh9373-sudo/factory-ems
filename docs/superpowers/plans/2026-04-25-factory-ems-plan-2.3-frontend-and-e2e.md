# Factory EMS · Plan 2.3 · 前端 cost/bills 6 页 + E2E + perf

**Goal:** 落地子项目 2 最后一波：6 个新前端页面、4 条新 E2E 用例、性能验证、子项目 2 正式发布 v2.0.0。

**Architecture:** 前端复用 React 18 + AntD + ECharts + Vite。新增路由前缀 `/cost/*` 和 `/bills/*`；不动既有路由。看板从 9 宫格扩 10 宫格（或独立 tab `/dashboard/cost`）。

**Tech Stack (增量):** AntD `Form.List` + `InputNumber` 做权重编辑器；ECharts pie 做面板 ⑩；ECharts sankey 已有；不引入新前端依赖。

**Spec reference:** `docs/superpowers/specs/2026-04-25-factory-ems-subproject-2-cost-allocation.md`

---

## 依赖前提

- Plan 2.2 已完成、tag `v1.2.0-plan2.2`
- 后端 `/api/v1/cost/*` `/api/v1/bills/*` `/api/v1/dashboard/cost-distribution` 全部可用
- 模拟数据 ≥ 3 个完整月（账期管理 + 历史对比有素材）

---

## 范围边界

### 本 Plan 交付

| 前端页面 | 职责 |
|---|---|
| `/cost/rules` | 规则列表 + 编辑器（algorithm 4 选 1 + JSONB weights 表单 + 生效区间） |
| `/cost/runs` | 批次历史 + 触发新 run + 状态实时刷新（轮询） |
| `/cost/runs/:id` | 单次 run 详情：按组织 / 品类透视的 line 表 + 4 段拆分 |
| `/bills` | 账单列表（按账期 + 组织过滤）+ 批量导出 |
| `/bills/periods` | 账期管理（list + close + lock + unlock，全 audit） |
| `/bills/:id` | 单张账单详情 + bill_line 来源链 |
| `/dashboard` 扩展 | 面板 ⑩ 当月成本分布饼图 + 表格 |

| E2E（Playwright）新增 | |
|---|---|
| `cost-rule-crud.spec.ts` | 创建 PROPORTIONAL 规则 + dry-run 预览 |
| `cost-run-flow.spec.ts` | 触发 run → 等 SUCCESS → 看到明细 |
| `bill-period-lifecycle.spec.ts` | close → lock → 重跑被拒 → unlock |
| `cost-export.spec.ts` | COST_MONTHLY Excel 下载 + PK 头校验 |

### 不在本 Plan 内

- 真实数据采集（推到子项目 3 之后做）
- 跨工厂集中财务 / ERP 双向同步（v2）
- 阶梯电价 / 容（需）量电费（v2）

### 交付演示场景

1. ADMIN 登录 → `/cost/rules` 创建规则"1#变压器残差按面积分给 3 车间"
2. 点 dry-run → 弹窗显示预览金额 / 4 段拆分
3. 进 `/cost/runs` 点"新建 run" → 选 2026-03 → 提交 → 状态从 PENDING → RUNNING → SUCCESS（≤ 30s）
4. 点 run 详情 → 表格按组织节点列出 4 段拆分 → "下载 Excel"
5. 进 `/bills/periods` → 2026-03 行 → close → 自动跳 `/bills?period=2026-03` 列出账单
6. 点 lock → 弹"操作不可撤销"确认框 → 确认 → audit 入库
7. 切回 `/cost/runs` 重跑 2026-03 → toast "账期已锁，请先解锁"
8. ADMIN 进 `/bills/periods` → unlock → toast 提示 + audit
9. 看板首页 → 看到面板 ⑩ 当月成本饼图（一车间 35% / 二车间 28% / 公摊 12% / ...）
10. 4 条新 E2E + 既有 E2E 全绿
11. 性能：50 并发看板 p95 < 1s（含面板 ⑩）；COST_MONTHLY 月报 < 5s

---

## 关键架构决策

1. **路由分模块**：`/cost/*` 与 `/bills/*` 各自一个子路由树（`router/index.tsx` 加 2 个 children），不混。菜单按 RBAC：`FINANCE` / `ADMIN` 才看到这 2 个一级菜单。
2. **规则编辑器复用 AntD `Form.List`**：4 种 algorithm 切换时动态切换权重子表单。`weights` 直接拼成 JSONB 提交。前端不做算法逻辑校验（交后端）。
3. **dry-run 用 Modal 弹窗 + Table**：不跳页，预览完关闭即可，鼓励多次试。
4. **run 状态轮询**：500ms 间隔，DONE/SUCCESS/FAILED 后停止。沿用 Plan 1.3 报表导出的轮询 hook。
5. **账期锁定二次确认**：用 AntD `Modal.confirm`，必须输入"我确认锁定 2026-03"才放行（防误操作）。
6. **看板面板 ⑩ 与既有 9 宫格同布局**：不另开 tab；MVP 直接扩 grid 到 10 格，第 10 格饼图。后续如果需要更多成本面板再开 `/dashboard/cost` 子页。
7. **org-scope 在前端表现**：账单 list 默认带当前用户的 viewable orgIds 过滤，FINANCE 默认全选。
8. **导出沿用 Plan 1.3 的 `useExportPolling` hook**：表单只传 `preset` + `params`，不重写下载逻辑。
9. **i18n**：本 Plan 还是中文 only（与 v1 一致），保留 i18next key 但不加英文翻译，留给后续。
10. **错误处理**：409 账期已锁、403 权限不足、422 算法参数无效——统一在 `api/client.ts` interceptor 转 toast。
11. **不引入状态管理库**：用现有 React Query；`/cost/rules` 列表 staleTime=30s。
12. **打 tag 时机**：所有 E2E 通过后打 `v2.0.0`（子项目 2 正式发布）。

---

## Phase 索引（估算 Task 数）

| Phase | 范围 | 估 Tasks |
|---|---|---|
| A | 路由 / 菜单 / RBAC：`router/index.tsx` 加 cost+bills 子树 + Sidebar 加菜单 + FINANCE 角色识别 | 4 |
| B | `api/cost.ts` + `api/bills.ts`：所有 REST 调用 typed 包装 | 5 |
| C | `/cost/rules` 列表 + 创建/编辑器（4 algorithm × Form.List）+ dry-run Modal | 10 |
| D | `/cost/runs` 列表 + 触发新 run + 轮询状态 | 6 |
| E | `/cost/runs/:id` 明细页（透视表 + 4 段拆分） | 5 |
| F | `/bills` 列表 + 过滤 + 批量导出 | 5 |
| G | `/bills/periods` 账期管理（close / lock / unlock + 二次确认 + audit toast） | 6 |
| H | `/bills/:id` 详情 + bill_line 来源链 | 4 |
| I | 看板面板 ⑩：饼图组件 + 表格 + Dashboard.tsx grid 扩 10 格 | 5 |
| J | E2E `cost-rule-crud.spec.ts` | 3 |
| K | E2E `cost-run-flow.spec.ts` | 3 |
| L | E2E `bill-period-lifecycle.spec.ts` | 4 |
| M | E2E `cost-export.spec.ts` | 3 |
| N | 性能：k6 脚本扩展（cost-distribution + cost-monthly）+ 跑基线 | 4 |
| O | 文档：`docs/ops/runbook-2.0.md` + 验收日志 + tag `v2.0.0` | 3 |

**合计估算：~70 tasks，5-7 工作日。**

---

## 验收

- 前端 `npm run build` exit 0
- 全 E2E 跑过：既有 + 4 条新加 = 11 条以上
- 看板首页 9+1 面板全部出数
- COST_MONTHLY / BILL 三种格式导出都能下
- 性能：50 并发看板 p95 < 1s（含面板 ⑩）
- 子项目 2 验收日志 `docs/ops/verification-YYYY-MM-DD-plan2.3.md`
- Tag `v2.0.0`

## 不变量（防回归）

- `/cost/*` 和 `/bills/*` 菜单只对 ADMIN / FINANCE 可见；VIEWER 不能在路由层访问
- 账期 lock / unlock 必须二次确认 + 调用 audit-aware 接口
- run 状态轮询必须在 SUCCESS/FAILED 后停止（防内存泄漏）
- 所有金额展示统一 `BigDecimal` → `string` 格式化（保留 4 位小数显示，UI 默认 2 位、可展开看 4 位）
- 任何成本相关导出都走 Plan 1.3 异步导出（`/api/v1/reports/export`）+ `useExportPolling`
