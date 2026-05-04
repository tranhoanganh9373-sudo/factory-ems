# Factory EMS · Plan 1.5.3 · 上线收尾（无硬件可交付项 + 现场 SOP）

**Goal:** 在 Plan 1.5.2 v1.5.2 之上加 (1) TCP 连接池 (2) 状态报警接入 audit_logs (3) 部署/调试 runbook，
打 tag `v1.5.0` 标志子项目 1.5 正式发布。**真实物理仪表对接 demo + TLS** 写为
可选 + SOP，等用户有硬件时按文档补 verification 即可。

**Architecture:** 在 v1.5.2 ems-collector 模块上扩展。复用既有：DefaultModbusMasterFactory (改造成
连接池) + StateTransitionListener (注入 audit 实现) + 现有 audit_logs 表。

**Spec reference:** `docs/superpowers/specs/2026-04-27-factory-ems-subproject-1.5-data-acquisition.md`
**Baseline:** Plan 1.5.2 v1.5.2-plan1.5.2（137 测试）

---

## 范围边界

### 本 Plan 交付（无硬件）

| 模块 | 内容 |
|---|---|
| `ems-collector`（扩） | TCP 连接池 + AlarmTransitionListener 接 audit_logs |
| `docs/ops/`（扩） | runbook §11 (RTU SOP) / §12 (部署 checklist) / §13 (调试工具) / §14 (TLS optional) |

### 不在本 Plan 实测内（待硬件）

- 真实物理 Modbus 仪表对接 verification（写 SOP 模板 + checklist）
- TLS over Modbus 实证（写 stunnel + Spring SSL 两种方案 docs）

---

## Phase 索引

| Phase | 范围 | 估 task |
|---|---|---|
| A | TCP 连接池 (同 host:port + unitId 多 device 共享 ModbusMaster 实例) + 单测 | 5 |
| B | AlarmTransitionListener 实现：写 audit_logs (`COLLECTOR_STATE_CHANGE`) + 单测 | 4 |
| C | docs runbook §11–§14 (RTU SOP / 部署 checklist / 调试工具 / TLS optional) | 4 |
| D | verification log + tag `v1.5.0` (子项目 1.5 正式发布) | 2 |

**合计 ~15 task。**

---

## 关键架构决策

1. **连接池按 (host, port) 键控**：同一物理仪表挂多 unit-id 时共享 TCP 连接。每个 ModbusTCPMaster
   实例线程不安全，通过 ReentrantLock 串行化（与 SerialPortLockRegistry 同模式但键不同）。
2. **池中 master 不主动 close**：device shutdown 时只解引用；最后一个 device 撤离时才真正 close。
3. **StateTransitionListener → audit_logs**：每次状态转移写一条 audit，actor=system，
   action=`COLLECTOR_STATE_CHANGE`，resource=`COLLECTOR/{deviceId}`，summary 含 from/to/reason。
4. **alarm 不引新表**：复用 audit_logs 让 `/admin/audit` 现有 UI 直接看；后续 Plan 1.6 再考虑独立 alarm 表。
5. **TLS 写 docs only**：1.5.3 不引 stunnel sidecar / Spring SSL 配置。等用户决策后续。
