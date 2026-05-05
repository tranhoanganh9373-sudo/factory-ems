package com.ems.alarm.service;

import com.ems.alarm.dto.AlarmDTO;
import com.ems.alarm.dto.AlarmListItemDTO;
import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.AlarmType;
import com.ems.alarm.entity.ResolvedReason;
import com.ems.alarm.exception.AlarmNotFoundException;
import com.ems.alarm.observability.AlarmMetrics;
import com.ems.alarm.repository.AlarmRepository;
import org.springframework.data.jpa.domain.Specification;
import com.ems.alarm.service.impl.AlarmServiceImpl;
import com.ems.collector.channel.ChannelRepository;
import com.ems.collector.sink.DiagnosticRingBuffer;
import com.ems.core.dto.PageDTO;
import com.ems.meter.entity.Meter;
import com.ems.meter.repository.MeterRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AlarmServiceImplTest {

    private final AlarmRepository alarmRepo = mock(AlarmRepository.class);
    private final AlarmStateMachine stateMachine = mock(AlarmStateMachine.class);
    private final MeterRepository meterRepo = mock(MeterRepository.class);
    private final ChannelRepository channelRepo = mock(ChannelRepository.class);
    private final DiagnosticRingBuffer ring = new DiagnosticRingBuffer();
    private final ThresholdResolver thresholds = mock(ThresholdResolver.class);

    private final AlarmServiceImpl service = new AlarmServiceImpl(
            alarmRepo, stateMachine, meterRepo, channelRepo, ring, thresholds,
            AlarmMetrics.NOOP);

    // ── getById ──────────────────────────────────────────────────────────────

    @Test
    void getById_existing_returnsDtoWithMeterCodeAndName() {
        Alarm a = newAlarm(10L, 20L, AlarmStatus.ACTIVE);
        Meter m = newMeter(20L, "M-1", "Boiler");
        when(alarmRepo.findById(10L)).thenReturn(Optional.of(a));
        when(meterRepo.findById(20L)).thenReturn(Optional.of(m));

        AlarmDTO dto = service.getById(10L);

        assertThat(dto.id()).isEqualTo(10L);
        assertThat(dto.deviceId()).isEqualTo(20L);
        assertThat(dto.deviceCode()).isEqualTo("M-1");
        assertThat(dto.deviceName()).isEqualTo("Boiler");
        assertThat(dto.alarmType()).isEqualTo(AlarmType.SILENT_TIMEOUT);
        assertThat(dto.status()).isEqualTo(AlarmStatus.ACTIVE);
        assertThat(dto.triggeredAt()).isNotNull();
    }

    @Test
    void getById_meterMissing_fallsBackToUnknownAndEmptyName() {
        Alarm a = newAlarm(11L, 21L, AlarmStatus.ACTIVE);
        when(alarmRepo.findById(11L)).thenReturn(Optional.of(a));
        when(meterRepo.findById(21L)).thenReturn(Optional.empty());

        AlarmDTO dto = service.getById(11L);

        assertThat(dto.deviceCode()).isEqualTo("unknown");
        assertThat(dto.deviceName()).isEqualTo("");
    }

    @Test
    void getById_alarmNotFound_throws() {
        when(alarmRepo.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(99L))
                .isInstanceOf(AlarmNotFoundException.class)
                .hasMessageContaining("99");
    }

    // ── ack ──────────────────────────────────────────────────────────────────

    @Test
    void ack_existingActive_callsStateMachineAndSaves() {
        Alarm a = newAlarm(12L, 22L, AlarmStatus.ACTIVE);
        when(alarmRepo.findById(12L)).thenReturn(Optional.of(a));

        service.ack(12L, 42L);

        InOrder order = inOrder(alarmRepo, stateMachine);
        order.verify(alarmRepo).findById(12L);
        order.verify(stateMachine).ack(a, 42L);
        order.verify(alarmRepo).save(a);
    }

    @Test
    void ack_alarmNotFound_throwsAndDoesNotCallStateMachine() {
        when(alarmRepo.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ack(99L, 1L))
                .isInstanceOf(AlarmNotFoundException.class);

        verifyNoInteractions(stateMachine);
        verify(alarmRepo, never()).save(any());
    }

    // ── resolve ───────────────────────────────────────────────────────────────

    @Test
    void resolve_existing_callsStateMachineWithManualReason() {
        Alarm a = newAlarm(13L, 23L, AlarmStatus.ACTIVE);
        when(alarmRepo.findById(13L)).thenReturn(Optional.of(a));
        ArgumentCaptor<ResolvedReason> reasonCaptor = ArgumentCaptor.forClass(ResolvedReason.class);

        service.resolve(13L);

        InOrder order = inOrder(stateMachine, alarmRepo);
        order.verify(stateMachine).resolve(eq(a), reasonCaptor.capture());
        order.verify(alarmRepo).save(a);

        assertThat(reasonCaptor.getValue()).isEqualTo(ResolvedReason.MANUAL);
    }

    @Test
    void resolve_alarmNotFound_throws() {
        when(alarmRepo.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(99L))
                .isInstanceOf(AlarmNotFoundException.class);

        verify(alarmRepo, never()).save(any());
    }

    // ── countActive ───────────────────────────────────────────────────────────

    @Test
    void countActive_delegatesToRepo() {
        when(alarmRepo.countByStatus(AlarmStatus.ACTIVE)).thenReturn(7L);

        long result = service.countActive();

        assertThat(result).isEqualTo(7L);
    }

    // ── list ──────────────────────────────────────────────────────────────────

    @Test
    void list_passesFiltersAndPagingToRepo() {
        OffsetDateTime from = OffsetDateTime.now().minusDays(1);
        OffsetDateTime to = OffsetDateTime.now();
        Page<Alarm> emptyPage = new PageImpl<>(Collections.emptyList(),
                PageRequest.of(1, 10, Sort.by("triggeredAt").descending()), 0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Specification<Alarm>> specCaptor = ArgumentCaptor.forClass(Specification.class);
        ArgumentCaptor<PageRequest> pageCaptor = ArgumentCaptor.forClass(PageRequest.class);

        when(alarmRepo.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(emptyPage);

        PageDTO<AlarmListItemDTO> result = service.list(
                AlarmStatus.ACTIVE, 5L, AlarmType.SILENT_TIMEOUT, from, to, 2, 10);

        verify(alarmRepo).findAll(specCaptor.capture(), pageCaptor.capture());
        assertThat(specCaptor.getValue()).isNotNull();

        PageRequest pr = pageCaptor.getValue();
        assertThat(pr.getPageNumber()).isEqualTo(1);  // zero-based: page 2 → index 1
        assertThat(pr.getPageSize()).isEqualTo(10);
        assertThat(pr.getSort()).isEqualTo(Sort.by("triggeredAt").descending());

        assertThat(result.total()).isEqualTo(0L);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(10);
    }

    // ── healthSummary ─────────────────────────────────────────────────────────

    @Test
    void healthSummary_onlyMetersWithRecentGoodSampleCountAsOnline() {
        // 3 meters: m1 有近期 GOOD 样本，m2 仅有 BAD 样本，m3 没绑通道。
        // 期望：online=1（仅 m1），offline=2（m2 + m3）。
        Meter m1 = newMeter(1L, "M1", "n1");
        m1.setChannelId(10L);
        m1.setChannelPointKey("aaa");
        Meter m2 = newMeter(2L, "M2", "n2");
        m2.setChannelId(10L);
        m2.setChannelPointKey("bbb");
        Meter m3 = newMeter(3L, "M3", "n3");  // 未绑通道

        when(meterRepo.findAll()).thenReturn(java.util.List.of(m1, m2, m3));
        when(thresholds.resolve(anyLong())).thenReturn(
            new ThresholdResolver.Resolved(60, 3, false));
        when(alarmRepo.countByStatus(any(AlarmStatus.class))).thenReturn(0L);
        when(alarmRepo.findAll(any(Specification.class), any(PageRequest.class)))
            .thenReturn(new PageImpl<>(java.util.List.of()));

        ring.record(new com.ems.collector.transport.Sample(
            10L, "aaa", java.time.Instant.now(),
            42.0, com.ems.collector.transport.Quality.GOOD, java.util.Map.of()));
        ring.record(new com.ems.collector.transport.Sample(
            10L, "bbb", java.time.Instant.now(),
            null, com.ems.collector.transport.Quality.BAD, java.util.Map.of()));

        var summary = service.healthSummary();

        assertThat(summary.onlineCount()).isEqualTo(1L);
        assertThat(summary.offlineCount()).isEqualTo(2L);
        assertThat(summary.maintenanceCount()).isZero();
    }

    @Test
    void healthSummary_staleGoodSampleCountsAsOffline() {
        // 单个 meter，最近一次 GOOD 样本超出 freshness 窗口 → 离线。
        Meter m = newMeter(1L, "M", "n");
        m.setChannelId(10L);
        m.setChannelPointKey("k");
        when(meterRepo.findAll()).thenReturn(java.util.List.of(m));
        when(thresholds.resolve(anyLong())).thenReturn(
            new ThresholdResolver.Resolved(60, 3, false));
        when(alarmRepo.countByStatus(any(AlarmStatus.class))).thenReturn(0L);
        when(alarmRepo.findAll(any(Specification.class), any(PageRequest.class)))
            .thenReturn(new PageImpl<>(java.util.List.of()));

        ring.record(new com.ems.collector.transport.Sample(
            10L, "k",
            java.time.Instant.now().minus(java.time.Duration.ofMinutes(10)),
            42.0, com.ems.collector.transport.Quality.GOOD, java.util.Map.of()));

        var summary = service.healthSummary();

        assertThat(summary.onlineCount()).isZero();
        assertThat(summary.offlineCount()).isEqualTo(1L);
    }

    @Test
    void healthSummary_maintenanceMetersExcludedFromOnlineOffline() {
        // m1 在维护中，即使有近期 GOOD 样本也只计 maintenance、不计 online。
        Meter m1 = newMeter(1L, "M1", "n1");
        m1.setChannelId(10L);
        m1.setChannelPointKey("aaa");
        Meter m2 = newMeter(2L, "M2", "n2");
        m2.setChannelId(10L);
        m2.setChannelPointKey("bbb");
        when(meterRepo.findAll()).thenReturn(java.util.List.of(m1, m2));
        when(thresholds.resolve(1L)).thenReturn(
            new ThresholdResolver.Resolved(60, 3, true));
        when(thresholds.resolve(2L)).thenReturn(
            new ThresholdResolver.Resolved(60, 3, false));
        when(alarmRepo.countByStatus(any(AlarmStatus.class))).thenReturn(0L);
        when(alarmRepo.findAll(any(Specification.class), any(PageRequest.class)))
            .thenReturn(new PageImpl<>(java.util.List.of()));

        ring.record(new com.ems.collector.transport.Sample(
            10L, "aaa", java.time.Instant.now(),
            42.0, com.ems.collector.transport.Quality.GOOD, java.util.Map.of()));
        ring.record(new com.ems.collector.transport.Sample(
            10L, "bbb", java.time.Instant.now(),
            42.0, com.ems.collector.transport.Quality.GOOD, java.util.Map.of()));

        var summary = service.healthSummary();

        assertThat(summary.maintenanceCount()).isEqualTo(1L);
        assertThat(summary.onlineCount()).isEqualTo(1L);
        assertThat(summary.offlineCount()).isZero();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Alarm newAlarm(Long id, Long deviceId, AlarmStatus status) {
        Alarm a = new Alarm();
        a.setId(id);
        a.setDeviceId(deviceId);
        a.setStatus(status);
        a.setAlarmType(AlarmType.SILENT_TIMEOUT);
        a.setTriggeredAt(OffsetDateTime.now());
        return a;
    }

    private Meter newMeter(Long id, String code, String name) {
        Meter m = new Meter();
        m.setId(id);
        m.setCode(code);
        m.setName(name);
        return m;
    }
}
