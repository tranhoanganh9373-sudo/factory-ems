package com.ems.collector.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.Range;

import java.math.BigDecimal;

/**
 * 单个寄存器的解码描述。一台 device 通常有 5–30 个 register（电压 A/B/C、电流 A/B/C、有功、无功、电度...）。
 *
 * <p>约束（除注解外，DeviceConfig 装载时还要做跨字段校验）：
 * <ul>
 *   <li>count 必须等于 dataType.wordCount()，否则 RegisterDecoder 无法对齐字节</li>
 *   <li>byteOrder 对 UINT16/INT16/BIT 无意义但仍接受（统一默认 ABCD）</li>
 *   <li>tsField 命名要符合 InfluxDB field key 习惯：字母数字 + 下划线</li>
 * </ul>
 *
 * @param name        逻辑名称（YAML 内可读，仅 logging 用）
 * @param address     起始寄存器地址（基于 0；YAML 可写 0x2000）
 * @param count       占用 16-bit 寄存器数量；BIT 类型可以填 1
 * @param function    Modbus 功能码族
 * @param dataType    解码后类型（驱动 wordCount + decode 算法）
 * @param byteOrder   多字节拼装顺序，默认 ABCD
 * @param scale       缩放系数（原始整数 × scale = 工程单位值），默认 1
 * @param unit        工程单位（V / kW / kWh / Hz...），仅 logging 用
 * @param tsField     写到 InfluxDB 时的 field key
 * @param kind        语义（GAUGE / ACCUMULATOR / COUNTER），驱动 wrap-around 处理
 */
public record RegisterConfig(
        @NotBlank String name,
        @Min(0) int address,
        @Min(1) @Range(max = 4) int count,
        @NotNull FunctionType function,
        @NotNull DataType dataType,
        ByteOrder byteOrder,
        BigDecimal scale,
        String unit,
        @NotBlank @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]*",
                message = "tsField must start with letter/underscore and contain only [A-Za-z0-9_]") String tsField,
        RegisterKind kind
) {
    public RegisterConfig {
        if (byteOrder == null) byteOrder = ByteOrder.ABCD;
        if (scale == null) scale = BigDecimal.ONE;
        if (kind == null) kind = RegisterKind.GAUGE;
    }
}
