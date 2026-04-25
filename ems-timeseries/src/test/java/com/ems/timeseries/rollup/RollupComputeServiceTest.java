package com.ems.timeseries.rollup;

import com.ems.timeseries.config.InfluxProperties;
import com.ems.timeseries.model.Granularity;
import com.ems.timeseries.rollup.RollupComputeService.MeterCtx;
import com.ems.timeseries.rollup.entity.RollupJobFailure;
import com.ems.timeseries.rollup.repository.RollupDailyRepository;
import com.ems.timeseries.rollup.repository.RollupHourlyRepository;
import com.ems.timeseries.rollup.repository.RollupJobFailureRepository;
import com.ems.timeseries.rollup.repository.RollupMonthlyRepository;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxColumn;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RollupComputeServiceTest {

    InfluxDBClient influx;
    QueryApi queryApi;
    InfluxProperties props;
    RollupHourlyRepository hourlyRepo;
    RollupDailyRepository dailyRepo;
    RollupMonthlyRepository monthlyRepo;
    RollupJobFailureRepository failureRepo;
    RollupComputeService svc;

    static final MeterCtx METER = new MeterCtx(1L, 10L, "M1");
    static final Instant HOUR_BUCKET = Instant.parse("2026-04-25T14:00:00Z");

    @BeforeEach
    void setup() {
        influx = mock(InfluxDBClient.class);
        queryApi = mock(QueryApi.class);
        when(influx.getQueryApi()).thenReturn(queryApi);
        props = new InfluxProperties();
        props.setBucket("factory_ems"); props.setOrg("factory"); props.setMeasurement("energy_reading");
        hourlyRepo = mock(RollupHourlyRepository.class);
        dailyRepo = mock(RollupDailyRepository.class);
        monthlyRepo = mock(RollupMonthlyRepository.class);
        failureRepo = mock(RollupJobFailureRepository.class);
        svc = new RollupComputeService(influx, props, hourlyRepo, dailyRepo, monthlyRepo, failureRepo);
    }

    @Test
    void computeBucket_hour_aggregatesAndUpserts() {
        when(queryApi.query(anyString(), anyString())).thenReturn(List.of(
            tableOf(rec(1.0), rec(2.0), rec(3.0))));
        when(failureRepo.findActive(anyString(), any(), anyLong())).thenReturn(Optional.empty());

        boolean ok = svc.computeBucket(METER, Granularity.HOUR, HOUR_BUCKET);

        assertThat(ok).isTrue();
        ArgumentCaptor<BigDecimal> sum = ArgumentCaptor.forClass(BigDecimal.class);
        verify(hourlyRepo).upsert(eq(1L), eq(10L), eq(HOUR_BUCKET.atOffset(java.time.ZoneOffset.UTC)),
            sum.capture(), any(), any(), any(), eq(3));
        assertThat(sum.getValue().doubleValue()).isEqualTo(6.0);
    }

    @Test
    void computeBucket_emptyInflux_skipsUpsert() {
        when(queryApi.query(anyString(), anyString())).thenReturn(List.of());
        when(failureRepo.findActive(anyString(), any(), anyLong())).thenReturn(Optional.empty());

        boolean ok = svc.computeBucket(METER, Granularity.HOUR, HOUR_BUCKET);

        assertThat(ok).isTrue();
        verify(hourlyRepo, never()).upsert(anyLong(), anyLong(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void computeBucket_influxThrows_recordsFailure_attempt1_5min() {
        when(queryApi.query(anyString(), anyString())).thenThrow(new RuntimeException("connect refused"));
        when(failureRepo.findActive(anyString(), any(), anyLong())).thenReturn(Optional.empty());

        boolean ok = svc.computeBucket(METER, Granularity.HOUR, HOUR_BUCKET);

        assertThat(ok).isFalse();
        ArgumentCaptor<RollupJobFailure> cap = ArgumentCaptor.forClass(RollupJobFailure.class);
        verify(failureRepo).save(cap.capture());
        RollupJobFailure f = cap.getValue();
        assertThat(f.getAttempt()).isEqualTo(1);
        assertThat(f.getGranularity()).isEqualTo("HOURLY");
        assertThat(f.getAbandoned()).isFalse();
        // 5 分钟 ± 一点点
        long secondsTillRetry = java.time.Duration.between(OffsetDateTime.now(), f.getNextRetryAt()).getSeconds();
        assertThat(secondsTillRetry).isBetween(290L, 310L);
    }

    @Test
    void computeBucket_influxThrows_attempt3_thenAbandoned() {
        RollupJobFailure existing = new RollupJobFailure();
        existing.setGranularity("HOURLY");
        existing.setBucketTs(HOUR_BUCKET.atOffset(java.time.ZoneOffset.UTC));
        existing.setMeterId(1L);
        existing.setAttempt(3);
        existing.setAbandoned(false);
        when(queryApi.query(anyString(), anyString())).thenThrow(new RuntimeException("still failing"));
        when(failureRepo.findActive(anyString(), any(), anyLong())).thenReturn(Optional.of(existing));

        svc.computeBucket(METER, Granularity.HOUR, HOUR_BUCKET);

        ArgumentCaptor<RollupJobFailure> cap = ArgumentCaptor.forClass(RollupJobFailure.class);
        verify(failureRepo).save(cap.capture());
        assertThat(cap.getValue().getAbandoned()).isTrue();
    }

    @Test
    void computeBucket_success_clearsExistingFailure() {
        RollupJobFailure existing = new RollupJobFailure();
        existing.setId(99L);
        when(queryApi.query(anyString(), anyString())).thenReturn(List.of(tableOf(rec(1.0))));
        when(failureRepo.findActive(anyString(), any(), anyLong())).thenReturn(Optional.of(existing));

        boolean ok = svc.computeBucket(METER, Granularity.HOUR, HOUR_BUCKET);

        assertThat(ok).isTrue();
        verify(failureRepo, times(1)).delete(existing);
    }

    /* ---------- helpers ---------- */

    private static FluxRecord rec(double value) {
        FluxRecord r = new FluxRecord(0);
        try {
            Field f = FluxRecord.class.getDeclaredField("values");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Object> values = (Map<String, Object>) f.get(r);
            values.put("_value", value);
        } catch (Exception e) { throw new RuntimeException(e); }
        return r;
    }

    private static FluxTable tableOf(FluxRecord... records) {
        FluxTable t = new FluxTable();
        try {
            Field f = FluxTable.class.getDeclaredField("records");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<FluxRecord> list = (List<FluxRecord>) f.get(t);
            for (FluxRecord r : records) list.add(r);
            Field cols = FluxTable.class.getDeclaredField("columns");
            cols.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<FluxColumn> colList = (List<FluxColumn>) cols.get(t);
            // unused but required for instance hygiene
            assertThat(colList).isNotNull();
        } catch (Exception e) { throw new RuntimeException(e); }
        return t;
    }
}
