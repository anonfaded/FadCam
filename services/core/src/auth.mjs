import crypto from 'node:crypto'

const devices = new Map()

export function registerDevice({ deviceId, name, userId }) {
  const token = crypto.randomBytes(32).toString('hex')
  const device = { deviceId, name: name ?? deviceId, userId: userId ?? null, tokenHash: hash(token), createdAt: new Date().toISOString() }
  devices.set(deviceId, device)
  return { deviceId, token }
}

export function authenticateDevice(deviceId, token) {
  const device = devices.get(deviceId)
  return Boolean(device && token && hash(token) === device.tokenHash)
}

function hash(value) { return crypto.createHash('sha256').update(value).digest('hex') }
