package com.ems.audit.aspect;

import com.ems.audit.annotation.Audited;
import com.ems.audit.event.AuditEvent;
import com.ems.audit.service.AuditService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

@Aspect
@Component
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);
    private static final SpelExpressionParser PARSER = new SpelExpressionParser();
    private static final DefaultParameterNameDiscoverer DISC = new DefaultParameterNameDiscoverer();
    /** Field-name substrings (lowercased) whose values are masked in the audit detail JSON. */
    private static final Set<String> SENSITIVE_KEY_FRAGMENTS = Set.of(
        "password", "passwordhash", "passwd", "pwd", "secret", "token", "credential", "apikey");
    private static final String MASK = "***";

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
            JsonNode argsNode = objectMapper.valueToTree(pjp.getArgs());
            maskSensitive(argsNode);
            String detail = objectMapper.writeValueAsString(argsNode);
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

    private void maskSensitive(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) return;
        if (node.isArray()) {
            for (JsonNode child : node) maskSensitive(child);
            return;
        }
        if (node instanceof ObjectNode obj) {
            Iterator<String> it = obj.fieldNames();
            while (it.hasNext()) {
                String name = it.next();
                String lower = name.toLowerCase(Locale.ROOT);
                boolean sensitive = false;
                for (String frag : SENSITIVE_KEY_FRAGMENTS) {
                    if (lower.contains(frag)) { sensitive = true; break; }
                }
                if (sensitive) {
                    obj.put(name, MASK);
                } else {
                    maskSensitive(obj.get(name));
                }
            }
        }
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
