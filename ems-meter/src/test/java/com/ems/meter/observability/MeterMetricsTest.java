package com.ems.meter.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MeterMetricsTest {

    @Test
    void registers_allThreeMetrics() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MeterMetrics metrics = new MeterMetrics(registry);

        metrics.setMaxLagSeconds(42);
        metrics.incrementInsert("elec");
        metrics.incrementDropped("duplicate");

        assertThat(registry.find("ems.meter.reading.lag.seconds").gauge().value())
                .isEqualTo(42d);
        assertThat(registry.find("ems.meter.reading.insert.rate")
                .tag("energy_type", "elec").counter().count()).isEqualTo(1d);
        assertThat(registry.find("ems.meter.reading.dropped.total")
                .tag("reason", "duplicate").counter().count()).isEqualTo(1d);
    }

    @Test
    void incrementInsert_unknownEnergyType_normalizesToOther() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MeterMetrics metrics = new MeterMetrics(registry);

        metrics.incrementInsert("plasma");

        assertThat(registry.find("ems.meter.reading.insert.rate")
                .tag("energy_type", "other").counter().count()).isEqualTo(1d);
    }

    @Test
    void incrementDropped_unknownReason_normalizesToOther() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MeterMetrics metrics = new MeterMetrics(registry);

        metrics.incrementDropped("bizarre-reason");

        assertThat(registry.find("ems.meter.reading.dropped.total")
                .tag("reason", "other").counter().count()).isEqualTo(1d);
    }

    @Test
    void setMaxLagSeconds_lastWriteWins() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MeterMetrics metrics = new MeterMetrics(registry);

        metrics.setMaxLagSeconds(10);
        metrics.setMaxLagSeconds(99);
        metrics.setMaxLagSeconds(33);

        assertThat(registry.find("ems.meter.reading.lag.seconds").gauge().value())
                .isEqualTo(33d);
    }
}
