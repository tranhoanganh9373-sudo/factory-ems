# Verification · Plan 1.5.3 · 上线收尾（v1.5.0 子项目正式发布）

**Date:** 2026-04-28
**Spec:** `docs/superpowers/specs/2026-04-27-factory-ems-subproject-1.5-data-acquisition.md`
**Plan:** `docs/superpowers/plans/2026-04-28-factory-ems-plan-1.5.3-production-readiness.md`
**Baseline:** Plan 1.5.2 v1.5.2-plan1.5.2（137 测试）

---

## §1 范围

子项目 1.5 v0.3.0 / v1.5.0 — 上线收尾。在 v1.5.2 之上加：
- TCP 连接池（共 host:port 多 unit-id 复用）
- 状态告警接入 audit_logs
- 现场实施 SOP / 部署 checklist / 调试工具

真实物理仪表 demo + TLS 写为可选 SOP，等用户有硬件按 runbook §11.3 补 verification 即可。

## §2 Phase 完成情况

| Phase | 内容 | 状态 | 测试增量 |
|---|---|---|---|
| A | TcpConnectionPool — 同 host:port 共享 master + 引用计数 | ✅ | +5 |
| B | AlarmTransitionListener — 状态切换写 audit_logs | ✅ | +3 |
| C | runbook §11–§12 (RTU SOP / 部署 checklist / 调试工具 / TLS optional) | ✅ | (docs only) |
| D | verification log + tag v1.5.0 | ✅ | — |

**累计 v0.3.0 测试增量：+8（137 → 145）。**

## §3 验收

```bash
$ cd ems-collector && mvn test
[INFO] Tests run: 145, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

$ mvn -pl ems-app -am -DskipTests install     # 全 app build
[INFO] BUILD SUCCESS

$ cd frontend && pnpm typecheck && pnpm build  # 前端无变化但确认仍 build
✓
```

## §4 关键架构决策（plan §"关键架构决策" 5 条）

| # | 决策 | 实现 | 备注 |
|---|---|---|---|
| 1 | 连接池按 (host, port) 键控 | `TcpConnectionPool` | 引用计数；归零自动 close |
| 2 | 池中 master 不主动 close | `release` 引用 -1 不归零时不关 | 最后一个 device 才真正关 |
| 3 | 状态切换写 audit_logs | `AlarmTransitionListener` | action=`COLLECTOR_STATE_CHANGE` |
| 4 | alarm 不引新表 | 复用 audit_logs | 后续 Plan 1.6 再独立 |
| 5 | TLS 仅 docs | runbook §11.5 stunnel + Spring SSL 两方案 | 等用户决策 |

## §5 子项目 1.5 v1.5.0 状态总览

| Plan | Tag | 交付内容 |
|---|---|---|
| 1.5.1 | v1.5.1-plan1.5.1 | Modbus TCP MVP（YAML / 调度 / 解码 / 状态机 / Influx sink / status REST / health / metrics / 前端） |
| 1.5.2 | v1.5.2-plan1.5.2 | RTU + 共串口锁 + SQLite 持久化 buffer + 配置热加载 + 前端 reload 按钮 |
| 1.5.3 | **v1.5.0** | 连接池 + 状态告警 audit + 现场实施 SOP / 部署 checklist / TLS optional |

**Test 总数：145。后端 mvn install + 前端 pnpm build 全绿。**

## §6 已知不接（推后）

详见 runbook §12。摘要：
- TLS over Modbus 仅 docs（未实现）
- 独立 alarm 表 + 通知通道（短信/邮件/企微/钉钉）
- 边缘 gateway 双层架构
- 配置 CRUD UI
- 仪表自动发现
- OPC-UA / IEC-104 / MQTT（独立子项目 1.6）

## §7 等硬件交付的项

部署到第一个有真实 Modbus 仪表的客户现场后：

1. 按 runbook §11.3 走完装机 checklist
2. 24h 跑通无 UNREACHABLE
3. 写 verification-YYYY-MM-DD-real-hardware.md 记录配置 + 实际寄存器映射 + 数据样本
4. 反馈到 spec / runbook 修订

## §8 下一步

子项目 1.5 v1.5.0 正式发布。下一个候选：
- **子项目 3 能效诊断**：基线建模 / 异常识别 / 节能建议（现在数据采集底座完整，可以接入真实数据）
- **子项目 1.6 协议扩展**：OPC-UA / MQTT / IEC-104 / DLT-645
- **第一个真实部署**：选个客户场点跑一周，反哺 runbook
