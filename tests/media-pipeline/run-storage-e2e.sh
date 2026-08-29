#!/usr/bin/env bash
set -euo pipefail

COMPOSE=(docker compose -f deployment/docker-compose.e2e.yml)
STREAM=e2e-test
BUCKET=fad-e2e
OBJECT=recordings/${STREAM}/processed.mp4
API_USER=api-e2e
API_PASSWORD=api-e2e-pass
API_URL=http://localhost:9997
GATEWAY_URL=http://localhost:8081
BOOTSTRAP_SECRET=e2e-device-bootstrap-secret-0123456789
MINIO_HOST=http://fad-e2e:fad-e2e-password@minio:9000
CAMERA_ID=11111111-1111-1111-1111-111111111111
ORG_ID=22222222-2222-2222-2222-222222222222
DEVICE_KEY=e2e-device-001

mc() {
  "${COMPOSE[@]}" run --rm -T -e "MC_HOST_e2e=${MINIO_HOST}" --entrypoint mc minio-uploader "$@"
}

collect_logs() {
  echo '--- container status ---'; "${COMPOSE[@]}" ps -a || true
  echo '--- Core logs ---'; "${COMPOSE[@]}" logs --no-color core || true
  echo '--- Gateway logs ---'; "${COMPOSE[@]}" logs --no-color gateway || true
  echo '--- MediaMTX logs ---'; "${COMPOSE[@]}" logs --no-color mediamtx || true
  echo '--- FFmpeg publisher logs ---'; "${COMPOSE[@]}" logs --no-color ffmpeg-publisher || true
  echo '--- MinIO logs ---'; "${COMPOSE[@]}" logs --no-color minio || true
}

cleanup() {
  if [[ ${KEEP_E2E_STACK:-0} != 1 && ${E2E_CI:-0} != 1 ]]; then "${COMPOSE[@]}" down -v --remove-orphans || true; fi
}
trap cleanup EXIT

"${COMPOSE[@]}" up -d --build mediamtx gateway minio

ready=0
for i in {1..60}; do
  if curl -fsS http://localhost:8081/health >/dev/null \
    && curl -fsS http://localhost:8080/api/v1/health >/dev/null \
    && curl -fsS -u "${API_USER}:${API_PASSWORD}" "${API_URL}/v3/paths/list" >/dev/null \
    && curl -fsS http://localhost:9000/minio/health/live >/dev/null \
    && mc ls e2e >/dev/null 2>&1; then ready=1; break; fi
  sleep 1
done

if [[ $ready != 1 ]]; then
  echo 'FAIL: media stack did not become ready'; collect_logs; exit 1
fi

"${COMPOSE[@]}" exec -T postgres psql -U fad -d fad -v ON_ERROR_STOP=1 <<SQL
INSERT INTO organizations(id,name) VALUES ('${ORG_ID}','E2E Organization') ON CONFLICT (id) DO NOTHING;
INSERT INTO cameras(id,organization_id,name,device_key,status) VALUES ('${CAMERA_ID}','${ORG_ID}','E2E Camera','${DEVICE_KEY}','offline') ON CONFLICT (id) DO UPDATE SET organization_id=EXCLUDED.organization_id, device_key=EXCLUDED.device_key, revoked_at=NULL, status='offline';
SQL

TOKEN_RESPONSE=$(curl -fsS -X POST "http://localhost:8080/api/v1/auth/device/token" -H "x-bootstrap-secret: ${BOOTSTRAP_SECRET}" -H 'content-type: application/json' -d "{\"deviceKey\":\"${DEVICE_KEY}\",\"name\":\"E2E Camera\"}")
DEVICE_TOKEN=$(node -e 'const j=JSON.parse(process.argv[1]); if(!j.token) process.exit(1); process.stdout.write(j.token)' "$TOKEN_RESPONSE")
export DEVICE_TOKEN GATEWAY_URL

"${COMPOSE[@]}" up -d ffmpeg-publisher

stream_ready=0
for i in {1..45}; do
  if curl -fsS -u "${API_USER}:${API_PASSWORD}" "${API_URL}/v3/paths/list" | node -e 'let s=""; process.stdin.on("data",d=>s+=d).on("end",()=>{const j=JSON.parse(s); process.exit(j.items?.some(x=>x?.name==="e2e-test")?0:1)})'; then stream_ready=1; break; fi
  sleep 1
done

if [[ $stream_ready != 1 ]]; then echo "FAIL: MediaMTX did not expose '${STREAM}' after publisher startup"; collect_logs; exit 1; fi

node tests/media-pipeline/test.mjs

REC_PATH=''
for i in {1..120}; do
  REC_PATH=$("${COMPOSE[@]}" run --rm -T recording-inspector 2>/dev/null | awk -v stream="$STREAM" '$0 ~ ("/recordings/" stream "/") && $0 ~ /\.mp4$/ {print; exit}' | tr -d '\r' | head -n 1 || true)
  if [[ -n "$REC_PATH" ]]; then break; fi
  sleep 1
done

if [[ -z "$REC_PATH" ]]; then echo 'FAIL: MediaMTX did not finalize an fMP4 recording'; collect_logs; exit 1; fi

echo "Recorded source: $REC_PATH"
"${COMPOSE[@]}" stop ffmpeg-publisher
"${COMPOSE[@]}" run --rm -T --entrypoint ffmpeg ffmpeg-publisher -y -i "$REC_PATH" -c:v libx264 -preset ultrafast -c:a aac /recordings/processed.mp4
mc mb --ignore-existing "e2e/${BUCKET}"
mc cp /recordings/processed.mp4 "e2e/${BUCKET}/${OBJECT}"
STATS=$(mc stat --json "e2e/${BUCKET}/${OBJECT}")
echo "$STATS"
node -e 'const s=JSON.parse(process.argv[1]); if (!s.size || s.size <= 0) process.exit(1); console.log(`PASS: MinIO object size=${s.size}`)' "$STATS"
echo 'PASS: persistent device auth -> Gateway -> MediaMTX -> recording -> FFmpeg -> MinIO'
