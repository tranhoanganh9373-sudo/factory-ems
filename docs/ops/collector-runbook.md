# Collector Runbook · 子项目 1.5 · Plan 1.5.1

**适用版本：** 1.5.1-plan1.5.1 起
**模块：** `ems-collector`
**Spec：** `docs/superpowers/specs/2026-04-27-factory-ems-subproject-1.5-data-acquisition.md`
**Plan：** `docs/superpowers/plans/2026-04-27-factory-ems-plan-1.5.1-modbus-tcp-mvp.md`

---

## 0. 一句话

> Collector 按 `collector.yml` 里声明的 device 列表，定期通过 j2mod TCP 读 Modbus 寄存器，
> 解码后写 InfluxDB；看板 / 报表 / 分摊 全链路无感切换数据源（与 mock-data CLI 走同一份 measurement/tag schema）。

Plan 1.5.1 只支持 Modbus TCP；RTU 推到 Plan 1.5.2。

---

## 1. 启用 Collector（生产部署）

### 1.1 前置条件

- 库里至少一条 `meters` 记录，且 `code` / `influx_measurement` / `influx_tag_key` / `influx_tag_value` 全填
- 部署机能 TCP 路由到目标 Modbus 仪表（502 端口）
- InfluxDB（既有 ems-timeseries 配置）正常 — 通过 `/actuator/health/influx` 验证

### 1.2 collector.yml 模板

```yaml
ems:
  collector:
    enabled: true                 # 开关；false 时整个 collector 跳过
    devices:
      - id: meter-A1              # 自由命名，仅日志/metrics 用，不必等于 meterCode
        meter-code: MOCK-M-ELEC-001     # 必须能在 meters 表查到
        protocol: TCP
        host: 192.168.10.21
        port: 502                 # 默认 502 可省略
        unit-id: 1                # Modbus slave id (1..247)
        polling-interval-ms: 5000 # 至少 1000；防止误配把仪表打挂
        timeout-ms: 1000          # ≤ polling-interval-ms
        retries: 3                # 周期内重试，0 = 失败立即降级
        backoff-ms: 25000         # ≥ polling-interval-ms；DEGRADED 状态下用
        max-buffer-size: 10000    # 内存 buffer（Plan 1.5.2 才会用）
        registers:
          - name: voltage_a
            address: 0x2000
            count: 2
            function: HOLDING
            data-type: FLOAT32
            byte-order: ABCD
            scale: 1.0
            unit: V
            ts-field: voltage_a
          - name: power_active
            address: 0x2014
            count: 2
            function: HOLDING
            data-type: FLOAT32
            byte-order: ABCD
            scale: 0.001          # 仪表用 W，系统用 kW
            unit: kW
            ts-field: power_active
          - name: energy_total
            address: 0x4000
            count: 2
            function: HOLDING
            data-type: UINT32
            byte-order: ABCD
            scale: 0.01           # 仪表 0.01 kWh 单位
            unit: kWh
            ts-field: energy_total
            kind: ACCUMULATOR     # 写伴随 _delta field
```

### 1.3 启动验证（按顺序逐项）

1. **进程启动** — 后端日志看到：`collector started: N device(s)`
2. **健康端点** — `GET /actuator/health/collector` 返回 UP，`details.healthy = N`
3. **状态接口** — `GET /api/v1/collector/status`（需 ADMIN）返回每个 device，state=HEALTHY
4. **看板曲线** — dashboard 上对应 meter 的实时曲线开始更新，时间戳间隔 ≈ pollingIntervalMs
5. **指标端点** — `GET /actuator/metrics/ems.collector.read.success?tag=device:meter-A1` count > 0

---

## 2. YAML schema 详解

### 2.1 设备级（DeviceConfig）

| 字段 | 取值 | 必填 | 说明 |
|---|---|---|---|
| id | `[A-Za-z0-9_-]+` | ✓ | 设备逻辑 id；用作 metrics tag、状态接口主键 |
| meter-code | string | ✓ | meters 表的 code；启动时强校验存在性 |
| protocol | TCP / ~~RTU~~ | ✓ | 1.5.1 仅 TCP，RTU 推到 1.5.2 |
| host | IP / hostname | TCP | TCP 必填 |
| port | int | | 默认 502 |
| unit-id | 1..247 | ✓ | Modbus slave id |
| polling-interval-ms | ≥ 1000 | ✓ | 正常周期 |
| timeout-ms | ≤ pollingInterval | ✓ | 单次 read 超时 |
| retries | 0..10 | ✓ | 周期内重试次数；0 = 失败立即降级 |
| backoff-ms | ≥ pollingInterval | | 默认 5×polling；DEGRADED 状态下周期 |
| max-buffer-size | ≥ 100 | | 默认 10000；1.5.1 内存 buffer 未启用 |
| registers | ≥ 1 | ✓ | 寄存器列表（见下表） |

### 2.2 寄存器级（RegisterConfig）

| 字段 | 取值 | 必填 | 说明 |
|---|---|---|---|
| name | string | ✓ | 仅 logging；可读名 |
| address | ≥ 0 | ✓ | 起始地址，可写 0x2000 |
| count | 1..4 | ✓ | 16-bit 寄存器数；必须等于 dataType.wordCount() |
| function | HOLDING / INPUT / COIL / DISCRETE_INPUT | ✓ | Modbus 功能码；COIL/DISCRETE_INPUT 仅允许 dataType=BIT |
| data-type | UINT16 / INT16 / UINT32 / INT32 / FLOAT32 / FLOAT64 / BIT | ✓ | |
| byte-order | ABCD / CDAB / BADC / DCBA | | 默认 ABCD；详见 §2.3 |
| scale | BigDecimal | | 默认 1；可负 |
| unit | string | | 仅 logging |
| ts-field | `[A-Za-z_][A-Za-z0-9_]*` | ✓ | InfluxDB field key；同 device 内唯一 |
| kind | GAUGE / ACCUMULATOR / COUNTER | | 默认 GAUGE；ACCUMULATOR/COUNTER 自动写 `${ts-field}_delta` |

### 2.3 byte-order 语义（多字节寄存器）

给定 4 字节 [A B C D] 的标准大端表示：

| 配置 | 等效字节序 | 典型场景 |
|---|---|---|
| ABCD | A B C D | IEEE 标准；多数西门子 / 施耐德 |
| CDAB | C D A B（每 4 字节内 16-bit 字两两交换） | 国产电表（华立 / 德力西）word-swap |
| BADC | B A D C（每 16-bit 字内字节交换） | 较少见 |
| DCBA | D C B A（整体反转）| 完全小端 |

16-bit 数据：ABCD == CDAB（无操作），BADC == DCBA（字节交换）。

8-byte FLOAT64 同规则套用：CDAB 每 4 字节段独立交换，BADC 每 2 字节独立交换。

---

## 3. 状态机

```
HEALTHY ──cycleFailed──▶ DEGRADED ──N×backoff cycles failed──▶ UNREACHABLE
   ▲                        │                                      │
   └──── any read success ──┴──────────────────────────────────────┘
```

| 当前 | 触发 | 转移 | 副作用 |
|---|---|---|---|
| HEALTHY | retries+1 次重试都失败 | DEGRADED | scheduler 周期切到 backoffMs；audit transition |
| DEGRADED | 任一次 read 成功 | HEALTHY | 周期切回 pollingIntervalMs；连错计数清零 |
| DEGRADED | 连续 3 次 cycle 失败 | UNREACHABLE | 周期切到 30s 重连；停止常规 polling |
| UNREACHABLE | 任一次 read 成功 | HEALTHY | 同上恢复 |
| UNREACHABLE | 失败 | UNREACHABLE | 留 30s 后再试 |

UNREACHABLE 只在出问题时进入，30s 重连节奏是为了不打爆网络也不让服务长期不感知恢复。

---

## 4. 一次 polling 周期会发生什么

1. scheduler 触发 device 的 task
2. master 若没连接则 `open()`（TCP 三次握手）
3. 按 register 列表逐一读取（一个 device 内串行）
4. 每个寄存器：j2mod 读 → byte[] → `RegisterDecoder.decode(byteOrder, dataType, scale)` → BigDecimal
5. ACCUMULATOR/COUNTER 类的 register 走 `AccumulatorTracker.observe()`，计算 delta
6. 全部读成功后组装一个 InfluxDB Point（多 field）→ `writeApi.writePoint(bucket, org, point)`
7. snapshot 状态更新（lastReadAt / successCount++）
8. CollectorService 立即查 `nextDelayMs()` re-schedule

任一寄存器失败 → 整个 cycle 失败 → 重试 retries 次 → 仍失败则计入 cycleErrors，状态机推进。

---

## 5. 排查 (`troubleshooting`)

### 5.1 启动失败：collector configuration invalid

启动日志：
```
collector configuration invalid:
  - devices[0] (id=foo): meterCode 'XYZ' not found in meters table
```

→ 检查 `meters` 表是否有 `code='XYZ'`。collector 启动期 fail-fast，进程不会启起来。

### 5.2 device 一直 UNREACHABLE

按顺序排查：

```bash
# 1. 部署机到仪表 ICMP
ping <host>

# 2. TCP 502 端口
nc -zv <host> 502

# 3. 第三方工具直读寄存器（确认仪表可达 + 地址正确）
mbpoll -m tcp -a <unit-id> -r <addr> <host>     # Linux modbus-tools

# 4. 后端日志
docker logs factory-ems-factory-ems-1 --tail 200 | grep "device <id>"

# 5. j2mod 调试日志（最后手段）
# 在 application.yml 里加：
#   logging.level.com.ghgande.j2mod: DEBUG
```

### 5.3 数据写入了但看板看不见

- 检查 meter 的 `influxMeasurement` / `influxTagKey` / `influxTagValue` 与看板查询是否一致
- `GET /actuator/metrics/ems.collector.read.success?tag=device:<id>` 看 count 是否在涨
- `influx query 'from(bucket:"factory_ems") |> range(start:-5m) |> filter(fn: (r) => r["_measurement"] == "<measurement>")' ` 看是否有数据
- 时区：collector 用 `Instant.now()` UTC 时戳；看板按本地时区显示，若部署机 NTP 漂移会显示在错误时段

### 5.4 ACCUMULATOR delta 异常

- 第一次 polling 没 `_delta` field 是预期（spec §5.2）；从第二次开始才有
- 进程重启后第一次也没 delta，丢一周期是 MVP 接受
- delta 看起来突然很大：检查仪表是否真的有 wrap-around（UINT32 max ≈ 42.9 亿，按 0.01 kWh scale 约 4290 万 kWh），或者人工把仪表清零了

### 5.5 device 报"timeoutMs must be <= pollingIntervalMs"

启动校验失败。`timeout-ms` 不能大于 `polling-interval-ms`，否则 read 还没返回就该启下一周期。

---

## 6. Metrics & Health

### 6.1 Prometheus / Actuator

| 端点 / 指标 | 内容 |
|---|---|
| `/actuator/health/collector` | UP / DOWN / OUT_OF_SERVICE / UNKNOWN |
| `ems.collector.read.success{device=...}` | 按 device 的成功 cycle 计数 |
| `ems.collector.read.failure{device=...}` | 按 device 的失败 cycle 计数 |
| `ems.collector.read.duration{device=...}` | timer，每次 cycle 耗时 |

### 6.2 健康聚合规则

| 局面 | actuator status | details |
|---|---|---|
| `enabled=false` | UNKNOWN | `disabled: true` |
| 无 device 配置 | UP | `devices: 0` |
| 全 HEALTHY | UP | counts |
| 部分 DEGRADED 无 UNREACHABLE | UP | counts |
| 任一 UNREACHABLE 但非全部 | OUT_OF_SERVICE | counts |
| 全 UNREACHABLE | DOWN | counts |

---

## 7. 已知限制（推到 Plan 1.5.2 / 1.5.3）

- **不支持 RTU**（串口）— 启动校验直接拒绝
- **不支持配置热加载** — 修改 `collector.yml` 必须重启进程
- **不持久化 buffer** — 断网超过内存上限会丢点（Plan 1.5.2 加 SQLite buffer）
- **不做并发限制** — 一台部署机挂太多 device 时 ScheduledExecutor 线程数上限要手动调整
- **不做 TLS 隧道** — 假设采集网络受信内网（OT/VLAN）
- **不做 Modbus 子站** — 只做 master，不模拟从机给上游 SCADA 读
- **进程重启 ACCUMULATOR 丢一周期 delta** — checkpoint 不持久化

---

## 8. 测试覆盖

- `CollectorPropertiesValidatorTest` 11 case — YAML 跨字段校验
- `RegisterDecoderTest` 31 case — 6 dataType × 4 byteOrder = 24 组合穷举 + 边界
- `TcpModbusMasterTest` 11 case — j2mod 包装 + 内嵌 slave
- `DevicePollerTest` 10 case — 状态机 8 种迁移
- `AccumulatorTrackerTest` 9 case — wrap-around + scale + multi-tsField
- `DevicePollerAccumulatorTest` 3 case — poller × accumulator 集成
- `CollectorServiceTest` 7 case — 调度 + 优雅关闭 + 多 device 隔离
- `InfluxReadingSinkTest` 5 case — Point 拼装 + 错误吞掉
- `CollectorStatusControllerTest` 5 case — REST DTO 映射 + 截断
- `CollectorMetricsTest` 4 case — Micrometer counter/timer
- `CollectorHealthIndicatorTest` 6 case — 6 种 health 聚合
- `CollectorEndToEndIT` 5 case — 真 j2mod slave + 真 CollectorService + 真 RegisterDecoder 端到端

合计 **107 单元 + 集成测试**，全绿。

---

## 9. 下一阶段（Plan 1.5.2 预告）

- Modbus RTU 支持（jSerialComm 串口 IO）
- 配置热加载 `POST /api/v1/collector/reload`（仅 ADMIN）
- 持久化 buffer（SQLite）+ 断网恢复批量补传
- 多 device 共享串口的并发控制
