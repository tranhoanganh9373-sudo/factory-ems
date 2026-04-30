#!/usr/bin/env bash
# Stop observability stack. Pass -v to remove volumes (data wipe).

cd "$(dirname "$0")/.."

if [ ! -f .env.obs ]; then
    echo "ERROR: .env.obs not found." >&2
    exit 1
fi

docker compose --env-file .env.obs -f docker-compose.obs.yml down "$@"
