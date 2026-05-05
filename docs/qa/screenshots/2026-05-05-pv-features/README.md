# 光伏 / 绿电统计功能 — QA 验证 (2026-05-05)

测试 Issue #20 (PV 管理 UI) merge 后所有 PV / 绿电相关统计端到端工作。

## 测试数据

通过 InfluxDB 直接写入 30 天小时级 mock 数据 + PG ts_rollup_* 三层填充：

| 项 | 数量 |
|---|---|
| Influx points | 5,760 (8 表 × 720 小时) |
| ts_rollup_hourly | 5,760 |
| ts_rollup_daily | 240 |
| ts_rollup_monthly | 16 |
| 时间范围 | 2026-04-05T16Z → 2026-05-05T16Z |

测试场景遵循 `WithPvScenario.java` 模式：
- 5×消费表：恒定 100 kWh/h
- 光伏主表：白天 11-15 UTC 期间 600 kWh/h，其他时段 0
- 并网出线表（卖电）：白天 100 kWh/h，其他 0
- 并网进线表（买电）：白天 0，其他 500 kWh/h（光伏自消纳剩余由电网补）

**能量守恒检查**：消费 360,000 = 光伏 90,000 + 入网 285,000 - 上网售 15,000 ✓

## API 验证结果

| API | 端点 | 验证 |
|---|---|---|
| 能源类型构成 | `GET /dashboard/energy-source-mix?range=THIS_MONTH` | GRID 76.9% / SOLAR 23.1% ✓ |
| PV 曲线 | `GET /dashboard/pv-curve?range=THIS_MONTH` | 122 hourly buckets，白天峰值 600，夜间 0 ✓ |
| 碳排报表 | `GET /report/carbon?orgNodeId=4&from=...&to=...` | 自消纳 75,000 kWh，**减排 39,975 kg CO₂** ✓ |
| 上网售电收入 | `GET /cost/savings?orgNodeId=5&from=2026-04-01&to=2026-04-30` | feedInRevenue=**4,800 元** ✓（PR #27 修复） |

减排计算 = 75,000 × (0.581 - 0.048) = **39,975 kg CO₂ ≈ 40 吨**（30 天）

上网售电收入 4,800 元 = 15,000 kWh 上网量按 SHARP/PEAK/FLAT/VALLEY 时段加权计算（与 0.40 元/kWh 平均价 ≈ 6,000 元的粗估在合理区间内）。

## 历史 Caveat（已修复）

最初测试时 `GET /cost/savings` 返回 0，原因是 `FeedInRevenueServiceImpl` 要求 `SOLAR && EXPORT` 同时满足，但 `WithPvScenario` 标准接线 seed 中无此组合：
- `MOCK-PV-SOLAR-MAIN`: SOLAR + IMPORT + GENERATE
- `MOCK-PV-GRID-EXPORT`: GRID + EXPORT + GRID_TIE

PR #27（commit 09d7ec0）修复：改用 `GRID_TIE && EXPORT` 过滤上网点，`source` 参数仅用于 tariff 价格档查询，不再过滤 meter。

**Org 节点说明：** 8 个 PV 表挂在 `org_node_id=5`（测试车间 / MOCK-WS-A），不是 `=4`（测试工厂）。`/cost/savings` 不会自动向下汇总子节点，必须传 5 才有数据。

## 截图清单

| # | 文件 | 内容 |
|---|---|---|
| 01 | `01-dashboard-this-month-full.png` | Dashboard 全屏（THIS_MONTH range，光伏数据已显示）|
| 02 | `02-pv-vs-load-panel.png` | ⑬ PV 发电 vs 厂区负载 双 Y 轴折线图特写 |
| 03 | `03-energy-source-mix-panel.png` | ⑫ 能源来源构成饼图特写（电网 76.9% / 光伏 23.1%）|
| 04 | `04-topn-with-pv-first.png` | TopN 排名特写（光伏主表 15,000 kWh 居首）|
| 05 | `05-energy-params-tariff.png` | `/settings/energy-params` 上网电价 tab 全屏 |
| 06 | `06-energy-params-carbon.png` | `/settings/energy-params` 碳排因子 tab 全屏 |
| 07 | `07-energy-params-create-modal.png` | 新增电价 Modal（append-only 提示） |
| 08 | `08-reports-with-carbon.png` | `/report` 即席查询页（CarbonPanel 需选对 org+date 才会渲染，未触发）|
| 10 | `10-bills-with-savings.png` | `/bills` 账单页（SavingsPanel 需选定账期才渲染；API 值 feedInRevenue=4,800 元 已验证）|

## 验证用账户

`admin` / `admin123!`

## 复现步骤

```bash
# 1. 重新生成 seed 数据
python3 /tmp/seed_pv_30d.py        # 写 Influx
python3 /tmp/seed_pg_rollups.py    # 生成 PG 填充 SQL
docker exec factory-ems-postgres-1 psql -U ems -d factory_ems -f /tmp/seed_rollups.sql

# 2. 浏览器验证
# 登录 http://localhost:8888 → /dashboard?range=THIS_MONTH

# 3. API 验证
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8888/api/v1/report/carbon?orgNodeId=4&from=2026-04-05T00:00:00Z&to=2026-05-05T23:59:59Z"
```
