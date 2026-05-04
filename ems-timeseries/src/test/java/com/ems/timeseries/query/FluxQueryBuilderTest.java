package com.ems.timeseries.query;

import com.ems.timeseries.model.Granularity;
import com.ems.timeseries.model.TimeRange;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FluxQueryBuilderTest {

    private static final TimeRange RANGE = new TimeRange(
        Instant.parse("2026-04-25T00:00:00Z"),
        Instant.parse("2026-04-25T01:00:00Z"));

    @Test
    void aggregateByMeter_buildsExpectedFlux() {
        String q = FluxQueryBuilder.aggregateByMeter(
            "factory_ems", "energy_reading",
            List.of("M1", "M-2_x"), RANGE, Granularity.HOUR, FluxQueryBuilder.Agg.SUM);

        assertThat(q)
            .contains("from(bucket: \"factory_ems\")")
            .contains("range(start: 2026-04-25T00:00:00Z, stop: 2026-04-25T01:00:00Z)")
            .contains("r._measurement == \"energy_reading\"")
            .contains("contains(value: r.meter_code, set: [\"M1\", \"M-2_x\"])")
            .contains("aggregateWindow(every: 1h, fn: sum");
    }

    @Test
    void granularity_mapsToFluxWindow() {
        assertThat(Granularity.MINUTE.fluxWindow()).isEqualTo("1m");
        assertThat(Granularity.HOUR.fluxWindow()).isEqualTo("1h");
        assertThat(Granularity.DAY.fluxWindow()).isEqualTo("1d");
        assertThat(Granularity.MONTH.fluxWindow()).isEqualTo("1mo");
    }

    @Test
    void aggregateByMeter_rejectsBadTagChars() {
        assertThatThrownBy(() -> FluxQueryBuilder.aggregateByMeter(
                "b", "m", List.of("ok", "bad\";drop"), RANGE, Granularity.HOUR, FluxQueryBuilder.Agg.SUM))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("非法 meter_code");
    }

    @Test
    void aggregateByMeter_rejectsEmptyMeters() {
        assertThatThrownBy(() -> FluxQueryBuilder.aggregateByMeter(
                "b", "m", List.of(), RANGE, Granularity.HOUR, FluxQueryBuilder.Agg.SUM))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aggregateByMeter_rejectsBlankBucket() {
        assertThatThrownBy(() -> FluxQueryBuilder.aggregateByMeter(
                "", "m", List.of("M1"), RANGE, Granularity.HOUR, FluxQueryBuilder.Agg.SUM))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bucket");
    }

    @Test
    void sumOverRange_buildsExpectedFlux() {
        String q = FluxQueryBuilder.sumOverRange("factory_ems", "energy_reading",
            List.of("M1"), RANGE);
        assertThat(q)
            .contains("group(columns: [\"meter_code\", \"energy_type\"])")
            .contains("|> sum()")
            .contains("range(start: 2026-04-25T00:00:00Z");
    }

    @Test
    void timeRange_rejectsInvalidOrder() {
        assertThatThrownBy(() -> new TimeRange(
                Instant.parse("2026-04-25T01:00:00Z"),
                Instant.parse("2026-04-25T00:00:00Z")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cumulativeOverRange_emitsFirstLastUnionPivot() {
        String q = FluxQueryBuilder.cumulativeOverRange("b", "m", List.of("M1"), RANGE);
        assertThat(q)
            .contains("data = from(bucket: \"b\")")
            .contains("first_v = data |> first()")
            .contains("last_v  = data |> last()")
            .contains("union(tables: [first_v, last_v])")
            .contains("pivot(rowKey: [\"meter_code\", \"energy_type\"]")
            .contains("_value: r.last - r.first");
    }

    @Test
    void integralOverRange_buildsTrapezoidalIntegralWith1hUnit() {
        String q = FluxQueryBuilder.integralOverRange("b", "m", List.of("M1"), RANGE);
        assertThat(q)
            .contains("group(columns: [\"meter_code\", \"energy_type\"])")
            .contains("|> integral(unit: 1h)")
            .contains("keep(columns: [\"meter_code\", \"energy_type\", \"_value\"])");
    }

    @Test
    void bucketedDeltaForMeter_emitsDifferenceAndAggregateSum() {
        String q = FluxQueryBuilder.bucketedDeltaForMeter("b", "m", List.of("M1"), RANGE, Granularity.HOUR);
        assertThat(q)
            .contains("|> difference(nonNegative: true)")
            .contains("aggregateWindow(every: 1h, fn: sum")
            .contains("keep(columns: [\"_time\", \"_value\", \"meter_code\", \"energy_type\"])");
    }

    @Test
    void bucketedIntegralForMeter_emitsAggregateWindowWithIntegralFn() {
        String q = FluxQueryBuilder.bucketedIntegralForMeter("b", "m", List.of("M1"), RANGE, Granularity.HOUR);
        assertThat(q)
            .contains("aggregateWindow(every: 1h, fn: (column, tables=<-) => tables |> integral(unit: 1h, column: column)")
            .contains("keep(columns: [\"_time\", \"_value\", \"meter_code\", \"energy_type\"])");
    }

    @Test
    void integralOverRange_rejectsBadTagChars() {
        assertThatThrownBy(() -> FluxQueryBuilder.integralOverRange(
                "b", "m", List.of("bad\";drop"), RANGE))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aggregateFunctions_haveCorrectFlux() {
        String q = FluxQueryBuilder.aggregateByMeter("b", "m", List.of("M1"),
            RANGE, Granularity.MINUTE, FluxQueryBuilder.Agg.MEAN);
        assertThat(q).contains("fn: mean");
    }
}
