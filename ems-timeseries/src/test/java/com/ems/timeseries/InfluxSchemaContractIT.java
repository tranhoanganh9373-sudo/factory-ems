package com.ems.timeseries;

import com.ems.timeseries.config.InfluxProperties;
import com.ems.timeseries.model.Granularity;
import com.ems.timeseries.model.MeterPoint;
import com.ems.timeseries.model.TimeRange;
import com.ems.timeseries.query.NoopRollupReader;
import com.ems.timeseries.query.TimeSeriesQueryService;
import com.ems.timeseries.query.TimeSeriesQueryService.MeterRef;
import com.ems.timeseries.query.impl.TimeSeriesQueryServiceImpl;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约测试：起真 InfluxDB 2.7 容器 → 写测试数据 → 调 TimeSeriesQueryService → 断言聚合结果与时间戳。
 * 用于及早发现外部采集 schema 漂移与 Flux 查询行为变更。
 */
@Testcontainers
class InfluxSchemaContractIT {

    static final String ORG = "factory";
    static final String BUCKET = "factory_ems";
    static final String TOKEN = "test-admin-token-must-be-long-enough";
    static final String MEASUREMENT = "energy_reading";

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

    static InfluxDBClient client;
    static TimeSeriesQueryService svc;

    static final Instant T0 = Instant.parse("2026-04-25T00:00:00Z");

    @BeforeAll
    static void seed() {
        String url = "http://" + INFLUX.getHost() + ":" + INFLUX.getMappedPort(8086);
        client = InfluxDBClientFactory.create(url, TOKEN.toCharArray(), ORG, BUCKET);

        InfluxProperties props = new InfluxProperties();
        props.setUrl(url); props.setToken(TOKEN); props.setOrg(ORG); props.setBucket(BUCKET);
        props.setMeasurement(MEASUREMENT);
        svc = new TimeSeriesQueryServiceImpl(client, props, new NoopRollupReader());

        WriteApiBlocking writer = client.getWriteApiBlocking();
        // 写 ELEC 测点 M1：60 个 1min 点，每个 +1.0
        for (int i = 0; i < 60; i++) {
            writer.writePoint(Point.measurement(MEASUREMENT)
                .addTag("meter_code", "M1")
                .addTag("energy_type", "ELEC")
                .addField("value", 1.0)
                .time(T0.plus(i, ChronoUnit.MINUTES), WritePrecision.NS));
        }
        // 写 ELEC 测点 M2：60 个点，每个 +2.0
        for (int i = 0; i < 60; i++) {
            writer.writePoint(Point.measurement(MEASUREMENT)
                .addTag("meter_code", "M2")
                .addTag("energy_type", "ELEC")
                .addField("value", 2.0)
                .time(T0.plus(i, ChronoUnit.MINUTES), WritePrecision.NS));
        }
        // 写 WATER 测点 W1：60 个点，每个 +0.5
        for (int i = 0; i < 60; i++) {
            writer.writePoint(Point.measurement(MEASUREMENT)
                .addTag("meter_code", "W1")
                .addTag("energy_type", "WATER")
                .addField("value", 0.5)
                .time(T0.plus(i, ChronoUnit.MINUTES), WritePrecision.NS));
        }
    }

    @AfterAll
    static void closeClient() {
        if (client != null) client.close();
    }

    @Test
    void minute_query_returnsAllPoints() {
        TimeRange range = new TimeRange(T0, T0.plus(60, ChronoUnit.MINUTES));
        List<MeterPoint> out = svc.queryByMeter(
            List.of(new MeterRef(1L, "M1", "ELEC")), range, Granularity.MINUTE);

        assertThat(out).hasSize(1);
        // aggregateWindow with createEmpty:false 会跳过空桶，期望 60 个
        assertThat(out.get(0).points()).hasSize(60);
        assertThat(out.get(0).points().get(0).value()).isEqualTo(1.0);
    }

    @Test
    void hour_query_aggregatesToOneBucket() {
        TimeRange range = new TimeRange(T0, T0.plus(60, ChronoUnit.MINUTES));
        List<MeterPoint> out = svc.queryByMeter(
            List.of(new MeterRef(1L, "M1", "ELEC")), range, Granularity.HOUR);

        // 60 分钟 × 1.0 sum → 一个 hourly 桶 = 60.0
        assertThat(out).hasSize(1);
        assertThat(out.get(0).points()).hasSize(1);
        assertThat(out.get(0).points().get(0).value()).isEqualTo(60.0);
    }

    @Test
    void sumByMeter_returnsTotalPerMeter() {
        TimeRange range = new TimeRange(T0, T0.plus(60, ChronoUnit.MINUTES));
        Map<Long, Double> out = svc.sumByMeter(List.of(
            new MeterRef(1L, "M1", "ELEC"),
            new MeterRef(2L, "M2", "ELEC")
        ), range);

        assertThat(out).containsEntry(1L, 60.0).containsEntry(2L, 120.0);
    }

    @Test
    void sumByEnergyType_groupsCorrectly() {
        TimeRange range = new TimeRange(T0, T0.plus(60, ChronoUnit.MINUTES));
        Map<String, Double> out = svc.sumByEnergyType(List.of(
            new MeterRef(1L, "M1", "ELEC"),
            new MeterRef(2L, "M2", "ELEC"),
            new MeterRef(3L, "W1", "WATER")
        ), range);

        // ELEC = 60 + 120 = 180；WATER = 30 (60 × 0.5)
        assertThat(out).containsEntry("ELEC", 180.0).containsEntry("WATER", 30.0);
    }

    @Test
    void unknownMeterCode_returnsZeroPoints() {
        TimeRange range = new TimeRange(T0, T0.plus(60, ChronoUnit.MINUTES));
        List<MeterPoint> out = svc.queryByMeter(
            List.of(new MeterRef(99L, "GHOST-M99", "ELEC")), range, Granularity.MINUTE);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).points()).isEmpty();
    }
}
