BEGIN;

CREATE TABLE IF NOT EXISTS camera_permissions (
  id UUID PRIMARY KEY,
  camera_id UUID NOT NULL REFERENCES cameras(id) ON DELETE CASCADE,
  resource_type TEXT NOT NULL,
  resource_id TEXT NOT NULL,
  action TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(camera_id, resource_type, resource_id, action)
);

CREATE INDEX IF NOT EXISTS camera_permissions_camera_idx ON camera_permissions(camera_id);
CREATE INDEX IF NOT EXISTS camera_permissions_resource_idx ON camera_permissions(resource_type, resource_id, action);

COMMIT;
