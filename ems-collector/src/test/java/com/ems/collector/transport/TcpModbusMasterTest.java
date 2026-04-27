package com.ems.collector.transport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TcpModbusMasterTest {

    private ModbusSlaveTestFixture slave;

    @BeforeEach
    void setUp() throws Exception {
        slave = ModbusSlaveTestFixture.start(1);
    }

    @AfterEach
    void tearDown() {
        if (slave != null) slave.close();
    }

    @Test
    void open_readHolding_close_roundTrip() throws Exception {
        slave.setHoldingRegister(0, (short) 0x1234);
        slave.setHoldingRegister(1, (short) 0x5678);

        try (var master = new TcpModbusMaster("127.0.0.1", slave.port(), 1000)) {
            master.open();
            assertThat(master.isConnected()).isTrue();

            byte[] data = master.readHolding(slave.unitId(), 0, 2);
            assertThat(data).containsExactly(0x12, 0x34, 0x56, 0x78);
        }
    }

    @Test
    void readHolding_atHigherAddress() throws Exception {
        slave.setHoldingRegister(0x10, (short) 0xCAFE);

        try (var master = new TcpModbusMaster("127.0.0.1", slave.port(), 1000)) {
            master.open();
            byte[] data = master.readHolding(slave.unitId(), 0x10, 1);
            assertThat(data).containsExactly((byte) 0xCA, (byte) 0xFE);
        }
    }

    @Test
    void readHolding_uint32Pair_isBigEndian() throws Exception {
        slave.setHoldingUInt32(0x20, 0xDEADBEEF);

        try (var master = new TcpModbusMaster("127.0.0.1", slave.port(), 1000)) {
            master.open();
            byte[] data = master.readHolding(slave.unitId(), 0x20, 2);
            assertThat(data).containsExactly((byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF);
        }
    }

    @Test
    void readHolding_float32Pair_decodesToOriginalValue() throws Exception {
        slave.setHoldingFloat32(0x30, 3.14159f);

        try (var master = new TcpModbusMaster("127.0.0.1", slave.port(), 1000)) {
            master.open();
            byte[] data = master.readHolding(slave.unitId(), 0x30, 2);
            int bits = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16)
                    | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
            assertThat(Float.intBitsToFloat(bits)).isEqualTo(3.14159f);
        }
    }

    @Test
    void readBeforeOpen_throwsTransient() {
        var master = new TcpModbusMaster("127.0.0.1", slave.port(), 1000);
        assertThatThrownBy(() -> master.readHolding(1, 0, 1))
                .isInstanceOf(ModbusIoException.class)
                .matches(t -> ((ModbusIoException) t).isTransient());
    }

    @Test
    void connectFailed_throwsTransient() {
        // 用一个保证不会有人监听的端口
        try (var master = new TcpModbusMaster("127.0.0.1", 1, 500)) {
            assertThatThrownBy(master::open)
                    .isInstanceOf(ModbusIoException.class)
                    .matches(t -> ((ModbusIoException) t).isTransient());
        }
    }

    @Test
    void close_isIdempotent() {
        var master = new TcpModbusMaster("127.0.0.1", slave.port(), 1000);
        master.close();
        master.close();           // does not throw
        assertThat(master.isConnected()).isFalse();
    }

    @Test
    void disconnect_thenReconnect_works() throws Exception {
        slave.setHoldingRegister(0, (short) 0xAA55);
        try (var master = new TcpModbusMaster("127.0.0.1", slave.port(), 1000)) {
            master.open();
            byte[] r1 = master.readHolding(slave.unitId(), 0, 1);
            master.close();
            assertThat(master.isConnected()).isFalse();

            master.open();
            byte[] r2 = master.readHolding(slave.unitId(), 0, 1);
            assertThat(r2).isEqualTo(r1).containsExactly((byte) 0xAA, 0x55);
        }
    }

    @Test
    void readCoil_works() throws Exception {
        slave.addCoil(true);
        slave.addCoil(false);
        slave.addCoil(true);

        try (var master = new TcpModbusMaster("127.0.0.1", slave.port(), 1000)) {
            master.open();
            boolean[] coils = master.readCoils(slave.unitId(), 0, 3);
            assertThat(coils).containsExactly(true, false, true);
        }
    }

    @Test
    void readDiscreteInputs_works() throws Exception {
        slave.addDiscreteInput(false);
        slave.addDiscreteInput(true);

        try (var master = new TcpModbusMaster("127.0.0.1", slave.port(), 1000)) {
            master.open();
            boolean[] di = master.readDiscreteInputs(slave.unitId(), 0, 2);
            assertThat(di).containsExactly(false, true);
        }
    }

    @Test
    void readInputRegisters_works() throws Exception {
        slave.addInputRegister((short) 0x0001);
        slave.addInputRegister((short) 0x0203);

        try (var master = new TcpModbusMaster("127.0.0.1", slave.port(), 1000)) {
            master.open();
            byte[] data = master.readInput(slave.unitId(), 0, 2);
            assertThat(data).containsExactly(0x00, 0x01, 0x02, 0x03);
        }
    }
}
