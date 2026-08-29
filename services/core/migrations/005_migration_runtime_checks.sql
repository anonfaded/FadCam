-- Runtime marker for migration/recovery integration tests.
-- The migration runner itself serializes migrations with pg_advisory_lock.
BEGIN;
CREATE TABLE IF NOT EXISTS migration_runtime_checks (
  name TEXT PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO migration_runtime_checks(name)
VALUES ('migration-runner-v1')
ON CONFLICT (name) DO NOTHING;
COMMIT;
