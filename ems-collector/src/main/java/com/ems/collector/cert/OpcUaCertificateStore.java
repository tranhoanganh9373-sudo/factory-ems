package com.ems.collector.cert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Component
public class OpcUaCertificateStore {

    private static final Logger log = LoggerFactory.getLogger(OpcUaCertificateStore.class);

    private static final Set<PosixFilePermission> OWNER_RW = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path trustedDir;
    private final Path pendingDir;
    private final Path rejectedDir;
    private final ObjectMapper mapper;

    public OpcUaCertificateStore(
            @Value("${ems.secrets.dir:#{systemProperties['user.home']}/.ems/secrets}") Path secretsDir) {
        Path base = secretsDir.resolve("opcua/certs");
        this.trustedDir  = base.resolve("trusted");
        this.pendingDir  = base.resolve("pending");
        this.rejectedDir = base.resolve("rejected");
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(trustedDir);
        Files.createDirectories(pendingDir);
        Files.createDirectories(rejectedDir);
    }

    // ── existing methods (unchanged signatures) ──────────────────────────────

    public boolean isTrusted(X509Certificate cert) throws IOException, NoSuchAlgorithmException, CertificateEncodingException {
        var thumb = thumbprint(cert);
        try (Stream<Path> stream = Files.list(trustedDir)) {
            return stream.anyMatch(p -> p.getFileName().toString().endsWith(thumb + ".der"));
        }
    }

    /** Original approve overload — keeps cert file named by displayName. */
    public void approve(X509Certificate cert, String displayName) throws IOException, NoSuchAlgorithmException, CertificateEncodingException {
        var thumb = thumbprint(cert);
        var name = (displayName != null ? displayName : "cert") + "-" + thumb + ".der";
        Files.write(trustedDir.resolve(name), cert.getEncoded());
    }

    public String thumbprint(X509Certificate cert) throws NoSuchAlgorithmException, CertificateEncodingException {
        var md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(cert.getEncoded()));
    }

    // ── new methods ───────────────────────────────────────────────────────────

    /**
     * 写入 pending/{thumbprint}.der + pending/{thumbprint}.json。
     * 幂等：若同 thumbprint 的 .der 已存在，跳过写入（保留原 firstSeenAt）。
     * 文件权限 rw-------（spec §8.6）。
     */
    public void addPending(X509Certificate cert, Long channelId, String endpointUrl)
            throws IOException, NoSuchAlgorithmException, CertificateEncodingException {
        String thumb = thumbprint(cert);
        Path derPath  = pendingDir.resolve(thumb + ".der");
        Path jsonPath = pendingDir.resolve(thumb + ".json");

        if (Files.exists(derPath)) {
            return;
        }

        Files.write(derPath, cert.getEncoded());
        setOwnerRw(derPath);

        Map<String, Object> meta = Map.of(
                "thumbprint",  thumb,
                "channelId",   channelId,
                "endpointUrl", endpointUrl,
                "firstSeenAt", Instant.now().toString(),
                "subjectDn",   cert.getSubjectX500Principal().getName());
        Files.writeString(jsonPath, mapper.writeValueAsString(meta));
        setOwnerRw(jsonPath);
    }

    /** 扫描 pending 目录，返回所有待审批证书列表。 */
    public List<PendingCertificate> listPending() throws IOException {
        List<PendingCertificate> result = new ArrayList<>();
        if (!Files.exists(pendingDir)) return result;

        try (Stream<Path> stream = Files.list(pendingDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                  .forEach(jsonPath -> {
                      try {
                          @SuppressWarnings("unchecked")
                          Map<String, Object> meta = mapper.readValue(jsonPath.toFile(), Map.class);
                          result.add(new PendingCertificate(
                                  (String) meta.get("thumbprint"),
                                  ((Number) meta.get("channelId")).longValue(),
                                  (String) meta.get("endpointUrl"),
                                  Instant.parse((String) meta.get("firstSeenAt")),
                                  (String) meta.get("subjectDn")));
                      } catch (Exception e) {
                          log.warn("skipping malformed pending cert json: {} - {}", jsonPath, e.getMessage());
                      }
                  });
        }
        return result;
    }

    /**
     * 按 thumbprint 审批：把 pending/{thumbprint}.der 移动到 trusted/，删除对应 .json。
     *
     * @throws IOException 若 pending 中不存在该 thumbprint
     */
    public void approve(String thumbprint) throws IOException {
        Path derPath  = pendingDir.resolve(thumbprint + ".der");
        Path jsonPath = pendingDir.resolve(thumbprint + ".json");
        if (!Files.exists(derPath)) {
            throw new IOException("pending cert not found: " + thumbprint);
        }
        Files.move(derPath, trustedDir.resolve(thumbprint + ".der"));
        Files.deleteIfExists(jsonPath);
    }

    /**
     * 按 thumbprint 拒绝：把 pending/{thumbprint}.der 移动到 rejected/，删除对应 .json。
     *
     * @throws IOException 若 pending 中不存在该 thumbprint
     */
    public void reject(String thumbprint) throws IOException {
        Path derPath  = pendingDir.resolve(thumbprint + ".der");
        Path jsonPath = pendingDir.resolve(thumbprint + ".json");
        if (!Files.exists(derPath)) {
            throw new IOException("pending cert not found: " + thumbprint);
        }
        Files.move(derPath, rejectedDir.resolve(thumbprint + ".der"));
        Files.deleteIfExists(jsonPath);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private static void setOwnerRw(Path path) {
        try {
            Files.setPosixFilePermissions(path, OWNER_RW);
        } catch (UnsupportedOperationException | IOException ignored) {
            // non-POSIX FS (Windows CI) — best-effort
        }
    }
}
