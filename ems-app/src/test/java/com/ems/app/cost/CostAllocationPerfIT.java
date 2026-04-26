package com.ems.app.cost;

import com.ems.app.FactoryEmsApplication;
import com.ems.cost.entity.CostAllocationRun;
import com.ems.cost.entity.RunStatus;
import com.ems.cost.repository.CostAllocationLineRepository;
import com.ems.cost.repository.CostAllocationRunRepository;
import com.ems.cost.service.CostAllocationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Cost-allocation performance baseline.
 *
 * Spec target: 50 rules × 200 target orgs × 1 month (30 days × 24h) hourly data → SUCCESS in ≤ 30s.
 * Seeds via JdbcTemplate (batchUpdate for the 36k rollup rows) to keep seed time bounded.
 */
@SpringBootTest(classes = FactoryEmsApplication.class)
@ActiveProfiles("test")
@Testcontainers
class CostAllocationPerfIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    private static final ZoneOffset Z = ZoneOffset.ofHours(8);
    private static final int RULE_COUNT = 50;
    private static final int ORGS_PER_RULE = 4;
    private static final int TOTAL_ORGS = RULE_COUNT * ORGS_PER_RULE; // 200
    private static final int DAYS = 30;
    private static final OffsetDateTime PERIOD_START = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, Z);
    private static final OffsetDateTime PERIOD_END   = PERIOD_START.plusDays(DAYS);
    private static final long BUDGET_MS = 30_000L;

    @Autowired CostAllocationService service;
    @Autowired CostAllocationRunRepository runRepo;
    @Autowired CostAllocationLineRepository lineRepo;
    @Autowired JdbcTemplate jdbc;

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS) // overall guard incl. seed
    void run_50_rules_200_orgs_30_days_under_30s() {
        wipePerfData();
        SeedIds ids = seed();

        long t0 = System.nanoTime();
        Long runId = service.submitRun(PERIOD_START, PERIOD_END, ids.ruleIds, 1L);
        await().atMost(60, TimeUnit.SECONDS).pollInterval(250, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    CostAllocationRun r = runRepo.findById(runId).orElseThrow();
                    assertThat(r.getStatus()).isEqualTo(RunStatus.SUCCESS);
                });
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        int lineCount = lineRepo.findByRunId(runId).size();
        System.out.printf(
                "PERF: %d rules x %d orgs x %d days -> %d ms (lines=%d)%n",
                RULE_COUNT, TOTAL_ORGS, DAYS, elapsedMs, lineCount);

        assertThat(lineCount).isEqualTo(TOTAL_ORGS);
        assertThat(elapsedMs)
                .as("submitRun → SUCCESS within %d ms", BUDGET_MS)
                .isLessThanOrEqualTo(BUDGET_MS);
    }

    private void wipePerfData() {
        jdbc.update("DELETE FROM cost_allocation_line");
        jdbc.update("DELETE FROM cost_allocation_run");
        jdbc.update("DELETE FROM cost_allocation_rule");
        jdbc.update("DELETE FROM ts_rollup_hourly WHERE meter_id IN (SELECT id FROM meters WHERE code LIKE 'PERF-%')");
        jdbc.update("DELETE FROM meters WHERE code LIKE 'PERF-%'");
        jdbc.update("DELETE FROM tariff_periods WHERE plan_id IN (SELECT id FROM tariff_plans WHERE name = 'PERF-TARIFF')");
        jdbc.update("DELETE FROM tariff_plans WHERE name = 'PERF-TARIFF'");
        jdbc.update("DELETE FROM org_nodes WHERE code LIKE 'PERF-%'");
    }

    private SeedIds seed() {
        Long elecId = jdbc.queryForObject(
                "SELECT id FROM energy_types WHERE code = 'ELEC'", Long.class);

        Long plantId = jdbc.queryForObject(
                "INSERT INTO org_nodes(parent_id, code, name, node_type) " +
                "VALUES (NULL, ?, ?, 'PLANT') RETURNING id",
                Long.class, "PERF-PLANT", "perf 厂区");

        Long[] orgs = new Long[TOTAL_ORGS];
        for (int i = 0; i < TOTAL_ORGS; i++) {
            orgs[i] = jdbc.queryForObject(
                    "INSERT INTO org_nodes(parent_id, code, name, node_type) " +
                    "VALUES (?, ?, ?, 'WORKSHOP') RETURNING id",
                    Long.class, plantId, "PERF-WS-" + i, "perf workshop " + i);
        }

        Long[] meters = new Long[RULE_COUNT];
        for (int i = 0; i < RULE_COUNT; i++) {
            meters[i] = jdbc.queryForObject(
                    "INSERT INTO meters(code, name, energy_type_id, org_node_id, " +
                    " influx_measurement, influx_tag_key, influx_tag_value, enabled) " +
                    "VALUES (?, ?, ?, ?, 'electricity', 'meter_id', ?, true) RETURNING id",
                    Long.class,
                    "PERF-METER-" + i, "perf m " + i, elecId, plantId, "PERF-METER-" + i);
        }

        Long planId = jdbc.queryForObject(
                "INSERT INTO tariff_plans(name, energy_type_id, effective_from, enabled) " +
                "VALUES ('PERF-TARIFF', ?, DATE '2026-01-01', true) RETURNING id",
                Long.class, elecId);
        addPeriod(planId, "SHARP", "19:00", "21:00", "1.20");
        addPeriod(planId, "PEAK",  "08:00", "19:00", "0.80");
        addPeriod(planId, "FLAT",  "21:00", "23:00", "0.50");
        addPeriod(planId, "FLAT",  "06:00", "08:00", "0.50");
        addPeriod(planId, "VALLEY","23:00", "06:00", "0.30");

        seedRollupBatch(meters, plantId);

        List<Long> ruleIds = new ArrayList<>(RULE_COUNT);
        for (int r = 0; r < RULE_COUNT; r++) {
            Long[] targetIds = new Long[ORGS_PER_RULE];
            for (int j = 0; j < ORGS_PER_RULE; j++) {
                targetIds[j] = orgs[r * ORGS_PER_RULE + j];
            }
            ruleIds.add(insertRule(r, meters[r], targetIds));
        }
        return new SeedIds(plantId, orgs, meters, planId, ruleIds);
    }

    private void seedRollupBatch(Long[] meters, Long plantId) {
        int totalHours = DAYS * 24;
        OffsetDateTime now = OffsetDateTime.now();
        BigDecimal v = new BigDecimal("10");
        List<Object[]> batch = new ArrayList<>(meters.length * totalHours);
        for (Long meterId : meters) {
            for (int h = 0; h < totalHours; h++) {
                batch.add(new Object[] {
                        meterId, plantId, PERIOD_START.plusHours(h),
                        v, v, v, v, 60, now
                });
            }
        }
        jdbc.batchUpdate(
                "INSERT INTO ts_rollup_hourly(meter_id, org_node_id, hour_ts, " +
                "sum_value, avg_value, max_value, min_value, count, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                batch);
    }

    private Long insertRule(int idx, Long sourceMeterId, Long[] targetIds) {
        StringBuilder weights = new StringBuilder("{\"basis\":\"FIXED\",\"values\":{");
        for (int j = 0; j < targetIds.length; j++) {
            if (j > 0) weights.append(',');
            weights.append('"').append(targetIds[j]).append("\":")
                    .append(1.0 / targetIds.length);
        }
        weights.append("}}");
        final String weightsJson = weights.toString();

        return jdbc.execute((java.sql.Connection con) -> {
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO cost_allocation_rule(code, name, energy_type, algorithm, " +
                    "source_meter_id, target_org_ids, weights, priority, enabled, effective_from) " +
                    "VALUES (?, ?, 'ELEC', 'PROPORTIONAL', ?, ?, ?::jsonb, 100, true, DATE '2026-01-01') " +
                    "RETURNING id")) {
                Array arr = con.createArrayOf("bigint", targetIds);
                ps.setString(1, "PERF-RULE-" + idx);
                ps.setString(2, "perf rule " + idx);
                ps.setLong(3, sourceMeterId);
                ps.setArray(4, arr);
                ps.setString(5, weightsJson);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    private void addPeriod(Long planId, String type, String start, String end, String price) {
        jdbc.update(
                "INSERT INTO tariff_periods(plan_id, period_type, time_start, time_end, price_per_unit) " +
                "VALUES (?, ?, ?::time, ?::time, ?::numeric)",
                planId, type, start, end, price);
    }

    private record SeedIds(Long plantId, Long[] orgIds, Long[] meterIds,
                           Long planId, List<Long> ruleIds) {}
}
