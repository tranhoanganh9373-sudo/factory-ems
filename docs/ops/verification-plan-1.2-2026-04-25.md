# Plan 1.2 验证日志 — 2026-04-25

> Goal: 在测试容器（PostgreSQL + InfluxDB Testcontainer）下，跑通 Plan 1.2 各模块的集成测试，确认后端核心域骨架可用。

## 后端 IT 全绿

| 模块 | IT 类 | 用例数 | 耗时 |
|---|---|---|---|
| ems-orgtree | `ClosureConsistencyIT` | 5 | — |
| ems-auth | `PermissionResolverIT` | 5+ | — |
| ems-auth | `AuthFlowIT` | 5 | 24.2s |
| ems-meter | `MeterCrudIT` | 8 | 22.9s |
| ems-timeseries | `InfluxSchemaContractIT` | 5 | 8.7s |
| ems-timeseries | `RollupPipelineIT` | 4 | 18.9s |
| ems-dashboard | `DashboardIT` | 9 | 27.0s |
| ems-report | `ReportIT` | 6 | 16.3s |

**合计 47+ 用例，0 失败 0 错误。**

## Plan 1.1 遗留待办（B3 / L1 / L2）现状核验

| # | 项 | 落地点 | 状态 |
|---|---|---|---|
| **B3** | `User#1` 乐观锁 | `UserRepository#markLoginSuccess/Failure` 改 `@Modifying` JPQL UPDATE，绕过 `@Version` | ✅ 已修 |
| **L1** | orgtree VIEWER 全树返回 | `OrgNodeServiceImpl#getTree` 调 `permissions.visibleNodeIds(uid)` 过滤；`hasAllNodes` ALL_NODE_IDS_MARKER 哨兵让 ADMIN 跳过过滤 | ✅ 已修 |
| **L2** | audit detail JSON 含明文 password | `AuditAspect#maskSensitive` 递归遮蔽含 `password/passwd/pwd/secret/token/credential/apikey/passwordhash` 子串的字段名为 `***` | ✅ 已修 |

## Plan 1.2 完成度

12/12 phase 全部交付：
- A 迁移 / B meter / C timeseries / D rollup / E dashboard / F report
- G 测点管理 UI / H 看板 UI / I 报表导出 UI
- J InfluxDB 种子 + Compose / K E2E 5 用例 / L perf 脚本 + 模板

## 待用真实环境跑（不阻塞 tag）

- Phase J 在 `docker compose up` 全栈环境跑种子 → 看板端到端
- Phase K 5 个新 E2E 用例（依赖种子数据 + 全栈拉起）
- Phase L `dashboard-load.sh --report` + `report-load.sh --report` 实测填表
- 演示 9 步走完一遍

## 结论

后端代码层面 Plan 1.2 收尾，安全遗留 3 项已落地。可打 tag `v1.2.0-plan1.2`。
