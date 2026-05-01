package com.ems.app.security.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitPropertiesTest {

    @Test
    void defaults_whenNoOverrides() {
        RateLimitProperties props = bind(Map.of());

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getReadPerMinute()).isEqualTo(600);
        assertThat(props.getWritePerMinute()).isEqualTo(60);
        assertThat(props.getBurstMultiplier()).isEqualTo(2);
        assertThat(props.getExemptPathPrefixes())
                .containsExactlyInAnyOrder("/actuator", "/error", "/login", "/api/v1/auth");
    }

    @Test
    void overrides_areApplied() {
        RateLimitProperties props = bind(Map.of(
                "ems.security.rate-limit.enabled", "false",
                "ems.security.rate-limit.read-per-minute", "5",
                "ems.security.rate-limit.write-per-minute", "1",
                "ems.security.rate-limit.burst-multiplier", "1",
                "ems.security.rate-limit.exempt-path-prefixes[0]", "/health"));

        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getReadPerMinute()).isEqualTo(5);
        assertThat(props.getWritePerMinute()).isEqualTo(1);
        assertThat(props.getBurstMultiplier()).isEqualTo(1);
        assertThat(props.getExemptPathPrefixes()).containsExactly("/health");
    }

    private static RateLimitProperties bind(Map<String, Object> overrides) {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(overrides);
        return new Binder(source)
                .bind("ems.security.rate-limit", RateLimitProperties.class)
                .orElseGet(RateLimitProperties::new);
    }
}
