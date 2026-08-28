const base = process.env.GATEWAY_URL ?? 'http://localhost:8081';
const path = process.env.TEST_STREAM_PATH ?? 'e2e-test';
const mediaMtxApi = process.env.MEDIAMTX_API ?? 'http://localhost:9997';

async function getJson(url) {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`${url} returned HTTP ${response.status}`);
  return response.json();
}

async function main() {
  const deadline = Date.now() + Number(process.env.E2E_TIMEOUT_MS ?? 30000);
  let found = false;

  while (Date.now() < deadline) {
    const result = await getJson(`${base}/api/v1/media/streams/${encodeURIComponent(path)}`);
    if (result?.path === path && result?.ready === true) {
      found = true;
      break;
    }
    await new Promise(resolve => setTimeout(resolve, 1000));
  }

  if (!found) {
    throw new Error(`Gateway did not observe ready stream '${path}' within the timeout`);
  }

  const media = await getJson(`${mediaMtxApi}/v3/paths/list`);
  const match = media?.items?.find(item => item?.name === path);
  if (!match) throw new Error(`MediaMTX API did not report stream '${path}'`);

  console.log(`PASS: FFmpeg synthetic source -> MediaMTX -> Gateway -> assertion (${path})`);
}

main().catch(error => {
  console.error(`FAIL: ${error.message}`);
  process.exit(1);
});
