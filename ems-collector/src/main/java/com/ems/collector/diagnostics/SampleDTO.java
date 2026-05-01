package com.ems.collector.diagnostics;

import java.util.Map;

/**
 * 暴露给前端的样本视图。
 *
 * <p>独立于 collector 内部 {@link com.ems.collector.transport.Sample} 记录：
 * 仅暴露 UI 需要的字段，且把 {@code Instant} 序列化为 ISO-8601 字符串方便前端 dayjs 解析。
 *
 * @param pointKey  点位 key（同 channel 内唯一）
 * @param timestamp ISO-8601 字符串
 * @param value     Number / Boolean / String / null
 * @param quality   GOOD / UNCERTAIN / BAD
 * @param tags      附加元数据（topic / latency / error 等）
 */
public record SampleDTO(
    String pointKey,
    String timestamp,
    Object value,
    String quality,
    Map<String, String> tags
) {}
