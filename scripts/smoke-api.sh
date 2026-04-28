#!/usr/bin/env bash
# 全 API smoke：login → 打一遍前端常用 GET endpoint，输出 method url status 三列。
# 用法：scripts/smoke-api.sh [base_url]   默认 http://localhost:8888
set -u
BASE="${1:-http://localhost:8888}"

TOKEN=$(curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' \
  "$BASE/api/v1/auth/login" | grep -oP '"accessToken":"\K[^"]+')

if [[ -z "$TOKEN" ]]; then echo "LOGIN FAILED"; exit 2; fi
echo "token len=${#TOKEN}"
echo

PASS=0; FAIL=0; SLOW=0
hit() {
  local method="$1" path="$2"
  local code dur out
  out=$(curl -s -o /tmp/smoke.body -w "%{http_code} %{time_total}" \
        -X "$method" -H "Authorization: Bearer $TOKEN" "$BASE$path" 2>&1)
  code=${out%% *}; dur=${out##* }
  local mark
  case "$code" in
    2[0-9][0-9]) mark="OK  "; PASS=$((PASS+1)) ;;
    4[0-9][0-9]) mark="4xx "; ;;  # 4xx is informational here, not a fail
    5[0-9][0-9]|000) mark="FAIL"; FAIL=$((FAIL+1)) ;;
    *) mark="??? "; ;;
  esac
  printf "%s %-7s %3s %5.2fs  %s\n" "$mark" "$method" "$code" "$dur" "$path"
  if [[ "$code" == "5"* || "$code" == "000" ]]; then
    head -c 200 /tmp/smoke.body; echo
  fi
}

echo "── auth / users ──"
hit GET /api/v1/auth/me
hit GET /api/v1/users
hit GET /api/v1/roles

echo "── dashboard ──"
hit GET "/api/v1/dashboard/kpi"
hit GET "/api/v1/dashboard/realtime-series?metric=power_active"
hit GET "/api/v1/dashboard/energy-composition?from=2026-04-01T00:00:00Z&to=2026-04-28T00:00:00Z"
hit GET "/api/v1/dashboard/energy-intensity?from=2026-04-01T00:00:00Z&to=2026-04-28T00:00:00Z"
hit GET "/api/v1/dashboard/tariff-distribution?from=2026-04-01T00:00:00Z&to=2026-04-28T00:00:00Z"
hit GET "/api/v1/dashboard/cost-distribution"
# cost-monthly 实际在 ReportPresetController 下；下面 reports 段会打到
hit GET "/api/v1/dashboard/sankey?from=2026-04-01T00:00:00Z&to=2026-04-28T00:00:00Z"
hit GET "/api/v1/dashboard/top-n?from=2026-04-01T00:00:00Z&to=2026-04-28T00:00:00Z&n=5"

echo "── meters / org / energy types ──"
hit GET /api/v1/meters
hit GET /api/v1/org-nodes/tree
hit GET /api/v1/energy-types
hit GET /api/v1/meter-topology

echo "── tariff / shifts / production ──"
hit GET /api/v1/tariff/plans
hit GET /api/v1/shifts
hit GET "/api/v1/production/entries?from=2026-04-01&to=2026-04-28"
hit GET "/api/v1/production/entries/daily-totals?from=2026-04-01&to=2026-04-28&orgNodeId=1"

echo "── cost / billing ──"
hit GET /api/v1/cost/rules
hit GET "/api/v1/cost/runs/1"
hit GET "/api/v1/cost/runs/1/lines"
hit GET /api/v1/bills/periods
hit GET "/api/v1/bills?periodId=1"

echo "── reports ──"
hit GET "/api/v1/report/preset/daily?date=2026-04-26"
hit GET "/api/v1/report/preset/monthly?ym=2026-04"
hit GET "/api/v1/report/preset/yearly?year=2026"
hit GET "/api/v1/report/preset/shift?date=2026-04-26"
hit GET "/api/v1/report/preset/cost-monthly?ym=2026-04"
hit GET "/api/v1/report/ad-hoc?from=2026-04-01T00:00:00Z&to=2026-04-28T00:00:00Z&dim=org&metric=power_active"

echo "── floorplans ──"
hit GET /api/v1/floorplans

echo "── admin ──"
hit GET "/api/v1/audit-logs?page=1&size=20"

echo "── collector ──"
hit GET /api/v1/collector/status

echo "── ops ──"
hit GET /api/v1/ops/rollup/running
hit GET /api/v1/ops/rollup/plans

echo "── actuator ──"
hit GET /actuator/health
hit GET /actuator/info
hit GET /actuator/metrics
hit GET "/actuator/metrics/jvm.memory.used"

echo
echo "════════════════════════════════════════════════════════════════"
echo "   PASS=$PASS  FAIL=$FAIL"
echo "════════════════════════════════════════════════════════════════"
exit $FAIL
