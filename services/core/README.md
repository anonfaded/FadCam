# Fad Core

Fad Core is the control-plane service for the fused Fad TV platform.

## Responsibilities

- platform identity and organizations
- channels and permissions
- production and media metadata
- programme and schedule metadata
- service health and integration contracts

Media bytes do not belong here. Store media in MinIO and keep live transport in MediaMTX.

## Initial API contract

The first implementation will expose a versioned `/api/v1` API. Authentication, database persistence, and channel management will be added incrementally with integration tests.

## Integration boundary

```text
FadCam -> Media Gateway -> MediaMTX
                       -> FFmpeg
                       -> MinIO
                       -> PeerTube

             Fad Core owns metadata and orchestration.
```
