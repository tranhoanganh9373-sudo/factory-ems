# Factory EMS · 采集中断告警实施计划（ems-alarm）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地新模块 `ems-alarm`：检测采集设备数据中断并通过站内通知 + Webhook 派发，含完整生命周期管理（触发 → 确认 → 自动恢复 → 历史留痕）+ 全套前端 UI。商业化前最低门槛功能。

**Architecture:** 在 v2.1.x 模块化单体上新增 `ems-alarm` 模块（与 `ems-floorplan`/`ems-billing` 同层级）。检测引擎 `@Scheduled` 直读 `CollectorService.snapshots()`（已暴露 `lastReadAt` + `consecutiveErrors`），不查 InfluxDB、不动 collector schema。派发器分两通道：站内（写 `alarm_inbox` 表）+ Webhook（`@Async` HTTP POST，HMAC 签名 + 内存重试队列）。前端新增"系统健康"一级菜单，全局铃铛 30s 轮询。

**Tech Stack:**
- 后端：Java 21 + Spring Boot 3.3.4（沿用 parent pom）+ Spring `@Scheduled` + Spring `@Async` + JPA + Flyway
- HTTP 客户端：Java 11 `HttpClient`（内置，无新依赖）
- Webhook IT：MockWebServer（OkHttp）—— 沿用既有 testcontainers 风格
- 前端：React + antd + react-query + react-router（既有栈）

**Spec reference:** `docs/superpowers/specs/2026-04-29-acquisition-alarm-design.md`

---

## 依赖前提

- v2.1.x 已上线（auth + meter + tariff + cost + billing + floorplan）
- `ems-collector` v1.5.x 已上线，能在 `application.yml` 配置 `ems.collector.enabled=true` 启动 pollers
- 至少 1 个 `meters` 行 + 对应 collector YAML 配置（demo / 集成测试用）
- Postgres（Flyway 走 ems-app 集中 migration）

---

## 关键架构决策（写给后续 plan 与读者）

1. **检测数据源 = collector snapshot，不查 InfluxDB**：`CollectorService.snapshots()` 已经返回每个 device 的 `lastReadAt` + `consecutiveErrors`，alarm 检测只从这里读，绕开 InfluxDB 查询负担。代价：首版只覆盖 collector 配置的 meter（mock 数据 / 直接 API 写入的设备不监控）；这与"采集中断告警"的语义一致。
2. **device 标识用 `meter.id`**：alarm 表 `device_id BIGINT` 实际就是 `meter.id`。snapshot 提供的 `meterCode` 通过 `MeterRepository` 反查 id。`device_type` 字段保留枚举（METER/COLLECTOR），首版只写 `METER`。
3. **不引入新依赖**：HTTP webhook 用 JDK 内置 `java.net.http.HttpClient`；签名用 `javax.crypto.Mac`；重试用 `ScheduledExecutorService` 延迟提交。OkHttp（仅测试 MockWebServer）/ Spring Retry / 持久化队列都不引入到生产代码。
4. **Flyway migration 写 `ems-app/src/main/resources/db/migration/V2.2.0__init_alarm.sql`**：与现有约定一致（Flyway 集中、版本递增）。**不要**在 ems-alarm 模块下放 migration。
5. **alarm_inbox 自建，不复用 audit_logs**：audit_logs 是审计流，受众不同；alarm_inbox 按 user × alarm × kind 一行，前端按 `user_id + read_at IS NULL` 取未读。
6. **Webhook 重试用内存延迟队列**：进程崩溃重试丢失，由 `webhook_delivery_log` 留痕 + UI 手动重放兜底。spec §9 已记录此风险。
7. **状态机仅由检测逻辑驱动**：webhook 失败 / 站内写入失败都不影响 `alarms.status`。下发结果只在 `webhook_delivery_log` 留痕。
8. **抑制窗口同时管两件事**：(a) RESOLVED 后 5min 不再触发（防抖），(b) ACTIVE 触发后 5min 内不允许 AUTO 恢复（防抖）。两者共用同一参数 `suppression-window-seconds`，spec §3.3 已对齐。
9. **轮询而非事件驱动**：alarm 不订阅 collector 状态机事件，避免双向耦合。每 60s 主动扫一次 snapshots()，单实例运行（首版不引 ShedLock）。
10. **包名 + 文件结构沿用既有约定**：`com.ems.alarm.{controller, dto, entity, repository, service, service.impl, exception, config}`，禁止新造 `domain/repo/api` 等命名。
11. **JaCoCo 模块阈值 70%**：spec §7 已确认。在 `ems-alarm/pom.xml` 中显式配置（参考 `ems-floorplan` 风格）。
12. **前端"系统健康"作一级菜单**，路由前缀 `/alarms/*`，沿用 react-query useQuery/useMutation 风格 + antd 组件。

---

## 文件清单（locks decomposition decisions）

### 后端 — 新建 `ems-alarm` 模块

```
ems-alarm/
├── pom.xml                                            (Task A1)
├── src/main/java/com/ems/alarm/
│   ├── config/
│   │   ├── AlarmProperties.java                       (Task A3)
│   │   └── AlarmModuleConfig.java                     (Task A3 / E4 加 bean)
│   ├── entity/
│   │   ├── Alarm.java                                 (Task B1)
│   │   ├── AlarmStatus.java       (enum)              (Task B1)
│   │   ├── AlarmType.java         (enum)              (Task B1)
│   │   ├── ResolvedReason.java    (enum)              (Task B1)
│   │   ├── AlarmRuleOverride.java                     (Task B1)
│   │   ├── WebhookConfig.java                         (Task B1)
│   │   ├── WebhookDeliveryLog.java                    (Task B1)
│   │   ├── DeliveryStatus.java    (enum)              (Task B1)
│   │   ├── AlarmInbox.java                            (Task B1)
│   │   └── InboxKind.java         (enum)              (Task B1)
│   ├── repository/
│   │   ├── AlarmRepository.java                       (Task B2)
│   │   ├── AlarmRuleOverrideRepository.java           (Task B2)
│   │   ├── WebhookConfigRepository.java               (Task B2)
│   │   ├── WebhookDeliveryLogRepository.java          (Task B2)
│   │   └── AlarmInboxRepository.java                  (Task B2)
│   ├── exception/
│   │   ├── AlarmStateException.java                   (Task C1)
│   │   ├── AlarmNotFoundException.java                (Task C1)
│   │   └── WebhookConfigInvalidException.java         (Task C1)
│   ├── service/
│   │   ├── ThresholdResolver.java                     (Task C2)
│   │   ├── AlarmStateMachine.java                     (Task C3)
│   │   ├── AlarmDetector.java       (interface)       (Task D1)
│   │   ├── AlarmDispatcher.java     (interface)       (Task E1)
│   │   ├── InAppChannel.java        (interface)       (Task E2)
│   │   ├── WebhookChannel.java      (interface)       (Task E4)
│   │   ├── WebhookSigner.java                         (Task E3)
│   │   ├── AlarmService.java        (interface)       (Task F1)
│   │   ├── adapter/
│   │   │   ├── WebhookAdapter.java  (interface)       (Task E3)
│   │   │   └── GenericJsonAdapter.java                (Task E3)
│   │   └── impl/
│   │       ├── AlarmDetectorImpl.java                 (Task D1)
│   │       ├── AlarmDispatcherImpl.java               (Task E1)
│   │       ├── InAppChannelImpl.java                  (Task E2)
│   │       ├── WebhookChannelImpl.java                (Task E4)
│   │       └── AlarmServiceImpl.java                  (Task F1)
│   ├── dto/
│   │   ├── AlarmDTO.java                              (Task F1)
│   │   ├── AlarmListItemDTO.java                      (Task F1)
│   │   ├── HealthSummaryDTO.java                      (Task F1)
│   │   ├── DefaultsDTO.java                           (Task F2)
│   │   ├── OverrideRequestDTO.java                    (Task F2)
│   │   ├── WebhookConfigDTO.java                      (Task F3)
│   │   ├── WebhookConfigRequestDTO.java               (Task F3)
│   │   ├── WebhookTestResultDTO.java                  (Task F3)
│   │   └── DeliveryLogDTO.java                        (Task F3)
│   └── controller/
│       ├── AlarmController.java                       (Task F1)
│       ├── AlarmRuleController.java                   (Task F2)
│       └── WebhookController.java                     (Task F3)
└── src/test/java/com/ems/alarm/
    ├── service/
    │   ├── ThresholdResolverTest.java                 (Task C2)
    │   ├── AlarmStateMachineTest.java                 (Task C3)
    │   ├── AlarmDetectorTest.java                     (Task D1)
    │   ├── InAppChannelTest.java                      (Task E2)
    │   ├── WebhookSignerTest.java                     (Task E3)
    │   └── GenericJsonAdapterTest.java                (Task E3)
    └── it/
        ├── AlarmServiceIT.java                        (Task D3)
        ├── WebhookDispatcherIT.java                   (Task E5)
        └── AlarmApiIT.java                            (Task F4)
```

### ems-app 集成

- `ems-app/pom.xml` —— 加 `ems-alarm` 依赖（Task A2）
- `ems-app/src/main/resources/application.yml` —— 加 `ems.alarm.*`（Task A3）
- `ems-app/src/main/resources/db/migration/V2.2.0__init_alarm.sql` —— 5 张表 + 索引（Task A4）

### 父 pom

- `pom.xml` —— `<modules>` 段加 `<module>ems-alarm</module>`（Task A1）

### 前端

```
frontend/src/
├── api/alarm.ts                          (Task G1)
├── components/
│   ├── AlarmBell.tsx                     (Task G2)
│   └── AlarmCenterDrawer.tsx             (Task G2)
├── pages/alarms/
│   ├── health.tsx                        (Task G3)
│   ├── history.tsx                       (Task G4)
│   ├── rules.tsx                         (Task G5)
│   └── webhook.tsx                       (Task G6)
├── pages/meter/
│   ├── list.tsx                          (Task G7 — 改动)
│   └── detail.tsx                        (Task G7 — 改动)
├── pages/dashboard/index.tsx             (Task G7 — 改动)
├── layouts/AppLayout.tsx                 (Task G2 — 改动 / 集成铃铛)
└── routes 注册（具体文件路径在 Task G3 中定位）
```

### 文档

- `docs/ops/alarm-runbook.md` —— 新建（Task H2）
- `docs/ops/README.md` —— 加一行索引（Task H2）
- `docs/ops/verification-2026-04-29-alarm.md` —— 落地后写（Task H1）

---

## Phase 索引

| Phase | 范围 | Tasks | 估时 |
|---|---|---|---|
| A | 模块骨架：pom + 父模块注册 + ems-app 接入 + AlarmProperties + Flyway migration | A1–A4 | 0.5 d |
| **A末** | **文档落实：alarm-config-reference.md（Phase A 文档任务）** | **A5** | **0.25 d** |
| B | 实体 + Repository（5 张表 × Entity + Repo）| B1–B2 | 0.5 d |
| **B末** | **文档落实：alarm-data-model.md** | **B3** | **0.25 d** |
| C | 异常类 + ThresholdResolver + AlarmStateMachine（含单测）| C1–C3 | 0.5 d |
| **C末** | **文档落实：alarm-business-rules.md** | **C4** | **0.25 d** |
| D | AlarmDetector：检测逻辑 + @Scheduled + 集成测试 | D1–D3 | 1 d |
| **D末** | **文档落实：alarm-detection-rules.md** | **D4** | **0.25 d** |
| E | 派发器：Dispatcher + InAppChannel + WebhookChannel + Adapter + 重试 + IT | E1–E5 | 1 d |
| **E末** | **文档落实：alarm-webhook-integration.md** | **E6** | **0.5 d**（含多语言验签代码 + 至少 3 个对接示例） |
| F | REST API：AlarmController / RuleController / WebhookController + ApiIT | F1–F4 | 1 d |
| **F末** | **文档落实：docs/api/alarm-api.md（16 端点完整规约）** | **F5** | **0.5 d** |
| G | 前端：API client + Bell + 4 个页面 + 设备列表/详情/dashboard 改动 | G1–G7 | 1.5 d |
| **G末** | **文档落实：alarm-user-guide.md（含截图占位 + FAQ）** | **G8** | **0.25 d** |
| H | E2E 冒烟 + 文档 + 验收报告 + tag | H1–H2 | 0.5 d |
| **H末** | **文档总收尾：alarm-feature-overview.md + alarm-runbook.md + 索引联动** | **H3** | **0.5 d** |

**合计估算：~34 tasks（含 8 个文档任务），约 8 工作日。**

> **每 Phase 末文档任务的执行原则**：
> 1. **必须在该 Phase 全部技术任务完成后立刻做**，避免实现细节淡化导致文档与代码脱节
> 2. 占位骨架文件已在 `docs/product/*.md` 与 `docs/api/*.md` 中创建，每个文件末尾有"Phase 任务清单"明示要填什么
> 3. 文档完成时必须**删除文件末尾的"Phase 任务清单"和"占位骨架"注脚**
> 4. 每个文档任务独立 commit，commit message 格式：`docs(alarm): 完成 alarm-xxx.md 第 N 章`

---

## 任务详情

### Phase A — 模块骨架

#### Task A1: 创建 ems-alarm 模块骨架

**Files:**
- Create: `ems-alarm/pom.xml`
- Modify: `pom.xml`（父 pom，注册新模块）

- [ ] **Step 1: 写 ems-alarm/pom.xml（参考 ems-floorplan/pom.xml）**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.ems</groupId>
        <artifactId>factory-ems</artifactId>
        <version>1.1.0-SNAPSHOT</version>
    </parent>
    <artifactId>ems-alarm</artifactId>

    <dependencies>
        <dependency><groupId>com.ems</groupId><artifactId>ems-core</artifactId><version>${project.version}</version></dependency>
        <dependency><groupId>com.ems</groupId><artifactId>ems-audit</artifactId><version>${project.version}</version></dependency>
        <dependency><groupId>com.ems</groupId><artifactId>ems-meter</artifactId><version>${project.version}</version></dependency>
        <dependency><groupId>com.ems</groupId><artifactId>ems-collector</artifactId><version>${project.version}</version></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.testcontainers</groupId><artifactId>postgresql</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-testcontainers</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId><scope>test</scope></dependency>
        <dependency><groupId>com.squareup.okhttp3</groupId><artifactId>mockwebserver</artifactId><version>4.12.0</version><scope>test</scope></dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.jacoco</groupId>
                <artifactId>jacoco-maven-plugin</artifactId>
                <executions>
                    <execution>
                        <id>check</id>
                        <configuration>
                            <rules>
                                <rule>
                                    <element>BUNDLE</element>
                                    <limits>
                                        <limit>
                                            <counter>LINE</counter>
                                            <value>COVEREDRATIO</value>
                                            <minimum>0.70</minimum>
                                        </limit>
                                    </limits>
                                </rule>
                            </rules>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: 父 pom 注册 module**

修改 `pom.xml` 的 `<modules>` 段，在 `<module>ems-collector</module>` 之后插入：

```xml
        <module>ems-alarm</module>
```

- [ ] **Step 3: 编译验证**

Run: `./mvnw -pl ems-alarm -am compile -DskipTests`
Expected: BUILD SUCCESS（空模块也能编译）

- [ ] **Step 4: 提交**

```bash
git add pom.xml ems-alarm/pom.xml
git commit -m "feat(alarm): 创建 ems-alarm 模块骨架（pom + 父模块注册）"
```

#### Task A2: ems-app 接入 ems-alarm 依赖

**Files:**
- Modify: `ems-app/pom.xml`

- [ ] **Step 1: 在 dependencies 中加 ems-alarm**（在 `ems-collector` 依赖项之后）

```xml
<dependency><groupId>com.ems</groupId><artifactId>ems-alarm</artifactId><version>${project.version}</version></dependency>
```

- [ ] **Step 2: 编译验证**

Run: `./mvnw -pl ems-app -am compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add ems-app/pom.xml
git commit -m "feat(alarm): ems-app 接入 ems-alarm 依赖"
```

#### Task A3: AlarmProperties + AlarmModuleConfig + application.yml 配置

**Files:**
- Create: `ems-alarm/src/main/java/com/ems/alarm/config/AlarmProperties.java`
- Create: `ems-alarm/src/main/java/com/ems/alarm/config/AlarmModuleConfig.java`
- Modify: `ems-app/src/main/resources/application.yml`

- [ ] **Step 1: 写 AlarmProperties**

```java
package com.ems.alarm.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(prefix = "ems.alarm")
public record AlarmProperties(
        @Min(1) int defaultSilentTimeoutSeconds,
        @Min(1) int defaultConsecutiveFailCount,
        @Min(10) int pollIntervalSeconds,
        @Min(0) int suppressionWindowSeconds,
        @Min(0) int webhookRetryMax,
        @NotEmpty List<@Positive Integer> webhookRetryBackoffSeconds,
        @Min(1000) int webhookTimeoutDefaultMs
) {}
```

- [ ] **Step 2: AlarmModuleConfig（启用 properties + scheduling + async + clock bean）**

```java
package com.ems.alarm.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(AlarmProperties.class)
@EnableAsync
@EnableScheduling
public class AlarmModuleConfig {
    @Bean
    public Clock alarmClock() {
        return Clock.systemUTC();
    }
}
```

- [ ] **Step 3: application.yml 加默认值（在 ems: 段尾，与 ems.jwt 同级）**

```yaml
  alarm:
    default-silent-timeout-seconds: 600
    default-consecutive-fail-count: 3
    poll-interval-seconds: 60
    suppression-window-seconds: 300
    webhook-retry-max: 3
    webhook-retry-backoff-seconds: [10, 60, 300]
    webhook-timeout-default-ms: 5000
```

- [ ] **Step 4: 启动验证**

Run: `./mvnw -pl ems-app -am spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=dev`
Expected: 启动日志无 `ConfigurationProperties` 校验失败；看到 `Started FactoryEmsApplication ...` 后 Ctrl-C。

- [ ] **Step 5: 提交**

```bash
git add ems-alarm/src/main/java/com/ems/alarm/config/ \
        ems-app/src/main/resources/application.yml
git commit -m "feat(alarm): AlarmProperties + AlarmModuleConfig + application.yml 默认值"
```

#### Task A4: Flyway migration V2.2.0__init_alarm.sql

**Files:**
- Create: `ems-app/src/main/resources/db/migration/V2.2.0__init_alarm.sql`

- [ ] **Step 1: 写 5 张表 + 索引**

```sql
-- ems-alarm: 采集中断告警
-- spec: docs/superpowers/specs/2026-04-29-acquisition-alarm-design.md

CREATE TABLE alarms (
    id              BIGSERIAL PRIMARY KEY,
    device_id       BIGINT       NOT NULL,
    device_type     VARCHAR(32)  NOT NULL,
    alarm_type      VARCHAR(32)  NOT NULL,
    severity        VARCHAR(16)  NOT NULL DEFAULT 'WARNING',
    status          VARCHAR(16)  NOT NULL,
    triggered_at    TIMESTAMPTZ  NOT NULL,
    acked_at        TIMESTAMPTZ,
    acked_by        BIGINT,
    resolved_at     TIMESTAMPTZ,
    resolved_reason VARCHAR(32),
    last_seen_at    TIMESTAMPTZ,
    detail          JSONB,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_alarms_device_status ON alarms (device_id, status);
CREATE INDEX idx_alarms_status_trig   ON alarms (status, triggered_at DESC);
CREATE INDEX idx_alarms_triggered_at  ON alarms (triggered_at DESC);

CREATE TABLE alarm_rules_override (
    device_id              BIGINT      PRIMARY KEY,
    silent_timeout_seconds INT,
    consecutive_fail_count INT,
    maintenance_mode       BOOLEAN     NOT NULL DEFAULT FALSE,
    maintenance_note       VARCHAR(255),
    updated_at             TIMESTAMPTZ NOT NULL,
    updated_by             BIGINT
);

CREATE TABLE webhook_config (
    id            BIGSERIAL    PRIMARY KEY,
    enabled       BOOLEAN      NOT NULL DEFAULT FALSE,
    url           VARCHAR(512) NOT NULL,
    secret        VARCHAR(255),
    adapter_type  VARCHAR(32)  NOT NULL DEFAULT 'GENERIC_JSON',
    timeout_ms    INT          NOT NULL DEFAULT 5000,
    updated_at    TIMESTAMPTZ  NOT NULL,
    updated_by    BIGINT
);

CREATE TABLE webhook_delivery_log (
    id              BIGSERIAL    PRIMARY KEY,
    alarm_id        BIGINT       NOT NULL,
    attempts        INT          NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    last_error      VARCHAR(512),
    response_status INT,
    response_ms     INT,
    payload         TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_wdl_alarm  ON webhook_delivery_log (alarm_id);
CREATE INDEX idx_wdl_status ON webhook_delivery_log (status, created_at DESC);

CREATE TABLE alarm_inbox (
    id          BIGSERIAL    PRIMARY KEY,
    alarm_id    BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    kind        VARCHAR(16)  NOT NULL,
    read_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_inbox_user_unread ON alarm_inbox (user_id, read_at);
```

- [ ] **Step 2: 启动 ems-app 让 Flyway 跑 migration**

Run: `./mvnw -pl ems-app -am spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=dev`
Expected: 日志中 `Migrating schema "public" to version "2.2.0 - init alarm"` SUCCESS。Ctrl-C 停掉。

- [ ] **Step 3: psql 验证 5 张表存在**

```bash
docker exec factory-ems-postgres-1 psql -U ems -d ems -c "\dt alarms alarm_rules_override webhook_config webhook_delivery_log alarm_inbox"
```
Expected: 5 张表全部列出。

- [ ] **Step 4: 提交**

```bash
git add ems-app/src/main/resources/db/migration/V2.2.0__init_alarm.sql
git commit -m "feat(alarm): Flyway V2.2.0 — 5 张告警表 + 索引"
```

---

### Phase B — Entity + Repository

#### Task B1: 实体类 + 枚举

**Files:**
- Create: `ems-alarm/src/main/java/com/ems/alarm/entity/{Alarm, AlarmStatus, AlarmType, ResolvedReason, AlarmRuleOverride, WebhookConfig, WebhookDeliveryLog, DeliveryStatus, AlarmInbox, InboxKind}.java`

- [ ] **Step 1: 写 5 个枚举**

```java
// AlarmStatus.java
package com.ems.alarm.entity;
public enum AlarmStatus { ACTIVE, ACKED, RESOLVED }

// AlarmType.java
package com.ems.alarm.entity;
public enum AlarmType { SILENT_TIMEOUT, CONSECUTIVE_FAIL }

// ResolvedReason.java
package com.ems.alarm.entity;
public enum ResolvedReason { AUTO, MANUAL }

// DeliveryStatus.java
package com.ems.alarm.entity;
public enum DeliveryStatus { SUCCESS, FAILED }

// InboxKind.java
package com.ems.alarm.entity;
public enum InboxKind { TRIGGERED, RESOLVED }
```

- [ ] **Step 2: Alarm 实体（参考 Floorplan.java 风格 — 不用 Lombok）**

```java
package com.ems.alarm.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

@Entity
@Table(name = "alarms")
public class Alarm {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "device_type", nullable = false, length = 32)
    private String deviceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "alarm_type", nullable = false, length = 32)
    private AlarmType alarmType;

    @Column(nullable = false, length = 16)
    private String severity = "WARNING";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AlarmStatus status;

    @Column(name = "triggered_at", nullable = false)
    private OffsetDateTime triggeredAt;

    @Column(name = "acked_at")
    private OffsetDateTime ackedAt;

    @Column(name = "acked_by")
    private Long ackedBy;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolved_reason", length = 32)
    private ResolvedReason resolvedReason;

    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    // === getters & setters（IDE 生成；本仓库不用 Lombok）===
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDeviceId() { return deviceId; }
    public void setDeviceId(Long deviceId) { this.deviceId = deviceId; }
    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }
    public AlarmType getAlarmType() { return alarmType; }
    public void setAlarmType(AlarmType alarmType) { this.alarmType = alarmType; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public AlarmStatus getStatus() { return status; }
    public void setStatus(AlarmStatus status) { this.status = status; }
    public OffsetDateTime getTriggeredAt() { return triggeredAt; }
    public void setTriggeredAt(OffsetDateTime t) { this.triggeredAt = t; }
    public OffsetDateTime getAckedAt() { return ackedAt; }
    public void setAckedAt(OffsetDateTime t) { this.ackedAt = t; }
    public Long getAckedBy() { return ackedBy; }
    public void setAckedBy(Long ackedBy) { this.ackedBy = ackedBy; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(OffsetDateTime t) { this.resolvedAt = t; }
    public ResolvedReason getResolvedReason() { return resolvedReason; }
    public void setResolvedReason(ResolvedReason r) { this.resolvedReason = r; }
    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(OffsetDateTime t) { this.lastSeenAt = t; }
    public Map<String, Object> getDetail() { return detail; }
    public void setDetail(Map<String, Object> detail) { this.detail = detail; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 3: AlarmRuleOverride 实体（PK = device_id）**

```java
@Entity @Table(name = "alarm_rules_override")
public class AlarmRuleOverride {
    @Id @Column(name = "device_id") private Long deviceId;
    @Column(name = "silent_timeout_seconds") private Integer silentTimeoutSeconds;
    @Column(name = "consecutive_fail_count") private Integer consecutiveFailCount;
    @Column(name = "maintenance_mode", nullable = false) private boolean maintenanceMode;
    @Column(name = "maintenance_note", length = 255) private String maintenanceNote;
    @Column(name = "updated_at", nullable = false) private OffsetDateTime updatedAt;
    @Column(name = "updated_by") private Long updatedBy;
    // getters/setters
}
```

- [ ] **Step 4: 其余 3 个实体类（WebhookConfig / WebhookDeliveryLog / AlarmInbox）**

按相同风格写。所有时间字段用 `OffsetDateTime`。`WebhookDeliveryLog` 的 `status`、`AlarmInbox` 的 `kind` 用 `@Enumerated(EnumType.STRING)`。

- [ ] **Step 5: 编译验证**

Run: `./mvnw -pl ems-alarm -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 6: 提交**

```bash
git add ems-alarm/src/main/java/com/ems/alarm/entity/
git commit -m "feat(alarm): 5 个实体类 + 5 个枚举"
```

#### Task B2: Repository 接口（5 个）

**Files:**
- Create: `ems-alarm/src/main/java/com/ems/alarm/repository/{AlarmRepository, AlarmRuleOverrideRepository, WebhookConfigRepository, WebhookDeliveryLogRepository, AlarmInboxRepository}.java`

- [ ] **Step 1: 写 AlarmRepository（最关键）**

```java
package com.ems.alarm.repository;

import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.AlarmType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface AlarmRepository extends JpaRepository<Alarm, Long> {

    @Query("""
        SELECT a FROM Alarm a
        WHERE a.deviceId = :deviceId AND a.alarmType = :type
          AND a.status IN (com.ems.alarm.entity.AlarmStatus.ACTIVE,
                           com.ems.alarm.entity.AlarmStatus.ACKED)
        ORDER BY a.triggeredAt DESC
        """)
    Optional<Alarm> findActive(@Param("deviceId") Long deviceId, @Param("type") AlarmType type);

    long countByStatus(AlarmStatus status);

    @Query("""
        SELECT a FROM Alarm a
        WHERE (:status IS NULL OR a.status = :status)
          AND (:deviceId IS NULL OR a.deviceId = :deviceId)
          AND (:type IS NULL OR a.alarmType = :type)
          AND (:from IS NULL OR a.triggeredAt >= :from)
          AND (:to IS NULL OR a.triggeredAt < :to)
        """)
    Page<Alarm> search(@Param("status") AlarmStatus status,
                       @Param("deviceId") Long deviceId,
                       @Param("type") AlarmType type,
                       @Param("from") OffsetDateTime from,
                       @Param("to") OffsetDateTime to,
                       Pageable pageable);

    List<Alarm> findTop10ByOrderByTriggeredAtDesc();
}
```

- [ ] **Step 2: 其余 4 个 Repository**

```java
public interface AlarmRuleOverrideRepository extends JpaRepository<AlarmRuleOverride, Long> {}

public interface WebhookConfigRepository extends JpaRepository<WebhookConfig, Long> {
    Optional<WebhookConfig> findFirstByOrderByIdAsc();
}

public interface WebhookDeliveryLogRepository extends JpaRepository<WebhookDeliveryLog, Long> {
    Page<WebhookDeliveryLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
}

public interface AlarmInboxRepository extends JpaRepository<AlarmInbox, Long> {
    long countByUserIdAndReadAtIsNull(Long userId);
    Page<AlarmInbox> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
}
```

- [ ] **Step 3: 编译验证**

Run: `./mvnw -pl ems-alarm -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add ems-alarm/src/main/java/com/ems/alarm/repository/
git commit -m "feat(alarm): 5 个 Repository 接口"
```

---

### Phase C — 异常 + 阈值解析 + 状态机

#### Task C1: 异常类

**Files:**
- Create: `ems-alarm/src/main/java/com/ems/alarm/exception/{AlarmStateException, AlarmNotFoundException, WebhookConfigInvalidException}.java`

- [ ] **Step 1: 先确认 ems-core 异常类签名**

Run: `cat ems-core/src/main/java/com/ems/core/exception/ConflictException.java ems-core/src/main/java/com/ems/core/exception/BusinessException.java`

记录：每个异常类的可用构造签名，决定 alarm 异常 extends 哪个。

- [ ] **Step 2: 三个异常**

```java
// AlarmNotFoundException.java
package com.ems.alarm.exception;
import com.ems.core.exception.NotFoundException;
public class AlarmNotFoundException extends NotFoundException {
    public AlarmNotFoundException(Long id) { super("Alarm", id); }
}

// AlarmStateException.java —— 沿用 ems-core ConflictException
package com.ems.alarm.exception;
import com.ems.core.exception.ConflictException;
public class AlarmStateException extends ConflictException {
    public AlarmStateException(String message) { super(message); }
}

// WebhookConfigInvalidException.java —— BusinessException + ErrorCode.BAD_REQUEST
package com.ems.alarm.exception;
import com.ems.core.constant.ErrorCode;
import com.ems.core.exception.BusinessException;
public class WebhookConfigInvalidException extends BusinessException {
    public WebhookConfigInvalidException(String message) {
        super(ErrorCode.BAD_REQUEST, message);
    }
}
```

> 如 Step 1 显示 `ConflictException` 无 `(String)` 构造，改 `AlarmStateException extends BusinessException` 用 `ErrorCode.CONFLICT`。

- [ ] **Step 3: 编译 + 提交**

```bash
./mvnw -pl ems-alarm -am compile
git add ems-alarm/src/main/java/com/ems/alarm/exception/
git commit -m "feat(alarm): 3 个业务异常类"
```

#### Task C2: ThresholdResolver + 单测

**Files:**
- Create: `ems-alarm/src/main/java/com/ems/alarm/service/ThresholdResolver.java`
- Create: `ems-alarm/src/test/java/com/ems/alarm/service/ThresholdResolverTest.java`

- [ ] **Step 1: 写测试（覆盖 3 个场景）**

```java
package com.ems.alarm.service;

import com.ems.alarm.config.AlarmProperties;
import com.ems.alarm.entity.AlarmRuleOverride;
import com.ems.alarm.repository.AlarmRuleOverrideRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ThresholdResolverTest {

    private final AlarmProperties props = new AlarmProperties(600, 3, 60, 300, 3, List.of(10, 60, 300), 5000);
    private final AlarmRuleOverrideRepository repo = mock(AlarmRuleOverrideRepository.class);
    private final ThresholdResolver resolver = new ThresholdResolver(props, repo);

    @Test
    void noOverride_usesGlobalDefaults() {
        when(repo.findById(1L)).thenReturn(Optional.empty());
        ThresholdResolver.Resolved r = resolver.resolve(1L);
        assertThat(r.silentTimeoutSeconds()).isEqualTo(600);
        assertThat(r.consecutiveFailCount()).isEqualTo(3);
        assertThat(r.maintenanceMode()).isFalse();
    }

    @Test
    void override_takesPrecedenceOverDefault() {
        AlarmRuleOverride o = new AlarmRuleOverride();
        o.setDeviceId(1L);
        o.setSilentTimeoutSeconds(120);
        o.setConsecutiveFailCount(5);
        o.setMaintenanceMode(true);
        when(repo.findById(1L)).thenReturn(Optional.of(o));

        ThresholdResolver.Resolved r = resolver.resolve(1L);
        assertThat(r.silentTimeoutSeconds()).isEqualTo(120);
        assertThat(r.consecutiveFailCount()).isEqualTo(5);
        assertThat(r.maintenanceMode()).isTrue();
    }

    @Test
    void partialOverride_fallsBackPerField() {
        AlarmRuleOverride o = new AlarmRuleOverride();
        o.setDeviceId(1L);
        o.setSilentTimeoutSeconds(120);
        o.setConsecutiveFailCount(null);  // not set → fall back to global
        when(repo.findById(1L)).thenReturn(Optional.of(o));

        ThresholdResolver.Resolved r = resolver.resolve(1L);
        assertThat(r.silentTimeoutSeconds()).isEqualTo(120);
        assertThat(r.consecutiveFailCount()).isEqualTo(3);  // global
    }
}
```

- [ ] **Step 2: 跑测试 — 应失败**

Run: `./mvnw -pl ems-alarm test -Dtest=ThresholdResolverTest`
Expected: FAIL（ThresholdResolver 还不存在）

- [ ] **Step 3: 写实现**

```java
package com.ems.alarm.service;

import com.ems.alarm.config.AlarmProperties;
import com.ems.alarm.entity.AlarmRuleOverride;
import com.ems.alarm.repository.AlarmRuleOverrideRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class ThresholdResolver {
    private final AlarmProperties props;
    private final AlarmRuleOverrideRepository repo;

    public ThresholdResolver(AlarmProperties props, AlarmRuleOverrideRepository repo) {
        this.props = props;
        this.repo = repo;
    }

    public Resolved resolve(Long deviceId) {
        Optional<AlarmRuleOverride> ov = repo.findById(deviceId);
        int silent = ov.map(AlarmRuleOverride::getSilentTimeoutSeconds)
                       .filter(v -> v != null)
                       .orElse(props.defaultSilentTimeoutSeconds());
        int fail   = ov.map(AlarmRuleOverride::getConsecutiveFailCount)
                       .filter(v -> v != null)
                       .orElse(props.defaultConsecutiveFailCount());
        boolean maint = ov.map(AlarmRuleOverride::isMaintenanceMode).orElse(false);
        return new Resolved(silent, fail, maint);
    }

    public record Resolved(int silentTimeoutSeconds, int consecutiveFailCount, boolean maintenanceMode) {}
}
```

- [ ] **Step 4: 跑测试 — 应通过**

Run: `./mvnw -pl ems-alarm test -Dtest=ThresholdResolverTest`
Expected: PASS（3 tests）

- [ ] **Step 5: 提交**

```bash
git add ems-alarm/src/main/java/com/ems/alarm/service/ThresholdResolver.java \
        ems-alarm/src/test/java/com/ems/alarm/service/ThresholdResolverTest.java
git commit -m "feat(alarm): ThresholdResolver — 设备覆盖优先 + 全局默认回落"
```

#### Task C3: AlarmStateMachine + 单测

**Files:**
- Create: `ems-alarm/src/main/java/com/ems/alarm/service/AlarmStateMachine.java`
- Create: `ems-alarm/src/test/java/com/ems/alarm/service/AlarmStateMachineTest.java`

- [ ] **Step 1: 写测试**

```java
package com.ems.alarm.service;

import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.ResolvedReason;
import com.ems.alarm.exception.AlarmStateException;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.*;

class AlarmStateMachineTest {

    private final AlarmStateMachine sm = new AlarmStateMachine();

    @Test
    void ack_fromActive_setsAckedAtAndBy() {
        Alarm a = newAlarm(AlarmStatus.ACTIVE);
        sm.ack(a, 42L);
        assertThat(a.getStatus()).isEqualTo(AlarmStatus.ACKED);
        assertThat(a.getAckedBy()).isEqualTo(42L);
        assertThat(a.getAckedAt()).isNotNull();
    }

    @Test
    void ack_fromResolved_throwsConflict() {
        Alarm a = newAlarm(AlarmStatus.RESOLVED);
        assertThatThrownBy(() -> sm.ack(a, 42L))
                .isInstanceOf(AlarmStateException.class)
                .hasMessageContaining("RESOLVED");
    }

    @Test
    void resolve_fromActive_setsResolvedFields() {
        Alarm a = newAlarm(AlarmStatus.ACTIVE);
        sm.resolve(a, ResolvedReason.MANUAL);
        assertThat(a.getStatus()).isEqualTo(AlarmStatus.RESOLVED);
        assertThat(a.getResolvedReason()).isEqualTo(ResolvedReason.MANUAL);
        assertThat(a.getResolvedAt()).isNotNull();
    }

    @Test
    void resolve_fromAcked_ok() {
        Alarm a = newAlarm(AlarmStatus.ACKED);
        sm.resolve(a, ResolvedReason.AUTO);
        assertThat(a.getStatus()).isEqualTo(AlarmStatus.RESOLVED);
    }

    @Test
    void resolve_fromAlreadyResolved_throws() {
        Alarm a = newAlarm(AlarmStatus.RESOLVED);
        assertThatThrownBy(() -> sm.resolve(a, ResolvedReason.MANUAL))
                .isInstanceOf(AlarmStateException.class);
    }

    private Alarm newAlarm(AlarmStatus s) {
        Alarm a = new Alarm();
        a.setStatus(s);
        a.setTriggeredAt(OffsetDateTime.now());
        return a;
    }
}
```

- [ ] **Step 2: 跑测试 — 应失败**

Run: `./mvnw -pl ems-alarm test -Dtest=AlarmStateMachineTest`
Expected: FAIL

- [ ] **Step 3: 写实现**

```java
package com.ems.alarm.service;

import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.ResolvedReason;
import com.ems.alarm.exception.AlarmStateException;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
public class AlarmStateMachine {

    public void ack(Alarm a, Long userId) {
        if (a.getStatus() != AlarmStatus.ACTIVE) {
            throw new AlarmStateException("Cannot ack alarm in status " + a.getStatus());
        }
        a.setStatus(AlarmStatus.ACKED);
        a.setAckedAt(OffsetDateTime.now());
        a.setAckedBy(userId);
    }

    public void resolve(Alarm a, ResolvedReason reason) {
        if (a.getStatus() == AlarmStatus.RESOLVED) {
            throw new AlarmStateException("Already resolved");
        }
        a.setStatus(AlarmStatus.RESOLVED);
        a.setResolvedAt(OffsetDateTime.now());
        a.setResolvedReason(reason);
    }
}
```

- [ ] **Step 4: 测试通过 + 提交**

```bash
./mvnw -pl ems-alarm test -Dtest=AlarmStateMachineTest
git add ems-alarm/src/main/java/com/ems/alarm/service/AlarmStateMachine.java \
        ems-alarm/src/test/java/com/ems/alarm/service/AlarmStateMachineTest.java
git commit -m "feat(alarm): AlarmStateMachine — ACK/RESOLVE 状态转换 + 单测"
```

---

### Phase D — 检测引擎

#### Task D1: AlarmDetector 接口 + 实现 + 单测

**Files:**
- Create: `ems-alarm/src/main/java/com/ems/alarm/service/AlarmDetector.java`（interface）
- Create: `ems-alarm/src/main/java/com/ems/alarm/service/impl/AlarmDetectorImpl.java`
- Create: `ems-alarm/src/test/java/com/ems/alarm/service/AlarmDetectorTest.java`

- [ ] **Step 1: 接口**

```java
package com.ems.alarm.service;

public interface AlarmDetector {
    /** 扫描所有受监控设备并触发/恢复告警。catch 单设备异常，不抛出。 */
    void scan();
}
```

- [ ] **Step 2: 写测试（6 核心场景：超时触发 / 从未上报不触发 / 连续失败触发 / 维护跳过 / 重复不重写 / 抑制窗口外自动恢复）**

```java
package com.ems.alarm.service;

import com.ems.alarm.config.AlarmProperties;
import com.ems.alarm.entity.*;
import com.ems.alarm.repository.AlarmRepository;
import com.ems.alarm.service.impl.AlarmDetectorImpl;
import com.ems.collector.poller.DeviceSnapshot;
import com.ems.collector.poller.DeviceState;
import com.ems.collector.service.CollectorService;
import com.ems.meter.entity.Meter;
import com.ems.meter.repository.MeterRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AlarmDetectorTest {
    private final AlarmProperties props = new AlarmProperties(600, 3, 60, 300, 3, List.of(10, 60, 300), 5000);
    private final CollectorService collector = mock(CollectorService.class);
    private final MeterRepository meters = mock(MeterRepository.class);
    private final AlarmRepository alarms = mock(AlarmRepository.class);
    private final ThresholdResolver thresholds = mock(ThresholdResolver.class);
    private final AlarmStateMachine sm = new AlarmStateMachine();
    private final AlarmDispatcher dispatcher = mock(AlarmDispatcher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-04-29T10:00:00Z"), ZoneId.of("UTC"));

    private final AlarmDetectorImpl detector = new AlarmDetectorImpl(
            collector, meters, alarms, thresholds, sm, dispatcher, props, clock);

    @Test
    void silentTimeout_triggersAlarm() {
        DeviceSnapshot snap = snap("dev-1", "M-001", DeviceState.HEALTHY,
                Instant.parse("2026-04-29T09:49:00Z"), 0);  // 11min ago > 10min threshold
        when(collector.snapshots()).thenReturn(List.of(snap));
        when(meters.findByCode("M-001")).thenReturn(Optional.of(meter(1L, "M-001")));
        when(thresholds.resolve(1L)).thenReturn(new ThresholdResolver.Resolved(600, 3, false));
        when(alarms.findActive(eq(1L), eq(AlarmType.SILENT_TIMEOUT))).thenReturn(Optional.empty());
        when(alarms.save(any(Alarm.class))).thenAnswer(inv -> inv.getArgument(0));

        detector.scan();

        verify(alarms).save(argThat(a ->
                a.getAlarmType() == AlarmType.SILENT_TIMEOUT
                && a.getStatus() == AlarmStatus.ACTIVE
                && a.getDeviceId().equals(1L)));
        verify(dispatcher).dispatch(any());
    }

    @Test
    void neverSeenBefore_doesNotTrigger() {
        DeviceSnapshot snap = snap("dev-1", "M-001", DeviceState.HEALTHY, null, 0);
        when(collector.snapshots()).thenReturn(List.of(snap));
        when(meters.findByCode("M-001")).thenReturn(Optional.of(meter(1L, "M-001")));
        when(thresholds.resolve(1L)).thenReturn(new ThresholdResolver.Resolved(600, 3, false));
        when(alarms.findActive(any(), any())).thenReturn(Optional.empty());

        detector.scan();

        verify(alarms, never()).save(any());
    }

    @Test
    void consecutiveFail_triggersAlarm() {
        DeviceSnapshot snap = snap("dev-1", "M-001", DeviceState.DEGRADED,
                Instant.parse("2026-04-29T09:59:00Z"), 5);  // ≥ 3 fails
        when(collector.snapshots()).thenReturn(List.of(snap));
        when(meters.findByCode("M-001")).thenReturn(Optional.of(meter(1L, "M-001")));
        when(thresholds.resolve(1L)).thenReturn(new ThresholdResolver.Resolved(600, 3, false));
        when(alarms.findActive(eq(1L), eq(AlarmType.CONSECUTIVE_FAIL))).thenReturn(Optional.empty());
        when(alarms.save(any(Alarm.class))).thenAnswer(inv -> inv.getArgument(0));

        detector.scan();
        verify(alarms).save(argThat(a -> a.getAlarmType() == AlarmType.CONSECUTIVE_FAIL));
    }

    @Test
    void maintenanceMode_skipsDevice() {
        DeviceSnapshot snap = snap("dev-1", "M-001", DeviceState.UNREACHABLE,
                Instant.parse("2026-04-29T08:00:00Z"), 100);
        when(collector.snapshots()).thenReturn(List.of(snap));
        when(meters.findByCode("M-001")).thenReturn(Optional.of(meter(1L, "M-001")));
        when(thresholds.resolve(1L)).thenReturn(new ThresholdResolver.Resolved(600, 3, true));

        detector.scan();
        verify(alarms, never()).save(any());
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void existingActive_doesNotDuplicate() {
        DeviceSnapshot snap = snap("dev-1", "M-001", DeviceState.UNREACHABLE,
                Instant.parse("2026-04-29T09:00:00Z"), 5);
        when(collector.snapshots()).thenReturn(List.of(snap));
        when(meters.findByCode("M-001")).thenReturn(Optional.of(meter(1L, "M-001")));
        when(thresholds.resolve(1L)).thenReturn(new ThresholdResolver.Resolved(600, 3, false));
        Alarm existing = new Alarm();
        existing.setId(99L);
        existing.setStatus(AlarmStatus.ACTIVE);
        existing.setTriggeredAt(OffsetDateTime.parse("2026-04-29T09:01:00Z"));
        when(alarms.findActive(eq(1L), any())).thenReturn(Optional.of(existing));

        detector.scan();
        verify(alarms, never()).save(argThat(a -> a.getId() == null));
    }

    @Test
    void recoveryAfterSuppressionWindow_autoResolves() {
        DeviceSnapshot snap = snap("dev-1", "M-001", DeviceState.HEALTHY,
                Instant.parse("2026-04-29T09:59:30Z"), 0);  // recent + healthy
        when(collector.snapshots()).thenReturn(List.of(snap));
        when(meters.findByCode("M-001")).thenReturn(Optional.of(meter(1L, "M-001")));
        when(thresholds.resolve(1L)).thenReturn(new ThresholdResolver.Resolved(600, 3, false));

        Alarm active = new Alarm();
        active.setId(99L);
        active.setStatus(AlarmStatus.ACTIVE);
        active.setTriggeredAt(OffsetDateTime.parse("2026-04-29T09:50:00Z"));  // 10min ago > 5min window
        when(alarms.findActive(eq(1L), eq(AlarmType.SILENT_TIMEOUT))).thenReturn(Optional.of(active));
        when(alarms.findActive(eq(1L), eq(AlarmType.CONSECUTIVE_FAIL))).thenReturn(Optional.empty());
        when(alarms.save(any(Alarm.class))).thenAnswer(inv -> inv.getArgument(0));

        detector.scan();
        verify(alarms).save(argThat(a -> a.getStatus() == AlarmStatus.RESOLVED
                                       && a.getResolvedReason() == ResolvedReason.AUTO));
    }

    private DeviceSnapshot snap(String id, String code, DeviceState state,
                                Instant lastReadAt, long consecutiveErrors) {
        return new DeviceSnapshot(id, code, state, lastReadAt, null,
                consecutiveErrors, 0L, 0L, null);
    }

    private Meter meter(Long id, String code) {
        Meter m = new Meter();
        m.setId(id);
        m.setCode(code);
        return m;
    }
}
```

- [ ] **Step 3: 跑测试 — 应失败**

Run: `./mvnw -pl ems-alarm test -Dtest=AlarmDetectorTest`
Expected: FAIL（AlarmDetectorImpl 还不存在）

- [ ] **Step 4: 写 AlarmDetectorImpl**

```java
package com.ems.alarm.service.impl;

import com.ems.alarm.config.AlarmProperties;
import com.ems.alarm.entity.*;
import com.ems.alarm.repository.AlarmRepository;
import com.ems.alarm.service.AlarmDetector;
import com.ems.alarm.service.AlarmDispatcher;
import com.ems.alarm.service.AlarmStateMachine;
import com.ems.alarm.service.ThresholdResolver;
import com.ems.collector.poller.DeviceSnapshot;
import com.ems.collector.service.CollectorService;
import com.ems.meter.entity.Meter;
import com.ems.meter.repository.MeterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class AlarmDetectorImpl implements AlarmDetector {

    private static final Logger log = LoggerFactory.getLogger(AlarmDetectorImpl.class);

    private final CollectorService collector;
    private final MeterRepository meters;
    private final AlarmRepository alarms;
    private final ThresholdResolver thresholds;
    private final AlarmStateMachine sm;
    private final AlarmDispatcher dispatcher;
    private final AlarmProperties props;
    private final Clock clock;

    public AlarmDetectorImpl(CollectorService collector, MeterRepository meters,
                             AlarmRepository alarms, ThresholdResolver thresholds,
                             AlarmStateMachine sm, AlarmDispatcher dispatcher,
                             AlarmProperties props, Clock clock) {
        this.collector = collector;
        this.meters = meters;
        this.alarms = alarms;
        this.thresholds = thresholds;
        this.sm = sm;
        this.dispatcher = dispatcher;
        this.props = props;
        this.clock = clock;
    }

    @Override
    @Scheduled(fixedDelayString = "#{${ems.alarm.poll-interval-seconds} * 1000}")
    public void scan() {
        for (DeviceSnapshot snap : collector.snapshots()) {
            try {
                scanOne(snap);
            } catch (Exception e) {
                log.warn("Scan failed for device {}: {}", snap.deviceId(), e.getMessage(), e);
            }
        }
    }

    private void scanOne(DeviceSnapshot snap) {
        Optional<Meter> meterOpt = meters.findByCode(snap.meterCode());
        if (meterOpt.isEmpty()) return;
        Long meterId = meterOpt.get().getId();

        ThresholdResolver.Resolved t = thresholds.resolve(meterId);
        if (t.maintenanceMode()) return;

        boolean silentHit = checkSilent(snap, t.silentTimeoutSeconds());
        boolean failHit   = snap.consecutiveErrors() >= t.consecutiveFailCount();

        AlarmType primaryType = silentHit ? AlarmType.SILENT_TIMEOUT
                              : failHit ? AlarmType.CONSECUTIVE_FAIL
                              : null;

        if (primaryType != null) {
            Optional<Alarm> active = alarms.findActive(meterId, primaryType);
            if (active.isEmpty()) {
                fire(meterId, primaryType, snap, t);
            }
        } else {
            tryAutoResolve(meterId, AlarmType.SILENT_TIMEOUT);
            tryAutoResolve(meterId, AlarmType.CONSECUTIVE_FAIL);
        }
    }

    private boolean checkSilent(DeviceSnapshot snap, int thresholdSec) {
        if (snap.lastReadAt() == null) return false;          // 从未上报 → 不触发
        Duration silent = Duration.between(snap.lastReadAt(), clock.instant());
        return silent.toSeconds() > thresholdSec;
    }

    private void fire(Long meterId, AlarmType type, DeviceSnapshot snap, ThresholdResolver.Resolved t) {
        Alarm a = new Alarm();
        a.setDeviceId(meterId);
        a.setDeviceType("METER");
        a.setAlarmType(type);
        a.setStatus(AlarmStatus.ACTIVE);
        a.setTriggeredAt(OffsetDateTime.now(clock));
        if (snap.lastReadAt() != null) {
            a.setLastSeenAt(OffsetDateTime.ofInstant(snap.lastReadAt(), ZoneOffset.UTC));
        }
        Map<String, Object> detail = new HashMap<>();
        detail.put("threshold_silent_seconds", t.silentTimeoutSeconds());
        detail.put("threshold_consecutive_fails", t.consecutiveFailCount());
        detail.put("snapshot_consecutive_errors", snap.consecutiveErrors());
        a.setDetail(detail);
        Alarm saved = alarms.save(a);
        dispatcher.dispatch(saved);
    }

    private void tryAutoResolve(Long meterId, AlarmType type) {
        Optional<Alarm> active = alarms.findActive(meterId, type);
        if (active.isEmpty()) return;
        Alarm a = active.get();
        Duration since = Duration.between(a.getTriggeredAt().toInstant(), clock.instant());
        if (since.toSeconds() > props.suppressionWindowSeconds()) {
            sm.resolve(a, ResolvedReason.AUTO);
            alarms.save(a);
            dispatcher.dispatchResolved(a);
        }
    }
}
```

- [ ] **Step 5: 测试通过**

Run: `./mvnw -pl ems-alarm test -Dtest=AlarmDetectorTest`
Expected: PASS（6 tests）

> 如果某测试失败，**先看实际行为**对照预期 — 可能是 `OffsetDateTime` 时区比较，或 mockito argument matcher 顺序。修测试或修实现以匹配预期，不要相互妥协。

- [ ] **Step 6: 提交**

```bash
git add ems-alarm/src/main/java/com/ems/alarm/service/AlarmDetector.java \
        ems-alarm/src/main/java/com/ems/alarm/service/impl/AlarmDetectorImpl.java \
        ems-alarm/src/test/java/com/ems/alarm/service/AlarmDetectorTest.java
git commit -m "feat(alarm): AlarmDetector — 静默超时/连续失败检测 + 自动恢复"
```

#### Task D2: 验证 detector 在 ems-app 启动并能注入

**Files:**（无新文件，验证步骤）

- [ ] **Step 1: 临时存根 AlarmDispatcher（D1 编译需要 — 可在 D1 之前先做）**

如果 D1 编译失败说 `AlarmDispatcher` bean 不存在，临时新建 `service/AlarmDispatcher.java` 接口 + `impl/NoOpAlarmDispatcherImpl.java` 占位（标 `@Service @ConditionalOnMissingBean`），E1 时替换。

> **建议顺序：先做 D1 写接口（不实现）+ NoOp 实现 → D1 实现 + 测 → E1 替换 NoOp 为真实 dispatcher。** 这样 D 阶段始终编译可运行。

- [ ] **Step 2: 启动 ems-app 验证**

Run: `./mvnw -pl ems-app -am spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=dev`
Expected: 启动成功；日志看到 `Started FactoryEmsApplication ...`；等 60s 看到一轮 scan（无 ERROR）。Ctrl-C。

- [ ] **Step 3: 提交（如有日志格式调整）**

无改动则跳过。如果做了 NoOp dispatcher 临时存根：

```bash
git add ems-alarm/src/main/java/com/ems/alarm/service/AlarmDispatcher.java \
        ems-alarm/src/main/java/com/ems/alarm/service/impl/NoOpAlarmDispatcherImpl.java
git commit -m "chore(alarm): NoOp AlarmDispatcher 占位（E1 替换）"
```

#### Task D3: AlarmServiceIT — 集成测试（Testcontainers）

**Files:**
- Create: `ems-alarm/src/test/java/com/ems/alarm/it/AlarmServiceIT.java`

- [ ] **Step 1: 写完整生命周期 IT**

```java
package com.ems.alarm.it;

import com.ems.alarm.entity.*;
import com.ems.alarm.repository.AlarmRepository;
import com.ems.alarm.service.AlarmDetector;
import com.ems.alarm.service.AlarmStateMachine;
import com.ems.collector.poller.DeviceSnapshot;
import com.ems.collector.poller.DeviceState;
import com.ems.collector.service.CollectorService;
import com.ems.meter.entity.Meter;
import com.ems.meter.repository.MeterRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class AlarmServiceIT {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    @MockBean CollectorService collector;
    @Autowired MeterRepository meters;
    @Autowired AlarmRepository alarms;
    @Autowired AlarmDetector detector;
    @Autowired AlarmStateMachine sm;

    @Test
    void fullLifecycle_triggerAckRecover() {
        // 1) seed: 1 meter
        Meter m = new Meter();
        m.setCode("M-IT-001");
        m.setName("IT meter");
        // ... fill mandatory fields per Meter entity
        Meter saved = meters.save(m);

        // 2) snapshot: lastSeen 20min ago → SILENT_TIMEOUT
        DeviceSnapshot snap = new DeviceSnapshot("dev-it-1", "M-IT-001", DeviceState.HEALTHY,
                Instant.now().minusSeconds(1200), null, 0L, 0L, 0L, null);
        when(collector.snapshots()).thenReturn(List.of(snap));

        // 3) scan → ACTIVE alarm written
        detector.scan();
        List<Alarm> active = alarms.findAll();
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getStatus()).isEqualTo(AlarmStatus.ACTIVE);
        assertThat(active.get(0).getDeviceId()).isEqualTo(saved.getId());

        // 4) ack
        Alarm a = active.get(0);
        sm.ack(a, 1L);
        alarms.save(a);
        assertThat(alarms.findById(a.getId()).get().getStatus()).isEqualTo(AlarmStatus.ACKED);

        // 5) snapshot recovers (recent lastSeen)
        DeviceSnapshot fresh = new DeviceSnapshot("dev-it-1", "M-IT-001", DeviceState.HEALTHY,
                Instant.now(), null, 0L, 1L, 0L, null);
        when(collector.snapshots()).thenReturn(List.of(fresh));

        // 6) Force triggeredAt back > suppressionWindow
        a.setTriggeredAt(a.getTriggeredAt().minusMinutes(10));
        alarms.save(a);

        // 7) scan → AUTO RESOLVED
        detector.scan();
        Alarm finalAlarm = alarms.findById(a.getId()).orElseThrow();
        assertThat(finalAlarm.getStatus()).isEqualTo(AlarmStatus.RESOLVED);
        assertThat(finalAlarm.getResolvedReason()).isEqualTo(ResolvedReason.AUTO);
    }
}
```

> **本地 macOS docker-java 兼容问题**：本地跑 IT 可能失败（spec §7.2 已记录例外）。允许 `mvn -DskipITs verify` 本地，CI 必须跑全集。

- [ ] **Step 2: 跑 IT**

Run: `./mvnw -pl ems-alarm test -Dtest=AlarmServiceIT`
Expected: PASS（前提：Docker 可用）

- [ ] **Step 3: 提交**

```bash
git add ems-alarm/src/test/java/com/ems/alarm/it/AlarmServiceIT.java
git commit -m "test(alarm): AlarmServiceIT — 完整生命周期 IT"
```

---

### Phase E — 派发器

#### Task E1: AlarmDispatcher 接口 + 真实实现

**Files:**
- Create: `ems-alarm/src/main/java/com/ems/alarm/service/AlarmDispatcher.java`（如未在 D2 创建）
- Create: `ems-alarm/src/main/java/com/ems/alarm/service/impl/AlarmDispatcherImpl.java`
- Delete: `NoOpAlarmDispatcherImpl.java`（如 D2 创建过）

- [ ] **Step 1: 接口（如未存在）**

```java
package com.ems.alarm.service;

import com.ems.alarm.entity.Alarm;

public interface AlarmDispatcher {
    void dispatch(Alarm alarm);          // TRIGGERED
    void dispatchResolved(Alarm alarm);  // RESOLVED（仅站内）
}
```

- [ ] **Step 2: 真实实现（委托 InAppChannel + WebhookChannel）**

```java
package com.ems.alarm.service.impl;

import com.ems.alarm.entity.Alarm;
import com.ems.alarm.service.AlarmDispatcher;
import com.ems.alarm.service.InAppChannel;
import com.ems.alarm.service.WebhookChannel;
import org.springframework.stereotype.Service;

@Service
public class AlarmDispatcherImpl implements AlarmDispatcher {
    private final InAppChannel inApp;
    private final WebhookChannel webhook;

    public AlarmDispatcherImpl(InAppChannel inApp, WebhookChannel webhook) {
        this.inApp = inApp;
        this.webhook = webhook;
    }

    @Override
    public void dispatch(Alarm a) {
        inApp.sendTriggered(a);
        webhook.sendTriggered(a);
    }

    @Override
    public void dispatchResolved(Alarm a) {
        inApp.sendResolved(a);
        // 首版恢复事件不发 webhook
    }
}
```

- [ ] **Step 3: 删除 NoOp（如 D2 创建过）**

```bash
rm -f ems-alarm/src/main/java/com/ems/alarm/service/impl/NoOpAlarmDispatcherImpl.java
```

> **暂不提交**：InAppChannel / WebhookChannel 还未实现。E2/E3/E4 完成后整体编译通过再 commit。

#### Task E2: InAppChannel 实现 + 单测

**Files:**
- Create: `ems-alarm/src/main/java/com/ems/alarm/service/InAppChannel.java`（interface）
- Create: `ems-alarm/src/main/java/com/ems/alarm/service/impl/InAppChannelImpl.java`
- Create: `ems-alarm/src/test/java/com/ems/alarm/service/InAppChannelTest.java`

- [ ] **Step 1: 先确认 ems-auth 中 UserRepository 可按角色查询**

Run: `grep -rn "findByRole\|RoleName\|hasRole" ems-auth/src/main/java/`

记录可用方法签名。如无 `findByRoleNameInAndEnabledTrue`，用现有 query 替代或新加方法（首选不动 ems-auth，用既有 query 加 stream filter）。

- [ ] **Step 2: 接口**

```java
package com.ems.alarm.service;
import com.ems.alarm.entity.Alarm;

public interface InAppChannel {
    void sendTriggered(Alarm a);
    void sendResolved(Alarm a);
}
```

- [ ] **Step 3: 测试**

```java
package com.ems.alarm.service;

import com.ems.alarm.entity.*;
import com.ems.alarm.repository.AlarmInboxRepository;
import com.ems.alarm.service.impl.InAppChannelImpl;
// import ems-auth User + UserRepository
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class InAppChannelTest {
    private final UserRepository users = mock(UserRepository.class);
    private final AlarmInboxRepository inbox = mock(AlarmInboxRepository.class);
    private final InAppChannelImpl channel = new InAppChannelImpl(users, inbox);

    @Test
    void sendTriggered_writesOneRowPerEligibleUser() {
        when(users.findActiveAdminsAndOperators()).thenReturn(List.of(user(1L), user(2L), user(3L)));
        Alarm a = newAlarm(1L);

        channel.sendTriggered(a);

        ArgumentCaptor<List<AlarmInbox>> cap = ArgumentCaptor.forClass(List.class);
        verify(inbox).saveAll(cap.capture());
        assertThat(cap.getValue()).hasSize(3);
        assertThat(cap.getValue()).allMatch(r -> r.getKind() == InboxKind.TRIGGERED);
    }

    @Test
    void sendResolved_writesKindResolved() {
        when(users.findActiveAdminsAndOperators()).thenReturn(List.of(user(1L)));
        Alarm a = newAlarm(1L);

        channel.sendResolved(a);

        ArgumentCaptor<List<AlarmInbox>> cap = ArgumentCaptor.forClass(List.class);
        verify(inbox).saveAll(cap.capture());
        assertThat(cap.getValue().get(0).getKind()).isEqualTo(InboxKind.RESOLVED);
    }
    // helpers user() / newAlarm()
}
```

- [ ] **Step 4: 实现**

```java
package com.ems.alarm.service.impl;

import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmInbox;
import com.ems.alarm.entity.InboxKind;
import com.ems.alarm.repository.AlarmInboxRepository;
import com.ems.alarm.service.InAppChannel;
// 引入 ems-auth User + UserRepository
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Component
public class InAppChannelImpl implements InAppChannel {
    private final UserRepository users;
    private final AlarmInboxRepository inbox;

    public InAppChannelImpl(UserRepository users, AlarmInboxRepository inbox) {
        this.users = users;
        this.inbox = inbox;
    }

    @Override @Transactional
    public void sendTriggered(Alarm a) { write(a, InboxKind.TRIGGERED); }
    @Override @Transactional
    public void sendResolved(Alarm a)  { write(a, InboxKind.RESOLVED); }

    private void write(Alarm a, InboxKind kind) {
        List<User> recipients = users.findActiveAdminsAndOperators();
        OffsetDateTime now = OffsetDateTime.now();
        List<AlarmInbox> rows = recipients.stream().map(u -> {
            AlarmInbox row = new AlarmInbox();
            row.setAlarmId(a.getId());
            row.setUserId(u.getId());
            row.setKind(kind);
            row.setCreatedAt(now);
            return row;
        }).toList();
        inbox.saveAll(rows);
    }
}
```

> **若 ems-auth 无 `findActiveAdminsAndOperators` 方法**：在 InAppChannelImpl 内用 `users.findAll()` + stream filter（按角色集合 + enabled）。先用最简方案验证流程，性能优化留后。

- [ ] **Step 5: 跑测试**

Run: `./mvnw -pl ems-alarm test -Dtest=InAppChannelTest`
Expected: PASS

- [ ] **Step 6: 暂不提交**（等 WebhookChannel 完成）

#### Task E3: WebhookSigner + WebhookAdapter + GenericJsonAdapter + 单测

**Files:**
- Create: `ems-alarm/src/main/java/com/ems/alarm/service/WebhookSigner.java`
- Create: `ems-alarm/src/main/java/com/ems/alarm/service/adapter/{WebhookAdapter, GenericJsonAdapter}.java`
- Create: `ems-alarm/src/test/java/com/ems/alarm/service/{WebhookSignerTest, GenericJsonAdapterTest}.java`

- [ ] **Step 1: WebhookSigner 测试**

```java
class WebhookSignerTest {
    @Test
    void hmacSha256_knownVector() {
        // RFC 4231 test vector: secret="key", body="The quick brown fox jumps over the lazy dog"
        // expected = sha256=f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8
        String sig = WebhookSigner.sign("key",
                "The quick brown fox jumps over the lazy dog");
        assertThat(sig).isEqualTo(
                "sha256=f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    @Test
    void emptySecret_returnsEmptySignature() {
        assertThat(WebhookSigner.sign(null, "data")).isEmpty();
        assertThat(WebhookSigner.sign("",   "data")).isEmpty();
    }
}
```

- [ ] **Step 2: WebhookSigner 实现**

```java
package com.ems.alarm.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public final class WebhookSigner {
    private WebhookSigner() {}

    public static String sign(String secret, String body) {
        if (secret == null || secret.isEmpty()) return "";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC sign failed", e);
        }
    }
}
```

- [ ] **Step 3: WebhookAdapter 接口 + GenericJsonAdapter**

```java
// WebhookAdapter.java
package com.ems.alarm.service.adapter;
import com.ems.alarm.entity.Alarm;
public interface WebhookAdapter {
    String getType();
    String buildPayload(Alarm alarm, String deviceCode, String deviceName);
}

// GenericJsonAdapter.java
package com.ems.alarm.service.adapter;

import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class GenericJsonAdapter implements WebhookAdapter {
    private final ObjectMapper mapper;
    public GenericJsonAdapter(ObjectMapper mapper) { this.mapper = mapper; }

    @Override public String getType() { return "GENERIC_JSON"; }

    @Override
    public String buildPayload(Alarm a, String deviceCode, String deviceName) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("event", a.getStatus() == AlarmStatus.RESOLVED ? "alarm.resolved" : "alarm.triggered");
        p.put("alarm_id", a.getId());
        p.put("device_id", a.getDeviceId());
        p.put("device_type", a.getDeviceType());
        p.put("device_code", deviceCode);
        p.put("device_name", deviceName);
        p.put("alarm_type", a.getAlarmType().name());
        p.put("severity", a.getSeverity());
        p.put("triggered_at", a.getTriggeredAt().toString());
        if (a.getLastSeenAt() != null) p.put("last_seen_at", a.getLastSeenAt().toString());
        if (a.getDetail() != null) p.put("detail", a.getDetail());
        try { return mapper.writeValueAsString(p); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
```

- [ ] **Step 4: GenericJsonAdapter 测试**

```java
class GenericJsonAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final GenericJsonAdapter adapter = new GenericJsonAdapter(mapper);

    @Test void payload_containsAllRequiredFields() throws Exception {
        Alarm a = buildAlarm();  // helper
        String json = adapter.buildPayload(a, "M-001", "Meter A1");
        Map<?, ?> parsed = mapper.readValue(json, Map.class);
        assertThat(parsed).containsKeys("event", "alarm_id", "device_code", "device_name",
                "alarm_type", "severity", "triggered_at");
        assertThat(parsed.get("event")).isEqualTo("alarm.triggered");
    }

    @Test void timestamp_isIso8601WithOffset() throws Exception {
        Alarm a = buildAlarm();
        a.setTriggeredAt(OffsetDateTime.parse("2026-04-29T08:15:30+08:00"));
        String json = adapter.buildPayload(a, "M-001", "Meter A1");
        assertThat(json).contains("\"triggered_at\":\"2026-04-29T08:15:30+08:00\"");
    }
}
```

- [ ] **Step 5: 测试 + 提交**

```bash
./mvnw -pl ems-alarm test -Dtest=WebhookSignerTest,GenericJsonAdapterTest
git add ems-alarm/src/main/java/com/ems/alarm/service/WebhookSigner.java \
        ems-alarm/src/main/java/com/ems/alarm/service/adapter/ \
        ems-alarm/src/test/java/com/ems/alarm/service/WebhookSignerTest.java \
        ems-alarm/src/test/java/com/ems/alarm/service/GenericJsonAdapterTest.java
git commit -m "feat(alarm): WebhookSigner（HMAC-SHA256）+ GenericJsonAdapter"
```

#### Task E4: WebhookChannel 实现 + 重试 + ModuleConfig bean

**Files:**
- Create: `ems-alarm/src/main/java/com/ems/alarm/service/WebhookChannel.java`（interface）
- Create: `ems-alarm/src/main/java/com/ems/alarm/service/impl/WebhookChannelImpl.java`
- Modify: `ems-alarm/src/main/java/com/ems/alarm/config/AlarmModuleConfig.java`

- [ ] **Step 1: 接口**

```java
package com.ems.alarm.service;

import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.WebhookConfig;

public interface WebhookChannel {
    void sendTriggered(Alarm a);
    void retryDelivery(Long deliveryLogId);
    WebhookTestResult test(WebhookConfig cfg, Alarm sampleAlarm, String deviceCode, String deviceName);

    record WebhookTestResult(int statusCode, long durationMs, String error) {}
}
```

- [ ] **Step 2: AlarmModuleConfig 增加三个 bean**

```java
// 加在 AlarmModuleConfig.java 内
@Bean(name = "webhookExecutor")
public ThreadPoolTaskExecutor webhookExecutor() {
    ThreadPoolTaskExecutor t = new ThreadPoolTaskExecutor();
    t.setCorePoolSize(2);
    t.setMaxPoolSize(4);
    t.setQueueCapacity(100);
    t.setThreadNamePrefix("alarm-webhook-");
    t.initialize();
    return t;
}

@Bean(destroyMethod = "shutdown")
public ScheduledExecutorService webhookRetryScheduler() {
    return Executors.newScheduledThreadPool(1,
            r -> {
                Thread t = new Thread(r, "alarm-webhook-retry");
                t.setDaemon(true);
                return t;
            });
}

@Bean
public HttpClient webhookHttpClient() {
    return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
}
```

- [ ] **Step 3: WebhookChannelImpl**

```java
package com.ems.alarm.service.impl;

import com.ems.alarm.config.AlarmProperties;
import com.ems.alarm.entity.*;
import com.ems.alarm.repository.*;
import com.ems.alarm.service.WebhookChannel;
import com.ems.alarm.service.WebhookSigner;
import com.ems.alarm.service.adapter.WebhookAdapter;
import com.ems.meter.entity.Meter;
import com.ems.meter.repository.MeterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class WebhookChannelImpl implements WebhookChannel {
    private static final Logger log = LoggerFactory.getLogger(WebhookChannelImpl.class);

    private final WebhookConfigRepository cfgRepo;
    private final WebhookDeliveryLogRepository deliveryRepo;
    private final AlarmRepository alarmRepo;
    private final MeterRepository meterRepo;
    private final Map<String, WebhookAdapter> adaptersByType;  // Spring auto-wires by Map
    private final AlarmProperties props;
    private final ScheduledExecutorService retryScheduler;
    private final HttpClient http;

    public WebhookChannelImpl(WebhookConfigRepository cfgRepo,
                              WebhookDeliveryLogRepository deliveryRepo,
                              AlarmRepository alarmRepo,
                              MeterRepository meterRepo,
                              java.util.List<WebhookAdapter> adapters,
                              AlarmProperties props,
                              ScheduledExecutorService webhookRetryScheduler,
                              HttpClient webhookHttpClient) {
        this.cfgRepo = cfgRepo;
        this.deliveryRepo = deliveryRepo;
        this.alarmRepo = alarmRepo;
        this.meterRepo = meterRepo;
        this.adaptersByType = adapters.stream()
                .collect(java.util.stream.Collectors.toMap(WebhookAdapter::getType, a -> a));
        this.props = props;
        this.retryScheduler = webhookRetryScheduler;
        this.http = webhookHttpClient;
    }

    @Override
    @Async("webhookExecutor")
    public void sendTriggered(Alarm a) {
        cfgRepo.findFirstByOrderByIdAsc()
               .filter(WebhookConfig::isEnabled)
               .ifPresent(cfg -> attemptDelivery(a, cfg, 1));
    }

    private void attemptDelivery(Alarm a, WebhookConfig cfg, int attempt) {
        WebhookAdapter adapter = adaptersByType.getOrDefault(cfg.getAdapterType(),
                adaptersByType.get("GENERIC_JSON"));
        Meter m = meterRepo.findById(a.getDeviceId()).orElse(null);
        String code = m != null ? m.getCode() : "unknown";
        String name = m != null ? m.getName() : "";
        String body = adapter.buildPayload(a, code, name);
        String sig  = WebhookSigner.sign(cfg.getSecret(), body);

        long start = System.currentTimeMillis();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.getUrl()))
                    .header("Content-Type", "application/json")
                    .header("X-EMS-Event", "alarm.triggered")
                    .header("X-EMS-Signature", sig)
                    .timeout(Duration.ofMillis(cfg.getTimeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            long dur = System.currentTimeMillis() - start;
            if (res.statusCode() / 100 == 2) {
                writeDeliveryLog(a, attempt, DeliveryStatus.SUCCESS, null,
                        res.statusCode(), (int) dur, body);
            } else {
                scheduleRetry(a, cfg, attempt, "HTTP " + res.statusCode(),
                        res.statusCode(), (int) dur, body);
            }
        } catch (Exception e) {
            long dur = System.currentTimeMillis() - start;
            scheduleRetry(a, cfg, attempt,
                    e.getClass().getSimpleName() + ": " + e.getMessage(),
                    null, (int) dur, body);
        }
    }

    private void scheduleRetry(Alarm a, WebhookConfig cfg, int attempt, String error,
                               Integer status, int durMs, String body) {
        if (attempt > props.webhookRetryMax()) {
            writeDeliveryLog(a, attempt, DeliveryStatus.FAILED, error, status, durMs, body);
            log.error("Webhook delivery failed after {} attempts: alarm_id={}, last_error={}",
                    attempt, a.getId(), error);
            return;
        }
        int backoffSec = props.webhookRetryBackoffSeconds().get(attempt - 1);
        retryScheduler.schedule(
                () -> attemptDelivery(a, cfg, attempt + 1),
                backoffSec,
                TimeUnit.SECONDS);
    }

    private void writeDeliveryLog(Alarm a, int attempts, DeliveryStatus status, String error,
                                  Integer respStatus, Integer respMs, String body) {
        WebhookDeliveryLog row = new WebhookDeliveryLog();
        row.setAlarmId(a.getId());
        row.setAttempts(attempts);
        row.setStatus(status);
        row.setLastError(error);
        row.setResponseStatus(respStatus);
        row.setResponseMs(respMs);
        row.setPayload(body);
        deliveryRepo.save(row);
    }

    @Override
    public WebhookTestResult test(WebhookConfig cfg, Alarm sample, String code, String name) {
        WebhookAdapter adapter = adaptersByType.getOrDefault(cfg.getAdapterType(),
                adaptersByType.get("GENERIC_JSON"));
        String body = adapter.buildPayload(sample, code, name);
        long start = System.currentTimeMillis();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.getUrl()))
                    .header("Content-Type", "application/json")
                    .header("X-EMS-Event", "alarm.test")
                    .header("X-EMS-Signature", WebhookSigner.sign(cfg.getSecret(), body))
                    .timeout(Duration.ofMillis(cfg.getTimeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new WebhookTestResult(res.statusCode(), System.currentTimeMillis() - start, null);
        } catch (Exception e) {
            return new WebhookTestResult(0, System.currentTimeMillis() - start, e.toString());
        }
    }

    @Override
    public void retryDelivery(Long deliveryLogId) {
        WebhookDeliveryLog old = deliveryRepo.findById(deliveryLogId).orElseThrow();
        Alarm a = alarmRepo.findById(old.getAlarmId()).orElseThrow();
        cfgRepo.findFirstByOrderByIdAsc().ifPresent(cfg -> attemptDelivery(a, cfg, 1));
    }
}
```

- [ ] **Step 4: 提交（E1+E2+E4 整体）**

```bash
./mvnw -pl ems-alarm -am compile
git add ems-alarm/src/main/java/com/ems/alarm/service/AlarmDispatcher.java \
        ems-alarm/src/main/java/com/ems/alarm/service/InAppChannel.java \
        ems-alarm/src/main/java/com/ems/alarm/service/WebhookChannel.java \
        ems-alarm/src/main/java/com/ems/alarm/service/impl/AlarmDispatcherImpl.java \
        ems-alarm/src/main/java/com/ems/alarm/service/impl/InAppChannelImpl.java \
        ems-alarm/src/main/java/com/ems/alarm/service/impl/WebhookChannelImpl.java \
        ems-alarm/src/main/java/com/ems/alarm/config/AlarmModuleConfig.java \
        ems-alarm/src/test/java/com/ems/alarm/service/InAppChannelTest.java
# 删除 NoOp（如有）
git rm -f ems-alarm/src/main/java/com/ems/alarm/service/impl/NoOpAlarmDispatcherImpl.java 2>/dev/null || true
git commit -m "feat(alarm): AlarmDispatcher + InAppChannel + WebhookChannel @Async + 重试"
```

#### Task E5: WebhookDispatcherIT — MockWebServer 集成测试

**Files:**
- Create: `ems-alarm/src/test/java/com/ems/alarm/it/WebhookDispatcherIT.java`

- [ ] **Step 1: 写 IT，覆盖 4 场景**

```java
package com.ems.alarm.it;

import com.ems.alarm.entity.*;
import com.ems.alarm.repository.*;
import com.ems.alarm.service.WebhookChannel;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "ems.alarm.webhook-retry-backoff-seconds[0]=1",
        "ems.alarm.webhook-retry-backoff-seconds[1]=1",
        "ems.alarm.webhook-retry-backoff-seconds[2]=1"
})
class WebhookDispatcherIT {

    @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");
    static MockWebServer server;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    @BeforeAll static void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }
    @AfterAll static void tearDown() throws Exception { server.shutdown(); }

    @Autowired WebhookChannel channel;
    @Autowired WebhookConfigRepository cfgRepo;
    @Autowired WebhookDeliveryLogRepository deliveryRepo;
    @Autowired AlarmRepository alarmRepo;

    @BeforeEach
    void seed() {
        // clean repos, write WebhookConfig with url=server.url("/").toString()
        // write 1 Alarm
    }

    @Test void successOn2xx_writesDeliveryLogSuccess() {
        server.enqueue(new MockResponse().setResponseCode(200));
        Alarm a = alarmRepo.findAll().get(0);
        channel.sendTriggered(a);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(deliveryRepo.findAll())
                        .anyMatch(d -> d.getStatus() == DeliveryStatus.SUCCESS && d.getAttempts() == 1));
    }

    @Test void retryOn5xx_thenSuccess() {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(200));
        Alarm a = alarmRepo.findAll().get(0);
        channel.sendTriggered(a);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(deliveryRepo.findAll())
                        .anyMatch(d -> d.getStatus() == DeliveryStatus.SUCCESS && d.getAttempts() == 2));
    }

    @Test void timeoutTriggersRetry() {
        server.enqueue(new MockResponse().setBodyDelay(10, TimeUnit.SECONDS).setResponseCode(200));
        server.enqueue(new MockResponse().setResponseCode(200));
        // configure timeoutMs=500
        Alarm a = alarmRepo.findAll().get(0);
        channel.sendTriggered(a);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(deliveryRepo.findAll())
                        .anyMatch(d -> d.getAttempts() >= 2));
    }

    @Test void allRetriesFail_writesFailedLog() {
        for (int i = 0; i < 4; i++) server.enqueue(new MockResponse().setResponseCode(500));
        Alarm a = alarmRepo.findAll().get(0);
        channel.sendTriggered(a);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(deliveryRepo.findAll())
                        .anyMatch(d -> d.getStatus() == DeliveryStatus.FAILED && d.getAttempts() == 4));
    }
}
```

> **加测试依赖**：`ems-alarm/pom.xml` 加 `awaitility` 测试依赖（如果未引入）：

```xml
<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <version>4.2.2</version>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: 跑 IT + 提交**

```bash
./mvnw -pl ems-alarm test -Dtest=WebhookDispatcherIT
git add ems-alarm/src/test/java/com/ems/alarm/it/WebhookDispatcherIT.java ems-alarm/pom.xml
git commit -m "test(alarm): WebhookDispatcherIT — 2xx/重试/超时/全败 4 场景"
```

---

### Phase F — REST API

#### Task F1: AlarmController + DTO + AlarmService + Service Impl

**Files:**
- Create: `ems-alarm/src/main/java/com/ems/alarm/dto/{AlarmDTO, AlarmListItemDTO, HealthSummaryDTO}.java`
- Create: `ems-alarm/src/main/java/com/ems/alarm/service/AlarmService.java`
- Create: `ems-alarm/src/main/java/com/ems/alarm/service/impl/AlarmServiceImpl.java`
- Create: `ems-alarm/src/main/java/com/ems/alarm/controller/AlarmController.java`

- [ ] **Step 1: DTO（record）**

```java
public record AlarmListItemDTO(
        Long id, Long deviceId, String deviceCode, String deviceName,
        AlarmType alarmType, String severity, AlarmStatus status,
        OffsetDateTime triggeredAt, OffsetDateTime lastSeenAt, OffsetDateTime ackedAt) {}

public record AlarmDTO(
        Long id, Long deviceId, String deviceCode, String deviceName,
        AlarmType alarmType, String severity, AlarmStatus status,
        OffsetDateTime triggeredAt, OffsetDateTime ackedAt, Long ackedBy,
        OffsetDateTime resolvedAt, ResolvedReason resolvedReason,
        OffsetDateTime lastSeenAt, Map<String, Object> detail) {}

public record HealthSummaryDTO(
        long onlineCount, long offlineCount, long alarmCount, long maintenanceCount,
        List<TopOffender> topOffenders) {
    public record TopOffender(Long deviceId, String deviceCode, long activeAlarmCount) {}
}
```

- [ ] **Step 2: AlarmService 接口 + Impl**

```java
public interface AlarmService {
    PageDTO<AlarmListItemDTO> list(AlarmStatus status, Long deviceId, AlarmType type,
                                   OffsetDateTime from, OffsetDateTime to,
                                   int page, int size);
    AlarmDTO getById(Long id);
    void ack(Long id, Long userId);
    void resolve(Long id);
    long countActive();
    HealthSummaryDTO healthSummary();
}
```

实现委托：`AlarmRepository.search()` + `AlarmStateMachine` + `MeterRepository` 反查 code/name + `CollectorService.snapshots()` 算 healthSummary。

- [ ] **Step 3: Controller**

```java
@RestController
@RequestMapping("/api/v1/alarms")
public class AlarmController {

    private final AlarmService service;

    public AlarmController(AlarmService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Result<PageDTO<AlarmListItemDTO>> list(
            @RequestParam(required = false) AlarmStatus status,
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) AlarmType alarmType,
            @RequestParam(required = false) @DateTimeFormat(iso = DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(service.list(status, deviceId, alarmType, from, to, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Result<AlarmDTO> getById(@PathVariable Long id) {
        return Result.ok(service.getById(id));
    }

    @PostMapping("/{id}/ack")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "ACK", resourceType = "ALARM", resourceIdExpr = "#id")
    public Result<Void> ack(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal user) {
        service.ack(id, user.getId());
        return Result.ok();
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "RESOLVE", resourceType = "ALARM", resourceIdExpr = "#id")
    public Result<Void> resolve(@PathVariable Long id) {
        service.resolve(id);
        return Result.ok();
    }

    @GetMapping("/active/count")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Result<Map<String, Long>> activeCount() {
        return Result.ok(Map.of("count", service.countActive()));
    }

    @GetMapping("/health-summary")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Result<HealthSummaryDTO> healthSummary() {
        return Result.ok(service.healthSummary());
    }
}
```

> **`UserPrincipal` 类型**：先 `grep -rn "class UserPrincipal\|interface UserPrincipal" ems-auth/`，确认包名 + getId() 方法签名。

- [ ] **Step 4: 1-indexed 分页转换**

`AlarmServiceImpl.list()` 内：`PageRequest.of(page - 1, size, Sort.by("triggeredAt").descending())`。

- [ ] **Step 5: 提交**

```bash
git add ems-alarm/src/main/java/com/ems/alarm/dto/ \
        ems-alarm/src/main/java/com/ems/alarm/service/AlarmService.java \
        ems-alarm/src/main/java/com/ems/alarm/service/impl/AlarmServiceImpl.java \
        ems-alarm/src/main/java/com/ems/alarm/controller/AlarmController.java
git commit -m "feat(alarm): AlarmController + AlarmService — 6 端点"
```

#### Task F2: AlarmRuleController

**Files:**
- Create: `ems-alarm/src/main/java/com/ems/alarm/controller/AlarmRuleController.java`
- Create: `ems-alarm/src/main/java/com/ems/alarm/dto/{DefaultsDTO, OverrideRequestDTO}.java`

- [ ] **Step 1: DTO**

```java
public record DefaultsDTO(int silentTimeoutSeconds, int consecutiveFailCount, int suppressionWindowSeconds) {}

public record OverrideRequestDTO(
        @Positive Integer silentTimeoutSeconds,    // null = 沿用全局
        @Positive Integer consecutiveFailCount,
        boolean maintenanceMode,
        @Size(max = 255) String maintenanceNote) {}
```

- [ ] **Step 2: 5 端点**

```java
@RestController
@RequestMapping("/api/v1/alarm-rules")
public class AlarmRuleController {

    private final AlarmProperties props;
    private final AlarmRuleOverrideRepository repo;

    @GetMapping("/defaults") @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Result<DefaultsDTO> defaults() {
        return Result.ok(new DefaultsDTO(
                props.defaultSilentTimeoutSeconds(),
                props.defaultConsecutiveFailCount(),
                props.suppressionWindowSeconds()));
    }

    @GetMapping("/overrides") @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Result<List<AlarmRuleOverride>> listOverrides() {
        return Result.ok(repo.findAll());
    }

    @GetMapping("/overrides/{deviceId}") @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Result<AlarmRuleOverride> getOverride(@PathVariable Long deviceId) {
        return Result.ok(repo.findById(deviceId)
                .orElseThrow(() -> new NotFoundException("AlarmRuleOverride", deviceId)));
    }

    @PutMapping("/overrides/{deviceId}") @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "UPDATE_OVERRIDE", resourceType = "ALARM_RULE", resourceIdExpr = "#deviceId")
    public Result<AlarmRuleOverride> setOverride(@PathVariable Long deviceId,
                                                 @Valid @RequestBody OverrideRequestDTO req,
                                                 @AuthenticationPrincipal UserPrincipal user) {
        AlarmRuleOverride o = repo.findById(deviceId).orElseGet(AlarmRuleOverride::new);
        o.setDeviceId(deviceId);
        o.setSilentTimeoutSeconds(req.silentTimeoutSeconds());
        o.setConsecutiveFailCount(req.consecutiveFailCount());
        o.setMaintenanceMode(req.maintenanceMode());
        o.setMaintenanceNote(req.maintenanceNote());
        o.setUpdatedAt(OffsetDateTime.now());
        o.setUpdatedBy(user.getId());
        return Result.ok(repo.save(o));
    }

    @DeleteMapping("/overrides/{deviceId}") @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "DELETE_OVERRIDE", resourceType = "ALARM_RULE", resourceIdExpr = "#deviceId")
    public Result<Void> deleteOverride(@PathVariable Long deviceId) {
        repo.deleteById(deviceId);
        return Result.ok();
    }
}
```

- [ ] **Step 3: 提交**

```bash
git add ems-alarm/src/main/java/com/ems/alarm/controller/AlarmRuleController.java \
        ems-alarm/src/main/java/com/ems/alarm/dto/DefaultsDTO.java \
        ems-alarm/src/main/java/com/ems/alarm/dto/OverrideRequestDTO.java
git commit -m "feat(alarm): AlarmRuleController — 默认值只读 + 设备覆盖 CRUD"
```

#### Task F3: WebhookController

**Files:**
- Create: `ems-alarm/src/main/java/com/ems/alarm/controller/WebhookController.java`
- Create: `ems-alarm/src/main/java/com/ems/alarm/dto/{WebhookConfigDTO, WebhookConfigRequestDTO, WebhookTestResultDTO, DeliveryLogDTO}.java`

- [ ] **Step 1: DTO**

```java
public record WebhookConfigDTO(
        boolean enabled, String url, String secret /* "***" */, String adapterType, int timeoutMs,
        OffsetDateTime updatedAt) {}

public record WebhookConfigRequestDTO(
        boolean enabled,
        @NotBlank @Size(max = 512) String url,
        @Size(max = 255) String secret,
        @Size(max = 32) String adapterType,        // null → "GENERIC_JSON"
        @Min(1000) @Max(30000) int timeoutMs) {}

public record WebhookTestResultDTO(int statusCode, long durationMs, String error) {}

public record DeliveryLogDTO(
        Long id, Long alarmId, int attempts, DeliveryStatus status,
        String lastError, Integer responseStatus, Integer responseMs,
        OffsetDateTime createdAt) {}
```

- [ ] **Step 2: Controller（5 端点）**

```java
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasRole('ADMIN')")
public class WebhookController {

    private final WebhookConfigRepository cfgRepo;
    private final WebhookDeliveryLogRepository deliveryRepo;
    private final WebhookChannel webhookChannel;
    // ctor

    @GetMapping("/webhook-config")
    public Result<WebhookConfigDTO> get() {
        WebhookConfig cfg = cfgRepo.findFirstByOrderByIdAsc().orElseGet(WebhookConfig::new);
        return Result.ok(new WebhookConfigDTO(
                cfg.isEnabled(), cfg.getUrl(),
                cfg.getSecret() == null || cfg.getSecret().isEmpty() ? "" : "***",
                cfg.getAdapterType(), cfg.getTimeoutMs(), cfg.getUpdatedAt()));
    }

    @PutMapping("/webhook-config")
    @Audited(action = "UPDATE_WEBHOOK", resourceType = "WEBHOOK_CONFIG")
    public Result<WebhookConfigDTO> update(@Valid @RequestBody WebhookConfigRequestDTO req,
                                           @AuthenticationPrincipal UserPrincipal user) {
        validateUrl(req.url());
        WebhookConfig cfg = cfgRepo.findFirstByOrderByIdAsc().orElseGet(WebhookConfig::new);
        cfg.setEnabled(req.enabled());
        cfg.setUrl(req.url());
        if (req.secret() != null) cfg.setSecret(req.secret());
        cfg.setAdapterType(req.adapterType() == null ? "GENERIC_JSON" : req.adapterType());
        cfg.setTimeoutMs(req.timeoutMs());
        cfg.setUpdatedAt(OffsetDateTime.now());
        cfg.setUpdatedBy(user.getId());
        WebhookConfig saved = cfgRepo.save(cfg);
        return Result.ok(toDto(saved));
    }

    @PostMapping("/webhook-config/test")
    public Result<WebhookTestResultDTO> test(@Valid @RequestBody WebhookConfigRequestDTO req) {
        validateUrl(req.url());
        WebhookConfig probe = new WebhookConfig();
        probe.setUrl(req.url());
        probe.setSecret(req.secret());
        probe.setAdapterType(req.adapterType() == null ? "GENERIC_JSON" : req.adapterType());
        probe.setTimeoutMs(req.timeoutMs());
        Alarm sample = sampleAlarm();   // helper builds an in-memory non-persisted alarm
        var r = webhookChannel.test(probe, sample, "M-TEST", "Test Meter");
        return Result.ok(new WebhookTestResultDTO(r.statusCode(), r.durationMs(), r.error()));
    }

    @GetMapping("/webhook-deliveries")
    public Result<PageDTO<DeliveryLogDTO>> deliveries(@RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        Page<WebhookDeliveryLog> p = deliveryRepo.findAllByOrderByCreatedAtDesc(
                PageRequest.of(page - 1, size));
        return Result.ok(/* map to DeliveryLogDTO + PageDTO */);
    }

    @PostMapping("/webhook-deliveries/{id}/retry")
    @Audited(action = "RETRY_DELIVERY", resourceType = "WEBHOOK_DELIVERY", resourceIdExpr = "#id")
    public Result<Void> retry(@PathVariable Long id) {
        webhookChannel.retryDelivery(id);
        return Result.ok();
    }

    private void validateUrl(String url) {
        try {
            URI uri = URI.create(url);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new WebhookConfigInvalidException("url scheme must be http or https");
            }
        } catch (IllegalArgumentException e) {
            throw new WebhookConfigInvalidException("invalid url: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 3: 提交**

```bash
git add ems-alarm/src/main/java/com/ems/alarm/controller/WebhookController.java \
        ems-alarm/src/main/java/com/ems/alarm/dto/Webhook*.java \
        ems-alarm/src/main/java/com/ems/alarm/dto/DeliveryLogDTO.java
git commit -m "feat(alarm): WebhookController — 配置 CRUD + 测试 + 重放"
```

#### Task F4: AlarmApiIT — 端到端 API 测试

**Files:**
- Create: `ems-alarm/src/test/java/com/ems/alarm/it/AlarmApiIT.java`

- [ ] **Step 1: 写测试（MockMvc + Testcontainers + Spring Security）**

```java
@SpringBootTest @AutoConfigureMockMvc @Testcontainers
class AlarmApiIT {
    @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) { /* ... */ }

    @Autowired MockMvc mvc;
    @Autowired AlarmRepository alarmRepo;

    @Test @WithMockUser(roles = "ADMIN")
    void list_pagination_returnsExpected() throws Exception {
        // seed 5 alarms
        mvc.perform(get("/api/v1/alarms?status=ACTIVE&page=1&size=20"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.items").isArray())
           .andExpect(jsonPath("$.data.page").value(1));
    }

    @Test @WithMockUser(roles = "OPERATOR")
    void ack_forbiddenForOperator_returns403() throws Exception {
        Alarm a = newActive();
        mvc.perform(post("/api/v1/alarms/" + a.getId() + "/ack"))
           .andExpect(status().isForbidden());
    }

    @Test @WithMockUser(roles = "ADMIN")
    void ack_alreadyResolved_returns409() throws Exception {
        Alarm a = newResolved();
        mvc.perform(post("/api/v1/alarms/" + a.getId() + "/ack"))
           .andExpect(status().isConflict());
    }

    @Test @WithMockUser(roles = "ADMIN")
    void getById_notFound_returns404() throws Exception {
        mvc.perform(get("/api/v1/alarms/999999"))
           .andExpect(status().isNotFound());
    }

    @Test @WithMockUser(roles = "ADMIN")
    void putWebhookConfig_invalidScheme_returns400() throws Exception {
        String body = """
                {"enabled":true,"url":"ftp://nope","timeoutMs":5000}
                """;
        mvc.perform(put("/api/v1/webhook-config").contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isBadRequest());
    }

    @Test
    void list_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/v1/alarms"))
           .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: 跑测试 + 提交**

```bash
./mvnw -pl ems-alarm test -Dtest=AlarmApiIT
git add ems-alarm/src/test/java/com/ems/alarm/it/AlarmApiIT.java
git commit -m "test(alarm): AlarmApiIT — 全 REST 端点 happy path + 权限 + 状态码"
```

---

### Phase G — 前端

#### Task G1: API 客户端 src/api/alarm.ts

**Files:**
- Create: `frontend/src/api/alarm.ts`

- [ ] **Step 1: 写 API 客户端（参考 floorplan.ts / meter.ts 风格）**

```typescript
import { http } from '@/api/http';

export type AlarmStatus = 'ACTIVE' | 'ACKED' | 'RESOLVED';
export type AlarmType = 'SILENT_TIMEOUT' | 'CONSECUTIVE_FAIL';
export type DeliveryStatus = 'SUCCESS' | 'FAILED';

export interface AlarmListItemDTO {
  id: number;
  deviceId: number;
  deviceCode: string;
  deviceName: string;
  alarmType: AlarmType;
  severity: string;
  status: AlarmStatus;
  triggeredAt: string;
  lastSeenAt: string | null;
  ackedAt: string | null;
}

export interface AlarmDTO extends AlarmListItemDTO {
  ackedBy: number | null;
  resolvedAt: string | null;
  resolvedReason: 'AUTO' | 'MANUAL' | null;
  detail: Record<string, unknown> | null;
}

export interface OverrideRequest {
  silentTimeoutSeconds?: number | null;
  consecutiveFailCount?: number | null;
  maintenanceMode: boolean;
  maintenanceNote?: string | null;
}

export interface WebhookConfigRequest {
  enabled: boolean;
  url: string;
  secret?: string;
  adapterType?: string;
  timeoutMs: number;
}

export interface HealthSummaryDTO {
  onlineCount: number;
  offlineCount: number;
  alarmCount: number;
  maintenanceCount: number;
  topOffenders: Array<{ deviceId: number; deviceCode: string; activeAlarmCount: number }>;
}

export const alarmApi = {
  list: (params: { status?: AlarmStatus; deviceId?: number; alarmType?: AlarmType; from?: string; to?: string; page?: number; size?: number }) =>
    http.get('/api/v1/alarms', { params }).then((r) => r.data.data),
  getById: (id: number) => http.get(`/api/v1/alarms/${id}`).then((r) => r.data.data),
  ack:     (id: number) => http.post(`/api/v1/alarms/${id}/ack`),
  resolve: (id: number) => http.post(`/api/v1/alarms/${id}/resolve`),
  activeCount:   () => http.get('/api/v1/alarms/active/count').then((r) => r.data.data.count as number),
  healthSummary: () => http.get('/api/v1/alarms/health-summary').then((r) => r.data.data as HealthSummaryDTO),
};

export const alarmRuleApi = {
  getDefaults:   () => http.get('/api/v1/alarm-rules/defaults').then((r) => r.data.data),
  listOverrides: () => http.get('/api/v1/alarm-rules/overrides').then((r) => r.data.data),
  getOverride:   (deviceId: number) => http.get(`/api/v1/alarm-rules/overrides/${deviceId}`).then((r) => r.data.data),
  setOverride:   (deviceId: number, req: OverrideRequest) => http.put(`/api/v1/alarm-rules/overrides/${deviceId}`, req).then((r) => r.data.data),
  clearOverride: (deviceId: number) => http.delete(`/api/v1/alarm-rules/overrides/${deviceId}`),
};

export const webhookApi = {
  get:    () => http.get('/api/v1/webhook-config').then((r) => r.data.data),
  update: (req: WebhookConfigRequest) => http.put('/api/v1/webhook-config', req).then((r) => r.data.data),
  test:   (req: WebhookConfigRequest) => http.post('/api/v1/webhook-config/test', req).then((r) => r.data.data),
  listDeliveries: (params: { page?: number; size?: number }) => http.get('/api/v1/webhook-deliveries', { params }).then((r) => r.data.data),
  retry:  (id: number) => http.post(`/api/v1/webhook-deliveries/${id}/retry`),
};
```

- [ ] **Step 2: lint + 提交**

```bash
cd frontend && pnpm lint
git add frontend/src/api/alarm.ts
git commit -m "feat(frontend): alarm API client（alarmApi/alarmRuleApi/webhookApi）"
```

#### Task G2: AlarmBell + AlarmCenterDrawer + AppLayout 集成

**Files:**
- Create: `frontend/src/components/AlarmBell.tsx`
- Create: `frontend/src/components/AlarmCenterDrawer.tsx`
- Modify: `frontend/src/layouts/AppLayout.tsx`（具体路径 grep `Layout` + `Header` 定位）

- [ ] **Step 1: AlarmBell（30s 轮询 + 角标）**

```tsx
import { Badge, Button } from 'antd';
import { BellOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { alarmApi } from '@/api/alarm';
import { AlarmCenterDrawer } from './AlarmCenterDrawer';

export function AlarmBell() {
  const [open, setOpen] = useState(false);
  const { data: count = 0 } = useQuery({
    queryKey: ['alarms', 'active-count'],
    queryFn: () => alarmApi.activeCount(),
    refetchInterval: 30_000,
  });
  return (
    <>
      <Badge count={count} overflowCount={99}>
        <Button type="text" icon={<BellOutlined style={{ fontSize: 18 }} />} onClick={() => setOpen(true)} />
      </Badge>
      <AlarmCenterDrawer open={open} onClose={() => setOpen(false)} />
    </>
  );
}
```

- [ ] **Step 2: AlarmCenterDrawer**

```tsx
import { Drawer, List, Tag, Button, Space, App } from 'antd';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { alarmApi, type AlarmListItemDTO } from '@/api/alarm';

export function AlarmCenterDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { message } = App.useApp();
  const nav = useNavigate();
  const qc = useQueryClient();
  const { data } = useQuery({
    queryKey: ['alarms', 'recent-active'],
    queryFn: () => alarmApi.list({ status: 'ACTIVE', page: 1, size: 20 }),
    enabled: open,
  });
  const ack = useMutation({
    mutationFn: alarmApi.ack,
    onSuccess: () => { message.success('已确认'); qc.invalidateQueries({ queryKey: ['alarms'] }); },
  });

  return (
    <Drawer title="告警中心" open={open} onClose={onClose} width={420}>
      <List
        dataSource={data?.items ?? []}
        renderItem={(a: AlarmListItemDTO) => (
          <List.Item
            actions={[
              <Button key="ack" size="small" onClick={() => ack.mutate(a.id)}>确认</Button>,
              <Button key="go" type="link" size="small"
                      onClick={() => { nav(`/alarms/history?id=${a.id}`); onClose(); }}>详情</Button>,
            ]}
          >
            <List.Item.Meta
              title={<Space><Tag color="error">告警</Tag>{a.deviceCode} — {a.deviceName}</Space>}
              description={`${a.alarmType} · ${a.triggeredAt}`}
            />
          </List.Item>
        )}
      />
    </Drawer>
  );
}
```

- [ ] **Step 3: AppLayout 集成**

```bash
grep -rn "AppLayout\|<Header" frontend/src/layouts/
```

定位现有顶栏文件，在用户头像之前插入 `<AlarmBell />`。

- [ ] **Step 4: 提交**

```bash
cd frontend && pnpm lint && pnpm build
git add frontend/src/components/Alarm*.tsx frontend/src/layouts/AppLayout.tsx
git commit -m "feat(frontend): AlarmBell + AlarmCenterDrawer + AppLayout 集成"
```

#### Task G3: 健康总览页 `/alarms/health` + 路由 + 菜单

**Files:**
- Create: `frontend/src/pages/alarms/health.tsx`
- Modify: `frontend/src/routes/index.tsx`（或具体路由文件，grep 定位）+ menu 配置

- [ ] **Step 1: 健康总览页**

```tsx
import { Card, Col, Row, Statistic, Table } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { alarmApi } from '@/api/alarm';

export default function AlarmHealthPage() {
  const { data: summary } = useQuery({
    queryKey: ['alarms', 'health'],
    queryFn: alarmApi.healthSummary,
    refetchInterval: 30_000,
  });
  return (
    <div>
      <Row gutter={16}>
        <Col span={6}><Card><Statistic title="在线设备" value={summary?.onlineCount ?? 0} valueStyle={{ color: '#52c41a' }} /></Card></Col>
        <Col span={6}><Card><Statistic title="离线设备" value={summary?.offlineCount ?? 0} valueStyle={{ color: '#999' }} /></Card></Col>
        <Col span={6}><Card><Statistic title="告警中"   value={summary?.alarmCount ?? 0}  valueStyle={{ color: '#ff4d4f' }} /></Card></Col>
        <Col span={6}><Card><Statistic title="维护中"   value={summary?.maintenanceCount ?? 0} valueStyle={{ color: '#faad14' }} /></Card></Col>
      </Row>
      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col span={24}>
          <Card title="Top 10 异常设备">
            <Table
              rowKey="deviceId"
              dataSource={summary?.topOffenders ?? []}
              columns={[
                { title: '设备编码', dataIndex: 'deviceCode' },
                { title: '活动告警数', dataIndex: 'activeAlarmCount', align: 'right' },
              ]}
              pagination={false}
            />
          </Card>
        </Col>
      </Row>
    </div>
  );
}
```

> **24h 时序图首版省略**：如果时间紧，只放上面 4 卡 + Top10 表 ship。后续迭代加 LineChart。

- [ ] **Step 2: 路由 + 菜单注册**

`grep -rn "createBrowserRouter\|RouterProvider\|<Route " frontend/src/` 定位路由表，加 `/alarms/health`、`/alarms/history`、`/alarms/rules`、`/alarms/webhook` 4 个 lazy route，并在菜单配置中加"系统健康"分组。

- [ ] **Step 3: 提交**

```bash
cd frontend && pnpm lint && pnpm build
git add frontend/src/pages/alarms/health.tsx frontend/src/routes frontend/src/menu/...
git commit -m "feat(frontend): 健康总览页 + 系统健康菜单分组 + 路由"
```

#### Task G4: 告警历史页 `/alarms/history`

**Files:**
- Create: `frontend/src/pages/alarms/history.tsx`

- [ ] **Step 1: 列表 + 筛选**

参考 `pages/floorplan/list.tsx` 风格：上方 Form 筛选条 + 下方 Table。

字段：状态 / 设备 / 类型 / 时间范围。Table 列：触发时间 / 设备 / 类型 / 状态 / 持续时长 / 操作。
操作：详情 + 确认（ACTIVE 时）+ 手动恢复（ACTIVE/ACKED 时）。

详情用 `Modal`，调 `alarmApi.getById(id)`。

- [ ] **Step 2: 提交**

```bash
git add frontend/src/pages/alarms/history.tsx
git commit -m "feat(frontend): 告警历史页（筛选 + 详情/确认/恢复）"
```

#### Task G5: 阈值规则页 `/alarms/rules`

**Files:**
- Create: `frontend/src/pages/alarms/rules.tsx`

- [ ] **Step 1: 默认值卡（只读）+ 设备覆盖 Table + 编辑 Modal**

```tsx
// 默认值卡：调 alarmRuleApi.getDefaults() 显示 silent / fail / suppression
// 设备覆盖 Table：alarmRuleApi.listOverrides() + 操作列编辑/清除
// 编辑 Modal: deviceId 必填，三个数字字段可空（"沿用默认"），维护模式 Switch + 备注 textarea
//   保存调 alarmRuleApi.setOverride
```

- [ ] **Step 2: 提交**

#### Task G6: Webhook 配置页 `/alarms/webhook`

**Files:**
- Create: `frontend/src/pages/alarms/webhook.tsx`

- [ ] **Step 1: 表单 + 测试按钮 + 下发日志 Table**

```tsx
// Form 字段: enabled Switch / url Input / secret Password / adapterType Select(GENERIC_JSON) / timeoutMs Number
// "测试" 按钮：调 webhookApi.test() → Toast 显示 statusCode + duration
// "保存" 按钮：调 webhookApi.update()
// 下方 Table：webhookApi.listDeliveries() + 重放按钮调 webhookApi.retry(id)
```

- [ ] **Step 2: 提交**

#### Task G7: 设备列表 / 详情 / 仪表盘集成

**Files:**
- Modify: `frontend/src/pages/meter/list.tsx`
- Modify: `frontend/src/pages/meter/detail.tsx`
- Modify: `frontend/src/pages/dashboard/index.tsx`

- [ ] **Step 1: meter/list 加状态列（前端 join）**

```tsx
const STATUS_COLOR: Record<string, string> = {
  ONLINE: 'success', OFFLINE: 'default', ALARM: 'error', MAINTENANCE: 'warning',
};

// useQuery healthSummary → 用 topOffenders + 自有 alarmApi.list({ status: 'ACTIVE' }) 构建 deviceId → status map
// 给 meter Table 加列：
{ title: '状态', key: 'alarmStatus', render: (_, row) => {
    const s = statusMap[row.id] ?? 'ONLINE';
    return <Tag color={STATUS_COLOR[s]}>{s}</Tag>;
  } }
```

> **替代方案**：未来给后端 `/meters` API 加 `alarmStatus` 字段一次返回，避免前端 join。当前 YAGNI 保持前端 join。

- [ ] **Step 2: meter/detail 顶部状态徽章 + 最近 5 条告警 Timeline**

```tsx
// useQuery alarmApi.list({ deviceId, page: 1, size: 5 })
// 顶部 <Tag color={STATUS_COLOR[currentStatus]}>{label}</Tag>
// Timeline 渲染最近 5 条
```

- [ ] **Step 3: dashboard 加"采集健康"卡**

```tsx
const { data: summary } = useQuery({ queryKey: ['alarms', 'health'], queryFn: alarmApi.healthSummary });
const onlineRate = summary ? (summary.onlineCount / Math.max(1, summary.onlineCount + summary.offlineCount)) * 100 : 0;

<Card title="采集健康" hoverable onClick={() => nav('/alarms/health')}>
  <Statistic title="在线率" value={onlineRate.toFixed(1)} suffix="%" />
  <Statistic title="当前告警" value={summary?.alarmCount ?? 0} valueStyle={{ color: '#ff4d4f' }} />
</Card>
```

- [ ] **Step 4: 提交**

```bash
cd frontend && pnpm lint && pnpm build
git add frontend/src/pages/meter/list.tsx frontend/src/pages/meter/detail.tsx frontend/src/pages/dashboard/index.tsx
git commit -m "feat(frontend): 设备列表/详情/仪表盘 集成告警状态"
```

---

### Phase H — E2E + 文档 + 验收

#### Task H1: E2E 冒烟（Playwright）+ 验收日志

**Files:**
- Create: `e2e/tests/alarm-smoke.spec.ts`
- Create: `docs/ops/verification-2026-04-29-alarm.md`

- [ ] **Step 1: Playwright 4 个冒烟场景**

```ts
import { test, expect } from '@playwright/test';
import { login } from './helpers';

test('alarm: bell + history + webhook + maintenance', async ({ page }) => {
  await login(page, 'admin', 'admin123');

  // 1) 健康总览
  await page.goto('/alarms/health');
  await expect(page.getByText(/在线设备/)).toBeVisible();
  await expect(page.getByText(/告警中/)).toBeVisible();

  // 2) Webhook 配置 + 测试（指向 localhost:9999 期望失败）
  await page.goto('/alarms/webhook');
  await page.getByLabel('URL').fill('http://localhost:9999/');
  await page.getByLabel('Timeout').fill('1000');
  await page.getByRole('button', { name: /测试/ }).click();
  await expect(page.getByText(/(失败|失败|connection)/i)).toBeVisible({ timeout: 5000 });

  // 3) 阈值规则 — 设备 1 设 maintenance
  await page.goto('/alarms/rules');
  // ...

  // 4) 历史页
  await page.goto('/alarms/history');
  await expect(page.getByRole('table')).toBeVisible();
});
```

- [ ] **Step 2: 跑 E2E**

```bash
cd e2e && pnpm test -- alarm-smoke
```

- [ ] **Step 3: 写验收日志**

```markdown
# Verification — 采集中断告警 ems-alarm 2026-04-29

## 范围
- spec: docs/superpowers/specs/2026-04-29-acquisition-alarm-design.md
- plan: docs/superpowers/plans/2026-04-29-acquisition-alarm-plan.md

## 测试结果
- ✅ ./mvnw -pl ems-alarm test                  — 单测 25+ tests 全通过
- ✅ ./mvnw -pl ems-alarm verify                — IT 全通过（CI 跑；macOS 本地 -DskipITs）
- ✅ ./mvnw clean verify                        — 全模块绿
- ✅ pnpm lint && pnpm build                    — 0 errors
- ✅ pnpm test:e2e -- alarm-smoke               — 4 用例全通过
- ✅ 手工验证：关 collector → 等 10min → 铃铛角标 +1
- ✅ 手工验证：webhook URL 配 mock 端点 → 触发 → 接收方拿到带签名 payload
- ✅ 手工验证：设备恢复 → 5min 后 RESOLVED + 站内"已恢复"

## 性能基线
- 1000 设备 / 1 分钟一轮：实测 _____ ms（手工压测）

## 已知例外
- macOS docker-java 兼容问题，AlarmServiceIT/WebhookDispatcherIT/AlarmApiIT 本地需 -DskipITs。CI Linux 全绿。
```

- [ ] **Step 4: 提交**

```bash
git add e2e/tests/alarm-smoke.spec.ts docs/ops/verification-2026-04-29-alarm.md
git commit -m "test(e2e): alarm-smoke + 2026-04-29 验收日志"
```

#### Task H2: alarm-runbook + docs/ops/README 索引 + tag

**Files:**
- Create: `docs/ops/alarm-runbook.md`
- Modify: `docs/ops/README.md`

- [ ] **Step 1: alarm-runbook**

```markdown
# Alarm Runbook

## 配置 Webhook
1. 进入 系统健康 → Webhook 配置
2. 填 URL（必须 https / http）+ Secret（用于 HMAC 签名）+ Timeout
3. 点击 测试 → 应在 5s 内返回 2xx
4. 启用开关打开 → 保存

## 静默超时调优
- 默认 600s（10min）
- 单设备覆盖：系统健康 → 阈值规则 → 设备列表 → 编辑

## 排查 Webhook 失败
- 系统健康 → Webhook 配置 → 下发日志
- status=FAILED 的行 → 重放按钮
- 持续失败：检查接收方日志、X-EMS-Signature 校验、网络

## 维护期抑制
- 系统健康 → 阈值规则 → 设备 → 维护模式 ON + 备注（可选）
- ON 期间 alarm 完全跳过该设备

## 状态机
- 触发 → ACTIVE
- 用户 ACK → ACKED
- 数据恢复 + 距触发 > 5min → 自动 RESOLVED
- 手动 → RESOLVED（resolved_reason=MANUAL）

## 检测口径
- 静默超时：MAX(meter_reading.ts) 距 NOW() 超过阈值（默认 10min）
- 连续失败：collector consecutiveErrors ≥ 阈值（默认 3 次）
- 任一命中即触发；同设备同类型在 ACTIVE/ACKED 时不重复创建

## 已知限制
- 首版仅监控 ems-collector 配置的设备
- Webhook 重试用内存队列（进程崩溃丢失，靠下发日志手动重放）
- 单实例运行（多实例部署需引入 ShedLock）
```

- [ ] **Step 2: docs/ops/README.md 加索引**

参考既有格式，在 verification 列表里加 `verification-2026-04-29-alarm.md` 一行；runbook 列表里加 `alarm-runbook.md` 一行。

- [ ] **Step 3: 提交 + 打 tag**

```bash
git add docs/ops/alarm-runbook.md docs/ops/README.md
git commit -m "docs(ops): alarm runbook + 索引更新"
git tag v1.6.0-alarm
```

---

## 关键不变量（防回归 / 写给后续 plan）

- **alarms 表只由 AlarmDetector + AlarmStateMachine 写**：其他模块禁止直插，避免状态机被绕过
- **Webhook 失败永远不影响 alarms.status**：解耦原则
- **抑制窗口 5min 同时管"防再触发" + "防自动恢复抖动"**：两者共用同一参数
- **首版仅监控 collector 配置的设备**：mock 数据 / 直接 API 写入的设备不触发 alarm，符合"采集中断告警"语义
- **Webhook 重试在内存**：进程崩溃丢失重试，靠 delivery_log 兜底；商业化前可考虑持久化队列
- **`@Scheduled scan()` 单设备异常 catch + log，绝不抛出**：保持调度器存活
- **Flyway migrations 必须放 ems-app**：不允许 ems-alarm 自己放 migration

---

## 风险与待验证点

1. **Phase A spike**：MockWebServer 4.12.0 在 Java 21 + Spring Boot 3.3.4 是否有问题。如果有，回退 4.11.x 或换 wiremock。
2. **Phase D Clock 注入**：AlarmDetector 用 `Clock` 而非 `Instant.now()`；测试要用 `Clock.fixed`。生产 wire `Clock.systemUTC()`（已在 AlarmModuleConfig）。
3. **Phase E `@Async` 上下文**：Spring Security 的 `SecurityContext` 默认不传给 `@Async` 线程。webhook 派发不需要用户上下文，OK；但如果未来想审计触发者，要配 `DelegatingSecurityContextAsyncTaskExecutor`。
4. **Phase E 重试时 Alarm 实体已 detached**：`attemptDelivery` 拿到的 `Alarm` 跨线程；只读 getter，不要 lazy load。如果遇到 LazyInitializationException，加 `@Transactional(readOnly = true)` 或在初次 dispatch 时把 detail 物化。
5. **Phase F 1-indexed 分页**：Spring Data 是 0-indexed。Controller 接到 `page=1` 要传 `PageRequest.of(page - 1, size)` 给 repo。所有 controller 都要这样做（参考 ems-meter 既有 controller）。
6. **Phase G antd Form 重置**：阈值覆盖编辑 Modal 关闭时要 `form.resetFields()`，否则下次打开有脏数据。
7. **Phase G 状态色板覆盖**：meter 表的"状态"是计算列（join alarm 后），切换页面 / 排序时颜色要稳定。

---

## 验收

- 全后端 `./mvnw clean verify` exit 0（CI；macOS 本地 `-DskipITs`）
- `./mvnw -pl ems-alarm test` 0 失败，coverage ≥ 70%
- 集成测试 `AlarmServiceIT` + `WebhookDispatcherIT` + `AlarmApiIT` 全绿
- 前端 `pnpm lint && pnpm build` 0 错；4 个新页 + 4 处既有改动可用
- E2E `alarm-smoke.spec.ts` 全通过
- 关 collector → 10min 后铃铛角标 +1，列表出现告警，webhook 接收方拿到 payload
- 设备恢复 → 5min 后 AUTO RESOLVED
- 维护模式 ON → 设备完全静默
- `docs/ops/alarm-runbook.md` 让运维能照着配 webhook + 调阈值
- tag `v1.6.0-alarm` 打到 main

---

## 启动建议

第一会话：**Phase A + B 一气呵成**（模块骨架 + 实体 + Repo + Migration），完成后整个数据层稳定，剩下 Phase 都是业务逻辑。Phase D 是关键 spike — `CollectorService.snapshots()` 是否能从 ems-alarm 顺利注入（跨模块依赖），这一步过了风险就排除了。

---

## 附录：每 Phase 末文档任务详情

> **统一原则**：每个文档任务在对应 Phase 全部技术任务完成后立刻执行，不允许跨 Phase 累积。

### Task A5: 完成 docs/product/alarm-config-reference.md

**Files:**
- Modify: `docs/product/alarm-config-reference.md`（占位骨架已存在）

- [ ] **Step 1: 把 spec §12 的参数表完整复制到 §1**
  - 7 个全局参数全部列出，每行加 1 句调优建议
  - 标注哪些参数修改后需重启 ems-app

- [ ] **Step 2: §2 设备级覆盖**
  - 字段含义（沿用 spec §12.1）
  - 通过 UI（系统健康 → 阈值规则页）/ API（PUT `/alarm-rules/overrides/{deviceId}`）两种方式
  - 留空（NULL）= 沿用全局默认的行为

- [ ] **Step 3: §3 三个完整 YAML 场景**（直接 copy spec §12.3 的高可靠工控 / 一般工厂 / 低频采集 三段）

- [ ] **Step 4: §4 修改后的生效方式**
  - 全局 `application.yml` 改动 → 重启
  - 设备覆盖 → 立即生效（下一轮 scan 即应用，最长 60s 等待）
  - 维护模式开关 → 立即生效

- [ ] **Step 5: §5 校验失败处理**（搬 spec §15.3 的启动校验失败表）

- [ ] **Step 6: 删除文档末尾的"Phase A 任务清单"段**

- [ ] **Step 7: 提交**

```bash
git add docs/product/alarm-config-reference.md
git commit -m "docs(alarm): Phase A — 完成配置参数参考"
```

### Task B3: 完成 docs/product/alarm-data-model.md

**Files:**
- Modify: `docs/product/alarm-data-model.md`

- [ ] **Step 1: §1 表关系图**

```
                    ┌─────────────┐
                    │  meters     │  (既有)
                    └─────┬───────┘
                          │ id  ←──── alarms.device_id (软关联，无 FK)
                          │     ←──── alarm_rules_override.device_id
                          │
        ┌─────────────────┼─────────────────────────────┐
        │                 │                             │
   ┌────┴─────┐    ┌──────┴────────────┐         ┌─────┴──────┐
   │ alarms   │ ←──│ webhook_delivery_ │         │ alarm_inbox│
   └──────────┘    │       log         │         └─────┬──────┘
                   └───────────────────┘               │
                                                       │ user_id ──→ users (既有)
                   ┌──────────────────┐
                   │ webhook_config   │  (单行系统级)
                   └──────────────────┘
```

- [ ] **Step 2: §2-§6 五张表的完整字段词典**
  - 每张表逐字段说明（搬 spec §2.1）
  - 加业务含义列：每字段在业务流程中扮演什么角色
  - `webhook_config.secret` 必须明确标注为敏感数据

- [ ] **Step 3: §7 数据生命周期**
  - 各表保留策略：alarms 永久 / inbox 永久 / delivery_log 建议 90 天 / config 永久
  - 何时该归档（首版无归档机制，写到 ops backlog）

- [ ] **Step 4: §8 业务 SQL 5-10 条示例**
  - 当前所有 ACTIVE 告警按设备分组：`SELECT device_id, COUNT(*) FROM alarms WHERE status='ACTIVE' GROUP BY device_id;`
  - 最近 7 天告警 Top 10 设备
  - 某设备最近一次告警的完整生命周期
  - 24 小时内 Webhook 失败统计
  - 用户未读告警数
  - 维护模式中的设备列表
  - …

- [ ] **Step 5: 删除骨架文件末尾的"Phase B 任务清单"段**

- [ ] **Step 6: 提交**

```bash
git add docs/product/alarm-data-model.md
git commit -m "docs(alarm): Phase B — 完成数据模型说明"
```

### Task C4: 完成 docs/product/alarm-business-rules.md

**Files:**
- Modify: `docs/product/alarm-business-rules.md`

- [ ] **Step 1: §1-§2 状态机图 + 三状态详解**（搬 spec §14）

- [ ] **Step 2: §3 阈值解析规则**
  - 设备覆盖优先 → 全局默认回落
  - 部分覆盖示例（仅设了 silent_timeout 没设 fail_count，结果是什么）
  - 维护模式怎么算：`maintenanceMode=true` 即跳过

- [ ] **Step 3: §4 抑制窗口**
  - **双重作用**清楚解释：
    - (a) RESOLVED 后 5min 内不再触发同类型 → 防"上报一条又断"抖动
    - (b) ACTIVE 触发后 5min 内不允许 AUTO 恢复 → 防瞬时恢复造成 RESOLVED→再 ACTIVE 抖动
  - 时间线图示

- [ ] **Step 4: §5 维护模式完整流程**
  - 何时开（计划停机 / 设备搬迁 / 保养）
  - 何时关（计划结束）
  - 备注字段建议（写"原因 + 计划恢复时间 + 联系人"）
  - 期间错过的告警**不会补发**（明确告知）

- [ ] **Step 5: §6 边界场景表**（搬 spec §14.4）

- [ ] **Step 6: §7 用户应对场景手册**
  - 至少 5 个场景（设备真坏 / 网络抖动 / 计划停机 / 误开维护 / 重复触发）
  - 每场景：系统行为 + 用户应做什么

- [ ] **Step 7: 删除骨架"Phase C 任务清单"段**

- [ ] **Step 8: 提交**

```bash
git add docs/product/alarm-business-rules.md
git commit -m "docs(alarm): Phase C — 完成业务规则说明"
```

### Task D4: 完成 docs/product/alarm-detection-rules.md

**Files:**
- Modify: `docs/product/alarm-detection-rules.md`

- [ ] **Step 1: §1 触发条件总览**
  - 两种触发条件 OR 关系
  - 流程图（ASCII / Mermaid）

- [ ] **Step 2: §2 静默超时（SILENT_TIMEOUT）完整定义**
  - 判定逻辑：`NOW() - lastReadAt > threshold` 即触发
  - `lastReadAt IS NULL` 不触发
  - 阈值默认 600s，可全局/设备级覆盖
  - 调优建议：阈值 = 采集周期的 5-10 倍

- [ ] **Step 3: §3 连续失败（CONSECUTIVE_FAIL）**
  - 数据来源：collector `DevicePoller.consecutiveCycleErrors`（per-device 内存）
  - 清零时机：每次成功 read 即清零
  - 阈值默认 3 次，达到即触发
  - **collector 重启后清零**（影响章节 §6 详述）

- [ ] **Step 4: §4 检测节奏**
  - 默认 60 秒一轮
  - 单实例运行（多实例需 ShedLock，首版不引入）
  - 单设备耗时 < 5ms，1000 设备一轮 < 5s

- [ ] **Step 5: §5 不会触发告警的 5-7 个场景**
  - 设备从未上报：lastReadAt IS NULL
  - 维护模式 ON
  - 同设备同类型已 ACTIVE / ACKED
  - 抑制窗口内（RESOLVED 不到 5min）
  - 抑制窗口内（刚 ACTIVE 不到 5min，不会立即 AUTO 恢复）
  - 设备未在 collector 配置中（无 snapshot）
  - 阈值设置过大

- [ ] **Step 6: §6 collector 重启的检测行为**
  - SILENT_TIMEOUT 不受影响（依赖 lastReadAt，重启后仍能读到旧值）
  - CONSECUTIVE_FAIL 影响：内存计数清零，需重新积累 N 个失败周期才会触发

- [ ] **Step 7: §7 监控覆盖范围**
  - 首版仅监控 `CollectorService.snapshots()` 返回的设备
  - 通过 mock-data CLI / 直接 API 写入的设备**不**监控
  - 理由：alarm 模块不查 InfluxDB，只读 collector 内存

- [ ] **Step 8: §8 故障排查决策树**
  - 至少 3 个常见客户问题 + 排查步骤：
    - "设备掉线了但没收到告警？"
    - "告警刚自动恢复又重新触发？"
    - "维护模式期间还在产生告警？"

- [ ] **Step 9: 删除骨架"Phase D 任务清单"段**

- [ ] **Step 10: 提交**

```bash
git add docs/product/alarm-detection-rules.md
git commit -m "docs(alarm): Phase D — 完成检测规则说明"
```

### Task E6: 完成 docs/product/alarm-webhook-integration.md

**Files:**
- Modify: `docs/product/alarm-webhook-integration.md`

- [ ] **Step 1: §1 适用场景**
  - 何时需要 webhook（IM 推送 / 工单系统 / 告警平台）
  - 替代方案（仅站内通知）

- [ ] **Step 2: §2 配置 Webhook UI 流程**
  - 截图占位 5 张：webhook 配置页 / 字段表单 / 测试按钮 / 成功响应 / 启用开关
  - 字段说明（搬 spec §16）

- [ ] **Step 3: §3 Payload 完整字段词典**（搬 spec §13.1）

- [ ] **Step 4: §4 HTTP Headers**（搬 spec §13.2）

- [ ] **Step 5: §5 接收方实现要点**
  - 验签（必须）
  - 幂等去重（按 alarm_id + event）
  - 路由（按 alarm_type 分流）
  - 状态码（2xx 表示已接受；5xx 触发 EMS 重试）

- [ ] **Step 6: §6 对接示例（3 个完整）**

  **6.1 钉钉机器人**：转换 → 中间适配层架构图（Mermaid）+ 适配层 Python 脚本完整代码

  **6.2 企微机器人**：类似钉钉

  **6.3 自定义后端 Python 完整代码**

```python
# Flask 接收器示例
import hmac, hashlib
from flask import Flask, request, jsonify

app = Flask(__name__)
SECRET = "your-secret-here"
processed = set()  # 简化幂等：生产用 Redis/DB

@app.post('/ems-webhook')
def receive():
    body = request.get_data()
    sig_header = request.headers.get('X-EMS-Signature', '')
    expected = "sha256=" + hmac.new(SECRET.encode(), body, hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, sig_header):
        return jsonify(error="bad signature"), 403

    payload = request.json
    key = (payload['alarm_id'], payload['event'])
    if key in processed:
        return jsonify(status="duplicate"), 200
    processed.add(key)

    # 业务处理 ...
    return jsonify(status="ok"), 200
```

- [ ] **Step 7: §7 重试与失败处理**
  - 3 次重试 + 指数退避 [10s, 60s, 300s]
  - 失败后 delivery_log 记录
  - UI 手动重放

- [ ] **Step 8: §8 测试 Webhook**
  - UI"发送测试"按钮行为
  - 用 webhook.site / ngrok 验证步骤

- [ ] **Step 9: §9 安全建议**
  - secret 强度（≥ 32 字符随机）
  - HTTPS 推荐
  - IP 白名单（接收方）
  - 接收方鉴权

- [ ] **Step 10: §10 故障排查**（搬 spec §15.2）

- [ ] **Step 11: §11 新增 Adapter 步骤**（搬 spec §13.4）

- [ ] **Step 12: 多语言验签代码**

```python
# Python: hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()
```
```javascript
// Node.js
const crypto = require('crypto');
const expected = 'sha256=' + crypto.createHmac('sha256', secret).update(body).digest('hex');
```
```java
// Java
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
String sig = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
```

- [ ] **Step 13: 删除骨架"Phase E 任务清单"段**

- [ ] **Step 14: 提交**

```bash
git add docs/product/alarm-webhook-integration.md
git commit -m "docs(alarm): Phase E — 完成 Webhook 接入指南（含 3 对接示例 + 多语言验签）"
```

### Task F5: 完成 docs/api/alarm-api.md

**Files:**
- Modify: `docs/api/alarm-api.md`

- [ ] **Step 1: §1.1-§1.6 告警操作类 6 端点**

每个端点完整写出：
- HTTP method + path
- 鉴权角色（搬 spec §11.1）
- Query 参数表（每个参数：名称 / 类型 / 必填 / 默认 / 说明）
- 请求体（如有）
- 响应 Schema
- curl 完整示例
- 常见错误响应（404 / 409 / 403）

示例（GET `/alarms`）：

```bash
curl -X GET 'http://localhost:8080/api/v1/alarms?status=ACTIVE&page=1&size=20' \
  -H 'Authorization: Bearer <TOKEN>'
```

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "id": 12345,
        "deviceId": 88,
        "deviceCode": "M-A01-001",
        "deviceName": "一号车间总表",
        "alarmType": "SILENT_TIMEOUT",
        "severity": "WARNING",
        "status": "ACTIVE",
        "triggeredAt": "2026-04-29T08:15:30+08:00",
        "lastSeenAt": "2026-04-29T08:00:12+08:00",
        "ackedAt": null
      }
    ],
    "total": 3,
    "page": 1,
    "size": 20
  }
}
```

- [ ] **Step 2: §2.1-§2.5 阈值规则类 5 端点**（同样风格）

- [ ] **Step 3: §3.1-§3.5 Webhook 配置类 5 端点**
  - 注意 §3.1 的响应中 `secret` 字段 mask 为 `"***"`
  - §3.2 的 400 错误示例（invalid scheme / timeout out of range）

- [ ] **Step 4: §4 错误码完整表**（搬 spec §15.1）

- [ ] **Step 5: §5 DTO Schema**（OpenAPI 3.0 风格 YAML 块）

- [ ] **Step 6: §6 客户端集成提示**
  - axios 拦截器统一处理 errorMsg
  - 错误码 → Toast 模式

- [ ] **Step 7: 删除骨架"Phase F 任务清单"段**

- [ ] **Step 8: 提交**

```bash
git add docs/api/alarm-api.md
git commit -m "docs(alarm): Phase F — 完成 alarm-api.md 16 端点完整规约"
```

### Task G8: 完成 docs/product/alarm-user-guide.md

**Files:**
- Modify: `docs/product/alarm-user-guide.md`

- [ ] **Step 1: §1 角色对照表**（搬 spec §11.1，简化为用户友好）

- [ ] **Step 2: §2 操作员视角 3 个场景**
  - 看到铃铛 +1 该怎么办（5 步流程 + 4 张截图占位）
  - 看告警历史（筛选 / 排序 / 详情）
  - 看设备实时状态（设备列表的状态色 / 设备详情的最近告警时间线）

- [ ] **Step 3: §3 管理员视角 5 个场景**
  - 处理一条新告警（铃铛 → 抽屉 → 确认 → 自动恢复）
  - 给某台设备单独配置阈值（含截图占位）
  - 维护期开维护模式（含备注示例）
  - 配置 Webhook（链接到 alarm-webhook-integration.md）
  - 看健康总览（4 卡 + Top10 + 何时该警觉）

- [ ] **Step 4: §4 FAQ 至少 8 条**
  - 为什么我没收到告警？
  - 告警自动恢复了，我之前确认是不是白做了？
  - 维护模式期间错过的告警还会补发吗？
  - 可以批量确认告警吗？
  - 同一设备同时段多个告警？
  - 我离职了，账号删除后我处理过的告警还能查到吗？
  - 改了阈值多久生效？
  - Webhook 接收方是钉钉，要怎么接？

- [ ] **Step 5: §5 快捷操作**
  - 直达 URL：`/alarms/history?id=123` 直接打开某告警详情
  - （键盘快捷键首版无）

- [ ] **Step 6: §6 术语词汇表**
  - 告警 / 静默超时 / 连续失败 / 抑制窗口 / 维护模式 / 站内通知 / Webhook / HMAC 签名

- [ ] **Step 7: 删除骨架"Phase G 任务清单"段**

- [ ] **Step 8: 提交**

```bash
git add docs/product/alarm-user-guide.md
git commit -m "docs(alarm): Phase G — 完成用户使用手册（管理员/操作员视角 + FAQ）"
```

### Task H3: 文档总收尾 + alarm-runbook + 索引联动

**Files:**
- Modify: `docs/product/alarm-feature-overview.md`
- Create: `docs/ops/alarm-runbook.md`（已在 H2 计划中，此处明确列出）
- Modify: `docs/ops/README.md`
- Modify: `docs/product/README.md`（更新链接状态：占位 → 已完成）
- Modify: `docs/api/README.md`（同上）

- [ ] **Step 1: 完成 alarm-feature-overview.md**
  - §1 一句话价值：从销售视角写
  - §2 解决什么问题：3-5 个客户痛点
  - §3 核心功能：3-5 bullet
  - §4 适用场景：3 个典型场景
  - §5 不在范围：搬 spec §8.2
  - §6 与其他模块的关系：和 ems-collector / ems-meter 协作图
  - 删除骨架"Phase H 任务清单"段

- [ ] **Step 2: 完成 docs/ops/alarm-runbook.md**（已在 Task H2 中定义内容大纲，此处合并执行）

- [ ] **Step 3: 更新 docs/ops/README.md 索引**
  - "Verification Reports" 段加 `verification-2026-04-29-alarm.md` 一行
  - "Runbooks" 段加 `alarm-runbook.md` 一行
  - 在合适位置加新段「Product Docs」指向 `docs/product/`
  - 加新段「API Docs」指向 `docs/api/`

- [ ] **Step 4: 更新 docs/product/README.md**
  - 把每行的 "Phase 负责人" 列改为 "✅ 已完成"
  - 加版本与日期信息（v1.6.0 / 2026-XX-XX）

- [ ] **Step 5: 更新 docs/api/README.md**
  - 同上：alarm-api.md 状态改 ✅

- [ ] **Step 6: 提交**

```bash
git add docs/product/alarm-feature-overview.md docs/product/README.md \
        docs/api/README.md docs/ops/README.md docs/ops/alarm-runbook.md
git commit -m "docs(alarm): Phase H — 文档总收尾（功能概览 + runbook + 全索引联动）"
```

---

## 文档完整性检查清单（Phase H 最后做）

每个文件末尾不应再含"占位骨架"或"Phase X 任务清单"段。

```bash
# 检查残留占位符
grep -rn "占位骨架\|Phase.*任务清单\|（待 Phase" docs/product/ docs/api/
# 期望：无输出
```
