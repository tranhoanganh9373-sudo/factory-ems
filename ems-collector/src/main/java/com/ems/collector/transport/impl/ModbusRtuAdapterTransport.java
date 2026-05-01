package com.ems.collector.transport.impl;

import com.ems.collector.codec.RegisterDecoder;
import com.ems.collector.config.ByteOrder;
import com.ems.collector.config.DataType;
import com.ems.collector.config.DeviceConfig;
import com.ems.collector.config.Parity;
import com.ems.collector.config.Protocol;
import com.ems.collector.protocol.ChannelConfig;
import com.ems.collector.protocol.ModbusPoint;
import com.ems.collector.protocol.ModbusRtuConfig;
import com.ems.collector.transport.ModbusIoException;
import com.ems.collector.transport.Quality;
import com.ems.collector.transport.RtuModbusMaster;
import com.ems.collector.transport.Sample;
import com.ems.collector.transport.SampleSink;
import com.ems.collector.transport.TestResult;
import com.ems.collector.transport.Transport;
import com.ems.collector.transport.TransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Modbus RTU Transport — 复用既有 {@link RtuModbusMaster}（j2mod 包装）+ {@link RegisterDecoder}。
 *
 * <p>结构与 {@link ModbusTcpAdapterTransport} 一致，仅参数源不同（串口 vs TCP）。
 * RtuModbusMaster 构造需要 {@link DeviceConfig}，本类通过 {@link #toDeviceConfig} 从
 * {@link ModbusRtuConfig} 适配。
 *
 * <p>线程命名 `modbus-rtu-{channelId}`。
 */
public final class ModbusRtuAdapterTransport implements Transport {

    private static final Logger log = LoggerFactory.getLogger(ModbusRtuAdapterTransport.class);
    private static final int DEFAULT_TIMEOUT_MS = 1000;
    private static final int TEST_TIMEOUT_MS = 2000;
    private static final int TEST_TIMEOUT_CAP_MS = 10_000;

    private RtuModbusMaster master;
    private ScheduledExecutorService scheduler;
    private volatile boolean connected = false;

    @Override
    public void start(Long channelId, ChannelConfig config, SampleSink sink) {
        if (!(config instanceof ModbusRtuConfig cfg)) {
            throw new TransportException("expected ModbusRtuConfig, got " + config.getClass().getSimpleName());
        }
        master = new RtuModbusMaster(toDeviceConfig(cfg, DEFAULT_TIMEOUT_MS));
        try {
            master.open();
            connected = true;
        } catch (ModbusIoException e) {
            master = null;
            throw new TransportException("Modbus RTU connect failed: " + e.getMessage(), e);
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "modbus-rtu-" + channelId);
            t.setDaemon(true);
            return t;
        });
        long periodMs = cfg.pollInterval().toMillis();
        scheduler.scheduleAtFixedRate(
                () -> pollAll(channelId, cfg, sink),
                0L, periodMs, TimeUnit.MILLISECONDS);
        log.info("Modbus RTU transport started: channel={} port={} baud={} unitId={} interval={}ms points={}",
                channelId, cfg.serialPort(), cfg.baudRate(), cfg.unitId(), periodMs, cfg.points().size());
    }

    private void pollAll(Long channelId, ModbusRtuConfig cfg, SampleSink sink) {
        for (ModbusPoint p : cfg.points()) {
            try {
                Object value = readPoint(cfg.unitId(), p);
                sink.accept(new Sample(channelId, p.key(), Instant.now(),
                        value, Quality.GOOD, Map.of()));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                log.warn("Modbus RTU read failed for channel={} point={}: {}", channelId, p.key(), msg);
                sink.accept(new Sample(channelId, p.key(), Instant.now(),
                        null, Quality.BAD, Map.of("error", msg)));
            }
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
                log.warn("Modbus RTU master close error: {}", e.toString());
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
        if (!(config instanceof ModbusRtuConfig cfg)) {
            return TestResult.fail("expected ModbusRtuConfig");
        }
        int timeoutMs = cfg.timeout() != null
                ? Math.min((int) cfg.timeout().toMillis(), TEST_TIMEOUT_CAP_MS)
                : TEST_TIMEOUT_MS;
        long start = System.currentTimeMillis();
        try (RtuModbusMaster m = new RtuModbusMaster(toDeviceConfig(cfg, timeoutMs))) {
            m.open();
            return TestResult.ok(System.currentTimeMillis() - start);
        } catch (Exception e) {
            return TestResult.fail(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /**
     * 把 ModbusRtuConfig 适配成 RtuModbusMaster 需要的 DeviceConfig（仅 RTU 串口字段有效，
     * 其他字段填占位值满足 record compact constructor 默认值规则）。
     *
     * <p>本对象不会进入 {@code CollectorPropertiesValidator}（只在 transport 内即时使用），
     * 因此 NotEmpty registers 等校验注解不会触发。
     */
    private static DeviceConfig toDeviceConfig(ModbusRtuConfig cfg, int timeoutMs) {
        Parity parity;
        try {
            parity = Parity.valueOf(cfg.parity().toUpperCase());
        } catch (Exception e) {
            parity = Parity.NONE;
        }
        int periodMs = Math.max(1000, (int) cfg.pollInterval().toMillis());
        return new DeviceConfig(
                "rtu-adapter",
                "rtu-adapter",
                Protocol.RTU,
                null, null,
                cfg.serialPort(),
                cfg.baudRate(),
                cfg.dataBits(),
                cfg.stopBits(),
                parity,
                cfg.unitId(),
                periodMs,
                Math.max(100, timeoutMs),
                0,
                periodMs * 5,
                10_000,
                List.of()
        );
    }
}
