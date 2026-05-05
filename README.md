# Factory EMS

> **工厂能源管理一体化平台** — 私有部署、按业务语义运转、内置电价 / 分摊 / 报警 / 报表。
> 适用版本：v1.7.0+ ｜ 最近更新：2026-05-01

把现场仪表 / PLC / 网关的数据采集起来，做成跨车间的统一视图、自动报表、内部分摊账单和断线报警，无需自建监控基础设施、无需拼装多套开源工具。

---

## 谁该用它

- **制造工厂 / 工业园区**：年用电 500 万 kWh+，多车间，需要统一能源视图与内部分摊
- **能源服务公司（ESCO）/ 节能改造方**：服务多个客户场地，需要数据完整性证明 + 自动月报
- **园区运营 / 物业**：按租户独立计量与计费
- **碳核查 / 双控合规企业**：数据可追溯、按工序聚合、应对突击核查

详见 [`docs/product/product-overview.md`](./docs/product/product-overview.md)。

---

## 核心能力（一图）

```
   现场仪表 / PLC / 网关
            │
            ▼
   ① 采、缓冲、续传   collector / alarm
            │
            ▼
   ② 看（实时 + 报表）dashboard / report / floorplan
            │
            ▼
   ③ 算（电价 + 分摊）tariff / cost / billing
            │
            ▼
   ④ 管（权限 + 审计）auth / admin / audit / observability
```

- **多协议采集**：OPC UA / Modbus TCP / MQTT 内置 + 缓冲落盘 + 断线续传
- **复杂电价**：分时 / 阶梯 / 容量电价规则化沉淀
- **成本分摊引擎**：按面积 / 人头 / 产量，规则化、可试算、留审计
- **报表与平面图**：日 / 月 / 年 / 班次报表 + CAD 底图实时态
- **采集中断报警 + Webhook**：HMAC 签名，外推钉钉 / 企微 / ITSM
- **可观测性栈**：17 指标 + 4 SLO + 7 Grafana 看板
- **细粒度 RBAC**：4 角色 + 节点级权限隔离 + 全程审计

详见 [`docs/product/feature-overview.md`](./docs/product/feature-overview.md)。

---

## 30 秒决定要不要继续读

**是 EMS**：
- 私有部署，数据完全本地化
- 业务语义贴近能源管理（电价 / 分摊 / 班次 / 维护模式都开箱即用）
- 单 compose 起栈（Postgres + InfluxDB + 后端 + 前端 + nginx）

**不是 EMS**：
- 不替代 SCADA / DCS / PLC（只读不控）
- 不是 MES / ERP（不管工单 / 物料 / 订单）
- 不是通用 BI（报表针对能源场景预设）
- 不内置短信 / 邮件 / IM 直连（仅 Webhook）

详见 [`docs/product/product-overview.md` §5](./docs/product/product-overview.md)。

---

## 快速启动

### 开发模式（10 分钟）

```bash
# 1. 准备：JDK 21、Maven 3.9+、Node 20+ & pnpm 9+、Docker Desktop

# 2. 起依赖（Postgres + InfluxDB）
docker compose -f docker-compose.dev.yml up -d

# 3. 后端
./mvnw -pl ems-app spring-boot:run

# 4. 前端（另一终端）
cd frontend && pnpm install && pnpm dev
```

访问 http://localhost:5173，用 `admin` / `admin123!` 登录。

### 生产部署（30 分钟）

```bash
# 1. 准备环境变量
cp .env.example .env
openssl rand -base64 32         # EMS_DB_PASSWORD
openssl rand -base64 48         # EMS_JWT_SECRET
# 编辑 .env 填入

# 2. 构建 + 起栈
docker compose build
docker compose up -d

# 3. 验证
curl http://localhost:8888/actuator/health/liveness
```

详见 [`docs/ops/deployment.md`](./docs/ops/deployment.md) 和 [`docs/ops/dev-setup.md`](./docs/ops/dev-setup.md)。

---

## 文档导航

### 产品文档（面向用户 / 客户）
- [产品介绍](./docs/product/product-overview.md) — 平台是什么、解决什么、面向谁
- [功能全景图](./docs/product/feature-overview.md) — 16 个模块各自能做什么、怎么协作
- [用户使用手册](./docs/product/user-guide.md) — 4 角色（ADMIN / OPERATOR / FINANCE / VIEWER）日常操作
- [产品文档索引](./docs/product/README.md) — 14 份模块概览全清单
- 模块概览：
  [组织树](./docs/product/orgtree-feature-overview.md) ｜
  [仪表](./docs/product/meter-feature-overview.md) ｜
  [电价](./docs/product/tariff-feature-overview.md) ｜
  [班次产量](./docs/product/production-feature-overview.md) ｜
  [平面图](./docs/product/floorplan-feature-overview.md) ｜
  [仪表盘](./docs/product/dashboard-feature-overview.md) ｜
  [报表](./docs/product/report-feature-overview.md) ｜
  [成本分摊](./docs/product/cost-feature-overview.md) ｜
  [账单](./docs/product/billing-feature-overview.md) ｜
  [认证审计](./docs/product/auth-audit-feature-overview.md) ｜
  [平台底座](./docs/product/platform-internals-overview.md) ｜
  [报警](./docs/product/alarm-feature-overview.md) ｜
  [可观测性](./docs/product/observability-feature-overview.md) ｜
  [采集协议](./docs/product/collector-protocols-user-guide.md)

### 运维文档（面向实施 / 运维）
- [部署手册](./docs/ops/deployment.md)
- [开发环境搭建](./docs/ops/dev-setup.md)
- [报警 Runbook](./docs/ops/alarm-runbook.md) ｜ [可观测性 Runbook](./docs/ops/observability-runbook.md) ｜ [账单 Runbook](./docs/ops/billing-runbook.md)
- [Onboarding Checklist](./docs/ops/onboarding-checklist.md)

### 现场上线 SOP（"装-通-看-警-钱-报-效" 全闭环）
按顺序走完即可把一个新工厂从空白到月报自动化全部跑通：

1. **选型**：[meter-selection-guide.md](./docs/install/meter-selection-guide.md) — ≤50 块表场景的 BOM + 采购清单
2. **现场施工**：[field-installation-sop.md](./docs/install/field-installation-sop.md) — 10 步从勘测到 24h 验收
3. **通道导入**：`scripts/csv-to-channels.py` + `scripts/import-channels.sh` — 把 meter mapping CSV 转通道 JSON 一键导入
4. **仪表导入**：`scripts/csv-to-meters.py` + `scripts/import-meters.sh` — 自动解析 channelName→channelId 后批量注册 Meter（或前端 `/meters` 页"批量导入"按钮，v2 新增）
5. **看板上线**：[dashboard-commissioning-sop.md](./docs/install/dashboard-commissioning-sop.md) — org-tree、平面图、KPI 配齐
6. **5 分钟演示**：[dashboard-demo-quickstart.md](./docs/install/dashboard-demo-quickstart.md) + `scripts/demo-up.sh` — 客户参观 / 销售 demo 用
7. **报警上线**：[alarm-commissioning-sop.md](./docs/install/alarm-commissioning-sop.md) — 断线报警 + Webhook 推钉钉/企微
8. **账单上线**：[billing-commissioning-sop.md](./docs/install/billing-commissioning-sop.md) — 工业分时电价 + 内部分摊 + 账单 LOCK
9. **月报自动化**：[report-automation-sop.md](./docs/install/report-automation-sop.md) + `scripts/monthly-report-mail.sh` — cron 每月 1 号自动 PDF + 邮件
10. **生产能效**：[production-energy-sop.md](./docs/install/production-energy-sop.md) — 录产量 → 单位产品能耗 → 改造前后对比

辅助：`scripts/backup.sh` + `scripts/restore.sh`（生产备份/恢复）。

### API 文档（面向集成开发）
- [API 索引](./docs/api/README.md)
- [报警 API](./docs/api/alarm-api.md) ｜ [采集 API](./docs/api/collector-api.md) ｜ [可观测性 API](./docs/api/observability-metrics-api.md)

### 设计文档（面向贡献者）
- 设计规约：[`docs/superpowers/specs/`](./docs/superpowers/specs/)
- 实施计划：[`docs/superpowers/plans/`](./docs/superpowers/plans/)

---

## 模块速查

| 模块 | 入口 | 模块 | 入口 |
|---|---|---|---|
| ems-orgtree | `/orgtree` | ems-cost | `/cost/*` |
| ems-meter | `/meters` | ems-billing | `/bills/*` |
| ems-tariff | `/tariff` | ems-alarm | `/alarms/*` |
| ems-production | `/production/*` | ems-collector | `/collector` |
| ems-floorplan | `/floorplan/*` | ems-auth | `/login`, `/admin/users/*` |
| ems-dashboard | `/dashboard` | ems-audit | `/admin/audit` |
| ems-report | `/report/*` | ems-timeseries | （内部） |

---

## 版本与状态

| 子项目 | 范围 | 状态 |
|---|---|---|
| 地基 MVP | 认证 / 组织树 / 仪表 / 审计 / 时序 / 报表 / 电价 / 班次 / 平面图 | ✅ v1.1 ~ v1.3 |
| 采集层 | OPC UA / Modbus / MQTT + 缓冲续传 | ✅ v1.5 |
| 报警 | 采集中断报警 + Webhook | ✅ v1.6 |
| 可观测性栈 | 17 指标 + 4 SLO + 7 看板 | ✅ v1.7.0-obs |
| 成本分摊 | 规则引擎 + 异步运行 + 审计 | ✅ v2.0 |
| 账单 | 期次管理 + 关账解锁 | ✅ v2.1 |
| 报警增强 / 协议扩展 | 覆盖更多场景 | ✅ v2.2 / v2.3 |

详见 [`docs/product/product-overview.md` §8 路线图](./docs/product/product-overview.md)。

---

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Java 21、Spring Boot 3.3、Maven 多模块 |
| 前端 | React 18、Vite、AntD、Zustand |
| 数据 | PostgreSQL 15 + InfluxDB 2 + Flyway 迁移 |
| 部署 | Docker Compose + nginx |
| 观测 | Prometheus + Grafana + Tempo + Loki |

---

## License

详见 [`LICENSE`](./LICENSE)。
