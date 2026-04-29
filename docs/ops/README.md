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
