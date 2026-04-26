package com.ems.report.export;

import com.ems.report.matrix.ReportMatrix;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;

/**
 * ReportMatrix → Excel 导出器（POI SXSSF 流式写）。
 *  - 标题合并单元格（第 1 行）
 *  - 表头（行维度名 + 列头）
 *  - 数据行（含每行总计列）
 *  - 列总计行（最后一行）
 *  - 单位放在标题第 2 行
 *  - 默认每 100 行 flush 到磁盘，避免大报表 OOM
 */
public final class ExcelExporter {

    /** SXSSF 内存窗口大小：保留多少行在内存，其它落盘。 */
    public static final int WINDOW_ROWS = 100;

    private ExcelExporter() {}

    /** 写入 out；out 由调用方关闭。 */
    public static void write(ReportMatrix matrix, OutputStream out) {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(WINDOW_ROWS)) {
            wb.setCompressTempFiles(true);
            Sheet sheet = wb.createSheet("Report");

            CellStyle titleStyle = titleStyle(wb);
            CellStyle headerStyle = headerStyle(wb);
            CellStyle numberStyle = numberStyle(wb);
            CellStyle totalStyle = totalStyle(wb);

            int totalCols = matrix.columns().size() + 2;  // 行标签 + 列... + 行总计
            int rowIdx = 0;

            // 标题
            Row titleRow = sheet.createRow(rowIdx++);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue(matrix.title() != null ? matrix.title() : "Report");
            titleCell.setCellStyle(titleStyle);
            if (totalCols > 1) {
                sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, totalCols - 1));
            }

            // 单位行（如有）
            if (matrix.unit() != null) {
                Row unitRow = sheet.createRow(rowIdx++);
                Cell unitCell = unitRow.createCell(0);
                unitCell.setCellValue("单位：" + matrix.unit());
                if (totalCols > 1) {
                    sheet.addMergedRegion(new CellRangeAddress(unitRow.getRowNum(), unitRow.getRowNum(), 0, totalCols - 1));
                }
            }

            // 表头
            Row headerRow = sheet.createRow(rowIdx++);
            int colIdx = 0;
            Cell rowDimHeader = headerRow.createCell(colIdx++);
            rowDimHeader.setCellValue(rowDimensionLabel(matrix.rowDimension()));
            rowDimHeader.setCellStyle(headerStyle);
            for (ReportMatrix.Column c : matrix.columns()) {
                Cell h = headerRow.createCell(colIdx++);
                h.setCellValue(c.label());
                h.setCellStyle(headerStyle);
            }
            Cell totalHeader = headerRow.createCell(colIdx);
            totalHeader.setCellValue("合计");
            totalHeader.setCellStyle(headerStyle);

            // 数据行
            for (ReportMatrix.Row r : matrix.rows()) {
                Row excelRow = sheet.createRow(rowIdx++);
                int c = 0;
                excelRow.createCell(c++).setCellValue(r.label());
                for (Double v : r.cells()) {
                    Cell cell = excelRow.createCell(c++);
                    cell.setCellValue(v != null ? v : 0.0);
                    cell.setCellStyle(numberStyle);
                }
                Cell rt = excelRow.createCell(c);
                rt.setCellValue(r.rowTotal());
                rt.setCellStyle(numberStyle);
            }

            // 列总计行
            if (!matrix.rows().isEmpty()) {
                Row totalsRow = sheet.createRow(rowIdx++);
                int c = 0;
                Cell label = totalsRow.createCell(c++);
                label.setCellValue("合计");
                label.setCellStyle(totalStyle);
                for (Double v : matrix.columnTotals()) {
                    Cell cell = totalsRow.createCell(c++);
                    cell.setCellValue(v != null ? v : 0.0);
                    cell.setCellStyle(totalStyle);
                }
                Cell grand = totalsRow.createCell(c);
                grand.setCellValue(matrix.grandTotal());
                grand.setCellStyle(totalStyle);
            }

            // 列宽：行标签较宽，数值列固定
            sheet.setColumnWidth(0, 6000);
            for (int i = 1; i < totalCols; i++) sheet.setColumnWidth(i, 4000);

            wb.write(out);
            wb.dispose();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CellStyle titleStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setFontHeightInPoints((short) 14);
        s.setFont(f);
        s.setAlignment(HorizontalAlignment.CENTER);
        return s;
    }

    private static CellStyle headerStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        s.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderTop(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
        return s;
    }

    private static CellStyle numberStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        DataFormat df = wb.createDataFormat();
        s.setDataFormat(df.getFormat("#,##0.00"));
        return s;
    }

    private static CellStyle totalStyle(Workbook wb) {
        CellStyle s = numberStyle(wb);
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        s.setFillForegroundColor(IndexedColors.GREY_40_PERCENT.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return s;
    }

    private static String rowDimensionLabel(ReportMatrix.RowDimension d) {
        return switch (d) {
            case ORG_NODE -> "组织节点";
            case METER -> "测点";
            case COST_CENTER -> "成本中心";
        };
    }
}
