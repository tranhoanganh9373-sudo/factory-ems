package com.ems.meter.controller;

import com.ems.core.dto.Result;
import com.ems.meter.dto.CreateMeterReq;
import com.ems.meter.dto.MeterDTO;
import com.ems.meter.dto.MeterImportRow;
import com.ems.meter.dto.UpdateMeterReq;
import com.ems.meter.service.MeterCsvParser;
import com.ems.meter.service.MeterService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/meters")
public class MeterController {

    private final MeterService service;

    public MeterController(MeterService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Result<List<MeterDTO>> list(
            @RequestParam(required = false) Long orgNodeId,
            @RequestParam(required = false) Long energyTypeId,
            @RequestParam(required = false) Boolean enabled) {
        return Result.ok(service.list(orgNodeId, energyTypeId, enabled));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public Result<MeterDTO> get(@PathVariable Long id) { return Result.ok(service.getById(id)); }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<MeterDTO>> create(@Valid @RequestBody CreateMeterReq req) {
        MeterDTO d = service.create(req);
        return ResponseEntity.status(201).body(Result.ok(d));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<MeterDTO> update(@PathVariable Long id, @Valid @RequestBody UpdateMeterReq req) {
        return Result.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * meters.csv → JSON 解析端点。前端把用户上传的 CSV 喂给这里，拿到 {@link MeterImportRow}
     * 列表后再逐行调用 POST /meters 创建——这样可以复用现有的"逐行状态表 + 同 code 跳过"UX。
     *
     * <p>解析失败抛 IllegalArgumentException，由 GlobalExceptionHandler 映射为 400。
     */
    @PostMapping(value = "/parse-csv", consumes = "multipart/form-data")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<MeterImportRow>> parseCsv(@RequestPart("file") MultipartFile file)
            throws IOException {
        try (var in = file.getInputStream()) {
            return Result.ok(MeterCsvParser.parse(in));
        }
    }
}
