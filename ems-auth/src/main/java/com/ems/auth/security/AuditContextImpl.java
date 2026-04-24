package com.ems.auth.security;

import com.ems.audit.aspect.AuditContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class AuditContextImpl implements AuditContext {

    @Override
    public Long currentUserId() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof AuthUser u) return u.getUserId();
        return null;
    }

    @Override
    public String currentUsername() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? null : a.getName();
    }

    @Override
    public String currentIp() {
        var req = currentRequest();
        return req == null ? null : req.getRemoteAddr();
    }

    @Override
    public String currentUserAgent() {
        var req = currentRequest();
        return req == null ? null : req.getHeader("User-Agent");
    }

    private jakarta.servlet.http.HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes s ? s.getRequest() : null;
    }
}
