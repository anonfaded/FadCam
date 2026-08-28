import { authenticateDevice } from '../../core/src/auth.mjs'

export function requireDeviceAuth(request) {
  const deviceId = request.headers.get('x-fad-device-id')
  const token = request.headers.get('x-fad-device-token')
  if (!deviceId || !authenticateDevice(deviceId, token)) return { ok: false, status: 401 }
  return { ok: true, deviceId }
}
