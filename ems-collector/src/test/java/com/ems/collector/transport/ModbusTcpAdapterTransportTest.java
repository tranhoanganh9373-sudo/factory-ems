package com.ems.collector.transport;

import com.ems.collector.protocol.ModbusPoint;
import com.ems.collector.protocol.ModbusTcpConfig;
import com.ems.collector.transport.ModbusSlaveTestFixture;
import com.ems.collector.transport.Quality;
import com.ems.collector.transport.Sample;
import com.ems.collector.transport.TestResult;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用进程内 j2mod ModbusSlaveTestFixture，避免 Docker testcontainers 依赖（CP-1.1 已确认本地不兼容）。
 */
class ModbusTcpAdapterTransportTest {

    @Test
    void readsHoldingRegisterAndPushesSample() throws Exception {
        try (ModbusSlaveTestFixture slave = ModbusSlaveTestFixture.start(1)) {
            slave.setHoldingRegister(0, (short) 0x1234);

            ModbusTcpConfig cfg = new ModbusTcpConfig(
                    "127.0.0.1", slave.port(), slave.unitId(),
                    Duration.ofMillis(200), Duration.ofMillis(1000),
                    List.of(new ModbusPoint("p1", "HOLDING", 0, 1, "INT16",
                            "ABCD", null, "kW")));

            ConcurrentLinkedQueue<Sample> samples = new ConcurrentLinkedQueue<>();
            ModbusTcpAdapterTransport t = new ModbusTcpAdapterTransport();
            try {
                t.start(99L, cfg, samples::add);
                Awaitility.await().atMost(3, TimeUnit.SECONDS)
                        .until(() -> !samples.isEmpty());

                Sample s = samples.peek();
                assertThat(s).isNotNull();
                assertThat(s.channelId()).isEqualTo(99L);
                assertThat(s.pointKey()).isEqualTo("p1");
                assertThat(s.quality()).isEqualTo(Quality.GOOD);
                assertThat(((BigDecimal) s.value()).intValueExact()).isEqualTo(0x1234);
                assertThat(t.isConnected()).isTrue();
            } finally {
                t.stop();
            }
        }
    }

    @Test
    void testConnectionSucceedsAgainstFixture() throws Exception {
        try (ModbusSlaveTestFixture slave = ModbusSlaveTestFixture.start(1)) {
            ModbusTcpConfig cfg = new ModbusTcpConfig(
                    "127.0.0.1", slave.port(), slave.unitId(),
                    Duration.ofSeconds(1), Duration.ofMillis(1000),
                    List.of(new ModbusPoint("p1", "HOLDING", 0, 1, "INT16",
                            "ABCD", null, null)));

            ModbusTcpAdapterTransport t = new ModbusTcpAdapterTransport();
            TestResult r = t.testConnection(cfg);

            assertThat(r.success()).isTrue();
            assertThat(r.latencyMs()).isNotNull();
        }
    }

    @Test
    void testConnectionReportsFailureForUnreachableHost() {
        ModbusTcpConfig cfg = new ModbusTcpConfig(
                "127.0.0.1", 1, 1,
                Duration.ofSeconds(1), Duration.ofMillis(500),
                List.of(new ModbusPoint("p1", "HOLDING", 0, 1, "INT16",
                        "ABCD", null, null)));

        ModbusTcpAdapterTransport t = new ModbusTcpAdapterTransport();
        TestResult r = t.testConnection(cfg);

        assertThat(r.success()).isFalse();
        assertThat(r.message()).isNotBlank();
    }
}
