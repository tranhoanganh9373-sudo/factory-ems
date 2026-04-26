package com.ems.report.matrix;

import java.util.List;

/**
 * 二维数据透视：行 = 节点（org / meter），列 = 时间桶或能源品类，单元 = 数值。
 * 三种 exporter（CSV / Excel / PDF）共享同一份数据，避免逻辑分叉。
 *  - title: 报表显示标题
 *  - rowDimension / columnDimension: 数据维度，便于 exporter 决定渲染方式
 *  - columns: 列头（按时间桶 / 能源品类）
 *  - rows: 行（含 cells，长度 = columns.size()）
 *  - columnTotals: 各列纵向求和（与 columns 对齐）
 *  - grandTotal: 全表总和
 */
public record ReportMatrix(
        String title,
        RowDimension rowDimension,
        ColumnDimension columnDimension,
        String unit,
        List<Column> columns,
        List<Row> rows,
        List<Double> columnTotals,
        double grandTotal
) {

    public record Column(String key, String label) {}

    public record Row(String key, String label, List<Double> cells, double rowTotal) {}

    /**
     * 行维度。
     *  - ORG_NODE / METER：子项目 1 既有
     *  - COST_CENTER：子项目 2 新增；行 = 组织节点（成本中心视角），与 ORG_NODE 数据来源不同（来自 bill 表而非 telemetry）
     */
    public enum RowDimension { ORG_NODE, METER, COST_CENTER }

    /**
     * 列维度。
     *  - TIME_BUCKET / ENERGY_TYPE：子项目 1 既有
     *  - TARIFF_BAND：子项目 2 新增；列 = 尖/峰/平/谷/合计（4 段电价拆分）
     */
    public enum ColumnDimension { TIME_BUCKET, ENERGY_TYPE, TARIFF_BAND }
}
