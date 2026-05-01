package com.ems.collector.diagnostics;

import com.ems.collector.runtime.ChannelStateRegistry;
import com.ems.collector.runtime.ConnectionState;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Channel transport 维度的 health indicator，bean 名 {@code collectorTransport}，
 * 暴露在 {@code /actuator/health/collectorTransport}。
 *
 * <p>聚合规则：
 * <ul>
 *   <li>无 channel → UP（"empty registry"）</li>
 *   <li>所有 channel CONNECTED / CONNECTING → UP</li>
 *   <li>存在 DISCONNECTED / ERROR → 自定 DEGRADED 状态</li>
 * </ul>
 *
 * <p>注意与 {@code com.ems.collector.health.CollectorHealthIndicator}
 * （device-level，bean 名 "collector"）共存，维度互补。
 */
@Component("collectorTransport")
public class CollectorTransportHealthIndicator implements HealthIndicator {

    private final ChannelStateRegistry registry;

    public CollectorTransportHealthIndicator(ChannelStateRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Health health() {
        var all = registry.snapshotAll();
        long disconnected = all.stream()
                .filter(s -> s.connState() == ConnectionState.DISCONNECTED
                        || s.connState() == ConnectionState.ERROR)
                .count();
        var builder = (disconnected == 0) ? Health.up() : Health.status("DEGRADED");
        return builder
                .withDetail("total", all.size())
                .withDetail("disconnected", disconnected)
                .build();
    }
}
