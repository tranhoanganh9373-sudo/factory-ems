package com.ems.collector.secret;

import com.ems.audit.aspect.AuditContext;
import com.ems.audit.event.AuditEvent;
import com.ems.audit.service.AuditService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.regex.Pattern;

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

    /**
     * Multipart 上传 .pfx (PKCS#12) → 后端解析 → 落盘成 {@code secret://opcua/<name>.pem}
     * + {@code secret://opcua/<name>.pem.password}（OPC UA Transport 仍读 PEM，零改动）。
     *
     * <p>password 既是 keystore 解密密码，也作为生成的 encrypted PKCS#8 PEM 的密码——
     * 这样 channel 配置可继续用 {@code certPasswordRef=secret://opcua/<name>.pem.password}。
     */
    @PostMapping(path = "/opcua/cert", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> uploadPfx(
            @RequestPart("file") MultipartFile file,
            @RequestParam("name") String name,
            @RequestParam("password") String password,
            @RequestParam(value = "alias", required = false) String alias) {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is empty");
        }
        // The binding upload size gate is `spring.servlet.multipart.max-file-size`
        // (servlet container rejects oversized parts before this controller runs).
        // The check below is defense-in-depth — if the YAML cap is misconfigured
        // or removed, this still blocks oversized payloads from being parsed.
        if (file.getSize() > MAX_PFX_BYTES) {
            throw new IllegalArgumentException("file too large (max 64KB)");
        }
        validateName(name);
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("password is required");
        }

        byte[] pfxBytes;
        try {
            pfxBytes = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read uploaded file", e);
        }

        var result = Pkcs12CertificateImporter.importPfx(pfxBytes, password, alias);

        String pemRef = "secret://opcua/" + name + ".pem";
        String pwdRef = "secret://opcua/" + name + ".pem.password";

        // Write password first, then PEM. Rationale: a dangling password file is
        // harmless (nothing references it until the PEM is also present), whereas
        // a PEM without its password is an unrecoverable cert that breaks the
        // OPC UA transport on next startup with a cryptic decryption error.
        resolver.write(pwdRef, password);
        resolver.write(pemRef, result.certificatePem() + "\n" + result.encryptedKeyPem());

        auditService.record(new AuditEvent(
            auditContext.currentUserId(), auditContext.currentUsername(),
            "SECRET_PFX_UPLOAD", "SECRET", pemRef,
            "pfx uploaded fingerprint=" + result.fingerprintHex(),
            null, null, null, OffsetDateTime.now()));

        return ResponseEntity.noContent().build();
    }

    private static final long MAX_PFX_BYTES = 65_536L;
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final int NAME_MAX_LEN = 100;

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (name.length() > NAME_MAX_LEN) {
            throw new IllegalArgumentException("name too long (max " + NAME_MAX_LEN + ")");
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                "name must match " + NAME_PATTERN.pattern());
        }
    }
}
