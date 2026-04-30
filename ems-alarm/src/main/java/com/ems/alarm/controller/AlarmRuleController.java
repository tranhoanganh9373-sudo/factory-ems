package com.ems.alarm.controller;

import com.ems.alarm.config.AlarmProperties;
import com.ems.alarm.dto.DefaultsDTO;
import com.ems.alarm.dto.OverrideRequestDTO;
import com.ems.alarm.entity.AlarmRuleOverride;
import com.ems.alarm.repository.AlarmRuleOverrideRepository;
import com.ems.audit.annotation.Audited;
import com.ems.auth.security.AuthUser;
import com.ems.core.dto.Result;
import com.ems.core.exception.NotFoundException;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/alarm-rules")
public class AlarmRuleController {

    private final AlarmProperties props;
    private final AlarmRuleOverrideRepository repo;

    public AlarmRuleController(AlarmProperties props, AlarmRuleOverrideRepository repo) {
        this.props = props;
        this.repo = repo;
    }

    @GetMapping("/defaults")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Result<DefaultsDTO> defaults() {
        return Result.ok(new DefaultsDTO(
                props.defaultSilentTimeoutSeconds(),
                props.defaultConsecutiveFailCount(),
                props.suppressionWindowSeconds()));
    }

    @GetMapping("/overrides")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Result<List<AlarmRuleOverride>> listOverrides() {
        return Result.ok(repo.findAll());
    }

    @GetMapping("/overrides/{deviceId}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Result<AlarmRuleOverride> getOverride(@PathVariable Long deviceId) {
        return Result.ok(repo.findById(deviceId)
                .orElseThrow(() -> new NotFoundException("AlarmRuleOverride", deviceId)));
    }

    @PutMapping("/overrides/{deviceId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "UPDATE_OVERRIDE", resourceType = "ALARM_RULE", resourceIdExpr = "#deviceId")
    public Result<AlarmRuleOverride> setOverride(@PathVariable Long deviceId,
                                                  @Valid @RequestBody OverrideRequestDTO req,
                                                  @AuthenticationPrincipal AuthUser user) {
        AlarmRuleOverride o = repo.findById(deviceId).orElseGet(AlarmRuleOverride::new);
        o.setDeviceId(deviceId);
        o.setSilentTimeoutSeconds(req.silentTimeoutSeconds());
        o.setConsecutiveFailCount(req.consecutiveFailCount());
        o.setMaintenanceMode(req.maintenanceMode());
        o.setMaintenanceNote(req.maintenanceNote());
        o.setUpdatedAt(OffsetDateTime.now());
        o.setUpdatedBy(user.getUserId());
        return Result.ok(repo.save(o));
    }

    @DeleteMapping("/overrides/{deviceId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "DELETE_OVERRIDE", resourceType = "ALARM_RULE", resourceIdExpr = "#deviceId")
    public Result<Void> deleteOverride(@PathVariable Long deviceId) {
        repo.deleteById(deviceId);
        return Result.ok();
    }
}
