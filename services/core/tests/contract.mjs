const base = process.env.CORE_URL || 'http://127.0.0.1:8080';

async function request(path, options) {
  const response = await fetch(`${base}${path}`, options);
  let body = null;
  try { body = await response.json(); } catch {}
  return { response, body };
}

const health = await request('/api/v1/health');
if (health.response.status !== 200 || health.body?.status !== 'ok' || health.body?.database !== 'ok') {
  throw new Error(`health contract failed: ${health.response.status}`);
}

const invalid = await request('/api/v1/streams', {
  method: 'POST',
  headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ id: 'contract-invalid', name: '../escape' }),
});
if (invalid.response.status !== 400 || invalid.body?.error !== 'invalid_stream') {
  throw new Error(`validation contract failed: ${invalid.response.status}`);
}

const missing = await request('/api/v1/streams/does-not-exist');
if (missing.response.status !== 404 || missing.body?.error !== 'not_found') {
  throw new Error(`not-found contract failed: ${missing.response.status}`);
}

console.log('PASS: Core API health, validation, and not-found contracts');
