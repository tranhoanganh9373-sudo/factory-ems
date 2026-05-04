package com.ems.alarm.service.impl;

import com.ems.alarm.config.TopologyAlarmProperties;
import com.ems.alarm.dto.TopologyTransition;
import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.AlarmType;
import com.ems.alarm.repository.AlarmRepository;
import com.ems.dashboard.dto.TopologyConsistencyDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TopologyConsistencyAlarmServiceImplTest {

    @Mock
    AlarmRepository alarmRepository;

    TopologyAlarmProperties props;
    TopologyConsistencyAlarmServiceImpl svc;

    @BeforeEach
    void setUp() {
        props = new TopologyAlarmProperties(false, "0 5 * * * *", "0 15 3 * * *", 7, -0.15, -0.10);
        svc = new TopologyConsistencyAlarmServiceImpl(alarmRepository, null, null, null, props);
    }

    private TopologyConsistencyDTO row(long parentId, double ratio, String severity) {
        double parentReading = 1000.0;
        double residual = ratio * parentReading;
        double childrenSum = parentReading - residual;
        return new TopologyConsistencyDTO(parentId, "MAIN-" + parentId, "Main " + parentId,
                "ELEC", "kWh", parentReading, childrenSum, 3, residual, ratio, severity);
    }

    @Test
    @DisplayName("ENTER: ratio crosses -15% with no active alarm")
    void enter_whenRatioCrossesEnterThreshold_andNoActiveAlarm() {
        var dto = row(42L, -0.18, "ALARM");
        when(alarmRepository.findActive(eq(42L), eq(AlarmType.TOPOLOGY_NEGATIVE_RESIDUAL)))
                .thenReturn(Optional.empty());

        var result = svc.classify(List.of(dto));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOf(TopologyTransition.Enter.class);
    }

    @Test
    @DisplayName("NOOP: ratio in -10%..-15% band with no active alarm (still QUIET)")
    void noop_whenRatioInBufferZone_andNoActiveAlarm() {
        var dto = row(42L, -0.12, "WARN_NEGATIVE");
        when(alarmRepository.findActive(eq(42L), eq(AlarmType.TOPOLOGY_NEGATIVE_RESIDUAL)))
                .thenReturn(Optional.empty());

        var result = svc.classify(List.of(dto));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOf(TopologyTransition.NoOp.class);
    }

    @Test
    @DisplayName("SUSTAIN: active alarm exists and ratio still below -10%")
    void sustain_whenActiveAlarmAndRatioStillBelowExit() {
        var dto = row(42L, -0.13, "WARN_NEGATIVE");
        Alarm existing = new Alarm();
        existing.setId(99L);
        existing.setStatus(AlarmStatus.ACTIVE);
        when(alarmRepository.findActive(eq(42L), eq(AlarmType.TOPOLOGY_NEGATIVE_RESIDUAL)))
                .thenReturn(Optional.of(existing));

        var result = svc.classify(List.of(dto));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOfSatisfying(TopologyTransition.Sustain.class,
                t -> assertThat(t.alarmId()).isEqualTo(99L));
    }

    @Test
    @DisplayName("EXIT: active alarm exists and ratio recovered above -10%")
    void exit_whenActiveAlarmAndRatioRecoveredAboveExit() {
        var dto = row(42L, -0.05, "WARN");
        Alarm existing = new Alarm();
        existing.setId(99L);
        existing.setStatus(AlarmStatus.ACTIVE);
        when(alarmRepository.findActive(eq(42L), eq(AlarmType.TOPOLOGY_NEGATIVE_RESIDUAL)))
                .thenReturn(Optional.of(existing));

        var result = svc.classify(List.of(dto));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOfSatisfying(TopologyTransition.Exit.class,
                t -> assertThat(t.alarmId()).isEqualTo(99L));
    }

    @Test
    @DisplayName("Boundary: ratio = -0.15 exactly is treated as ENTER (≤ inclusive)")
    void enter_whenRatioEqualsEnterThresholdExactly() {
        var dto = row(42L, -0.15, "ALARM");
        when(alarmRepository.findActive(any(), any())).thenReturn(Optional.empty());

        var result = svc.classify(List.of(dto));

        assertThat(result.get(0)).isInstanceOf(TopologyTransition.Enter.class);
    }

    @Test
    @DisplayName("Boundary: ratio = -0.10 exactly with active alarm is treated as SUSTAIN")
    void sustain_whenRatioEqualsExitThresholdExactly() {
        var dto = row(42L, -0.10, "WARN_NEGATIVE");
        Alarm existing = new Alarm();
        existing.setId(99L);
        existing.setStatus(AlarmStatus.ACTIVE);
        when(alarmRepository.findActive(any(), any())).thenReturn(Optional.of(existing));

        var result = svc.classify(List.of(dto));

        assertThat(result.get(0)).isInstanceOf(TopologyTransition.Sustain.class);
    }

    @Test
    @DisplayName("Positive residual rows are NoOp (no transition emitted)")
    void noTransition_whenRatioPositive() {
        var dto = row(42L, 0.03, "INFO");
        when(alarmRepository.findActive(any(), any())).thenReturn(Optional.empty());

        var result = svc.classify(List.of(dto));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOf(TopologyTransition.NoOp.class);
    }

    @Test
    @DisplayName("Null residualRatio (parent=0) is NoOp")
    void noTransition_whenRatioNull() {
        var dto = new TopologyConsistencyDTO(42L, "MAIN-42", "Main", "ELEC", "kWh",
                0.0, 5.0, 1, 5.0, null, "INFO");
        when(alarmRepository.findActive(any(), any())).thenReturn(Optional.empty());

        var result = svc.classify(List.of(dto));

        assertThat(result.get(0)).isInstanceOf(TopologyTransition.NoOp.class);
    }
}
