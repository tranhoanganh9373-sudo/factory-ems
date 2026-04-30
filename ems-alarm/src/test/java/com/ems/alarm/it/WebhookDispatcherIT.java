package com.ems.alarm.it;

import com.ems.alarm.config.AlarmModuleConfig;
import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.AlarmType;
import com.ems.alarm.entity.DeliveryStatus;
import com.ems.alarm.entity.WebhookConfig;
import com.ems.alarm.repository.AlarmRepository;
import com.ems.alarm.repository.WebhookConfigRepository;
import com.ems.alarm.repository.WebhookDeliveryLogRepository;
import com.ems.alarm.service.InAppChannel;
import com.ems.alarm.service.WebhookChannel;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Webhook 派发集成测试（MockWebServer + Testcontainers）。
 * 本机 Docker Desktop 兼容问题，CI 中删除 {@code @Disabled} 即可启用。
 */
@Disabled("Testcontainers + Docker Desktop 本地兼容问题，CI 环境删除此注解即可")
@SpringBootTest(classes = WebhookDispatcherIT.TestApp.class)
@Testcontainers
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // 缩短重试退避以加快测试
        "ems.alarm.webhook-retry-backoff-seconds[0]=1",
        "ems.alarm.webhook-retry-backoff-seconds[1]=1",
        "ems.alarm.webhook-retry-backoff-seconds[2]=1"
})
class WebhookDispatcherIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    static MockWebServer server;

    @BeforeAll
    static void startServer() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterAll
    static void stopServer() throws Exception {
        server.shutdown();
    }

    // 用 @MockBean 替换 InAppChannel 避免本 IT 必须 wiring 整个 ems-auth 链路
    @MockBean InAppChannel inApp;

    @Autowired WebhookChannel channel;
    @Autowired WebhookConfigRepository cfgRepo;
    @Autowired WebhookDeliveryLogRepository deliveryRepo;
    @Autowired AlarmRepository alarmRepo;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        deliveryRepo.deleteAll();
        alarmRepo.deleteAll();
        cfgRepo.deleteAll();

        Alarm a = new Alarm();
        a.setDeviceId(1L);
        a.setDeviceType("METER");
        a.setAlarmType(AlarmType.SILENT_TIMEOUT);
        a.setSeverity("WARNING");
        a.setStatus(AlarmStatus.ACTIVE);
        a.setTriggeredAt(OffsetDateTime.now());
        alarmRepo.save(a);

        WebhookConfig cfg = new WebhookConfig();
        cfg.setEnabled(true);
        cfg.setUrl(server.url("/").toString());
        cfg.setSecret("test-secret");
        cfg.setAdapterType("GENERIC_JSON");
        cfg.setTimeoutMs(2000);
        cfg.setUpdatedAt(OffsetDateTime.now());
        cfg.setUpdatedBy(1L);
        cfgRepo.save(cfg);
    }

    @Test
    void successOn2xx_writesDeliveryLogSuccess() {
        server.enqueue(new MockResponse().setResponseCode(200));
        Alarm a = alarmRepo.findAll().get(0);

        channel.sendTriggered(a);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(deliveryRepo.findAll())
                        .anyMatch(d -> d.getStatus() == DeliveryStatus.SUCCESS && d.getAttempts() == 1));
    }

    @Test
    void retryOn5xx_thenSuccess() {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(200));
        Alarm a = alarmRepo.findAll().get(0);

        channel.sendTriggered(a);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(deliveryRepo.findAll())
                        .anyMatch(d -> d.getStatus() == DeliveryStatus.SUCCESS && d.getAttempts() == 2));
    }

    @Test
    void timeoutTriggersRetry() {
        // body delay > timeout(2s) → 第 1 次失败；第 2 次成功
        server.enqueue(new MockResponse().setBodyDelay(5, TimeUnit.SECONDS).setResponseCode(200));
        server.enqueue(new MockResponse().setResponseCode(200));
        Alarm a = alarmRepo.findAll().get(0);

        channel.sendTriggered(a);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(deliveryRepo.findAll())
                        .anyMatch(d -> d.getStatus() == DeliveryStatus.SUCCESS && d.getAttempts() == 2));
    }

    @Test
    void allRetriesFail_writesFailedLog() {
        // 4 次都返 500（默认 retryMax=3，会尝试 1+3=4 次）
        for (int i = 0; i < 4; i++) {
            server.enqueue(new MockResponse().setResponseCode(500));
        }
        Alarm a = alarmRepo.findAll().get(0);

        channel.sendTriggered(a);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(deliveryRepo.findAll())
                        .anyMatch(d -> d.getStatus() == DeliveryStatus.FAILED && d.getAttempts() == 4));
    }

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackages = {"com.ems.alarm.entity", "com.ems.meter.entity"})
    @EnableJpaRepositories(basePackages = {
            "com.ems.alarm.repository",
            "com.ems.meter.repository"
    })
    @ComponentScan(basePackages = {
            "com.ems.alarm.service",
            "com.ems.alarm.config"
    })
    @Import(AlarmModuleConfig.class)
    static class TestApp {
    }
}
