package com.ems.alarm.it;

import com.ems.alarm.config.AlarmModuleConfig;
import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.AlarmType;
import com.ems.alarm.entity.TopologyConsistencyHistory;
import com.ems.alarm.repository.AlarmRepository;
import com.ems.alarm.repository.TopologyConsistencyHistoryRepository;
import com.ems.alarm.service.TopologyConsistencyAlarmService;
import com.ems.dashboard.dto.TopologyConsistencyDTO;
import com.ems.dashboard.service.DashboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Disabled("Testcontainers + Docker Desktop 本地兼容问题，CI 环境删除此注解即可")
@SpringBootTest(classes = TopologyConsistencyAlarmIT.TestApp.class)
@Testcontainers
@ActiveProfiles("test")
class TopologyConsistencyAlarmIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    @MockBean DashboardService dashboardService;

    @Autowired TopologyConsistencyAlarmService svc;
    @Autowired AlarmRepository alarmRepository;
    @Autowired TopologyConsistencyHistoryRepository historyRepository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM topology_consistency_history");
        jdbc.update("DELETE FROM alarm_inbox");
        jdbc.update("DELETE FROM alarms");
    }

    @Test
    void enter_writesAlarmAndHistoryRow() {
        TopologyConsistencyDTO bad = new TopologyConsistencyDTO(
                42L, "MAIN-A", "厂区主进线", "ELEC", "kWh",
                1000.0, 1180.0, 3, -180.0, -0.18, "ALARM");
        when(dashboardService.topologyConsistency(any())).thenReturn(List.of(bad));

        svc.runOnce();

        Optional<Alarm> active = alarmRepository.findActive(42L, AlarmType.TOPOLOGY_NEGATIVE_RESIDUAL);
        assertThat(active).isPresent();
        assertThat(active.get().getStatus()).isEqualTo(AlarmStatus.ACTIVE);
        assertThat(active.get().getSeverity()).isEqualTo("MEDIUM");

        List<TopologyConsistencyHistory> rows = historyRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getParentMeterId()).isEqualTo(42L);
    }

    @Test
    void exit_autoAcksExistingAlarm() {
        TopologyConsistencyDTO bad = new TopologyConsistencyDTO(
                43L, "MAIN-B", "B 进线", "ELEC", "kWh",
                1000.0, 1180.0, 3, -180.0, -0.18, "ALARM");
        when(dashboardService.topologyConsistency(any())).thenReturn(List.of(bad));
        svc.runOnce();

        TopologyConsistencyDTO recovered = new TopologyConsistencyDTO(
                43L, "MAIN-B", "B 进线", "ELEC", "kWh",
                1000.0, 1020.0, 3, -20.0, -0.02, "INFO");
        Mockito.reset(dashboardService);
        when(dashboardService.topologyConsistency(any())).thenReturn(List.of(recovered));
        svc.runOnce();

        Optional<Alarm> stillActive = alarmRepository.findActive(43L, AlarmType.TOPOLOGY_NEGATIVE_RESIDUAL);
        assertThat(stillActive).isEmpty();
        assertThat(historyRepository.findAll()).hasSize(2);
    }

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackages = {"com.ems.alarm.entity", "com.ems.meter.entity", "com.ems.auth.entity"})
    @EnableJpaRepositories(basePackages = {
            "com.ems.alarm.repository",
            "com.ems.meter.repository",
            "com.ems.auth.repository"
    })
    @ComponentScan(basePackages = {
            "com.ems.alarm.service",
            "com.ems.alarm.config"
    })
    @Import(AlarmModuleConfig.class)
    static class TestApp {
    }
}
