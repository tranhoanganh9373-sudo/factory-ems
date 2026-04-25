#!/usr/bin/env bash
# scripts/seed-influx.sh
# Seeds 7 days of synthetic energy_reading data into InfluxDB.
# Compatible with Git Bash for Windows (bash 3.2+, no GNU-only coreutils).
#
# Usage:
#   INFLUX_TOKEN=$EMS_INFLUX_TOKEN bash scripts/seed-influx.sh
#
# Optional overrides:
#   INFLUX_URL=http://localhost:8086
#   INFLUX_ORG=factory
#   INFLUX_BUCKET=factory_ems

set -euo pipefail

: "${INFLUX_URL:=http://localhost:8086}"
: "${INFLUX_TOKEN:?must be set — export INFLUX_TOKEN=<your-token>}"
: "${INFLUX_ORG:=factory}"
: "${INFLUX_BUCKET:=factory_ems}"

MEASUREMENT="energy_reading"
DAYS=7
INTERVAL_MINUTES=5
POINTS_PER_DAY=$(( 24 * 60 / INTERVAL_MINUTES ))   # 288
TOTAL_POINTS=$(( DAYS * POINTS_PER_DAY ))             # 2016

# Meter definitions: "code|energy_type|base_value|amplitude"
# base_value: typical per-interval reading; amplitude: daily sinusoidal swing
METERS="M-1|ELEC|50|20
M-2|ELEC|40|15
M-3|WATER|8|3
M-4|WATER|6|2
M-5|GAS|12|5
M-6|STEAM|5|2"

# Current time in seconds since epoch (UTC), rounded down to 5-min boundary
NOW=$(date -u +%s)
BOUNDARY=$(( NOW - NOW % (INTERVAL_MINUTES * 60) ))
# Start = BOUNDARY minus 7 days
START_TS=$(( BOUNDARY - DAYS * 24 * 3600 ))

# Tiny pseudo-random jitter: deterministic but varies per point
# Uses linear congruential generator seeded per meter
lcg_next() {
    # lcg_state must be set before calling; updates lcg_state in-place
    lcg_state=$(( (1103515245 * lcg_state + 12345) & 0x7fffffff ))
    echo "$lcg_state"
}

# Integer-only sine approximation: sin(x) ~ x - x^3/6 for small x
# We use a lookup table for 12 discrete hour-of-day buckets (0..11 → 0..23)
# Returns value scaled by 1000 (i.e., sin * 1000 as integer)
# Precomputed: sin(2π*h/24)*1000 for h=0..23
SIN_TABLE="0 259 500 707 866 966 1000 966 866 707 500 259 0 -259 -500 -707 -866 -966 -1000 -966 -866 -707 -500 -259"

sin_hour() {
    local h=$1
    local i=0
    local val
    for val in $SIN_TABLE; do
        if [ "$i" -eq "$h" ]; then
            echo "$val"
            return
        fi
        i=$(( i + 1 ))
    done
    echo "0"
}

echo "=== InfluxDB Seed Script ==="
echo "URL:     $INFLUX_URL"
echo "Org:     $INFLUX_ORG"
echo "Bucket:  $INFLUX_BUCKET"
echo "Window:  $DAYS days, $INTERVAL_MINUTES-min resolution, $TOTAL_POINTS points/meter"
echo ""

total_written=0

# Process each meter
IFS=$'\n'
for meter_def in $METERS; do
    # Parse fields from the meter definition line
    meter_code=$(echo "$meter_def" | cut -d'|' -f1)
    energy_type=$(echo "$meter_def" | cut -d'|' -f2)
    base_val=$(echo "$meter_def"   | cut -d'|' -f3)
    amplitude=$(echo "$meter_def"  | cut -d'|' -f4)

    echo "Seeding meter $meter_code ($energy_type) ..."

    # Seed LCG with a value derived from meter code
    meter_num=$(echo "$meter_code" | tr -d 'M-')
    lcg_state=$(( meter_num * 31337 + 1 ))

    # Build line-protocol payload in a temp file
    tmp_file=$(mktemp /tmp/influx_seed_XXXXXX.lp)

    i=0
    while [ "$i" -lt "$TOTAL_POINTS" ]; do
        ts=$(( START_TS + i * INTERVAL_MINUTES * 60 ))

        # Hour of day for sinusoidal pattern
        hour=$(( (ts % 86400) / 3600 ))

        # sin_hour returns value*1000
        sin_val=$(sin_hour "$hour")

        # swing = amplitude * sin_val / 1000  (integer division)
        swing=$(( amplitude * sin_val / 1000 ))

        # jitter in range -2..+2  (lcg gives 0..0x7fffffff)
        lcg_state=$(( (1103515245 * lcg_state + 12345) & 0x7fffffff ))
        jitter=$(( (lcg_state % 5) - 2 ))

        # Final value = base + swing + jitter, minimum 1
        val=$(( base_val + swing + jitter ))
        if [ "$val" -lt 1 ]; then
            val=1
        fi

        # InfluxDB line protocol: measurement,tags field=value timestamp_ns
        ts_ns=$(( ts * 1000000000 ))
        echo "${MEASUREMENT},meter_code=${meter_code},energy_type=${energy_type} value=${val} ${ts_ns}" >> "$tmp_file"

        i=$(( i + 1 ))
    done

    # Write to InfluxDB via HTTP API (line protocol endpoint)
    http_status=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "${INFLUX_URL}/api/v2/write?org=${INFLUX_ORG}&bucket=${INFLUX_BUCKET}&precision=ns" \
        -H "Authorization: Token ${INFLUX_TOKEN}" \
        -H "Content-Type: text/plain; charset=utf-8" \
        --data-binary "@${tmp_file}")

    rm -f "$tmp_file"

    if [ "$http_status" -eq 204 ]; then
        echo "  -> OK ($TOTAL_POINTS points written, HTTP $http_status)"
        total_written=$(( total_written + TOTAL_POINTS ))
    else
        echo "  -> ERROR: HTTP $http_status for meter $meter_code" >&2
        exit 1
    fi
done
IFS=' '

echo ""
echo "=== Seed complete ==="
echo "Total points written: $total_written ($(echo "$METERS" | wc -l | tr -d ' ') meters x $TOTAL_POINTS points)"
echo "Time range: $(date -u -d "@${START_TS}" '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date -u -r "${START_TS}" '+%Y-%m-%dT%H:%M:%SZ') to now"
