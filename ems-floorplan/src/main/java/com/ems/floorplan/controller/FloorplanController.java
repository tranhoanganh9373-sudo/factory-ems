package com.ems.floorplan.controller;

import com.ems.core.dto.Result;
import com.ems.floorplan.dto.*;
import com.ems.floorplan.service.FloorplanService;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Floorplan REST API.
 *
 * <p>Note on image serving: in production, Nginx should be configured with an alias
 * to serve images directly from the upload directory, bypassing Spring entirely:
 * {@code location /api/v1/floorplans/{id}/image { alias /var/www/uploads/floorplans/{path}; }}
 * The {@code GET /{id}/image} endpoint below is provided for dev environments or
 * when Nginx alias is not configured.
 */
@RestController
@RequestMapping("/api/v1/floorplans")
public class FloorplanController {

    private final FloorplanService service;

    public FloorplanController(FloorplanService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Result<List<FloorplanDTO>> list(@RequestParam(required = false) Long orgNodeId) {
        return Result.ok(service.list(orgNodeId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public Result<FloorplanWithPointsDTO> getById(@PathVariable Long id) {
        return Result.ok(service.getById(id));
    }

    @PostMapping("/upload")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<FloorplanDTO> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("name") String name,
            @RequestParam("orgNodeId") Long orgNodeId) {
        return Result.ok(service.upload(file, name, orgNodeId));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<FloorplanDTO> update(@PathVariable Long id,
                                       @Valid @RequestBody UpdateFloorplanReq req) {
        return Result.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }

    @PutMapping("/{id}/points")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<FloorplanWithPointsDTO> setPoints(@PathVariable Long id,
                                                     @Valid @RequestBody SetPointsReq req) {
        return Result.ok(service.setPoints(id, req));
    }

    /**
     * Returns raw image bytes. In production, Nginx alias serves this path directly.
     * Sets Content-Type from stored content_type and Cache-Control for browser caching.
     */
    @GetMapping("/{id}/image")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Resource> loadImage(@PathVariable Long id) {
        // Resolve the floorplan to get metadata for headers
        FloorplanWithPointsDTO dto = service.getById(id);
        Resource resource = service.loadImage(id);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(dto.floorplan().contentType()))
                .contentLength(dto.floorplan().fileSizeBytes())
                .header(HttpHeaders.CACHE_CONTROL, "max-age=3600")
                .body(resource);
    }
}
