package com.ems.report.controller;

import com.ems.report.async.AsyncReportRunner;
import com.ems.report.async.FileTokenStore;
import com.ems.report.dto.FileTokenDTO;
import com.ems.report.dto.ReportRequest;
import com.ems.report.service.ReportService;
import com.ems.report.support.CsvReportWriter;
import com.ems.timeseries.model.Granularity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.format.annotation.DateTimeFormat;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.ems.report.dto.ReportRow;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/v1/report")
@PreAuthorize("isAuthenticated()")
public class ReportController {

    private final ReportService service;
    private final AsyncReportRunner runner;
    private final FileTokenStore store;
    private final ObjectMapper objectMapper;

    public ReportController(ReportService service, AsyncReportRunner runner,
                            FileTokenStore store, ObjectMapper objectMapper) {
        this.service = service;
        this.runner = runner;
        this.store = store;
        this.objectMapper = objectMapper;
    }

    /** 同步导出：CSV 直接流式返回。适合小到中规模查询（数千行级）。 */
    @GetMapping(value = "/ad-hoc", produces = "text/csv; charset=UTF-8")
    public ResponseEntity<StreamingResponseBody> adHoc(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "HOUR") Granularity granularity,
            @RequestParam(required = false) Long orgNodeId,
            @RequestParam(required = false) List<String> energyType,
            @RequestParam(required = false) List<Long> meterId) {

        ReportRequest req = new ReportRequest(from, to, granularity, orgNodeId, energyType, meterId);
        // 立即查询触发权限校验：ForbiddenException 必须在返回 200 + body 之前抛出，否则会中途破坏响应。
        Stream<ReportRow> rows = service.queryStream(req);
        StreamingResponseBody body = out -> CsvReportWriter.write(rows, out);
        String filename = "ad-hoc-" + filenameTs(from) + "_" + filenameTs(to) + ".csv";

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .body(body);
    }

    /** 异步提交：返回 token；后台跑 CSV，写入临时文件。 */
    @PostMapping("/ad-hoc/async")
    public ResponseEntity<FileTokenDTO> submitAsync(@RequestBody ReportRequest req) {
        if (req == null || req.from() == null || req.to() == null || req.granularity() == null) {
            return ResponseEntity.badRequest().build();
        }
        String filename = "ad-hoc-" + filenameTs(req.from()) + "_" + filenameTs(req.to()) + ".csv";
        FileTokenStore.Entry entry = store.create(filename);
        runner.submit(entry, req);
        return ResponseEntity.accepted().body(toDto(entry));
    }

    /** 下载异步导出文件：READY → 200 + 字节；PENDING/RUNNING → 202；FAILED → 500；NotFound/expired → 410。
     *  返回类型固定 byte[]：避开 ResponseEntity&lt;?&gt; + StreamingResponseBody 触发的
     *  HttpMessageNotWritableException(No converter for Lambda) 旧 bug，并显式设 Content-Length 避免
     *  浏览器对 chunked encoding 偶发 ERR_INCOMPLETE_CHUNKED_ENCODING。 */
    @GetMapping("/file/{token}")
    public ResponseEntity<byte[]> download(@PathVariable("token") String token) throws IOException {
        FileTokenStore.Entry e = store.find(token).orElse(null);
        if (e == null) {
            byte[] msg = "token expired or not found".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return ResponseEntity.status(HttpStatus.GONE)
                    .contentType(MediaType.TEXT_PLAIN)
                    .contentLength(msg.length)
                    .body(msg);
        }
        return switch (e.status) {
            case PENDING, RUNNING -> jsonResponse(HttpStatus.ACCEPTED, toDto(e));
            case FAILED -> jsonResponse(HttpStatus.INTERNAL_SERVER_ERROR, toDto(e));
            case READY, DONE -> {
                byte[] data = Files.readAllBytes(e.file);
                store.evict(token);
                yield ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + e.filename + "\"")
                    .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                    .contentLength(data.length)
                    .body(data);
            }
        };
    }

    private ResponseEntity<byte[]> jsonResponse(HttpStatus status, Object payload) {
        try {
            byte[] data = objectMapper.writeValueAsBytes(payload);
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .contentLength(data.length)
                    .body(data);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            byte[] err = ("{\"error\":\"json failed: " + ex.getMessage() + "\"}").getBytes();
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .contentLength(err.length)
                    .body(err);
        }
    }

    private static FileTokenDTO toDto(FileTokenStore.Entry e) {
        return new FileTokenDTO(e.token, e.status.name(), e.filename,
            e.createdAt, e.expiresAt, e.bytes, e.error);
    }

    private static String filenameTs(Instant t) {
        return DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(java.time.ZoneOffset.UTC).format(t);
    }
}
