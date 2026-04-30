package com.ems.app.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class ObservabilityConfig {

    @Bean
    MeterRegistryCustomizer<MeterRegistry> commonTagsCustomizer(
            @Value("${spring.application.name:factory-ems}") String application,
            @Value("${HOSTNAME:unknown}") String instance) {
        return registry -> registry.config().meterFilter(
                MeterFilter.commonTags(List.of(
                        Tag.of("application", application),
                        Tag.of("instance", instance)
                )));
    }
}
