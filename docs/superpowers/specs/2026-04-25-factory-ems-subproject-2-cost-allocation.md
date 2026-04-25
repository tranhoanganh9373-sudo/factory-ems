# 通用工厂能源管理系统 · 子项目 2 · 分项计量 & 成本分摊设计文档

- **Project**: factory-ems
- **Subproject**: 2 — 分项计量 + 按组织架构的成本分摊
- **Spec Date**: 2026-04-25
- **Baseline**: 子项目 1 v1.0.0
- **Author**: impl

---

## 1. 背景与定位

### 1.1 与子项目 1 的关系

子项目 1（v1.0.0）已经交付：

- 多能源数据模型（电/水/气/汽/油 + 测点 + 父子拓扑）
- 实时看板（KPI / 实时曲线 / 9 宫格）
- 基础历史报表（日/月/年/班次预设 + Excel/PDF/CSV 异步导出）
- 电价方案（4 时段 + 跨零点）+ 班次 + 日产量

子项目 2 在其上加一层 **"成本"** 视角：

> "电费这个月 87 万，三车间花了多少？是被照明白嫖了，还是真的产线吃电多？"

它不再回答"用了多少能"（子项目 1 已经回答），而是回答 **"谁花了多少钱"**。

### 1.2 子项目 2 的价值

| 角色 | 拿到的能力 |
|---|---|
| 财务 | 月底自动跑出每个组织节点 / 成本中心的能源账单（含分时电价拆分） |
| 车间主任 | 自己车间被分摊了多少公摊（照明/空压站/锅炉房）、为什么 |
| 设备/工艺 | 单位产量成本（不只是单位产量能耗，而是 ¥/件），找节能 ROI |
| 厂长 | 一张能源成本分布图：直接消耗 vs 公摊；可变 vs 固定 |

### 1.3 不在子项目 2 范围内

明确推到子项目 3 或更后：

- 能效诊断（基线建模 / 异常识别 / 节能建议）→ 子项目 3
- 多工厂集中财务结算 / 跨法人主体对账
- 与外部 ERP / 财务系统（用友/SAP）的双向同步 → 后续做 connector
- 阶梯电价 / 容（需）量电费 / 力调电费 / 基本电费 → MVP 只做"按时段电价 × 度数"，复杂电费结构进 v2

---

## 2. 核心约束与前置条件

### 2.1 数据前提

子项目 2 启动需要子项目 1 已经稳定运行 **≥ 1 个月**，库里要有：

- ≥ 1 个完整月的小时级聚合（`ts_rollup_hourly`）
- 完整的组织树（`org_node`）+ 测点表（`meter`）
- 至少 1 套电价方案（`tariff_plan` + 4 时段）
- 班次（`shift`）和日产量（`production_entry`）

否则分摊的输入数据为空，分摊结果没意义。

### 2.2 关键不变量

- **测点级用量是"事实"**：分摊算法不能修改 `telemetry_*` 原始数据；分摊结果落在独立的 `cost_allocation_*` 表里。
- **分摊可重跑、可回滚**：每次运行产出一个 `cost_allocation_run`，旧的不删；账单引用 run_id，重跑生成新 run，不是覆盖。
- **金额币种统一为人民币（CNY），保留 4 位小数**：避免 ¥0.01 的尾差污染到账单。
- **跨零点统一规则沿用子项目 1**：`if (!start.isBefore(end)) endDt = endDt.plusDays(1)`。

### 2.3 不引入的复杂度

- 不做实时分摊。所有分摊离线跑（按账期一次性算），看板只读最近一次 run。
- 不做按"工单"分摊，只做按"组织节点"分摊（工单粒度等子项目 3 再说）。
- 不做用户自定义脚本规则。规则限定在内置算法 4 种内（直接 / 比例 / 差值 / 复合）。

---

## 3. 架构总览

### 3.1 新模块（在 v1.0.0 模块化单体基础上加 2 个）

```
ems-meter (已有: 测点 + 拓扑)
  ↓
ems-tariff (已有: 电价方案)            ems-orgtree (已有)         ems-production (已有)
  ↓                                   ↓                          ↓
       └────────┬───────────┬─────────┴──────────┬───────────────┘
                ↓           ↓                    ↓
          ems-cost (新)：分项计量规则 + 分摊引擎 + 分摊运行
                ↓
          ems-billing (新)：账期 + 账单生成 + 账单导出
                ↓
          ems-report (扩展)：成本类预设报表（成本分布、单位产量成本）
```

### 3.2 不动的部分

- 部署拓扑、容器清单、JVM/PG/Influx 版本：**不变**
- 鉴权 / 权限 / 审计 / org-scope 过滤：**不变**
- 前端框架（React 18 + AntD + ECharts + Vite）：**不变**
- 报表内核 `ReportMatrix`：**不变**，只新增 RowDimension/ColumnDimension 枚举值（`COST_CENTER`）

### 3.3 与子项目 1 的兼容承诺

- 所有 v1.0.0 API 路径、表结构、Flyway 编号 **不动不删**。
- 子项目 2 的迁移文件编号从 `V200__` 开始，避免与 v1 的 `V1xx__` 冲突。
- 前端新增路由前缀 `/cost/*` 和 `/bills/*`；既有路由不动。

---

## 4. 数据模型

### 4.1 PostgreSQL 新表

#### 4.1.1 `cost_allocation_rule` — 分摊规则

```
id            BIGINT PK
code          VARCHAR(64) UNIQUE  -- 规则代码，账单引用
name          VARCHAR(128)
description   TEXT
energy_type   VARCHAR(32)         -- ELEC / WATER / GAS / STEAM / OIL
algorithm     VARCHAR(32)         -- DIRECT / PROPORTIONAL / RESIDUAL / COMPOSITE
source_meter_id  BIGINT REFERENCES meter(id)   -- 主表（"被分的"那个）
target_org_ids   BIGINT[]                       -- 分给哪些组织节点
weights        JSONB              -- 算法相关参数
priority       INT                -- 规则执行顺序（数字小先跑）
enabled        BOOLEAN DEFAULT true
effective_from DATE               -- 生效起
effective_to   DATE               -- 生效止（null = 永久）
created_at / updated_at TIMESTAMPTZ
```

`weights` JSONB 结构按 `algorithm` 不同：

- `DIRECT`：`{}` 不需要权重，直接归 `target_org_ids[0]`
- `PROPORTIONAL`：`{"basis": "AREA|HEADCOUNT|PRODUCTION|FIXED", "values": {"orgId1": 0.4, "orgId2": 0.6}}`
- `RESIDUAL`：`{"deductMeterIds": [123, 124, 125]}` — 主表减去这几个子表，剩下的按 `weights.values` 分
- `COMPOSITE`：链式 `[{"algorithm": "RESIDUAL", ...}, {"algorithm": "PROPORTIONAL", ...}]`

#### 4.1.2 `cost_allocation_run` — 分摊批次

```
id              BIGINT PK
period_start    TIMESTAMPTZ
period_end      TIMESTAMPTZ
status          VARCHAR(16)   -- PENDING / RUNNING / SUCCESS / FAILED / SUPERSEDED
algorithm_version VARCHAR(16) -- 引擎语义版本，方便排查老 run
total_amount    NUMERIC(18, 4)  -- 本次分摊总金额
created_by      BIGINT REFERENCES users(id)
created_at      TIMESTAMPTZ
finished_at     TIMESTAMPTZ
error_message   TEXT
```

`SUPERSEDED`：被同账期的新 run 替换（旧 run 不删，账单引用老 run_id 仍有效）。

#### 4.1.3 `cost_allocation_line` — 分摊明细

```
id              BIGINT PK
run_id          BIGINT REFERENCES cost_allocation_run(id) ON DELETE CASCADE
rule_id         BIGINT REFERENCES cost_allocation_rule(id)
target_org_id   BIGINT REFERENCES org_node(id)
energy_type     VARCHAR(32)
quantity        NUMERIC(18, 4)   -- 分摊到的能源量（kWh / m³ / Nm³ / t）
amount          NUMERIC(18, 4)   -- 分摊到的金额（CNY，已含分时电价拆分）
sharp_quantity  NUMERIC(18, 4)
peak_quantity   NUMERIC(18, 4)
flat_quantity   NUMERIC(18, 4)
valley_quantity NUMERIC(18, 4)
sharp_amount / peak_amount / flat_amount / valley_amount  NUMERIC(18, 4)
created_at      TIMESTAMPTZ
INDEX (run_id, target_org_id)
```

电的话拆 4 段；非电品类 `*_quantity` / `*_amount` 都为 0，只用 `quantity` / `amount`。

#### 4.1.4 `bill_period` — 账期

```
id            BIGINT PK
year_month    VARCHAR(7)  UNIQUE   -- "2026-04"
status        VARCHAR(16)          -- OPEN / CLOSED / LOCKED
locked_at     TIMESTAMPTZ          -- LOCKED 后 amount 不能再变
locked_by     BIGINT REFERENCES users(id)
period_start  TIMESTAMPTZ
period_end    TIMESTAMPTZ
created_at    TIMESTAMPTZ
```

`LOCKED` 是审计需要：财务对完账后锁账期，再跑分摊不影响已锁账单。

#### 4.1.5 `bill` — 账单（每个账期 × 每个组织节点 × 每个能源品类一行）

```
id             BIGINT PK
period_id      BIGINT REFERENCES bill_period(id)
run_id         BIGINT REFERENCES cost_allocation_run(id)
org_node_id    BIGINT REFERENCES org_node(id)
energy_type    VARCHAR(32)
quantity       NUMERIC(18, 4)
amount         NUMERIC(18, 4)
sharp_amount / peak_amount / flat_amount / valley_amount NUMERIC(18, 4)
production_qty NUMERIC(18, 4)        -- 该期间该组织的产量（含义见 ems-production）
unit_cost      NUMERIC(18, 6)        -- amount / production_qty，可空
unit_intensity NUMERIC(18, 6)        -- quantity / production_qty
created_at     TIMESTAMPTZ
UNIQUE (period_id, org_node_id, energy_type)
```

#### 4.1.6 `bill_line` — 账单分摊来源明细（解释"这 ¥1234 是怎么来的"）

```
id            BIGINT PK
bill_id       BIGINT REFERENCES bill(id) ON DELETE CASCADE
rule_id       BIGINT REFERENCES cost_allocation_rule(id)
source_label  VARCHAR(128)    -- "1#变压器 总表 残差分摊"
quantity      NUMERIC(18, 4)
amount        NUMERIC(18, 4)
```

### 4.2 新增视图（read-only，给报表用）

```
v_cost_breakdown     -- 按 (period, org, energy) 透视的 quantity/amount/unit_cost
v_cost_distribution  -- 按 period 透视各 org 占比
```

### 4.3 不改的表

`meter`, `meter_topology`, `org_node`, `tariff_*`, `production_*`, `shift`, `telemetry_*` —— 全部保持。子项目 2 是"读 v1 + 写自己的表"。

### 4.4 InfluxDB

不动。子项目 2 没有新的时序数据，分摊结果是"派生事实"放在 PG。

### 4.5 Flyway 编号

`ems-cost` 模块使用 `V200__` ~ `V249__`；`ems-billing` 用 `V250__` ~ `V299__`。

---

## 5. 模块清单与职责

### 5.1 新增模块

```
ems-cost/
├── entity/         CostAllocationRule, CostAllocationRun, CostAllocationLine
├── repository/     JPA repos
├── service/        CostAllocationService（runRun / dryRun / supersede）
│                   AllocationAlgorithm（DirectAlgorithm / ProportionalAlgorithm /
│                                         ResidualAlgorithm / CompositeAlgorithm）
│                   TariffCostCalculator（quantity × tariff time slot → amount）
├── controller/     CostAllocationController, CostRuleController
└── dto/            分摊规则 / 分摊运行 / 分摊明细的 record DTO

ems-billing/
├── entity/         BillPeriod, Bill, BillLine
├── repository/     ...
├── service/        BillingService（generateBills / lockPeriod / exportBill）
├── controller/     BillController, BillPeriodController
└── dto/            ...
```

### 5.2 现有模块改动

| 模块 | 改动 |
|---|---|
| `ems-tariff` | 暴露一个 `TariffPriceLookupService`（按测点 + 时间窗口算 4 段电价 × 度数 → 金额）— 当前 `resolvePrice` 是单点调用，扩展为批量区间调用以提升分摊性能 |
| `ems-production` | `ProductionService` 暴露按 (orgNodeId, periodStart, periodEnd) 求合计产量的方法，给账单 `unit_cost` 用 |
| `ems-report` | `ReportMatrix.RowDimension` 枚举增加 `COST_CENTER`；新增预设：成本分布报表（按账期 × 组织节点 × 能源品类） |
| `ems-dashboard` | 新增看板面板 ⑩ 成本分布（饼图 + 表格），数据从最近 SUCCESS run 读 |
| `ems-meter` | **不动**。父子拓扑已经支持分项计量结构 |

### 5.3 跨模块协作点

- **分摊运行** = `ems-cost` 调度，输入 = (`ems-meter` 测点用量) + (`ems-tariff` 电价) + (`ems-orgtree` 组织树) → 输出 = `cost_allocation_line`。
- **账单生成** = `ems-billing` 输入 = (`cost_allocation_run` SUCCESS) + (`ems-production` 产量) → 输出 = `bill` + `bill_line`。
- 单向依赖图：`billing → cost → tariff/production/meter/orgtree`，无循环。

---

## 6. 数据流

### 6.1 分摊运行（每月跑一次，可重跑）

```
1. POST /api/v1/cost/runs        body: {periodStart, periodEnd, ruleIds?}
2. 校验账期 BillPeriod 是否 LOCKED；LOCKED 拒绝重跑
3. 创建 CostAllocationRun.PENDING
4. 异步线程池（沿用 Plan 1.3 的 ThreadPoolTaskExecutor 或单独配置）执行：
   for rule in rules sorted by priority:
     algorithm = AlgorithmFactory.of(rule.algorithm)
     lines = algorithm.allocate(rule, period)
     allocationLineRepo.saveAll(lines)
5. 把同账期上一次 SUCCESS run 标 SUPERSEDED
6. 当前 run 标 SUCCESS，记录 total_amount
7. 失败：FAILED + error_message
```

### 6.2 账单生成（账期关闭时一次性跑）

```
1. PUT /api/v1/bills/periods/{ym}/close   触发账单生成
2. 找最新 SUCCESS run（必须 period 完全覆盖账期）
3. 按 (org, energy) 聚合 cost_allocation_line → bill
4. 关联 production 算 unit_cost / unit_intensity
5. bill_line 直接来自 cost_allocation_line（按 rule_id 分组）
6. BillPeriod.status = CLOSED
```

### 6.3 账期锁定（财务对账后）

```
PUT /api/v1/bills/periods/{ym}/lock
要求 status = CLOSED；锁后任何 cost run 重跑不会回写到该账期的账单。
```

### 6.4 看板分摊面板读路径

看板面板 ⑩ 始终读最近一次 SUCCESS run，避免显示中间态。

---

## 7. REST API

### 7.1 路径前缀约定

- `/api/v1/cost/...` — 分摊
- `/api/v1/bills/...` — 账单

### 7.2 核心端点

```
# 分摊规则
GET    /api/v1/cost/rules
POST   /api/v1/cost/rules
PUT    /api/v1/cost/rules/{id}
DELETE /api/v1/cost/rules/{id}     -- 软删除（enabled=false）
POST   /api/v1/cost/rules/{id}/dry-run?period=YYYY-MM   -- 不落库的预览

# 分摊运行
GET    /api/v1/cost/runs?period=YYYY-MM
POST   /api/v1/cost/runs           -- 触发新 run
GET    /api/v1/cost/runs/{id}
GET    /api/v1/cost/runs/{id}/lines?orgNodeId=&energyType=

# 账期 / 账单
GET    /api/v1/bills/periods
PUT    /api/v1/bills/periods/{ym}/close
PUT    /api/v1/bills/periods/{ym}/lock
PUT    /api/v1/bills/periods/{ym}/unlock      -- ADMIN only + 审计

GET    /api/v1/bills?period=YYYY-MM&orgNodeId=&energyType=
GET    /api/v1/bills/{id}
GET    /api/v1/bills/{id}/lines
POST   /api/v1/bills/export        -- 复用 Plan 1.3 异步导出器，preset=BILL
```

### 7.3 权限

- 分摊规则 CRUD：ADMIN + 财务角色（新增 `FINANCE`）
- 分摊运行触发：FINANCE / ADMIN
- 账单查看：org-scope 过滤；只能看到自己组织树下的账单
- 账期锁/解锁：ADMIN only，必审计

---

## 8. 前端结构

### 8.1 新增路由

```
/cost/rules                  分摊规则列表 + 编辑器
/cost/runs                   分摊批次历史 + 触发新 run
/cost/runs/:id               单次 run 详情（按组织 / 品类透视的明细表）
/bills                       账单列表（按账期 + 组织过滤）
/bills/periods               账期管理（关闭 / 锁定 / 解锁）
/bills/:id                   单张账单详情 + 分摊来源（bill_line）
```

### 8.2 看板新增

- 9 宫格扩成 10 宫格或独立 tab `/dashboard/cost`：
  - 面板 ⑩ 当月成本分布（饼图：各组织 % + 表格 quantity/amount/unit_cost）
- 既有 9 宫格不动。

### 8.3 报表新增

- 新预设：`/report/cost-monthly`（行 = 组织节点，列 = 4 段电价 + 合计）
- 复用 Plan 1.3 异步导出（POST `/api/v1/reports/export` 加 `preset=COST_MONTHLY`）

---

## 9. 关键算法

### 9.1 PROPORTIONAL（比例分摊）

```
total = source_meter 在 period 内的用量
for orgId, weight in rule.weights.values:
  qty[orgId] = total * weight   # weights 已 normalize 到 1.0
  amt[orgId] = TariffCostCalculator.calc(source_meter, period, qty[orgId])
```

`basis` 决定 weight 来源：

- `FIXED`：直接用 `weights.values` 配置
- `AREA`：从 `org_node.area_m2` 归一化（org_node 加扩展字段）
- `HEADCOUNT`：从 `org_node.headcount`
- `PRODUCTION`：从 `production_entry` 同期产量

### 9.2 RESIDUAL（差值分摊）

```
total = source_meter 在 period 内的用量
deducted = sum(deductMeter.qty for deductMeter in rule.weights.deductMeterIds)
residual = total - deducted    # 公摊部分（照明 / 损耗 / 未计量）
if residual < 0: 报警，仍然分（避免负数到账单 → clamp 到 0 或按比例缩 deductMeter）
然后按 PROPORTIONAL 把 residual 分给 target_orgs
```

负残差是常见的：测点漂移 / 传感器偏差。MVP 选择 clamp 到 0 + 写 warn 日志，不阻塞。

### 9.3 DIRECT（直接归集）

整个 source_meter 的量归 `target_org_ids[0]`。等价于"这块表就是 X 车间的"。

### 9.4 COMPOSITE（链式）

```
for sub_rule in rule.weights:
  AlgorithmFactory.of(sub_rule.algorithm).allocate(...)
```

例：先做 RESIDUAL（拆出公摊），再把公摊用 PROPORTIONAL 分给 3 个车间。

### 9.5 分时电价拆分

每条 allocation_line 的 amount 都按子项目 1 的电价时段拆出 4 段：

```
qty_period = 测点在 period 内按小时聚合的总量 → 每小时归一段（SHARP/PEAK/FLAT/VALLEY）
按段乘价 → 4 个 amount，求和 = total_amount
```

非电品类不拆段，一段计价（按月份均价 or 配置的固定价）。

---

## 10. 安全与权限

### 10.1 新增角色

`FINANCE` —— 看所有账单 + 触发分摊运行 + 账期 close。

### 10.2 既有 org-scope 过滤

账单查询沿用子项目 1 的 `OrgScopeFilter`：用户只能看到自己 viewable orgNode 子树下的账单。

财务角色 `FINANCE` 默认 viewable = 全部组织。

### 10.3 锁账期审计

`bill_period.status = LOCKED` 与 `unlock` 必走 audit_log，记录 `actor_user_id` + `before/after status`。

---

## 11. 测试策略

### 11.1 新增单元测试覆盖目标

- `ems-cost`：80%（4 个算法各自的边界用例 + 时段拆分）
- `ems-billing`：75%（账期状态机 + bill 聚合 + lock 防写）

### 11.2 集成测试

- 真 Postgres + 真 InfluxDB（沿用 v1 的 Testcontainers）
- 端到端用例：种 1 个月时序 → 跑分摊 → 关账期 → 锁账期 → 重跑被拒
- 算法回归：固定输入数据 + 期望输出（fixture-based）

### 11.3 E2E（Playwright）

- `/cost/rules` 创建 PROPORTIONAL 规则
- `/cost/runs` 触发 + 等 SUCCESS
- `/bills` 看到账单 + 导出 Excel + 校验 PK 头
- 锁账期后再触发 run 的 UI 提示

---

## 12. 部署与运维

### 12.1 数据库迁移

`V200__cost_allocation_rule.sql` 起。Flyway 自动跑。

### 12.2 性能预算（MVP 目标）

- 1 个月数据 + 50 条规则 + 200 个组织节点：分摊 run 全程 ≤ 30s
- 账单聚合 + 写库：≤ 5s
- 单组织节点账单页加载：≤ 1s

### 12.3 容量规划

- `cost_allocation_line`：≈ 规则数 × 组织数 × 月数 = 50 × 200 × 12 ≈ 12 万行/年。无压力。
- `bill` + `bill_line`：≈ 组织数 × 能源种类 × 月数 = 200 × 5 × 12 = 1.2 万行/年。

### 12.4 备份范围

新增 `cost_*` / `bill*` 表纳入既有 PG 备份，不需要单独流程。

---

## 13. 工作量心理预期

| 模块 | 估算 | 说明 |
|---|---|---|
| ems-cost 后端 | 8–10 天 | 4 个算法 + dry-run + 时段拆分 + 测试 |
| ems-billing 后端 | 4–5 天 | 账期状态机 + 账单聚合 + 锁定审计 |
| ems-report 扩展 | 1–2 天 | 新增 1 个 RowDimension + 1 个预设 |
| ems-dashboard 扩展 | 1 天 | 1 个新面板 |
| 前端 cost / bills | 6–8 天 | 6 个页面 + 规则编辑器 + dry-run 预览 |
| E2E + perf | 2 天 | |
| 文档 / 验证 | 1 天 | |
| **合计** | **23–29 天** | |

---

## 14. 决策记录

| 决策 | 选择 | 备选 | 理由 |
|---|---|---|---|
| 算法落地 | 4 种内置（DIRECT/PROPORTIONAL/RESIDUAL/COMPOSITE）+ JSONB 参数 | DSL / 用户脚本 | MVP 不引入沙箱，4 种已覆盖 90% 场景 |
| 分摊触发 | 离线手动 + 异步运行 | 实时增量 | 实时增量在测点漂移时会震荡，离线可重跑可比对 |
| 重跑策略 | 老 run SUPERSEDED 不删 | 直接覆盖 | 审计可追溯 |
| 账期锁 | 显式状态机 OPEN→CLOSED→LOCKED | 隐式按日期 | 财务流程要求人工确认 |
| 币种 | CNY only + 4 位小数 | 多币种 | 国内单工厂场景，多币种留到后续 |
| 时段拆分 | 沿用 v1 的 4 段（SHARP/PEAK/FLAT/VALLEY） | 自定义段 | v1 已落地，不增加复杂度 |
| 模块边界 | cost 与 billing 分模块 | 合一 | billing 关心账期 / 锁定 / 导出，cost 关心算法 — 关注点不同 |

---

## 15. 后续动作

- [ ] 评审本 spec（用户 + 财务角色 stakeholder）
- [ ] 拆 Plan 2.1（cost 后端） / Plan 2.2（billing 后端 + 报表 + 看板）/ Plan 2.3（前端 6 页 + E2E + perf）
- [ ] Plan 2.1 启动前先在子项目 1 v1.0.0 上跑 1 个月，攒足真实数据

---

## 附录 A · 术语

| 术语 | 含义 |
|---|---|
| 分项计量 | sub-metering — 把一个总表的量拆分到多个子节点 |
| 残差 / 公摊 | 总表 - 子表 = 没有归属测点的量（照明 / 损耗 / 未计量） |
| 成本中心 | cost center — 财务上承接成本的最小单位（这里映射到 org_node） |
| 账期 | billing period — 一个月（YYYY-MM） |
| run | 一次分摊批次（CostAllocationRun） |
| 锁账期 | LOCKED — 财务对账后冻结，账单不可再变 |
