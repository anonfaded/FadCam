const sessions = new Map()

export function createProduction(input) {
  const id = input.id || crypto.randomUUID()
  const session = { id, name: input.name || id, channelId: input.channelId ?? null, status: 'draft', scenes: [], createdAt: new Date().toISOString() }
  sessions.set(id, session)
  return session
}

export function getProduction(id) { return sessions.get(id) ?? null }
