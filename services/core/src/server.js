import http from 'node:http';
import crypto from 'node:crypto';
import pg from 'pg';
import { generateToken, hashToken, safeEqualHex, tokenFromAuthorization } from './auth.mjs';

const { Pool } = pg;
const port = Number(process.env.PORT || 8080);
const version = '0.3.0';
const maxBodyBytes = Number(process.env.MAX_BODY_BYTES || 1024 * 1024);
const pool = new Pool({ connectionString: process.env.DATABASE_URL });
const bootstrapSecret = process.env.AUTH_BOOTSTRAP_SECRET || '';
const serviceTokenHash = hashToken(process.env.CORE_SERVICE_TOKEN || '');

if (!Number.isInteger(maxBodyBytes) || maxBodyBytes < 1024 || maxBodyBytes > 10 * 1024 * 1024) throw new Error('MAX_BODY_BYTES must be an integer between 1024 and 10485760');
if (!serviceTokenHash) throw new Error('CORE_SERVICE_TOKEN is required');

async function dbReady() { await pool.query('SELECT 1'); }
async function jsonBody(req) {
  const declaredLength = Number(req.headers['content-length'] || 0);
  if (declaredLength > maxBodyBytes) { const e = new Error('request body too large'); e.code = 'BODY_TOO_LARGE'; throw e; }
  const chunks = []; let total = 0;
  for await (const chunk of req) { total += chunk.length; if (total > maxBodyBytes) { const e = new Error('request body too large'); e.code = 'BODY_TOO_LARGE'; throw e; } chunks.push(chunk); }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'); } catch { const e = new Error('invalid JSON'); e.code = 'INVALID_JSON'; throw e; }
}
function send(res, status, body, requestId) { res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'x-request-id': requestId }); res.end(JSON.stringify(body)); }
function error(res, status, code, message, requestId, details) { return send(res, status, { error: { code, message, ...(details ? { details } : {}) }, requestId }, requestId); }
function serviceAuthorized(req) { const token = req.headers['x-service-token']; return typeof token === 'string' && safeEqualHex(hashToken(token), serviceTokenHash); }
function validName(name) { return typeof name === 'string' && /^[A-Za-z0-9_-]{1,128}$/.test(name); }

async function authenticate(req) {
  const raw = tokenFromAuthorization(req.headers.authorization);
  const tokenHash = hashToken(raw || '');
  if (!tokenHash) return null;
  const result = await pool.query(`SELECT t.id AS token_id, t.camera_id, t.expires_at, c.organization_id, c.status
    FROM auth_tokens t JOIN cameras c ON c.id=t.camera_id
    WHERE t.token_hash=$1 AND t.revoked_at IS NULL AND c.revoked_at IS NULL
      AND (t.expires_at IS NULL OR t.expires_at > now()) LIMIT 1`, [tokenHash]);
  if (!result.rowCount) return null;
  const identity = result.rows[0];
  await pool.query('UPDATE auth_tokens SET last_used_at=now() WHERE id=$1', [identity.token_id]);
  return identity;
}

async function audit(identity, req, action, resourceType, resourceId, requestId, metadata = {}) {
  await pool.query(`INSERT INTO audit_events(organization_id,camera_id,action,resource_type,resource_id,request_id,ip_address,metadata)
    VALUES($1,$2,$3,$4,$5,$6,$7,$8)`, [identity?.organization_id ?? null, identity?.camera_id ?? null, action, resourceType ?? null, resourceId ?? null, requestId, req.socket.remoteAddress ?? null, metadata]);
}

const server = http.createServer(async (req, res) => {
  const requestId = req.headers['x-request-id']?.toString().slice(0, 128) || crypto.randomUUID();
  const path = req.url?.split('?')[0] ?? '/';
  try {
    if (req.method === 'GET' && path === '/api/v1/health') { await dbReady(); return send(res, 200, { status: 'ok', service: 'fad-core', version, database: 'ok' }, requestId); }

    if (req.method === 'POST' && path === '/api/v1/auth/device/token') {
      if (!bootstrapSecret || !safeEqualHex(hashToken(req.headers['x-bootstrap-secret']?.toString() || ''), hashToken(bootstrapSecret))) return error(res, 401, 'invalid_bootstrap_credentials', 'Invalid bootstrap credentials', requestId);
      const body = await jsonBody(req);
      if (!body.deviceKey || !body.name) return error(res, 400, 'invalid_device', 'deviceKey and name are required', requestId);
      const camera = await pool.query('SELECT id, organization_id, status FROM cameras WHERE device_key=$1 LIMIT 1', [body.deviceKey]);
      if (!camera.rowCount) return error(res, 404, 'device_not_found', 'Device is not registered', requestId);
      if (camera.rows[0].status === 'revoked') return error(res, 403, 'device_revoked', 'Device is revoked', requestId);
      const token = generateToken(); const tokenHash = hashToken(token); const id = crypto.randomUUID();
      await pool.query('INSERT INTO auth_tokens(id,camera_id,token_hash,expires_at) VALUES($1,$2,$3,$4)', [id, camera.rows[0].id, tokenHash, body.expiresAt ?? null]);
      await audit(camera.rows[0], req, 'device_token_issued', 'camera', camera.rows[0].id, requestId);
      return send(res, 201, { token, tokenType: 'Bearer', expiresAt: body.expiresAt ?? null }, requestId);
    }

    if (req.method === 'POST' && path === '/api/v1/auth/device/revoke') {
      const identity = await authenticate(req);
      if (!identity) return error(res, 401, 'unauthorized', 'Valid device authentication is required', requestId);
      await pool.query('UPDATE auth_tokens SET revoked_at=now() WHERE camera_id=$1 AND revoked_at IS NULL', [identity.camera_id]);
      await pool.query('UPDATE cameras SET revoked_at=now(), status=\'revoked\' WHERE id=$1', [identity.camera_id]);
      await audit(identity, req, 'device_revoked', 'camera', identity.camera_id, requestId);
      return send(res, 200, { revoked: true }, requestId);
    }

    if (req.method === 'POST' && path === '/api/v1/auth/introspect') {
      if (!serviceAuthorized(req)) return error(res, 401, 'invalid_service_credentials', 'Invalid service credentials', requestId);
      const identity = await authenticate(req);
      if (!identity) return error(res, 401, 'unauthorized', 'Invalid, expired, or revoked device token', requestId);
      return send(res, 200, { cameraId: identity.camera_id, organizationId: identity.organization_id }, requestId);
    }

    const trustedCamera = req.headers['x-authenticated-camera']?.toString();
    const identity = trustedCamera && serviceAuthorized(req) ? (await pool.query('SELECT id AS camera_id, organization_id, status FROM cameras WHERE id=$1 AND revoked_at IS NULL', [trustedCamera])).rows[0] : await authenticate(req);

    if (req.method === 'POST' && path === '/api/v1/streams') {
      if (!identity) return error(res, 401, 'unauthorized', 'Valid device authentication is required', requestId);
      const body = await jsonBody(req);
      if (!body.id || !validName(body.name)) return error(res, 400, 'invalid_stream', 'id and valid name are required', requestId);
      const cameraId = body.cameraId || identity.camera_id;
      if (cameraId !== identity.camera_id) return error(res, 403, 'camera_forbidden', 'Device cannot operate on another camera', requestId);
      if (body.channelId) {
        const channel = await pool.query('SELECT id FROM channels WHERE id=$1 AND organization_id=$2', [body.channelId, identity.organization_id]);
        if (!channel.rowCount) return error(res, 403, 'organization_forbidden', 'Channel is outside the device organization', requestId);
      }
      const existing = await pool.query('SELECT id,camera_id,name FROM streams WHERE id=$1 OR name=$2 LIMIT 1', [body.id, body.name]);
      if (existing.rowCount && existing.rows[0].camera_id && existing.rows[0].camera_id !== identity.camera_id) return error(res, 409, 'stream_conflict', 'Stream is owned by another camera', requestId);
      if (existing.rowCount && existing.rows[0].id !== body.id) return error(res, 409, 'stream_name_conflict', 'Stream name already exists', requestId);
      const result = await pool.query(`INSERT INTO streams(id,name,source,channel_id,camera_id,status) VALUES($1,$2,$3,$4,$5,'registered')
        ON CONFLICT(id) DO UPDATE SET name=EXCLUDED.name, source=EXCLUDED.source, channel_id=EXCLUDED.channel_id, updated_at=now()
        RETURNING id,name,source,channel_id AS "channelId",camera_id AS "cameraId",status`, [body.id, body.name, body.source ?? 'fadcam', body.channelId ?? null, cameraId]);
      await audit(identity, req, 'stream_registered', 'stream', body.id, requestId);
      return send(res, existing.rowCount ? 200 : 201, result.rows[0], requestId);
    }

    const match = path.match(/^\/api\/v1\/streams\/([^/]+)$/);
    if (req.method === 'GET' && match) {
      if (!identity) return error(res, 401, 'unauthorized', 'Valid device authentication is required', requestId);
      const result = await pool.query(`SELECT s.id,s.name,s.source,s.channel_id AS "channelId",s.camera_id AS "cameraId",s.status,s.created_at AS "createdAt",s.updated_at AS "updatedAt"
        FROM streams s WHERE (s.id=$1 OR s.name=$1) AND s.camera_id=$2 LIMIT 1`, [decodeURIComponent(match[1]), identity.camera_id]);
      return result.rowCount ? send(res, 200, result.rows[0], requestId) : error(res, 404, 'not_found', 'Stream not found', requestId);
    }
    return error(res, 404, 'not_found', 'Route not found', requestId);
  } catch (e) {
    if (e.code === 'BODY_TOO_LARGE') return error(res, 413, 'payload_too_large', 'Request body exceeds the configured limit', requestId);
    if (e.code === 'INVALID_JSON') return error(res, 400, 'invalid_json', 'Request body is not valid JSON', requestId);
    console.error({ requestId, error: e });
    return error(res, 503, 'service_unavailable', 'Service unavailable', requestId);
  }
});

server.listen(port, '0.0.0.0', () => console.log(`fad-core listening on ${port}`));
async function shutdown(signal) { console.log(`Received ${signal}; shutting down core`); server.close(async () => { await pool.end(); process.exit(0); }); setTimeout(() => process.exit(1), 5000).unref(); }
process.once('SIGTERM', () => shutdown('SIGTERM'));
process.once('SIGINT', () => shutdown('SIGINT'));
