#!/usr/bin/env bash
# Factory EMS — 批量导入 EMS meters
#
# 用法:
#   EMS_BASE_URL=https://ems.example.com  EMS_TOKEN=<jwt> \
#     ./scripts/import-meters.sh meters.json
#
# 前置:
#   - meters.json 由 ./scripts/csv-to-meters.py 生成（schema 见该文件 doc）
#   - JWT Token，ADMIN 角色（POST /api/v1/meters 权限要求）
#   - channel 已在 EMS 里建好（先跑 ./scripts/import-channels.sh）
#
# 行为:
#   - 启动时 GET /api/v1/channel 一次，建立 channelName → channelId 映射
#   - 逐条 POST /api/v1/meters，body 中 channelName 替换为 channelId
#   - HTTP 409 视为 SKIP（meter.code 唯一约束已存在）
#   - 任一 fatal 失败立即停止，不做回滚
#   - --dry-run 只打印 body，不发请求

set -euo pipefail

DRY_RUN=0
JSON_FILE=""

usage() {
  cat <<EOF
Usage: $0 [--dry-run] <meters.json>

Env required (unless --dry-run):
  EMS_BASE_URL   e.g. https://ems.example.com  (no trailing slash)
  EMS_TOKEN      JWT bearer token, ADMIN role
EOF
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage ;;
    *) JSON_FILE="$1"; shift ;;
  esac
done

[[ -z "$JSON_FILE" ]] && usage
[[ -f "$JSON_FILE" ]] || { echo "ERR: file not found: $JSON_FILE" >&2; exit 1; }
command -v jq >/dev/null || { echo "ERR: jq required" >&2; exit 1; }
command -v curl >/dev/null || { echo "ERR: curl required" >&2; exit 1; }

if [[ $DRY_RUN -eq 0 ]]; then
  : "${EMS_BASE_URL:?EMS_BASE_URL not set}"
  : "${EMS_TOKEN:?EMS_TOKEN not set}"
fi

count=$(jq '.meters | length' "$JSON_FILE")
[[ "$count" -gt 0 ]] || { echo "ERR: no meters in $JSON_FILE" >&2; exit 1; }
echo "Loaded $count meter(s) from $JSON_FILE"

# ---------- 1) 建立 channelName → channelId 映射 ----------

CHAN_MAP_FILE=$(mktemp)
trap 'rm -f "$CHAN_MAP_FILE"' EXIT

if [[ $DRY_RUN -eq 0 ]]; then
  resp=$(curl -sS -H "Authorization: Bearer $EMS_TOKEN" \
    "$EMS_BASE_URL/api/v1/channel")
  # 接口直接返回数组（ChannelController.java:45-47）
  echo "$resp" | jq 'map({(.name): .id}) | add // {}' > "$CHAN_MAP_FILE"
  nchan=$(jq 'length' "$CHAN_MAP_FILE")
  echo "Fetched $nchan channel(s) from EMS for name→id resolution"
else
  echo "{}" > "$CHAN_MAP_FILE"
fi
echo

# ---------- 2) 校验 meters.json 中所有 channelName 都能解析 ----------

if [[ $DRY_RUN -eq 0 ]]; then
  missing=$(jq -r --slurpfile m "$CHAN_MAP_FILE" \
    '.meters
     | map(select(.channelName) | select((.channelName | in($m[0])) | not) | .channelName)
     | unique
     | .[]' "$JSON_FILE")
  if [[ -n "$missing" ]]; then
    echo "ERR: 以下 channelName 在 EMS 中找不到（先跑 import-channels.sh）:" >&2
    echo "$missing" | sed 's/^/  - /' >&2
    exit 2
  fi
fi

# ---------- 3) 逐条 POST ----------

ok=0
skip=0
fail=0
for i in $(seq 0 $((count - 1))); do
  src=$(jq -c ".meters[$i]" "$JSON_FILE")
  code=$(echo "$src" | jq -r '.code')
  cname=$(echo "$src" | jq -r '.channelName // empty')

  # 用解析到的 channelId 替换 channelName；无 channelName 则 body 不带 channelId
  if [[ -n "$cname" ]]; then
    cid=$(jq -r --arg n "$cname" '.[$n] // empty' "$CHAN_MAP_FILE")
    body=$(echo "$src" | jq --argjson cid "${cid:-null}" \
      'del(.channelName) | .channelId = $cid')
  else
    body=$(echo "$src" | jq 'del(.channelName)')
  fi

  printf '[%d/%d] %-40s ... ' "$((i+1))" "$count" "$code"

  if [[ $DRY_RUN -eq 1 ]]; then
    echo "(dry-run)"
    echo "$body" | jq .
    continue
  fi

  tmp=$(mktemp)
  http=$(curl -sS -o "$tmp" -w '%{http_code}' \
    -X POST \
    -H "Authorization: Bearer $EMS_TOKEN" \
    -H "Content-Type: application/json" \
    -d "$body" \
    "$EMS_BASE_URL/api/v1/meters" || echo "000")

  case "$http" in
    200|201)
      id=$(jq -r '.data.id // .id' "$tmp")
      echo "OK (id=$id)"
      ok=$((ok + 1))
      ;;
    409)
      echo "SKIP (already exists)"
      skip=$((skip + 1))
      ;;
    *)
      echo "FAIL (HTTP $http)"
      cat "$tmp"
      echo
      rm -f "$tmp"
      fail=$((fail + 1))
      exit 3
      ;;
  esac
  rm -f "$tmp"
done

echo
echo "Summary: ok=$ok skip=$skip fail=$fail total=$count"
