package com.ems.core.constant;

public final class ErrorCode {
    private ErrorCode() {}
    public static final int OK = 0;
    public static final int PARAM_INVALID    = 400;
    public static final int UNAUTHORIZED     = 40001;
    public static final int FORBIDDEN        = 40003;
    public static final int NOT_FOUND        = 40004;
    public static final int CONFLICT         = 40009;
    public static final int BIZ_GENERIC      = 40000;
    public static final int INTERNAL_ERROR   = 50000;
}
