package com.ems.app.observability;

import com.ems.audit.event.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 监听全局 {@link AuditEvent} → 计入 {@code ems.app.audit.write.total{action}}（spec §8.5）。
 *
 * <p>此 listener 与 ems-audit 的 {@code AsyncAuditListener} 并行执行：
 * 前者只负责"意图计数"（业务发起 audit），后者负责异步持久化到 audit_log。
 * 即使持久化失败，意图仍被计入 — 这是有意为之，metric 反映的是业务调用频率。
 *
 * <p>本类是同步监听器：异常必须在内部 catch，否则会通过 Spring event multicaster
 * 传播，潜在影响同源 {@code AsyncAuditListener} 的派发（Key Invariant #2：
 * 业务路径不能因 metrics 失败而崩溃）。
 */
@Component
public class AuditEventCountingListener {

    private static final Logger log = LoggerFactory.getLogger(AuditEventCountingListener.class);

    private final AppMetrics metrics;

    public AuditEventCountingListener(AppMetrics metrics) {
        this.metrics = metrics;
    }

    @EventListener
    public void onAuditEvent(AuditEvent event) {
        try {
            metrics.incrementAudit(event.action());
        } catch (Throwable t) {
            log.warn("audit metric increment failed (non-fatal) action={}: {}",
                event.action(), t.toString());
        }
    }
}
