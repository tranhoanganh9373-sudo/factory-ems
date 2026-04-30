package com.ems.alarm.controller;

import com.ems.alarm.entity.WebhookConfig;
import com.ems.alarm.repository.WebhookConfigRepository;
import com.ems.alarm.repository.WebhookDeliveryLogRepository;
import com.ems.alarm.service.WebhookChannel;
import com.ems.auth.security.AuthUser;
import com.ems.core.constant.ErrorCode;
import com.ems.core.dto.Result;
import com.ems.core.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebMvcTest slice for {@link WebhookController}.
 *
 * <p>Security: a minimal permit-all {@link SecurityFilterChain} lets the filter chain run
 * (so @PreAuthorize fires via {@link EnableMethodSecurity}) while authentication is supplied
 * per-request via {@code SecurityMockMvcRequestPostProcessors.user()}.
 *
 * <p>The {@code @Audited} AOP aspect is NOT loaded in the WebMvcTest slice (no @Aspect
 * beans are auto-configured), so update() proceeds without audit side effects.
 *
 * <p>A {@link LocalExceptionHandler} is included inline to map {@link BusinessException}
 * and validation errors to proper HTTP status codes — the ems-app GlobalExceptionHandler
 * is not on the slice classpath.
 */
@WebMvcTest(controllers = WebhookController.class)
@ContextConfiguration(classes = {
        WebhookController.class,
        WebhookControllerTest.TestSecurityConfig.class,
        WebhookControllerTest.LocalExceptionHandler.class
})
class WebhookControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    WebhookConfigRepository cfgRepo;

    @MockBean
    WebhookDeliveryLogRepository deliveryRepo;

    @MockBean
    WebhookChannel webhookChannel;

    // ─── shared helpers ──────────────────────────────────────────

    private static AuthUser adminUser() {
        return new AuthUser(1L, "admin", "n/a", true, true,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static AuthUser operatorUser() {
        return new AuthUser(2L, "op", "n/a", true, true,
                List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));
    }

    /** Build a PUT body with all required fields; secret may be null (omitted) or any string. */
    private String putBody(String secret) throws Exception {
        var node = objectMapper.createObjectNode();
        node.put("enabled", true);
        node.put("url", "https://hooks.example.com/notify");
        if (secret != null) {
            node.put("secret", secret);
        }
        node.put("adapterType", "generic-json");
        node.put("timeoutMs", 5000);
        return objectMapper.writeValueAsString(node);
    }

    private WebhookConfig storedConfig(String secret) {
        WebhookConfig cfg = new WebhookConfig();
        cfg.setEnabled(true);
        cfg.setUrl("https://existing.example.com");
        cfg.setSecret(secret);
        cfg.setAdapterType("GENERIC_JSON");
        cfg.setTimeoutMs(5000);
        return cfg;
    }

    // ─── GET /api/v1/webhook-config ──────────────────────────────

    @Test
    @DisplayName("GET config – secret set – returns masked '***'")
    void getConfig_secretSet_returnsMaskedSecret() throws Exception {
        when(cfgRepo.findFirstByOrderByIdAsc()).thenReturn(Optional.of(storedConfig("real-secret-value")));

        mvc.perform(get("/api/v1/webhook-config").with(user(adminUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.secret").value("***"));
    }

    @Test
    @DisplayName("GET config – secret empty – returns empty string")
    void getConfig_secretEmpty_returnsEmptyString() throws Exception {
        when(cfgRepo.findFirstByOrderByIdAsc()).thenReturn(Optional.of(storedConfig("")));

        mvc.perform(get("/api/v1/webhook-config").with(user(adminUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.secret").value(""));
    }

    @Test
    @DisplayName("GET config – no config present – returns 200 with defaults (new WebhookConfig)")
    void getConfig_noConfigPresent_returnsDefaults() throws Exception {
        // Controller calls orElseGet(WebhookConfig::new) — new entity has enabled=false, null secret → ""
        when(cfgRepo.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/webhook-config").with(user(adminUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.secret").value(""));
    }

    // ─── PUT /api/v1/webhook-config – secret preservation ────────

    @Test
    @DisplayName("PUT config – blank secret – keeps original stored secret")
    void updateConfig_blankSecret_keepsOriginal() throws Exception {
        when(cfgRepo.findFirstByOrderByIdAsc()).thenReturn(Optional.of(storedConfig("orig-secret")));

        ArgumentCaptor<WebhookConfig> captor = ArgumentCaptor.forClass(WebhookConfig.class);
        when(cfgRepo.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(put("/api/v1/webhook-config")
                        .with(user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(putBody("")))
                .andExpect(status().isOk());

        assertThat(captor.getValue().getSecret()).isEqualTo("orig-secret");
    }

    @Test
    @DisplayName("PUT config – null secret (omitted from body) – keeps original stored secret")
    void updateConfig_nullSecret_keepsOriginal() throws Exception {
        when(cfgRepo.findFirstByOrderByIdAsc()).thenReturn(Optional.of(storedConfig("orig-secret")));

        ArgumentCaptor<WebhookConfig> captor = ArgumentCaptor.forClass(WebhookConfig.class);
        when(cfgRepo.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        // Build body without "secret" key — record field deserializes to null
        var node = objectMapper.createObjectNode();
        node.put("enabled", true);
        node.put("url", "https://hooks.example.com/notify");
        node.put("adapterType", "generic-json");
        node.put("timeoutMs", 5000);

        mvc.perform(put("/api/v1/webhook-config")
                        .with(user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(node)))
                .andExpect(status().isOk());

        assertThat(captor.getValue().getSecret()).isEqualTo("orig-secret");
    }

    @Test
    @DisplayName("PUT config – new non-blank secret – overwrites stored secret")
    void updateConfig_newSecret_overwrites() throws Exception {
        when(cfgRepo.findFirstByOrderByIdAsc()).thenReturn(Optional.of(storedConfig("orig-secret")));

        ArgumentCaptor<WebhookConfig> captor = ArgumentCaptor.forClass(WebhookConfig.class);
        when(cfgRepo.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(put("/api/v1/webhook-config")
                        .with(user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(putBody("new-secret")))
                .andExpect(status().isOk());

        assertThat(captor.getValue().getSecret()).isEqualTo("new-secret");
    }

    // ─── PUT /api/v1/webhook-config – URL scheme validation ──────

    @Test
    @DisplayName("PUT config – ftp:// scheme – returns 400")
    void updateConfig_invalidScheme_returns400() throws Exception {
        var node = objectMapper.createObjectNode();
        node.put("enabled", true);
        node.put("url", "ftp://bad.example.com");
        node.put("timeoutMs", 5000);

        mvc.perform(put("/api/v1/webhook-config")
                        .with(user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(node)))
                .andExpect(status().isBadRequest());
    }

    // ─── PUT /api/v1/webhook-config – timeoutMs bounds ───────────

    @Test
    @DisplayName("PUT config – timeoutMs=999 (below @Min(1000)) – returns 400")
    void updateConfig_timeoutTooSmall_returns400() throws Exception {
        var node = objectMapper.createObjectNode();
        node.put("enabled", true);
        node.put("url", "https://hooks.example.com/notify");
        node.put("timeoutMs", 999);

        mvc.perform(put("/api/v1/webhook-config")
                        .with(user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(node)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT config – timeoutMs=30001 (above @Max(30000)) – returns 400")
    void updateConfig_timeoutTooLarge_returns400() throws Exception {
        var node = objectMapper.createObjectNode();
        node.put("enabled", true);
        node.put("url", "https://hooks.example.com/notify");
        node.put("timeoutMs", 30001);

        mvc.perform(put("/api/v1/webhook-config")
                        .with(user(adminUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(node)))
                .andExpect(status().isBadRequest());
    }

    // ─── Authorization ────────────────────────────────────────────

    @Test
    @DisplayName("GET config – OPERATOR role – returns 403 (class-level @PreAuthorize('hasRole(ADMIN)'))")
    void updateConfig_operator_returns403() throws Exception {
        mvc.perform(get("/api/v1/webhook-config").with(user(operatorUser())))
                .andExpect(status().isForbidden());
    }

    // ─── inner test configurations ────────────────────────────────

    /**
     * Minimal security config for the slice:
     * - @EnableMethodSecurity activates @PreAuthorize on the controller
     * - permit-all filter chain defers auth to MockMvc's user() post-processor
     */
    @Configuration
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
            http
                    .csrf(c -> c.disable())
                    .authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }
    }

    /**
     * Inline exception handler to map {@link BusinessException} and validation errors to the
     * correct HTTP status codes. The ems-app GlobalExceptionHandler is not loaded in the
     * WebMvcTest slice.
     */
    @RestControllerAdvice
    static class LocalExceptionHandler {

        @ExceptionHandler(BusinessException.class)
        ResponseEntity<Result<?>> handleBusiness(BusinessException ex) {
            HttpStatus status = switch (ex.getCode()) {
                case ErrorCode.NOT_FOUND    -> HttpStatus.NOT_FOUND;
                case ErrorCode.CONFLICT     -> HttpStatus.CONFLICT;
                case ErrorCode.FORBIDDEN    -> HttpStatus.FORBIDDEN;
                case ErrorCode.UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
                default -> HttpStatus.BAD_REQUEST;
            };
            return ResponseEntity.status(status)
                    .body(Result.error(ex.getCode(), ex.getMessage()));
        }

        @ExceptionHandler(AccessDeniedException.class)
        ResponseEntity<Result<?>> handleAccessDenied(AccessDeniedException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Result.error(ErrorCode.FORBIDDEN, "access denied"));
        }

        @ExceptionHandler(MethodArgumentNotValidException.class)
        ResponseEntity<Result<?>> handleValidation(MethodArgumentNotValidException ex) {
            String msg = ex.getBindingResult().getFieldErrors().stream()
                    .map(e -> e.getField() + ": " + e.getDefaultMessage())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("validation failed");
            return ResponseEntity.badRequest()
                    .body(Result.error(ErrorCode.PARAM_INVALID, msg));
        }
    }
}
