package com.ems.collector.transport.impl;

import com.ems.collector.protocol.*;
import com.ems.collector.transport.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class VirtualTransportTest {

    @Test
    void startSchedulesSamplesUntilStopped() throws Exception {
        var cfg = new VirtualConfig(
            Duration.ofMillis(100),
            List.of(new VirtualPoint("p", VirtualMode.CONSTANT, Map.of("value", 7.0), null)));
        var samples = new CopyOnWriteArrayList<Sample>();
        var transport = new VirtualTransport();

        transport.start(42L, cfg, samples::add);
        try {
            assertThat(transport.isConnected()).isTrue();
            await().atMost(Duration.ofSeconds(2))
                .until(() -> samples.size() >= 2);
            assertThat(samples).allSatisfy(s -> {
                assertThat(s.channelId()).isEqualTo(42L);
                assertThat(s.pointKey()).isEqualTo("p");
                assertThat(s.value()).isEqualTo(7.0);
                assertThat(s.quality()).isEqualTo(Quality.GOOD);
                assertThat(s.tags()).containsEntry("virtual", "true");
            });
        } finally {
            transport.stop();
        }
        assertThat(transport.isConnected()).isFalse();
    }

    @Test
    void testConnectionReturnsOk() {
        var cfg = new VirtualConfig(Duration.ofSeconds(1), List.of(
            new VirtualPoint("p", VirtualMode.CONSTANT, Map.of("value", 0.0), null)));
        assertThat(new VirtualTransport().testConnection(cfg).success()).isTrue();
    }
}
