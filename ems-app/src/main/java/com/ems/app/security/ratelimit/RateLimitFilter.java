package com.ems.app.security.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
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

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final int MAX_TRACKED_IPS = 50_000;
    private static final String OVER_LIMIT_BODY =
            "{\"success\":false,\"error\":\"Rate limit exceeded\"}";

    private final RateLimitProperties props;
    private final ConcurrentMap<String, Bucket> readBuckets;
    private final ConcurrentMap<String, Bucket> writeBuckets;
    private final ConcurrentMap<String, Bucket> loginBuckets;

    public RateLimitFilter(RateLimitProperties props) {
        this.props = props;
        this.readBuckets = boundedMap();
        this.writeBuckets = boundedMap();
        this.loginBuckets = boundedMap();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!props.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String uri = request.getRequestURI();
        // Login is checked BEFORE the exempt list so /api/v1/auth/login is throttled
        // even though /api/v1/auth/* is exempt for refresh / password-change.
        boolean isLogin = isLoginPath(uri);
        if (!isLogin && isExempt(uri)) {
            chain.doFilter(request, response);
            return;
        }

        boolean isRead = isReadMethod(request.getMethod());
        String key = resolveClientIp(request);
        Bucket bucket = bucketFor(isLogin, isRead, key);

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }

        long retryAfterSec = Math.max(1L,
                (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L);
        log.warn("Rate limit exceeded ip={} method={} uri={} retryAfterSec={}",
                key, request.getMethod(), request.getRequestURI(), retryAfterSec);
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

    private boolean isLoginPath(String uri) {
        if (uri == null) {
            return false;
        }
        for (String prefix : props.getLoginPathPrefixes()) {
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

    private Bucket bucketFor(boolean isLogin, boolean isRead, String key) {
        ConcurrentMap<String, Bucket> map = isLogin ? loginBuckets
                : (isRead ? readBuckets : writeBuckets);
        return map.computeIfAbsent(key, k -> newBucket(isLogin, isRead));
    }

    private Bucket newBucket(boolean isLogin, boolean isRead) {
        int perMinute = isLogin ? props.getLoginPerMinute()
                : (isRead ? props.getReadPerMinute() : props.getWritePerMinute());
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
}
