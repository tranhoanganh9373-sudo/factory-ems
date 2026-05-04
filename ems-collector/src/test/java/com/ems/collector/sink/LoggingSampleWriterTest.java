package com.ems.collector.sink;

import com.ems.collector.transport.Quality;
import com.ems.collector.transport.Sample;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LoggingSampleWriter")
class LoggingSampleWriterTest {

    private DiagnosticRingBuffer ring;
    private LoggingSampleWriter writer;

    @BeforeEach
    void setUp() {
        ring = new DiagnosticRingBuffer();
        writer = new LoggingSampleWriter(ring);
    }

    @Test
    @DisplayName("write 把 sample 喂给 DiagnosticRingBuffer")
    void write_feedsRingBuffer() {
        writer.write(sample(1L, "p1", 10.0));
        writer.write(sample(1L, "p1", 11.0));
        writer.write(sample(2L, "p1", 99.0));

        assertThat(ring.getRecentSamples(1L, 10)).hasSize(2);
        assertThat(ring.getRecentSamples(2L, 10)).hasSize(1);
    }

    @Test
    @DisplayName("null sample 安全忽略，不喂 ring buffer")
    void write_nullSample_noop() {
        writer.write(null);
        assertThat(ring.getRecentSamples(1L, 10)).isEmpty();
    }

    @Test
    @DisplayName("sample.channelId 为 null 时安全忽略")
    void write_nullChannelId_noop() {
        writer.write(new Sample(null, "p", Instant.now(), 1.0, Quality.GOOD, Map.of()));
        assertThat(ring.getRecentSamples(1L, 10)).isEmpty();
    }

    private Sample sample(Long channelId, String pointKey, double value) {
        return new Sample(channelId, pointKey, Instant.now(), value, Quality.GOOD, Map.of());
    }
}
