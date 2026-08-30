#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

: "${PUBLIC_HOSTNAME:=stream.example.com}"
: "${SERVER_ROOM_URL:=http://192.168.1.100:8080}"

tmp_env="$(mktemp)"
trap 'rm -f "$tmp_env"' EXIT
cp server/.env.example "$tmp_env"
printf '\nPUBLIC_HOSTNAME=%s\nSERVER_ROOM_URL=%s\n' "$PUBLIC_HOSTNAME" "$SERVER_ROOM_URL" >> "$tmp_env"

docker compose --env-file "$tmp_env" -f server/docker-compose.yml --profile public config --quiet

docker run --rm \
  -e PUBLIC_HOSTNAME="$PUBLIC_HOSTNAME" \
  -e SERVER_ROOM_URL="$SERVER_ROOM_URL" \
  -v "$ROOT/server/public-gateway/Caddyfile:/etc/caddy/Caddyfile:ro" \
  caddy:2.10.2-alpine \
  caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile

echo "Server Room public gateway configuration is valid."
echo "A live external-network playback test still requires a reachable Server Room endpoint."
