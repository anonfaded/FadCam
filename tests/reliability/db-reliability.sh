#!/usr/bin/env bash
set -euo pipefail
COMPOSE=(docker compose -f deployment/docker-compose.e2e.yml)
trap '"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true' EXIT

"${COMPOSE[@]}" up -d --build postgres core gateway
for i in {1..60}; do
  if curl -fsS http://localhost:8080/api/v1/health >/dev/null && curl -fsS http://localhost:8081/health >/dev/null; then break; fi
  [[ $i == 60 ]] && { echo 'FAIL: stack did not become ready'; exit 1; }
  sleep 1
done

echo 'PASS: database available at startup'

# Database outage: the API must fail closed with a dependency error, not claim readiness.
"${COMPOSE[@]}" stop postgres >/dev/null
sleep 2
status=$(curl --max-time 5 -sS -o /tmp/core-db-down.json -w '%{http_code}' http://localhost:8080/api/v1/health || true)
if [[ "$status" != 503 ]]; then
  echo "FAIL: expected Core health 503 while PostgreSQL is unavailable, got $status"
  cat /tmp/core-db-down.json 2>/dev/null || true
  exit 1
fi
echo 'PASS: database outage produces controlled 503'

# Recovery: restart PostgreSQL without restarting Core; pg Pool must recover automatically.
"${COMPOSE[@]}" start postgres >/dev/null
for i in {1..60}; do
  if curl -fsS http://localhost:8080/api/v1/health >/dev/null; then break; fi
  [[ $i == 60 ]] && { echo 'FAIL: Core did not recover after PostgreSQL restart'; exit 1; }
  sleep 1
done
echo 'PASS: Core recovered after PostgreSQL restart'

# Concurrent migration execution: two independent runners must serialize on the advisory lock.
set +e
"${COMPOSE[@]}" run --rm -T core node src/migrate.mjs >/tmp/migration-a.log 2>&1 & a=$!
"${COMPOSE[@]}" run --rm -T core node src/migrate.mjs >/tmp/migration-b.log 2>&1 & b=$!
wait "$a"; ra=$?
wait "$b"; rb=$?
set -e
if [[ $ra != 0 || $rb != 0 ]]; then
  echo 'FAIL: concurrent migration runners disagreed'
  cat /tmp/migration-a.log /tmp/migration-b.log
  exit 1
fi
echo 'PASS: concurrent migration runners serialize safely'

# Failed migration must roll back and must not enter the ledger.
"${COMPOSE[@]}" exec -T core sh -c "mkdir -p /tmp/failing-migrations; printf '%s\\n' 'CREATE TABLE reliability_should_rollback(id integer);' 'SELECT 1/0;' > /tmp/failing-migrations/999_reliability_failure.sql"
set +e
"${COMPOSE[@]}" exec -T core node --input-type=module -e "import { migrate } from './src/migrate.mjs'; migrate(process.env.DATABASE_URL, '/tmp/failing-migrations').then(()=>process.exit(1)).catch(()=>process.exit(0))"
result=$?
set -e
if [[ $result != 0 ]]; then echo 'FAIL: migration failure test harness failed'; exit 1; fi
if "${COMPOSE[@]}" exec -T postgres psql -U fad -d fad -tAc "SELECT count(*) FROM schema_migrations WHERE version='999'" | grep -qx '0'; then
  echo 'PASS: failed migration rolled back and was not recorded'
else
  echo 'FAIL: failed migration entered schema_migrations'
  exit 1
fi
if "${COMPOSE[@]}" exec -T postgres psql -U fad -d fad -tAc "SELECT to_regclass('public.reliability_should_rollback') IS NULL" | grep -qx 't'; then
  echo 'PASS: failed migration left no partial schema'
else
  echo 'FAIL: failed migration left partial schema'
  exit 1
fi
