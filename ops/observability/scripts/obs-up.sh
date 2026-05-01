#!/usr/bin/env bash
# Start factory-ems observability stack idempotently.
# - Creates external docker network if missing
# - Generates Grafana password if .env.obs has empty OBS_GRAFANA_ADMIN_PASSWORD
# - Starts compose stack
# - Runs smoke check after a brief warmup

set -euo pipefail

cd "$(dirname "$0")/.."

NETWORK="${OBS_NETWORK_NAME:-ems-net}"
if ! docker network inspect "$NETWORK" >/dev/null 2>&1; then
    echo "Creating docker network: $NETWORK"
    docker network create "$NETWORK"
fi

if [ ! -f .env.obs ]; then
    echo "ERROR: .env.obs not found. Run ./scripts/grafana-init.sh first." >&2
    exit 1
fi

# Verify Grafana admin password is set
if ! grep -q '^OBS_GRAFANA_ADMIN_PASSWORD=.\+' .env.obs; then
    echo "OBS_GRAFANA_ADMIN_PASSWORD is empty in .env.obs"
    echo "Running grafana-init.sh to generate a password..."
    ./scripts/grafana-init.sh
fi

echo "Starting observability stack..."
docker compose --env-file .env.obs -f docker-compose.obs.yml up -d --build

echo "Waiting 10s for services to start..."
sleep 10

./scripts/obs-smoke.sh
