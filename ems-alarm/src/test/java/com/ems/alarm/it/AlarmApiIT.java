package com.ems.alarm.it;

import com.ems.alarm.config.AlarmModuleConfig;
import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.AlarmType;
import com.ems.alarm.entity.ResolvedReason;
import com.ems.alarm.repository.AlarmRepository;
import com.ems.alarm.service.InAppChannel;
import com.ems.alarm.service.WebhookChannel;
import com.ems.auth.security.AuthUser;
import com.ems.core.constant.ErrorCode;
import com.ems.core.dto.Result;
import com.ems.core.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 告警 REST API 端到端测试（@SpringBootTest + MockMvc + Testcontainers + Spring Security）。
 * 本机 Docker Desktop 兼容问题，CI 中删除 {@code @Disabled} 即可启用。
 */
@Disabled("Testcontainers + Docker Desktop 本地兼容问题，CI 环境删除此注解即可")
@SpringBootTest(classes = AlarmApiIT.TestApp.class)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class AlarmApiIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    @MockBean InAppChannel inApp;
    @MockBean WebhookChannel webhook;

    @Autowired MockMvc mvc;
    @Autowired AlarmRepository alarmRepo;
    @Autowired JdbcTemplate jdbc;

    Long activeAlarmId;
    Long resolvedAlarmId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM alarm_inbox");
        jdbc.update("DELETE FROM webhook_delivery_log");
        jdbc.update("DELETE FROM alarms");
        jdbc.update("DELETE FROM alarm_rules_override");
        jdbc.update("DELETE FROM webhook_config");
        jdbc.update("DELETE FROM meters WHERE code IN ('M-API-001')");
        jdbc.update("DELETE FROM org_nodes WHERE code='IT_API_FACTORY'");

        Long orgId = jdbc.queryForObject(
                "INSERT INTO org_nodes (name, code, node_type) VALUES ('API IT','IT_API_FACTORY','FACTORY') RETURNING id",
                Long.class);
        Long energyId;
        try {
            energyId = jdbc.queryForObject("SELECT id FROM energy_types WHERE code='ELEC'", Long.class);
        } catch (Exception e) {
            energyId = jdbc.queryForObject(
                    "INSERT INTO energy_types (code, name, unit) VALUES ('ELEC','电','kWh') RETURNING id",
                    Long.class);
        }
        Long meterId = jdbc.queryForObject(
                "INSERT INTO meters (code, name, energy_type_id, org_node_id, " +
                "influx_measurement, influx_tag_key, influx_tag_value) " +
                "VALUES ('M-API-001','API meter',?,?,'power','device','it001') RETURNING id",
                Long.class, energyId, orgId);

        // ACTIVE alarm
        Alarm active = new Alarm();
        active.setDeviceId(meterId);
        active.setDeviceType("METER");
        active.setAlarmType(AlarmType.SILENT_TIMEOUT);
        active.setSeverity("WARNING");
        active.setStatus(AlarmStatus.ACTIVE);
        active.setTriggeredAt(OffsetDateTime.now());
        activeAlarmId = alarmRepo.save(active).getId();

        // RESOLVED alarm
        Alarm resolved = new Alarm();
        resolved.setDeviceId(meterId);
        resolved.setDeviceType("METER");
        resolved.setAlarmType(AlarmType.CONSECUTIVE_FAIL);
        resolved.setSeverity("WARNING");
        resolved.setStatus(AlarmStatus.RESOLVED);
        resolved.setTriggeredAt(OffsetDateTime.now().minusHours(2));
        resolved.setResolvedAt(OffsetDateTime.now().minusMinutes(30));
        resolved.setResolvedReason(ResolvedReason.AUTO);
        resolvedAlarmId = alarmRepo.save(resolved).getId();
    }

    // ─── 测试用例 ──────────────────────────────────────────────

    @Test
    void list_admin_returnsItems() throws Exception {
        mvc.perform(get("/api/v1/alarms").param("page", "1").param("size", "20")
                        .with(adminUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.page").value(1));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void ack_operator_forbidden() throws Exception {
        mvc.perform(post("/api/v1/alarms/" + activeAlarmId + "/ack"))
                .andExpect(status().isForbidden());
    }

    @Test
    void ack_alreadyResolved_returnsConflict() throws Exception {
        mvc.perform(post("/api/v1/alarms/" + resolvedAlarmId + "/ack")
                        .with(adminUser()))
                .andExpect(status().isConflict());
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        mvc.perform(get("/api/v1/alarms/9999999")
                        .with(adminUser()))
                .andExpect(status().isNotFound());
    }

    @Test
    void putWebhookConfig_invalidScheme_returnsBadRequest() throws Exception {
        String body = "{\"enabled\":true,\"url\":\"ftp://nope.example.com\",\"timeoutMs\":5000}";
        mvc.perform(put("/api/v1/webhook-config")
                        .with(adminUser())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void list_unauthenticated_returnsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/alarms"))
                .andExpect(status().isUnauthorized());
    }

    // ─── 辅助：提供 AuthUser principal（避免 @WithMockUser principal 类型不匹配导致 NPE）──

    private static RequestPostProcessor adminUser() {
        AuthUser admin = new AuthUser(
                1L, "admin", "n/a", true, true,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        return user(admin);
    }

    // ─── TestApp ────────────────────────────────────────────────

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackages = {
            "com.ems.alarm.entity",
            "com.ems.meter.entity",
            "com.ems.auth.entity"
    })
    @EnableJpaRepositories(basePackages = {
            "com.ems.alarm.repository",
            "com.ems.meter.repository",
            "com.ems.auth.repository"
    })
    @ComponentScan(basePackages = {
            "com.ems.alarm",    // controller / service / dto / config
            "com.ems.audit",    // AuditAspect + AuditContext
            "com.ems.auth"      // SecurityConfig + AuthUser + AuditContextImpl
    })
    @Import(AlarmModuleConfig.class)
    static class TestApp {

        /**
         * 内嵌异常处理器：将 BusinessException（400/404/409 等）和 AccessDeniedException
         * 映射到正确 HTTP 状态码。ems-app 的 GlobalExceptionHandler 不在本测试的 classpath 扫描范围内。
         */
        @Bean
        LocalExceptionHandler localExceptionHandler() {
            return new LocalExceptionHandler();
        }
    }

    @RestControllerAdvice
    static class LocalExceptionHandler {

        @ExceptionHandler(BusinessException.class)
        ResponseEntity<Result<?>> biz(BusinessException ex) {
            HttpStatus s = switch (ex.getCode()) {
                case ErrorCode.NOT_FOUND    -> HttpStatus.NOT_FOUND;
                case ErrorCode.CONFLICT     -> HttpStatus.CONFLICT;
                case ErrorCode.FORBIDDEN    -> HttpStatus.FORBIDDEN;
                case ErrorCode.UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
                default -> HttpStatus.BAD_REQUEST;
            };
            return ResponseEntity.status(s).body(Result.error(ex.getCode(), ex.getMessage()));
        }

        @ExceptionHandler(AccessDeniedException.class)
        ResponseEntity<Result<?>> denied(AccessDeniedException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Result.error(ErrorCode.FORBIDDEN, "access denied"));
        }

        @ExceptionHandler(MethodArgumentNotValidException.class)
        ResponseEntity<Result<?>> validation(MethodArgumentNotValidException ex) {
            String msg = ex.getBindingResult().getFieldErrors().stream()
                    .map(e -> e.getField() + ": " + e.getDefaultMessage())
                    .reduce((a, b) -> a + "; " + b).orElse("validation failed");
            return ResponseEntity.badRequest().body(Result.error(ErrorCode.PARAM_INVALID, msg));
        }
    }
}
