package com.ems.alarm.service;

import com.ems.alarm.config.AlarmProperties;
import com.ems.alarm.entity.*;
import com.ems.alarm.repository.AlarmRepository;
import com.ems.alarm.service.impl.AlarmDetectorImpl;
import com.ems.collector.poller.DeviceSnapshot;
import com.ems.collector.poller.DeviceState;
import com.ems.collector.service.CollectorService;
import com.ems.meter.entity.Meter;
import com.ems.meter.repository.MeterRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AlarmDetectorTest {
    private final AlarmProperties props = new AlarmProperties(600, 3, 60, 300, 3, List.of(10, 60, 300), 5000);
    private final CollectorService collector = mock(CollectorService.class);
    private final MeterRepository meters = mock(MeterRepository.class);
    private final AlarmRepository alarms = mock(AlarmRepository.class);
    private final ThresholdResolver thresholds = mock(ThresholdResolver.class);
    private final AlarmStateMachine sm = new AlarmStateMachine();
    private final AlarmDispatcher dispatcher = mock(AlarmDispatcher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-04-29T10:00:00Z"), ZoneId.of("UTC"));

    private final AlarmDetectorImpl detector = new AlarmDetectorImpl(
            collector, meters, alarms, thresholds, sm, dispatcher, props, clock);

    @Test
    void silentTimeout_triggersAlarm() {
        DeviceSnapshot snap = snap("dev-1", "M-001", DeviceState.HEALTHY,
                Instant.parse("2026-04-29T09:49:00Z"), 0);  // 11min ago > 10min threshold
        when(collector.snapshots()).thenReturn(List.of(snap));
        when(meters.findByCode("M-001")).thenReturn(Optional.of(meter(1L, "M-001")));
        when(thresholds.resolve(1L)).thenReturn(new ThresholdResolver.Resolved(600, 3, false));
        when(alarms.findActive(eq(1L), eq(AlarmType.SILENT_TIMEOUT))).thenReturn(Optional.empty());
        when(alarms.save(any(Alarm.class))).thenAnswer(inv -> inv.getArgument(0));

        detector.scan();

        verify(alarms).save(argThat(a ->
                a.getAlarmType() == AlarmType.SILENT_TIMEOUT
                && a.getStatus() == AlarmStatus.ACTIVE
                && a.getDeviceId().equals(1L)));
        verify(dispatcher).dispatch(any());
    }

    @Test
    void neverSeenBefore_doesNotTrigger() {
        DeviceSnapshot snap = snap("dev-1", "M-001", DeviceState.HEALTHY, null, 0);
        when(collector.snapshots()).thenReturn(List.of(snap));
        when(meters.findByCode("M-001")).thenReturn(Optional.of(meter(1L, "M-001")));
        when(thresholds.resolve(1L)).thenReturn(new ThresholdResolver.Resolved(600, 3, false));
        when(alarms.findActive(any(), any())).thenReturn(Optional.empty());

        detector.scan();

        verify(alarms, never()).save(any());
    }

    @Test
    void consecutiveFail_triggersAlarm() {
        DeviceSnapshot snap = snap("dev-1", "M-001", DeviceState.DEGRADED,
                Instant.parse("2026-04-29T09:59:00Z"), 5);  // ≥ 3 fails
        when(collector.snapshots()).thenReturn(List.of(snap));
        when(meters.findByCode("M-001")).thenReturn(Optional.of(meter(1L, "M-001")));
        when(thresholds.resolve(1L)).thenReturn(new ThresholdResolver.Resolved(600, 3, false));
        when(alarms.findActive(eq(1L), eq(AlarmType.CONSECUTIVE_FAIL))).thenReturn(Optional.empty());
        when(alarms.save(any(Alarm.class))).thenAnswer(inv -> inv.getArgument(0));

        detector.scan();
        verify(alarms).save(argThat(a -> a.getAlarmType() == AlarmType.CONSECUTIVE_FAIL));
    }

    @Test
    void maintenanceMode_skipsDevice() {
        DeviceSnapshot snap = snap("dev-1", "M-001", DeviceState.UNREACHABLE,
                Instant.parse("2026-04-29T08:00:00Z"), 100);
        when(collector.snapshots()).thenReturn(List.of(snap));
        when(meters.findByCode("M-001")).thenReturn(Optional.of(meter(1L, "M-001")));
        when(thresholds.resolve(1L)).thenReturn(new ThresholdResolver.Resolved(600, 3, true));

        detector.scan();
        verify(alarms, never()).save(any());
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void existingActive_doesNotDuplicate() {
        DeviceSnapshot snap = snap("dev-1", "M-001", DeviceState.UNREACHABLE,
                Instant.parse("2026-04-29T09:00:00Z"), 5);
        when(collector.snapshots()).thenReturn(List.of(snap));
        when(meters.findByCode("M-001")).thenReturn(Optional.of(meter(1L, "M-001")));
        when(thresholds.resolve(1L)).thenReturn(new ThresholdResolver.Resolved(600, 3, false));
        Alarm existing = new Alarm();
        existing.setId(99L);
        existing.setStatus(AlarmStatus.ACTIVE);
        existing.setTriggeredAt(OffsetDateTime.parse("2026-04-29T09:01:00Z"));
        when(alarms.findActive(eq(1L), any())).thenReturn(Optional.of(existing));

        detector.scan();
        verify(alarms, never()).save(argThat(a -> a.getId() == null));
    }

    @Test
    void recoveryAfterSuppressionWindow_autoResolves() {
        DeviceSnapshot snap = snap("dev-1", "M-001", DeviceState.HEALTHY,
                Instant.parse("2026-04-29T09:59:30Z"), 0);  // recent + healthy
        when(collector.snapshots()).thenReturn(List.of(snap));
        when(meters.findByCode("M-001")).thenReturn(Optional.of(meter(1L, "M-001")));
        when(thresholds.resolve(1L)).thenReturn(new ThresholdResolver.Resolved(600, 3, false));

        Alarm active = new Alarm();
        active.setId(99L);
        active.setStatus(AlarmStatus.ACTIVE);
        active.setTriggeredAt(OffsetDateTime.parse("2026-04-29T09:50:00Z"));  // 10min ago > 5min window
        when(alarms.findActive(eq(1L), eq(AlarmType.SILENT_TIMEOUT))).thenReturn(Optional.of(active));
        when(alarms.findActive(eq(1L), eq(AlarmType.CONSECUTIVE_FAIL))).thenReturn(Optional.empty());
        when(alarms.save(any(Alarm.class))).thenAnswer(inv -> inv.getArgument(0));

        detector.scan();
        verify(alarms).save(argThat(a -> a.getStatus() == AlarmStatus.RESOLVED
                                       && a.getResolvedReason() == ResolvedReason.AUTO));
    }

    private DeviceSnapshot snap(String id, String code, DeviceState state,
                                Instant lastReadAt, long consecutiveErrors) {
        return new DeviceSnapshot(id, code, state, lastReadAt, null,
                consecutiveErrors, 0L, 0L, null);
    }

    private Meter meter(Long id, String code) {
        Meter m = new Meter();
        m.setId(id);
        m.setCode(code);
        return m;
    }
}
