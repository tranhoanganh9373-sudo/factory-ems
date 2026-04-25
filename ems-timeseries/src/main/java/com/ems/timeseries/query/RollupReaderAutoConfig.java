package com.ems.timeseries.query;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RollupReaderAutoConfig {

    @Bean
    @ConditionalOnMissingBean(RollupReaderPort.class)
    public RollupReaderPort noopRollupReader() {
        return new NoopRollupReader();
    }
}
