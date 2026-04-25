package com.ems.tariff;

import com.ems.audit.aspect.AuditContext;
import com.ems.core.exception.NotFoundException;
import com.ems.core.security.PermissionResolver;
import com.ems.tariff.dto.*;
import com.ems.tariff.repository.TariffPeriodRepository;
import com.ems.tariff.repository.TariffPlanRepository;
import com.ems.tariff.service.TariffService;
import com.ems.tariff.service.impl.TariffServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = TariffServiceIT.TestApp.class)
@Testcontainers
@ActiveProfiles("test")
class TariffServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired TariffService svc;
    @Autowired TariffPlanRepository planRepo;
    @Autowired TariffPeriodRepository periodRepo;
    @Autowired JdbcTemplate jdbc;

    Long elecTypeId;

    @BeforeEach
    void setUp() {
        periodRepo.deleteAll();
        planRepo.deleteAll();
        elecTypeId = jdbc.queryForObject(
                "SELECT id FROM energy_types WHERE code = 'ELEC'", Long.class);
    }

    // ---- Test 1: create plan with 4 periods, list shows plan with periods sorted by timeStart ----

    @Test
    void create_plan_withPeriods_persists() {
        List<CreateTariffPeriodReq> periods = List.of(
                new CreateTariffPeriodReq("PEAK",   LocalTime.of(8,  0), LocalTime.of(12, 0), new BigDecimal("1.2000")),
                new CreateTariffPeriodReq("FLAT",   LocalTime.of(12, 0), LocalTime.of(17, 0), new BigDecimal("0.8000")),
                new CreateTariffPeriodReq("SHARP",  LocalTime.of(17, 0), LocalTime.of(21, 0), new BigDecimal("1.5000")),
                new CreateTariffPeriodReq("VALLEY", LocalTime.of(22, 0), LocalTime.of(8,  0), new BigDecimal("0.4000"))
        );
        CreateTariffPlanReq req = new CreateTariffPlanReq(
                "工厂电价2024", elecTypeId,
                LocalDate.of(2024, 1, 1), null, periods);

        TariffPlanDTO dto = svc.create(req);

        assertThat(dto.id()).isNotNull();
        assertThat(dto.name()).isEqualTo("工厂电价2024");
        assertThat(dto.periods()).hasSize(4);

        // list shows 1 plan
        List<TariffPlanDTO> all = svc.list();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).periods()).hasSize(4);

        // periods sorted by timeStart ascending
        List<LocalTime> starts = all.get(0).periods().stream()
                .map(TariffPeriodDTO::timeStart).toList();
        assertThat(starts).isSortedAccordingTo(LocalTime::compareTo);
    }

    // ---- Test 2: update plan replaces periods ----

    @Test
    void update_plan_replacesPeriods() {
        List<CreateTariffPeriodReq> original = List.of(
                new CreateTariffPeriodReq("FLAT", LocalTime.of(0, 0), LocalTime.of(23, 59, 59), new BigDecimal("0.8000"))
        );
        TariffPlanDTO created = svc.create(new CreateTariffPlanReq(
                "方案A", elecTypeId, LocalDate.of(2024, 1, 1), null, original));

        List<CreateTariffPeriodReq> newPeriods = List.of(
                new CreateTariffPeriodReq("PEAK",   LocalTime.of(9, 0), LocalTime.of(17, 0), new BigDecimal("1.2000")),
                new CreateTariffPeriodReq("VALLEY", LocalTime.of(22, 0), LocalTime.of(6,  0), new BigDecimal("0.3000"))
        );
        TariffPlanDTO updated = svc.update(created.id(),
                new UpdateTariffPlanReq("方案A-更新", null, null, newPeriods));

        assertThat(updated.name()).isEqualTo("方案A-更新");
        assertThat(updated.periods()).hasSize(2);
        assertThat(updated.periods()).extracting(TariffPeriodDTO::periodType)
                .containsExactlyInAnyOrder("PEAK", "VALLEY");
    }

    // ---- Test 3: delete plan cascades periods ----

    @Test
    void delete_plan_cascadesPeriods() {
        List<CreateTariffPeriodReq> periods = List.of(
                new CreateTariffPeriodReq("FLAT", LocalTime.of(0, 0), LocalTime.of(23, 59), new BigDecimal("0.8000"))
        );
        TariffPlanDTO created = svc.create(new CreateTariffPlanReq(
                "方案删除测试", elecTypeId, LocalDate.of(2024, 1, 1), null, periods));

        svc.delete(created.id());

        assertThat(planRepo.findById(created.id())).isEmpty();
        assertThat(periodRepo.findByPlanIdOrderByTimeStartAsc(created.id())).isEmpty();
    }

    // ---- Test 4: resolve normal period (at=10:30, PEAK 09:00-17:00 → PEAK) ----

    @Test
    void resolve_normalPeriod() {
        List<CreateTariffPeriodReq> periods = List.of(
                new CreateTariffPeriodReq("PEAK", LocalTime.of(9, 0), LocalTime.of(17, 0), new BigDecimal("1.2000"))
        );
        svc.create(new CreateTariffPlanReq(
                "正常时段方案", elecTypeId, LocalDate.of(2024, 1, 1), null, periods));

        OffsetDateTime at = OffsetDateTime.of(2024, 6, 15, 10, 30, 0, 0, ZoneOffset.UTC);
        ResolvedPriceDTO result = svc.resolvePrice(elecTypeId, at);

        assertThat(result.periodType()).isEqualTo("PEAK");
        assertThat(result.pricePerUnit()).isEqualByComparingTo("1.2000");
    }

    // ---- Test 5: resolve cross-midnight period (at=01:00, VALLEY 22:00-06:00 → VALLEY) ----

    @Test
    void resolve_crossMidnightPeriod() {
        List<CreateTariffPeriodReq> periods = List.of(
                new CreateTariffPeriodReq("VALLEY", LocalTime.of(22, 0), LocalTime.of(6, 0), new BigDecimal("0.4000"))
        );
        svc.create(new CreateTariffPlanReq(
                "跨零点时段方案", elecTypeId, LocalDate.of(2024, 1, 1), null, periods));

        OffsetDateTime at = OffsetDateTime.of(2024, 6, 15, 1, 0, 0, 0, ZoneOffset.UTC);
        ResolvedPriceDTO result = svc.resolvePrice(elecTypeId, at);

        assertThat(result.periodType()).isEqualTo("VALLEY");
        assertThat(result.pricePerUnit()).isEqualByComparingTo("0.4000");
    }

    // ---- Test 6: resolve outside any period throws NotFoundException ----
    // Decision: throw NotFoundException (not FLAT fallback) so callers get explicit signal.

    @Test
    void resolve_outsideAnyPeriod_throws() {
        List<CreateTariffPeriodReq> periods = List.of(
                new CreateTariffPeriodReq("PEAK", LocalTime.of(9, 0), LocalTime.of(17, 0), new BigDecimal("1.2000"))
        );
        svc.create(new CreateTariffPlanReq(
                "有限时段方案", elecTypeId, LocalDate.of(2024, 1, 1), null, periods));

        // at=20:00 UTC — outside PEAK 09:00-17:00
        OffsetDateTime at = OffsetDateTime.of(2024, 6, 15, 20, 0, 0, 0, ZoneOffset.UTC);

        assertThatThrownBy(() -> svc.resolvePrice(elecTypeId, at))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("TariffPeriod");
    }

    // ---- Test 7: periodContains static helper unit tests ----

    @Test
    void periodContains_helperUnitTests() {
        LocalTime start = LocalTime.of(9, 0);
        LocalTime end   = LocalTime.of(17, 0);

        // exactly at start → inside
        assertThat(TariffServiceImpl.periodContains(start, end, LocalTime.of(9, 0))).isTrue();
        // one ns before end → inside
        assertThat(TariffServiceImpl.periodContains(start, end, LocalTime.of(16, 59))).isTrue();
        // exactly at end → outside (half-open [start, end))
        assertThat(TariffServiceImpl.periodContains(start, end, LocalTime.of(17, 0))).isFalse();
        // before start → outside
        assertThat(TariffServiceImpl.periodContains(start, end, LocalTime.of(8, 59))).isFalse();

        // cross-midnight: 22:00 → 06:00
        LocalTime cStart = LocalTime.of(22, 0);
        LocalTime cEnd   = LocalTime.of(6, 0);

        // at 23:00 → inside
        assertThat(TariffServiceImpl.periodContains(cStart, cEnd, LocalTime.of(23, 0))).isTrue();
        // at 01:00 → inside
        assertThat(TariffServiceImpl.periodContains(cStart, cEnd, LocalTime.of(1, 0))).isTrue();
        // exactly at cStart → inside
        assertThat(TariffServiceImpl.periodContains(cStart, cEnd, LocalTime.of(22, 0))).isTrue();
        // exactly at cEnd → outside
        assertThat(TariffServiceImpl.periodContains(cStart, cEnd, LocalTime.of(6, 0))).isFalse();
        // at 10:00 → outside
        assertThat(TariffServiceImpl.periodContains(cStart, cEnd, LocalTime.of(10, 0))).isFalse();
        // midnight (00:00) → inside cross-midnight
        assertThat(TariffServiceImpl.periodContains(cStart, cEnd, LocalTime.MIDNIGHT)).isTrue();
    }

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackages = {"com.ems.tariff.entity", "com.ems.meter.entity"})
    @EnableJpaRepositories(basePackages = {"com.ems.tariff.repository"})
    @ComponentScan(basePackages = {"com.ems.tariff.service"})
    static class TestApp {

        @Bean
        AuditContext auditContext() {
            return new AuditContext() {
                public Long currentUserId()    { return 1L; }
                public String currentUsername() { return "tester"; }
                public String currentIp()       { return "127.0.0.1"; }
                public String currentUserAgent(){ return "it"; }
            };
        }

        @Bean
        PermissionResolver permissionResolver() {
            return new PermissionResolver() {
                @Override public Set<Long> visibleNodeIds(Long userId) { return ALL_NODE_IDS_MARKER; }
                @Override public boolean canAccess(Long userId, Long orgNodeId) { return true; }
                @Override public boolean hasAllNodes(Set<Long> v) { return v == ALL_NODE_IDS_MARKER; }
                @Override public Long currentUserId() { return 1L; }
            };
        }
    }
}
