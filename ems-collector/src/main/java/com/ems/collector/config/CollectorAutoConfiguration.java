package com.ems.collector.config;

import com.ems.collector.buffer.BufferStore;
import com.ems.collector.buffer.SqliteBufferStore;
import com.ems.collector.poller.DevicePoller;
import com.ems.collector.poller.ReadingSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    /** 默认 BufferStore = SQLite。无条件创建：collector enabled=false 时也持有一个空 SQLite，
     *  让 InfluxReadingSink 等下游组件能稳定 wire（cheap：只开个文件不写）。
     *  测试可注入自定义 BufferStore（@Primary）替换。 */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public BufferStore defaultBufferStore(CollectorProperties props, ObjectMapper mapper) {
        return new SqliteBufferStore(props.buffer(), mapper);
    }
}
