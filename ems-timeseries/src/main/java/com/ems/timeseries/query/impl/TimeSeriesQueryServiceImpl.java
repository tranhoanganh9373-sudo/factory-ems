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
        Map<String, MeterRef> byCode = indexByCode(meters);

        // MINUTE 直查 Influx
        if (granularity == Granularity.MINUTE) {
            return assemble(byCode, queryInflux(byCode.keySet(), range, granularity, FluxQueryBuilder.Agg.SUM), Map.of());
        }

        // HOUR/DAY/MONTH 三路分派
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

    @Override
    public Map<Long, Double> sumByMeter(Collection<MeterRef> meters, TimeRange range) {
        if (meters == null || meters.isEmpty()) return Map.of();
        Map<String, MeterRef> byCode = indexByCode(meters);

        Map<Long, Double> result = new HashMap<>();
        // Phase C：默认全部走 Influx（NoopRollupReader 边界 = Instant.MIN）
        // Phase D 替换 RollupReaderImpl 后会走 rollup 加 Influx tail 的拼接路径
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
