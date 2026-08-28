const base = process.env.GATEWAY_URL ?? 'http://localhost:8081'
const id = `e2e-${Date.now()}`
const name = process.env.TEST_STREAM_PATH ?? 'e2e-test'

const response = await fetch(`${base}/api/v1/streams`, {
  method: 'POST',
  headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ id, name, source: 'fadcam' }),
})

if (response.status !== 201) throw new Error(`expected 201, got ${response.status}`)
const created = await response.json()
if (created.id !== id || created.name !== name) throw new Error('registration response mismatch')

const registered = await fetch(`${base}/api/v1/registry/${encodeURIComponent(id)}`)
if (!registered.ok) throw new Error(`registry lookup returned ${registered.status}`)
const stored = await registered.json()
if (stored.id !== id || stored.name !== name) throw new Error('registry lookup mismatch')

console.log('PASS: Gateway stream registration contract')
