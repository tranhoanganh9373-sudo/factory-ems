package com.ems.dashboard.service.impl;

import com.ems.core.exception.ForbiddenException;
import com.ems.dashboard.dto.*;
import com.ems.dashboard.service.DashboardService;
import com.ems.dashboard.support.DashboardSupport;
import com.ems.dashboard.support.MeterRecord;
import com.ems.dashboard.support.RangeResolver;
import com.ems.floorplan.dto.FloorplanPointDTO;
import com.ems.floorplan.dto.FloorplanWithPointsDTO;
import com.ems.floorplan.service.FloorplanService;
import com.ems.meter.entity.EnergyType;
import com.ems.meter.entity.MeterTopology;
import com.ems.meter.repository.EnergyTypeRepository;
import com.ems.meter.repository.MeterTopologyRepository;
import com.ems.production.service.ProductionEntryService;
import com.ems.tariff.service.TariffService;
import com.ems.timeseries.model.Granularity;
import com.ems.timeseries.model.MeterPoint;
import com.ems.timeseries.model.TimePoint;
import com.ems.timeseries.model.TimeRange;
import com.ems.timeseries.query.TimeSeriesQueryService;
import com.ems.timeseries.query.TimeSeriesQueryService.MeterRef;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final ProductionEntryService production;
    private final MeterTopologyRepository topology;
    private final FloorplanService floorplans;

    public DashboardServiceImpl(DashboardSupport support, TimeSeriesQueryService tsq,
                                TariffService tariff, EnergyTypeRepository energyTypes,
                                ProductionEntryService production,
                                MeterTopologyRepository topology,
                                FloorplanService floorplans) {
        this.support = support;
        this.tsq = tsq;
        this.tariff = tariff;
        this.energyTypes = energyTypes;
        this.production = production;
        this.topology = topology;
        this.floorplans = floorplans;
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
        // 短 range（TODAY/LAST_24H/YESTERDAY）用 MINUTE，曲线随时间真正前移；
        // THIS_MONTH 与 CUSTOM 统一 HOUR，避免 1 个月 × 1440 分钟桶把前端打爆。
        TimeRange range = RangeResolver.resolve(query);
        List<MeterRecord> meters = support.resolveMeters(query.orgNodeId(), query.energyType());
        if (meters.isEmpty()) return List.of();

        List<MeterRef> refs = toRefs(meters);
        List<MeterPoint> pts = tsq.queryByMeter(refs, range, pickRealtimeGranularity(query.range()));

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

        MeterRef ref = new MeterRef(m.meterId(), m.influxTagValue(), m.energyTypeCode(), m.valueKind());
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

    /* ---------------- ⑦ Energy intensity ---------------- */

    @Override
    public EnergyIntensityDTO energyIntensity(RangeQuery query) {
        TimeRange range = RangeResolver.resolve(query);
        List<MeterRecord> meters = support.resolveMeters(query.orgNodeId(), "ELEC");

        // 电耗按日聚合
        TreeMap<LocalDate, Double> elecByDay = new TreeMap<>();
        String elecUnit = null;
        if (!meters.isEmpty()) {
            elecUnit = unitByEnergyType(meters).get("ELEC");
            List<MeterPoint> pts = tsq.queryByMeter(toRefs(meters), range, Granularity.DAY);
            for (MeterPoint mp : pts) {
                for (TimePoint p : mp.points()) {
                    LocalDate d = p.ts().atZone(RangeResolver.ZONE).toLocalDate();
                    elecByDay.merge(d, p.value(), Double::sum);
                }
            }
        }

        // 产量按日聚合
        Long orgNodeId = query.orgNodeId();
        LocalDate fromDate = range.start().atZone(RangeResolver.ZONE).toLocalDate();
        LocalDate toDate = range.end().atZone(RangeResolver.ZONE).toLocalDate();
        Map<LocalDate, BigDecimal> prodByDay = (orgNodeId == null)
                ? Map.of()
                : production.dailyTotals(orgNodeId, fromDate, toDate);

        TreeMap<LocalDate, EnergyIntensityDTO.Point> merged = new TreeMap<>();
        for (Map.Entry<LocalDate, Double> e : elecByDay.entrySet()) {
            merged.put(e.getKey(), new EnergyIntensityDTO.Point(e.getKey(), e.getValue(), 0.0, null));
        }
        for (Map.Entry<LocalDate, BigDecimal> e : prodByDay.entrySet()) {
            EnergyIntensityDTO.Point cur = merged.get(e.getKey());
            double elec = cur != null ? cur.electricity() : 0.0;
            double prod = e.getValue().doubleValue();
            Double intensity = prod > 0 ? elec / prod : null;
            merged.put(e.getKey(), new EnergyIntensityDTO.Point(e.getKey(), elec, prod, intensity));
        }
        // recompute intensity for entries that came in via elecByDay only
        for (Map.Entry<LocalDate, EnergyIntensityDTO.Point> e : merged.entrySet()) {
            EnergyIntensityDTO.Point pt = e.getValue();
            if (pt.intensity() == null && pt.production() > 0) {
                e.setValue(new EnergyIntensityDTO.Point(pt.date(), pt.electricity(), pt.production(),
                        pt.electricity() / pt.production()));
            }
        }

        return new EnergyIntensityDTO(elecUnit, "件", new ArrayList<>(merged.values()));
    }

    /* ---------------- ⑧ Sankey ---------------- */

    @Override
    public SankeyDTO sankey(RangeQuery query) {
        TimeRange range = RangeResolver.resolve(query);
        List<MeterRecord> meters = support.resolveMeters(query.orgNodeId(), query.energyType());
        if (meters.isEmpty()) {
            return new SankeyDTO(List.of(), List.of());
        }

        Map<Long, MeterRecord> byId = new HashMap<>();
        for (MeterRecord m : meters) byId.put(m.meterId(), m);

        // 累计值（用于边权重 — 取 source 测点累计）
        Map<Long, Double> sums = tsq.sumByMeter(toRefs(meters), range);

        // 仅取在可见范围内的拓扑边
        List<MeterTopology> edges = topology.findAll().stream()
                .filter(e -> byId.containsKey(e.getParentMeterId()) && byId.containsKey(e.getChildMeterId()))
                .toList();

        // 节点：包含所有出现在边上的测点（避免孤儿）
        Set<Long> involved = new HashSet<>();
        for (MeterTopology e : edges) {
            involved.add(e.getParentMeterId());
            involved.add(e.getChildMeterId());
        }

        List<SankeyDTO.Node> nodes = new ArrayList<>(involved.size());
        for (Long id : involved) {
            MeterRecord m = byId.get(id);
            String label = m.code() + (m.name() != null ? " " + m.name() : "");
            nodes.add(new SankeyDTO.Node(String.valueOf(id), label, m.energyTypeCode(), m.unit()));
        }
        nodes.sort(Comparator.comparing(SankeyDTO.Node::id));

        List<SankeyDTO.Link> links = new ArrayList<>(edges.size());
        for (MeterTopology e : edges) {
            // 边权重 = child 累计（视作流向 child 的流量）；fallback 0.0
            double v = sums.getOrDefault(e.getChildMeterId(), 0.0);
            links.add(new SankeyDTO.Link(
                    String.valueOf(e.getParentMeterId()),
                    String.valueOf(e.getChildMeterId()),
                    v
            ));
        }
        links.sort(Comparator.comparing(SankeyDTO.Link::source).thenComparing(SankeyDTO.Link::target));
        return new SankeyDTO(nodes, links);
    }

    /* ---------------- ⑨ Floorplan live ---------------- */

    @Override
    public FloorplanLiveDTO floorplanLive(Long floorplanId, RangeQuery query) {
        FloorplanWithPointsDTO fp = floorplans.getById(floorplanId);

        // 用范围 + 用户可见性约束加载所有可见测点（不限制 energyType）
        TimeRange range = RangeResolver.resolve(query);
        List<MeterRecord> meters = support.resolveMeters(query.orgNodeId(), null);
        Map<Long, MeterRecord> byId = new HashMap<>();
        for (MeterRecord m : meters) byId.put(m.meterId(), m);

        // 仅保留底图测点中权限可见的部分
        List<FloorplanPointDTO> visiblePts = fp.points().stream()
                .filter(p -> byId.containsKey(p.meterId()))
                .toList();
        // 仅在"未指定 orgNodeId"时把"全图不可见"判为越权——带过滤器时 visiblePts 为空
        // 也可能是用户主动选的组织节点与底图测点没交集（admin 也会命中），不应抛 403。
        // 带过滤器走空响应分支：前端只渲染底图、不显示 markers。
        if (query.orgNodeId() == null && fp.points().size() > 0 && visiblePts.isEmpty()) {
            throw new ForbiddenException("无权访问该平面图上的任意测点");
        }

        Map<Long, Double> sums = visiblePts.isEmpty()
                ? Map.of()
                : tsq.sumByMeter(toRefsForMeters(visiblePts, byId), range);

        double max = sums.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);

        List<FloorplanLiveDTO.Point> out = new ArrayList<>(visiblePts.size());
        for (FloorplanPointDTO p : visiblePts) {
            MeterRecord m = byId.get(p.meterId());
            double v = sums.getOrDefault(p.meterId(), 0.0);
            String level = heatLevel(v, max);
            out.add(new FloorplanLiveDTO.Point(
                    p.id(), p.meterId(), m.code(), m.name(),
                    m.energyTypeCode(), m.unit(),
                    p.xRatio(), p.yRatio(), p.label(),
                    v, level
            ));
        }
        return new FloorplanLiveDTO(fp.floorplan(), out);
    }

    private static Granularity pickRealtimeGranularity(RangeType r) {
        if (r == null) return Granularity.HOUR;
        return switch (r) {
            case TODAY, LAST_24H, YESTERDAY -> Granularity.MINUTE;
            case THIS_MONTH, CUSTOM -> Granularity.HOUR;
        };
    }

    private static String heatLevel(double v, double max) {
        if (max <= 0) return "NONE";
        double r = v / max;
        if (r >= 0.7) return "HIGH";
        if (r >= 0.4) return "MEDIUM";
        if (r > 0) return "LOW";
        return "NONE";
    }

    private List<MeterRef> toRefsForMeters(List<FloorplanPointDTO> pts, Map<Long, MeterRecord> byId) {
        List<MeterRef> refs = new ArrayList<>(pts.size());
        for (FloorplanPointDTO p : pts) {
            MeterRecord m = byId.get(p.meterId());
            if (m != null) {
                refs.add(new MeterRef(m.meterId(), m.influxTagValue(), m.energyTypeCode(), m.valueKind()));
            }
        }
        return refs;
    }

    /* ---------------- helpers ---------------- */

    private List<MeterRef> toRefs(List<MeterRecord> meters) {
        List<MeterRef> refs = new ArrayList<>(meters.size());
        for (MeterRecord m : meters) {
            refs.add(new MeterRef(m.meterId(), m.influxTagValue(), m.energyTypeCode(), m.valueKind()));
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
