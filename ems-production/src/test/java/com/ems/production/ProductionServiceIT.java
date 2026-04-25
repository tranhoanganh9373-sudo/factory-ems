package com.ems.production;

import com.ems.audit.aspect.AuditContext;
import com.ems.core.exception.BusinessException;
import com.ems.core.exception.ForbiddenException;
import com.ems.core.security.PermissionResolver;
import com.ems.production.dto.*;
import com.ems.production.repository.ProductionEntryRepository;
import com.ems.production.repository.ShiftRepository;
import com.ems.production.service.ProductionEntryService;
import com.ems.production.service.ShiftService;
import com.ems.production.service.impl.ProductionEntryServiceImpl;
import com.ems.production.service.impl.ShiftServiceImpl;
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
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = ProductionServiceIT.TestApp.class)
@Testcontainers
@ActiveProfiles("test")
class ProductionServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired ShiftService shiftSvc;
    @Autowired ProductionEntryService entrySvc;
    @Autowired ShiftRepository shiftRepo;
    @Autowired ProductionEntryRepository entryRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestPermissionResolver testPermission;

    Long orgNodeId;

    @BeforeEach
    void setUp() {
        entryRepo.deleteAll();
        shiftRepo.deleteAll();
        // Reset to admin by default
        testPermission.setAdminMode(true);
        testPermission.setVisibleNodeIds(null);

        // Insert a root org_node for FK satisfaction
        jdbc.execute("DELETE FROM org_nodes WHERE code = 'TEST_ORG'");
        jdbc.update("INSERT INTO org_nodes (name, code, node_type, sort_order) VALUES ('测试工厂', 'TEST_ORG', 'FACTORY', 0)");
        orgNodeId = jdbc.queryForObject("SELECT id FROM org_nodes WHERE code = 'TEST_ORG'", Long.class);
    }

    // ---- Test 1: createShift_persists ----

    @Test
    void createShift_persists() {
        CreateShiftReq req = new CreateShiftReq("DAY", "白班", LocalTime.of(8, 0), LocalTime.of(20, 0), 1);
        ShiftDTO dto = shiftSvc.create(req);

        assertThat(dto.id()).isNotNull();
        assertThat(dto.code()).isEqualTo("DAY");
        assertThat(dto.name()).isEqualTo("白班");
        assertThat(dto.enabled()).isTrue();

        List<ShiftDTO> all = shiftSvc.list(false);
        assertThat(all).hasSize(1);
    }

    // ---- Test 2: deleteShift_referenced_throws ----

    @Test
    void deleteShift_referenced_throws() {
        ShiftDTO shift = shiftSvc.create(
                new CreateShiftReq("NIGHT", "夜班", LocalTime.of(20, 0), LocalTime.of(8, 0), 2));

        entrySvc.create(new CreateProductionEntryReq(
                orgNodeId, shift.id(), LocalDate.of(2026, 4, 1),
                "PROD_A", new BigDecimal("100"), "件", null));

        assertThatThrownBy(() -> shiftSvc.delete(shift.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("班次仍被产量记录引用");
    }

    // ---- Test 3: createEntry_duplicate_throws ----

    @Test
    void createEntry_duplicate_throws() {
        ShiftDTO shift = shiftSvc.create(
                new CreateShiftReq("DAY2", "白班2", LocalTime.of(8, 0), LocalTime.of(20, 0), 1));

        CreateProductionEntryReq req = new CreateProductionEntryReq(
                orgNodeId, shift.id(), LocalDate.of(2026, 4, 1),
                "PROD_B", new BigDecimal("50"), "件", null);

        entrySvc.create(req);

        assertThatThrownBy(() -> entrySvc.create(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("产量记录已存在");
    }

    // ---- Test 4: searchEntries_viewerOutsideScope_throws ----

    @Test
    void searchEntries_viewerOutsideScope_throws() {
        // Create another org node
        jdbc.execute("DELETE FROM org_nodes WHERE code = 'OTHER_ORG'");
        jdbc.update("INSERT INTO org_nodes (name, code, node_type, sort_order) VALUES ('其他工厂', 'OTHER_ORG', 'FACTORY', 1)");
        Long otherOrgId = jdbc.queryForObject("SELECT id FROM org_nodes WHERE code = 'OTHER_ORG'", Long.class);

        // Viewer can only see orgNodeId, not otherOrgId
        testPermission.setAdminMode(false);
        testPermission.setVisibleNodeIds(Set.of(orgNodeId));

        assertThatThrownBy(() -> entrySvc.search(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                otherOrgId,
                org.springframework.data.domain.PageRequest.of(0, 20)))
                .isInstanceOf(ForbiddenException.class);
    }

    // ---- Test 5: dailyTotals_aggregatesCorrectly ----

    @Test
    void dailyTotals_aggregatesCorrectly() {
        ShiftDTO shift = shiftSvc.create(
                new CreateShiftReq("DAY3", "白班3", LocalTime.of(8, 0), LocalTime.of(20, 0), 1));

        LocalDate d1 = LocalDate.of(2026, 4, 1);
        LocalDate d2 = LocalDate.of(2026, 4, 3);

        entrySvc.create(new CreateProductionEntryReq(
                orgNodeId, shift.id(), d1, "PROD_C", new BigDecimal("100"), "件", null));
        entrySvc.create(new CreateProductionEntryReq(
                orgNodeId, shift.id(), d1, "PROD_D", new BigDecimal("50"), "件", null));
        entrySvc.create(new CreateProductionEntryReq(
                orgNodeId, shift.id(), d2, "PROD_C", new BigDecimal("200"), "件", null));

        Map<LocalDate, BigDecimal> totals = entrySvc.dailyTotals(orgNodeId, d1, d2);

        assertThat(totals).containsKey(d1);
        assertThat(totals).containsKey(d2);
        assertThat(totals.get(d1)).isEqualByComparingTo("150");
        assertThat(totals.get(d2)).isEqualByComparingTo("200");
        // d1+1 = Apr 2 should be present with 0
        assertThat(totals.get(LocalDate.of(2026, 4, 2))).isEqualByComparingTo("0");
    }

    // ---- Test 6: importCsv_partialSuccess ----

    @Test
    void importCsv_partialSuccess() {
        ShiftDTO shift = shiftSvc.create(
                new CreateShiftReq("DAY4", "白班4", LocalTime.of(8, 0), LocalTime.of(20, 0), 1));

        String csv = "org_node_id,shift_id,entry_date,product_code,quantity,unit,remark\n" +
                orgNodeId + "," + shift.id() + ",2026-04-01,PROD_E,100,件,\n" +
                orgNodeId + "," + shift.id() + ",2026-04-02,PROD_E,200,件,\n" +
                orgNodeId + "," + shift.id() + ",2026-04-03,PROD_E,300,件,\n" +
                orgNodeId + "," + shift.id() + ",2026-04-04,PROD_E,-50,件,\n" +   // invalid: negative
                orgNodeId + "," + shift.id() + ",2026-04-05,PROD_E,400,件,\n";

        BulkImportResult result = entrySvc.importCsv(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                "test.csv");

        assertThat(result.total()).isEqualTo(5);
        assertThat(result.succeeded()).isEqualTo(4);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).rowNumber()).isEqualTo(4);
    }

    // ---- TestApp configuration ----

    static class TestPermissionResolver implements PermissionResolver {
        private boolean adminMode = true;
        private Set<Long> visibleNodeIds = null;

        public void setAdminMode(boolean adminMode) { this.adminMode = adminMode; }
        public void setVisibleNodeIds(Set<Long> ids) { this.visibleNodeIds = ids; }

        @Override
        public Set<Long> visibleNodeIds(Long userId) {
            return adminMode ? ALL_NODE_IDS_MARKER : (visibleNodeIds != null ? visibleNodeIds : Set.of());
        }

        @Override
        public boolean canAccess(Long userId, Long orgNodeId) { return adminMode; }

        @Override
        public boolean hasAllNodes(Set<Long> v) { return v == ALL_NODE_IDS_MARKER; }

        @Override
        public Long currentUserId() { return 1L; }
    }

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.ems.production.entity")
    @EnableJpaRepositories(basePackages = "com.ems.production.repository")
    @ComponentScan(basePackages = "com.ems.production.service")
    static class TestApp {

        @Bean
        AuditContext auditContext() {
            return new AuditContext() {
                public Long currentUserId()     { return 1L; }
                public String currentUsername() { return "tester"; }
                public String currentIp()       { return "127.0.0.1"; }
                public String currentUserAgent(){ return "it"; }
            };
        }

        @Bean
        TestPermissionResolver testPermissionResolver() {
            return new TestPermissionResolver();
        }

        @Bean
        @Primary
        PermissionResolver permissionResolver(TestPermissionResolver r) {
            return r;
        }
    }
}
