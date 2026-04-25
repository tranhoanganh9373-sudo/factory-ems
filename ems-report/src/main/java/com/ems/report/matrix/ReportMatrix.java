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

    public enum RowDimension { ORG_NODE, METER }
    public enum ColumnDimension { TIME_BUCKET, ENERGY_TYPE }
}
