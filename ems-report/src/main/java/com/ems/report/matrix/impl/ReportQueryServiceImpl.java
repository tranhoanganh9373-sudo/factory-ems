package com.ems.report.matrix.impl;

import com.ems.core.constant.ErrorCode;
import com.ems.core.exception.BusinessException;
import com.ems.dashboard.support.DashboardSupport;
import com.ems.dashboard.support.MeterRecord;
import com.ems.orgtree.dto.OrgNodeDTO;
import com.ems.orgtree.service.OrgNodeService;
import com.ems.report.matrix.ReportMatrix;
import com.ems.report.matrix.ReportMatrixRequest;
import com.ems.report.matrix.ReportQueryService;
import com.ems.timeseries.model.Granularity;
import com.ems.timeseries.model.MeterPoint;
import com.ems.timeseries.model.TimePoint;
import com.ems.timeseries.model.TimeRange;
import com.ems.timeseries.query.TimeSeriesQueryService;
import com.ems.timeseries.query.TimeSeriesQueryService.MeterRef;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 二维透视实现：
 *  - 权限过滤 → 测点候选
 *  - tsq.queryByMeter 拉时序点
 *  - 按 (rowKey, columnKey) 聚合求和
 *  - 行键由 rowDimension 决定（org_node 或 meter_id）
 *  - 列键由 columnDimension 决定（时间桶 ISO 字符串 或 能源品类 code）
 *  - 列保持时间顺序 / 能源品类按字典序
 *  - 行按 rowLabel 字典序
 */
@Service
public class ReportQueryServiceImpl implements ReportQueryService {

    /** 时间桶按本地时区显示，避免跨时区歧义。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final DashboardSupport support;
    private final TimeSeriesQueryService tsq;
    private final OrgNodeService orgNodes;

    public ReportQueryServiceImpl(DashboardSupport support, TimeSeriesQueryService tsq, OrgNodeService orgNodes) {
        this.support = support;
        this.tsq = tsq;
        this.orgNodes = orgNodes;
    }

    @Override
    public ReportMatrix query(ReportMatrixRequest req) {
        validate(req);

        List<MeterRecord> visible = support.resolveMeters(req.orgNodeId(), null);
        if (visible.isEmpty()) {
            return emptyMatrix(req);
        }
        if (req.meterIds() != null && !req.meterIds().isEmpty()) {
            Set<Long> ids = new HashSet<>(req.meterIds());
            visible = visible.stream().filter(m -> ids.contains(m.meterId())).toList();
        }
        if (req.energyTypes() != null && !req.energyTypes().isEmpty()) {
            Set<String> codes = req.energyTypes().stream()
                    .filter(Objects::nonNull).map(String::toUpperCase).collect(Collectors.toSet());
            visible = visible.stream()
                    .filter(m -> m.energyTypeCode() != null && codes.contains(m.energyTypeCode().toUpperCase()))
                    .toList();
        }
        if (visible.isEmpty()) {
            return emptyMatrix(req);
        }

        Map<Long, MeterRecord> byMeter = visible.stream()
                .collect(Collectors.toMap(MeterRecord::meterId, m -> m));

        // 加载组织节点（仅在 ORG_NODE 行维度时需要 label）
        Map<Long, String> orgNameById = (req.rowDimension() == ReportMatrix.RowDimension.ORG_NODE)
                ? loadOrgNames(visible) : Map.of();

        // 拉时序点
        List<MeterRef> refs = visible.stream()
                .map(m -> new MeterRef(m.meterId(), m.influxTagValue(), m.energyTypeCode())).toList();
        List<MeterPoint> points = tsq.queryByMeter(refs, new TimeRange(req.from(), req.to()), req.granularity());

        // 聚合 (rowKey, colKey) → sum
        Map<String, String> rowLabels = new HashMap<>();
        Map<String, String> colLabels = new LinkedHashMap<>();
        Map<String, Map<String, Double>> grid = new HashMap<>();
        // 列顺序池（保持插入顺序）

        for (MeterPoint mp : points) {
            MeterRecord rec = byMeter.get(mp.meterId());
            if (rec == null) continue;
            String rowKey;
            String rowLabel;
            if (req.rowDimension() == ReportMatrix.RowDimension.ORG_NODE) {
                rowKey = String.valueOf(rec.orgNodeId());
                rowLabel = orgNameById.getOrDefault(rec.orgNodeId(), "Node " + rec.orgNodeId());
            } else {
                rowKey = String.valueOf(rec.meterId());
                rowLabel = rec.code() + (rec.name() != null ? " " + rec.name() : "");
            }
            rowLabels.putIfAbsent(rowKey, rowLabel);

            for (TimePoint tp : mp.points()) {
                String colKey;
                String colLabel;
                if (req.columnDimension() == ReportMatrix.ColumnDimension.TIME_BUCKET) {
                    colKey = bucketKey(tp.ts(), req.granularity());
                    colLabel = colKey;
                } else {
                    colKey = rec.energyTypeCode() != null ? rec.energyTypeCode() : "UNKNOWN";
                    colLabel = colKey;
                }
                colLabels.putIfAbsent(colKey, colLabel);
                grid.computeIfAbsent(rowKey, k -> new HashMap<>())
                        .merge(colKey, tp.value(), Double::sum);
            }
        }

        // 列排序：TIME_BUCKET 按 key 自然顺序（ISO 时间字符串可比较）；ENERGY_TYPE 按字典序
        List<String> orderedColKeys = new ArrayList<>(colLabels.keySet());
        Collections.sort(orderedColKeys);

        List<ReportMatrix.Column> columns = orderedColKeys.stream()
                .map(k -> new ReportMatrix.Column(k, colLabels.get(k))).toList();

        // 行排序：按 rowLabel 字典序
        List<String> orderedRowKeys = rowLabels.keySet().stream()
                .sorted((a, b) -> rowLabels.get(a).compareTo(rowLabels.get(b)))
                .toList();

        // 单元 + 行/列总计
        double[] colTotals = new double[orderedColKeys.size()];
        double grand = 0.0;
        List<ReportMatrix.Row> rows = new ArrayList<>(orderedRowKeys.size());
        for (String rk : orderedRowKeys) {
            Map<String, Double> rowMap = grid.getOrDefault(rk, Map.of());
            List<Double> cells = new ArrayList<>(orderedColKeys.size());
            double rowSum = 0.0;
            for (int i = 0; i < orderedColKeys.size(); i++) {
                double v = rowMap.getOrDefault(orderedColKeys.get(i), 0.0);
                cells.add(v);
                colTotals[i] += v;
                rowSum += v;
            }
            grand += rowSum;
            rows.add(new ReportMatrix.Row(rk, rowLabels.get(rk), cells, rowSum));
        }

        List<Double> colTotalsList = new ArrayList<>(orderedColKeys.size());
        for (double v : colTotals) colTotalsList.add(v);

        String unit = pickUnit(visible);
        String title = req.title() != null ? req.title() : defaultTitle(req);

        return new ReportMatrix(title, req.rowDimension(), req.columnDimension(),
                unit, columns, rows, colTotalsList, grand);
    }

    private Map<Long, String> loadOrgNames(List<MeterRecord> meters) {
        Set<Long> ids = meters.stream().map(MeterRecord::orgNodeId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> out = new HashMap<>();
        for (Long id : ids) {
            try {
                OrgNodeDTO n = orgNodes.getById(id);
                out.put(id, n.name());
            } catch (RuntimeException ignored) {
                // 节点已删除 / 不可见 — 用 fallback
            }
        }
        return out;
    }

    /** 当所有可见测点单位一致时返回该单位；否则返回 null（前端列名自带单位）。 */
    private static String pickUnit(List<MeterRecord> meters) {
        String unit = null;
        for (MeterRecord m : meters) {
            if (m.unit() == null) continue;
            if (unit == null) unit = m.unit();
            else if (!unit.equals(m.unit())) return null;
        }
        return unit;
    }

    private static String bucketKey(Instant ts, Granularity g) {
        var local = ts.atZone(ZONE);
        return switch (g) {
            case HOUR -> local.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00"));
            case DAY -> local.toLocalDate().toString();
            case MONTH -> YearMonth.from(local.toLocalDate()).toString();
            default -> local.toString();
        };
    }

    private static String defaultTitle(ReportMatrixRequest req) {
        return "报表 " + req.from() + " ~ " + req.to() + " (" + req.granularity() + ")";
    }

    private static ReportMatrix emptyMatrix(ReportMatrixRequest req) {
        return new ReportMatrix(
                req.title() != null ? req.title() : defaultTitle(req),
                req.rowDimension(), req.columnDimension(),
                null, List.of(), List.of(), List.of(), 0.0
        );
    }

    private static void validate(ReportMatrixRequest req) {
        if (req == null) throw new BusinessException(ErrorCode.PARAM_INVALID, "req 不能为空");
        if (req.from() == null || req.to() == null)
            throw new BusinessException(ErrorCode.PARAM_INVALID, "from/to 不能为空");
        if (!req.to().isAfter(req.from()))
            throw new BusinessException(ErrorCode.PARAM_INVALID, "to 必须晚于 from");
        if (req.granularity() == null)
            throw new BusinessException(ErrorCode.PARAM_INVALID, "granularity 不能为空");
        if (req.rowDimension() == null)
            throw new BusinessException(ErrorCode.PARAM_INVALID, "rowDimension 不能为空");
        if (req.columnDimension() == null)
            throw new BusinessException(ErrorCode.PARAM_INVALID, "columnDimension 不能为空");
        // 防止误用：当 columnDimension = TIME_BUCKET 时，granularity != null 已经覆盖
    }
}
