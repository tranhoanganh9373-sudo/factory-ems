# 成本分摊（ems-cost）· 功能概览

> 适用版本：v1.3.0+ ｜ 最近更新：2026-05-01
> 受众：销售 / 客户 / 财务 / 实施工程师

---

## §1 一句话价值

把"总表读到的电费"按业务规则分摊到各车间 / 各产线 / 各成本中心。支持直分、按权重、残差三种算法，可以组合使用。月结跑一次，每个节点拿到自己应承担的电费和来源明细，财务对账有据。

---

## §2 解决什么问题

- **总表只有一个数，分到各车间靠手算**：总表读 30000 度，要分给一车间 12000 度、二车间 10000 度、办公区 8000 度，靠 Excel 手工拉表，每月做一次，累且容易错。
- **分摊规则混合多种**：有些子节点有自己的子表（直分）；有些没子表只能按面积 / 人数 / 产值（按权重）；剩下没分到的算总表的"残差"挂到一个公共节点（残差）。一套规则里要混合三种算法。
- **分摊结果要可追溯**：财务问"这 5000 度怎么算出来的"，得说清用的是哪条规则、哪些子表 / 权重、当时单价多少。口头交代不清楚，得存在数据库里。
- **试算 + 正式跑要分两步**：跑正式之前财务想先试算看结果合不合理，避免错了要清数据再来一遍。

---

## §3 核心功能

### §3.1 三种分摊算法

- **§3.1.1 直分（DIRECT）**：父节点的能耗 = 各子表读数之和。最简单的"有子表用子表"。
- **§3.1.2 按权重（PROPORTIONAL）**：父节点能耗按权重比例分给各子节点。权重支持：
  - **静态权重**：固定值（如面积 1000 / 800 / 200 m²）。
  - **动态权重**：基于产量 / 人数 / 营业额，每月从外部数据源（如 ERP）查。
  - **能源权重**：按其他能源类型的子表读数比例（如电费按各车间的水表读数比例分）。
- **§3.1.3 残差（RESIDUAL）**：父节点的总能耗 - 已分摊给确定子节点的能耗 = 残差，挂到指定的"公共消耗"节点（如照明、损耗）。

### §3.2 复合规则

一个父节点可以同时用多种算法。例：总进线表 → 一二车间走直分（有子表）+ 办公区走按面积权重 + 剩下挂到"线损"节点。

### §3.3 试算（dry-run）

- **`POST /cost/rules/{ruleId}/dry-run`**：单条规则试算，看结果不落库。
- **`POST /cost/dry-run-all`**：所有规则一次性试算。

### §3.4 正式分摊运行（run）

- **`POST /cost/runs`**：发起一次正式分摊。状态机 `PENDING → RUNNING → SUCCESS / FAILED`。
- **`GET /cost/runs/{runId}`**：查运行状态。
- **`GET /cost/runs/{runId}/lines`**：拉运行结果明细（每个节点 / 每条规则一行）。

### §3.5 单价计算

服务层 `TariffCostCalculator` 调 ems-tariff 的 `/resolve` 接口取当时电价 → 按时段（峰平谷）拆分能耗 × 单价 → 算出各时段电费。

### §3.6 异步运行 + 进度查询

大数据量时单次分摊可能跑几分钟。运行进入 RUNNING 状态后前端可轮询 `/cost/runs/{runId}` 看进度。

---

## §4 适用场景

### 场景 A：标准月结分摊

每月 5 号财务跑上月分摊：

1. 在 `/cost/dry-run-all` 试算，看结果是否合理（总数 = 总表读数 ± 0.1%、各车间数字合直觉）。
2. 试算 OK 后调 `POST /cost/runs`（参数：上月时间窗），后端跑约 30s-2min。
3. 状态变 SUCCESS 后调 `/cost/runs/{runId}/lines` 拉明细，导出给财务做账。
4. 任何环节有问题，靠 dry-run 复现重跑、找错；正式 run 不会重复，每个时间窗最多一次成功记录。

### 场景 B：办公区按面积分摊

办公区没有独立电表（也不愿意装），但财务要分到各楼层。配置一条规则：

- 父节点：办公区总表
- 子节点：1F (300 m²) / 2F (500 m²) / 3F (200 m²)
- 算法：PROPORTIONAL，权重 = 面积，权重源 = 静态值

每次跑分摊，办公区电费按 30%/50%/20% 分到三层。如果某层装修，改静态权重一次性更新。

### 场景 C：复合规则 — 一二车间直分 + 办公残差

总进线表读 5000 度。一车间分表读 2000 度、二车间分表读 1500 度、办公区无表。配置：

- 规则 1：总进线 → 一车间（DIRECT，子表读数）→ 2000 度
- 规则 2：总进线 → 二车间（DIRECT，子表读数）→ 1500 度
- 规则 3：总进线 → 办公区（RESIDUAL）→ 5000 - 2000 - 1500 = 1500 度

所有车间都算到了，且总和 = 总表读数（无遗漏、无重复）。

### 场景 D：动态权重 — 按产量分摊

公用蒸汽（无子表）按各产线产量比例分。每次月结：

1. 读 ems-production 上月各产线产量（A 线 100 吨 / B 线 80 吨 / C 线 70 吨）。
2. 按产量比 40%/32%/28% 分摊蒸汽费。

产量按月波动，权重也跟着动，分摊结果就与生产强度挂钩。

---

## §5 不在范围（首版）

- **不做实时分摊**：分摊是月度 / 批次后处理，不实时算。实时看请用仪表盘。
- **不做规则审批流**：规则建好直接生效；规则改动靠审计日志追溯，不走多人审批。
- **不做规则版本管理**：当前规则是"现在生效的"。改了就生效，不留历史版本（历史 run 的 lines 表保留当时用的规则参数）。
- **不做规则冲突自动检测**：如果两条规则同时分给同一子节点，会重复计算——靠运营人为校验 / dry-run 时核对。
- **不做权重自动校正**：如果三个权重加起来不是 100%（如 30/30/30 = 90%），系统会按比例归一化但不报警。需要严格控制时人为校验。
- **不做反向分摊（按用途追溯）**："这 5000 度都被谁用了"靠 lines 表逐节点拉，没有"按用途自动分组"功能。

---

## §6 与其他模块的关系

```
ems-orgtree         规则的父子节点指向组织节点
ems-meter           DIRECT 算法读子表数据
ems-timeseries      所有能耗数据来源
ems-tariff          单价（按时段 + 节点 → 单价）
ems-production      动态权重源之一（产量）
       │
       ▼
   ems-cost
       │
       ├──────> ems-billing      账单复用分摊结果
       └──────> ems-report       /report/preset/cost-monthly 用
```

成本分摊是账单和成本报表的上游：账单按节点 → 找该节点应承担的费用 → 来自 cost 的运行结果。

---

## §7 接口入口

- **前端路径**：
  - `/cost/rules` — 规则定义
  - `/cost/runs` — 历次运行 + 试算
- **API 前缀**：`/api/v1/cost`、`/api/v1/cost/rules`
- **关键端点**：

| 端点 | 用途 |
|---|---|
| `GET /api/v1/cost/rules` | 规则列表 |
| `GET /api/v1/cost/rules/{id}` | 规则详情 |
| `POST /api/v1/cost/rules` | 新建规则 |
| `PUT /api/v1/cost/rules/{id}` | 修改规则 |
| `DELETE /api/v1/cost/rules/{id}` | 删除规则 |
| `POST /api/v1/cost/rules/{ruleId}/dry-run` | 单规则试算 |
| `POST /api/v1/cost/dry-run-all` | 全规则试算 |
| `POST /api/v1/cost/runs` | 发起正式分摊 |
| `GET /api/v1/cost/runs/{runId}` | 运行状态 |
| `GET /api/v1/cost/runs/{runId}/lines` | 运行结果明细 |

---

## §8 关键字段

### `cost_rules` 表

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigint | 主键 |
| `name` | varchar | 规则名 |
| `parent_node_id` | bigint | 父节点（被分摊源）|
| `algorithm` | varchar | `DIRECT` / `PROPORTIONAL` / `RESIDUAL` |
| `weight_basis` | varchar | 权重源（仅 PROPORTIONAL）：`AREA` / `HEADCOUNT` / `PRODUCTION` / `STATIC` 等 |
| `target_node_ids` | jsonb | 目标节点列表（含权重值）|
| `enabled` | bool | 启用开关 |
| `version` | bigint | 乐观锁 |

### `cost_runs` 表

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigint | 运行 ID |
| `status` | varchar | `PENDING` / `RUNNING` / `SUCCESS` / `FAILED` |
| `from_time` / `to_time` | timestamp | 时间窗 |
| `started_at` / `finished_at` | timestamp | |
| `error_msg` | varchar | 失败原因 |

### `cost_run_lines` 表

| 字段 | 类型 | 说明 |
|---|---|---|
| `run_id` | bigint | 关联 run |
| `rule_id` | bigint | 用的哪条规则 |
| `from_node_id` / `to_node_id` | bigint | 分摊源 / 目标 |
| `quantity` | numeric | 分摊到的能耗（原始单位）|
| `amount` | numeric(12,2) | 分摊到的金额（元）|
| `period_breakdown` | jsonb | 按时段拆分（峰 / 平 / 谷 各占多少）|

---

**相关文档**

- 账单：[billing-feature-overview.md](./billing-feature-overview.md)
- 电价：[tariff-feature-overview.md](./tariff-feature-overview.md)
- 仪表与计量：[meter-feature-overview.md](./meter-feature-overview.md)
- 组织树：[orgtree-feature-overview.md](./orgtree-feature-overview.md)
- 报表：[report-feature-overview.md](./report-feature-overview.md)
- 平台总览：[product-overview.md](./product-overview.md)
