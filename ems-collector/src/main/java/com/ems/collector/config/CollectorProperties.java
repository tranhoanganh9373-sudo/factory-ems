package com.ems.collector.config;

import jakarta.validation.Valid;
import org.springframework.boot.context.properties.ConfigurationProperties;
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
        @Valid List<DeviceConfig> devices
) {
    public CollectorProperties {
        if (devices == null) devices = List.of();
    }
}
