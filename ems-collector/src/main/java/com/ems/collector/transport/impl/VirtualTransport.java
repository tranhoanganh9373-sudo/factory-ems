package com.ems.collector.transport.impl;

import com.ems.collector.protocol.*;
import com.ems.collector.transport.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;

public final class VirtualTransport implements Transport {

    private static final Logger log = LoggerFactory.getLogger(VirtualTransport.class);

    private final VirtualSignalGenerator generator = new VirtualSignalGenerator();
    private ScheduledExecutorService scheduler;
    private volatile boolean connected = false;
    private Long channelId;

    @Override
    public void start(Long channelId, ChannelConfig config, SampleSink sink) {
        if (!(config instanceof VirtualConfig cfg)) {
            throw new TransportException("expected VirtualConfig, got " + config.getClass().getSimpleName());
        }
        this.channelId = channelId;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "virtual-" + channelId);
            t.setDaemon(true);
            return t;
        });
        connected = true;
        long periodMs = cfg.pollInterval().toMillis();
        scheduler.scheduleAtFixedRate(() -> tick(channelId, cfg, sink),
            0, periodMs, TimeUnit.MILLISECONDS);
        log.info("Virtual transport started: channel={} interval={}ms points={}",
                channelId, periodMs, cfg.points().size());
    }

    private void tick(Long channelId, VirtualConfig cfg, SampleSink sink) {
        try {
            var now = Instant.now();
            for (var p : cfg.points()) {
                sink.accept(new Sample(channelId, p.key(), now,
                    generator.generate(p, now), Quality.GOOD,
                    Map.of("virtual", "true")));
            }
        } catch (Throwable t) {
            log.warn("Virtual tick failed for channel={}: {}", channelId, t.toString());
        }
    }

    @Override
    public void stop() {
        connected = false;
        if (scheduler != null) scheduler.shutdownNow();
        log.info("Virtual transport stopped: channel={}", channelId);
    }

    @Override
    public boolean isConnected() { return connected; }

    @Override
    public TestResult testConnection(ChannelConfig config) {
        if (!(config instanceof VirtualConfig)) {
            return TestResult.fail("expected VirtualConfig");
        }
        return TestResult.ok(0L);
    }
}
