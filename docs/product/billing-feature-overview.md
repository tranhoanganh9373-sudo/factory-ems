# 账单（ems-billing）· 功能概览

> 适用版本：v1.3.0+ ｜ 最近更新：2026-05-01
> 受众：销售 / 客户 / 财务 / 实施工程师

---

## §1 一句话价值

把"上月各节点应付电费"固化成账单。每个月一个"账期（period）"，账期从打开（OPEN）到关账（CLOSED）到锁定（LOCKED）三态流转。锁定后任何金额变动都进审计日志、不能直接覆盖。财务对账有月度凭证，老板审月度数据有"快照"。

---

## §2 解决什么问题

- **能耗一直在变，月度数据要"定格"**：5 月底统计 5 月数据不能受 6 月新数据影响。账期关账后，5 月账单的金额就不再变。
- **财务月结流程要规范**：账期"打开 → 关账 → 锁定"是不可逆流程，每一步可追溯（谁关账、何时锁定）。
- **账单要按节点拆分**：一份账单可以有多行（一车间 ¥45000、二车间 ¥38000、办公区 ¥8000），每行独立。
- **领导看仪表盘要拿月度成本数据**：从账单 lines 表汇总比从 InfluxDB 实时算快得多，账单本身就是预聚合好的成本快照。

---

## §3 核心功能

### §3.1 账期生命周期（period status）

- **OPEN**：账期刚建立，可往里加 / 改账单。
- **CLOSED**：财务确认本月数据完整，关账。账单只读，不能直接改。
- **LOCKED**：财务终审锁定。后续任何金额修改都得通过"调账单"（adjustment）记录到审计日志。

状态机：`OPEN → CLOSED → LOCKED`（不可回退；如需修改已锁定数据，新增反向调账，不直接覆盖）。

### §3.2 账单（bill）+ 明细（bill lines）

- **账单（bill）**：一个账期内可以有多份账单（如分电、水、蒸汽）。每份账单关联一个账期 + 能源类型 + 总金额。
- **明细（bill lines）**：一份账单按节点拆分多行，每行 = 一个节点的费用 + 用量 + 单价 / 时段拆分。

### §3.3 账期管理接口

- **`POST /bills/periods`**：新建账期（一个月一份）。
- **`PUT /bills/periods/{id}/close`**：关账。
- **`PUT /bills/periods/{id}/lock`**：锁定（CLOSED → LOCKED）。
- **`PUT /bills/periods/{id}/unlock`**：解锁（仅特殊情况；进审计）。

### §3.4 仪表盘聚合接口

- **`GET /dashboard/cost-distribution`**：返回某账期内各节点电费分布。仪表盘饼图用此端点（实现在 ems-billing 但前缀 `/dashboard`，因数据来自账单）。

### §3.5 与成本分摊联动

账单的 lines 通常由 ems-cost 的运行结果填充（每个 cost_run_lines 一行 → bill_lines 一行）。也支持人工录入或 Excel 导入（v2.x）。

### §3.6 多能源类型

账期内可以并存：电账单（按电度数 × 电价）、水账单（按 m³ × 水价）、蒸汽账单。每份账单独立。

---

## §4 适用场景

### 场景 A：标准月结流程

1. **每月 1 日 0:00 自动建立 5 月账期**（定时任务，OPEN 状态）。
2. **5 月 5 日，运营录完上月产量**（ems-production）。
3. **5 月 6 日，财务跑成本分摊**（ems-cost）→ run SUCCESS。
4. **将 cost run 的 lines 写入 5 月账单的 bill_lines**（自动 / 半自动）。
5. **5 月 8 日财务核对账单数据 OK，关账**（CLOSED）。
6. **5 月 15 日总监审核签字，锁定**（LOCKED）。
7. **6 月开始仪表盘 / 报表查询历史 5 月数据，永远从锁定的账单出**。

### 场景 B：领导审查月度成本

总经理看 `/dashboard` → 切到"近 12 个月" → 仪表盘各组件中的成本相关组件（如成本分布饼）从账期 LOCKED 数据出，不会因为 InfluxDB 偶尔丢点而抖动。

### 场景 C：财务发现锁定后数据错了

5 月账单已 LOCKED，但 6 月发现 5 月某车间电表数据修复后差 2000 度。流程：

1. 不能直接改 5 月账单。
2. 在 6 月账单加一条"调账行"（adjustment line）：备注"5 月电表读数修复差额，调整 +¥1200"。
3. 6 月账单总金额包含此调整。
4. 5 月账单本身不变，依然 LOCKED。
5. 审计日志记录"6 月调整对应 5 月某节点 ¥1200"。

### 场景 D：客户参观 — 看月度账单

客户问"上个月某车间花了多少钱"。运维登录 → `/billing/periods/2026-04` → 看锁定的 4 月账单 lines → 按节点导出 PDF 给客户。

---

## §5 不在范围（首版）

- **不做开票流程（发票生成）**：账单是内部成本拆分凭证，不对外出具发票。需要发票请走外部 ERP。
- **不做付款管理**：账单不跟付款状态挂钩（已付 / 未付 / 部分付），也不集成银行收付款。
- **不做账期跨月份合并**：每月一个账期，不支持"季度账期"或"跨月报表"。跨月看走 ems-report。
- **不做客户合同价绑定**：账单按 ems-tariff 的电价方案出，不跟客户合同价（如内部转移定价）做关联。
- **不做收入科目联动**：账单只算成本，不映射到会计科目（如 5101 营业收入）。需要走外部财务系统对接。
- **不做账单退回 / 撤销**：LOCKED 不可逆。错误用调账行修正，不撤销整个账单。

---

## §6 与其他模块的关系

```
ems-cost           分摊结果填充账单 lines
ems-tariff         单价（resolve at 时间点）
ems-orgtree        账单 lines 按节点
ems-meter          原始用量数据
ems-audit          关账 / 锁定 / 解锁记日志
       │
       ▼
   ems-billing
       │
       ├──────> ems-dashboard    /dashboard/cost-distribution 端点
       └──────> ems-report       /report/preset/cost-monthly 数据源
```

账单是月度成本数据的归宿：分摊算完落到这里，报表和仪表盘也从这里查。

---

## §7 接口入口

- **前端路径**：
  - `/billing/periods` — 账期列表
  - `/billing/periods/{ym}` — 某月账期详情
- **API 前缀**：`/api/v1/bills`、`/api/v1/bills/periods`
- **关键端点**：

| 端点 | 用途 |
|---|---|
| `GET /api/v1/bills/periods` | 账期列表 |
| `GET /api/v1/bills/periods/{ym}` | 某账期详情（YYYY-MM）|
| `POST /api/v1/bills/periods` | 新建账期 |
| `PUT /api/v1/bills/periods/{id}/close` | 关账 |
| `PUT /api/v1/bills/periods/{id}/lock` | 锁定 |
| `PUT /api/v1/bills/periods/{id}/unlock` | 解锁 |
| `GET /api/v1/bills` | 账单列表（按账期 / 能源类型筛选）|
| `GET /api/v1/bills/{id}` | 账单详情 |
| `GET /api/v1/bills/{id}/lines` | 账单明细行 |
| `GET /api/v1/dashboard/cost-distribution` | 仪表盘成本分布饼数据 |

---

## §8 关键字段

### `bill_periods` 表

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigint | 主键 |
| `period_ym` | varchar(7) | YYYY-MM（如 `2026-05`）|
| `status` | varchar | `OPEN` / `CLOSED` / `LOCKED` |
| `closed_at` / `locked_at` | timestamp | 关账 / 锁定时间 |
| `closed_by` / `locked_by` | bigint | 操作人 user_id |

### `bills` 表

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigint | 主键 |
| `period_id` | bigint | 关联账期 |
| `energy_type_id` | bigint | 能源类型 |
| `total_amount` | numeric(14,2) | 账单总金额（元）|
| `total_quantity` | numeric | 账单总用量（原始单位）|

### `bill_lines` 表

| 字段 | 类型 | 说明 |
|---|---|---|
| `bill_id` | bigint | 关联账单 |
| `node_id` | bigint | 节点 |
| `quantity` | numeric | 该节点用量 |
| `amount` | numeric(12,2) | 该节点金额 |
| `period_breakdown` | jsonb | 按时段（峰 / 平 / 谷）拆分 |
| `cost_run_id` | bigint | 来源 run（可空，人工录入时为空）|

---

**相关文档**

- 成本分摊：[cost-feature-overview.md](./cost-feature-overview.md)
- 电价：[tariff-feature-overview.md](./tariff-feature-overview.md)
- 报表：[report-feature-overview.md](./report-feature-overview.md)
- 仪表盘：[dashboard-feature-overview.md](./dashboard-feature-overview.md)
- 平台总览：[product-overview.md](./product-overview.md)
