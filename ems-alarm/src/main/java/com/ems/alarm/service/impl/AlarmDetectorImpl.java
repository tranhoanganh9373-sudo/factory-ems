package com.ems.alarm.service.impl;

import com.ems.alarm.config.AlarmProperties;
import com.ems.alarm.entity.*;
import com.ems.alarm.observability.AlarmMetrics;
import com.ems.alarm.repository.AlarmRepository;
import com.ems.alarm.service.AlarmDetector;
import com.ems.alarm.service.AlarmDispatcher;
import com.ems.alarm.service.AlarmStateMachine;
import com.ems.alarm.service.ThresholdResolver;
import com.ems.collector.poller.DeviceSnapshot;
import com.ems.collector.service.CollectorService;
import com.ems.meter.entity.Meter;
import com.ems.meter.repository.MeterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class AlarmDetectorImpl implements AlarmDetector {

    private static final Logger log = LoggerFactory.getLogger(AlarmDetectorImpl.class);

    private final CollectorService collector;
    private final MeterRepository meters;
    private final AlarmRepository alarms;
    private final ThresholdResolver thresholds;
    private final AlarmStateMachine sm;
    private final AlarmDispatcher dispatcher;
    private final AlarmProperties props;
    private final Clock clock;
    private final AlarmMetrics metrics;

    public AlarmDetectorImpl(CollectorService collector, MeterRepository meters,
                             AlarmRepository alarms, ThresholdResolver thresholds,
                             AlarmStateMachine sm, AlarmDispatcher dispatcher,
                             AlarmProperties props, Clock clock,
                             AlarmMetrics metrics) {
        this.collector = collector;
        this.meters = meters;
        this.alarms = alarms;
        this.thresholds = thresholds;
        this.sm = sm;
        this.dispatcher = dispatcher;
        this.props = props;
        this.clock = clock;
        this.metrics = metrics == null ? AlarmMetrics.NOOP : metrics;
    }

    @Override
    @Scheduled(fixedDelayString = "#{${ems.alarm.poll-interval-seconds:60} * 1000}")
    public void scan() {
        long startNanos = System.nanoTime();
        try {
            for (DeviceSnapshot snap : collector.snapshots()) {
                try {
                    scanOne(snap);
                } catch (Exception e) {
                    log.warn("Scan failed for device {}: {}", snap.deviceId(), e.getMessage(), e);
                }
            }
        } finally {
            metrics.recordDetectorScan(Duration.ofNanos(System.nanoTime() - startNanos));
            refreshActiveGauges();
        }
    }

    /** 把 ACTIVE+ACKED 计数同步到 {@code ems.alarm.active.count{type}} gauge。 */
    private void refreshActiveGauges() {
        for (AlarmType t : AlarmType.values()) {
            long count = alarms.countActiveByType(t);
            metrics.setActive(t.name().toLowerCase(Locale.ROOT), count);
        }
    }

    private void scanOne(DeviceSnapshot snap) {
        Optional<Meter> meterOpt = meters.findByCode(snap.meterCode());
        if (meterOpt.isEmpty()) return;
        Long meterId = meterOpt.get().getId();

        ThresholdResolver.Resolved t = thresholds.resolve(meterId);
        if (t.maintenanceMode()) return;

        boolean silentHit = checkSilent(snap, t.silentTimeoutSeconds());
        boolean failHit   = snap.consecutiveErrors() >= t.consecutiveFailCount();

        AlarmType primaryType = silentHit ? AlarmType.SILENT_TIMEOUT
                              : failHit   ? AlarmType.CONSECUTIVE_FAIL
                              : null;

        if (primaryType != null) {
            Optional<Alarm> active = alarms.findActive(meterId, primaryType);
            if (active.isEmpty()) {
                fire(meterId, primaryType, snap, t);
            }
        } else {
            tryAutoResolve(meterId, AlarmType.SILENT_TIMEOUT);
            tryAutoResolve(meterId, AlarmType.CONSECUTIVE_FAIL);
        }
    }

    private boolean checkSilent(DeviceSnapshot snap, int thresholdSec) {
        if (snap.lastReadAt() == null) return false;
        Duration silent = Duration.between(snap.lastReadAt(), clock.instant());
        return silent.toSeconds() > thresholdSec;
    }

    private void fire(Long meterId, AlarmType type, DeviceSnapshot snap, ThresholdResolver.Resolved t) {
        Alarm a = new Alarm();
        a.setDeviceId(meterId);
        a.setDeviceType("METER");
        a.setAlarmType(type);
        a.setStatus(AlarmStatus.ACTIVE);
        a.setTriggeredAt(OffsetDateTime.now(clock));
        if (snap.lastReadAt() != null) {
            a.setLastSeenAt(OffsetDateTime.ofInstant(snap.lastReadAt(), ZoneOffset.UTC));
        }
        Map<String, Object> detail = new HashMap<>();
        detail.put("threshold_silent_seconds", t.silentTimeoutSeconds());
        detail.put("threshold_consecutive_fails", t.consecutiveFailCount());
        detail.put("snapshot_consecutive_errors", snap.consecutiveErrors());
        a.setDetail(detail);
        Alarm saved = alarms.save(a);
        metrics.incrementCreated(type.name().toLowerCase(Locale.ROOT));
        dispatcher.dispatch(saved);
    }

    private void tryAutoResolve(Long meterId, AlarmType type) {
        Optional<Alarm> active = alarms.findActive(meterId, type);
        if (active.isEmpty()) return;
        Alarm a = active.get();
        Duration since = Duration.between(a.getTriggeredAt().toInstant(), clock.instant());
        if (since.toSeconds() > props.suppressionWindowSeconds()) {
            sm.resolve(a, ResolvedReason.AUTO);
            alarms.save(a);
            metrics.incrementResolved("auto");
            dispatcher.dispatchResolved(a);
        }
    }
}
