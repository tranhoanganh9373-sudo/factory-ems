package com.ems.app.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import static org.assertj.core.api.Assertions.assertThat;

class TraceIdFilterTest {

    @Test
    void shouldGenerateTraceIdIfNotPresent() throws ServletException, IOException {
        var req = new MockHttpServletRequest();
        var res = new MockHttpServletResponse();
        FilterChain chain = (r, s) -> {
            assertThat(MDC.get("traceId")).isNotBlank();
            ((jakarta.servlet.http.HttpServletResponse) s).setHeader("X-Trace-Id", MDC.get("traceId"));
        };
        new TraceIdFilter().doFilter(req, res, chain);
        assertThat(res.getHeader("X-Trace-Id")).isNotBlank();
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    void shouldUseIncomingTraceId() throws ServletException, IOException {
        var req = new MockHttpServletRequest();
        req.addHeader("X-Trace-Id", "abc123");
        var res = new MockHttpServletResponse();
        FilterChain chain = (r, s) -> assertThat(MDC.get("traceId")).isEqualTo("abc123");
        new TraceIdFilter().doFilter(req, res, chain);
    }
}
