package com.ems.app.security.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-IP rate limiter with separate token buckets for read and write methods.
 *
 * <p>Reads (GET/HEAD/OPTIONS) and writes (everything else) use independent buckets.
 * Capacity = {@code burstMultiplier x perMinute}; refill rate = {@code perMinute / minute}.
 * Over-limit responses are HTTP 429 with a {@code Retry-After} header (seconds).
 *
 * <p>IP resolution prefers the first segment of {@code X-Forwarded-For}; otherwise the
 * request's remote address.
 *
 * <p>Memory: bucket maps are bounded with simple LRU eviction (access-order
 * {@link LinkedHashMap}, capped at {@link #MAX_TRACKED_IPS}). Tradeoff vs Caffeine:
 * one fewer dependency at the cost of a single intrinsic lock around the map. For v1
 * traffic (single-instance, intranet-scale) this is acceptable; if write contention
 * shows up in profiling, migrate to Caffeine without changing the public surface.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_TRACKED_IPS = 50_000;
    private static final String OVER_LIMIT_BODY =
            "{\"success\":false,\"error\":\"Rate limit exceeded\"}";

    private final RateLimitProperties props;
    private final ConcurrentMap<String, Bucket> readBuckets;
    private final ConcurrentMap<String, Bucket> writeBuckets;

    public RateLimitFilter(RateLimitProperties props) {
        this.props = props;
        this.readBuckets = boundedMap();
        this.writeBuckets = boundedMap();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!props.isEnabled() || isExempt(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        boolean isRead = isReadMethod(request.getMethod());
        String key = resolveClientIp(request);
        Bucket bucket = bucketFor(isRead, key);

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }

        long retryAfterSec = Math.max(1L,
                (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L);
        response.setStatus(429);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSec));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(OVER_LIMIT_BODY);
    }

    /* ── helpers ───────────────────────────────────────────────────── */

    private boolean isExempt(String uri) {
        if (uri == null) {
            return false;
        }
        for (String prefix : props.getExemptPathPrefixes()) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isReadMethod(String method) {
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }

    private static String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // Take only the first segment; ignore comma-list traversals.
            int comma = xff.indexOf(',');
            String first = (comma >= 0 ? xff.substring(0, comma) : xff).trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }

    private Bucket bucketFor(boolean isRead, String key) {
        ConcurrentMap<String, Bucket> map = isRead ? readBuckets : writeBuckets;
        return map.computeIfAbsent(key, k -> newBucket(isRead));
    }

    private Bucket newBucket(boolean isRead) {
        int perMinute = isRead ? props.getReadPerMinute() : props.getWritePerMinute();
        long capacity = (long) props.getBurstMultiplier() * perMinute;
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(capacity)
                .refillIntervally(perMinute, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(bandwidth).build();
    }

    private static <K, V> ConcurrentMap<K, V> boundedMap() {
        return new BoundedConcurrentMap<>(MAX_TRACKED_IPS);
    }

    /**
     * ConcurrentMap-shaped wrapper around an LRU LinkedHashMap. Mutating operations
     * are guarded by the backing map's intrinsic lock — sufficient for low-contention
     * lookup and bucket creation. Per-IP rate enforcement is lock-free inside Bucket4j
     * once the bucket reference is obtained.
     */
    private static final class BoundedConcurrentMap<K, V>
            extends java.util.AbstractMap<K, V>
            implements ConcurrentMap<K, V> {

        private final Map<K, V> backing;

        BoundedConcurrentMap(int maxEntries) {
            this.backing = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                    return size() > maxEntries;
                }
            });
        }

        @Override
        public java.util.Set<Entry<K, V>> entrySet() {
            return backing.entrySet();
        }

        @Override
        public V put(K key, V value) {
            return backing.put(key, value);
        }

        @Override
        public V get(Object key) {
            return backing.get(key);
        }

        @Override
        public V putIfAbsent(K key, V value) {
            synchronized (backing) {
                V existing = backing.get(key);
                if (existing != null) {
                    return existing;
                }
                backing.put(key, value);
                return null;
            }
        }

        @Override
        public boolean remove(Object key, Object value) {
            synchronized (backing) {
                V existing = backing.get(key);
                if (existing != null && existing.equals(value)) {
                    backing.remove(key);
                    return true;
                }
                return false;
            }
        }

        @Override
        public boolean replace(K key, V oldValue, V newValue) {
            synchronized (backing) {
                V existing = backing.get(key);
                if (existing != null && existing.equals(oldValue)) {
                    backing.put(key, newValue);
                    return true;
                }
                return false;
            }
        }

        @Override
        public V replace(K key, V value) {
            synchronized (backing) {
                if (backing.containsKey(key)) {
                    return backing.put(key, value);
                }
                return null;
            }
        }

        @Override
        public V computeIfAbsent(K key, java.util.function.Function<? super K, ? extends V> mappingFunction) {
            synchronized (backing) {
                V existing = backing.get(key);
                if (existing != null) {
                    return existing;
                }
                V created = mappingFunction.apply(key);
                if (created != null) {
                    backing.put(key, created);
                }
                return created;
            }
        }
    }
}
