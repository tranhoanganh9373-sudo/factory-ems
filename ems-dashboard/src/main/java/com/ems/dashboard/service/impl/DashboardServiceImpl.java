package com.ems.dashboard.service.impl;

import com.ems.dashboard.dto.*;
import com.ems.dashboard.service.DashboardService;
import com.ems.dashboard.support.DashboardSupport;
import com.ems.dashboard.support.MeterRecord;
import com.ems.dashboard.support.RangeResolver;
import com.ems.meter.entity.EnergyType;
import com.ems.meter.repository.EnergyTypeRepository;
import com.ems.tariff.service.TariffService;
import com.ems.timeseries.model.Granularity;
import com.ems.timeseries.model.MeterPoint;
import com.ems.timeseries.model.TimePoint;
import com.ems.timeseries.model.TimeRange;
import com.ems.timeseries.query.TimeSeriesQueryService;
import com.ems.timeseries.query.TimeSeriesQueryService.MeterRef;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 看板服务实现：5 个面板都共享 resolveMeters → TimeSeriesQueryService 的查询路径。
 * 所有 panel 在同一类内方便共享 helper 与一致的 DTO 组装。
 */
@Service
public class DashboardServiceImpl implements DashboardService {

    private final DashboardSupport support;
    private final TimeSeriesQueryService tsq;
    private final TariffService tariff;
    private final EnergyTypeRepository energyTypes;

    public DashboardServiceImpl(DashboardSupport support, TimeSeriesQueryService tsq,
                                TariffService tariff, EnergyTypeRepository energyTypes) {
        this.support = support;
        this.tsq = tsq;
        this.tariff = tariff;
        this.energyTypes = energyTypes;
    }

    /* ---------------- ① KPI ---------------- */

    @Override
    public List<KpiDTO> kpi(RangeQuery query) {
        TimeRange range = RangeResolver.resolve(query);
        List<MeterRecord> meters = support.resolveMeters(query.orgNodeId(), query.energyType());
        if (meters.isEmpty()) return List.of();

        List<MeterRef> refs = toRefs(meters);
        Map<String, String> unitOf = unitByEnergyType(meters);

        Map<String, Double> cur = tsq.sumByEnergyType(refs, range);

        long len = range.durationSeconds();
        Map<String, Double> prev = tsq.sumByEnergyType(refs, RangeResolver.shiftBack(range, len));

        // 同比：去年同期同长度
        TimeRange yoyRange = new TimeRange(
            range.start().atZone(RangeResolver.ZONE).minusYears(1).toInstant(),
            range.end().atZone(RangeResolver.ZONE).minusYears(1).toInstant()
        );
        Map<String, Double> prevYear = tsq.sumByEnergyType(refs, yoyRange);

        List<KpiDTO> out = new ArrayList<>(cur.size());
        cur.forEach((type, v) -> out.add(new KpiDTO(
            type, unitOf.get(type), v,
            ratio(v, prev.get(type)),
            ratio(v, prevYear.get(type))
        )));
        out.sort(Comparator.comparing(KpiDTO::energyType));
        return out;
    }

    /* ---------------- ② Realtime series ---------------- */

    @Override
    public List<SeriesDTO> realtimeSeries(RangeQuery query) {
        // 强制小时分桶；range 默认 LAST_24H 由 controller 传入
        TimeRange range = RangeResolver.resolve(query);
        List<MeterRecord> meters = support.resolveMeters(query.orgNodeId(), query.energyType());
        if (meters.isEmpty()) return List.of();

        List<MeterRef> refs = toRefs(meters);
        List<MeterPoint> pts = tsq.queryByMeter(refs, range, Granularity.HOUR);

        Map<Long, String> typeByMeter = new HashMap<>();
        Map<String, String> unitByType = unitByEnergyType(meters);
        for (MeterRecord m : meters) typeByMeter.put(m.meterId(), m.energyTypeCode());

        // 按 energyType 聚合每个时间桶
        Map<String, TreeMap<Instant, Double>> grouped = new HashMap<>();
        for (MeterPoint mp : pts) {
            String type = typeByMeter.get(mp.meterId());
            if (type == null) continue;
            TreeMap<Instant, Double> bucket = grouped.computeIfAbsent(type, k -> new TreeMap<>());
            for (TimePoint p : mp.points()) {
                bucket.merge(p.ts(), p.value(), Double::sum);
            }
        }

        List<SeriesDTO> out = new ArrayList<>(grouped.size());
        grouped.forEach((type, byTs) -> {
            List<SeriesDTO.Bucket> buckets = new ArrayList<>(byTs.size());
            byTs.forEach((ts, v) -> buckets.add(new SeriesDTO.Bucket(ts, v)));
            out.add(new SeriesDTO(type, unitByType.get(type), buckets));
        });
        out.sort(Comparator.comparing(SeriesDTO::energyType));
        return out;
    }

    /* ---------------- ③ Composition ---------------- */

    @Override
    public List<CompositionDTO> energyComposition(RangeQuery query) {
        TimeRange range = RangeResolver.resolve(query);
        // composition 不再受 energyType 过滤限制（要看构成）；忽略 query.energyType()
        List<MeterRecord> meters = support.resolveMeters(query.orgNodeId(), null);
        if (meters.isEmpty()) return List.of();

        Map<String, String> unitOf = unitByEnergyType(meters);
        Map<String, Double> totals = tsq.sumByEnergyType(toRefs(meters), range);
        double sum = totals.values().stream().mapToDouble(Double::doubleValue).sum();

        List<CompositionDTO> out = new ArrayList<>(totals.size());
        totals.forEach((type, v) -> {
            Double share = sum > 0 ? v / sum : null;
            out.add(new CompositionDTO(type, unitOf.get(type), v, share));
        });
        out.sort(Comparator.comparing(CompositionDTO::energyType));
        return out;
    }

    /* ---------------- ④ Meter detail ---------------- */

    @Override
    public MeterDetailDTO meterDetail(Long meterId, RangeQuery query) {
        TimeRange range = RangeResolver.resolve(query);
        MeterRecord m = support.resolveOneMeter(meterId);

        MeterRef ref = new MeterRef(m.meterId(), m.influxTagValue(), m.energyTypeCode());
        List<MeterPoint> series = tsq.queryByMeter(List.of(ref), range, Granularity.HOUR);
        Map<Long, Double> totalsMap = tsq.sumByMeter(List.of(ref), range);
        double total = totalsMap.getOrDefault(m.meterId(), 0.0);

        List<MeterDetailDTO.Point> pts = series.isEmpty() ? List.of() :
            series.get(0).points().stream()
                .map(p -> new MeterDetailDTO.Point(p.ts(), p.value()))
                .toList();

        return new MeterDetailDTO(
            m.meterId(), m.code(), m.name(), m.energyTypeCode(), m.unit(),
            m.orgNodeId(), total, pts
        );
    }

    /* ---------------- ⑤ Top-N ---------------- */

    @Override
    public List<TopNItemDTO> topN(RangeQuery query, int topN) {
        if (topN <= 0) topN = 10;
        TimeRange range = RangeResolver.resolve(query);
        List<MeterRecord> meters = support.resolveMeters(query.orgNodeId(), query.energyType());
        if (meters.isEmpty()) return List.of();

        Map<Long, Double> sums = tsq.sumByMeter(toRefs(meters), range);

        List<TopNItemDTO> all = new ArrayList<>(meters.size());
        for (MeterRecord m : meters) {
            double v = sums.getOrDefault(m.meterId(), 0.0);
            all.add(new TopNItemDTO(m.meterId(), m.code(), m.name(),
                m.energyTypeCode(), m.unit(), m.orgNodeId(), v));
        }
        all.sort(Comparator.comparingDouble(TopNItemDTO::total).reversed());
        return all.size() > topN ? all.subList(0, topN) : all;
    }

    /* ---------------- ⑥ Tariff distribution ---------------- */

    private static final String[] PERIOD_ORDER = {"SHARP", "PEAK", "FLAT", "VALLEY"};

    @Override
    public TariffDistributionDTO tariffDistribution(RangeQuery query) {
        TimeRange range = RangeResolver.resolve(query);
        // 强制 ELEC：尖峰平谷只针对电
        List<MeterRecord> meters = support.resolveMeters(query.orgNodeId(), "ELEC");
        if (meters.isEmpty()) {
            return new TariffDistributionDTO(null, 0.0, List.of());
        }

        EnergyType elec = energyTypes.findByCode("ELEC")
                .orElseThrow(() -> new IllegalStateException("ELEC energy type not found"));

        List<MeterPoint> pts = tsq.queryByMeter(toRefs(meters), range, Granularity.HOUR);

        Map<String, Double> byPeriod = new LinkedHashMap<>();
        for (String p : PERIOD_ORDER) byPeriod.put(p, 0.0);

        for (MeterPoint mp : pts) {
            for (TimePoint p : mp.points()) {
                OffsetDateTime at = p.ts().atOffset(ZoneOffset.UTC);
                String periodType;
                try {
                    periodType = tariff.resolvePeriodType(elec.getId(), at);
                } catch (RuntimeException ignored) {
                    periodType = "FLAT";
                }
                byPeriod.merge(periodType, p.value(), Double::sum);
            }
        }

        double total = byPeriod.values().stream().mapToDouble(Double::doubleValue).sum();
        Map<String, String> unitOf = unitByEnergyType(meters);
        String unit = unitOf.get("ELEC");

        List<TariffDistributionDTO.Slice> slices = new ArrayList<>(byPeriod.size());
        for (Map.Entry<String, Double> e : byPeriod.entrySet()) {
            double v = e.getValue();
            Double share = total > 0 ? v / total : null;
            slices.add(new TariffDistributionDTO.Slice(e.getKey(), v, share));
        }
        return new TariffDistributionDTO(unit, total, slices);
    }

    /* ---------------- helpers ---------------- */

    private List<MeterRef> toRefs(List<MeterRecord> meters) {
        List<MeterRef> refs = new ArrayList<>(meters.size());
        for (MeterRecord m : meters) {
            refs.add(new MeterRef(m.meterId(), m.influxTagValue(), m.energyTypeCode()));
        }
        return refs;
    }

    private Map<String, String> unitByEnergyType(List<MeterRecord> meters) {
        Map<String, String> out = new HashMap<>();
        for (MeterRecord m : meters) {
            if (m.energyTypeCode() != null) out.putIfAbsent(m.energyTypeCode(), m.unit());
        }
        return out;
    }

    private static Double ratio(Double cur, Double prev) {
        if (cur == null || prev == null || prev == 0.0) return null;
        return (cur - prev) / prev;
    }
}
