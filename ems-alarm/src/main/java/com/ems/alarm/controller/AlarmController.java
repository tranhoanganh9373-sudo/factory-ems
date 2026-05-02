package com.ems.alarm.controller;

import com.ems.alarm.dto.AlarmDTO;
import com.ems.alarm.dto.AlarmListItemDTO;
import com.ems.alarm.dto.HealthSummaryDTO;
import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.AlarmType;
import com.ems.alarm.service.AlarmService;
import com.ems.audit.annotation.Audited;
import com.ems.auth.security.AuthUser;
import com.ems.core.dto.PageDTO;
import com.ems.core.dto.Result;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/alarms")
@Validated
public class AlarmController {

    private final AlarmService service;

    public AlarmController(AlarmService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Result<PageDTO<AlarmListItemDTO>> list(
            @RequestParam(required = false) AlarmStatus status,
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) AlarmType alarmType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        return Result.ok(service.list(status, deviceId, alarmType, from, to, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Result<AlarmDTO> getById(@PathVariable Long id) {
        return Result.ok(service.getById(id));
    }

    @PostMapping("/{id}/ack")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "ACK", resourceType = "ALARM", resourceIdExpr = "#id")
    public Result<Void> ack(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        service.ack(id, user.getUserId());
        return Result.ok();
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "RESOLVE", resourceType = "ALARM", resourceIdExpr = "#id")
    public Result<Void> resolve(@PathVariable Long id) {
        service.resolve(id);
        return Result.ok();
    }

    @GetMapping("/active/count")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Result<Map<String, Long>> activeCount() {
        return Result.ok(Map.of("count", service.countActive()));
    }

    @GetMapping("/health-summary")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Result<HealthSummaryDTO> healthSummary() {
        return Result.ok(service.healthSummary());
    }
}
