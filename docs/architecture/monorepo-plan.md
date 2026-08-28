# Fad TV monorepo execution plan

This repository is the orchestration point for a production-to-consumption virtual TV platform.

## Runtime boundaries

- `app/` — Android FadCam production client.
- `services/core/` — control plane and PostgreSQL-owned metadata.
- `services/gateway/` — authenticated media ingress and MediaMTX control boundary.
- `services/production/` — live production sessions, scenes and programme output.
- `services/playout/` — schedules, playlists and channel automation.
- `services/epg/` — programme guide and feed generation.
- `services/monetization/` — entitlements, advertising and revenue integration boundaries.
- `services/analytics/` — audience, media and revenue events.
- `platform/` — pinned media/storage/distribution engines.
- `apps/web/` — FadPlay browser experience.
- `apps/tv/` — FadPlay TV experience.
- `contracts/` — versioned API and event schemas shared by services.
- `deployment/` — local, CI and production orchestration.
- `tests/` — unit, integration, media-pipeline and end-to-end verification.

## Delivery gates

1. PostgreSQL schema and Core readiness.
2. Gateway registration backed by Core.
3. Device authentication and authorization.
4. FFmpeg -> MediaMTX -> Gateway discovery E2E.
5. Recording -> FFmpeg processing -> MinIO object verification.
6. Real FadCam ingest against the authenticated Gateway.
7. Production session and program-output control.
8. Playout scheduling and live override.
9. EPG generation and FadPlay live/VOD consumption.
10. Monetization and analytics with auditable events.

No later gate is considered complete while an earlier gate is failing in CI.
