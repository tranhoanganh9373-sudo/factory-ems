package com.ems.report.async;

import com.ems.report.dto.ExportFormat;
import com.ems.report.dto.ExportPreset;
import com.ems.report.dto.ReportExportRequest;
import com.ems.report.export.CsvMatrixExporter;
import com.ems.report.export.ExcelExporter;
import com.ems.report.export.PdfExporter;
import com.ems.report.matrix.ReportMatrix;
import com.ems.report.preset.ReportPresetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.util.List;

/**
 * 异步执行 ReportPresetService → exporter → 写文件。
 * 通过 {@link ReportExportExecutorConfig#BEAN_NAME} 线程池执行；SecurityContext 由该线程池的 TaskDecorator 透传。
 *
 * 输出路径：{ems.report.export.base-dir}/{token}.{ext}（默认 ./ems_uploads/exports）
 */
@Component
public class AsyncExportRunner {

    private static final Logger log = LoggerFactory.getLogger(AsyncExportRunner.class);

    private final ReportPresetService presets;

    @Value("${ems.report.export.base-dir:./ems_uploads/exports}")
    private String baseDir;

    public AsyncExportRunner(ReportPresetService presets) {
        this.presets = presets;
    }

    /**
     * 提交异步任务。返回前 entry 已注册到 store；后台 worker 会更新 status。
     * 单测可直接调用此方法（在 mock 线程池下变成同步执行）。
     */
    @Async(ReportExportExecutorConfig.BEAN_NAME)
    public void run(FileTokenStore.Entry entry, ReportExportRequest req) {
        try {
            entry.status = FileTokenStore.Status.RUNNING;
            ReportMatrix matrix = buildMatrix(req);
            Path outFile = resolveOutputPath(entry.token, req.format());
            Files.createDirectories(outFile.getParent());
            try (OutputStream out = Files.newOutputStream(outFile)) {
                writeMatrix(matrix, req.format(), out);
            }
            entry.file = outFile;
            entry.bytes = Files.size(outFile);
            entry.status = FileTokenStore.Status.DONE;
        } catch (Throwable t) {
            log.warn("async export failed: token={} err={}", entry.token, t.toString());
            entry.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            entry.status = FileTokenStore.Status.FAILED;
        }
    }

    private ReportMatrix buildMatrix(ReportExportRequest req) {
        if (req.preset() == null) {
            throw new IllegalArgumentException("preset is required");
        }
        if (req.params() == null) {
            throw new IllegalArgumentException("params is required");
        }
        ReportExportRequest.Params p = req.params();
        Long orgNodeId = p.orgNodeId();
        List<String> energyTypes = p.energyTypes();

        return switch (req.preset()) {
            case DAILY -> {
                LocalDate date = require(p.date(), "params.date is required for DAILY");
                yield presets.daily(date, orgNodeId, energyTypes);
            }
            case MONTHLY -> {
                YearMonth ym = require(p.yearMonth(), "params.yearMonth is required for MONTHLY");
                yield presets.monthly(ym, orgNodeId, energyTypes);
            }
            case YEARLY -> {
                Year y = require(p.year(), "params.year is required for YEARLY");
                yield presets.yearly(y, orgNodeId, energyTypes);
            }
            case SHIFT -> {
                LocalDate date = require(p.date(), "params.date is required for SHIFT");
                Long shiftId = require(p.shiftId(), "params.shiftId is required for SHIFT");
                yield presets.shift(date, shiftId, orgNodeId, energyTypes);
            }
            case COST_MONTHLY -> {
                YearMonth ym = require(p.yearMonth(), "params.yearMonth is required for COST_MONTHLY");
                yield presets.costMonthly(ym, orgNodeId);
            }
        };
    }

    private static void writeMatrix(ReportMatrix matrix, ExportFormat format, OutputStream out) throws IOException {
        switch (format) {
            case CSV -> CsvMatrixExporter.write(matrix, out);
            case EXCEL -> ExcelExporter.write(matrix, out);
            case PDF -> PdfExporter.write(matrix, out);
        }
        out.flush();
    }

    Path resolveOutputPath(String token, ExportFormat format) {
        return Paths.get(baseDir, token + "." + format.ext());
    }

    /** 测试钩子：覆盖 baseDir。 */
    void setBaseDir(String dir) { this.baseDir = dir; }

    private static <T> T require(T v, String msg) {
        if (v == null) throw new IllegalArgumentException(msg);
        return v;
    }
}
