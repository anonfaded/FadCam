const base = process.env.GATEWAY_URL ?? 'http://localhost:8081'
const path = process.env.TEST_STREAM_PATH ?? 'e2e-test'
const mediaMtxApi = process.env.MEDIAMTX_API ?? 'http://localhost:9997'
const mediaMtxUser = process.env.MEDIAMTX_API_USER ?? 'api-e2e'
const mediaMtxPassword = process.env.MEDIAMTX_API_PASSWORD ?? 'api-e2e-pass'

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms))
const mediaHeaders = { Authorization: `Basic ${Buffer.from(`${mediaMtxUser}:${mediaMtxPassword}`).toString('base64')}` }

async function getJson(url, options = {}) {
  const response = await fetch(url, options)
  if (!response.ok) throw new Error(`${url} returned HTTP ${response.status}`)
  return response.json()
}

async function waitForMediaMtx() {
  const deadline = Date.now() + Number(process.env.E2E_TIMEOUT_MS ?? 45000)
  let lastError = 'no response'
  while (Date.now() < deadline) {
    try {
      const media = await getJson(`${mediaMtxApi}/v3/paths/list`, { headers: mediaHeaders })
      const match = media?.items?.find(item => item?.name === path)
      if (match) return match
      lastError = `path absent; active paths=${JSON.stringify(media?.items ?? [])}`
    } catch (error) { lastError = error.message }
    await sleep(1000)
  }
  throw new Error(`MediaMTX did not report live stream '${path}': ${lastError}`)
}

async function main() {
  const media = await waitForMediaMtx()
  if (media.name !== path) throw new Error(`MediaMTX returned unexpected stream: ${JSON.stringify(media)}`)

  const deadline = Date.now() + Number(process.env.E2E_TIMEOUT_MS ?? 45000)
  let lastError = 'no response'
  while (Date.now() < deadline) {
    try {
      const result = await getJson(`${base}/api/v1/streams/${encodeURIComponent(path)}`)
      if (result?.name === path) {
        console.log(`PASS: authenticated FFmpeg publisher -> MediaMTX -> Gateway (${path})`)
        return
      }
      lastError = `Gateway returned unexpected path payload: ${JSON.stringify(result)}`
    } catch (error) { lastError = error.message }
    await sleep(1000)
  }

  throw new Error(`Gateway did not discover '${path}' after MediaMTX reported it live: ${lastError}`)
}

main().catch(error => {
  console.error(`FAIL: ${error.message}`)
  process.exit(1)
})
