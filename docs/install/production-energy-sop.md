# 生产能效上线 SOP（产量录入 → 单位产品能耗 → 改造前后对比）

> **场景**：报表 SOP 跑通后，最后一块拼图——把"产量"喂进系统，让 EMS 能算出"每件产品多少度电"，作为节能改造立项 / 验收的硬指标。
>
> **前置**：
> - `report-automation-sop.md` 已跑通，PDF 月报能自动出
> - `dashboard-commissioning-sop.md` 跑通，能在看板看到电量趋势
> - 至少 1 个完整 LOCKED 账期（有可信电量数据）

---

## 0. 这一步对你来说必要吗？（先做这个判断）

| 你的工厂 | 要不要做生产能效 |
|---|---|
| 制造业（注塑/冲压/焊接/装配/食品/印染/纺织 …）| 必做。节能改造 ROI 全靠"单位产品能耗"算 |
| 办公楼 / 商超 / 数据中心 / 医院 | 跳过。"产出"不是计件的，能效用 kWh/㎡ 或 kWh/床位 表达，而 ems-production 模块按"件数"建模 |
| 混合体（前面 1F-3F 是车间、4F 是办公）| 只对车间节点录入，办公节点直接跳过 |

**第二个判断**：你愿意每天 / 每班花 5 分钟录产量吗？

- 班长 / 车间统计员愿意 → 走本 SOP
- 没人录 → 这块就是空的，看板"单位产量能耗"曲线一直为 0；功能等以后再启用，装机交付不要硬上

---

## 1. 步骤 ①：先把"产量怎么算"想清楚

这是产品给的能力（`ProductionEntryServiceImpl.java` 验证）：

| 维度 | 含义 | 例子 |
|---|---|---|
| `orgNodeId` | 哪个节点产出的 | 1F 冲压车间 = 节点 5 |
| `shiftId` | 哪个班产出的 | 早班 = 1，中班 = 2，夜班 = 3 |
| `entryDate` | 哪一天 | 2026-05-15 |
| `productCode` | 产品编码 | "SKU-001" / "外壳-A" |
| `quantity` | 数量 | 1250 |
| `unit` | 单位 | "件" / "kg" / "米" / "套" |

**唯一约束**：`(orgNodeId, shiftId, entryDate, productCode)` 4 元组唯一。同班同节点同日同 SKU 重复 POST 会 409。

**实际部署最常见的 2 种粒度**：

| 粒度 | 录入频率 | 适合 |
|---|---|---|
| 班次级（早/中/夜 各 1 条）| 每班结束后录 | 多班次连续生产；推荐 |
| 日级（一天 1 条 → 用同一个"全天"班次）| 每天下班录 | 单班、不区分班次的小厂 |

> 不要做"每小时录"或"每件录"。EMS 把产量数据当成统计口径，不是 MES。

---

## 2. 步骤 ②：建班次（`POST /api/v1/shifts`）

### 2.1 推荐：3 班标准建模

```bash
TOKEN="<admin-jwt>"
BASE="https://ems.example.com"

# 早班 08:00-16:00
curl -s -X POST "$BASE/api/v1/shifts" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"code":"MORNING","name":"早班","timeStart":"08:00:00","timeEnd":"16:00:00","sortOrder":1}'

# 中班 16:00-00:00
curl -s -X POST "$BASE/api/v1/shifts" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"code":"AFTERNOON","name":"中班","timeStart":"16:00:00","timeEnd":"00:00:00","sortOrder":2}'

# 夜班 00:00-08:00（注意：跨零点的班一律拆成两段时间不重叠的 LocalTime）
curl -s -X POST "$BASE/api/v1/shifts" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"code":"NIGHT","name":"夜班","timeStart":"00:00:00","timeEnd":"08:00:00","sortOrder":3}'
```

> **跨零点夜班的现实做法**：把"昨晚 22:00 - 今早 06:00"这种班拆成 22:00-23:59:59 + 00:00-06:00 两条会很丑。
> 简化：约定夜班 `entryDate = 班次结束当天`（即"白天才是班次的归属日"）。班长 5 月 16 日 06:00 下班录夜班产量时填 `entryDate=2026-05-16, shiftId=3`。

### 2.2 确认列表

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/shifts" | jq
# 期望: 3 条，按 sortOrder 排
```

### 2.3 单班次工厂

只建 1 个 `code=DAY name=全天 timeStart=00:00 timeEnd=23:59:59 sortOrder=1`。

---

## 3. 步骤 ③：录产量（两种方式）

### 3.1 方式 A：单条录入（API / 前端表单）

```bash
# 5 月 15 日早班，1F 冲压车间产 SKU-001 共 1250 件
curl -s -X POST "$BASE/api/v1/production/entries" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{
    "orgNodeId": 5,
    "shiftId": 1,
    "entryDate": "2026-05-15",
    "productCode": "SKU-001",
    "quantity": 1250,
    "unit": "件",
    "remark": "正常班产"
  }'
# 201 Created
```

### 3.2 方式 B：CSV 批量导入（推荐——每天/每周一次性传）

CSV 列固定：`org_node_id,shift_id,entry_date,product_code,quantity,unit,remark`（**列名可以有 header，第一行会被跳过**）。

```csv
org_node_id,shift_id,entry_date,product_code,quantity,unit,remark
5,1,2026-05-15,SKU-001,1250,件,正常班
5,2,2026-05-15,SKU-001,1180,件,
5,3,2026-05-15,SKU-001,900,件,夜班减产
6,1,2026-05-15,SKU-002,800,kg,
6,2,2026-05-15,SKU-002,820,kg,
```

> 重要约束（`ProductionEntryServiceImpl.java:165-168`）：
> - 字段不支持带逗号的引号包裹值。`remark` 里有逗号会直接报错；填的时候用中文逗号"，"或全角句号代替。

```bash
curl -s -X POST "$BASE/api/v1/production/entries/import" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@production-2026-05-15.csv"
# 返回 {total: 5, succeeded: 5, errors: []}
# 重复行（4 元组冲突）会进 errors 但不会让整体失败
```

### 3.3 方式 C：前端页面录（如果走 GUI 不走 CSV）

`/production/entries` 页面支持：
- 单条 + 列表查
- CSV 上传（同 §3.2 走的就是这个端点）

---

## 4. 步骤 ④：验证"单位产品能耗"已经活了

录完 1 周产量后，看 3 个地方：

### 4.1 看板"单位产量能耗"卡片（最直观）

```bash
# 当月 1F 冲压的能耗强度
curl -s -H "Authorization: Bearer $TOKEN" \
  "$BASE/api/v1/dashboard/energy-intensity?range=THIS_MONTH&orgNodeId=5" | jq
```

返回结构：

```json
{
  "data": {
    "electricityUnit": "kWh",
    "productionUnit": "件",
    "points": [
      {"date": "2026-05-15", "electricity": 320.5, "production": 3330, "intensity": 0.0963},
      {"date": "2026-05-16", "electricity": 305.1, "production": 3210, "intensity": 0.0951}
    ]
  }
}
```

`intensity = electricity / production`（kWh / 件）。这个数就是节能改造的硬指标。

> 如果 `production` 为 0 那天 `intensity` 是 null（不是 NaN/Infinity），前端会画断点。

### 4.2 报表里的"单位产量能耗"列

跑月报：

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "$BASE/api/v1/report/preset/cost-monthly?yearMonth=2026-05&orgNodeId=5" | jq '.data'
```

应该多出 `intensityKwhPerUnit` 字段（如果产品里有这一列）。没有产量数据的节点这一列是 null，所以没产量时月度报表会留空。

### 4.3 看板首页"产量趋势"（可选）

`/dashboard` 切到 1F 冲压节点，应该看到：
- 顶部多 1 张"日产量"条形图
- "单位产量能耗"曲线非空

---

## 5. 步骤 ⑤：改造前后对比（这才是录产量的真正价值）

录满 1 个月的"基线"数据 → 做节能改造（换变频、调电机、关停空载）→ 再录 1 个月 → 对比：

```bash
# 改造前：4 月
curl -s -H "Authorization: Bearer $TOKEN" \
  "$BASE/api/v1/dashboard/energy-intensity?range=CUSTOM&from=2026-04-01T00:00:00Z&to=2026-04-30T23:59:59Z&orgNodeId=5" \
  | jq '[.data.points[].intensity] | add / length'  # 月均 kWh/件

# 改造后：5 月
curl -s -H "Authorization: Bearer $TOKEN" \
  "$BASE/api/v1/dashboard/energy-intensity?range=CUSTOM&from=2026-05-01T00:00:00Z&to=2026-05-31T23:59:59Z&orgNodeId=5" \
  | jq '[.data.points[].intensity] | add / length'
```

输出例：

```
4 月: 0.0985 kWh/件   （基线）
5 月: 0.0892 kWh/件   （改造后，-9.4%）
节省: 月产量 100,000 件 × 0.0093 kWh × 0.85 元 = 790 元/月
回本期: 改造投入 8,000 元 ÷ 790 元/月 ≈ 10 个月
```

这就是给老板看的 ROI 报告。看板/报表只展示原始数据，对比和换算最简单的做法是 Excel 拉出来手算，或者写一张 Grafana 仪表板。

---

## 6. 步骤 ⑥：录入习惯固化

| 谁录 | 频率 | 怎么进 EMS |
|---|---|---|
| 班长 | 每班结束 | 前端表单 1 条 |
| 车间统计员 | 每天下班 | 1 张 CSV 全车间汇总，导入 |
| MES / ERP（如有）| 实时 | 写一个 cron 脚本从 MES 拉产量 → POST `/entries`（用 bot 账号）|

> 冷启动建议：先让 1 个车间的 1 个班长录 2 周，确认"单位产量能耗"曲线对得上感觉，再推全厂。一上来 4 个车间 12 个班全推，没人会坚持录。

---

## 7. 验收清单

- [ ] 3 个班次（或 1 个全天）建好，`/api/v1/shifts` 返回非空
- [ ] 至少 1 周连续产量数据，每个生产节点 ≥ 5 条记录
- [ ] CSV 导入跑通：`succeeded > 0 && errors == []`（或全是已知重复）
- [ ] `GET /api/v1/dashboard/energy-intensity?orgNodeId=<生产节点>` 返回 `intensity` 非 null
- [ ] 看板"单位产量能耗"曲线非空，数值在合理范围（一般 0.01 - 10 kWh/件 之间，量级对不上就是 unit 写错了）
- [ ] 跟班长 / 统计员对接好"每班/每天 5 分钟录入"流程（不能是临时找人补的）

---

## 8. 故障速查

| 现象 | 排查路径 |
|---|---|
| `POST /entries` 返回 409 "产量记录已存在" | 4 元组（orgNode, shift, date, productCode）冲突；用 `PUT /entries/{id}` 改，不要重复 POST |
| CSV 导入 errors 全是"列数不足" | 行尾少 `,`；不带 remark 也要保留末尾逗号 `5,1,2026-05-15,SKU-001,1250,件,` |
| CSV 导入 errors 出现"不支持含逗号的带引号字段" | remark 字段里有逗号或带 `"` 包裹；改全角逗号或去掉引号 |
| `dashboard/energy-intensity` 返回 `production: 0` | 该日没录产量；或 orgNodeId 选错了节点（选了办公节点）|
| `intensity` 看着特别大（如 1500 kWh/件）| 单位写错——把"个"当"千件"了；或 `quantity` 应填 1500 写成了 1.5 |
| 录产量但报表 `intensityKwhPerUnit` 还是 null | 报表口径用的是节点电量；该节点下属仪表如果都没接，电量就是 0/null。先回 `dashboard-commissioning-sop.md` 验证仪表点位 |
| 夜班归属日纠结 | 团队约定一致即可——本 SOP 推荐"班次结束当天" |

---

## 9. 收尾：完整路线图绿灯了

至此 EMS 装机交付全闭环，"装-通-看-警-钱-报-效"7 步全打勾：

| 阶段 | 文档 | 状态 |
|---|---|---|
| ① 选表选服务器 | `meter-selection-guide.md` | ✅ |
| ② 现场施工 | `field-installation-sop.md` | ✅ |
| ③ 通道导入 | `meter-register-mapping-template.csv` + `csv-to-channels.py` + `import-channels.sh` + 前端"批量导入" | ✅ |
| ④ 看板上线 | `dashboard-commissioning-sop.md` | ✅ |
| ⑤ 5 分钟 demo | `dashboard-demo-quickstart.md` + `demo-up.sh` | ✅ |
| ⑥ 告警上线 | `alarm-commissioning-sop.md` | ✅ |
| ⑦ 账单上线 | `billing-commissioning-sop.md` | ✅ |
| ⑧ 月报自动化 | `report-automation-sop.md` + `monthly-report-mail.sh` | ✅ |
| ⑨ 生产能效 | 本文 | ✅ |

---

**相关文档**

- 月报自动化 SOP：[report-automation-sop.md](./report-automation-sop.md)
- 账单上线 SOP：[billing-commissioning-sop.md](./billing-commissioning-sop.md)
- 看板上线 SOP：[dashboard-commissioning-sop.md](./dashboard-commissioning-sop.md)
- 看板演示快速上手：[dashboard-demo-quickstart.md](./dashboard-demo-quickstart.md)
- 告警上线 SOP：[alarm-commissioning-sop.md](./alarm-commissioning-sop.md)
- 现场施工 SOP：[field-installation-sop.md](./field-installation-sop.md)
- 选表指南：[meter-selection-guide.md](./meter-selection-guide.md)
- 通道批量导入：`scripts/csv-to-channels.py` + `scripts/import-channels.sh`
- 仪表批量导入：`scripts/csv-to-meters.py` + `scripts/import-meters.sh`（或前端 `/meters` 页"批量导入"按钮，v2 新增）
