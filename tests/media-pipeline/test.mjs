const base = process.env.GATEWAY_URL ?? 'http://localhost:8081';
const path = process.env.TEST_STREAM_PATH ?? 'e2e-test';
const mediaMtxApi = process.env.MEDIAMTX_API ?? 'http://localhost:9997';
const mediaMtxUser = process.env.MEDIAMTX_API_USER ?? 'any';
const mediaMtxPassword = process.env.MEDIAMTX_API_PASSWORD ?? '';

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));
const mediaHeaders = { Authorization: `Basic ${Buffer.from(`${mediaMtxUser}:${mediaMtxPassword}`).toString('base64')}` };

async function getJson(url, options = {}) {
  const response = await fetch(url, options);
  if (!response.ok) throw new Error(`${url} returned HTTP ${response.status}`);
  return response.json();
}

async function main() {
  const deadline = Date.now() + Number(process.env.E2E_TIMEOUT_MS ?? 45000);
  let found = false;
  let lastError = 'no response';

  while (Date.now() < deadline) {
    try {
      const result = await getJson(`${base}/api/v1/streams/${encodeURIComponent(path)}`);
      if (result?.name === path) {
        found = true;
        break;
      }
      lastError = `Gateway returned unexpected path payload: ${JSON.stringify(result)}`;
    } catch (error) {
      lastError = error.message;
    }
    await sleep(1000);
  }

  if (!found) throw new Error(`Gateway did not discover '${path}' within the timeout: ${lastError}`);

  const media = await getJson(`${mediaMtxApi}/v3/paths/list`, { headers: mediaHeaders });
  const match = media?.items?.find(item => item?.name === path);
  if (!match) throw new Error(`MediaMTX API did not report stream '${path}'; items=${JSON.stringify(media?.items ?? [])}`);

  console.log(`PASS: FFmpeg synthetic source -> MediaMTX -> Gateway -> assertion (${path})`);
}

main().catch(error => {
  console.error(`FAIL: ${error.message}`);
  process.exit(1);
});
