package com.ems.collector.transport;

/**
 * 同步连接测试结果。前端 [测试连接] 按钮使用。
 *
 * @param success    成功标志
 * @param message    OK / 错误信息
 * @param latencyMs  连接耗时（成功时填，失败时 null）
 */
public record TestResult(boolean success, String message, Long latencyMs) {

    public static TestResult ok(long latencyMs) {
        return new TestResult(true, "OK", latencyMs);
    }

    public static TestResult fail(String msg) {
        return new TestResult(false, msg, null);
    }
}
