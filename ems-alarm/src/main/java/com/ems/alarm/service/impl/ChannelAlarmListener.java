package com.ems.alarm.service.impl;

import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.AlarmType;
import com.ems.alarm.entity.ResolvedReason;
import com.ems.alarm.observability.AlarmMetrics;
import com.ems.alarm.repository.AlarmRepository;
import com.ems.alarm.service.AlarmDispatcher;
import com.ems.alarm.service.AlarmStateMachine;
import com.ems.collector.runtime.ChannelFailureEvent;
import com.ems.collector.runtime.ChannelRecoveredEvent;
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
 * 监听 {@link com.ems.collector.runtime.ChannelStateRegistry} 发布的 channel 故障/恢复事件，
 * 创建 / 解除 {@link AlarmType#COMMUNICATION_FAULT} 告警。
 *
 * <p>设计：channel-scoped — alarm.deviceType="CHANNEL"，alarm.deviceId=channelId。
 * 这样在 meter 绑定之前就能告警。
 *
 * <p>幂等：同一 channel 同时只有一条 ACTIVE/ACKED 的 COMMUNICATION_FAULT。
 *
 * <p>异常隔离：listener 抛出会被 publisher catch 并 log warn（见
 * {@code ChannelStateRegistry#safePublish}），但本类内部仍包一层 try/catch 保险。
 */
@Component
public class ChannelAlarmListener {

    private static final Logger log = LoggerFactory.getLogger(ChannelAlarmListener.class);

    private static final String DEVICE_TYPE_CHANNEL = "CHANNEL";
    private static final String SEVERITY_WARNING = "WARNING";
    private static final String METRIC_TYPE = "communication_fault";

    private final AlarmRepository alarms;
    private final AlarmStateMachine sm;
    private final AlarmDispatcher dispatcher;
    private final AlarmMetrics metrics;
    private final Clock clock;

    public ChannelAlarmListener(AlarmRepository alarms,
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
    public void onChannelFailure(ChannelFailureEvent ev) {
        try {
            Optional<Alarm> existing = alarms.findActive(ev.channelId(), AlarmType.COMMUNICATION_FAULT);
            if (existing.isPresent()) return;

            Alarm a = new Alarm();
            a.setDeviceType(DEVICE_TYPE_CHANNEL);
            a.setDeviceId(ev.channelId());
            a.setAlarmType(AlarmType.COMMUNICATION_FAULT);
            a.setStatus(AlarmStatus.ACTIVE);
            a.setSeverity(SEVERITY_WARNING);
            a.setTriggeredAt(OffsetDateTime.now(clock));

            Map<String, Object> detail = new HashMap<>();
            detail.put("protocol", ev.protocol());
            detail.put("errorMessage", ev.errorMessage());
            detail.put("consecutiveFailures", ev.consecutiveFailures());
            a.setDetail(detail);

            Alarm saved = alarms.save(a);
            metrics.incrementCreated(METRIC_TYPE);
            dispatcher.dispatch(saved);
        } catch (Throwable t) {
            log.warn("Failed to create COMMUNICATION_FAULT alarm for channel {}: {}",
                    ev.channelId(), t.toString());
        }
    }

    @EventListener
    public void onChannelRecovered(ChannelRecoveredEvent ev) {
        try {
            Optional<Alarm> active = alarms.findActive(ev.channelId(), AlarmType.COMMUNICATION_FAULT);
            if (active.isEmpty()) return;
            Alarm a = active.get();
            sm.resolve(a, ResolvedReason.AUTO);
            alarms.save(a);
            metrics.incrementResolved("auto");
            dispatcher.dispatchResolved(a);
        } catch (Throwable t) {
            log.warn("Failed to auto-resolve COMMUNICATION_FAULT alarm for channel {}: {}",
                    ev.channelId(), t.toString());
        }
    }
}
