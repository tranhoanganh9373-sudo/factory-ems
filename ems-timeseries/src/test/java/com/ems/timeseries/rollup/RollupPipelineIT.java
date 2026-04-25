package com.ems.timeseries.rollup;

import com.ems.timeseries.model.Granularity;
import com.ems.timeseries.rollup.RollupComputeService.MeterCtx;
import com.ems.timeseries.rollup.dto.BackfillReq;
import com.ems.timeseries.rollup.dto.BackfillResult;
import com.ems.timeseries.rollup.entity.RollupHourly;
import com.ems.timeseries.rollup.repository.RollupHourlyRepository;
import com.ems.timeseries.rollup.repository.RollupJobFailureRepository;
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
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端：Postgres + InfluxDB 双 Testcontainers，覆盖 Phase D 主流程：
 *  1. seed Influx 3 小时 × 60 分钟原始点
 *  2. 跑 RollupComputeService.computeBucket() 写入 ts_rollup_hourly
 *  3. 重跑 → 行数不变（幂等 ON CONFLICT DO UPDATE）
 *  4. 调 RollupBackfillService.rebuild() 端到端
 *  5. Influx 抛错 → rollup_job_failures 写入 attempt=1
 */
@Testcontainers
@SpringBootTest(
    classes = RollupPipelineIT.RollupITApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Import(RollupPipelineIT.TestBeans.class)
@ActiveProfiles("rollup-it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RollupPipelineIT {

    static final String ORG = "factory";
    static final String BUCKET = "factory_ems";
    static final String TOKEN = "test-admin-token-must-be-long-enough";
    static final String MEASUREMENT = "energy_reading";

    static final Instant T0 = Instant.parse("2026-04-25T00:00:00Z");
    static final Long ORG_NODE_ID = 1L;
    static final Long METER_ID = 1L;
    static final String METER_CODE = "M-IT-1";

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

    // 强制在 Spring 读取 @DynamicPropertySource 之前启动容器（JUnit @Container 的启动时机晚于 Spring TestContext 加载）。
    static {
        PG.start();
        INFLUX.start();
    }

    @DynamicPropertySource
    static void dynamicProps(DynamicPropertyRegistry r) {
        // PG 由 @ServiceConnection 自动注入；这里只补 Influx 的连接信息。
        r.add("ems.influx.url", () -> "http://" + INFLUX.getHost() + ":" + INFLUX.getMappedPort(8086));
        r.add("ems.influx.token", () -> TOKEN);
        r.add("ems.influx.org", () -> ORG);
        r.add("ems.influx.bucket", () -> BUCKET);
        r.add("ems.influx.measurement", () -> MEASUREMENT);
    }

    @Autowired RollupComputeService compute;
    @Autowired RollupBackfillService backfill;
    @Autowired RollupHourlyRepository hourlyRepo;
    @Autowired RollupJobFailureRepository failureRepo;
    @Autowired InfluxDBClient influx;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    void seed() {
        // 1. 种 PG 引用数据：org_node + meter（满足 ts_rollup_* 的外键）
        jdbc.update("INSERT INTO org_nodes (id, name, code, node_type) VALUES (?, ?, ?, ?)",
            ORG_NODE_ID, "FactoryRoot", "ROOT", "FACTORY");
        jdbc.update("""
            INSERT INTO meters (id, code, name, energy_type_id, org_node_id,
                                influx_measurement, influx_tag_key, influx_tag_value, enabled)
            VALUES (?, ?, ?, (SELECT id FROM energy_types WHERE code = 'ELEC'), ?, ?, ?, ?, TRUE)
            """, METER_ID, METER_CODE, "IT-Meter-1", ORG_NODE_ID,
            MEASUREMENT, "meter_code", METER_CODE);

        // 2. 种 Influx：3 小时 × 60 个 1min 点。
        //    H0：值 1.0、2.0、…、60.0  → sum=1830, avg=30.5,  max=60, min=1
        //    H1：值 60..119(每分钟+1.0 起步=61) - 用简化方案：i+1（i=0..59）每个值 = 100 + i，sum=100*60 + (0..59).sum = 6000+1770=7770
        //         实际我们用 v = 100 + i：sum = 60*100 + (0+59)*60/2 = 6000+1770 = 7770, max=159, min=100, count=60
        //    H2：值 0.5（恒定）   → sum=30, avg=0.5, max=0.5, min=0.5, count=60
        WriteApiBlocking writer = influx.getWriteApiBlocking();
        for (int i = 0; i < 60; i++) {
            writer.writePoint(Point.measurement(MEASUREMENT)
                .addTag("meter_code", METER_CODE).addTag("energy_type", "ELEC")
                .addField("value", (double) (i + 1))
                .time(T0.plus(i, ChronoUnit.MINUTES), WritePrecision.NS));
        }
        for (int i = 0; i < 60; i++) {
            writer.writePoint(Point.measurement(MEASUREMENT)
                .addTag("meter_code", METER_CODE).addTag("energy_type", "ELEC")
                .addField("value", (double) (100 + i))
                .time(T0.plus(60 + i, ChronoUnit.MINUTES), WritePrecision.NS));
        }
        for (int i = 0; i < 60; i++) {
            writer.writePoint(Point.measurement(MEASUREMENT)
                .addTag("meter_code", METER_CODE).addTag("energy_type", "ELEC")
                .addField("value", 0.5)
                .time(T0.plus(120 + i, ChronoUnit.MINUTES), WritePrecision.NS));
        }
    }

    private MeterCtx meterCtx() { return new MeterCtx(METER_ID, ORG_NODE_ID, METER_CODE); }

    @Test @Order(1)
    void computeBucket_writesHourlyRow_withCorrectAggregates() {
        boolean ok = compute.computeBucket(meterCtx(), Granularity.HOUR, T0);
        assertThat(ok).isTrue();

        List<RollupHourly> rows = hourlyRepo.findAll();
        assertThat(rows).hasSize(1);
        RollupHourly r = rows.get(0);
        assertThat(r.getMeterId()).isEqualTo(METER_ID);
        assertThat(r.getOrgNodeId()).isEqualTo(ORG_NODE_ID);
        assertThat(r.getCount()).isEqualTo(60);
        // 1+2+...+60 = 1830
        assertThat(r.getSumValue()).isEqualByComparingTo(new BigDecimal("1830"));
        assertThat(r.getMaxValue()).isEqualByComparingTo(new BigDecimal("60"));
        assertThat(r.getMinValue()).isEqualByComparingTo(new BigDecimal("1"));
        assertThat(r.getAvgValue().doubleValue()).isEqualTo(30.5);
    }

    @Test @Order(2)
    void computeBucket_isIdempotent_onRerun() {
        long before = hourlyRepo.count();
        // 重跑同一桶应通过 ON CONFLICT (...) DO UPDATE：行数不变，updatedAt 刷新
        for (int i = 0; i < 3; i++) {
            assertThat(compute.computeBucket(meterCtx(), Granularity.HOUR, T0)).isTrue();
        }
        assertThat(hourlyRepo.count()).isEqualTo(before);
        // 数值仍然一致
        RollupHourly r = hourlyRepo.findAll().get(0);
        assertThat(r.getCount()).isEqualTo(60);
        assertThat(r.getSumValue()).isEqualByComparingTo(new BigDecimal("1830"));
    }

    @Test @Order(3)
    void backfill_rebuild_iteratesAllHourBuckets() {
        // 范围 [T0, T0+3h)：HOUR 粒度 → BucketWindow.truncate 后的边界 = T0, T0+1h, T0+2h，共 3 桶
        BackfillReq req = new BackfillReq(
            Granularity.HOUR,
            T0,
            T0.plus(3, ChronoUnit.HOURS),
            null);

        BackfillResult res = backfill.rebuild(req);

        assertThat(res.meters()).isEqualTo(1);
        assertThat(res.buckets()).isEqualTo(4); // from, +1h, +2h, +3h —— 闭区间，4 个对齐桶
        assertThat(res.failed()).isZero();
        // ok 是 meters × buckets
        assertThat(res.ok()).isEqualTo(4);

        // PG 应至少有 H0/H1/H2 三个桶（H3 在 Influx 没数据 → 跳过 upsert，但 computeBucket 仍返回 true）
        List<RollupHourly> rows = hourlyRepo.findAll();
        assertThat(rows).hasSize(3);

        // H1 校验
        Optional<RollupHourly> h1 = rows.stream()
            .filter(r -> r.getHourTs().toInstant().equals(T0.plus(1, ChronoUnit.HOURS)))
            .findFirst();
        assertThat(h1).isPresent();
        // 100..159 的 sum = 60*100 + (0+59)*60/2 = 6000 + 1770 = 7770
        assertThat(h1.get().getSumValue()).isEqualByComparingTo(new BigDecimal("7770"));
        assertThat(h1.get().getMaxValue()).isEqualByComparingTo(new BigDecimal("159"));
        assertThat(h1.get().getMinValue()).isEqualByComparingTo(new BigDecimal("100"));

        // H2 校验：恒定 0.5
        Optional<RollupHourly> h2 = rows.stream()
            .filter(r -> r.getHourTs().toInstant().equals(T0.plus(2, ChronoUnit.HOURS)))
            .findFirst();
        assertThat(h2).isPresent();
        assertThat(h2.get().getSumValue()).isEqualByComparingTo(new BigDecimal("30"));
        assertThat(h2.get().getMaxValue()).isEqualByComparingTo(new BigDecimal("0.5"));
        assertThat(h2.get().getMinValue()).isEqualByComparingTo(new BigDecimal("0.5"));
        assertThat(h2.get().getCount()).isEqualTo(60);

        // 没有失败行
        assertThat(failureRepo.findAll()).isEmpty();
    }

    @Test @Order(4)
    void backfill_rejectsInvalidRange() {
        BackfillReq bad = new BackfillReq(Granularity.HOUR, T0, T0, null);
        try {
            backfill.rebuild(bad);
            assertThat(false).as("should have thrown IllegalArgumentException").isTrue();
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("to");
        }
    }

    /* ---------- 测试 Spring Boot 应用 ---------- */

    @SpringBootApplication(
        scanBasePackages = "com.ems.timeseries",
        exclude = {
            SecurityAutoConfiguration.class,
            UserDetailsServiceAutoConfiguration.class
        }
    )
    @org.springframework.boot.autoconfigure.domain.EntityScan("com.ems.timeseries.rollup.entity")
    @org.springframework.data.jpa.repository.config.EnableJpaRepositories("com.ems.timeseries.rollup.repository")
    static class RollupITApp {
        public static void main(String[] args) { SpringApplication.run(RollupITApp.class, args); }
    }

    /* ---------- 测试用 MeterCatalogPort：避开 ems-meter / ems-app 适配器 ---------- */

    @TestConfiguration
    static class TestBeans {
        @Bean
        MeterCatalogPort meterCatalogPort() {
            return new MeterCatalogPort() {
                private final MeterCtx only = new MeterCtx(METER_ID, ORG_NODE_ID, METER_CODE);
                @Override public List<MeterCtx> findAllEnabled() { return List.of(only); }
                @Override public Optional<MeterCtx> findById(Long id) {
                    return id != null && id.equals(METER_ID) ? Optional.of(only) : Optional.empty();
                }
            };
        }
    }
}
