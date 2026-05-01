package com.ems.collector.transport;

import java.time.Instant;
import java.util.Map;

/**
 * 采集到的单条样本。
 *
 * <p>跨协议通用：Modbus / OPC UA / MQTT / VIRTUAL 全都向 {@link SampleSink} 推送 Sample。
 *
 * @param channelId 来源 channel 主键
 * @param pointKey  config 中点位的 key（同 channel 内唯一）
 * @param timestamp 采样时间
 * @param value     解码后的值（Number / Boolean / String / null）
 * @param quality   GOOD / UNCERTAIN / BAD
 * @param tags      附加元数据：MQTT topic / OPC UA subscriptionId / error 等
 */
public record Sample(
    Long channelId,
    String pointKey,
    Instant timestamp,
    Object value,
    Quality quality,
    Map<String, String> tags
) {}
