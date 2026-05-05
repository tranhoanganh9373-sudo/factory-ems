package com.ems.collector.config;

import com.ems.collector.buffer.BufferStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CollectorAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(CollectorAutoConfiguration.class);

    @Test
    void bufferStore_notCreated_whenCollectorDisabled() {
        contextRunner
                .withPropertyValues("ems.collector.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(BufferStore.class));
    }

    @Test
    void bufferStore_notCreated_whenPropertyAbsent() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(BufferStore.class));
    }

    @Test
    void bufferStore_created_whenCollectorEnabled(@TempDir Path tmp) {
        contextRunner
                .withPropertyValues(
                        "ems.collector.enabled=true",
                        "ems.collector.buffer.path=" + tmp.resolve("buffer.db").toAbsolutePath()
                )
                .run(context -> assertThat(context).hasSingleBean(BufferStore.class));
    }
}
