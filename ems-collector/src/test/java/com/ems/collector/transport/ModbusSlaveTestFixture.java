package com.ems.collector.transport;

import com.ghgande.j2mod.modbus.procimg.ProcessImage;
import com.ghgande.j2mod.modbus.procimg.SimpleDigitalIn;
import com.ghgande.j2mod.modbus.procimg.SimpleDigitalOut;
import com.ghgande.j2mod.modbus.procimg.SimpleInputRegister;
import com.ghgande.j2mod.modbus.procimg.SimpleProcessImage;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;
import com.ghgande.j2mod.modbus.slave.ModbusSlave;
import com.ghgande.j2mod.modbus.slave.ModbusSlaveFactory;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * j2mod 内嵌 Modbus TCP slave，用于 IT/单元测试不依赖真硬件。每个 instance 占一个随机空闲端口。
 *
 * <p>用法：
 * <pre>{@code
 * try (var slave = ModbusSlaveTestFixture.start(1)) {
 *     slave.setHoldingRegister(0, (short) 0x1234);
 *     // 用 slave.port() 连过去
 * }
 * }</pre>
 *
 * <p>SimpleProcessImage 的"地址"由 register 添加顺序决定：第一个 add 占 address 0，第二个占 1，依此类推。
 * 想直接 set 高地址（比如 0x2000）需要 add 足量占位 register 把列表撑到目标 index。{@link #setHoldingRegister(int, short)}
 * 自动按需扩容。
 */
public final class ModbusSlaveTestFixture implements AutoCloseable {

    private final int port;
    private final int unitId;
    private final ModbusSlave slave;
    private final SimpleProcessImage image;

    private ModbusSlaveTestFixture(int port, int unitId, ModbusSlave slave, SimpleProcessImage image) {
        this.port = port;
        this.unitId = unitId;
        this.slave = slave;
        this.image = image;
    }

    public static ModbusSlaveTestFixture start(int unitId) throws Exception {
        int port = pickFreePort();
        SimpleProcessImage image = new SimpleProcessImage();
        ModbusSlave slave = ModbusSlaveFactory.createTCPSlave(port, 5);
        slave.addProcessImage(unitId, image);
        slave.open();
        return new ModbusSlaveTestFixture(port, unitId, slave, image);
    }

    public int port() {
        return port;
    }

    public int unitId() {
        return unitId;
    }

    public ProcessImage image() {
        return image;
    }

    /** Set a 16-bit holding register at the given address; auto-grows the image. */
    public void setHoldingRegister(int address, short value) {
        ensureRegisterSize(address);
        SimpleRegister r = (SimpleRegister) image.getRegister(address);
        r.setValue(value);
    }

    /** Set 2 consecutive holding registers from a 32-bit value (big-endian word, ABCD layout). */
    public void setHoldingUInt32(int address, int value) {
        setHoldingRegister(address, (short) ((value >>> 16) & 0xFFFF));
        setHoldingRegister(address + 1, (short) (value & 0xFFFF));
    }

    /** Set 2 consecutive holding registers from raw 4 bytes (ABCD order). */
    public void setHoldingFloat32(int address, float value) {
        int bits = Float.floatToRawIntBits(value);
        setHoldingUInt32(address, bits);
    }

    /** Add a single input register (FC04). Sequentially appended; address = current size. */
    public int addInputRegister(short value) {
        image.addInputRegister(new SimpleInputRegister(value));
        return image.getInputRegisterCount() - 1;
    }

    /** Add a coil (FC01). */
    public int addCoil(boolean value) {
        image.addDigitalOut(new SimpleDigitalOut(value));
        return image.getDigitalOutCount() - 1;
    }

    /** Add a discrete input (FC02). */
    public int addDiscreteInput(boolean value) {
        image.addDigitalIn(new SimpleDigitalIn(value));
        return image.getDigitalInCount() - 1;
    }

    @Override
    public void close() {
        if (slave != null) {
            slave.close();
        }
    }

    /** 把 holding register 列表撑到 ≥ address+1，缺位补 0。 */
    private void ensureRegisterSize(int address) {
        while (image.getRegisterCount() <= address) {
            image.addRegister(new SimpleRegister(0));
        }
    }

    private static int pickFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }
}
