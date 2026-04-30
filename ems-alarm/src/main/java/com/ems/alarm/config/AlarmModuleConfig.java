package com.ems.alarm.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
@EnableConfigurationProperties(AlarmProperties.class)
@EnableAsync
@EnableScheduling
public class AlarmModuleConfig {

    @Bean
    public Clock alarmClock() {
        return Clock.systemUTC();
    }

    @Bean(name = "webhookExecutor")
    public ThreadPoolTaskExecutor webhookExecutor() {
        ThreadPoolTaskExecutor t = new ThreadPoolTaskExecutor();
        t.setCorePoolSize(2);
        t.setMaxPoolSize(4);
        t.setQueueCapacity(100);
        t.setThreadNamePrefix("alarm-webhook-");
        t.initialize();
        return t;
    }

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService webhookRetryScheduler() {
        return Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "alarm-webhook-retry");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    public HttpClient webhookHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }
}
