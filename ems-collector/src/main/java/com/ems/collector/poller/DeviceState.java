package com.ems.collector.poller;

/**
 * 单设备运行时状态。状态机：
 * <pre>
 *   HEALTHY ──cycleFailed──> DEGRADED ──N×backoff cycles failed──> UNREACHABLE
 *      ^                        |                                       |
 *      └─────success any time───┴───────────────────────────────────────┘
 * </pre>
 *
 * <ul>
 *   <li>HEALTHY: 正常 polling，按 {@code pollingIntervalMs}</li>
 *   <li>DEGRADED: 上一周期重试用尽仍失败；切到 {@code backoffMs} 周期</li>
 *   <li>UNREACHABLE: 持续在 backoff 周期下也读不通；停止常规 polling，
 *       只按固定 30s 周期尝试单次重连读</li>
 * </ul>
 */
public enum DeviceState {
    HEALTHY,
    DEGRADED,
    UNREACHABLE
}
