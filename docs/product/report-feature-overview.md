# 报表（ems-report）· 功能概览

> 适用版本：v1.3.0+ ｜ 最近更新：2026-05-01
> 受众：销售 / 客户 / 财务 / 运营 / 管理层 / 实施工程师

---

## §1 一句话价值

把"什么时间、哪个节点、哪种能源、按什么粒度统计"这件事固化成系统能力。运营要日报、财务要月报、管理要年度审查、班组要班次能耗——一处接口对应所有口径。同时支持自定义查询（ad-hoc）和大数据量异步导出 Excel / PDF。

---

## §2 解决什么问题

- **一个 Excel 表满足不了所有人**：财务月底要按节点 + 时段 + 单价，运营要按设备 + 班次，领导要按厂区 + 同比环比。手工算每月一次累成狗。
- **数据散落难凑齐**：能耗在 InfluxDB、电价在 PostgreSQL、产量在 production_entries、节点结构在 orgtree——一份报表要拼齐 4 处数据。
- **大数据量导出会超时**：一年 365 天 × 200 块表 × 96 时段 ≈ 700 万行数据，HTTP 同步导出秒级超时；得做异步 + token 下载。
- **同比环比业务想要每张报表都有**：但实现起来需要查两段数据 + 算差值 + 算百分比，每张报表重复造轮子。

---

## §3 核心功能

### §3.1 5 张预设报表（preset reports）

每张接口固定参数、固定粒度、固定列：

- **§3.1.1 日报（`/preset/daily`）**：选某天 + 某节点 → 按 15min / 1h 粒度的能耗 + 功率曲线 + 当日累计 + 当日单位产量能耗。
- **§3.1.2 月报（`/preset/monthly`）**：选某月 + 某节点 → 每天一行 + 月汇总 + 同比（去年同月）+ 环比（上月）。
- **§3.1.3 年报（`/preset/yearly`）**：选某年 + 某节点 → 每月一行 + 年汇总 + 同比（去年）。
- **§3.1.4 班次报（`/preset/shift`）**：选时间窗 + 某节点 → 每个班次一行 + 单位产量能耗。需 ems-production 已录入产量。
- **§3.1.5 月度成本报（`/preset/cost-monthly`）**：选某月 + 某节点 → 各子节点的电费分解 + 各时段（峰平谷）占比 + 同比环比。

### §3.2 自定义报表（ad-hoc）

- **同步 CSV 流式下载**（`GET /report/ad-hoc`）：JSON 参数描述维度（节点 / 能源 / 时段）+ 粒度（15min / 1h / 1d）+ 时间窗，返回 CSV 文件流。适合中等数据量（< 10 万行）。
- **异步导出 + token 下载**（`POST /report/ad-hoc/async` → `GET /report/file/{token}`）：服务端开线程跑，结果落到 `EMS_REPORT_EXPORT_BASE_DIR` 目录，前端拿 token 来下载。适合大数据量（百万行级）。

### §3.3 矩阵导出（Excel / PDF）

- **`POST /reports/export`**：通用导出入口，请求体描述"行维度（如节点）+ 列维度（如时间段）+ 度量（能耗 / 功率 / 单价 / 电费）"，服务端组装成矩阵后导出为 Excel 或 PDF。
- **`GET /reports/export/{token}`**：异步导出后取结果。
- **格式**：CSV（默认）/ Excel（POI 生成）/ PDF（带页眉页脚 + 图表）。

### §3.4 通用能力

- **节点子树聚合**：选某节点（如"主厂区"），所有子节点的数据自动汇总，不用一次次手动加。
- **同比环比**：每个数值列旁边自动跟两列百分比。
- **多能源类型**：一份报表可以同时统计电 + 水 + 蒸汽（按能源类型分组）。
- **权限范围**：报表自动按用户的节点权限过滤——管不到的厂区不出现在结果里。
- **导出文件保留期**：异步导出文件默认保留 7 天后清理，可配置。

---

## §4 适用场景

### 场景 A：财务月底 — 按部门出电费分摊报表

财务每月 5 号给各部门出"上月电费分摊单"。在 `/report/preset/cost-monthly` 选上月、节点选"全厂"、按"二级节点"展开 → 一秒出"一车间 ¥45000 / 二车间 ¥38000 / 办公区 ¥8000，含峰平谷拆分 + 同比 -3% / +5% / -1%"。导出 PDF 发给各部门负责人。

### 场景 B：运营 — 班次能耗对比

每月初做绩效考核。在 `/report/preset/shift` 选上月、节点选"包装车间"，得到每天每班一行"产量 + 能耗 + 单位产量能耗"。透视后发现"夜班 5.2 度/件 < 早班 5.8 度/件"——夜班奖励、早班排查。

### 场景 C：大数据量 — 出具历年明细

碳核查要"过去 3 年所有仪表 15 分钟级电量明细"。该数据量约 1.5 亿行：

1. 在 `/report/ad-hoc/async` POST 请求体（含 3 年时间窗 + 全表 + 15min 粒度），收到 token。
2. 后端线程跑约 10-30 分钟，落 CSV 到磁盘。
3. 用 token 调 `/report/file/{token}` 下载 CSV.gz（约 800MB 压缩）。
4. 数据交给审计师离线分析。

### 场景 D：管理层季度审查

总经理要"近 4 个季度全厂用电趋势 + 单位产量能耗 + 电价构成"。一份月报（4 个月）的组合：在 `/report/preset/monthly` 跑 4 次（每个月一次）→ 拼成一张大 Excel → 加 PivotChart 出曲线。或通过 `/reports/export` 矩阵导出一次性出全。

---

## §5 不在范围（首版）

- **不做报表自定义编辑器**：预设报表是产品定的列和粒度，用户不能在 UI 拖列、改聚合函数。如要自定义维度，走 ad-hoc 接口或 Grafana。
- **不做报表订阅 / 邮件推送**：不能"每月 5 号自动出月报发邮件"。这类规划在 v2.x（含定时任务 + 邮件 / 钉钉推送）。
- **不做实时报表**：所有报表查 InfluxDB 已落盘数据，最新数据有 1-5 分钟延迟。
- **不做报表权限到列**：用户能看到报表就能看到所有列；不支持"财务能看金额、运营只看物理量"这种列级权限。
- **不做跨能源单位换算（标煤）**：电、水、蒸汽各自的物理量直出；折算"吨标煤"由用户在 Excel 自行公式或定制扩展。
- **不做 BI 仪表板**：不做拖拽式可视化（仪表盘可视化看 ems-dashboard 或 Grafana）。

---

## §6 与其他模块的关系

```
ems-orgtree         节点子树聚合
ems-meter           最细粒度按仪表
ems-timeseries      所有能耗 / 功率数据来自 InfluxDB
ems-tariff          月度成本报取价
ems-production      班次报取产量数据
ems-cost / ems-billing  月度成本报 / 复用其计算结果
ems-auth            报表按用户权限范围过滤
       │
       └────> ems-report（聚合 + 计算 + 导出层）
```

报表是各模块数据的"消费者 + 加工者"——把多源数据按业务口径揉成可读形式。

---

## §7 接口入口

- **前端路径**：`/reports`
- **API 前缀**：`/api/v1/report`、`/api/v1/reports`
- **关键端点**：

| 端点 | 用途 |
|---|---|
| `GET /api/v1/report/preset/daily` | 日报 |
| `GET /api/v1/report/preset/monthly` | 月报 |
| `GET /api/v1/report/preset/yearly` | 年报 |
| `GET /api/v1/report/preset/shift` | 班次报 |
| `GET /api/v1/report/preset/cost-monthly` | 月度成本报 |
| `GET /api/v1/report/ad-hoc` | 自定义同步 CSV 流 |
| `POST /api/v1/report/ad-hoc/async` | 自定义异步导出（返回 token）|
| `GET /api/v1/report/file/{token}` | 异步结果下载 |
| `POST /api/v1/reports/export` | 矩阵 Excel / PDF 导出 |
| `GET /api/v1/reports/export/{token}` | 异步矩阵结果下载 |

---

## §8 性能与文件存储

- **预设报表**：走 InfluxDB rollup 聚合（5min / 1h / 1d），单次 < 500 ms（月报 / 年报）。
- **同步 ad-hoc**：HTTP 流式 CSV，理论无大小限制但浏览器超时一般 5-10 min；推荐 < 10 万行。
- **异步导出**：服务端线程池跑（`ReportExportExecutorConfig`），结果落 `EMS_REPORT_EXPORT_BASE_DIR`（默认 `/data/ems_uploads/exports/`）。token 7 天过期。
- **矩阵 Excel**：用 Apache POI SXSSFWorkbook（流式），支持百万行级；PDF 用 OpenPDF 生成。

---

## §9 关键字段

### 文件 token 表 `report_export_tokens`

| 字段 | 类型 | 说明 |
|---|---|---|
| `token` | varchar | UUID，下载凭证 |
| `file_path` | varchar | 服务端落盘路径 |
| `format` | varchar | `CSV` / `XLSX` / `PDF` |
| `status` | varchar | `PENDING` / `RUNNING` / `SUCCESS` / `FAILED` |
| `created_at` | timestamp | 创建时间 |
| `expires_at` | timestamp | 过期时间（默认 +7 天）|
| `error_msg` | varchar | 失败原因（status=FAILED 时）|

---

**相关文档**

- 仪表盘（实时态）：[dashboard-feature-overview.md](./dashboard-feature-overview.md)
- 班次与产量：[production-feature-overview.md](./production-feature-overview.md)
- 电价：[tariff-feature-overview.md](./tariff-feature-overview.md)
- 成本分摊：[cost-feature-overview.md](./cost-feature-overview.md)
- 账单：[billing-feature-overview.md](./billing-feature-overview.md)
- 平台总览：[product-overview.md](./product-overview.md)
