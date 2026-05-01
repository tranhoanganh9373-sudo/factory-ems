package com.ems.app.migration;

import com.ems.app.FactoryEmsApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @ParameterizedTest
    @ValueSource(strings = { "channel", "collector_metrics" })
    @DisplayName("V2.3.0 迁移后表存在")
    void tableExists_afterV230Migration_returnsOne(String tableName) {
        var count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = ?",
                Integer.class, tableName);
        assertThat(count).isEqualTo(1);
    }
}
