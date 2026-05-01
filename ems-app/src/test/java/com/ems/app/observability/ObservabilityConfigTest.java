package com.ems.app.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityConfigTest {

    @Test
    void customizer_addsApplicationAndInstanceLabels() {
        var registry = new SimpleMeterRegistry();
        new ObservabilityConfig().commonTagsCustomizer("factory-ems", "host-x").customize(registry);

        Counter c = registry.counter("dummy");
        c.increment();

        var meter = registry.find("dummy").counter();
        assertThat(meter).isNotNull();
        assertThat(meter.getId().getTag("application")).isEqualTo("factory-ems");
        assertThat(meter.getId().getTag("instance")).isEqualTo("host-x");
    }
}
