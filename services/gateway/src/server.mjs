import { serve } from '@hono/node-server'
import { Hono } from 'hono'

const app = new Hono()
const port = Number(process.env.PORT || 8081)
const mediamtxApi = process.env.MEDIAMTX_API_URL || 'http://mediamtx:9997'
const mediamtxUser = process.env.MEDIAMTX_API_USER || 'any'
const mediamtxPass = process.env.MEDIAMTX_API_PASSWORD || ''
const coreApi = process.env.CORE_API_URL || 'http://core:8080'

function validPath(path) { return Boolean(path) && !path.includes('..') && !path.includes('/') }
function mediamtxHeaders() { return { Authorization: `Basic ${Buffer.from(`${mediamtxUser}:${mediamtxPass}`).toString('base64')}` } }

app.get('/health', (c) => c.json({ service: 'fad-gateway', status: 'ok' }))
app.get('/api/v1/health', async (c) => {
  try { const r = await fetch(`${coreApi}/api/v1/health`); if (!r.ok) throw new Error(); return c.json({ service: 'fad-gateway', status: 'ok', dependencies: { core: 'ok', mediamtx: mediamtxApi } }) }
  catch { return c.json({ service: 'fad-gateway', status: 'degraded', dependencies: { core: 'unavailable', mediamtx: mediamtxApi } }, 503) }
})

app.post('/api/v1/streams', async (c) => {
  const body = await c.req.json().catch(() => null)
  if (!body?.id || !validPath(body.name)) return c.json({ error: 'id and valid name are required' }, 400)
  const r = await fetch(`${coreApi}/api/v1/streams`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) })
  return c.json(await r.json(), r.status)
})

app.get('/api/v1/streams/:path', async (c) => {
  const path = c.req.param('path')
  if (!validPath(path)) return c.json({ error: 'invalid stream path' }, 400)

  // The live-path list is the authoritative discovery mechanism for this
  // gateway. MediaMTX can expose an active publisher in /v3/paths/list while
  // the per-path /get endpoint is transient during publisher state changes.
  // Listing also matches the discovery contract used by the E2E test.
  try {
    const response = await fetch(`${mediamtxApi}/v3/paths/list`, {
      headers: mediamtxHeaders(),
    })
    if (!response.ok) return c.json({ error: 'MediaMTX path discovery failed', status: response.status }, 502)

    const payload = await response.json()
    const item = Array.isArray(payload?.items)
      ? payload.items.find(candidate => candidate?.name === path)
      : null

    if (item) return c.json(item)
    return c.json({ error: 'stream not found', path }, 404)
  } catch {
    return c.json({ error: 'MediaMTX path discovery unavailable' }, 502)
  }
})

app.get('/api/v1/registry/:id', async (c) => {
  const response = await fetch(`${coreApi}/api/v1/streams/${encodeURIComponent(c.req.param('id'))}`)
  return c.json(await response.json(), response.status)
})

serve({ fetch: app.fetch, port })
console.log(`Fad Gateway listening on ${port}`)
