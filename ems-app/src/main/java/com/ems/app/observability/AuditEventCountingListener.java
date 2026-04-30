package com.ems.app.observability;

import com.ems.audit.event.AuditEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 监听全局 {@link AuditEvent} → 计入 {@code ems.app.audit.write.total{action}}（spec §8.5）。
 *
 * <p>此 listener 与 ems-audit 的 {@code AsyncAuditListener} 并行执行：
 * 前者只负责"意图计数"（业务发起 audit），后者负责异步持久化到 audit_log。
 * 即使持久化失败，意图仍被计入 — 这是有意为之，metric 反映的是业务调用频率。
 */
@Component
public class AuditEventCountingListener {

    private final AppMetrics metrics;

    public AuditEventCountingListener(AppMetrics metrics) {
        this.metrics = metrics;
    }

    @EventListener
    public void onAuditEvent(AuditEvent event) {
        metrics.incrementAudit(event.action());
    }
}
