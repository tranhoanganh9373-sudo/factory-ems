package com.ems.collector.health;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * 给 CollectorService 用的 Micrometer 指标包装。每 device 一组 counter + timer，
 * tag {@code device=<deviceId>}。Prometheus / Actuator metrics endpoint 都能直接拿。
 *
 * <p>指标：
 * <ul>
 *   <li>{@code ems.collector.read.success} — counter，每次成功 cycle +1</li>
 *   <li>{@code ems.collector.read.failure} — counter，每次失败 cycle +1</li>
 *   <li>{@code ems.collector.read.duration} — timer，每次 cycle 耗时（成功+失败都计）</li>
 * </ul>
 */
@Component
public class CollectorMetrics {

    private static final String SUCCESS = "ems.collector.read.success";
    private static final String FAILURE = "ems.collector.read.failure";
    private static final String DURATION = "ems.collector.read.duration";

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> successCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> failureCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> timers = new ConcurrentHashMap<>();

    public CollectorMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String deviceId, boolean success, long durationNanos) {
        Counter c = success
                ? successCounters.computeIfAbsent(deviceId, id ->
                Counter.builder(SUCCESS).tag("device", id).register(registry))
                : failureCounters.computeIfAbsent(deviceId, id ->
                Counter.builder(FAILURE).tag("device", id).register(registry));
        c.increment();

        Timer t = timers.computeIfAbsent(deviceId, id ->
                Timer.builder(DURATION).tag("device", id).register(registry));
        t.record(durationNanos, TimeUnit.NANOSECONDS);
    }
}
