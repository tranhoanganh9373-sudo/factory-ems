package com.ems.meter.repository;

import com.ems.meter.entity.CarbonFactor;
import com.ems.meter.entity.EnergySource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Disabled("Local Mac docker-java incompatibility; CI Linux runner enables")
@SpringBootTest(classes = CarbonFactorRepositoryIT.TestApp.class)
@Testcontainers
@ActiveProfiles("test")
class CarbonFactorRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    CarbonFactorRepository repo;

    @BeforeEach
    void cleanup() {
        repo.deleteAll();
    }

    @Test
    void findEffective_returnsMostRecentEffectiveRow() {
        // Arrange — two rows for same region+source, different effectiveFrom
        repo.save(new CarbonFactor("CN-EAST", EnergySource.GRID,
                LocalDate.of(2024, 1, 1), new BigDecimal("0.5000")));
        repo.save(new CarbonFactor("CN-EAST", EnergySource.GRID,
                LocalDate.of(2025, 1, 1), new BigDecimal("0.4800")));

        // Act — asOf >= both rows
        Optional<CarbonFactor> result = repo.findEffective("CN-EAST", EnergySource.GRID,
                LocalDate.of(2025, 6, 1));

        // Assert — returns the row with the later effectiveFrom
        assertThat(result).isPresent();
        assertThat(result.get().getEffectiveFrom()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(result.get().getFactorKgPerKwh()).isEqualByComparingTo("0.4800");
    }

    @Test
    void findEffective_returnsEmptyWhenAsOfBeforeAnyRow() {
        // Arrange
        repo.save(new CarbonFactor("CN-EAST", EnergySource.GRID,
                LocalDate.of(2025, 1, 1), new BigDecimal("0.5000")));

        // Act — asOf is before effectiveFrom
        Optional<CarbonFactor> result = repo.findEffective("CN-EAST", EnergySource.GRID,
                LocalDate.of(2024, 12, 31));

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void findEffective_includesRowWithEqualEffectiveFrom() {
        // Arrange
        repo.save(new CarbonFactor("CN-EAST", EnergySource.SOLAR,
                LocalDate.of(2025, 1, 1), new BigDecimal("0.0500")));

        // Act — asOf == effectiveFrom (boundary: <= not <)
        Optional<CarbonFactor> result = repo.findEffective("CN-EAST", EnergySource.SOLAR,
                LocalDate.of(2025, 1, 1));

        // Assert
        assertThat(result).isPresent();
        assertThat(result.get().getEffectiveFrom()).isEqualTo(LocalDate.of(2025, 1, 1));
    }

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.ems.meter.entity")
    @EnableJpaRepositories(basePackages = "com.ems.meter.repository")
    static class TestApp {}
}
