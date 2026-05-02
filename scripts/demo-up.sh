#!/usr/bin/env bash
# Factory EMS — 一键拉起 demo 环境
#
# 用途：5 分钟跑通完整看板演示（采购前给老板/客户看效果）
#
# 流程：
#   1. 起 docker-compose.dev.yml 里的 postgres / influxdb
#   2. 跑 tools/mock-data-generator 灌 1 个月模拟数据（small=20 块表）
#   3. 起 ems-app / frontend / nginx
#   4. 打印登录信息和下一步指引
#
# 用法：
#   ./scripts/demo-up.sh                  # 默认 small=20 块表 / 1 个月
#   EMS_SCALE=medium ./scripts/demo-up.sh # 中等规模 120 块表 / 3 个月
#
# 详情见 docs/install/dashboard-demo-quickstart.md
# mock-data-generator 详情见 docs/ops/mock-data-runbook.md

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

SCALE="${EMS_SCALE:-small}"
MONTHS="${EMS_MONTHS:-1}"

case "$SCALE" in
  small|medium|large) ;;
  *) echo "ERR: EMS_SCALE must be small/medium/large (got: $SCALE)" >&2; exit 1 ;;
esac

command -v docker >/dev/null || { echo "ERR: docker not found" >&2; exit 1; }
[[ -x ./mvnw ]] || { echo "ERR: ./mvnw not executable in $REPO_ROOT" >&2; exit 1; }

echo "==> Step 1/3: starting docker compose (dev profile) — postgres + influxdb"
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d \
  postgres influxdb

echo "==> waiting for postgres ..."
for i in $(seq 1 30); do
  if docker exec ems-postgres-dev pg_isready -U ems -d factory_ems >/dev/null 2>&1; then
    echo "    postgres ready"
    break
  fi
  sleep 1
  if [[ $i -eq 30 ]]; then
    echo "ERR: postgres did not become ready in 30s" >&2
    exit 1
  fi
done

echo "==> waiting for influxdb ..."
for i in $(seq 1 30); do
  if docker exec ems-influxdb influx ping >/dev/null 2>&1; then
    echo "    influxdb ready"
    break
  fi
  sleep 1
  if [[ $i -eq 30 ]]; then
    echo "ERR: influxdb did not become ready in 30s" >&2
    exit 1
  fi
done

echo "==> Step 2/3: running mock-data-generator (scale=$SCALE, months=$MONTHS)"
./mvnw -pl tools/mock-data-generator -am -q spring-boot:run \
  -Dspring-boot.run.arguments="--scale=$SCALE --months=$MONTHS"

echo "==> Step 3/3: starting app + frontend + nginx"
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d \
  ems-app frontend nginx

case "$SCALE" in
  small)  RANGE="20 表 / 1 月";;
  medium) RANGE="120 表 / 3 月";;
  large)  RANGE="300 表 / 6 月";;
esac

cat <<EOF

================================================================
  Demo 环境就绪
================================================================
  访问地址:   http://localhost            (nginx)
              http://localhost:5173       (vite dev, 如启用)

  登录账号:   MOCK-admin     / Mock123!  (ADMIN)
              MOCK-finance-1 / Mock123!  (FINANCE)
              MOCK-mgr-a     / Mock123!  (MANAGER, 冲压车间)
              MOCK-viewer-1  / Mock123!  (VIEWER)

  规模:       $SCALE  ($RANGE)
  Org-tree:   1 工厂 → 4 车间 → 20 工序 (mock-data-generator 默认)

  下一步:     按 docs/install/dashboard-demo-quickstart.md 走 4 个看点
  关闭:       docker compose -f docker-compose.yml -f docker-compose.dev.yml down

================================================================
EOF
