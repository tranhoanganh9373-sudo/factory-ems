package com.ems.collector.health;

import com.ems.audit.entity.AuditLog;
import com.ems.audit.repository.AuditLogRepository;
import com.ems.collector.poller.DevicePoller;
import com.ems.collector.poller.DeviceState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Plan 1.5.3 Phase B — DevicePoller 状态切换写 audit_logs。
 *
 * <p>action = {@code COLLECTOR_STATE_CHANGE}
 * <br>resourceType = {@code COLLECTOR}
 * <br>resourceId = deviceId
 * <br>summary = "{from} → {to}: {reason}"
 *
 * <p>actor = system（Modbus 自动 polling 没有用户上下文）。
 *
 * <p>@ConditionalOnBean(AuditLogRepository.class) — 让 ems-collector 单测在不加载
 * Spring/JPA 时不实例化此 bean；CollectorAutoConfiguration 的 NOOP 默认依然生效。
 *
 * <p>{@link Propagation#REQUIRES_NEW} — audit 写独立事务；poller transition 失败也不
 * 应回滚 audit，反之 audit 故障也不应让 polling 失败。
 */
@Component
@ConditionalOnBean(AuditLogRepository.class)
public class AlarmTransitionListener implements DevicePoller.StateTransitionListener {

    private static final Logger log = LoggerFactory.getLogger(AlarmTransitionListener.class);

    private final AuditLogRepository repo;

    public AlarmTransitionListener(AuditLogRepository repo) {
        this.repo = repo;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTransition(String deviceId, DeviceState from, DeviceState to,
                             String reason, Instant at) {
        try {
            AuditLog row = new AuditLog();
            row.setActorUserId(null);
            row.setActorUsername("system");
            row.setAction("COLLECTOR_STATE_CHANGE");
            row.setResourceType("COLLECTOR");
            row.setResourceId(deviceId);
            row.setSummary(from + " → " + to + ": " + (reason == null ? "" : reason));
            row.setOccurredAt(OffsetDateTime.ofInstant(at == null ? Instant.now() : at, ZoneOffset.UTC));
            repo.save(row);
        } catch (Exception e) {
            // audit 写失败不能影响 poller — 仅记日志
            log.warn("alarm audit write failed for device {}: {}", deviceId, e.toString());
        }
    }
}
