package com.ems.production.controller;

import com.ems.core.dto.PageDTO;
import com.ems.core.dto.Result;
import com.ems.production.dto.*;
import com.ems.production.service.ProductionEntryService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/production")
public class ProductionEntryController {

    private final ProductionEntryService service;

    public ProductionEntryController(ProductionEntryService service) { this.service = service; }

    @GetMapping("/entries")
    @PreAuthorize("isAuthenticated()")
    public Result<PageDTO<ProductionEntryDTO>> search(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long orgNodeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<ProductionEntryDTO> result = service.search(from, to, orgNodeId, pageable);
        return Result.ok(PageDTO.of(result.getContent(), result.getTotalElements(), page, size));
    }

    @GetMapping("/entries/{id}")
    @PreAuthorize("isAuthenticated()")
    public Result<ProductionEntryDTO> getById(@PathVariable Long id) {
        return Result.ok(service.getById(id));
    }

    @PostMapping("/entries")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<ProductionEntryDTO>> create(@Valid @RequestBody CreateProductionEntryReq req) {
        ProductionEntryDTO dto = service.create(req);
        return ResponseEntity.status(201).body(Result.ok(dto));
    }

    @PutMapping("/entries/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<ProductionEntryDTO> update(@PathVariable Long id,
                                              @Valid @RequestBody UpdateProductionEntryReq req) {
        return Result.ok(service.update(id, req));
    }

    @DeleteMapping("/entries/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/entries/import")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<BulkImportResult> importCsv(@RequestParam("file") MultipartFile file) throws IOException {
        return Result.ok(service.importCsv(file.getInputStream(), file.getOriginalFilename()));
    }

    @GetMapping("/entries/daily-totals")
    @PreAuthorize("isAuthenticated()")
    public Result<Map<LocalDate, BigDecimal>> dailyTotals(
            @RequestParam Long orgNodeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Result.ok(service.dailyTotals(orgNodeId, from, to));
    }
}
