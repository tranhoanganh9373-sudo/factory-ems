#!/usr/bin/env bash
# Factory EMS — 月度报表自动出 PDF + 邮件推送
#
# 配套：docs/install/report-automation-sop.md
#
# 用途：cron 每月 1 号 06:00 跑，出上月月度成本报 PDF + 邮件给收件人
#
# 必填环境变量:
#   EMS_BASE_URL        e.g. https://ems.example.com（无尾斜杠）
#   EMS_TOKEN           JWT Bearer Token（建议 READONLY 角色 bot 账号）
#   REPORT_RECIPIENTS   收件人，逗号分隔，e.g. "boss@example.com,finance@example.com"
#
# 可选环境变量:
#   REPORT_DRY_RUN      1 = 只下 PDF 不发邮件（测试用）
#   REPORT_YEAR_MONTH   报表月份，YYYY-MM；默认 = 上月
#   REPORT_PRESET       预设种类，默认 COST_MONTHLY；可改 MONTHLY / YEARLY 等
#   REPORT_ORG_ID       组织节点 id，默认 1（工厂根）
#   REPORT_TMP_DIR      PDF 落盘临时目录，默认 /tmp
#   MSMTP_ACCOUNT       msmtp 账号名，默认 report-bot
#
# Cron 示例（详见 SOP §3.2）：
#   0 6 1 * * ems  EMS_BASE_URL=... EMS_TOKEN=... REPORT_RECIPIENTS=... \
#                  /opt/factory-ems/scripts/monthly-report-mail.sh \
#                  >> /var/log/ems-report.log 2>&1

set -euo pipefail

# ---------- 入参校验 ----------

: "${EMS_BASE_URL:?EMS_BASE_URL not set}"
: "${EMS_TOKEN:?EMS_TOKEN not set}"

DRY_RUN="${REPORT_DRY_RUN:-0}"
PRESET="${REPORT_PRESET:-COST_MONTHLY}"
ORG_ID="${REPORT_ORG_ID:-1}"
TMP_DIR="${REPORT_TMP_DIR:-/tmp}"
MSMTP_ACCOUNT="${MSMTP_ACCOUNT:-report-bot}"

if [[ "$DRY_RUN" != "1" ]]; then
  : "${REPORT_RECIPIENTS:?REPORT_RECIPIENTS not set (or set REPORT_DRY_RUN=1 to skip mail)}"
  # 防 email header injection：CR/LF 会注入额外 To/Bcc/Reply-To header
  if [[ "$REPORT_RECIPIENTS" =~ $'\n'|$'\r' ]]; then
    echo "ERR: REPORT_RECIPIENTS 含 CR/LF，拒绝以防 header 注入" >&2
    exit 1
  fi
fi

command -v curl >/dev/null || { echo "ERR: curl required" >&2; exit 1; }
command -v jq   >/dev/null || { echo "ERR: jq required" >&2; exit 1; }

# ---------- 计算上月（兼容 GNU date 与 BSD/macOS date） ----------

if [[ -n "${REPORT_YEAR_MONTH:-}" ]]; then
  # 防注入：会拼到 jq params 和 PDF 文件名
  if [[ ! "$REPORT_YEAR_MONTH" =~ ^[0-9]{4}-(0[1-9]|1[0-2])$ ]]; then
    echo "ERR: REPORT_YEAR_MONTH 格式必须是 YYYY-MM（含合法月份）" >&2
    exit 1
  fi
  YM="$REPORT_YEAR_MONTH"
elif date -d "last month" +%Y-%m >/dev/null 2>&1; then
  YM=$(date -d "last month" +%Y-%m)            # GNU
else
  YM=$(date -v-1m +%Y-%m)                      # BSD / macOS
fi

echo "[$(date -Iseconds)] start: yearMonth=$YM preset=$PRESET orgId=$ORG_ID dry_run=$DRY_RUN"

# ---------- 1) 提交异步导出 ----------

REQ_BODY=$(jq -n \
  --arg ym "$YM" \
  --arg preset "$PRESET" \
  --argjson orgId "$ORG_ID" \
  '{
    format: "PDF",
    preset: $preset,
    params: { yearMonth: $ym, orgNodeId: $orgId, energyTypes: ["ELEC"] }
  }')

SUBMIT=$(curl -sS -X POST "$EMS_BASE_URL/api/v1/reports/export" \
  -H "Authorization: Bearer $EMS_TOKEN" \
  -H "Content-Type: application/json" \
  -d "$REQ_BODY")

TOKEN_FILE=$(echo "$SUBMIT" | jq -r '.data.token // empty')
if [[ -z "$TOKEN_FILE" ]]; then
  echo "ERR: export submit failed: $SUBMIT" >&2
  exit 2
fi
# 防服务端返回异常 token（含 / .. 等）被拼到 URL/文件路径里 → 路径穿越
if [[ ! "$TOKEN_FILE" =~ ^[A-Za-z0-9_-]+$ ]]; then
  echo "ERR: token 格式非法（只允许字母数字+下划线+连字符）: 长度=${#TOKEN_FILE}" >&2
  exit 2
fi
echo "submitted, token=${TOKEN_FILE:0:8}…（已脱敏，完整 token 仅在内存）"

# ---------- 2) 轮询直到 SUCCESS / FAILED（最长 5 分钟） ----------

DEADLINE=$(( $(date +%s) + 300 ))
while true; do
  META=$(curl -sS -H "Authorization: Bearer $EMS_TOKEN" \
    "$EMS_BASE_URL/api/v1/reports/export/$TOKEN_FILE")
  STATUS=$(echo "$META" | jq -r '.data.status // "UNKNOWN"')
  case "$STATUS" in
    SUCCESS) echo "export ready"; break ;;
    FAILED)
      ERR=$(echo "$META" | jq -r '.data.errorMsg // "unknown"')
      echo "ERR: export failed: $ERR" >&2
      exit 3
      ;;
    PENDING|RUNNING)
      [[ $(date +%s) -gt $DEADLINE ]] && { echo "ERR: timeout waiting for export"; exit 4; }
      sleep 5
      ;;
    *)  echo "ERR: unknown status: $STATUS"; exit 5 ;;
  esac
done

# ---------- 3) 下载 PDF ----------

PDF_PATH="$TMP_DIR/ems-report-${YM}-${TOKEN_FILE}.pdf"
HTTP=$(curl -sS -o "$PDF_PATH" -w '%{http_code}' \
  -H "Authorization: Bearer $EMS_TOKEN" \
  "$EMS_BASE_URL/api/v1/report/file/$TOKEN_FILE")
if [[ "$HTTP" != "200" ]]; then
  echo "ERR: download HTTP $HTTP" >&2
  rm -f "$PDF_PATH"
  exit 6
fi
SIZE=$(wc -c < "$PDF_PATH")
if [[ $SIZE -lt 1000 ]]; then
  echo "ERR: PDF too small ($SIZE bytes), looks broken" >&2
  rm -f "$PDF_PATH"
  exit 7
fi
echo "downloaded: $PDF_PATH ($SIZE bytes)"

# ---------- 4) 发邮件（除非 DRY_RUN） ----------

if [[ "$DRY_RUN" == "1" ]]; then
  echo "DRY_RUN=1, skipping mail. PDF kept at $PDF_PATH"
  exit 0
fi

if ! command -v msmtp >/dev/null && ! command -v sendmail >/dev/null; then
  echo "ERR: neither msmtp nor sendmail found; install msmtp + msmtp-mta" >&2
  exit 8
fi

SUBJECT="EMS 月度成本报表 - $YM"
BOUNDARY="EMS-REPORT-$(date +%s)-$$"
B64=$(base64 < "$PDF_PATH" | tr -d '\n' | fold -w 76)

MAIL=$(cat <<EOF
To: $REPORT_RECIPIENTS
Subject: $SUBJECT
MIME-Version: 1.0
Content-Type: multipart/mixed; boundary="$BOUNDARY"

--$BOUNDARY
Content-Type: text/plain; charset=UTF-8

EMS 系统月度自动报表

报表月份: $YM
预设: $PRESET
组织节点: $ORG_ID

附件为该月度成本报表 PDF。如有问题请联系系统管理员。

-- 自动发送，请勿直接回复
EMS Report Bot
$(date -Iseconds)

--$BOUNDARY
Content-Type: application/pdf
Content-Transfer-Encoding: base64
Content-Disposition: attachment; filename="ems-report-${YM}.pdf"

$B64

--$BOUNDARY--
EOF
)

if command -v msmtp >/dev/null; then
  echo "$MAIL" | msmtp --account="$MSMTP_ACCOUNT" -t
else
  echo "$MAIL" | sendmail -t
fi

echo "mail sent to: $REPORT_RECIPIENTS"
echo "[$(date -Iseconds)] done"

# 保留 PDF 7 天，老的清理（与异步 token 7 天过期对齐）
find "$TMP_DIR" -maxdepth 1 -name 'ems-report-*.pdf' -mtime +7 -delete 2>/dev/null || true
