# 看板上线 SOP（org-tree + meter + floorplan + dashboard）

> **场景**：50 块电表已通过 [field-installation-sop.md](./field-installation-sop.md) 接通 collector，
> 现在要把数据"看见"——配 org-tree、注册 meter、上传楼层底图、挂点、进仪表盘验收。
>
> **前置**：
> - `/collector` 页 4 条通道全 `CONNECTED`、24h 成功率 ≥ 99%
> - 已有 ADMIN 账号 + JWT Token（`POST /api/v1/auth/login`）
>
> **架构链路提醒**：
>
> ```
> channel.points[].key  ==  meter.code   ←—— 关键约定（InfluxSampleWriter.java:24）
>          │                    │
>     未匹配静默丢弃         注册了才存 InfluxDB
>          │                    │
>      ↓                    ↓
>   不存数据              进 dashboard / report / billing
> ```
>
> 含义：channel 抓到的所有 point 都会触发一次 sink，但只有 `meter.code = point.key` 的才真正落库。
> 所以不必给每个测点都建 Meter，挑你要看的（功率 + 电量）即可。其余的白白多写 5 千条/天没意义。

---

## 0. 总体规划（4 楼层 50 块表）

按 a 方案 org-tree 结构：

```
工厂总节点 (FACTORY)        — code=MOCK-FACTORY-001
├── 1F (FLOOR)              — code=MOCK-FACTORY-001-1F
├── 2F (FLOOR)              — code=MOCK-FACTORY-001-2F
├── 3F (FLOOR)              — code=MOCK-FACTORY-001-3F
└── 4F (FLOOR)              — code=MOCK-FACTORY-001-4F
```

每块物理表注册 **2 条 Meter**：
- `<meter_tag>-power_total`（瞬时功率）
- `<meter_tag>-energy_total`（累计电量）

50 块表 → 100 条 Meter 行。其余测点（电压/电流/频率/PF）通过 channel 上送，但不入 InfluxDB（除非未来要看波形图再加）。

---

## 1. 步骤 ①：建 org-tree（5 个节点）

API：`POST /api/v1/org-nodes`（ADMIN）

```bash
TOKEN="<jwt>"
BASE="https://ems.example.com"

# 1.1 工厂根节点
curl -s -X POST "$BASE/api/v1/org-nodes" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"parentId":null,"name":"MOCK 工厂","code":"MOCK-FACTORY-001","nodeType":"FACTORY","sortOrder":0}'
# → 返回 {"data": {"id": 1, ...}}  记下 factoryId
FACTORY_ID=1

# 1.2 4 个楼层
for i in 1 2 3 4; do
  curl -s -X POST "$BASE/api/v1/org-nodes" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d "{\"parentId\":$FACTORY_ID,\"name\":\"${i}F\",\"code\":\"MOCK-FACTORY-001-${i}F\",\"nodeType\":\"FLOOR\",\"sortOrder\":${i}}"
done
```

**校验**：

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/org-nodes/tree" | jq .
```

应返回根节点 + 4 子节点。前端 `/admin/orgtree` 也能直接图形化建。

> **`nodeType`** 字段是开放枚举：`FACTORY` / `FLOOR` / `WORKSHOP` 都可（regex `[A-Za-z0-9_\-]+`）。a 方案选 `FLOOR`；如果你之后想转车间组织结构，把 `FLOOR` 改成 `WORKSHOP` 即可。

---

## 2. 步骤 ②：查 energy_type id（电）

50 块表全是电表，所有 Meter 行的 `energyTypeId` 取 `code='ELEC'` 那条：

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/energy-types" | jq '.data[] | select(.code=="ELEC") | .id'
# → 1
ELEC_ID=1
```

> seed 默认有 3 种：`ELEC`(电,kWh) / `WATER`(水,m³) / `STEAM`(蒸汽,t)。

---

## 3. 步骤 ③：注册 Meter（100 条）

API：`POST /api/v1/meters`（ADMIN）

**关键字段**（验证自 `CreateMeterReq.java`）：

| 字段 | 含义 | 我们的填法 |
|---|---|---|
| `code` | 必须等于 channel 的 `points[].key`（否则数据进不了 InfluxDB） | 例：`1F-M-01-power_total` |
| `name` | 业务名 | `1F-M01 总功率` |
| `energyTypeId` | 能源类型 | `ELEC_ID`（=1） |
| `orgNodeId` | 所属节点 | 该楼层的 id |
| `influxMeasurement` | InfluxDB measurement 名 | `energy_reading`（与 `MeterSeeder.java:26` 对齐） |
| `influxTagKey` | tag 列名 | `meter_code`（与 `MeterSeeder.java:27` 对齐） |
| `influxTagValue` | tag 值 | 同 `code`（如 `1F-M-01-power_total`） |
| `channelId` | 关联的 channel id | 该楼层串口服务器对应的 channel id |
| `enabled` | 启用 | `true` |

**手工录入示例**（一块表 = 2 条 Meter）：

```bash
# 假设已查好：1F 节点 id=2, 1F-MCC-485 channel id=1
ORG_1F=2
CHAN_1F=1

for kind in power_total energy_total; do
  curl -s -X POST "$BASE/api/v1/meters" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d "{
      \"code\":\"1F-M-01-${kind}\",
      \"name\":\"1F-M01 ${kind}\",
      \"energyTypeId\":${ELEC_ID},
      \"orgNodeId\":${ORG_1F},
      \"influxMeasurement\":\"energy_reading\",
      \"influxTagKey\":\"meter_code\",
      \"influxTagValue\":\"1F-M-01-${kind}\",
      \"channelId\":${CHAN_1F},
      \"enabled\":true
    }"
done
```

**批量化**（50 块表 100 条不要手敲）：用 `csv-to-meters.py` + `import-meters.sh` 两步走，与 channel 导入是同形态：

```bash
# 1) 转换：从 meter mapping CSV 生成 meters JSON（只保留 power_total + energy_total）
./scripts/csv-to-meters.py \
  docs/install/meter-register-mapping-template.csv \
  --floor-org 1F=2,2F=3,3F=4,4F=5 \
  --floor-channel-name 1F=1F-MCC-485,2F=2F-MCC-485,3F=3F-MCC-485,4F=4F-MCC-485 \
  --include-suffix power_total,energy_total \
  -o /tmp/meters.json

# 2) 导入：脚本会自动 GET /api/v1/channel 把 channelName 解析成 channelId
EMS_BASE_URL="$BASE" EMS_TOKEN="$TOKEN" \
  ./scripts/import-meters.sh /tmp/meters.json
```

> 默认导出全部点位（每块表 ~10 条 Meter）。看板 + 账单只用 `power_total` + `energy_total`，所以加 `--include-suffix` 把 100 条砍到 50×2=100 条 Meter。其它电压/电流字段后续要时补即可。

**或前端 GUI 走（v2 新增，不想登服务器跑命令的话）**：

进 `/meters` 页 → 右上"批量导入"按钮 → 拖入 §1 生成的 `meters.json`（`csv-to-meters.py` 输出原样，schema 一致）→ "开始导入"。

- GUI 会自动 `GET /api/v1/channel` 解析 `channelName → channelId`（与 `import-meters.sh` 同款逻辑）
- 找不到的 `channelName` 该行直接 fail 并明确提示，不影响其他行
- HTTP 409 → SKIP，其他错误 → FAIL，全程不阻塞
- 完成后列表自动刷新（`invalidateQueries(['meters', 'topology'])`）
- 失败行可重试，无需重新上传

实现：`frontend/src/pages/meters/MeterBatchImportModal.tsx`。

**校验**：

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/meters?orgNodeId=${ORG_1F}" | jq '.data | length'
# 应该 = 24（1F 12 块表 × 2 条 Meter）
```

如果新建的 Meter 几分钟后 InfluxDB 里没有数据：
- 99% 是 `code` 与 channel 的 `point.key` 拼写不一致 → `GET /api/v1/channel/<id>` 对一下
- `channelId` 写错了
- channel 本身没在采（去 `/collector` 页看 connState）

---

## 4. 步骤 ④：准备楼层底图

| 准备方式 | 说明 |
|---|---|
| **CAD 导出**（推荐） | 1) AutoCAD 打开 dwg → 2) 按楼层框选 → 3) 导出为 PNG/JPG（**1920×1080** 即可，5 MB 内） |
| **手画占位** | Figma / Excalidraw / 甚至 PPT 截图，先有图能装电表，等 CAD 到再换 |
| **平面图实测照片** | 配电室拍照、纸图扫描，对凑合验收够用 |

**必须**：4 张图（1F/2F/3F/4F 各一张）。文件名规范：`floor-1F.png`、`floor-2F.png`...

> 大文件（> 10 MB）会让前端加载慢；超过 5 MB 用 ImageOptim / TinyPNG 压一压。

---

## 5. 步骤 ⑤：上传底图 + 创建 floorplan

API：`POST /api/v1/floorplans/upload`（multipart，ADMIN）

```bash
for floor in 1F 2F 3F 4F; do
  ORG_ID=$(curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/org-nodes/tree" \
    | jq ".data[].children[] | select(.name==\"$floor\") | .id")

  curl -s -X POST "$BASE/api/v1/floorplans/upload" \
    -H "Authorization: Bearer $TOKEN" \
    -F "file=@./floor-${floor}.png" \
    -F "name=${floor} 平面图" \
    -F "orgNodeId=${ORG_ID}" \
    | jq '.data.id'
done
```

或直接前端 `/floorplan` 页点「新建」上传更直观。

---

## 6. 步骤 ⑥：拖挂点（图形化操作）

1. 进 `/floorplan` 列表 → 点 1F 行的「编辑」→ 进 `/floorplan/editor/<id>`
2. 左侧栏列出 1F 节点下所有 Meter（24 条），右边显示底图
3. **把每块表的 `power_total` Meter 拖到底图上对应物理位置**（energy_total 不需要拖，平面图只看实时功率）
4. 拖完点「保存」

> 数据存储：`floorplan.points` JSONB 字段，每个挂点 `{meterId, xRatio, yRatio, label}`，xy 是 0-1 之间的相对坐标，浏览器按底图原始尺寸缩放。
>
> 要批量化（不在前端拖）：调 `PUT /api/v1/floorplans/{id}/points`，传 `SetPointsReq` JSON。但实践上拖一遍比写脚本快，50 块表的位置只有现场工程师知道。

---

## 7. 步骤 ⑦：进 /dashboard 验收

1. 登入 → `/dashboard` 默认显示根节点（MOCK 工厂）的全场视图
2. 顶部下拉切到「1F」→ 所有面板切到 1F 范围

**逐板检查**：

| 面板 | 看什么 | 通过标准 |
|---|---|---|
| KPI 摘要卡 | 今日累计电量 / 当前需求功率 / 采集在线率 / 活跃告警 | 数字非 0 / 在线率 ≥ 99% |
| 实时功率曲线 | 1F 总功率近 1h 折线 | 有数据点、5 min 粒度连续 |
| 能耗构成饼图 | 1F 各表电量占比 | 12 块表都出现 |
| Top N 设备 | 1F 用电 Top 10 | 排序正确，电量随时间累计 |
| **平面图实时态** | **嵌入 1F 平面图，挂点显示功率** | **绿色挂点显示数字，鼠标悬停看 meter 详情** |
| 电价分布 | 本月峰/平/谷电费占比 | 录了电价就有；没录显示空 |

平面图实时态接口：`GET /api/v1/dashboard/floorplan/{id}/live`，30 秒自动刷新。

---

## 8. 故障速查

| 现象 | 排查路径 |
|---|---|
| KPI 卡片全为 0 | ① 没注册 Meter（步骤 ③ 没做完）② Meter 的 `code` 与 channel `point.key` 不一致 ③ channel 本身没采到数（看 `/collector`） |
| 实时功率曲线空 | InfluxDB 没数据。`docker exec -it ems-influxdb influx query 'from(bucket:"ems") \|> range(start: -1h) \|> filter(fn: (r) => r._measurement == "meter_readings") \|> limit(n:5)'` 看有没有数据 |
| Top N 显示一半 | 有些 meter 的 `energy_total` 没注册或 `enabled=false` |
| 平面图挂点全灰 | meter 的 `channelId` 或 `point_key` 写错；后端 join 不到实时数据 |
| 平面图挂点位置乱 | 之前编辑后没点保存；或者底图换过尺寸（重新拖） |
| Sankey 桑基图空 | Sankey 需要"上下游能源关系"；50 块表如果没建电气拓扑就显示空。这属于正常情况，不影响其他面板 |

---

## 9. 验收清单（所有项 ✅ 才算上线）

- [ ] org-tree 5 节点已建（FACTORY + 4 FLOOR）
- [ ] Meter 100 条已建（50 块表 × 2 个 code）
- [ ] InfluxDB 里 `meter_readings` measurement 有数据，过去 1h 数据点 ≥ 50 × 60 = 3000 条
- [ ] 4 张楼层底图已上传，每张关联到对应 FLOOR 节点
- [ ] 4 张底图已拖完挂点，每张 ~12 个挂点
- [ ] `/dashboard` 切到工厂根：KPI 全有数、实时曲线连续、Top N 排序正确
- [ ] `/dashboard` 切到 1F：平面图实时态显示 12 个绿色挂点，悬停有 meter 名 + 数字
- [ ] 关掉某条 channel 5 分钟，对应楼层的挂点变红 → 说明告警链路也通（下一阶段告警 SOP 详细测）

---

## 10. 与下一阶段（告警）的衔接

看板上线后，下一步（告警上线）可以复用：
- **org-tree 节点**：告警规则按节点过滤（如"1F 任一表掉线 → 告警")
- **Meter 注册**：告警阈值挂在 Meter 上（如"1F-M-01 当前功率 > 100 kW 持续 5 min → 告警"）
- **平面图**：挂点会自动随告警状态变红/变黄，无需额外配置

告警 SOP 后续单独出一份。

---

**相关文档**

- 看板产品介绍：[../product/dashboard-feature-overview.md](../product/dashboard-feature-overview.md)
- 平面图产品介绍：[../product/floorplan-feature-overview.md](../product/floorplan-feature-overview.md)
- 仪表与计量：[../product/meter-feature-overview.md](../product/meter-feature-overview.md)
- 选型指南：[meter-selection-guide.md](./meter-selection-guide.md)
- 现场施工 SOP：[field-installation-sop.md](./field-installation-sop.md)
- 5 分钟演示：[dashboard-demo-quickstart.md](./dashboard-demo-quickstart.md)
- 告警上线 SOP：[alarm-commissioning-sop.md](./alarm-commissioning-sop.md)
- 账单上线 SOP：[billing-commissioning-sop.md](./billing-commissioning-sop.md)
- 月报自动化 SOP：[report-automation-sop.md](./report-automation-sop.md)
- 生产能效 SOP：[production-energy-sop.md](./production-energy-sop.md)
- 通道配置 JSON：[channel-config-import.json](./channel-config-import.json)
- 通道批量导入：`scripts/csv-to-channels.py` + `scripts/import-channels.sh`
- 仪表批量导入：`scripts/csv-to-meters.py` + `scripts/import-meters.sh`
