package com.ems.collector.buffer;

import com.ems.collector.config.CollectorProperties;
import com.ems.collector.sink.InfluxReadingSink;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 后台周期 task：从 {@link BufferStore} 取 unsent reading 批量补传 InfluxDB。
 *
 * <p>运行节奏：每 {@link BufferProperties#flushIntervalMs()}（默认 30s）触发一轮。
 * 单轮取 1000 条；写一条成功就标 sent；任一 batch 失败下轮再试。
 *
 * <p>同时每小时跑一次 {@link BufferStore#vacuum()} 清掉 sent 行 + 超 TTL 数据。
 */
@Component
@ConditionalOnBean({BufferStore.class, InfluxReadingSink.class})
public class BufferFlushScheduler {

    private static final int FLUSH_BATCH = 1000;
    private static final long VACUUM_INTERVAL_MS = 3_600_000L; // 1h

    private static final Logger log = LoggerFactory.getLogger(BufferFlushScheduler.class);

    private final BufferStore buffer;
    private final InfluxReadingSink sink;
    private final long flushIntervalMs;
    private ScheduledExecutorService scheduler;
    private volatile long lastVacuumAt;

    @Autowired
    public BufferFlushScheduler(BufferStore buffer, InfluxReadingSink sink, CollectorProperties props) {
        this.buffer = buffer;
        this.sink = sink;
        this.flushIntervalMs = props.buffer().flushIntervalMs();
    }

    @PostConstruct
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ems-buffer-flush");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::flushTick,
                flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
        log.info("BufferFlushScheduler started: interval={}ms", flushIntervalMs);
    }

    /** Visible for testing. */
    void flushTick() {
        try {
            List<BufferStore.BufferEntry> batch = buffer.peekUnsent(FLUSH_BATCH);
            if (batch.isEmpty()) {
                maybeVacuum();
                return;
            }
            List<Long> sentIds = new ArrayList<>(batch.size());
            for (BufferStore.BufferEntry e : batch) {
                if (sink.flushOne(e.reading())) {
                    sentIds.add(e.id());
                } else {
                    break; // 一个失败就停（顺序补传 + 减少无谓失败）
                }
            }
            if (!sentIds.isEmpty()) {
                buffer.markSent(sentIds);
                log.info("flushed {} buffered readings to Influx (remaining unsent: {})",
                        sentIds.size(), buffer.unsentCount());
            }
            maybeVacuum();
        } catch (Throwable t) {
            log.error("buffer flush tick raised: {}", t.toString(), t);
        }
    }

    private void maybeVacuum() {
        long now = System.currentTimeMillis();
        if (now - lastVacuumAt > VACUUM_INTERVAL_MS) {
            lastVacuumAt = now;
            try {
                buffer.vacuum();
            } catch (Exception e) {
                log.warn("buffer vacuum raised: {}", e.toString());
            }
        }
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) scheduler.shutdownNow();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scheduler.shutdownNow();
            }
        }
    }
}
