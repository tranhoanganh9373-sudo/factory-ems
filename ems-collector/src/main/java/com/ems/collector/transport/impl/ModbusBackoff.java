package com.ems.collector.transport.impl;

/**
 * Modbus 重连退避公式（共用于 {@link ModbusTcpAdapterTransport} 与
 * {@link ModbusRtuAdapterTransport}）。
 *
 * <p>序列：1s → 2s → 4s → 8s → 16s → 32s → 60s（cap）。
 * 公式：{@code Math.min(60_000L, 1000L << Math.min(attempts, 6))}.
 *
 * <p>无状态、纯函数；不可实例化。
 */
final class ModbusBackoff {

    static final long MAX_DELAY_MS = 60_000L;
    static final long BASE_DELAY_MS = 1_000L;
    static final int SHIFT_CAP = 6;

    private ModbusBackoff() {
    }

    /**
     * @param attempts 已失败次数（首次失败后调 {@code nextDelayMs(0)} 取首段 1s）
     * @return 下次重试前应睡眠的毫秒数（≥ 1000，≤ 60000）
     */
    static long nextDelayMs(int attempts) {
        int safe = Math.max(0, attempts);
        int shift = Math.min(safe, SHIFT_CAP);
        return Math.min(MAX_DELAY_MS, BASE_DELAY_MS << shift);
    }
}
