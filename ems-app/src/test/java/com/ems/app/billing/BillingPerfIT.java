package com.ems.app.billing;

import com.ems.app.FactoryEmsApplication;
import com.ems.billing.dto.BillDTO;
import com.ems.billing.dto.BillPeriodDTO;
import com.ems.billing.service.BillingService;
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
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 2.2 Phase N — bill aggregation performance baseline.
 *
 * Spec target: 200 orgs × 5 energy = 1000 bill rows，账单聚合 + 写库 ≤ 5s。
 * 直接 JDBC seed 1 个 SUCCESS cost run + 1000 cost_allocation_line（绕开 cost 引擎本身的耗时）；
 * 测的是 billing.generateBills 的纯账单聚合+写库时间。
 */
@SpringBootTest(classes = FactoryEmsApplication.class)
@ActiveProfiles("test")
@Testcontainers
class BillingPerfIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    private static final int ORG_COUNT = 200;
    private static final String[] ENERGY_TYPES = {"ELEC", "WATER", "GAS", "STEAM", "OIL"};
    private static final long BUDGET_MS = 5_000L;

    @Autowired BillingService billing;
    @Autowired JdbcTemplate jdbc;

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void generate_bills_200_orgs_5_energies_under_5s() {
        wipePerfData();
        SeedIds ids = seed();

        BillPeriodDTO period = billing.ensurePeriod(YearMonth.of(2026, 3));

        long t0 = System.nanoTime();
        BillPeriodDTO closed = billing.generateBills(period.id(), 1L);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        List<BillDTO> bills = billing.listBills(period.id(), null);
        System.out.printf("PERF: %d orgs x %d energies -> %d bills in %d ms%n",
                ORG_COUNT, ENERGY_TYPES.length, bills.size(), elapsedMs);

        assertThat(closed.status().name()).isEqualTo("CLOSED");
        assertThat(bills).hasSize(ORG_COUNT * ENERGY_TYPES.length);   // 1000
        assertThat(elapsedMs)
                .as("generateBills within %d ms", BUDGET_MS)
                .isLessThanOrEqualTo(BUDGET_MS);

        // sanity：随便挑一个 bill 应聚合非零金额（每条 line 注入 1.0）
        assertThat(bills.get(0).amount()).isPositive();
    }

    private void wipePerfData() {
        jdbc.update("DELETE FROM bill_line");
        jdbc.update("DELETE FROM bill");
        jdbc.update("DELETE FROM bill_period");
        jdbc.update("DELETE FROM cost_allocation_line");
        jdbc.update("DELETE FROM cost_allocation_run");
        jdbc.update("DELETE FROM cost_allocation_rule");
        jdbc.update("DELETE FROM meters WHERE code LIKE 'BPERF-%'");
        jdbc.update("DELETE FROM org_nodes WHERE code LIKE 'BPERF-%'");
    }

    private SeedIds seed() {
        Long elecId = jdbc.queryForObject("SELECT id FROM energy_types WHERE code = 'ELEC'", Long.class);

        Long plantId = jdbc.queryForObject(
                "INSERT INTO org_nodes(parent_id, code, name, node_type) " +
                "VALUES (NULL, ?, ?, 'PLANT') RETURNING id",
                Long.class, "BPERF-PLANT", "billing perf 厂区");

        Long[] orgs = new Long[ORG_COUNT];
        for (int i = 0; i < ORG_COUNT; i++) {
            orgs[i] = jdbc.queryForObject(
                    "INSERT INTO org_nodes(parent_id, code, name, node_type) " +
                    "VALUES (?, ?, ?, 'WORKSHOP') RETURNING id",
                    Long.class, plantId, "BPERF-WS-" + i, "billing perf ws " + i);
        }

        // 一块 dummy meter（cost rule FK 要求）
        Long sourceMeterId = jdbc.queryForObject(
                "INSERT INTO meters(code, name, energy_type_id, org_node_id, " +
                " influx_measurement, influx_tag_key, influx_tag_value, enabled) " +
                "VALUES (?, ?, ?, ?, 'electricity', 'meter_id', ?, true) RETURNING id",
                Long.class,
                "BPERF-METER", "billing perf meter", elecId, plantId, "BPERF-METER");

        // 5 个 rule，每个 energy_type 一个；rule 必须引用 source_meter_id
        Long[] ruleIds = new Long[ENERGY_TYPES.length];
        for (int e = 0; e < ENERGY_TYPES.length; e++) {
            ruleIds[e] = insertRule("BPERF-RULE-" + e, ENERGY_TYPES[e], sourceMeterId, orgs);
        }

        // 1 个 SUCCESS run，period 2026-03
        Long runId = jdbc.queryForObject(
                "INSERT INTO cost_allocation_run(period_start, period_end, status, " +
                " algorithm_version, total_amount, created_by, created_at, finished_at) " +
                "VALUES (TIMESTAMPTZ '2026-03-01 00:00:00+08', TIMESTAMPTZ '2026-04-01 00:00:00+08', " +
                " 'SUCCESS', 'v1', 0, 1, now(), now()) RETURNING id",
                Long.class);

        // 1000 cost_allocation_line：(org, energy)。每条注入 quantity=1, amount=1（含 4 段拆分）
        seedLines(runId, ruleIds, orgs);

        return new SeedIds(plantId, orgs, sourceMeterId, ruleIds, runId);
    }

    private void seedLines(Long runId, Long[] ruleIds, Long[] orgs) {
        List<Object[]> batch = new ArrayList<>(orgs.length * ENERGY_TYPES.length);
        BigDecimal one = BigDecimal.ONE;
        BigDecimal q = new BigDecimal("0.25");
        for (int e = 0; e < ENERGY_TYPES.length; e++) {
            for (Long org : orgs) {
                batch.add(new Object[]{
                        runId, ruleIds[e], org, ENERGY_TYPES[e],
                        one,         // quantity
                        one,         // amount
                        q, q, q, q,  // sharp/peak/flat/valley quantity each = 0.25
                        q, q, q, q   // sharp/peak/flat/valley amount each = 0.25 (sum=1)
                });
            }
        }
        jdbc.batchUpdate(
                "INSERT INTO cost_allocation_line(run_id, rule_id, target_org_id, energy_type, " +
                " quantity, amount, " +
                " sharp_quantity, peak_quantity, flat_quantity, valley_quantity, " +
                " sharp_amount, peak_amount, flat_amount, valley_amount) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                batch);
    }

    private Long insertRule(String code, String energyType, Long sourceMeterId, Long[] targetIds) {
        StringBuilder weights = new StringBuilder("{\"basis\":\"FIXED\",\"values\":{");
        for (int j = 0; j < targetIds.length; j++) {
            if (j > 0) weights.append(',');
            weights.append('"').append(targetIds[j]).append("\":").append(1.0 / targetIds.length);
        }
        weights.append("}}");
        final String weightsJson = weights.toString();

        return jdbc.execute((java.sql.Connection con) -> {
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO cost_allocation_rule(code, name, energy_type, algorithm, " +
                    "source_meter_id, target_org_ids, weights, priority, enabled, effective_from) " +
                    "VALUES (?, ?, ?, 'PROPORTIONAL', ?, ?, ?::jsonb, 100, true, DATE '2026-01-01') " +
                    "RETURNING id")) {
                Array arr = con.createArrayOf("bigint", targetIds);
                ps.setString(1, code);
                ps.setString(2, "billing perf rule " + code);
                ps.setString(3, energyType);
                ps.setLong(4, sourceMeterId);
                ps.setArray(5, arr);
                ps.setString(6, weightsJson);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    private record SeedIds(Long plantId, Long[] orgIds, Long meterId, Long[] ruleIds, Long runId) {}
}
