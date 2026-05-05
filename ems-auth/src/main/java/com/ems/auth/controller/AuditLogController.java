package com.ems.auth.controller;

import com.ems.audit.entity.AuditLog;
import com.ems.audit.repository.AuditLogRepository;
import com.ems.auth.dto.AuditLogDTO;
import com.ems.core.dto.PageDTO;
import com.ems.core.dto.Result;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api/v1/audit-logs")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AuditLogController {

    private final AuditLogRepository repo;
    public AuditLogController(AuditLogRepository r) { this.repo = r; }

    @GetMapping
    public Result<PageDTO<AuditLogDTO>> search(
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        // 防御：page 1-based；任何 ≤ 0 当 1 处理（旧客户端笔误也照样能拿到第一页）。
        // size 限 1..200 避免极端值打挂数据库。
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(200, size));
        Pageable p = PageRequest.of(safePage - 1, safeSize);
        Page<AuditLog> pg = repo.search(actorUserId, resourceType, action, from, to, p);
        var items = pg.map(a -> new AuditLogDTO(a.getId(), a.getActorUserId(), a.getActorUsername(),
            a.getAction(), a.getResourceType(), a.getResourceId(),
            a.getSummary(), a.getDetail(), a.getIp(), a.getUserAgent(), a.getOccurredAt())
        ).toList();
        return Result.ok(PageDTO.of(items, pg.getTotalElements(), safePage, safeSize));
    }
}
