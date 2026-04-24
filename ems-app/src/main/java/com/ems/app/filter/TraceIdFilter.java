package com.ems.app.filter;

import com.ems.core.util.TraceIdHolder;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(1)
public class TraceIdFilter implements Filter {

    public static final String HEADER = "X-Trace-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req  = (HttpServletRequest)  request;
        HttpServletResponse res = (HttpServletResponse) response;
        String traceId = req.getHeader(HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        TraceIdHolder.set(traceId);
        res.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            TraceIdHolder.clear();
        }
    }
}
