package com.ems.collector.transport.impl;

import com.ems.collector.codec.RegisterDecoder;
import com.ems.collector.config.ByteOrder;
import com.ems.collector.config.DataType;
import com.ems.collector.protocol.ChannelConfig;
import com.ems.collector.protocol.ModbusPoint;
import com.ems.collector.protocol.ModbusTcpConfig;
import com.ems.collector.runtime.ChannelStateRegistry;
import com.ems.collector.transport.ModbusIoException;
import com.ems.collector.transport.Quality;
import com.ems.collector.transport.Sample;
import com.ems.collector.transport.SampleSink;
import com.ems.collector.transport.TcpModbusMaster;
import com.ems.collector.transport.TestResult;
import com.ems.collector.transport.Transport;
import com.ems.collector.transport.TransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Modbus TCP Transport — 复用既有 {@link TcpModbusMaster}（j2mod 包装）+ {@link RegisterDecoder}。
 *
 * <p>线程模型：
 * <ul>
 *   <li>{@link #start} 同步连接 → 启动单线程 daemon ScheduledExecutorService</li>
 *   <li>调度器按 {@link ModbusTcpConfig#pollInterval()} 周期轮询 points，每个 point 一次 read 调用</li>
 *   <li>读失败仅 log + 推送 BAD quality sample，不中断调度</li>
 *   <li>{@link #stop} shutdownNow + close master，幂等</li>
 * </ul>
 *
 * <p>自动重连退避（Phase 1）：每个 cycle 起头若 {@code master == null || !master.isConnected()}，
 * 按 {@link ModbusBackoff} 序列 1s→2s→4s→8s→16s→32s→60s 睡眠后重开 master。重开成功 reset
 * {@code reconnectAttempts} 并继续本周期 polling；重开失败仅记账并退出本周期。每周期所有点
 * 都 IOException 时强制 close master，让下个周期走重连分支。
 */
public final class ModbusTcpAdapterTransport implements Transport {

    private static final Logger log = LoggerFactory.getLogger(ModbusTcpAdapterTransport.class);
    private static final int DEFAULT_TIMEOUT_MS = 1000;
    private static final int TEST_TIMEOUT_MS = 2000;
    private static final int TEST_TIMEOUT_CAP_MS = 10_000;

    private final ChannelStateRegistry registry;
    private final Supplier<TcpModbusMaster> masterFactoryOverride;

    private TcpModbusMaster master;
    private ScheduledExecutorService scheduler;
    private volatile boolean connected = false;
    private volatile int reconnectAttempts = 0;
    private volatile Supplier<TcpModbusMaster> masterFactory;

    /** Convenience for legacy fixture-based tests; registry is null and recordSuccess/Failure no-op. */
    public ModbusTcpAdapterTransport() {
        this(null, null);
    }

    /** Production constructor wired by {@code ChannelTransportFactory}. */
    public ModbusTcpAdapterTransport(ChannelStateRegistry registry) {
        this(registry, null);
    }

    /** Package-private for test injection of a controllable {@link TcpModbusMaster} supplier. */
    ModbusTcpAdapterTransport(ChannelStateRegistry registry,
                              Supplier<TcpModbusMaster> masterFactory) {
        this.registry = registry;
        this.masterFactoryOverride = masterFactory;
    }

    @Override
    public void start(Long channelId, ChannelConfig config, SampleSink sink) {
        if (!(config instanceof ModbusTcpConfig cfg)) {
            throw new TransportException("expected ModbusTcpConfig, got " + config.getClass().getSimpleName());
        }
        int timeoutMs = cfg.timeout() != null ? (int) cfg.timeout().toMillis() : DEFAULT_TIMEOUT_MS;
        masterFactory = masterFactoryOverride != null
                ? masterFactoryOverride
                : () -> new TcpModbusMaster(cfg.host(), cfg.port(), timeoutMs);

        master = masterFactory.get();
        try {
            master.open();
            connected = true;
        } catch (ModbusIoException e) {
            master = null;
            throw new TransportException("Modbus TCP connect failed: " + e.getMessage(), e);
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "modbus-tcp-" + channelId);
            t.setDaemon(true);
            return t;
        });
        long periodMs = cfg.pollInterval().toMillis();
        scheduler.scheduleWithFixedDelay(
                () -> pollAll(channelId, cfg, sink),
                0L, periodMs, TimeUnit.MILLISECONDS);
        log.info("Modbus TCP transport started: channel={} {}:{} unitId={} interval={}ms points={}",
                channelId, cfg.host(), cfg.port(), cfg.unitId(), periodMs, cfg.points().size());
    }

    private void pollAll(Long channelId, ModbusTcpConfig cfg, SampleSink sink) {
        if (master == null || !master.isConnected()) {
            if (!ensureReopened(channelId)) {
                return;
            }
        }

        int ioFailureCount = 0;
        for (ModbusPoint p : cfg.points()) {
            try {
                Object value = readPoint(cfg.unitId(), p);
                sink.accept(new Sample(channelId, p.key(), Instant.now(),
                        value, Quality.GOOD, Map.of()));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                log.warn("Modbus read failed for channel={} point={}: {}", channelId, p.key(), msg);
                sink.accept(new Sample(channelId, p.key(), Instant.now(),
                        null, Quality.BAD, Map.of("error", msg)));
                if (e instanceof ModbusIoException) {
                    ioFailureCount++;
                }
            }
        }

        if (!cfg.points().isEmpty() && ioFailureCount == cfg.points().size()) {
            log.warn("Modbus channel={} all {} points failed — force-closing master to trigger reconnect",
                    channelId, ioFailureCount);
            forceCloseMaster();
        }
    }

    /**
     * 重连分支：sleep backoff → 关闭旧 master → new master → open()。
     * 成功 → reset attempts、log INFO、return true（继续 polling）。"采集成功"由 sample sink 上报，
     * TCP 握手成功并不等于"采集成功"——握手后若 register 全部读失败仍应算 channel 不健康。
     * 失败 → 增加 attempts、log WARN、{@link ChannelStateRegistry#recordFailure}、return false（退出本周期）。
     */
    private boolean ensureReopened(Long channelId) {
        long sleepMs = ModbusBackoff.nextDelayMs(reconnectAttempts);
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }

        if (master != null) {
            try {
                master.close();
            } catch (Exception e) {
                log.debug("Modbus TCP stale master close ignored: {}", e.toString());
            }
        }

        long openStart = System.currentTimeMillis();
        try {
            master = masterFactory.get();
            master.open();
            long elapsed = System.currentTimeMillis() - openStart;
            connected = true;
            int prevAttempts = reconnectAttempts;
            reconnectAttempts = 0;
            log.info("Modbus TCP reopened channel={} after {} attempt(s) in {}ms",
                    channelId, prevAttempts + 1, elapsed);
            return true;
        } catch (Exception e) {
            int attempt = ++reconnectAttempts;
            long nextDelay = ModbusBackoff.nextDelayMs(attempt);
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            log.warn("Modbus TCP reopen channel={} failed (attempt#{}, next backoff={}ms): {}",
                    channelId, attempt, nextDelay, msg);
            connected = false;
            if (registry != null) {
                registry.recordFailure(channelId, msg);
            }
            return false;
        }
    }

    private void forceCloseMaster() {
        connected = false;
        if (master != null) {
            try {
                master.close();
            } catch (Exception e) {
                log.warn("Modbus TCP master close error during force-close: {}", e.toString());
            }
            master = null;
        }
    }

    private Object readPoint(int unitId, ModbusPoint p) throws ModbusIoException {
        String kind = p.registerKind() == null ? "" : p.registerKind().toUpperCase();
        return switch (kind) {
            case "HOLDING" -> decodeRegisterRead(
                    master.readHolding(unitId, p.address(), p.quantity()), p);
            case "INPUT" -> decodeRegisterRead(
                    master.readInput(unitId, p.address(), p.quantity()), p);
            case "COIL" -> {
                boolean[] bits = master.readCoils(unitId, p.address(), p.quantity());
                yield bits.length > 0 ? bits[0] : null;
            }
            case "DISCRETE_INPUT" -> {
                boolean[] bits = master.readDiscreteInputs(unitId, p.address(), p.quantity());
                yield bits.length > 0 ? bits[0] : null;
            }
            default -> throw new ModbusIoException("unsupported registerKind: " + p.registerKind(), false);
        };
    }

    private static BigDecimal decodeRegisterRead(byte[] raw, ModbusPoint p) {
        DataType dt = DataType.valueOf(p.dataType());
        ByteOrder order = p.byteOrder() == null ? ByteOrder.ABCD : ByteOrder.valueOf(p.byteOrder());
        BigDecimal scale = p.scale() == null ? null : BigDecimal.valueOf(p.scale());
        return RegisterDecoder.decode(raw, dt, order, scale);
    }

    @Override
    public void stop() {
        connected = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (master != null) {
            try {
                master.close();
            } catch (Exception e) {
                log.warn("Modbus TCP master close error: {}", e.toString());
            }
            master = null;
        }
    }

    @Override
    public boolean isConnected() {
        return connected && master != null && master.isConnected();
    }

    @Override
    public TestResult testConnection(ChannelConfig config) {
        if (!(config instanceof ModbusTcpConfig cfg)) {
            return TestResult.fail("expected ModbusTcpConfig");
        }
        long start = System.currentTimeMillis();
        int timeoutMs = cfg.timeout() != null
                ? Math.min((int) cfg.timeout().toMillis(), TEST_TIMEOUT_CAP_MS)
                : TEST_TIMEOUT_MS;
        try (TcpModbusMaster m = new TcpModbusMaster(cfg.host(), cfg.port(), timeoutMs)) {
            m.open();
            return TestResult.ok(System.currentTimeMillis() - start);
        } catch (Exception e) {
            return TestResult.fail(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }
}
