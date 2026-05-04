# 仪表盘（ems-dashboard）· 功能概览

> 适用版本：v1.3.0+ ｜ 最近更新：2026-05-01
> 受众：销售 / 客户 / 一线运营 / 实施工程师

---

## §1 一句话价值

登录后第一眼看到的全场能耗驾驶舱：今天用了多少电、当前需求功率、能耗集中在哪些区域、采集健康率、单位产量能耗趋势、各电价时段占比、Top 设备、平面图实时态。所有 KPI 一屏呈现，不必切多个页面去凑。

---

## §2 解决什么问题

- **打开系统不知道看什么**：登录后只是一堆菜单，需要逐个点进去才能拼出全场能耗画像。
- **领导看一眼要看完关键数据**：报表是详细数据，但日常 30 秒扫一眼想看"今天有没有异常"，需要 KPI 概览。
- **运营和管理看的角度不同**：运营关注"实时功率 + 哪个表掉线"，管理关注"环比 + 单位产量 + 占比"。一个仪表盘要兼顾两类视角。
- **数据散落**：能耗在 InfluxDB、电价在 PostgreSQL、产量在产量表、报警在报警表——一屏聚合需要后端代劳。

---

## §3 核心功能

仪表盘由若干"组件（widget）"拼成，每个组件对应一个 API。当前内置 10 个组件：

- **§3.1 KPI 摘要卡（`/kpi`）**：今日累计用电、当前需求功率、采集健康在线率、活跃报警数、单位产量能耗。一屏顶部 4-5 张卡片。
- **§3.2 实时功率曲线（`/realtime-series`）**：选时段（今日 / 本周）+ 节点 → 5 分钟粒度的功率折线。
- **§3.3 能耗构成饼图（`/energy-composition`）**：按子节点 / 按能源类型拆分占比。
- **§3.4 Top N 设备（`/top-n`）**：用电最多 / 增长最快 / 故障最多的 Top 10。
- **§3.5 电价分布饼（`/tariff-distribution`）**：本月各电价档位（峰 / 平 / 谷 / 尖峰）的电费占比，用来识别"夜间用电太少导致谷价红利没吃到"之类的异常。
- **§3.6 成本分布（`/cost-distribution`，由 ems-billing 提供）**：本月各节点的费用排行。
- **§3.7 能源强度趋势（`/energy-intensity`）**：单位产量能耗的近 N 天 / 月趋势曲线（要 ems-production 录入产量数据才有用）。
- **§3.8 Sankey 桑基图（`/sankey`）**：能源从总进线 → 各车间 → 各产线的流向，直观看能源走向哪儿。
- **§3.9 平面图实时态（`/floorplan/{id}/live`）**：嵌入指定底图，实时更新各挂点状态。
- **§3.10 仪表详情页（`/meter/{id}/detail`）**：点 Top N 或 Sankey 下钻到单表详情，看该表近期的功率曲线 + 累计电量 + 历史报警。

**节点切换**：右上角下拉框选某节点（按用户权限范围过滤），所有组件按该节点过滤。

**自动刷新**：组件按合理周期自动 fetch（实时类 30 秒，统计类 5 分钟）。

---

## §4 适用场景

### 场景 A：日常运营 — 上班 30 秒扫一眼

进 `/dashboard`，先看 KPI 摘要卡：今日已用 8500 度（同比 +5%、环比 -3%）、当前功率 850 kW、采集在线率 98.5%、活跃报警 2 条。然后看 Top N 是否有反常（突然出现的高耗设备）。30 秒看完，正常就关页面，异常就下钻到具体仪表。

### 场景 B：领导审查 — 月底回顾

财务总监进 `/dashboard`，节点切到"主厂区"，时段选"近 30 天"。看能耗构成（一车间 40% / 二车间 35% / 办公区 25%）+ 电价分布（峰电 35% / 平电 50% / 谷电 15%）。识别"二车间夜班负荷低，谷电没吃到红利"作为下次开会议题。

### 场景 C：故障演示 — 客户参观

打开 `/dashboard`，节点选"全厂"，主屏放 Sankey 桑基图加平面图实时态。观众一眼能看到全场能源流向和设备实时态，比 Excel 报表直观。

### 场景 D：节能改造效果对比

某车间 6 月装了变频器，在 `/dashboard` 节点选该车间、时段选"近 90 天"。看能源强度曲线（单位产量能耗），改造前后对比效果一目了然。

---

## §5 不在范围（首版）

- **不做自定义布局**：组件位置和数量是产品预设，用户不能拖拽布局或新增组件。需要定制看板可走 Grafana（观测栈自带）。
- **不做组件参数自由配置**：每个组件的时间窗口、聚合粒度由产品定，用户只能切节点和时段（小范围）。
- **不做 KPI 阈值报警**：仪表盘只展示数据，不做"今日用电 > 阈值就发短信"这种业务规则。这类规则规划在 v2.x。
- **不做导出 / 截图**：仪表盘是浏览态，不支持一键生成 PDF。导出请用报表模块。
- **不做大屏模式**：组件大小固定，没有"全屏 TV 大屏展示"专用模式。需要时可用浏览器全屏 + 缩放凑合。
- **不做下钻链路自定义**：点击"Top N 设备"固定跳转到仪表详情页，不支持自定义跳转目标。

---

## §6 与其他模块的关系

```
ems-orgtree            节点过滤
ems-meter              仪表元数据
ems-timeseries         所有功率 / 能耗数据来自 InfluxDB
ems-tariff             /tariff-distribution 用
ems-production         /energy-intensity 用（产量数据）
ems-cost / ems-billing /cost-distribution 用
ems-floorplan          /floorplan/{id}/live 嵌入
ems-alarm              KPI 卡里的"活跃报警"数
       │
       └────> ems-dashboard（聚合 + 渲染层）
```

仪表盘只是消费者，它不存数据，所有数据来自其他模块。前置模块的数据质量直接决定仪表盘的可用性。

---

## §7 接口入口

- **前端路径**：`/dashboard`
- **API 前缀**：`/api/v1/dashboard`
- **关键端点**：

| 端点 | 用途 |
|---|---|
| `GET /kpi` | KPI 卡片数据 |
| `GET /realtime-series` | 实时功率曲线 |
| `GET /energy-composition` | 能耗构成 |
| `GET /top-n` | Top N 设备 |
| `GET /tariff-distribution` | 电价档位分布 |
| `GET /cost-distribution` | 成本分布（实现在 ems-billing 模块）|
| `GET /energy-intensity` | 能源强度趋势 |
| `GET /sankey` | 桑基图 |
| `GET /floorplan/{id}/live` | 平面图实时态 |
| `GET /meter/{id}/detail` | 仪表详情下钻 |

---

## §8 性能与缓存

- KPI 类查询走 InfluxDB rollup 聚合层（5min / 1h / 1d），不查原始数据，单次延迟 < 200 ms
- 节点 + 时段切换是同步触发，前端不做客户端缓存
- 后端服务层对组件结果做秒级缓存（避免多用户同节点重复查询）

---

**相关文档**

- 报表（详细分析）：[report-feature-overview.md](./report-feature-overview.md)
- 仪表与计量：[meter-feature-overview.md](./meter-feature-overview.md)
- 平面图：[floorplan-feature-overview.md](./floorplan-feature-overview.md)
- 班次与产量：[production-feature-overview.md](./production-feature-overview.md)
- 电价：[tariff-feature-overview.md](./tariff-feature-overview.md)
- 平台总览：[product-overview.md](./product-overview.md)
