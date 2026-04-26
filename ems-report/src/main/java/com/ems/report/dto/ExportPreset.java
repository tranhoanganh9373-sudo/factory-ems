package com.ems.report.dto;

/** 异步导出预设种类。与 ReportPresetService 的预设方法对应。
 *  COST_MONTHLY (Plan 2.2 Phase J)：行 = 组织节点（成本中心），列 = 4 段电价 + 合计（CNY）。
 *  BILL：留到 Plan 2.3 与前端拉齐 schema 后再加（"每张账单一个 sheet" 的 Excel 多 sheet 语义需 frontend 明确分组键）。
 */
public enum ExportPreset {
    DAILY,
    MONTHLY,
    YEARLY,
    SHIFT,
    COST_MONTHLY
}
