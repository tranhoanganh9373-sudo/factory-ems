package com.ems.collector.config;

import com.ems.collector.buffer.BufferProperties;
import jakarta.validation.Valid;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Collector 模块根配置。映射 YAML：
 *
 * <pre>{@code
 * ems:
 *   collector:
 *     enabled: true
 *     devices: [...]
 * }</pre>
 *
 * <p>{@code @Validated} 让 Spring Boot 启动期对所有 device + register 跑 JSR-303 注解校验，
 * 任一字段不合规即 fail-fast；进一步的跨字段校验由 {@link CollectorPropertiesValidator}
 * 完成（如 protocol/host 一致性、register count vs dataType wordCount）。
 *
 * <p>{@code enabled=false} 时下游 {@code CollectorService} 应跳过 polling 启动，
 * 但 properties 本身仍解析（便于 config 完整性巡检）。
 */
@ConfigurationProperties(prefix = "ems.collector")
@Validated
public record CollectorProperties(
        boolean enabled,
        @Valid List<DeviceConfig> devices,
        BufferProperties buffer
) {
    // Record 有多个构造器（canonical + convenience），Spring Boot 不会自动选；这里把
    // canonical 标为 @ConstructorBinding，让 ConfigurationProperties binder 走 record
    // 构造器路径而不是回退到 JavaBean (会因没 default ctor 失败)。
    @ConstructorBinding
    public CollectorProperties {
        if (devices == null) devices = List.of();
        if (buffer == null) buffer = new BufferProperties(null, null, null, null);
    }

    /** Convenience ctor — buffer 用默认值。测试与旧调用方用此签名。 */
    public CollectorProperties(boolean enabled, List<DeviceConfig> devices) {
        this(enabled, devices, null);
    }
}
