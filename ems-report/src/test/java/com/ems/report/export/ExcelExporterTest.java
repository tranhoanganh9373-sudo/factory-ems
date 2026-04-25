package com.ems.report.export;

import com.ems.report.matrix.ReportMatrix;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExcelExporterTest {

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
    void write_producesParseableXlsx_withTitleHeadersAndTotals() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelExporter.write(sample(), out);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("Report");

            // row 0 = title
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).contains("2026 年 4 月日报");
            // row 1 = unit
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("单位：kWh");
            // row 2 = header
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("组织节点");
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("2026-04-25");
            assertThat(sheet.getRow(2).getCell(2).getStringCellValue()).isEqualTo("2026-04-26");
            assertThat(sheet.getRow(2).getCell(3).getStringCellValue()).isEqualTo("合计");

            // row 3 = 一车间
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("一车间");
            assertThat(sheet.getRow(3).getCell(1).getNumericCellValue()).isEqualTo(10.0);
            assertThat(sheet.getRow(3).getCell(2).getNumericCellValue()).isEqualTo(20.0);
            assertThat(sheet.getRow(3).getCell(3).getNumericCellValue()).isEqualTo(30.0);

            // row 5 = totals row
            assertThat(sheet.getRow(5).getCell(0).getStringCellValue()).isEqualTo("合计");
            assertThat(sheet.getRow(5).getCell(1).getNumericCellValue()).isEqualTo(15.0);
            assertThat(sheet.getRow(5).getCell(2).getNumericCellValue()).isEqualTo(20.0);
            assertThat(sheet.getRow(5).getCell(3).getNumericCellValue()).isEqualTo(35.0);
        }
    }

    @Test
    void write_handlesEmptyMatrix() throws IOException {
        ReportMatrix empty = new ReportMatrix("空报表",
                ReportMatrix.RowDimension.METER, ReportMatrix.ColumnDimension.ENERGY_TYPE,
                null, List.of(), List.of(), List.of(), 0.0);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelExporter.write(empty, out);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("空报表");
            // header row exists（只有"测点"+合计 两列）
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("测点");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("合计");
            // 没有数据行 → row 2 应为 null
            assertThat(sheet.getRow(2)).isNull();
        }
    }

    @Test
    void write_omitsUnitRow_whenUnitIsNull() throws IOException {
        ReportMatrix m = new ReportMatrix("混合单位",
                ReportMatrix.RowDimension.ORG_NODE, ReportMatrix.ColumnDimension.ENERGY_TYPE,
                null,
                List.of(new ReportMatrix.Column("ELEC", "ELEC"), new ReportMatrix.Column("WATER", "WATER")),
                List.of(new ReportMatrix.Row("10", "一车间", List.of(100.0, 50.0), 150.0)),
                List.of(100.0, 50.0), 150.0);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelExporter.write(m, out);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);
            // row 0 = title, row 1 直接是 header（无单位行）
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("组织节点");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("ELEC");
        }
    }
}
