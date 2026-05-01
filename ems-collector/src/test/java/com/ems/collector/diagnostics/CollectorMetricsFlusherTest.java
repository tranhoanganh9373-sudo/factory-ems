package com.ems.collector.diagnostics;

import com.ems.collector.runtime.ChannelStateRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Java 25 + Mockito 不能 mock ChannelStateRegistry 也不能 mock JdbcTemplate
 * （byte-buddy 在 Java 25 下对部分 Spring 类失败）。改用真实 ChannelStateRegistry +
 * 自定义 RecordingJdbcTemplate stub（重写 update varargs，仅计数 / 抛异常）。
 */
@DisplayName("CollectorMetricsFlusher")
class CollectorMetricsFlusherTest {

    private ChannelStateRegistry registry;
    private RecordingJdbcTemplate jdbc;
    private CollectorMetricsFlusher flusher;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-04-30T10:00:00Z"), ZoneOffset.UTC);
        registry = new ChannelStateRegistry(clock);
        jdbc = new RecordingJdbcTemplate();
        flusher = new CollectorMetricsFlusher(registry, jdbc);
    }

    @Test
    @DisplayName("flush 对每个 state 调用 1 次 jdbc.update")
    void flush_invokesUpdateOncePerState() {
        registry.register(1L, "VIRTUAL");
        registry.register(2L, "VIRTUAL");
        flusher.flush();
        assertThat(jdbc.updateCount).isEqualTo(2);
    }

    @Test
    @DisplayName("flush jdbc 抛异常时不向上传播")
    void flush_jdbcThrows_swallowsAndLogs() {
        registry.register(1L, "VIRTUAL");
        jdbc.throwOnUpdate = true;
        flusher.flush();   // 不应抛
        assertThat(jdbc.updateCount).isEqualTo(1);
    }

    @Test
    @DisplayName("空 snapshotAll 不调用 jdbc.update")
    void flush_emptyRegistry_noJdbcCall() {
        flusher.flush();
        assertThat(jdbc.updateCount).isZero();
    }

    static final class RecordingJdbcTemplate extends JdbcTemplate {
        int updateCount = 0;
        boolean throwOnUpdate = false;

        @Override
        public int update(String sql, Object... args) {
            updateCount++;
            if (throwOnUpdate) throw new RuntimeException("db down");
            return 1;
        }
    }
}
