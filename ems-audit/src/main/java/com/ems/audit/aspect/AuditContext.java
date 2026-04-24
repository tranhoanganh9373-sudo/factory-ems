package com.ems.audit.aspect;

/**
 * Minimal interface to avoid ems-audit depending on ems-auth.
 * Actual implementation provided by ems-auth module.
 */
public interface AuditContext {
    Long currentUserId();
    String currentUsername();
    String currentIp();
    String currentUserAgent();
}
