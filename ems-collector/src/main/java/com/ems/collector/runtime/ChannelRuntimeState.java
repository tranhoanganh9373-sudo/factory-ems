package com.ems.collector.runtime;

import java.time.Instant;
import java.util.Map;

/**
 * Channel 运行时快照（不含历史细节，由 {@link ChannelStateRegistry} 实时构造）。
 *
 * <p>UI / Prometheus 通过它读取连接状态、24h 累计、平均延迟等。
 *
 * @param channelId        来源 channel 主键
 * @param protocol         如 "MODBUS_TCP" / "OPC_UA" / "MQTT" / "VIRTUAL"
 * @param connState        CONNECTING / CONNECTED / DISCONNECTED / ERROR
 * @param lastConnectAt    上次发起连接时间
 * @param lastSuccessAt    上次成功采集时间
 * @param lastFailureAt    上次失败时间
 * @param lastErrorMessage 上次错误信息（截断到 200 字符）
 * @param successCount24h  24 小时滑动窗口内成功次数
 * @param failureCount24h  24 小时滑动窗口内失败次数
 * @param avgLatencyMs     最近 100 次延迟平均
 * @param protocolMeta     协议特定元数据（OPC UA: subscriptionId; MQTT: brokerVersion）
 */
public record ChannelRuntimeState(
    Long channelId,
    String protocol,
    ConnectionState connState,
    Instant lastConnectAt,
    Instant lastSuccessAt,
    Instant lastFailureAt,
    String lastErrorMessage,
    long successCount24h,
    long failureCount24h,
    long avgLatencyMs,
    Map<String, Object> protocolMeta
) {}
