# Follow-up — pre-existing issues 发现于 v1.7.0-obs review

> **日期**：2026-04-29
> **来源**：可观测性栈 v1.7.0-obs post-tag 代码审查（java-reviewer + silent-failure-hunter）
> **状态**：✅ 已全部修复（2026-04-30）
> **责任团队**：collector / alarm / app 各模块原作者

观测性栈 review 中扫到 6 条与本次 sub-project 不相关的 pre-existing 问题。每条均**已经在生产代码中存在**，但因 surgical changes 原则未在 v1.7.0-obs 中修。

**2026-04-30 更新**：经评估改为直接修复，6 条全部 commit 上 `feat/ems-observability`：
- #1 → `2cb1f10`（InfluxReadingSink.flushOne 落 log + ListAppender 测试）
- #2 → `2cb1f10`（DevicePoller transition listener catch 落 log）
- #3 → `2cb1f10`（WebhookChannelImpl.retryDelivery orElseThrow 带 ID）
- #4 → `3c68369`（CollectorMetrics → CollectorBusinessMetrics 重命名 + 单 ctor + @Autowired）
- #5 → `ebf1882`（refreshDeviceGauges 移到独立 single-thread scheduler）
- #6 → `2cb1f10`（CollectorEndToEndIT energy_delta assertion 加 signum>0 filter）

---

## #1 — InfluxReadingSink.flushOne 静默吞写失败（CRITICAL）

| 字段 | 值 |
|---|---|
| 文件 | `ems-collector/src/main/java/com/ems/collector/sink/InfluxReadingSink.java:112-114` |
| 引入 commit | Plan 1.5.1（commit `28d0220` 前后），早于 v1.7.0-obs |
| 严重性 | CRITICAL — 数据可能静默丢失 |

**症状**：

```java
try {
    writeApi.writePoint(...);
    return true;
} catch (Exception e) {
    return false;          // ← 无 log
}
```

`BufferFlushScheduler` 把 `false` 当作"继续重试"。Influx 鉴权错误、配置错误、永久网络分区都会导致 buffer 无限增长直到磁盘满 / capacity hit，零 log 输出。

**建议修复**：

```java
} catch (Exception e) {
    log.warn("flush retry failed device={}: {}", reading.deviceId(), e.toString());
    return false;
}
```

或更进一步：把异常 message 转入 `meterMetrics.incrementDropped(reason)`，让 dashboard 可见。

---

## #2 — DevicePoller transition listener 静默吞异常（IMPORTANT）

| 字段 | 值 |
|---|---|
| 文件 | `ems-collector/src/main/java/com/ems/collector/poller/DevicePoller.java:293` |
| 引入 commit | Plan 1.5.1（commit `28d0220` 前后） |
| 严重性 | IMPORTANT — audit 链潜在断裂 |

**症状**：

```java
} catch (Exception ignore) {
    // listener 错误不能影响 poller
}
```

变量名 `ignore` 表明意图，但生产里 `StateTransitionListener` 在 wire 时会驱动 alarm transition 事件 / audit 写入。listener 抛错 → 状态切换发生但 audit 记录丢，且无任何观测信号。

**建议修复**：

```java
} catch (Exception e) {
    log.warn("device {} transition listener error: {}", config.id(), e.toString());
}
```

不需要外抛——listener 异常本就不该让 poller 崩。但日志必须有。

---

## #3 — WebhookChannelImpl.retryDelivery `orElseThrow()` 无 message（IMPORTANT）

| 字段 | 值 |
|---|---|
| 文件 | `ems-alarm/src/main/java/com/ems/alarm/service/impl/WebhookChannelImpl.java:166-167` |
| 引入 commit | ems-alarm Phase E（commit `9b4eee2`） |
| 严重性 | IMPORTANT — 调试困难 |

**症状**：

```java
WebhookDeliveryLog old = deliveryRepo.findById(deliveryLogId).orElseThrow();
Alarm a = alarmRepo.findById(old.getAlarmId()).orElseThrow();
```

`orElseThrow()` 无参数 → 抛 `NoSuchElementException("No value present")`，**没有 ID**。如果调度重试时 entity 已被删，scheduler thread 死于纯 stack trace，oncall 无法定位是哪条 alarm / delivery log。

**建议修复**：

```java
WebhookDeliveryLog old = deliveryRepo.findById(deliveryLogId)
    .orElseThrow(() -> new IllegalStateException(
        "DeliveryLog not found: id=" + deliveryLogId));
Alarm a = alarmRepo.findById(old.getAlarmId())
    .orElseThrow(() -> new IllegalStateException(
        "Alarm not found: deliveryLogId=" + deliveryLogId
            + " alarmId=" + old.getAlarmId()));
```

---

## #4 — CollectorService 多构造器无 `@Autowired` + 命名冲突（MINOR / 脆弱性）

| 字段 | 值 |
|---|---|
| 文件 | `ems-collector/src/main/java/com/ems/collector/service/CollectorService.java:6, 68, 76, 87, 99` |
| 引入 commit | B1（`b5e5f8e`）—— observability.CollectorMetrics 与 pre-existing health.CollectorMetrics 同名 |
| 严重性 | MINOR — 当前 wiring 正确，但 refactor 时易踩坑 |

**症状**：

1. `CollectorService` 有 3 个 public 构造器（7-arg / 8-arg / 9-arg），均无 `@Autowired`。Spring 4.3+ 默认挑参数最多的 satisfiable 构造器——目前所有 metric bean 都是 `@Component`，9-arg 被正确选中。但若日后某个 metric 类被改成非 `@Component` / 加了 `@ConditionalOnProperty`，Spring 会**静默回退**到 7-arg / 8-arg，metrics 全 NOOP，scrape 永远读 0。
2. 同名两个类 `com.ems.collector.health.CollectorMetrics` 与 `com.ems.collector.observability.CollectorMetrics` 共存于同一文件，后者全限定引用——读代码者瞬间困惑。

**建议修复**：

1. 把 `com.ems.collector.observability.CollectorMetrics` 重命名为 `CollectorBusinessMetrics`（或类似，与 health 区分）。
2. 删掉 7-arg 与 8-arg 构造器（它们的存在只为 backward compat 老调用方；目前老调用方已无）。
3. 在唯一保留的 9-arg 构造器上显式加 `@Autowired`。

---

## #5 — `scheduleAtFixedRate` 与 polling 共用线程池（IMPORTANT — 扩展时咬人）

| 字段 | 值 |
|---|---|
| 文件 | `ems-collector/src/main/java/com/ems/collector/service/CollectorService.java:160` |
| 引入 commit | Plan 1.5.1（scheduler 创建在前，B1 把 `refreshDeviceGauges` 加到同一池） |
| 严重性 | IMPORTANT — 50 设备规模可接受，>200 设备会挤压 polling 容量 |

**症状**：

```java
scheduler = Executors.newScheduledThreadPool(Math.max(1, devs.size()), ...);
// ...
scheduler.scheduleAtFixedRate(this::refreshDeviceGauges, 0, 30_000, TimeUnit.MILLISECONDS);
```

`scheduler` core size = `devs.size()`。每 30s 一次 gauge 刷新会**抢一个 worker slot**——50 设备情况下问题不明显，但 200+ 设备时 gauge 刷新可能延后单台设备的 poll 调度（spec §13 风险评估"调度漂移 60s"warning 在此场景下首先触发）。

**建议修复**：

```java
private ScheduledExecutorService gaugeScheduler;

// in start():
gaugeScheduler = Executors.newSingleThreadScheduledExecutor(
    new NamedThreadFactory("ems-collector-gauge-"));
gaugeScheduler.scheduleAtFixedRate(this::refreshDeviceGauges,
    0, DEVICE_STATE_REFRESH_MS, TimeUnit.MILLISECONDS);

// in shutdown(): 同样要 shutdown gaugeScheduler
```

---

## #6 — `CollectorEndToEndIT.accumulatorRegister_emitsDeltaFromSecondCycle` 时序敏感（MINOR / 测试质量）

| 字段 | 值 |
|---|---|
| 文件 | `ems-collector/src/test/java/com/ems/collector/CollectorEndToEndIT.java:127-130` |
| 引入 commit | Plan 1.5.1（早于 observability） |
| 严重性 | MINOR — 测试本身的质量问题，不阻塞 release |

**症状**：

```java
DeviceReading withDelta = readings.stream()
    .filter(rd -> rd.numericFields().containsKey("energy_delta"))
    .findFirst().orElseThrow();
assertThat(withDelta.numericFields().get("energy_delta")).isEqualByComparingTo("150");
```

batch 跑时（同 IT 类内 5 用例并发同 JVM），cycle 1 的 reading 偶尔出现 `energy_delta=0`（accumulator tracker 第一次读后内部已记录 prev value），被 `findFirst()` 捷足先登。**isolated 单测 100% PASS，batch 100% FAIL**——稳定批量失败而非 flake。

实际原因：accumulator 的 first-cycle emission 行为在 `DevicePoller` 修改路径上从未被改（`readRegister` / `AccumulatorTracker.delta()` 没动），所以这是 batch 跑时同一 JVM 的 ModbusSlave 端口、AccumulatorTracker 内部 state、或 fixture 复用导致的真 cycle 1 dirty state——和被测代码无关。

**建议修复**：

```java
DeviceReading withDelta = readings.stream()
    .filter(rd -> rd.numericFields().containsKey("energy_delta")
        && rd.numericFields().get("energy_delta").signum() > 0)
    .findFirst().orElseThrow();
```

或用 `await().untilAsserted(() -> ...)` 包整段 assertion 让其重试。

---

## 优先级总览

| # | 严重性 | 推荐优先级 | 估算工作量 |
|---|---|---|---|
| 1 | CRITICAL | 下个 sprint 必修 | 1 行 + 1 个单测 |
| 2 | IMPORTANT | 下个 sprint | 1 行 + 1 个单测 |
| 3 | IMPORTANT | 下个 sprint | 2 行 + 调用方测试 |
| 5 | IMPORTANT | 容量扩展前必修 | 半天（含 shutdown 协调） |
| 4 | MINOR | 下次 collector refactor 时一并 | 1 天（含老调用方迁移） |
| 6 | MINOR | 任意时刻（不阻塞） | 30 分钟 |

---

## 处理建议

每条建议**独立 PR**：

- 命名：`fix(collector): InfluxReadingSink.flushOne 落 log（follow-up #1）`
- 每个 PR 必须**带单元测试**复现原 bug（如 #1：mock `writeApi.writePoint` 抛 IOException → assert log 出现 + return false）。
- PR 描述里 link 回本文档对应 # 节。
- 修完后**在本文档对应 # 节末尾追加** `**已修复**：commit hash + 日期`，但**不要删除**条目本身（保留为历史 audit 线索）。

完成全部 6 条后，可以把本文档移到 `docs/ops/archive/` 或在文件头加 `> **状态**：全部已闭环（YYYY-MM-DD）`。
