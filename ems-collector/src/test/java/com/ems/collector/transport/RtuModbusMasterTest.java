package com.ems.collector.transport;

import com.ems.collector.config.ByteOrder;
import com.ems.collector.config.DataType;
import com.ems.collector.config.DeviceConfig;
import com.ems.collector.config.FunctionType;
import com.ems.collector.config.Parity;
import com.ems.collector.config.Protocol;
import com.ems.collector.config.RegisterConfig;
import com.ems.collector.config.RegisterKind;
import com.ghgande.j2mod.modbus.util.SerialParameters;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase A — RtuModbusMaster 单测。
 *
 * <p>真实 RTU 连接验证（open / read）依赖物理串口或 socat 虚拟串口对，CI 不跑。
 * 这里覆盖：
 * <ul>
 *   <li>SerialParameters 映射正确（baud / databits / stopbits / parity / encoding）</li>
 *   <li>Parity 枚举到 j2mod int 的转换</li>
 *   <li>未 open 直接 read → ModbusIoException(transient)</li>
 * </ul>
 *
 * <p>Plan 1.5.2 Phase G 集成测试用 j2mod RTU master/slave 同 JVM loopback；
 * Plan 1.5.3 才接真硬件。
 */
class RtuModbusMasterTest {

    @Test
    void serialParameters_mapsAllFieldsCorrectly() {
        DeviceConfig dev = rtuDevice("/dev/ttyUSB0", 19200, 8, 1, Parity.EVEN);
        SerialParameters p = RtuModbusMaster.buildSerialParameters(dev);
        assertThat(p.getPortName()).isEqualTo("/dev/ttyUSB0");
        assertThat(p.getBaudRate()).isEqualTo(19200);
        assertThat(p.getDatabits()).isEqualTo(8);
        assertThat(p.getStopbits()).isEqualTo(1);
        assertThat(p.getParity()).isEqualTo(2);  // EVEN = 2
        assertThat(p.getEncoding()).isEqualTo("rtu");
    }

    @Test
    void parity_NONE_mapsToZero() {
        assertThat(RtuModbusMaster.parityToJ2mod(Parity.NONE)).isZero();
    }

    @Test
    void parity_ODD_mapsToOne() {
        assertThat(RtuModbusMaster.parityToJ2mod(Parity.ODD)).isEqualTo(1);
    }

    @Test
    void parity_EVEN_mapsToTwo() {
        assertThat(RtuModbusMaster.parityToJ2mod(Parity.EVEN)).isEqualTo(2);
    }

    @Test
    void parity_null_defaultsToNone() {
        assertThat(RtuModbusMaster.parityToJ2mod(null)).isZero();
    }

    @Test
    void readBeforeOpen_throwsTransientError() {
        DeviceConfig dev = rtuDevice("/dev/ttyNonExistent", 9600, 8, 1, Parity.NONE);
        var master = new RtuModbusMaster(dev);

        assertThatThrownBy(() -> master.readHolding(1, 0, 1))
                .isInstanceOf(ModbusIoException.class)
                .matches(t -> ((ModbusIoException) t).isTransient())
                .hasMessageContaining("not connected");
    }

    @Test
    void close_isIdempotent() {
        DeviceConfig dev = rtuDevice("/dev/ttyAny", 9600, 8, 1, Parity.NONE);
        var master = new RtuModbusMaster(dev);
        master.close();   // never opened
        master.close();   // again
        assertThat(master.isConnected()).isFalse();
    }

    @Test
    void connectFailed_onNonExistentPort_throwsTransient() {
        // 用不存在的串口，j2mod 应该抛连接错误
        DeviceConfig dev = rtuDevice("/dev/this-port-does-not-exist-9999", 9600, 8, 1, Parity.NONE);
        var master = new RtuModbusMaster(dev);
        assertThatThrownBy(master::open)
                .isInstanceOf(ModbusIoException.class)
                .matches(t -> ((ModbusIoException) t).isTransient());
    }

    /* ── helpers ───────────────────────────────────────────────────────── */

    private static DeviceConfig rtuDevice(String port, int baud, int dataBits, int stopBits, Parity parity) {
        return new DeviceConfig(
                "rtu-1", "MOCK-M-ELEC-001", Protocol.RTU,
                null, null,                 // TCP fields null
                port, baud, dataBits, stopBits, parity,
                1, 5000, 1000, 0, 25_000, 10_000,
                List.of(new RegisterConfig("voltage_a", 0, 2,
                        FunctionType.HOLDING, DataType.FLOAT32, ByteOrder.ABCD,
                        BigDecimal.ONE, "V", "voltage_a", RegisterKind.GAUGE))
        );
    }
}
