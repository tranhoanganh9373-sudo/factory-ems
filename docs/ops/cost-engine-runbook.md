# Cost Engine Runbook — Plan 2.1

> 适用范围：factory-ems 子项目 2 · Plan 2.1（成本分摊后端）。
> 与 [`runbook.md`](./runbook.md) / [`runbook-1.3.md`](./runbook-1.3.md) 互补；本文聚焦 cost-allocation 域日常运维。

---

## 1. 模块速览

| 模块 | 职责 | 关键 Bean |
|---|---|---|
| `ems-cost` | 规则 CRUD、dry-run、异步 run、4 段（SHARP/PEAK/FLAT/VALLEY）成本计算 | `CostAllocationServiceImpl`, `CostRuleServiceImpl`, `AllocationAlgorithmFactory` |
| `ems-tariff` | 4 段电价定价查询（跨零点支持） | `TariffPriceLookupService` |
| `ems-meter` (port) | 表计 → 组织节点元数据 | `MeterMetadataPort` |
| `ems-timeseries` (rollup) | 小时级用电量来源 | `MeterUsageReader` 读取 `ts_rollup_hourly` |

数据流：
```
ts_rollup_hourly  →  MeterUsageReader  ┐
                                       │
tariff_periods    →  TariffPriceLookup ┼→ AllocationStrategy.allocate(rule, ctx)
                                       │     │
cost_allocation_rule (weights JSONB)   ┘     └→ List<CostAllocationLine> (含 4 段拆分)
                                                        │
                                                        ▼
                                              cost_allocation_line + cost_allocation_run
```

---

## 2. REST API

所有 endpoint 都在 `/api/v1/cost`。读权限 `isAuthenticated()`；写权限 `hasAnyRole('FINANCE','ADMIN')`。

### 2.1 规则 CRUD（`CostRuleController`）

| 方法 | 路径 | 角色 |
|---|---|---|
| GET | `/rules` | 任意已登录 |
| GET | `/rules/{id}` | 任意已登录 |
| POST | `/rules` | FINANCE / ADMIN |
| PUT | `/rules/{id}` | FINANCE / ADMIN |
| DELETE | `/rules/{id}` | FINANCE / ADMIN |

`CreateCostRuleReq` 必填：`code`, `name`, `energyType`, `algorithm`, `sourceMeterId`, `targetOrgIds`(≥1), `effectiveFrom`。
`weights` 结构按 `algorithm` 不同：

- **DIRECT**：`weights` 可省。
- **PROPORTIONAL**：`{"basis":"FIXED","values":{"<orgId>":<weight>, ...}}`（FIXED）
  或 `{"basis":"AREA"|"HEADCOUNT"}`（按 `org_nodes.area_m2` / `headcount` 自动取）。
  > ⚠️ FIXED 时键名必须是 `values`（不是 `fixed`）。
- **RESIDUAL**：parent meter 总量 − 子表读数；剩余划归 `targetOrgIds[0]`，`weights` 可省。
- **COMPOSITE**：`{"steps":[ <分步规则数组> ]}`，每步形态等同 PROPORTIONAL/DIRECT/RESIDUAL；
  `CostRuleService.validateAlgorithmShape()` 会拒绝缺 `steps` 的请求。

### 2.2 Dry-run（`CostAllocationController`）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/rules/{ruleId}/dry-run` | 单条规则预览（不写库） |
| POST | `/dry-run-all` | 当前期间内全部启用规则的预览 |

请求体：`{ "periodStart": "2026-03-01T00:00+08:00", "periodEnd": "2026-04-01T00:00+08:00" }`。
返回 `List<CostLineDTO>`，含每条 line 的 4 段拆分。

### 2.3 异步 Run

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/runs` | 提交一次运行；返回 `202 Accepted` + `{"runId":...}` |
| GET | `/runs/{runId}` | 查询状态（PENDING / RUNNING / SUCCESS / FAILED / SUPERSEDED） |
| GET | `/runs/{runId}/lines?orgNodeId=` | 查询该 run 下所有/某组织节点的 lines |

请求体：`{ "periodStart": ..., "periodEnd": ..., "ruleIds": [1,2,3] }`。`ruleIds` 留空 = 跑该期间全部启用规则。

`createdBy` 自动从 SecurityContext 取（`AuditContext.currentUserId()`）。

---

## 3. 状态机 & SUPERSEDED 语义

```
PENDING ──(worker 接管)──► RUNNING ──(成功)──► SUCCESS
                              │
                              └──(异常)──► FAILED   (写入 error_message ≤ 4000 字符)
```

**重跑同一 (periodStart, periodEnd)**：

1. 新 run 进入 SUCCESS 写库前，`runRepository.markPriorSuccessSuperseded()` 把先前 SUCCESS 改为 `SUPERSEDED`。
2. 部分唯一索引（`ux_cost_run_period_success WHERE status='SUCCESS'`）保证同期间只有 1 条 SUCCESS。
3. 因此 `runRepository.findSuccessByPeriod(start, end)` 永远只返回最新版本。
4. SUPERSEDED 行的 `cost_allocation_line` 不会自动删除，可作为审计追溯——下游报表查询应按 `runId` 而非按 period 查 lines（Plan 2.2 报表层已遵循此约定）。

**FAILED 处理**：worker 单独的 `markFailed` 短事务写状态；不会回滚已 commit 的 lines。
若需重跑只须新提交一次（不要 UPDATE 现有 FAILED 记录）。

---

## 4. 算法字段速查

| 算法 | weights 必填字段 | 例子 |
|---|---|---|
| DIRECT | — | `{}` |
| PROPORTIONAL FIXED | `basis="FIXED"`, `values{orgId:weight}` | `{"basis":"FIXED","values":{"12":0.6,"13":0.4}}` |
| PROPORTIONAL AREA / HEADCOUNT | `basis` | `{"basis":"AREA"}` |
| RESIDUAL | — | `{}`（剩余落到 `targetOrgIds[0]`） |
| COMPOSITE | `steps[]` | `{"steps":[{"weights":{...}},{"weights":{...}}]}` |

> 配置完成后先发 `/dry-run` 看一行 sample，确认 4 段拆分加总等于 amount。

---

## 5. 性能基线

| 指标 | 实测 | 目标 |
|---|---|---|
| 50 rules × 200 orgs × 30 天 hourly | 5.4 s（dev PG，testcontainers, 含 36 k 行 rollup） | ≤ 30 s |
| 单规则 dry-run（24 h, 2 orgs） | < 200 ms | — |

回归测试：`ems-app/src/test/java/com/ems/app/cost/CostAllocationPerfIT.java`。
若实测 > 30 s 需排查（按概率排序）：

1. `ts_rollup_hourly` 缺索引 → 检查 `idx_rollup_hourly_org_node_hour` / `idx_rollup_hourly_hour`。
2. `tariff_periods` 全表扫 → `idx_tariff_periods_plan` + `effective_from/to` 下推。
3. JPA N+1：`MeterUsageReaderImpl` 当前使用 `findByMeterIdAndHourTsBetween`，确认未被改成逐小时查询。
4. Executor 池被吞：`CostAllocationExecutorConfig`（core=1, max=2, queue=20），看 `runs` 表是否堆积 PENDING。

---

## 6. 常见问题 / 排错

### 6.1 提交后一直停在 PENDING

- 看应用日志，搜索 `cost-alloc run id=<X>`。完全无日志时多半 `costAllocationExecutor` 还没启动或已被关闭。
- 同时出现 `Run not PENDING` 异常时，说明前一次的 worker 已把状态推到 RUNNING 但仍占线程；等待或排查死循环。

### 6.2 Run SUCCESS 但 lines 为空

- 期间内无可用 rollup 数据（`ts_rollup_hourly`）—— 查 `SELECT COUNT(*) FROM ts_rollup_hourly WHERE meter_id=? AND hour_ts >= ? AND hour_ts < ?`。
- 全部 rule 的 `effectiveFrom > periodStart` 或 `enabled=false` —— 查 `cost_allocation_rule` 表 + 用 `dryRun()` 单独验证。

### 6.3 PROPORTIONAL FIXED 始终平均拆分

> 历史教训：`weights.fixed` ≠ `weights.values`。
> `WeightResolverImpl` FIXED 分支只读 `values`；其它键名一律走平均回退。
> 修复方法：把 JSON 改成 `{"basis":"FIXED","values":{"<orgId>":<w>}}`。

### 6.4 同期间重跑前一个 SUCCESS 没变 SUPERSEDED

- 检查 V2.0.1 partial unique index 是否在：`SELECT indexdef FROM pg_indexes WHERE indexname='ux_cost_run_period_success'`。
- 检查 `markPriorSuccessSuperseded` 是否在 `finalizeSuccess` 之内（service.impl.CostAllocationServiceImpl）。
- 若 partial idx 缺失会出现唯一约束冲突 → SUCCESS 永远落不了，新 run 进 FAILED。

### 6.5 `effectiveTo` 当天的 run 应该跑还是不跑？

`isEffectiveAt(rule, periodStart.toLocalDate())` 用 `periodStart` 的日期对比；`effectiveTo == periodStart.toLocalDate()` 视为生效。
客户希望「end 当天即失效」时，跑前手动把 `effectiveTo` 改成前一天。

---

## 7. 监控建议（Plan 2.2 之前的占位）

- `cost_allocation_run` 表 `status`+`finished_at-started_at` 可直接画分位线。
- `cost_allocation_line` 总条数随时间增长应近线性；突然斜率变化 → 跑了大期间或重复期间。
- worker 异常落库为 `error_message`；在 ELK / Grafana 上对 `cost-alloc run id=.* FAILED` 关键字告警即可。

---

## 8. 相关文档

- spec：[`docs/superpowers/specs/2026-04-25-factory-ems-subproject-2-cost-allocation.md`](../superpowers/specs/2026-04-25-factory-ems-subproject-2-cost-allocation.md)
- plan：[`docs/superpowers/plans/2026-04-25-factory-ems-plan-2.1-cost-backend.md`](../superpowers/plans/2026-04-25-factory-ems-plan-2.1-cost-backend.md)
- DB 迁移：`V2.0.0` rule、`V2.0.1` run、`V2.0.2` line、`V2.0.3` org_nodes 加 area/headcount。
- 验证记录：[`verification-2026-04-26.md`](./verification-2026-04-26.md) §Plan 2.1。
