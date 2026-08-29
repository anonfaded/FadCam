import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import pg from 'pg'

const { Client } = pg
const MIGRATION_LOCK_KEY = 874321

export async function migrate(connectionString, directory = path.resolve(process.cwd(), 'db')) {
  const client = new Client({ connectionString })
  await client.connect()
  let locked = false
  try {
    await client.query('SELECT pg_advisory_lock($1)', [MIGRATION_LOCK_KEY])
    locked = true
    await client.query(`CREATE TABLE IF NOT EXISTS schema_migrations (
      version TEXT PRIMARY KEY,
      checksum TEXT NOT NULL,
      applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
    )`)

    const files = (await fs.readdir(directory))
      .filter(name => /^\d+_.+\.sql$/.test(name))
      .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }))

    const versions = new Set()
    for (const file of files) {
      const version = file.split('_', 1)[0]
      if (versions.has(version)) throw new Error(`duplicate migration version ${version}`)
      versions.add(version)
    }

    for (const file of files) {
      const version = file.split('_', 1)[0]
      const sql = await fs.readFile(path.join(directory, file), 'utf8')
      const checksum = crypto.createHash('sha256').update(sql).digest('hex')
      const existing = await client.query('SELECT checksum FROM schema_migrations WHERE version=$1', [version])
      if (existing.rowCount) {
        if (existing.rows[0].checksum !== checksum) throw new Error(`migration ${file} checksum changed`)
        continue
      }
      await client.query('BEGIN')
      try {
        await client.query(sql)
        await client.query('INSERT INTO schema_migrations(version, checksum) VALUES ($1,$2)', [version, checksum])
        await client.query('COMMIT')
        console.log(`Applied migration ${file}`)
      } catch (error) {
        await client.query('ROLLBACK')
        throw new Error(`migration ${file} failed: ${error.message}`, { cause: error })
      }
    }
  } finally {
    if (locked) {
      try { await client.query('SELECT pg_advisory_unlock($1)', [MIGRATION_LOCK_KEY]) } catch {}
    }
    await client.end()
  }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  if (!process.env.DATABASE_URL) throw new Error('DATABASE_URL is required')
  migrate(process.env.DATABASE_URL).catch(error => { console.error(error); process.exit(1) })
}
