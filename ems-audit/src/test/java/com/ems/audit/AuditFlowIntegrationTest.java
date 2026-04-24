package com.ems.audit;

import com.ems.audit.event.AuditEvent;
import com.ems.audit.listener.AsyncAuditListener;
import com.ems.audit.repository.AuditLogRepository;
import com.ems.audit.service.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = AuditFlowIntegrationTest.TestApp.class)
@Testcontainers
@ActiveProfiles("test")
class AuditFlowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired AuditService service;
    @Autowired AuditLogRepository repo;
    @Autowired AsyncAuditListener listener;

    @Test
    void shouldPersistAuditEvent() {
        AuditEvent ev = new AuditEvent(1L, "admin", "LOGIN", "AUTH", "admin",
            "logged in", "{}", "127.0.0.1", "junit", OffsetDateTime.now());
        service.record(ev);

        await().atMost(10, TimeUnit.SECONDS).pollInterval(100, TimeUnit.MILLISECONDS).untilAsserted(() ->
            assertThat(repo.findAll())
                .hasSize(1)
                .first()
                .satisfies(a -> {
                    assertThat(a.getAction()).isEqualTo("LOGIN");
                    assertThat(a.getResourceType()).isEqualTo("AUTH");
                    assertThat(a.getActorUsername()).isEqualTo("admin");
                })
        );
    }

    @Test
    void shouldPersistLoginEvent() {
        AuditEvent ev = new AuditEvent(2L, "zhang3", "LOGIN", "AUTH", "zhang3",
            "登录成功", null, "10.0.0.1", "curl", OffsetDateTime.now());
        service.record(ev);

        await().atMost(10, TimeUnit.SECONDS).pollInterval(100, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            var all = repo.findAll();
            assertThat(all).anyMatch(a -> "LOGIN".equals(a.getAction())
                && "zhang3".equals(a.getActorUsername()));
        });
    }

    @Configuration
    @EnableAutoConfiguration
    @EnableAsync
    @EnableTransactionManagement
    @ComponentScan(basePackages = "com.ems.audit")
    @EntityScan(basePackages = "com.ems.audit.entity")
    @EnableJpaRepositories(basePackages = "com.ems.audit.repository")
    static class TestApp {

        @Bean com.ems.audit.aspect.AuditContext testCtx() {
            return new com.ems.audit.aspect.AuditContext() {
                public Long currentUserId() { return 1L; }
                public String currentUsername() { return "admin"; }
                public String currentIp() { return "127.0.0.1"; }
                public String currentUserAgent() { return "junit"; }
            };
        }

        @Bean(name = "auditExecutor")
        Executor auditExecutor() {
            ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
            ex.setCorePoolSize(1);
            ex.setMaxPoolSize(2);
            ex.setQueueCapacity(100);
            ex.setThreadNamePrefix("audit-it-");
            ex.setTaskDecorator(noopDecorator());
            ex.initialize();
            return ex;
        }

        private TaskDecorator noopDecorator() {
            return r -> r;
        }
    }
}
