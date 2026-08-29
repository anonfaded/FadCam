# FadCam 24/7 TV pipeline

This directory defines the server-side broadcast contract. Nothing here is a Gradle or Android dependency.

## Target flow

```text
FadCam live source
      |
      | RTMP publish
      v
MediaMTX /fadcam-ingest
      |
      | RTMP/RTSP live input
      v
ffplayout channel
      |
      | RTMP stream output
      v
MediaMTX /fadcam
      |
      +--> HLS  http://HOST:8888/fadcam/index.m3u8
      |
      +--> WebRTC http://HOST:8889/fadcam
      |
      +--> RTSP  rtsp://HOST:8554/fadcam
      |
      +--> SRT   srt://HOST:8890?streamid=read:... 
      v
Viewer / CDN
```

MediaMTX is deliberately the transport boundary. It can publish and read RTMP and can generate HLS and WebRTC from the same path. HLS is also suitable for CDN scaling. See the upstream MediaMTX documentation for the supported protocol matrix.

## Current implementation milestone

The first server milestone is to make ffplayout a persistent service instead of using the upstream `static.Dockerfile` as the runtime. The upstream static Dockerfile is a build/export image: its command builds the binary and Debian package and exits; it is not a long-running playout container.

The new `server/ffplayout-runtime/Dockerfile` downloads the pinned ffplayout v2.2.1 server release for amd64 or arm64, installs the required FFmpeg runtime, creates the `ffpu` service account, and starts the ffplayout HTTP/API service on port 8787.

## Bootstrap order

1. Start the TV profile:

   ```bash
   docker compose --profile tv up -d --build
   ```

2. Open ffplayout at `http://HOST:8787` and complete its first-time setup. ffplayout v2 requires a fresh configuration/database; do not reuse a v1 database.

3. Configure the channel media directory as `/media`.

4. Configure the channel's **stream output** to target:

   ```text
   rtmp://mediamtx:1935/fadcam
   ```

5. Keep the output codecs compatible with the MediaMTX/browser targets. H.264 video plus AAC audio is the safest first interoperability target.

6. Once ffplayout is publishing, verify the MediaMTX path through its control API and then open:

   ```text
   http://HOST:8888/fadcam/index.m3u8
   http://HOST:8889/fadcam
   ```

## FadCam live ingest milestone

The Android application does not currently contain an RTMP publisher. Therefore the server pipeline must **not** claim to be end-to-end until FadCam has a live-publish implementation.

The next Android milestone is a small, isolated publisher layer that takes the camera/audio output and publishes it to:

```text
rtmp://SERVER:1935/fadcam-ingest/<camera-id>
```

MediaMTX will then expose that source to the playout layer. The publisher must remain optional so recording and the normal APK build continue to work when streaming is disabled.

## Reliability requirements

The final 24/7 service is not complete until all of these survive a restart:

- ffplayout process restart
- MediaMTX restart
- host reboot
- missing media item
- empty/short playlist
- temporary publisher disconnect
- viewer disconnect/reconnect

The Docker services use `restart: unless-stopped`; application-level channel recovery still needs to be implemented and tested.
