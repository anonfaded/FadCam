# FadCam forensic runtime audit

## Scope
This audit records the production wiring that must be verified from the Android application through Server Room and back to the viewer, plus the rest of the application feature surface.

## Server Room path
Camera/media producer -> RemoteStreamService -> RemoteStreamManager -> TransportController -> MediaTransport -> DIRECT or RELAY -> ServerRoomRelayAgent/RelaySessionController -> Server Room relay endpoint -> public gateway -> HLS/WebRTC viewer.

## Required invariants
- One authoritative transport state at a time.
- Relay authentication and device/session binding are mandatory.
- Relay sessions expire and abandoned requests are reclaimed.
- DIRECT failure transitions to RELAY without duplicate active transports.
- Relay recovery transitions back to DIRECT without dropping lifecycle ownership.
- Service destruction tears down manager, transport, relay session, HTTP server, and worker resources.
- Public gateway exposes viewer traffic only; control/API/metrics remain private.

## Evidence already established
- Android/platform validation, authenticated relay security, and native security workflows are green at the audited head.
- RTMP -> MediaMTX -> HLS validation is green.
- Release lint defects were fixed at their source rather than suppressed.

## Remaining production proof
CI must exercise the real production object graph, not only isolated relay protocol tests. The critical test is a real Server Room viewer session driven through Android's RemoteStreamService and the relay path, including DIRECT -> RELAY -> DIRECT recovery, stale-session cleanup, concurrent viewers, malformed requests, and shutdown.

## Other FadCam features
Audit each feature as a vertical slice: UI entry -> ViewModel/controller -> service/repository -> persistence/network/native layer -> lifecycle/error handling -> user-visible result. No feature is considered wired merely because its class exists.

Priority surfaces: recording/capture, background service lifecycle, camera/audio, local HTTP/HLS serving, Server Room, relay, QR pairing, authentication/session management, storage/export, media processing/Media3/FFmpeg/OpenCV, settings, notifications/accessibility integrations, and release/build variants.

## Gate
A feature is production-ready only when construction, runtime path, lifecycle teardown, failure behavior, security boundary, and automated validation are all demonstrated.
