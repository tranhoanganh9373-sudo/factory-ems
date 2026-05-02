# 电价 + 分摊 + 账单上线 SOP（把电量变成钱）

> **场景**：采集 + 看板 + 告警都已上线后，把电量真正变成"钱"——录入工业分时电价、配（可选的）成本分摊规则、出月度账单。
>
> **前置**：
> - `/dashboard` 能看见 50 块表的实时数据
> - InfluxDB 至少有 7 天连续数据（不然分时电费算不准）
> - org-tree 已建（FACTORY → 4 FLOOR）
> - ADMIN JWT Token

---

## 0. 数据流总览

```
ems-meter ──读数──> ems-timeseries (InfluxDB)
                            │
                            ├─ ems-tariff ──按时段拆分 × 单价─┐
                            │   (峰平谷电价方案)               │
                            │                                  ▼
                            └─ ems-cost ──分摊规则──> 各节点电费  ──┐
                                (DIRECT/PROP/RESIDUAL)              │
                                                                   ▼
                                                           ems-billing
                                                          (月度账期 + 账单)
                                                                   │
                                                            ┌──────┴──────┐
                                                            ▼             ▼
                                                       /dashboard    PDF 导出
                                                       成本分布         报表
```

**关键判断 — 你需不需要 cost 分摊？**

| 场景 | 是否需要 cost 规则 | 走哪条路 |
|---|---|---|
| **50 块表都独立计量**（每回路一块表） | **不需要** | Path A: tariff → 直接月度账单按表聚合 |
| 有公共照明 / 走廊 / 无表区域 | 需要 1-2 条 RESIDUAL | Path B: tariff → cost → 账单 |
| 多租户共享某条总进线 | 需要 PROPORTIONAL（按面积/人数） | Path B |

> **本 SOP 优先走 Path A**（最简上线），Path B 在 §3 给最小示例。

---

## 1. 步骤 ①：录工业分时电价

API：`POST /api/v1/tariff/plans`（ADMIN）

### 1.1 准备数据

从供电局**电费单**或**电力公司官网**抄当地工业大工业分时电价。以江苏 2026 年示意值为例：

| 时段 | 类型 | 时间段 | 电价（元/kWh） |
|---|---|---|---|
| 尖峰 | SHARP | 19:00–21:00 | 1.35 |
| 高峰 | PEAK | 08:00–11:00, 13:00–19:00 | 1.10 |
| 平段 | FLAT | 06:00–08:00, 11:00–13:00, 21:00–22:00 | 0.65 |
| 低谷 | VALLEY | 22:00–06:00 | 0.40 |

> 你的实际电价**一定**问当地电力公司（或翻最近一张电费单），不要用上面示意值。

### 1.2 录入

```bash
TOKEN="<jwt>"
BASE="https://ems.example.com"
ELEC_ID=1   # ems-meter 的 ELEC energyType id（前面 SOP 已查）

curl -s -X POST "$BASE/api/v1/tariff/plans" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{
    "name": "江苏-工业大工业-2026",
    "energyTypeId": 1,
    "effectiveFrom": "2026-01-01",
    "effectiveTo": null,
    "periods": [
      {"periodType":"VALLEY","timeStart":"22:00:00","timeEnd":"06:00:00","pricePerUnit":"0.40"},
      {"periodType":"FLAT",  "timeStart":"06:00:00","timeEnd":"08:00:00","pricePerUnit":"0.65"},
      {"periodType":"PEAK",  "timeStart":"08:00:00","timeEnd":"11:00:00","pricePerUnit":"1.10"},
      {"periodType":"FLAT",  "timeStart":"11:00:00","timeEnd":"13:00:00","pricePerUnit":"0.65"},
      {"periodType":"PEAK",  "timeStart":"13:00:00","timeEnd":"19:00:00","pricePerUnit":"1.10"},
      {"periodType":"SHARP", "timeStart":"19:00:00","timeEnd":"21:00:00","pricePerUnit":"1.35"},
      {"periodType":"FLAT",  "timeStart":"21:00:00","timeEnd":"22:00:00","pricePerUnit":"0.65"}
    ]
  }'
# → 返回 {"data": {"id": 1, ...}}
```

⚠️ 注意：
- 时段必须**全天 24h 覆盖、无空隙、无重叠**，否则 resolve 时段会拿不到价格
- VALLEY 跨零点的话拆成 22:00-24:00 + 00:00-06:00 两条；上例为简化用了一条 22-06
- `effectiveTo: null` 表示"永久生效，直到下一个方案接替"

### 1.3 校验

```bash
# 查某个时刻应该走哪个时段、单价多少
curl -s -H "Authorization: Bearer $TOKEN" \
  "$BASE/api/v1/tariff/resolve?energyTypeId=1&at=2026-05-01T20:30:00+08:00" | jq
# 期望: periodType=SHARP, pricePerUnit=1.35
```

---

## 2. 步骤 ②：（Path A）创建账期，让月结跑起来

如果 50 块表都独立计量，**跳过 cost 规则**，直接进账期管理。

### 2.1 建 5 月账期

```bash
curl -s -X POST "$BASE/api/v1/bills/periods" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{
    "yearMonth": "2026-05",
    "name": "2026 年 5 月账期"
  }'
# → 返回 {"data": {"id": 1, "status": "OPEN", ...}}
```

> 每月 1 日 0:00 系统会自动建立当月账期（OPEN 状态），手工建只是首次启用 / 补建。

### 2.2 查账期当前状态

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "$BASE/api/v1/bills/periods/2026-05" | jq
```

### 2.3 月初跑账单

具体怎么从 meter 读数 + tariff 自动填 `bill_lines`：见 `docs/ops/billing-runbook.md`（账期 → cost run → bill 的具体调度细节，这里不重复）。要点：

- 5 月结束后（6 月 1-5 日）跑一次月结
- 系统按 5 月每日 24 小时 × 各时段单价 × 各表读数 = 每表分时电费
- 写入 `bill_lines`（每个 meter 一行 / 每个楼层节点一行 — 看产品配置）

### 2.4 关账 + 锁定

财务核对完数字后：

```bash
PERIOD_ID=1

# 关账（CLOSED）— 数据只读
curl -s -X PUT "$BASE/api/v1/bills/periods/$PERIOD_ID/close" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"note": "5 月数据完整，财务核对 OK"}'

# 锁定（LOCKED）— 终审，调账只能新增反向行
curl -s -X PUT "$BASE/api/v1/bills/periods/$PERIOD_ID/lock" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"note": "总监签字"}'
```

⚠️ `OPEN → CLOSED → LOCKED` **不可回退**。LOCKED 后想改只能加调账行（不直接覆盖）。`/api/v1/bills/periods/{id}/unlock` 在极端情况可解锁，但全程进审计。

---

## 3. 步骤 ③：（仅 Path B 才做）配 cost 分摊规则

如果有"公共照明 / 损耗 / 无表区域"想分摊，配一条 **RESIDUAL** 规则。

### 3.1 例：1F 总进线 - 各回路 = 1F 公共

假设 1F 装了主进线表（meter id=5，1F-MAIN）+ 12 个回路子表，期望"总-子=公共"挂到「1F-公共」节点（org node id=10）。

```bash
curl -s -X POST "$BASE/api/v1/cost/rules" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{
    "code": "MOCK-RULE-1F-RESIDUAL",
    "name": "1F 公共消耗（残差）",
    "description": "1F 总进线 - 12 个子表读数 = 公共照明/损耗，挂到 1F-公共节点",
    "energyType": "ELEC",
    "algorithm": "RESIDUAL",
    "sourceMeterId": 5,
    "targetOrgIds": [10],
    "weights": null,
    "priority": 100,
    "enabled": true,
    "effectiveFrom": "2026-05-01",
    "effectiveTo": null
  }'
```

### 3.2 试算（dry-run）—— 看结果合不合理，不落库

```bash
RULE_ID=1

curl -s -X POST "$BASE/api/v1/cost/rules/$RULE_ID/dry-run" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{
    "periodStart": "2026-05-01T00:00:00+08:00",
    "periodEnd":   "2026-06-01T00:00:00+08:00"
  }' | jq
# 看 .data.lines[]：每个节点的 kWh + 金额
```

判断合理性：1F 公共占总耗的 **3-8%** 算正常；超过 15% 说明**回路漏装表**或**电表读数有问题**。

### 3.3 正式跑（run）

```bash
curl -s -X POST "$BASE/api/v1/cost/runs" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{
    "periodStart": "2026-05-01T00:00:00+08:00",
    "periodEnd":   "2026-06-01T00:00:00+08:00",
    "ruleIds": null
  }'
# → 返回 {"data": {"id": 42, "status": "PENDING"}}
RUN_ID=42

# 轮询状态
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/cost/runs/$RUN_ID" | jq '.data.status'
# 期望状态变化: PENDING → RUNNING → SUCCESS

# 拉明细
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/cost/runs/$RUN_ID/lines" | jq
```

cost run SUCCESS 后，billing 模块会把这些 lines 落到当月账期的 `bill_lines`，再走 §2.4 的关账锁定。

---

## 4. 步骤 ④：仪表盘 + 验收

### 4.1 看成本分布

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "$BASE/api/v1/dashboard/cost-distribution?periodId=1" | jq
```

`/dashboard` 的「成本分布饼」组件就是从这个端点出。账期 LOCKED 后这饼图就不会再变了——5 月历史回看不会跳。

### 4.2 验收清单

- [ ] 工业分时电价已录、`/api/v1/tariff/resolve` 不同时间点取价正确
- [ ] **Path A**：5 月账期已建（OPEN）
- [ ] **Path B（如适用）**：cost rule 已建、dry-run 结果合理（公共消耗占比 3-8%）、正式 run 状态 SUCCESS
- [ ] `/api/v1/bills/periods/2026-05` 返回账期 + 多份账单（按能源类型）+ 多行 lines
- [ ] 各楼层 5 月电费总和 ≈ 总用电量 × 加权平均电价（误差 <1%）
- [ ] 账期已 CLOSED → LOCKED；尝试改 lines 应报错
- [ ] `/dashboard` 选 5 月账期，「成本分布」饼图各楼层占比正确

---

## 5. 路径 B 的 3 条典型规则（速查）

| 场景 | algorithm | sourceMeterId | targetOrgIds | weights | 备注 |
|---|---|---|---|---|---|
| 1F 总进线 → 12 个子表（直分） | DIRECT | 5（1F-MAIN）| [11..22]（各回路节点）| null | 一般不需要 — 子表已独立计量 |
| 办公区按面积分到 4 楼层 | PROPORTIONAL | 30（办公区表）| [2,3,4,5]（4 个 FLOOR id）| `{"2":300,"3":500,"4":200,"5":300}` | 单位 m²；权重和不必为 100，会自动归一化 |
| 总进线 - 已分摊 = 公共 | RESIDUAL | 1（总进线）| [99]（"公共"节点）| null | 必须在其他规则之后跑（priority 排序） |

---

## 6. 故障速查

| 现象 | 排查路径 |
|---|---|
| `POST /tariff/plans` 报"时段重叠" | 检查 periods 数组的 timeStart/timeEnd 有无 overlap |
| `GET /tariff/resolve` 返回空 | 该时刻不在 effectiveFrom..effectiveTo 之间，或 24h 没全覆盖 |
| dry-run 总数 != 总表读数 | RESIDUAL 规则没生效 / 子表读数缺失 / 子表 channel 没采到数据 |
| run 卡在 RUNNING 不动 | 看 ems-app 日志；多半是某个子查询太慢，看 `cost-engine-runbook.md` 里的索引建议 |
| 账期已 CLOSED 但发现某节点金额错 | 不能直接改。在下个月加调账行（adjustment）或临时 unlock（进审计） |
| 账单 lines 是空的 | 该账期没绑定任何 cost run；或 Path A 模式下定时任务没跑（看 `billing-runbook.md` 的调度配置） |
| 仪表盘成本分布与账单数字对不上 | 检查 `/cost-distribution` 是按账期出还是实时算（首版 LOCKED 后应一致） |

---

## 7. 常见调优

- **跨午夜的 VALLEY 时段**（22:00–06:00）：可以拆成 22:00-24:00 + 00:00-06:00 两个 period，更清晰；或者像示例那样写一条让后端处理（取决于 ems-tariff 实现是否支持，建议先拆成两条更稳）
- **季节性电价**：工业电价夏季 / 非夏季可能不同。当前一份 plan 是固定的，季节切换需要建 2 份 plan + 用 effectiveFrom/effectiveTo 切换
- **基本电费 / 容量电费**：v1.x 不内置，需要在分摊侧手工加一行"基本电费"作为 RESIDUAL 或固定金额（产品没原生支持，要 v2 才有）

---

## 8. 与下一阶段的衔接

电价 + 账单跑通后，路线图剩下的两块：

- **报表自动化**（`ems-report`）：日 / 月 PDF 自动出，1 号自动发邮件给厂长。账单已 LOCKED → 报表数据稳定
- **生产能效**（`ems-production`）：录入产量 → 算"单位产品能耗" → 改造前后效果对比

---

**相关文档**

- 电价产品介绍：[../product/tariff-feature-overview.md](../product/tariff-feature-overview.md)
- 成本分摊产品介绍：[../product/cost-feature-overview.md](../product/cost-feature-overview.md)
- 账单产品介绍：[../product/billing-feature-overview.md](../product/billing-feature-overview.md)
- 成本引擎 ops runbook：[../ops/cost-engine-runbook.md](../ops/cost-engine-runbook.md)
- 账单 ops runbook：[../ops/billing-runbook.md](../ops/billing-runbook.md)
- 选型指南：[meter-selection-guide.md](./meter-selection-guide.md)
- 现场施工 SOP：[field-installation-sop.md](./field-installation-sop.md)
- 看板上线 SOP：[dashboard-commissioning-sop.md](./dashboard-commissioning-sop.md)
- 5 分钟演示：[dashboard-demo-quickstart.md](./dashboard-demo-quickstart.md)
- 告警上线 SOP：[alarm-commissioning-sop.md](./alarm-commissioning-sop.md)
- 月报自动化 SOP：[report-automation-sop.md](./report-automation-sop.md)
- 生产能效 SOP：[production-energy-sop.md](./production-energy-sop.md)
