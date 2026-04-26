# 子项目 2 总操作手册 · v2.0.0

**Plan 集：** 2.1 (cost 后端) + 2.2 (billing + 报表 + 看板⑩) + 2.3 (前端 + E2E)
**适用版本：** v2.0.0 起
**前置：** 子项目 1 v1.0.0（用户/组织树/测点/电价/班次/产量/dashboard）

---

## 0. 一图看懂

```
[测点用量 (rollup)]      [电价方案]     [组织树]      [产量]
        \                  |             |              |
         └──────► [ems-cost: 4 算法 + 4 段拆分] ◄───────┘
                          ↓ cost_allocation_run.SUCCESS
                  [ems-billing: 账期状态机 + bill 聚合]
                          ↓
              [REST: /cost/* /bills/* /dashboard/cost-distribution]
                          ↓
            [前端: /cost/rules /cost/runs /bills /bills/periods]
```

数据流：`telemetry → rollup_hourly → 电价 × 4 段拆分 → cost_allocation_line → bill (按 org × energy 聚合) → 账单 / 报表 / 看板⑩`。

---

## 1. 角色 & 权限

| 角色 | cost rules CRUD | 触发 cost run | close 账期 | lock 账期 | unlock | 看账单 |
|---|---|---|---|---|---|---|
| ADMIN | ✅ | ✅ | ✅ | ✅ | **✅ (only)** | 全部 |
| FINANCE | ✅ | ✅ | ✅ | ✅ | ❌ | 全部 (默认 viewable=ALL) |
| VIEWER | ❌ | ❌ | ❌ | ❌ | ❌ | viewable 子树 |

`FINANCE` 由迁移 V2.1.3 注入；用户经 ADMIN 在 `/admin/users` 赋角色。

---

## 2. 月底关账完整流程

> 假设要关 `2026-03` 的账。

### 2.1 准备

1. 确认整月 `production_entry` 已录（`/production/entry`），缺失会导致 `unit_cost = NULL`（前端显示 "—"）。
2. 确认电价方案 effective 覆盖整月。
3. 确认有 SUCCESS 的 `cost_allocation_run` 完全覆盖整月（`period_start <= 2026-03-01 AND period_end >= 2026-04-01`）。
   - 没有 → 进 `/cost/runs` 点"新建批次"，period 选 2026-03-01 ~ 2026-04-01，等 SUCCESS。

### 2.2 关账期

1. `/bills/periods` → 点"创建账期" → 选 2026-03 → 提交（status=OPEN）。
2. 找到该行 → 点"关账期 + 生成账单" → 自动跳到 `/bills?periodId=...` 列表。
3. 检查每个组织 × 能源的账单。

### 2.3 锁账期

1. `/bills/periods` → CLOSED 状态 → 点"锁定" → 弹二次确认框。
2. 输入 `我确认锁定 2026-03` → 点"锁定"。状态变 LOCKED；audit_logs 自动写一条。

### 2.4 解锁（紧急更正）

仅 ADMIN：`/bills/periods` → LOCKED 行 → 点"解锁 (ADMIN)" → 输入 `我确认解锁 2026-03` → 状态回 CLOSED。可以再"重新生成"。

### 2.5 重写

CLOSED → 直接再点"重新生成"：bill 整批 DELETE（FK CASCADE 清 bill_line）→ 重新 INSERT。新 id 序列。

---

## 3. 看板面板⑩ 成本分布

`/dashboard` 最下方一栏。月份 picker 留空 = 取库里最新 SUCCESS run（不限账期）；选月份 = 该账期的 covering run。

数据：饼图（按 org 占比）+ 表格（用量 / 金额 / 占比）。run 信息显示在右上 Tag。

后端查询：`SELECT FROM cost_allocation_line WHERE run_id=(SELECT id FROM cost_allocation_run WHERE status='SUCCESS' [AND covering] ORDER BY finished_at DESC LIMIT 1) GROUP BY target_org_id`。**与 bill 表解耦** — 账期未关也能看实时成本结构。

---

## 4. 报表 cost-monthly

`POST /api/v1/reports/export` body：

```json
{ "preset": "COST_MONTHLY", "format": "EXCEL", "yearMonth": "2026-03", "orgNodeId": null }
```

或前端 `/bills` 页选账期后点"导出 Excel (COST_MONTHLY)"。

仅取 `energy_type=ELEC` 的账单（4 段电价拆分对电才有意义）。行 = 组织（COST_CENTER）× 列 = 尖/峰/平/谷/合计 5 段（TARIFF_BAND）。

**BILL 预设（每张账单一个 sheet）** 留到 frontend 与后端拉齐 schema 后加。

---

## 5. 状态机不变量

```
bill_period.status:  OPEN → CLOSED → LOCKED
                              ↑          │
                              └─ unlock ─┘
合法转换：
  OPEN → CLOSED (close)         首次关账期
  CLOSED → CLOSED (close)       重写
  CLOSED → LOCKED (lock)        审计冻结
  LOCKED → CLOSED (unlock)      仅 ADMIN

任何其他 → IllegalStateException → HTTP 409
```

LOCKED 期间任何 bill / bill_line 写都被 service 层 `BillPeriod.assertWritable()` 拒。

---

## 6. 性能预算

| 场景 | 规模 | 预算 | 实测 |
|---|---|---|---|
| `cost_allocation` (Plan 2.1) | 50 rules × 200 orgs × 30 days | ≤ 30 s | **5.4 s** |
| `generateBills` (Plan 2.2) | 200 orgs × 5 energies = 1000 bills | ≤ 5 s | **2.5 s** |
| `dashboard cost-distribution` | 1000 lines | ≤ 1 s | 手测 |
| `COST_MONTHLY` 月报 | 200 行 × 5 列 | ≤ 5 s | 手测（异步） |

---

## 7. 故障排查 (FAQ)

### 关账期报 "No SUCCESS cost_allocation_run covers"

库里没有完全覆盖账期的 SUCCESS run。查：

```sql
SELECT id, period_start, period_end, status, finished_at FROM cost_allocation_run
WHERE period_start <= '2026-03-01 00:00:00+08'
  AND period_end   >= '2026-04-01 00:00:00+08'
  AND status = 'SUCCESS'
ORDER BY finished_at DESC;
```

空 → 在 `/cost/runs` 触发 + 等 SUCCESS。

### close / lock 报 IllegalStateException ... LOCKED

账期已锁。先 unlock（ADMIN）。

### 看板⑩ 永远空

库里没 SUCCESS run。先在 `/cost/runs` 触发任意一次成功的分摊。

### `unit_cost` / `unit_intensity` 全 NULL

该 org 在账期内没有 `production_entry` 行。属于"已知行为"。

### 重写后旧 bill detail 链接 404

预期：旧 bill DELETE，CASCADE 清掉 bill_line。审计快照看 audit_logs.detail。

### Frontend `/cost/*` 或 `/bills/*` 进不去

角色不够（VIEWER）。在 `/admin/users` 让 ADMIN 给账户加 FINANCE 或 ADMIN 角色。

### Mockito UnfinishedStubbingException 在测试中

@Entity 类（如 CostAllocationRule）在嵌套 `when().thenReturn()` 中被 mock 时触发。改用匿名子类覆写 getter 替代 `mock(...)`（参考 BillingServiceImplTest.rule()）。

---

## 8. 模块边界

```
ems-billing/  → ems-cost / ems-orgtree / ems-production / ems-tariff / ems-audit / ems-core
ems-report/   → 既有 + ems-cost / ems-billing  （为 costMonthly preset）
ems-app/      → 全部聚合 + 4 Flyway 迁移
```

无循环。`ems-cost` 在 Plan 2.2 / 2.3 期间**只加查询，不改逻辑**（保留 SUPERSEDED 语义）。

---

## 9. 部署 / 备份

- 4 张新表（`bill_period`, `bill`, `bill_line`, 加 `cost_allocation_*` for cost）纳入既有 PG 备份，无需单独流程。
- 异步导出文件落地 `${ems.report.export.base-dir}` (默认 `./ems_uploads/exports`)，定期清理 30 分钟前 token 文件即可。
- `cost_allocation_*` 容量：50 rules × 200 orgs × 12 月 ≈ 12 万行/年；`bill*`：200 orgs × 5 energy × 12 月 = 1.2 万行/年。无索引调优需求。

---

## 10. 与子项目 3 的对接

子项目 3 = 能效诊断（基线建模 / 异常识别 / 节能建议）。它读：
- `bill` (单位成本 KPI 的 baseline)
- `cost_allocation_line` (成本异常的钻取)
- `production_entry` (强度比较)

不写 cost / bill 表。子项目 2 → 3 数据流单向。
