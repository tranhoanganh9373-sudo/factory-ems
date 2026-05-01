package com.ems.mockdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Truncates MOCK-prefixed seed data so the generator can re-seed deterministically.
 * Wired via {@code MockDataApplication#run} when {@code --reset=true}.
 *
 * <p>SQL sequence mirrors {@code docs/ops/mock-data-runbook.md} §"Reset & re-seed".
 * Tables holding mixed real+mock rows are scoped by {@code MOCK-}/{@code mock-} prefix;
 * tables that only ever hold generated data (rollups, topology, production_entries)
 * are unconditional. Influx raw points are NOT cleared here — operators drop the
 * bucket via docker-compose.
 *
 * <p><b>If you change {@link #RESET_SQL}, also update:</b>
 * <ol>
 *   <li>{@code docs/ops/mock-data-runbook.md} §"Reset & re-seed" SQL block</li>
 *   <li>{@code MockDataReseterTest} — the test reads {@code RESET_SQL} directly so
 *       it auto-detects content drift, but verify the per-statement assertions still
 *       cover any new MOCK- prefix scoping you add.</li>
 * </ol>
 *
 * <p><b>Scale notes:</b> all 13 DELETEs run in a single {@link Transactional}
 * boundary. For Plan 2.1 default ("medium" scale ≈ 262K hourly rollup rows) this
 * holds locks briefly and is fine. For "large" scale (millions of rollup rows),
 * consider running the SQL by hand in batches outside the application — see the
 * runbook's manual SQL block for an equivalent script.
 */
@Component
public class MockDataReseter {

    private static final Logger log = LoggerFactory.getLogger(MockDataReseter.class);

    /** Visible for testing — see class-level javadoc on drift discipline. */
    static final List<String> RESET_SQL = List.of(
        "DELETE FROM production_entries",
        "DELETE FROM ts_rollup_monthly",
        "DELETE FROM ts_rollup_daily",
        "DELETE FROM ts_rollup_hourly",
        "DELETE FROM meter_topology",
        "DELETE FROM meters WHERE code LIKE 'MOCK-%'",
        "DELETE FROM tariff_periods",
        "DELETE FROM tariff_plans WHERE name LIKE 'MOCK-%'",
        "DELETE FROM shifts WHERE code LIKE 'MOCK-%'",
        "DELETE FROM org_node_closure WHERE descendant_id IN (SELECT id FROM org_nodes WHERE code LIKE 'MOCK-%')",
        "DELETE FROM org_nodes WHERE code LIKE 'MOCK-%'",
        "DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'mock-%')",
        "DELETE FROM users WHERE username LIKE 'mock-%'"
    );

    private final JdbcTemplate jdbc;

    public MockDataReseter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void reset() {
        log.info("--- Phase A: reset MOCK- prefixed seed data ({} tables) ---", RESET_SQL.size());
        for (String sql : RESET_SQL) {
            jdbc.execute(sql);
        }
        log.info("reset complete");
    }
}
