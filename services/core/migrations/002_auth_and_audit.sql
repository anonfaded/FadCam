BEGIN;

ALTER TABLE cameras
  ADD CONSTRAINT cameras_device_key_format CHECK (device_key ~ '^[A-Za-z0-9_-]{8,128}$');

CREATE TABLE IF NOT EXISTS auth_tokens (
  id UUID PRIMARY KEY,
  camera_id UUID NOT NULL REFERENCES cameras(id) ON DELETE CASCADE,
  token_hash TEXT NOT NULL UNIQUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_used_at TIMESTAMPTZ,
  expires_at TIMESTAMPTZ,
  revoked_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS auth_tokens_camera_idx ON auth_tokens(camera_id);
CREATE INDEX IF NOT EXISTS auth_tokens_active_idx ON auth_tokens(token_hash) WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS audit_events (
  id BIGSERIAL PRIMARY KEY,
  organization_id UUID REFERENCES organizations(id) ON DELETE SET NULL,
  user_id UUID REFERENCES users(id) ON DELETE SET NULL,
  camera_id UUID REFERENCES cameras(id) ON DELETE SET NULL,
  action TEXT NOT NULL,
  resource_type TEXT,
  resource_id TEXT,
  request_id TEXT,
  ip_address INET,
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS audit_events_created_idx ON audit_events(created_at DESC);
CREATE INDEX IF NOT EXISTS audit_events_org_idx ON audit_events(organization_id, created_at DESC);
CREATE INDEX IF NOT EXISTS audit_events_camera_idx ON audit_events(camera_id, created_at DESC);

COMMIT;
