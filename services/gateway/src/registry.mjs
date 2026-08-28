const streams = new Map()

export function registerStream(input) {
  const now = new Date().toISOString()
  const stream = {
    id: input.id,
    name: input.name,
    channelId: input.channelId ?? null,
    source: input.source ?? 'fadcam',
    status: 'registered',
    createdAt: streams.get(input.id)?.createdAt ?? now,
    updatedAt: now,
  }
  streams.set(input.id, stream)
  return stream
}

export function getStream(id) {
  return streams.get(id) ?? null
}

export function listStreams() {
  return [...streams.values()]
}
