package com.ems.dashboard;

import com.ems.core.exception.ForbiddenException;
import com.ems.core.security.PermissionResolver;
import com.ems.dashboard.dto.*;
import com.ems.dashboard.service.DashboardService;
import com.ems.timeseries.rollup.MeterCatalogPort;
import com.ems.timeseries.rollup.RollupComputeService;
import com.ems.orgtree.dto.CreateOrgNodeReq;
import com.ems.orgtree.dto.MoveOrgNodeReq;
import com.ems.orgtree.dto.OrgNodeDTO;
import com.ems.orgtree.dto.UpdateOrgNodeReq;
import com.ems.orgtree.service.OrgNodeService;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 端到端：DashboardService → DashboardSupport → 真 MeterRepository / EnergyTypeRepository / TimeSeriesQueryService → PG + Influx。
 *
 * 配置策略：
 *  - 仅扫描 com.ems.dashboard / com.ems.timeseries（不拉 ems-orgtree / ems-auth / ems-meter service 层）
 *  - MeterRepository / EnergyTypeRepository 通过 @EnableJpaRepositories + @EntityScan 单独引入
 *  - PermissionResolver / OrgNodeService 由 TestBeans 提供桩，由测试 setter 控制 ADMIN/VIEWER 行为
 */
@Testcontainers
@SpringBootTest(
    classes = DashboardIT.DashboardITApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Import(DashboardIT.TestBeans.class)
@ActiveProfiles("dashboard-it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DashboardIT {

    static final String ORG = "factory";
    static final String BUCKET = "factory_ems";
    static final String TOKEN = "test-admin-token-must-be-long-enough";
    static final String MEASUREMENT = "energy_reading";

    static final Instant T0 = Instant.parse("2026-04-25T00:00:00Z");
    // 父节点 ROOT (id=1), 两个子节点 LineA (id=2), LineB (id=3)
    static final long ROOT = 1L, LINE_A = 2L, LINE_B = 3L;
    // 4 个测点：M1/M2 = ELEC，M3 = WATER；M1/M3 在 LineA，M2 在 LineB
    static final long M1 = 1L, M2 = 2L, M3 = 3L;

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("ems_test")
        .withUsername("ems")
        .withPassword("ems");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> INFLUX = new GenericContainer<>(DockerImageName.parse("influxdb:2.7-alpine"))
        .withExposedPorts(8086)
        .withEnv("DOCKER_INFLUXDB_INIT_MODE", "setup")
        .withEnv("DOCKER_INFLUXDB_INIT_USERNAME", "admin")
        .withEnv("DOCKER_INFLUXDB_INIT_PASSWORD", "adminpass")
        .withEnv("DOCKER_INFLUXDB_INIT_ORG", ORG)
        .withEnv("DOCKER_INFLUXDB_INIT_BUCKET", BUCKET)
        .withEnv("DOCKER_INFLUXDB_INIT_ADMIN_TOKEN", TOKEN)
        .waitingFor(Wait.forHttp("/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));

    static {
        PG.start();
        INFLUX.start();
    }

    @DynamicPropertySource
    static void dynamicProps(DynamicPropertyRegistry r) {
        r.add("ems.influx.url", () -> "http://" + INFLUX.getHost() + ":" + INFLUX.getMappedPort(8086));
        r.add("ems.influx.token", () -> TOKEN);
        r.add("ems.influx.org", () -> ORG);
        r.add("ems.influx.bucket", () -> BUCKET);
        r.add("ems.influx.measurement", () -> MEASUREMENT);
    }

    @Autowired DashboardService service;
    @Autowired InfluxDBClient influx;
    @Autowired JdbcTemplate jdbc;
    @Autowired StubPermissionResolver permissions;
    @Autowired StubOrgNodeService orgNodes;

    @BeforeAll
    void seed() {
        // org_nodes
        jdbc.update("INSERT INTO org_nodes (id, parent_id, name, code, node_type) VALUES (?, NULL, 'Factory', 'ROOT', 'FACTORY')", ROOT);
        jdbc.update("INSERT INTO org_nodes (id, parent_id, name, code, node_type) VALUES (?, ?, 'LineA', 'LINE_A', 'LINE')", LINE_A, ROOT);
        jdbc.update("INSERT INTO org_nodes (id, parent_id, name, code, node_type) VALUES (?, ?, 'LineB', 'LINE_B', 'LINE')", LINE_B, ROOT);
        // meters
        insertMeter(M1, "M-1", "Meter-1", "ELEC", LINE_A, "M-1");
        insertMeter(M2, "M-2", "Meter-2", "ELEC", LINE_B, "M-2");
        insertMeter(M3, "M-3", "Meter-3", "WATER", LINE_A, "M-3");

        // 闭包关系（stub OrgNodeService 不读 PG）
        orgNodes.setDescendants(Map.of(
            ROOT, List.of(ROOT, LINE_A, LINE_B),
            LINE_A, List.of(LINE_A),
            LINE_B, List.of(LINE_B)
        ));

        // 默认登录态：ADMIN（uid=1）
        permissions.set(1L, PermissionResolver.ALL_NODE_IDS_MARKER);

        // Influx：在 [T0, T0+2h) 内每分钟一个点，每个测点不同值
        WriteApiBlocking writer = influx.getWriteApiBlocking();
        for (int i = 0; i < 120; i++) {
            Instant ts = T0.plus(i, ChronoUnit.MINUTES);
            writePoint(writer, "M-1", "ELEC",  10.0, ts);
            writePoint(writer, "M-2", "ELEC",  20.0, ts);
            writePoint(writer, "M-3", "WATER",  5.0, ts);
        }
    }

    /* ---------------- ① KPI ---------------- */

    @Test @Order(1)
    void kpi_admin_aggregatesAllEnergyTypes() {
        // 取 [T0, T0+2h) 全量
        var q = new RangeQuery(RangeType.CUSTOM, T0, T0.plus(2, ChronoUnit.HOURS), null, null);
        List<KpiDTO> out = service.kpi(q);

        // 2 hours × 60 minutes
        // ELEC 总和 = (10 + 20) × 120 = 3600
        // WATER 总和 = 5 × 120 = 600
        assertThat(out).hasSize(2);
        var elec = out.stream().filter(k -> "ELEC".equals(k.energyType())).findFirst().orElseThrow();
        assertThat(elec.unit()).isEqualTo("kWh");
        assertThat(elec.total()).isEqualTo(3600.0);
        // 上一窗口 + 去年同期均无数据 → mom/yoy 均为 null
        assertThat(elec.mom()).isNull();
        assertThat(elec.yoy()).isNull();

        var water = out.stream().filter(k -> "WATER".equals(k.energyType())).findFirst().orElseThrow();
        assertThat(water.unit()).isEqualTo("m3");
        assertThat(water.total()).isEqualTo(600.0);
    }

    /* ---------------- ② Realtime ---------------- */

    @Test @Order(2)
    void realtime_groupsByEnergyType_andSumsBuckets() {
        var q = new RangeQuery(RangeType.CUSTOM, T0, T0.plus(2, ChronoUnit.HOURS), null, null);
        List<SeriesDTO> out = service.realtimeSeries(q);

        assertThat(out).hasSize(2);
        var elec = out.stream().filter(s -> "ELEC".equals(s.energyType())).findFirst().orElseThrow();
        // 2 个小时桶
        assertThat(elec.points()).hasSize(2);
        // 每个小时桶 ELEC sum = 60×10 + 60×20 = 1800（M1 + M2）
        for (SeriesDTO.Bucket b : elec.points()) {
            assertThat(b.value()).isEqualTo(1800.0);
        }
        var water = out.stream().filter(s -> "WATER".equals(s.energyType())).findFirst().orElseThrow();
        for (SeriesDTO.Bucket b : water.points()) {
            assertThat(b.value()).isEqualTo(300.0); // 60 × 5
        }
    }

    /* ---------------- ③ Composition ---------------- */

    @Test @Order(3)
    void composition_returnsCorrectShares() {
        var q = new RangeQuery(RangeType.CUSTOM, T0, T0.plus(2, ChronoUnit.HOURS), null, null);
        List<CompositionDTO> out = service.energyComposition(q);

        assertThat(out).hasSize(2);
        // ELEC 3600 / 4200 = 6/7；WATER 600 / 4200 = 1/7
        var elec = out.stream().filter(c -> "ELEC".equals(c.energyType())).findFirst().orElseThrow();
        assertThat(elec.total()).isEqualTo(3600.0);
        assertThat(elec.share()).isCloseTo(3600.0 / 4200.0, org.assertj.core.data.Offset.offset(1e-9));
        var water = out.stream().filter(c -> "WATER".equals(c.energyType())).findFirst().orElseThrow();
        assertThat(water.share()).isCloseTo(600.0 / 4200.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    /* ---------------- ④ Meter detail ---------------- */

    @Test @Order(4)
    void meterDetail_singleMeter_total_and_curve() {
        var q = new RangeQuery(RangeType.CUSTOM, T0, T0.plus(2, ChronoUnit.HOURS), null, null);
        MeterDetailDTO out = service.meterDetail(M2, q);
        assertThat(out.meterId()).isEqualTo(M2);
        assertThat(out.energyTypeCode()).isEqualTo("ELEC");
        assertThat(out.total()).isEqualTo(20.0 * 120);
        assertThat(out.series()).hasSize(2);
        for (var p : out.series()) assertThat(p.value()).isEqualTo(20.0 * 60);
    }

    /* ---------------- ⑤ Top-N ---------------- */

    @Test @Order(5)
    void topN_returnsAllMeters_sortedByTotal() {
        var q = new RangeQuery(RangeType.CUSTOM, T0, T0.plus(2, ChronoUnit.HOURS), null, null);
        List<TopNItemDTO> out = service.topN(q, 10);
        assertThat(out).hasSize(3);
        // M2 (=2400) > M1 (=1200) > M3 (=600)
        assertThat(out.get(0).meterId()).isEqualTo(M2);
        assertThat(out.get(0).total()).isEqualTo(2400.0);
        assertThat(out.get(1).meterId()).isEqualTo(M1);
        assertThat(out.get(2).meterId()).isEqualTo(M3);
    }

    @Test @Order(6)
    void topN_withEnergyTypeFilter_excludesOthers() {
        var q = new RangeQuery(RangeType.CUSTOM, T0, T0.plus(2, ChronoUnit.HOURS), null, "ELEC");
        List<TopNItemDTO> out = service.topN(q, 10);
        assertThat(out).hasSize(2);
        assertThat(out.stream().map(TopNItemDTO::energyTypeCode))
            .containsOnly("ELEC");
    }

    /* ---------------- 权限过滤 ---------------- */

    @Test @Order(7)
    void viewer_seesOnlyVisibleMeters() {
        // 切换到 VIEWER：只看 LineB
        permissions.set(2L, Set.of(LINE_B));
        try {
            var q = new RangeQuery(RangeType.CUSTOM, T0, T0.plus(2, ChronoUnit.HOURS), null, null);
            List<TopNItemDTO> out = service.topN(q, 10);
            assertThat(out).hasSize(1);
            assertThat(out.get(0).meterId()).isEqualTo(M2);
        } finally {
            permissions.set(1L, PermissionResolver.ALL_NODE_IDS_MARKER);
        }
    }

    @Test @Order(8)
    void viewer_accessingForbiddenOrgNode_throws() {
        permissions.set(2L, Set.of(LINE_B));
        try {
            var q = new RangeQuery(RangeType.CUSTOM, T0, T0.plus(2, ChronoUnit.HOURS), LINE_A, null);
            assertThatThrownBy(() -> service.kpi(q)).isInstanceOf(ForbiddenException.class);
        } finally {
            permissions.set(1L, PermissionResolver.ALL_NODE_IDS_MARKER);
        }
    }

    @Test @Order(9)
    void viewer_meterDetail_outsideScope_throws() {
        permissions.set(2L, Set.of(LINE_B));
        try {
            var q = new RangeQuery(RangeType.CUSTOM, T0, T0.plus(2, ChronoUnit.HOURS), null, null);
            assertThatThrownBy(() -> service.meterDetail(M3 /* LineA */, q))
                .isInstanceOf(ForbiddenException.class);
        } finally {
            permissions.set(1L, PermissionResolver.ALL_NODE_IDS_MARKER);
        }
    }

    /* ---------------- helpers ---------------- */

    private void insertMeter(long id, String code, String name, String type,
                             long orgNodeId, String tagValue) {
        jdbc.update("""
            INSERT INTO meters (id, code, name, energy_type_id, org_node_id,
                                influx_measurement, influx_tag_key, influx_tag_value, enabled)
            VALUES (?, ?, ?, (SELECT id FROM energy_types WHERE code = ?), ?, ?, ?, ?, TRUE)
            """, id, code, name, type, orgNodeId, MEASUREMENT, "meter_code", tagValue);
    }

    private static void writePoint(WriteApiBlocking writer, String tag, String type, double v, Instant ts) {
        writer.writePoint(Point.measurement(MEASUREMENT)
            .addTag("meter_code", tag)
            .addTag("energy_type", type)
            .addField("value", v)
            .time(ts, WritePrecision.NS));
    }

    /* ---------------- IT 应用上下文 ---------------- */

    @SpringBootApplication(
        scanBasePackages = {"com.ems.dashboard", "com.ems.timeseries"},
        exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class}
    )
    @EntityScan({"com.ems.timeseries.rollup.entity", "com.ems.meter.entity"})
    @EnableJpaRepositories({"com.ems.timeseries.rollup.repository", "com.ems.meter.repository"})
    static class DashboardITApp {
        public static void main(String[] args) { SpringApplication.run(DashboardITApp.class, args); }
    }

    /* ---------------- 桩 Beans ---------------- */

    @TestConfiguration
    static class TestBeans {
        @Bean PermissionResolver permissionResolver() { return new StubPermissionResolver(); }
        @Bean OrgNodeService orgNodeService() { return new StubOrgNodeService(); }
        @Bean MeterCatalogPort meterCatalogPort() {
            return new MeterCatalogPort() {
                @Override public List<RollupComputeService.MeterCtx> findAllEnabled() { return List.of(); }
                @Override public Optional<RollupComputeService.MeterCtx> findById(Long meterId) { return Optional.empty(); }
            };
        }
    }

    static class StubPermissionResolver implements PermissionResolver {
        private volatile Long uid;
        private volatile Set<Long> visible;

        public void set(Long uid, Set<Long> visible) {
            this.uid = uid;
            this.visible = visible;
        }

        @Override public Set<Long> visibleNodeIds(Long userId) { return visible; }
        @Override public boolean canAccess(Long userId, Long orgNodeId) {
            return visible == ALL_NODE_IDS_MARKER || visible.contains(orgNodeId);
        }
        @Override public boolean hasAllNodes(Set<Long> v) { return v == ALL_NODE_IDS_MARKER; }
        @Override public Long currentUserId() { return uid; }
    }

    static class StubOrgNodeService implements OrgNodeService {
        private volatile Map<Long, List<Long>> descendants = Map.of();

        public void setDescendants(Map<Long, List<Long>> map) { this.descendants = map; }

        @Override public List<Long> findDescendantIds(Long id) {
            return descendants.getOrDefault(id, List.of(id));
        }

        @Override public OrgNodeDTO create(CreateOrgNodeReq req) { throw new UnsupportedOperationException(); }
        @Override public OrgNodeDTO update(Long id, UpdateOrgNodeReq req) { throw new UnsupportedOperationException(); }
        @Override public void move(Long id, MoveOrgNodeReq req) { throw new UnsupportedOperationException(); }
        @Override public void delete(Long id) { throw new UnsupportedOperationException(); }
        @Override public OrgNodeDTO getById(Long id) { throw new UnsupportedOperationException(); }
        @Override public List<OrgNodeDTO> getTree(Long rootId) { throw new UnsupportedOperationException(); }
    }
}
