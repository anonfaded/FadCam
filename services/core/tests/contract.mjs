import crypto from 'node:crypto'
import pg from 'pg'

const base = process.env.CORE_URL || 'http://127.0.0.1:8080'
const gateway = process.env.GATEWAY_URL || 'http://127.0.0.1:8081'
const db = new pg.Client({ connectionString: process.env.DATABASE_URL })
const bootstrap = process.env.AUTH_BOOTSTRAP_SECRET || 'e2e-device-bootstrap-secret-0123456789'

async function request(url, options = {}) {
  const response = await fetch(url, options)
  let body = null
  try { body = await response.json() } catch {}
  return { response, body }
}
function assert(condition, message) { if (!condition) throw new Error(message) }
function headers(token) { return { authorization: `Bearer ${token}`, 'content-type': 'application/json' } }

await db.connect()
const suffix = crypto.randomUUID().slice(0, 8)
const orgA = crypto.randomUUID(), orgB = crypto.randomUUID()
const camA = crypto.randomUUID(), camB = crypto.randomUUID(), camC = crypto.randomUUID()
const keyA = `contract-a-${suffix}`, keyB = `contract-b-${suffix}`, keyC = `contract-c-${suffix}`
const streamA = `contract-stream-${suffix}`
await db.query('INSERT INTO organizations(id,name) VALUES ($1,$2),($3,$4)', [orgA, 'Contract Org A', orgB, 'Contract Org B'])
await db.query('INSERT INTO cameras(id,organization_id,name,device_key,status) VALUES ($1,$2,$3,$4,$5),($6,$2,$7,$8,$5),($9,$3,$10,$11,$5)', [camA, orgA, 'Contract Camera A', keyA, 'offline', camB, 'Contract Camera B', keyB, camC, 'Contract Camera C', keyC])

try {
  let r = await request(`${base}/api/v1/health`)
  assert(r.response.status === 200 && r.body?.status === 'ok' && r.body?.database === 'ok', `health: ${r.response.status}`)

  r = await request(`${base}/api/v1/auth/device/token`, { method: 'POST', headers: { 'x-bootstrap-secret': bootstrap, 'content-type': 'application/json' }, body: JSON.stringify({ deviceKey: keyA, name: 'Contract Camera A' }) })
  assert(r.response.status === 201 && r.body?.token, `token issue: ${r.response.status}`)
  const tokenA = r.body.token

  r = await request(`${base}/api/v1/streams`, { method: 'POST', headers: headers(tokenA), body: JSON.stringify({ id: streamA, name: streamA }) })
  assert(r.response.status === 201, `own create: ${r.response.status}`)
  assert(r.response.headers.get('x-request-id'), 'missing request id')
  r = await request(`${base}/api/v1/streams/${streamA}`, { headers: headers(tokenA) })
  assert(r.response.status === 200 && r.body?.cameraId === camA, `own lookup: ${r.response.status}`)

  r = await request(`${base}/api/v1/streams`, { method: 'POST', headers: headers(tokenA), body: JSON.stringify({ id: `${streamA}-2`, name: streamA }) })
  assert(r.response.status === 409 && r.body?.error?.code === 'stream_name_conflict', `duplicate: ${r.response.status}`)

  r = await request(`${base}/api/v1/streams`, { method: 'POST', headers: headers(tokenA), body: JSON.stringify({ id: 'bad', name: '../escape' }) })
  assert(r.response.status === 400 && r.body?.error?.code === 'invalid_stream', `invalid resource: ${r.response.status}`)

  r = await request(`${base}/api/v1/streams`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ id: 'unauth', name: 'unauth' }) })
  assert(r.response.status === 401 && r.body?.error?.code === 'unauthorized', `missing auth: ${r.response.status}`)

  r = await request(`${base}/api/v1/streams/${streamA}`, { headers: headers('not-a-real-token-0123456789') })
  assert(r.response.status === 401, `invalid token: ${r.response.status}`)

  r = await request(`${base}/api/v1/auth/device/token`, { method: 'POST', headers: { 'x-bootstrap-secret': bootstrap, 'content-type': 'application/json' }, body: JSON.stringify({ deviceKey: keyB, name: 'Contract Camera B', expiresAt: '2000-01-01T00:00:00Z' }) })
  const expired = r.body.token
  r = await request(`${base}/api/v1/streams/${streamA}`, { headers: headers(expired) })
  assert(r.response.status === 401, `expired token: ${r.response.status}`)

  r = await request(`${base}/api/v1/auth/device/token`, { method: 'POST', headers: { 'x-bootstrap-secret': bootstrap, 'content-type': 'application/json' }, body: JSON.stringify({ deviceKey: keyB, name: 'Contract Camera B' }) })
  const tokenB = r.body.token
  r = await request(`${base}/api/v1/streams/${streamA}`, { headers: headers(tokenB) })
  assert(r.response.status === 404, `cross-camera isolation: ${r.response.status}`)
  await db.query('DELETE FROM camera_permissions WHERE camera_id=$1', [camB])
  r = await request(`${base}/api/v1/streams/${streamA}`, { headers: headers(tokenB) })
  assert(r.response.status === 403, `permission denial: ${r.response.status}`)

  r = await request(`${base}/api/v1/auth/device/token`, { method: 'POST', headers: { 'x-bootstrap-secret': bootstrap, 'content-type': 'application/json' }, body: JSON.stringify({ deviceKey: keyC, name: 'Contract Camera C' }) })
  const tokenC = r.body.token
  r = await request(`${base}/api/v1/streams/${streamA}`, { headers: headers(tokenC) })
  assert(r.response.status === 404, `organization isolation: ${r.response.status}`)
  r = await request(`${base}/api/v1/auth/device/revoke`, { method: 'POST', headers: headers(tokenC) })
  assert(r.response.status === 200, `revoke: ${r.response.status}`)
  r = await request(`${base}/api/v1/streams/${streamA}`, { headers: headers(tokenC) })
  assert(r.response.status === 401, `revoked token: ${r.response.status}`)

  r = await request(`${base}/api/v1/streams`, { method: 'POST', headers: headers(tokenA), body: '{not-json' })
  assert(r.response.status === 400 && r.body?.error?.code === 'invalid_json', `invalid JSON: ${r.response.status}`)

  r = await request(`${base}/api/v1/streams`, { method: 'POST', headers: headers(tokenA), body: JSON.stringify({ id: 'huge', name: 'x'.repeat(1_100_000) }) })
  assert(r.response.status === 413 && r.body?.error?.code === 'payload_too_large', `oversized body: ${r.response.status}`)

  r = await request(`${gateway}/api/v1/registry/${streamA}`, { headers: headers(tokenA) })
  assert([200, 404].includes(r.response.status), `gateway dependency contract: ${r.response.status}`)
  r = await request(`${gateway}/api/v1/registry/${streamA}`, { headers: headers('not-a-real-token-0123456789') })
  assert(r.response.status === 401, `gateway invalid token: ${r.response.status}`)

  console.log('PASS: authentication, authorization, ownership, conflicts, validation, request IDs, Gateway contracts')
} finally {
  await db.query('DELETE FROM streams WHERE id=$1', [streamA])
  await db.query('DELETE FROM auth_tokens WHERE camera_id IN ($1,$2,$3)', [camA, camB, camC])
  await db.query('DELETE FROM audit_events WHERE organization_id IN ($1,$2)', [orgA, orgB])
  await db.query('DELETE FROM camera_permissions WHERE camera_id IN ($1,$2,$3)', [camA, camB, camC])
  await db.query('DELETE FROM cameras WHERE id IN ($1,$2,$3)', [camA, camB, camC])
  await db.query('DELETE FROM organizations WHERE id IN ($1,$2)', [orgA, orgB])
  await db.end()
}
