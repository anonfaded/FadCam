#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

tmp_env="$(mktemp)"
trap 'rm -f "$tmp_env"' EXIT
cp server/.env.example "$tmp_env"
printf '\nPUBLIC_HOSTNAME=:80\n' >> "$tmp_env"

docker compose --env-file "$tmp_env" -f server/docker-compose.yml --profile public config --quiet

docker build --quiet -t fadcam-server-room-discovery-test server/public-gateway >/dev/null

docker run --rm fadcam-server-room-discovery-test python -m py_compile /app/discovery.py

docker run --rm \
  -e PUBLIC_HOSTNAME=:80 \
  -v "$ROOT/server/public-gateway/Caddyfile:/etc/caddy/Caddyfile:ro" \
  caddy:2.10.2-alpine \
  caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile

echo "Server Room automatic-discovery gateway configuration is valid."
echo "The runtime smoke test must run on a host sharing the Server Room LAN."
