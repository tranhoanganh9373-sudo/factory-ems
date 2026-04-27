# Verification · Plan 1.5.1 · Modbus TCP MVP

**Date:** 2026-04-27
**Spec:** `docs/superpowers/specs/2026-04-27-factory-ems-subproject-1.5-data-acquisition.md`
**Plan:** `docs/superpowers/plans/2026-04-27-factory-ems-plan-1.5.1-modbus-tcp-mvp.md`

---

## §1 范围

子项目 1.5 「数据采集与边缘集成」第一波交付：Modbus TCP master + YAML 配置 + 调度 +
InfluxDB sink + 状态接口 + 健康/指标 + 前端只读状态页 + j2mod 内嵌 slave 测试设施。
跑通"YAML 声明 device → 周期 read → 解码 → 写 InfluxDB → 看板能看到"端到端数据流。

子项目 1.5 之后还有 1.5.2 (RTU + 持久化 buffer + 配置热加载) 和 1.5.3 (TLS / 真机 demo / runbook)。

## §2 Phase 完成情况

| Phase | 内容 | 状态 | 测试数 |
|---|---|---|---|
| A | ems-collector 模块骨架 + j2mod 3.2.1 依赖 + 接 ems-app | ✅ | (build only) |
| B | YAML schema (CollectorProperties) + Validator + MeterCodeValidator | ✅ | 11 |
| C | ModbusMaster 抽象 + TcpModbusMaster + ModbusSlaveTestFixture | ✅ | 11 |
| D | RegisterDecoder 6 dataType × 4 byteOrder + scale | ✅ | 31 |
| E | DevicePoller 状态机 (HEALTHY/DEGRADED/UNREACHABLE) | ✅ | 10 |
| F | CollectorService 调度 + 优雅关闭 + 多 device 隔离 | ✅ | 7 |
| G | InfluxReadingSink (一次 polling 一个 Point + 多 field) | ✅ | 5 |
| H | AccumulatorTracker (UINT32 wrap-around + _delta field) | ✅ | 9 + 3 |
| I | GET /api/v1/collector/status + DTO + ADMIN 权限 | ✅ | 5 |
| J | CollectorMetrics (Micrometer) + CollectorHealthIndicator | ✅ | 4 + 6 |
| K | (合并入 C 的 ModbusSlaveTestFixture，跳过) | ✅ | — |
| L | 端到端 IT (真 slave + 真 service + 真 decoder + 真 metrics) | ✅ | 5 |
| M | 前端 /collector 路由 + 状态表 | ✅ | (build only) |
| N | runbook + verification + tag | ✅ | — |

**合计：107 个单元/集成测试全绿。**

## §3 验收测试

```bash
# Backend 全测试
$ cd ems-collector && mvn test
[INFO] Tests run: 107, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

# 全 app build
$ mvn -pl ems-app -am -DskipTests install
[INFO] BUILD SUCCESS

# Frontend typecheck + build
$ cd frontend && pnpm typecheck && pnpm build
✓ tsc -b --noEmit
✓ built in 10.96s
```

## §4 演示场景手工验证（runbook §1.3）

| 步骤 | 命令 | 期望 |
|---|---|---|
| 1. 启动 | `docker compose up -d factory-ems` | 日志 `collector started: N device(s)` |
| 2. 健康 | `curl /actuator/health/collector` | UP, details healthy=N |
| 3. 状态 | `curl -H "Bearer $TOKEN" /api/v1/collector/status` | 列表，每个 state=HEALTHY |
| 4. 看板 | 浏览器 dashboard | meter 实时曲线更新 |
| 5. metrics | `curl /actuator/metrics/ems.collector.read.success?tag=device:X` | count > 0 |
| 6. 故障 | 改 host 为不可达地址 + 重启 | state=DEGRADED → UNREACHABLE，看板 lastReadAt 卡住 |
| 7. 恢复 | 改回正确 host + 重启 | state=HEALTHY |

> **注：** Plan 1.5.1 不接入真硬件，演示用 j2mod 内嵌 slave 验证（IT 已覆盖）。
> Plan 1.5.3 会给真机对接 demo + 现场实施 checklist。

## §5 设计决策回顾（spec §"关键架构决策" 14 条全部落实）

| # | 决策 | 实现位置 | 备注 |
|---|---|---|---|
| 1 | transport 抽象 + j2mod 包装到一个文件 | `ModbusMaster` / `TcpModbusMaster` | 业务代码不直接依赖 j2mod |
| 2 | 每 device 一个 transport + poller + future | `CollectorService.start()` | scheduler core size = devices.size |
| 3 | decode 与 transport 解耦 | `RegisterDecoder` 纯函数 | 6 类型 × 4 字节序 24 组合穷举 |
| 4 | 一次 polling 一个 Point | `InfluxReadingSink.buildPoint` | 多 field 一条；不每寄存器一条 |
| 5 | MeterReadingPort 抽象（用 ReadingSink 接口实现）| `ReadingSink` + `InfluxReadingSink` | 测试用 lambda 替身 |
| 6 | YAML 校验失败 → fail-fast | `MeterCodeValidator` ApplicationReadyEvent | meter-code 不存在抛 IllegalStateException |
| 7 | 状态机 enum + 切换 listener | `DeviceState` + `StateTransitionListener` | NOOP 默认；audit 接入留作 1.5.3 |
| 8 | Polling 时戳用 Instant.now() | `DevicePoller.pollOnce()` | NTP 是部署前提 |
| 9 | wrap-around 工程单位下处理 | `AccumulatorTracker` | (UINT32_MAX+1) × scale 公式 |
| 10 | Polling 周期 ≥ 1000ms | `DeviceConfig` `@Min(1000)` | 防误配把仪表打挂 |
| 11 | 错误降频 retries+1 → DEGRADED → UNREACHABLE | `DevicePoller` 状态机 | 8 种迁移单测全覆盖 |
| 12 | j2mod slave 用作 IT fixture | `ModbusSlaveTestFixture` | 随机端口 + auto-grow registers |
| 13 | /collector/status ADMIN-only | `CollectorStatusController` `@PreAuthorize` | spec 与 /admin/audit 同级敏感度 |
| 14 | 不在 ems-meter 加 collector 字段 | meters 表 schema 0 改动 | 配置只活在 YAML + 运行时 map |

## §6 已知限制（推 1.5.2 / 1.5.3）

详见 runbook §7。摘要：
- 不支持 RTU
- 不支持配置热加载
- 不持久化 buffer
- 不做 TLS 隧道
- 进程重启 ACCUMULATOR 丢一周期 delta

## §7 风险点验证

spec §6 列了 7 个风险点，本次落实情况：

| # | 风险 | 实测结果 |
|---|---|---|
| 1 | j2mod 在 Java 21 + Spring Boot 3.x 兼容 | ✅ 通过 — 11 个 TcpModbusMasterTest 全绿 |
| 2 | TCP 长连接稳定性 | ⏳ 留作 1.5.3 真机回归 |
| 3 | 多 device 并发 | ✅ CollectorService.multipleDevices_pollIndependently 测试通过 |
| 4 | YAML 校验时机 | ✅ ApplicationReadyEvent + fail-fast 验证 |
| 5 | 时间戳准确性 | ✅ Instant.now() 在 polling 完成时取 |
| 6 | 断网持续时间长 | ⏳ 仅内存 buffer，约 14h；持久化推 1.5.2 |
| 7 | RTU 串口在容器里 | ⏳ 推 1.5.2 |

## §8 下一步

1. 提交 Plan 1.5.1 阶段 commit + 打 tag `v1.5.1-plan1.5.1`
2. 更新 memory 写入 1.5.1 完成事实
3. 用户决定后续：
   - **A.** 启动 Plan 1.5.2（RTU + 配置热加载 + 持久化 buffer）
   - **B.** 接真实 Modbus 仪表跑一次 demo（Plan 1.5.3 提前部分内容）
   - **C.** 切到子项目 3 能效诊断（这要先有真实采集数据）
