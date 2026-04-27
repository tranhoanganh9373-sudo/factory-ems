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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DevicePollerTest {

    private FakeMaster master;
    private List<DeviceReading> sinkReadings;
    private List<String> transitions;
    private DevicePoller poller;

    @BeforeEach
    void setUp() {
        master = new FakeMaster();
        sinkReadings = new ArrayList<>();
        transitions = new ArrayList<>();
        poller = new DevicePoller(
                deviceWithRetries(2),  // 1 normal + 2 retries = 3 attempts per cycle
                master,
                sinkReadings::add,
                Clock.fixed(Instant.parse("2026-04-27T00:00:00Z"), ZoneOffset.UTC),
                (id, from, to, reason, at) -> transitions.add(from + "→" + to + ":" + reason)
        );
    }

    @Test
    void initialState_isHealthy() {
        assertThat(poller.state()).isEqualTo(DeviceState.HEALTHY);
        assertThat(poller.snapshot().consecutiveErrors()).isZero();
    }

    @Test
    void successfulCycle_writesSink_staysHealthy_resetsErrorCount() {
        master.queueSuccessForRegisters();
        boolean ok = poller.pollOnce();
        assertThat(ok).isTrue();
        assertThat(poller.state()).isEqualTo(DeviceState.HEALTHY);
        assertThat(sinkReadings).hasSize(1);
        assertThat(sinkReadings.get(0).deviceId()).isEqualTo("dev-1");
        assertThat(sinkReadings.get(0).numericFields()).containsKey("voltage_a");
    }

    @Test
    void firstCycleFailure_transitionsToDegraded() {
        master.queueAttempts(false, false, false);   // 3 attempts all fail
        boolean ok = poller.pollOnce();
        assertThat(ok).isFalse();
        assertThat(poller.state()).isEqualTo(DeviceState.DEGRADED);
        assertThat(transitions).anyMatch(s -> s.startsWith("HEALTHY→DEGRADED"));
        assertThat(poller.nextDelayMs()).isEqualTo((long) deviceWithRetries(2).backoffMs());
    }

    @Test
    void retrySucceedsBeforeFailureLimit_staysHealthy() {
        master.queueAttempts(false, true);  // 1st fail, 2nd succeed → cycle success
        boolean ok = poller.pollOnce();
        assertThat(ok).isTrue();
        assertThat(poller.state()).isEqualTo(DeviceState.HEALTHY);
        assertThat(transitions).isEmpty();   // no transition; stayed HEALTHY
    }

    @Test
    void degradedToUnreachable_afterThreeMoreFails() {
        // 1st cycle: HEALTHY → DEGRADED
        master.queueAttempts(false, false, false);
        poller.pollOnce();
        assertThat(poller.state()).isEqualTo(DeviceState.DEGRADED);

        // 2nd, 3rd cycle: still DEGRADED (consecutiveCycleErrors=2,3)
        master.queueAttempts(false, false, false);
        poller.pollOnce();
        assertThat(poller.state()).isEqualTo(DeviceState.DEGRADED);

        master.queueAttempts(false, false, false);
        poller.pollOnce();
        assertThat(poller.state()).isEqualTo(DeviceState.UNREACHABLE);

        assertThat(transitions).anyMatch(s -> s.startsWith("DEGRADED→UNREACHABLE"));
        assertThat(poller.nextDelayMs()).isEqualTo(DevicePoller.UNREACHABLE_RECONNECT_MS);
    }

    @Test
    void unreachableRecoversToHealthyOnSuccess() {
        // Push poller into UNREACHABLE
        for (int i = 0; i < 4; i++) {
            master.queueAttempts(false, false, false);
            poller.pollOnce();
        }
        assertThat(poller.state()).isEqualTo(DeviceState.UNREACHABLE);

        // Now succeed
        master.queueSuccessForRegisters();
        boolean ok = poller.pollOnce();
        assertThat(ok).isTrue();
        assertThat(poller.state()).isEqualTo(DeviceState.HEALTHY);
        assertThat(transitions.get(transitions.size() - 1)).startsWith("UNREACHABLE→HEALTHY");
    }

    @Test
    void degradedRecoversToHealthyOnSuccess() {
        master.queueAttempts(false, false, false);
        poller.pollOnce();
        assertThat(poller.state()).isEqualTo(DeviceState.DEGRADED);

        master.queueSuccessForRegisters();
        poller.pollOnce();
        assertThat(poller.state()).isEqualTo(DeviceState.HEALTHY);
        assertThat(poller.snapshot().consecutiveErrors()).isZero();
    }

    @Test
    void transientErrorClosesMasterBetweenAttempts() {
        master.queueAttempts(false, true); // 1st transient fail; 2nd succeed
        poller.pollOnce();
        assertThat(master.closeCalls()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void snapshotReflectsCounts() {
        master.queueSuccessForRegisters();
        poller.pollOnce();
        master.queueAttempts(false, false, false);
        poller.pollOnce();

        DeviceSnapshot snap = poller.snapshot();
        assertThat(snap.successCount()).isEqualTo(1);
        assertThat(snap.failureCount()).isEqualTo(1);
        assertThat(snap.state()).isEqualTo(DeviceState.DEGRADED);
        assertThat(snap.lastError()).isNotNull();
    }

    @Test
    void nextDelayMs_reflectsState() {
        DeviceConfig cfg = deviceWithRetries(2);
        assertThat(poller.nextDelayMs()).isEqualTo((long) cfg.pollingIntervalMs()); // HEALTHY

        master.queueAttempts(false, false, false);
        poller.pollOnce();
        assertThat(poller.nextDelayMs()).isEqualTo((long) cfg.backoffMs()); // DEGRADED

        for (int i = 0; i < 3; i++) {
            master.queueAttempts(false, false, false);
            poller.pollOnce();
        }
        assertThat(poller.nextDelayMs()).isEqualTo(DevicePoller.UNREACHABLE_RECONNECT_MS);
    }

    /* ── helpers ─────────────────────────────────────────────────────── */

    private static DeviceConfig deviceWithRetries(int retries) {
        return new DeviceConfig(
                "dev-1", "MOCK-M-ELEC-001", Protocol.TCP,
                "127.0.0.1", 502,
                null, null, null, null, Parity.NONE,
                1, 5000, 1000, retries, 25_000, 10_000,
                List.of(new RegisterConfig(
                        "voltage_a", 0x0000, 2,
                        FunctionType.HOLDING, DataType.FLOAT32,
                        ByteOrder.ABCD, BigDecimal.ONE, "V",
                        "voltage_a", RegisterKind.GAUGE
                ))
        );
    }

    /**
     * Programmable fake master.
     * <ul>
     *   <li>{@link #queueSuccessForRegisters()} — 准备好下一次读到的字节（FLOAT32=1.0f）</li>
     *   <li>{@link #queueAttempts(boolean...)} — 按顺序铺多次 attempt 的成败结果</li>
     * </ul>
     */
    static class FakeMaster implements ModbusMaster {
        private boolean connected = false;
        private int closeCalls = 0;
        private final Deque<Boolean> attempts = new ArrayDeque<>();
        private byte[] nextSuccessBytes = floatAsBytes(1.0f);

        void queueSuccessForRegisters() {
            // Always succeed; clear any queued attempts list
            attempts.clear();
        }

        void queueAttempts(boolean... results) {
            attempts.clear();
            for (boolean r : results) attempts.addLast(r);
        }

        int closeCalls() { return closeCalls; }

        @Override public void open() throws ModbusIoException {
            connected = true;
        }
        @Override public void close() {
            connected = false;
            closeCalls++;
        }
        @Override public boolean isConnected() {
            return connected;
        }

        @Override public byte[] readHolding(int unitId, int address, int count) throws ModbusIoException {
            return nextResult(count);
        }
        @Override public byte[] readInput(int unitId, int address, int count) throws ModbusIoException {
            return nextResult(count);
        }
        @Override public boolean[] readCoils(int unitId, int address, int count) throws ModbusIoException {
            nextResult(count);
            return new boolean[count];
        }
        @Override public boolean[] readDiscreteInputs(int unitId, int address, int count) throws ModbusIoException {
            nextResult(count);
            return new boolean[count];
        }

        private byte[] nextResult(int count) throws ModbusIoException {
            if (attempts.isEmpty()) {
                // default: succeed
                if (!connected) throw new ModbusIoException("not connected", true);
                return nextSuccessBytes;
            }
            boolean ok = attempts.pollFirst();
            if (ok) {
                if (!connected) throw new ModbusIoException("not connected", true);
                return nextSuccessBytes;
            }
            throw new ModbusIoException("simulated read failure", true);
        }

        private static byte[] floatAsBytes(float f) {
            int bits = Float.floatToRawIntBits(f);
            return new byte[]{
                    (byte) ((bits >>> 24) & 0xFF),
                    (byte) ((bits >>> 16) & 0xFF),
                    (byte) ((bits >>> 8) & 0xFF),
                    (byte) (bits & 0xFF)
            };
        }
    }
}
