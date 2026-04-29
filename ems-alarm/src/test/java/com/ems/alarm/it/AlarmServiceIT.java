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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 告警完整生命周期集成测试（Testcontainers + PostgreSQL）。
 *
 * <p>跳过条件：Docker 引擎不可用（本机 Docker Desktop VM 未启动时自动跳过，
 * CI 环境 Docker 正常时自动运行）。</p>
 */
@Disabled("Docker Desktop VM engine not responding on this machine; re-enable in CI")
@SpringBootTest(classes = AlarmServiceIT.TestApp.class)
@ActiveProfiles("test")
class AlarmServiceIT {

    // 手动管理容器生命周期，避免 @ServiceConnection 在 ContextCustomizer 阶段提前访问 Docker
    static final PostgreSQLContainer<?> PG;

    static {
        boolean dockerAvailable = false;
        try {
            dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception ignored) {
        }
        if (dockerAvailable) {
            PG = new PostgreSQLContainer<>("postgres:15-alpine");
            PG.start();
        } else {
            PG = null;
        }
    }

    @DynamicPropertySource
    static void registerDataSource(DynamicPropertyRegistry registry) {
        if (PG != null) {
            registry.add("spring.datasource.url", PG::getJdbcUrl);
            registry.add("spring.datasource.username", PG::getUsername);
            registry.add("spring.datasource.password", PG::getPassword);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        } else {
            // Docker 不可用：禁用 DataSource 和 Flyway 自动配置，避免 Spring Context 启动失败。
            // 实际测试方法会被 @BeforeEach 的 assumeTrue 跳过。
            registry.add("spring.autoconfigure.exclude",
                    () -> "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                          "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
                          "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration," +
                          "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration");
        }
    }

    @MockBean
    CollectorService collector;

    @Autowired AlarmRepository alarms;
    @Autowired AlarmDetector detector;
    @Autowired AlarmStateMachine sm;
    @Autowired JdbcTemplate jdbc;

    Long meterId;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(PG != null,
                "Docker 不可用（本机 Docker Desktop VM 未启动）；CI 中自动运行");
    }

    @BeforeEach
    void seed() {
        if (PG == null) return;
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
        // 1) lastReadAt = 20min ago → 超过 silent timeout(600s) → 触发 SILENT_TIMEOUT 告警
        DeviceSnapshot snap = new DeviceSnapshot(
                "dev-it-1", "M-IT-001", DeviceState.HEALTHY,
                Instant.now().minusSeconds(1200), null, 0L, 0L, 0L, null);
        when(collector.snapshots()).thenReturn(List.of(snap));

        detector.scan();

        List<Alarm> active = alarms.findAll();
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getStatus()).isEqualTo(AlarmStatus.ACTIVE);
        assertThat(active.get(0).getDeviceId()).isEqualTo(meterId);

        // 2) ack
        Alarm a = active.get(0);
        sm.ack(a, 1L);
        alarms.save(a);
        assertThat(alarms.findById(a.getId()).orElseThrow().getStatus()).isEqualTo(AlarmStatus.ACKED);

        // 3) 把 triggeredAt 回拨超过 suppressionWindowSeconds(300s)，让 auto-resolve 生效
        a.setTriggeredAt(a.getTriggeredAt().minusSeconds(400));
        alarms.save(a);

        // 4) 提供 lastReadAt=now 的快照（不触发 silent 条件），scan → AUTO RESOLVED
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
