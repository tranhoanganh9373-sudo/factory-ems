package com.ems.alarm.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 告警模块业务 metrics（spec §8.3）。
 *
 * <p>5 个指标：
 * <ul>
 *   <li>{@code ems.alarm.detector.duration} — Timer，一轮检测扫描耗时</li>
 *   <li>{@code ems.alarm.active.count{type}} — Gauge，当前 ACTIVE+ACKED 计数</li>
 *   <li>{@code ems.alarm.created.total{type}} — Counter，累计触发</li>
 *   <li>{@code ems.alarm.resolved.total{reason}} — Counter，累计恢复（auto/manual）</li>
 *   <li>{@code ems.alarm.webhook.delivery.duration{outcome,attempt}} — Timer，webhook 单次耗时</li>
 * </ul>
 *
 * <p>未知 label 值统一归 {@code other}，attempt 钳到 {@code [1,3]}。Active gauge 通过
 * 持久 {@link AtomicLong} 持有，调用方在 create/resolve 后调用 {@link #setActive(String, long)}
 * 同步刷新；首次调用某 type 时 lazily 注册 gauge。
 */
@Component
public class AlarmMetrics {

    /** 已知 alarm type；未匹配 → 归一化为 {@code "other"}。 */
    static final Set<String> KNOWN_TYPES = Set.of("silent_timeout", "consecutive_fail", "communication_fault", "other");
    /** 已知 resolved reason；未匹配 → 归一化为 {@code "other"}。 */
    static final Set<String> KNOWN_REASONS = Set.of("auto", "manual", "other");
    /** 已知 webhook outcome；未匹配 → 归一化为 {@code "failure"}（保守视为失败）。 */
    static final Set<String> KNOWN_OUTCOMES = Set.of("success", "failure");

    /**
     * NOOP fallback：用于手动构造测试或不希望接 Spring registry 的场景。
     * 内部使用一次性 SimpleMeterRegistry，写入会累积但不会被任何 scrape 看到，等价于 NOOP。
     */
    public static final AlarmMetrics NOOP = new AlarmMetrics(new SimpleMeterRegistry());

    private final MeterRegistry registry;
    /** 每个 type 一个 holder，gauge lazily 注册（首次 setActive 调用）。 */
    private final ConcurrentMap<String, AtomicLong> activeHolders = new ConcurrentHashMap<>();

    public AlarmMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 一轮 detector 扫描耗时。在 {@code AlarmDetectorImpl.scan()} 的 finally 块调用。 */
    public void recordDetectorScan(Duration duration) {
        Timer.builder("ems.alarm.detector.duration")
                .description("Alarm detector scan duration")
                .register(registry)
                .record(duration);
    }

    /**
     * 同步 ACTIVE+ACKED 告警数（按 type 分）。
     * <p>调用方负责传入正确的 count（来自 repo 计数）。未知 type 归一化到 {@code other}。
     */
    public void setActive(String type, long count) {
        String norm = normalizeType(type);
        activeHolders.computeIfAbsent(norm, t -> {
            AtomicLong holder = new AtomicLong();
            registry.gauge("ems.alarm.active.count", List.of(Tag.of("type", t)), holder);
            return holder;
        }).set(count);
    }

    /** 累计触发告警数。 */
    public void incrementCreated(String type) {
        Counter.builder("ems.alarm.created.total")
                .description("Total alarms created")
                .tag("type", normalizeType(type))
                .register(registry)
                .increment();
    }

    /** 累计恢复告警数（auto / manual）。 */
    public void incrementResolved(String reason) {
        String norm = KNOWN_REASONS.contains(reason) ? reason : "other";
        Counter.builder("ems.alarm.resolved.total")
                .description("Total alarms resolved")
                .tag("reason", norm)
                .register(registry)
                .increment();
    }

    /**
     * Webhook 单次调用耗时。
     * @param outcome {@code success} / {@code failure}（其它值视为 failure）
     * @param attempt 1-based 尝试次数（钳到 {@code [1,3]}）
     */
    public void recordWebhookDelivery(String outcome, int attempt, Duration duration) {
        String normOutcome = KNOWN_OUTCOMES.contains(outcome) ? outcome : "failure";
        int clamped = Math.max(1, Math.min(3, attempt));
        Timer.builder("ems.alarm.webhook.delivery.duration")
                .description("Webhook delivery duration")
                .tag("outcome", normOutcome)
                .tag("attempt", String.valueOf(clamped))
                .register(registry)
                .record(duration);
    }

    private static String normalizeType(String type) {
        return KNOWN_TYPES.contains(type) ? type : "other";
    }
}
