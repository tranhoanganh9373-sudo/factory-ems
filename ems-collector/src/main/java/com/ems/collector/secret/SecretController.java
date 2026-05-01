package com.ems.collector.secret;

import com.ems.audit.aspect.AuditContext;
import com.ems.audit.event.AuditEvent;
import com.ems.audit.service.AuditService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/secrets")
@PreAuthorize("hasRole('ADMIN')")
public class SecretController {

    private final SecretResolver resolver;
    private final AuditService auditService;
    private final AuditContext auditContext;

    public SecretController(SecretResolver resolver, AuditService auditService, AuditContext auditContext) {
        this.resolver = resolver;
        this.auditService = auditService;
        this.auditContext = auditContext;
    }

    public record WriteRequest(@NotBlank String ref, @NotBlank String value) {}

    @GetMapping
    public List<String> list() {
        return resolver.listRefs();
    }

    @PostMapping
    public ResponseEntity<Void> write(@RequestBody WriteRequest req) {
        resolver.write(req.ref(), req.value());
        auditService.record(new AuditEvent(
            auditContext.currentUserId(), auditContext.currentUsername(),
            "SECRET_WRITE", "SECRET", req.ref(),
            "secret written", null, null, null, OffsetDateTime.now()));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@RequestParam @NotBlank String ref) {
        resolver.delete(ref);
        auditService.record(new AuditEvent(
            auditContext.currentUserId(), auditContext.currentUsername(),
            "SECRET_DELETE", "SECRET", ref,
            "secret deleted", null, null, null, OffsetDateTime.now()));
        return ResponseEntity.noContent().build();
    }
}
