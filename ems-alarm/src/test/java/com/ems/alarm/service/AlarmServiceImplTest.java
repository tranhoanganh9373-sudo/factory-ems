package com.ems.alarm.service;

import com.ems.alarm.dto.AlarmDTO;
import com.ems.alarm.dto.AlarmListItemDTO;
import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.AlarmType;
import com.ems.alarm.entity.ResolvedReason;
import com.ems.alarm.exception.AlarmNotFoundException;
import com.ems.alarm.repository.AlarmRepository;
import com.ems.alarm.service.impl.AlarmServiceImpl;
import com.ems.collector.service.CollectorService;
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
    private final CollectorService collectorService = mock(CollectorService.class);
    private final ThresholdResolver thresholds = mock(ThresholdResolver.class);

    private final AlarmServiceImpl service = new AlarmServiceImpl(
            alarmRepo, stateMachine, meterRepo, collectorService, thresholds);

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

        ArgumentCaptor<AlarmStatus> statusCaptor = ArgumentCaptor.forClass(AlarmStatus.class);
        ArgumentCaptor<Long> deviceIdCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<AlarmType> typeCaptor = ArgumentCaptor.forClass(AlarmType.class);
        ArgumentCaptor<OffsetDateTime> fromCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> toCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<PageRequest> pageCaptor = ArgumentCaptor.forClass(PageRequest.class);

        when(alarmRepo.search(any(), any(), any(), any(), any(), any())).thenReturn(emptyPage);

        PageDTO<AlarmListItemDTO> result = service.list(
                AlarmStatus.ACTIVE, 5L, AlarmType.SILENT_TIMEOUT, from, to, 2, 10);

        verify(alarmRepo).search(
                statusCaptor.capture(), deviceIdCaptor.capture(), typeCaptor.capture(),
                fromCaptor.capture(), toCaptor.capture(), pageCaptor.capture());

        assertThat(statusCaptor.getValue()).isEqualTo(AlarmStatus.ACTIVE);
        assertThat(deviceIdCaptor.getValue()).isEqualTo(5L);
        assertThat(typeCaptor.getValue()).isEqualTo(AlarmType.SILENT_TIMEOUT);
        assertThat(fromCaptor.getValue()).isEqualTo(from);
        assertThat(toCaptor.getValue()).isEqualTo(to);

        PageRequest pr = pageCaptor.getValue();
        assertThat(pr.getPageNumber()).isEqualTo(1);  // zero-based: page 2 → index 1
        assertThat(pr.getPageSize()).isEqualTo(10);
        assertThat(pr.getSort()).isEqualTo(Sort.by("triggeredAt").descending());

        assertThat(result.total()).isEqualTo(0L);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(10);
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
