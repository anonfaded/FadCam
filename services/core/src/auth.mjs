import crypto from 'node:crypto'

const TOKEN_BYTES = 32

export function generateToken() {
  return crypto.randomBytes(TOKEN_BYTES).toString('base64url')
}

export function hashToken(token) {
  if (typeof token !== 'string' || token.length < 20) return null
  return crypto.createHash('sha256').update(token, 'utf8').digest('hex')
}

export function safeEqualHex(left, right) {
  if (typeof left !== 'string' || typeof right !== 'string') return false
  const a = Buffer.from(left, 'hex')
  const b = Buffer.from(right, 'hex')
  return a.length === b.length && a.length > 0 && crypto.timingSafeEqual(a, b)
}

export function tokenFromAuthorization(header) {
  if (typeof header !== 'string') return null
  const match = header.match(/^Bearer\s+(.+)$/i)
  return match?.[1] ?? null
}
