package com.ems.app.security.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the per-IP API rate limiter.
 *
 * <p>Bound from {@code ems.security.rate-limit.*} in {@code application.yml}.
 * Restart required to apply changes.
 */
@ConfigurationProperties("ems.security.rate-limit")
public class RateLimitProperties {

    /** Master switch. When false, the filter is a no-op. */
    private boolean enabled = true;

    /** Per-IP limit for read methods (GET / HEAD / OPTIONS), per minute. */
    private int readPerMinute = 600;

    /** Per-IP limit for write methods (POST / PUT / PATCH / DELETE), per minute. */
    private int writePerMinute = 60;

    /** Bucket capacity = burstMultiplier x perMinute. */
    private int burstMultiplier = 2;

    /**
     * Per-IP limit for the login endpoint, per minute. Stricter than {@link #writePerMinute}
     * to mitigate slow-rate dictionary / credential-stuffing attacks (OWASP API4:2023).
     * RealityCheck 2026-05-02: 30 successive logins were accepted with prior config.
     */
    private int loginPerMinute = 10;

    /**
     * URI prefixes treated as login attempts and bucketed against {@link #loginPerMinute}.
     * Matched BEFORE {@link #exemptPathPrefixes}, so the rest of {@code /api/v1/auth/*}
     * (refresh, password-change, …) can still be exempt while login is throttled.
     */
    private List<String> loginPathPrefixes = new ArrayList<>(List.of(
            "/api/v1/auth/login"));

    /** Path prefixes that bypass rate limiting (case-sensitive prefix match). */
    private List<String> exemptPathPrefixes = new ArrayList<>(List.of(
            "/actuator", "/error", "/login", "/api/v1/auth"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getReadPerMinute() {
        return readPerMinute;
    }

    public void setReadPerMinute(int readPerMinute) {
        this.readPerMinute = readPerMinute;
    }

    public int getWritePerMinute() {
        return writePerMinute;
    }

    public void setWritePerMinute(int writePerMinute) {
        this.writePerMinute = writePerMinute;
    }

    public int getBurstMultiplier() {
        return burstMultiplier;
    }

    public void setBurstMultiplier(int burstMultiplier) {
        this.burstMultiplier = burstMultiplier;
    }

    public int getLoginPerMinute() {
        return loginPerMinute;
    }

    public void setLoginPerMinute(int loginPerMinute) {
        this.loginPerMinute = loginPerMinute;
    }

    public List<String> getLoginPathPrefixes() {
        return loginPathPrefixes;
    }

    public void setLoginPathPrefixes(List<String> loginPathPrefixes) {
        this.loginPathPrefixes = loginPathPrefixes;
    }

    public List<String> getExemptPathPrefixes() {
        return exemptPathPrefixes;
    }

    public void setExemptPathPrefixes(List<String> exemptPathPrefixes) {
        this.exemptPathPrefixes = exemptPathPrefixes;
    }
}
