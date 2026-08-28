# Fad TV — fused platform foundation

This repository is the Android production client and the top-level integration point for the Fad TV platform.

## System roles

- `app/` — FadCam production client: camera, field capture, recording, monitoring and future producer controls.
- `platform/mediamtx/` — live ingest, routing and protocol gateway.
- `platform/ffmpeg/` — media processing, transcoding, packaging and automated clip generation.
- `platform/minio/` — object storage for source media, VOD, recordings, thumbnails, captions and production assets.
- `platform/peertube/` — audience publishing/VOD/live distribution foundation.

## Target flow

```text
FadCam -> MediaMTX -> FFmpeg -> MinIO
                    |             |
                    +-----------> PeerTube -> Fad audience apps/web/TV
```

The five components remain independently buildable. They are pinned as Git submodules so the FadCam repository becomes a reproducible integration point instead of copying or forking source into the Android application.

## Product layers to build above the media infrastructure

1. Fad Core — identity, organizations, channels, permissions and API.
2. Fad Studio — multi-camera production and control-room UI.
3. Fad Newsroom — assignments, stories, editorial review and publishing.
4. Fad Playout — scheduled linear TV, live overrides and automatic return-to-schedule.
5. Fad EPG — worldwide programme guide.
6. Fad Play — viewer applications for Android, Android TV and web.
7. Fad Ads / Payments — advertising, subscriptions, PPV and creator revenue sharing.
8. Fad Analytics — viewing, stream health, production and revenue analytics.

## Integration rules

- Never expose MediaMTX directly as the public viewer API.
- Keep media objects in MinIO; keep application metadata in a dedicated database/service.
- Treat FFmpeg as a worker, not as application state.
- Keep live ingest authenticated and private by default.
- Pin production deployments to immutable commits/tags rather than tracking moving branches.
- Every integration must have health checks and an end-to-end test: capture -> ingest -> process -> store -> publish -> playback.

## Current foundation pins

- FadCam: `c3d4e1b6950e004d939bc977d027ae2457671da4`
- MediaMTX: `3ad078489df76e3f1b28c635b179ed44961fb644`
- FFmpeg: `3acec0a1af2dda0a0838689b8b8649e7deb080a0`
- MinIO: `7aac2a2c5b7c882e68c1ce017d8256be2feea27f`
- PeerTube: `9cf034c43a099abe48696725061c375b2f7fd06a`

These pins are the initial integration snapshot. They should be advanced deliberately after compatibility/build tests pass.
