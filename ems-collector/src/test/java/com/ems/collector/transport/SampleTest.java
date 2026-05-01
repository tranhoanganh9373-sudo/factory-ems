package com.ems.collector.transport;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SampleTest {

    @Test
    void buildsSampleWithTags() {
        Sample s = new Sample(1L, "p1",
                Instant.parse("2026-04-30T10:00:00Z"),
                42.0, Quality.GOOD, Map.of("topic", "sensors/t"));

        assertThat(s.channelId()).isEqualTo(1L);
        assertThat(s.pointKey()).isEqualTo("p1");
        assertThat(s.timestamp()).isEqualTo(Instant.parse("2026-04-30T10:00:00Z"));
        assertThat(s.value()).isEqualTo(42.0);
        assertThat(s.quality()).isEqualTo(Quality.GOOD);
        assertThat(s.tags()).containsEntry("topic", "sensors/t");
    }

    @Test
    void allowsNullValueWithBadQuality() {
        Sample s = new Sample(7L, "errPoint",
                Instant.parse("2026-04-30T10:01:00Z"),
                null, Quality.BAD, Map.of("error", "timeout"));

        assertThat(s.value()).isNull();
        assertThat(s.quality()).isEqualTo(Quality.BAD);
        assertThat(s.tags()).containsEntry("error", "timeout");
    }
}
