package com.ems.collector.sink;

import com.ems.collector.transport.Quality;
import com.ems.collector.transport.Sample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LoggingSampleWriter")
class LoggingSampleWriterTest {

    private final LoggingSampleWriter writer = new LoggingSampleWriter();

    @Test
    @DisplayName("write 累积样本到对应 channel buffer")
    void write_accumulatesPerChannel() {
        writer.write(sample(1L, "p1", 10.0));
        writer.write(sample(1L, "p1", 11.0));
        writer.write(sample(2L, "p1", 99.0));

        assertThat(writer.getRecentSamples(1L, 10)).hasSize(2);
        assertThat(writer.getRecentSamples(2L, 10)).hasSize(1);
    }

    @Test
    @DisplayName("getRecentSamples 最新在前")
    void getRecentSamples_newestFirst() {
        writer.write(sample(1L, "p", 1.0));
        writer.write(sample(1L, "p", 2.0));
        var recent = writer.getRecentSamples(1L, 10);
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).value()).isEqualTo(2.0);
        assertThat(recent.get(1).value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("buffer 超过 BUFFER_PER_CHANNEL 自动 trim")
    void write_trimsOldestWhenFull() {
        for (int i = 0; i < LoggingSampleWriter.BUFFER_PER_CHANNEL + 50; i++) {
            writer.write(sample(1L, "p", (double) i));
        }
        var recent = writer.getRecentSamples(1L, Integer.MAX_VALUE);
        assertThat(recent).hasSize(LoggingSampleWriter.BUFFER_PER_CHANNEL);
        // 最新 = i=149，最老应该 = 50
        assertThat((Double) recent.get(0).value())
            .isEqualTo(LoggingSampleWriter.BUFFER_PER_CHANNEL + 49.0);
    }

    @Test
    @DisplayName("不存在 channel 返回空列表")
    void getRecentSamples_unknownChannel_empty() {
        assertThat(writer.getRecentSamples(99L, 10)).isEmpty();
    }

    @Test
    @DisplayName("null sample 安全忽略")
    void write_nullSample_noop() {
        writer.write(null);
        assertThat(writer.getRecentSamples(1L, 10)).isEmpty();
    }

    private Sample sample(Long channelId, String pointKey, double value) {
        return new Sample(channelId, pointKey, Instant.now(), value, Quality.GOOD, Map.of());
    }
}
