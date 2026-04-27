package com.ems.collector.poller;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * 一次成功 polling 周期的所有 register 解码结果。
 *
 * @param deviceId       YAML 里的 device.id（仅 logging / metrics 标签用）
 * @param meterCode      meters 表的 code，下游用来查 measurement / tags
 * @param timestamp      polling 完成时刻（Instant.now()，写 InfluxDB 的 ts 用这个）
 * @param numericFields  {@link com.ems.collector.config.RegisterConfig#tsField} → BigDecimal
 *                       已经过 byte-order reorder + dataType decode + scale。
 * @param booleanFields  COIL / DISCRETE_INPUT 的解码结果，tsField → boolean
 */
public record DeviceReading(
        String deviceId,
        String meterCode,
        Instant timestamp,
        Map<String, BigDecimal> numericFields,
        Map<String, Boolean> booleanFields
) {}
