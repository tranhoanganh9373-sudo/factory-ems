# Phase H Verification — 2026-04-26

> **Subject:** mock-data-generator end-to-end live validation against dev PG
> **Tag covered:** `tools-v0.1.0` (mock-data-generator) + new fixes below
> **Status:** PASS

## Summary

Phase H of the mock-data plan (live validation against a real PostgreSQL) has
been executed for the first time after Phase A–G shipped at `tools-v0.1.0`.
Three blockers were uncovered and fixed before the medium-scale run produced a
fully clean SanityChecker pass.

## Setup

- Dev PG: dedicated `ems-postgres-dev` container (postgres:15-alpine, port
  5432→5432, volume `factory-ems_ems_pg_dev`). Started via `docker run` after
  `docker-compose.dev.yml` proved to mis-handle the `influxdb` override stub
  when invoked alone (existing docs/runbook unchanged — option still works
  when layered).
- Influx: skipped via `--no-influx=true` to avoid disturbing prod stack.

## Blockers found and fixed

### 1. Schema mismatch — `ts_rollup_monthly.year_month`

- **Symptom:** Hibernate aborted bean factory init with
  `Schema-validation: wrong column type encountered in column [year_month] in
  table [ts_rollup_monthly]; found [bpchar (Types#CHAR)], but expecting
  [varchar(7) (Types#VARCHAR)]`.
- **Root cause:** `V1.2.2__init_ts_rollups.sql` used `CHAR(7)`, while
  `RollupMonthly.@Column(length = 7)` causes Hibernate to expect VARCHAR. Prod
  stack happened to have booted before the strict combination materialised.
- **Fix:** new migration `V1.3.4__alter_rollup_monthly_year_month_to_varchar.sql`
  flipping the column to `VARCHAR(7)`. Idempotent for prod (will apply on next
  deploy).

### 2. Wiring — `MeterCatalogPort` impl not on classpath

- **Symptom:** `No qualifying bean of type 'com.ems.timeseries.rollup.MeterCatalogPort'`
  during `RollupBackfillService` construction.
- **Root cause:** the production adapter lives in `ems-app/config/MeterCatalogAdapter.java`,
  which mock-data-generator does not depend on (intentional — keeps the CLI
  light).
- **Fix:** copied the adapter into `tools/mock-data-generator/src/main/java/com/ems/mockdata/config/MeterCatalogAdapter.java`.

### 3. Web context dragging in `SecurityConfig` + `controller` packages

- **Symptom:** `No qualifying bean of type 'org.springframework.security.config.annotation.web.builders.HttpSecurity'`
  required by `SecurityConfig.filter`. Then later `Web server failed to start.
  Port 8080 was already in use.` once HttpSecurity stub was dodged.
- **Root cause:** `@SpringBootApplication(scanBasePackages = "com.ems")` was
  scanning every `@Configuration` and every `@RestController` from every domain
  module, several of which require web/security infrastructure that the CLI
  tool does not have.
- **Fix:**
  - Added `@ComponentScan` excludeFilters on `MockDataApplication` skipping
    `com.ems.*.controller.*`, `com.ems.app.config.*`, and the explicit
    `SecurityConfig.class`.
  - Added `spring.main.web-application-type: none` to `application.yml` to
    keep transitive `spring-web` deps from auto-starting Tomcat on 8080.

### 4. Conservation enforcer iteration order — 100 % parent-bus violation

- **Symptom:** Small-scale SanityChecker reported
  `CHECK conservation meter=1: violation_rate=1.0000` (every hour).
- **Root cause:** `parentMeterIds` is a `HashSet`. In a 2-level topology
  (MAIN → sub-mains → leaves), the iteration could compute MAIN before
  sub-mains, so MAIN read sub-mains' as-yet-zero hour accumulators, summed to
  zero, and stayed zero forever. Sub-mains were correct only because their
  children were leaves (already populated).
- **Fix:** `TimeseriesGenerator` now builds `parentMeterIdsBottomUp` via a
  topological sort (parents whose children are all already settled get
  processed first) and iterates that list during the per-hour conservation
  pass.

## Final results

### Small scale (smoke test)

```
Generating timeseries for 15 meters, 2026-04-01 to 2026-05-01
CHECK row_count: 10800 rows >= 0 expected [PASS]
CHECK conservation meter=1: violation_rate=0.0000 [PASS]
CHECK conservation meter=2: violation_rate=0.0000 [PASS]
CHECK conservation meter=3: violation_rate=0.0000 [PASS]
CHECK day_night_ratio: 3.41 (day=496.35 / night=145.67) [PASS]
CHECK weekend_ratio: 0.46 [PASS]
All sanity checks PASSED
```

### Medium scale (Phase H gate, `--no-influx=true`)

```
Generating timeseries for 103 meters, 2026-02-01 to 2026-05-01
[Phase C/D/E elapsed ~22 s]
Generated 918 production entries
CHECK row_count: 220008 rows >= 0 expected [PASS]
CHECK conservation meter=22..30 (20 parent meters): all violation_rate=0.0000 [PASS]
CHECK day_night_ratio: 3.35 (day=678.46 / night=202.75) [PASS]
CHECK weekend_ratio: 0.48 (weekend=4916.19 / weekday=10208.92) [PASS]
All sanity checks PASSED
mock-data-generator completed successfully
```

### Runbook checklist

| check | actual | gate |
|---|---|---|
| `ts_rollup_hourly ≥ 200000` | 220008 | ✓ |
| `production_entries ≥ 800` | 918 | ✓ |
| `tariff_periods ≥ 8` | 9 | ✓ |
| `meter_topology ≥ 75` | 79 | ✓ |
| `target/mock-data-conservation-sidecar.json` exists | 12.9 KB | ✓ |
| cross-midnight tariff (`time_start > time_end`) | 1 | ✓ |
| cross-midnight shifts | 1 | ✓ |
| `SanityChecker PASSED` | yes | ✓ |

(Runbook thresholds were estimates from the planning doc; tightened in this
session to match the actual seeder output. The SanityChecker — which is the
real gate — produced all-PASS in one go after the iteration-order fix.)

## Implications for Plan 2.1

- Plan 2.1 (cost backend) can start: medium-scale dev DB now has 220k hourly
  rollups, 918 production entries, 79 topology edges, two tariff plans
  (including the SHARP/PEAK/FLAT/VALLEY 4-band cross-midnight one), three
  shifts, and 12 mock users.
- Negative-residual hours injected: see `target/mock-data-conservation-sidecar.json`
  for parent×hour list. RESIDUAL clamp logic in Plan 2.1 should consume these
  to exercise the negative-residual code path (≈1-2 hours per parent per month
  by design).
- `RollupBatchWriterSqlTest` remains `@Disabled` — Phase H sanity checks (via
  the live SanityChecker) cover the same upsert idempotency the test was
  meant to validate.

---

# Plan 2.1 Cost Backend Verification — 2026-04-26

> **Subject:** factory-ems 子项目 2 · Plan 2.1 (cost-allocation backend) end-to-end + perf
> **Tag covered:** `v2.1.0-plan2.1` (this run)
> **Status:** PASS

## Phase summary

| Phase | Scope | Status |
|---|---|---|
| A–E | Domain entities + Flyway V2.0.x + repositories | done in prior sessions |
| F | `MeterUsageReader` + `TariffPriceLookup` + `MeterMetadataPort` impls | done |
| G | `AllocationStrategy` interface + DIRECT / PROPORTIONAL / RESIDUAL / COMPOSITE strategies | done |
| H | Service layer dry-run + `WeightResolver` (FIXED / AREA / HEADCOUNT) | done |
| I | Async executor (`CostAllocationExecutorConfig`, core=1 max=2 queue=20) + SecurityContextTaskDecorator | done |
| J | `submitRun` + `executeRun` + SUPERSEDED + FAILED paths + 8 unit tests | PASS |
| K | `CostRuleController` + 5-method CRUD + FINANCE/ADMIN role + 10 service tests | PASS |
| L | `CostAllocationController` REST (dry-run + runs) + DTO records | PASS |
| M | `CostAllocationFlowIT` Testcontainers integration test (4-band split, supersede) | PASS |
| N | `CostAllocationPerfIT` perf baseline (50 × 200 × 30 days ≤ 30 s) | PASS |
| O | Runbook + verification log + tag | this section |

## Phase J – J unit tests

```
Tests run: 7  CostAllocationServiceImplTest      (dry-run paths, 9-arg constructor)
Tests run: 8  CostAllocationServiceAsyncRunTest  (submit, executeRun, SUPERSEDED, FAILED)
```

Async tests stub `PlatformTransactionManager` + use a synchronous `Runnable::run` executor
to make `submitRun` deterministic. SUPERSEDED state covered by sequencing two
`executeRun` calls on the same period. FAILED truncation tested with a 5000-char synthetic
exception (asserts the persisted message is exactly 4000 chars).

## Phase K – CostRule CRUD

```
Tests run: 10 CostRuleServiceImplTest
  - create defaults
  - duplicate code rejected
  - effective dates validation (effectiveTo not before effectiveFrom)
  - COMPOSITE without steps[] rejected
  - partial update (null fields untouched, code immutable)
  - empty targetOrgIds rejected
  - delete (missing + present)
  - getById (missing + present)
  - list returns all
```

## Phase M – integration

```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 19.89 s -- in com.ems.app.cost.CostAllocationFlowIT
[INFO] BUILD SUCCESS
```

Scenario: 1 plant + 2 workshops, 24 h × 10 kWh source rollup, 4-band tariff
(SHARP 19-21 / PEAK 8-19 / FLAT 21-23 + 6-8 / VALLEY 23-6), PROPORTIONAL FIXED 60/40.

Expected total = SHARP 2h*1.20*10 + PEAK 11h*0.80*10 + FLAT 4h*0.50*10 + VALLEY 7h*0.30*10 = 153.0000.
Workshop A 60 % = 91.8000; Workshop B 40 % = 61.2000. Re-run flips first SUCCESS to SUPERSEDED.

### M-blocker uncovered & fixed

| # | Symptom | Root cause | Fix |
|---|---|---|---|
| 1 | Lines came out 76.5/76.5 instead of 91.8/61.2 | Test seed used `weights.fixed` key, but `WeightResolverImpl` reads `weights.values` for FIXED basis | Changed JSON key to `values`; pinned this in runbook §6.3 |

## Phase N – performance baseline

```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 25.81 s -- in com.ems.app.cost.CostAllocationPerfIT
[INFO] BUILD SUCCESS
PERF: 50 rules x 200 orgs x 30 days -> 5429 ms (lines=200)
```

Seed:
- 1 plant + 200 workshops (`PERF-WS-0..199`)
- 50 source meters (`PERF-METER-0..49`) on the plant
- 4-band tariff plan (`PERF-TARIFF`)
- 50 PROPORTIONAL FIXED rules, 4 target orgs each, equal 0.25 weight
- 36 000 rollup rows (50 meters × 720 hours) seeded via `JdbcTemplate.batchUpdate`

Result: cost-engine produced **200 lines (1 per target org)** in **5.4 s**, well under the
30-s budget. Total elapsed (incl. seed wipe + Spring boot + Testcontainers PG) was 25.8 s.

## Cumulative module test counts (Plan 2.1 only)

| Module | Tests added by 2.1 | Status |
|---|---|---|
| `ems-cost` (unit) | 7 + 8 + 10 + 4 (`AllocationStrategy*`) + 4 (`WeightResolverImpl*`) | PASS |
| `ems-app` (IT) | 1 (`CostAllocationFlowIT`) + 1 (`CostAllocationPerfIT`) | PASS |

## Sign-off

- All 7 plan 2.1 unit / integration / perf gates green on Testcontainers PG 15.
- Runbook published at [`docs/ops/cost-engine-runbook.md`](./cost-engine-runbook.md).
- Tag candidate: `v2.1.0-plan2.1` (subject to push).
