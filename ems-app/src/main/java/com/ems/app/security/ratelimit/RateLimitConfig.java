package com.ems.app.security.ratelimit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Wires the {@link RateLimitFilter} into the servlet filter chain.
 *
 * <p>Registered with high precedence so over-limit clients are rejected before
 * authentication runs. {@link com.ems.app.filter.TraceIdFilter} uses
 * {@code @Order(1)}, so this filter sits at HIGHEST_PRECEDENCE+10 (far below
 * order 1) — the trace filter wraps the rate-limit response with a trace id.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    public RateLimitFilter rateLimitFilter(RateLimitProperties props) {
        return new RateLimitFilter(props);
    }
}
