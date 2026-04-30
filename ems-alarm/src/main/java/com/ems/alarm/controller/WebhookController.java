package com.ems.alarm.controller;

import com.ems.alarm.dto.DeliveryLogDTO;
import com.ems.alarm.dto.WebhookConfigDTO;
import com.ems.alarm.dto.WebhookConfigRequestDTO;
import com.ems.alarm.dto.WebhookTestResultDTO;
import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.AlarmType;
import com.ems.alarm.entity.WebhookConfig;
import com.ems.alarm.entity.WebhookDeliveryLog;
import com.ems.alarm.exception.WebhookConfigInvalidException;
import com.ems.alarm.repository.WebhookConfigRepository;
import com.ems.alarm.repository.WebhookDeliveryLogRepository;
import com.ems.alarm.service.WebhookChannel;
import com.ems.audit.annotation.Audited;
import com.ems.auth.security.AuthUser;
import com.ems.core.dto.PageDTO;
import com.ems.core.dto.Result;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasRole('ADMIN')")
public class WebhookController {

    private final WebhookConfigRepository cfgRepo;
    private final WebhookDeliveryLogRepository deliveryRepo;
    private final WebhookChannel webhookChannel;

    public WebhookController(WebhookConfigRepository cfgRepo,
                             WebhookDeliveryLogRepository deliveryRepo,
                             WebhookChannel webhookChannel) {
        this.cfgRepo = cfgRepo;
        this.deliveryRepo = deliveryRepo;
        this.webhookChannel = webhookChannel;
    }

    @GetMapping("/webhook-config")
    public Result<WebhookConfigDTO> get() {
        WebhookConfig cfg = cfgRepo.findFirstByOrderByIdAsc().orElseGet(WebhookConfig::new);
        return Result.ok(toDto(cfg));
    }

    @PutMapping("/webhook-config")
    @Audited(action = "UPDATE_WEBHOOK", resourceType = "WEBHOOK_CONFIG")
    public Result<WebhookConfigDTO> update(@Valid @RequestBody WebhookConfigRequestDTO req,
                                            @AuthenticationPrincipal AuthUser user) {
        validateUrl(req.url());
        WebhookConfig cfg = cfgRepo.findFirstByOrderByIdAsc().orElseGet(WebhookConfig::new);
        cfg.setEnabled(req.enabled());
        cfg.setUrl(req.url());
        // 仅当 secret 非空时更新；空串或 null 表示"保持不变"
        if (req.secret() != null && !req.secret().isEmpty()) {
            cfg.setSecret(req.secret());
        }
        cfg.setAdapterType(req.adapterType() == null ? "GENERIC_JSON" : req.adapterType());
        cfg.setTimeoutMs(req.timeoutMs());
        cfg.setUpdatedAt(OffsetDateTime.now());
        cfg.setUpdatedBy(user.getUserId());
        WebhookConfig saved = cfgRepo.save(cfg);
        return Result.ok(toDto(saved));
    }

    @PostMapping("/webhook-config/test")
    public Result<WebhookTestResultDTO> test(@Valid @RequestBody WebhookConfigRequestDTO req) {
        validateUrl(req.url());
        WebhookConfig probe = new WebhookConfig();
        probe.setUrl(req.url());
        probe.setSecret(req.secret() == null ? "" : req.secret());
        probe.setAdapterType(req.adapterType() == null ? "GENERIC_JSON" : req.adapterType());
        probe.setTimeoutMs(req.timeoutMs());
        Alarm sample = sampleAlarm();
        WebhookChannel.WebhookTestResult r = webhookChannel.test(probe, sample, "M-TEST", "Test Meter");
        return Result.ok(new WebhookTestResultDTO(r.statusCode(), r.durationMs(), r.error()));
    }

    @GetMapping("/webhook-deliveries")
    public Result<PageDTO<DeliveryLogDTO>> deliveries(@RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        Page<WebhookDeliveryLog> p = deliveryRepo.findAllByOrderByCreatedAtDesc(
                PageRequest.of(page - 1, size));
        List<DeliveryLogDTO> items = p.getContent().stream().map(this::toDeliveryDto).toList();
        return Result.ok(PageDTO.of(items, p.getTotalElements(), page, size));
    }

    @PostMapping("/webhook-deliveries/{id}/retry")
    @Audited(action = "RETRY_DELIVERY", resourceType = "WEBHOOK_DELIVERY", resourceIdExpr = "#id")
    public Result<Void> retry(@PathVariable Long id) {
        webhookChannel.retryDelivery(id);
        return Result.ok();
    }

    private void validateUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null
                    || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new WebhookConfigInvalidException("url scheme must be http or https");
            }
            if (uri.getHost() == null || uri.getHost().isEmpty()) {
                throw new WebhookConfigInvalidException("url must include host");
            }
        } catch (IllegalArgumentException e) {
            throw new WebhookConfigInvalidException("invalid url: " + e.getMessage());
        }
    }

    private WebhookConfigDTO toDto(WebhookConfig cfg) {
        String secretMask = (cfg.getSecret() == null || cfg.getSecret().isEmpty()) ? "" : "***";
        return new WebhookConfigDTO(
                cfg.isEnabled(),
                cfg.getUrl(),
                secretMask,
                cfg.getAdapterType(),
                cfg.getTimeoutMs(),
                cfg.getUpdatedAt()
        );
    }

    private DeliveryLogDTO toDeliveryDto(WebhookDeliveryLog l) {
        return new DeliveryLogDTO(
                l.getId(),
                l.getAlarmId(),
                l.getAttempts(),
                l.getStatus(),
                l.getLastError(),
                l.getResponseStatus(),
                l.getResponseMs(),
                l.getCreatedAt()
        );
    }

    private Alarm sampleAlarm() {
        Alarm a = new Alarm();
        a.setId(0L);
        a.setDeviceId(0L);
        a.setDeviceType("METER");
        a.setAlarmType(AlarmType.SILENT_TIMEOUT);
        a.setSeverity("WARNING");
        a.setStatus(AlarmStatus.ACTIVE);
        a.setTriggeredAt(OffsetDateTime.now());
        return a;
    }
}
