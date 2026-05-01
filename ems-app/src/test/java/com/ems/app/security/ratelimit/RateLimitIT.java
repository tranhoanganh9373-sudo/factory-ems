package com.ems.app.security.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * End-to-end MockMvc integration test exercising the rate-limit filter.
 *
 * <p>Boots a focused Spring context with the real {@link RateLimitFilter} bean
 * (registered through {@link RateLimitConfig}) plus a probe controller; no DB,
 * no Flyway, no Testcontainers. The filter is wired into the MockMvc chain
 * via {@code addFilters(...)} so the full per-IP token-bucket logic runs
 * against real HTTP semantics.
 *
 * <p>Test profile sets {@code read-per-minute=5} and {@code burst-multiplier=1};
 * the 6th GET to {@code /api/v1/probe} must return 429 with a {@code Retry-After}
 * header.
 */
@SpringBootTest(classes = {
        RateLimitConfig.class,
        RateLimitIT.ProbeController.class
})
@TestPropertySource(properties = {
        "ems.security.rate-limit.read-per-minute=5",
        "ems.security.rate-limit.burst-multiplier=1",
        // Trim the default exempt list so /api/v1/probe is rate-limited.
        "ems.security.rate-limit.exempt-path-prefixes[0]=/actuator",
        "ems.security.rate-limit.exempt-path-prefixes[1]=/error",
        "ems.security.rate-limit.exempt-path-prefixes[2]=/login"
})
class RateLimitIT {

    @Autowired RateLimitFilter rateLimitFilter;
    @Autowired ProbeController probeController;

    @Test
    void sixthRequest_isRateLimited() throws Exception {
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(probeController)
                .addFilters(rateLimitFilter)
                .build();

        // 5 requests fit in the bucket; all should pass through to the controller.
        for (int i = 0; i < 5; i++) {
            MvcResult ok = mvc.perform(get("/api/v1/probe").with(req -> {
                req.setRemoteAddr("192.0.2.42");
                return req;
            })).andReturn();
            assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        }

        // 6th must be 429 from the rate limiter.
        MvcResult result = mvc.perform(get("/api/v1/probe").with(req -> {
            req.setRemoteAddr("192.0.2.42");
            return req;
        })).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(429);
        String retryAfter = result.getResponse().getHeader("Retry-After");
        assertThat(retryAfter).isNotNull();
        assertThat(Integer.parseInt(retryAfter)).isPositive();
        assertThat(result.getResponse().getContentType()).contains("application/json");
        assertThat(result.getResponse().getContentAsString())
                .contains("\"success\":false")
                .contains("Rate limit exceeded");
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/v1/probe")
        String probe() {
            return "ok";
        }
    }
}
