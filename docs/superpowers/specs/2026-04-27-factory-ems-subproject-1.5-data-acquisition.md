# 通用工厂能源管理系统 · 子项目 1.5 · 数据采集（Modbus）设计文档

- **Project**: factory-ems
- **Subproject**: 1.5 — 数据采集与边缘集成（Modbus TCP/RTU）
- **Spec Date**: 2026-04-27
- **Baseline**: 子项目 1 v1.0.0 + 子项目 2 v2.0.0
- **Author**: impl

---

## 1. 背景与定位

### 1.1 为什么是 1.5 不是 3

子项目 1（v1.0.0 平台 + 看板 + 报表）和子项目 2（v2.0.0 分摊 + 账单）目前所有数据都来自 `tools/mock-data-generator` 生成的合成 timeseries。**没有任何一行数据来自真实电表**。要从「演示」变成「可上线的商业产品」，必须先把真实采集层补上：

> 「dashboard 上的曲线，到底是工厂里那块电表的真实读数，还是 mock 脚本里 Sin 波 + 随机扰动？」

如果跳过采集直接做子项目 3（能效诊断），所有的基线模型、异常阈值、节能建议都建立在合成数据上，真实采集接入后基线全废、模型重训。所以这个子项目的编号取 1.5——**它在分析层（3）之前，但在基础平台（1）之后**，是上线必经的一层。

### 1.2 子项目 1.5 的价值

| 角色 | 拿到的能力 |
|---|---|
| 现场实施 | 在 YAML 里描述一台 Modbus 电表的连接 + 寄存器映射，重启后系统就能读到数据，无需改代码 |
| 运维 | 看到每个采集设备的健康状态（最后采集时间、丢点率、连续错误数），并接入既有 alarm |
| 数据 | 看板/报表/分摊全链路的 timeseries 来自真实仪表，不再是 mock |
| 商务 | 「能接入工厂现有 Modbus 智能电表」从「计划中」变成「已交付」 |

### 1.3 不在子项目 1.5 范围内

明确推到 1.5 之后或永远不做：

- **OPC-UA / BACnet / IEC-104 / DLT/645 / MQTT**：1.5 只做 Modbus TCP + RTU。其他协议留作子项目 1.6 / 1.7。
- **边缘网关部署形态**：1.5 是中心化拉，Spring Boot 直连仪表/PLC。边缘 collector + 中心 ingester 的双层架构留给后续。
- **YAML 在线编辑**：MVP 只支持「修改 YAML 文件 → 重启服务 → 重新加载配置」。运行时 CRUD（前端编辑点位）留给后续。
- **采集器自动发现**：不做 Modbus 设备扫描或地址自动发现，所有点位由 YAML 显式声明。
- **协议级安全**：Modbus 本身没加密；1.5 假设采集网络是受信内网（OT 网或专用 VLAN），不做 TLS 隧道封装。
- **历史数据回填**：仪表本地存储的历史读数不主动 dump 进来。系统只采上线后的数据。

---

## 2. 核心约束与前置条件

### 2.1 技术决策

按用户决策，spec 锁定以下：

| 决策点 | 选择 | 替代项 / 理由 |
|---|---|---|
| 协议 | **Modbus TCP + RTU 只做这两种** | 中国工业现场 80%+ 智能电表/能源仪表走 Modbus；其他协议按需后续加 |
| 库 | **不自研，使用 [j2mod](https://github.com/steveohara/j2mod)**（Apache 2.0，主流 Java Modbus 库，TCP+RTU 都支持） | 不要再写自己的协议栈；j2mod 既有 master 又有 slave（slave 用来跑 IT 不用真硬件） |
| 部署 | **中心化拉**，Spring Boot 直连仪表 | 简单；OT/IT 网络由部署方打通；边缘网关后续再加 |
| 配置 | **YAML 文件**（`collector.yml`），重启加载 | 第一阶段不做 DB 配置 + UI CRUD；YAML 用 Spring `@ConfigurationProperties` 绑定 |
| 部署单元 | **现有 ems-app 进程内新增 ems-collector module** | 不拆独立服务；ems-collector 是 Spring Bean，启动时读 YAML，按 device 启 polling task |
| 数据出口 | **复用既有 InfluxDB ingest 路径**（`MeterReadingPort` 接口） | 子项目 1 已经定义 InfluxDB 接入点，采集层写入这里就和 mock-data 走同一通路 |
| RTU 串口库 | **[jSerialComm](https://github.com/Fazecast/jSerialComm)**（Apache 2.0，跨平台串口库，j2mod 默认依赖它） | RTU 必须串口；jSerialComm 比 RXTX 维护好 |

### 2.2 关键不变量

- **采集层不修改既有 `meters` 表 schema**：1.5 只新增 collector-side 配置，不改 `meters` / `org_nodes` / `tariff_*` 任何已发布表。
- **每个 YAML device 必须映射到一条已存在的 `meters` 记录**：通过 `meter-code` 字段关联。如果 meter-code 在 meters 表里找不到，启动 fail-fast，不偷偷创建。
- **InfluxDB 写入语义不变**：写入的 measurement / tag-key / tag-value 完全沿用 meters 表的 `influx_measurement`、`influx_tag_key`、`influx_tag_value` 字段。看板 / 报表 / 分摊 完全无感知地切换数据源。
- **采集器关闭不应影响业务读路径**：collector 可以被独立 disable（`ems.collector.enabled=false`），但 dashboard / 报表 / 分摊 不受影响（因为读的是 InfluxDB，写不写新数据是另一回事）。
- **断网期间不丢点（best-effort）**：本地内存 buffer + 重连后批量补写。MVP 用内存 buffer（容量 10000 点 / device），溢出最旧的丢；持久化 buffer 留给后续。

### 2.3 不引入的复杂度

- **不做 change-of-value 触发**：所有点位都按固定周期 polling。COV 留给后续。
- **不做 Modbus 子站（slave）**：本系统只做 master，不模拟从机给上游 SCADA 读。j2mod 的 slave 类只在测试里用。
- **不做用户自定义寄存器解码脚本**：解码限定在内置 `data-type` 枚举（UINT16 / INT16 / UINT32 / INT32 / FLOAT32 / FLOAT64 / 累加翻转检测）。
- **不做协议网关合并**：一个 YAML device 对应一个独立 TCP 连接 / 串口，不做 Modbus gateway 多 unit-id 共享。
- **不做高可用**：collector 单点，宕机重启即可。

---

## 3. 架构总览

### 3.1 新模块

```
factory-ems/
├── ems-collector/                 ← 新增模块
│   ├── pom.xml                    (依赖 j2mod, jSerialComm, ems-meter, ems-timeseries)
│   ├── src/main/java/com/ems/collector/
│   │   ├── config/                YAML binding (CollectorProperties, DeviceConfig, RegisterConfig)
│   │   ├── transport/             ModbusMaster 抽象 + TcpMaster + RtuMaster impl (j2mod 包装)
│   │   ├── codec/                 RegisterDecoder：寄存器字节 → typed value（含 endian + scale）
│   │   ├── service/               CollectorService（按设备调度 polling）
│   │   ├── poller/                DevicePoller（每个 device 一个 task；含重试/降频/buffer）
│   │   ├── health/                CollectorHealthIndicator + Micrometer 指标
│   │   └── controller/            GET /api/v1/collector/status（只读，列出每个 device 的 state）
│   └── src/test/java/             含 j2mod 内嵌 slave 的 IT
└── ems-app/
    └── pom.xml                    + ems-collector 依赖
```

### 3.2 配置模型（YAML）

```yaml
# collector.yml — 由 ems-app 在启动时通过 spring.config.import 加载
ems:
  collector:
    enabled: true
    devices:
      - id: meter-A1
        meter-code: MOCK-M-ELEC-001          # 必须能在 meters 表查到
        protocol: TCP                        # TCP | RTU
        host: 192.168.10.21
        port: 502
        unit-id: 1
        polling-interval-ms: 5000
        timeout-ms: 1000
        retries: 3
        backoff-ms: 2000                     # 连错 N 次后降频到这个间隔
        max-buffer-size: 10000
        registers:
          - name: voltage_a
            address: 0x2000
            count: 2                         # 读多少个 16-bit 寄存器
            function: HOLDING                # HOLDING | INPUT | COIL | DISCRETE_INPUT
            data-type: FLOAT32
            byte-order: ABCD                 # ABCD | CDAB | BADC | DCBA
            scale: 1.0
            unit: V
            ts-field: voltage_a              # 写到 InfluxDB 的 field 名
          - name: power_active
            address: 0x2014
            count: 2
            function: HOLDING
            data-type: FLOAT32
            byte-order: ABCD
            scale: 0.001                     # 仪表用 W，系统用 kW
            unit: kW
            ts-field: power_active
          - name: energy_total
            address: 0x4000
            count: 2
            function: HOLDING
            data-type: UINT32
            byte-order: ABCD
            scale: 0.01                      # 仪表用 0.01kWh 单位
            unit: kWh
            ts-field: energy_total
            kind: ACCUMULATOR                # 累计量；用于翻转检测

      - id: meter-RTU-001
        meter-code: MOCK-M-ELEC-002
        protocol: RTU
        serial-port: /dev/ttyUSB0
        baud-rate: 9600
        data-bits: 8
        stop-bits: 1
        parity: NONE
        unit-id: 1
        polling-interval-ms: 10000
        # ...
        registers: [...]
```

### 3.3 关键数据流

```
┌─────────────┐     ┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│ Modbus 设备  │ →→  │ DevicePoller │ →→  │  Decoder     │ →→  │ ReadingBatch │
│ (TCP/RTU)   │     │ (j2mod read) │     │ (scale/unit) │     │  (内存)       │
└─────────────┘     └──────────────┘     └──────────────┘     └──────┬───────┘
                                                                     ↓
                                                          ┌──────────────────┐
                                                          │ MeterReadingPort │
                                                          │ (现有 Influx 接入) │
                                                          └──────────────────┘
                                                                     ↓
                                                          ┌──────────────────┐
                                                          │ InfluxDB         │
                                                          └──────────────────┘
                                                                     ↓
                                          (无感切换，下游 看板 / 报表 / 分摊 全部生效)
```

### 3.4 调度

- 启动时：`CollectorService.init()` 读 `CollectorProperties.devices`，校验 meter-code 都在 meters 表，每个 device 创建一个 `DevicePoller` 并交给 `ScheduledExecutorService`。
- 运行时：每个 poller 按 `polling-interval-ms` 周期触发；连错 N 次后切到 `backoff-ms` 周期，恢复后切回正常周期。
- 关闭时：每个 poller `close()` j2mod transport，flush 内存 buffer。

### 3.5 健康可观测

- `/api/v1/collector/status` 返回每个 device 的 `{deviceId, state: HEALTHY|DEGRADED|UNREACHABLE, lastReadAt, errorCount, lastError}`。
- Spring Boot Actuator `/actuator/health/collector` 聚合：所有 device HEALTHY → UP；任一 UNREACHABLE > 阈值 → DEGRADED；全部 UNREACHABLE → DOWN。
- Micrometer 指标：`ems.collector.read.success`、`ems.collector.read.failure`、`ems.collector.read.duration`（按 device 打 tag）。

---

## 4. Plan 拆分

### Plan 1.5.1 — Modbus TCP MVP（本次启动）

**估算 13–15 个 Phase（A–N），是子项目 1.5 的 v0.1.0**。

| Phase | 内容 |
|---|---|
| A | ems-collector 模块骨架 + j2mod 依赖 + maven 接入 ems-app |
| B | YAML schema + `CollectorProperties` ConfigurationProperties + 启动校验（meter-code 存在性） |
| C | `ModbusMaster` transport 抽象 + `TcpModbusMaster`（j2mod 包装）+ 连接生命周期 / 重连 |
| D | `RegisterDecoder`：UINT16/INT16/UINT32/INT32/FLOAT32/FLOAT64 + 4 种 byte-order + scale |
| E | `DevicePoller`：单设备 polling loop + 重试 + 降频 + 内存 buffer |
| F | `CollectorService`：按 YAML 装配 pollers + ScheduledExecutorService + 优雅关闭 |
| G | 写入 InfluxDB：复用 `MeterReadingPort`，组装 measurement+tags+fields |
| H | 累加量翻转检测（ACCUMULATOR kind）：本次值 < 上次值 → 减去 wrap-around |
| I | `/api/v1/collector/status` controller + DeviceState DTO |
| J | `CollectorHealthIndicator` + Micrometer 指标 |
| K | j2mod 内嵌 slave 测试设施：`ModbusSlaveSimulator`（在 IT classpath 下启 TCP slave） |
| L | 集成测试：启动 slave + collector + InfluxDB testcontainer，校验数据落库 |
| M | 前端只读状态面板：`/collector` 路由展示 `DeviceState` 列表（轮询 5s） |
| N | docs/ops/runbook 更新 + verification 日志 + tag `v1.5.1-plan1.5.1` |

### Plan 1.5.2 — Modbus RTU + 工程化（v0.2.0）

| Phase | 内容 |
|---|---|
| A | `RtuModbusMaster` 实现（jSerialComm + j2mod RTU） |
| B | YAML 增加 RTU 字段（serial-port / baud-rate / parity 等） |
| C | 多设备共享串口（同一 /dev/tty 上挂多个 unit-id）的并发控制 |
| D | 持久化 buffer（断网超过内存上限的部分落到本地 SQLite）+ 重连补传 |
| E | 配置热加载（`/api/v1/collector/reload`，仅 ADMIN）：不重启进程更新 device 列表 |
| F | RTU 集成测试（基于虚拟串口对 socat / com0com 或 j2mod RTU loopback） |
| G | E2E：`/collector` 页面 + reload 操作 |
| H | tag `v1.5.2-plan1.5.2` |

### Plan 1.5.3 — 真实部署强化（v1.5.0）

| Phase | 内容 |
|---|---|
| A | TLS 隧道封装（stunnel 或 spring-boot 自带，给 Modbus TCP 套 TLS） |
| B | 连接池 + 限流（同一 IP 多 device 共享 TCP 连接） |
| C | 远程 collector heartbeat → 中心 alarm（接 Plan 1.3 的报警表） |
| D | 部署文档：onsite checklist + 网络拓扑示例 + 调试工具 |
| E | 真实仪表对接 demo（用 1 块物理电表，记录从拆箱到上线全过程） |
| F | tag `v1.5.0` 子项目 1.5 正式发布 |

---

## 5. 数据契约

### 5.1 YAML → InfluxDB 写入

| YAML 字段 | InfluxDB 落点 | 说明 |
|---|---|---|
| `meter-code` | 通过 meters 表反查 `influx_measurement` / `influx_tag_key` / `influx_tag_value` | 不用 YAML 里直接写 measurement，避免和 meters 表脱钩 |
| `register.ts-field` | InfluxDB field key | 同一 device 多寄存器写到同一 measurement 的不同 field |
| `register.scale × 原始值` | InfluxDB field value | float |
| polling 触发时刻 | InfluxDB timestamp | 秒级；不取设备返回的内嵌时间戳（Modbus 也没这个） |

### 5.2 累加量 wrap-around 规则

UINT32 累加器的最大值是 0xFFFFFFFF = 4_294_967_295。当 `current < previous` 时认为翻转：

```
delta = (max_uint32 - previous) + current + 1
```

并把 `delta` 当作本周期增量写入。**绝对值仍然写 current**，但下游（rollup / 计费）应该用 delta。MVP 先把 `delta` 也写一个伴随 field（`{ts-field}_delta`）；rollup 改造留给后续。

### 5.3 错误状态机

DevicePoller 的状态：

```
HEALTHY  ──(连续 retries 次失败)──>  DEGRADED ──(连续 backoff 周期失败)──>  UNREACHABLE
   ↑                                     │                                      │
   └─────(任一次成功)─────────────────────┴──────────────────────────────────────┘
```

DEGRADED 时降频 polling；UNREACHABLE 时停止 polling 仅心跳重连。

---

## 6. 风险与待验证点

1. **j2mod 在 Java 21 / Spring Boot 3.x 下兼容性**：j2mod 最新版（3.2.x）声明支持 Java 11+，需要在 Phase A spike 验证一次基本读寄存器能跑。
2. **Modbus TCP 长连接稳定性**：工厂网络可能 NAT 超时断流；需要 keep-alive 或定期重连策略。Phase C 决定。
3. **多 device 并发**：MVP 每个 device 一个线程；如果点位上百个，线程数不能爆。Phase F 用线程池 + 限流（每个 device 一个 future，不是一个线程）。
4. **YAML 校验时机**：启动时强校验 meter-code 全部存在；如果允许部分 device 失败启动其他正常，需要明确策略。MVP 默认 fail-fast；后续可加 `partial-startup: true`。
5. **时间戳准确性**：polling 周期 5s 但实际触发可能漂移（GC、调度延迟），InfluxDB 时间戳应在写入时取，不在 schedule 时取。
6. **断网持续时间长**：内存 buffer 10000 点 / 5s 周期 = ~14 小时容量。再长就丢点。Phase 1.5.2 加持久化 buffer。
7. **Modbus RTU 串口在容器里**：需要 `--device=/dev/ttyUSB0` 挂载到 docker。Plan 1.5.2 / 1.5.3 部署文档要写清。

---

## 7. Definition of Done（子项目 1.5 v1.5.0）

- 至少 3 个 device（mix TCP + RTU）按 YAML 配置上线，连续 24h 无丢点
- `/collector` 页面正确反映 device 状态，UNREACHABLE 设备能在 5s 内被识别
- 看板/报表/分摊 全链路读到的数据来自 collector（关闭 mock-data CLI 后系统不靠合成数据）
- Modbus 协议错误（CRC / 非法地址 / 超时）能在 audit log 看到，不静默丢
- 部署文档允许一名工厂实施工程师在不看源码的情况下，把一台陌生 Modbus 电表接入系统

---

## 8. 启动建议

第一会话先做 Plan 1.5.1 Phase A–C（骨架 + YAML 解析 + TCP master 接通 j2mod 内嵌 slave），跑通一次 read holding registers。剩下 phase 在后续会话推进。
