package com.ems.collector.config;

import com.ems.collector.poller.DevicePoller;
import com.ems.collector.poller.ReadingSink;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 启用 {@link CollectorProperties} 的 ConfigurationProperties 绑定，
 * 并提供其他 Phase 还没替换的默认 Bean（noop sink / system clock / noop transition listener）。
 *
 * <p>Phase G 会注册自己的 InfluxDB-backed {@link ReadingSink}，覆盖此处默认。
 */
@Configuration
@EnableConfigurationProperties(CollectorProperties.class)
public class CollectorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ReadingSink defaultReadingSink() {
        return reading -> { /* noop until Phase G */ };
    }

    @Bean
    @ConditionalOnMissingBean
    public Clock collectorClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    public DevicePoller.StateTransitionListener defaultStateTransitionListener() {
        return DevicePoller.StateTransitionListener.NOOP;
    }
}
