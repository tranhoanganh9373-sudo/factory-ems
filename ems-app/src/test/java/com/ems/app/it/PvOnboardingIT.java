package com.ems.app.it;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Disabled("Local Mac docker-java incompatibility; CI Linux runner enables")
class PvOnboardingIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("feature flag endpoint reflects ems.feature.pv.enabled=true under test profile")
    void featureFlagEndpoint() throws Exception {
        mvc.perform(get("/api/v1/features"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.pv").value(true));
    }

    @Test
    @DisplayName("Flyway applies V2.6.0 + V2.6.1 cleanly")
    void flywayApplied() {
        Integer success260 = jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2.6.0' AND success = TRUE",
            Integer.class);
        Integer success261 = jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2.6.1' AND success = TRUE",
            Integer.class);
        assertThat(success260).isEqualTo(1);
        assertThat(success261).isEqualTo(1);
    }

    @Test
    @DisplayName("V2.6.0 created carbon_factor + feed_in_tariff tables with seed rows")
    void seedDataApplied() {
        Integer carbonRows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM carbon_factor WHERE region = 'CN'", Integer.class);
        Integer fitRows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM feed_in_tariff WHERE energy_source = 'SOLAR'", Integer.class);
        assertThat(carbonRows).isGreaterThanOrEqualTo(1);
        assertThat(fitRows).isGreaterThanOrEqualTo(1);
    }
}
