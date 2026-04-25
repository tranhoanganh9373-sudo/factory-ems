package com.ems.floorplan;

import com.ems.audit.aspect.AuditContext;
import com.ems.core.exception.BusinessException;
import com.ems.core.security.PermissionResolver;
import com.ems.floorplan.dto.*;
import com.ems.floorplan.repository.FloorplanPointRepository;
import com.ems.floorplan.repository.FloorplanRepository;
import com.ems.floorplan.service.FloorplanService;
import com.ems.floorplan.service.impl.FloorplanServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = FloorplanServiceIT.TestApp.class)
@Testcontainers
@ActiveProfiles("test")
class FloorplanServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("ems.floorplan.base-dir", () -> tempDir.toAbsolutePath().toString());
        registry.add("ems.floorplan.max-bytes", () -> "100");
    }

    @Autowired FloorplanService svc;
    @Autowired FloorplanRepository floorplanRepo;
    @Autowired FloorplanPointRepository pointRepo;
    @Autowired JdbcTemplate jdbc;

    Long orgNodeId;
    Long meterId1;
    Long meterId2;
    byte[] pngBytes;

    @BeforeEach
    void setUp() throws Exception {
        pointRepo.deleteAll();
        floorplanRepo.deleteAll();
        jdbc.update("DELETE FROM meters WHERE code IN ('MTR-001', 'MTR-002')");
        jdbc.update("DELETE FROM org_nodes WHERE code = 'TEST_FACTORY'");

        // Insert a minimal org_node for FK
        orgNodeId = jdbc.queryForObject(
                "INSERT INTO org_nodes (name, code, node_type) VALUES ('Test Factory', 'TEST_FACTORY', 'FACTORY') RETURNING id",
                Long.class);

        // Insert energy type if not present
        Long energyTypeId;
        try {
            energyTypeId = jdbc.queryForObject("SELECT id FROM energy_types WHERE code = 'ELEC'", Long.class);
        } catch (Exception e) {
            energyTypeId = jdbc.queryForObject(
                    "INSERT INTO energy_types (code, name, unit) VALUES ('ELEC', '电', 'kWh') RETURNING id",
                    Long.class);
        }

        // Insert two meters for FK in floorplan_points
        meterId1 = jdbc.queryForObject(
                "INSERT INTO meters (code, name, energy_type_id, org_node_id, influx_measurement, influx_tag_key, influx_tag_value) " +
                "VALUES ('MTR-001', 'Meter 1', ?, ?, 'power', 'device', 'mtr001') RETURNING id",
                Long.class, energyTypeId, orgNodeId);

        meterId2 = jdbc.queryForObject(
                "INSERT INTO meters (code, name, energy_type_id, org_node_id, influx_measurement, influx_tag_key, influx_tag_value) " +
                "VALUES ('MTR-002', 'Meter 2', ?, ?, 'power', 'device', 'mtr002') RETURNING id",
                Long.class, energyTypeId, orgNodeId);

        // Generate a 200x100 PNG fixture in memory
        BufferedImage img = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        pngBytes = baos.toByteArray();
    }

    // ---- Test 1: upload valid PNG succeeds and dimensions recorded ----

    @Test
    void upload_validPng_succeeds_andDimensionsRecorded() {
        // Override max-bytes to allow this upload: set it larger via a separate test
        // We need to override ems.floorplan.max-bytes for this test specifically.
        // Since DynamicPropertySource sets it to 100 (too small for a PNG), we use
        // a direct service invocation with a larger max. Instead, let's create a
        // MockMultipartFile and test with a tiny valid PNG.
        // The PNG fixture is >100 bytes, so for this test we need a bigger max.
        // We'll use the service directly and set max-bytes via reflection for this test.
        FloorplanServiceImpl impl = (FloorplanServiceImpl) svc;
        setField(impl, "maxBytes", 10_485_760L);

        MockMultipartFile file = new MockMultipartFile(
                "file", "floor.png", "image/png", pngBytes);

        FloorplanDTO dto = svc.upload(file, "Test Floor", orgNodeId);

        assertThat(dto.id()).isNotNull();
        assertThat(dto.widthPx()).isEqualTo(200);
        assertThat(dto.heightPx()).isEqualTo(100);
        assertThat(dto.contentType()).isEqualTo("image/png");
        assertThat(dto.name()).isEqualTo("Test Floor");
        assertThat(dto.fileSizeBytes()).isEqualTo(pngBytes.length);
    }

    // ---- Test 2: upload unsupported type throws ----

    @Test
    void upload_unsupportedType_throws() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "data.txt", "text/plain", "hello world".getBytes());

        assertThatThrownBy(() -> svc.upload(file, "Bad Upload", orgNodeId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的文件类型");
    }

    // ---- Test 3: upload oversize throws ----
    // max-bytes is set to 100 via @DynamicPropertySource; PNG is >100 bytes

    @Test
    void upload_oversize_throws() {
        // Ensure maxBytes is 100 (as set by DynamicPropertySource)
        FloorplanServiceImpl impl = (FloorplanServiceImpl) svc;
        setField(impl, "maxBytes", 100L);

        MockMultipartFile file = new MockMultipartFile(
                "file", "floor.png", "image/png", pngBytes);

        assertThatThrownBy(() -> svc.upload(file, "Big Floor", orgNodeId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("文件大小超过限制");
    }

    // ---- Test 4: setPoints replaces all points ----

    @Test
    void setPoints_replacesAll() {
        FloorplanServiceImpl impl = (FloorplanServiceImpl) svc;
        setField(impl, "maxBytes", 10_485_760L);

        MockMultipartFile file = new MockMultipartFile(
                "file", "floor.png", "image/png", pngBytes);
        FloorplanDTO fp = svc.upload(file, "Floor With Points", orgNodeId);

        // Set 2 points
        SetPointsReq req2 = new SetPointsReq(List.of(
                new SetPointsReq.PointEntry(meterId1, new BigDecimal("0.2"), new BigDecimal("0.3"), "Meter A"),
                new SetPointsReq.PointEntry(meterId2, new BigDecimal("0.7"), new BigDecimal("0.8"), "Meter B")
        ));
        FloorplanWithPointsDTO withTwo = svc.setPoints(fp.id(), req2);
        assertThat(withTwo.points()).hasSize(2);

        // Replace with 1 point
        SetPointsReq req1 = new SetPointsReq(List.of(
                new SetPointsReq.PointEntry(meterId1, new BigDecimal("0.5"), new BigDecimal("0.5"), "Only Meter")
        ));
        FloorplanWithPointsDTO withOne = svc.setPoints(fp.id(), req1);
        assertThat(withOne.points()).hasSize(1);
        assertThat(withOne.points().get(0).meterId()).isEqualTo(meterId1);

        // Verify via getById
        FloorplanWithPointsDTO fetched = svc.getById(fp.id());
        assertThat(fetched.points()).hasSize(1);
    }

    // ---- Test 5: setPoints duplicate meterId throws ----

    @Test
    void setPoints_duplicateMeterId_throws() {
        FloorplanServiceImpl impl = (FloorplanServiceImpl) svc;
        setField(impl, "maxBytes", 10_485_760L);

        MockMultipartFile file = new MockMultipartFile(
                "file", "floor.png", "image/png", pngBytes);
        FloorplanDTO fp = svc.upload(file, "Floor Dup Test", orgNodeId);

        SetPointsReq req = new SetPointsReq(List.of(
                new SetPointsReq.PointEntry(meterId1, new BigDecimal("0.2"), new BigDecimal("0.3"), "A"),
                new SetPointsReq.PointEntry(meterId1, new BigDecimal("0.5"), new BigDecimal("0.6"), "B")
        ));

        assertThatThrownBy(() -> svc.setPoints(fp.id(), req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重复的 meterId");
    }

    // ---- Test 6: delete cascades points ----

    @Test
    void delete_cascadesPoints() {
        FloorplanServiceImpl impl = (FloorplanServiceImpl) svc;
        setField(impl, "maxBytes", 10_485_760L);

        MockMultipartFile file = new MockMultipartFile(
                "file", "floor.png", "image/png", pngBytes);
        FloorplanDTO fp = svc.upload(file, "Floor To Delete", orgNodeId);

        SetPointsReq req = new SetPointsReq(List.of(
                new SetPointsReq.PointEntry(meterId1, new BigDecimal("0.1"), new BigDecimal("0.1"), null)
        ));
        svc.setPoints(fp.id(), req);

        svc.delete(fp.id());

        assertThat(floorplanRepo.findById(fp.id())).isEmpty();
        assertThat(pointRepo.findByFloorplanIdOrderByIdAsc(fp.id())).isEmpty();
    }

    // ---- Test 7: loadImage returns resource matching written bytes ----

    @Test
    void loadImage_returnsResource() throws Exception {
        FloorplanServiceImpl impl = (FloorplanServiceImpl) svc;
        setField(impl, "maxBytes", 10_485_760L);

        MockMultipartFile file = new MockMultipartFile(
                "file", "floor.png", "image/png", pngBytes);
        FloorplanDTO fp = svc.upload(file, "Image Test Floor", orgNodeId);

        org.springframework.core.io.Resource resource = svc.loadImage(fp.id());

        assertThat(resource.exists()).isTrue();
        byte[] readBack = resource.getInputStream().readAllBytes();
        assertThat(readBack).isEqualTo(pngBytes);
    }

    // ---- helper: set private field via reflection ----

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Object actual = target;
            if (target instanceof org.springframework.aop.framework.Advised advised) {
                actual = advised.getTargetSource().getTarget();
            }
            Class<?> c = actual.getClass();
            while (c != null && c != Object.class) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    f.set(actual, value);
                    return;
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
            throw new NoSuchFieldException(fieldName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + fieldName, e);
        }
    }

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackages = {"com.ems.floorplan.entity"})
    @EnableJpaRepositories(basePackages = {"com.ems.floorplan.repository"})
    @ComponentScan(basePackages = {"com.ems.floorplan.service"})
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
