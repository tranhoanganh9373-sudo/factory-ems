package com.ems.collector.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.Range;

import java.util.List;

/**
 * 单台 Modbus 设备配置。一个 device 一个 ModbusMaster + 一个 DevicePoller + 一组 register。
 *
 * <p>跨字段约束（注解无法表达，由 {@link CollectorPropertiesValidator} 启动校验）：
 * <ul>
 *   <li>protocol == TCP → host + port + unitId 必填</li>
 *   <li>protocol == RTU → serialPort + baudRate + parity 必填（Plan 1.5.1 不支持 RTU，启动时拒绝）</li>
 *   <li>每个 register 的 dataType.wordCount() 必须等于 register.count</li>
 *   <li>register.tsField 在同一 device 内唯一</li>
 *   <li>backoffMs ≥ pollingIntervalMs（降频不能比正常更频繁）</li>
 *   <li>timeoutMs ≤ pollingIntervalMs</li>
 *   <li>meterCode 必须能在 meters 表查到（由 {@link MeterCodeValidator} 在 Application Ready 时校验）</li>
 * </ul>
 *
 * @param id                 device 逻辑 id（YAML 内 / 日志 / metrics tag），不必等于 meterCode
 * @param meterCode          meters 表的 code，用于反查 measurement / tags
 * @param protocol           TCP / RTU
 * @param host               TCP only — IP 或 hostname
 * @param port               TCP only — 端口（默认 502）
 * @param serialPort         RTU only — /dev/ttyUSB0 / COM3
 * @param baudRate           RTU only — 波特率
 * @param dataBits           RTU only — 数据位（默认 8）
 * @param stopBits           RTU only — 停止位（默认 1）
 * @param parity             RTU only — 校验
 * @param unitId             Modbus slave id (1..247)
 * @param pollingIntervalMs  正常 polling 周期（≥ 1000ms 防止误配把仪表打挂）
 * @param timeoutMs          单次 read 超时
 * @param retries            连续重试次数（默认 3）
 * @param backoffMs          DEGRADED 状态下降频后的周期
 * @param maxBufferSize      内存 buffer 上限（点数）
 * @param registers          至少 1 个寄存器
 */
public record DeviceConfig(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]+") String id,
        @NotBlank String meterCode,
        @NotNull Protocol protocol,
        // TCP fields
        String host,
        Integer port,
        // RTU fields
        String serialPort,
        Integer baudRate,
        Integer dataBits,
        Integer stopBits,
        Parity parity,
        // Common
        @NotNull @Range(min = 1, max = 247) Integer unitId,
        @NotNull @Min(1000) @Max(3_600_000) Integer pollingIntervalMs,
        @NotNull @Min(100) @Max(60_000) Integer timeoutMs,
        @NotNull @Min(0) @Max(10) Integer retries,
        @NotNull @Min(1000) @Max(3_600_000) Integer backoffMs,
        @NotNull @Min(100) @Max(1_000_000) Integer maxBufferSize,
        @NotEmpty @Valid List<RegisterConfig> registers
) {
    public DeviceConfig {
        if (port == null) port = 502;
        if (dataBits == null) dataBits = 8;
        if (stopBits == null) stopBits = 1;
        if (parity == null) parity = Parity.NONE;
        if (retries == null) retries = 3;
        if (backoffMs == null && pollingIntervalMs != null) backoffMs = pollingIntervalMs * 5;
        if (maxBufferSize == null) maxBufferSize = 10_000;
    }
}
