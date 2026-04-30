package com.ems.app.migration;

import com.ems.app.FactoryEmsApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2.3.0 migration IT — 验证 channel + collector_metrics 表创建成功。
 * spec: docs/superpowers/specs/2026-04-30-collector-protocols-design.md
 */
@SpringBootTest(classes = FactoryEmsApplication.class)
@ActiveProfiles("test")
@Testcontainers
class ChannelMigrationIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void channelTableExists() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'channel'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void collectorMetricsTableExists() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'collector_metrics'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
