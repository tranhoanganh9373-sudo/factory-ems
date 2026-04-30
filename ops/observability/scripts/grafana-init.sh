#!/usr/bin/env bash
# Generate strong random Grafana admin password and write to .env.obs.
# Prints the generated password ONCE; afterwards it lives only in .env.obs.

set -euo pipefail

cd "$(dirname "$0")/.."

if [ ! -f .env.obs ]; then
    if [ -f .env.obs.example ]; then
        cp .env.obs.example .env.obs
        echo "Created .env.obs from .env.obs.example"
    else
        echo "ERROR: .env.obs.example not found." >&2
        exit 1
    fi
fi

# Generate 24-char base64 (URL-safe-ish) password
PASS="$(openssl rand -base64 24 | tr -d '/+=' | head -c 24)"

if grep -q '^OBS_GRAFANA_ADMIN_PASSWORD=' .env.obs; then
    # macOS BSD sed needs '' after -i; Linux GNU sed doesn't. Detect.
    if [[ "$(uname)" == "Darwin" ]]; then
        sed -i '' "s|^OBS_GRAFANA_ADMIN_PASSWORD=.*|OBS_GRAFANA_ADMIN_PASSWORD=$PASS|" .env.obs
    else
        sed -i "s|^OBS_GRAFANA_ADMIN_PASSWORD=.*|OBS_GRAFANA_ADMIN_PASSWORD=$PASS|" .env.obs
    fi
else
    echo "OBS_GRAFANA_ADMIN_PASSWORD=$PASS" >> .env.obs
fi

cat <<EOF
====================================================
Grafana admin password generated and saved to .env.obs
Password: $PASS
Save it now — this is the only time it is printed.
====================================================
EOF
