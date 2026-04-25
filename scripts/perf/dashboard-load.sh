#!/usr/bin/env bash
# scripts/perf/dashboard-load.sh
#
# Phase L1 — Dashboard concurrency performance test.
# 50 concurrent users × 100 requests each = 5000 total
# Targets: GET /api/v1/dashboard/kpi?range=TODAY
#          GET /api/v1/dashboard/realtime-series?range=LAST_24H
#
# Pass criterion: p95 < 1000 ms
#
# Usage:
#   bash scripts/perf/dashboard-load.sh [--report]
#
# Optional env overrides:
#   API=http://localhost:8888     (default)
#   ADMIN_USER=admin              (default)
#   ADMIN_PASS=admin123!          (default)
#   CONCURRENCY=50                (default)
#   TOTAL=5000                    (default; hey uses per-url, mjs uses total)
#
# --report  : append results to docs/ops/perf-2026-04-25.md
#
# Bash 3.2 compatible (Git Bash / macOS / Linux).

set -euo pipefail

# ── Defaults ──────────────────────────────────────────────────────────────────
: "${API:=http://localhost:8888}"
: "${ADMIN_USER:=admin}"
: "${ADMIN_PASS:=admin123!}"
: "${CONCURRENCY:=50}"
: "${TOTAL:=5000}"
REPORT_FLAG=0

# ── Arg parsing ───────────────────────────────────────────────────────────────
for arg in "$@"; do
  case "$arg" in
    --report) REPORT_FLAG=1 ;;
    *) ;;
  esac
done

# ── Resolve script dir (works in Bash 3.2) ────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
REPORT_FILE="$PROJECT_ROOT/docs/ops/perf-2026-04-25.md"
MJS_RUNNER="$SCRIPT_DIR/dashboard-load.mjs"

# ── Helpers ───────────────────────────────────────────────────────────────────
log()  { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*" >&2; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

require_cmd() { command -v "$1" >/dev/null 2>&1; }

# ── 1. Obtain JWT token ────────────────────────────────────────────────────────
log "Fetching JWT from $API/api/v1/auth/login ..."

if ! require_cmd curl; then die "curl is required but not found."; fi
if ! require_cmd jq;   then die "jq is required but not found.";  fi

TOKEN_RESPONSE=$(curl -sf \
  -X POST "$API/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" \
  --max-time 30) || die "Login request failed. Is the app running at $API?"

TOKEN=$(printf '%s' "$TOKEN_RESPONSE" | jq -r '.data.accessToken // .accessToken // empty')
if [ -z "$TOKEN" ]; then
  die "Could not extract accessToken from login response: $TOKEN_RESPONSE"
fi
log "Token obtained (${#TOKEN} chars)."

# ── 2. Choose runner: hey or Node.js fallback ─────────────────────────────────
RUN_TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')
P50=""; P90=""; P95=""; P99=""; MEAN=""; ERROR_RATE=""
RUNNER_USED=""

if require_cmd hey; then
  # ── hey path ────────────────────────────────────────────────────────────────
  log "Using hey for load test (concurrency=$CONCURRENCY, total=$TOTAL)."
  RUNNER_USED="hey"

  # Per-URL count = TOTAL / 2 endpoints (rounded down)
  PER_URL=$(( TOTAL / 2 ))

  URL_KPI="$API/api/v1/dashboard/kpi?range=TODAY"
  URL_RS="$API/api/v1/dashboard/realtime-series?range=LAST_24H"

  parse_hey_latency() {
    # hey outputs a "Latency distribution" section like:
    #   10% in 0.0123 secs
    #   50% in 0.0456 secs
    # Convert to ms integers.
    local pct="$1"
    local hey_output="$2"
    local secs
    secs=$(printf '%s\n' "$hey_output" | grep -E "^\s+${pct}% in" | \
      sed 's/.*in \([0-9.]*\) secs.*/\1/' | head -1)
    if [ -n "$secs" ]; then
      # Multiply by 1000, awk handles float → int
      awk "BEGIN { printf \"%d\", $secs * 1000 }"
    else
      printf '0'
    fi
  }

  parse_hey_error_rate() {
    local hey_output="$1"
    local total_req
    local success_req
    total_req=$(printf '%s\n' "$hey_output" | grep -E "^\s+\[200\]" | \
      awk '{print $2}' | head -1)
    # If no 200 line found, treat all as errors
    if [ -z "$total_req" ]; then
      printf '1.00'
      return
    fi
    # Sum all status lines for total
    local all_sum
    all_sum=$(printf '%s\n' "$hey_output" | grep -E "^\s+\[[0-9]+\]" | \
      awk '{s+=$2} END {print s}')
    awk "BEGIN { printf \"%.4f\", 1 - ($total_req / $all_sum) }"
  }

  log "Phase 1: KPI endpoint ($PER_URL requests, c=$CONCURRENCY) ..."
  HEY_KPI=$(hey -n "$PER_URL" -c "$CONCURRENCY" \
    -H "Authorization: Bearer $TOKEN" \
    "$URL_KPI" 2>&1) || true

  log "Phase 2: Realtime-series endpoint ($PER_URL requests, c=$CONCURRENCY) ..."
  HEY_RS=$(hey -n "$PER_URL" -c "$CONCURRENCY" \
    -H "Authorization: Bearer $TOKEN" \
    "$URL_RS" 2>&1) || true

  # Combine both hey outputs for latency stats (use the worse of the two for p95)
  # We compute stats from each and take the higher p95
  KPI_P50=$(parse_hey_latency 50 "$HEY_KPI")
  KPI_P90=$(parse_hey_latency 90 "$HEY_KPI")
  KPI_P95=$(parse_hey_latency 95 "$HEY_KPI")
  KPI_P99=$(parse_hey_latency 99 "$HEY_KPI")

  RS_P50=$(parse_hey_latency 50 "$HEY_RS")
  RS_P90=$(parse_hey_latency 90 "$HEY_RS")
  RS_P95=$(parse_hey_latency 95 "$HEY_RS")
  RS_P99=$(parse_hey_latency 99 "$HEY_RS")

  # Use max of the two endpoints for overall p-values
  P50=$(( KPI_P50 > RS_P50 ? KPI_P50 : RS_P50 ))
  P90=$(( KPI_P90 > RS_P90 ? KPI_P90 : RS_P90 ))
  P95=$(( KPI_P95 > RS_P95 ? KPI_P95 : RS_P95 ))
  P99=$(( KPI_P99 > RS_P99 ? KPI_P99 : RS_P99 ))

  KPI_ERR=$(parse_hey_error_rate "$HEY_KPI")
  RS_ERR=$(parse_hey_error_rate "$HEY_RS")
  ERROR_RATE=$(awk "BEGIN { e=($KPI_ERR + $RS_ERR)/2; printf \"%.4f\",e }")

  # Mean: parse "Average" line from hey
  parse_hey_mean() {
    local hey_output="$1"
    local secs
    secs=$(printf '%s\n' "$hey_output" | grep -E "^\s+Average:" | \
      sed 's/.*Average:\s*\([0-9.]*\) secs.*/\1/' | head -1)
    if [ -n "$secs" ]; then
      awk "BEGIN { printf \"%d\", $secs * 1000 }"
    else
      printf '0'
    fi
  }
  KPI_MEAN=$(parse_hey_mean "$HEY_KPI")
  RS_MEAN=$(parse_hey_mean "$HEY_RS")
  MEAN=$(( (KPI_MEAN + RS_MEAN) / 2 ))

elif require_cmd node; then
  # ── Node.js fallback path ───────────────────────────────────────────────────
  if [ ! -f "$MJS_RUNNER" ]; then
    die "Node.js fallback $MJS_RUNNER not found."
  fi
  log "hey not found. Using Node.js fallback (concurrency=$CONCURRENCY, total=$TOTAL)."
  RUNNER_USED="node"

  MJS_OUT=$(node "$MJS_RUNNER" "$API" "$TOKEN" "$CONCURRENCY" "$TOTAL" 2>/dev/null)
  P50=$(printf '%s' "$MJS_OUT"     | jq -r '.p50')
  P90=$(printf '%s' "$MJS_OUT"     | jq -r '.p90')
  P95=$(printf '%s' "$MJS_OUT"     | jq -r '.p95')
  P99=$(printf '%s' "$MJS_OUT"     | jq -r '.p99')
  MEAN=$(printf '%s' "$MJS_OUT"    | jq -r '.mean')
  ERROR_RATE=$(printf '%s' "$MJS_OUT" | jq -r '.errorRate')

else
  die "Neither 'hey' nor 'node' found. Install one to run this test."
fi

# ── 3. Evaluate pass/fail ─────────────────────────────────────────────────────
P95_INT=$(printf '%.0f' "$P95" 2>/dev/null || printf '%s' "$P95")
THRESHOLD=1000

printf '\n'
printf '======================================================\n'
printf '  Dashboard Load Test Results (%s)\n' "$RUN_TIMESTAMP"
printf '  Runner: %s\n' "$RUNNER_USED"
printf '  Endpoints: kpi?range=TODAY + realtime-series?range=LAST_24H\n'
printf '  Requests: %s  Concurrency: %s\n' "$TOTAL" "$CONCURRENCY"
printf '------------------------------------------------------\n'
printf '  p50  = %s ms\n'  "$P50"
printf '  p90  = %s ms\n'  "$P90"
printf '  p95  = %s ms\n'  "$P95"
printf '  p99  = %s ms\n'  "$P99"
printf '  mean = %s ms\n'  "$MEAN"
printf '  errorRate = %s\n' "$ERROR_RATE"
printf '------------------------------------------------------\n'

if [ "$P95_INT" -lt "$THRESHOLD" ] 2>/dev/null; then
  printf '  PASS  p95 %s ms < %s ms threshold\n' "$P95" "$THRESHOLD"
  VERDICT="PASS"
  EXIT_CODE=0
else
  printf '  FAIL  p95 %s ms >= %s ms threshold\n' "$P95" "$THRESHOLD"
  VERDICT="FAIL"
  EXIT_CODE=1
fi
printf '======================================================\n'

# ── 4. Append to report if requested ─────────────────────────────────────────
if [ "$REPORT_FLAG" -eq 1 ]; then
  log "Appending results to $REPORT_FILE ..."
  {
    printf '\n---\n'
    printf '### 看板压测结果 — %s\n\n' "$RUN_TIMESTAMP"
    printf '| 指标 | 值 |\n'
    printf '|---|---|\n'
    printf '| Runner | %s |\n' "$RUNNER_USED"
    printf '| 并发数 | %s |\n' "$CONCURRENCY"
    printf '| 总请求 | %s |\n' "$TOTAL"
    printf '| p50 | %s ms |\n' "$P50"
    printf '| p90 | %s ms |\n' "$P90"
    printf '| p95 | %s ms |\n' "$P95"
    printf '| p99 | %s ms |\n' "$P99"
    printf '| mean | %s ms |\n' "$MEAN"
    printf '| errorRate | %s |\n' "$ERROR_RATE"
    printf '| 阈值 | p95 < 1000 ms |\n'
    printf '| 结论 | **%s** |\n' "$VERDICT"
    printf '\n'
  } >> "$REPORT_FILE"
  log "Report updated."
fi

exit $EXIT_CODE
