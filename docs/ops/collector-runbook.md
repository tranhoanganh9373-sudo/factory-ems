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

UNREACHABLE 只在出问题时进入，30s 重连节奏既不打爆网络，也能让服务及时感知恢复。

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

- 第一次 polling 没 `_delta` field 是预期（spec §5.2），从第二次开始才有
- 进程重启后第一次也没 delta，MVP 阶段丢一周期可接受
- delta 突然变得很大：检查仪表是否真的有 wrap-around（UINT32 max ≈ 42.9 亿，按 0.01 kWh scale 约 4290 万 kWh），或者人工把仪表清零了

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

---

## 10. Plan 1.5.2 增量（v0.2.0）

### 10.1 Modbus RTU

YAML 增加 RTU 字段（详见 §2.1）。校验：
- `serial-port` 必填（`/dev/ttyUSB0` 或 `COM3`）
- `baud-rate` 必填，`1200..115200`
- `data-bits` 默认 8（`5..8`），`stop-bits` 默认 1（`1` 或 `2`），`parity` 默认 NONE

容器化部署 RTU：
```bash
# Linux：把 USB 串口设备透传到容器
docker run --device=/dev/ttyUSB0 ...
```

### 10.2 多 device 共串口

同 `/dev/ttyUSB0` 上挂多个 unit-id（典型 RS-485 总线挂多块仪表）：
- `SerialPortLockRegistry` 按串口路径键控 ReentrantLock（标准化 trim+lowercase）
- 同串口 device 的 `pollOnce()` 全局串行；不同串口并发不受影响
- 顺序调度由 `CollectorService.scheduleAfter` 自然提供 fairness（ReentrantLock 内置 fair=true）

### 10.3 持久化 buffer（SQLite）

InfluxDB 抖动 / 断网时，写失败的 reading 落到 `./data/collector-buffer.db`：

| 配置 | 默认 | 说明 |
|---|---|---|
| `ems.collector.buffer.path` | `./data/collector-buffer.db` | 必须可写（容器需挂载 volume） |
| `max-rows-per-device` | 100000 | 超限 FIFO 丢最旧 |
| `ttl-days` | 7 | vacuum 清理超期数据 |
| `flush-interval-ms` | 30000 | 后台 task 周期 |

`BufferFlushScheduler` 后台线程每 30s 取最早 1000 条尝试补传 InfluxDB；任一失败 break 等下轮。
每 1 小时跑一次 `VACUUM` 清掉 `sent=1` 与超 TTL 的行。

### 10.4 配置热加载

```bash
# 改 collector.yml 后，触发 reload（无需重启进程）
curl -X POST -H "Authorization: Bearer $TOKEN" \
     http://factory-ems:8888/api/v1/collector/reload
```

返回示例：
```json
{
  "code": 0,
  "data": {
    "added": ["meter-A1"],
    "modified": [],
    "removed": ["meter-X3"],
    "unchanged": 12
  }
}
```

不变化的 device polling 不受影响。reload 写 audit_logs（`COLLECTOR_RELOAD` action）。
失败语义：YAML 解析或校验失败 → 返 400 + 错误清单，**当前运行状态不变**。

---

## 11. Plan 1.5.3 增量（v0.3.0）

### 11.1 TCP 连接池

同物理仪表（`host:port`）挂多 unit-id 时共享一个 `ModbusTCPMaster` 连接：
- `TcpConnectionPool` 按 `(host, port)` 键控 PoolEntry
- 引用计数：device acquire +1，shutdown release -1，归零自动 close + 移除
- 每个 PoolEntry 携带 ReentrantLock；多 unit-id read 通过 lock 串行化

适合场景：一台多功能仪表通过一个 TCP socket 暴露 N 个测量回路。

### 11.2 状态告警接入 audit_logs

`AlarmTransitionListener` 实现 `DevicePoller.StateTransitionListener`，每次状态切换写一条
audit：

| 字段 | 值 |
|---|---|
| action | `COLLECTOR_STATE_CHANGE` |
| resource_type | `COLLECTOR` |
| resource_id | deviceId |
| summary | `{from} → {to}: {reason}` |
| actor_username | `system` |

通过既有 `/admin/audit` 页面直接查（无需独立 alarm UI）。后续 Plan 1.6 再考虑独立 alarm 表。

### 11.3 现场实施 SOP（实地装机 checklist）

#### 装机前（远程）

- [ ] 工厂 / 车间网络拓扑图：仪表 IP 段、collector 部署机所在网段、是否同 VLAN
- [ ] 仪表型号 + 通信参数清单（每台一行）：协议（TCP/RTU）、IP/串口、unit-id、波特率、寄存器映射文档
- [ ] 部署机硬件：CPU 4 核 + 8 GB + 100 GB SSD（mock-data CLI 实测够 50 device × 5s polling）
- [ ] 部署机端口开放：8888（前端）、8086（InfluxDB 仅内网）、5432（PG 仅内网）
- [ ] 部署机能 ping 通 InfluxDB / PG / 仪表 IP

#### 装机当天（现场）

- [ ] 拷贝 `docker-compose.yml` + `.env` + `collector.yml` 到部署机
- [ ] `docker compose up -d` 启栈；`/actuator/health` 全 UP
- [ ] 先用 `mbpoll` / `Modbus Poll` 等第三方工具直读 1 块仪表的关键寄存器，确认仪表可达 + 寄存器地址正确
- [ ] 拷贝同款配置到 collector.yml；启 collector；`/api/v1/collector/status` 看到 HEALTHY
- [ ] InfluxDB 内 query 该 measurement，确认每 polling 周期都有新数据
- [ ] 看板曲线确认数据合理（电压在 220 ± 10V 范围、功率非负、电度递增）
- [ ] 持续观察 1 小时，无 DEGRADED / UNREACHABLE 转移

#### 装机后（远程跟踪）

- [ ] 接入 Prometheus 抓取 `ems.collector.*` metrics，告警规则：
      `failure_rate > 0.05 持续 10min` 或 `state=UNREACHABLE > 30min`
- [ ] 24 小时内复查 `/admin/audit` 看是否有 `COLLECTOR_STATE_CHANGE` 异常
- [ ] 1 周内做一次 buffer flush 复查：`SELECT count(*) FROM collector_buffer WHERE sent=0` < 100

### 11.4 排查工具箱

| 场景 | 工具 |
|---|---|
| Modbus TCP 端口能不能通 | `nc -zv 192.168.10.21 502` |
| 能不能直读寄存器 | `mbpoll -m tcp -a 1 -r 0x2000 -c 2 192.168.10.21` |
| Modbus RTU 串口活着不 | `screen /dev/ttyUSB0 9600` 看是否有数据流 |
| j2mod 协议层 trace | `application.yml` 加 `logging.level.com.ghgande.j2mod: DEBUG` |
| InfluxDB 看历史数据 | `influx query 'from(bucket:"factory_ems") |> range(start:-1h)'` |
| Buffer 积压情况 | `sqlite3 ./data/collector-buffer.db 'SELECT device_id, count(*) FROM collector_buffer WHERE sent=0 GROUP BY 1'` |

### 11.5 TLS 隧道封装（可选；OT 内网 vs 互联网）

OT 内网通常受信，多数场景**不需要** TLS。collector 经互联网或跨厂区访问仪表时，
可选两种方案：

#### 方案 A：stunnel sidecar

```
[collector container] ── plain TCP ──▶ [stunnel]
                                          │ TLS over WAN
                                          ▼
                                     [stunnel] ── plain TCP ──▶ [Modbus 仪表]
```

优点：collector 不改一行代码；stunnel 独立维护证书。
缺点：双端都要部署 stunnel。

#### 方案 B：Spring SSL Bundle + 自定义 SocketFactory

j2mod 不原生支持 TLS。需要自实现 `AbstractSerialConnection` 替代 `TCPMasterConnection`
让 socket 走 SSLSocketFactory。代码改动较大；后续 Plan 1.6 评估收益再做。

**当前做法**：保持 Modbus TCP 明文 + 部署在受信内网，网络层（防火墙 / VLAN / VPN）
做隔离即可。

---

## 12. 已知不接 1.5.3（推 Plan 1.6 / 后续）

- TLS over Modbus（仅 docs 提及，未实现）
- 独立 alarm 表 + 通知通道（短信 / 邮件 / 企微 / 钉钉）
- 边缘 gateway 双层架构（中心 collector → 边缘 collector → 仪表）
- 配置 CRUD UI（前端编辑 collector.yml → DB → reload）
- 仪表自动发现（Modbus 扫描、BACnet whois）
- OPC-UA / IEC-104 / MQTT 协议（独立子项目 1.6 起步）
