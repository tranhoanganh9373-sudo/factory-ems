# scripts/

本目录有两类脚本：

1. **Demo seed**（`seed-postgres.sql`、`seed-influx.sh`）—— 给 demo / 评估快速灌示例数据
2. **Commissioning & ops**（`csv-to-channels.py` 等 8 个）—— 真实工厂上线时用，跟 `docs/install/*.md` 一组 SOP 配套

跟着 [项目根 README](../README.md) "现场上线 SOP" 段一步步走即可。

---

## Prerequisites

- Docker Compose v2 (`docker compose` command)
- A populated `.env` file (copy from `.env.example` and fill in all values)
- 通用工具：`bash`、`curl`、`jq`（commissioning 脚本依赖）；`python3`（CSV → JSON 转换）

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

## Commissioning & ops scripts

工厂上线 / 日常运维用，与 `docs/install/*.md` SOP 一对一配套。所有脚本 `set -euo pipefail` + 入参校验 + 失败立即停止。

### csv-to-channels.py + import-channels.sh — 通道批量导入

**做什么**：把 `docs/install/meter-register-mapping-template.csv` 转成 channel JSON，再循环 `POST /api/v1/channel`。
**何时用**：仪表寄存器表填好后、看板上线前。
**示例**：

```bash
./scripts/csv-to-channels.py docs/install/meter-register-mapping-template.csv \
  --floor-host 1F=10.0.1.11,2F=10.0.1.12,3F=10.0.1.13,4F=10.0.1.14 \
  -o channels.json
EMS_BASE_URL=https://ems.example.com EMS_TOKEN=$JWT \
  ./scripts/import-channels.sh channels.json
```

**配套 SOP**：[field-installation-sop.md](../docs/install/field-installation-sop.md) §9、[dashboard-commissioning-sop.md](../docs/install/dashboard-commissioning-sop.md) §3。

### csv-to-meters.py + import-meters.sh — 仪表批量导入

**做什么**：把 meter mapping CSV 转成 Meter JSON，自动 `GET /api/v1/channel` 解析 channelName→channelId，再循环 `POST /api/v1/meters`。
**何时用**：channel 已导入后，看板上线前。
**示例**：

```bash
./scripts/csv-to-meters.py docs/install/meter-register-mapping-template.csv \
  --floor-org 1F=2,2F=3,3F=4,4F=5 \
  --floor-channel-name 1F=1F-MCC-485,2F=2F-MCC-485,3F=3F-MCC-485,4F=4F-MCC-485 \
  --include-suffix power_total,energy_total \
  -o meters.json
EMS_BASE_URL=https://ems.example.com EMS_TOKEN=$JWT \
  ./scripts/import-meters.sh meters.json
```

**配套 SOP**：[dashboard-commissioning-sop.md](../docs/install/dashboard-commissioning-sop.md) §3 批量化段。

**GUI 替代**：v2 起 `/meters` 页右上"批量导入"按钮可吃同 schema 的 `meters.json`，实现见 `frontend/src/pages/meters/MeterBatchImportModal.tsx`。两条路径等价、互不冲突。

### demo-up.sh — 5 分钟演示一键起栈

**做什么**：起 Postgres + InfluxDB → 跑 mock-data-generator 灌 1 个月数据 → 起后端 + 前端。
**何时用**：客户参观、销售 demo、新人 onboarding。
**示例**：

```bash
./scripts/demo-up.sh                    # 默认 small (20 块表 / 1 月)
EMS_SCALE=medium ./scripts/demo-up.sh   # 120 块表 / 3 月
```

**配套 SOP**：[dashboard-demo-quickstart.md](../docs/install/dashboard-demo-quickstart.md)。

### monthly-report-mail.sh — 月报自动出 PDF + 邮件

**做什么**：cron 每月 1 号 06:00 跑，调 `POST /reports/export` 提交异步 PDF 导出 → 轮询 → 下载 → 通过 msmtp/sendmail 发邮件。
**何时用**：账期已 LOCKED 后想自动月报推送。
**示例**：

```bash
EMS_BASE_URL=https://ems.example.com EMS_TOKEN=$JWT \
  REPORT_RECIPIENTS="boss@example.com,finance@example.com" \
  REPORT_DRY_RUN=1 \
  ./scripts/monthly-report-mail.sh   # dry-run，只下 PDF 不发邮件
```

**配套 SOP**：[report-automation-sop.md](../docs/install/report-automation-sop.md)。

### backup.sh — 完整备份

**做什么**：备份 `.env` 快照 + Postgres `pg_dump` + InfluxDB `influx backup` + `data/ems_uploads/` rsync；可选 `RCLONE_REMOTE` 推远端。30 天自动清理。
**何时用**：cron 每日 02:00。
**示例**：

```bash
sudo ./scripts/backup.sh
# 或：BACKUP_ROOT=/mnt/backups RCLONE_REMOTE=oss:ems-backups ./scripts/backup.sh
```

**配套 SOP**：[installation-manual.md](../docs/install/installation-manual.md) §8.1。

### restore.sh — 备份恢复演练

**做什么**：从 `backup.sh` 输出目录还原到测试机（默认拒绝在生产 `/opt/factory-ems` 上跑，需 `FORCE=1` 显式覆盖）。
**何时用**：部署后 30 天内必做一次演练，验证备份可用。
**示例**：

```bash
sudo TARGET=/opt/factory-ems-restore REPO_URL=git@example.com:org/factory-ems.git \
  ./scripts/restore.sh /opt/factory-ems-backups/20260501-194530
```

**配套 SOP**：[installation-manual.md](../docs/install/installation-manual.md) §8.4。

---

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
