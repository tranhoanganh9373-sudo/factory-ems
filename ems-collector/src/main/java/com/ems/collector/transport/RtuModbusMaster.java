package com.ems.collector.transport;

import com.ems.collector.config.DeviceConfig;
import com.ems.collector.config.Parity;
import com.ghgande.j2mod.modbus.ModbusException;
import com.ghgande.j2mod.modbus.facade.ModbusSerialMaster;
import com.ghgande.j2mod.modbus.procimg.InputRegister;
import com.ghgande.j2mod.modbus.procimg.Register;
import com.ghgande.j2mod.modbus.util.BitVector;
import com.ghgande.j2mod.modbus.util.SerialParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Modbus RTU master：j2mod {@link ModbusSerialMaster} 包装。
 *
 * <p>序列化语义同 {@link TcpModbusMaster}：j2mod 抛出的 ModbusException 全部转
 * {@link ModbusIoException}（transient=true）；连接生命周期不在这层做重连/重试。
 *
 * <p>多 device 同 {@code serial-port} 共串口的互斥由
 * {@link com.ems.collector.poller.DevicePoller} 通过 {@code SerialPortLockRegistry}
 * 在 pollOnce() 入口加锁保证（Phase C）。本类内部仅用 synchronized 保护单实例的并发。
 */
public class RtuModbusMaster implements ModbusMaster {

    private static final Logger log = LoggerFactory.getLogger(RtuModbusMaster.class);

    private final SerialParameters params;
    private final int timeoutMs;
    private ModbusSerialMaster master;

    public RtuModbusMaster(DeviceConfig dev) {
        this.params = buildSerialParameters(dev);
        this.timeoutMs = dev.timeoutMs();
    }

    /** Visible for testing. */
    static SerialParameters buildSerialParameters(DeviceConfig dev) {
        SerialParameters p = new SerialParameters();
        p.setPortName(dev.serialPort());
        p.setBaudRate(dev.baudRate());
        p.setDatabits(dev.dataBits());
        p.setStopbits(dev.stopBits());
        p.setParity(parityToJ2mod(dev.parity()));
        p.setEncoding("rtu");
        p.setEcho(false);
        return p;
    }

    /** j2mod 的 parity 字段接受 int (0=none,1=odd,2=even) 或 string；这里用 int 更明确。 */
    static int parityToJ2mod(Parity parity) {
        return switch (parity == null ? Parity.NONE : parity) {
            case NONE -> 0;
            case ODD -> 1;
            case EVEN -> 2;
        };
    }

    @Override
    public synchronized void open() throws ModbusIoException {
        if (master != null && master.isConnected()) return;
        master = new ModbusSerialMaster(params);
        master.setTimeout(timeoutMs);
        try {
            master.connect();
        } catch (Exception e) {
            master = null;
            throw new ModbusIoException("RTU connect " + params.getPortName()
                    + " (baud=" + params.getBaudRate() + ") failed: " + e.getMessage(), e, true);
        }
        log.debug("Modbus RTU connected: port={} baud={}", params.getPortName(), params.getBaudRate());
    }

    @Override
    public synchronized void close() {
        if (master == null) return;
        try {
            master.disconnect();
        } catch (Exception e) {
            log.warn("Modbus RTU disconnect raised: {}", e.toString());
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
            return concat(regs, count);
        } catch (ModbusException e) {
            throw new ModbusIoException("RTU readHolding(unit=" + unitId + ", addr=0x"
                    + Integer.toHexString(address) + ", count=" + count + "): "
                    + e.getMessage(), e, true);
        }
    }

    @Override
    public synchronized byte[] readInput(int unitId, int address, int count) throws ModbusIoException {
        ensureOpen();
        try {
            InputRegister[] regs = master.readInputRegisters(unitId, address, count);
            return concatInput(regs, count);
        } catch (ModbusException e) {
            throw new ModbusIoException("RTU readInput failed: " + e.getMessage(), e, true);
        }
    }

    @Override
    public synchronized boolean[] readCoils(int unitId, int address, int count) throws ModbusIoException {
        ensureOpen();
        try {
            BitVector bv = master.readCoils(unitId, address, count);
            return toBoolArray(bv, count);
        } catch (ModbusException e) {
            throw new ModbusIoException("RTU readCoils failed: " + e.getMessage(), e, true);
        }
    }

    @Override
    public synchronized boolean[] readDiscreteInputs(int unitId, int address, int count) throws ModbusIoException {
        ensureOpen();
        try {
            BitVector bv = master.readInputDiscretes(unitId, address, count);
            return toBoolArray(bv, count);
        } catch (ModbusException e) {
            throw new ModbusIoException("RTU readDiscreteInputs failed: " + e.getMessage(), e, true);
        }
    }

    private void ensureOpen() throws ModbusIoException {
        if (master == null || !master.isConnected()) {
            throw new ModbusIoException("RTU not connected (call open() first)", true);
        }
    }

    private static byte[] concat(Register[] regs, int expected) throws ModbusIoException {
        if (regs == null || regs.length != expected) {
            throw new ModbusIoException("readHolding length=" + (regs == null ? "null" : regs.length)
                    + " expected " + expected, true);
        }
        byte[] out = new byte[regs.length * 2];
        for (int i = 0; i < regs.length; i++) {
            byte[] b = regs[i].toBytes();
            out[i * 2] = b[0];
            out[i * 2 + 1] = b[1];
        }
        return out;
    }

    private static byte[] concatInput(InputRegister[] regs, int expected) throws ModbusIoException {
        if (regs == null || regs.length != expected) {
            throw new ModbusIoException("readInput length=" + (regs == null ? "null" : regs.length)
                    + " expected " + expected, true);
        }
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
        for (int i = 0; i < count; i++) out[i] = bv.getBit(i);
        return out;
    }
}
