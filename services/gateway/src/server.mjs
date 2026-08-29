import { serve } from '@hono/node-server'
import { Hono } from 'hono'

const app = new Hono()
const port = Number(process.env.PORT || 8081)
const mediamtxApi = process.env.MEDIAMTX_API_URL || 'http://mediamtx:9997'
const mediamtxUser = process.env.MEDIAMTX_API_USER
const mediamtxPass = process.env.MEDIAMTX_API_PASSWORD
const coreApi = process.env.CORE_API_URL || 'http://core:8080'
const upstreamTimeoutMs = Number(process.env.UPSTREAM_TIMEOUT_MS || 5000)

if (!mediamtxUser || !mediamtxPass) {
  throw new Error('MEDIAMTX_API_USER and MEDIAMTX_API_PASSWORD are required')
}
if (!Number.isInteger(upstreamTimeoutMs) || upstreamTimeoutMs < 250 || upstreamTimeoutMs > 30000) {
  throw new Error('UPSTREAM_TIMEOUT_MS must be an integer between 250 and 30000')
}

function validPath(path) { return Boolean(path) && !path.includes('..') && !path.includes('/') }
function mediamtxHeaders() {
  return { Authorization: `Basic ${Buffer.from(`${mediamtxUser}:${mediamtxPass}`).toString('base64')}` }
}
function fetchWithTimeout(url, options = {}) {
  return fetch(url, { ...options, signal: AbortSignal.timeout(upstreamTimeoutMs) })
}

app.get('/health', (c) => c.json({ service: 'fad-gateway', status: 'ok' }))
app.get('/api/v1/health', async (c) => {
  const dependencies = { core: 'unavailable', mediamtx: 'unavailable' }
  try {
    const [coreResponse, mediamtxResponse] = await Promise.all([
      fetchWithTimeout(`${coreApi}/api/v1/health`),
      fetchWithTimeout(`${mediamtxApi}/v3/paths/list`, { headers: mediamtxHeaders() }),
    ])
    if (coreResponse.ok) dependencies.core = 'ok'
    if (mediamtxResponse.ok) dependencies.mediamtx = 'ok'
    const healthy = dependencies.core === 'ok' && dependencies.mediamtx === 'ok'
    return c.json({ service: 'fad-gateway', status: healthy ? 'ok' : 'degraded', dependencies }, healthy ? 200 : 503)
  } catch {
    return c.json({ service: 'fad-gateway', status: 'degraded', dependencies }, 503)
  }
})

app.post('/api/v1/streams', async (c) => {
  const body = await c.req.json().catch(() => null)
  if (!body?.id || !validPath(body.name)) return c.json({ error: 'id and valid name are required' }, 400)
  try {
    const r = await fetchWithTimeout(`${coreApi}/api/v1/streams`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body),
    })
    return c.json(await r.json(), r.status)
  } catch {
    return c.json({ error: 'Core service unavailable' }, 503)
  }
})

app.get('/api/v1/streams/:path', async (c) => {
  const path = c.req.param('path')
  if (!validPath(path)) return c.json({ error: 'invalid stream path' }, 400)

  // The live-path list is the authoritative discovery mechanism for this
  // gateway. MediaMTX can expose an active publisher in /v3/paths/list while
  // the per-path /get endpoint is transient during publisher state changes.
  try {
    const response = await fetchWithTimeout(`${mediamtxApi}/v3/paths/list`, {
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
  try {
    const response = await fetchWithTimeout(`${coreApi}/api/v1/streams/${encodeURIComponent(c.req.param('id'))}`)
    return c.json(await response.json(), response.status)
  } catch {
    return c.json({ error: 'Core service unavailable' }, 503)
  }
})

const server = serve({ fetch: app.fetch, port })
console.log(`Fad Gateway listening on ${port}`)

function shutdown(signal) {
  console.log(`Received ${signal}; shutting down gateway`)
  server.close(() => process.exit(0))
  setTimeout(() => process.exit(1), 5000).unref()
}
process.once('SIGTERM', () => shutdown('SIGTERM'))
process.once('SIGINT', () => shutdown('SIGINT'))
