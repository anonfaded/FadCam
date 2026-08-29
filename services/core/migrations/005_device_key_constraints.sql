BEGIN;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conrelid = 'cameras'::regclass
      AND conname = 'cameras_device_key_format'
  ) THEN
    ALTER TABLE cameras
      ADD CONSTRAINT cameras_device_key_format
      CHECK (device_key ~ '^[A-Za-z0-9_-]{8,128}$');
  END IF;
END $$;

COMMIT;
