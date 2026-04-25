package com.ems.report.export;

import com.ems.report.matrix.ReportMatrix;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PdfExporterTest {

    private static ReportMatrix sample() {
        return new ReportMatrix(
                "2026 年 4 月日报",
                ReportMatrix.RowDimension.ORG_NODE,
                ReportMatrix.ColumnDimension.TIME_BUCKET,
                "kWh",
                List.of(
                        new ReportMatrix.Column("2026-04-25", "2026-04-25"),
                        new ReportMatrix.Column("2026-04-26", "2026-04-26")
                ),
                List.of(
                        new ReportMatrix.Row("10", "一车间", List.of(10.0, 20.0), 30.0),
                        new ReportMatrix.Row("11", "二车间", List.of(5.0, 0.0), 5.0)
                ),
                List.of(15.0, 20.0),
                35.0
        );
    }

    @Test
    void renderHtml_containsTitleHeadersAndDataRows() {
        String html = PdfExporter.renderHtml(sample());
        assertThat(html).contains("2026 年 4 月日报");
        assertThat(html).contains("单位：kWh");
        assertThat(html).contains("<th>组织节点</th>");
        assertThat(html).contains("<th>2026-04-25</th>");
        assertThat(html).contains("<th>2026-04-26</th>");
        assertThat(html).contains("<th>合计</th>");
        assertThat(html).contains("一车间");
        assertThat(html).contains("二车间");
        // 行合计 30.00 / 5.00 / 35.00 (grand) — 至少一处出现
        assertThat(html).contains("30.00");
        assertThat(html).contains("35.00");
        // 列总计行
        assertThat(html).contains("class=\"totals\"");
    }

    @Test
    void renderHtml_escapesHtmlInUserFields() {
        ReportMatrix m = new ReportMatrix(
                "<script>alert(1)</script>",
                ReportMatrix.RowDimension.METER, ReportMatrix.ColumnDimension.TIME_BUCKET,
                null,
                List.of(new ReportMatrix.Column("k", "<x>")),
                List.of(new ReportMatrix.Row("1", "M&A", List.of(1.0), 1.0)),
                List.of(1.0), 1.0);
        String html = PdfExporter.renderHtml(m);
        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html).contains("M&amp;A");
        assertThat(html).contains("&lt;x&gt;");
    }

    @Test
    void write_producesPdfWithCorrectMagic() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfExporter.write(sample(), out);
        byte[] bytes = out.toByteArray();
        assertThat(bytes.length).isGreaterThan(500);
        // PDF 文件以 %PDF- 开头
        assertThat(new String(bytes, 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    void renderHtml_emptyMatrix_omitsTotalsFooter() {
        ReportMatrix empty = new ReportMatrix("空报表",
                ReportMatrix.RowDimension.METER, ReportMatrix.ColumnDimension.ENERGY_TYPE,
                null, List.of(), List.of(), List.of(), 0.0);
        String html = PdfExporter.renderHtml(empty);
        assertThat(html).contains("空报表");
        assertThat(html).doesNotContain("class=\"totals\"");
    }
}
