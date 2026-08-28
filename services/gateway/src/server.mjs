import { serve } from '@hono/node-server'
import { Hono } from 'hono'

const app = new Hono()
const port = Number(process.env.PORT || 8081)
const mediamtxApi = process.env.MEDIAMTX_API_URL || 'http://mediamtx:9997'

app.get('/health', (c) => c.json({ service: 'fad-gateway', status: 'ok' }))
app.get('/api/v1/health', (c) => c.json({ service: 'fad-gateway', status: 'ok', dependencies: { mediamtx: mediamtxApi } }))

app.get('/api/v1/streams/:path', async (c) => {
  const path = c.req.param('path')
  if (!path || path.includes('..') || path.includes('/')) return c.json({ error: 'invalid stream path' }, 400)
  const response = await fetch(`${mediamtxApi}/v3/paths/get/${encodeURIComponent(path)}`)
  if (!response.ok) return c.json({ error: 'stream not found' }, 404)
  return c.json(await response.json())
})

serve({ fetch: app.fetch, port })
console.log(`Fad Gateway listening on ${port}`)
