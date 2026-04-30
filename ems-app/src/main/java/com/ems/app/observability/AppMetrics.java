package com.ems.app.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 应用层跨模块业务 metrics（spec §8.5）。
 *
 * <p>2 个 active 指标：
 * <ul>
 *   <li>{@code ems.app.audit.write.total{action}} — Counter，由
 *       {@link AuditEventCountingListener} 监听 {@code com.ems.audit.event.AuditEvent} 后递增。
 *       计数 audit 写入"意图"，独立于 ems-audit 的实际持久化结果。</li>
 *   <li>{@code ems.app.exception.total{type}} — Counter，由
 *       {@code GlobalExceptionHandler} 兜底分支（{@code @ExceptionHandler(Exception.class)}）递增。
 *       仅计 unhandled 路径，BusinessException / AccessDenied 等业务预期异常不计入。</li>
 * </ul>
 *
 * <p>另外 2 个 spec §8.5 指标在 v1 不在本类注册：
 * <ul>
 *   <li>{@code ems.app.scheduled.duration} — 已由 {@link SchedulerInstrumentationAspect}（Task A3）通过
 *       AOP {@code @Around} 提供。</li>
 *   <li>{@code ems.app.scheduled.drift.seconds} — v1 占位，应用层暂不埋点。
 *       Phase D 通过 PromQL 基于 duration timer 的 last_run 时间戳推导漂移，
 *       不需要应用侧 Gauge。</li>
 * </ul>
 *
 * <p>未知 / null label 值统一归一化为 {@code "other"}，控制 cardinality。
 */
@Component
public class AppMetrics {

    /**
     * NOOP fallback：用于手动构造测试或不希望接 Spring registry 的场景。
     * 内部使用一次性 SimpleMeterRegistry，写入会累积但不会被任何 scrape 看到，等价于 NOOP。
     */
    public static final AppMetrics NOOP = new AppMetrics(new SimpleMeterRegistry());

    private final MeterRegistry registry;

    public AppMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 累计 audit 写入意图 +1。{@code action} 为空时归一化为 {@code "other"}。 */
    public void incrementAudit(String action) {
        String norm = (action == null || action.isBlank()) ? "other" : action;
        Counter.builder("ems.app.audit.write.total")
                .description("Audit log write count")
                .tag("action", norm)
                .register(registry)
                .increment();
    }

    /** GlobalExceptionHandler 兜底分支异常计数 +1。{@code type} 为空时归一化为 {@code "other"}。 */
    public void incrementException(String type) {
        String norm = (type == null || type.isBlank()) ? "other" : type;
        Counter.builder("ems.app.exception.total")
                .description("GlobalExceptionHandler fallback exception count")
                .tag("type", norm)
                .register(registry)
                .increment();
    }
}
