package com.ems.meter.controller;

import com.ems.core.constant.ErrorCode;
import com.ems.core.exception.BusinessException;
import com.ems.meter.entity.EnergySource;
import com.ems.meter.entity.FeedInTariff;
import com.ems.meter.repository.FeedInTariffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 上网电价控制器单元测试 —— @WebMvcTest 仅装载 controller 层 + Spring Security
 * + 一个最小 @RestControllerAdvice（映射 BusinessException → CONFLICT）。
 *
 * <p>覆盖：happy path / 409 dup / 403 non-admin / 400 invalid input。
 * 仓储 @MockBean，无数据库依赖，运行 < 5s。
 */
@WebMvcTest
@ContextConfiguration(classes = {
        FeedInTariffControllerTest.BootApp.class,
        FeedInTariffController.class,
        FeedInTariffControllerTest.MinimalAdvice.class
})
class FeedInTariffControllerTest {

    /** ems-meter 是库模块，没有 @SpringBootApplication。@WebMvcTest 需要一个 @SpringBootConfiguration 才能引导上下文。
     *  @EnableMethodSecurity 必不可少，否则 @PreAuthorize("hasRole('ADMIN')") 会被 silently ignored，403 用例就失效。 */
    @SpringBootConfiguration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
    static class BootApp {}


    @Autowired MockMvc mvc;
    @MockBean FeedInTariffRepository repo;

    /**
     * ems-meter 不依赖 ems-app，所以借不到生产 GlobalExceptionHandler。
     * 这里复刻它对 BusinessException(CONFLICT) → 409 的核心映射，让 controller 抛出的
     * ConflictException 在测试中得到正确的 HTTP 状态码。
     */
    @TestConfiguration
    @RestControllerAdvice
    static class MinimalAdvice {
        @ExceptionHandler(BusinessException.class)
        public ResponseEntity<String> biz(BusinessException ex) {
            HttpStatus s = ex.getCode() == ErrorCode.CONFLICT ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(s).body(ex.getMessage());
        }
    }

    @BeforeEach
    void resetCounters() {
        when(repo.existsByRegionAndEnergySourceAndPeriodTypeAndEffectiveFrom(
                any(), any(), any(), any())).thenReturn(false);
    }

    // ─── GET /feed-in-tariff ──────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET 列表 — 200 + 由仓储排序方法供数据")
    void list_returnsRowsFromRepo() throws Exception {
        FeedInTariff row = new FeedInTariff(
                "CN", EnergySource.SOLAR, "PEAK", LocalDate.of(2026, 5, 1), new BigDecimal("0.4500"));
        when(repo.findAllByOrderByRegionAscEnergySourceAscPeriodTypeAscEffectiveFromDesc())
                .thenReturn(List.of(row));

        mvc.perform(get("/api/v1/feed-in-tariff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].region").value("CN"))
                .andExpect(jsonPath("$.data[0].energySource").value("SOLAR"))
                .andExpect(jsonPath("$.data[0].periodType").value("PEAK"))
                .andExpect(jsonPath("$.data[0].price").value(0.4500));
    }

    // ─── POST /feed-in-tariff ─────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST 新增 — happy path 201 + 调用 save")
    void create_admin_returns201() throws Exception {
        FeedInTariff saved = new FeedInTariff(
                "CN", EnergySource.SOLAR, "PEAK", LocalDate.of(2026, 5, 1), new BigDecimal("0.4500"));
        when(repo.save(any(FeedInTariff.class))).thenReturn(saved);

        String body = """
            {"region":"CN","energySource":"SOLAR","periodType":"PEAK",
             "effectiveFrom":"2026-05-01","price":"0.4500"}
            """;

        mvc.perform(post("/api/v1/feed-in-tariff")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.region").value("CN"));

        verify(repo).save(any(FeedInTariff.class));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST 重复 (region, source, period, effective_from) — 409 + 不调用 save")
    void create_duplicate_returns409() throws Exception {
        when(repo.existsByRegionAndEnergySourceAndPeriodTypeAndEffectiveFrom(
                any(), any(), any(), any())).thenReturn(true);

        String body = """
            {"region":"CN","energySource":"SOLAR","periodType":"PEAK",
             "effectiveFrom":"2020-01-01","price":"0.4500"}
            """;

        mvc.perform(post("/api/v1/feed-in-tariff")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());

        verify(repo, never()).save(any());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    @DisplayName("POST 非 ADMIN — 403 + 不调用 save")
    void create_nonAdmin_returns403() throws Exception {
        String body = """
            {"region":"CN","energySource":"SOLAR","periodType":"PEAK",
             "effectiveFrom":"2026-05-01","price":"0.4500"}
            """;

        mvc.perform(post("/api/v1/feed-in-tariff")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        verify(repo, never()).save(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST price < 0 — 400 (DecimalMin 校验)")
    void create_negativePrice_returns400() throws Exception {
        String body = """
            {"region":"CN","energySource":"SOLAR","periodType":"PEAK",
             "effectiveFrom":"2026-05-01","price":"-0.0001"}
            """;

        mvc.perform(post("/api/v1/feed-in-tariff")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(repo, never()).save(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST periodType 非法 — 400 (Pattern 校验)")
    void create_invalidPeriodType_returns400() throws Exception {
        String body = """
            {"region":"CN","energySource":"SOLAR","periodType":"BOGUS",
             "effectiveFrom":"2026-05-01","price":"0.4500"}
            """;

        mvc.perform(post("/api/v1/feed-in-tariff")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST 缺 effective_from — 400 (NotNull 校验)")
    void create_missingEffectiveFrom_returns400() throws Exception {
        String body = """
            {"region":"CN","energySource":"SOLAR","periodType":"PEAK","price":"0.4500"}
            """;

        mvc.perform(post("/api/v1/feed-in-tariff")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
