# Billing Runbook · 子项目 2 · Plan 2.2

**适用版本：** 1.2.0-plan2.2 起
**模块：** `ems-billing`
**Spec：** `docs/superpowers/specs/2026-04-25-factory-ems-subproject-2-cost-allocation.md` §4.1.4-§4.1.6 / §6 / §7 / §10

---

## 0. 一句话

> 账期是把 **cost_allocation_run.SUCCESS** 的明细按 (org, energy) 聚合落到 `bill` 表的过程。状态机 `OPEN → CLOSED → LOCKED`；关账期 = 写账单；锁账期 = 防写；解锁仅 ADMIN。

---

## 1. 状态机

```
OPEN ── close ──▶ CLOSED ── lock ──▶ LOCKED
                 ▲                    │
                 └────── unlock ──────┘
                 │
                 └─ close (重写：先删旧 bill+line 再写新)
```

| 起 | 操作 | 终 | 谁 | 副作用 |
|---|---|---|---|---|
| OPEN | close | CLOSED | FINANCE/ADMIN | 写 bill + bill_line，记 closed_by/closed_at |
| CLOSED | close | CLOSED | FINANCE/ADMIN | 删旧 bill（CASCADE 删 bill_line）→ 重写 |
| CLOSED | lock | LOCKED | FINANCE/ADMIN | 记 locked_by/locked_at；audit_log 一条 |
| LOCKED | unlock | CLOSED | **仅 ADMIN** | 清 lock 字段；audit_log 一条 |
| LOCKED | close / generateBills | ❌ | — | IllegalStateException → HTTP 409 |

`BillPeriod` 的 `close()` / `lock()` / `unlock()` 是显式方法，禁止外部改 `status` 字段。`assertWritable()` 用于 service 层任何 bill 写操作前的兜底拒绝。

---

## 2. REST 端点速查

### 账期

```
GET    /api/v1/bills/periods                 列表
GET    /api/v1/bills/periods/{ym}            按 YYYY-MM 单查
POST   /api/v1/bills/periods?ym=YYYY-MM      创建（已存在则原样返回）  FINANCE/ADMIN
PUT    /api/v1/bills/periods/{id}/close      关账期 = 触发 generateBills FINANCE/ADMIN
PUT    /api/v1/bills/periods/{id}/lock       锁定                         FINANCE/ADMIN，audited
PUT    /api/v1/bills/periods/{id}/unlock     解锁                         **仅 ADMIN**，audited
```

### 账单

```
GET    /api/v1/bills?periodId={id}&orgNodeId={?}&energyType={?}     列表
GET    /api/v1/bills/{id}                                            详情
GET    /api/v1/bills/{id}/lines                                      来源链（bill_line）
```

### 看板（面板 ⑩）

```
GET    /api/v1/dashboard/cost-distribution?period=YYYY-MM   period 可空 → 取最新 SUCCESS run
```

### 报表

```
GET    /api/v1/report/preset/cost-monthly?ym=YYYY-MM&orgNodeId={?}   行=组织、列=4 段电价+合计
POST   /api/v1/reports/export                                          preset=COST_MONTHLY 走异步导出
```

---

## 3. 标准操作流程（月底关账）

> 假设要关 `2026-03` 的账。

### 3.1 前置检查

1. `2026-03` 已有 SUCCESS 的 `cost_allocation_run`，且其 `period_start <= 2026-03-01` 且 `period_end >= 2026-04-01`（**完全覆盖** 整月）。
   - 没有？先在 `/cost/runs` 触发一次 `submitRun(2026-03-01, 2026-04-01, ruleIds=null)`，等 SUCCESS。
2. 整月的 `production_entry`（产量数据）已录入 — 没有也行，`unit_cost` 会是 NULL（前端显示 "—"）。

### 3.2 关账期

```bash
# 1) 创建账期（如尚未存在）
curl -X POST '/api/v1/bills/periods?ym=2026-03' \
     -H 'Authorization: Bearer <FINANCE/ADMIN token>'
# → { "id": 100, "yearMonth": "2026-03", "status": "OPEN", ... }

# 2) 触发账单生成 + 关账期
curl -X PUT '/api/v1/bills/periods/100/close' \
     -H 'Authorization: Bearer <FINANCE/ADMIN token>'
# → { "id": 100, "status": "CLOSED", "closedBy": <userId>, ... }

# 3) 检查账单
curl '/api/v1/bills?periodId=100' -H 'Authorization: Bearer ...'
```

### 3.3 重写（更正后再次关）

```bash
# 直接再 close 一次。bill + bill_line 会被整体 DELETE 后重写。
curl -X PUT '/api/v1/bills/periods/100/close' -H 'Authorization: Bearer ...'
```

`run_id` 会指向当时引用的 SUCCESS run；如果上游 cost run 也有重新跑，会引用最新那个 SUCCESS。

### 3.4 锁账期

财务对完账后：

```bash
curl -X PUT '/api/v1/bills/periods/100/lock' -H 'Authorization: Bearer <FINANCE/ADMIN>'
# → { "status": "LOCKED", "lockedBy": <userId>, ... }
# 同时 audit_logs 写一条 action=LOCK, resource_type=BILL_PERIOD, resource_id=100。
```

之后**任何**对该账期的 `close` / `lock` / `unlock` 之外的写都会被拒（IllegalStateException → HTTP 409）。

### 3.5 解锁（紧急更正）

```bash
curl -X PUT '/api/v1/bills/periods/100/unlock' -H 'Authorization: Bearer <ADMIN only>'
# audit_logs 写 action=UNLOCK 一条；status 回到 CLOSED；可以再 close 重写。
```

---

## 4. generateBills 算法

`BillingService.generateBills(periodId, actorUserId)`（事务内全程）：

1. `BillPeriod.assertWritable()` — LOCKED 直接抛 `IllegalStateException`。
2. `runRepo.findLatestSuccessCovering(period.start, period.end)` — 必须有覆盖账期的 SUCCESS run，否则抛 `"No SUCCESS cost_allocation_run covers ..."`。
3. 取该 run 的全部 `cost_allocation_line`。
4. 若 `period.status == CLOSED`：先 `DELETE FROM bill WHERE period_id=?`（FK CASCADE 自动清 bill_line）— 重写策略。
5. `GROUP BY (target_org_id, energy_type)`，聚合 quantity / amount / 4 段 amount。
6. `productionLookup.sumByOrgIds(orgs, period_start, period_end - 1d)` — 一次性查产量。
7. 每个 (org, energy)：
   - 写一行 `bill`：`unit_cost = amount / production_qty`，若产量 NULL 或 0 则 unit_cost / unit_intensity 都为 NULL。
   - 同 (org, energy) 内按 rule_id 分组写 `bill_line`，`source_label = rule.name`（找不到则 `"rule#{id}"`）。
8. `period.close(actor)` → `status=CLOSED`，记 closed_at/closed_by。

事务失败整体回滚；`bill_period.status` 不会变；旧 bill 不会被删（前提是 step 4 之前失败）。

---

## 5. 看板面板 ⑩ — cost distribution

数据源：**最近一次 SUCCESS** 的 `cost_allocation_run`（与 bill 表解耦 —— 即使账期还没关也能看）。

```sql
-- 等价 SQL（实际由 BillingServiceImpl.costDistribution 实现）
WITH latest AS (
  SELECT id, finished_at FROM cost_allocation_run
  WHERE status = 'SUCCESS'
    AND period_start <= :start AND period_end >= :end   -- 给定 period 时
  ORDER BY finished_at DESC LIMIT 1
)
SELECT target_org_id, SUM(quantity), SUM(amount)
FROM cost_allocation_line WHERE run_id = (SELECT id FROM latest)
GROUP BY target_org_id;
```

`period` 为空 → 直接选库里最新 SUCCESS run（不限账期）。返回 `(orgNodeId, orgName, quantity, amount, percent)`，按 `amount desc` 排序。

---

## 6. 报表 cost-monthly

`ReportPresetService.costMonthly(YearMonth, orgNodeId?)`：

- 数据来源：**bill 表**（不是 telemetry / cost_allocation_line）。账期未 CLOSED 则返回空 matrix（不抛异常 → 前端显示 "暂无账单"）。
- 仅取 `energy_type=ELEC` 的账单 — 4 段电价拆分仅对电有意义。
- 行：组织节点（COST_CENTER 维度）；列：尖 / 峰 / 平 / 谷 / 合计 5 段（TARIFF_BAND 维度）。
- 单位：CNY；保留 4 位小数（前端默认显示 2 位）。

异步导出走 `POST /api/v1/reports/export`，body：

```json
{ "format": "EXCEL", "preset": "COST_MONTHLY", "params": { "yearMonth": "2026-03" } }
```

格式支持 EXCEL / PDF / CSV，三种共享 ReportMatrix 内核。

> **⚠️ BILL preset (每张账单一个 sheet)** 留到 Plan 2.3 与前端对齐 schema 后再加。当前 COST_MONTHLY 已能满足"组织 × 4 段电价"的主用例。

---

## 7. 角色权限

| 角色 | 看账单 | close 账期 | lock 账期 | unlock 账期 | 改分摊规则 | 触发 cost run |
|---|---|---|---|---|---|---|
| ADMIN | ✅ 全部 | ✅ | ✅ | **✅** | ✅ | ✅ |
| FINANCE | ✅ 全部 (默认 viewable=ALL) | ✅ | ✅ | ❌ | ✅ | ✅ |
| VIEWER | 仅 viewable 子树 | ❌ | ❌ | ❌ | ❌ | ❌ |

`FINANCE` 角色由 `V2.1.3__seed_finance_role.sql` 注入。具体用户的角色赋予走 `/api/v1/users/...` 由 ADMIN 操作。

> **TODO（Plan 2.3）：** OrgScopeFilter 子树过滤当前未中央化；BillController 对 VIEWER 用户的子树过滤当前由调用方传 `orgNodeId` 实现，与子项目 1 报表查询同质。

---

## 8. 不变量（防回归）

1. `bill_period.status` 流转**只**允许：`OPEN→CLOSED` / `CLOSED→CLOSED` / `CLOSED→LOCKED` / `LOCKED→CLOSED`。
2. `LOCKED` 期间任何 bill / bill_line 写操作必须被 service 层拒绝（`assertWritable()`）。
3. 看板 ⑩ 永远读最近 SUCCESS 的 `cost_allocation_run`，不读 bill 表（保证未关账期也能看）。
4. `COST_MONTHLY` 报表与潜在的 `BILL` 报表都走 `ReportMatrix` 内核，**禁止单独写新 exporter**。
5. `unit_cost` / `unit_intensity` 在产量 = 0 / NULL 时**保持 NULL**（不要塞 0 或 ∞）。前端显示 `—`。
6. cost_allocation_run 的 SUPERSEDED 语义保留：旧 SUCCESS 不删，账单引用老 `run_id` 仍然有效。

---

## 9. 故障排查

### 9.1 close 报 "No SUCCESS cost_allocation_run covers"

`cost_allocation_run` 表里没有完全覆盖该账期的 SUCCESS run。检查：

```sql
SELECT id, period_start, period_end, status FROM cost_allocation_run
WHERE period_start <= TIMESTAMPTZ '2026-03-01 00:00:00+08'
  AND period_end   >= TIMESTAMPTZ '2026-04-01 00:00:00+08'
  AND status = 'SUCCESS';
```

空集 → 先在 `/cost/runs` 触发一次（period 必须从 03-01 到 04-01 整月）。

### 9.2 close 报 IllegalStateException ... LOCKED

账期已锁。先 unlock（仅 ADMIN）。

### 9.3 看板 ⑩ 永远是 0 或为空

可能：
- 库里压根没 SUCCESS run（先 cost run 一次）
- 给定 period 没有任何 cost run 覆盖（看 9.1）
- cost_allocation_line 真的都是 0（cost rule 出问题，独立排查）

### 9.4 `unit_cost` / `unit_intensity` 全为 NULL

产量 (`production_entry`) 未录入 / 该 org 在这段时间没产量。属于"已知行为"，前端展示 `—`。

### 9.5 重写后旧 bill_line 的导出还有效吗？

旧 bill 已 `DELETE`，`bill_line` 经 FK CASCADE 也清掉。**老的导出 token 文件**仍然在磁盘上（导出已生成 .xlsx 文件不会被删），但库里查 `/bills/{oldId}/lines` 会返回 404。审计需要老快照 → 走 `audit_logs.detail`。

---

## 10. 性能预算

| 场景 | 规模 | 预算 | 测试 |
|---|---|---|---|
| `generateBills` | 200 org × 5 energy = 1000 bill | ≤ 5 s | `BillingPerfIT` |
| 看板 cost-distribution | 1000 line / org-group | ≤ 1 s | 手测 |
| COST_MONTHLY 月报 | 200 行 × 5 列 | ≤ 5 s | 手测（异步） |

超预算时：先看 SQL（多半是 N+1 查 `org_node` name，可加 viewable cache）；其次看是不是每个 (org, energy) 单独 INSERT，可改 batch。
