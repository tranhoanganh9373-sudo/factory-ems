package com.ems.alarm.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(AlarmProperties.class)
@EnableAsync
@EnableScheduling
public class AlarmModuleConfig {
    @Bean
    public Clock alarmClock() {
        return Clock.systemUTC();
    }
}
