import test from 'node:test'
import assert from 'node:assert/strict'
import { generateToken, hashToken, safeEqualHex, tokenFromAuthorization } from '../src/auth.mjs'

test('generates cryptographically strong bearer tokens', () => {
  const a = generateToken(); const b = generateToken()
  assert.equal(typeof a, 'string'); assert.ok(a.length >= 40); assert.notEqual(a, b)
})

test('hashes tokens and compares hashes in constant time', () => {
  const token = generateToken(); const hash = hashToken(token)
  assert.equal(hashToken(token), hash)
  assert.equal(safeEqualHex(hash, hash), true)
  assert.equal(safeEqualHex(hash, hashToken(generateToken())), false)
  assert.equal(safeEqualHex(hash, hash.slice(0, -2)), false)
})

test('parses only Bearer authorization values', () => {
  const token = generateToken()
  assert.equal(tokenFromAuthorization(`Bearer ${token}`), token)
  assert.equal(tokenFromAuthorization(`bearer ${token}`), token)
  assert.equal(tokenFromAuthorization(`Basic ${token}`), null)
  assert.equal(tokenFromAuthorization(undefined), null)
})
