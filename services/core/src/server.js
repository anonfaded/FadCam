import http from 'node:http';
import { randomUUID } from 'node:crypto';
import pg from 'pg';

const { Pool } = pg;
const port = Number(process.env.PORT || 8080);
const version = '0.2.0';
const pool = new Pool({ connectionString: process.env.DATABASE_URL });

async function dbReady() { await pool.query('SELECT 1'); }
async function jsonBody(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
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
    console.error(error);
    send(res, 503, { error: 'service_unavailable' });
  }
});

server.listen(port, '0.0.0.0', () => console.log(`fad-core listening on ${port}`));
