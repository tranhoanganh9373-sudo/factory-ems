package com.ems.alarm.service.impl;

import com.ems.alarm.service.TopologyConsistencyAlarmService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConditionalOnProperty(name = "ems.alarm.topology.enabled", havingValue = "true")
public class TopologyConsistencyScheduler {

    private static final Logger log = LoggerFactory.getLogger(TopologyConsistencyScheduler.class);

    private final TopologyConsistencyAlarmService service;
    private final MeterRegistry registry;

    public TopologyConsistencyScheduler(TopologyConsistencyAlarmService service,
                                        MeterRegistry registry) {
        this.service = service;
        this.registry = registry;
    }

    @Scheduled(cron = "${ems.alarm.topology.cron:0 5 * * * *}")
    public void run() {
        long startNanos = System.nanoTime();
        try {
            service.runOnce();
            registry.counter("ems_alarm_topology_runs_total", "outcome", "ok").increment();
        } catch (Exception e) {
            log.error("Topology consistency alarm scan failed", e);
            registry.counter("ems_alarm_topology_runs_total", "outcome", "error").increment();
        } finally {
            registry.timer("ems_alarm_topology_run_duration")
                    .record(Duration.ofNanos(System.nanoTime() - startNanos));
        }
    }
}
