# mock-data-generator

Standalone Spring Boot CLI tool that populates the factory-ems v1.0.0 schema with
realistic mock data at three scales (small/medium/large). Used to enable sub-project 2
(cost allocation) development validation without production data.

## Prerequisites

- Local PostgreSQL running on 5432 with database `factory_ems` (user `ems`, password `ems_dev`)
- Local InfluxDB running on 8086 (optional; use `--no-influx=true` to skip)
- Schema migrated via the main app or: `./mvnw -pl ems-app flyway:migrate`

## Quick start (small scale, no Influx)

```bash
./mvnw -pl tools/mock-data-generator -am spring-boot:run \
  -Dspring-boot.run.arguments="--scale=small --months=1 --no-influx=true"
```

## All arguments

| Argument         | Default   | Description                                        |
|------------------|-----------|----------------------------------------------------|
| `--scale`        | `small`   | `small` / `medium` / `large`                       |
| `--months`       | scale-dep | Number of months of timeseries to generate         |
| `--start`        | today-N   | Start date `YYYY-MM-DD`                            |
| `--seed`         | `42`      | RNG seed for reproducibility                       |
| `--seed-only`    | `all`     | `all` / `master` / `timeseries`                    |
| `--reset`        | `false`   | (reserved) drop MOCK- rows before seeding          |
| `--no-influx`    | `false`   | Skip InfluxDB writes                               |
| `--verify-only`  | `false`   | Run sanity checks only (exit 0=pass, 2=fail)       |

## Scale profiles

| Scale  | Meters | Months |
|--------|--------|--------|
| small  | 20     | 1      |
| medium | 120    | 3      |
| large  | 300    | 6      |

## What gets seeded

- **OrgTree**: 1 factory -> 4 workshops -> 20 processes + 1 utility area (`MOCK-*` prefix)
- **Meters**: electric / water / steam meters with Influx tags
- **MeterTopology**: 2-tier parent-child topology for electric meters
- **TariffPlans**: 2 plans (peak-valley with cross-midnight valley; flat-rate)
- **Shifts**: 3 shifts day/mid/night (night is cross-midnight)
- **Users**: 12 users (admin/finance/manager/viewer roles)
- **Timeseries**: minute-level Influx points + hourly/daily/monthly rollups
- **ProductionEntries**: workshop x shift x day entries

## Verification

A sidecar JSON `target/mock-data-conservation-sidecar.json` is written listing
injected negative-residual hours (for Plan 2.1 clamp-path testing).

Exit codes: 0 = success, 1 = prod-guard triggered, 2 = sanity check failed.
