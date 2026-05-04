package com.ems.dashboard.service.impl;

import com.ems.dashboard.dto.PvCurveDTO;
import com.ems.dashboard.service.SolarSelfConsumptionService;
import com.ems.dashboard.support.DashboardSupport;
import com.ems.dashboard.support.MeterRecord;
import com.ems.meter.entity.EnergySource;
import com.ems.meter.entity.FlowDirection;
import com.ems.meter.entity.MeterRole;
import com.ems.timeseries.model.Granularity;
import com.ems.timeseries.model.MeterPoint;
import com.ems.timeseries.model.TimePoint;
import com.ems.timeseries.model.TimeRange;
import com.ems.timeseries.query.TimeSeriesQueryService;
import com.ems.timeseries.query.TimeSeriesQueryService.MeterRef;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

@Service
public class SolarSelfConsumptionServiceImpl implements SolarSelfConsumptionService {

    private final DashboardSupport support;
    private final TimeSeriesQueryService tsq;

    public SolarSelfConsumptionServiceImpl(DashboardSupport support, TimeSeriesQueryService tsq) {
        this.support = support;
        this.tsq = tsq;
    }

    @Override
    public SelfConsumptionSummary summarize(Long orgNodeId, TimeRange range) {
        List<MeterRecord> meters = support.resolveMeters(orgNodeId, null);
        List<MeterRef> pvRefs  = toRefs(filter(meters, MeterRole.GENERATE, EnergySource.SOLAR, null));
        List<MeterRef> expRefs = toRefs(filter(meters, MeterRole.GRID_TIE, null, FlowDirection.EXPORT));

        TreeMap<Instant, Double> genBy = aggByTs(tsq.queryByMeter(pvRefs, range, Granularity.HOUR));
        TreeMap<Instant, Double> expBy = aggByTs(tsq.queryByMeter(expRefs, range, Granularity.HOUR));

        BigDecimal gen = BigDecimal.ZERO, exp = BigDecimal.ZERO, self = BigDecimal.ZERO;
        Set<Instant> allTs = new TreeSet<>();
        allTs.addAll(genBy.keySet());
        allTs.addAll(expBy.keySet());
        for (Instant ts : allTs) {
            double g = genBy.getOrDefault(ts, 0.0);
            double e = expBy.getOrDefault(ts, 0.0);
            gen  = gen.add(BigDecimal.valueOf(g));
            exp  = exp.add(BigDecimal.valueOf(e));
            self = self.add(BigDecimal.valueOf(Math.max(0.0, g - e)));
        }
        BigDecimal ratio = gen.signum() == 0 ? null : self.divide(gen, 4, RoundingMode.HALF_UP);
        return new SelfConsumptionSummary(gen, exp, self, ratio);
    }

    @Override
    public PvCurveDTO curve(Long orgNodeId, TimeRange range) {
        List<MeterRecord> meters = support.resolveMeters(orgNodeId, null);
        List<MeterRef> pvRefs      = toRefs(filter(meters, MeterRole.GENERATE, EnergySource.SOLAR, null));
        List<MeterRef> consumeRefs = toRefs(filter(meters, MeterRole.CONSUME, null, null));

        TreeMap<Instant, Double> genBy  = aggByTs(tsq.queryByMeter(pvRefs, range, Granularity.HOUR));
        TreeMap<Instant, Double> loadBy = aggByTs(tsq.queryByMeter(consumeRefs, range, Granularity.HOUR));

        Set<Instant> allTs = new TreeSet<>();
        allTs.addAll(genBy.keySet());
        allTs.addAll(loadBy.keySet());

        List<PvCurveDTO.HourBucket> buckets = new ArrayList<>(allTs.size());
        for (Instant ts : allTs) {
            buckets.add(new PvCurveDTO.HourBucket(ts,
                genBy.getOrDefault(ts, 0.0),
                loadBy.getOrDefault(ts, 0.0)));
        }
        String unit = meters.isEmpty() ? "kWh" : meters.get(0).unit();
        return new PvCurveDTO(unit, buckets);
    }

    private static List<MeterRecord> filter(List<MeterRecord> ms,
                                             MeterRole role, EnergySource src, FlowDirection dir) {
        return ms.stream()
            .filter(m -> role == null || m.role() == role)
            .filter(m -> src  == null || m.energySource() == src)
            .filter(m -> dir  == null || m.flowDirection() == dir)
            .toList();
    }

    // MeterRef actual signature: (Long meterId, String influxTagValue, String energyTypeCode, ValueKind valueKind)
    private static List<MeterRef> toRefs(List<MeterRecord> ms) {
        return ms.stream()
            .map(m -> new MeterRef(m.meterId(), m.influxTagValue(), m.energyTypeCode(), m.valueKind()))
            .toList();
    }

    private static TreeMap<Instant, Double> aggByTs(List<MeterPoint> pts) {
        TreeMap<Instant, Double> out = new TreeMap<>();
        for (MeterPoint mp : pts) {
            for (TimePoint p : mp.points()) out.merge(p.ts(), p.value(), Double::sum);
        }
        return out;
    }
}
