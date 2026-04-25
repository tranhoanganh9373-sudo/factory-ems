package com.ems.report.service.impl;

import com.ems.core.constant.ErrorCode;
import com.ems.core.exception.BusinessException;
import com.ems.dashboard.support.DashboardSupport;
import com.ems.dashboard.support.MeterRecord;
import com.ems.report.dto.ReportRequest;
import com.ems.report.dto.ReportRow;
import com.ems.report.service.ReportService;
import com.ems.timeseries.model.MeterPoint;
import com.ems.timeseries.model.TimePoint;
import com.ems.timeseries.model.TimeRange;
import com.ems.timeseries.query.TimeSeriesQueryService;
import com.ems.timeseries.query.TimeSeriesQueryService.MeterRef;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class ReportServiceImpl implements ReportService {

    private final DashboardSupport support;
    private final TimeSeriesQueryService tsq;

    public ReportServiceImpl(DashboardSupport support, TimeSeriesQueryService tsq) {
        this.support = support;
        this.tsq = tsq;
    }

    @Override
    public Stream<ReportRow> queryStream(ReportRequest req) {
        validate(req);

        // 权限过滤后的可见测点
        List<MeterRecord> visible = support.resolveMeters(req.orgNodeId(), null);
        if (visible.isEmpty()) return Stream.empty();

        // 显式 meterId 过滤
        if (req.meterIds() != null && !req.meterIds().isEmpty()) {
            Set<Long> ids = new HashSet<>(req.meterIds());
            visible = visible.stream().filter(m -> ids.contains(m.meterId())).toList();
        }

        // energyType 过滤（不区分大小写）
        if (req.energyTypes() != null && !req.energyTypes().isEmpty()) {
            Set<String> codes = req.energyTypes().stream()
                .filter(Objects::nonNull)
                .map(String::toUpperCase)
                .collect(Collectors.toSet());
            visible = visible.stream()
                .filter(m -> m.energyTypeCode() != null && codes.contains(m.energyTypeCode().toUpperCase()))
                .toList();
        }
        if (visible.isEmpty()) return Stream.empty();

        // 时序查询（混合 rollup + Influx）
        List<MeterRef> refs = visible.stream()
            .map(m -> new MeterRef(m.meterId(), m.influxTagValue(), m.energyTypeCode()))
            .toList();
        Map<Long, MeterRecord> byId = visible.stream()
            .collect(Collectors.toMap(MeterRecord::meterId, m -> m));

        List<MeterPoint> points = tsq.queryByMeter(refs, new TimeRange(req.from(), req.to()), req.granularity());

        // 扁平化为行流；按 (ts asc, meterCode asc) 排序便于 CSV 阅读
        return points.stream()
            .flatMap(mp -> {
                MeterRecord rec = byId.get(mp.meterId());
                if (rec == null) return Stream.empty();
                return mp.points().stream().map(tp -> toRow(rec, tp));
            })
            .sorted((a, b) -> {
                int c = a.ts().compareTo(b.ts());
                return c != 0 ? c : a.meterCode().compareTo(b.meterCode());
            });
    }

    private static ReportRow toRow(MeterRecord rec, TimePoint tp) {
        return new ReportRow(
            tp.ts(),
            rec.meterId(),
            rec.code(),
            rec.name(),
            rec.orgNodeId(),
            rec.energyTypeCode(),
            rec.unit(),
            tp.value()
        );
    }

    private static void validate(ReportRequest req) {
        if (req == null) throw new BusinessException(ErrorCode.PARAM_INVALID, "req 不能为空");
        if (req.from() == null || req.to() == null)
            throw new BusinessException(ErrorCode.PARAM_INVALID, "from/to 不能为空");
        if (!req.to().isAfter(req.from()))
            throw new BusinessException(ErrorCode.PARAM_INVALID, "to 必须晚于 from");
        if (req.granularity() == null)
            throw new BusinessException(ErrorCode.PARAM_INVALID, "granularity 不能为空");
    }
}
