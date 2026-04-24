package com.ems.audit.listener;

import com.ems.audit.entity.AuditLog;
import com.ems.audit.event.AuditEvent;
import com.ems.audit.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AsyncAuditListener {

    private static final Logger log = LoggerFactory.getLogger(AsyncAuditListener.class);

    private final AuditLogRepository repo;

    public AsyncAuditListener(AuditLogRepository repo) {
        this.repo = repo;
    }

    /** Fires only after the surrounding business transaction successfully commits. */
    @Async("auditExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAfterCommit(AuditEvent ev) {
        writeLog(ev);
    }

    /** Auth events have no wrapping transaction; listen to them directly. */
    @Async("auditExecutor")
    @EventListener(condition = "#ev.action == 'LOGIN' or #ev.action == 'LOGOUT' or #ev.action == 'LOGIN_FAIL'")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAuthEvent(AuditEvent ev) {
        writeLog(ev);
    }

    private void writeLog(AuditEvent ev) {
        try {
            AuditLog a = new AuditLog();
            a.setActorUserId(ev.actorUserId());
            a.setActorUsername(ev.actorUsername());
            a.setAction(ev.action());
            a.setResourceType(ev.resourceType());
            a.setResourceId(ev.resourceId());
            a.setSummary(ev.summary());
            a.setDetail(ev.detailJson());
            a.setIp(ev.ip());
            a.setUserAgent(ev.userAgent());
            a.setOccurredAt(ev.occurredAt());
            repo.save(a);
        } catch (Exception e) {
            log.error("audit_write_failed action={} resource={} id={}",
                ev.action(), ev.resourceType(), ev.resourceId(), e);
        }
    }
}
