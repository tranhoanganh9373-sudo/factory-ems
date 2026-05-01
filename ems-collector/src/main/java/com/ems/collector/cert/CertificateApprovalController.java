package com.ems.collector.cert;

import com.ems.audit.aspect.AuditContext;
import com.ems.audit.event.AuditEvent;
import com.ems.audit.service.AuditService;
import com.ems.collector.runtime.ChannelCertificateApprovedEvent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/collector")
@PreAuthorize("hasRole('ADMIN')")
public class CertificateApprovalController {

    private final OpcUaCertificateStore certStore;
    private final AuditService auditService;
    private final AuditContext auditContext;
    private final ApplicationEventPublisher publisher;

    public CertificateApprovalController(OpcUaCertificateStore certStore,
                                         AuditService auditService,
                                         AuditContext auditContext,
                                         ApplicationEventPublisher publisher) {
        this.certStore = certStore;
        this.auditService = auditService;
        this.auditContext = auditContext;
        this.publisher = publisher;
    }

    @GetMapping("/cert-pending")
    public List<PendingCertificate> listPending() throws IOException {
        return certStore.listPending();
    }

    public record TrustCertRequest(@NotBlank String thumbprint) {}

    @PostMapping("/{channelId}/trust-cert")
    public ResponseEntity<Void> trust(@PathVariable Long channelId,
                                      @Valid @RequestBody TrustCertRequest req) throws IOException {
        certStore.approve(req.thumbprint());
        auditService.record(new AuditEvent(
                auditContext.currentUserId(), auditContext.currentUsername(),
                "OPCUA_CERT_TRUST", "CHANNEL", String.valueOf(channelId),
                "OPC UA certificate trusted thumbprint=" + req.thumbprint(),
                null, auditContext.currentIp(), auditContext.currentUserAgent(),
                OffsetDateTime.now()));
        publisher.publishEvent(new ChannelCertificateApprovedEvent(
                channelId, req.thumbprint(), auditContext.currentUsername(), Instant.now()));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/cert-pending/{thumbprint}")
    public ResponseEntity<Void> reject(@PathVariable String thumbprint) throws IOException {
        certStore.reject(thumbprint);
        auditService.record(new AuditEvent(
                auditContext.currentUserId(), auditContext.currentUsername(),
                "OPCUA_CERT_REJECT", "CERT", thumbprint,
                "OPC UA certificate rejected",
                null, auditContext.currentIp(), auditContext.currentUserAgent(),
                OffsetDateTime.now()));
        return ResponseEntity.noContent().build();
    }
}
