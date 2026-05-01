package com.ems.collector.sink;

import com.ems.collector.transport.Quality;
import com.ems.collector.transport.Sample;
import com.ems.meter.entity.Meter;
import com.ems.meter.repository.MeterRepository;
import com.ems.timeseries.config.InfluxProperties;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.write.Point;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("InfluxSampleWriter")
class InfluxSampleWriterTest {

    private InfluxDBClient client;
    private WriteApiBlocking writeApi;
    private InfluxProperties props;
    private MeterRepository meters;
    private InfluxSampleWriter writer;

    @BeforeEach
    void setUp() {
        client = mock(InfluxDBClient.class);
        writeApi = mock(WriteApiBlocking.class);
        when(client.getWriteApiBlocking()).thenReturn(writeApi);
        props = new InfluxProperties();
        props.setBucket("ems-bucket");
        props.setOrg("ems-org");
        meters = mock(MeterRepository.class);
        writer = new InfluxSampleWriter(client, props, meters);
    }

    @Test
    @DisplayName("找到 meter 时写 Point 到 InfluxDB（measurement + tag + field）")
    void write_meterFound_writesPoint() {
        Meter meter = newMeter("hvac", "device", "hvac-01");
        when(meters.findByChannelIdAndCode(1L, "hvac-01-flow"))
            .thenReturn(Optional.of(meter));

        Sample sample = new Sample(1L, "hvac-01-flow", Instant.parse("2026-04-30T10:00:00Z"),
            23.5, Quality.GOOD, Map.of());
        writer.write(sample);

        verify(writeApi, times(1))
            .writePoint(eq("ems-bucket"), eq("ems-org"), any(Point.class));
    }

    @Test
    @DisplayName("找不到 meter 时跳过，不写 InfluxDB")
    void write_meterNotFound_skips() {
        when(meters.findByChannelIdAndCode(1L, "unknown")).thenReturn(Optional.empty());
        Sample sample = new Sample(1L, "unknown", Instant.now(), 1.0, Quality.GOOD, Map.of());
        writer.write(sample);

        verify(writeApi, never()).writePoint(anyString(), anyString(), any(Point.class));
    }

    @Test
    @DisplayName("null sample 安全忽略")
    void write_null_noop() {
        writer.write(null);
        verify(writeApi, never()).writePoint(anyString(), anyString(), any(Point.class));
    }

    @Test
    @DisplayName("sample.channelId 为 null 时安全忽略")
    void write_nullChannelId_noop() {
        Sample sample = new Sample(null, "p", Instant.now(), 1.0, Quality.GOOD, Map.of());
        writer.write(sample);
        verify(writeApi, never()).writePoint(anyString(), anyString(), any(Point.class));
    }

    @Test
    @DisplayName("Boolean value 写为 boolean field")
    void write_booleanValue_writesPoint() {
        Meter meter = newMeter("alarms", "device", "d1");
        when(meters.findByChannelIdAndCode(1L, "high")).thenReturn(Optional.of(meter));

        Sample sample = new Sample(1L, "high", Instant.now(), Boolean.TRUE, Quality.GOOD, Map.of());
        writer.write(sample);

        verify(writeApi, times(1)).writePoint(anyString(), anyString(), any(Point.class));
    }

    @Test
    @DisplayName("null value 跳过，不写 InfluxDB")
    void write_nullValue_skips() {
        Meter meter = newMeter("m", "t", "v");
        when(meters.findByChannelIdAndCode(1L, "p")).thenReturn(Optional.of(meter));

        Sample sample = new Sample(1L, "p", Instant.now(), null, Quality.BAD, Map.of());
        writer.write(sample);

        verify(writeApi, never()).writePoint(anyString(), anyString(), any(Point.class));
    }

    @Test
    @DisplayName("写 Influx 抛异常时不向上传播")
    void write_influxThrows_swallows() {
        Meter meter = newMeter("m", "t", "v");
        when(meters.findByChannelIdAndCode(1L, "p")).thenReturn(Optional.of(meter));
        doThrow(new RuntimeException("influx down"))
            .when(writeApi).writePoint(anyString(), anyString(), any(Point.class));

        Sample sample = new Sample(1L, "p", Instant.now(), 1.0, Quality.GOOD, Map.of());
        // 不应抛
        writer.write(sample);
    }

    private static Meter newMeter(String measurement, String tagKey, String tagValue) {
        Meter m = new Meter();
        m.setInfluxMeasurement(measurement);
        m.setInfluxTagKey(tagKey);
        m.setInfluxTagValue(tagValue);
        return m;
    }
}
