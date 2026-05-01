# Mock Data Generator Runbook

> **Module:** `tools/mock-data-generator`
> **Spec:** `docs/superpowers/plans/2026-04-25-factory-ems-mock-data-plan.md`
> **Status:** Phase A–G complete (15/15 unit tests green). Phase H (live validation) is manual.

## What it does

Spring Boot CLI that populates the factory-ems v1.0.0 schema with realistic mock
data so subproject 2 (cost allocation) development can run without real plant
hardware. Real telemetry collection is deferred until after subproject 3.

## Prerequisites

You need a running PostgreSQL **on host** port 5432 (so the CLI can reach it
from `mvnw spring-boot:run`). The production docker-compose does **not** expose
PG to the host.

Two ways to get PG + Influx with host port mappings:

```bash
# Option 1: dedicated dev compose (preferred — separate volume so it can't
# corrupt your production-like data)
docker compose -f docker-compose.dev.yml up -d postgres influxdb

# Option 2: layer the override on top of an already-running stack
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d
```

`docker-compose.dev.yml` adds:
- `ems-postgres-dev` on `localhost:5432`
- `influxdb` port `8086:8086` exposed

Confirm:
```bash
docker port ems-postgres-dev    # should show 0.0.0.0:5432->5432/tcp
psql -h localhost -p 5432 -U ems -d factory_ems -c '\dt' # should show v1 tables
```

## Run small scale (5 minutes)

```bash
./mvnw -pl tools/mock-data-generator -am package -DskipTests
./mvnw -pl tools/mock-data-generator spring-boot:run \
  -Dspring-boot.run.arguments="--scale=small --months=1 --start=2026-04-01"
```

What it does:
- Seeds master data (`MOCK-` prefix): org tree, ~30 meters, tariff plans, shifts, users
- Generates 1 month of minute-level Influx points + hourly/daily/monthly rollups
- Generates ~360 production entries
- Runs sanity check, exits non-zero if any check fails

Expected output:
```
... seeders running ...
... timeseries generation: ~22000 hourly rollups ...
SanityChecker:
  ts_rollup_hourly count: 21600 (>= 50% of expected) — OK
  conservation violations: 0.8% (<= 5%) — OK
  day/night ratio: 2.4 (in [1.5, 8.0]) — OK
  weekend/weekday ratio: 0.48 (in [0.3, 0.7]) — OK
SanityChecker: PASS
```

## Run medium scale (30 minutes — Plan 2.1 prerequisite)

```bash
./mvnw -pl tools/mock-data-generator spring-boot:run \
  -Dspring-boot.run.arguments="--scale=medium --months=3 --start=2026-02-01"
```

Produces:
- 120 meters
- 3 months: 2026-02-01 ~ 2026-04-30
- ~262000 `ts_rollup_hourly` rows
- ~15.6M Influx raw points (skip with `--no-influx=true` if Influx not running)

This is the dataset Plan 2.1 (cost backend) needs to start.

## Reset & re-seed

Pass `--reset=true` to truncate all `MOCK-`/`mock-` prefixed seed rows in PG before re-seeding:

```bash
./mvnw -pl tools/mock-data-generator spring-boot:run -Dspring-boot.run.arguments="--reset=true --scale=medium"
```

The reseter (`com.ems.mockdata.MockDataReseter`) issues 13 DELETEs in FK-safe
order, scoped by prefix on tables that hold mixed real+mock rows
(`meters`/`users`/`org_nodes`/`tariff_plans`/`shifts`) and unconditional on
mock-only tables (rollups / topology / production_entries). All within one
transaction.

Influx raw points: drop the bucket and re-init via docker-compose
(`MockDataReseter` does not touch Influx).

If you'd rather run the SQL by hand (e.g. dry-run), the equivalent statements are:

```sql
-- in factory_ems
DELETE FROM production_entries;
DELETE FROM ts_rollup_monthly;
DELETE FROM ts_rollup_daily;
DELETE FROM ts_rollup_hourly;
DELETE FROM meter_topology;
DELETE FROM meters WHERE code LIKE 'MOCK-%';
DELETE FROM tariff_periods;
DELETE FROM tariff_plans WHERE name LIKE 'MOCK-%';
DELETE FROM shifts WHERE code LIKE 'MOCK-%';
DELETE FROM org_node_closure WHERE descendant_id IN (SELECT id FROM org_nodes WHERE code LIKE 'MOCK-%');
DELETE FROM org_nodes WHERE code LIKE 'MOCK-%';
DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'mock-%');
DELETE FROM users WHERE username LIKE 'mock-%';
```

## CLI flags

| Flag | Default | Meaning |
|---|---|---|
| `--scale` | `medium` | `small` / `medium` / `large` (see ScaleProfile) |
| `--months` | `3` | how many months of timeseries to generate |
| `--start` | `2026-02-01` | first day (ISO date) |
| `--seed` | `42` | RNG seed for deterministic output |
| `--seed-only` | `all` | `master` / `timeseries` / `all` |
| `--no-influx` | `false` | skip raw Influx writes (PG rollups only) |
| `--verify-only` | `false` | run SanityChecker without seeding |
| `--reset` | `false` | truncate `MOCK-`/`mock-` prefixed seed rows before re-seeding |

## Troubleshooting

**Connection refused on port 5432**
The prod docker-compose doesn't expose PG. Use `docker-compose.dev.yml` (above).

**`changeme` Influx token error**
Set `EMS_INFLUX_TOKEN` env var to your dev token, or pass `--no-influx=true`.

**`mvn` aborts with "do not run in prod profile"**
Intentional safety guard. Don't pass `SPRING_PROFILES_ACTIVE=prod`. The default
`mock` profile is correct for this tool.

**Sanity check fails on conservation violations >5%**
NoiseInjector + ConservationEnforcer should produce ≤1% violations. If you see
more, you may have run the tool twice without resetting — old aggregates stack.

## Phase H validation checklist

After running medium scale, confirm before starting Plan 2.1:

- [ ] `SELECT COUNT(*) FROM ts_rollup_hourly` ≥ 200000 (medium scale 103 meters × 90 days × 24 hours ≈ 222480 minus noise/missing)
- [ ] `SELECT COUNT(*) FROM production_entries WHERE entry_date >= '2026-02-01'` ≥ 800
- [ ] `SELECT COUNT(*) FROM tariff_periods` ≥ 8 (2 plans, ≥4 periods each)
- [ ] `SELECT COUNT(*) FROM meter_topology` ≥ 75 (medium scale: 19 sub-mains + 60 leaves = 79 edges)
- [ ] `target/mock-data-conservation-sidecar.json` exists (lists injected negative-residual hours)
- [ ] At least 1 cross-midnight TIME band in `tariff_periods` (`time_start > time_end`)
- [ ] At least 1 cross-midnight `shifts.time_start > time_end` row
- [ ] SanityChecker prints `All sanity checks PASSED` (this is the authoritative pass criterion — counts above are sanity ranges, not gates)
