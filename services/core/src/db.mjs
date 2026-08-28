import pg from 'pg'

const { Pool } = pg
export const pool = new Pool({ connectionString: process.env.DATABASE_URL })

export async function migrate() {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS organizations (id UUID PRIMARY KEY, name TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
    CREATE TABLE IF NOT EXISTS users (id UUID PRIMARY KEY, organization_id UUID REFERENCES organizations(id), email TEXT UNIQUE NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
    CREATE TABLE IF NOT EXISTS channels (id UUID PRIMARY KEY, organization_id UUID REFERENCES organizations(id), name TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
    CREATE TABLE IF NOT EXISTS cameras (id UUID PRIMARY KEY, organization_id UUID REFERENCES organizations(id), name TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
    CREATE TABLE IF NOT EXISTS streams (id UUID PRIMARY KEY, channel_id UUID REFERENCES channels(id), camera_id UUID REFERENCES cameras(id), name TEXT UNIQUE NOT NULL, source TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'registered', created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
    CREATE TABLE IF NOT EXISTS media (id UUID PRIMARY KEY, stream_id UUID REFERENCES streams(id), object_key TEXT NOT NULL, mime_type TEXT, size_bytes BIGINT, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
    CREATE TABLE IF NOT EXISTS productions (id UUID PRIMARY KEY, organization_id UUID REFERENCES organizations(id), name TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'draft', created_at TIMESTAMPTZ NOT NULL DEFAULT now());
    CREATE TABLE IF NOT EXISTS permissions (user_id UUID REFERENCES users(id), resource_type TEXT NOT NULL, resource_id UUID NOT NULL, action TEXT NOT NULL, PRIMARY KEY(user_id, resource_type, resource_id, action));
  `)
}
