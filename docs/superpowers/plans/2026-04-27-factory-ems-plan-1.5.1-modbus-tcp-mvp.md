# Factory EMS · Plan 1.5.1 · 数据采集 MVP（Modbus TCP）

**Goal:** 落地子项目 1.5 第一波：`ems-collector` 模块 + j2mod TCP master + YAML 配置 + DevicePoller 调度 + InfluxDB 写入。完成后能在不动 mock-data CLI 的前提下，从一台真实/模拟 Modbus TCP 电表读取寄存器，看板曲线显示真实数据。

**Architecture:** 在 v2.0.0 模块化单体上新增 `ems-collector` 模块（与 `ems-cost`/`ems-billing` 同层级）。复用既有 InfluxDB client + meters 表 + Plan 1.3 的 ThreadPoolTaskExecutor 模式。不动既有 v1/v2 的表 schema 与 API。

**Tech Stack (增量):**
- [j2mod 3.2.x](https://github.com/steveohara/j2mod) — Apache 2.0，Java Modbus TCP/RTU master + slave
- [jSerialComm](https://github.com/Fazecast/jSerialComm) — j2mod 默认依赖（Plan 1.5.1 不真用，1.5.2 RTU 才用，但 pom 引入避免后续冲突）
- 复用既有：InfluxDB Java Client、Spring Boot ConfigurationProperties、Micrometer

**Spec reference:** `docs/superpowers/specs/2026-04-27-factory-ems-subproject-1.5-data-acquisition.md`

---

## 依赖前提

- 子项目 1 v1.0.0 + 子项目 2 v2.0.0 已上线
- `meters` 表里至少有 1 条用于绑定 collector 的记录（meter-code、influx_measurement、influx_tag_key、influx_tag_value 都已填）
- Docker 环境能跑 j2mod 内嵌 slave（用于 IT，不依赖真硬件）
- InfluxDB 写入路径已经被 mock-data CLI 验证可用（沿用相同 measurement/tag schema）

---

## 范围边界

### 本 Plan 交付

| 模块 | 职责 |
|---|---|
| `ems-collector`（新） | YAML 配置加载、ModbusMaster 抽象 + TCP 实现、DevicePoller 调度、寄存器解码、写 InfluxDB、状态 controller、Health/Metrics |
| `ems-app`（扩） | maven 依赖 `ems-collector`、`spring.config.import: classpath:collector.yml` 接入 |
| `frontend/`（扩） | `/collector` 路由 + 只读状态面板（device 列表 + state tag + lastReadAt） |

### 不在本 Plan 内（推到 1.5.2 / 1.5.3）

- Modbus RTU（串口）→ Plan 1.5.2 Phase A
- 配置热加载 `/api/v1/collector/reload` → Plan 1.5.2 Phase E
- 持久化 buffer（断网超过 10000 点）→ Plan 1.5.2 Phase D
- 配置 CRUD UI（前端编辑 YAML → DB）→ 1.5.2 之后再讨论
- TLS over Modbus / 连接池 → Plan 1.5.3
- 真机对接 demo / 现场实施文档 → Plan 1.5.3

### 交付演示场景（手工 + 自动化）

1. 在 `collector.yml` 里声明 1 个 device 指向 j2mod 内嵌 slave（端口 5020），含 3 个寄存器（voltage / power / energy）
2. 启动后端，j2mod 内嵌 slave 同步启动并喂 mock 寄存器值
3. `GET /api/v1/collector/status` → 返回 `[{deviceId, state:HEALTHY, lastReadAt:..., errorCount:0}]`
4. `GET /actuator/health/collector` → UP
5. `GET /actuator/metrics/ems.collector.read.success?tag=device:meter-A1` → count > 0
6. InfluxDB query：最近 1 分钟该 measurement 有 ≥ 12 个数据点（5s 周期）
7. 看板 dashboard：该 meter 的实时曲线显示 mock 寄存器值（不再是 mock-data CLI 灌的 sin 波）
8. 改 `collector.yml` 的 host 指向不存在地址 → 重启 → 状态 UNREACHABLE，errorCount 递增，dashboard 显示该 meter 的 lastReadAt 停在重启前
9. 改回正确 host → 重启 → 状态恢复 HEALTHY
10. 单元 + 集成测试全绿；mvn `-pl ems-collector test` 0 失败
11. E2E：`/collector` 页面渲染状态表，至少 1 行 HEALTHY tag

---

## 关键架构决策

1. **transport 抽象 + j2mod 包装在内层**：`ModbusMaster` 接口（`open() / close() / readHolding(unit, addr, count) / readInput(unit, addr, count) / readCoil(unit, addr, count)`），`TcpModbusMaster` 用 j2mod 实现。**业务代码只写 `ModbusMaster`，j2mod API 只在一个文件里出现**。后续换 lib（j2mod → digitalpetri）只换 transport 类。
2. **每个 device 一个 transport 一个 poller 一个 future**：不共享连接（MVP）。`ScheduledExecutorService` core=N (devices)，避免线程过多。
3. **decode 与 transport 解耦**：`RegisterDecoder.decode(byte[] raw, DataType, ByteOrder, BigDecimal scale) → BigDecimal`。MVP 数据类型 6 种：UINT16 / INT16 / UINT32 / INT32 / FLOAT32 / FLOAT64。byte-order 4 种：ABCD / CDAB / BADC / DCBA（应对 word swap 与字节序混排）。
4. **写 InfluxDB 多 field 一次性写**：一个 device 一次 polling 周期内，所有寄存器读完后组装一个 `Point`（measurement = meters 表查到的，tags 来自 meters 表，fields 是该 device 所有 ts-field）。**不要每个寄存器一条 Point**。
5. **MeterReadingPort 抽象**：在 `ems-collector` 里定义 `MeterReadingPort` 接口（`writePoint(measurement, tags, fields, timestamp)`），实现走既有 InfluxDB client。这样 collector 不直接依赖 `com.influxdb.*`，便于换 backend（影响面：单测 mock 容易；后续如果切 TimescaleDB 只换实现）。
6. **YAML 启动校验失败 = 应用启动失败**：所有 device 的 `meter-code` 必须能在 meters 表查到 → fail-fast。后续 `partial-startup: true` 可选，MVP 不做。
7. **状态机用枚举 + lastTransitionAt**：HEALTHY / DEGRADED / UNREACHABLE。状态切换写 audit log（reuse `@Audited` 切面），不写 dashboard alarm 表（alarm 接入留给 1.5.3）。
8. **Polling 时间戳来自系统时钟**：`Instant.now()` 写 InfluxDB，不取 device 内嵌时间（Modbus 也不返回）。集群部署时所有节点必须 NTP 同步——这是 EMS 公认前提，不另写。
9. **累加量翻转检测就地处理**：`AccumulatorTracker` 在 collector 内存里维护 per-device per-register 上次值；checkpoint 不持久化（重启从下次 polling 重新基准，会丢一个周期的 delta —— MVP 接受）。
10. **Polling 周期最小值 1000ms**：YAML 校验时强制；防止误配 100ms 把仪表打挂。
11. **错误降频策略**：连错 retries 次后状态 → DEGRADED，polling 周期切到 `backoff-ms`（默认 5×polling-interval）。再连错 N×backoff 周期 → UNREACHABLE，停止 polling，仅 30s 一次重连尝试。
12. **j2mod slave 用作 IT fixture**：`com.ghgande.j2mod.modbus.slave.ModbusSlaveFactory.createTCPSlave(port, processImage)` 在测试 setup 启动，喂固定寄存器值。运行在随机端口（避免 CI 撞车）。
13. **`/api/v1/collector/status` 权限**：ADMIN + ENGINEER 可读，VIEWER 不可见。和 `/admin/audit` 同级敏感度。
14. **不在 ems-meter 加 collector 字段**：collector 与 meter 的关联只活在 YAML 与运行时 map，不污染 meters 表 schema。

---

## Phase 索引（估算 Task 数）

| Phase | 范围 | 估 Tasks |
|---|---|---|
| A | `ems-collector` 模块骨架：pom + j2mod 3.2.x + jSerialComm 依赖 + 加入 ems-app 依赖链 + 空 `@SpringBootApplication` smoke 启动 OK | 4 |
| B | `CollectorProperties` ConfigurationProperties + YAML schema 完整字段（device + register）+ `@Validated` Bean Validation + 启动时 meter-code 全部校验存在性 + 单测覆盖各种异常 YAML | 7 |
| C | `ModbusMaster` 接口 + `TcpModbusMaster` 实现（j2mod TCPMaster 包装）+ 连接生命周期 + 重连 + 单测（用 j2mod slave 测 connect/read/disconnect/reconnect） | 6 |
| D | `RegisterDecoder`：6 种 data-type × 4 种 byte-order 解码 + scale 应用 + 单测穷举 24 个组合（fixture 表驱动） | 6 |
| E | `DevicePoller`：单设备 polling loop + retry + backoff + DEGRADED/UNREACHABLE 状态切换 + 单测（用 fake transport 模拟成功/失败序列） | 8 |
| F | `CollectorService`：按 YAML 装配 pollers + ScheduledExecutorService core=N + graceful shutdown（`@PreDestroy` 等所有 in-flight read 完成或超时 5s） + 单测 | 6 |
| G | `MeterReadingPort` 接口 + `InfluxDbReadingPort` 实现 + 一次 polling → 一个 Point（多 field）+ 单测 mock InfluxDB | 5 |
| H | `AccumulatorTracker`：UINT32 wrap-around 检测 + 写伴随 `_delta` field + 单测（含跨 wrap 临界） | 4 |
| I | `CollectorStatusController`：`GET /api/v1/collector/status` + DTO（deviceId / state / lastReadAt / errorCount / lastError）+ 权限 + 单测 | 4 |
| J | `CollectorHealthIndicator`（聚合 device 状态成 UP/DEGRADED/DOWN）+ Micrometer 指标（success/failure counter + read.duration timer，按 device tag）+ 单测 | 4 |
| K | `ModbusSlaveTestFixture`：j2mod slave 在 IT setUp 启动 + ProcessImage 喂可控寄存器值（含浮点 / 累加器 / 多 unit-id） | 4 |
| L | 集成测试：Spring Boot context + slave + InfluxDB Testcontainer，跑 3 周期 polling，校验 InfluxDB 收到正确 measurement/tags/fields/values；含状态切换（slave stop → UNREACHABLE → restart → HEALTHY） | 6 |
| M | 前端：`/collector` 路由 + 只读 Table（device 列表 + state Tag 配色 + lastReadAt 相对时间 + errorCount + lastError 截断）+ 5s 轮询 + 单测（vitest 渲染 + react-query mock） | 5 |
| N | 文档 `docs/ops/collector-runbook.md`（YAML schema 实例、状态机、排查步骤、j2mod debug 日志开法）+ verification 日志 `docs/ops/verification-2026-04-27-plan1.5.1.md` + tag `v1.5.1-plan1.5.1` | 3 |

**合计估算：~72 tasks，按照子项目 2 的并发节奏 5–7 工作日。**

---

## 关键不变量（防回归 / 写给后续 plan）

- **Collector 不写 meters 表**：所有 meter 元数据由人工/运维管，collector 只读。
- **Polling timestamp 用 `Instant.now()`，不用 device 时间**：NTP 是部署前提。
- **InfluxDB Point 一次 polling 一条**：不要拆寄存器多 Point。
- **状态机迁移必须打 audit log**：`HEALTHY → DEGRADED`、`DEGRADED → UNREACHABLE`、回升路径都是。
- **YAML schema 改 = breaking change**：增字段必须有默认值，删/改字段需要 migration script + 现场 sop。
- **`ems.collector.enabled=false` 必须能完全 noop**：禁用 collector 的部署（纯导入数据 / 离线分析）不应该 crash。
- **测试不依赖真硬件**：所有 `*IT.java` 用 j2mod slave；CI 不允许串口/外网。

---

## 验收

- 全后端 `./mvnw -pl ems-app -am test` exit 0
- `./mvnw -pl ems-collector test` 0 失败
- 集成测试 `CollectorIT`：3 个 device + slave，跑 3 周期，InfluxDB 数据匹配预期 fixture
- 前端 `pnpm build` 0 错；`/collector` 页面手测能看到状态
- E2E `collector-status.spec.ts`：登录 → goto `/collector` → 至少 1 行 HEALTHY 可见
- 文档 `docs/ops/collector-runbook.md` 让一个新工程师可以照着配 1 个新 device 上线
- tag `v1.5.1-plan1.5.1` 打到 main

---

## 风险与待验证点（spec §6 的细化）

1. **Phase A spike**：j2mod 3.2.x 在 Java 21 + Spring Boot 3.x 是否有兼容性 issue。如果有，回退到 3.1.x 或换 digitalpetri。
2. **Phase C TCP keep-alive**：长连接断流是否影响 read。j2mod `TCPMasterConnection.setReconnecting(true)` 测一下。
3. **Phase E 状态机覆盖**：单测要覆盖 8 种迁移路径（H→D, D→U, U→H, U→D, D→H, H→H 重复成功, U→U 重复失败 backoff, panic 路径）。
4. **Phase F 优雅关闭**：5s 超时之内未结束的 read 直接强 cancel，不能阻塞 SIGTERM。
5. **Phase L Influx Testcontainer**：CI 跑 InfluxDB 容器启动慢（~10s），把 `ems-collector` 的 IT 用 `@Tag("docker")` 区分，本地默认跳过，CI 单独跑。
6. **Phase M 前端轮询节流**：不要每 1s 轮询，5s 即可，且页面切走时 disable refetch。

---

## 启动建议

第一会话：**Phase A + B + C 接 j2mod slave 跑通一次 read holding registers**。这一步过了协议层风险就排除了，剩下 phase 是工程化。
