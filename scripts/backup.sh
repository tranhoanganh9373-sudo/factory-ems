#!/usr/bin/env bash
# Factory EMS — 完整备份脚本
#
# 实现自 docs/install/installation-manual.md §8.1 的参考方案，
# 并补：env 解析健壮化、关键步骤失败立即停止、可选 rclone 推远端。
#
# 用法（生产）:
#   sudo /opt/factory-ems/scripts/backup.sh
#
# 用法（cron）:
#   0 2 * * * ems-ops /opt/factory-ems/scripts/backup.sh >> /var/log/ems-backup.log 2>&1
#
# 环境变量（可选）:
#   EMS_HOME           项目根，默认 /opt/factory-ems（含 .env 与 docker-compose.yml）
#   BACKUP_ROOT        备份输出根目录，默认 /opt/factory-ems-backups
#   BACKUP_RETAIN_DAYS 保留天数，默认 30
#   RCLONE_REMOTE      非空则把当次备份 rsync 到远端，例如 "remote:factory-ems-backups"
#
# 退出码：
#   0 成功 / 1 入参或前置失败 / 2 PG 备份失败 / 3 Influx 备份失败 / 4 上传同步失败

set -euo pipefail

EMS_HOME="${EMS_HOME:-/opt/factory-ems}"
BACKUP_ROOT="${BACKUP_ROOT:-/opt/factory-ems-backups}"
RETAIN="${BACKUP_RETAIN_DAYS:-30}"
RCLONE_REMOTE="${RCLONE_REMOTE:-}"

DATE=$(date +%Y%m%d-%H%M%S)
DEST="$BACKUP_ROOT/$DATE"

# ---------- 前置 ----------

[[ -d "$EMS_HOME" ]] || { echo "ERR: EMS_HOME not found: $EMS_HOME" >&2; exit 1; }
[[ -f "$EMS_HOME/.env" ]] || { echo "ERR: .env not found in $EMS_HOME" >&2; exit 1; }
command -v docker >/dev/null || { echo "ERR: docker required" >&2; exit 1; }
command -v gzip   >/dev/null || { echo "ERR: gzip required" >&2; exit 1; }
command -v rsync  >/dev/null || { echo "ERR: rsync required" >&2; exit 1; }

mkdir -p "$DEST"
# 收紧目录权限：影响 env.snapshot / influx-backup（含 token）/ ems_uploads
chmod 700 "$DEST"
cd "$EMS_HOME"

# 解析 .env 单个 key（兼容含空格的值；忽略注释行；剥离引号）
env_get() {
  local key="$1"
  awk -F= -v k="$key" '
    /^[[:space:]]*#/ || NF<2 { next }
    $1==k { sub(/^[^=]*=/, ""); gsub(/^[ "\x27]+|[ "\x27]+$/, ""); print; exit }
  ' .env
}

DB_USER=$(env_get EMS_DB_USER)
DB_NAME=$(env_get EMS_DB_NAME)
INFLUX_TOKEN=$(env_get EMS_INFLUX_TOKEN)

[[ -n "$DB_USER" && -n "$DB_NAME" ]] || {
  echo "ERR: EMS_DB_USER / EMS_DB_NAME not set in .env" >&2; exit 1; }
[[ -n "$INFLUX_TOKEN" ]] || {
  echo "ERR: EMS_INFLUX_TOKEN not set in .env" >&2; exit 1; }

echo "[$(date -Iseconds)] backup start → $DEST"

# ---------- 1) .env 快照 ----------

cp .env "$DEST/env.snapshot"
chmod 600 "$DEST/env.snapshot"

# ---------- 2) PostgreSQL 逻辑备份 ----------

if ! docker compose exec -T postgres \
       pg_dump -U "$DB_USER" -d "$DB_NAME" \
     | gzip > "$DEST/postgres.sql.gz"; then
  echo "ERR: pg_dump failed" >&2
  rm -rf "$DEST"
  exit 2
fi
PG_SIZE=$(stat -f %z "$DEST/postgres.sql.gz" 2>/dev/null \
           || stat -c %s "$DEST/postgres.sql.gz")
echo "  postgres.sql.gz: $PG_SIZE bytes"

# ---------- 3) InfluxDB 热备 ----------

INFLUX_TMP="/tmp/influx-backup-$DATE"
# 兜底清理：失败路径也要把容器内 tmp dir 清掉，否则一直占空间
cleanup_influx_tmp() { docker compose exec -T influxdb rm -rf "$INFLUX_TMP" >/dev/null 2>&1 || true; }
if ! docker compose exec -T influxdb \
       influx backup "$INFLUX_TMP" --token "$INFLUX_TOKEN"; then
  cleanup_influx_tmp
  echo "ERR: influx backup failed" >&2
  exit 3
fi
INFLUX_CID=$(docker compose ps -q influxdb)
if ! docker cp "$INFLUX_CID:$INFLUX_TMP" "$DEST/influx-backup"; then
  cleanup_influx_tmp
  echo "ERR: docker cp influx backup failed" >&2
  exit 3
fi
cleanup_influx_tmp
echo "  influx-backup/: $(du -sh "$DEST/influx-backup" | awk '{print $1}')"

# ---------- 4) 上传文件（增量 rsync）----------

if [[ -d data/ems_uploads ]]; then
  rsync -a --delete data/ems_uploads/ "$DEST/ems_uploads/"
  echo "  ems_uploads/: $(du -sh "$DEST/ems_uploads" | awk '{print $1}')"
fi

# ---------- 5) 推远端（可选）----------

if [[ -n "$RCLONE_REMOTE" ]]; then
  command -v rclone >/dev/null || {
    echo "ERR: RCLONE_REMOTE set but rclone not installed" >&2; exit 4; }
  echo "  rclone → $RCLONE_REMOTE/$DATE ..."
  # 关键：必须排除 env.snapshot（含 DB 密码 + Influx token），不能推到远端
  # rclone 的 --include 不加 --exclude '*' 会等于全量同步
  rclone sync "$DEST" "$RCLONE_REMOTE/$DATE" \
    --exclude "env.snapshot" \
    --include "*.gz" --include "ems_uploads/**" --include "influx-backup/**" \
    --exclude "*"
fi

# ---------- 6) 清理过期备份 ----------

find "$BACKUP_ROOT" -maxdepth 1 -type d -name '????????-??????' -mtime "+$RETAIN" \
  -exec rm -rf {} + 2>/dev/null || true

echo "[$(date -Iseconds)] backup done: $DEST"
