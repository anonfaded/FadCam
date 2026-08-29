# FadCam camera → RTMP pipeline

The live publishing path is now split into three layers:

```text
FadCam camera + microphone
        ↓
RtmpPublisherService (foreground lifecycle)
        ↓
RtmpPublisher (RootEncoder)
        ↓
H.264 + AAC
        ↓
RTMP / RTMPS destination
        ├── YouTube
        ├── Twitch
        ├── Facebook
        └── Custom RTMP
```

`RtmpPublisherLauncher` is the application-facing entry point. It validates the endpoint, starts the foreground service on Android 8+, and keeps stream credentials out of persistent storage.

## Runtime requirements

Before calling `RtmpPublisherLauncher.start(...)`, the application must have granted:

- `CAMERA`
- `RECORD_AUDIO`
- `POST_NOTIFICATIONS` where required by the Android version

The foreground service is declared for the debug build as `camera|microphone` so the current Android debug validation and real-device milestone exercise the correct Android service model.

## Destination examples

```java
RtmpPublisherLauncher.start(
    context,
    RtmpDestination.YOUTUBE,
    serverUrl,
    streamKey);
```

The server URL should come from the platform's current live-control configuration. Do not hard-code rotating platform ingest URLs into the application.

## Next integration boundary

This milestone deliberately stops at the RTMP publication boundary. MediaMTX remains the server-side ingest/router, ffplayout remains the 24/7 playout layer, and MediaMTX remains the HLS/WebRTC output layer.

Reconnect, network-change recovery, credential vaulting, platform OAuth, and real-device end-to-end verification are subsequent milestones.
