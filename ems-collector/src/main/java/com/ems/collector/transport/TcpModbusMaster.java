package com.ems.collector.transport;

import com.ghgande.j2mod.modbus.ModbusException;
import com.ghgande.j2mod.modbus.facade.ModbusTCPMaster;
import com.ghgande.j2mod.modbus.procimg.InputRegister;
import com.ghgande.j2mod.modbus.procimg.Register;
import com.ghgande.j2mod.modbus.util.BitVector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * j2mod 的 {@link ModbusTCPMaster} 包装，把：
 * <ul>
 *   <li>{@code Register[]} → {@code byte[]}（big-endian, 每个 register 2 字节）</li>
 *   <li>{@code BitVector} → {@code boolean[]}</li>
 *   <li>{@link ModbusException} / {@link Exception} → {@link ModbusIoException}</li>
 * </ul>
 *
 * <p>重要 invariant：不在这一层做重连 / 重试。连错由 {@link com.ems.collector.poller.DevicePoller}
 * 处理（统一状态机），这层只负责"现在能不能开 socket、能不能读"。
 */
public class TcpModbusMaster implements ModbusMaster {

    private static final Logger log = LoggerFactory.getLogger(TcpModbusMaster.class);

    private final String host;
    private final int port;
    private final int timeoutMs;
    private ModbusTCPMaster master;

    public TcpModbusMaster(String host, int port, int timeoutMs) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public synchronized void open() throws ModbusIoException {
        if (master != null && master.isConnected()) return;
        try {
            // Resolve host first to give a clear DNS error vs. connection error.
            InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw new ModbusIoException("DNS resolve failed: " + host, e, false);
        }
        master = new ModbusTCPMaster(host, port);
        master.setTimeout(timeoutMs);
        try {
            master.connect();
        } catch (Exception e) {
            master = null;
            throw new ModbusIoException("connect " + host + ":" + port + " failed: " + e.getMessage(), e, true);
        }
        log.debug("Modbus TCP connected: {}:{}", host, port);
    }

    @Override
    public synchronized void close() {
        if (master == null) return;
        try {
            master.disconnect();
        } catch (Exception e) {
            log.warn("Modbus disconnect raised: {}", e.toString());
        } finally {
            master = null;
        }
    }

    @Override
    public synchronized boolean isConnected() {
        return master != null && master.isConnected();
    }

    @Override
    public synchronized byte[] readHolding(int unitId, int address, int count) throws ModbusIoException {
        ensureOpen();
        try {
            Register[] regs = master.readMultipleRegisters(unitId, address, count);
            if (regs == null || regs.length != count) {
                throw new ModbusIoException("readHolding returned unexpected length: "
                        + (regs == null ? "null" : regs.length) + " expected " + count, true);
            }
            return concat(regs);
        } catch (ModbusException e) {
            throw new ModbusIoException("readHolding(unit=" + unitId + ", addr=0x"
                    + Integer.toHexString(address) + ", count=" + count + ") failed: "
                    + e.getMessage(), e, true);
        }
    }

    @Override
    public synchronized byte[] readInput(int unitId, int address, int count) throws ModbusIoException {
        ensureOpen();
        try {
            InputRegister[] regs = master.readInputRegisters(unitId, address, count);
            if (regs == null || regs.length != count) {
                throw new ModbusIoException("readInput returned unexpected length: "
                        + (regs == null ? "null" : regs.length) + " expected " + count, true);
            }
            byte[] out = new byte[count * 2];
            for (int i = 0; i < count; i++) {
                byte[] b = regs[i].toBytes();
                out[i * 2] = b[0];
                out[i * 2 + 1] = b[1];
            }
            return out;
        } catch (ModbusException e) {
            throw new ModbusIoException("readInput failed: " + e.getMessage(), e, true);
        }
    }

    @Override
    public synchronized boolean[] readCoils(int unitId, int address, int count) throws ModbusIoException {
        ensureOpen();
        try {
            BitVector bv = master.readCoils(unitId, address, count);
            return toBoolArray(bv, count);
        } catch (ModbusException e) {
            throw new ModbusIoException("readCoils failed: " + e.getMessage(), e, true);
        }
    }

    @Override
    public synchronized boolean[] readDiscreteInputs(int unitId, int address, int count) throws ModbusIoException {
        ensureOpen();
        try {
            BitVector bv = master.readInputDiscretes(unitId, address, count);
            return toBoolArray(bv, count);
        } catch (ModbusException e) {
            throw new ModbusIoException("readDiscreteInputs failed: " + e.getMessage(), e, true);
        }
    }

    private void ensureOpen() throws ModbusIoException {
        if (master == null || !master.isConnected()) {
            throw new ModbusIoException("not connected (call open() first)", true);
        }
    }

    private static byte[] concat(Register[] regs) {
        byte[] out = new byte[regs.length * 2];
        for (int i = 0; i < regs.length; i++) {
            byte[] b = regs[i].toBytes();
            out[i * 2] = b[0];
            out[i * 2 + 1] = b[1];
        }
        return out;
    }

    private static boolean[] toBoolArray(BitVector bv, int count) {
        boolean[] out = new boolean[count];
        for (int i = 0; i < count; i++) {
            out[i] = bv.getBit(i);
        }
        return out;
    }
}
