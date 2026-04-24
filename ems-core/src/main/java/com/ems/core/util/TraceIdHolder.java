package com.ems.core.util;

import org.slf4j.MDC;

public final class TraceIdHolder {
    public static final String KEY = "traceId";
    private TraceIdHolder() {}
    public static String get() {
        String v = MDC.get(KEY);
        return v == null ? "-" : v;
    }
    public static void set(String v) { MDC.put(KEY, v); }
    public static void clear() { MDC.remove(KEY); }
}
