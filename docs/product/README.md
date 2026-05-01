# 产品说明书目录

> **受众**：最终用户、客户实施人员、销售技术支持、撰写产品说明书的 AI。
> **不要写**：实现细节、代码、内部架构（这些在 `docs/superpowers/` 与 `docs/ops/`）。

## 写作约定

- **从用户视角**：从"管理员要做什么"、"操作员看到什么"出发，不从"系统怎么实现"出发
- **场景化**：每个功能配 1-2 个真实使用场景（如"想给某台设备单独设置 30 秒超时"）
- **截图占位**：截图后续补，先用 `[截图：...]` 占位描述要拍什么
- **避免技术黑话**：不写 "JPA Entity"、"@Async"、"HMAC"，要写 "数据表"、"后台异步处理"、"消息签名"
- **每个文档独立可读**：不假设读者读过其他文档，必要的术语在该文档内首次出现处简注

## 文档清单

### 平台总览（系统级，2026-05-01 起）

| 文档 | 受众 | 范围 |
|------|------|------|
| [product-overview.md](./product-overview.md) | 销售 / 客户决策者 / 项目方 | 产品介绍 + 价值主张 + 客户类型 + 平台架构 + 竞品对比 + 路线图 |
| [feature-overview.md](./feature-overview.md) | 客户 / 实施 / 产品对接人 | 16 模块功能全景 + 4 个端到端业务流 + 模块矩阵速查 |
| [user-guide.md](./user-guide.md) | 最终用户（4 个角色） | 角色 × 日常场景 + 跨角色协作流 + FAQ + 操作速查卡 |

### 模块功能概览（按模块，2026-05-01 起）

| 文档 | 模块 | 范围 |
|------|------|------|
| [orgtree-feature-overview.md](./orgtree-feature-overview.md) | 组织树 | 节点树 + 节点类型 + 路径 + 节点级权限的依据 |
| [meter-feature-overview.md](./meter-feature-overview.md) | 仪表与计量 | 仪表档案 + 拓扑 + 阈值 + 维护模式 + InfluxDB 映射 |
| [tariff-feature-overview.md](./tariff-feature-overview.md) | 电价 | 方案 + 时段 + 4 档位 + 生效期管理 + resolve 接口 |
| [production-feature-overview.md](./production-feature-overview.md) | 班次与产量 | 班次定义 + 产量录入 + 单位产量能耗 |
| [floorplan-feature-overview.md](./floorplan-feature-overview.md) | 平面图 | 底图 + 仪表挂点 + 实时态 + 编辑 |
| [dashboard-feature-overview.md](./dashboard-feature-overview.md) | 仪表盘 | 10 个组件（KPI / 实时 / 构成 / Top N / Sankey / 平面图等）|
| [report-feature-overview.md](./report-feature-overview.md) | 报表 | 5 张预设报表 + ad-hoc 同步 / 异步导出 + 矩阵 Excel/PDF |
| [cost-feature-overview.md](./cost-feature-overview.md) | 成本分摊 | 三种算法（直分 / 权重 / 残差）+ 试算 + 正式 run |
| [billing-feature-overview.md](./billing-feature-overview.md) | 账单 | 账期生命周期 OPEN/CLOSED/LOCKED + 账单明细 + 调账 |
| [auth-audit-feature-overview.md](./auth-audit-feature-overview.md) | 认证 + 审计 | 4 角色 + 节点权限 + JWT + @Audited 切面 |
| [platform-internals-overview.md](./platform-internals-overview.md) | 平台底座 | ems-core 公共件 + ems-timeseries InfluxDB rollup 三级聚合 |

### 子项目：采集中断告警（ems-alarm，2026-04-29 起）

| 文档 | 受众 | 范围 | Phase 负责人 |
|------|------|------|-------------|
| [alarm-feature-overview.md](./alarm-feature-overview.md) | 销售/客户 | 功能概览 + 价值主张 + 适用场景 | Phase H ✅ |
| [alarm-config-reference.md](./alarm-config-reference.md) | 实施工程师 | 全部配置参数 + 调优建议 | Phase A ✅ |
| [alarm-data-model.md](./alarm-data-model.md) | 实施/数据分析 | 5 张表的字段含义 + 业务含义 | Phase B ✅ |
| [alarm-business-rules.md](./alarm-business-rules.md) | 客户/实施 | 业务规则（状态机 + 抑制窗口 + 维护模式） | Phase C ✅ |
| [alarm-detection-rules.md](./alarm-detection-rules.md) | 客户/实施 | 检测口径（什么时候触发、什么时候不触发） | Phase D ✅ |
| [alarm-webhook-integration.md](./alarm-webhook-integration.md) | 客户开发对接 | Webhook 接入指南（含钉钉/企微/自定义示例） | Phase E ✅ |
| [alarm-user-guide.md](./alarm-user-guide.md) | 最终用户 | 操作手册（管理员视角 + 操作员视角） | Phase G ✅ |

> Phase F 的 API 规约写到 [`docs/api/alarm-api.md`](../api/alarm-api.md)（开发对接文档）。

### 子项目：可观测性栈（observability，2026-04-29 起）

| 文档 | 受众 | 范围 | Phase 负责人 |
|------|------|------|-------------|
| [observability-feature-overview.md](./observability-feature-overview.md) | 销售/客户 | 功能概览 + 价值主张 + 适用场景 + 与 ems-alarm 协同 | Phase G ✅ |
| [observability-config-reference.md](./observability-config-reference.md) | 实施工程师 | 配置参考（env / yml / 启停 / 升级 / 预算 / FAQ） | Phase A ✅ |
| [observability-metrics-dictionary.md](./observability-metrics-dictionary.md) | 数据/集成工程师 | 17 个业务指标字典 + cardinality + PromQL 模板 | Phase B ✅ |
| [observability-slo-rules.md](./observability-slo-rules.md) | 客户管理/运维 | 4 SLO + 16 告警 + 路由 + 客户视角 | Phase D ✅ |
| [observability-dashboards-guide.md](./observability-dashboards-guide.md) | 客户/工程值班/运维 | 7 dashboard 使用指南 + 下钻路径 | Phase E ✅ |
| [observability-user-guide.md](./observability-user-guide.md) | 客户运维 | Grafana 操作手册 + SLO 解读 + 告警响应 + 维护模式 + FAQ | Phase G ✅ |

## 与 docs/ops 的边界

- `docs/ops/alarm-runbook.md` —— 运维场景（排查、备份、监控接入），由 Phase H 完成
- `docs/product/` —— 产品功能说明，从用户能做什么出发

二者互不复制内容，互相 link。
