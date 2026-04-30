package com.ems.alarm.it;

import com.ems.alarm.config.AlarmModuleConfig;
import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.ResolvedReason;
import com.ems.alarm.repository.AlarmRepository;
import com.ems.alarm.service.AlarmDetector;
import com.ems.alarm.service.AlarmStateMachine;
import com.ems.collector.poller.DeviceSnapshot;
import com.ems.collector.poller.DeviceState;
import com.ems.collector.service.CollectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
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

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 告警完整生命周期集成测试（Testcontainers + PostgreSQL）。
 *
 * <p>本地 macOS Docker Desktop + docker-java 兼容问题（plan §7.2 已记录）会导致
 * Testcontainers 找不到合法的 Docker 环境。FloorplanServiceIT 同样在本机被 skip。
 * CI 中（Linux + 完整 Docker 引擎）应删除 {@code @Disabled} 让本测试自动运行。
 */
@Disabled("Testcontainers + Docker Desktop 本地兼容问题，CI 环境删除此注解即可")
@SpringBootTest(classes = AlarmServiceIT.TestApp.class)
@Testcontainers
@ActiveProfiles("test")
class AlarmServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    @MockBean CollectorService collector;
    @Autowired AlarmRepository alarms;
    @Autowired AlarmDetector detector;
    @Autowired AlarmStateMachine sm;
    @Autowired JdbcTemplate jdbc;

    Long meterId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM alarm_inbox");
        jdbc.update("DELETE FROM webhook_delivery_log");
        jdbc.update("DELETE FROM alarms");
        jdbc.update("DELETE FROM alarm_rules_override");
        jdbc.update("DELETE FROM meters WHERE code='M-IT-001'");
        jdbc.update("DELETE FROM org_nodes WHERE code='IT_FACTORY'");

        Long orgNodeId = jdbc.queryForObject(
                "INSERT INTO org_nodes (name, code, node_type) VALUES ('IT Factory','IT_FACTORY','FACTORY') RETURNING id",
                Long.class);

        Long energyTypeId;
        try {
            energyTypeId = jdbc.queryForObject("SELECT id FROM energy_types WHERE code='ELEC'", Long.class);
        } catch (Exception e) {
            energyTypeId = jdbc.queryForObject(
                    "INSERT INTO energy_types (code, name, unit) VALUES ('ELEC','电','kWh') RETURNING id",
                    Long.class);
        }

        meterId = jdbc.queryForObject(
                "INSERT INTO meters (code, name, energy_type_id, org_node_id, " +
                "influx_measurement, influx_tag_key, influx_tag_value) " +
                "VALUES ('M-IT-001','IT meter',?,?,'power','device','it001') RETURNING id",
                Long.class, energyTypeId, orgNodeId);
    }

    @Test
    void fullLifecycle_triggerAckRecover() {
        DeviceSnapshot snap = new DeviceSnapshot(
                "dev-it-1", "M-IT-001", DeviceState.HEALTHY,
                Instant.now().minusSeconds(1200), null, 0L, 0L, 0L, null);
        when(collector.snapshots()).thenReturn(List.of(snap));

        detector.scan();

        List<Alarm> active = alarms.findAll();
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getStatus()).isEqualTo(AlarmStatus.ACTIVE);
        assertThat(active.get(0).getDeviceId()).isEqualTo(meterId);

        Alarm a = active.get(0);
        sm.ack(a, 1L);
        alarms.save(a);
        assertThat(alarms.findById(a.getId()).orElseThrow().getStatus()).isEqualTo(AlarmStatus.ACKED);

        a.setTriggeredAt(a.getTriggeredAt().minusSeconds(400));
        alarms.save(a);

        DeviceSnapshot fresh = new DeviceSnapshot(
                "dev-it-1", "M-IT-001", DeviceState.HEALTHY,
                Instant.now(), null, 0L, 1L, 0L, null);
        when(collector.snapshots()).thenReturn(List.of(fresh));

        detector.scan();

        Alarm finalAlarm = alarms.findById(a.getId()).orElseThrow();
        assertThat(finalAlarm.getStatus()).isEqualTo(AlarmStatus.RESOLVED);
        assertThat(finalAlarm.getResolvedReason()).isEqualTo(ResolvedReason.AUTO);
    }

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackages = {"com.ems.alarm.entity", "com.ems.meter.entity"})
    @EnableJpaRepositories(basePackages = {
            "com.ems.alarm.repository",
            "com.ems.meter.repository"
    })
    @ComponentScan(basePackages = {
            "com.ems.alarm.service",
            "com.ems.alarm.config"
    })
    @Import(AlarmModuleConfig.class)
    static class TestApp {
    }
}
