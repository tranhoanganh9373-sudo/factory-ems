# scripts/

Seed scripts for bootstrapping a fresh factory-ems stack with demo data.

## Prerequisites

- Docker Compose v2 (`docker compose` command)
- A populated `.env` file (copy from `.env.example` and fill in all values)

## 1. Boot the stack

```bash
docker compose up -d
```

Wait until all services are healthy:

```bash
docker compose ps
```

For local development (publishes InfluxDB port 8086 to the host):

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d
```

## 2. Apply Postgres seed

Inserts demo org hierarchy (Factory → LineA/LineB → Cell-1/2/3), energy types
(ELEC/WATER/GAS/STEAM), and 6 meters. Idempotent via `ON CONFLICT DO NOTHING`.

```bash
docker compose exec -T postgres \
  psql -U "$EMS_DB_USER" -d "$EMS_DB_NAME" \
  < scripts/seed-postgres.sql
```

Or with explicit values if `.env` is not sourced in your shell:

```bash
docker compose exec -T postgres \
  psql -U ems -d factory_ems \
  < scripts/seed-postgres.sql
```

## 3. Apply InfluxDB seed

Seeds 7 days of synthetic `energy_reading` data at 5-minute resolution for all
6 demo meters (~2016 points per meter, ~12 096 total). Idempotent — InfluxDB
upserts by tag+timestamp so re-runs overwrite existing points.

```bash
INFLUX_TOKEN="$EMS_INFLUX_TOKEN" bash scripts/seed-influx.sh
```

Optional environment overrides (defaults match `.env.example`):

| Variable         | Default                 |
|------------------|-------------------------|
| `INFLUX_URL`     | `http://localhost:8086` |
| `INFLUX_TOKEN`   | _(required)_            |
| `INFLUX_ORG`     | `factory`               |
| `INFLUX_BUCKET`  | `factory_ems`           |

> **Note:** `INFLUX_URL` defaults to `http://localhost:8086` (host-side access).
> Port 8086 is only published in the dev compose layer. In production the seed
> script must run from inside the Docker network or after temporarily enabling
> the commented-out `ports` entry in `docker-compose.yml`.

## 4. Verify

Call the dashboard KPI endpoint (replace the JWT token as needed):

```bash
curl -s -H "Authorization: Bearer <JWT>" \
  http://localhost:8888/api/v1/dashboard/kpi | jq .
```

A healthy response returns `200 OK` with energy totals for each meter.

## Seed script notes

### seed-postgres.sql

- Uses explicit IDs (1-6) for `org_nodes` to keep the closure table inserts
  readable. `setval()` is called after to keep the sequence consistent.
- All inserts are `ON CONFLICT ... DO NOTHING` — safe to run multiple times.
- Column names match the Flyway schema exactly:
  `org_nodes`, `org_node_closure`, `energy_types`, `meters`.

### seed-influx.sh

- Written for bash 3.2+ (Git Bash on Windows compatible).
- Uses only POSIX utilities: `curl`, `cut`, `date`, `mktemp`, `wc`.
- Sinusoidal daily pattern: `value = base + amplitude * sin(2π * hour / 24) + jitter`.
- Writes via the InfluxDB v2 HTTP write API (`/api/v2/write`) with line protocol.
- Exits non-zero on any HTTP error.
