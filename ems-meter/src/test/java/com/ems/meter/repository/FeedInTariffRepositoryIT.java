package com.ems.meter.repository;

import com.ems.meter.entity.EnergySource;
import com.ems.meter.entity.FeedInTariff;
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
@SpringBootTest(classes = FeedInTariffRepositoryIT.TestApp.class)
@Testcontainers
@ActiveProfiles("test")
class FeedInTariffRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    FeedInTariffRepository repo;

    @BeforeEach
    void cleanup() {
        repo.deleteAll();
    }

    @Test
    void findEffective_returnsMostRecentEffectiveRow() {
        // Arrange — two rows for same region+source+period, different effectiveFrom
        repo.save(new FeedInTariff("CN-EAST", EnergySource.SOLAR, "PEAK",
                LocalDate.of(2024, 1, 1), new BigDecimal("0.3800")));
        repo.save(new FeedInTariff("CN-EAST", EnergySource.SOLAR, "PEAK",
                LocalDate.of(2025, 1, 1), new BigDecimal("0.4200")));

        // Act — asOf >= both rows
        Optional<FeedInTariff> result = repo.findEffective("CN-EAST", EnergySource.SOLAR,
                "PEAK", LocalDate.of(2025, 6, 1));

        // Assert — returns the row with the later effectiveFrom
        assertThat(result).isPresent();
        assertThat(result.get().getEffectiveFrom()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(result.get().getPrice()).isEqualByComparingTo("0.4200");
    }

    @Test
    void findEffective_returnsEmptyWhenAsOfBeforeAnyRow() {
        // Arrange
        repo.save(new FeedInTariff("CN-EAST", EnergySource.SOLAR, "PEAK",
                LocalDate.of(2025, 1, 1), new BigDecimal("0.4200")));

        // Act — asOf is before effectiveFrom
        Optional<FeedInTariff> result = repo.findEffective("CN-EAST", EnergySource.SOLAR,
                "PEAK", LocalDate.of(2024, 12, 31));

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void findEffective_includesRowWithEqualEffectiveFrom() {
        // Arrange
        repo.save(new FeedInTariff("CN-EAST", EnergySource.SOLAR, "OFFPEAK",
                LocalDate.of(2025, 1, 1), new BigDecimal("0.2500")));

        // Act — asOf == effectiveFrom (boundary: <= not <)
        Optional<FeedInTariff> result = repo.findEffective("CN-EAST", EnergySource.SOLAR,
                "OFFPEAK", LocalDate.of(2025, 1, 1));

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
