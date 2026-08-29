import http from 'node:http';
import pg from 'pg';

const { Pool } = pg;
const port = Number(process.env.PORT || 8080);
const version = '0.2.0';
const maxBodyBytes = Number(process.env.MAX_BODY_BYTES || 1024 * 1024);
const pool = new Pool({ connectionString: process.env.DATABASE_URL });

if (!Number.isInteger(maxBodyBytes) || maxBodyBytes < 1024 || maxBodyBytes > 10 * 1024 * 1024) {
  throw new Error('MAX_BODY_BYTES must be an integer between 1024 and 10485760');
}

async function dbReady() { await pool.query('SELECT 1'); }
async function jsonBody(req) {
  const declaredLength = Number(req.headers['content-length'] || 0);
  if (declaredLength > maxBodyBytes) {
    const error = new Error('request body too large');
    error.code = 'BODY_TOO_LARGE';
    throw error;
  }

  const chunks = [];
  let total = 0;
  for await (const chunk of req) {
    total += chunk.length;
    if (total > maxBodyBytes) {
      const error = new Error('request body too large');
      error.code = 'BODY_TOO_LARGE';
      throw error;
    }
    chunks.push(chunk);
  }

  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
  } catch {
    const error = new Error('invalid JSON');
    error.code = 'INVALID_JSON';
    throw error;
  }
}
function send(res, status, body) {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(body));
}

const server = http.createServer(async (req, res) => {
  const path = req.url?.split('?')[0] ?? '/';
  try {
    if (req.method === 'GET' && path === '/api/v1/health') {
      await dbReady();
      return send(res, 200, { status: 'ok', service: 'fad-core', version, database: 'ok' });
    }
    if (req.method === 'POST' && path === '/api/v1/streams') {
      const body = await jsonBody(req);
      if (!body.id || !body.name || !/^[A-Za-z0-9_-]+$/.test(body.name)) return send(res, 400, { error: 'invalid_stream' });
      const id = body.id;
      const result = await pool.query(
        `INSERT INTO streams (id, name, source, channel_id, status) VALUES ($1,$2,$3,$4,'registered')
         ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, source=EXCLUDED.source, channel_id=EXCLUDED.channel_id, updated_at=now()
         RETURNING id, name, source, channel_id AS "channelId", status`,
        [id, body.name, body.source ?? 'fadcam', body.channelId ?? null]
      );
      return send(res, 201, result.rows[0]);
    }
    const match = path.match(/^\/api\/v1\/streams\/([^/]+)$/);
    if (req.method === 'GET' && match) {
      const result = await pool.query('SELECT id, name, source, channel_id AS "channelId", status, created_at AS "createdAt", updated_at AS "updatedAt" FROM streams WHERE id=$1 OR name=$1 LIMIT 1', [decodeURIComponent(match[1])]);
      return result.rowCount ? send(res, 200, result.rows[0]) : send(res, 404, { error: 'not_found' });
    }
    send(res, 404, { error: 'not_found' });
  } catch (error) {
    if (error.code === 'BODY_TOO_LARGE') return send(res, 413, { error: 'payload_too_large' });
    if (error.code === 'INVALID_JSON') return send(res, 400, { error: 'invalid_json' });
    console.error(error);
    send(res, 503, { error: 'service_unavailable' });
  }
});

server.listen(port, '0.0.0.0', () => console.log(`fad-core listening on ${port}`));

async function shutdown(signal) {
  console.log(`Received ${signal}; shutting down core`);
  server.close(async () => {
    await pool.end();
    process.exit(0);
  });
  setTimeout(() => process.exit(1), 5000).unref();
}
process.once('SIGTERM', () => shutdown('SIGTERM'));
process.once('SIGINT', () => shutdown('SIGINT'));
