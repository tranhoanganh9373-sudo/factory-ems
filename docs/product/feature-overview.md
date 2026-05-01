# Factory EMS · 功能全景图

> 适用版本：v1.7.0+ ｜ 最近更新：2026-05-01
> 受众：客户 / 实施工程师 / 产品对接人 / 撰写说明书的 AI
> 阅读时长：约 15 分钟

---

## §1 全景一图

Factory EMS 是一个**模块化能源管理平台**。从能源数据流向看，它做四件事：

```
   现场仪表 / PLC / 网关
            │
            ▼
   ┌──────────────────┐
   │ ① 采、缓冲、续传   │   collector / alarm
   │  保证数据进得来    │
   └────────┬─────────┘
            │
            ▼
   ┌──────────────────┐
   │ ② 看（实时 + 报表）│   dashboard / report / floorplan
   │  把数据呈现出来    │
   └────────┬─────────┘
            │
            ▼
   ┌──────────────────┐
   │ ③ 算（电价 + 分摊）│   tariff / cost / billing
   │  把数据变成账与 KPI │
   └────────┬─────────┘
            │
            ▼
   ┌──────────────────┐
   │ ④ 管（权限 + 审计）│   auth / admin / audit / observability
   │  保证平台可治理     │
   └──────────────────┘
```

四件事背后是 **16 个 Maven 模块**（`ems-*`），分四层组织：**数据底座 / 业务领域 / 平台能力 / 启动**。

---

## §2 数据底座（4 个模块）

### `ems-core` — 通用基础

平台所有模块共享的工具与约定：通用响应外壳（success / data / error）、统一异常类型、JWT 工具、常量（角色码、权限码、错误码）、安全相关 helper。**面向用户没有功能，但所有功能都依赖它**。

### `ems-orgtree` — 组织树

工厂的组织层级。典型结构：

```
某能源管理公司（根节点）
├── 主厂区
│   ├── 一车间
│   │   ├── 总装产线
│   │   └── 焊接产线
│   └── 二车间
└── 分厂区
    └── 包装车间
```

支持任意层级的树形结构，每个节点带有面积、人数等元数据，用于后续分摊。**所有报表、账单、告警都按节点聚合**——选了"一车间"，自动汇总该节点下所有仪表。

**典型用法**：
- 报表选节点：自动包含该节点下所有产线的能耗
- 权限按节点下放：财务只能看自己片区的账单
- 分摊按面积：节点的面积字段直接用于按面积分摊

### `ems-meter` — 仪表 / 计量点管理

每一块仪表（电表、水表、气表、蒸汽表、热量表）的元数据登记表，包括：

- **基本属性**：名称、安装位置、所属节点、能源类型、计量单位
- **采集属性**：采集协议、设备地址、点位映射
- **业务属性**：超时阈值（多久没收到数据算断线）、连续失败次数阈值、是否在维护期
- **状态**：启用 / 停用 / 维护中

支持**仪表树**视图，配合组织树呈现"哪些设备挂在哪个节点下"。

**典型用法**：
- 设备级阈值：主变压器超时 30 秒就告警，照明回路超时 5 分钟才告警
- 维护模式：提前给某台仪表开维护模式，停机检修期间不出告警
- 查阈值历史：审计日志能查出"谁在什么时候改了哪台仪表的阈值"

### `ems-timeseries` — 时序数据封装

把 InfluxDB 包装成业务可用的接口，提供：

- **写入**：仪表读数批量写入 InfluxDB
- **查询**：按设备 / 时间窗口 / 聚合粒度（5min / 15min / 1h / 1d / 1mo）查询
- **回填（rollup）**：从原始数据自动汇总成 5 分钟、15 分钟、1 小时、日、月级聚合表，加速长跨度报表
- **回滚作业失败追踪**：rollup 失败的时间窗口被记录，便于重试

**面向用户没有 UI**——用户感知到的是"报表查 1 年范围只用几秒钟"，背后就是 rollup 在工作。

---

## §3 采集与质量（3 个模块）

### `ems-collector` — 数据采集

把现场仪表 / PLC / 网关上的数据搬到平台里。架构特点：

- **多协议**：内置 OPC UA、Modbus TCP、MQTT 三种协议，每种协议独立的 driver
- **多通道**：每条协议下可配置多个通道（一个工厂往往有多套 PLC 或多个 OPC UA Server）
- **缓冲落盘**：网络中断时，读数先落本地 SQLite，恢复后自动回灌 InfluxDB
- **断线检测**：通过"静默超时"和"连续失败"双口径判断设备是否在线
- **健康指标**：每个通道、每台设备的在线率、采集成功率、最近一次成功时间

**配置外挂**：采集配置（哪些设备、用哪些协议、轮询多快）走文件而非 DB，便于运维不停机切换。

**与现场对接的常见模式**：
- PLC 走 OPC UA Server（西门子 / AB / Beckhoff 都支持）
- 老旧仪表走 Modbus TCP（RS-485 转网关后接入）
- 边缘网关走 MQTT（统一上传，平台订阅）

详见专题文档 [`collector-protocols-user-guide.md`](./collector-protocols-user-guide.md)。

### `ems-alarm` — 采集中断告警

数据质量哨兵。监测每台设备的采集状态，断了就告警：

- **双口径检测**：静默超时（多久没数据）+ 连续失败（连续采集错误）
- **状态机闭环**：触发 → 已确认 → 已解决，全程审计
- **设备级阈值覆盖**：单台仪表可单独配阈值，覆盖全局默认值
- **维护模式**：提前开维护模式，期间告警自动屏蔽，操作有备注、有审计
- **站内通知**：铃铛角标 + 通知抽屉，30 秒轮询一次
- **Webhook 外发**：HMAC-SHA256 签名外推到企业 IM / ITSM，失败重试，下发流水可查

详见专题文档 [`alarm-feature-overview.md`](./alarm-feature-overview.md)。

### 可观测性（observability，跨模块能力）

平台自身的健康监控。不是独立模块，而是嵌入到所有业务模块的指标埋点 + 一套独立的部署栈：

- **17 个业务指标**：API QPS、采集成功率、Webhook 时延、报表生成耗时等
- **4 个 SLO**：API 可用性、采集完整率、告警时延、报表准时率
- **7 个 Grafana 看板**：API 健康、采集层、告警链路、报表与导出、数据库、Web、SLO 总览
- **16 个 Prometheus 告警规则**：关联到 4 个 SLO，可路由到值班 / 一线 / 客户

详见专题文档 [`observability-feature-overview.md`](./observability-feature-overview.md)。

---

## §4 业务运营（3 个模块）

### `ems-tariff` — 电价

复杂电价规则的存储与运算：

- **分时电价**：尖峰平谷（如 8:00-11:00 是峰、22:00-次日 6:00 是谷）
- **阶梯电价**：累计用电量到某个区间走某价位
- **季节切换**：夏季峰平谷的时段定义与冬季可能不同
- **容量电费 / 力调电费**（v2.x 起）：按需求侧的最高用电功率或功率因数额外计费
- **生效期管理**：新电价从某天 0:00 起生效，旧电价历史可查

**典型用法**：
- 把电网公布的最新电价表录入系统
- 给账单引擎用：每张账单按当时生效的电价分时分价位汇总
- 给报表用：能耗趋势图叠加电费曲线，看哪个时段贵

### `ems-production` — 班次与产量

把生产维度数据接入能源平台，建立"能耗 vs. 产量"的关联：

- **班次定义**：工厂的早 / 中 / 晚班时段（可跨日，如夜班 22:00 - 次日 6:00）
- **班次实例**：每天 / 每条产线一条记录，记录该班次实际开始 / 结束时间
- **产量录入**：班次结束后录入实际产量（件 / kg / 吨等）
- **班次报表**：自动按班次窗口聚合能耗，得出"单位产量能耗"

**典型用法**：
- 横向对比：早班 vs. 夜班的单位产量能耗差异
- 纵向对比：本月 vs. 上月相同产量条件下的能耗
- 异常发现：某天单位产量能耗突增 → 设备老化、工艺漂移、产量录入错误

### `ems-floorplan` — 平面图

仪表的地理可视化层：

- **底图上传**：上传车间 CAD 转 PNG 或厂区平面图
- **仪表挂点**：把仪表拖到底图上对应的位置
- **实时态显示**：每个挂点实时显示读数（kW / kvarh / m³ / kg）+ 在线状态（绿 / 黄 / 红）
- **多张底图**：一个工厂可有多张图（一楼 / 二楼 / 厂区俯视图）

**典型用法**：
- 接待参观：一图直观展示工厂实时能耗分布
- 运维巡检：一眼看哪台设备掉线，红点定位到具体位置
- 培训新人：让新员工建立"哪些设备在哪、用多少电"的物理认知

---

## §5 分析与报表（2 个模块）

### `ems-dashboard` — 仪表盘（首页）

登录后的第一屏，KPI 概览：

- **当前总览**：今日累计用电、当前需求功率、采集健康在线率
- **能耗构成（饼图）**：按节点 / 按能源类型拆分占比
- **能耗趋势**：今日 / 本周 / 本月的曲线
- **能源强度**：单位产量能耗（如 kWh / 件）的近期趋势
- **Sankey 图**：能源从总进线到各车间各产线的流向（v2.x）
- **Top N 设备**：用电最多 / 故障最多 / 增幅最高的 Top 设备
- **平面图缩略图**：实时态嵌入

接口路径前缀 `/api/v1/dashboard`，端点包括 `/kpi`、`/energy-composition`、`/energy-intensity`、`/realtime-series`、`/sankey`、`/top-n`、`/tariff-distribution`、`/cost-distribution`、`/floorplan/{id}/live`、`/meter/{id}/detail`。

### `ems-report` — 报表

按周期生成的能耗报表，5 类预设：

- **日报**：单日 24 小时分时能耗 + 当日总量 + 同比环比
- **月报**：30 天日级能耗 + 月总量 + 分时电价分布
- **年报**：12 个月能耗趋势 + 年累计 + 同比
- **班次报**：按班次维度聚合，含单位产量能耗
- **导出（自定义）**：自由选节点 + 时段 + 粒度，导出 Excel / CSV

**特点**：
- 异步生成：长跨度报表后台跑，跑完通知 / 进列表
- 模板预设：开箱即用，不用每张表自己拼公式
- 节点聚合：选某个组织节点，自动汇总其下所有仪表
- 矩阵视图：行=日期 / 列=设备 / 单元=能耗，便于横向对比

---

## §6 财务（2 个模块）

### `ems-cost` — 成本分摊

把总能耗 / 总能耗费用按规则拆分到各个组织节点：

- **分摊规则**：按面积 / 按人头 / 按产量 / 自定义比例 / 多规则组合
- **试算（dry-run）**：保存规则前先试算，看每个节点拿多少，确认无误再正式跑
- **分摊运行（run）**：异步任务，每月一次，跑完产出"分摊明细"
- **明细审计**：每条明细记录"哪个节点 / 多少能耗 / 多少金额 / 用了哪条规则"，全程可追溯

**典型流程**：
1. 财务在"分摊规则"页面配置：总进线 → 一车间 60% / 二车间 40%
2. 月底点"试算"，看各节点拿多少
3. 确认无误，点"正式分摊"，系统异步跑
4. 跑完账单引擎读分摊结果，出账单

接口路径前缀 `/api/v1/cost`，端点包括 `/rules`、`/runs`、`/runs/{runId}`、`/runs/{runId}/lines`、`/dry-run-all`、`/rules/{ruleId}/dry-run`。

### `ems-billing` — 账单

按期次（月）出账：

- **期次（period）管理**：每月一个期次，期次有"开放 / 关闭 / 锁定"三态
- **账单生成**：基于电价 + 用量 + 分摊结果生成"该节点该月该付多少"
- **账单明细**：分时段（峰平谷）+ 分电价档位的细项
- **关账期 / 解锁**：关账后账单不可改，需要修改时由有权限角色解锁、留审计

**典型流程**：
1. 月初：财务"打开"上个月期次
2. 等分摊跑完，账单自动生成
3. 财务复核明细，没问题点"关账"
4. 如有错误，"解锁" → 调整 → "再关账"，每一步进审计

接口路径前缀 `/api/v1/bills`、`/api/v1/bills/periods`，端点包括 `/{id}`、`/{id}/lines`、`/{id}/lock`、`/{id}/unlock`、`/{id}/close`、`/{ym}`。

---

## §7 平台能力（3 个模块）

### `ems-auth` — 认证 / 授权

JWT 鉴权、登录、密码、4 个角色：

| 角色码 | 名称 | 范围 |
|---|---|---|
| `ADMIN` | 管理员 | 全部权限，含用户管理、采集配置、告警配置、关账解锁 |
| `OPERATOR` | 运维 | 看告警、确认 / 关闭告警、维护模式开关 |
| `FINANCE` | 财务 | 分摊规则、账单期次、关账 |
| `VIEWER` | 查看者 | 按节点权限读，无写操作 |

**节点级权限**：除 ADMIN 外，所有用户绑定到组织节点的子树，看不到子树外的数据。例：财务 A 绑定到"主厂区"，看不到"分厂区"的账单。

### `ems-audit` — 审计日志

记录敏感操作：

- **谁**：用户 ID + 用户名 + 角色
- **干了什么**：模块 + 动作 + 目标对象 ID
- **结果**：成功 / 失败 + 错误信息
- **何时**：精确到毫秒
- **改前 / 改后**：关键字段的旧值 / 新值快照

**触发点**：所有写操作（创建 / 更新 / 删除）+ 关键读操作（如下载账单、导出报表）+ 登录 / 登出。

**查询入口**：`/admin/audit` 页面，支持按模块、动作、用户、时间范围筛选。

### `ems-app` — 启动

Spring Boot 启动模块，把上述所有 `ems-*` 模块打包成单一 jar 启动。配置入口、Flyway 迁移、bean 装配都在这里。**面向用户没有功能**，但部署时只需启动这一个 jar。

---

## §8 典型业务流（端到端）

### 流程 A：上电就跑（首日 30 分钟）

1. **部署**：`docker compose up -d` 起栈
2. **登录**：admin / admin123!（首次登录强制改密）
3. **建组织**：进 `/orgtree`，建一棵简单的树（厂区 → 车间 → 产线）
4. **登记仪表**：进 `/meters`，登记 5~10 块仪表（基本属性 + 采集协议）
5. **配采集**：在采集配置文件里填上 PLC 地址 + 点位映射
6. **看仪表盘**：回到 `/dashboard`，看实时数据是否流入
7. **接告警**：进 `/alarms/webhook`，配企业微信 / 钉钉 Webhook 地址，做一次断线测试

### 流程 B：月底出账（财务）

1. **打开期次**：进 `/bills/periods`，把上月期次状态置为"开放"
2. **跑分摊**：进 `/cost/runs`，触发上月分摊运行，等异步完成
3. **生成账单**：在 `/bills` 列表看账单生成，逐张复核明细
4. **导出报表**：进 `/report/monthly`，导出月报 Excel 给客户
5. **关账**：账单复核完毕，回到 `/bills/periods` 点"关账"
6. **审计追溯**：如客户对某条明细有疑问，进 `/admin/audit` 按账单 ID 查改动历史

### 流程 C：处理一次断线（运维）

1. **收到告警**：钉钉 / 企业微信收到 Webhook 推送，或站内铃铛角标提醒
2. **看健康总览**：进 `/alarms/health`，定位是哪台设备
3. **现场处置**：到现场恢复网线 / 重启网关
4. **确认告警**：回到 `/alarms/history`，把对应告警标记"已确认"
5. **等自动恢复**：数据流恢复后，告警状态自动转"已解决"
6. **如计划停机**：提前进 `/meters` 给相关设备开维护模式，期间不出告警

### 流程 D：调整电价（管理员 + 财务协作）

1. **录入新电价**：管理员进 `/tariff`，填新分时电价表，设生效日期
2. **试算账单**：财务在 `/bills` 选某张草稿账单，预览新电价下的金额
3. **确认上线**：到生效日，新账单自动按新电价出
4. **审计存档**：电价变更进审计日志，下次出账时可追溯

---

## §9 模块矩阵速查

| 模块 | 类型 | 面向用户的入口 | 核心数据表 | 关键 API 前缀 |
|---|---|---|---|---|
| ems-core | 平台 | 无 | 无 | 无 |
| ems-orgtree | 业务 | `/orgtree` | `org_node` | `/api/v1/orgtree` |
| ems-meter | 业务 | `/meters` | `meters` | `/api/v1/meters` |
| ems-timeseries | 平台 | 无（被报表/dashboard 调用）| InfluxDB measurements | 无 |
| ems-collector | 采集 | `/collector` | 配置文件 + InfluxDB | `/api/v1/collector` |
| ems-alarm | 采集 | `/alarms/*` | `alarms`, `alarm_rules`, etc | `/api/v1/alarms` |
| ems-tariff | 业务 | `/tariff` | `tariffs` | `/api/v1/tariff` |
| ems-production | 业务 | `/production/*` | `shifts`, `production_records` | `/api/v1/production` |
| ems-floorplan | 业务 | `/floorplan/*` | `floorplans` | `/api/v1/floorplan` |
| ems-dashboard | 业务 | `/dashboard` | 无（聚合查询）| `/api/v1/dashboard` |
| ems-report | 业务 | `/report/*` | `report_jobs` | `/api/v1/report` |
| ems-cost | 财务 | `/cost/*` | `cost_allocation_rule/run/line` | `/api/v1/cost` |
| ems-billing | 财务 | `/bills/*` | `bill_period`, `bill`, `bill_line` | `/api/v1/bills` |
| ems-auth | 平台 | `/login`, `/profile`, `/admin/users/*` | `users`, `roles`, `user_roles` | `/api/v1/auth` |
| ems-audit | 平台 | `/admin/audit` | `audit_logs` | `/api/v1/audit` |
| ems-app | 启动 | 无 | 无 | 无 |

---

**相关文档**

- 产品介绍：[product-overview.md](./product-overview.md)
- 用户使用手册：[user-guide.md](./user-guide.md)

**模块功能概览**

- 组织树：[orgtree-feature-overview.md](./orgtree-feature-overview.md)
- 仪表与计量：[meter-feature-overview.md](./meter-feature-overview.md)
- 电价：[tariff-feature-overview.md](./tariff-feature-overview.md)
- 班次与产量：[production-feature-overview.md](./production-feature-overview.md)
- 平面图：[floorplan-feature-overview.md](./floorplan-feature-overview.md)
- 仪表盘：[dashboard-feature-overview.md](./dashboard-feature-overview.md)
- 报表：[report-feature-overview.md](./report-feature-overview.md)
- 成本分摊：[cost-feature-overview.md](./cost-feature-overview.md)
- 账单：[billing-feature-overview.md](./billing-feature-overview.md)
- 认证与审计：[auth-audit-feature-overview.md](./auth-audit-feature-overview.md)
- 平台底座（core + timeseries）：[platform-internals-overview.md](./platform-internals-overview.md)
- 告警：[alarm-feature-overview.md](./alarm-feature-overview.md)
- 可观测性：[observability-feature-overview.md](./observability-feature-overview.md)
- 采集协议：[collector-protocols-user-guide.md](./collector-protocols-user-guide.md)

**其它**

- 安装向导：[../install/installation-guide.md](../install/installation-guide.md)
- API 索引：[../api/README.md](../api/README.md)
- 部署运维：[../ops/deployment.md](../ops/deployment.md)
