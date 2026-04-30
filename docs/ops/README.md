# docs/ — 文档总索引

> **本文件**：运维 / 验收 / 上线 documents（`docs/ops/`）
> **同级目录**：
> - 📘 [`docs/product/`](../product/README.md) —— 产品说明书（功能、配置、用户指南，给最终用户/销售/实施）
> - 🔌 [`docs/api/`](../api/README.md) —— API 规约（开发对接，给第三方集成）
> - 📐 [`docs/superpowers/`](../superpowers/) —— spec / plan（设计规格 + 实施计划，给开发团队）
>
> **文档分工原则**
>
> | 想知道 | 看哪里 |
> |--------|--------|
> | 这个功能能做什么 / 怎么用 | `docs/product/` |
> | 怎么集成 API | `docs/api/` |
> | 怎么部署 / 排障 / 备份 | `docs/ops/`（本目录） |
> | 设计是怎么决定的 / 实施怎么排期 | `docs/superpowers/` |

---

# docs/ops — 运维与验收文档索引

## Verification Reports

历史文件命名不一致（`-plan-X.Y` 前缀位置、是否带 plan 后缀），下面给出权威映射。
**新增报告请用规范：** `verification-{YYYY-MM-DD}-{scope}.md`，其中 `scope` 为
`planX.Y` 或简短主题（如 `deploy-pack`、`mockdata-phaseH`）。

| 文件 | 覆盖 Plan / 主题 | 日期 |
|---|---|---|
| [verification-2026-04-25.md](./verification-2026-04-25.md) | Plan 1.1 — 地基 + 认证 + 审计 | 2026-04-25 |
| [verification-plan-1.2-2026-04-25.md](./verification-plan-1.2-2026-04-25.md) | Plan 1.2 — 核心域骨架 | 2026-04-25 |
| [verification-2026-04-25-plan1.3.md](./verification-2026-04-25-plan1.3.md) | Plan 1.3 — 全功能骨架 | 2026-04-25 |
| [verification-2026-04-26.md](./verification-2026-04-26.md) | Mock-data Phase H — 实库验证 | 2026-04-26 |
| [verification-2026-04-26-plan2.2.md](./verification-2026-04-26-plan2.2.md) | Plan 2.2 — 计费 + 报表后端 | 2026-04-26 |
| [verification-2026-04-26-plan2.3.md](./verification-2026-04-26-plan2.3.md) | Plan 2.3 — 前端 + E2E | 2026-04-26 |
| [verification-2026-04-27-plan1.5.1.md](./verification-2026-04-27-plan1.5.1.md) | Plan 1.5.1 — Modbus TCP MVP | 2026-04-27 |
| [verification-2026-04-28-plan1.5.2.md](./verification-2026-04-28-plan1.5.2.md) | Plan 1.5.2 — RTU + 热重载 + 持久 buffer | 2026-04-28 |
| [verification-2026-04-28-plan1.5.3.md](./verification-2026-04-28-plan1.5.3.md) | Plan 1.5.3 — 上线收尾 (v1.5.0) | 2026-04-28 |
| [verification-2026-04-28-deploy-pack.md](./verification-2026-04-28-deploy-pack.md) | 装机交付包验收（无硬件等待期） | 2026-04-28 |
| [verification-2026-04-29-alarm.md](./verification-2026-04-29-alarm.md) | 采集中断告警 ems-alarm | 2026-04-29 |

> Plan 2.1（成本后端）的验收并入 [verification-2026-04-26-plan2.2.md](./verification-2026-04-26-plan2.2.md)（计费依赖成本，连同验收）。

## Runbooks

- [dev-setup.md](./dev-setup.md) — 本地开发环境
- [deployment.md](./deployment.md) — 部署
- [runbook.md](./runbook.md) — 通用运维
- [runbook-1.3.md](./runbook-1.3.md) — Plan 1.3 后操作手册
- [runbook-2.0.md](./runbook-2.0.md) — v2.0 stack 运维
- [collector-runbook.md](./collector-runbook.md) — 采集器运维
- [billing-runbook.md](./billing-runbook.md) — 计费运维
- [cost-engine-runbook.md](./cost-engine-runbook.md) — 成本引擎运维
- [mock-data-runbook.md](./mock-data-runbook.md) — Mock-data 工具
- [nginx-setup.md](./nginx-setup.md) — Nginx 配置
- [dry-run-procedure.md](./dry-run-procedure.md) — Dry-run 流程
- [onboarding-checklist.md](./onboarding-checklist.md) — 上线 checklist
- [perf-2026-04-25.md](./perf-2026-04-25.md) — 性能基线
- [alarm-runbook.md](./alarm-runbook.md) — 采集中断告警运维
- [observability-deployment.md](./observability-deployment.md) — 可观测性栈部署（Phase C ✅）

## Cross-Reference: 产品 / API / Spec / Plan

### 产品说明书（[docs/product/](../product/README.md)）

| 文档 | 状态 | 范围 |
|------|------|------|
| `alarm-feature-overview.md` | 已完成（Phase H） | 销售/客户视角的功能概览 |
| `alarm-config-reference.md` | 已完成（Phase A） | 配置参数详解 + 调优 |
| `alarm-data-model.md` | 已完成（Phase B） | 5 张表字段词典 + 业务 SQL |
| `alarm-business-rules.md` | 已完成（Phase C） | 状态机 + 抑制窗 + 维护模式 |
| `alarm-detection-rules.md` | 已完成（Phase D） | 检测口径 + 不触发场景 + 排障决策树 |
| `alarm-webhook-integration.md` | 已完成（Phase E） | 钉钉/企微/自定义对接 + 多语言验签 |
| `alarm-user-guide.md` | 已完成（Phase G） | 管理员/操作员使用手册 + FAQ |
| `observability-config-reference.md` | 已完成（Phase A） | obs 栈配置 / 启停 / 升级 / 资源预算 / FAQ |
| `observability-metrics-dictionary.md` | 已完成（Phase B） | 17 个业务指标字典 + cardinality + PromQL |
| `observability-slo-rules.md` | 已完成（Phase D） | 4 SLO + 16 告警 + 客户视角 |

### API 规约（[docs/api/](../api/README.md)）

| 文档 | 状态 | 范围 |
|------|------|------|
| `alarm-api.md` | 已完成（Phase F） | 16 端点完整规约 + curl 示例 |

### Spec / Plan（[docs/superpowers/](../superpowers/)）

| 文档 | 内容 |
|------|------|
| `specs/2026-04-29-acquisition-alarm-design.md` | 完整设计规格（含 §11-§16 用户/配置/对接/状态机/错误码/部署六章） |
| `plans/2026-04-29-acquisition-alarm-plan.md` | 实施计划（A1-H3 共 ~34 任务，每 Phase 末附文档落实任务） |
