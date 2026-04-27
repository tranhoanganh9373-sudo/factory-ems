# Factory EMS · Plan 1.5.2 · RTU + 工程化加固

**Goal:** 在 Plan 1.5.1 v1.5.1 之上加 Modbus RTU（串口）支持 + 多设备共享串口 + 持久化 buffer + 配置热加载 + 端到端 E2E spec。完成后子项目 1.5 接近上线就绪状态（仅差 1.5.3 真机 demo 与现场实施文档）。

**Architecture:** 在 v1.5.1 ems-collector 模块上扩展。复用 1.5.1 的 ModbusMaster 抽象 + DevicePoller 状态机 + ReadingSink，仅在 transport 层 + lifecycle 层增量。

**Tech Stack (增量):**
- [j2mod 3.2.x] 已有，使用 `ModbusSerialMaster` (RTU)
- [jSerialComm](https://github.com/Fazecast/jSerialComm) 已在 pom，1.5.2 才真用
- [SQLite JDBC](https://github.com/xerial/sqlite-jdbc) — 持久化 buffer，零运维（嵌入式文件 DB，与系统主 Postgres 解耦）
- 复用既有：Spring Boot ConfigurationProperties + Bean Validation + Actuator + Micrometer

**Spec reference:** `docs/superpowers/specs/2026-04-27-factory-ems-subproject-1.5-data-acquisition.md`
**Baseline:** Plan 1.5.1 v1.5.1-plan1.5.1（107 单测全绿）

---

## 依赖前提

- Plan 1.5.1 已交付 + tag 已打
- ems-collector 模块结构稳定
- 真硬件可选；本 Plan 内部测试用 j2mod RTU loopback / 虚拟串口对（socat 或 com0com）

---

## 范围边界

### 本 Plan 交付

| 模块 | 内容 |
|---|---|
| `ems-collector`（扩） | RTU 实现 + 共串口控制 + 持久化 buffer + 热加载 endpoint |
| `frontend/`（扩） | 状态页加 reload 按钮 + 配置查看（read-only） |

### 不在本 Plan 内（推到 1.5.3）

- 真实物理 Modbus 仪表对接 demo（"拆箱到上线"工程文档）
- TLS over Modbus / 连接池 / 中心 alarm 接入
- 边缘 gateway 双层架构

### 交付演示场景

1. **RTU 配置上线**：`collector.yml` 声明一台 RTU device（虚拟串口），重启后状态接口看到 HEALTHY
2. **多 device 共串口**：同一 `/dev/ttyUSB0` 上挂 unit-id=1 / 2 / 3 三台，并发 polling 走同一串口（互斥）
3. **持久化 buffer**：故意拔掉 InfluxDB 容器 30 秒，恢复后 buffer 中的数据补传，曲线无空洞
4. **配置热加载**：YAML 文件改完 → `POST /api/v1/collector/reload` → 不重启进程，新 device 上线、移除的 device 关闭
5. **E2E**：登录 → goto `/collector` → 验证状态 + 点 reload 按钮触发 reload

---

## 关键架构决策

1. **RTU master 用 j2mod ModbusSerialMaster**：j2mod 已经包含，不引新依赖
2. **共串口互斥**：每个 unique `serial-port` 路径一个 `ReentrantLock`；同一串口下多个 DevicePoller 在 `pollOnce()` 入口先 acquire 锁。串口物理上就是单工总线，同时发请求会乱
3. **持久化 buffer 用 SQLite**：嵌入式文件 DB，不引外部组件。Schema 极简（id / device_id / payload_json / created_at / sent）。有 sent=false 的旧记录在 sink 写入成功后批量补传 + 标记 sent=true
4. **buffer 容量与 TTL**：单 device 上限 100,000 条 / 7 天；超限丢最旧。SQLite 文件路径配置 `ems.collector.buffer.path` 默认 `./data/collector-buffer.db`
5. **热加载语义**：reload 是"diff + apply"：
   - 旧 YAML device list vs 新 YAML device list（按 id diff）
   - **新增**：构造 master + poller，schedule
   - **删除**：cancel future + close master + remove poller
   - **修改**（同 id 但配置变了）：先 remove old，再 new
   - 重要：reload 期间不影响"未变化"的 device 的 polling
6. **热加载并发安全**：reload 持有 service-level write lock；常规 polling 持 read lock；reload 期间常规 polling 不受影响（read 锁可并发持有），但能阻挡多个 reload 并发
7. **热加载权限**：`POST /api/v1/collector/reload` ADMIN-only，写 audit log（`COLLECTOR_RELOAD`）
8. **YAML 热加载源**：reload 不接受 body；从磁盘文件 `collector.yml` 重新读取（与启动一致）。这样运维不能误把临时 inline YAML 提到生产
9. **RTU 启动校验放开**：CollectorPropertiesValidator 删除"RTU support deferred"硬拒；改为校验 serialPort + baudRate + parity 必填
10. **RTU 测试不依赖物理串口**：j2mod 自身有 RTU loopback；CI 使用 j2mod RTU slave + RTU master 在同一 JVM 内 loopback。Linux 实测可选 socat 创建虚拟串口对（test profile 可选）
11. **buffer 写入异步**：Sink 写 InfluxDB 失败时落 SQLite buffer。InfluxDB 恢复后由后台 flush task 批量补传（每 30s）。这样故障期 polling 不被阻塞
12. **buffer flush 顺序**：FIFO（按 id 升序）。失败时该 batch 整体不标 sent，下次再重试

---

## Phase 索引（估算 Task 数）

| Phase | 范围 | 估 Tasks |
|---|---|---|
| A | RtuModbusMaster 实现（j2mod ModbusSerialMaster + jSerialComm 包装）+ 单测（j2mod RTU loopback fixture） | 6 |
| B | YAML 增加 RTU 字段路径 + Validator 删 RTU 硬拒 + 跨字段校验（serialPort 与 host 互斥）+ 单测 | 4 |
| C | 多 device 共串口：`SerialPortLockRegistry` + `DevicePoller` 通过 lock 串行化 + 单测（2 个 device 同串口顺序读） | 5 |
| D | 持久化 buffer：SQLite schema (Flyway 不进，用 ems-collector 自己的 init script) + `BufferStore` 接口 + `SqliteBufferStore` 实现 + 单测 | 8 |
| E | InfluxReadingSink 接 buffer：写失败 → 落 buffer；后台 `BufferFlushScheduler` 每 30s 补传 + 单测 | 6 |
| F | 配置热加载：`/api/v1/collector/reload` ADMIN-only + `CollectorService.reload(newProps)` diff+apply + 单测（spy on poller.shutdown 调用） | 7 |
| G | 集成测试 `RtuLoopbackIT`：j2mod RTU slave + master 同 JVM；buffer 持久化 IT；热加载 IT（add/remove/modify device） | 6 |
| H | 前端：`/collector` 加 reload 按钮（ADMIN）+ 操作 audit 提示 + 配置只读视图（GET /collector/config） + 单测 | 5 |
| I | E2E spec `collector-status.spec.ts`：登录 → goto `/collector` → 状态可见 + reload 按钮可点（成功 toast） | 3 |
| J | docs runbook 更新（RTU schema / 共串口策略 / buffer 行为 / reload SOP）+ verification log + tag `v1.5.2-plan1.5.2` | 3 |

**合计估算：~53 tasks，5–7 工作日。**

---

## 数据契约增量

### YAML schema（RTU 字段）

```yaml
ems:
  collector:
    enabled: true
    buffer:
      path: ./data/collector-buffer.db   # 新增
      max-rows-per-device: 100000        # 新增
      ttl-days: 7                        # 新增
      flush-interval-ms: 30000           # 新增
    devices:
      - id: meter-RTU-001
        meter-code: MOCK-M-ELEC-002
        protocol: RTU
        serial-port: /dev/ttyUSB0        # RTU 必填
        baud-rate: 9600                  # RTU 必填
        data-bits: 8                     # 默认 8
        stop-bits: 1                     # 默认 1
        parity: NONE                     # NONE | EVEN | ODD
        unit-id: 1
        polling-interval-ms: 10000
        # ...其余字段同 TCP device
```

### `/api/v1/collector/reload` 协议

```
POST /api/v1/collector/reload
Auth: ADMIN
Body: 空
Response: {
  "code": 0,
  "data": {
    "added": ["meter-A1", "meter-A2"],
    "removed": ["meter-old"],
    "modified": ["meter-B1"],
    "unchanged": 5,
    "errors": []   // 校验失败的 device id 列表
  }
}
```

reload 失败（YAML 解析 / 校验失败）→ 返 400 + 错误详情，**当前 collector 状态不变**。

### Buffer SQLite schema

```sql
CREATE TABLE IF NOT EXISTS collector_buffer (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id TEXT NOT NULL,
  meter_code TEXT NOT NULL,
  payload_json TEXT NOT NULL,        -- 序列化的 DeviceReading
  created_at INTEGER NOT NULL,       -- ms epoch
  sent INTEGER NOT NULL DEFAULT 0    -- 0 / 1
);
CREATE INDEX idx_buffer_device_unsent ON collector_buffer(device_id, sent, id);
CREATE INDEX idx_buffer_created ON collector_buffer(created_at);
```

---

## 关键不变量（防回归 / 写给 Plan 1.5.3 与未来）

- 所有 1.5.1 不变量继续成立（Collector 不写 meters 表 / Polling 用 Instant.now / Point 一次 polling 一条 / 等）
- 共串口的 device 必须串行 polling；任意 deadlock 前提是同串口 = 同 lock
- Buffer flush 失败必须保留数据；只有写入 InfluxDB 成功才能标 sent=true
- 热加载 reload 必须不影响 unchanged device；任何 reload bug 让现有 polling 暂停 = P0 故障
- SQLite buffer 文件丢了不致命（最坏丢 1 周内 backlog），但路径需可写；启动校验确认目录存在
- RTU loopback fixture 用 socat 是开发可选；CI 默认走 j2mod 内嵌

---

## 验收

- 全后端 `mvn test` exit 0；ems-collector 测试数 ≥ 150（1.5.1 的 107 + 本 plan 增量 ~50）
- 集成测试：3 device 同 `/dev/ttyUSB-virtual` 共串口轮询，全部 HEALTHY
- 持久化 IT：故意 throw InfluxException → buffer 入库 → 模拟恢复 → buffer 清空、数据落 InfluxDB
- 热加载 IT：reload 修改 device A、新增 device B、移除 device C → 状态接口反映正确，未变化的 device D 不受影响
- 前端：`/collector` 可见 reload 按钮（ADMIN），点击后 toast「已 reload N 个 device 变更」
- E2E：collector-status.spec.ts 登录后访问 `/collector` 看到状态 + 点 reload 成功
- 文档：runbook 增加 §10 (RTU)、§11 (共串口)、§12 (buffer)、§13 (热加载)
- tag `v1.5.2-plan1.5.2` 打到 main

---

## 风险与待验证点

1. **j2mod RTU 在 Java 21 + Linux 上的稳定性**：j2mod 的 RTU 走 jSerialComm；Phase A 测试要确认在 Java 21 没有 native lib 加载问题
2. **socat 虚拟串口在 CI 上可用性**：备份方案是 j2mod RTU loopback（双 master/slave 同 JVM）；不依赖 socat
3. **SQLite WAL 模式在容器只读 root FS 上**：路径必须挂载到 writable volume；启动校验
4. **Buffer 写性能**：50 device × 5s polling × 故障 1 小时 = 36000 条；SQLite 单线程写 8000 TPS 没问题，但要确认 WAL + busy_timeout
5. **热加载的 race condition**：CollectorService.reload 期间一个 device 正在 pollOnce()。锁粒度要保证 reload 与单 device polling 互斥而不是阻塞
6. **YAML 热加载的安全**：reload 从磁盘读，不从 body 读 — 防止 ADMIN 误把临时配置 inline 提交到生产环境
7. **配置热加载的可观测**：每次 reload 必须写 audit log + Micrometer counter `ems.collector.reload.count`

---

## 启动建议

第一会话：**Phase A + B + C** —— RTU master + YAML 字段 + 共串口锁。把"RTU 协议层 + 共串口语义"两块最大不确定性先排除。Buffer + 热加载留作后续会话。
