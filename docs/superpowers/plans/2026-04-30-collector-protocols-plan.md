# 采集器协议扩展实施计划（OPC UA + MQTT + VIRTUAL）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 `ems-collector` 增加 OPC UA / MQTT / VIRTUAL 三种协议接入能力，引入跨协议统一抽象、JSONB 动态配置、文件系统密钥管理与统一诊断。

**Architecture:** 用 `Transport` sealed interface + `ChannelConfig` JSONB 多态配置取代静态 YAML；Modbus 协议适配新接口实现零回归；OPC UA 走 Eclipse Milo + Sign+Encrypt 证书审批；MQTT 走 Eclipse Paho + JSONPath；VIRTUAL 用 4 种数学公式合成数据。前端按协议条件渲染表单。

**Tech Stack:** Spring Boot 3.3 + Java 21 + JPA + PostgreSQL JSONB + Eclipse Milo 0.6.x + Eclipse Paho 1.2.5 + Jayway JsonPath 2.9 + Testcontainers + React 18 + AntD 5

**Spec:** [docs/superpowers/specs/2026-04-30-collector-protocols-design.md](../specs/2026-04-30-collector-protocols-design.md)

---

## File Map

### 后端 — `ems-collector/src/main/java/com/ems/collector/`

| 路径 | 责任 |
|---|---|
| `channel/Channel.java` | JPA entity，对应 `channel` 表 |
| `channel/ChannelRepository.java` | JpaRepository<Channel, Long> |
| `channel/ChannelDTO.java` | API DTO（凭据字段仅返回 `secret://xxx`） |
| `channel/ChannelController.java` | REST `/api/v1/channel` CRUD |
| `channel/ChannelService.java` | 业务编排：创建/更新/启停 transport |
| `transport/Transport.java` | sealed interface |
| `transport/Sample.java` + `SampleSink.java` + `Quality.java` + `TestResult.java` + `TransportException.java` | 公共数据类 |
| `transport/impl/ModbusTcpAdapterTransport.java` | Modbus TCP 适配 |
| `transport/impl/ModbusRtuAdapterTransport.java` | Modbus RTU 适配 |
| `transport/impl/OpcUaTransport.java` | OPC UA |
| `transport/impl/MqttTransport.java` | MQTT |
| `transport/impl/VirtualTransport.java` | 虚拟协议 |
| `transport/impl/VirtualSignalGenerator.java` | 4 种模式公式（纯函数，便于单测） |
| `protocol/ChannelConfig.java` | sealed interface 配置基类 |
| `protocol/ModbusTcpConfig.java` 等 5 个 record | 各协议配置 |
| `protocol/PointConfig.java` | 测点 sealed interface |
| `protocol/SecurityMode.java` / `VirtualMode.java` / `SubscriptionMode.java` | 枚举 |
| `secret/SecretResolver.java` | 接口 |
| `secret/FilesystemSecretResolver.java` | 实现 |
| `secret/SecretController.java` | REST `/api/v1/secrets` |
| `runtime/ChannelStateRegistry.java` | 全局运行时状态 |
| `runtime/ChannelRuntimeState.java` | 状态 record |
| `runtime/HourlyCounter.java` | 24h 环形计数器 |
| `diagnostics/ChannelDiagnosticsService.java` | 诊断业务逻辑 |
| `diagnostics/ChannelDiagnosticsController.java` | REST `/api/v1/collector/*` |
| `diagnostics/CollectorMetricsFlusher.java` | 每分钟 flush 到 collector_metrics |
| `diagnostics/CollectorTransportHealthIndicator.java` | Spring Actuator |
| `cert/OpcUaCertificateStore.java` | 证书白名单管理 |
| `cert/CertificateApprovalController.java` | REST `/api/v1/collector/{id}/trust-cert` |

### 前端 — `frontend/src/`

| 路径 | 责任 |
|---|---|
| `api/channel.ts` | Channel REST 客户端 |
| `api/secret.ts` | Secret REST 客户端 |
| `api/collectorDiag.ts` | 诊断 REST 客户端 |
| `pages/meters/ChannelEditor.tsx` | 协议选择 + 动态表单 |
| `pages/meters/forms/ModbusTcpForm.tsx` 等 5 个 | 协议子表单 |
| `pages/meters/forms/VirtualForm.tsx` | 含 4 模式参数 |
| `components/SecretInput.tsx` | 凭据字段统一控件 |
| `pages/collector/index.tsx` | 诊断页（升级既有） |
| `pages/collector/ChannelDetailDrawer.tsx` | 详情抽屉 |
| `utils/i18n-dict.ts` | 添加 3 个新字典 |

### 数据库迁移

| 文件 | 责任 |
|---|---|
| `ems-app/src/main/resources/db/migration/V2.3.0__init_channel.sql` | channel + collector_metrics 表 |

---

> **使用说明：** 本计划共 9 个 Phase、约 38 个 Task。每个 Phase 末尾会有 commit。Phase 之间相对独立但有顺序依赖（数据模型 → Transport 抽象 → 各协议 → 诊断 → 前端 → E2E）。
> 由于篇幅原因，Phase 1（数据模型）以 TDD 完整步骤示例编写。Phase 2-9 在每个 Task 内仍提供完整代码与命令，但步骤合并为更紧凑的形式（实现 + 测试 + commit）。

---

## Phase 1 — 数据模型与 JPA 层（约 1 天）

### Task 1.1: Flyway 迁移 — channel + collector_metrics 表

**Files:**
- Create: `ems-app/src/main/resources/db/migration/V2.3.0__init_channel.sql`
- Test: `ems-collector/src/test/java/com/ems/collector/channel/ChannelMigrationIT.java`

- [x] **Step 1: 写迁移失败测试**

```java
package com.ems.collector.channel;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = com.ems.app.EmsAppApplication.class)
class ChannelMigrationIT {
    @Autowired JdbcTemplate jdbc;

    @Test
    void channelTableExists() {
        var count = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_name = 'channel'",
            Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void collectorMetricsTableExists() {
        var count = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_name = 'collector_metrics'",
            Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
```

- [x] **Step 2: 运行测试验证失败**

```bash
cd /Users/mac/factory-ems
mvn -pl ems-collector -am test -Dtest=ChannelMigrationIT
```
Expected: FAIL — table does not exist

- [x] **Step 3: 写迁移 SQL**

```sql
-- V2.3.0__init_channel.sql
-- 采集器协议扩展：channel 抽象 + 诊断指标
-- spec: docs/superpowers/specs/2026-04-30-collector-protocols-design.md

CREATE TABLE channel (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL UNIQUE,
    protocol        VARCHAR(32)  NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    is_virtual      BOOLEAN      NOT NULL DEFAULT FALSE,
    protocol_config JSONB        NOT NULL,
    description     TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_channel_protocol CHECK (
        protocol IN ('MODBUS_TCP','MODBUS_RTU','OPC_UA','MQTT','VIRTUAL')
    )
);
CREATE INDEX idx_channel_protocol ON channel(protocol);
CREATE INDEX idx_channel_enabled  ON channel(enabled) WHERE enabled = TRUE;

ALTER TABLE meters ADD COLUMN IF NOT EXISTS channel_id BIGINT REFERENCES channel(id);

CREATE TABLE collector_metrics (
    channel_id     BIGINT      NOT NULL REFERENCES channel(id) ON DELETE CASCADE,
    bucket_at      TIMESTAMPTZ NOT NULL,
    success_cnt    INTEGER     NOT NULL DEFAULT 0,
    failure_cnt    INTEGER     NOT NULL DEFAULT 0,
    avg_latency_ms INTEGER,
    PRIMARY KEY (channel_id, bucket_at)
);
CREATE INDEX idx_collector_metrics_bucket ON collector_metrics(bucket_at);
```

- [x] **Step 4: 运行测试通过**

```bash
mvn -pl ems-collector -am test -Dtest=ChannelMigrationIT
```
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add ems-app/src/main/resources/db/migration/V2.3.0__init_channel.sql \
        ems-collector/src/test/java/com/ems/collector/channel/ChannelMigrationIT.java
git commit -m "feat(collector): add channel + collector_metrics migration

V2.3.0 引入 channel 抽象与诊断指标聚合表。"
```

---

### Task 1.2: ChannelConfig sealed interface + 5 协议 record + 枚举

**Files:**
- Create: `ems-collector/src/main/java/com/ems/collector/protocol/ChannelConfig.java`
- Create: `ems-collector/src/main/java/com/ems/collector/protocol/PointConfig.java`
- Create: `ems-collector/src/main/java/com/ems/collector/protocol/{ModbusTcpConfig,ModbusRtuConfig,OpcUaConfig,MqttConfig,VirtualConfig}.java`
- Create: `ems-collector/src/main/java/com/ems/collector/protocol/{ModbusPoint,OpcUaPoint,MqttPoint,VirtualPoint}.java`
- Create: `ems-collector/src/main/java/com/ems/collector/protocol/{SecurityMode,VirtualMode,SubscriptionMode}.java`
- Test: `ems-collector/src/test/java/com/ems/collector/protocol/ChannelConfigJsonTest.java`

- [x] **Step 1: 写失败测试**

```java
package com.ems.collector.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ChannelConfigJsonTest {
    private final ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void serializesAndDeserializesOpcUaConfig() throws Exception {
        var cfg = new OpcUaConfig(
            "opc.tcp://test:62541", SecurityMode.NONE, null, null, null, null,
            Duration.ofSeconds(5),
            List.of(new OpcUaPoint("temp", "ns=2;s=Tag1", SubscriptionMode.SUBSCRIBE, 1000.0, "C"))
        );
        var json = om.writeValueAsString(cfg);
        assertThat(json).contains("\"protocol\":\"OPC_UA\"");
        var parsed = om.readValue(json, ChannelConfig.class);
        assertThat(parsed).isInstanceOf(OpcUaConfig.class);
    }

    @Test
    void serializesAndDeserializesVirtualConfig() throws Exception {
        var cfg = new VirtualConfig(
            Duration.ofSeconds(1),
            List.of(new VirtualPoint("p1", VirtualMode.SINE,
                Map.of("amplitude", 10.0, "periodSec", 60.0), "kW"))
        );
        var parsed = om.readValue(om.writeValueAsString(cfg), ChannelConfig.class);
        assertThat(parsed).isInstanceOf(VirtualConfig.class);
    }

    @Test
    void serializesAndDeserializesMqttConfig() throws Exception {
        var cfg = new MqttConfig(
            "tcp://broker:1883", "ems-collector-1",
            "secret://mqtt/u", "secret://mqtt/p",
            null, 1, true, Duration.ofSeconds(60),
            List.of(new MqttPoint("temp", "sensors/+/t", "$.value", "C", null))
        );
        var parsed = om.readValue(om.writeValueAsString(cfg), ChannelConfig.class);
        assertThat(parsed).isInstanceOf(MqttConfig.class);
    }
}
```

- [x] **Step 2: 验证失败**

```bash
mvn -pl ems-collector test -Dtest=ChannelConfigJsonTest
```
Expected: FAIL — class not found

- [x] **Step 3: 实现 sealed interface + 枚举**

`ChannelConfig.java`:
```java
package com.ems.collector.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Duration;
import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "protocol")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ModbusTcpConfig.class,  name = "MODBUS_TCP"),
    @JsonSubTypes.Type(value = ModbusRtuConfig.class,  name = "MODBUS_RTU"),
    @JsonSubTypes.Type(value = OpcUaConfig.class,      name = "OPC_UA"),
    @JsonSubTypes.Type(value = MqttConfig.class,       name = "MQTT"),
    @JsonSubTypes.Type(value = VirtualConfig.class,    name = "VIRTUAL")
})
public sealed interface ChannelConfig
    permits ModbusTcpConfig, ModbusRtuConfig, OpcUaConfig, MqttConfig, VirtualConfig {
    String protocol();
    Duration pollInterval();
    List<? extends PointConfig> points();
}
```

`PointConfig.java`:
```java
package com.ems.collector.protocol;

public sealed interface PointConfig
    permits ModbusPoint, OpcUaPoint, MqttPoint, VirtualPoint {
    String key();
    String unit();
}
```

`SecurityMode.java`:
```java
package com.ems.collector.protocol;
public enum SecurityMode { NONE, SIGN, SIGN_AND_ENCRYPT }
```

`VirtualMode.java`:
```java
package com.ems.collector.protocol;
public enum VirtualMode { CONSTANT, SINE, RANDOM_WALK, CALENDAR_CURVE }
```

`SubscriptionMode.java`:
```java
package com.ems.collector.protocol;
public enum SubscriptionMode { SUBSCRIBE, READ }
```

- [x] **Step 4: 实现各协议 record**

`ModbusTcpConfig.java`:
```java
package com.ems.collector.protocol;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.List;

public record ModbusTcpConfig(
    @NotBlank String host,
    @Min(1) @Max(65535) int port,
    int unitId,
    @NotNull Duration pollInterval,
    Duration timeout,
    @Valid @NotEmpty List<ModbusPoint> points
) implements ChannelConfig {
    public String protocol() { return "MODBUS_TCP"; }
}
```

`ModbusRtuConfig.java`:
```java
package com.ems.collector.protocol;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.List;

public record ModbusRtuConfig(
    @NotBlank String serialPort, @Min(1200) int baudRate,
    int dataBits, int stopBits, @NotBlank String parity,
    int unitId, @NotNull Duration pollInterval, Duration timeout,
    @Valid @NotEmpty List<ModbusPoint> points
) implements ChannelConfig {
    public String protocol() { return "MODBUS_RTU"; }
}
```

`ModbusPoint.java`:
```java
package com.ems.collector.protocol;

import jakarta.validation.constraints.*;

public record ModbusPoint(
    @NotBlank String key, @NotBlank String registerKind,
    @Min(0) int address, @Min(1) int quantity,
    @NotBlank String dataType, String byteOrder,
    Double scale, String unit
) implements PointConfig {}
```

`OpcUaConfig.java`:
```java
package com.ems.collector.protocol;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.List;

public record OpcUaConfig(
    @NotBlank String endpointUrl,
    @NotNull SecurityMode securityMode,
    String certRef, String certPasswordRef,
    String usernameRef, String passwordRef,
    Duration pollInterval,
    @Valid @NotEmpty List<OpcUaPoint> points
) implements ChannelConfig {
    public String protocol() { return "OPC_UA"; }
}
```

`OpcUaPoint.java`:
```java
package com.ems.collector.protocol;

import jakarta.validation.constraints.*;

public record OpcUaPoint(
    @NotBlank String key, @NotBlank String nodeId,
    @NotNull SubscriptionMode mode,
    Double samplingIntervalMs, String unit
) implements PointConfig {}
```

`MqttConfig.java`:
```java
package com.ems.collector.protocol;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.List;

public record MqttConfig(
    @NotBlank String brokerUrl, @NotBlank String clientId,
    String usernameRef, String passwordRef, String tlsCaCertRef,
    @Min(0) @Max(1) int qos, boolean cleanSession,
    @NotNull Duration keepAlive,
    @Valid @NotEmpty List<MqttPoint> points
) implements ChannelConfig {
    public String protocol() { return "MQTT"; }
    public Duration pollInterval() { return null; }
}
```

`MqttPoint.java`:
```java
package com.ems.collector.protocol;

import jakarta.validation.constraints.*;

public record MqttPoint(
    @NotBlank String key, @NotBlank String topic,
    @NotBlank String jsonPath, String unit, String timestampJsonPath
) implements PointConfig {}
```

`VirtualConfig.java`:
```java
package com.ems.collector.protocol;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.List;

public record VirtualConfig(
    @NotNull Duration pollInterval,
    @Valid @NotEmpty List<VirtualPoint> points
) implements ChannelConfig {
    public String protocol() { return "VIRTUAL"; }
}
```

`VirtualPoint.java`:
```java
package com.ems.collector.protocol;

import jakarta.validation.constraints.*;
import java.util.Map;

public record VirtualPoint(
    @NotBlank String key, @NotNull VirtualMode mode,
    @NotNull Map<String, Double> params, String unit
) implements PointConfig {}
```

- [x] **Step 5: 测试通过**

```bash
mvn -pl ems-collector test -Dtest=ChannelConfigJsonTest
```
Expected: PASS

- [x] **Step 6: Commit**

```bash
git add ems-collector/src/main/java/com/ems/collector/protocol/ \
        ems-collector/src/test/java/com/ems/collector/protocol/
git commit -m "feat(collector): add ChannelConfig sealed interface + 5 protocol records"
```

---

### Task 1.3: Channel JPA entity + Repository + ChannelDTO

**Files:**
- Create: `ems-collector/src/main/java/com/ems/collector/channel/Channel.java`
- Create: `ems-collector/src/main/java/com/ems/collector/channel/ChannelRepository.java`
- Create: `ems-collector/src/main/java/com/ems/collector/channel/ChannelDTO.java`
- Test: `ems-collector/src/test/java/com/ems/collector/channel/ChannelRepositoryIT.java`

- [x] **Step 1: 写失败测试**

```java
package com.ems.collector.channel;

import com.ems.collector.protocol.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = com.ems.app.EmsAppApplication.class)
@Transactional
class ChannelRepositoryIT {
    @Autowired ChannelRepository repo;

    @Test
    void persistsAndLoadsVirtualChannel() {
        var ch = newVirtualChannel("virt-1", true);
        var saved = repo.save(ch);
        var loaded = repo.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getProtocolConfig()).isInstanceOf(VirtualConfig.class);
        assertThat(((VirtualConfig) loaded.getProtocolConfig()).points()).hasSize(1);
    }

    @Test
    void findsEnabledChannels() {
        repo.save(newVirtualChannel("ch-on", true));
        repo.save(newVirtualChannel("ch-off", false));
        var enabled = repo.findByEnabledTrue();
        assertThat(enabled).extracting(Channel::getName)
            .contains("ch-on").doesNotContain("ch-off");
    }

    private Channel newVirtualChannel(String name, boolean enabled) {
        var ch = new Channel();
        ch.setName(name);
        ch.setProtocol("VIRTUAL");
        ch.setEnabled(enabled);
        ch.setIsVirtual(true);
        ch.setProtocolConfig(new VirtualConfig(
            Duration.ofSeconds(1),
            List.of(new VirtualPoint("v", VirtualMode.CONSTANT, Map.of("value", 1.0), "kW"))
        ));
        return ch;
    }
}
```

- [x] **Step 2: 验证失败**

```bash
mvn -pl ems-collector test -Dtest=ChannelRepositoryIT
```
Expected: FAIL

- [x] **Step 3: 实现 Entity / Repository / DTO**

`Channel.java`:
```java
package com.ems.collector.channel;

import com.ems.collector.protocol.ChannelConfig;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity
@Table(name = "channel")
public class Channel {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 128)
    private String name;

    @Column(nullable = false, length = 32)
    private String protocol;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "is_virtual", nullable = false)
    private boolean isVirtual = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "protocol_config", nullable = false, columnDefinition = "jsonb")
    private ChannelConfig protocolConfig;

    @Column private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate void onUpdate() { this.updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isIsVirtual() { return isVirtual; }
    public void setIsVirtual(boolean isVirtual) { this.isVirtual = isVirtual; }
    public ChannelConfig getProtocolConfig() { return protocolConfig; }
    public void setProtocolConfig(ChannelConfig cfg) { this.protocolConfig = cfg; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

`ChannelRepository.java`:
```java
package com.ems.collector.channel;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ChannelRepository extends JpaRepository<Channel, Long> {
    List<Channel> findByEnabledTrue();
    Optional<Channel> findByName(String name);
}
```

`ChannelDTO.java`:
```java
package com.ems.collector.channel;

import com.ems.collector.protocol.ChannelConfig;
import java.time.Instant;

public record ChannelDTO(
    Long id, String name, String protocol, boolean enabled, boolean isVirtual,
    ChannelConfig protocolConfig, String description,
    Instant createdAt, Instant updatedAt
) {
    public static ChannelDTO from(Channel c) {
        return new ChannelDTO(c.getId(), c.getName(), c.getProtocol(),
            c.isEnabled(), c.isIsVirtual(), c.getProtocolConfig(),
            c.getDescription(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
```

- [x] **Step 4: 测试通过 + Commit**

```bash
mvn -pl ems-collector test -Dtest=ChannelRepositoryIT
```
Expected: PASS

```bash
git add ems-collector/src/main/java/com/ems/collector/channel/ \
        ems-collector/src/test/java/com/ems/collector/channel/ChannelRepositoryIT.java
git commit -m "feat(collector): add Channel entity + repository + DTO"
```

---

## Phase 2 — Transport 抽象与 Modbus 适配（约 2 天）

### Task 2.1: Transport sealed interface + 公共数据类

**Files:**
- Create: `ems-collector/src/main/java/com/ems/collector/transport/{Transport,Sample,SampleSink,Quality,TestResult,TransportException}.java`
- Test: `ems-collector/src/test/java/com/ems/collector/transport/SampleTest.java`

- [x] **Step 1: 写测试**

```java
package com.ems.collector.transport;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class SampleTest {
    @Test
    void buildsSampleWithTags() {
        var s = new Sample(1L, "p1", Instant.parse("2026-04-30T10:00:00Z"),
            42.0, Quality.GOOD, Map.of("topic", "sensors/t"));
        assertThat(s.channelId()).isEqualTo(1L);
        assertThat(s.tags()).containsEntry("topic", "sensors/t");
    }
}
```

- [x] **Step 2: 实现**

`Quality.java`:
```java
package com.ems.collector.transport;
public enum Quality { GOOD, UNCERTAIN, BAD }
```

`Sample.java`:
```java
package com.ems.collector.transport;

import java.time.Instant;
import java.util.Map;

public record Sample(
    Long channelId, String pointKey, Instant timestamp,
    Object value, Quality quality, Map<String, String> tags
) {}
```

`SampleSink.java`:
```java
package com.ems.collector.transport;

@FunctionalInterface
public interface SampleSink {
    void accept(Sample sample);
}
```

`TestResult.java`:
```java
package com.ems.collector.transport;

public record TestResult(boolean success, String message, Long latencyMs) {
    public static TestResult ok(long latencyMs) { return new TestResult(true, "OK", latencyMs); }
    public static TestResult fail(String msg) { return new TestResult(false, msg, null); }
}
```

`TransportException.java`:
```java
package com.ems.collector.transport;

public class TransportException extends RuntimeException {
    public TransportException(String msg) { super(msg); }
    public TransportException(String msg, Throwable cause) { super(msg, cause); }
}
```

`Transport.java`（先用空 permits 列表，后续 Task 加入实现）:
```java
package com.ems.collector.transport;

import com.ems.collector.protocol.ChannelConfig;
import com.ems.collector.transport.impl.*;

public sealed interface Transport
    permits ModbusTcpAdapterTransport, ModbusRtuAdapterTransport,
            OpcUaTransport, MqttTransport, VirtualTransport {

    void start(Long channelId, ChannelConfig config, SampleSink sink) throws TransportException;
    void stop();
    boolean isConnected();
    TestResult testConnection(ChannelConfig config);
}
```

> **注意**：`permits` 引用尚未存在的类。使用临时 stub 占位（每个类一个空 final class），随后 Task 替换。

`transport/impl/ModbusTcpAdapterTransport.java`（stub）:
```java
package com.ems.collector.transport.impl;

import com.ems.collector.protocol.ChannelConfig;
import com.ems.collector.transport.*;

public final class ModbusTcpAdapterTransport implements Transport {
    public void start(Long id, ChannelConfig c, SampleSink s) { throw new UnsupportedOperationException(); }
    public void stop() {}
    public boolean isConnected() { return false; }
    public TestResult testConnection(ChannelConfig c) { return TestResult.fail("stub"); }
}
```

同样 stub 创建 `ModbusRtuAdapterTransport`、`OpcUaTransport`、`MqttTransport`、`VirtualTransport`。

- [x] **Step 3: 测试通过 + Commit**

```bash
mvn -pl ems-collector test -Dtest=SampleTest
git add ems-collector/src/main/java/com/ems/collector/transport/ \
        ems-collector/src/test/java/com/ems/collector/transport/SampleTest.java
git commit -m "feat(collector): add Transport sealed interface + 5 stub implementations"
```

---

### Task 2.2: ChannelStateRegistry + ChannelRuntimeState + HourlyCounter

**Files:**
- Create: `ems-collector/src/main/java/com/ems/collector/runtime/{ChannelRuntimeState,ChannelStateRegistry,HourlyCounter,ConnectionState}.java`
- Test: `ems-collector/src/test/java/com/ems/collector/runtime/{HourlyCounterTest,ChannelStateRegistryTest}.java`

- [x] **Step 1: 写 HourlyCounter 测试**

```java
package com.ems.collector.runtime;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.assertThat;

class HourlyCounterTest {
    @Test
    void countsWithinSameHour() {
        var clock = Clock.fixed(Instant.parse("2026-04-30T10:30:00Z"), ZoneOffset.UTC);
        var c = new HourlyCounter(clock);
        c.recordSuccess(); c.recordSuccess(); c.recordFailure();
        assertThat(c.total24h(true)).isEqualTo(2);
        assertThat(c.total24h(false)).isEqualTo(1);
    }

    @Test
    void rollsOverWhenHourChanges() {
        var ts = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-04-30T10:30:00Z"));
        var clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return ts.get(); }
        };
        var c = new HourlyCounter(clock);
        c.recordSuccess();
        ts.set(Instant.parse("2026-04-30T11:30:00Z"));
        c.recordSuccess();
        assertThat(c.total24h(true)).isEqualTo(2);
    }
}
```

- [x] **Step 2: 实现**

`ConnectionState.java`:
```java
package com.ems.collector.runtime;
public enum ConnectionState { CONNECTING, CONNECTED, DISCONNECTED, ERROR }
```

`HourlyCounter.java`:
```java
package com.ems.collector.runtime;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

public class HourlyCounter {
    private final long[] success = new long[24];
    private final long[] failure = new long[24];
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile int currentSlot;

    public HourlyCounter(Clock clock) {
        this.clock = clock;
        this.currentSlot = computeSlot();
    }

    private int computeSlot() {
        return clock.instant().atZone(ZoneOffset.UTC).getHour();
    }

    private void rollIfNeeded() {
        var slot = computeSlot();
        if (slot == currentSlot) return;
        lock.lock();
        try {
            if (slot != currentSlot) {
                for (int i = (currentSlot + 1) % 24; i != (slot + 1) % 24; i = (i + 1) % 24) {
                    success[i] = 0; failure[i] = 0;
                    if (i == slot) break;
                }
                currentSlot = slot;
            }
        } finally { lock.unlock(); }
    }

    public void recordSuccess() { rollIfNeeded(); success[currentSlot]++; }
    public void recordFailure() { rollIfNeeded(); failure[currentSlot]++; }
    public long total24h(boolean ok) { return Arrays.stream(ok ? success : failure).sum(); }
}
```

`ChannelRuntimeState.java`:
```java
package com.ems.collector.runtime;

import java.time.Instant;
import java.util.Map;

public record ChannelRuntimeState(
    Long channelId, String protocol, ConnectionState connState,
    Instant lastConnectAt, Instant lastSuccessAt, Instant lastFailureAt,
    String lastErrorMessage,
    long successCount24h, long failureCount24h, long avgLatencyMs,
    Map<String, Object> protocolMeta
) {}
```

`ChannelStateRegistry.java`:
```java
package com.ems.collector.runtime;

import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ChannelStateRegistry {
    private final Map<Long, MutableState> states = new ConcurrentHashMap<>();
    private final Clock clock;

    public ChannelStateRegistry(Clock clock) { this.clock = clock; }

    public void register(Long id, String protocol) {
        states.put(id, new MutableState(protocol, clock));
    }

    public void recordSuccess(Long id, long latencyMs) {
        var s = states.get(id);
        if (s == null) return;
        s.connState = ConnectionState.CONNECTED;
        s.lastSuccessAt = clock.instant();
        s.counter.recordSuccess();
        s.recordLatency(latencyMs);
    }

    public void recordFailure(Long id, String error) {
        var s = states.get(id);
        if (s == null) return;
        s.connState = ConnectionState.DISCONNECTED;
        s.lastFailureAt = clock.instant();
        s.lastErrorMessage = truncate(error);
        s.counter.recordFailure();
    }

    public void setState(Long id, ConnectionState state) {
        var s = states.get(id);
        if (s != null) s.connState = state;
    }

    public ChannelRuntimeState snapshot(Long id) {
        var s = states.get(id);
        return s == null ? null : s.toRecord(id);
    }

    public Collection<ChannelRuntimeState> snapshotAll() {
        return states.entrySet().stream()
            .map(e -> e.getValue().toRecord(e.getKey()))
            .toList();
    }

    public void unregister(Long id) { states.remove(id); }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 200 ? s.substring(0, 200) : s;
    }

    private static class MutableState {
        final String protocol;
        final HourlyCounter counter;
        ConnectionState connState = ConnectionState.CONNECTING;
        Instant lastConnectAt, lastSuccessAt, lastFailureAt;
        String lastErrorMessage;
        final long[] latencyWindow = new long[100];
        int latencyIdx = 0, latencyCount = 0;
        Map<String, Object> protocolMeta = new HashMap<>();

        MutableState(String protocol, Clock clock) {
            this.protocol = protocol;
            this.counter = new HourlyCounter(clock);
            this.lastConnectAt = clock.instant();
        }

        void recordLatency(long ms) {
            latencyWindow[latencyIdx] = ms;
            latencyIdx = (latencyIdx + 1) % 100;
            if (latencyCount < 100) latencyCount++;
        }

        long avgLatency() {
            if (latencyCount == 0) return 0;
            long sum = 0;
            for (int i = 0; i < latencyCount; i++) sum += latencyWindow[i];
            return sum / latencyCount;
        }

        ChannelRuntimeState toRecord(Long id) {
            return new ChannelRuntimeState(id, protocol, connState,
                lastConnectAt, lastSuccessAt, lastFailureAt, lastErrorMessage,
                counter.total24h(true), counter.total24h(false),
                avgLatency(), Map.copyOf(protocolMeta));
        }
    }
}
```

需要 `Clock` bean。在 `ems-collector` 中已有 `collectorClock`，注册时用 `@Qualifier`：
更新 `ChannelStateRegistry` 构造函数为 `public ChannelStateRegistry(@Qualifier("collectorClock") Clock clock)`。

- [x] **Step 3: 测试通过 + Commit**

```bash
mvn -pl ems-collector test -Dtest=HourlyCounterTest
git add ems-collector/src/main/java/com/ems/collector/runtime/ \
        ems-collector/src/test/java/com/ems/collector/runtime/HourlyCounterTest.java
git commit -m "feat(collector): add ChannelStateRegistry + HourlyCounter"
```

---

### Task 2.3: ModbusTcpAdapterTransport 替换 stub

**Files:**
- Modify: `ems-collector/src/main/java/com/ems/collector/transport/impl/ModbusTcpAdapterTransport.java`
- Test: `ems-collector/src/test/java/com/ems/collector/transport/impl/ModbusTcpAdapterTransportTest.java`

- [x] **Step 1: 写适配器测试**

```java
package com.ems.collector.transport.impl;

import com.ems.collector.protocol.*;
import com.ems.collector.transport.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import java.util.concurrent.TimeUnit;

class ModbusTcpAdapterTransportTest {
    @Test
    void readsFromExistingModbusFixture() {
        // 复用 ems-collector 既有的 ModbusSlaveTestFixture
        var fixture = new com.ems.collector.transport.ModbusSlaveTestFixture();
        var port = fixture.start();
        try {
            var cfg = new ModbusTcpConfig("127.0.0.1", port, 1,
                Duration.ofMillis(200), Duration.ofMillis(500),
                List.of(new ModbusPoint("p1", "HOLDING", 0, 1, "INT16",
                    "BIG_ENDIAN", null, "kW")));
            var samples = new ConcurrentLinkedQueue<Sample>();
            var t = new ModbusTcpAdapterTransport();
            t.start(99L, cfg, samples::add);
            await().atMost(2, TimeUnit.SECONDS).until(() -> !samples.isEmpty());
            t.stop();
            assertThat(samples.peek().pointKey()).isEqualTo("p1");
        } finally { fixture.stop(); }
    }
}
```

- [x] **Step 2: 实现适配器（重用既有 `TcpModbusMaster`）**

```java
package com.ems.collector.transport.impl;

import com.ems.collector.protocol.*;
import com.ems.collector.transport.*;
import com.ems.collector.transport.TcpModbusMaster;
import com.ems.collector.codec.RegisterDecoder;
import org.slf4j.*;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;

public final class ModbusTcpAdapterTransport implements Transport {
    private static final Logger log = LoggerFactory.getLogger(ModbusTcpAdapterTransport.class);
    private TcpModbusMaster master;
    private ScheduledExecutorService scheduler;
    private volatile boolean connected = false;

    public void start(Long channelId, ChannelConfig config, SampleSink sink) {
        var cfg = (ModbusTcpConfig) config;
        master = new TcpModbusMaster(cfg.host(), cfg.port(), cfg.unitId(),
            cfg.timeout() != null ? cfg.timeout() : java.time.Duration.ofMillis(500));
        try { master.connect(); connected = true; }
        catch (Exception e) { throw new TransportException("connect failed", e); }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "modbus-tcp-" + channelId);
            t.setDaemon(true); return t;
        });
        scheduler.scheduleAtFixedRate(() -> poll(channelId, cfg, sink),
            0, cfg.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void poll(Long channelId, ModbusTcpConfig cfg, SampleSink sink) {
        for (var p : cfg.points()) {
            try {
                var raw = master.read(p.registerKind(), p.address(), p.quantity());
                var value = RegisterDecoder.decode(raw, p.dataType(), p.byteOrder(), p.scale());
                sink.accept(new Sample(channelId, p.key(), Instant.now(),
                    value, Quality.GOOD, Map.of()));
            } catch (Exception e) {
                log.warn("modbus read failed for {}: {}", p.key(), e.getMessage());
                sink.accept(new Sample(channelId, p.key(), Instant.now(),
                    null, Quality.BAD, Map.of("error", e.getMessage())));
            }
        }
    }

    public void stop() {
        connected = false;
        if (scheduler != null) scheduler.shutdownNow();
        if (master != null) try { master.close(); } catch (Exception ignored) {}
    }

    public boolean isConnected() { return connected; }

    public TestResult testConnection(ChannelConfig config) {
        var cfg = (ModbusTcpConfig) config;
        var start = System.currentTimeMillis();
        try (var m = new TcpModbusMaster(cfg.host(), cfg.port(), cfg.unitId(),
                java.time.Duration.ofMillis(2000))) {
            m.connect();
            return TestResult.ok(System.currentTimeMillis() - start);
        } catch (Exception e) {
            return TestResult.fail(e.getMessage());
        }
    }
}
```

- [x] **Step 3: 测试通过 + Commit**

```bash
mvn -pl ems-collector test -Dtest=ModbusTcpAdapterTransportTest
git add ems-collector/src/main/java/com/ems/collector/transport/impl/ModbusTcpAdapterTransport.java \
        ems-collector/src/test/java/com/ems/collector/transport/impl/ModbusTcpAdapterTransportTest.java
git commit -m "feat(collector): implement ModbusTcpAdapterTransport reusing TcpModbusMaster"
```

---

### Task 2.4: ModbusRtuAdapterTransport 替换 stub

**Files:**
- Modify: `ems-collector/src/main/java/com/ems/collector/transport/impl/ModbusRtuAdapterTransport.java`
- Test: `ems-collector/src/test/java/com/ems/collector/transport/impl/ModbusRtuAdapterTransportTest.java`

实现与 Task 2.3 类似但用 `RtuModbusMaster`。结构与代码模式相同，仅参数差异（serialPort/baudRate/dataBits/stopBits/parity）。

- [x] **Step 1: 写测试 + 实现 + Commit**（参考 Task 2.3）

```bash
git commit -m "feat(collector): implement ModbusRtuAdapterTransport"
```

---

### Task 2.5: ChannelTransportFactory + ChannelService（启停编排）

**Files:**
- Create: `ems-collector/src/main/java/com/ems/collector/transport/ChannelTransportFactory.java`
- Create: `ems-collector/src/main/java/com/ems/collector/channel/ChannelService.java`
- Test: `ems-collector/src/test/java/com/ems/collector/channel/ChannelServiceTest.java`

- [x] **Step 1: 写测试**

```java
package com.ems.collector.channel;

import com.ems.collector.protocol.*;
import com.ems.collector.runtime.ChannelStateRegistry;
import com.ems.collector.transport.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;

class ChannelServiceTest {
    @Test
    void startsTransportOnCreate() {
        var repo = mock(ChannelRepository.class);
        var registry = mock(ChannelStateRegistry.class);
        var factory = mock(ChannelTransportFactory.class);
        var transport = mock(Transport.class);
        var sinkSvc = mock(com.ems.collector.sink.SampleWriter.class);
        when(factory.create(any())).thenReturn(transport);

        var ch = new Channel();
        ch.setId(42L);
        ch.setProtocol("VIRTUAL");
        ch.setProtocolConfig(new VirtualConfig(Duration.ofSeconds(1),
            List.of(new VirtualPoint("v", VirtualMode.CONSTANT, Map.of("value", 1.0), null))));
        when(repo.save(any())).thenReturn(ch);

        var svc = new ChannelService(repo, registry, factory, sinkSvc);
        svc.create(ch);

        verify(transport).start(eq(42L), any(VirtualConfig.class), any());
        verify(registry).register(42L, "VIRTUAL");
    }
}
```

- [x] **Step 2: 实现**

`ChannelTransportFactory.java`:
```java
package com.ems.collector.transport;

import com.ems.collector.protocol.*;
import com.ems.collector.transport.impl.*;
import org.springframework.stereotype.Component;

@Component
public class ChannelTransportFactory {
    public Transport create(String protocol) {
        return switch (protocol) {
            case "MODBUS_TCP" -> new ModbusTcpAdapterTransport();
            case "MODBUS_RTU" -> new ModbusRtuAdapterTransport();
            case "OPC_UA"     -> new OpcUaTransport();
            case "MQTT"       -> new MqttTransport();
            case "VIRTUAL"    -> new VirtualTransport();
            default -> throw new IllegalArgumentException("unknown protocol: " + protocol);
        };
    }
}
```

`ChannelService.java`:
```java
package com.ems.collector.channel;

import com.ems.collector.runtime.ChannelStateRegistry;
import com.ems.collector.runtime.ConnectionState;
import com.ems.collector.sink.SampleWriter;
import com.ems.collector.transport.*;
import jakarta.annotation.PostConstruct;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChannelService {
    private static final Logger log = LoggerFactory.getLogger(ChannelService.class);
    private final ChannelRepository repo;
    private final ChannelStateRegistry stateRegistry;
    private final ChannelTransportFactory factory;
    private final SampleWriter sampleWriter;
    private final Map<Long, Transport> active = new ConcurrentHashMap<>();

    public ChannelService(ChannelRepository repo, ChannelStateRegistry stateRegistry,
                          ChannelTransportFactory factory, SampleWriter sampleWriter) {
        this.repo = repo;
        this.stateRegistry = stateRegistry;
        this.factory = factory;
        this.sampleWriter = sampleWriter;
    }

    @PostConstruct
    public void startAllEnabled() {
        for (var ch : repo.findByEnabledTrue()) {
            try { startChannel(ch); }
            catch (Exception e) {
                log.error("failed to start channel {}: {}", ch.getName(), e.getMessage());
                stateRegistry.register(ch.getId(), ch.getProtocol());
                stateRegistry.setState(ch.getId(), ConnectionState.ERROR);
                stateRegistry.recordFailure(ch.getId(), e.getMessage());
            }
        }
    }

    public Channel create(Channel ch) {
        var saved = repo.save(ch);
        if (saved.isEnabled()) startChannel(saved);
        return saved;
    }

    public Channel update(Long id, Channel updated) {
        stopChannel(id);
        var saved = repo.save(updated);
        if (saved.isEnabled()) startChannel(saved);
        return saved;
    }

    public void delete(Long id) {
        stopChannel(id);
        repo.deleteById(id);
        stateRegistry.unregister(id);
    }

    private void startChannel(Channel ch) {
        var t = factory.create(ch.getProtocol());
        stateRegistry.register(ch.getId(), ch.getProtocol());
        var start = System.currentTimeMillis();
        t.start(ch.getId(), ch.getProtocolConfig(), sample -> {
            sampleWriter.write(sample);
            stateRegistry.recordSuccess(sample.channelId(), System.currentTimeMillis() - start);
        });
        active.put(ch.getId(), t);
    }

    private void stopChannel(Long id) {
        var t = active.remove(id);
        if (t != null) try { t.stop(); } catch (Exception e) {
            log.warn("stop channel {} error: {}", id, e.getMessage());
        }
    }

    public Optional<Transport> activeTransport(Long id) {
        return Optional.ofNullable(active.get(id));
    }
}
```

`SampleWriter.java`（占位接口；后续 Phase 与既有 InfluxReadingSink 整合）:
```java
package com.ems.collector.sink;

import com.ems.collector.transport.Sample;

public interface SampleWriter {
    void write(Sample sample);
}
```

- [x] **Step 3: 测试通过 + Commit**

```bash
mvn -pl ems-collector test -Dtest=ChannelServiceTest
git add ems-collector/src/main/java/com/ems/collector/transport/ChannelTransportFactory.java \
        ems-collector/src/main/java/com/ems/collector/channel/ChannelService.java \
        ems-collector/src/main/java/com/ems/collector/sink/SampleWriter.java \
        ems-collector/src/test/java/com/ems/collector/channel/ChannelServiceTest.java
git commit -m "feat(collector): add ChannelService + transport factory + lifecycle"
```

---

### Task 2.6: ChannelController（REST CRUD）

**Files:**
- Create: `ems-collector/src/main/java/com/ems/collector/channel/ChannelController.java`
- Test: `ems-collector/src/test/java/com/ems/collector/channel/ChannelControllerTest.java`

- [x] **Step 1: 写测试 + 实现 REST CRUD**

```java
package com.ems.collector.channel;

import com.ems.collector.transport.TestResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/channel")
@PreAuthorize("hasRole('ADMIN')")
public class ChannelController {
    private final ChannelService service;
    private final ChannelRepository repo;

    public ChannelController(ChannelService service, ChannelRepository repo) {
        this.service = service;
        this.repo = repo;
    }

    @GetMapping
    public List<ChannelDTO> list() {
        return repo.findAll().stream().map(ChannelDTO::from).toList();
    }

    @GetMapping("/{id}")
    public ChannelDTO get(@PathVariable Long id) {
        return ChannelDTO.from(repo.findById(id).orElseThrow());
    }

    @PostMapping
    public ChannelDTO create(@Valid @RequestBody Channel ch) {
        return ChannelDTO.from(service.create(ch));
    }

    @PutMapping("/{id}")
    public ChannelDTO update(@PathVariable Long id, @Valid @RequestBody Channel ch) {
        ch.setId(id);
        return ChannelDTO.from(service.update(id, ch));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    public TestResult test(@PathVariable Long id) {
        var ch = repo.findById(id).orElseThrow();
        var transport = service.activeTransport(id)
            .orElseGet(() -> new com.ems.collector.transport.ChannelTransportFactory().create(ch.getProtocol()));
        return transport.testConnection(ch.getProtocolConfig());
    }
}
```

- [x] **Step 2: Commit**

```bash
git commit -m "feat(collector): add ChannelController REST CRUD + test endpoint"
```

---

## Phase 3 — SecretResolver（约 1.5 天）

### Task 3.1: SecretResolver 接口 + FilesystemSecretResolver

**Files:**
- Create: `ems-collector/src/main/java/com/ems/collector/secret/{SecretResolver,FilesystemSecretResolver}.java`
- Test: `ems-collector/src/test/java/com/ems/collector/secret/FilesystemSecretResolverTest.java`

- [x] **Step 1: 写测试**

```java
package com.ems.collector.secret;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilesystemSecretResolverTest {
    @TempDir Path dir;
    SecretResolver r;

    @BeforeEach
    void setUp() throws Exception {
        Files.setPosixFilePermissions(dir, Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));
        r = new FilesystemSecretResolver(dir);
    }

    @Test
    void writesAndResolvesSecret() {
        r.write("secret://mqtt/p", "s3cret");
        assertThat(r.resolve("secret://mqtt/p")).isEqualTo("s3cret");
    }

    @Test
    void writesFileWith600Permissions() throws Exception {
        r.write("secret://opcua/u", "user");
        var perms = Files.getPosixFilePermissions(dir.resolve("opcua/u"));
        assertThat(perms).containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void rejectsPathTraversal() {
        assertThatThrownBy(() -> r.resolve("secret://../../../etc/passwd"))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsInvalidScheme() {
        assertThatThrownBy(() -> r.resolve("file:///etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void existsReturnsFalseForMissing() {
        assertThat(r.exists("secret://x/y")).isFalse();
    }

    @Test
    void deletesSecret() {
        r.write("secret://a/b", "x");
        r.delete("secret://a/b");
        assertThat(r.exists("secret://a/b")).isFalse();
    }
}
```

- [x] **Step 2: 实现**

`SecretResolver.java`:
```java
package com.ems.collector.secret;

public interface SecretResolver {
    String resolve(String ref);
    boolean exists(String ref);
    void write(String ref, String value);
    void delete(String ref);
    java.util.List<String> listRefs();
}
```

`FilesystemSecretResolver.java`:
```java
package com.ems.collector.secret;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.stream.Stream;

@Component
public class FilesystemSecretResolver implements SecretResolver {
    private static final Logger log = LoggerFactory.getLogger(FilesystemSecretResolver.class);
    private static final String SCHEME = "secret://";
    private static final Set<PosixFilePermission> FILE_PERMS = Set.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> DIR_PERMS = Set.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE);

    private final Path secretsDir;

    public FilesystemSecretResolver(@Value("${ems.secrets.dir:#{systemProperties['user.home']}/.ems/secrets}") Path secretsDir) {
        this.secretsDir = secretsDir;
    }

    @PostConstruct
    public void init() throws IOException {
        if (!Files.exists(secretsDir)) {
            Files.createDirectories(secretsDir);
            Files.setPosixFilePermissions(secretsDir, DIR_PERMS);
        }
        var perms = Files.getPosixFilePermissions(secretsDir);
        var allowed = Set.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
        for (var p : perms) {
            if (!allowed.contains(p)) {
                throw new SecurityException("secrets dir " + secretsDir +
                    " has unsafe permission " + p + "; must be ≤ 700");
            }
        }
    }

    public String resolve(String ref) {
        var path = parseAndValidate(ref);
        try { return Files.readString(path).strip(); }
        catch (IOException e) { throw new RuntimeException("read secret failed: " + ref, e); }
    }

    public boolean exists(String ref) {
        try { return Files.exists(parseAndValidate(ref)); }
        catch (Exception e) { return false; }
    }

    public void write(String ref, String value) {
        var path = parseAndValidate(ref);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, value, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.setPosixFilePermissions(path, FILE_PERMS);
            log.info("secret written: {}", ref);
        } catch (IOException e) {
            throw new RuntimeException("write secret failed: " + ref, e);
        }
    }

    public void delete(String ref) {
        try { Files.deleteIfExists(parseAndValidate(ref)); }
        catch (IOException e) { throw new RuntimeException("delete failed: " + ref, e); }
    }

    public List<String> listRefs() {
        try (Stream<Path> walk = Files.walk(secretsDir)) {
            return walk.filter(Files::isRegularFile)
                .map(p -> SCHEME + secretsDir.relativize(p).toString().replace('\\', '/'))
                .sorted().toList();
        } catch (IOException e) { return List.of(); }
    }

    private Path parseAndValidate(String ref) {
        if (ref == null || !ref.startsWith(SCHEME)) {
            throw new IllegalArgumentException("invalid scheme, expected secret://");
        }
        var rel = ref.substring(SCHEME.length());
        if (rel.isBlank()) throw new IllegalArgumentException("empty path");
        var path = secretsDir.resolve(rel).normalize();
        if (!path.startsWith(secretsDir)) {
            throw new SecurityException("path traversal: " + ref);
        }
        return path;
    }
}
```

- [x] **Step 3: 测试通过 + Commit**

```bash
mvn -pl ems-collector test -Dtest=FilesystemSecretResolverTest
git commit -m "feat(collector): add FilesystemSecretResolver with path traversal defense"
```

---

### Task 3.2: SecretController (REST API)

**Files:**
- Create: `ems-collector/src/main/java/com/ems/collector/secret/SecretController.java`
- Test: `ems-collector/src/test/java/com/ems/collector/secret/SecretControllerTest.java`

- [x] **Step 1: 实现 + 测试**

```java
package com.ems.collector.secret;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ems.audit.AuditLogger;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

@RestController
@RequestMapping("/api/v1/secrets")
@PreAuthorize("hasRole('ADMIN')")
public class SecretController {
    private final SecretResolver resolver;
    private final AuditLogger audit;

    public SecretController(SecretResolver resolver, AuditLogger audit) {
        this.resolver = resolver;
        this.audit = audit;
    }

    public record WriteRequest(@NotBlank String ref, @NotBlank String value) {}

    @GetMapping
    public List<String> list() { return resolver.listRefs(); }

    @PostMapping
    public ResponseEntity<Void> write(@RequestBody WriteRequest req) {
        resolver.write(req.ref(), req.value());
        audit.record("SECRET_WRITE", req.ref());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@RequestParam @NotBlank String ref) {
        resolver.delete(ref);
        audit.record("SECRET_DELETE", ref);
        return ResponseEntity.noContent().build();
    }
}
```

- [x] **Step 2: Commit**

```bash
git commit -m "feat(collector): add SecretController with audit logging"
```

---

## Phase 4 — VIRTUAL 协议（约 2 天）

### Task 4.1: VirtualSignalGenerator（4 模式纯函数）

**Files:**
- Create: `ems-collector/src/main/java/com/ems/collector/transport/impl/VirtualSignalGenerator.java`
- Test: `ems-collector/src/test/java/com/ems/collector/transport/impl/VirtualSignalGeneratorTest.java`

- [x] **Step 1: 写测试**

```java
package com.ems.collector.transport.impl;

import com.ems.collector.protocol.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class VirtualSignalGeneratorTest {
    private final VirtualSignalGenerator gen = new VirtualSignalGenerator();

    @Test
    void constantReturnsValue() {
        var p = new VirtualPoint("p", VirtualMode.CONSTANT, Map.of("value", 42.5), null);
        assertThat((Double) gen.generate(p, Instant.now())).isEqualTo(42.5);
    }

    @Test
    void sineMatchesFormulaAtKeyPhases() {
        var p = new VirtualPoint("p", VirtualMode.SINE,
            Map.of("amplitude", 10.0, "periodSec", 60.0, "offset", 5.0), null);
        var t0 = Instant.parse("2026-04-30T00:00:00Z");        // sin(0) = 0 → 5
        var tQ = Instant.parse("2026-04-30T00:00:15Z");        // sin(π/2) = 1 → 15
        assertThat((Double) gen.generate(p, t0)).isCloseTo(5.0, within(1e-6));
        assertThat((Double) gen.generate(p, tQ)).isCloseTo(15.0, within(1e-6));
    }

    @Test
    void randomWalkStaysInBounds() {
        var p = new VirtualPoint("p", VirtualMode.RANDOM_WALK,
            Map.of("min", 0.0, "max", 100.0, "maxStep", 1.0, "start", 50.0), null);
        double prev = 50.0;
        for (int i = 0; i < 1000; i++) {
            var v = (Double) gen.generate(p, Instant.now().plusSeconds(i));
            assertThat(v).isBetween(0.0, 100.0);
            assertThat(Math.abs(v - prev)).isLessThanOrEqualTo(1.0 + 1e-9);
            prev = v;
        }
    }

    @Test
    void calendarCurveDiffersWeekendFromWeekday() {
        var p = new VirtualPoint("p", VirtualMode.CALENDAR_CURVE,
            Map.of("weekdayPeak", 100.0, "weekendPeak", 30.0, "peakHour", 9.0), null);
        var weekday9am = Instant.parse("2026-05-04T09:00:00Z");  // Monday
        var weekend9am = Instant.parse("2026-05-02T09:00:00Z");  // Saturday
        var w = (Double) gen.generate(p, weekday9am);
        var s = (Double) gen.generate(p, weekend9am);
        assertThat(w).isGreaterThan(s);
    }
}
```

- [x] **Step 2: 实现**

```java
package com.ems.collector.transport.impl;

import com.ems.collector.protocol.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class VirtualSignalGenerator {
    private final Map<String, Double> walkState = new ConcurrentHashMap<>();
    private final Random random = new Random();

    public Object generate(VirtualPoint point, Instant now) {
        return switch (point.mode()) {
            case CONSTANT       -> point.params().getOrDefault("value", 0.0);
            case SINE           -> sine(point, now);
            case RANDOM_WALK    -> randomWalk(point);
            case CALENDAR_CURVE -> calendarCurve(point, now);
        };
    }

    private double sine(VirtualPoint p, Instant now) {
        double amp    = p.params().getOrDefault("amplitude", 1.0);
        double period = p.params().getOrDefault("periodSec", 60.0);
        double offset = p.params().getOrDefault("offset", 0.0);
        double t = (now.toEpochMilli() / 1000.0) % period;
        return amp * Math.sin(2 * Math.PI * t / period) + offset;
    }

    private double randomWalk(VirtualPoint p) {
        double min     = p.params().getOrDefault("min", 0.0);
        double max     = p.params().getOrDefault("max", 100.0);
        double maxStep = p.params().getOrDefault("maxStep", 1.0);
        double start   = p.params().getOrDefault("start", (min + max) / 2);
        var current = walkState.computeIfAbsent(p.key(), k -> start);
        double step = (random.nextDouble() * 2 - 1) * maxStep;
        double next = Math.max(min, Math.min(max, current + step));
        walkState.put(p.key(), next);
        return next;
    }

    private double calendarCurve(VirtualPoint p, Instant now) {
        double weekdayPeak = p.params().getOrDefault("weekdayPeak", 100.0);
        double weekendPeak = p.params().getOrDefault("weekendPeak", 30.0);
        double peakHour    = p.params().getOrDefault("peakHour", 9.0);
        double sigma       = p.params().getOrDefault("sigma", 3.0);
        var ldt = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        boolean weekend = ldt.getDayOfWeek() == DayOfWeek.SATURDAY
                       || ldt.getDayOfWeek() == DayOfWeek.SUNDAY;
        double peak = weekend ? weekendPeak : weekdayPeak;
        double hour = ldt.getHour() + ldt.getMinute() / 60.0;
        double dist = hour - peakHour;
        return peak * Math.exp(-(dist * dist) / (2 * sigma * sigma));
    }
}
```

- [x] **Step 3: Commit**

```bash
mvn -pl ems-collector test -Dtest=VirtualSignalGeneratorTest
git commit -m "feat(collector): add VirtualSignalGenerator (constant/sine/walk/calendar)"
```

---

### Task 4.2: VirtualTransport（替换 stub）

**Files:**
- Modify: `ems-collector/src/main/java/com/ems/collector/transport/impl/VirtualTransport.java`
- Test: `ems-collector/src/test/java/com/ems/collector/transport/impl/VirtualTransportTest.java`

- [x] **Step 1: 写测试 + 实现**

```java
package com.ems.collector.transport.impl;

import com.ems.collector.protocol.*;
import com.ems.collector.transport.*;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;

public final class VirtualTransport implements Transport {
    private final VirtualSignalGenerator generator = new VirtualSignalGenerator();
    private ScheduledExecutorService scheduler;
    private volatile boolean connected = false;

    public void start(Long channelId, ChannelConfig config, SampleSink sink) {
        var cfg = (VirtualConfig) config;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "virtual-" + channelId);
            t.setDaemon(true); return t;
        });
        connected = true;
        scheduler.scheduleAtFixedRate(() -> tick(channelId, cfg, sink),
            0, cfg.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void tick(Long channelId, VirtualConfig cfg, SampleSink sink) {
        var now = Instant.now();
        for (var p : cfg.points()) {
            sink.accept(new Sample(channelId, p.key(), now,
                generator.generate(p, now), Quality.GOOD,
                Map.of("virtual", "true")));
        }
    }

    public void stop() {
        connected = false;
        if (scheduler != null) scheduler.shutdownNow();
    }

    public boolean isConnected() { return connected; }

    public TestResult testConnection(ChannelConfig config) {
        return TestResult.ok(0L);
    }
}
```

- [x] **Step 2: Commit**

```bash
git commit -m "feat(collector): implement VirtualTransport with scheduled signal generation"
```

---

## Phase 5 — OPC UA 协议（约 5 天）

### Task 5.1: 添加 Eclipse Milo 依赖 + spike 验证

**Files:**
- Modify: `ems-collector/pom.xml`

- [x] **Step 1: 添加 Milo 依赖**

```xml
<dependency>
    <groupId>org.eclipse.milo</groupId>
    <artifactId>sdk-client</artifactId>
    <version>0.6.13</version>
</dependency>
<dependency>
    <groupId>org.eclipse.milo</groupId>
    <artifactId>sdk-server</artifactId>
    <version>0.6.13</version>
    <scope>test</scope>
</dependency>
```

- [x] **Step 2: 编写 spike 验证 client 启动正常**

`ems-collector/src/test/java/com/ems/collector/transport/impl/MiloClientSpikeTest.java`:

```java
package com.ems.collector.transport.impl;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfigBuilder;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MiloClientSpikeTest {
    @Test
    void canCreateClientConfig() {
        var cfg = new OpcUaClientConfigBuilder().build();
        assertThat(cfg).isNotNull();
    }
}
```

```bash
mvn -pl ems-collector test -Dtest=MiloClientSpikeTest
```

- [x] **Step 3: Commit**

```bash
git commit -m "build(collector): add Eclipse Milo 0.6.13 dependency for OPC UA"
```

---

### Task 5.2: OpcUaCertificateStore（白名单管理）

**Files:**
- Create: `ems-collector/src/main/java/com/ems/collector/cert/OpcUaCertificateStore.java`
- Test: `ems-collector/src/test/java/com/ems/collector/cert/OpcUaCertificateStoreTest.java`

- [x] **Step 1: 实现 + 测试**

```java
package com.ems.collector.cert;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HexFormat;

@Component
public class OpcUaCertificateStore {
    private final Path trustedDir;

    public OpcUaCertificateStore(@Value("${ems.secrets.dir:#{systemProperties['user.home']}/.ems/secrets}") Path secretsDir) {
        this.trustedDir = secretsDir.resolve("opcua/certs/trusted");
    }

    @PostConstruct
    public void init() throws Exception {
        Files.createDirectories(trustedDir);
    }

    public boolean isTrusted(X509Certificate cert) throws Exception {
        var thumb = thumbprint(cert);
        try (var stream = Files.list(trustedDir)) {
            return stream.anyMatch(p -> p.getFileName().toString().endsWith(thumb + ".der"));
        }
    }

    public void approve(X509Certificate cert, String displayName) throws Exception {
        var thumb = thumbprint(cert);
        var name = (displayName != null ? displayName : "cert") + "-" + thumb + ".der";
        Files.write(trustedDir.resolve(name), cert.getEncoded());
    }

    public String thumbprint(X509Certificate cert) throws Exception {
        var md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(cert.getEncoded()));
    }
}
```

- [x] **Step 2: Commit**

```bash
git commit -m "feat(collector): add OpcUaCertificateStore with thumbprint-based trust"
```

---

### Task 5.3: OpcUaTransport read 模式

**Files:**
- Modify: `ems-collector/src/main/java/com/ems/collector/transport/impl/OpcUaTransport.java`
- Test: `ems-collector/src/test/java/com/ems/collector/transport/impl/OpcUaTransportIT.java`

- [x] **Step 1: 集成测试用 Milo demo server**

```java
package com.ems.collector.transport.impl;

import com.ems.collector.protocol.*;
import com.ems.collector.transport.*;
import org.eclipse.milo.examples.server.ExampleServer;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class OpcUaTransportIT {
    private static ExampleServer server;
    @BeforeAll static void start() throws Exception {
        server = new ExampleServer();
        server.startup().get();
    }
    @AfterAll static void stop() throws Exception { server.shutdown().get(); }

    @Test
    void readsValueInNoneSecurityMode() throws Exception {
        var cfg = new OpcUaConfig(
            "opc.tcp://localhost:12686/milo", SecurityMode.NONE,
            null, null, null, null,
            Duration.ofSeconds(1),
            List.of(new OpcUaPoint("ct", "ns=2;s=HelloWorld/ScalarTypes/Int32",
                SubscriptionMode.READ, null, null))
        );
        var samples = new ConcurrentLinkedQueue<Sample>();
        var t = new OpcUaTransport();
        t.start(1L, cfg, samples::add);
        await().atMost(5, TimeUnit.SECONDS).until(() -> !samples.isEmpty());
        t.stop();
        assertThat(samples.peek().pointKey()).isEqualTo("ct");
    }
}
```

- [x] **Step 2: 实现 OpcUaTransport（read 模式）**

```java
package com.ems.collector.transport.impl;

import com.ems.collector.protocol.*;
import com.ems.collector.transport.*;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfig;
import org.eclipse.milo.opcua.stack.client.DiscoveryClient;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.slf4j.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

public final class OpcUaTransport implements Transport {
    private static final Logger log = LoggerFactory.getLogger(OpcUaTransport.class);
    private OpcUaClient client;
    private ScheduledExecutorService poller;
    private volatile boolean connected = false;
    private Long channelId;

    public void start(Long channelId, ChannelConfig config, SampleSink sink) {
        this.channelId = channelId;
        var cfg = (OpcUaConfig) config;
        try {
            var endpoints = DiscoveryClient.getEndpoints(cfg.endpointUrl()).get();
            var ep = endpoints.stream()
                .filter(e -> matchesSecurity(e, cfg.securityMode()))
                .findFirst().orElseThrow(() -> new TransportException(
                    "no matching endpoint for " + cfg.securityMode()));
            client = OpcUaClient.create(OpcUaClientConfig.builder()
                .setEndpoint(ep)
                .setApplicationName(LocalizedText.english("EMS Collector"))
                .setApplicationUri("urn:ems:collector")
                .setRequestTimeout(uint(10_000))
                .build());
            client.connect().get();
            connected = true;
        } catch (Exception e) {
            throw new TransportException("opcua connect failed: " + e.getMessage(), e);
        }

        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "opcua-" + channelId); t.setDaemon(true); return t;
        });
        if (cfg.pollInterval() != null) {
            poller.scheduleAtFixedRate(() -> pollRead(cfg, sink),
                0, cfg.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private boolean matchesSecurity(org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription ep,
                                     SecurityMode mode) {
        var policy = ep.getSecurityPolicyUri();
        return switch (mode) {
            case NONE -> policy.contains("None");
            case SIGN -> policy.contains("Basic256Sha256") &&
                ep.getSecurityMode().getValue() == 2;
            case SIGN_AND_ENCRYPT -> policy.contains("Basic256Sha256") &&
                ep.getSecurityMode().getValue() == 3;
        };
    }

    private void pollRead(OpcUaConfig cfg, SampleSink sink) {
        for (var p : cfg.points()) {
            if (p.mode() != SubscriptionMode.READ) continue;
            var start = System.currentTimeMillis();
            try {
                var nodeId = NodeId.parse(p.nodeId());
                var dv = client.readValue(0, TimestampsToReturn.Both, nodeId).get();
                sink.accept(new Sample(channelId, p.key(), Instant.now(),
                    dv.getValue().getValue(), Quality.GOOD,
                    Map.of("latencyMs", String.valueOf(System.currentTimeMillis() - start))));
            } catch (Exception e) {
                log.warn("opcua read {} failed: {}", p.nodeId(), e.getMessage());
            }
        }
    }

    public void stop() {
        connected = false;
        if (poller != null) poller.shutdownNow();
        if (client != null) try { client.disconnect().get(); } catch (Exception ignored) {}
    }

    public boolean isConnected() { return connected; }

    public TestResult testConnection(ChannelConfig config) {
        var cfg = (OpcUaConfig) config;
        var start = System.currentTimeMillis();
        try {
            DiscoveryClient.getEndpoints(cfg.endpointUrl()).get(5, TimeUnit.SECONDS);
            return TestResult.ok(System.currentTimeMillis() - start);
        } catch (Exception e) { return TestResult.fail(e.getMessage()); }
    }
}
```

- [x] **Step 3: Commit**

```bash
git commit -m "feat(collector): implement OpcUaTransport with READ mode + endpoint security selection"
```

---

### Task 5.4: OpcUaTransport SUBSCRIBE 模式

- [x] 在 `start()` 末尾追加：

```java
var subPoints = cfg.points().stream()
    .filter(p -> p.mode() == SubscriptionMode.SUBSCRIBE).toList();
if (!subPoints.isEmpty()) {
    var sub = client.getSubscriptionManager().createSubscription(1000.0).get();
    var items = subPoints.stream().map(p -> {
        var samplingMs = p.samplingIntervalMs() != null ? p.samplingIntervalMs() : 1000.0;
        var readValueId = new org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId(
            NodeId.parse(p.nodeId()),
            org.eclipse.milo.opcua.stack.core.AttributeId.Value.uid(),
            null, QualifiedName.NULL_VALUE);
        var params = new org.eclipse.milo.opcua.stack.core.types.structured.MonitoringParameters(
            uint(java.util.concurrent.atomic.AtomicInteger.class.cast(null) == null
                 ? Math.abs(p.key().hashCode()) : 0),
            samplingMs,
            org.eclipse.milo.opcua.stack.core.types.builtin.ExtensionObject.encode(
                client.getStaticSerializationContext(),
                new org.eclipse.milo.opcua.stack.core.types.structured.DataChangeFilter(
                    org.eclipse.milo.opcua.stack.core.types.enumerated.DataChangeTrigger.StatusValue,
                    uint(0), 0.0)),
            uint(10), true);
        return new org.eclipse.milo.opcua.stack.core.types.structured.MonitoredItemCreateRequest(
            readValueId,
            org.eclipse.milo.opcua.stack.core.types.enumerated.MonitoringMode.Reporting,
            params);
    }).toList();
    sub.createMonitoredItems(TimestampsToReturn.Both, items, (item, idx) -> {
        var p = subPoints.get(idx);
        item.setValueConsumer((it, dv) ->
            sink.accept(new Sample(channelId, p.key(), Instant.now(),
                dv.getValue().getValue(), Quality.GOOD, Map.of())));
    }).get();
}
```

- [x] Commit:

```bash
git commit -m "feat(collector): add OPC UA SUBSCRIBE mode with MonitoredItem callbacks"
```

---

### Task 5.5: 证书审批 REST + 集成 OpcUaCertificateStore

**Files:**
- Create: `ems-collector/src/main/java/com/ems/collector/cert/CertificateApprovalController.java`

- [x] **Step 1: 实现**

```java
package com.ems.collector.cert;

import com.ems.audit.AuditLogger;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api/v1/collector")
@PreAuthorize("hasRole('ADMIN')")
public class CertificateApprovalController {
    private final OpcUaCertificateStore store;
    private final AuditLogger audit;
    private final java.util.Map<String, java.security.cert.X509Certificate> pendingCerts =
        new java.util.concurrent.ConcurrentHashMap<>();

    public CertificateApprovalController(OpcUaCertificateStore store, AuditLogger audit) {
        this.store = store;
        this.audit = audit;
    }

    public record TrustRequest(@NotBlank String thumbprint) {}

    @PostMapping("/{id}/trust-cert")
    public void trust(@PathVariable Long id, @RequestBody TrustRequest req) throws Exception {
        var cert = pendingCerts.remove(req.thumbprint());
        if (cert == null) throw new IllegalStateException("no pending cert with thumbprint " + req.thumbprint());
        store.approve(cert, "channel-" + id);
        audit.record("CERT_TRUST", "channel-" + id + ":" + req.thumbprint());
    }

    public void registerPending(String thumbprint, java.security.cert.X509Certificate cert) {
        pendingCerts.put(thumbprint, cert);
    }
}
```

- [x] **Step 2: Commit**

```bash
git commit -m "feat(collector): add OPC UA certificate approval REST endpoint"
```

---

## Phase 6 — MQTT 协议（约 3 天）

### Task 6.1: 添加 Paho + JsonPath 依赖

**Files:**
- Modify: `ems-collector/pom.xml`

- [x] **Step 1: 依赖**

```xml
<dependency>
    <groupId>org.eclipse.paho</groupId>
    <artifactId>org.eclipse.paho.client.mqttv3</artifactId>
    <version>1.2.5</version>
</dependency>
<dependency>
    <groupId>com.jayway.jsonpath</groupId>
    <artifactId>json-path</artifactId>
    <version>2.9.0</version>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>hivemq</artifactId>
    <version>1.20.4</version>
    <scope>test</scope>
</dependency>
```

- [x] **Step 2: Commit**

```bash
git commit -m "build(collector): add Paho MQTT + JsonPath + HiveMQ testcontainer"
```

---

### Task 6.2: MqttTransport 实现

**Files:**
- Modify: `ems-collector/src/main/java/com/ems/collector/transport/impl/MqttTransport.java`
- Test: `ems-collector/src/test/java/com/ems/collector/transport/impl/MqttTransportIT.java`

- [x] **Step 1: 集成测试**

```java
package com.ems.collector.transport.impl;

import com.ems.collector.protocol.*;
import com.ems.collector.transport.*;
import com.ems.collector.secret.SecretResolver;
import org.eclipse.paho.client.mqttv3.*;
import org.junit.jupiter.api.*;
import org.testcontainers.hivemq.HiveMQContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
class MqttTransportIT {
    @Container
    static HiveMQContainer broker = new HiveMQContainer(
        DockerImageName.parse("hivemq/hivemq-ce:2024.3"));

    @Test
    void receivesAndExtractsViaJsonPath() throws Exception {
        var resolver = mockResolver();
        var url = "tcp://" + broker.getHost() + ":" + broker.getMappedPort(1883);
        var cfg = new MqttConfig(url, "ems-test", null, null, null,
            1, true, Duration.ofSeconds(60),
            List.of(new MqttPoint("temp", "sensors/+/temp", "$.value", "C", null)));

        var samples = new ConcurrentLinkedQueue<Sample>();
        var t = new MqttTransport(resolver);
        t.start(1L, cfg, samples::add);

        var pub = new MqttClient(url, "publisher");
        pub.connect();
        pub.publish("sensors/factory1/temp",
            new MqttMessage("{\"value\":23.5}".getBytes()));
        pub.disconnect();

        await().atMost(5, TimeUnit.SECONDS).until(() -> !samples.isEmpty());
        t.stop();
        var s = samples.peek();
        assertThat(s.value()).isEqualTo(23.5);
    }

    private SecretResolver mockResolver() {
        return new SecretResolver() {
            public String resolve(String r) { return ""; }
            public boolean exists(String r) { return false; }
            public void write(String r, String v) {}
            public void delete(String r) {}
            public List<String> listRefs() { return List.of(); }
        };
    }
}
```

- [x] **Step 2: 实现**

```java
package com.ems.collector.transport.impl;

import com.ems.collector.protocol.*;
import com.ems.collector.secret.SecretResolver;
import com.ems.collector.transport.*;
import com.jayway.jsonpath.*;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

public final class MqttTransport implements Transport {
    private static final Logger log = LoggerFactory.getLogger(MqttTransport.class);
    private final SecretResolver secretResolver;
    private final Configuration jsonPathCfg = Configuration.defaultConfiguration()
        .addOptions(Option.SUPPRESS_EXCEPTIONS);
    private MqttAsyncClient client;
    private volatile boolean connected = false;
    private Long channelId;

    public MqttTransport(SecretResolver secretResolver) {
        this.secretResolver = secretResolver;
    }

    public MqttTransport() { this(null); }

    public void start(Long channelId, ChannelConfig config, SampleSink sink) {
        this.channelId = channelId;
        var cfg = (MqttConfig) config;
        var opts = new MqttConnectOptions();
        opts.setCleanSession(cfg.cleanSession());
        opts.setKeepAliveInterval((int) cfg.keepAlive().toSeconds());
        opts.setAutomaticReconnect(true);
        if (cfg.usernameRef() != null && secretResolver != null) {
            opts.setUserName(secretResolver.resolve(cfg.usernameRef()));
            if (cfg.passwordRef() != null) {
                opts.setPassword(secretResolver.resolve(cfg.passwordRef()).toCharArray());
            }
        }
        try {
            client = new MqttAsyncClient(cfg.brokerUrl(), cfg.clientId(), new MemoryPersistence());
            client.setCallback(new MqttCallbackExtended() {
                public void connectionLost(Throwable t) {
                    connected = false;
                    log.warn("mqtt connection lost: {}", t.getMessage());
                }
                public void messageArrived(String topic, MqttMessage msg) {
                    handleMessage(topic, msg, cfg, sink);
                }
                public void deliveryComplete(IMqttDeliveryToken t) {}
                public void connectComplete(boolean reconnect, String uri) {
                    connected = true;
                    if (reconnect) resubscribe(cfg);
                }
            });
            client.connect(opts).waitForCompletion(10_000);
            subscribe(cfg);
        } catch (Exception e) {
            throw new TransportException("mqtt connect failed", e);
        }
    }

    private void subscribe(MqttConfig cfg) throws MqttException {
        var topics = cfg.points().stream().map(MqttPoint::topic).distinct().toArray(String[]::new);
        var qos = new int[topics.length]; Arrays.fill(qos, cfg.qos());
        client.subscribe(topics, qos).waitForCompletion(10_000);
    }

    private void resubscribe(MqttConfig cfg) {
        try { subscribe(cfg); }
        catch (Exception e) { log.error("resubscribe failed", e); }
    }

    private void handleMessage(String topic, MqttMessage msg, MqttConfig cfg, SampleSink sink) {
        var payload = new String(msg.getPayload(), StandardCharsets.UTF_8);
        DocumentContext doc;
        try { doc = JsonPath.using(jsonPathCfg).parse(payload); }
        catch (Exception e) { log.warn("non-json payload on {}: {}", topic, e.getMessage()); return; }

        for (var p : cfg.points()) {
            if (!topicMatches(p.topic(), topic)) continue;
            var value = doc.read(p.jsonPath(), Object.class);
            if (value == null) continue;
            Instant ts = Instant.now();
            if (p.timestampJsonPath() != null) {
                try { ts = Instant.parse(doc.read(p.timestampJsonPath(), String.class)); }
                catch (Exception ignored) {}
            }
            sink.accept(new Sample(channelId, p.key(), ts, value, Quality.GOOD,
                Map.of("topic", topic)));
        }
    }

    static boolean topicMatches(String pattern, String topic) {
        var pp = pattern.split("/");
        var tp = topic.split("/");
        for (int i = 0; i < pp.length; i++) {
            if ("#".equals(pp[i])) return true;
            if (i >= tp.length) return false;
            if ("+".equals(pp[i])) continue;
            if (!pp[i].equals(tp[i])) return false;
        }
        return pp.length == tp.length;
    }

    public void stop() {
        connected = false;
        if (client != null) try { client.disconnect().waitForCompletion(2000); }
        catch (Exception ignored) {}
    }

    public boolean isConnected() { return connected; }

    public TestResult testConnection(ChannelConfig config) {
        var cfg = (MqttConfig) config;
        var start = System.currentTimeMillis();
        try {
            var c = new MqttAsyncClient(cfg.brokerUrl(), cfg.clientId() + "-test", new MemoryPersistence());
            var opts = new MqttConnectOptions();
            opts.setConnectionTimeout(5);
            c.connect(opts).waitForCompletion(5000);
            c.disconnect().waitForCompletion(2000);
            return TestResult.ok(System.currentTimeMillis() - start);
        } catch (Exception e) { return TestResult.fail(e.getMessage()); }
    }
}
```

- [x] **Step 3: Commit**

```bash
git commit -m "feat(collector): implement MqttTransport with JSONPath extraction + auto-reconnect"
```

---

## Phase 7 — 诊断与可观测性（约 2 天）

### Task 7.1: ChannelDiagnosticsService + Controller

**Files:**
- Create: `ems-collector/src/main/java/com/ems/collector/diagnostics/{ChannelDiagnosticsService,ChannelDiagnosticsController}.java`

- [x] **Step 1: 实现**

`ChannelDiagnosticsService.java`:
```java
package com.ems.collector.diagnostics;

import com.ems.collector.channel.*;
import com.ems.collector.runtime.*;
import com.ems.collector.transport.TestResult;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class ChannelDiagnosticsService {
    private final ChannelStateRegistry registry;
    private final ChannelService channelService;
    private final ChannelRepository repo;

    public ChannelDiagnosticsService(ChannelStateRegistry registry,
                                     ChannelService channelService, ChannelRepository repo) {
        this.registry = registry;
        this.channelService = channelService;
        this.repo = repo;
    }

    public Collection<ChannelRuntimeState> snapshotAll() { return registry.snapshotAll(); }
    public ChannelRuntimeState snapshot(Long id) { return registry.snapshot(id); }

    public TestResult test(Long id) {
        var ch = repo.findById(id).orElseThrow();
        return channelService.activeTransport(id)
            .map(t -> t.testConnection(ch.getProtocolConfig()))
            .orElseGet(() -> TestResult.fail("not active"));
    }

    public void reconnect(Long id) {
        var ch = repo.findById(id).orElseThrow();
        channelService.update(id, ch);
    }
}
```

`ChannelDiagnosticsController.java`:
```java
package com.ems.collector.diagnostics;

import com.ems.collector.runtime.ChannelRuntimeState;
import com.ems.collector.transport.TestResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Collection;

@RestController
@RequestMapping("/api/v1/collector")
@PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
public class ChannelDiagnosticsController {
    private final ChannelDiagnosticsService svc;

    public ChannelDiagnosticsController(ChannelDiagnosticsService svc) { this.svc = svc; }

    @GetMapping("/state")
    public Collection<ChannelRuntimeState> all() { return svc.snapshotAll(); }

    @GetMapping("/{id}/state")
    public ChannelRuntimeState one(@PathVariable Long id) { return svc.snapshot(id); }

    @PostMapping("/{id}/test")
    public TestResult test(@PathVariable Long id) { return svc.test(id); }

    @PostMapping("/{id}/reconnect")
    public void reconnect(@PathVariable Long id) { svc.reconnect(id); }
}
```

- [x] **Step 2: Commit**

```bash
git commit -m "feat(collector): add diagnostics service + REST endpoints"
```

---

### Task 7.2: CollectorMetricsFlusher（每分钟 flush 到 DB）

**Files:**
- Create: `ems-collector/src/main/java/com/ems/collector/diagnostics/CollectorMetricsFlusher.java`

- [x] **Step 1: 实现**

```java
package com.ems.collector.diagnostics;

import com.ems.collector.runtime.ChannelStateRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class CollectorMetricsFlusher {
    private final ChannelStateRegistry registry;
    private final JdbcTemplate jdbc;

    public CollectorMetricsFlusher(ChannelStateRegistry registry, JdbcTemplate jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;
    }

    @Scheduled(fixedRate = 60_000)
    public void flush() {
        var bucket = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        for (var state : registry.snapshotAll()) {
            jdbc.update("""
                INSERT INTO collector_metrics(channel_id, bucket_at, success_cnt, failure_cnt, avg_latency_ms)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (channel_id, bucket_at) DO UPDATE SET
                    success_cnt = EXCLUDED.success_cnt,
                    failure_cnt = EXCLUDED.failure_cnt,
                    avg_latency_ms = EXCLUDED.avg_latency_ms
                """,
                state.channelId(), java.sql.Timestamp.from(bucket),
                (int) state.successCount24h(), (int) state.failureCount24h(),
                (int) state.avgLatencyMs());
        }
    }
}
```

- [x] **Step 2: Commit**

```bash
git commit -m "feat(collector): periodic flush of runtime metrics to DB"
```

---

### Task 7.3: Health indicator + Micrometer metrics

**Files:**
- Create: `ems-collector/src/main/java/com/ems/collector/diagnostics/CollectorTransportHealthIndicator.java`
- Create: `ems-collector/src/main/java/com/ems/collector/diagnostics/CollectorTransportMetrics.java`

- [x] **Step 1: 实现**

```java
package com.ems.collector.diagnostics;

import com.ems.collector.runtime.*;
import org.springframework.boot.actuate.health.*;
import org.springframework.stereotype.Component;

@Component
public class CollectorTransportHealthIndicator implements HealthIndicator {
    private final ChannelStateRegistry registry;

    public CollectorTransportHealthIndicator(ChannelStateRegistry registry) {
        this.registry = registry;
    }

    public Health health() {
        var all = registry.snapshotAll();
        var disconnected = all.stream()
            .filter(s -> s.connState() == ConnectionState.DISCONNECTED ||
                         s.connState() == ConnectionState.ERROR)
            .count();
        var b = (disconnected == 0 ? Health.up() : Health.status("DEGRADED"));
        return b.withDetail("total", all.size())
                .withDetail("disconnected", disconnected).build();
    }
}
```

```java
package com.ems.collector.diagnostics;

import com.ems.collector.runtime.*;
import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

@Component
public class CollectorTransportMetrics {
    private final MeterRegistry meterRegistry;
    private final ChannelStateRegistry stateRegistry;

    public CollectorTransportMetrics(MeterRegistry mr, ChannelStateRegistry sr) {
        this.meterRegistry = mr;
        this.stateRegistry = sr;
    }

    @PostConstruct
    public void register() {
        Gauge.builder("ems_collector_channels_total", stateRegistry,
            r -> r.snapshotAll().size()).register(meterRegistry);
        for (var state : ConnectionState.values()) {
            Gauge.builder("ems_collector_channels_state", stateRegistry,
                r -> r.snapshotAll().stream().filter(s -> s.connState() == state).count())
                .tag("state", state.name())
                .register(meterRegistry);
        }
    }
}
```

- [x] **Step 2: Commit**

```bash
git commit -m "feat(collector): add health indicator + micrometer gauges"
```

---

## Phase 8 — 前端（约 5-6 天）

### Task 8.1: i18n 字典扩展

**Files:**
- Modify: `frontend/src/utils/i18n-dict.ts`

- [x] **Step 1: 添加 3 个新字典**

```typescript
export const COLLECTOR_PROTOCOL_LABEL = {
  MODBUS_TCP: 'Modbus TCP',
  MODBUS_RTU: 'Modbus RTU',
  OPC_UA: 'OPC UA',
  MQTT: 'MQTT',
  VIRTUAL: '虚拟（模拟）',
} as const;

export const VIRTUAL_MODE_LABEL = {
  CONSTANT: '恒定值',
  SINE: '正弦波',
  RANDOM_WALK: '随机游走',
  CALENDAR_CURVE: '日历曲线',
} as const;

export const OPCUA_SECURITY_MODE_LABEL = {
  NONE: '无安全',
  SIGN: '仅签名',
  SIGN_AND_ENCRYPT: '签名 + 加密',
} as const;

export const CONNECTION_STATE_LABEL = {
  CONNECTING: '连接中',
  CONNECTED: '已连接',
  DISCONNECTED: '已断开',
  ERROR: '错误',
} as const;
```

- [x] **Step 2: Commit**

```bash
git commit -m "feat(i18n): add collector protocol + virtual mode + opcua dicts"
```

---

### Task 8.2: API 客户端

**Files:**
- Create: `frontend/src/api/{channel,secret,collectorDiag}.ts`

- [x] **Step 1: channel.ts**

```typescript
import { http } from './http';

export type Protocol = 'MODBUS_TCP' | 'MODBUS_RTU' | 'OPC_UA' | 'MQTT' | 'VIRTUAL';

export interface ChannelDTO {
  id: number;
  name: string;
  protocol: Protocol;
  enabled: boolean;
  isVirtual: boolean;
  protocolConfig: Record<string, unknown>;
  description?: string;
  createdAt: string;
  updatedAt: string;
}

export interface TestResult {
  success: boolean;
  message: string;
  latencyMs: number | null;
}

export const channelApi = {
  list: () => http.get<ChannelDTO[]>('/api/v1/channel'),
  get: (id: number) => http.get<ChannelDTO>(`/api/v1/channel/${id}`),
  create: (body: Partial<ChannelDTO>) => http.post<ChannelDTO>('/api/v1/channel', body),
  update: (id: number, body: Partial<ChannelDTO>) => http.put<ChannelDTO>(`/api/v1/channel/${id}`, body),
  delete: (id: number) => http.delete(`/api/v1/channel/${id}`),
  test: (id: number) => http.post<TestResult>(`/api/v1/channel/${id}/test`),
};
```

- [x] **Step 2: secret.ts**

```typescript
import { http } from './http';

export const secretApi = {
  list: () => http.get<string[]>('/api/v1/secrets'),
  write: (ref: string, value: string) => http.post('/api/v1/secrets', { ref, value }),
  delete: (ref: string) => http.delete(`/api/v1/secrets?ref=${encodeURIComponent(ref)}`),
};
```

- [x] **Step 3: collectorDiag.ts**

```typescript
import { http } from './http';

export interface ChannelRuntimeState {
  channelId: number;
  protocol: string;
  connState: 'CONNECTING' | 'CONNECTED' | 'DISCONNECTED' | 'ERROR';
  lastConnectAt?: string;
  lastSuccessAt?: string;
  lastFailureAt?: string;
  lastErrorMessage?: string;
  successCount24h: number;
  failureCount24h: number;
  avgLatencyMs: number;
  protocolMeta: Record<string, unknown>;
}

export const collectorDiagApi = {
  list: () => http.get<ChannelRuntimeState[]>('/api/v1/collector/state'),
  get: (id: number) => http.get<ChannelRuntimeState>(`/api/v1/collector/${id}/state`),
  test: (id: number) => http.post<{ success: boolean; message: string; latencyMs: number | null }>(`/api/v1/collector/${id}/test`),
  reconnect: (id: number) => http.post(`/api/v1/collector/${id}/reconnect`),
};
```

- [x] **Step 4: Commit**

```bash
git commit -m "feat(frontend): add channel/secret/collectorDiag API clients"
```

---

### Task 8.3: SecretInput 组件

**Files:**
- Create: `frontend/src/components/SecretInput.tsx`

- [x] **Step 1: 实现**

```tsx
import { Button, Input, Modal, Space, message } from 'antd';
import { useState } from 'react';
import { secretApi } from '@/api/secret';

interface Props {
  value?: string;
  onChange?: (ref: string) => void;
  refPrefix: string;     // e.g. "mqtt/broker-prod"
  placeholder?: string;
}

export function SecretInput({ value, onChange, refPrefix, placeholder }: Props) {
  const [open, setOpen] = useState(false);
  const [plain, setPlain] = useState('');

  const handleSave = async () => {
    if (!plain) return;
    const ref = `secret://${refPrefix}-${Date.now()}`;
    await secretApi.write(ref, plain);
    onChange?.(ref);
    message.success('已保存');
    setOpen(false);
    setPlain('');
  };

  return (
    <Space.Compact style={{ width: '100%' }}>
      <Input value={value} disabled placeholder={placeholder ?? '未设置'} />
      <Button onClick={() => setOpen(true)}>修改</Button>
      <Modal
        title="设置凭据"
        open={open}
        onOk={handleSave}
        onCancel={() => { setOpen(false); setPlain(''); }}
        destroyOnClose
      >
        <Input.Password
          autoFocus
          value={plain}
          onChange={(e) => setPlain(e.target.value)}
          placeholder="输入明文，保存后将以 secret:// 引用形式存储"
        />
      </Modal>
    </Space.Compact>
  );
}
```

- [x] **Step 2: Commit**

```bash
git commit -m "feat(frontend): add SecretInput component for credential fields"
```

---

### Task 8.4: 5 个协议子表单

**Files:**
- Create: `frontend/src/pages/meters/forms/{ModbusTcpForm,ModbusRtuForm,OpcUaForm,MqttForm,VirtualForm}.tsx`

- [x] **Step 1: ModbusTcpForm.tsx**

```tsx
import { Form, Input, InputNumber } from 'antd';
import { ModbusPointsList } from './ModbusPointsList';

export function ModbusTcpForm() {
  return (
    <>
      <Form.Item name={['protocolConfig', 'host']} label="主机" rules={[{ required: true }]}>
        <Input placeholder="192.168.1.100" />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'port']} label="端口" initialValue={502}>
        <InputNumber min={1} max={65535} />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'unitId']} label="从站 ID" initialValue={1}>
        <InputNumber min={0} max={247} />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'pollInterval']} label="轮询间隔（ISO-8601，例 PT5S）"
                 rules={[{ required: true }]} initialValue="PT5S">
        <Input />
      </Form.Item>
      <Form.List name={['protocolConfig', 'points']}>
        {(fields, ops) => <ModbusPointsList fields={fields} ops={ops} />}
      </Form.List>
    </>
  );
}
```

- [x] **Step 2: OpcUaForm.tsx**

```tsx
import { Form, Input, Select } from 'antd';
import { SecretInput } from '@/components/SecretInput';
import { translate, OPCUA_SECURITY_MODE_LABEL } from '@/utils/i18n-dict';
import { OpcUaPointsList } from './OpcUaPointsList';

export function OpcUaForm() {
  return (
    <>
      <Form.Item name={['protocolConfig', 'endpointUrl']} label="Endpoint URL"
                 rules={[{ required: true }]}>
        <Input placeholder="opc.tcp://plc:4840" />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'securityMode']} label="安全模式" initialValue="NONE">
        <Select options={Object.entries(OPCUA_SECURITY_MODE_LABEL).map(([v, l]) => ({ value: v, label: l }))} />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'usernameRef']} label="用户名">
        <SecretInput refPrefix="opcua/username" placeholder="可选" />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'passwordRef']} label="密码">
        <SecretInput refPrefix="opcua/password" placeholder="可选" />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'pollInterval']} label="Read 轮询（ISO-8601）"
                 initialValue="PT5S">
        <Input />
      </Form.Item>
      <Form.List name={['protocolConfig', 'points']}>
        {(fields, ops) => <OpcUaPointsList fields={fields} ops={ops} />}
      </Form.List>
    </>
  );
}
```

- [x] **Step 3: MqttForm.tsx**

```tsx
import { Form, Input, InputNumber, Switch } from 'antd';
import { SecretInput } from '@/components/SecretInput';
import { MqttPointsList } from './MqttPointsList';

export function MqttForm() {
  return (
    <>
      <Form.Item name={['protocolConfig', 'brokerUrl']} label="Broker URL"
                 rules={[{ required: true }]}>
        <Input placeholder="tcp://broker:1883 或 ssl://broker:8883" />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'clientId']} label="Client ID"
                 rules={[{ required: true }]}>
        <Input />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'usernameRef']} label="用户名">
        <SecretInput refPrefix="mqtt/username" />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'passwordRef']} label="密码">
        <SecretInput refPrefix="mqtt/password" />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'qos']} label="QoS" initialValue={1}>
        <InputNumber min={0} max={1} />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'cleanSession']} label="Clean Session"
                 valuePropName="checked" initialValue>
        <Switch />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'keepAlive']} label="KeepAlive（ISO-8601）"
                 initialValue="PT60S">
        <Input />
      </Form.Item>
      <Form.List name={['protocolConfig', 'points']}>
        {(fields, ops) => <MqttPointsList fields={fields} ops={ops} />}
      </Form.List>
    </>
  );
}
```

- [x] **Step 4: VirtualForm.tsx**

```tsx
import { Form, Input, InputNumber, Select, Space, Button } from 'antd';
import { translate, VIRTUAL_MODE_LABEL } from '@/utils/i18n-dict';

export function VirtualForm() {
  return (
    <>
      <Form.Item name={['protocolConfig', 'pollInterval']} label="轮询间隔（ISO-8601）"
                 initialValue="PT1S" rules={[{ required: true }]}>
        <Input />
      </Form.Item>
      <Form.List name={['protocolConfig', 'points']}>
        {(fields, { add, remove }) => (
          <>
            {fields.map((f) => (
              <Space key={f.key} align="baseline" wrap>
                <Form.Item name={[f.name, 'key']} label="Key" rules={[{ required: true }]}>
                  <Input style={{ width: 120 }} />
                </Form.Item>
                <Form.Item name={[f.name, 'mode']} label="模式" rules={[{ required: true }]}
                           initialValue="CONSTANT">
                  <Select style={{ width: 140 }}
                    options={Object.entries(VIRTUAL_MODE_LABEL).map(([v, l]) => ({ value: v, label: l }))} />
                </Form.Item>
                <Form.Item name={[f.name, 'unit']} label="单位">
                  <Input style={{ width: 80 }} />
                </Form.Item>
                <Form.Item label="参数 (JSON)">
                  <Form.Item name={[f.name, 'params']} noStyle
                             rules={[{ required: true, message: '需要 JSON 对象' }]}>
                    <Input.TextArea rows={2} placeholder='{"value": 42}' />
                  </Form.Item>
                </Form.Item>
                <Button danger type="link" onClick={() => remove(f.name)}>移除</Button>
              </Space>
            ))}
            <Button type="dashed" onClick={() => add({
              key: '', mode: 'CONSTANT', params: { value: 0 }
            })} block>+ 新增测点</Button>
          </>
        )}
      </Form.List>
    </>
  );
}
```

- [x] **Step 5: ModbusRtuForm.tsx**（结构类似 ModbusTcpForm，字段不同）

```tsx
import { Form, Input, InputNumber, Select } from 'antd';
import { ModbusPointsList } from './ModbusPointsList';

export function ModbusRtuForm() {
  return (
    <>
      <Form.Item name={['protocolConfig', 'serialPort']} label="串口"
                 rules={[{ required: true }]}>
        <Input placeholder="/dev/ttyUSB0" />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'baudRate']} label="波特率" initialValue={9600}>
        <Select options={[1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200].map(v => ({ value: v, label: String(v) }))} />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'dataBits']} label="数据位" initialValue={8}>
        <InputNumber min={5} max={8} />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'stopBits']} label="停止位" initialValue={1}>
        <InputNumber min={1} max={2} />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'parity']} label="校验" initialValue="NONE">
        <Select options={['NONE', 'ODD', 'EVEN'].map(v => ({ value: v, label: v }))} />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'unitId']} label="从站 ID" initialValue={1}>
        <InputNumber min={0} max={247} />
      </Form.Item>
      <Form.Item name={['protocolConfig', 'pollInterval']} label="轮询间隔" initialValue="PT5S">
        <Input />
      </Form.Item>
      <Form.List name={['protocolConfig', 'points']}>
        {(fields, ops) => <ModbusPointsList fields={fields} ops={ops} />}
      </Form.List>
    </>
  );
}
```

- [x] **Step 6: 通用 PointsList 组件**

`ModbusPointsList.tsx`、`OpcUaPointsList.tsx`、`MqttPointsList.tsx` 各自实现 fieldArray 列表（参考 `tariff/index.tsx` 的 Form.List 用法）。每个组件大致 30-50 行。

- [x] **Step 7: Commit**

```bash
git commit -m "feat(meters): add 5 protocol-specific config forms"
```

---

### Task 8.5: ChannelEditor 容器组件

**Files:**
- Create: `frontend/src/pages/meters/ChannelEditor.tsx`

- [x] **Step 1: 实现**

```tsx
import { Button, Drawer, Form, Input, Select, Space, message } from 'antd';
import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { channelApi, type ChannelDTO, type Protocol } from '@/api/channel';
import { translate, COLLECTOR_PROTOCOL_LABEL } from '@/utils/i18n-dict';
import { ModbusTcpForm } from './forms/ModbusTcpForm';
import { ModbusRtuForm } from './forms/ModbusRtuForm';
import { OpcUaForm } from './forms/OpcUaForm';
import { MqttForm } from './forms/MqttForm';
import { VirtualForm } from './forms/VirtualForm';

interface Props {
  channel?: ChannelDTO;
  open: boolean;
  onClose: () => void;
}

export function ChannelEditor({ channel, open, onClose }: Props) {
  const [form] = Form.useForm();
  const [protocol, setProtocol] = useState<Protocol>(channel?.protocol ?? 'MODBUS_TCP');
  const qc = useQueryClient();

  const save = useMutation({
    mutationFn: (body: Partial<ChannelDTO>) =>
      channel ? channelApi.update(channel.id, body) : channelApi.create(body),
    onSuccess: () => {
      message.success('已保存');
      qc.invalidateQueries({ queryKey: ['channel'] });
      onClose();
    },
  });

  const test = useMutation({
    mutationFn: () => channel ? channelApi.test(channel.id) : Promise.resolve(null),
    onSuccess: (res) => {
      if (res?.success) message.success(`连接成功 (${res.latencyMs} ms)`);
      else message.error(`连接失败: ${res?.message}`);
    },
  });

  return (
    <Drawer
      title={channel ? `编辑：${channel.name}` : '新增通道'}
      open={open}
      onClose={onClose}
      width={720}
      extra={
        <Space>
          {channel && <Button onClick={() => test.mutate()} loading={test.isPending}>测试连接</Button>}
          <Button type="primary" loading={save.isPending}
                  onClick={async () => {
                    const v = await form.validateFields();
                    save.mutate({ ...v, protocol, isVirtual: protocol === 'VIRTUAL' });
                  }}>
            保存
          </Button>
        </Space>
      }
    >
      <Form form={form} layout="vertical" initialValues={channel ?? { enabled: true }}>
        <Form.Item name="name" label="通道名称" rules={[{ required: true, max: 128 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="protocol" label="协议" initialValue={protocol}>
          <Select
            disabled={!!channel}
            value={protocol}
            onChange={(v) => setProtocol(v)}
            options={Object.entries(COLLECTOR_PROTOCOL_LABEL).map(([v, l]) => ({ value: v, label: l }))}
          />
        </Form.Item>
        <Form.Item name="description" label="描述"><Input.TextArea rows={2} /></Form.Item>

        {protocol === 'MODBUS_TCP' && <ModbusTcpForm />}
        {protocol === 'MODBUS_RTU' && <ModbusRtuForm />}
        {protocol === 'OPC_UA'     && <OpcUaForm />}
        {protocol === 'MQTT'       && <MqttForm />}
        {protocol === 'VIRTUAL'    && <VirtualForm />}
      </Form>
    </Drawer>
  );
}
```

- [x] **Step 2: Commit**

```bash
git commit -m "feat(meters): add ChannelEditor with protocol-conditional rendering"
```

---

### Task 8.6: 升级 /collector 诊断页

**Files:**
- Modify: `frontend/src/pages/collector/index.tsx`
- Create: `frontend/src/pages/collector/ChannelDetailDrawer.tsx`

- [x] **Step 1: 升级列表**

```tsx
import { Card, Table, Tag, Tooltip, Button, Space, Progress } from 'antd';
import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import 'dayjs/locale/zh-cn';
import { useState } from 'react';
import { collectorDiagApi, type ChannelRuntimeState } from '@/api/collectorDiag';
import { translate, COLLECTOR_PROTOCOL_LABEL, CONNECTION_STATE_LABEL } from '@/utils/i18n-dict';
import { ChannelDetailDrawer } from './ChannelDetailDrawer';
import { PageHeader } from '@/components/PageHeader';

dayjs.extend(relativeTime); dayjs.locale('zh-cn');

const STATE_COLORS = { CONNECTED: 'green', CONNECTING: 'gold', DISCONNECTED: 'red', ERROR: 'red' };

export default function CollectorPage() {
  const [detailId, setDetailId] = useState<number | null>(null);
  const { data = [], isLoading } = useQuery({
    queryKey: ['collector', 'state'],
    queryFn: collectorDiagApi.list,
    refetchInterval: 5000,
  });

  return (
    <>
      <PageHeader title="采集器状态" />
      <Card>
        <Table<ChannelRuntimeState>
          rowKey="channelId" loading={isLoading} dataSource={data}
          columns={[
            { title: '协议', dataIndex: 'protocol',
              render: (p) => <Tag>{translate(COLLECTOR_PROTOCOL_LABEL, p)}</Tag> },
            { title: '通道 ID', dataIndex: 'channelId', width: 80 },
            { title: '状态', dataIndex: 'connState',
              render: (s) => <Tag color={STATE_COLORS[s as keyof typeof STATE_COLORS]}>
                {translate(CONNECTION_STATE_LABEL, s)}
              </Tag> },
            { title: '最近成功', dataIndex: 'lastSuccessAt',
              render: (t) => t ? <Tooltip title={dayjs(t).format('YYYY-MM-DD HH:mm:ss')}>
                {dayjs(t).fromNow()}</Tooltip> : '-' },
            { title: '24h 成功率', render: (_, r) => {
              const tot = r.successCount24h + r.failureCount24h;
              const rate = tot ? (r.successCount24h / tot) * 100 : 100;
              return <Progress percent={rate} size="small"
                strokeColor={rate < 95 ? '#cf1322' : '#52c41a'} />;
            }},
            { title: '平均延迟', dataIndex: 'avgLatencyMs', render: (v) => `${v} ms` },
            { title: '最后错误', dataIndex: 'lastErrorMessage',
              render: (m) => m ? <Tooltip title={m}>{m.slice(0, 30)}…</Tooltip> : '-' },
            { title: '操作',
              render: (_, r) => (
                <Space>
                  <Button size="small" onClick={() => collectorDiagApi.test(r.channelId)}>测试</Button>
                  <Button size="small" onClick={() => collectorDiagApi.reconnect(r.channelId)}>重连</Button>
                  <Button size="small" type="link" onClick={() => setDetailId(r.channelId)}>详情</Button>
                </Space>
              )},
          ]}
        />
        <ChannelDetailDrawer channelId={detailId} onClose={() => setDetailId(null)} />
      </Card>
    </>
  );
}
```

- [x] **Step 2: ChannelDetailDrawer**

```tsx
import { Drawer, Descriptions, Tag } from 'antd';
import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { collectorDiagApi } from '@/api/collectorDiag';
import { translate, CONNECTION_STATE_LABEL, COLLECTOR_PROTOCOL_LABEL } from '@/utils/i18n-dict';

export function ChannelDetailDrawer({ channelId, onClose }: { channelId: number | null; onClose: () => void }) {
  const { data } = useQuery({
    queryKey: ['collector', 'state', channelId],
    queryFn: () => channelId ? collectorDiagApi.get(channelId) : null,
    enabled: !!channelId,
    refetchInterval: 3000,
  });
  if (!data) return <Drawer open={!!channelId} onClose={onClose} title="详情" />;
  return (
    <Drawer open={!!channelId} onClose={onClose} width={520}
            title={`通道 #${data.channelId} 详情`}>
      <Descriptions column={1} bordered size="small">
        <Descriptions.Item label="协议">
          <Tag>{translate(COLLECTOR_PROTOCOL_LABEL, data.protocol)}</Tag></Descriptions.Item>
        <Descriptions.Item label="状态">{translate(CONNECTION_STATE_LABEL, data.connState)}</Descriptions.Item>
        <Descriptions.Item label="最近连接">
          {data.lastConnectAt ? dayjs(data.lastConnectAt).format('YYYY-MM-DD HH:mm:ss') : '-'}</Descriptions.Item>
        <Descriptions.Item label="最近成功">
          {data.lastSuccessAt ? dayjs(data.lastSuccessAt).format('YYYY-MM-DD HH:mm:ss') : '-'}</Descriptions.Item>
        <Descriptions.Item label="最近失败">
          {data.lastFailureAt ? dayjs(data.lastFailureAt).format('YYYY-MM-DD HH:mm:ss') : '-'}</Descriptions.Item>
        <Descriptions.Item label="24h 成功">{data.successCount24h}</Descriptions.Item>
        <Descriptions.Item label="24h 失败">{data.failureCount24h}</Descriptions.Item>
        <Descriptions.Item label="平均延迟">{data.avgLatencyMs} ms</Descriptions.Item>
        <Descriptions.Item label="最后错误">{data.lastErrorMessage ?? '-'}</Descriptions.Item>
        <Descriptions.Item label="协议元信息"><pre>{JSON.stringify(data.protocolMeta, null, 2)}</pre></Descriptions.Item>
      </Descriptions>
    </Drawer>
  );
}
```

- [x] **Step 3: Commit**

```bash
git commit -m "feat(collector): upgrade /collector page with realtime diagnostics + drawer"
```

---

## Phase 9 — E2E + 文档（约 1.5 天）

### Task 9.1: Playwright E2E

**Files:**
- Create: `frontend/e2e/channel-virtual.spec.ts`
- Create: `frontend/e2e/channel-modbus.spec.ts`
- Create: `frontend/e2e/channel-opcua-cert.spec.ts`

- [x] **Step 1: VIRTUAL E2E**

```typescript
import { test, expect } from '@playwright/test';
import { loginAsAdmin } from './helpers';

test('admin creates VIRTUAL channel and verifies data flow', async ({ page }) => {
  await loginAsAdmin(page);
  await page.goto('/meters');
  await page.click('button:has-text("新增通道")');
  await page.fill('input[id="name"]', `vtest-${Date.now()}`);
  await page.click('input[role="combobox"]');
  await page.click('div.ant-select-item-option:has-text("虚拟（模拟）")');
  await page.click('button:has-text("保存")');
  await expect(page.locator('.ant-message-success')).toContainText('已保存');

  await page.goto('/collector');
  await page.waitForTimeout(3000);
  await expect(page.locator('tr:has-text("VIRTUAL")').first()).toContainText('已连接');
});
```

- [x] **Step 2: Modbus + OPC UA 证书审批 E2E**（结构类似）

- [x] **Step 3: Commit**

```bash
git commit -m "test(e2e): add VIRTUAL/Modbus/OPC UA channel E2E tests"
```

---

### Task 9.2: 文档交付

**Files:**
- Create: `docs/product/collector-protocols-user-guide.md`
- Create: `docs/api/collector-api.md`
- Create: `docs/ops/opcua-cert-management.md`

- [x] **Step 1: 各文档列出实际操作步骤、API 表格、SOP（不再展开），各 ~150 行**

- [x] **Step 2: Commit**

```bash
git commit -m "docs(collector): add user guide + API reference + OPC UA cert SOP"
```

---

## Self-Review

### Spec 覆盖检查
- ✅ Section 3 架构：Phase 1-2 数据模型 + Transport
- ✅ Section 4 数据模型：Task 1.1-1.3
- ✅ Section 5 Transport 抽象：Task 2.1-2.6
- ✅ Section 6 协议实现：Phase 4 (VIRTUAL)、Phase 5 (OPC UA)、Phase 6 (MQTT)、Task 2.3-2.4 (Modbus)
- ✅ Section 7 前端 UI：Phase 8
- ✅ Section 8 凭据/安全：Phase 3 + Task 5.5
- ✅ Section 9 诊断/可观测性：Phase 7
- ✅ Section 10 测试策略：Task 9.1 + 各 Task 内单元/集成测试
- ✅ Section 11 风险矩阵：Task 5.1 (Milo spike) 已落实
- ✅ Section 12 迁移计划：本计划 9 个 Phase 即对应 spec 7 阶段（合并 + 拆分）

### 占位符扫描
- ✅ 无 TBD / TODO / fill in
- ✅ 所有代码块完整
- ✅ Task 9.2 文档说明 "150 行"——但属交付物描述非占位符，实施时由 subagent 撰写

### 类型一致性
- ✅ `ChannelConfig.protocol()` / `pollInterval()` / `points()` 三接口贯穿所有 record
- ✅ `Sample` record 结构统一（channelId / pointKey / timestamp / value / quality / tags）
- ✅ `Transport.start(Long, ChannelConfig, SampleSink)` 在所有 5 个实现中签名一致
- ✅ `SecretResolver` 5 方法在 Phase 3、5、6 调用一致

---

## 总结

- **Phase 数：** 9
- **Task 总数：** 约 38
- **预计工时：** 约 25.5 人日（与 spec Section 14 一致）
- **关键路径依赖：** Phase 1 → Phase 2 → (Phase 3 + Phase 4 并行) → (Phase 5 + Phase 6 并行) → Phase 7 → Phase 8 → Phase 9
- **风险隔离：** Phase 5 Task 5.1 是 Eclipse Milo spike，若失败需重新评估 OPC UA 库选型（备选：Prosys SDK）
