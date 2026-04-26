package com.ems.report.async;

import com.ems.report.dto.ExportFormat;
import com.ems.report.dto.ExportPreset;
import com.ems.report.dto.ReportExportRequest;
import com.ems.report.matrix.ReportMatrix;
import com.ems.report.preset.ReportPresetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncExportRunnerTest {

    @TempDir Path tmpDir;

    ReportPresetService presets;
    AsyncExportRunner runner;
    FileTokenStore store;

    private static ReportMatrix sample() {
        return new ReportMatrix(
                "测试报表",
                ReportMatrix.RowDimension.ORG_NODE,
                ReportMatrix.ColumnDimension.TIME_BUCKET,
                "kWh",
                List.of(new ReportMatrix.Column("c1", "2026-04-25"),
                        new ReportMatrix.Column("c2", "2026-04-26")),
                List.of(new ReportMatrix.Row("10", "一车间", List.of(10.0, 20.0), 30.0)),
                List.of(10.0, 20.0),
                30.0
        );
    }

    private static ReportExportRequest dailyReq(ExportFormat fmt) {
        return new ReportExportRequest(
                fmt,
                ExportPreset.DAILY,
                new ReportExportRequest.Params(
                        LocalDate.parse("2026-04-25"), null, null, null, 10L, List.of("ELEC")));
    }

    @BeforeEach
    void setup() {
        presets = mock(ReportPresetService.class);
        store = new FileTokenStore();
        runner = new AsyncExportRunner(presets);
        runner.setBaseDir(tmpDir.toString());
        when(presets.daily(any(LocalDate.class), ArgumentMatchers.anyLong(), anyList())).thenReturn(sample());
        when(presets.monthly(any(YearMonth.class), any(), any())).thenReturn(sample());
        when(presets.yearly(any(Year.class), any(), any())).thenReturn(sample());
        when(presets.shift(any(LocalDate.class), ArgumentMatchers.anyLong(), any(), any())).thenReturn(sample());
        when(presets.costMonthly(any(YearMonth.class), any())).thenReturn(sample());
    }

    @Test
    void run_costMonthly_excel_dispatches_to_costMonthly_preset() throws IOException {
        FileTokenStore.Entry entry = store.create("cost-monthly.xlsx");
        ReportExportRequest req = new ReportExportRequest(
                ExportFormat.EXCEL,
                ExportPreset.COST_MONTHLY,
                new ReportExportRequest.Params(null, YearMonth.parse("2026-03"), null, null, null, null));

        runner.run(entry, req);

        assertThat(entry.status).isEqualTo(FileTokenStore.Status.DONE);
        assertThat(entry.file).isNotNull();
        assertThat(Files.size(entry.file)).isPositive();
        verify(presets).costMonthly(eq(YearMonth.parse("2026-03")), eq(null));
    }

    @Test
    void run_costMonthly_missing_yearMonth_fails_with_clear_error() {
        FileTokenStore.Entry entry = store.create("cost-monthly.csv");
        ReportExportRequest req = new ReportExportRequest(
                ExportFormat.CSV,
                ExportPreset.COST_MONTHLY,
                new ReportExportRequest.Params(null, null, null, null, null, null));

        runner.run(entry, req);

        assertThat(entry.status).isEqualTo(FileTokenStore.Status.FAILED);
        assertThat(entry.error).contains("yearMonth");
    }

    @Test
    void run_csv_writesFileAndTransitionsRunningToDone() throws IOException {
        FileTokenStore.Entry entry = store.create("report.csv");
        assertThat(entry.status).isEqualTo(FileTokenStore.Status.PENDING);

        runner.run(entry, dailyReq(ExportFormat.CSV));

        assertThat(entry.status).isEqualTo(FileTokenStore.Status.DONE);
        assertThat(entry.file).isNotNull();
        assertThat(entry.file.getFileName().toString()).endsWith(".csv").contains(entry.token);
        assertThat(entry.bytes).isPositive();
        // BOM + 标题
        byte[] bytes = Files.readAllBytes(entry.file);
        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        assertThat(bytes[1]).isEqualTo((byte) 0xBB);
        assertThat(bytes[2]).isEqualTo((byte) 0xBF);
        verify(presets).daily(eq(LocalDate.parse("2026-04-25")), eq(10L), eq(List.of("ELEC")));
    }

    @Test
    void run_excel_writesXlsxFile() throws IOException {
        FileTokenStore.Entry entry = store.create("report.xlsx");

        runner.run(entry, dailyReq(ExportFormat.EXCEL));

        assertThat(entry.status).isEqualTo(FileTokenStore.Status.DONE);
        assertThat(entry.file.getFileName().toString()).endsWith(".xlsx");
        // PK xlsx 头
        byte[] bytes = Files.readAllBytes(entry.file);
        assertThat(bytes[0]).isEqualTo((byte) 'P');
        assertThat(bytes[1]).isEqualTo((byte) 'K');
    }

    @Test
    void run_pdf_writesPdfFile() throws IOException {
        FileTokenStore.Entry entry = store.create("report.pdf");

        runner.run(entry, dailyReq(ExportFormat.PDF));

        assertThat(entry.status).isEqualTo(FileTokenStore.Status.DONE);
        assertThat(entry.file.getFileName().toString()).endsWith(".pdf");
        try (var in = new ByteArrayInputStream(Files.readAllBytes(entry.file))) {
            byte[] head = in.readNBytes(4);
            // %PDF
            assertThat(new String(head)).isEqualTo("%PDF");
        }
    }

    @Test
    void run_monthly_dispatchesToMonthlyPreset() {
        FileTokenStore.Entry entry = store.create("m.csv");
        var req = new ReportExportRequest(
                ExportFormat.CSV, ExportPreset.MONTHLY,
                new ReportExportRequest.Params(null, YearMonth.parse("2026-04"), null, null, null, null));

        runner.run(entry, req);

        assertThat(entry.status).isEqualTo(FileTokenStore.Status.DONE);
        verify(presets).monthly(eq(YearMonth.parse("2026-04")), eq(null), eq(null));
    }

    @Test
    void run_yearly_dispatchesToYearlyPreset() {
        FileTokenStore.Entry entry = store.create("y.csv");
        var req = new ReportExportRequest(
                ExportFormat.CSV, ExportPreset.YEARLY,
                new ReportExportRequest.Params(null, null, Year.of(2026), null, null, null));

        runner.run(entry, req);

        assertThat(entry.status).isEqualTo(FileTokenStore.Status.DONE);
        verify(presets).yearly(eq(Year.of(2026)), eq(null), eq(null));
    }

    @Test
    void run_shift_dispatchesToShiftPreset() {
        FileTokenStore.Entry entry = store.create("s.csv");
        var req = new ReportExportRequest(
                ExportFormat.CSV, ExportPreset.SHIFT,
                new ReportExportRequest.Params(LocalDate.parse("2026-04-25"), null, null, 7L, null, null));

        runner.run(entry, req);

        assertThat(entry.status).isEqualTo(FileTokenStore.Status.DONE);
        verify(presets).shift(eq(LocalDate.parse("2026-04-25")), eq(7L), eq(null), eq(null));
    }

    @Test
    void run_failed_whenPresetThrows_setsStatusFailedWithError() {
        when(presets.daily(any(LocalDate.class), any(), any()))
                .thenThrow(new IllegalStateException("boom"));
        FileTokenStore.Entry entry = store.create("err.csv");

        runner.run(entry, dailyReq(ExportFormat.CSV));

        assertThat(entry.status).isEqualTo(FileTokenStore.Status.FAILED);
        assertThat(entry.error).contains("IllegalStateException").contains("boom");
        assertThat(entry.file).isNull();
    }

    @Test
    void run_failed_whenPresetParamMissing() {
        FileTokenStore.Entry entry = store.create("err.csv");
        var bad = new ReportExportRequest(
                ExportFormat.CSV, ExportPreset.DAILY,
                new ReportExportRequest.Params(null, null, null, null, null, null));

        runner.run(entry, bad);

        assertThat(entry.status).isEqualTo(FileTokenStore.Status.FAILED);
        assertThat(entry.error).contains("date");
    }

    @Test
    void resolveOutputPath_usesTokenAndExt() {
        FileTokenStore.Entry entry = store.create("x");
        Path p = runner.resolveOutputPath(entry.token, ExportFormat.EXCEL);
        assertThat(p.getFileName().toString()).isEqualTo(entry.token + ".xlsx");
    }
}
