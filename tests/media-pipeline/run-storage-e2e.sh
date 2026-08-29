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
CORE_URL=http://localhost:8080
BOOTSTRAP_SECRET=e2e-device-bootstrap-secret-0123456789
MINIO_HOST=http://fad-e2e:fad-e2e-password@minio:9000
CAMERA_ID=11111111-1111-1111-1111-111111111111
ORG_ID=22222222-2222-2222-2222-222222222222
DEVICE_KEY=e2e-device-001
mc() { "${COMPOSE[@]}" run --rm -T -e "MC_HOST_e2e=${MINIO_HOST}" --entrypoint mc minio-uploader "$@"; }
collect_logs() { echo '--- container status ---'; "${COMPOSE[@]}" ps -a || true; echo '--- Core logs ---'; "${COMPOSE[@]}" logs --no-color core || true; echo '--- Gateway logs ---'; "${COMPOSE[@]}" logs --no-color gateway || true; echo '--- MediaMTX logs ---'; "${COMPOSE[@]}" logs --no-color mediamtx || true; echo '--- FFmpeg publisher logs ---'; "${COMPOSE[@]}" logs --no-color ffmpeg-publisher || true; echo '--- MinIO logs ---'; "${COMPOSE[@]}" logs --no-color minio || true; }
cleanup() { if [[ ${KEEP_E2E_STACK:-0} != 1 && ${E2E_CI:-0} != 1 ]]; then "${COMPOSE[@]}" down -v --remove-orphans || true; fi; }
trap cleanup EXIT
"${COMPOSE[@]}" up -d --build mediamtx gateway minio
ready=0
for i in {1..60}; do if curl -fsS "${GATEWAY_URL}/health" >/dev/null && curl -fsS "${CORE_URL}/api/v1/health" >/dev/null && curl -fsS -u "${API_USER}:${API_PASSWORD}" "${API_URL}/v3/paths/list" >/dev/null && curl -fsS http://localhost:9000/minio/health/live >/dev/null && mc ls e2e >/dev/null 2>&1; then ready=1; break; fi; sleep 1; done
if [[ $ready != 1 ]]; then echo 'FAIL: media stack did not become ready'; collect_logs; exit 1; fi
"${COMPOSE[@]}" exec -T postgres psql -U fad -d fad -v ON_ERROR_STOP=1 <<SQL
INSERT INTO organizations(id,name) VALUES ('${ORG_ID}','E2E Organization') ON CONFLICT (id) DO NOTHING;
INSERT INTO cameras(id,organization_id,name,device_key,status) VALUES ('${CAMERA_ID}','${ORG_ID}','E2E Camera','${DEVICE_KEY}','offline') ON CONFLICT (id) DO UPDATE SET organization_id=EXCLUDED.organization_id,device_key=EXCLUDED.device_key,revoked_at=NULL,status='offline';
SQL
TOKEN_RESPONSE=$(curl -fsS -X POST "${CORE_URL}/api/v1/auth/device/token" -H "x-bootstrap-secret: ${BOOTSTRAP_SECRET}" -H 'content-type: application/json' -d "{\"deviceKey\":\"${DEVICE_KEY}\",\"name\":\"E2E Camera\"}")
DEVICE_TOKEN=$(node -e 'const j=JSON.parse(process.argv[1]); if(!j.token) process.exit(1); process.stdout.write(j.token)' "$TOKEN_RESPONSE")
export DEVICE_TOKEN GATEWAY_URL
if curl -sS -o /dev/null -w '%{http_code}' "${GATEWAY_URL}/api/v1/registry/e2e-registry" -H 'Authorization: Bearer invalid-token' | grep -qx '401'; then echo 'PASS: invalid token rejected'; else echo 'FAIL: invalid token accepted'; exit 1; fi
EXPIRED=$(curl -fsS -X POST "${CORE_URL}/api/v1/auth/device/token" -H "x-bootstrap-secret: ${BOOTSTRAP_SECRET}" -H 'content-type: application/json' -d "{\"deviceKey\":\"${DEVICE_KEY}\",\"name\":\"E2E Camera\",\"expiresAt\":\"2000-01-01T00:00:00Z\"}")
EXPIRED_TOKEN=$(node -e 'const j=JSON.parse(process.argv[1]); process.stdout.write(j.token)' "$EXPIRED")
if curl -sS -o /dev/null -w '%{http_code}' "${GATEWAY_URL}/api/v1/registry/e2e-registry" -H "Authorization: Bearer ${EXPIRED_TOKEN}" | grep -qx '401'; then echo 'PASS: expired token rejected'; else echo 'FAIL: expired token accepted'; exit 1; fi
REGISTER=$(curl -fsS -X POST "${GATEWAY_URL}/api/v1/streams" -H "Authorization: Bearer ${DEVICE_TOKEN}" -H 'content-type: application/json' -d '{"id":"e2e-registry","name":"e2e-test"}')
node -e 'const j=JSON.parse(process.argv[1]); if(j.id!=="e2e-registry"||j.cameraId!==process.argv[2]) process.exit(1)' "$REGISTER" "$CAMERA_ID"
if curl -sS -o /dev/null -w '%{http_code}' -X POST "${GATEWAY_URL}/api/v1/streams" -H "Authorization: Bearer ${DEVICE_TOKEN}" -H 'content-type: application/json' -d '{"id":"e2e-registry-2","name":"e2e-test"}' | grep -qx '409'; then echo 'PASS: duplicate stream conflict'; else echo 'FAIL: duplicate stream conflict contract'; exit 1; fi
CAMERA2_ID=33333333-3333-3333-3333-333333333333
DEVICE2_KEY=e2e-device-002
"${COMPOSE[@]}" exec -T postgres psql -U fad -d fad -v ON_ERROR_STOP=1 -c "INSERT INTO cameras(id,organization_id,name,device_key,status) VALUES ('${CAMERA2_ID}','${ORG_ID}','E2E Camera 2','${DEVICE2_KEY}','offline') ON CONFLICT (id) DO UPDATE SET revoked_at=NULL,status='offline';"
TOKEN2=$(curl -fsS -X POST "${CORE_URL}/api/v1/auth/device/token" -H "x-bootstrap-secret: ${BOOTSTRAP_SECRET}" -H 'content-type: application/json' -d "{\"deviceKey\":\"${DEVICE2_KEY}\",\"name\":\"E2E Camera 2\"}" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>process.stdout.write(JSON.parse(s).token))')
if curl -sS -o /dev/null -w '%{http_code}' "${GATEWAY_URL}/api/v1/registry/e2e-registry" -H "Authorization: Bearer ${TOKEN2}" | grep -qx '404'; then echo 'PASS: cross-camera ownership isolation'; else echo 'FAIL: cross-camera access'; exit 1; fi
"${COMPOSE[@]}" exec -T postgres psql -U fad -d fad -v ON_ERROR_STOP=1 -c "DELETE FROM camera_permissions WHERE camera_id='${CAMERA2_ID}';"
if curl -sS -o /dev/null -w '%{http_code}' "${GATEWAY_URL}/api/v1/registry/e2e-registry" -H "Authorization: Bearer ${TOKEN2}" | grep -qx '403'; then echo 'PASS: permission denial'; else echo 'FAIL: permission denial'; exit 1; fi
CAMERA3_ID=44444444-4444-4444-4444-444444444444
DEVICE3_KEY=e2e-device-003
"${COMPOSE[@]}" exec -T postgres psql -U fad -d fad -v ON_ERROR_STOP=1 -c "INSERT INTO cameras(id,organization_id,name,device_key,status) VALUES ('${CAMERA3_ID}','${ORG_ID}','E2E Camera 3','${DEVICE3_KEY}','offline') ON CONFLICT (id) DO UPDATE SET revoked_at=NULL,status='offline';"
TOKEN3=$(curl -fsS -X POST "${CORE_URL}/api/v1/auth/device/token" -H "x-bootstrap-secret: ${BOOTSTRAP_SECRET}" -H 'content-type: application/json' -d "{\"deviceKey\":\"${DEVICE3_KEY}\",\"name\":\"E2E Camera 3\"}" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>process.stdout.write(JSON.parse(s).token))')
curl -fsS -X POST "${CORE_URL}/api/v1/auth/device/revoke" -H "Authorization: Bearer ${TOKEN3}" >/dev/null
if curl -sS -o /dev/null -w '%{http_code}' "${GATEWAY_URL}/api/v1/registry/e2e-registry" -H "Authorization: Bearer ${TOKEN3}" | grep -qx '401'; then echo 'PASS: revoked token rejected'; else echo 'FAIL: revoked token accepted'; exit 1; fi
ORG4_ID=55555555-5555-5555-5555-555555555555
CAMERA4_ID=66666666-6666-6666-6666-666666666666
DEVICE4_KEY=e2e-device-004
"${COMPOSE[@]}" exec -T postgres psql -U fad -d fad -v ON_ERROR_STOP=1 <<SQL
INSERT INTO organizations(id,name) VALUES ('${ORG4_ID}','Other Organization') ON CONFLICT (id) DO NOTHING;
INSERT INTO cameras(id,organization_id,name,device_key,status) VALUES ('${CAMERA4_ID}','${ORG4_ID}','Other Camera','${DEVICE4_KEY}','offline') ON CONFLICT (id) DO UPDATE SET organization_id=EXCLUDED.organization_id,revoked_at=NULL,status='offline';
SQL
TOKEN4=$(curl -fsS -X POST "${CORE_URL}/api/v1/auth/device/token" -H "x-bootstrap-secret: ${BOOTSTRAP_SECRET}" -H 'content-type: application/json' -d "{\"deviceKey\":\"${DEVICE4_KEY}\",\"name\":\"Other Camera\"}" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>process.stdout.write(JSON.parse(s).token))')
if curl -sS -o /dev/null -w '%{http_code}' "${GATEWAY_URL}/api/v1/registry/e2e-registry" -H "Authorization: Bearer ${TOKEN4}" | grep -qx '404'; then echo 'PASS: organization isolation'; else echo 'FAIL: cross-organization access'; exit 1; fi
"${COMPOSE[@]}" up -d ffmpeg-publisher
stream_ready=0
for i in {1..45}; do if curl -fsS -u "${API_USER}:${API_PASSWORD}" "${API_URL}/v3/paths/list" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const j=JSON.parse(s);process.exit(j.items?.some(x=>x?.name==="e2e-test")?0:1)})'; then stream_ready=1; break; fi; sleep 1; done
if [[ $stream_ready != 1 ]]; then echo "FAIL: MediaMTX did not expose '${STREAM}' after publisher startup"; collect_logs; exit 1; fi
node tests/media-pipeline/test.mjs
REC_PATH=''
for i in {1..120}; do REC_PATH=$("${COMPOSE[@]}" run --rm -T recording-inspector 2>/dev/null | awk -v stream="$STREAM" '$0 ~ ("/recordings/" stream "/") && $0 ~ /\.mp4$/ {print; exit}' | tr -d '\r' | head -n 1 || true); if [[ -n "$REC_PATH" ]]; then break; fi; sleep 1; done
if [[ -z "$REC_PATH" ]]; then echo 'FAIL: MediaMTX did not finalize an fMP4 recording'; collect_logs; exit 1; fi
echo "Recorded source: $REC_PATH"
"${COMPOSE[@]}" stop ffmpeg-publisher
"${COMPOSE[@]}" run --rm -T --entrypoint ffmpeg ffmpeg-publisher -y -i "$REC_PATH" -c:v libx264 -preset ultrafast -c:a aac /recordings/processed.mp4
mc mb --ignore-existing "e2e/${BUCKET}"
mc cp /recordings/processed.mp4 "e2e/${BUCKET}/${OBJECT}"
STATS=$(mc stat --json "e2e/${BUCKET}/${OBJECT}")
echo "$STATS"
node -e 'const s=JSON.parse(process.argv[1]); if (!s.size || s.size <= 0) process.exit(1); console.log(`PASS: MinIO object size=${s.size}`)' "$STATS"
# Fail-closed migration check: changing an applied migration checksum must prevent Core startup.
"${COMPOSE[@]}" exec -T core sh -c "printf '\\n-- tamper-test\\n' >> /app/db/002_auth_and_audit.sql"
"${COMPOSE[@]}" restart core >/dev/null
sleep 3
if curl --max-time 5 -sS "${CORE_URL}/api/v1/health" >/dev/null 2>&1; then echo 'FAIL: Core started after migration checksum tampering'; exit 1; else echo 'PASS: migration checksum tampering fails closed'; fi
echo 'PASS: persistent auth -> authorization -> ownership -> Gateway -> MediaMTX -> recording -> FFmpeg -> MinIO'
