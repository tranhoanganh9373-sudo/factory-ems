package com.ems.report.dto;

import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.util.List;

/**
 * POST /api/v1/reports/export 入参：
 *  - format: 输出格式 CSV / EXCEL / PDF
 *  - preset: 报表预设 DAILY / MONTHLY / YEARLY / SHIFT
 *  - params: 预设参数容器（不同预设取不同字段）
 */
public record ReportExportRequest(
        ExportFormat format,
        ExportPreset preset,
        Params params
) {

    /**
     * 预设参数：
     *  - DAILY 用 date
     *  - MONTHLY 用 yearMonth
     *  - YEARLY 用 year
     *  - SHIFT 用 date + shiftId
     *  - 通用：orgNodeId / energyTypes
     */
    public record Params(
            LocalDate date,
            YearMonth yearMonth,
            Year year,
            Long shiftId,
            Long orgNodeId,
            List<String> energyTypes
    ) {}
}
