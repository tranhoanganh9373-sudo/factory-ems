package com.ems.collector.transport.impl;

import com.ems.collector.protocol.*;
import com.ems.collector.transport.*;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;

public final class VirtualTransport implements Transport {
    private final VirtualSignalGenerator generator = new VirtualSignalGenerator();
    private ScheduledExecutorService scheduler;
    private volatile boolean connected = false;

    @Override
    public void start(Long channelId, ChannelConfig config, SampleSink sink) {
        var cfg = (VirtualConfig) config;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "virtual-" + channelId);
            t.setDaemon(true);
            return t;
        });
        connected = true;
        scheduler.scheduleAtFixedRate(() -> tick(channelId, cfg, sink),
            0, cfg.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void tick(Long channelId, VirtualConfig cfg, SampleSink sink) {
        var now = Instant.now();
        for (var p : cfg.points()) {
            sink.accept(new Sample(channelId, p.key(), now,
                generator.generate(p, now), Quality.GOOD,
                Map.of("virtual", "true")));
        }
    }

    @Override
    public void stop() {
        connected = false;
        if (scheduler != null) scheduler.shutdownNow();
    }

    @Override
    public boolean isConnected() { return connected; }

    @Override
    public TestResult testConnection(ChannelConfig config) {
        return TestResult.ok(0L);
    }
}
