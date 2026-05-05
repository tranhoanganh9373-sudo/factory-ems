package com.ems.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.ems.core.config.PvFeatureProperties;

@SpringBootApplication(scanBasePackages = "com.ems")
@EntityScan(basePackages = "com.ems")
@EnableJpaRepositories(basePackages = "com.ems")
@EnableAsync
@EnableScheduling
@EnableTransactionManagement
@EnableConfigurationProperties(PvFeatureProperties.class)
public class FactoryEmsApplication {
    public static void main(String[] args) {
        SpringApplication.run(FactoryEmsApplication.class, args);
    }
}
