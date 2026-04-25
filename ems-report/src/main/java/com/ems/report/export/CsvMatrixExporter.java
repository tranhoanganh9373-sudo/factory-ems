package com.ems.report.export;

import com.ems.report.matrix.ReportMatrix;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * ReportMatrix → CSV 导出（与 Excel/PDF exporter 共用同一份 matrix）。
 * 结构：UTF-8 BOM + 标题行 + 单位行（可空） + 表头 + 数据行 + 列总计行。
 */
public final class CsvMatrixExporter {

    /** UTF-8 BOM (EF BB BF) — 让 Excel 直接识别为 UTF-8。 */
    public static final byte[] UTF8_BOM = new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

    private CsvMatrixExporter() {}

    public static void write(ReportMatrix matrix, OutputStream out) {
        try {
            out.write(UTF8_BOM);
            Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            try (CSVPrinter p = new CSVPrinter(w, CSVFormat.DEFAULT)) {
                // 标题
                p.printRecord(matrix.title() != null ? matrix.title() : "Report");
                if (matrix.unit() != null) {
                    p.printRecord("单位：" + matrix.unit());
                }
                // 表头：行维度名 + 列头... + 合计
                p.print(rowDimensionLabel(matrix.rowDimension()));
                for (ReportMatrix.Column c : matrix.columns()) {
                    p.print(c.label());
                }
                p.print("合计");
                p.println();
                // 数据
                for (ReportMatrix.Row r : matrix.rows()) {
                    p.print(r.label());
                    for (Double v : r.cells()) {
                        p.print(v != null ? v : 0.0);
                    }
                    p.print(r.rowTotal());
                    p.println();
                }
                // 列总计
                if (!matrix.rows().isEmpty()) {
                    p.print("合计");
                    for (Double v : matrix.columnTotals()) {
                        p.print(v != null ? v : 0.0);
                    }
                    p.print(matrix.grandTotal());
                    p.println();
                }
                p.flush();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String rowDimensionLabel(ReportMatrix.RowDimension d) {
        return switch (d) {
            case ORG_NODE -> "组织节点";
            case METER -> "测点";
        };
    }
}
