package com.ems.alarm.service.impl;

import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.AlarmType;
import com.ems.alarm.entity.ResolvedReason;
import com.ems.alarm.observability.AlarmMetrics;
import com.ems.alarm.repository.AlarmRepository;
import com.ems.alarm.service.AlarmDispatcher;
import com.ems.alarm.service.AlarmStateMachine;
import com.ems.collector.runtime.ChannelCertificateApprovedEvent;
import com.ems.collector.runtime.ChannelCertificatePendingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 监听 OPC UA 证书事件，创建 / 解除 {@link AlarmType#OPC_UA_CERT_PENDING} 告警。
 *
 * <p>幂等：同一 channel 同时只有一条 ACTIVE/ACKED 的 OPC_UA_CERT_PENDING。
 */
@Component
public class CertificatePendingListener {

    private static final Logger log = LoggerFactory.getLogger(CertificatePendingListener.class);

    private static final String DEVICE_TYPE_CHANNEL = "CHANNEL";
    private static final String SEVERITY_WARNING = "WARNING";
    private static final String METRIC_TYPE = "opcua_cert_pending";

    private final AlarmRepository alarms;
    private final AlarmStateMachine sm;
    private final AlarmDispatcher dispatcher;
    private final AlarmMetrics metrics;
    private final Clock clock;

    public CertificatePendingListener(AlarmRepository alarms,
                                      AlarmStateMachine sm,
                                      AlarmDispatcher dispatcher,
                                      AlarmMetrics metrics,
                                      @Qualifier("alarmClock") Clock clock) {
        this.alarms = alarms;
        this.sm = sm;
        this.dispatcher = dispatcher;
        this.metrics = metrics == null ? AlarmMetrics.NOOP : metrics;
        this.clock = clock;
    }

    @EventListener
    public void onCertificatePending(ChannelCertificatePendingEvent ev) {
        try {
            Optional<Alarm> existing = alarms.findActive(ev.channelId(), AlarmType.OPC_UA_CERT_PENDING);
            if (existing.isPresent()) return;

            Alarm a = new Alarm();
            a.setDeviceType(DEVICE_TYPE_CHANNEL);
            a.setDeviceId(ev.channelId());
            a.setAlarmType(AlarmType.OPC_UA_CERT_PENDING);
            a.setStatus(AlarmStatus.ACTIVE);
            a.setSeverity(SEVERITY_WARNING);
            a.setTriggeredAt(OffsetDateTime.now(clock));

            Map<String, Object> detail = new HashMap<>();
            detail.put("thumbprint", ev.thumbprint());
            detail.put("endpointUrl", ev.endpointUrl());
            detail.put("subjectDn", ev.subjectDn());
            a.setDetail(detail);

            Alarm saved = alarms.save(a);
            metrics.incrementCreated(METRIC_TYPE);
            dispatcher.dispatch(saved);
        } catch (Throwable t) {
            log.warn("Failed to create OPC_UA_CERT_PENDING alarm for channel {}: {}",
                    ev.channelId(), t.toString());
        }
    }

    @EventListener
    public void onCertificateApproved(ChannelCertificateApprovedEvent ev) {
        try {
            Optional<Alarm> active = alarms.findActive(ev.channelId(), AlarmType.OPC_UA_CERT_PENDING);
            if (active.isEmpty()) return;
            Alarm a = active.get();
            sm.resolve(a, ResolvedReason.AUTO);
            alarms.save(a);
            metrics.incrementResolved("auto");
            dispatcher.dispatchResolved(a);
        } catch (Throwable t) {
            log.warn("Failed to auto-resolve OPC_UA_CERT_PENDING alarm for channel {}: {}",
                    ev.channelId(), t.toString());
        }
    }
}
