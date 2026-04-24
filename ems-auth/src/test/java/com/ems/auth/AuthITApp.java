package com.ems.auth;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {
    "com.ems.core", "com.ems.audit", "com.ems.orgtree", "com.ems.auth"
})
@EntityScan(basePackages = "com.ems")
@EnableJpaRepositories(basePackages = "com.ems")
public class AuthITApp {}
