package com.ems.collector.diagnostics;

import com.ems.collector.runtime.ChannelStateRegistry;
import com.ems.collector.runtime.ConnectionState;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Channel transport 维度的 Micrometer gauge：
 * <ul>
 *   <li>{@code ems_collector_channels_total} — 当前 registry 大小</li>
 *   <li>{@code ems_collector_channels_state{state=...}} — 每个 ConnectionState 的计数</li>
 * </ul>
 *
 * <p>Gauge 通过 {@link MeterRegistry} 注册，传 {@link ChannelStateRegistry} 作为 ref obj
 * （weak ref 安全：bean 单例不会被 GC）。
 */
@Component
public class CollectorTransportMetrics {

    private final MeterRegistry meterRegistry;
    private final ChannelStateRegistry stateRegistry;

    public CollectorTransportMetrics(MeterRegistry meterRegistry,
                                     ChannelStateRegistry stateRegistry) {
        this.meterRegistry = meterRegistry;
        this.stateRegistry = stateRegistry;
    }

    @PostConstruct
    public void register() {
        Gauge.builder("ems_collector_channels_total", stateRegistry,
                        r -> r.snapshotAll().size())
                .description("Total number of channels in registry")
                .register(meterRegistry);

        for (var state : ConnectionState.values()) {
            Gauge.builder("ems_collector_channels_state", stateRegistry,
                            r -> r.snapshotAll().stream()
                                    .filter(s -> s.connState() == state).count())
                    .tag("state", state.name())
                    .description("Number of channels per connection state")
                    .register(meterRegistry);
        }
    }
}
