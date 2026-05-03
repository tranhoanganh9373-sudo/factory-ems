# 仪表与计量（ems-meter）· 功能概览

> 适用版本：v1.1.0+ ｜ 最近更新：2026-05-01
> 受众：销售 / 客户 / 实施工程师

---

## §1 一句话价值

每一块物理仪表（电表 / 水表 / 蒸汽表 / 气表 / 热量表）在系统里有一份对应的"计量点"档案。档案里写明它是什么类型、属于哪个组织节点、用什么协议采集、阈值多严格、是否在维护期。所有时序数据用计量点 ID 关联，报警和报表都从这里读元数据。

---

## §2 解决什么问题

- **现场仪表数百块，型号品牌混杂，没有统一档案**：维修时找不到接线图，更换时不知道历史读数对应哪个计量口径。
- **每块仪表的"重要度"不同，统一阈值要么漏报要么误报**：主变压器和照明回路对断线的容忍度差几个数量级，需要逐表配置。
- **计量层级想画成树（总表 → 分表 → 末端表）但没地方存**：手工拉 Excel 表关系，更换设备时改起来一团乱。
- **维护期间报警泛滥**：年检 / 计划停机时几十块仪表同时离线，报警轰炸盖过了真异常。

---

## §3 核心功能

- **仪表档案**：每块仪表存编码、名称、能源类型、所属组织节点、采集协议、设备地址、点位映射、单位（kWh / m³ / kg / GJ）、是否启用。
- **3 种能源类型**（首版）：`ELEC` 电（kWh）、`WATER` 水（m³）、`STEAM` 蒸汽（t）。其他能源类型可定制扩展。
- **仪表拓扑（树）**：通过 `MeterTopology` 表把仪表组织成"父表 → 子表"的层级关系，方便做"总分表对账"（总表读数 vs. 子表读数总和的差值看泄漏 / 偷电）。
- **设备级阈值**：`silence_seconds`（静默超时秒数）和 `consecutive_failures`（连续失败次数）两个阈值可在每块仪表上单独覆盖全局默认。
- **维护模式**：单台仪表可临时进入维护期，报警自动跳过，操作进审计日志。
- **InfluxDB 字段映射**：`influxMeasurement` / `influxTagKey` / `influxTagValue` 三个字段定义"这块仪表的时序数据在 InfluxDB 哪里"，让一份代码同时支持多种 measurement 命名约定。
- **采集通道关联**：通过 `channelId` 字段绑定到具体采集通道（OPC UA / Modbus / MQTT），配合 ems-collector 工作。
- **乐观锁防覆盖**：`version` 字段防多人同时改。
- **能源类型字典**：`/api/v1/energy-types` 提供下拉选项数据。

---

## §4 适用场景

### 场景 A：实施工程师入场 — 把现场仪表清单录入

拿到客户提供的"仪表台账"Excel，逐行录入：每行一块仪表，填编码、名称、安装位置（挂到组织节点）、能源类型、协议（OPC UA / Modbus）、设备地址、点位。录完后把 InfluxDB 字段映射也填上（一般和编码同名）。然后到 `/collector` 看采集状态，一块块仪表开始有数据流入。

### 场景 B：年检 / 计划停机 — 提前开维护模式

某车间 6 月 15 日做年检，预计 8 小时所有仪表都离线。运维提前一天进 `/meters`，把这批仪表挑出来批量开维护模式（带备注"6 月年检"）。年检期间无报警，年检结束关维护模式恢复监测。

### 场景 C：能源审计 — 总分表对账

碳核查要求"总进线电量 = 各车间分表电量之和 ± 5%"。把总表设为某车间所有分表的父表（拓扑关系），月度报表自动算总分表差值百分比，超出阈值则人工排查（可能有偷电、表损、计量误差）。

### 场景 D：高敏感设备 — 单独配阈值

主变压器是关键设备，业务上不能容忍超过 30 秒数据缺失。在 `/meters` 编辑这台主变压器，把 `silence_seconds` 设为 30；其他普通照明回路保持全局默认 300 秒。

---

## §5 不在范围（首版）

- **不做仪表自动发现**：仪表必须人工录入，平台不主动扫现场网络发现新仪表（涉及网络安全和管理责任边界）。
- **不做仪表自动型号库**：不内置厂商品牌型号清单，仪表"型号"字段是自由文本。
- **不做接线图 / 安装照片管理**：照片上传要走 ems-floorplan 模块或外部 PDM。
- **不做电表互感器（CT / PT）变比管理**：变比修正应在采集层（ems-collector）完成，仪表档案存的是变比换算后的工程值。
- **不做仪表标定 / 误差曲线**：标定记录在外部计量管理系统，本平台只读到一个工程值。
- **不做计量点跨能源转换**：电、水、蒸汽各自独立，不做"水的当量电"等折算（折算应在报表层用公式做）。

---

## §6 与其他模块的关系

```
ems-orgtree    每块仪表绑定到一个组织节点
       │
       ▼
   ems-meter ────> ems-collector  通过 channelId 找到对应采集通道
       │
       ├──────> ems-alarm        阈值（silence/failure）+ 维护模式
       ├──────> ems-timeseries   读 InfluxDB 时用 measurement+tag 定位
       ├──────> ems-cost         分摊规则可指定"哪些仪表参与"
       ├──────> ems-billing      账单按仪表统计 + 加电价
       ├──────> ems-report       所有报表的最细维度
       ├──────> ems-dashboard    详情页 /meter/{id}/detail
       └──────> ems-floorplan    平面图把仪表挂到底图上
```

仪表档案是第二位被建的元数据（仅次于组织树）。

---

## §7 接口入口

- **前端路径**：`/meters`
- **API 前缀**：`/api/v1/meters`、`/api/v1/meter-topology`、`/api/v1/energy-types`
- **关键端点**：
  - `GET /` — 列表（含筛选：节点、能源类型、启用状态）
  - `GET /{id}` — 单仪表详情
  - `POST /` / `PUT /{id}` — 新建 / 修改
  - `DELETE /{id}` — 删除（被采集 / 报警 / 报表引用时拒绝）
  - `GET /api/v1/meter-topology?parentId=X` — 拉某父表的拓扑子树
  - `PUT /api/v1/meter-topology/{childMeterId}` — 把子表挂到某父表下

---

## §8 关键字段

### `meters` 表

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigint | 主键 |
| `code` | varchar | 业务编码，唯一 |
| `name` | varchar | 显示名 |
| `energy_type_id` | bigint | 能源类型外键 |
| `org_node_id` | bigint | 所属组织节点 |
| `influx_measurement` | varchar | InfluxDB measurement 名 |
| `influx_tag_key` | varchar | tag key（通常是 `meter_code`）|
| `influx_tag_value` | varchar | tag value（通常 = `code`）|
| `channel_id` | bigint | 采集通道 ID |
| `silence_seconds` | int | 静默超时（秒），覆盖全局默认 |
| `consecutive_failures` | int | 连续失败次数阈值，覆盖全局默认 |
| `maintenance_mode` | bool | 是否维护期 |
| `enabled` | bool | 是否启用 |
| `version` | bigint | 乐观锁 |

### `meter_topology` 表

| 字段 | 类型 | 说明 |
|---|---|---|
| `parent_meter_id` | bigint | 父表 |
| `child_meter_id` | bigint | 子表 |
| `created_at` | timestamp | |

### `energy_types` 表（种子数据）

| code | name | unit |
|---|---|---|
| `ELEC` | 电 | kWh |
| `WATER` | 水 | m³ |
| `STEAM` | 蒸汽 | t |

---

**相关文档**

- 组织树：[orgtree-feature-overview.md](./orgtree-feature-overview.md)
- 平面图：[floorplan-feature-overview.md](./floorplan-feature-overview.md)
- 采集协议：[collector-protocols-user-guide.md](./collector-protocols-user-guide.md)
- 报警：[alarm-feature-overview.md](./alarm-feature-overview.md)
- 平台总览：[product-overview.md](./product-overview.md)
