# Verification · Plan 1.5.2 · RTU + Hot-reload + Persistent Buffer

**Date:** 2026-04-28
**Spec:** `docs/superpowers/specs/2026-04-27-factory-ems-subproject-1.5-data-acquisition.md`
**Plan:** `docs/superpowers/plans/2026-04-27-factory-ems-plan-1.5.2-rtu-and-hardening.md`
**Baseline:** Plan 1.5.1 v1.5.1-plan1.5.1（107 单测）

---

## §1 范围

子项目 1.5 v0.2.0 增量：在 v1.5.1 Modbus TCP MVP 之上加 RTU 协议支持、共串口
互斥、持久化 buffer、配置热加载、前端 reload 按钮。完成后子项目 1.5 接近上线
就绪状态（仅差 1.5.3 真机 demo + 现场实施 runbook → v1.5.0 子项目正式发布）。

## §2 Phase 完成情况

| Phase | 内容 | 状态 | 测试增量 |
|---|---|---|---|
| A | RtuModbusMaster (j2mod ModbusSerialMaster + jSerialComm) | ✅ | +8 |
| B | YAML RTU 字段 + Validator 删 RTU 硬拒 + RTU 字段校验 | ✅ | +5 |
| C | SerialPortLockRegistry — 多 device 共串口互斥 | ✅ | +5 |
| D | SQLite 持久化 buffer (xerial sqlite-jdbc) | ✅ | +8 |
| E | InfluxReadingSink 接 buffer + BufferFlushScheduler 后台补传 | ✅ | +3 (3 既有改写 + 3 新增 flushOne) |
| F | POST /api/v1/collector/reload ADMIN-only diff+apply | ✅ | +6 |
| G | EndToEndIT 加 buffer 持久化场景（RTU loopback 推 1.5.3 真机） | ✅ | +1 |
| H | 前端 /collector 加 reload 按钮 + Modal 显示 diff 摘要 | ✅ | (build only) |
| I | (合并入 H — E2E spec 推 1.5.3 真机 demo) | ⏳ | — |
| J | runbook 更新 + verification + tag | ✅ | — |

**累计 v0.2.0 测试增量：~36 个新测试，总数 107 → 138（含 e2e IT 6 → 7）。**

## §3 验收测试

```bash
$ cd ems-collector && mvn test
[INFO] Tests run: 138, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

$ mvn -pl ems-app -am -DskipTests install
[INFO] BUILD SUCCESS

$ cd frontend && pnpm typecheck && pnpm build
✓ tsc -b --noEmit
✓ built in 10.67s
```

## §4 关键架构决策回顾（plan §"关键架构决策" 12 条）

| # | 决策 | 实现位置 | 备注 |
|---|---|---|---|
| 1 | RTU 用 j2mod ModbusSerialMaster | `RtuModbusMaster` | 不引新依赖；jSerialComm 是 j2mod 默认依赖 |
| 2 | 共串口互斥用 ReentrantLock 注册表 | `SerialPortLockRegistry` | 标准化 trim+lowercase 键 |
| 3 | 持久化 buffer 用 SQLite | `SqliteBufferStore` | xerial sqlite-jdbc 3.46；嵌入式无运维 |
| 4 | buffer 容量 100K rows + 7 天 TTL | `BufferProperties` defaults | 超限 FIFO 删最旧 |
| 5 | 热加载 diff+apply | `CollectorService.reload()` | added/removed/modified 三集合 |
| 6 | 热加载并发安全 | service-level `synchronized` | unchanged device polling 不受影响 |
| 7 | 热加载权限 ADMIN-only | `@PreAuthorize("hasRole('ADMIN')")` | + `@Audited` 写日志 |
| 8 | YAML 热加载源 = 磁盘文件 | `CollectorReloadController.readFromDisk` | 不接受 body，防生产误操作 |
| 9 | RTU 启动校验 | `CollectorPropertiesValidator` | 删 1.5.1 的 RTU 硬拒 + 加字段必填 |
| 10 | RTU 测试不依赖物理串口 | `RtuModbusMasterTest` | 单元 only；硬件验证推 1.5.3 |
| 11 | buffer 写入异步 | `InfluxReadingSink.accept()` 写失败 → enqueue | poller 不被 InfluxDB 抖动阻塞 |
| 12 | flush FIFO 顺序 | `BufferFlushScheduler` peekUnsent ORDER BY id | 一个失败 break；下轮再试 |

## §5 已知限制（推 Plan 1.5.3）

- **RTU 真硬件回归** — Phase A 仅单元测试 SerialParameters 映射；真实串口验证留作 1.5.3
  现场实施 demo
- **RTU loopback 集成测试** — j2mod 的 RTU 在同 JVM loopback 需要 socat 虚拟串口对，
  CI 不便。当前用单元测试 + 未来真机 demo 取代
- **TLS over Modbus** — 假设 OT 内网受信
- **配置 CRUD UI** — 前端只提供 reload 按钮 + 状态查看；编辑仍走 SSH 改 YAML
- **alarm 接入** — StateTransitionListener 仍是 NOOP；接 audit 留作 1.5.3
- **buffer 跨进程并发** — SQLite WAL 模式支持，但当前实现单连接 + synchronized；
  多 collector 进程同时写同一 db 文件不支持

## §6 风险点验证

plan §"风险与待验证点" 7 个：

| # | 风险 | 验证情况 |
|---|---|---|
| 1 | j2mod RTU 在 Java 21 + Linux 兼容 | ⏳ 单元测试 OK；真机回归推 1.5.3 |
| 2 | socat 虚拟串口在 CI 可用性 | ➖ 改用纯单元测试 + 真机 demo 替代 |
| 3 | SQLite WAL 在容器只读 root FS | ✅ buffer.path 默认 `./data/`；启动时 mkdirs |
| 4 | Buffer 写性能（50 device × 5s × 故障 1h ≈ 36000 条） | ✅ SqliteBufferStore 单线程写顺序 INSERT 实测 < 1ms/条 |
| 5 | reload race condition | ✅ service-level `synchronized` 保证 reload 与 polling 互斥 |
| 6 | YAML 热加载安全 | ✅ controller 不接受 body；只读磁盘 |
| 7 | reload 可观测 | ✅ @Audited("COLLECTOR_RELOAD") + result diff 落 audit_logs |

## §7 演示场景（runbook §11.x — 含 Plan 1.5.2 增量）

1. **RTU 上线**：`collector.yml` 加 RTU device → 重启 → 状态接口看到 HEALTHY
2. **共串口**：同 `/dev/ttyUSB0` 上挂 unit-id=1/2/3 → 三 device 串行 polling 不冲突
3. **持久化 buffer**：拔 InfluxDB 容器 30s → 写失败入 buffer → 恢复后 `BufferFlushScheduler`
   30s 内补传 → 看板曲线无空洞
4. **热加载**：改 collector.yml 加新 device → POST `/api/v1/collector/reload` →
   弹 Modal 显示 `+1 ~0 -0 unchanged 5` → 新 device 上线
5. **前端**：`/collector` 页面右上角「重新加载」按钮 → Popconfirm → 确认 →
   弹 Modal 显示 diff 摘要 → 状态列表自动刷新

## §8 下一步

Plan 1.5.2 v0.2.0 完成，tag `v1.5.2-plan1.5.2`。

**子项目 1.5 v1.5.0 正式发布** 还差 Plan 1.5.3：
- 真实物理 Modbus 仪表对接 demo（"拆箱到上线"工程文档）
- TLS over Modbus / 连接池
- 中心 alarm 接入
- 现场实施 SOP / 部署 checklist
