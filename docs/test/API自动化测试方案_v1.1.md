# Factory-EMS API 自动化测试方案（v1.1）

> 项目：factory-ems | 版本：1.1.0-SNAPSHOT  
> Spring Boot 3.3.4 · Java 21 · PostgreSQL 15 · InfluxDB 2.7 · React/TS  
> 微服务架构：16 模块 · 已有测试 137 个 · k6 压测 ×3  
> 生成日期：2026-05-02 | 基于实时代码扫描结果

---

## §0 现有测试盘点

### 0.1 已覆盖（按模块）

| 模块 | 测试文件数 | 覆盖强度 | 覆盖类型 |
|------|----------|---------|---------|
| ems-collector | 50 | ★★★★★ | 单元 + 传输 + E2E IT |
| ems-alarm | 17 | ★★★★☆ | 单元 + API IT + repository IT |
| ems-app | 15 | ★★★★☆ | 限流 + 账单 + 成本 + 异常 |
| ems-report | 10 | ★★★☆☆ | 报表生成 |
| ems-cost | 9 | ★★★☆☆ | 分摊逻辑 |
| ems-timeseries | 8 | ★★★☆☆ | rollup + schema contract |
| ems-meter | 4 | ★★☆☆☆ | CRUD IT + unit |
| ems-auth | 3 | ★★☆☆☆ | 权限 + 认证流 |
| ems-tariff | 2 | ★☆☆☆☆ | 时段判定 |
| ems-orgtree | 2 | ★★☆☆☆ | 闭包一致性 |
| ems-dashboard | 2 | ★★☆☆☆ | 仪表盘 |
| ems-billing | 2 | ★★☆☆☆ | 账单聚合 |
| ems-production | 1 | ★☆☆☆☆ | 生产服务 |
| ems-floorplan | 1 | ★☆☆☆☆ | 平面图 |
| ems-core | 1 | ★☆☆☆☆ | 核心工具 |
| ems-audit | 1 | ★☆☆☆☆ | 审计 |

### 0.2 已有但未找到的测试类型

| 测试类型 | 状态 | 严重度 |
|---------|------|--------|
| 数据采集丢点/重复测试 | ❌ 无 | 🔴 P0 |
| 电价计算精度回归测试 | ❌ 无 | 🔴 P0 |
| Collector SQLite buffer 故障恢复测试 | ❌ 无 | 🔴 P0 |
| 跨服务 Contract 测试 | ⚠️ 仅 1 个 | 🟡 P1 |
| 安全渗透测试 (OWASP API Top 10) | ❌ 无 | 🟡 P1 |
| 前端 E2E (Playwright) | ❌ 无 | 🟡 P1 |
| InfluxDB 宕机降级测试 | ❌ 无 | 🟡 P1 |
| Rollup 数据一致性验证 | ❌ 需要补充 | 🟢 P2 |
| k6 数据采集写入压测 | ❌ 需要新增 | 🟢 P2 |

---

## §1 测试金字塔（针对本项目）

```
┌─────────────────────┐  新增
│ E2E 前端 (3%)       │  Playwright: 登录→查仪表→看大屏→导出报表
├─────────────────────┤
│ k6 负载测试 (5%)    │  已有3个，新增3个 (数据上报+查询+混合)
├─────────────────────┤  新增
│ 跨服务Contract (7%) │  Spring Cloud Contract: timeseries ↔ dashboard/report/alarm
├─────────────────────┤
│ API 集成测试 (25%)  │  MockMvc + Testcontainers (已有框架,补覆盖率)
├─────────────────────┤
│ 单元测试 (60%)      │  JUnit5 + Mockito (已有框架,补覆盖)
└─────────────────────┘
```

---

## §2 按风险优先级：缺口补救测试

### 🔴 P0-1：数据采集丢点/重复专项

**风险**：电表 Modbus/OPC UA 轮询在背压、网络抖动、采集器重启时丢点或重复写入。

**测试文件位置**：`ems-collector/src/test/java/com/ems/collector/DataLossIT.java`（新增）

```java
@SpringBootTest(classes = CollectorTestApp.class)
@Testcontainers
class DataLossIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    GenericContainer<?> influx = new GenericContainer<>(
        DockerImageName.parse("influxdb:2.7-alpine"))
        .withEnv("DOCKER_INFLUXDB_INIT_MODE", "setup")
        .withEnv("DOCKER_INFLUXDB_INIT_ORG", "test")
        .withEnv("DOCKER_INFLUXDB_INIT_BUCKET", "test")
        .withEnv("DOCKER_INFLUXDB_INIT_ADMIN_TOKEN", "token")
        .withExposedPorts(8086);

    @Autowired CollectorService collectorSvc;
    @Autowired InfluxSampleWriter influxWriter;

    /**
     * 背压下不丢点。
     * 100个 register × 100轮 轮询 = 10000 条预期数据；
     * 模拟 Modbus slave 响应延迟 50ms，验证最终 InfluxDB 中有 10000±1 个 unique point。
     */
    @Test
    void sustainedPolling_noDataLoss() { /* ... */ }

    /**
     * 采集器重启后不重复写历史点。
     * 先跑 20 轮 → 记下最后 timestamp → kill/start CollectorService →
     * 再跑 5 轮 → 验证流入 InfluxDB 的数据没有早于"最后 timestamp-1 分钟"的点。
     */
    @Test
    void restart_doesNotReplayOldData() { /* ... */ }

    /**
     * MQTT 乱序到达（早到的晚发消息）→ pipeline 去重逻辑生效。
     */
    @Test
    void outOfOrderMqtt_dedupByMeterTimestamp() { /* ... */ }
}
```

### 🔴 P0-2：电价计算精度回归测试

**风险**：分时电价时段边界、需量计算、功率因数奖惩逻辑错误导致计费偏差。

**测试文件位置**：`ems-tariff/src/test/java/com/ems/tariff/TouPrecisionTest.java`（新增）

```java
class TouPrecisionTest {

    @Autowired TariffService tariffSvc;

    // 时段边界 — 09:59:59 vs 10:00:00 不能被"碰运气"的 floor/ceil 搞混
    @ParameterizedTest
    @CsvSource({
        "2026-07-15T09:59:59, PEAK_SHARP,   夏季工作日，09:59 仍为尖峰",
        "2026-07-15T10:00:00, PEAK_SHARP,   10:00 仍在尖峰时段",
        "2026-07-15T11:59:59, PEAK_SHARP,   11:59 尖峰末",
        "2026-07-15T12:00:00, PEAK,         12:00 转为高峰",
        "2026-01-01T12:00:00, VALLEY,       元旦全天低谷价",
    })
    void periodBoundary_precise(LocalDateTime dt, PeriodType expected, String desc) { }

    // 电费金额 — BigDecimal 精度，float/double 累加大数必漂
    @Test
    void largeAccumulation_noFloatingPointDrift() {
        // 用 10000 个单价 0.5138 元/kWh × 100kWh 的区间累加，
        // 结果与 BigDecimal 精确值相差 < 0.001 元
    }
}
```

### 🔴 P0-3：Collector Buffer 故障恢复

**风险**：InfluxDB 不可达时数据应写入 SQLite buffer；恢复后回放 buffer 不丢不重。

**测试文件位置**：`ems-collector/src/test/java/com/ems/collector/BufferRecoveryIT.java`（新增）

```java
class BufferRecoveryIT {

    /**
     * - 停止 InfluxDB 容器
     * - 连续轮询 30 轮（数据应落 SQLite buffer）
     * - 重启 InfluxDB 容器
     * - 触发 buffer replay
     * - 验证 InfluxDB 中 30 轮数据齐全且无重复
     */
    @Test
    void influxOutage_bufferThenReplay() { }

    /**
     * buffer 满时不丢 — SQLite buffer 到达上限后，
     * 最老的数据被覆盖，最新的保留在 buffer 中。
     */
    @Test
    void bufferFull_retainsLatest() { }
}
```

---

### 🟡 P1-1：跨服务 Contract 测试

**现状**：仅 `InfluxSchemaContractIT` 一个。需扩展。

**新增文件**：

```
ems-timeseries/src/test/java/com/ems/timeseries/contract/
├── TimeSeriesQueryContractTest.java   // timeseries 输出格式约定
├── RollupFreshnessContractTest.java   // rollup 延迟 SLA (5min内)
└── MeterCatalogContractTest.java      // meter SPI 接口契约

ems-alarm/src/test/java/com/ems/alarm/contract/
└── AlarmSinkContractTest.java         // 告警输出格式 (webhook+inapp)
```

```java
/**
 * Contract: TimeSeriesQueryService.query 的输出结构，
 * 所有消费者 (dashboard/report/alarm/cost) 都依赖。
 */
@SpringBootTest
class TimeSeriesQueryContractTest {
    @Test
    void queryResult_structure() {
        // 给定：1 个 meter × 1h 粒度 × AVG
        // When：TimeSeriesQueryService.query(...)
        // Then：返回结构必含 {meterId, points: [{time, value, quality}], granularity}
        //       且 time 为 RFC3339 格式
    }
}
```

### 🟡 P1-2：安全测试 (OWASP API Top 10)

**新增 k6 脚本**：`perf/k6/security.js`

```javascript
// 覆盖 OWASP API Top 10 相关项：
// - Broken Object Level Auth (BOLA)：用 meter-A 的 token 读 meter-B 的数据
// - Broken Authentication：空 token / 过期 token / 伪造 token
// - Excessive Data Exposure：list 接口不带 filter 时是否限制 page size
// - Lack of Rate Limiting：验证已有的 RateLimitFilter 是否对数据采集接口也生效
// - Injection：SQL 注入 / Flux 注入参数
```

### 🟡 P1-3：InfluxDB 宕机降级

**新增**：`ems-timeseries/src/test/java/com/ems/timeseries/InfluxFallbackIT.java`

```java
class InfluxFallbackIT {
    @Test
    void query_duringInfluxOutage_returnsGracefulError() {
        // InfluxDB 容器 pause → query 不抛 500，返回 Result.error("INFLUX_UNAVAILABLE")
        // 且 dashboard/report 前端应有降级 UI（非白屏）
    }

    @Test
    void collector_overridesInfluxOutage_withBuffer() {
        // 验证 ems-collector 在 InfluxDB 宕机时切换到缓冲模式，非直接丢弃
        // 此测试依赖 BufferRecoveryIT 中的 buffer 机制
    }
}
```

---

### 🟢 P2-1：Rollup 数据一致性验证

**新增**：`ems-timeseries/src/test/java/com/ems/timeseries/rollup/RollupConsistencyIT.java`

```java
class RollupConsistencyIT {
    /**
     * 写入 5min 窗口的 300 个原始点（1s 间隔），
     * 跑 5min rollup task → 查 rollup 结果：
     * AVG = stream avg, MIN = stream min, MAX = stream max。
     */
    @Test
    void rollup5min_matchesRawAggregation() { }

    /**
     * 空数据窗口：0 个原始点 → rollup measurement 应有占位或跳过，不应写垃圾。
     */
    @Test
    void emptyWindow_doesNotCorruptRollup() { }

    /**
     * 回填：先存历史数据，跑 RollupBackfillService，
     * 验证回填后查询结果 = 按天手工聚合。
     */
    @Test
    void backfill_equalsHandAggregation() { }
}
```

### 🟢 P2-2：k6 数据采集写入压测

**新增**：`perf/k6/collector-write.js`

```javascript
// 模拟 100 个 meter × 每 30 秒上报一次 × 持续 10 分钟
// SLO: P95 写入 < 50ms, 错误率 < 0.01%
// 写入完成后：脚本自动查 InfluxDB API 验证点数 = 预期点数
```

### 🟢 P2-3：前端 E2E 冒烟测试

**新增**：`frontend/e2e/smoke.spec.ts`（Playwright）

```typescript
// 5 个关键路径：
// 1. 登录 → 仪表盘加载 → KPI 卡片有数据
// 2. 导航到计量点管理 → 列表加载 → 点击详情
// 3. 导航到日度报表 → 选择日期 → 表格渲染
// 4. 告警规则页 → 创建规则 → 列表出现新规则
// 5. 导出报表 → 下载 Excel 文件不损坏
```

---

## §3 按模块：补充测试清单

```
ems-tariff (2→8 个测试文件):
├── TouPrecisionTest.java           ← 新增 P0
├── DemandChargeTest.java           ← 新增
├── PowerFactorAdjustmentTest.java   ← 新增
├── MultiTariffTemplateTest.java     ← 新增 (多电价模板切换)
├── HolidayCalendarTest.java         ← 新增 (节假日判定)
└── TariffChangeRetroactiveTest.java ← 新增 (电价变更后历史数据不改)

ems-timeseries (8→12 个测试文件):
├── RollupConsistencyIT.java       ← 新增 P2
├── InfluxFallbackIT.java          ← 新增 P1
├── TimeSeriesQueryContractTest.java ← 新增 P1
└── RollupFreshnessContractTest.java ← 新增 P1

ems-meter (4→7 个测试文件):
├── MeterBatchImportTest.java       ← 新增
├── EnergyTypeTest.java             ← 新增
└── MeterTopologyCycleTest.java     ← 新增 (循环引用检测)

ems-auth (3→6 个测试文件):
├── TokenRefreshRaceConditionTest.java ← 新增
├── BruteForceProtectionTest.java      ← 新增
└── RoleHierarchyTest.java            ← 新增

ems-audit (1→3 个测试文件):
├── AuditTrailConsistencyTest.java  ← 新增 (审计日志不丢)
└── AuditPurgingTest.java           ← 新增

ems-core (1→3 个测试文件):
├── ResultSerializationTest.java    ← 新增 (JSON 格式一致性)
└── TraceIdPropagationTest.java     ← 新增 (跨服务 trace)
```

---

## §4 CI/CD 集成方案

基于项目已有的 GitHub Actions（从 `docker-compose.dev.yml` 推断），新增：

```yaml
# .github/workflows/test-suite.yml (新增 job)

  test-gaps:
    needs: [unit]
    runs-on: ubuntu-latest
    services:
      influxdb:
        image: influxdb:2.7-alpine
        env:
          DOCKER_INFLUXDB_INIT_MODE: setup
          DOCKER_INFLUXDB_INIT_ORG: test
          DOCKER_INFLUXDB_INIT_BUCKET: test
          DOCKER_INFLUXDB_INIT_ADMIN_TOKEN: test-token
        ports: ['8086:8086']
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'temurin' }
      - run: mvn test -P gaps
        # 仅运行新增的 gap 测试 (DataLossIT, BufferRecoveryIT,
        # TouPrecisionTest, RollupConsistencyIT, Contract tests)
      - name: Quality Gate
        run: |
          # 新测试全绿，且覆盖率新增模块 ≥ 80%

  # k6 压测（非 PR 触发，仅 main/release 分支）
  performance:
    if: github.ref == 'refs/heads/main'
    needs: [build]
    runs-on: ubuntu-latest
    steps:
      - uses: grafana/k6-action@v0.3
        with:
          filename: perf/k6/collector-write.js
          flags: --out json=results.json
      - uses: grafana/k6-action@v0.3
        with:
          filename: perf/k6/dashboard.js
      - uses: grafana/k6-action@v0.3
        with:
          filename: perf/k6/report-monthly.js
```

---

## §5 测试数据策略

```
已有工具:
├── tools/mock-data-generator/      ← 已有 MockDataApplication
│   └── 输出: 按 Modbus TCP 协议的模拟电表数据
├── tools/modbus-simulator/         ← 已有 Modbus 从站模拟器
│   └── 用途: CollectorEndToEndIT 已经在用 (ModbusSlaveTestFixture)

数据准备:
├── Seed SQL 脚本 (src/test/resources/db/seed/)
│   ├── seed-meters.sql      (100 个计量点 + 电/水/气 3 种能源类型)
│   ├── seed-tariffs.sql     (3 种电价模板 × 12 个月的时段表)
│   ├── seed-orgtree.sql     (工厂→车间→产线 三级组织树)
│   └── seed-alarm-rules.sql (5 条典型告警规则)
│
├── InfluxDB seed (通过 InfluxDB v2 API)
│   ├── 过去 12 个月 × 20 个 meter × 1h rollup 数据
│   └── 近 7 天 × 20 个 meter × 5min 明细数据
│
└── 共享 Test Fixture:
    @TestConfiguration
    public class EmsTestFixtures {
        @Bean MeterFactory meterFactory();      // 快捷创建 Meter
        @Bean TariffFactory tariffFactory();    // 快捷创建电价模板
        @Bean BatchFactory batchFactory();      // 快捷创建测试批次
    }
```

---

## §6 现有测试框架复用

项目已有成熟的测试基础设施，新测试直接复用：

```java
// 已有依赖（无需新增）
Testcontainers 1.21.3   → PostgreSQL + InfluxDB 容器
JUnit 5                  → @Test, @Timeout, @ParameterizedTest
AssertJ                  → assertThat(...).isEqualTo(...)
Awaitility               → await().atMost(10, SECONDS).until(...)
MockMvc / WebTestClient  → ems-app 已有使用
k6                       → perf/k6/ 目录已有 3 个脚本模板
```

---

## §7 实施优先级顺序

```
第 1 周（P0 灭火）:
├── DataLossIT.java            （丢点/重复）
├── TouPrecisionTest.java      （电价精度）
└── BufferRecoveryIT.java      （buffer 恢复）

第 2 周（P1 加固）:
├── Contract 测试 ×3
├── InfluxFallbackIT.java      （InfluxDB 宕机）
└── k6 collector-write.js      （写入压测）

第 3-4 周（P2 持续改进）:
├── RollupConsistencyIT.java
├── 前端 E2E smoke.spec.ts
└── 模块补充清单（tariff/meter/auth/audit 各 +2~3 个测试）

持续:
└── 每个 PR 自动运行 gap 测试套件
```

---

## §8 你担心的四个风险 — 对应测试

| 风险 | 对应测试 | 位置 |
|------|---------|------|
| 数据采集丢点/重复 | `DataLossIT`, `BufferRecoveryIT` | ems-collector |
| 需量/功率因数/电费算错 | `TouPrecisionTest`, `DemandChargeTest`, `PowerFactorAdjustmentTest` | ems-tariff |
| 高并发查询扛不住 | `collector-write.js` (k6), `dashboard.js` (已有) | perf/k6/ |
| 第三方对接不稳定 | `InfluxFallbackIT`, 已有 `OpcUaTransportIT`, `MqttTransportIT` | ems-timeseries / ems-collector |
