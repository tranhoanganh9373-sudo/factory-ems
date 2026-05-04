package com.ems.app.security.ratelimit;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private static final String IP = "10.0.0.1";

    @Test
    void underLimitGet_passesThrough() throws ServletException, IOException {
        RateLimitProperties props = props(p -> p.setReadPerMinute(10));
        RateLimitFilter filter = new RateLimitFilter(props);

        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse res = sendGet(filter, IP, "/api/v1/things");
            assertThat(res.getStatus()).isNotEqualTo(429);
        }
    }

    @Test
    void overLimitGet_returns429WithRetryAfter() throws ServletException, IOException {
        RateLimitProperties props = props(p -> {
            p.setReadPerMinute(10);
            p.setBurstMultiplier(1);
        });
        RateLimitFilter filter = new RateLimitFilter(props);

        MockHttpServletResponse last = null;
        for (int i = 0; i < 11; i++) {
            last = sendGet(filter, IP, "/api/v1/things");
        }

        assertThat(last).isNotNull();
        assertThat(last.getStatus()).isEqualTo(429);
        String retryAfter = last.getHeader("Retry-After");
        assertThat(retryAfter).isNotNull();
        assertThat(Integer.parseInt(retryAfter)).isPositive();
    }

    @Test
    void overLimitGet_doesNotInvokeChain() throws ServletException, IOException {
        RateLimitProperties props = props(p -> {
            p.setReadPerMinute(2);
            p.setBurstMultiplier(1);
        });
        RateLimitFilter filter = new RateLimitFilter(props);

        // Burn the bucket
        sendGet(filter, IP, "/api/v1/things");
        sendGet(filter, IP, "/api/v1/things");

        // Third should NOT invoke chain
        MockHttpServletRequest req = newReq("GET", "/api/v1/things", IP);
        MockHttpServletResponse res = new MockHttpServletResponse();
        ChainSpy chain = new ChainSpy();
        filter.doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(chain.invoked).isFalse();
    }

    @Test
    void readAndWrite_useSeparateBuckets() throws ServletException, IOException {
        RateLimitProperties props = props(p -> {
            p.setReadPerMinute(100);
            p.setWritePerMinute(2);
            p.setBurstMultiplier(1);
        });
        RateLimitFilter filter = new RateLimitFilter(props);

        // Two writes succeed
        assertThat(sendPost(filter, IP, "/api/v1/things").getStatus()).isNotEqualTo(429);
        assertThat(sendPost(filter, IP, "/api/v1/things").getStatus()).isNotEqualTo(429);
        // Third write blocked
        assertThat(sendPost(filter, IP, "/api/v1/things").getStatus()).isEqualTo(429);

        // GETs unaffected
        for (int i = 0; i < 50; i++) {
            assertThat(sendGet(filter, IP, "/api/v1/things").getStatus()).isNotEqualTo(429);
        }
    }

    @Test
    void exemptPaths_bypassRateLimiting() throws ServletException, IOException {
        RateLimitProperties props = props(p -> {
            p.setReadPerMinute(2);
            p.setWritePerMinute(2);
            p.setBurstMultiplier(1);
        });
        RateLimitFilter filter = new RateLimitFilter(props);

        for (int i = 0; i < 100; i++) {
            assertThat(sendGet(filter, IP, "/actuator/health").getStatus()).isNotEqualTo(429);
            assertThat(sendGet(filter, IP, "/login").getStatus()).isNotEqualTo(429);
            // /api/v1/auth/* is exempt EXCEPT /api/v1/auth/login (covered by login bucket).
            assertThat(sendPost(filter, IP, "/api/v1/auth/refresh").getStatus()).isNotEqualTo(429);
            assertThat(sendGet(filter, IP, "/error").getStatus()).isNotEqualTo(429);
        }
    }

    @Test
    void loginPath_isRateLimitedEvenThoughAuthPrefixIsExempt() throws ServletException, IOException {
        // RealityCheck 2026-05-02: 30 successive POSTs to /api/v1/auth/login were all 200
        // because the entire /api/v1/auth prefix was exempt. The login bucket must take
        // precedence over the exempt list and enforce the configured per-minute cap.
        RateLimitProperties props = props(p -> {
            p.setLoginPerMinute(5);
            p.setBurstMultiplier(1);
        });
        RateLimitFilter filter = new RateLimitFilter(props);

        for (int i = 0; i < 5; i++) {
            assertThat(sendPost(filter, IP, "/api/v1/auth/login").getStatus()).isNotEqualTo(429);
        }
        MockHttpServletResponse blocked = sendPost(filter, IP, "/api/v1/auth/login");
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isNotNull();
    }

    @Test
    void loginBucket_isSeparateFromWriteBucket() throws ServletException, IOException {
        // Saturating the login bucket must NOT block other writes from the same IP.
        RateLimitProperties props = props(p -> {
            p.setLoginPerMinute(2);
            p.setWritePerMinute(20);
            p.setBurstMultiplier(1);
        });
        RateLimitFilter filter = new RateLimitFilter(props);

        sendPost(filter, IP, "/api/v1/auth/login");
        sendPost(filter, IP, "/api/v1/auth/login");
        assertThat(sendPost(filter, IP, "/api/v1/auth/login").getStatus()).isEqualTo(429);

        assertThat(sendPost(filter, IP, "/api/v1/things").getStatus()).isNotEqualTo(429);
    }

    @Test
    void xForwardedFor_firstSegmentIsBucketKey() throws ServletException, IOException {
        RateLimitProperties props = props(p -> {
            p.setWritePerMinute(1);
            p.setBurstMultiplier(1);
        });
        RateLimitFilter filter = new RateLimitFilter(props);

        // Both come from same remoteAddr but different XFF — should be different buckets
        MockHttpServletResponse a = sendPostWithXff(filter, "192.168.1.10", "1.2.3.4", "/api/v1/things");
        MockHttpServletResponse b = sendPostWithXff(filter, "192.168.1.10", "5.6.7.8", "/api/v1/things");

        assertThat(a.getStatus()).isNotEqualTo(429);
        assertThat(b.getStatus()).isNotEqualTo(429);

        // The same XFF a second time hits the limit
        MockHttpServletResponse aAgain = sendPostWithXff(filter, "192.168.1.10", "1.2.3.4", "/api/v1/things");
        assertThat(aAgain.getStatus()).isEqualTo(429);
    }

    @Test
    void disabledFlag_shortCircuits() throws ServletException, IOException {
        RateLimitProperties props = props(p -> {
            p.setEnabled(false);
            p.setReadPerMinute(1);
            p.setWritePerMinute(1);
            p.setBurstMultiplier(1);
        });
        RateLimitFilter filter = new RateLimitFilter(props);

        for (int i = 0; i < 1000; i++) {
            assertThat(sendGet(filter, IP, "/api/v1/things").getStatus()).isNotEqualTo(429);
        }
    }

    /* ── helpers ───────────────────────────────────────────────────── */

    private static RateLimitProperties props(java.util.function.Consumer<RateLimitProperties> mutator) {
        RateLimitProperties p = new RateLimitProperties();
        p.setExemptPathPrefixes(List.of("/actuator", "/error", "/login", "/api/v1/auth"));
        mutator.accept(p);
        return p;
    }

    private static MockHttpServletRequest newReq(String method, String uri, String remoteAddr) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setRequestURI(uri);
        req.setRemoteAddr(remoteAddr);
        return req;
    }

    private static MockHttpServletResponse sendGet(RateLimitFilter filter, String ip, String uri)
            throws ServletException, IOException {
        MockHttpServletRequest req = newReq("GET", uri, ip);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }

    private static MockHttpServletResponse sendPost(RateLimitFilter filter, String ip, String uri)
            throws ServletException, IOException {
        MockHttpServletRequest req = newReq("POST", uri, ip);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }

    private static MockHttpServletResponse sendPostWithXff(RateLimitFilter filter,
                                                           String remoteAddr,
                                                           String xff,
                                                           String uri) throws ServletException, IOException {
        MockHttpServletRequest req = newReq("POST", uri, remoteAddr);
        req.addHeader("X-Forwarded-For", xff);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }

    private static class ChainSpy extends MockFilterChain {
        boolean invoked;
        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                             jakarta.servlet.ServletResponse response)
                throws IOException, ServletException {
            invoked = true;
            super.doFilter(request, response);
        }
    }
}
