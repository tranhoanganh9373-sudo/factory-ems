package com.ems.collector.secret;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Component
public class FilesystemSecretResolver implements SecretResolver {
    private static final Logger log = LoggerFactory.getLogger(FilesystemSecretResolver.class);
    private static final String SCHEME = "secret://";
    private static final Set<PosixFilePermission> FILE_PERMS = Set.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> ALLOWED_DIR_PERMS = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE);

    private final Path secretsDir;

    public FilesystemSecretResolver(
            @Value("${ems.secrets.dir:#{systemProperties['user.home']}/.ems/secrets}") Path secretsDir) {
        this.secretsDir = secretsDir;
    }

    @PostConstruct
    public void init() throws IOException {
        if (!Files.exists(secretsDir)) {
            Files.createDirectories(secretsDir);
        }
        // Heal-on-init: 无论 dir 是新建还是已存在，每次启动都尝试收紧到 0700。
        // 之前只在「新建」分支 chmod，导致容器升级/重建时若 dir 已存在（umask 0022
        // 创建为 0755），后续校验直接抛 SecurityException，阻塞启动。
        try {
            Files.setPosixFilePermissions(secretsDir, ALLOWED_DIR_PERMS);
        } catch (UnsupportedOperationException e) {
            log.warn("filesystem does not support POSIX permissions; skipping strict perm enforcement on {}", secretsDir);
            return;
        }
        var perms = Files.getPosixFilePermissions(secretsDir);
        for (var p : perms) {
            if (!ALLOWED_DIR_PERMS.contains(p)) {
                throw new SecurityException(
                    "secrets dir " + secretsDir + " has unsafe permission " + p +
                    "; must be <= 700 (rwx------)");
            }
        }
        log.info("SecretResolver initialized at {}", secretsDir);
    }

    @Override
    public String resolve(String ref) {
        var path = parseAndValidate(ref);
        try { return Files.readString(path).strip(); }
        catch (IOException e) {
            throw new RuntimeException("read secret failed: " + ref, e);
        }
    }

    @Override
    public boolean exists(String ref) {
        try { return Files.exists(parseAndValidate(ref)); }
        catch (Exception e) { return false; }
    }

    @Override
    public void write(String ref, String value) {
        var path = parseAndValidate(ref);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, value,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try { Files.setPosixFilePermissions(path, FILE_PERMS); }
            catch (UnsupportedOperationException ignored) {}
            log.info("secret written: {}", ref);
        } catch (IOException e) {
            throw new RuntimeException("write secret failed: " + ref, e);
        }
    }

    @Override
    public void delete(String ref) {
        try { Files.deleteIfExists(parseAndValidate(ref)); }
        catch (IOException e) {
            throw new RuntimeException("delete secret failed: " + ref, e);
        }
    }

    @Override
    public List<String> listRefs() {
        if (!Files.exists(secretsDir)) return List.of();
        try (Stream<Path> walk = Files.walk(secretsDir)) {
            return walk.filter(Files::isRegularFile)
                .map(p -> SCHEME + secretsDir.relativize(p).toString().replace('\\', '/'))
                .sorted()
                .toList();
        } catch (IOException e) {
            log.warn("listRefs failed: {}", e.getMessage());
            return List.of();
        }
    }

    private Path parseAndValidate(String ref) {
        if (ref == null || !ref.startsWith(SCHEME)) {
            throw new IllegalArgumentException("invalid secret ref scheme; expected " + SCHEME);
        }
        var rel = ref.substring(SCHEME.length());
        if (rel.isBlank()) {
            throw new IllegalArgumentException("empty secret path");
        }
        var path = secretsDir.resolve(rel).normalize();
        if (!path.startsWith(secretsDir)) {
            throw new SecurityException("path traversal attempt: " + ref);
        }
        return path;
    }
}
