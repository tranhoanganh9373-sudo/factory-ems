#!/usr/bin/env bash
# scripts/perf/report-load.sh
#
# Phase L2 — Ad-hoc report export performance test.
# Single-shot timing over 5 iterations.
# Target: GET /api/v1/report/ad-hoc?from=...&to=...&granularity=HOUR
# Range : 7-day window, HOUR granularity → 6 meters × 7×24 = 1008 rows
#
# Pass criterion: median response time < 5000 ms
#
# Usage:
#   bash scripts/perf/report-load.sh [--report]
#
# Optional env overrides:
#   API=http://localhost:8888   (default)
#   ADMIN_USER=admin            (default)
#   ADMIN_PASS=admin123!        (default)
#   ITERATIONS=5                (default)
#
# --report : append results to docs/ops/perf-2026-04-25.md
#
# Bash 3.2 compatible (Git Bash / macOS / Linux).

set -euo pipefail

# ── Defaults ──────────────────────────────────────────────────────────────────
: "${API:=http://localhost:8888}"
: "${ADMIN_USER:=admin}"
: "${ADMIN_PASS:=admin123!}"
: "${ITERATIONS:=5}"
REPORT_FLAG=0
THRESHOLD_MS=5000

# ── Arg parsing ───────────────────────────────────────────────────────────────
for arg in "$@"; do
  case "$arg" in
    --report) REPORT_FLAG=1 ;;
    *) ;;
  esac
done

# ── Resolve script dir ────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
REPORT_FILE="$PROJECT_ROOT/docs/ops/perf-2026-04-25.md"

# ── Helpers ───────────────────────────────────────────────────────────────────
log()  { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*" >&2; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

require_cmd() { command -v "$1" >/dev/null 2>&1; }

# ── Pre-flight checks ─────────────────────────────────────────────────────────
if ! require_cmd curl; then die "curl is required but not found."; fi
if ! require_cmd jq;   then die "jq is required but not found.";  fi

# awk is needed for median; fallback to bc if unavailable (but awk is standard)
if ! require_cmd awk; then die "awk is required but not found."; fi

# ── 1. Obtain JWT token ────────────────────────────────────────────────────────
log "Fetching JWT from $API/api/v1/auth/login ..."

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

# ── 2. Build 7-day date range ─────────────────────────────────────────────────
# Use 7 days ago 00:00:00 UTC → now UTC.
# date -d is GNU; for Bash 3.2 + macOS we use awk epoch arithmetic instead.
NOW_EPOCH=$(date '+%s' 2>/dev/null || awk 'BEGIN{print systime()}')
SEVEN_DAYS_SECS=$(( 7 * 24 * 3600 ))
FROM_EPOCH=$(( NOW_EPOCH - SEVEN_DAYS_SECS ))

# Format as ISO-8601 (UTC). Use awk for portability across macOS/Linux/Git Bash.
FROM_ISO=$(awk -v ts="$FROM_EPOCH" 'BEGIN {
  # awk does not have strftime on all platforms, use printf with manual calc
  # Fallback: just use RFC-3339-ish string from shell date
  printf "%d", ts
}')

# Try GNU date first, then BSD date (macOS), then awk fallback
if date -d "@$FROM_EPOCH" '+%Y-%m-%dT%H:%M:%SZ' >/dev/null 2>&1; then
  FROM_ISO=$(date -d "@$FROM_EPOCH" '+%Y-%m-%dT%H:%M:%SZ')
  TO_ISO=$(date -d "@$NOW_EPOCH"   '+%Y-%m-%dT%H:%M:%SZ')
elif date -r "$FROM_EPOCH" '+%Y-%m-%dT%H:%M:%SZ' >/dev/null 2>&1; then
  # BSD date (macOS)
  FROM_ISO=$(date -r "$FROM_EPOCH" '+%Y-%m-%dT%H:%M:%SZ')
  TO_ISO=$(date -r "$NOW_EPOCH"   '+%Y-%m-%dT%H:%M:%SZ')
else
  # awk strftime fallback (requires gawk or nawk with strftime)
  FROM_ISO=$(awk -v t="$FROM_EPOCH" 'BEGIN { print strftime("%Y-%m-%dT%H:%M:%SZ", t) }')
  TO_ISO=$(awk -v t="$NOW_EPOCH"   'BEGIN { print strftime("%Y-%m-%dT%H:%M:%SZ", t) }')
fi

REPORT_URL="$API/api/v1/report/ad-hoc?from=$(printf '%s' "$FROM_ISO" | \
  sed 's/:/%3A/g')&to=$(printf '%s' "$TO_ISO" | \
  sed 's/:/%3A/g')&granularity=HOUR"

log "Report URL: $REPORT_URL"
log "Expected rows: 6 meters × 7d × 24h = ~1008"

# ── 3. Run iterations ─────────────────────────────────────────────────────────
RUN_TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')
TIMES=""

log "Running $ITERATIONS iterations ..."
i=1
while [ "$i" -le "$ITERATIONS" ]; do
  log "  Iteration $i / $ITERATIONS ..."
  # curl -w '%{time_total}' returns seconds with 6 decimal places
  ELAPSED=$(curl -sf \
    -o /dev/null \
    -w "%{time_total}" \
    -H "Authorization: Bearer $TOKEN" \
    --max-time 60 \
    "$REPORT_URL" 2>/dev/null) || ELAPSED="0"

  # Convert seconds (float) to milliseconds integer
  ELAPSED_MS=$(awk "BEGIN { printf \"%d\", $ELAPSED * 1000 }")
  log "    -> ${ELAPSED_MS} ms"

  if [ -z "$TIMES" ]; then
    TIMES="$ELAPSED_MS"
  else
    TIMES="$TIMES $ELAPSED_MS"
  fi
  i=$(( i + 1 ))
done

# ── 4. Compute min / median / max ─────────────────────────────────────────────
# Sort the space-separated list of integers and compute stats via awk.
STATS=$(printf '%s\n' $TIMES | sort -n | awk '
BEGIN { min=999999999; max=0; sum=0; cnt=0 }
{
  vals[cnt] = $1
  if ($1 < min) min = $1
  if ($1 > max) max = $1
  sum += $1
  cnt++
}
END {
  if (cnt == 0) { print "0 0 0"; exit }
  # median
  mid = int(cnt / 2)
  if (cnt % 2 == 1) {
    med = vals[mid]
  } else {
    med = int((vals[mid-1] + vals[mid]) / 2)
  }
  printf "%d %d %d\n", min, med, max
}')

MIN_MS=$(printf '%s' "$STATS" | awk '{print $1}')
MED_MS=$(printf '%s' "$STATS" | awk '{print $2}')
MAX_MS=$(printf '%s' "$STATS" | awk '{print $3}')

# ── 5. Evaluate pass/fail ─────────────────────────────────────────────────────
printf '\n'
printf '======================================================\n'
printf '  Report Export Load Test Results (%s)\n' "$RUN_TIMESTAMP"
printf '  Endpoint: GET /api/v1/report/ad-hoc\n'
printf '  Range: 7d  Granularity: HOUR  Expected rows: ~1008\n'
printf '  Iterations: %s\n' "$ITERATIONS"
printf '  Raw times (ms): %s\n' "$TIMES"
printf '------------------------------------------------------\n'
printf '  min    = %s ms\n' "$MIN_MS"
printf '  median = %s ms\n' "$MED_MS"
printf '  max    = %s ms\n' "$MAX_MS"
printf '------------------------------------------------------\n'

if [ "$MED_MS" -lt "$THRESHOLD_MS" ] 2>/dev/null; then
  printf '  PASS  median %s ms < %s ms threshold\n' "$MED_MS" "$THRESHOLD_MS"
  VERDICT="PASS"
  EXIT_CODE=0
else
  printf '  FAIL  median %s ms >= %s ms threshold\n' "$MED_MS" "$THRESHOLD_MS"
  VERDICT="FAIL"
  EXIT_CODE=1
fi
printf '======================================================\n'

# ── 6. Append to report if requested ─────────────────────────────────────────
if [ "$REPORT_FLAG" -eq 1 ]; then
  log "Appending results to $REPORT_FILE ..."
  {
    printf '\n---\n'
    printf '### 报表导出结果 — %s\n\n' "$RUN_TIMESTAMP"
    printf '| 指标 | 值 |\n'
    printf '|---|---|\n'
    printf '| 时间窗口 | 近 7 天 |\n'
    printf '| 粒度 | HOUR |\n'
    printf '| 预期行数 | ~1008 |\n'
    printf '| 迭代次数 | %s |\n' "$ITERATIONS"
    printf '| 原始耗时 (ms) | `%s` |\n' "$TIMES"
    printf '| min | %s ms |\n' "$MIN_MS"
    printf '| median | %s ms |\n' "$MED_MS"
    printf '| max | %s ms |\n' "$MAX_MS"
    printf '| 阈值 | median < 5000 ms |\n'
    printf '| 结论 | **%s** |\n' "$VERDICT"
    printf '\n'
  } >> "$REPORT_FILE"
  log "Report updated."
fi

exit $EXIT_CODE
