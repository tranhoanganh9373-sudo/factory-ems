package com.ems.timeseries.query.impl;

import com.ems.timeseries.config.InfluxProperties;
import com.ems.timeseries.model.Granularity;
import com.ems.timeseries.model.MeterPoint;
import com.ems.timeseries.model.TimePoint;
import com.ems.timeseries.model.TimeRange;
import com.ems.timeseries.query.FluxQueryBuilder;
import com.ems.timeseries.query.RollupReaderPort;
import com.ems.timeseries.query.TimeSeriesQueryService;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class TimeSeriesQueryServiceImpl implements TimeSeriesQueryService {

    private final InfluxDBClient influx;
    private final InfluxProperties props;
    private final RollupReaderPort rollupReader;

    public TimeSeriesQueryServiceImpl(InfluxDBClient influx, InfluxProperties props, RollupReaderPort rollupReader) {
        this.influx = influx;
        this.props = props;
        this.rollupReader = rollupReader;
    }

    @Override
    public List<MeterPoint> queryByMeter(Collection<MeterRef> meters, TimeRange range, Granularity granularity) {
        if (meters == null || meters.isEmpty()) return List.of();

        // 按 valueKind 分组分派。INTERVAL_DELTA 走 rollup+Influx；CUMULATIVE_ENERGY/INSTANT_POWER 仅查 Influx
        // （rollup 是按 sum 算的，对累积量/瞬时量无意义）。null 视为 INTERVAL_DELTA（兼容老 3-arg MeterRef）。
        Map<com.ems.core.constant.ValueKind, List<MeterRef>> byKind = meters.stream()
            .collect(Collectors.groupingBy(r ->
                r.valueKind() == null ? com.ems.core.constant.ValueKind.INTERVAL_DELTA : r.valueKind()));

        List<MeterPoint> all = new ArrayList<>(meters.size());
        for (var entry : byKind.entrySet()) {
            switch (entry.getKey()) {
                case INTERVAL_DELTA -> all.addAll(queryIntervalDeltaBuckets(entry.getValue(), range, granularity));
                case CUMULATIVE_ENERGY -> all.addAll(queryCumulativeBuckets(entry.getValue(), range, granularity));
                case INSTANT_POWER -> all.addAll(queryIntegralBuckets(entry.getValue(), range, granularity));
            }
        }
        return all;
    }

    /** INTERVAL_DELTA 时序桶查询：MINUTE 直查 Influx；HOUR/DAY/MONTH 走 rollup + Influx 边界拼接。 */
    private List<MeterPoint> queryIntervalDeltaBuckets(Collection<MeterRef> meters, TimeRange range, Granularity granularity) {
        if (meters.isEmpty()) return List.of();
        Map<String, MeterRef> byCode = indexByCode(meters);

        if (granularity == Granularity.MINUTE) {
            return assemble(byCode, queryInflux(byCode.keySet(), range, granularity, FluxQueryBuilder.Agg.SUM), Map.of());
        }

        Instant boundary = rollupReader.rollupBoundary(granularity);
        boolean allFromRollup = !boundary.isBefore(range.end());
        boolean allFromInflux = !boundary.isAfter(range.start());

        Map<Long, List<TimePoint>> rollupBuckets = allFromInflux
            ? Map.of()
            : rollupReader.readBuckets(byCode.values().stream().map(MeterRef::meterId).toList(),
                new TimeRange(range.start(), allFromRollup ? range.end() : boundary), granularity);

        Map<String, List<TimePoint>> influxBuckets = allFromRollup
            ? Map.of()
            : queryInflux(byCode.keySet(), new TimeRange(allFromInflux ? range.start() : boundary, range.end()),
                granularity, FluxQueryBuilder.Agg.SUM);

        return assemble(byCode, influxBuckets, rollupBuckets);
    }

    /** CUMULATIVE_ENERGY 时序桶查询：difference(nonNegative) 转增量后按桶 sum。仅 Influx，不参与 rollup。 */
    private List<MeterPoint> queryCumulativeBuckets(Collection<MeterRef> meters, TimeRange range, Granularity granularity) {
        if (meters.isEmpty()) return List.of();
        Map<String, MeterRef> byCode = indexByCode(meters);
        String flux = FluxQueryBuilder.bucketedDeltaForMeter(
            props.getBucket(), props.getMeasurement(), byCode.keySet(), range, granularity);
        return assemble(byCode, runQueryToBuckets(flux), Map.of());
    }

    /** INSTANT_POWER 时序桶查询：每桶对功率梯形积分（unit:1h），输出 kWh/桶（假设输入 kW）。仅 Influx。 */
    private List<MeterPoint> queryIntegralBuckets(Collection<MeterRef> meters, TimeRange range, Granularity granularity) {
        if (meters.isEmpty()) return List.of();
        Map<String, MeterRef> byCode = indexByCode(meters);
        String flux = FluxQueryBuilder.bucketedIntegralForMeter(
            props.getBucket(), props.getMeasurement(), byCode.keySet(), range, granularity);
        return assemble(byCode, runQueryToBuckets(flux), Map.of());
    }

    /** 共用：把 Flux 查询结果折成 meter_code → list<TimePoint>。 */
    private Map<String, List<TimePoint>> runQueryToBuckets(String flux) {
        Map<String, List<TimePoint>> out = new HashMap<>();
        for (FluxTable t : influx.getQueryApi().query(flux, props.getOrg())) {
            for (FluxRecord r : t.getRecords()) {
                String code = (String) r.getValueByKey("meter_code");
                Instant ts = r.getTime();
                Object v = r.getValue();
                if (code == null || ts == null || !(v instanceof Number n)) continue;
                out.computeIfAbsent(code, k -> new ArrayList<>()).add(new TimePoint(ts, n.doubleValue()));
            }
        }
        return out;
    }

    @Override
    public Map<Long, Double> sumByMeter(Collection<MeterRef> meters, TimeRange range) {
        if (meters == null || meters.isEmpty()) return Map.of();

        // 按 valueKind 分组，每组走对应的聚合算子。结果合并按 meterId。
        // null valueKind 视为 INTERVAL_DELTA（兼容 3-arg MeterRef 旧构造）。
        Map<com.ems.core.constant.ValueKind, List<MeterRef>> byKind = meters.stream()
            .collect(Collectors.groupingBy(r ->
                r.valueKind() == null ? com.ems.core.constant.ValueKind.INTERVAL_DELTA : r.valueKind()));

        Map<Long, Double> result = new HashMap<>();
        for (var entry : byKind.entrySet()) {
            switch (entry.getKey()) {
                case INTERVAL_DELTA -> result.putAll(sumIntervalDelta(entry.getValue(), range));
                case CUMULATIVE_ENERGY -> result.putAll(sumCumulative(entry.getValue(), range));
                case INSTANT_POWER -> result.putAll(sumIntegral(entry.getValue(), range));
            }
        }
        return result;
    }

    /** INTERVAL_DELTA 聚合：每 sample 是周期能耗，区间 sum()。原 V2.4.0 之前的唯一路径。 */
    private Map<Long, Double> sumIntervalDelta(Collection<MeterRef> meters, TimeRange range) {
        if (meters.isEmpty()) return Map.of();
        Map<String, MeterRef> byCode = indexByCode(meters);

        Map<Long, Double> result = new HashMap<>();
        Instant boundary = rollupReader.rollupBoundary(Granularity.HOUR);
        boolean allFromInflux = !boundary.isAfter(range.start());
        boolean allFromRollup = !boundary.isBefore(range.end());

        if (!allFromInflux) {
            Map<Long, Double> rollupPart = rollupReader.sumByMeter(
                byCode.values().stream().map(MeterRef::meterId).toList(),
                new TimeRange(range.start(), allFromRollup ? range.end() : boundary),
                Granularity.HOUR);
            rollupPart.forEach((id, v) -> result.merge(id, v, Double::sum));
        }

        if (!allFromRollup) {
            TimeRange influxRange = new TimeRange(allFromInflux ? range.start() : boundary, range.end());
            String flux = FluxQueryBuilder.sumOverRange(props.getBucket(), props.getMeasurement(), byCode.keySet(), influxRange);
            for (FluxTable t : influx.getQueryApi().query(flux, props.getOrg())) {
                for (FluxRecord r : t.getRecords()) {
                    String code = (String) r.getValueByKey("meter_code");
                    Object v = r.getValue();
                    MeterRef ref = byCode.get(code);
                    if (ref != null && v instanceof Number n) {
                        result.merge(ref.meterId(), n.doubleValue(), Double::sum);
                    }
                }
            }
        }
        return result;
    }

    /**
     * INSTANT_POWER 聚合：功率序列对时间梯形积分（kW × h = kWh）。直接走 Influx，
     * 不参与 hourly rollup（rollup 用 sum()，对瞬时量没意义）。
     *
     * <p>精度依赖采样间隔：5s 轮询的工业负载误差通常 0.1%-0.5%；秒级突变负载会丢峰值。
     */
    private Map<Long, Double> sumIntegral(Collection<MeterRef> meters, TimeRange range) {
        if (meters.isEmpty()) return Map.of();
        Map<String, MeterRef> byCode = indexByCode(meters);

        Map<Long, Double> result = new HashMap<>();
        String flux = FluxQueryBuilder.integralOverRange(
            props.getBucket(), props.getMeasurement(), byCode.keySet(), range);
        for (FluxTable t : influx.getQueryApi().query(flux, props.getOrg())) {
            for (FluxRecord r : t.getRecords()) {
                String code = (String) r.getValueByKey("meter_code");
                Object v = r.getValue();
                MeterRef ref = byCode.get(code);
                if (ref != null && v instanceof Number n) {
                    result.merge(ref.meterId(), n.doubleValue(), Double::sum);
                }
            }
        }
        return result;
    }

    /**
     * CUMULATIVE_ENERGY 聚合：每个 meter 取区间 last - first。直接走 Influx，不参与 hourly rollup
     * （rollup 是按 sum() 算的，对累积量没意义）。
     */
    private Map<Long, Double> sumCumulative(Collection<MeterRef> meters, TimeRange range) {
        if (meters.isEmpty()) return Map.of();
        Map<String, MeterRef> byCode = indexByCode(meters);

        Map<Long, Double> result = new HashMap<>();
        String flux = FluxQueryBuilder.cumulativeOverRange(
            props.getBucket(), props.getMeasurement(), byCode.keySet(), range);
        for (FluxTable t : influx.getQueryApi().query(flux, props.getOrg())) {
            for (FluxRecord r : t.getRecords()) {
                String code = (String) r.getValueByKey("meter_code");
                Object v = r.getValue();
                MeterRef ref = byCode.get(code);
                if (ref != null && v instanceof Number n) {
                    result.merge(ref.meterId(), n.doubleValue(), Double::sum);
                }
            }
        }
        return result;
    }

    @Override
    public Map<String, Double> sumByEnergyType(Collection<MeterRef> meters, TimeRange range) {
        Map<Long, Double> perMeter = sumByMeter(meters, range);
        Map<Long, String> typeOf = meters.stream()
            .collect(Collectors.toMap(MeterRef::meterId, MeterRef::energyTypeCode, (a, b) -> a));
        Map<String, Double> result = new HashMap<>();
        perMeter.forEach((meterId, v) -> {
            String type = typeOf.get(meterId);
            if (type != null) result.merge(type, v, Double::sum);
        });
        return result;
    }

    /* ---------- helpers ---------- */

    private Map<String, MeterRef> indexByCode(Collection<MeterRef> meters) {
        return meters.stream().collect(Collectors.toMap(MeterRef::influxTagValue, m -> m, (a, b) -> a));
    }

    /** 调 Influx 返回 meter_code → list<TimePoint>。 */
    private Map<String, List<TimePoint>> queryInflux(Collection<String> meterCodes, TimeRange range,
                                                     Granularity granularity, FluxQueryBuilder.Agg agg) {
        if (meterCodes.isEmpty()) return Map.of();
        String flux = FluxQueryBuilder.aggregateByMeter(props.getBucket(), props.getMeasurement(),
            meterCodes, range, granularity, agg);
        Map<String, List<TimePoint>> out = new HashMap<>();
        for (FluxTable t : influx.getQueryApi().query(flux, props.getOrg())) {
            for (FluxRecord r : t.getRecords()) {
                String code = (String) r.getValueByKey("meter_code");
                Instant ts = r.getTime();
                Object v = r.getValue();
                if (code == null || ts == null || !(v instanceof Number n)) continue;
                out.computeIfAbsent(code, k -> new ArrayList<>()).add(new TimePoint(ts, n.doubleValue()));
            }
        }
        return out;
    }

    private List<MeterPoint> assemble(Map<String, MeterRef> byCode,
                                      Map<String, List<TimePoint>> influxByCode,
                                      Map<Long, List<TimePoint>> rollupByMeter) {
        List<MeterPoint> result = new ArrayList<>(byCode.size());
        for (MeterRef ref : byCode.values()) {
            List<TimePoint> merged = new ArrayList<>();
            List<TimePoint> rollupSeg = rollupByMeter.getOrDefault(ref.meterId(), List.of());
            List<TimePoint> influxSeg = influxByCode.getOrDefault(ref.influxTagValue(), List.of());
            merged.addAll(rollupSeg);
            merged.addAll(influxSeg);
            merged.sort((a, b) -> a.ts().compareTo(b.ts()));
            result.add(new MeterPoint(ref.meterId(), ref.influxTagValue(), ref.energyTypeCode(),
                Collections.unmodifiableList(merged)));
        }
        return result;
    }
}
