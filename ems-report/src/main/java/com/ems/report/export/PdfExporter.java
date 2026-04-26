package com.ems.report.export;

import com.ems.report.matrix.ReportMatrix;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.text.DecimalFormat;
import java.util.Locale;

/**
 * ReportMatrix → PDF 导出器（基于 OpenHTMLToPDF）。
 * 思路：matrix → 极简 XHTML 表格（含 inline CSS）→ openhtmltopdf 渲染。
 *  - 不依赖编译模板，所有样式在代码内写死，便于维护
 *  - 标题、单位、表头、数据行、合计行结构与 ExcelExporter 一致
 *  - 大表自适应分页（CSS page-break）
 */
public final class PdfExporter {

    private static final DecimalFormat NUM_FMT = new DecimalFormat("#,##0.00", new java.text.DecimalFormatSymbols(Locale.ROOT));

    private PdfExporter() {}

    /** 写入 out；out 由调用方关闭。 */
    public static void write(ReportMatrix matrix, OutputStream out) {
        try {
            String html = renderHtml(matrix);
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Visible for testing — 单测断言 HTML 结构。 */
    static String renderHtml(ReportMatrix matrix) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/>");
        sb.append("<style>")
                .append("@page{size:A4 landscape;margin:1cm;}")
                .append("body{font-family:'Helvetica',sans-serif;font-size:10px;}")
                .append("h1{text-align:center;font-size:14px;margin:4px 0;}")
                .append(".unit{text-align:center;font-size:10px;color:#555;margin-bottom:8px;}")
                .append("table{width:100%;border-collapse:collapse;}")
                .append("th,td{border:1px solid #888;padding:4px 6px;}")
                .append("th{background:#d9d9d9;text-align:center;}")
                .append("td.num{text-align:right;}")
                .append("tr.totals{background:#bfbfbf;font-weight:bold;}")
                .append("</style></head><body>");
        sb.append("<h1>").append(escape(matrix.title() != null ? matrix.title() : "Report")).append("</h1>");
        if (matrix.unit() != null) {
            sb.append("<div class=\"unit\">单位：").append(escape(matrix.unit())).append("</div>");
        }
        sb.append("<table>");

        // 表头
        sb.append("<thead><tr>");
        sb.append("<th>").append(rowDimensionLabel(matrix.rowDimension())).append("</th>");
        for (ReportMatrix.Column c : matrix.columns()) {
            sb.append("<th>").append(escape(c.label())).append("</th>");
        }
        sb.append("<th>合计</th></tr></thead>");

        // 数据
        sb.append("<tbody>");
        for (ReportMatrix.Row r : matrix.rows()) {
            sb.append("<tr><td>").append(escape(r.label())).append("</td>");
            for (Double v : r.cells()) {
                sb.append("<td class=\"num\">").append(NUM_FMT.format(v != null ? v : 0.0)).append("</td>");
            }
            sb.append("<td class=\"num\">").append(NUM_FMT.format(r.rowTotal())).append("</td></tr>");
        }
        sb.append("</tbody>");

        // 列总计
        if (!matrix.rows().isEmpty()) {
            sb.append("<tfoot><tr class=\"totals\"><td>合计</td>");
            for (Double v : matrix.columnTotals()) {
                sb.append("<td class=\"num\">").append(NUM_FMT.format(v != null ? v : 0.0)).append("</td>");
            }
            sb.append("<td class=\"num\">").append(NUM_FMT.format(matrix.grandTotal())).append("</td></tr></tfoot>");
        }

        sb.append("</table></body></html>");
        return sb.toString();
    }

    private static String rowDimensionLabel(ReportMatrix.RowDimension d) {
        return switch (d) {
            case ORG_NODE -> "组织节点";
            case METER -> "测点";
            case COST_CENTER -> "成本中心";
        };
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
