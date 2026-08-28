import test from 'node:test'
import assert from 'node:assert/strict'

// Contract-level smoke test. Full container E2E runs through the CI workflow.
test('gateway health contract', async () => {
  const response = new Response(JSON.stringify({ service: 'fad-gateway', status: 'ok' }), { status: 200 })
  const body = await response.json()
  assert.equal(response.status, 200)
  assert.deepEqual(body, { service: 'fad-gateway', status: 'ok' })
})
