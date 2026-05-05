package com.ems.meter.controller;

import com.ems.core.constant.ErrorCode;
import com.ems.core.exception.BusinessException;
import com.ems.meter.entity.CarbonFactor;
import com.ems.meter.entity.EnergySource;
import com.ems.meter.repository.CarbonFactorRepository;
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
 * 碳排因子控制器单元测试。结构与 FeedInTariffControllerTest 对称：
 * happy path / 409 dup / 403 non-admin / 400 invalid input。
 */
@WebMvcTest
@ContextConfiguration(classes = {
        CarbonFactorControllerTest.BootApp.class,
        CarbonFactorController.class,
        CarbonFactorControllerTest.MinimalAdvice.class
})
class CarbonFactorControllerTest {

    /** ems-meter 是库模块，没有 @SpringBootApplication。@WebMvcTest 需要一个 @SpringBootConfiguration 才能引导上下文。
     *  @EnableMethodSecurity 必不可少，否则 @PreAuthorize("hasRole('ADMIN')") 会被 silently ignored，403 用例就失效。 */
    @SpringBootConfiguration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
    static class BootApp {}


    @Autowired MockMvc mvc;
    @MockBean CarbonFactorRepository repo;

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
        when(repo.existsByRegionAndEnergySourceAndEffectiveFrom(
                any(), any(), any())).thenReturn(false);
    }

    // ─── GET /carbon-factor ───────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET 列表 — 200 + 由仓储排序方法供数据")
    void list_returnsRowsFromRepo() throws Exception {
        CarbonFactor row = new CarbonFactor(
                "CN", EnergySource.GRID, LocalDate.of(2020, 1, 1), new BigDecimal("0.5810"));
        when(repo.findAllByOrderByRegionAscEnergySourceAscEffectiveFromDesc())
                .thenReturn(List.of(row));

        mvc.perform(get("/api/v1/carbon-factor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].region").value("CN"))
                .andExpect(jsonPath("$.data[0].energySource").value("GRID"))
                .andExpect(jsonPath("$.data[0].factorKgPerKwh").value(0.5810));
    }

    // ─── POST /carbon-factor ──────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST 新增 — happy path 201 + 调用 save")
    void create_admin_returns201() throws Exception {
        CarbonFactor saved = new CarbonFactor(
                "CN", EnergySource.GRID, LocalDate.of(2026, 5, 1), new BigDecimal("0.5500"));
        when(repo.save(any(CarbonFactor.class))).thenReturn(saved);

        String body = """
            {"region":"CN","energySource":"GRID",
             "effectiveFrom":"2026-05-01","factorKgPerKwh":"0.5500"}
            """;

        mvc.perform(post("/api/v1/carbon-factor")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.region").value("CN"));

        verify(repo).save(any(CarbonFactor.class));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST 重复 (region, source, effective_from) — 409 + 不调用 save")
    void create_duplicate_returns409() throws Exception {
        when(repo.existsByRegionAndEnergySourceAndEffectiveFrom(
                any(), any(), any())).thenReturn(true);

        String body = """
            {"region":"CN","energySource":"GRID",
             "effectiveFrom":"2020-01-01","factorKgPerKwh":"0.5810"}
            """;

        mvc.perform(post("/api/v1/carbon-factor")
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
            {"region":"CN","energySource":"GRID",
             "effectiveFrom":"2026-05-01","factorKgPerKwh":"0.5500"}
            """;

        mvc.perform(post("/api/v1/carbon-factor")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        verify(repo, never()).save(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST factor < 0 — 400 (DecimalMin 校验)")
    void create_negativeFactor_returns400() throws Exception {
        String body = """
            {"region":"CN","energySource":"GRID",
             "effectiveFrom":"2026-05-01","factorKgPerKwh":"-0.0001"}
            """;

        mvc.perform(post("/api/v1/carbon-factor")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(repo, never()).save(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST 缺 region — 400 (NotBlank 校验)")
    void create_missingRegion_returns400() throws Exception {
        String body = """
            {"region":"","energySource":"GRID",
             "effectiveFrom":"2026-05-01","factorKgPerKwh":"0.5500"}
            """;

        mvc.perform(post("/api/v1/carbon-factor")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
