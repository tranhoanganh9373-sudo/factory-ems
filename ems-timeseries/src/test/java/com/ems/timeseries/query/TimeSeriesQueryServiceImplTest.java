package com.ems.timeseries.query;

import com.ems.timeseries.config.InfluxProperties;
import com.ems.timeseries.model.Granularity;
import com.ems.timeseries.model.TimePoint;
import com.ems.timeseries.model.TimeRange;
import com.ems.timeseries.query.TimeSeriesQueryService.MeterRef;
import com.ems.timeseries.query.impl.TimeSeriesQueryServiceImpl;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxColumn;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TimeSeriesQueryServiceImplTest {

    InfluxDBClient influx;
    QueryApi queryApi;
    InfluxProperties props;
    RollupReaderPort rollup;
    TimeSeriesQueryServiceImpl svc;

    static final TimeRange HOUR_RANGE = new TimeRange(
        Instant.parse("2026-04-25T00:00:00Z"),
        Instant.parse("2026-04-25T02:00:00Z"));

    @BeforeEach
    void setup() {
        influx = mock(InfluxDBClient.class);
        queryApi = mock(QueryApi.class);
        when(influx.getQueryApi()).thenReturn(queryApi);
        props = new InfluxProperties();
        props.setBucket("factory_ems"); props.setOrg("factory"); props.setMeasurement("energy_reading");
        rollup = mock(RollupReaderPort.class);
        svc = new TimeSeriesQueryServiceImpl(influx, props, rollup);
    }

    @Test
    void queryByMeter_minute_callsInfluxOnly() {
        when(rollup.rollupBoundary(Granularity.MINUTE)).thenReturn(Instant.MIN);
        when(queryApi.query(anyString(), anyString())).thenReturn(List.of(
            tableOf(record("M1", Instant.parse("2026-04-25T00:01:00Z"), 5.0))));

        var out = svc.queryByMeter(
            List.of(new MeterRef(1L, "M1", "ELEC")),
            new TimeRange(Instant.parse("2026-04-25T00:00:00Z"), Instant.parse("2026-04-25T00:10:00Z")),
            Granularity.MINUTE);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).meterId()).isEqualTo(1L);
        assertThat(out.get(0).points()).hasSize(1);
        verify(rollup, never()).readBuckets(org.mockito.ArgumentMatchers.anyCollection(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void queryByMeter_hour_allFromRollup_skipsInflux() {
        when(rollup.rollupBoundary(Granularity.HOUR)).thenReturn(Instant.parse("2026-04-25T03:00:00Z"));
        when(rollup.readBuckets(org.mockito.ArgumentMatchers.anyCollection(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(Map.of(1L, List.of(new TimePoint(Instant.parse("2026-04-25T00:00:00Z"), 100.0))));

        var out = svc.queryByMeter(List.of(new MeterRef(1L, "M1", "ELEC")), HOUR_RANGE, Granularity.HOUR);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).points().get(0).value()).isEqualTo(100.0);
        verify(queryApi, never()).query(anyString(), anyString());
    }

    @Test
    void queryByMeter_hour_allFromInflux_skipsRollup() {
        when(rollup.rollupBoundary(Granularity.HOUR)).thenReturn(Instant.MIN);
        when(queryApi.query(anyString(), anyString())).thenReturn(List.of(
            tableOf(record("M1", Instant.parse("2026-04-25T00:00:00Z"), 50.0),
                    record("M1", Instant.parse("2026-04-25T01:00:00Z"), 60.0))));

        var out = svc.queryByMeter(List.of(new MeterRef(1L, "M1", "ELEC")), HOUR_RANGE, Granularity.HOUR);

        assertThat(out.get(0).points()).extracting(TimePoint::value).containsExactly(50.0, 60.0);
        verify(rollup, never()).readBuckets(org.mockito.ArgumentMatchers.anyCollection(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void queryByMeter_hour_split_mergesAndSorts() {
        // boundary 在 range 中间，rollup 段 [0:00 → 1:00)，influx 段 [1:00 → 2:00)
        Instant boundary = Instant.parse("2026-04-25T01:00:00Z");
        when(rollup.rollupBoundary(Granularity.HOUR)).thenReturn(boundary);
        when(rollup.readBuckets(org.mockito.ArgumentMatchers.anyCollection(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(Map.of(1L, List.of(new TimePoint(Instant.parse("2026-04-25T00:00:00Z"), 100.0))));
        when(queryApi.query(anyString(), anyString())).thenReturn(List.of(
            tableOf(record("M1", Instant.parse("2026-04-25T01:00:00Z"), 50.0))));

        var out = svc.queryByMeter(List.of(new MeterRef(1L, "M1", "ELEC")), HOUR_RANGE, Granularity.HOUR);

        assertThat(out.get(0).points()).extracting(TimePoint::value).containsExactly(100.0, 50.0);
    }

    @Test
    void sumByEnergyType_groupsBySumPerType() {
        when(rollup.rollupBoundary(Granularity.HOUR)).thenReturn(Instant.MIN);
        when(queryApi.query(anyString(), anyString())).thenReturn(List.of(
            tableOf(record("M1", null, 30.0)),
            tableOf(record("M2", null, 40.0)),
            tableOf(record("W1", null, 5.5))));

        var out = svc.sumByEnergyType(List.of(
            new MeterRef(1L, "M1", "ELEC"),
            new MeterRef(2L, "M2", "ELEC"),
            new MeterRef(3L, "W1", "WATER")
        ), HOUR_RANGE);

        assertThat(out).containsEntry("ELEC", 70.0).containsEntry("WATER", 5.5);
    }

    @Test
    void queryByMeter_emptyMeters_returnsEmpty() {
        assertThat(svc.queryByMeter(List.of(), HOUR_RANGE, Granularity.HOUR)).isEmpty();
        verify(queryApi, never()).query(anyString(), anyString());
    }

    @Test
    void timeRange_validates() {
        assertThatThrownBy(() -> new TimeRange(
                Instant.parse("2026-04-25T01:00:00Z"),
                Instant.parse("2026-04-25T00:00:00Z")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /* ---------- helpers ---------- */

    private static FluxRecord record(String meterCode, Instant ts, double value) {
        FluxRecord r = new FluxRecord(0);
        // FluxRecord 字段大多通过反射设值（库内部填）。这里只填 values map。
        try {
            Field f = FluxRecord.class.getDeclaredField("values");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> values = (java.util.Map<String, Object>) f.get(r);
            values.put("meter_code", meterCode);
            values.put("_value", value);
            if (ts != null) values.put("_time", ts);
        } catch (Exception e) { throw new RuntimeException(e); }
        return r;
    }

    private static FluxTable tableOf(FluxRecord... records) {
        FluxTable t = new FluxTable();
        // FluxTable.records 是 List<FluxRecord>，可直接 add
        try {
            Field f = FluxTable.class.getDeclaredField("records");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<FluxRecord> list = (List<FluxRecord>) f.get(t);
            for (FluxRecord r : records) list.add(r);
            // columns 不为空则 record.getValueByKey 能 work，但我们直接 put 到 values map，已可用
            Field cols = FluxTable.class.getDeclaredField("columns");
            cols.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<FluxColumn> colList = (List<FluxColumn>) cols.get(t);
            // 留空即可，因为 getValueByKey 会从 values map 中查
        } catch (Exception e) { throw new RuntimeException(e); }
        return t;
    }
}
