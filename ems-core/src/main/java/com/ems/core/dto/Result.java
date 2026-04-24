package com.ems.core.dto;

public record Result<T>(int code, T data, String message, String traceId) {
    public static <T> Result<T> ok(T data) {
        return new Result<>(0, data, "ok", com.ems.core.util.TraceIdHolder.get());
    }
    public static <T> Result<T> ok() { return ok(null); }
    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, null, message, com.ems.core.util.TraceIdHolder.get());
    }
}
