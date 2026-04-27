package com.ems.collector.poller;

import com.ems.collector.config.ByteOrder;
import com.ems.collector.config.DataType;
import com.ems.collector.config.DeviceConfig;
import com.ems.collector.config.FunctionType;
import com.ems.collector.config.Parity;
import com.ems.collector.config.Protocol;
import com.ems.collector.config.RegisterConfig;
import com.ems.collector.config.RegisterKind;
import com.ems.collector.transport.ModbusIoException;
import com.ems.collector.transport.ModbusMaster;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase H 集成 — DevicePoller 在 ACCUMULATOR register 上自动 emit `_delta` 伴随 field。 */
class DevicePollerAccumulatorTest {

    @Test
    void accumulator_writesDeltaFieldStartingFromSecondCycle() {
        // device: 单 register UINT32 ACCUMULATOR，scale=1
        DeviceConfig dev = device(RegisterKind.ACCUMULATOR);
        ScriptedMaster master = new ScriptedMaster();

        List<DeviceReading> readings = new ArrayList<>();
        DevicePoller poller = new DevicePoller(
                dev, master, readings::add,
                Clock.fixed(Instant.parse("2026-04-27T00:00:00Z"), ZoneOffset.UTC),
                DevicePoller.StateTransitionListener.NOOP
        );

        // 1st cycle — value 100；首次没有 delta
        master.queueUInt32(100L);
        assertThat(poller.pollOnce()).isTrue();
        assertThat(readings.get(0).numericFields()).containsKey("energy");
        assertThat(readings.get(0).numericFields()).doesNotContainKey("energy_delta");

        // 2nd cycle — value 110；delta=10
        master.queueUInt32(110L);
        assertThat(poller.pollOnce()).isTrue();
        DeviceReading r2 = readings.get(1);
        assertThat(r2.numericFields().get("energy_delta")).isEqualByComparingTo("10");
        assertThat(r2.numericFields().get("energy")).isEqualByComparingTo("110");
    }

    @Test
    void gauge_doesNotEmitDelta() {
        DeviceConfig dev = device(RegisterKind.GAUGE);
        ScriptedMaster master = new ScriptedMaster();

        List<DeviceReading> readings = new ArrayList<>();
        DevicePoller poller = new DevicePoller(
                dev, master, readings::add,
                Clock.fixed(Instant.parse("2026-04-27T00:00:00Z"), ZoneOffset.UTC),
                DevicePoller.StateTransitionListener.NOOP
        );

        master.queueUInt32(100L);
        poller.pollOnce();
        master.queueUInt32(200L);
        poller.pollOnce();

        assertThat(readings.get(1).numericFields()).doesNotContainKey("energy_delta");
    }

    @Test
    void accumulator_wrapAroundDelta() {
        DeviceConfig dev = device(RegisterKind.ACCUMULATOR);
        ScriptedMaster master = new ScriptedMaster();

        List<DeviceReading> readings = new ArrayList<>();
        DevicePoller poller = new DevicePoller(
                dev, master, readings::add,
                Clock.fixed(Instant.parse("2026-04-27T00:00:00Z"), ZoneOffset.UTC),
                DevicePoller.StateTransitionListener.NOOP
        );

        master.queueUInt32(0xFFFFFFFEL);  // prev = 4294967294
        poller.pollOnce();
        master.queueUInt32(5L);            // wrap → curr = 5
        poller.pollOnce();

        // expected raw delta = (0xFFFFFFFF - 0xFFFFFFFE) + 5 + 1 = 7
        assertThat(readings.get(1).numericFields().get("energy_delta")).isEqualByComparingTo("7");
    }

    /* ── helpers ───────────────────────────────────────────────────────── */

    private static DeviceConfig device(RegisterKind kind) {
        return new DeviceConfig(
                "dev-1", "MOCK-M-ELEC-001", Protocol.TCP,
                "127.0.0.1", 502,
                null, null, null, null, Parity.NONE,
                1, 5000, 1000, 0, 25_000, 1000,
                List.of(new RegisterConfig(
                        "energy", 0x4000, 2,
                        FunctionType.HOLDING, DataType.UINT32,
                        ByteOrder.ABCD, BigDecimal.ONE, "kWh",
                        "energy", kind))
        );
    }

    /** Master that returns predefined UINT32 (4 bytes big-endian) per readHolding call. */
    static class ScriptedMaster implements ModbusMaster {
        private boolean connected = false;
        private final Deque<byte[]> queue = new ArrayDeque<>();

        void queueUInt32(long value) {
            queue.add(new byte[]{
                    (byte) ((value >>> 24) & 0xFF),
                    (byte) ((value >>> 16) & 0xFF),
                    (byte) ((value >>> 8) & 0xFF),
                    (byte) (value & 0xFF)});
        }

        @Override public void open() { connected = true; }
        @Override public void close() { connected = false; }
        @Override public boolean isConnected() { return connected; }
        @Override public byte[] readHolding(int u, int a, int c) throws ModbusIoException {
            if (!connected) throw new ModbusIoException("not connected", true);
            if (queue.isEmpty()) throw new ModbusIoException("no data", true);
            return queue.pollFirst();
        }
        @Override public byte[] readInput(int u, int a, int c) { return new byte[c * 2]; }
        @Override public boolean[] readCoils(int u, int a, int c) { return new boolean[c]; }
        @Override public boolean[] readDiscreteInputs(int u, int a, int c) { return new boolean[c]; }
    }
}
