package com.ems.dashboard.service.impl;

import com.ems.core.config.PvFeatureProperties;
import com.ems.core.exception.ForbiddenException;
import com.ems.dashboard.dto.*;
import com.ems.dashboard.service.DashboardService;
import com.ems.dashboard.support.DashboardSupport;
import com.ems.dashboard.support.MeterRecord;
import com.ems.dashboard.support.RangeResolver;
import com.ems.floorplan.dto.FloorplanPointDTO;
import com.ems.floorplan.dto.FloorplanWithPointsDTO;
import com.ems.floorplan.service.FloorplanService;
import com.ems.meter.entity.EnergySource;
import com.ems.meter.entity.EnergyType;
import com.ems.meter.entity.FlowDirection;
import com.ems.meter.entity.MeterRole;
import com.ems.meter.entity.MeterTopology;
import com.ems.meter.repository.EnergyTypeRepository;
import com.ems.meter.repository.MeterTopologyRepository;
import com.ems.production.service.ProductionEntryService;
import com.ems.tariff.service.TariffService;
import com.ems.timeseries.model.Granularity;
import com.ems.timeseries.model.MeterPoint;
import com.ems.timeseries.model.TimePoint;
import com.ems.timeseries.model.TimeRange;
import com.ems.dashboard.service.SolarSelfConsumptionService;
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
    private final PvFeatureProperties pvProps;
    private final SolarSelfConsumptionService selfConsumption;

    public DashboardServiceImpl(DashboardSupport support, TimeSeriesQueryService tsq,
                                TariffService tariff, EnergyTypeRepository energyTypes,
                                ProductionEntryService production,
                                MeterTopologyRepository topology,
                                FloorplanService floorplans,
                                PvFeatureProperties pvProps,
                                SolarSelfConsumptionService selfConsumption) {
        this.support = support;
        this.tsq = tsq;
        this.tariff = tariff;
        this.energyTypes = energyTypes;
        this.production = production;
        this.topology = topology;
        this.floorplans = floorplans;
        this.pvProps = pvProps;
        this.selfConsumption = selfConsumption;
    }

    /* ---------------- ① KPI ---------------- */

    @Override
    public List<KpiDTO> kpi(RangeQuery query) {
        TimeRange range = RangeResolver.resolve(query);
        List<MeterRecord> meters = support.resolveMeters(query.orgNodeId(), query.energyType());
        if (meters.isEmpty()) return List.of();

        if (!pvProps.enabled()) return legacyKpi(meters, range);
        return pvAwareKpi(meters, range);
    }

    private List<KpiDTO> legacyKpi(List<MeterRecord> meters, TimeRange range) {
        // 拓扑根聚合：只把"可见集合的根"参与求和，避免父表 + 子表的双重计算
        List<MeterRecord> roots = support.filterToVisibleRoots(meters);
        List<MeterRef> refs = toRefs(roots);
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

    private List<KpiDTO> pvAwareKpi(List<MeterRecord> meters, TimeRange range) {
        List<MeterRecord> consumeMeters = meters.stream()
            .filter(m -> m.role() == MeterRole.CONSUME).toList();
        List<MeterRecord> gridImport = meters.stream()
            .filter(m -> m.role() == MeterRole.GRID_TIE && m.flowDirection() == FlowDirection.IMPORT).toList();
        List<MeterRecord> gridExport = meters.stream()
            .filter(m -> m.role() == MeterRole.GRID_TIE && m.flowDirection() == FlowDirection.EXPORT).toList();
        List<MeterRecord> generateMeters = meters.stream()
            .filter(m -> m.role() == MeterRole.GENERATE).toList();

        boolean hasPvOrGridTie = !gridImport.isEmpty() || !gridExport.isEmpty() || !generateMeters.isEmpty();
        if (!hasPvOrGridTie) return legacyKpi(consumeMeters, range);

        Map<String, String> unitOf = unitByEnergyType(meters);

        Map<String, Double> cur = computeTotal(gridImport, gridExport, generateMeters, range);
        long len = range.durationSeconds();
        Map<String, Double> prev = computeTotal(gridImport, gridExport, generateMeters,
                                                RangeResolver.shiftBack(range, len));
        TimeRange yoyRange = new TimeRange(
            range.start().atZone(RangeResolver.ZONE).minusYears(1).toInstant(),
            range.end().atZone(RangeResolver.ZONE).minusYears(1).toInstant()
        );
        Map<String, Double> prevYear = computeTotal(gridImport, gridExport, generateMeters, yoyRange);

        List<KpiDTO> out = new ArrayList<>(cur.size());
        cur.forEach((type, v) -> out.add(new KpiDTO(
            type, unitOf.get(type), v,
            ratio(v, prev.get(type)),
            ratio(v, prevYear.get(type))
        )));
        out.sort(Comparator.comparing(KpiDTO::energyType));
        return out;
    }

    private Map<String, Double> computeTotal(List<MeterRecord> gridImport,
                                             List<MeterRecord> gridExport,
                                             List<MeterRecord> generateMeters,
                                             TimeRange range) {
        Map<String, Double> gridIn   = tsq.sumByEnergyType(toRefs(gridImport), range);
        Map<String, Double> gridOut  = tsq.sumByEnergyType(toRefs(gridExport), range);
        Map<String, Double> generate = tsq.sumByEnergyType(toRefs(generateMeters), range);

        // total = Σ(GRID_TIE.import) − Σ(GRID_TIE.export) + Σ(GENERATE.production)
        Set<String> types = new HashSet<>();
        types.addAll(gridIn.keySet());
        types.addAll(gridOut.keySet());
        types.addAll(generate.keySet());
        Map<String, Double> total = new HashMap<>();
        for (String t : types) {
            double v = gridIn.getOrDefault(t, 0.0)
                     - gridOut.getOrDefault(t, 0.0)
                     + generate.getOrDefault(t, 0.0);
            total.put(t, v);
        }
        return total;
    }

    /* ---------------- ② Realtime series ---------------- */

    @Override
    public List<SeriesDTO> realtimeSeries(RangeQuery query) {
        // 短 range（TODAY/LAST_24H/YESTERDAY）用 MINUTE，曲线随时间真正前移；
        // THIS_MONTH 与 CUSTOM 统一 HOUR，避免 1 个月 × 1440 分钟桶把前端打爆。
        TimeRange range = RangeResolver.resolve(query);
        List<MeterRecord> meters = support.resolveMeters(query.orgNodeId(), query.energyType());
        if (meters.isEmpty()) return List.of();

        // 拓扑根聚合：实时曲线只画根表的轨迹，避免重叠
        List<MeterRecord> roots = support.filterToVisibleRoots(meters);
        List<MeterRef> refs = toRefs(roots);
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

        // 拓扑根聚合：能耗构成的"总量"分母也走根表口径
        List<MeterRecord> roots = support.filterToVisibleRoots(meters);
        Map<String, String> unitOf = unitByEnergyType(meters);
        Map<String, Double> totals = tsq.sumByEnergyType(toRefs(roots), range);
        double sum = totals.values().stream().mapToDouble(Double::doubleValue).sum();

        List<CompositionDTO> out = new ArrayList<>(totals.size());
        totals.forEach((type, v) -> {
            Double share = sum > 0 ? v / sum : null;
            out.add(new CompositionDTO(type, unitOf.get(type), v, share));
        });
        out.sort(Comparator.comparing(CompositionDTO::energyType));
        return out;
    }

    /* ---------------- ③b Energy source mix (PV-gated) ---------------- */

    @Override
    public List<EnergySourceMixDTO> energySourceMix(RangeQuery query) {
        if (!pvProps.enabled()) return List.of();

        TimeRange range = RangeResolver.resolve(query);
        List<MeterRecord> meters = support.resolveMeters(query.orgNodeId(), null);
        if (meters.isEmpty()) return List.of();

        Map<EnergySource, List<MeterRecord>> bySource = meters.stream()
            .collect(java.util.stream.Collectors.groupingBy(MeterRecord::energySource));

        Map<EnergySource, Double> totalBySource = new java.util.EnumMap<>(EnergySource.class);
        String unit = meters.get(0).unit();
        for (var entry : bySource.entrySet()) {
            List<MeterRecord> roots = support.filterToVisibleRoots(entry.getValue());
            Map<String, Double> sums = tsq.sumByEnergyType(toRefs(roots), range);
            double v = sums.values().stream().mapToDouble(Double::doubleValue).sum();
            totalBySource.put(entry.getKey(), v);
        }

        double grandTotal = totalBySource.values().stream().mapToDouble(Double::doubleValue).sum();

        List<EnergySourceMixDTO> out = new ArrayList<>(totalBySource.size());
        totalBySource.forEach((src, v) -> {
            Double share = grandTotal > 0 ? v / grandTotal : null;
            out.add(new EnergySourceMixDTO(src, unit, v, share));
        });
        out.sort(Comparator.comparing(d -> d.energySource().name()));
        return out;
    }

    /* ---------------- ③c PV curve (PV-gated) ---------------- */

    @Override
    public PvCurveDTO pvCurve(RangeQuery query) {
        if (!pvProps.enabled()) return new PvCurveDTO("kWh", List.of());
        TimeRange range = RangeResolver.resolve(query);
        return selfConsumption.curve(query.orgNodeId(), range);
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
    public List<TopNItemDTO> topN(RangeQuery query, int topN, String scope) {
        if (topN <= 0) topN = 10;
        TimeRange range = RangeResolver.resolve(query);
        List<MeterRecord> meters = support.resolveMeters(query.orgNodeId(), query.energyType());
        if (meters.isEmpty()) return List.of();

        // 默认仅叶子，避免父表 + 子表混排（4.20 的子表 vs 0.80 的父表无意义比较）
        String s = (scope == null || scope.isBlank()) ? "LEAVES" : scope.trim().toUpperCase();
        List<MeterRecord> ranked = switch (s) {
            case "ALL" -> meters;
            case "ROOTS" -> support.filterToVisibleRoots(meters);
            default -> support.filterToVisibleLeaves(meters);
        };
        if (ranked.isEmpty()) return List.of();

        Map<Long, Double> sums = tsq.sumByMeter(toRefs(ranked), range);

        List<TopNItemDTO> all = new ArrayList<>(ranked.size());
        for (MeterRecord m : ranked) {
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
            // 用户视图优先显示名称；缺失时回落到编码。
            String label = m.name() != null && !m.name().isBlank() ? m.name() : m.code();
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

    /* ---------------- ⑩ Energy breakdown (按测点细分 + 其他/未分摊) ---------------- */

    @Override
    public EnergyBreakdownDTO energyBreakdown(RangeQuery query) {
        TimeRange range = RangeResolver.resolve(query);
        // 单一能源类型才能对比根表与子表（不同类型不可加减）
        String et = query.energyType();
        if (et == null || et.isBlank()) et = "ELEC";

        List<MeterRecord> meters = support.resolveMeters(query.orgNodeId(), et);
        if (meters.isEmpty()) {
            return new EnergyBreakdownDTO(et, null, 0.0, List.of());
        }

        List<MeterRecord> roots = support.filterToVisibleRoots(meters);
        Set<Long> rootIds = roots.stream().map(MeterRecord::meterId).collect(java.util.stream.Collectors.toSet());
        Set<Long> visibleIds = meters.stream().map(MeterRecord::meterId).collect(java.util.stream.Collectors.toSet());
        Map<Long, MeterRecord> byId = new HashMap<>();
        for (MeterRecord m : meters) byId.put(m.meterId(), m);

        // 直接子表 = visible 集合中 parent_meter_id ∈ rootIds 的表
        List<MeterTopology> edges = topology.findAll();
        List<Long> directChildIds = new ArrayList<>();
        for (MeterTopology e : edges) {
            if (rootIds.contains(e.getParentMeterId()) && visibleIds.contains(e.getChildMeterId())) {
                directChildIds.add(e.getChildMeterId());
            }
        }

        // 一次性把 roots + direct children 都查 sumByMeter
        List<MeterRef> refs = new ArrayList<>(roots.size() + directChildIds.size());
        refs.addAll(toRefs(roots));
        for (Long cid : directChildIds) {
            MeterRecord m = byId.get(cid);
            if (m != null) refs.add(new MeterRef(m.meterId(), m.influxTagValue(), m.energyTypeCode(), m.valueKind()));
        }
        Map<Long, Double> sums = tsq.sumByMeter(refs, range);

        double rootTotal = 0.0;
        for (MeterRecord r : roots) rootTotal += sums.getOrDefault(r.meterId(), 0.0);

        double covered = 0.0;
        List<EnergyBreakdownDTO.Item> items = new ArrayList<>();
        for (Long cid : directChildIds) {
            MeterRecord m = byId.get(cid);
            if (m == null) continue;
            double v = sums.getOrDefault(cid, 0.0);
            covered += v;
            Double share = rootTotal > 0 ? v / rootTotal : null;
            items.add(new EnergyBreakdownDTO.Item(
                m.meterId(), m.code(), m.name(), v, share, false
            ));
        }
        items.sort(Comparator.comparingDouble((EnergyBreakdownDTO.Item it) -> it.value() == null ? 0.0 : it.value()).reversed());

        // 残差 = rootTotal - covered；可能为负（数据/配置异常，前端用警示色）
        double residual = rootTotal - covered;
        Double residualShare = rootTotal > 0 ? residual / rootTotal : null;
        items.add(new EnergyBreakdownDTO.Item(
            null, null, "其他/未分摊", residual, residualShare, true
        ));

        String unit = roots.isEmpty() ? null : roots.get(0).unit();
        return new EnergyBreakdownDTO(et, unit, rootTotal, items);
    }

    /* ---------------- ⑪ Topology consistency ---------------- */

    private static final double TOPO_OK_THRESHOLD = 0.05;     // ±5%
    private static final double TOPO_WARN_THRESHOLD = 0.15;   // ±15%

    @Override
    public List<TopologyConsistencyDTO> topologyConsistency(RangeQuery query) {
        TimeRange range = RangeResolver.resolve(query);
        // 不限定能源类型；逐个父子对在同一 energyType 内对比
        List<MeterRecord> meters = support.resolveMeters(query.orgNodeId(), null);
        if (meters.isEmpty()) return List.of();

        Map<Long, MeterRecord> byId = new HashMap<>();
        for (MeterRecord m : meters) byId.put(m.meterId(), m);
        Set<Long> visibleIds = byId.keySet();

        // parent → list of (visible) children
        Map<Long, List<Long>> childrenByParent = new HashMap<>();
        for (MeterTopology e : topology.findAll()) {
            if (visibleIds.contains(e.getParentMeterId()) && visibleIds.contains(e.getChildMeterId())) {
                childrenByParent.computeIfAbsent(e.getParentMeterId(), k -> new ArrayList<>())
                    .add(e.getChildMeterId());
            }
        }
        if (childrenByParent.isEmpty()) return List.of();

        // 一次性查所有相关 meter 的累计
        Set<Long> involved = new HashSet<>(childrenByParent.keySet());
        for (List<Long> ch : childrenByParent.values()) involved.addAll(ch);
        List<MeterRef> refs = new ArrayList<>(involved.size());
        for (Long id : involved) {
            MeterRecord m = byId.get(id);
            if (m != null) refs.add(new MeterRef(m.meterId(), m.influxTagValue(), m.energyTypeCode(), m.valueKind()));
        }
        Map<Long, Double> sums = tsq.sumByMeter(refs, range);

        List<TopologyConsistencyDTO> out = new ArrayList<>();
        for (Map.Entry<Long, List<Long>> entry : childrenByParent.entrySet()) {
            Long parentId = entry.getKey();
            MeterRecord parent = byId.get(parentId);
            if (parent == null) continue;
            double parentReading = sums.getOrDefault(parentId, 0.0);
            double childrenSum = 0.0;
            int childCount = 0;
            for (Long cid : entry.getValue()) {
                MeterRecord c = byId.get(cid);
                if (c == null) continue;
                // 不同 energyType 的子表跳过（混 ELEC + WATER 没意义）
                if (!java.util.Objects.equals(parent.energyTypeCode(), c.energyTypeCode())) continue;
                childrenSum += sums.getOrDefault(cid, 0.0);
                childCount++;
            }
            if (childCount == 0) continue;

            double residual = parentReading - childrenSum;
            Double ratio = parentReading > 0 ? residual / parentReading : null;
            String severity = classifySeverity(ratio);
            if ("OK".equals(severity)) continue;  // 仅暴露异常行

            out.add(new TopologyConsistencyDTO(
                parentId, parent.code(), parent.name(),
                parent.energyTypeCode(), parent.unit(),
                parentReading, childrenSum, childCount,
                residual, ratio, severity
            ));
        }
        // ALARM > WARN_NEGATIVE > WARN > INFO，再按 |ratio| 降序
        out.sort(Comparator.<TopologyConsistencyDTO>comparingInt(t -> severityRank(t.severity()))
            .thenComparing((a, b) -> {
                double ra = a.residualRatio() == null ? 0.0 : Math.abs(a.residualRatio());
                double rb = b.residualRatio() == null ? 0.0 : Math.abs(b.residualRatio());
                return Double.compare(rb, ra);
            }));
        return out;
    }

    private static String classifySeverity(Double ratio) {
        if (ratio == null) return "OK";
        double abs = Math.abs(ratio);
        if (abs <= TOPO_OK_THRESHOLD) return "OK";
        if (ratio > 0) return ratio > TOPO_WARN_THRESHOLD ? "WARN" : "INFO";
        return ratio < -TOPO_WARN_THRESHOLD ? "ALARM" : "WARN_NEGATIVE";
    }

    private static int severityRank(String s) {
        return switch (s) {
            case "ALARM" -> 0;
            case "WARN_NEGATIVE" -> 1;
            case "WARN" -> 2;
            case "INFO" -> 3;
            default -> 9;
        };
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
