package com.ems.auth.controller;

import com.ems.audit.entity.AuditLog;
import com.ems.audit.repository.AuditLogRepository;
import com.ems.auth.dto.AuditLogDTO;
import com.ems.core.dto.PageDTO;
import com.ems.core.dto.Result;
import org.springframework.data.domain.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api/v1/audit-logs")
@PreAuthorize("hasRole('ADMIN')")
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
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable p = PageRequest.of(page - 1, size);
        Page<AuditLog> pg = repo.search(actorUserId, resourceType, action, from, to, p);
        var items = pg.map(a -> new AuditLogDTO(a.getId(), a.getActorUserId(), a.getActorUsername(),
            a.getAction(), a.getResourceType(), a.getResourceId(),
            a.getSummary(), a.getDetail(), a.getIp(), a.getUserAgent(), a.getOccurredAt())
        ).toList();
        return Result.ok(PageDTO.of(items, pg.getTotalElements(), page, size));
    }
}
