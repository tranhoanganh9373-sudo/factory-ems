package com.ems.audit.aspect;

import com.ems.audit.annotation.Audited;
import com.ems.audit.event.AuditEvent;
import com.ems.audit.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Aspect
@Component
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);
    private static final SpelExpressionParser PARSER = new SpelExpressionParser();
    private static final DefaultParameterNameDiscoverer DISC = new DefaultParameterNameDiscoverer();

    private final AuditService auditService;
    private final AuditContext auditContext;
    private final ObjectMapper objectMapper;

    public AuditAspect(AuditService as, AuditContext ctx, ObjectMapper om) {
        this.auditService = as; this.auditContext = ctx; this.objectMapper = om;
    }

    @Around("@annotation(audited)")
    public Object around(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        Object result = pjp.proceed();
        try {
            MethodSignature sig = (MethodSignature) pjp.getSignature();
            var ctx = new MethodBasedEvaluationContext(pjp.getTarget(), sig.getMethod(), pjp.getArgs(), DISC);
            ctx.setVariable("result", result);
            String resId = eval(audited.resourceIdExpr(), ctx);
            String summary = eval(audited.summaryExpr(), ctx);
            String detail = objectMapper.writeValueAsString(pjp.getArgs());
            AuditEvent ev = new AuditEvent(
                auditContext.currentUserId(),
                auditContext.currentUsername(),
                audited.action(),
                audited.resourceType(),
                resId,
                summary,
                detail,
                auditContext.currentIp(),
                auditContext.currentUserAgent(),
                OffsetDateTime.now()
            );
            auditService.record(ev);
        } catch (Exception e) {
            log.error("audit_aspect_failed method={}", pjp.getSignature().toShortString(), e);
        }
        return result;
    }

    private String eval(String expr, MethodBasedEvaluationContext ctx) {
        if (expr == null || expr.isBlank()) return null;
        try {
            Object v = PARSER.parseExpression(expr).getValue(ctx);
            return v == null ? null : v.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
