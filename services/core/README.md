# Fad Core

Central control-plane service for the fused Fad TV platform.

## Responsibilities

- platform identity and organizations
- users, roles, and permissions
- channels and content ownership
- production and media metadata
- programme and schedule metadata
- stream registration and lifecycle metadata
- service health and integration contracts

Fad Core owns application metadata and orchestration. It does **not** store large media objects or perform video transcoding.

## Integration boundary

```text
FadCam -> Gateway -> MediaMTX -> FFmpeg -> MinIO
                         |                    |
                         +--------------------+-> PeerTube

                 Fad Core owns metadata,
                 identity, and orchestration.
```

## API contract

All externally consumed Fad Core APIs will be versioned under `/api/v1` and described in `contracts/api/`.

Authentication, database persistence, channel management, and event publishing are added incrementally with integration tests. Services must communicate through explicit contracts rather than reaching into another service's implementation.
