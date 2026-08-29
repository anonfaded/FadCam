import { serve } from '@hono/node-server'
import { Hono } from 'hono'

const app = new Hono()
const port = Number(process.env.PORT || 8081)
const mediamtxApi = process.env.MEDIAMTX_API_URL || 'http://mediamtx:9997'
const mediamtxUser = process.env.MEDIAMTX_API_USER
const mediamtxPass = process.env.MEDIAMTX_API_PASSWORD
const coreApi = process.env.CORE_API_URL || 'http://core:8080'
const coreServiceToken = process.env.CORE_SERVICE_TOKEN
const upstreamTimeoutMs = Number(process.env.UPSTREAM_TIMEOUT_MS || 5000)

if (!mediamtxUser || !mediamtxPass) throw new Error('MEDIAMTX_API_USER and MEDIAMTX_API_PASSWORD are required')
if (!coreServiceToken || coreServiceToken.length < 20) throw new Error('CORE_SERVICE_TOKEN is required and must be at least 20 characters')
if (!Number.isInteger(upstreamTimeoutMs) || upstreamTimeoutMs < 250 || upstreamTimeoutMs > 30000) throw new Error('UPSTREAM_TIMEOUT_MS must be an integer between 250 and 30000')

function validPath(path) { return Boolean(path) && !path.includes('..') && !path.includes('/') }
function mediamtxHeaders() { return { Authorization: `Basic ${Buffer.from(`${mediamtxUser}:${mediamtxPass}`).toString('base64')}` } }
function serviceHeaders(extra = {}) { return { 'x-service-token': coreServiceToken, ...extra } }
function fetchWithTimeout(url, options = {}) { return fetch(url, { ...options, signal: AbortSignal.timeout(upstreamTimeoutMs) }) }

async function authenticateClient(c) {
  const authorization = c.req.header('authorization')
  if (!authorization) return null
  try {
    const response = await fetchWithTimeout(`${coreApi}/api/v1/auth/introspect`, { method: 'POST', headers: serviceHeaders({ authorization }) })
    if (!response.ok) return null
    return await response.json()
  } catch { return null }
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
  } catch { return c.json({ service: 'fad-gateway', status: 'degraded', dependencies }, 503) }
})

app.post('/api/v1/streams', async (c) => {
  const identity = await authenticateClient(c)
  if (!identity?.cameraId) return c.json({ error: { code: 'unauthorized', message: 'Valid device authentication is required' } }, 401)
  const body = await c.req.json().catch(() => null)
  if (!body?.id || !validPath(body.name)) return c.json({ error: { code: 'invalid_stream', message: 'id and valid name are required' } }, 400)
  try {
    const r = await fetchWithTimeout(`${coreApi}/api/v1/streams`, { method: 'POST', headers: serviceHeaders({ 'content-type': 'application/json', 'x-authenticated-camera': identity.cameraId }), body: JSON.stringify(body) })
    return c.json(await r.json(), r.status)
  } catch { return c.json({ error: { code: 'core_unavailable', message: 'Core service unavailable' } }, 503) }
})

app.get('/api/v1/streams/:path', async (c) => {
  const identity = await authenticateClient(c)
  if (!identity?.cameraId) return c.json({ error: { code: 'unauthorized', message: 'Valid device authentication is required' } }, 401)
  const path = c.req.param('path')
  if (!validPath(path)) return c.json({ error: { code: 'invalid_path', message: 'Invalid stream path' } }, 400)
  try {
    const response = await fetchWithTimeout(`${mediamtxApi}/v3/paths/list`, { headers: mediamtxHeaders() })
    if (!response.ok) return c.json({ error: { code: 'mediamtx_discovery_failed', message: 'MediaMTX path discovery failed' } }, 502)
    const payload = await response.json(); const item = Array.isArray(payload?.items) ? payload.items.find(candidate => candidate?.name === path) : null
    if (item) return c.json(item)
    return c.json({ error: { code: 'not_found', message: 'Stream not found' } }, 404)
  } catch { return c.json({ error: { code: 'mediamtx_unavailable', message: 'MediaMTX path discovery unavailable' } }, 502) }
})

app.get('/api/v1/registry/:id', async (c) => {
  const identity = await authenticateClient(c)
  if (!identity?.cameraId) return c.json({ error: { code: 'unauthorized', message: 'Valid device authentication is required' } }, 401)
  try {
    const response = await fetchWithTimeout(`${coreApi}/api/v1/streams/${encodeURIComponent(c.req.param('id'))}`, { headers: serviceHeaders({ 'x-authenticated-camera': identity.cameraId }) })
    return c.json(await response.json(), response.status)
  } catch { return c.json({ error: { code: 'core_unavailable', message: 'Core service unavailable' } }, 503) }
})

const server = serve({ fetch: app.fetch, port })
console.log(`Fad Gateway listening on ${port}`)
function shutdown(signal) { console.log(`Received ${signal}; shutting down gateway`); server.close(() => process.exit(0)); setTimeout(() => process.exit(1), 5000).unref() }
process.once('SIGTERM', () => shutdown('SIGTERM'))
process.once('SIGINT', () => shutdown('SIGINT'))
