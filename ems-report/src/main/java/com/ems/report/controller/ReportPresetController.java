package com.ems.report.controller;

import com.ems.report.matrix.ReportMatrix;
import com.ems.report.preset.ReportPresetService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase L wiring — exposes ReportPresetService as REST.
 *
 * Routes:
 *   GET /api/v1/report/preset/daily?date=YYYY-MM-DD&orgNodeId=&energyType=ELEC&energyType=WATER
 *   GET /api/v1/report/preset/monthly?month=YYYY-MM&...
 *   GET /api/v1/report/preset/yearly?year=2026&...
 *   GET /api/v1/report/preset/shift?date=YYYY-MM-DD&shiftId=...&...
 *
 * Response shape adapts the internal {@link ReportMatrix} to the front-end's
 * flat schema (rowLabels / colLabels / values + axis names).
 */
@RestController
@RequestMapping("/api/v1/report/preset")
@PreAuthorize("isAuthenticated()")
public class ReportPresetController {

    private final ReportPresetService presets;

    public ReportPresetController(ReportPresetService presets) {
        this.presets = presets;
    }

    public record MatrixView(
            String title,
            String rowAxis,
            String colAxis,
            String unit,
            List<String> rowLabels,
            List<String> colLabels,
            List<List<Double>> values,
            List<Double> columnTotals,
            List<Double> rowTotals,
            double grandTotal) {

        public static MatrixView of(ReportMatrix m) {
            List<String> rowLabels = new ArrayList<>(m.rows().size());
            List<List<Double>> values = new ArrayList<>(m.rows().size());
            List<Double> rowTotals = new ArrayList<>(m.rows().size());
            for (ReportMatrix.Row r : m.rows()) {
                rowLabels.add(r.label());
                values.add(r.cells());
                rowTotals.add(r.rowTotal());
            }
            List<String> colLabels = m.columns().stream().map(ReportMatrix.Column::label).toList();
            return new MatrixView(
                    m.title(),
                    axisLabel(m.rowDimension()),
                    axisLabel(m.columnDimension()),
                    m.unit(),
                    rowLabels,
                    colLabels,
                    values,
                    m.columnTotals(),
                    rowTotals,
                    m.grandTotal()
            );
        }

        private static String axisLabel(ReportMatrix.RowDimension d) {
            return switch (d) {
                case ORG_NODE -> "组织节点";
                case METER -> "测点";
                case COST_CENTER -> "成本中心";
            };
        }

        private static String axisLabel(ReportMatrix.ColumnDimension d) {
            return switch (d) {
                case TIME_BUCKET -> "时段";
                case ENERGY_TYPE -> "能源品类";
                case TARIFF_BAND -> "电价时段";
            };
        }
    }

    @GetMapping("/daily")
    public MatrixView daily(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long orgNodeId,
            @RequestParam(name = "energyType", required = false) List<String> energyType) {
        return MatrixView.of(presets.daily(date, orgNodeId, energyType));
    }

    @GetMapping("/monthly")
    public MatrixView monthly(
            @RequestParam String month,
            @RequestParam(required = false) Long orgNodeId,
            @RequestParam(name = "energyType", required = false) List<String> energyType) {
        return MatrixView.of(presets.monthly(YearMonth.parse(month), orgNodeId, energyType));
    }

    @GetMapping("/yearly")
    public MatrixView yearly(
            @RequestParam int year,
            @RequestParam(required = false) Long orgNodeId,
            @RequestParam(name = "energyType", required = false) List<String> energyType) {
        return MatrixView.of(presets.yearly(Year.of(year), orgNodeId, energyType));
    }

    @GetMapping("/shift")
    public MatrixView shift(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam Long shiftId,
            @RequestParam(required = false) Long orgNodeId,
            @RequestParam(name = "energyType", required = false) List<String> energyType) {
        return MatrixView.of(presets.shift(date, shiftId, orgNodeId, energyType));
    }

    /**
     * Plan 2.2 Phase I — 成本月报。
     * GET /api/v1/report/preset/cost-monthly?ym=YYYY-MM&orgNodeId=
     * 行 = 组织节点（成本中心），列 = 尖/峰/平/谷/合计 5 段（CNY）。
     */
    @GetMapping("/cost-monthly")
    public MatrixView costMonthly(
            @RequestParam("ym") String ym,
            @RequestParam(required = false) Long orgNodeId) {
        return MatrixView.of(presets.costMonthly(YearMonth.parse(ym), orgNodeId));
    }
}
