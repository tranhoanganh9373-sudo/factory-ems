#!/usr/bin/env bash
# Factory EMS — 前端热更新脚本（L-001 永久修复）
#
# 背景：docker-compose 的 frontend-builder 只在 compose-up 时跑一次，把构建产物
# 写进命名卷 frontend_dist，nginx 只读挂载之。git push 后这两个容器都不会重启，
# 卷内 bundle 仍是旧版本，必须手动重建。本脚本把"重建 + 注入 + nginx reload"
# 流程固化为一条命令，匹配 backup.sh / restore.sh 的写法风格。
#
# docker-compose.yml 不动，frontend-builder 仍是首装的 bootstrap 路径；
# 本脚本只解决"已经跑起来后怎么热更新"。
#
# 用法:
#   /opt/factory-ems/scripts/redeploy-frontend.sh
#   /opt/factory-ems/scripts/redeploy-frontend.sh --no-install   # 复用现有 node_modules
#   /opt/factory-ems/scripts/redeploy-frontend.sh --help
#
# 环境变量（可选）:
#   EMS_HOME         项目根，默认 /opt/factory-ems
#   NGINX_CONTAINER  nginx 容器名，默认 factory-ems-nginx-1
#   FRONTEND_VOLUME  前端卷名，默认 factory-ems_frontend_dist
#
# 退出码：
#   0 成功 / 1 入参或前置失败 / 2 npm install 失败 / 3 npm build 失败
#   4 卷注入失败 / 5 nginx reload 失败

set -euo pipefail

EMS_HOME="${EMS_HOME:-/opt/factory-ems}"
NGINX_CONTAINER="${NGINX_CONTAINER:-factory-ems-nginx-1}"
FRONTEND_VOLUME="${FRONTEND_VOLUME:-factory-ems_frontend_dist}"

SKIP_INSTALL=0

usage() {
  cat <<EOF
Usage: $0 [--no-install] [--help]

Options:
  --no-install   跳过 npm ci（复用现有 node_modules，快速重跑用）
  --help, -h     打印本帮助

Env (optional):
  EMS_HOME         项目根，默认 /opt/factory-ems
  NGINX_CONTAINER  nginx 容器名，默认 factory-ems-nginx-1
  FRONTEND_VOLUME  前端卷名，默认 factory-ems_frontend_dist
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-install) SKIP_INSTALL=1; shift ;;
    --help|-h) usage; exit 0 ;;
    *) echo "ERR: unknown arg: $1" >&2; usage >&2; exit 1 ;;
  esac
done

# ---------- [1/4] 前置检查 ----------

echo "[1/4] pre-checks"

[[ -d "$EMS_HOME" ]] || { echo "ERR: EMS_HOME not found: $EMS_HOME" >&2; exit 1; }
FRONTEND_DIR="$EMS_HOME/frontend"
[[ -d "$FRONTEND_DIR" ]] || { echo "ERR: frontend dir not found: $FRONTEND_DIR" >&2; exit 1; }
[[ -f "$FRONTEND_DIR/package.json" ]] || {
  echo "ERR: $FRONTEND_DIR/package.json not found" >&2; exit 1; }

command -v docker >/dev/null || { echo "ERR: docker required" >&2; exit 1; }
command -v npm    >/dev/null || { echo "ERR: npm required" >&2; exit 1; }

# 防御：卷名必须真实存在，否则 alpine 一锅端可能误伤其他卷
if ! docker volume inspect "$FRONTEND_VOLUME" >/dev/null 2>&1; then
  echo "ERR: docker volume not found: $FRONTEND_VOLUME" >&2
  echo "    （首次部署请先 \`docker compose up -d\` 让 frontend-builder 创建卷）" >&2
  exit 1
fi

# 防御：nginx 容器必须在跑，reload 才有意义
if ! docker ps --format '{{.Names}}' | grep -qx "$NGINX_CONTAINER"; then
  echo "ERR: nginx container not running: $NGINX_CONTAINER" >&2
  exit 1
fi

cd "$FRONTEND_DIR"

# ---------- [2/4] 装依赖（可跳过）----------

if [[ $SKIP_INSTALL -eq 1 ]]; then
  echo "[2/4] npm ci  (skipped via --no-install)"
  [[ -d node_modules ]] || {
    echo "ERR: --no-install 但 node_modules 不存在；请先全量跑一次" >&2; exit 2; }
else
  echo "[2/4] npm ci"
  if ! npm ci; then
    echo "ERR: npm ci failed" >&2
    exit 2
  fi
fi

# ---------- [3/4] 构建 ----------

echo "[3/4] npm run build"
if ! npm run build; then
  echo "ERR: npm run build failed" >&2
  exit 3
fi
[[ -f dist/index.html ]] || { echo "ERR: dist/index.html not produced" >&2; exit 3; }

# ---------- [4/4] 注入卷 + nginx reload ----------

echo "[4/4] inject dist → volume($FRONTEND_VOLUME) + nginx reload"

# 用 alpine 一次性容器把 dist 同步进卷；先清旧文件再 cp，避免残留
# rm -rf /share/* 只动指定卷的 mountpoint，不会越界
if ! docker run --rm \
       -v "$FRONTEND_VOLUME:/share" \
       -v "$FRONTEND_DIR/dist:/src:ro" \
       alpine sh -c 'rm -rf /share/* /share/.[!.]* /share/..?* 2>/dev/null; cp -r /src/. /share/'; then
  echo "ERR: failed to inject dist into volume" >&2
  exit 4
fi

if ! docker exec "$NGINX_CONTAINER" nginx -s reload; then
  echo "ERR: nginx reload failed" >&2
  exit 5
fi

# ---------- 汇总 ----------

BUNDLE_SIZE=$(du -sh dist | awk '{print $1}')
INDEX_SHA=$(shasum -a 256 dist/index.html | awk '{print $1}' | cut -c1-12)
GIT_SHA=$(git -C "$EMS_HOME" rev-parse --short HEAD 2>/dev/null || echo "unknown")

echo
echo "[$(date -Iseconds)] redeploy-frontend done"
echo "  bundle size       : $BUNDLE_SIZE"
echo "  dist/index.html   : sha256:$INDEX_SHA"
echo "  git HEAD          : $GIT_SHA"
echo "  nginx container   : $NGINX_CONTAINER (reloaded)"
echo "  frontend volume   : $FRONTEND_VOLUME"
