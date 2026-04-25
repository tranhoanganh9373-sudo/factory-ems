package com.ems.report;

import com.ems.core.exception.ForbiddenException;
import com.ems.core.security.PermissionResolver;
import com.ems.report.async.FileTokenStore;
import com.ems.report.controller.ReportController;
import com.ems.report.dto.FileTokenDTO;
import com.ems.report.dto.ReportRequest;
import com.ems.timeseries.model.Granularity;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 端到端：ReportController → ReportService → DashboardSupport → 真 MeterRepository / EnergyTypeRepository / TimeSeriesQueryService → PG + Influx
 *  - 同步 CSV 导出：BOM + 表头 + 行内容
 *  - 异步 fileToken 全流（POST → 轮询 → READY → 下载）
 *  - 权限：viewer 只看自己 orgNode 子树
 */
@Testcontainers
@SpringBootTest(
    classes = ReportIT.ReportITApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Import(ReportIT.TestBeans.class)
@ActiveProfiles("report-it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReportIT {

    static final String ORG = "factory";
    static final String BUCKET = "factory_ems";
    static final String TOKEN = "test-admin-token-must-be-long-enough";
    static final String MEASUREMENT = "energy_reading";

    static final Instant T0 = Instant.parse("2026-04-25T00:00:00Z");
    static final long ROOT = 1L, LINE_A = 2L, LINE_B = 3L;
    static final long M1 = 1L, M2 = 2L, M3 = 3L;

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("ems_test").withUsername("ems").withPassword("ems");

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

    @Autowired ReportController controller;
    @Autowired FileTokenStore store;
    @Autowired InfluxDBClient influx;
    @Autowired JdbcTemplate jdbc;
    @Autowired StubPermissionResolver permissions;
    @Autowired StubOrgNodeService orgNodes;

    @BeforeAll
    void seed() {
        jdbc.update("INSERT INTO org_nodes (id, parent_id, name, code, node_type) VALUES (?, NULL, 'Factory', 'ROOT', 'FACTORY')", ROOT);
        jdbc.update("INSERT INTO org_nodes (id, parent_id, name, code, node_type) VALUES (?, ?, 'LineA', 'LINE_A', 'LINE')", LINE_A, ROOT);
        jdbc.update("INSERT INTO org_nodes (id, parent_id, name, code, node_type) VALUES (?, ?, 'LineB', 'LINE_B', 'LINE')", LINE_B, ROOT);
        insertMeter(M1, "M-1", "Meter-1", "ELEC", LINE_A);
        insertMeter(M2, "M-2", "Meter-2", "ELEC", LINE_B);
        insertMeter(M3, "M-3", "Meter-3", "WATER", LINE_A);

        orgNodes.setDescendants(Map.of(
            ROOT, List.of(ROOT, LINE_A, LINE_B),
            LINE_A, List.of(LINE_A),
            LINE_B, List.of(LINE_B)
        ));
        permissions.set(1L, PermissionResolver.ALL_NODE_IDS_MARKER);

        WriteApiBlocking writer = influx.getWriteApiBlocking();
        // [T0, T0+2h)：M1=10/min, M2=20/min, M3=5/min
        for (int i = 0; i < 120; i++) {
            Instant ts = T0.plus(i, ChronoUnit.MINUTES);
            writePoint(writer, "M-1", "ELEC", 10.0, ts);
            writePoint(writer, "M-2", "ELEC", 20.0, ts);
            writePoint(writer, "M-3", "WATER", 5.0, ts);
        }
    }

    /* ---------------- 同步导出 ---------------- */

    @Test @Order(1)
    void syncExport_returnsBomHeaderAndRows() throws Exception {
        var req = new ReportRequest(T0, T0.plus(2, ChronoUnit.HOURS), Granularity.HOUR, null, null, null);
        var resp = controller.adHoc(req.from(), req.to(), req.granularity(), null, null, null);
        byte[] bytes = drain(resp);
        // BOM
        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        assertThat(bytes[1]).isEqualTo((byte) 0xBB);
        assertThat(bytes[2]).isEqualTo((byte) 0xBF);
        String body = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        // 表头
        assertThat(body).startsWith("timestamp,meter_id,meter_code,meter_name,org_node_id,energy_type,unit,value");
        // 行数 = 3 测点 × 2 桶 = 6
        long rowCount = body.lines().count() - 1; // 减去表头
        assertThat(rowCount).isEqualTo(6L);
        // 至少包含一条 ELEC + 一条 WATER
        assertThat(body).contains(",ELEC,kWh,");
        assertThat(body).contains(",WATER,m3,");
    }

    @Test @Order(2)
    void syncExport_filteredByEnergyType_excludesOthers() throws Exception {
        var resp = controller.adHoc(T0, T0.plus(2, ChronoUnit.HOURS),
            Granularity.HOUR, null, List.of("ELEC"), null);
        byte[] bytes = drain(resp);
        String body = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertThat(body).contains(",ELEC,").doesNotContain(",WATER,");
        long rowCount = body.lines().count() - 1;
        // 2 个 ELEC 测点 × 2 桶 = 4
        assertThat(rowCount).isEqualTo(4L);
    }

    /* ---------------- 异步 fileToken 全流 ---------------- */

    @Test @Order(3)
    void asyncExport_submitPollDownload_succeeds() throws Exception {
        var req = new ReportRequest(T0, T0.plus(2, ChronoUnit.HOURS), Granularity.HOUR, null, null, null);
        ResponseEntity<FileTokenDTO> postResp = controller.submitAsync(req);
        assertThat(postResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        FileTokenDTO submitted = postResp.getBody();
        assertThat(submitted).isNotNull();
        assertThat(submitted.token()).isNotBlank();

        // 轮询直到 READY（最多 10s）
        long deadline = System.currentTimeMillis() + 10_000;
        ResponseEntity<?> getResp;
        while (true) {
            getResp = controller.download(submitted.token());
            if (getResp.getStatusCode() == HttpStatus.OK) break;
            if (System.currentTimeMillis() > deadline)
                throw new AssertionError("Async export did not complete within 10s, last status=" + getResp.getStatusCode());
            Thread.sleep(100);
        }

        // 下载内容
        Object body = getResp.getBody();
        assertThat(body).isInstanceOf(StreamingResponseBody.class);
        var baos = new ByteArrayOutputStream();
        ((StreamingResponseBody) body).writeTo(baos);
        byte[] bytes = baos.toByteArray();
        assertThat(bytes[0]).isEqualTo((byte) 0xEF); // BOM
        String csv = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertThat(csv).startsWith("timestamp,");
        assertThat(csv.lines().count() - 1).isEqualTo(6L);

        // 下载完应清理 token
        assertThat(store.find(submitted.token())).isEmpty();
    }

    @Test @Order(4)
    void asyncExport_unknownToken_returnsGone() {
        var resp = controller.download("does-not-exist-" + System.nanoTime());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    /* ---------------- 权限 ---------------- */

    @Test @Order(5)
    void viewer_seesOnlyVisibleSubtree() throws Exception {
        permissions.set(2L, Set.of(LINE_B));
        try {
            var resp = controller.adHoc(T0, T0.plus(2, ChronoUnit.HOURS), Granularity.HOUR, null, null, null);
            byte[] bytes = drain(resp);
            String body = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
            // 只 M2 (LineB)，2 个桶
            assertThat(body.lines().count() - 1).isEqualTo(2L);
            assertThat(body).contains(",M-2,").doesNotContain(",M-1,").doesNotContain(",M-3,");
        } finally {
            permissions.set(1L, PermissionResolver.ALL_NODE_IDS_MARKER);
        }
    }

    @Test @Order(6)
    void viewer_orgNodeOutsideScope_throws() {
        permissions.set(2L, Set.of(LINE_B));
        try {
            assertThatThrownBy(() ->
                controller.adHoc(T0, T0.plus(2, ChronoUnit.HOURS), Granularity.HOUR, LINE_A, null, null))
                .isInstanceOf(ForbiddenException.class);
        } finally {
            permissions.set(1L, PermissionResolver.ALL_NODE_IDS_MARKER);
        }
    }

    /* ---------------- helpers ---------------- */

    private static byte[] drain(ResponseEntity<StreamingResponseBody> resp) throws Exception {
        var baos = new ByteArrayOutputStream();
        StreamingResponseBody body = resp.getBody();
        assertThat(body).isNotNull();
        body.writeTo(baos);
        return baos.toByteArray();
    }

    private void insertMeter(long id, String code, String name, String type, long orgNodeId) {
        jdbc.update("""
            INSERT INTO meters (id, code, name, energy_type_id, org_node_id,
                                influx_measurement, influx_tag_key, influx_tag_value, enabled)
            VALUES (?, ?, ?, (SELECT id FROM energy_types WHERE code = ?), ?, ?, ?, ?, TRUE)
            """, id, code, name, type, orgNodeId, MEASUREMENT, "meter_code", code);
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
        scanBasePackages = {"com.ems.report", "com.ems.dashboard", "com.ems.timeseries"},
        exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class}
    )
    @EntityScan({"com.ems.timeseries.rollup.entity", "com.ems.meter.entity"})
    @EnableJpaRepositories({"com.ems.timeseries.rollup.repository", "com.ems.meter.repository"})
    static class ReportITApp {
        public static void main(String[] args) { SpringApplication.run(ReportITApp.class, args); }
    }

    @TestConfiguration
    static class TestBeans {
        @Bean PermissionResolver permissionResolver() { return new StubPermissionResolver(); }
        @Bean OrgNodeService orgNodeService() { return new StubOrgNodeService(); }
        @Bean MeterCatalogPort meterCatalogPort() {
            return new MeterCatalogPort() {
                @Override public List<RollupComputeService.MeterCtx> findAllEnabled() { return List.of(); }
                @Override public java.util.Optional<RollupComputeService.MeterCtx> findById(Long meterId) {
                    return java.util.Optional.empty();
                }
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
