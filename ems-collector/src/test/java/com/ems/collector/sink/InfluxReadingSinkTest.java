package com.ems.collector.sink;

import com.ems.collector.poller.DeviceReading;
import com.ems.meter.entity.Meter;
import com.ems.meter.repository.MeterRepository;
import com.ems.timeseries.config.InfluxProperties;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.write.Point;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InfluxReadingSinkTest {

    private InfluxDBClient client;
    private WriteApiBlocking writeApi;
    private InfluxProperties props;
    private MeterRepository meters;
    private InfluxReadingSink sink;

    @BeforeEach
    void setUp() {
        client = mock(InfluxDBClient.class);
        writeApi = mock(WriteApiBlocking.class);
        when(client.getWriteApiBlocking()).thenReturn(writeApi);
        props = new InfluxProperties();
        props.setBucket("factory_ems");
        props.setOrg("factory");
        meters = mock(MeterRepository.class);
        sink = new InfluxReadingSink(client, props, meters);
    }

    @Test
    void buildPoint_includesMeasurement_tag_and_numericFields() {
        Meter m = newMeter("MOCK-M-ELEC-001", "energy_reading", "meter_code", "MOCK-M-ELEC-001");
        DeviceReading r = new DeviceReading(
                "dev-1", "MOCK-M-ELEC-001",
                Instant.parse("2026-04-27T10:00:00Z"),
                Map.of("voltage_a", new BigDecimal("220.50"),
                       "power_active", new BigDecimal("12.34")),
                Map.of()
        );

        Point p = InfluxReadingSink.buildPoint(r, m);
        String line = p.toLineProtocol();

        assertThat(line).startsWith("energy_reading,meter_code=MOCK-M-ELEC-001 ");
        assertThat(line).contains("voltage_a=220.5");
        assertThat(line).contains("power_active=12.34");
        // 时间戳精度 MS：Instant.parse(...).toEpochMilli()
        long expectedMillis = Instant.parse("2026-04-27T10:00:00Z").toEpochMilli();
        assertThat(line).endsWith(" " + expectedMillis);
    }

    @Test
    void buildPoint_includesBooleanFields() {
        Meter m = newMeter("M1", "energy_reading", "meter_code", "M1");
        DeviceReading r = new DeviceReading(
                "dev-1", "M1", Instant.parse("2026-04-27T10:00:00Z"),
                Map.of(),
                Map.of("alarm_high", true, "alarm_low", false)
        );

        String line = InfluxReadingSink.buildPoint(r, m).toLineProtocol();
        assertThat(line).contains("alarm_high=true");
        assertThat(line).contains("alarm_low=false");
    }

    @Test
    void accept_meterFound_writesPointToCorrectBucket() {
        Meter m = newMeter("M1", "energy_reading", "meter_code", "M1");
        when(meters.findByCode("M1")).thenReturn(Optional.of(m));

        sink.accept(new DeviceReading(
                "dev-1", "M1", Instant.parse("2026-04-27T10:00:00Z"),
                Map.of("v_a", new BigDecimal("220.0")),
                Map.of()
        ));

        ArgumentCaptor<Point> pc = ArgumentCaptor.forClass(Point.class);
        verify(writeApi).writePoint(eq("factory_ems"), eq("factory"), pc.capture());
        assertThat(pc.getValue().toLineProtocol()).contains("v_a=220");
    }

    @Test
    void accept_meterMissing_dropsReadingSilently() {
        when(meters.findByCode("orphan")).thenReturn(Optional.empty());

        sink.accept(new DeviceReading(
                "dev-1", "orphan", Instant.parse("2026-04-27T10:00:00Z"),
                Map.of("v_a", new BigDecimal("220.0")),
                Map.of()
        ));

        verify(writeApi, never()).writePoint(any(), any(), any(Point.class));
    }

    @Test
    void accept_writeApiThrows_doesNotPropagate() {
        Meter m = newMeter("M1", "energy_reading", "meter_code", "M1");
        when(meters.findByCode("M1")).thenReturn(Optional.of(m));
        org.mockito.Mockito.doThrow(new RuntimeException("influx down"))
                .when(writeApi).writePoint(any(), any(), any(Point.class));

        // must NOT throw — sink swallows write errors so they don't blow up the poller cycle
        sink.accept(new DeviceReading(
                "dev-1", "M1", Instant.parse("2026-04-27T10:00:00Z"),
                Map.of("v_a", new BigDecimal("220.0")),
                Map.of()
        ));
    }

    private static Meter newMeter(String code, String measurement, String tagKey, String tagValue) {
        Meter m = new Meter();
        m.setCode(code);
        m.setName(code);
        m.setInfluxMeasurement(measurement);
        m.setInfluxTagKey(tagKey);
        m.setInfluxTagValue(tagValue);
        return m;
    }
}
