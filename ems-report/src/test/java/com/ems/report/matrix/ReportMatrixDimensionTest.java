package com.ems.report.matrix;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 2.2 Phase G — 验证 ReportMatrix 新增维度向下兼容。
 * 既有 (ORG_NODE/METER, TIME_BUCKET/ENERGY_TYPE) 不动；新增 COST_CENTER + TARIFF_BAND。
 */
class ReportMatrixDimensionTest {

    @Test
    void existing_row_dimensions_preserved() {
        assertThat(ReportMatrix.RowDimension.values())
                .contains(ReportMatrix.RowDimension.ORG_NODE,
                          ReportMatrix.RowDimension.METER);
    }

    @Test
    void existing_column_dimensions_preserved() {
        assertThat(ReportMatrix.ColumnDimension.values())
                .contains(ReportMatrix.ColumnDimension.TIME_BUCKET,
                          ReportMatrix.ColumnDimension.ENERGY_TYPE);
    }

    @Test
    void new_COST_CENTER_row_dimension_available() {
        assertThat(ReportMatrix.RowDimension.valueOf("COST_CENTER"))
                .isEqualTo(ReportMatrix.RowDimension.COST_CENTER);
    }

    @Test
    void new_TARIFF_BAND_column_dimension_available() {
        assertThat(ReportMatrix.ColumnDimension.valueOf("TARIFF_BAND"))
                .isEqualTo(ReportMatrix.ColumnDimension.TARIFF_BAND);
    }

    @Test
    void cost_matrix_can_be_constructed_with_new_dimensions() {
        ReportMatrix m = new ReportMatrix(
                "成本月报-2026-03",
                ReportMatrix.RowDimension.COST_CENTER,
                ReportMatrix.ColumnDimension.TARIFF_BAND,
                "CNY",
                List.of(
                        new ReportMatrix.Column("SHARP", "尖"),
                        new ReportMatrix.Column("PEAK", "峰"),
                        new ReportMatrix.Column("FLAT", "平"),
                        new ReportMatrix.Column("VALLEY", "谷"),
                        new ReportMatrix.Column("TOTAL", "合计")
                ),
                List.of(new ReportMatrix.Row(
                        "10", "一车间",
                        List.of(100.0, 200.0, 300.0, 80.0, 680.0),
                        680.0)),
                List.of(100.0, 200.0, 300.0, 80.0, 680.0),
                680.0
        );
        assertThat(m.rowDimension()).isEqualTo(ReportMatrix.RowDimension.COST_CENTER);
        assertThat(m.columnDimension()).isEqualTo(ReportMatrix.ColumnDimension.TARIFF_BAND);
        assertThat(m.columns()).hasSize(5);
        assertThat(m.rows()).hasSize(1);
        assertThat(m.grandTotal()).isEqualTo(680.0);
    }
}
