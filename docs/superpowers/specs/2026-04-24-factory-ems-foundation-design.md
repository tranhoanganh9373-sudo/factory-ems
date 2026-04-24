# 通用工厂能源管理系统 · 子项目 1 · 地基 MVP 设计文档

- **Status**: Draft, approved in brainstorming, awaiting user review
- **Date**: 2026-04-24
- **Author**: Claude Code (brainstorming session)
- **Scope**: 子项目 1 —— 数据地基 + 实时看板 + 基础报表
- **Project root**: `C:\Users\kris\projects\factory-ems`

---

## 1. 背景与总体路线图

本项目要开发一套通用工厂的能源管理系统（EMS）。项目定位为 **MVP / 原型**，用于先跑通一个可独立上线并验证价值的版本。

### 1.1 项目总体分解

完整 EMS 包含多个独立子系统。本项目拆为 3 个按顺序迭代的子项目，每个子项目独立走完 spec → plan → implementation 周期：

| 子项目 | 内容 | 状态 |
|---|---|---|
| **子项目 1（本文档）** | 数据地基（多能源数据模型 + 采集适配）+ 实时看板 + 基础历史报表 | 本文档定义 |
| 子项目 2 | 分项计量 + 按组织架构的成本分摊 | 未开始 |
| 子项目 3 | 能效诊断（基线建模 + 异常识别 + 节能建议） | 未开始 |

### 1.2 子项目 1 的价值

- 具备完整的主数据基础：组织树、能源品类、测点、电价时段、班次、产量、计量拓扑、平面图
- 管理者能通过 9 面板看板看到工厂能耗全貌
- 可生成日 / 月 / 年 / 班次 / 自定义时段报表并导出 Excel / PDF / CSV
- 已建立权限体系，支持"一车间主任只看一车间数据"
- 完整支持后续子项目 2 / 3 的数据与架构扩展

---

## 2. 核心约束与前置条件

| 类别 | 决策 |
|---|---|
| 数据来源 | 外部采集软件直接写入 **InfluxDB 2.x**，本系统只读 |
| 测点规模 | 约 1000 个测点 |
| 采集频率 | 1 分钟粒度 |
| 能源品类 | 电（kWh）、水（m³）、蒸汽（t） |
| 组织层级 | 自定义树（深度不固定） |
| 用户模型 | 多用户 + 组织树节点级细粒度权限 |
| 后端 | Java 21 + Spring Boot 3.x（主服务） + Python 3.11 + FastAPI（分析侧车，MVP 阶段占位不启动） |
| 前端 | React 18 + Vite + TypeScript + Ant Design 5 + ECharts 5 + Zustand + TanStack Query |
| 关系库 | PostgreSQL 15 |
| 部署 | 单机 Docker Compose |
| 班次 | 全工厂统一 |
| 审计 | 登录事件 + 所有写操作 |
| 语言 | 仅中文 |
| 数据保留 | InfluxDB 原始 1min 数据 1 年；PostgreSQL 预聚合数据永久 |
| 浏览器 | 现代浏览器（Chrome / Edge 近 2 年版本） |

### 2.1 明确不在子项目 1 范围内

- 工业协议采集（Modbus / OPC-UA / IEC-104 等）—— 外部采集软件已解决
- 告警 / 短信 / 邮件通知
- 成本分摊与账单（子项目 2）
- 能效诊断与节能建议（子项目 3）
- MES / ERP 对接（产量采用人工填报）
- 多工厂 / 多租户
- SSO / LDAP / AD 对接
- 2FA / 验证码 / 图形码
- IE11 兼容
- 视觉回归测试、压测、混沌测试

---

## 3. 架构总览

### 3.1 推荐方案：模块化单体（Modular Monolith）

Maven 多模块 Spring Boot 应用，按领域划分为 12 个模块，编译期约束模块间依赖方向。模块清单和依赖关系见 §6。

**决策理由**：
- 11+ 个清晰的领域边界，用 Maven 模块在编译期钉死比靠 code review 约束可靠
- 单体部署契合单机 Docker Compose 的运维水平
- 未来某模块成为瓶颈时可单独抽为微服务，边界已划好
- Python 分析侧车作为独立容器接入，保持语言边界

### 3.2 部署拓扑

```
┌──────────────────────────────────────────────────────────────┐
│  Docker Host (单机)                                            │
│                                                                │
│  ┌───────────┐   ┌──────────────────┐   ┌─────────────────┐  │
│  │  Nginx    │◄──┤ 用户浏览器        │   │  PostgreSQL 15  │  │
│  │ :80/:443  │   │ (React SPA)      │   │  :5432          │  │
│  │ 静态+反代  │   └──────────────────┘   │  主数据/聚合/审计│  │
│  └─────┬─────┘                          └────────▲────────┘  │
│        │  /api/*                                 │            │
│        ▼                                         │            │
│  ┌────────────────────┐                          │            │
│  │  Spring Boot 3.x   │──────────────────────────┘            │
│  │  factory-ems :8080 │──────┐                                │
│  └────────────────────┘      │                                │
│                               │ HTTP                           │
│                               ▼                                │
│  ┌────────────────┐   ┌──────────────────┐                   │
│  │ Python 侧车      │   │ InfluxDB 2.x     │                   │
│  │ FastAPI :8001   │   │ :8086 时序数据    │                   │
│  │ (MVP 占位)      │   └──────────────────┘                   │
│  └────────────────┘                                           │
│                                                                │
│  外部采集软件（不在本系统内） → 写入 InfluxDB                  │
└──────────────────────────────────────────────────────────────┘
```

### 3.3 容器清单

| 容器 | 镜像 | 端口 | 用途 |
|---|---|---|---|
| `nginx` | nginx:alpine | 80 / 443 | 静态资源 + 反向代理 + 平面图直出 |
| `factory-ems` | 自建（JDK 21 + Spring Boot） | 8080 | 主业务服务 |
| `factory-ems-analytics` | 自建（Python 3.11 + FastAPI） | 8001 | 分析侧车（MVP 不启动，占位） |
| `postgres` | postgres:15 | 5432 | 主数据 + 预聚合 + 审计 |
| `influxdb` | influxdb:2.7 | 8086 | 时序数据（外部写入） |

---

## 4. 数据模型

### 4.1 PostgreSQL 主数据表

**认证与权限（ems-auth）**

```
users              (id, username, password_hash, display_name, enabled,
                    created_at, last_login_at)
roles              (id, code, name, description)                    -- ADMIN, VIEWER
user_roles         (user_id, role_id)
node_permissions   (id, user_id, org_node_id,
                    scope  -- SUBTREE | NODE_ONLY
                   )
refresh_tokens     (jti PK, user_id, issued_at, expires_at, revoked_at)
```

**组织树（ems-orgtree）**

```
org_nodes          (id, parent_id, name, code, node_type,
                    sort_order, created_at)
org_node_closure   (ancestor_id, descendant_id, depth)  -- 闭包表
```

选用闭包表而非邻接表的理由：权限过滤和子树聚合查询使用 `WHERE ancestor_id = ?` 一句 SQL，性能可预测；邻接表的递归 CTE 在深树场景下性能差且难优化。

**能源品类与测点（ems-meter）**

```
energy_types       (id, code, name, unit)
                   -- 初始化 3 条：ELEC/WATER/STEAM
meters             (id, code, name, energy_type_id, org_node_id,
                    influx_measurement, influx_tag_key, influx_tag_value,
                    enabled, created_at)
meter_topology     (child_meter_id PK, parent_meter_id)  -- Sankey 用
```

**电价分时（ems-tariff）**

```
tariff_plans       (id, name, effective_from, effective_to, is_active)
tariff_periods     (id, plan_id, period_type,   -- SHARP|PEAK|FLAT|VALLEY
                    time_start, time_end, price_per_kwh)
```

**班次与产量（ems-production）**

```
shifts             (id, code, name, time_start, time_end,
                    cross_midnight BOOL)
production_entries (id, org_node_id, shift_id, entry_date,
                    product_code, quantity, unit, entered_by, entered_at)
```

**平面图（ems-floorplan）**

```
floorplans         (id, org_node_id, image_path, width_px, height_px,
                    uploaded_by, uploaded_at)
floorplan_points   (id, floorplan_id, meter_id, x_px, y_px, label)
```

**预聚合（ems-timeseries）**

```
ts_rollup_hourly   (meter_id, org_node_id, hour_ts,
                    sum_value, avg_value, max_value, min_value, count,
                    PRIMARY KEY (meter_id, hour_ts))
ts_rollup_daily    (meter_id, org_node_id, day_date,
                    sum_value, avg_value, max_value, min_value, count,
                    PRIMARY KEY (meter_id, day_date))
ts_rollup_monthly  (meter_id, org_node_id, year_month,
                    sum_value, avg_value, max_value, min_value, count,
                    PRIMARY KEY (meter_id, year_month))
```

冗余 `org_node_id` 字段是为了避免每次报表 join `meters` 表。`meters.org_node_id` 修改时需同步这三张表（触发器或应用层事务）。

**审计（ems-audit）**

```
audit_logs         (id, actor_user_id, actor_username,
                    action,          -- LOGIN|LOGOUT|CREATE|UPDATE|DELETE|CONFIG_CHANGE
                    resource_type,
                    resource_id,
                    summary TEXT,
                    detail JSONB,    -- {before, after, diff}
                    ip, user_agent, occurred_at)
```

### 4.2 InfluxDB 2.x 数据结构（与外部采集软件的契约）

```
bucket: factory_ems
measurement: energy_reading
tags:
  meter_code    = "ELEC-WS1-LINE1-M03"   -- 对应 meters.influx_tag_value
  energy_type   = "ELEC" | "WATER" | "STEAM"
fields:
  value         (float)                   -- 当次读数
  unit          (string, 可选)
timestamp: 1 分钟粒度
```

`meters.influx_tag_value` 字段定位一个测点在 InfluxDB 中的唯一 tag 值。Spring Boot 查询时据此构造 Flux。

### 4.3 数据保留策略

| 存储 | 粒度 | 保留期 | 实现 |
|---|---|---|---|
| InfluxDB 原始 | 1min | 1 年 | bucket retention |
| PostgreSQL hourly rollup | 1h | 永久 | 无过期 |
| PostgreSQL daily rollup | 1d | 永久 | 无过期 |
| PostgreSQL monthly rollup | 1mo | 永久 | 无过期 |
| audit_logs | 事件级 | 2 年 | 定时归档到冷存储 |

---

## 5. 模块清单与职责

### 5.1 Maven 模块依赖关系

```
ems-app (Spring Boot 启动、全局配置、异常处理、调度注册)
  │
  ├── ems-dashboard    (看板 API)
  ├── ems-report       (报表生成 + 导出)
  │
  ├── ems-floorplan    (平面图 + 点位)
  ├── ems-production   (产量 + 班次)
  ├── ems-tariff       (电价分时)
  ├── ems-meter        (能源品类 / 测点 / 计量拓扑)
  ├── ems-orgtree      (组织树 + 闭包维护)
  ├── ems-auth         (用户 / 角色 / 节点权限)
  │
  ├── ems-timeseries   (InfluxDB 适配 + 预聚合 Job)
  ├── ems-audit        (审计切面 + 日志写入)
  └── ems-core         (公共 DTO / 异常 / 工具)
```

### 5.2 模块职责表

| 模块 | 职责 | 对外接口 | 依赖 |
|---|---|---|---|
| ems-core | 通用 Result/Page DTO、错误码、工具类、Jackson 配置 | 公共基础类 | — |
| ems-audit | AOP 监听写操作和登录；异步写 `audit_logs` | `@Audited` 注解、`AuditService` | core |
| ems-timeseries | InfluxDB 查询封装；rollup Job；Python 侧车 HTTP 契约预留 | `TimeSeriesQueryService`、`RollupService` | core |
| ems-auth | 登录 / JWT / 角色 / 节点权限；`PermissionResolver` | `AuthService`、`PermissionResolver`、`@RequireNode` | core、audit |
| ems-orgtree | 组织树 CRUD；闭包维护；子树 / 祖先查询 | `OrgNodeService` | core、auth、audit |
| ems-meter | 能源品类字典；测点 CRUD；计量拓扑 | `MeterService`、`MeterTopologyService` | core、auth、orgtree、audit |
| ems-tariff | 电价方案 + 分时规则；`resolvePrice(at)` | `TariffService` | core、auth、audit |
| ems-production | 班次字典；产量填报；按时段 / 节点汇总 | `ShiftService`、`ProductionService` | core、auth、orgtree、audit |
| ems-floorplan | 平面图上传；点位 CRUD；读取时返回图片 URL + 坐标 + 实时值 | `FloorplanService` | core、auth、orgtree、meter、audit |
| ems-dashboard | 编排 9 个面板的数据；纯读；按当前用户权限过滤 | REST `/api/v1/dashboard/**` | 所有领域模块 + timeseries |
| ems-report | 报表查询（日 / 月 / 年 / 班次 / 自定义）；Excel / PDF / CSV 导出 | REST `/api/v1/reports/**` | 所有领域模块 + timeseries |
| ems-app | 启动类 / 配置 / CORS / 全局异常 / 定时任务 bean | — | 全部 |

### 5.3 跨模块关键协作点

**权限收口**：`PermissionResolver.visibleNodeIds(userId)` 是唯一权限判断点。`ems-dashboard` / `ems-report` 所有查询前调用，结果作为 `WHERE org_node_id IN (...)`。其他模块不做权限判断。

**审计切入**：`@Audited(resourceType, action)` 切面注解，异步 `AFTER_COMMIT` 入库，审计失败不回滚业务。

**InfluxDB 访问隔离**：只有 `ems-timeseries` 可直接调用 InfluxDB Client。其他模块需时序数据必须走 `TimeSeriesQueryService`。将来换时序库（TDengine / TimescaleDB）只改一个模块。

---

## 6. 数据流

### 6.1 时序读路径

`TimeSeriesQueryService.query(meterIds, range, granularity)`：

```
granularity=MINUTE 且 range ≤ 24h  → 直查 InfluxDB（Flux）
granularity=HOUR                    → 已 rollup 部分查 ts_rollup_hourly
                                       当前未 rollup 小时查 InfluxDB 现算
                                       合并返回
granularity=DAY                     → 已 rollup 部分查 ts_rollup_daily
                                       当日未 rollup 部分查 InfluxDB 现算
granularity=MONTH                   → 类似，当月部分现算
```

混合查询原因：用户永远在看"现在"，若只查已 rollup 区间会让当前桶空白。

### 6.2 主数据写路径

```
Browser → POST /api/v1/meters {...}
  → Controller
     → @PreAuthorize("hasRole('ADMIN')")
     → Service.create(dto)
          ┌── @Valid (DTO 字段校验)
          ├── 业务校验（编码唯一、外键存在）
          ├── TRANSACTION BEGIN
          ├── @Audited 切面包裹
          │    └── AFTER_COMMIT 异步 publish AuditEvent
          └── TRANSACTION COMMIT
     ← AuditListener 写 audit_logs（独立事务）
  ← 201 Created
```

### 6.3 组织树移动（特殊处理）

```
OrgNodeService.move(nodeId, newParentId) 在单事务内：
  1. 校验 newParent 不是 node 后代（防环）
  2. SELECT ... FOR UPDATE 锁受影响闭包行（防并发破坏）
  3. 删除 closure 中 node 子树对旧祖先的所有记录
  4. 插入 closure 中 node 子树对 newParent 所有祖先的新记录
  5. UPDATE org_nodes.parent_id
```

禁止模块外直接写 `org_node_closure`。

### 6.4 预聚合流水线

```
RollupScheduler (Spring @Scheduled)
  ├── HourlyRollupJob   每小时 :05  查 InfluxDB 上一小时 → upsert ts_rollup_hourly
  ├── DailyRollupJob    每日 00:10  读 hourly 累加       → upsert ts_rollup_daily
  └── MonthlyRollupJob  每月 1 日 00:30 读 daily 累加     → upsert ts_rollup_monthly
```

所有 upsert 用 `ON CONFLICT (..) DO UPDATE` 保证幂等；失败写 `rollup_job_failures` 指数退避重试（5min→30min→2h），3 次失败停止自动重试并告警。

补跑 API：`POST /api/v1/ops/rollup/rebuild?granularity=HOURLY&from=...&to=...`，仅 ADMIN。

### 6.5 平面图文件流

```
上传：POST /api/v1/floorplans (multipart)
     → 校验类型（png/jpg/svg）+ 大小（≤10MB）
     → 存 /data/ems_uploads/floorplans/{yyyy}/{MM}/{uuid}.{ext}
     → INSERT floorplans (image_path 存相对路径)

读取：GET /api/v1/floorplans/{id}/image
     → Nginx 拦截，直接从挂载卷返回文件（不经 Spring Boot）
```

**已知权衡**：Nginx 直出无法做精细权限控制。MVP 假设平面图可被所有登录用户查看。严格权限留后续版本。

---

## 7. REST API 与前端结构

### 7.1 API 约定

- 基础路径：`/api/v1`
- 响应结构：`{code, data, message}`，成功 `code=0`
- 分页：`?page=1&size=20&sort=createdAt,desc`，响应 `{items, total, page, size}`
- 时间：ISO-8601 带时区；日期：`yyyy-MM-dd`
- 数值敏感字段（价格）用字符串传递避免精度丢失
- 错误码 5 位：`00xxx` 通用 / `40xxx` 认证权限 / `10xxx` orgtree / `11xxx` meter / `12xxx` tariff ...

### 7.2 MVP 核心端点

```
认证
  POST /api/v1/auth/login, /refresh, /logout
  GET  /api/v1/auth/me

用户 & 权限（ADMIN）
  CRUD /api/v1/users
  GET  /api/v1/roles
  POST /api/v1/users/{id}/node-permissions

组织树
  GET    /api/v1/org-nodes/tree
  CRUD   /api/v1/org-nodes
  PATCH  /api/v1/org-nodes/{id}/move

测点
  GET   /api/v1/energy-types
  CRUD  /api/v1/meters
  CRUD  /api/v1/meter-topology

电价
  CRUD  /api/v1/tariff-plans
  CRUD  /api/v1/tariff-plans/{planId}/periods

班次 & 产量
  CRUD  /api/v1/shifts
  CRUD  /api/v1/production-entries

平面图
  CRUD  /api/v1/floorplans
  CRUD  /api/v1/floorplans/{id}/points
  GET   /api/v1/floorplans/{id}/image

看板（纯读，权限过滤）
  GET /api/v1/dashboard/kpi                   ① KPI 卡
  GET /api/v1/dashboard/realtime-series       ② 24h 曲线
  GET /api/v1/dashboard/top-n                 ⑤ Top-N 排行
  GET /api/v1/dashboard/tariff-distribution   ⑥ 尖峰平谷
  GET /api/v1/dashboard/energy-intensity      ⑦ 单位产量能耗
  GET /api/v1/dashboard/sankey                ⑧ 能流 Sankey
  GET /api/v1/dashboard/floorplan/{id}/live   ⑨ 平面图+实时值
  GET /api/v1/dashboard/meter/{id}/detail     ④ 测点详情页

报表
  POST /api/v1/reports/query          返回 JSON
  POST /api/v1/reports/export         返回 fileToken
  GET  /api/v1/reports/export/{token} 下载文件

运维
  POST /api/v1/ops/rollup/rebuild
  GET  /api/v1/ops/health
  GET  /api/v1/audit-logs
```

所有看板查询的公共参数：`orgNodeId?`、`energyType?`、`range=TODAY|YESTERDAY|THIS_MONTH|CUSTOM`、`from?`、`to?`。

### 7.3 前端目录结构

```
frontend/
├── src/
│   ├── api/             axios 实例 + 各模块封装
│   ├── stores/          Zustand (authStore, appStore)
│   ├── layouts/         AppLayout
│   ├── pages/           login / dashboard / orgtree / meter / tariff /
│   │                    production / floorplan / report / audit / admin
│   ├── components/      OrgTreeSelector / EnergyTypeTabs / DateRangePicker /
│   │                    ProtectedRoute / PermissionGate
│   ├── hooks/           usePermissions
│   ├── utils/
│   └── styles/
├── public/
├── vite.config.ts
└── package.json
```

### 7.4 关键前端交互

- **全局组织树筛选器**：常驻左侧；点击节点更新 `appStore.currentOrgNodeId`；所有看板 / 报表页面订阅该值自动刷新
- **看板刷新**：30 秒轮询（TanStack Query `refetchInterval`），页面不可见时停止，筛选条件变化立即取数，不用 WebSocket
- **平面图编辑器**：`react-konva` Canvas 实现底图 + 可拖拽测点图钉；查看模式按阈值染色，编辑模式可增删改
- **报表导出**：异步模式，提交返回 fileToken，前端轮询下载

---

## 8. 安全与权限

### 8.1 认证

JWT 双令牌：

| 令牌 | 存储 | 有效期 | 用途 |
|---|---|---|---|
| Access Token | 前端内存（Zustand） | 15 分钟 | `Authorization: Bearer` |
| Refresh Token | HttpOnly Cookie `SameSite=Lax` | 7 天 | `/auth/refresh` 换新，服务器可吊销 |

- 密码 BCrypt cost=12；最少 8 位；非纯数字；连续 5 次错误锁 15 分钟
- Refresh Token 单次使用 + 轮换（rotate）
- 登出吊销当前 Refresh Token

### 8.2 授权两层

**第 1 层 · 角色粗粒度**（`@PreAuthorize`）：MVP 定义 2 个角色
- `ROLE_ADMIN`：全部配置类写操作 + 查看所有节点
- `ROLE_VIEWER`：只读，按节点权限过滤

**第 2 层 · 节点细粒度**（`PermissionResolver`）：

```
visibleNodeIds(userId) =
    SELECT DISTINCT c.descendant_id
    FROM node_permissions np
    JOIN org_node_closure c ON c.ancestor_id = np.org_node_id
    WHERE np.user_id = :userId
      AND (np.scope = 'SUBTREE' OR c.depth = 0)
```

ADMIN 走旁路返回 `ALL_NODE_IDS_MARKER` 哨兵（`-1L`），查询时不加 IN 过滤避免扫大 IN。

### 8.3 强制过滤位置

所有看板 / 报表查询必须在 **Controller 层**调 `visibleNodeIds`，作为参数传入 Service。Service 保持对登录态无感，可被定时任务 / Python 侧车无权限上下文调用。

### 8.4 防绕过

单测点访问用统一切面 `@RequireMeterAccess("#meterId")`，SpEL 表达式从参数取出 meterId 校验 `canAccessMeter`。

### 8.5 CSRF / CORS / Headers

- CSRF：关闭（JWT Bearer 天然免疫）
- CORS：内网同域，关闭；开发环境 profile 开特定 origin
- 安全头（Nginx）：`X-Frame-Options: DENY`、`X-Content-Type-Options: nosniff`、CSP、HSTS（HTTPS 时）

### 8.6 审计

- 切面触发：`@Audited` 写操作、`/auth/login`、`/auth/logout`、写请求 4xx/5xx 失败
- `detail` JSONB：`{before, after, diff}`
- 敏感字段（password_hash、refresh_token、price）脱敏
- 审计查询按 actor / resourceType / action / 时段 / IP 筛选，仅 ADMIN

### 8.7 密钥管理

`EMS_JWT_SECRET`、`EMS_DB_PASSWORD`、`EMS_INFLUX_TOKEN` 等全部通过环境变量注入，源代码和配置文件禁止明文。生产用 `.env` + Docker Compose `env_file`，严禁 commit。

### 8.8 MVP 明确不做

验证码、2FA、SSO、操作 IP 白名单、设备管理 / 强踢。

---

## 9. 错误处理与可观测性

### 9.1 业务异常分类

| 异常类 | HTTP | 用途 | 日志级别 |
|---|---|---|---|
| `BusinessException` | 400 | 业务规则违反 | INFO |
| `NotFoundException` | 404 | 资源不存在 | INFO |
| `ForbiddenException` | 403 | 权限不足 | WARN |
| `UnauthorizedException` | 401 | 未登录 / token 失效 | DEBUG |
| `ConflictException` | 409 | 并发冲突 | WARN |
| 未分类 `RuntimeException` | 500 | Bug | ERROR + 堆栈 + 告警 |

### 9.2 全局异常处理

`@RestControllerAdvice` 映射上表；所有错误响应带 `traceId`；前端报错 UI 展示"错误码 + traceId"。

### 9.3 数据一致性

- Service 默认 `@Transactional(rollbackFor = Exception.class)`
- 审计 / 异步通知用 `@TransactionalEventListener(AFTER_COMMIT)`
- Controller 不开事务
- 实体带 `@Version` 乐观锁，冲突抛 `ConflictException`
- 唯一约束由数据库保证，违反时捕获转 `BusinessException`

### 9.4 结构化日志

JSON 格式输出到 stdout，字段至少：`ts, level, logger, traceId, spanId, userId, msg, 业务字段`。`TraceIdFilter` 生成 / 透传 traceId（支持 `X-Trace-Id` 头），放入 MDC。线程池任务手动透传。

### 9.5 Prometheus 指标

Actuator + Micrometer 暴露 `/actuator/prometheus`：

- `ems_http_requests_total{status,uri}`
- `ems_http_request_duration{uri}`
- `ems_rollup_job_duration{granularity}`
- `ems_rollup_job_failures_total`
- `ems_influxdb_query_duration`
- `ems_active_users`
- `ems_audit_write_lag`
- JVM / HikariCP / Tomcat 默认指标

### 9.6 健康检查

- `/actuator/health/liveness`：进程在线
- `/actuator/health/readiness`：PG + InfluxDB 可连
- 仅内网访问（Nginx 限 IP）

### 9.7 告警规则（外部监控系统执行）

- 5xx 错误率 > 1% 持续 5 分钟
- `rollup_job_failures_total` 增速 > 0
- p95 响应时延 > 2s
- JVM old gen > 85%
- PG / InfluxDB 连接失败

### 9.8 前端错误处理

- axios 响应拦截器按错误码分派（401 跳登录、403 弹权限不足、5xx 弹 Modal 带 traceId、网络异常弹重试）
- React Error Boundary 包裹 AppLayout 降级显示
- `navigator.onLine` 断网检测顶部红条

---

## 10. 测试策略

### 10.1 覆盖率目标

- 后端整体行覆盖率 ≥ 70%
- 核心模块（auth / timeseries / orgtree）≥ 80%
- CI 门槛：dashboard / report 覆盖率下降超 5pp 不允许合入

### 10.2 分层

**单元（~75%）**：JUnit 5 + Mockito。覆盖 `PermissionResolver`、`OrgNodeService.move()`、`TimeSeriesQueryService` 三路分派、`TariffService.resolvePrice` 时段命中、rollup 幂等逻辑。

**集成（~20%）**：Spring Boot Test + Testcontainers（PostgreSQL + InfluxDB 真容器）。覆盖每个 Controller 端到端、闭包表真实一致性、rollup 端到端、权限过滤真 SQL、审计事务边界、报表导出内容。

**E2E（~5%）**：Playwright。6 个保命用例：
1. 登录 → 看板 → 登出
2. ADMIN 建组织树 → 建测点 → 看板出现
3. 授 VIEWER 节点权限 → 只看子树
4. 导出月报 Excel → 下载非空
5. 上传平面图 → 拖图钉 → 查看模式看实时值
6. Token 过期 → 自动刷新 → 请求继续成功

### 10.3 合同测试

`InfluxSchemaContractTest`：启空 InfluxDB → 写约定数据 → 调 TimeSeriesQueryService → 断言。用于在 CI 中第一时间发现外部采集 schema 漂移。

### 10.4 前端测试

Vitest + RTL + MSW：覆盖 store 行为、工具函数、复用组件交互。不测 Ant Design 自身行为和 ECharts 配置对象。

### 10.5 测试数据

- 单元：纯内存
- 集成：每方法开事务 + 回滚，或 `@Sql` seed；容器销毁
- 本地 / E2E：`docker-compose.dev.yml` 带 `ems-seed` 容器；`seed/influx-seeder.py` 按正弦 + 噪声生成符合作息的模拟数据

### 10.6 非功能（MVP 只做基础验证）

- 50 并发用户刷新看板：p95 < 1s，p99 < 2s
- 1000 测点 × 1 年月报查询 < 5s
- 一次性跑 k6 / JMeter 验证，不集成 CI
- 压测 / 混沌 / 长稳：**不在 MVP 范围**

### 10.7 CI 流水线

```
lint  →  unit  →  integration  →  build images  →  e2e  →  publish (main)
≤2min   ≤3min     ≤8min            ≤5min           ≤10min
```

合入条件：全绿 + 覆盖率达标 + ≥1 人 approve + 无未解决 review comment。

### 10.8 TDD

采用 superpowers `test-driven-development` 方法论：先写失败测试（红）→ 最小实现（绿）→ 重构。每个新特性首个 commit 只含红测试和接口骨架。

---

## 11. 部署与运维

### 11.1 宿主机目录结构

```
/opt/factory-ems/
├── docker-compose.yml
├── .env                        (600 权限，不入 git)
├── nginx/
│   ├── nginx.conf
│   ├── conf.d/factory-ems.conf
│   └── ssl/
├── data/
│   ├── postgres/
│   ├── influxdb/
│   └── ems_uploads/
├── logs/
│   ├── ems/
│   └── nginx/
└── backup/
```

### 11.2 Docker Compose 关键点

- `nginx`：反代 + 静态 + 平面图直出（挂载 `ems_uploads` 只读）
- `factory-ems`：主服务，`env_file: .env`，healthcheck `/actuator/health/liveness`，`restart: unless-stopped`
- `factory-ems-analytics`：`profiles: ["analytics"]`，MVP 默认不启动
- `postgres`：persistent volume `data/postgres`
- `influxdb`：retention 配置为 8760h（1 年）
- `frontend_dist` volume：前端构建容器写入，nginx 只读消费

### 11.3 前端构建分发

多阶段 Dockerfile：
1. `node:20` 安装 + 构建
2. 小镜像 COPY `dist/`
首次 `up` 时写入 `frontend_dist` volume，nginx 消费。前端版本独立迭代不重建 nginx 镜像。

### 11.4 初始化流程

```bash
cp .env.example .env && vi .env       # 填强密码和 JWT secret
docker compose up -d postgres influxdb
# Flyway 在 factory-ems 启动时自动跑 spring.flyway.enabled=true
docker compose run --rm factory-ems java -jar app.jar --init-admin ...
docker compose up -d factory-ems nginx
curl http://localhost/actuator/health
```

### 11.5 数据库迁移

Flyway 管理：`ems-app/src/main/resources/db/migration/V*__*.sql`。规则：
- 生产环境只追加，禁止修改已发布脚本
- 破坏性变更两步走：新增 → 数据迁移 → 下版本删旧

### 11.6 备份与恢复

- 宿主机 cron 每日 02:00 备份 PG / InfluxDB / uploads，保留 30 天
- 异地副本 rsync 到另一机器或对象存储（手工配置，不内置）
- 季度恢复演练到备用环境（流程文档化，不自动）

### 11.7 版本发布

- PR 合 main → CI 通过 → 自动 tag + 构建镜像
- 运维：`docker compose pull && docker compose up -d`
- 回滚：修 `.env` 的 `EMS_VERSION` → `down` → `up`
- 数据库迁移不自动回滚，破坏性 DDL 必须"可回退"两步法

### 11.8 运维手册要点

`docs/ops/README.md` 应涵盖：

1. 常见故障排查（InfluxDB 连接失败 / PG 连接池满 / 磁盘写满）
2. rollup 补跑方法
3. 重置用户密码（SQL + bcrypt 工具）
4. 增加新能源品类步骤（SQL + 前端配置）
5. 日志位置与轮转（Logback SizeAndTimeBasedRollingPolicy，100MB 或每日切，保留 30 天）
6. 证书更新（certbot 60 天）

### 11.9 规模上限与扩容路径

**当前单机架构的吃紧阈值**：

- 测点 > 1 万：rollup 单线程跑不完一小时窗口
- 并发用户 > 200：PG 连接池 / Tomcat 线程需要调
- 报表跨度 > 5 年：monthly 量大需加索引
- 审计日志 > 1 亿行：需要按月分区

**扩容方向（子项目 1 不做，但架构不拒绝）**：
- rollup 抽独立服务多 worker 并行
- 引入 Redis 缓存热点看板数据
- PG 读写分离 + `ts_rollup_*` 按月分区
- 报表服务单独扩容
- 审计迁移 ClickHouse / ES

---

## 12. 模块工作量心理预期

子项目 1 模块规模（非承诺，仅供规划参考）：

| 工作项 | 估算 |
|---|---|
| 后端 11 个 Maven 模块（含 Flyway / 预聚合 / 审计 / 权限） | 核心工作量 |
| 前端 9 面板看板 + 7 个配置管理页 + 平面图编辑器 | 等同后端 |
| E2E + 集成测试 + seed 数据 | 15-20% 后端 |

**参考估工期**：1 个有经验工程师全职 2-3 个月，或 2-3 人团队 4-6 周。

---

## 13. 决策记录汇总

| 决策 | 选择 | 理由 |
|---|---|---|
| 架构风格 | 模块化单体 | 清晰边界 + 低运维 + 可演化 |
| 时序库 | InfluxDB 2.x（已有） | 由外部采集软件已决定 |
| 关系库 | PostgreSQL 15 | JSONB、时间函数、闭包表友好 |
| 后端 | Java 21 + Spring Boot 3.x | 工厂场景主流，生态成熟 |
| 数据分析 | Python 侧车（MVP 占位） | 子项目 3 再接入 |
| 前端 | React 18 + Ant Design + ECharts | 团队偏好 + 工业看板标配 |
| 组织树 | 闭包表 | 子树 / 祖先查询 O(1) |
| 预聚合落地 | PostgreSQL 分区表 | 报表快，运维简单，无需 TimescaleDB |
| 权限模型 | JWT + 角色 + 节点子树权限 | 满足"车间主任只看本车间" |
| 看板刷新 | 30s 轮询 | 规模下足够，复杂度低 |
| 平面图下发 | Nginx 直出 | 减轻后端，接受"所有登录用户可看"的 MVP 边界 |

---

## 14. 后续动作

1. 本文档待用户审阅通过
2. 通过后进入 `writing-plans` 技能，产出详细实现计划（按模块拆任务、定依赖、估时）
3. 实现阶段遵循 TDD

---

## 附录 A · 术语表

- **Rollup**：预聚合。把高频原始数据按小时 / 日 / 月汇总存入关系库，加速历史报表查询。
- **闭包表（Closure Table）**：用一张 `(ancestor, descendant, depth)` 表展开树的所有祖先-后代对，换得 O(1) 子树查询。
- **PermissionResolver**：权限解析器。系统内唯一的"当前用户能看哪些组织节点"的判定入口。
- **Flux**：InfluxDB 2.x 的函数式查询语言。
- **tariff period**：电价分时时段（尖 / 峰 / 平 / 谷）。
- **sharp / peak / flat / valley**：尖 / 峰 / 平 / 谷（电力分时电价四档）。
