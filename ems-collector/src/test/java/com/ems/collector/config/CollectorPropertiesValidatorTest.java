package com.ems.collector.config;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CollectorPropertiesValidatorTest {

    @Test
    void validate_emptyOrNull_isOk() {
        assertThat(CollectorPropertiesValidator.validate(null)).isEmpty();
        assertThat(CollectorPropertiesValidator.validate(new CollectorProperties(true, List.of())))
                .isEmpty();
    }

    @Test
    void validate_validTcpDevice_isOk() {
        var props = new CollectorProperties(true, List.of(tcpDevice("d1", "MOCK-M-ELEC-001")));
        assertThat(CollectorPropertiesValidator.validate(props)).isEmpty();
    }

    @Test
    void validate_duplicateDeviceId_isFlagged() {
        var props = new CollectorProperties(true, List.of(
                tcpDevice("dup", "MOCK-M-ELEC-001"),
                tcpDevice("dup", "MOCK-M-ELEC-002")
        ));
        assertThat(CollectorPropertiesValidator.validate(props))
                .anyMatch(s -> s.contains("duplicate device id"));
    }

    @Test
    void validate_tcpWithoutHost_isFlagged() {
        var device = new DeviceConfig(
                "d1", "MOCK-M-ELEC-001", Protocol.TCP,
                null, 502,                              // host missing
                null, null, null, null, null,
                1, 5000, 1000, 3, 25000, 10000,
                List.of(holdingFloat32Register("voltage_a", 0x2000))
        );
        var props = new CollectorProperties(true, List.of(device));
        assertThat(CollectorPropertiesValidator.validate(props))
                .anyMatch(s -> s.contains("requires host"));
    }

    @Test
    void validate_rtuWithRequiredFields_isOk() {
        var device = rtuDevice("/dev/ttyUSB0", 9600, 8, 1, Parity.NONE);
        var props = new CollectorProperties(true, List.of(device));
        assertThat(CollectorPropertiesValidator.validate(props)).isEmpty();
    }

    @Test
    void validate_rtuMissingSerialPort_isFlagged() {
        var device = rtuDevice(null, 9600, 8, 1, Parity.NONE);
        var props = new CollectorProperties(true, List.of(device));
        assertThat(CollectorPropertiesValidator.validate(props))
                .anyMatch(s -> s.contains("RTU requires serialPort"));
    }

    @Test
    void validate_rtuMissingBaudRate_isFlagged() {
        var device = rtuDevice("/dev/ttyUSB0", null, 8, 1, Parity.NONE);
        var props = new CollectorProperties(true, List.of(device));
        assertThat(CollectorPropertiesValidator.validate(props))
                .anyMatch(s -> s.contains("RTU requires baudRate"));
    }

    @Test
    void validate_rtuBaudRateOutOfRange_isFlagged() {
        var device = rtuDevice("/dev/ttyUSB0", 300, 8, 1, Parity.NONE);
        var props = new CollectorProperties(true, List.of(device));
        assertThat(CollectorPropertiesValidator.validate(props))
                .anyMatch(s -> s.contains("baudRate must be 1200..115200"));
    }

    @Test
    void validate_rtuStopBits3_isFlagged() {
        var device = rtuDevice("/dev/ttyUSB0", 9600, 8, 3, Parity.NONE);
        var props = new CollectorProperties(true, List.of(device));
        assertThat(CollectorPropertiesValidator.validate(props))
                .anyMatch(s -> s.contains("stopBits must be 1 or 2"));
    }

    @Test
    void validate_rtuDataBits9_isFlagged() {
        var device = rtuDevice("/dev/ttyUSB0", 9600, 9, 1, Parity.NONE);
        var props = new CollectorProperties(true, List.of(device));
        assertThat(CollectorPropertiesValidator.validate(props))
                .anyMatch(s -> s.contains("dataBits must be 5..8"));
    }

    @Test
    void validate_backoffShorterThanPolling_isFlagged() {
        var device = tcpDeviceBuilder("d1", "MOCK-M-ELEC-001")
                .pollingIntervalMs(5000)
                .backoffMs(2000)
                .build();
        var props = new CollectorProperties(true, List.of(device));
        assertThat(CollectorPropertiesValidator.validate(props))
                .anyMatch(s -> s.contains("backoffMs must be >= pollingIntervalMs"));
    }

    @Test
    void validate_timeoutLongerThanPolling_isFlagged() {
        var device = tcpDeviceBuilder("d1", "MOCK-M-ELEC-001")
                .pollingIntervalMs(2000)
                .timeoutMs(3000)
                .build();
        var props = new CollectorProperties(true, List.of(device));
        assertThat(CollectorPropertiesValidator.validate(props))
                .anyMatch(s -> s.contains("timeoutMs must be <= pollingIntervalMs"));
    }

    @Test
    void validate_duplicateTsFieldWithinDevice_isFlagged() {
        var device = tcpDeviceBuilder("d1", "MOCK-M-ELEC-001")
                .registers(List.of(
                        holdingFloat32Register("v_a", 0x2000),
                        holdingFloat32Register("v_a", 0x2002) // dup tsField
                ))
                .build();
        var props = new CollectorProperties(true, List.of(device));
        assertThat(CollectorPropertiesValidator.validate(props))
                .anyMatch(s -> s.contains("duplicate tsField 'v_a'"));
    }

    @Test
    void validate_countMismatchesDataType_isFlagged() {
        var bad = new RegisterConfig(
                "voltage_a", 0x2000,
                1,                           // wrong: FLOAT32 needs 2
                FunctionType.HOLDING, DataType.FLOAT32,
                ByteOrder.ABCD, BigDecimal.ONE, "V", "voltage_a", RegisterKind.GAUGE
        );
        var device = tcpDeviceBuilder("d1", "MOCK-M-ELEC-001").registers(List.of(bad)).build();
        var props = new CollectorProperties(true, List.of(device));
        assertThat(CollectorPropertiesValidator.validate(props))
                .anyMatch(s -> s.contains("count=1 mismatches dataType=FLOAT32"));
    }

    @Test
    void validate_coilWithNonBitDataType_isFlagged() {
        var bad = new RegisterConfig(
                "alarm", 0x0001,
                1, FunctionType.COIL, DataType.UINT16,
                ByteOrder.ABCD, BigDecimal.ONE, null, "alarm", RegisterKind.GAUGE
        );
        var device = tcpDeviceBuilder("d1", "MOCK-M-ELEC-001").registers(List.of(bad)).build();
        var props = new CollectorProperties(true, List.of(device));
        assertThat(CollectorPropertiesValidator.validate(props))
                .anyMatch(s -> s.contains("requires dataType=BIT"));
    }

    @Test
    void validate_holdingWithBitDataType_isFlagged() {
        var bad = new RegisterConfig(
                "x", 0x2000,
                1, FunctionType.HOLDING, DataType.BIT,
                ByteOrder.ABCD, BigDecimal.ONE, null, "x", RegisterKind.GAUGE
        );
        var device = tcpDeviceBuilder("d1", "MOCK-M-ELEC-001").registers(List.of(bad)).build();
        var props = new CollectorProperties(true, List.of(device));
        assertThat(CollectorPropertiesValidator.validate(props))
                .anyMatch(s -> s.contains("incompatible with dataType=BIT"));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static DeviceConfig tcpDevice(String id, String meterCode) {
        return tcpDeviceBuilder(id, meterCode).build();
    }

    private static DeviceBuilder tcpDeviceBuilder(String id, String meterCode) {
        return new DeviceBuilder(id, meterCode);
    }

    private static DeviceConfig rtuDevice(String port, Integer baud, Integer dataBits, Integer stopBits,
                                          Parity parity) {
        return new DeviceConfig(
                "rtu-1", "MOCK-M-ELEC-002", Protocol.RTU,
                null, null,
                port, baud, dataBits, stopBits, parity,
                1, 5000, 1000, 3, 25000, 10000,
                List.of(holdingFloat32Register("voltage_a", 0x2000))
        );
    }

    private static RegisterConfig holdingFloat32Register(String tsField, int address) {
        return new RegisterConfig(
                tsField, address, 2,
                FunctionType.HOLDING, DataType.FLOAT32,
                ByteOrder.ABCD, BigDecimal.ONE, "V",
                tsField, RegisterKind.GAUGE
        );
    }

    /** Minimal mutable builder so tests can override individual fields without verbose ctor. */
    private static final class DeviceBuilder {
        String id;
        String meterCode;
        int pollingIntervalMs = 5000;
        int timeoutMs = 1000;
        int backoffMs = 25_000;
        List<RegisterConfig> registers;

        DeviceBuilder(String id, String meterCode) {
            this.id = id;
            this.meterCode = meterCode;
            this.registers = List.of(holdingFloat32Register("voltage_a", 0x2000));
        }

        DeviceBuilder pollingIntervalMs(int v) { this.pollingIntervalMs = v; return this; }
        DeviceBuilder timeoutMs(int v)         { this.timeoutMs = v; return this; }
        DeviceBuilder backoffMs(int v)         { this.backoffMs = v; return this; }
        DeviceBuilder registers(List<RegisterConfig> r) { this.registers = r; return this; }

        DeviceConfig build() {
            return new DeviceConfig(
                    id, meterCode, Protocol.TCP,
                    "192.168.0.1", 502,
                    null, null, null, null, null,
                    1, pollingIntervalMs, timeoutMs, 3, backoffMs, 10_000,
                    registers
            );
        }
    }
}
