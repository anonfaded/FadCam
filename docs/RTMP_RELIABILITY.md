# Reliable RTMP publishing

The RTMP publisher now has three reliability boundaries:

```text
Camera + microphone
      ↓
RtmpPublisherService
      ↓
Keystore-backed credential vault
      ↓
RTMP / RTMPS
      ↓
MediaMTX / YouTube / Twitch / Facebook
```

## Credential handling

`RtmpCredentialVault` encrypts the server URL and stream key with AES-GCM using an Android Keystore AES key. SharedPreferences contains only ciphertext, IV, destination profile and the active credential alias. The stream key is never put into the start Intent for the new launcher API.

Use:

```java
RtmpPublisherLauncher.saveCredentials(
    context, "mediamtx", RtmpDestination.CUSTOM,
    "rtmp://server:1935/fadcam", streamKey);
RtmpPublisherLauncher.start(context, "mediamtx");
```

The legacy overload remains for source compatibility but immediately moves the supplied secret into the vault and starts by alias.

## Reconnect

Transient publisher failures use bounded exponential backoff: 1s, 2s, 4s, 8s, 16s, 32s, then up to 60s, with a maximum of eight attempts. A successful connection resets the retry budget. Authentication errors stop retries because repeatedly sending invalid credentials is not useful.

## Network recovery

The foreground service registers a default Android network callback. Loss of the active network stops the current RTMP publisher and waits for a usable network. A validated network-route change tears down the old publisher and schedules one reconnect. All reconnect work is generation-guarded so stale delayed callbacks cannot start a second publisher.

## Process restart

The service persists only the active credential alias. On a `START_STICKY` service recreation it reloads the encrypted credential record and reconstructs the endpoint in memory. The secret itself is never persisted in plaintext.

## Real-device validation gate

The implementation is ready for the physical-device test, but a GitHub Actions build cannot prove camera, microphone, carrier/Wi-Fi transition and actual MediaMTX playback on hardware. The required acceptance path is:

```text
Android phone
    ↓
Camera + microphone
    ↓
H.264 + AAC
    ↓
RtmpPublisherService
    ↓
RTMP
    ↓
MediaMTX ingest
    ↓
HLS / WebRTC
    ↓
Viewer
```

Test a deliberate Wi-Fi loss, recovery, and Wi-Fi ↔ cellular transition. Verify that MediaMTX sees one active publisher after recovery and that playback resumes without starting a duplicate service/publisher.

## Next platform milestone

After this reliability layer is verified on a physical device, implement platform authentication for YouTube, Twitch and Facebook. Multi-destination publishing should follow only after one stable RTMP session is proven end-to-end.
