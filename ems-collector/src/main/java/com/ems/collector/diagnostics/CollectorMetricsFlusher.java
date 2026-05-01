package com.ems.collector.diagnostics;

import com.ems.collector.runtime.ChannelStateRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 周期性把 {@link ChannelStateRegistry} 的快照刷到 {@code collector_metrics} 表。
 *
 * <p>每分钟执行一次（fixedRate 60s）。bucket 按分钟截断；同 bucket 内多次 flush
 * 通过主键 ON CONFLICT 覆盖最新值。任何异常都吞掉只记日志，避免一次失败影响下次调度。
 */
@Component
public class CollectorMetricsFlusher {

    private static final Logger log = LoggerFactory.getLogger(CollectorMetricsFlusher.class);

    private static final String UPSERT_SQL = """
            INSERT INTO collector_metrics(channel_id, bucket_at, success_cnt, failure_cnt, avg_latency_ms)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (channel_id, bucket_at) DO UPDATE SET
                success_cnt = EXCLUDED.success_cnt,
                failure_cnt = EXCLUDED.failure_cnt,
                avg_latency_ms = EXCLUDED.avg_latency_ms
            """;

    private final ChannelStateRegistry registry;
    private final JdbcTemplate jdbc;

    public CollectorMetricsFlusher(ChannelStateRegistry registry, JdbcTemplate jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;
    }

    @Scheduled(fixedRate = 60_000)
    public void flush() {
        try {
            var bucket = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            for (var state : registry.snapshotAll()) {
                jdbc.update(UPSERT_SQL,
                        state.channelId(),
                        Timestamp.from(bucket),
                        (int) state.successCount24h(),
                        (int) state.failureCount24h(),
                        (int) state.avgLatencyMs());
            }
        } catch (Throwable t) {
            log.warn("collector metrics flush failed: {}", t.toString());
        }
    }
}
