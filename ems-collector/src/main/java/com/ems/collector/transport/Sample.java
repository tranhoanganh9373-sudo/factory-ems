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
) {
    /**
     * tag key — 标识 BAD 样本的失败类别。让 {@code ChannelService} 在做 cycle 聚合时区分：
     * 传输层失败（IO/超时/socket reset）影响 channel.connState；
     * 单点解码失败（字节长度不匹配、unsupported dataType 等）只影响该点 Quality，不污染整通道。
     */
    public static final String TAG_ERROR_KIND = "errorKind";

    /** 传输层级 IO 失败（ModbusIoException、socket reset、超时）— 计入 cycle failure。 */
    public static final String ERROR_KIND_IO = "io";

    /** 单点解码失败（字节长度、dataType 校验等）— 仅影响该点 quality，不计 cycle failure。 */
    public static final String ERROR_KIND_DECODE = "decode";
}
