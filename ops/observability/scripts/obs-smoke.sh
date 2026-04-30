#!/usr/bin/env bash
# Smoke check: each service /ready or /api/health endpoint reachable.
# Includes end-to-end alert injection via Alertmanager v2 API.

set -euo pipefail

check() {
    local name="$1" url="$2"
    for i in 1 2 3 4 5 6; do
        if curl -fsS --max-time 5 "$url" >/dev/null 2>&1; then
            echo "  ✓ $name ready ($url)"
            return 0
        fi
        sleep 5
    done
    echo "  ✗ $name not ready after 30s ($url)" >&2
    return 1
}

echo "Smoke check observability stack..."
check prometheus    "http://127.0.0.1:9090/-/ready"
check alertmanager  "http://127.0.0.1:9093/-/ready"
check grafana       "http://127.0.0.1:3000/api/health"

# Loki and Tempo are not exposed on host by default; check via container exec
if docker ps --format '{{.Names}}' | grep -q '^factory-ems-obs[-_]loki[-_]'; then
    if docker exec "$(docker ps --filter 'name=factory-ems-obs.*loki' --format '{{.Names}}' | head -n1)" \
         wget -q --spider http://127.0.0.1:3100/ready 2>/dev/null; then
        echo "  ✓ loki ready (internal)"
    else
        echo "  ⚠ loki readiness probe failed (may still be warming up)"
    fi
fi

if docker ps --format '{{.Names}}' | grep -q '^factory-ems-obs[-_]tempo[-_]'; then
    if docker exec "$(docker ps --filter 'name=factory-ems-obs.*tempo' --format '{{.Names}}' | head -n1)" \
         wget -q --spider http://127.0.0.1:3200/ready 2>/dev/null; then
        echo "  ✓ tempo ready (internal)"
    else
        echo "  ⚠ tempo readiness probe failed (may still be warming up)"
    fi
fi

echo "All exposed services ready."

echo "Injecting test alert via Alertmanager API..."
curl -fsS -X POST http://127.0.0.1:9093/api/v2/alerts -H 'Content-Type: application/json' -d '[
  {"labels": {"alertname":"SmokeTest","severity":"warning","instance":"smoke"},
   "annotations": {"summary":"smoke test"},
   "startsAt":"'"$(date -u +%Y-%m-%dT%H:%M:%SZ)"'"}
]'
sleep 5
echo "Verifying alert propagated to alertmanager..."
curl -fsS http://127.0.0.1:9093/api/v2/alerts | jq -e '.[] | select(.labels.alertname=="SmokeTest")' >/dev/null
echo "  ✓ alert flow OK"
