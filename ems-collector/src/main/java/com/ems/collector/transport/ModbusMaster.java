package com.ems.collector.transport;

/**
 * Modbus master（主站）抽象。单一目的：屏蔽 j2mod API，让业务代码 / 测试 mock 不直接依赖
 * {@code com.ghgande.j2mod.*}。换实现（digitalpetri / 自研）只换这层。
 *
 * <p>线程安全约定：单实例不要求线程安全。一个 {@link com.ems.collector.poller.DevicePoller}
 * 持有一个 master，poller 自己保证串行调用。
 *
 * <p>连接生命周期：
 * <pre>
 *   master = new TcpModbusMaster(host, port, timeoutMs);
 *   master.open();                       // 阻塞，连不上抛 ModbusIoException(transient=false)
 *   try {
 *       byte[] r = master.readHolding(1, 0x2000, 2);   // 多次复用
 *   } finally {
 *       master.close();                  // 幂等
 *   }
 * </pre>
 */
public interface ModbusMaster extends AutoCloseable {

    /** Open the underlying transport (TCP socket / serial port). Idempotent if already open. */
    void open() throws ModbusIoException;

    /** Close the transport. Idempotent. Never throws. */
    @Override
    void close();

    /** Whether the underlying transport currently believes it has a usable connection. */
    boolean isConnected();

    /**
     * FC03 — Read holding registers.
     *
     * @param unitId  Modbus slave id (1..247)
     * @param address starting register address (0-based)
     * @param count   number of 16-bit registers to read (1..125)
     * @return big-endian byte[] of length {@code count*2}
     */
    byte[] readHolding(int unitId, int address, int count) throws ModbusIoException;

    /** FC04 — Read input registers. Same byte layout as {@link #readHolding}. */
    byte[] readInput(int unitId, int address, int count) throws ModbusIoException;

    /** FC01 — Read coils. */
    boolean[] readCoils(int unitId, int address, int count) throws ModbusIoException;

    /** FC02 — Read discrete inputs. */
    boolean[] readDiscreteInputs(int unitId, int address, int count) throws ModbusIoException;
}
