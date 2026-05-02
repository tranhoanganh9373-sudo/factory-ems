#!/usr/bin/env bash
# Factory EMS — 备份恢复脚本（在测试机演练用）
#
# 实现自 docs/install/installation-manual.md §8.4 的参考方案，
# 加：交互安全护栏、目标目录已存在的保护、关键步骤失败立即停止。
#
# ⚠️ 默认拒绝在生产 EMS_HOME（/opt/factory-ems）上恢复。
#    要覆盖必须 FORCE=1，否则只允许独立的测试目录。
#
# 用法:
#   sudo TARGET=/opt/factory-ems-restore \
#     ./scripts/restore.sh /opt/factory-ems-backups/20260501-194530
#
# 环境变量:
#   TARGET         恢复到的目录，默认 /opt/factory-ems-restore（必须为空或不存在）
#   REPO_URL       克隆 EMS 仓库的 URL（如目标目录不存在）
#   FORCE          1 = 允许覆盖 /opt/factory-ems（生产位置）；默认拒绝
#
# 退出码：
#   0 成功 / 1 入参或前置失败 / 2 PG 恢复失败 / 3 Influx 恢复失败

set -euo pipefail

SOURCE="${1:-}"
TARGET="${TARGET:-/opt/factory-ems-restore}"
REPO_URL="${REPO_URL:-}"
FORCE="${FORCE:-0}"

usage() {
  cat <<EOF
Usage: $0 <backup-dir>

Required:
  <backup-dir>   形如 /opt/factory-ems-backups/YYYYMMDD-HHMMSS，
                 须含 env.snapshot / postgres.sql.gz / influx-backup/

Env (optional):
  TARGET         默认 /opt/factory-ems-restore
  REPO_URL       目标目录不存在时用此 URL git clone
  FORCE=1        允许覆盖 /opt/factory-ems（生产）—— 危险
EOF
  exit 1
}

[[ -n "$SOURCE" ]] || usage
[[ -d "$SOURCE" ]] || { echo "ERR: backup dir not found: $SOURCE" >&2; exit 1; }
[[ -f "$SOURCE/env.snapshot" ]] || { echo "ERR: missing env.snapshot in $SOURCE" >&2; exit 1; }
[[ -f "$SOURCE/postgres.sql.gz" ]] || { echo "ERR: missing postgres.sql.gz in $SOURCE" >&2; exit 1; }
[[ -d "$SOURCE/influx-backup" ]] || { echo "ERR: missing influx-backup/ in $SOURCE" >&2; exit 1; }

if [[ "$TARGET" == "/opt/factory-ems" && "$FORCE" != "1" ]]; then
  echo "ERR: 拒绝在生产 /opt/factory-ems 上恢复；改 TARGET 或设 FORCE=1 (危险)" >&2
  exit 1
fi

command -v docker >/dev/null || { echo "ERR: docker required" >&2; exit 1; }
command -v rsync  >/dev/null || { echo "ERR: rsync required" >&2; exit 1; }
command -v zcat   >/dev/null || { echo "ERR: zcat required" >&2; exit 1; }

echo "[$(date -Iseconds)] restore from $SOURCE → $TARGET"

# ---------- 1) 准备目标目录 ----------

if [[ ! -d "$TARGET" ]]; then
  [[ -n "$REPO_URL" ]] || { echo "ERR: $TARGET 不存在且未设 REPO_URL" >&2; exit 1; }
  # -- 分隔，防 REPO_URL 以 - 开头被 git 当 flag（如 --upload-pack=...）
  git clone -- "$REPO_URL" "$TARGET"
fi
[[ -f "$TARGET/docker-compose.yml" ]] || {
  echo "ERR: $TARGET 不像 EMS 项目目录（无 docker-compose.yml）" >&2; exit 1; }

cp "$SOURCE/env.snapshot" "$TARGET/.env"
chmod 600 "$TARGET/.env"
cd "$TARGET"

# 解析 .env 关键 key（与 backup.sh 同一函数）
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

# ---------- 2) 起 DB / Influx ----------

docker compose up -d postgres influxdb

echo "  waiting for postgres + influxdb to be ready (max 60s) ..."
WAIT_DEADLINE=$(( $(date +%s) + 60 ))
PG_OK=0
INFLUX_OK=0
while [[ $(date +%s) -lt $WAIT_DEADLINE ]]; do
  if [[ $PG_OK -eq 0 ]] && \
     docker compose exec -T postgres pg_isready -U "$DB_USER" -d "$DB_NAME" >/dev/null 2>&1; then
    PG_OK=1
    echo "    postgres ready"
  fi
  if [[ $INFLUX_OK -eq 0 ]] && \
     docker compose exec -T influxdb influx ping >/dev/null 2>&1; then
    INFLUX_OK=1
    echo "    influxdb ready"
  fi
  [[ $PG_OK -eq 1 && $INFLUX_OK -eq 1 ]] && break
  sleep 2
done
[[ $PG_OK -eq 1 && $INFLUX_OK -eq 1 ]] || {
  echo "ERR: timeout waiting for db/influx (pg_ready=$PG_OK influx_ready=$INFLUX_OK)" >&2
  exit 1
}

# ---------- 3) 恢复 PostgreSQL ----------

echo "  restoring postgres ..."
if ! zcat "$SOURCE/postgres.sql.gz" \
     | docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME"; then
  echo "ERR: postgres restore failed" >&2
  exit 2
fi

# ---------- 4) 恢复 InfluxDB ----------

echo "  restoring influxdb ..."
INFLUX_CID=$(docker compose ps -q influxdb)
docker cp "$SOURCE/influx-backup" "$INFLUX_CID:/tmp/influx-restore"
if ! docker compose exec -T influxdb \
       influx restore /tmp/influx-restore --full --token "$INFLUX_TOKEN"; then
  echo "ERR: influx restore failed" >&2
  exit 3
fi
docker compose exec -T influxdb rm -rf /tmp/influx-restore || true

# ---------- 5) 恢复上传文件 ----------

if [[ -d "$SOURCE/ems_uploads" ]]; then
  mkdir -p data/ems_uploads
  rsync -a "$SOURCE/ems_uploads/" data/ems_uploads/
  echo "  ems_uploads restored"
fi

# ---------- 6) 起整栈 ----------

docker compose up -d

echo "[$(date -Iseconds)] restore done"
echo
echo "下一步：用 scripts/smoke-api.sh 跑 API 自检确认数据完整。"
