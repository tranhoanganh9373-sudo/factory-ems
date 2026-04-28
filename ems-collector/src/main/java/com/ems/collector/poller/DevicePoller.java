package com.ems.collector.poller;

import com.ems.collector.codec.RegisterDecoder;
import com.ems.collector.config.DeviceConfig;
import com.ems.collector.config.FunctionType;
import com.ems.collector.config.RegisterConfig;
import com.ems.collector.config.RegisterKind;
import com.ems.collector.transport.ModbusIoException;
import com.ems.collector.transport.ModbusMaster;

import java.util.concurrent.locks.Lock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 单设备 polling loop + 状态机。线程模型：单线程串行调用 {@link #pollOnce()}（由
 * {@link com.ems.collector.service.CollectorService} 的 ScheduledExecutorService 保证）。
 *
 * <p>失败次数语义：
 * <ul>
 *   <li>"一次 cycle 失败" = retries+1 次重试都失败</li>
 *   <li>HEALTHY → DEGRADED：1 次 cycle 失败</li>
 *   <li>DEGRADED → UNREACHABLE：连续 {@link #DEGRADED_TO_UNREACHABLE_THRESHOLD} 次 cycle 失败</li>
 *   <li>任何状态 → HEALTHY：任一次 cycle 成功</li>
 * </ul>
 *
 * <p>{@link #nextDelayMs()} 给 scheduler 用：
 * <ul>
 *   <li>HEALTHY → pollingIntervalMs</li>
 *   <li>DEGRADED → backoffMs</li>
 *   <li>UNREACHABLE → {@link #UNREACHABLE_RECONNECT_MS}（30s 一次重连尝试）</li>
 * </ul>
 *
 * <p>Phase E 暂不写 audit log；状态切换会回调
 * {@link StateTransitionListener}（Phase F/J 注入实际 audit 实现）。
 */
public class DevicePoller {

    /** DEGRADED 状态下连续多少次失败 cycle 后判 UNREACHABLE。 */
    static final int DEGRADED_TO_UNREACHABLE_THRESHOLD = 3;

    /** UNREACHABLE 状态下两次重连尝试之间的延迟。 */
    static final long UNREACHABLE_RECONNECT_MS = 30_000L;

    private static final Logger log = LoggerFactory.getLogger(DevicePoller.class);

    private final DeviceConfig config;
    private final ModbusMaster master;
    private final ReadingSink sink;
    private final Clock clock;
    private final StateTransitionListener listener;
    private final AccumulatorTracker accumulator = new AccumulatorTracker();
    /** RTU device 的串口互斥锁（同 port 多 unit-id 共享同一 lock）；TCP device = null。 */
    private final Lock serialLock;

    /* ── runtime state ─────────────────────────────────────────────────── */
    private DeviceState state = DeviceState.HEALTHY;
    private Instant lastReadAt;
    private Instant lastTransitionAt;
    private int consecutiveCycleErrors = 0;
    private long successCount = 0L;
    private long failureCount = 0L;
    private String lastError;

    public DevicePoller(DeviceConfig config,
                        ModbusMaster master,
                        ReadingSink sink,
                        Clock clock,
                        StateTransitionListener listener) {
        this(config, master, sink, clock, listener, null);
    }

    public DevicePoller(DeviceConfig config,
                        ModbusMaster master,
                        ReadingSink sink,
                        Clock clock,
                        StateTransitionListener listener,
                        Lock serialLock) {
        this.config = config;
        this.master = master;
        this.sink = sink;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.listener = listener == null ? StateTransitionListener.NOOP : listener;
        this.serialLock = serialLock;
        this.lastTransitionAt = this.clock.instant();
    }

    public DeviceConfig config() { return config; }
    public synchronized DeviceState state() { return state; }

    /**
     * 跑一次完整 polling cycle：retries+1 次尝试，第一次成功即写 sink + 切回 HEALTHY；
     * 全部失败则按状态机推进。
     *
     * @return true 当本轮成功
     */
    public synchronized boolean pollOnce() {
        if (serialLock != null) {
            serialLock.lock();
            try {
                return pollOnceInternal();
            } finally {
                serialLock.unlock();
            }
        }
        return pollOnceInternal();
    }

    private boolean pollOnceInternal() {
        Throwable lastEx = null;
        int attempts = config.retries() + 1;

        for (int i = 0; i < attempts; i++) {
            try {
                ensureOpen();
                Map<String, BigDecimal> numbers = new HashMap<>();
                Map<String, Boolean> bits = new HashMap<>();
                for (RegisterConfig reg : config.registers()) {
                    readRegister(reg, numbers, bits);
                }
                Instant now = clock.instant();
                lastReadAt = now;
                successCount++;
                sink.accept(new DeviceReading(
                        config.id(), config.meterCode(), now,
                        Map.copyOf(numbers), Map.copyOf(bits)));
                onCycleSuccess();
                return true;
            } catch (Throwable t) {
                lastEx = t;
                lastError = t.getMessage();
                // Transient connection error → drop connection so next attempt re-opens.
                if (t instanceof ModbusIoException me && me.isTransient()) {
                    master.close();
                }
                if (i < attempts - 1) {
                    log.debug("device {} attempt {}/{} failed: {}", config.id(), i + 1, attempts, t.toString());
                }
            }
        }
        failureCount++;
        log.warn("device {} cycle failed after {} attempts: {}", config.id(), attempts,
                lastEx == null ? "<unknown>" : lastEx.toString());
        onCycleFailure();
        return false;
    }

    /** 给 scheduler 用：下一周期延迟。 */
    public synchronized long nextDelayMs() {
        return switch (state) {
            case HEALTHY -> config.pollingIntervalMs();
            case DEGRADED -> config.backoffMs();
            case UNREACHABLE -> UNREACHABLE_RECONNECT_MS;
        };
    }

    public synchronized DeviceSnapshot snapshot() {
        return new DeviceSnapshot(
                config.id(), config.meterCode(), state,
                lastReadAt, lastTransitionAt,
                consecutiveCycleErrors, successCount, failureCount,
                lastError);
    }

    /** Force shutdown of the underlying transport. */
    public synchronized void shutdown() {
        master.close();
    }

    /* ── internals ─────────────────────────────────────────────────────── */

    private void ensureOpen() throws ModbusIoException {
        if (!master.isConnected()) {
            master.open();
        }
    }

    private void readRegister(RegisterConfig reg,
                              Map<String, BigDecimal> numbers,
                              Map<String, Boolean> bits) throws ModbusIoException {
        if (reg.function() == FunctionType.COIL) {
            boolean[] r = master.readCoils(config.unitId(), reg.address(), 1);
            bits.put(reg.tsField(), r[0]);
            return;
        }
        if (reg.function() == FunctionType.DISCRETE_INPUT) {
            boolean[] r = master.readDiscreteInputs(config.unitId(), reg.address(), 1);
            bits.put(reg.tsField(), r[0]);
            return;
        }
        byte[] raw = (reg.function() == FunctionType.HOLDING)
                ? master.readHolding(config.unitId(), reg.address(), reg.count())
                : master.readInput(config.unitId(), reg.address(), reg.count());
        BigDecimal value = RegisterDecoder.decode(raw, reg.dataType(), reg.byteOrder(), reg.scale());
        numbers.put(reg.tsField(), value);

        // ACCUMULATOR / COUNTER: 写伴随 _delta field（spec §5.2）
        if (reg.kind() == RegisterKind.ACCUMULATOR || reg.kind() == RegisterKind.COUNTER) {
            BigDecimal delta = accumulator.observe(reg.tsField(), value, reg.scale());
            if (delta != null) {
                numbers.put(reg.tsField() + "_delta", delta);
            }
        }
    }

    private void onCycleSuccess() {
        consecutiveCycleErrors = 0;
        if (state != DeviceState.HEALTHY) {
            transition(DeviceState.HEALTHY, "recovery: read succeeded");
        }
    }

    private void onCycleFailure() {
        consecutiveCycleErrors++;
        switch (state) {
            case HEALTHY -> transition(DeviceState.DEGRADED,
                    "cycle failed (attempts=" + (config.retries() + 1) + ")");
            case DEGRADED -> {
                if (consecutiveCycleErrors >= DEGRADED_TO_UNREACHABLE_THRESHOLD) {
                    transition(DeviceState.UNREACHABLE,
                            "consecutive failures=" + consecutiveCycleErrors);
                }
            }
            case UNREACHABLE -> { /* stay; next cycle is reconnect attempt */ }
        }
    }

    private void transition(DeviceState newState, String reason) {
        DeviceState old = state;
        state = newState;
        lastTransitionAt = clock.instant();
        log.info("device {} state {} → {} ({})", config.id(), old, newState, reason);
        try {
            listener.onTransition(config.id(), old, newState, reason, lastTransitionAt);
        } catch (Exception ignore) {
            // listener 错误不能影响 poller
        }
    }

    /** State transition hook for audit / metrics integration; supplied by CollectorService. */
    @FunctionalInterface
    public interface StateTransitionListener {
        StateTransitionListener NOOP = (id, from, to, reason, at) -> {};

        void onTransition(String deviceId, DeviceState from, DeviceState to,
                          String reason, Instant at);
    }
}
