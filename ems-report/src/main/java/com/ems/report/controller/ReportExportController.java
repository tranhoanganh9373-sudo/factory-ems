package com.ems.report.controller;

import com.ems.report.async.AsyncExportRunner;
import com.ems.report.async.FileTokenStore;
import com.ems.report.dto.ExportFormat;
import com.ems.report.dto.FileTokenDTO;
import com.ems.report.dto.ReportExportRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.IOException;
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
    private final ObjectMapper objectMapper;

    public ReportExportController(AsyncExportRunner runner, FileTokenStore store, ObjectMapper objectMapper) {
        this.runner = runner;
        this.store = store;
        this.objectMapper = objectMapper;
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

    /**
     * 下载或轮询导出。返回类型固定 ResponseEntity&lt;byte[]&gt; 并设 Content-Length，
     * 避开 chunked encoding —— Chrome/axios 在 responseType=blob 下对 chunked
     * Excel 流偶发 ERR_INCOMPLETE_CHUNKED_ENCODING（curl 同 endpoint 正常）。
     * 报表文件单次最多几 MB，全量加载到内存可接受。
     */
    @GetMapping("/export/{token}")
    public ResponseEntity<byte[]> downloadExport(@PathVariable("token") String token) throws IOException {
        FileTokenStore.Entry e = store.find(token).orElse(null);
        if (e == null) {
            return jsonResponse(HttpStatus.GONE, java.util.Map.of("error", "token expired or not found"));
        }
        if (e.status == FileTokenStore.Status.PENDING || e.status == FileTokenStore.Status.RUNNING) {
            return jsonResponse(HttpStatus.ACCEPTED, toDto(e));
        }
        if (e.status == FileTokenStore.Status.FAILED) {
            return jsonResponse(HttpStatus.GONE, toDto(e));
        }
        // READY / DONE — 一次性读完，显式 Content-Length。
        byte[] data = Files.readAllBytes(e.file);
        store.evict(token);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + e.filename + "\"")
                .contentType(MediaType.parseMediaType(contentTypeFor(e.filename)))
                .contentLength(data.length)
                .body(data);
    }

    private ResponseEntity<byte[]> jsonResponse(HttpStatus status, Object payload) {
        byte[] data;
        try {
            data = objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException ex) {
            data = ("{\"error\":\"json serialization failed: " + ex.getMessage() + "\"}").getBytes();
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(data.length)
                .body(data);
    }

    private static String buildExportFilename(ReportExportRequest req) {
        String tag = switch (req.preset()) {
            case DAILY -> "daily-" + (req.params().date() != null ? req.params().date() : "");
            case MONTHLY -> "monthly-" + (req.params().yearMonth() != null ? req.params().yearMonth() : "");
            case YEARLY -> "yearly-" + (req.params().year() != null ? req.params().year() : "");
            case SHIFT -> "shift-" + (req.params().date() != null ? req.params().date() : "")
                    + "-" + (req.params().shiftId() != null ? req.params().shiftId() : "");
            case COST_MONTHLY -> "cost-monthly-"
                    + (req.params().yearMonth() != null ? req.params().yearMonth() : "");
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
