#!/usr/bin/env bash
# Factory EMS — 批量导入 collector channels（Modbus TCP）
#
# 用法:
#   EMS_BASE_URL=https://ems.example.com  EMS_TOKEN=<jwt> \
#     ./scripts/import-channels.sh docs/install/channel-config-import.json
#
# 前置:
#   - JSON 文件结构见 docs/install/channel-config-import.json
#   - JWT Token 取得见 docs/api/auth-api.md
#   - 角色需为 ADMIN（POST /api/v1/channel 的权限要求）
#
# 行为:
#   - 逐条 POST /api/v1/channel
#   - 同名通道（DB 唯一约束）会被服务端 409 拒绝；已存在的会跳过
#   - 任一失败立即停止（exit 非 0），不做回滚
#   - --dry-run 仅打印将发送的 body，不真实请求

set -euo pipefail

DRY_RUN=0
JSON_FILE=""

usage() {
  cat <<EOF
Usage: $0 [--dry-run] <channels.json>

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

count=$(jq '.channels | length' "$JSON_FILE")
echo "Loaded $count channel(s) from $JSON_FILE"
echo

ok=0
skip=0
fail=0
for i in $(seq 0 $((count - 1))); do
  body=$(jq -c ".channels[$i]" "$JSON_FILE")
  name=$(echo "$body" | jq -r '.name')
  host=$(echo "$body" | jq -r '.protocolConfig.host')
  npts=$(echo "$body" | jq -r '.protocolConfig.points | length')

  printf '[%d/%d] %-20s host=%-15s points=%d ... ' \
    "$((i+1))" "$count" "$name" "$host" "$npts"

  if [[ $DRY_RUN -eq 1 ]]; then
    echo "(dry-run)"
    echo "$body" | jq .
    echo
    continue
  fi

  # POST and capture HTTP status separately from body
  tmp=$(mktemp)
  http=$(curl -sS -o "$tmp" -w '%{http_code}' \
    -X POST \
    -H "Authorization: Bearer $EMS_TOKEN" \
    -H "Content-Type: application/json" \
    -d "$body" \
    "$EMS_BASE_URL/api/v1/channel" || echo "000")

  case "$http" in
    200|201)
      id=$(jq -r '.id' "$tmp")
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
      exit 2
      ;;
  esac
  rm -f "$tmp"
done

echo
echo "Summary: ok=$ok skip=$skip fail=$fail total=$count"

if [[ $DRY_RUN -eq 0 && $ok -gt 0 ]]; then
  echo
  echo "Verifying connection state ..."
  curl -sS -H "Authorization: Bearer $EMS_TOKEN" \
    "$EMS_BASE_URL/api/v1/collector/state" \
    | jq '[.[] | {channelId, protocol, connState, successCount24h}]'
fi
