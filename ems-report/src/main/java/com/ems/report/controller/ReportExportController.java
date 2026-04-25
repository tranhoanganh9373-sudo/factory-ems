package com.ems.report.controller;

import com.ems.report.async.AsyncExportRunner;
import com.ems.report.async.FileTokenStore;
import com.ems.report.dto.ExportFormat;
import com.ems.report.dto.FileTokenDTO;
import com.ems.report.dto.ReportExportRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.nio.file.Files;

/**
 * 异步报表导出（matrix 流：CSV / Excel / PDF）。
 * 与遗留的 {@link ReportController}（/api/v1/report 单数）分开，避免路径前缀冲突。
 *
 * 路径与 Plan 1.3 设计文档一致：
 *   POST /api/v1/reports/export       提交（202 + token）
 *   GET  /api/v1/reports/export/{token} 下载（200 stream / 202 still running / 410 gone）
 */
@RestController
@RequestMapping("/api/v1/reports")
@PreAuthorize("isAuthenticated()")
public class ReportExportController {

    private final AsyncExportRunner runner;
    private final FileTokenStore store;

    public ReportExportController(AsyncExportRunner runner, FileTokenStore store) {
        this.runner = runner;
        this.store = store;
    }

    @PostMapping("/export")
    public ResponseEntity<FileTokenDTO> submitExport(@RequestBody ReportExportRequest req) {
        if (req == null || req.format() == null || req.preset() == null || req.params() == null) {
            return ResponseEntity.badRequest().build();
        }
        String filename = buildExportFilename(req);
        FileTokenStore.Entry entry = store.create(filename);
        runner.run(entry, req);
        return ResponseEntity.accepted().body(toDto(entry));
    }

    @GetMapping("/export/{token}")
    public ResponseEntity<?> downloadExport(@PathVariable("token") String token) {
        FileTokenStore.Entry e = store.find(token).orElse(null);
        if (e == null) {
            return ResponseEntity.status(HttpStatus.GONE).body("token expired or not found");
        }
        return switch (e.status) {
            case PENDING, RUNNING -> ResponseEntity.status(HttpStatus.ACCEPTED).body(toDto(e));
            case FAILED -> ResponseEntity.status(HttpStatus.GONE).body(toDto(e));
            case READY, DONE -> {
                StreamingResponseBody body = out -> {
                    try (InputStream in = Files.newInputStream(e.file)) {
                        in.transferTo(out);
                    } finally {
                        store.evict(token);
                    }
                };
                yield ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + e.filename + "\"")
                        .contentType(MediaType.parseMediaType(contentTypeFor(e.filename)))
                        .body(body);
            }
        };
    }

    private static String buildExportFilename(ReportExportRequest req) {
        String tag = switch (req.preset()) {
            case DAILY -> "daily-" + (req.params().date() != null ? req.params().date() : "");
            case MONTHLY -> "monthly-" + (req.params().yearMonth() != null ? req.params().yearMonth() : "");
            case YEARLY -> "yearly-" + (req.params().year() != null ? req.params().year() : "");
            case SHIFT -> "shift-" + (req.params().date() != null ? req.params().date() : "")
                    + "-" + (req.params().shiftId() != null ? req.params().shiftId() : "");
        };
        return "report-" + tag + "." + req.format().ext();
    }

    private static String contentTypeFor(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".xlsx")) return ExportFormat.EXCEL.contentType();
        if (lower.endsWith(".pdf")) return ExportFormat.PDF.contentType();
        return ExportFormat.CSV.contentType();
    }

    private static FileTokenDTO toDto(FileTokenStore.Entry e) {
        return new FileTokenDTO(e.token, e.status.name(), e.filename,
                e.createdAt, e.expiresAt, e.bytes, e.error);
    }
}
