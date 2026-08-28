# Fad Media Gateway

The gateway is the only application-facing boundary for live media infrastructure.

## Rules

- FadCam never receives public MediaMTX credentials.
- MediaMTX remains private behind the gateway/network boundary.
- Stream identities are issued by Fad Core.
- Ingest is authenticated and scoped to a production/channel.
- Viewer clients receive application-level playback URLs, never internal infrastructure addresses.

## Target flow

```text
FadCam -> Fad Gateway -> MediaMTX -> FFmpeg/recording
                                      |
                                      -> PeerTube / FadPlay
```

Health checks and end-to-end stream tests are required before production deployment.
