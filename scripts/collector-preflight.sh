#!/usr/bin/env bash
# collector-preflight — 现场装机前一键体检
#
# 用法：
#   scripts/collector-preflight.sh [--config deploy/collector.yml] [--env .env]
#
# 检查项（按依赖关系顺序）：
#   1. 磁盘 & 关键目录可写（buffer / logs / uploads）
#   2. .env 必填项存在
#   3. PostgreSQL / InfluxDB 端口可达
#   4. collector.yml YAML 合法 + 必填字段
#   5. 每个 device 网络可达：
#        TCP → host:port 通
#        RTU → serial-port 设备文件存在 + 当前用户可读
#   6. （可选）每个 TCP device 用 mbpoll 实读 1 个寄存器
#
# 输出：每行一项 [ OK ] / [WARN] / [FAIL] + 末尾一句话汇总。
# 退出码：FAIL=2 / 仅 WARN=1 / 全 OK=0
set -uo pipefail

# ─── 颜色 ──────────────────────────────────────────────────────────────
if [[ -t 1 ]]; then
  C_OK=$'\e[32m'; C_WARN=$'\e[33m'; C_FAIL=$'\e[31m'; C_RESET=$'\e[0m'; C_DIM=$'\e[2m'
else
  C_OK=""; C_WARN=""; C_FAIL=""; C_RESET=""; C_DIM=""
fi

PASS=0; WARN=0; FAIL=0
ok()   { echo "${C_OK}[ OK ]${C_RESET} $*"; PASS=$((PASS+1)); }
warn() { echo "${C_WARN}[WARN]${C_RESET} $*"; WARN=$((WARN+1)); }
fail() { echo "${C_FAIL}[FAIL]${C_RESET} $*"; FAIL=$((FAIL+1)); }
note() { echo "${C_DIM}       $*${C_RESET}"; }
hdr()  { echo; echo "── $* ──"; }

# ─── 参数 ──────────────────────────────────────────────────────────────
CFG="deploy/collector.yml"
ENV_FILE=".env"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --config) CFG="$2"; shift 2 ;;
    --env) ENV_FILE="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,/^set -/p' "$0" | sed 's/^# \?//' | head -n 20
      exit 0 ;;
    *) echo "unknown arg: $1"; exit 2 ;;
  esac
done

# ─── 1. 磁盘 & 目录 ─────────────────────────────────────────────────────
hdr "1) 磁盘 & 目录"

DATA_DIR="${DATA_DIR:-./data}"
LOG_DIR="${LOG_DIR:-./logs}"

for d in "$DATA_DIR" "$LOG_DIR" "$DATA_DIR/collector" "$DATA_DIR/ems_uploads"; do
  if mkdir -p "$d" 2>/dev/null && [[ -w "$d" ]]; then
    ok "目录可写: $d"
  else
    fail "目录不可写: $d"
  fi
done

# 可用空间 ≥ 5G（buffer 7 天 × 100K rows × 50 device ≈ 几百 MB；留余量）
AVAIL_MB=$(df -Pm "$DATA_DIR" 2>/dev/null | awk 'NR==2 {print $4}')
if [[ -n "${AVAIL_MB:-}" ]]; then
  if (( AVAIL_MB >= 5120 )); then
    ok "可用空间 ${AVAIL_MB} MB ≥ 5 GB"
  elif (( AVAIL_MB >= 1024 )); then
    warn "可用空间 ${AVAIL_MB} MB（< 5 GB；7 天 buffer + 30 天日志可能吃紧）"
  else
    fail "可用空间 ${AVAIL_MB} MB（< 1 GB；buffer 落盘极易丢数据）"
  fi
else
  warn "无法检测磁盘空间（df 失败）"
fi

# ─── 2. .env 必填项 ────────────────────────────────────────────────────
hdr "2) .env"

if [[ ! -f "$ENV_FILE" ]]; then
  fail ".env 不存在: $ENV_FILE — 复制 .env.example 后改"
else
  ok ".env 存在: $ENV_FILE"
  # shellcheck disable=SC1090
  set -a; source "$ENV_FILE" 2>/dev/null; set +a
  for k in EMS_DB_PASSWORD EMS_JWT_SECRET EMS_INFLUX_TOKEN INFLUXDB_ADMIN_PASSWORD; do
    v="${!k:-}"
    if [[ -z "$v" ]]; then
      fail "$k 未设置"
    elif [[ "$v" =~ ^(change_me|please-change|test|dev) ]]; then
      warn "$k 看起来还是占位符（$v）— 上线前必须改"
    elif (( ${#v} < 16 )); then
      warn "$k 长度 ${#v} < 16 — 强度太弱"
    else
      ok "$k 已设置（长度 ${#v}）"
    fi
  done
fi

# ─── 3. 依赖端口可达 ───────────────────────────────────────────────────
hdr "3) 依赖端口"

probe_tcp() {
  local host="$1" port="$2" desc="$3" timeout_s="${4:-3}"
  if timeout "$timeout_s" bash -c "</dev/tcp/$host/$port" 2>/dev/null; then
    ok "$desc 可达 ($host:$port)"
    return 0
  else
    fail "$desc 不可达 ($host:$port)"
    return 1
  fi
}

# 这两个跑在同一台部署机上时一般是 localhost / 容器名；compose 网络内部探测建议放容器里跑
PG_HOST="${EMS_DB_HOST:-postgres}"
INFLUX_HOST="${EMS_INFLUX_HOST:-influxdb}"

# 容器名解析不到时跳过；现场建议把脚本扔进 ems-app 容器里跑
if getent hosts "$PG_HOST" >/dev/null 2>&1 || [[ "$PG_HOST" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  probe_tcp "$PG_HOST" 5432 "PostgreSQL" || true
else
  note "PostgreSQL 主机 $PG_HOST 解析不到 — 在 docker compose exec factory-ems 里跑此脚本"
fi

if getent hosts "$INFLUX_HOST" >/dev/null 2>&1 || [[ "$INFLUX_HOST" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  probe_tcp "$INFLUX_HOST" 8086 "InfluxDB" || true
else
  note "InfluxDB 主机 $INFLUX_HOST 解析不到 — 在 docker compose exec factory-ems 里跑此脚本"
fi

# ─── 4. collector.yml 合法 ────────────────────────────────────────────
hdr "4) collector.yml"

if [[ ! -f "$CFG" ]]; then
  fail "配置不存在: $CFG"
elif ! command -v python3 >/dev/null 2>&1; then
  warn "python3 未装 — 跳过 YAML 语法校验（Spring Boot 启动会再校验）"
else
  if python3 -c "import sys, yaml; yaml.safe_load(open(sys.argv[1]))" "$CFG" 2>/dev/null; then
    ok "YAML 语法合法"
    DEVICE_COUNT=$(python3 -c "
import yaml, sys
d = yaml.safe_load(open(sys.argv[1])) or {}
devs = ((d.get('ems') or {}).get('collector') or {}).get('devices') or []
print(len(devs))
" "$CFG")
    ENABLED=$(python3 -c "
import yaml, sys, os
d = yaml.safe_load(open(sys.argv[1])) or {}
e = ((d.get('ems') or {}).get('collector') or {}).get('enabled')
# 处理 \${EMS_COLLECTOR_ENABLED:false} 占位
if isinstance(e, str) and e.startswith('\${'):
    var = e[2:-1].split(':',1)
    e = os.environ.get(var[0], var[1] if len(var)>1 else 'false').lower() == 'true'
print('true' if e else 'false')
" "$CFG")
    if [[ "$ENABLED" == "true" && "$DEVICE_COUNT" -eq 0 ]]; then
      fail "collector.enabled=true 但 devices: 列表为空 — 现场必须填仪表清单"
    elif [[ "$ENABLED" == "true" ]]; then
      ok "collector.enabled=true，配置 $DEVICE_COUNT 个设备"
    else
      warn "collector.enabled=false（current）— 启用前请确认仪表清单已填"
    fi
  else
    fail "YAML 语法错误"
    python3 -c "import yaml; yaml.safe_load(open('$CFG'))" 2>&1 | sed 's/^/       /' | head -n 5
  fi
fi

# ─── 5. 设备级连通性 ───────────────────────────────────────────────────
hdr "5) 设备连通性"

if [[ -f "$CFG" ]] && command -v python3 >/dev/null 2>&1; then
  python3 - <<PY 2>/dev/null > /tmp/collector-preflight-devs.$$
import yaml
d = yaml.safe_load(open("$CFG")) or {}
for x in ((d.get('ems') or {}).get('collector') or {}).get('devices') or []:
    proto = x.get('protocol', '?')
    if proto == 'TCP':
        print(f"TCP\t{x.get('id')}\t{x.get('host')}\t{x.get('port', 502)}")
    elif proto == 'RTU':
        print(f"RTU\t{x.get('id')}\t{x.get('serial-port')}")
PY
  if [[ ! -s /tmp/collector-preflight-devs.$$ ]]; then
    note "无 device 或 collector.enabled=false — 跳过"
  else
    while IFS=$'\t' read -r proto id arg1 arg2; do
      case "$proto" in
        TCP) probe_tcp "$arg1" "$arg2" "device $id" 2 || true ;;
        RTU)
          if [[ -e "$arg1" ]]; then
            if [[ -r "$arg1" && -w "$arg1" ]]; then
              ok "device $id 串口 $arg1 可读写"
            else
              fail "device $id 串口 $arg1 权限不足（需 dialout 组）"
            fi
          else
            fail "device $id 串口 $arg1 不存在"
          fi ;;
      esac
    done < /tmp/collector-preflight-devs.$$
  fi
  rm -f /tmp/collector-preflight-devs.$$
fi

# ─── 6. 可选：mbpoll 直读 ──────────────────────────────────────────────
hdr "6) Modbus 直读（可选 — 需要 mbpoll）"

if ! command -v mbpoll >/dev/null 2>&1; then
  note "mbpoll 未装 — 跳过寄存器直读"
  note "  Debian/Ubuntu: apt-get install mbpoll  /  macOS: brew install mbpoll"
else
  if [[ -f "$CFG" ]] && command -v python3 >/dev/null 2>&1; then
    python3 - <<PY 2>/dev/null > /tmp/collector-preflight-mbpoll.$$
import yaml
d = yaml.safe_load(open("$CFG")) or {}
for x in ((d.get('ems') or {}).get('collector') or {}).get('devices') or []:
    if x.get('protocol') != 'TCP': continue
    regs = x.get('registers') or []
    if not regs: continue
    r = regs[0]
    addr = r.get('address')
    if isinstance(addr, str) and addr.startswith('0x'):
        addr = int(addr, 16)
    print(f"{x.get('id')}\t{x.get('host')}\t{x.get('port',502)}\t{x.get('unit-id')}\t{addr}\t{r.get('count',2)}")
PY
    while IFS=$'\t' read -r id host port unit addr count; do
      out=$(timeout 5 mbpoll -1 -m tcp -p "$port" -a "$unit" -r "$((addr+1))" -c "$count" "$host" 2>&1)
      if echo "$out" | grep -qE '^\[' ; then
        ok "device $id 寄存器 $addr 读到值"
        echo "$out" | grep -E '^\[' | head -n 1 | sed 's/^/       /'
      else
        warn "device $id 读寄存器失败：$(echo "$out" | tail -n 1)"
      fi
    done < /tmp/collector-preflight-mbpoll.$$
    rm -f /tmp/collector-preflight-mbpoll.$$
  fi
fi

# ─── 汇总 ──────────────────────────────────────────────────────────────
echo
echo "════════════════════════════════════════════════════════════════"
echo "  总计: ${C_OK}OK ${PASS}${C_RESET} / ${C_WARN}WARN ${WARN}${C_RESET} / ${C_FAIL}FAIL ${FAIL}${C_RESET}"
echo "════════════════════════════════════════════════════════════════"

if (( FAIL > 0 )); then
  echo "${C_FAIL}阻断项存在 — 修完再继续装机。${C_RESET}"
  exit 2
elif (( WARN > 0 )); then
  echo "${C_WARN}有警告 — 评估后可继续。${C_RESET}"
  exit 1
fi
echo "${C_OK}全部 PASS — 可以 docker compose up -d。${C_RESET}"
exit 0
