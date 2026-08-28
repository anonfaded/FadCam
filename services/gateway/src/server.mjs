import { serve } from '@hono/node-server'
import { Hono } from 'hono'
import { getStream, listStreams, registerStream } from './registry.mjs'

const app = new Hono()
const port = Number(process.env.PORT || 8081)
const mediamtxApi = process.env.MEDIAMTX_API_URL || 'http://mediamtx:9997'

function validPath(path) {
  return Boolean(path) && !path.includes('..') && !path.includes('/')
}

app.get('/health', (c) => c.json({ service: 'fad-gateway', status: 'ok' }))
app.get('/api/v1/health', (c) => c.json({ service: 'fad-gateway', status: 'ok', dependencies: { mediamtx: mediamtxApi } }))

app.post('/api/v1/streams', async (c) => {
  const body = await c.req.json().catch(() => null)
  if (!body?.id || !validPath(body.name)) return c.json({ error: 'id and valid name are required' }, 400)
  return c.json(registerStream(body), 201)
})

app.get('/api/v1/streams', (c) => c.json({ items: listStreams() }))

app.get('/api/v1/streams/:path', async (c) => {
  const path = c.req.param('path')
  if (!validPath(path)) return c.json({ error: 'invalid stream path' }, 400)
  const response = await fetch(`${mediamtxApi}/v3/paths/get/${encodeURIComponent(path)}`)
  if (!response.ok) return c.json({ error: 'stream not found' }, 404)
  return c.json(await response.json())
})

app.get('/api/v1/registry/:id', (c) => {
  const stream = getStream(c.req.param('id'))
  return stream ? c.json(stream) : c.json({ error: 'stream not registered' }, 404)
})

serve({ fetch: app.fetch, port })
console.log(`Fad Gateway listening on ${port}`)
