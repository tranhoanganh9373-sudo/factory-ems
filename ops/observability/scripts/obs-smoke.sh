#!/usr/bin/env bash
# Smoke check: each service /ready or /api/health endpoint reachable.
# Phase F2 will extend this with end-to-end alert injection.

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
