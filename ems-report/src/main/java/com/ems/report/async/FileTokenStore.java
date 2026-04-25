package com.ems.report.async;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 异步导出令牌的内存存储。TTL 30min，过期自动清理临时文件。 */
@Component
public class FileTokenStore {

    private static final Logger log = LoggerFactory.getLogger(FileTokenStore.class);
    private static final Duration TTL = Duration.ofMinutes(30);

    /**
     * READY/DONE 含义相同，二者并存仅为兼容历史 API（CSV 同步导出走 READY；
     * 新版异步导出 /reports/export/{token} 走 DONE）。Controller 层判断 isTerminalSuccess()。
     */
    public enum Status {
        PENDING, RUNNING, READY, DONE, FAILED;

        public boolean isTerminalSuccess() { return this == READY || this == DONE; }
    }

    public static final class Entry {
        public final String token;
        public final String filename;
        public final Instant createdAt;
        public final Instant expiresAt;
        public volatile Status status = Status.PENDING;
        public volatile Path file;
        public volatile Long bytes;
        public volatile String error;

        Entry(String token, String filename, Instant created, Instant expires) {
            this.token = token; this.filename = filename;
            this.createdAt = created; this.expiresAt = expires;
        }
    }

    private final Map<String, Entry> map = new ConcurrentHashMap<>();

    public Entry create(String filename) {
        String token = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Entry e = new Entry(token, filename, now, now.plus(TTL));
        map.put(token, e);
        return e;
    }

    public Optional<Entry> find(String token) {
        Entry e = map.get(token);
        if (e == null) return Optional.empty();
        if (Instant.now().isAfter(e.expiresAt)) {
            evict(token);
            return Optional.empty();
        }
        return Optional.of(e);
    }

    public void evict(String token) {
        Entry e = map.remove(token);
        if (e != null && e.file != null) {
            try { Files.deleteIfExists(e.file); }
            catch (IOException ex) { log.warn("evict: delete tmp file failed: {}", ex.getMessage()); }
        }
    }

    public int size() { return map.size(); }

    @PreDestroy
    public void cleanupAll() {
        for (var token : Map.copyOf(map).keySet()) evict(token);
    }
}
