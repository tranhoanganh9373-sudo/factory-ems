package com.ems.audit.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {
    String action();
    String resourceType();
    /** SpEL expression to extract resourceId from method args or result (e.g. "#id" or "#result.id"). */
    String resourceIdExpr() default "";
    /** Optional SpEL expression to build summary. */
    String summaryExpr() default "";
}
