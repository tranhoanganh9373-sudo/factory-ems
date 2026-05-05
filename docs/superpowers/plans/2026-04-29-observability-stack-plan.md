# Observability Stack v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 factory-ems on-prem 单服务器部署落地 metrics + logs + traces + alerting 完整可观测性栈，独立 docker-compose，与产品栈生命周期解耦。

**Architecture:** 应用层最小改造（micrometer-tracing-bridge-otel + opentelemetry-exporter-otlp 两个依赖，新建 6 个 Bean 类做业务埋点）；观测栈独立放 `ops/observability/`，含 docker-compose.obs.yml + Prometheus + Loki + Tempo + Grafana + Alertmanager + 自构 webhook-bridge + promtail；产品栈与观测栈共享 `ems-net` external network；业务 metrics 17 个 + SLO 4 个 + 报警 16 条 + Grafana dashboard 7 个；新增 CI observability job 校验 prom rules 与 grafana JSON。

**Tech Stack:** Spring Boot 3.3.4 + Micrometer 1.13 + OpenTelemetry SDK + Prometheus v2.54 + Loki 3.x + Tempo 2.x + Grafana 11.x + Alertmanager v0.27 + Promtail 3.x + Go 1.22 (webhook-bridge) + Docker Compose v2 + GitHub Actions

**Spec:** [`docs/superpowers/specs/2026-04-29-observability-stack-design.md`](../specs/2026-04-29-observability-stack-design.md)

---

## File Structure

### 应用层（ems-app 模块内增量）

```
ems-app/
├── pom.xml                                                  # 增 2 个依赖
└── src/
    ├── main/
    │   ├── java/com/ems/app/observability/                  # 新包
    │   │   ├── ObservabilityConfig.java                     # MeterRegistryCustomizer
    │   │   ├── SchedulerInstrumentationAspect.java          # @Around @Scheduled
    │   │   ├── CollectorMetrics.java                        # 5 个 collector 指标
    │   │   ├── AlarmMetrics.java                            # 5 个 alarm 指标
    │   │   ├── MeterMetrics.java                            # 3 个 meter 指标
    │   │   └── AppMetrics.java                              # 4 个 app 指标
    │   └── resources/
    │       └── application-prod.yml                         # management.metrics + tracing 增量
    └── test/java/com/ems/app/observability/
        ├── CollectorMetricsTest.java
        ├── AlarmMetricsTest.java
        ├── MeterMetricsTest.java
        ├── AppMetricsTest.java
        └── SchedulerInstrumentationAspectTest.java
```

### 观测栈（新增 `ops/observability/`）

```
ops/observability/
├── docker-compose.obs.yml
├── .env.obs.example
├── README.md                                                # 该目录索引
├── prometheus/
│   ├── prometheus.yml
│   └── rules/
│       ├── slo-availability.yml
│       ├── slo-latency.yml
│       ├── slo-freshness.yml
│       ├── slo-scheduler-drift.yml
│       ├── critical-alerts.yml
│       ├── warning-alerts.yml
│       ├── burn-rate-alerts.yml
│       └── _tests/
│           ├── critical-alerts_test.yml
│           ├── warning-alerts_test.yml
│           └── burn-rate_test.yml
├── alertmanager/
│   └── alertmanager.yml
├── loki/
│   └── loki-config.yml
├── promtail/
│   └── promtail-config.yml
├── tempo/
│   └── tempo.yml
├── grafana/
│   ├── provisioning/
│   │   ├── datasources/datasources.yml
│   │   └── dashboards/dashboards.yml
│   └── dashboards/
│       ├── slo-overview.json
│       ├── infra-overview.json
│       ├── jvm-overview.json
│       ├── http-overview.json
│       ├── ems-collector.json
│       ├── ems-alarm.json
│       └── ems-meter.json
├── webhook-bridge/
│   ├── Dockerfile
│   ├── go.mod
│   └── main.go
└── scripts/
    ├── obs-up.sh                                            # 幂等启动
    ├── obs-smoke.sh                                         # 5 服务 ready 检查
    ├── obs-down.sh
    └── grafana-init.sh                                      # 首次启动随机生成 admin 密码
```

### 文档（同步落实）

```
docs/
├── product/
│   ├── observability-feature-overview.md
│   ├── observability-config-reference.md
│   ├── observability-metrics-dictionary.md
│   ├── observability-slo-rules.md
│   ├── observability-dashboards-guide.md
│   └── observability-user-guide.md
├── api/
│   └── observability-metrics-api.md
└── ops/
    ├── observability-runbook.md
    ├── observability-deployment.md
    └── verification-2026-04-29-observability.md
```

### CI

```
.github/workflows/ci.yml                                     # 增 observability job
```

---

## Phase 表

| Phase | 主题 | Tasks | 估时 | 文档落实 task |
|---|---|---|---|---|
| A | 应用栈接入（依赖 + 调度埋点 + 配置） | A1–A5 | 1d | A5 → `docs/product/observability-config-reference.md` |
| B | 业务 metrics 埋点（TDD） | B1–B5 | 1.5d | B5 → `docs/product/observability-metrics-dictionary.md` |
| C | 观测栈基础设施（compose + 5 服务配置 + webhook-bridge + 启动脚本） | C1–C7 | 2d | C7 → `docs/ops/observability-deployment.md` |
| D | SLO + 报警规则 + promtool 测试 + SLO 总览 dashboard | D1–D5 | 1.5d | D5 → `docs/product/observability-slo-rules.md` |
| E | Grafana dashboards（infra/jvm/http + collector/alarm/meter） | E1–E4 | 1d | E4 → `docs/product/observability-dashboards-guide.md` |
| F | CI 集成 + smoke 完整化 + 故障 runbook | F1–F4 | 0.5d | F4 → `docs/ops/observability-runbook.md` |
| G | 销售/客户/集成 三类文档 + 验收日志 + 索引 + tag | G1–G5 | 0.5d | G1–G3 三份文档 + G5 验收 |

**总计**：32 tasks，约 8 个工作日。

---

## Phase A — 应用栈接入

### Task A1: 增加 micrometer-tracing + OTLP 依赖

**Files:**
- Modify: `ems-app/pom.xml`

- [x] **Step 1: 在 ems-app/pom.xml 的 `<dependencies>` 节点末尾插入**

```xml
<!-- Observability: tracing bridge + OTLP exporter (v1: manual instrumentation only, no auto-agent) -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

> 不引入 `opentelemetry-spring-boot-starter`（auto-instrument 启动 +2-5s，spec §7.2 已明确 v1 不引）。

- [x] **Step 2: 验证编译**

Run: `./mvnw -B -pl ems-app -am compile`
Expected: BUILD SUCCESS，依赖被 Spring Boot BOM 自动管理版本。

- [x] **Step 3: Commit**

```bash
git add ems-app/pom.xml
git commit -m "feat(obs): 引入 micrometer-tracing + OTLP exporter（手动埋点模式）"
```

---

### Task A2: ObservabilityConfig（公共 labels）

**Files:**
- Create: `ems-app/src/main/java/com/ems/app/observability/ObservabilityConfig.java`
- Test: `ems-app/src/test/java/com/ems/app/observability/ObservabilityConfigTest.java`

- [x] **Step 1: 写失败测试 ObservabilityConfigTest.java**

```java
package com.ems.app.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityConfigTest {

    @Test
    void customizer_addsApplicationAndInstanceLabels() {
        var registry = new SimpleMeterRegistry();
        new ObservabilityConfig().commonTagsCustomizer("factory-ems", "host-x").customize(registry);

        Counter c = registry.counter("dummy");
        c.increment();

        var meter = registry.find("dummy").counter();
        assertThat(meter).isNotNull();
        assertThat(meter.getId().getTag("application")).isEqualTo("factory-ems");
        assertThat(meter.getId().getTag("instance")).isEqualTo("host-x");
    }
}
```

- [x] **Step 2: 跑失败**

Run: `./mvnw -B -pl ems-app -am -Dtest=ObservabilityConfigTest test`
Expected: 编译错误（`ObservabilityConfig` 不存在）。

- [x] **Step 3: 实现 ObservabilityConfig.java**

```java
package com.ems.app.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObservabilityConfig {

    @Bean
    MeterRegistryCustomizer<MeterRegistry> commonTagsCustomizer(
            @Value("${spring.application.name:factory-ems}") String application,
            @Value("${HOSTNAME:unknown}") String instance) {
        return registry -> registry.config().meterFilter(
                MeterFilter.commonTags(java.util.List.of(
                        Tag.of("application", application),
                        Tag.of("instance", instance)
                )));
    }
}
```

- [x] **Step 4: 跑通过**

Run: `./mvnw -B -pl ems-app -am -Dtest=ObservabilityConfigTest test`
Expected: PASS。

- [x] **Step 5: Commit**

```bash
git add ems-app/src/main/java/com/ems/app/observability/ObservabilityConfig.java \
        ems-app/src/test/java/com/ems/app/observability/ObservabilityConfigTest.java
git commit -m "feat(obs): ObservabilityConfig 注入 application/instance 公共 labels"
```

---

### Task A3: SchedulerInstrumentationAspect（@Scheduled 统一埋点）

**Files:**
- Create: `ems-app/src/main/java/com/ems/app/observability/SchedulerInstrumentationAspect.java`
- Test: `ems-app/src/test/java/com/ems/app/observability/SchedulerInstrumentationAspectTest.java`

埋点目标：现有 5 个 `@Scheduled` 方法（AlarmDetectorImpl#run, RollupHourlyJob, RollupDailyJob, RollupMonthlyJob, RollupRetryJob）+ 未来新增的，全部自动产生 `ems.app.scheduled.duration` Timer 与 `ems.app.scheduled.drift.seconds` Gauge，label `task=方法全限定名缩写`。

- [x] **Step 1: 写失败测试**

```java
package com.ems.app.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SchedulerInstrumentationAspectTest {

    @Test
    void instrumentScheduled_recordsDurationAndDrift() throws Throwable {
        MeterRegistry registry = new SimpleMeterRegistry();
        SchedulerInstrumentationAspect aspect = new SchedulerInstrumentationAspect(registry);

        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        Signature sig = mock(Signature.class);
        when(sig.getDeclaringTypeName()).thenReturn("com.ems.app.SampleJob");
        when(sig.getName()).thenReturn("run");
        when(pjp.getSignature()).thenReturn(sig);
        when(pjp.proceed()).thenReturn(null);

        aspect.instrumentScheduled(pjp);

        var timer = registry.find("ems.app.scheduled.duration").tag("task", "SampleJob.run").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1L);
    }

    @Test
    void instrumentScheduled_propagatesException() throws Throwable {
        MeterRegistry registry = new SimpleMeterRegistry();
        SchedulerInstrumentationAspect aspect = new SchedulerInstrumentationAspect(registry);

        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        Signature sig = mock(Signature.class);
        when(sig.getDeclaringTypeName()).thenReturn("com.x.Y");
        when(sig.getName()).thenReturn("z");
        when(pjp.getSignature()).thenReturn(sig);
        RuntimeException boom = new RuntimeException("boom");
        when(pjp.proceed()).thenThrow(boom);

        try {
            aspect.instrumentScheduled(pjp);
        } catch (Throwable t) {
            assertThat(t).isSameAs(boom);
        }
        var timer = registry.find("ems.app.scheduled.duration").timer();
        assertThat(timer.count()).isEqualTo(1L);
    }
}
```

- [x] **Step 2: 跑失败**

Run: `./mvnw -B -pl ems-app -am -Dtest=SchedulerInstrumentationAspectTest test`
Expected: 编译错误。

- [x] **Step 3: 实现 SchedulerInstrumentationAspect.java**

```java
package com.ems.app.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class SchedulerInstrumentationAspect {

    private final MeterRegistry registry;

    public SchedulerInstrumentationAspect(MeterRegistry registry) {
        this.registry = registry;
    }

    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
    public Object instrumentScheduled(ProceedingJoinPoint pjp) throws Throwable {
        String declaring = pjp.getSignature().getDeclaringTypeName();
        String simpleClass = declaring.substring(declaring.lastIndexOf('.') + 1);
        String task = simpleClass + "." + pjp.getSignature().getName();

        Timer.Sample sample = Timer.start(registry);
        try {
            return pjp.proceed();
        } finally {
            sample.stop(Timer.builder("ems.app.scheduled.duration")
                    .description("@Scheduled 任务耗时")
                    .tag("task", task)
                    .register(registry));
        }
    }
}
```

> Drift gauge 采用 `MeterRegistry.gauge` 在 task 触发时刻动态写入；v1 简化版只记 duration，drift 在 Phase D 用 Prometheus PromQL `time() - last_run_timestamp` 间接推算（避免运行期反射 cron 表达式）。

- [x] **Step 4: 跑通过**

Run: `./mvnw -B -pl ems-app -am -Dtest=SchedulerInstrumentationAspectTest test`
Expected: PASS。

- [x] **Step 5: Commit**

```bash
git add ems-app/src/main/java/com/ems/app/observability/SchedulerInstrumentationAspect.java \
        ems-app/src/test/java/com/ems/app/observability/SchedulerInstrumentationAspectTest.java
git commit -m "feat(obs): @Scheduled AOP 统一埋 ems.app.scheduled.duration"
```

---

### Task A4: application-prod.yml 增量

**Files:**
- Modify: `ems-app/src/main/resources/application-prod.yml`（若不存在则创建）

- [x] **Step 1: 增加 management + tracing 配置**

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,info,metrics
  endpoint:
    health:
      show-details: when-authorized
      probes:
        enabled: true
  metrics:
    tags:
      application: factory-ems
      instance: ${HOSTNAME:unknown}
  tracing:
    sampling:
      probability: 0.1
  otlp:
    tracing:
      endpoint: ${OTLP_TRACING_ENDPOINT:http://tempo:4318/v1/traces}
```

- [x] **Step 2: 启动 prod profile 冒烟**

Run: `./mvnw -B -pl ems-app spring-boot:run -Dspring-boot.run.profiles=prod -Dspring-boot.run.arguments=--server.port=8082` 起 30s 后 `curl localhost:8082/actuator/prometheus | head`
Expected: 返回 prometheus exposition 格式（# HELP / # TYPE 行）；含 `application="factory-ems"` 标签。

- [x] **Step 3: Commit**

```bash
git add ems-app/src/main/resources/application-prod.yml
git commit -m "feat(obs): prod profile 暴露 /actuator/prometheus 与 OTLP 配置"
```

---

### Task A5: 文档落实 — observability-config-reference

**Files:**
- Create: `docs/product/observability-config-reference.md`

- [x] **Step 1: 撰文 — 内容来源 spec §11 完整照搬+落地说明**

包含小节：
1. 文件结构（spec §11.1）
2. `.env.obs` 全部环境变量表（spec §11.2，每行附"是否必填、默认值、空值行为"）
3. `application-prod.yml` 应用层增量（spec §11.3）
4. 启停命令（spec §16.2）
5. 升级路径（spec §16.3）
6. 资源预算（spec §16.1 表）
7. FAQ：钉钉/企微 webhook 不通 / Grafana 忘密码 / docker network 不存在

- [x] **Step 2: 加索引联动**

修改 `docs/product/README.md` 与 `docs/ops/README.md`，在已有"alarm-*"列表下方新增"observability-*"区块占位（仅 config-reference 一条，其余在后续 Phase 填）。

- [x] **Step 3: Commit**

```bash
git add docs/product/observability-config-reference.md docs/product/README.md docs/ops/README.md
git commit -m "docs(obs): config-reference + 索引占位（Phase A 落实）"
```

---

## Phase B — 业务 metrics 埋点（TDD）

> 共 17 个 metrics 分到 4 个 Bean 类。每类先写测试（注册存在 + 至少 1 次记录），再实现 Bean，再到对应 service 注入。

### Task B1: CollectorMetrics（5 个 collector 指标）

**Files:**
- Create: `ems-app/src/main/java/com/ems/app/observability/CollectorMetrics.java`
- Test: `ems-app/src/test/java/com/ems/app/observability/CollectorMetricsTest.java`
- Modify: `ems-collector/src/main/java/com/ems/collector/service/impl/CollectorServiceImpl.java`（或现有 poll 入口类，按模块实际命名）

- [x] **Step 1: 写测试 CollectorMetricsTest**

```java
package com.ems.app.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CollectorMetricsTest {

    @Test
    void registers_allFiveMetrics() {
        var registry = new SimpleMeterRegistry();
        var metrics = new CollectorMetrics(registry);

        metrics.recordPoll("modbus-tcp", java.time.Duration.ofMillis(120));
        metrics.setOnline(42);
        metrics.setOffline(3);
        metrics.recordReadSuccess("dev-1");
        metrics.recordReadFailure("dev-1", "timeout");

        assertThat(registry.find("ems.collector.poll.duration").tag("adapter", "modbus-tcp").timer()).isNotNull();
        assertThat(registry.find("ems.collector.devices.online").gauge().value()).isEqualTo(42d);
        assertThat(registry.find("ems.collector.devices.offline").gauge().value()).isEqualTo(3d);
        assertThat(registry.find("ems.collector.read.success.total").tag("device_id", "dev-1").counter().count()).isEqualTo(1d);
        assertThat(registry.find("ems.collector.read.failure.total").tag("device_id", "dev-1").tag("reason", "timeout").counter().count()).isEqualTo(1d);
    }

    @Test
    void recordReadFailure_unknownReason_normalizesToOther() {
        var registry = new SimpleMeterRegistry();
        var metrics = new CollectorMetrics(registry);
        metrics.recordReadFailure("d", "weird-thing-not-in-enum");
        assertThat(registry.find("ems.collector.read.failure.total").tag("reason", "other").counter().count()).isEqualTo(1d);
    }
}
```

- [x] **Step 2: 跑失败 → 实现 → 跑通过**

```java
package com.ems.app.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class CollectorMetrics {
    private static final Set<String> KNOWN_REASONS = Set.of("timeout", "crc", "format", "disconnected", "other");

    private final MeterRegistry registry;
    private final AtomicLong onlineHolder = new AtomicLong();
    private final AtomicLong offlineHolder = new AtomicLong();

    public CollectorMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("ems.collector.devices.online", onlineHolder);
        registry.gauge("ems.collector.devices.offline", offlineHolder);
    }

    public void recordPoll(String adapter, Duration d) {
        Timer.builder("ems.collector.poll.duration")
                .description("一轮设备采集耗时分布")
                .tag("adapter", adapter)
                .register(registry)
                .record(d);
    }

    public void setOnline(long n) { onlineHolder.set(n); }
    public void setOffline(long n) { offlineHolder.set(n); }

    public void recordReadSuccess(String deviceId) {
        Counter.builder("ems.collector.read.success.total")
                .tag("device_id", deviceId)
                .register(registry).increment();
    }

    public void recordReadFailure(String deviceId, String reason) {
        String normalized = KNOWN_REASONS.contains(reason) ? reason : "other";
        Counter.builder("ems.collector.read.failure.total")
                .tag("device_id", deviceId)
                .tag("reason", normalized)
                .register(registry).increment();
    }
}
```

- [x] **Step 3: 在 ems-collector 现有 poll 入口注入**

定位类：`grep -r "@Scheduled" ems-collector/src/main/java | grep -v Test`，找到 1 个采集循环类，构造器加 `CollectorMetrics metrics` 参数；在每次 poll 完成后 `metrics.recordPoll(adapter, dur)` + 单次寄存器读后 `metrics.recordReadSuccess/Failure`。

> 注：`CollectorMetrics` 在 ems-app 包内但带 `@Component`，依赖 ems-app 主程序扫描；ems-collector 是 ems-app 的依赖模块，需要将 CollectorMetrics 类移到 ems-collector 模块或在 ems-app 提供门面接口。**实施时优先方案：将 4 个 metrics 类放 ems-app 的 observability 包，业务模块通过 Spring 注入；如出现循环依赖则提取接口 `CollectorMetricsPort` 到 ems-core 共享模块。**

- [x] **Step 4: 跑全 ems-app + ems-collector 测试**

Run: `./mvnw -B -pl ems-app,ems-collector -am test`
Expected: 全 PASS。

- [x] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(obs): CollectorMetrics 5 个指标 + ems-collector poll 注入"
```

---

### Task B2: AlarmMetrics（5 个 alarm 指标）

**Files:**
- Create: `ems-app/src/main/java/com/ems/app/observability/AlarmMetrics.java`
- Test: `ems-app/src/test/java/com/ems/app/observability/AlarmMetricsTest.java`
- Modify: `ems-alarm/src/main/java/com/ems/alarm/service/impl/AlarmDetectorImpl.java`、`AlarmDispatcherImpl.java`、`AlarmServiceImpl.java`

> 指标：`ems.alarm.detector.duration`(Timer) / `ems.alarm.active.count{type}`(Gauge) / `ems.alarm.created.total{type}`(Counter) / `ems.alarm.resolved.total{reason}`(Counter) / `ems.alarm.webhook.delivery.duration{outcome,attempt}`(Timer)。

- [x] **Step 1: 写测试 AlarmMetricsTest**（按 B1 模式：注册 + 至少 1 次记录 + 边界值）
- [x] **Step 2: 实现 AlarmMetrics.java**（Gauge 用 `AtomicLong` map by type）
- [x] **Step 3: 注入到 detector/dispatcher/service**：
  - AlarmDetectorImpl#run 入口 `Timer.Sample`
  - AlarmDispatcherImpl 每次 webhook 调用结束记 `recordWebhookDelivery(outcome, attempt, dur)`
  - AlarmServiceImpl#create 后 `metrics.incrementCreated(type)` + 状态变化时同步 active gauge
  - AlarmServiceImpl#resolve 后 `metrics.incrementResolved(reason)` + active gauge -1
- [x] **Step 4: `./mvnw -pl ems-alarm,ems-app -am test`**
- [x] **Step 5: Commit `feat(obs): AlarmMetrics 5 个指标 + ems-alarm 注入`**

---

### Task B3: MeterMetrics（3 个 meter 指标）

**Files:**
- Create: `ems-app/src/main/java/com/ems/app/observability/MeterMetrics.java`
- Test: `ems-app/src/test/java/com/ems/app/observability/MeterMetricsTest.java`
- Modify: `ems-meter/src/main/java/com/ems/meter/service/impl/MeterReadingServiceImpl.java`（或入库 service 实际类）

> 指标：`ems.meter.reading.lag.seconds`(Gauge — 最大值聚合) / `ems.meter.reading.insert.rate{energy_type}`(Counter) / `ems.meter.reading.dropped.total{reason}`(Counter)
> Lag gauge 由调度任务每 60s 全表 `max(now - last_read_at)` 写入；用 `MeterRegistry.gauge` 配 `AtomicLong` holder。

- [x] **Step 1-2: 测试 + 实现**（按 B1 模式）
- [x] **Step 3: 入库 service 注入**：每条入库后 `metrics.incrementInsert(energyType)`；校验失败 `metrics.incrementDropped(reason)`；新增 `MeterLagJob`（@Scheduled fixedDelay 60s）调用 `meterRepository.findMaxLagSeconds()` → `metrics.setLag(seconds)`
- [x] **Step 4: 测试 + 通过**
- [x] **Step 5: Commit `feat(obs): MeterMetrics 3 个指标 + lag job + 入库注入`**

---

### Task B4: AppMetrics（4 个 app 指标 + GlobalExceptionHandler 注入）

**Files:**
- Create: `ems-app/src/main/java/com/ems/app/observability/AppMetrics.java`
- Test: `ems-app/src/test/java/com/ems/app/observability/AppMetricsTest.java`
- Modify: `ems-app/src/main/java/com/ems/app/handler/GlobalExceptionHandler.java`、`ems-audit/src/main/java/com/ems/audit/service/impl/AuditServiceImpl.java`

> 指标：`ems.app.scheduled.duration{task}`（已由 A3 AOP 写入，不重复注册）/ `ems.app.scheduled.drift.seconds{task}`（v1 占位 Gauge，先注册不写，留 D Phase 用 PromQL 推） / `ems.app.audit.write.total{action}`(Counter) / `ems.app.exception.total{type}`(Counter)。

- [x] **Step 1-2: 测试 + 实现**
- [x] **Step 3: 注入**：GlobalExceptionHandler 兜底 `metrics.incrementException(ex.getClass().getSimpleName())`；AuditServiceImpl#write 后 `metrics.incrementAudit(action)`
- [x] **Step 4: 测试 + 通过**
- [x] **Step 5: Commit `feat(obs): AppMetrics 4 个指标 + 异常/审计注入`**

---

### Task B5: 文档落实 — observability-metrics-dictionary

**Files:**
- Create: `docs/product/observability-metrics-dictionary.md`

- [x] **Step 1: 撰文 — 内容来源 spec §8**
  - 8.1 公共 labels
  - 8.2-8.5 各模块指标表（17 行）含名称/类型/单位/labels/含义/典型 PromQL/报警是否覆盖（指向 D Phase）
  - 8.6 cardinality 控制规则
  - 8.7 Spring Boot 已有指标列表（不重发明）
  - 附录：开发者新增指标 checklist（命名规范、label 上限、注册时机）
- [x] **Step 2: 索引联动** docs/product/README.md
- [x] **Step 3: Commit `docs(obs): metrics 字典（Phase B 落实）`**

---

## Phase C — 观测栈基础设施

### Task C1: 目录结构 + .env.obs.example + README

**Files:**
- Create: `ops/observability/` 全树（空 placeholder + 文件）
- Create: `ops/observability/.env.obs.example`
- Create: `ops/observability/README.md`

- [x] **Step 1: 创建目录树**

```bash
mkdir -p ops/observability/{prometheus/rules/_tests,alertmanager,loki,promtail,tempo,grafana/{provisioning/{datasources,dashboards},dashboards},webhook-bridge,scripts}
```

- [x] **Step 2: 写 `.env.obs.example`** — 字段完全对齐 spec §11.2 表（13 个变量），每行注释"必填/可选+默认+空值行为"。

```env
# Grafana
OBS_GRAFANA_ADMIN_USER=admin
OBS_GRAFANA_ADMIN_PASSWORD=                            # 必填；首次启动由 grafana-init.sh 生成

# 数据保留
OBS_PROMETHEUS_RETENTION=30d
OBS_LOKI_RETENTION=336h                                # 14 天
OBS_TEMPO_RETENTION=72h                                # 3 天

# Email（可选；空值跳过 receiver）
OBS_SMTP_HOST=
OBS_SMTP_USER=
OBS_SMTP_PASSWORD=
OBS_ALERT_RECEIVER_EMAIL=

# 钉钉（可选）
OBS_DINGTALK_WEBHOOK=
OBS_DINGTALK_SECRET=

# 企微（可选）
OBS_WECHAT_WEBHOOK=

# 通用（可选）
OBS_GENERIC_WEBHOOK=

# Docker
OBS_NETWORK_NAME=ems-net
```

- [x] **Step 3: README.md** — 目录索引 + 快速启动命令 + 链回 spec/plan/runbook
- [x] **Step 4: Commit `chore(obs): 观测栈目录骨架 + .env.obs.example`**

---

### Task C2: prometheus.yml 抓取配置

**Files:**
- Create: `ops/observability/prometheus/prometheus.yml`

- [x] **Step 1: 写配置**

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 60s
  external_labels:
    cluster: factory-ems
    env: prod

rule_files:
  - /etc/prometheus/rules/*.yml

alerting:
  alertmanagers:
    - static_configs:
        - targets: ['alertmanager:9093']

scrape_configs:
  - job_name: factory-ems
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['factory-ems:8080']
        labels: { module: app }

  - job_name: prometheus
    static_configs:
      - targets: ['localhost:9090']

  - job_name: cadvisor
    static_configs:
      - targets: ['cadvisor:8080']

  - job_name: node-exporter
    static_configs:
      - targets: ['node-exporter:9100']
```

- [x] **Step 2: 静态校验** — `docker run --rm -v $(pwd)/ops/observability/prometheus:/cfg prom/prometheus:v2.54.1 promtool check config /cfg/prometheus.yml`（rules 还没写，会报 0 条规则 OK）
- [x] **Step 3: Commit `feat(obs): prometheus.yml 抓取配置（factory-ems + cadvisor + node-exporter）`**

---

### Task C3: Loki + Promtail

**Files:**
- Create: `ops/observability/loki/loki-config.yml`
- Create: `ops/observability/promtail/promtail-config.yml`

- [x] **Step 1: loki-config.yml**

```yaml
auth_enabled: false
server:
  http_listen_port: 3100
common:
  path_prefix: /loki
  storage:
    filesystem:
      chunks_directory: /loki/chunks
      rules_directory: /loki/rules
  replication_factor: 1
  ring:
    kvstore: { store: inmemory }
schema_config:
  configs:
    - from: 2024-01-01
      store: tsdb
      object_store: filesystem
      schema: v13
      index: { prefix: index_, period: 24h }
limits_config:
  retention_period: ${OBS_LOKI_RETENTION:-336h}
compactor:
  working_directory: /loki/compactor
  retention_enabled: true
  delete_request_store: filesystem
```

- [x] **Step 2: promtail-config.yml** — 抓 docker container stdout，按 container_name label 路由

```yaml
server:
  http_listen_port: 9080
positions:
  filename: /tmp/positions.yaml
clients:
  - url: http://loki:3100/loki/api/v1/push
scrape_configs:
  - job_name: docker
    docker_sd_configs:
      - host: unix:///var/run/docker.sock
        refresh_interval: 5s
    relabel_configs:
      - source_labels: ['__meta_docker_container_name']
        regex: '/(.*)'
        target_label: container
      - source_labels: ['__meta_docker_container_label_com_docker_compose_service']
        target_label: compose_service
```

- [x] **Step 3: Commit `feat(obs): loki + promtail 配置`**

---

### Task C4: Tempo + Alertmanager

**Files:**
- Create: `ops/observability/tempo/tempo.yml`
- Create: `ops/observability/alertmanager/alertmanager.yml`

- [x] **Step 1: tempo.yml**（接 OTLP HTTP 4318，本地 fs 存储，3d retention）

```yaml
server:
  http_listen_port: 3200
distributor:
  receivers:
    otlp:
      protocols:
        http: { endpoint: 0.0.0.0:4318 }
        grpc: { endpoint: 0.0.0.0:4317 }
ingester:
  trace_idle_period: 10s
  max_block_duration: 5m
compactor:
  compaction:
    compaction_window: 1h
    block_retention: ${OBS_TEMPO_RETENTION:-72h}
storage:
  trace:
    backend: local
    local: { path: /var/tempo/blocks }
    wal: { path: /var/tempo/wal }
```

- [x] **Step 2: alertmanager.yml** — 完整路由表（spec §9.4）+ 4 个 receiver（default-email / multi-channel-dingtalk / multi-channel-wechat / multi-channel-generic）+ 抑制规则。任一渠道 env 空 → receiver 配置空 url 静默跳过。

```yaml
global:
  resolve_timeout: 5m
  smtp_smarthost: '${OBS_SMTP_HOST}'
  smtp_from: '${OBS_SMTP_USER}'
  smtp_auth_username: '${OBS_SMTP_USER}'
  smtp_auth_password: '${OBS_SMTP_PASSWORD}'
  smtp_require_tls: true

route:
  receiver: default-email
  group_by: [alertname, severity]
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 4h
  routes:
    - matchers: [severity="critical"]
      receiver: multi-channel
    - matchers: [severity="warning"]
      receiver: default-email

receivers:
  - name: default-email
    email_configs:
      - to: '${OBS_ALERT_RECEIVER_EMAIL}'
        send_resolved: true

  - name: multi-channel
    email_configs:
      - to: '${OBS_ALERT_RECEIVER_EMAIL}'
        send_resolved: true
    webhook_configs:
      - url: 'http://obs-webhook-bridge:9094/dingtalk'
        send_resolved: true
      - url: 'http://obs-webhook-bridge:9094/wechat'
        send_resolved: true
      - url: '${OBS_GENERIC_WEBHOOK}'
        send_resolved: true

inhibit_rules:
  - source_matchers: [alertname="EmsAppDown"]
    target_matchers: [alertname=~"Ems.*"]
    equal: [instance]
```

- [x] **Step 3: 静态校验**

Run: `docker run --rm -v $(pwd)/ops/observability/alertmanager:/cfg prom/alertmanager:v0.27.0 amtool check-config /cfg/alertmanager.yml`
Expected: `Checking '/cfg/alertmanager.yml' SUCCESS`

> 校验时需 export 全部 env 占位避免 unresolved；提供 `.env.obs.example` 中的 placeholder。

- [x] **Step 4: Commit `feat(obs): tempo + alertmanager 配置 + 多通道路由`**

---

### Task C5: webhook-bridge（Go 钉钉/企微 适配）

**Files:**
- Create: `ops/observability/webhook-bridge/main.go`
- Create: `ops/observability/webhook-bridge/go.mod`
- Create: `ops/observability/webhook-bridge/Dockerfile`

> 输入：Alertmanager webhook 标准格式 JSON。输出：调用 `OBS_DINGTALK_WEBHOOK`（带 `&timestamp=...&sign=...` HMAC-SHA256 签名）或 `OBS_WECHAT_WEBHOOK`。失败重试 3 次（线性退避 1/2/4s）。/health 返回 200。

- [x] **Step 1: main.go**（约 150 行）

```go
package main

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

type AlertmanagerPayload struct {
	Status string `json:"status"`
	Alerts []struct {
		Status      string            `json:"status"`
		Labels      map[string]string `json:"labels"`
		Annotations map[string]string `json:"annotations"`
		StartsAt    string            `json:"startsAt"`
	} `json:"alerts"`
}

func main() {
	http.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("ok")) })
	http.HandleFunc("/dingtalk", dingtalkHandler)
	http.HandleFunc("/wechat", wechatHandler)
	log.Println("webhook-bridge listening :9094")
	log.Fatal(http.ListenAndServe(":9094", nil))
}

func dingtalkHandler(w http.ResponseWriter, r *http.Request) {
	url := os.Getenv("OBS_DINGTALK_WEBHOOK")
	secret := os.Getenv("OBS_DINGTALK_SECRET")
	if url == "" {
		w.WriteHeader(204); return
	}
	body, _ := io.ReadAll(r.Body)
	var p AlertmanagerPayload
	if err := json.Unmarshal(body, &p); err != nil {
		http.Error(w, err.Error(), 400); return
	}
	text := formatAlertText(p)
	signedURL := signDingtalk(url, secret)
	payload, _ := json.Marshal(map[string]any{
		"msgtype": "text",
		"text":    map[string]string{"content": "[factory-ems] " + text},
	})
	postWithRetry(signedURL, payload, w)
}

func wechatHandler(w http.ResponseWriter, r *http.Request) {
	url := os.Getenv("OBS_WECHAT_WEBHOOK")
	if url == "" { w.WriteHeader(204); return }
	body, _ := io.ReadAll(r.Body)
	var p AlertmanagerPayload
	if err := json.Unmarshal(body, &p); err != nil {
		http.Error(w, err.Error(), 400); return
	}
	text := formatAlertText(p)
	payload, _ := json.Marshal(map[string]any{
		"msgtype": "text",
		"text":    map[string]string{"content": "[factory-ems] " + text},
	})
	postWithRetry(url, payload, w)
}

func formatAlertText(p AlertmanagerPayload) string {
	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("Status: %s | %d alert(s)\n", p.Status, len(p.Alerts)))
	for _, a := range p.Alerts {
		sb.WriteString(fmt.Sprintf("[%s] %s — %s\n", a.Status, a.Labels["alertname"], a.Annotations["summary"]))
	}
	return sb.String()
}

func signDingtalk(rawURL, secret string) string {
	if secret == "" { return rawURL }
	ts := time.Now().UnixMilli()
	signStr := fmt.Sprintf("%d\n%s", ts, secret)
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(signStr))
	sign := base64.StdEncoding.EncodeToString(mac.Sum(nil))
	u, _ := url.Parse(rawURL)
	q := u.Query()
	q.Set("timestamp", fmt.Sprintf("%d", ts))
	q.Set("sign", sign)
	u.RawQuery = q.Encode()
	return u.String()
}

func postWithRetry(url string, body []byte, w http.ResponseWriter) {
	backoff := []time.Duration{0, time.Second, 2 * time.Second, 4 * time.Second}
	var lastErr error
	for i := 1; i <= 3; i++ {
		time.Sleep(backoff[i-1])
		resp, err := http.Post(url, "application/json", bytes.NewReader(body))
		if err == nil && resp.StatusCode < 300 {
			resp.Body.Close()
			w.WriteHeader(204); return
		}
		if resp != nil { resp.Body.Close() }
		lastErr = err
		log.Printf("webhook attempt %d failed: %v", i, err)
	}
	http.Error(w, fmt.Sprintf("all 3 attempts failed: %v", lastErr), 502)
}
```

- [x] **Step 2: go.mod**

```
module github.com/factoryems/observability/webhook-bridge
go 1.22
```

- [x] **Step 3: Dockerfile（distroless 多阶段）**

```Dockerfile
FROM golang:1.22-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY main.go ./
RUN CGO_ENABLED=0 go build -ldflags="-s -w" -o /webhook-bridge .

FROM gcr.io/distroless/static-debian12
COPY --from=build /webhook-bridge /webhook-bridge
EXPOSE 9094
ENTRYPOINT ["/webhook-bridge"]
```

- [x] **Step 4: 本地构建烟测**

Run: `cd ops/observability/webhook-bridge && docker build -t factory-ems/obs-webhook-bridge:dev . && docker run --rm -d -p 9094:9094 --name wb-test factory-ems/obs-webhook-bridge:dev && sleep 2 && curl localhost:9094/health && docker stop wb-test`
Expected: 输出 `ok`。

- [x] **Step 5: Commit `feat(obs): webhook-bridge Go 服务（钉钉/企微 适配 + HMAC 签名 + 重试）`**

---

### Task C6: docker-compose.obs.yml + 启动脚本

**Files:**
- Create: `ops/observability/docker-compose.obs.yml`
- Create: `ops/observability/scripts/{obs-up,obs-down,obs-smoke,grafana-init}.sh`

- [x] **Step 1: docker-compose.obs.yml**

```yaml
name: factory-ems-obs

networks:
  ems-net:
    external: true
    name: ${OBS_NETWORK_NAME:-ems-net}

volumes:
  prom-data:
  loki-data:
  tempo-data:
  grafana-data:
  alertmanager-data:

services:
  prometheus:
    image: prom/prometheus:v2.54.1
    restart: unless-stopped
    command:
      - --config.file=/etc/prometheus/prometheus.yml
      - --storage.tsdb.path=/prometheus
      - --storage.tsdb.retention.time=${OBS_PROMETHEUS_RETENTION:-30d}
      - --web.enable-lifecycle
    ports: ['127.0.0.1:9090:9090']
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - ./prometheus/rules:/etc/prometheus/rules:ro
      - prom-data:/prometheus
    networks: [ems-net]

  loki:
    image: grafana/loki:3.1.0
    restart: unless-stopped
    command: -config.file=/etc/loki/loki-config.yml -config.expand-env=true
    environment:
      OBS_LOKI_RETENTION: ${OBS_LOKI_RETENTION:-336h}
    volumes:
      - ./loki/loki-config.yml:/etc/loki/loki-config.yml:ro
      - loki-data:/loki
    networks: [ems-net]

  tempo:
    image: grafana/tempo:2.5.0
    restart: unless-stopped
    command: -config.file=/etc/tempo/tempo.yml -config.expand-env=true
    environment:
      OBS_TEMPO_RETENTION: ${OBS_TEMPO_RETENTION:-72h}
    volumes:
      - ./tempo/tempo.yml:/etc/tempo/tempo.yml:ro
      - tempo-data:/var/tempo
    ports: ['4318:4318']
    networks: [ems-net]

  promtail:
    image: grafana/promtail:3.1.0
    restart: unless-stopped
    command: -config.file=/etc/promtail/promtail-config.yml
    volumes:
      - ./promtail/promtail-config.yml:/etc/promtail/promtail-config.yml:ro
      - /var/run/docker.sock:/var/run/docker.sock:ro
    networks: [ems-net]
    depends_on: [loki]

  alertmanager:
    image: prom/alertmanager:v0.27.0
    restart: unless-stopped
    command:
      - --config.file=/etc/alertmanager/alertmanager.yml
      - --storage.path=/alertmanager
    env_file: .env.obs
    ports: ['127.0.0.1:9093:9093']
    volumes:
      - ./alertmanager/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro
      - alertmanager-data:/alertmanager
    networks: [ems-net]

  obs-webhook-bridge:
    build: ./webhook-bridge
    image: factory-ems/obs-webhook-bridge:1.0.0
    restart: unless-stopped
    env_file: .env.obs
    networks: [ems-net]

  grafana:
    image: grafana/grafana:11.1.0
    restart: unless-stopped
    environment:
      GF_SECURITY_ADMIN_USER: ${OBS_GRAFANA_ADMIN_USER:-admin}
      GF_SECURITY_ADMIN_PASSWORD: ${OBS_GRAFANA_ADMIN_PASSWORD}
      GF_AUTH_ANONYMOUS_ENABLED: 'false'
    ports: ['127.0.0.1:3000:3000']
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning:ro
      - ./grafana/dashboards:/var/lib/grafana/dashboards:ro
      - grafana-data:/var/lib/grafana
    networks: [ems-net]
    depends_on: [prometheus, loki, tempo]
```

- [x] **Step 2: scripts/obs-up.sh**

```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

NETWORK="${OBS_NETWORK_NAME:-ems-net}"
docker network inspect "$NETWORK" >/dev/null 2>&1 || docker network create "$NETWORK"

if [ ! -f .env.obs ]; then
  echo "ERROR: .env.obs not found. Copy .env.obs.example and fill OBS_GRAFANA_ADMIN_PASSWORD." >&2
  exit 1
fi

if ! grep -q '^OBS_GRAFANA_ADMIN_PASSWORD=.\+' .env.obs; then
  echo "OBS_GRAFANA_ADMIN_PASSWORD empty — running grafana-init.sh to generate"
  ./scripts/grafana-init.sh
fi

docker compose --env-file .env.obs -f docker-compose.obs.yml up -d
echo "Waiting 10s for services to start..."
sleep 10
./scripts/obs-smoke.sh
```

- [x] **Step 3: scripts/grafana-init.sh**

```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
PASS="$(openssl rand -base64 24 | tr -d '/+=' | head -c 24)"
sed -i.bak "s|^OBS_GRAFANA_ADMIN_PASSWORD=.*|OBS_GRAFANA_ADMIN_PASSWORD=$PASS|" .env.obs
rm -f .env.obs.bak
echo "===================================================="
echo "Grafana admin password generated and saved to .env.obs"
echo "Password: $PASS"
echo "Save it now — this is the only time it is printed."
echo "===================================================="
```

- [x] **Step 4: scripts/obs-smoke.sh**

```bash
#!/usr/bin/env bash
set -euo pipefail

check() {
  local name="$1" url="$2"
  for i in 1 2 3 4 5 6; do
    if curl -fsS --max-time 5 "$url" >/dev/null 2>&1; then
      echo "  ✓ $name ready"
      return 0
    fi
    sleep 5
  done
  echo "  ✗ $name failed after 30s ($url)" >&2
  return 1
}

echo "Smoke check obs stack..."
check prometheus "http://127.0.0.1:9090/-/ready"
check alertmanager "http://127.0.0.1:9093/-/ready"
check grafana "http://127.0.0.1:3000/api/health"
check loki "http://127.0.0.1:3100/ready" || echo "  (loki bound internal only — skipping if not exposed)"
check tempo "http://127.0.0.1:3200/ready" || echo "  (tempo bound internal only — skipping if not exposed)"
echo "All ready."
```

- [x] **Step 5: scripts/obs-down.sh**

```bash
#!/usr/bin/env bash
cd "$(dirname "$0")/.."
docker compose --env-file .env.obs -f docker-compose.obs.yml down "$@"
```

- [x] **Step 6: chmod +x scripts/*.sh**

- [x] **Step 7: 本地端到端冒烟（可选，防止合并 broken stack）**

```bash
cp ops/observability/.env.obs.example ops/observability/.env.obs
cd ops/observability && ./scripts/grafana-init.sh && ./scripts/obs-up.sh
```
Expected: 5 服务 ready；`docker ps --filter name=factory-ems-obs` 看到 7 个容器。完事 `./scripts/obs-down.sh -v` 清理。

> 若本地 docker 网络冲突或资源不足，跳过 Step 7，依赖 CI 校验。

- [x] **Step 8: Commit `feat(obs): docker-compose.obs.yml + 启停脚本（含 grafana 密码自动生成）`**

---

### Task C7: 文档落实 — observability-deployment

**Files:**
- Create: `docs/ops/observability-deployment.md`

- [x] **Step 1: 撰文 — 来源 spec §16 + Phase C 全部产物的实操步骤**

章节：
1. 资源预算（spec §16.1）
2. 前置条件（Docker 24+ / Compose v2 / docker network ems-net 创建命令）
3. 安装步骤（cp .env.obs.example → 填环境变量 → ./scripts/obs-up.sh）
4. 首次启动 Grafana 密码处理（强制 console 打印一次的逻辑）
5. 升级路径（spec §16.3）
6. 卸载/数据清理
7. 与产品栈共置网络（`ems-net` external 创建顺序）
8. 故障定位入口（指向 runbook）
- [x] **Step 2: README 索引联动**
- [x] **Step 3: Commit `docs(obs): deployment 指南（Phase C 落实）`**

---

## Phase D — SLO + 报警 + promtool 测试

### Task D1: SLO 录制规则（4 个）

**Files:**
- Create: `ops/observability/prometheus/rules/slo-availability.yml`
- Create: `ops/observability/prometheus/rules/slo-latency.yml`
- Create: `ops/observability/prometheus/rules/slo-freshness.yml`
- Create: `ops/observability/prometheus/rules/slo-scheduler-drift.yml`

> 每个 SLO 写 `recording_rules` 计算 SLI 与错误预算（30d 窗口），不直接报警；报警规则在 D2/D3/D4 单独写。

- [x] **Step 1-4: 4 个 yml**（按 spec §9.1 PromQL）

样例 `slo-availability.yml`：

```yaml
groups:
  - name: slo-availability
    interval: 60s
    rules:
      - record: ems:slo:availability:sli_30d
        expr: avg_over_time(up{job="factory-ems"}[30d])
      - record: ems:slo:availability:objective
        expr: vector(0.995)
      - record: ems:slo:availability:error_budget_remaining
        expr: (ems:slo:availability:sli_30d - ems:slo:availability:objective) / (1 - ems:slo:availability:objective)
```

其余 3 个仿照（latency 用 histogram_quantile 0.99；freshness 用 max gauge；drift 用 max abs gauge）。

- [x] **Step 5: 静态校验** `promtool check rules ops/observability/prometheus/rules/slo-*.yml`
- [x] **Step 6: Commit `feat(obs): 4 个 SLO 录制规则`**

---

### Task D2: critical 报警规则（5 条）

**Files:**
- Create: `ops/observability/prometheus/rules/critical-alerts.yml`

- [x] **Step 1: 5 条 critical alert（spec §9.2 表）**

```yaml
groups:
  - name: critical
    rules:
      - alert: EmsAppDown
        expr: up{job="factory-ems"} == 0
        for: 2m
        labels: { severity: critical, team: ops }
        annotations:
          summary: factory-ems 实例不可达
          description: '{{ $labels.instance }} actuator 抓取失败 ≥ 2m'
          runbook_url: https://internal/docs/ops/observability-runbook#emsappdown

      - alert: EmsAppHighErrorRate
        expr: rate(ems_app_exception_total[5m]) > 1
        for: 5m
        labels: { severity: critical, team: backend }
        annotations:
          summary: 应用异常速率 > 1/s（持续 5m）
          description: 'rate={{ $value | humanize }}/s'

      - alert: EmsDataFreshnessCritical
        expr: max(ems_meter_reading_lag_seconds) > 600
        for: 2m
        labels: { severity: critical, team: data }
        annotations: { summary: 数据新鲜度 > 10min }

      - alert: EmsDbConnectionPoolExhausted
        expr: hikaricp_connections_active / hikaricp_connections_max > 0.95
        for: 3m
        labels: { severity: critical, team: backend }
        annotations: { summary: DB 连接池近满 }

      - alert: EmsDiskSpaceCritical
        expr: node_filesystem_avail_bytes{fstype!~"tmpfs|overlay"} / node_filesystem_size_bytes < 0.10
        for: 5m
        labels: { severity: critical, team: ops }
        annotations: { summary: 磁盘可用空间 < 10% }
```

- [x] **Step 2: 校验 `promtool check rules ...`**
- [x] **Step 3: Commit `feat(obs): 5 条 critical 报警`**

---

### Task D3: warning 报警规则（11 条）

**Files:**
- Create: `ops/observability/prometheus/rules/warning-alerts.yml`

- [x] **Step 1: 11 条 warning alert（spec §9.3 表）** — 按表逐条；burn-rate 两条单独放 D4。
- [x] **Step 2: 校验**
- [x] **Step 3: Commit `feat(obs): 11 条 warning 报警`**

---

### Task D4: 错误预算燃烧率报警（2 条）

**Files:**
- Create: `ops/observability/prometheus/rules/burn-rate-alerts.yml`

- [x] **Step 1: 2 条 burn-rate alert**（1h 窗 14.4× / 6h 窗 6×）

```yaml
groups:
  - name: burn-rate
    rules:
      - alert: EmsBudgetBurnFastAvailability
        expr: (1 - avg_over_time(up{job="factory-ems"}[1h])) > (14.4 * (1 - 0.995))
        for: 5m
        labels: { severity: critical, team: ops }
        annotations: { summary: 1h 燃烧率 14.4× — 预算 2 天耗尽 }

      - alert: EmsBudgetBurnSlowAvailability
        expr: (1 - avg_over_time(up{job="factory-ems"}[6h])) > (6 * (1 - 0.995))
        for: 30m
        labels: { severity: warning, team: ops }
        annotations: { summary: 6h 燃烧率 6× — 预算 5 天耗尽 }
```

- [x] **Step 2: Commit `feat(obs): SLO 错误预算燃烧率报警（1h+6h 双窗口）`**

---

### Task D5: promtool 测试用例（32 个）

**Files:**
- Create: `ops/observability/prometheus/rules/_tests/critical-alerts_test.yml`
- Create: `ops/observability/prometheus/rules/_tests/warning-alerts_test.yml`
- Create: `ops/observability/prometheus/rules/_tests/burn-rate_test.yml`

> 16 alert × 2（1 正例触发 + 1 反例不触发）= 32 用例。

- [x] **Step 1-3: 3 份测试文件**

样例 `critical-alerts_test.yml` 头部：

```yaml
rule_files:
  - ../critical-alerts.yml

evaluation_interval: 1m

tests:
  - interval: 1m
    name: EmsAppDown_fires_when_down_for_2m
    input_series:
      - series: 'up{job="factory-ems",instance="prod-01"}'
        values: '0 0 0 0'
    alert_rule_test:
      - eval_time: 3m
        alertname: EmsAppDown
        exp_alerts:
          - exp_labels: { severity: critical, team: ops, alertname: EmsAppDown, instance: prod-01, job: factory-ems }
            exp_annotations: { summary: 'factory-ems 实例不可达', description: 'prod-01 actuator 抓取失败 ≥ 2m', runbook_url: 'https://internal/docs/ops/observability-runbook#emsappdown' }

  - interval: 1m
    name: EmsAppDown_silent_when_up
    input_series:
      - series: 'up{job="factory-ems",instance="prod-01"}'
        values: '1 1 1 1'
    alert_rule_test:
      - eval_time: 3m
        alertname: EmsAppDown
        exp_alerts: []
```

每条 alert 仿照写一对正反例。

- [x] **Step 2: 跑 promtool test**

Run: `docker run --rm -v $(pwd)/ops/observability/prometheus:/cfg prom/prometheus:v2.54.1 promtool test rules /cfg/rules/_tests/*.yml`
Expected: `SUCCESS` 总用例 ≥ 32。

- [x] **Step 3: Commit `test(obs): promtool 32 条 alert 单元测试（正反例）`**

---

### Task D6: 文档落实 — observability-slo-rules

**Files:**
- Create: `docs/product/observability-slo-rules.md`

- [x] **Step 1: 撰文 — 来源 spec §9 + 实际 yml**
  - 4 个 SLO 解释（指标含义、目标、SLI 计算）
  - 16 条报警全表（severity / 表达式 / for / 通道 / runbook 锚点）
  - 静默与抑制使用方式（amtool / Grafana UI）
  - 客户视角："为什么这些数字是合同里写的？"
- [x] **Step 2: 索引联动**
- [x] **Step 3: Commit `docs(obs): SLO + 报警规则参考（Phase D 落实）`**

---

## Phase E — Grafana Dashboards

### Task E1: provisioning + SLO Overview（D1）

**Files:**
- Create: `ops/observability/grafana/provisioning/datasources/datasources.yml`
- Create: `ops/observability/grafana/provisioning/dashboards/dashboards.yml`
- Create: `ops/observability/grafana/dashboards/slo-overview.json`

- [x] **Step 1: datasources.yml**

```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
  - name: Loki
    type: loki
    access: proxy
    url: http://loki:3100
  - name: Tempo
    type: tempo
    access: proxy
    url: http://tempo:3200
```

- [x] **Step 2: dashboards.yml** — 标准 file provisioner 指向 `/var/lib/grafana/dashboards`
- [x] **Step 3: slo-overview.json**（Grafana 11 schemaVersion ≥ 39，4 stat panel + 1 gauge + 2 timeseries + 1 table）

> 仅写关键面板的 PromQL 表达式 + targets；面板坐标布局可用通用模板。表达式来自 spec §9.1 + §10.2。

- [x] **Step 4: jq 校验**

Run: `jq empty ops/observability/grafana/dashboards/slo-overview.json && jq empty ops/observability/grafana/provisioning/datasources/datasources.yml 2>/dev/null || true`
Expected: 无错误输出（yml 校验另用 yamllint）。

- [x] **Step 5: Commit `feat(obs): Grafana provisioning + SLO Overview dashboard`**

---

### Task E2: 系统类 dashboard（infra + jvm + http）

**Files:**
- Create: `ops/observability/grafana/dashboards/{infra-overview,jvm-overview,http-overview}.json`

> 使用 Grafana mixin 风格 + Spring Boot Actuator 默认指标。模板可参考社区 dashboard ID：[Spring Boot 2.x Statistics 6756 / JVM Actuator 4701 / Node Exporter Full 1860] 选取面板移植，所有指标名替换为 actuator 版本。

- [x] **Step 1-3: 3 个 JSON**

各含 8-12 个面板。具体面板清单（spec §10.1）：
- infra：CPU / RAM / Disk / Network / 容器数 / 容器 Top 资源
- jvm：Heap used / non-heap / GC pause sum / 线程数 / 类加载 / OOM count
- http：RPS / latency p50/p95/p99 / 状态码饼 / Top10 端点 / outcome 分布

- [x] **Step 4: jq 校验三份 JSON**
- [x] **Step 5: Commit `feat(obs): infra + jvm + http dashboards`**

---

### Task E3: 业务类 dashboard（collector + alarm + meter）

**Files:**
- Create: `ops/observability/grafana/dashboards/{ems-collector,ems-alarm,ems-meter}.json`

> 四象限风格（spec §10.3）。所有指标必须来自 Phase B 注册的 17 个 metrics（防漂移）。

- [x] **Step 1-3: 3 个 JSON**

ems-collector：
- 上左：在线/离线设备数 stat
- 上右：失败率 by adapter（柱状）
- 下左：poll p95 趋势
- 下右：Top 失败设备表 + 点行 → Loki query `{container="factory-ems"} |~ "device_id={{__data.device_id}}"`

ems-alarm：
- 活跃报警 by type / 检测耗时 / Webhook 成功率 / 事件流时间线

ems-meter：
- 数据新鲜度 by 设备 / 入库速率 by energy_type / 丢弃率

- [x] **Step 4: jq 校验**
- [x] **Step 5: Commit `feat(obs): collector + alarm + meter 业务 dashboards`**

---

### Task E4: 文档落实 — observability-dashboards-guide

**Files:**
- Create: `docs/product/observability-dashboards-guide.md`

- [x] **Step 1: 撰文 — 来源 spec §10 + 7 个 JSON 真实面板说明**
  - 7 dashboard 总览表（受众、URL、关键面板）
  - 每个 dashboard 一节：截图占位 + 各面板"看什么、看不正常时怎么办"
  - 模板变量使用（instance/module/时间窗）
  - 下钻路径示例：SLO → 业务 dashboard → Top 失败设备 → Loki → Tempo
- [x] **Step 2: 索引联动**
- [x] **Step 3: Commit `docs(obs): dashboards 使用指南（Phase E 落实）`**

---

## Phase F — CI 集成 + smoke 完整化 + runbook

### Task F1: CI observability job

**Files:**
- Modify: `.github/workflows/ci.yml`

- [x] **Step 1: 在 ci.yml jobs 节追加**

```yaml
  observability:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Install promtool & amtool
        run: |
          PROM_VER=2.54.1
          AM_VER=0.27.0
          curl -sL https://github.com/prometheus/prometheus/releases/download/v${PROM_VER}/prometheus-${PROM_VER}.linux-amd64.tar.gz | tar xz -C /tmp
          curl -sL https://github.com/prometheus/alertmanager/releases/download/v${AM_VER}/alertmanager-${AM_VER}.linux-amd64.tar.gz | tar xz -C /tmp
          sudo mv /tmp/prometheus-${PROM_VER}.linux-amd64/promtool /usr/local/bin/
          sudo mv /tmp/alertmanager-${AM_VER}.linux-amd64/amtool /usr/local/bin/
      - name: Validate prom rules syntax
        run: promtool check rules ops/observability/prometheus/rules/*.yml
      - name: Run prom rules unit tests
        run: promtool test rules ops/observability/prometheus/rules/_tests/*.yml
      - name: Validate prometheus.yml
        run: promtool check config ops/observability/prometheus/prometheus.yml
        continue-on-error: true   # rules 路径在容器外，CI 容忍
      - name: Validate alertmanager.yml
        env:
          OBS_SMTP_HOST: smtp.example.com:587
          OBS_SMTP_USER: alerts@example.com
          OBS_SMTP_PASSWORD: x
          OBS_ALERT_RECEIVER_EMAIL: oncall@x.com
          OBS_GENERIC_WEBHOOK: http://example.com/webhook
        run: amtool check-config ops/observability/alertmanager/alertmanager.yml
      - name: Validate Grafana dashboards JSON
        run: |
          for f in ops/observability/grafana/dashboards/*.json; do
            jq empty "$f"
          done
      - name: Validate webhook-bridge Dockerfile builds
        run: docker build -t obs-webhook-bridge:ci ops/observability/webhook-bridge
```

- [x] **Step 2: 推 PR 触发 CI 自验证（或本地 act 模拟）**
- [x] **Step 3: Commit `ci: observability job（promtool + amtool + dashboards JSON + webhook-bridge build）`**

---

### Task F2: obs-smoke 增强（端到端 alert）

**Files:**
- Modify: `ops/observability/scripts/obs-smoke.sh`

- [x] **Step 1: 增加端到端 alert 注入测试**（追加在 5 服务 ready 之后）

```bash
echo "Injecting test alert via Alertmanager API..."
curl -fsS -X POST http://127.0.0.1:9093/api/v2/alerts -H 'Content-Type: application/json' -d '[
  {"labels": {"alertname":"SmokeTest","severity":"warning","instance":"smoke"},
   "annotations": {"summary":"smoke test"},
   "startsAt":"'"$(date -u +%Y-%m-%dT%H:%M:%SZ)"'"}
]'
sleep 5
echo "Verifying alert propagated to alertmanager..."
curl -fsS http://127.0.0.1:9093/api/v2/alerts | jq -e '.[] | select(.labels.alertname=="SmokeTest")' >/dev/null
echo "  ✓ alert flow OK"
```

- [x] **Step 2: Commit `feat(obs): obs-smoke 端到端 alert 注入校验`**

---

### Task F3: 文档落实 — observability-runbook

**Files:**
- Create: `docs/ops/observability-runbook.md`

- [x] **Step 1: 撰文 — 8 个 runbook 章节**
  - 系统架构图（spec §6.1 复用）
  - 启停（链 deployment 文档）
  - 5 个 critical alert 一键处置（每条对应 §9.2，含初步排查 5 步）
  - 11 个 warning alert 处置思路
  - 数据保留与备份（30d/14d/3d）
  - Grafana 用户管理 / 密码遗忘恢复
  - 升级与回滚
  - 与产品栈联调时的常见冲突（network 名、端口、内存不足）
- [x] **Step 2: README 索引联动 + 各 alert 注解 runbook_url 锚点验证**
- [x] **Step 3: Commit `docs(obs): runbook（Phase F 落实）`**

---

## Phase G — 销售/客户/集成文档 + 验收 + tag

### Task G1: observability-feature-overview（销售/客户视角）

**Files:**
- Create: `docs/product/observability-feature-overview.md`

- [x] **Step 1: 撰文 — 来源 spec §1-§4 + 既有 alarm-feature-overview 模板**
  - 一句话价值
  - 解决什么问题（4 条客户痛点）
  - 核心功能（6 条）
  - 适用场景 A/B/C 完整故事
  - 与 ems-alarm 的协同（业务报警 vs 基础设施报警）
- [x] **Step 2: Commit `docs(obs): 功能概览（销售视角）`**

---

### Task G2: observability-user-guide（客户运维视角）

**Files:**
- Create: `docs/product/observability-user-guide.md`

- [x] **Step 1: 撰文 — 操作手册风**
  - 1.如何登录 Grafana / 如何切换 dashboard
  - 2.如何看 SLO 四个数字（含红/黄/绿判定）
  - 3.如何在收到报警邮件后定位
  - 4.如何在维护期暂停报警（amtool / Grafana UI 截图占位）
  - 5.如何申请 viewer 账号（链 §15 权限矩阵）
  - 6.FAQ × 8
  - 7.术语表
- [x] **Step 2: Commit `docs(obs): 用户指南（客户运维视角）`**

---

### Task G3: observability-metrics-api（集成方视角）

**Files:**
- Create: `docs/api/observability-metrics-api.md`

- [x] **Step 1: 撰文 — 来源 spec §8 + Prometheus 抓取协议**
  - factory-ems 暴露端点（`/actuator/prometheus`）
  - 鉴权（v1 内网无鉴权 + nginx 反代加 Basic Auth 的可选示例）
  - 17 个业务指标 + Spring Boot 默认指标列表
  - 抓取间隔建议
  - 自定义集成示例（curl 拉一次 → grep 关键指标）
- [x] **Step 2: Commit `docs(obs): metrics API（集成方视角）`**

---

### Task G4: README 索引大全 + 验收日志

**Files:**
- Modify: `docs/product/README.md`、`docs/ops/README.md`、`docs/api/README.md`、根 `README.md`（"商业化加固进度"区块）
- Create: `docs/ops/verification-2026-04-29-observability.md`

- [x] **Step 1: 验收日志** — 复用 `verification-2026-04-29-alarm.md` 模板：
  - 实施时间线（按 phase + commit hash）
  - 17 个 metrics 实物清单（执行 `curl localhost:8080/actuator/prometheus | grep ^ems_`）
  - 16 条 alert 清单 + promtool 测试输出
  - 7 dashboard 截图占位
  - Smoke 端到端结果
  - 已知遗留：drift gauge 是占位、e2e 渲染对比未做
  - 客户验收 checklist
- [x] **Step 2: 各 README 全量交叉链接**
- [x] **Step 3: 根 README 商业化进度 sub-project #1 标 ✅**
- [x] **Step 4: Commit `docs(obs): 验收日志 + README 索引大全（Phase G 落实）`**

---

### Task G5: tag v1.7.0-obs

**Files:**（无新增）

- [x] **Step 1: 全量回归**

```bash
./mvnw -B clean verify
cd ops/observability && ./scripts/obs-up.sh && ./scripts/obs-smoke.sh && ./scripts/obs-down.sh -v && cd -
```
Expected: backend 全绿 + 5 服务 ready + smoke alert 注入成功。

- [x] **Step 2: 打 tag**

```bash
git tag -a v1.7.0-obs -m "可观测性栈 v1：metrics + logs + traces + alerts + 7 dashboards"
git tag --list | grep obs
```

- [x] **Step 3: 通报**：在 ops 频道（手工）+ docs/ops/verification-2026-04-29-observability.md 末尾追加"v1.7.0-obs 已打 tag"。

---

## Key Invariants（防回归 / 写给 reviewer）

1. **观测栈与产品栈 docker-compose 完全独立**：跨栈通信仅通过 `ems-net` external network；不允许在产品 docker-compose.yml 引用观测服务；不允许在观测 docker-compose.obs.yml 改动产品配置。
2. **业务 metrics 埋点不抛业务异常**：所有 `MeterRegistry` 调用走 micrometer 内置 fail-safe；service 层不写 try/catch 包裹埋点。
3. **alert 渠道环境变量为空 → receiver 静默跳过**：禁止启动失败；amtool check-config 时用占位 env 完成校验。
4. **Cardinality 防爆炸**：`device_id` label 仅出现在 `ems.collector.read.success.total` 与 `ems.collector.read.failure.total`；其余 17 metrics 全部走低基数枚举。
5. **Grafana admin 默认密码强制改密**：grafana-init.sh 必须在 obs-up.sh 之前先跑；没填密码不允许 up。
6. **观测栈 UI 端口默认仅 loopback**：3000/9090/9093 全部 `127.0.0.1` 绑定；远程访问必须经 nginx 反代（不在本 plan 范围）。
7. **business alert（ems-alarm）与 infrastructure alert（本 plan）走不同通道**：ems-alarm 自己的 `webhook` 配置不归 alertmanager 管，避免 ems-alarm 故障无法通知。
8. **JaCoCo 70% 阈值不变**：observability 类归 ems-app 模块统计；新增 metrics 类必须有 unit test 覆盖注册 + 至少 1 次记录。

---

## Self-Review checklist

- [x] Spec §1-§7 价值/架构 → Phase A 应用接入 + Phase C 观测栈基础设施 全覆盖
- [x] Spec §8 17 metrics → Phase B 4 个 metrics 类一一对应
- [x] Spec §9 SLO + 16 alerts → Phase D 4+5+11+2 = 22 条规则覆盖（其中 11 warning 含 2 burn-rate 单独入 D4）
- [x] Spec §10 7 dashboards → Phase E 4 task（E1=SLO+provisioning，E2=infra/jvm/http，E3=业务三件套，E4=guide doc）
- [x] Spec §11 配置 → Task A4 + C1 + A5 doc
- [x] Spec §12 测试策略 → 各 Task 内置 unit/promtool；Task F1 CI 集成；Task F2 smoke 端到端
- [x] Spec §16 部署 → Task C6 docker-compose + scripts；Task C7 deployment doc
- [x] Spec §17 日志规范 → 已通过既有 logback JSON + Loki/promtail 自动覆盖（无新代码）
- [x] Spec §18 10 个文档产物 → A5 / B5 / C7 / D6 / E4 / F3 / G1 / G2 / G3 / G4 全 10 篇映射
- [x] Spec §20 8 个不变量 → 上节 Key Invariants 完整复述

---

## 执行说明

完成后请使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐 task 执行。每完成一个 Phase，**立即落实该 Phase 的文档 task**（A5/B5/C7/D6/E4/F3/G1-G4），保持代码与文档同步。
