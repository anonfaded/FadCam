# Media pipeline E2E

This test verifies the discovery/assertion half of the live pipeline against a running stack:

`FFmpeg synthetic publisher -> MediaMTX ingest -> Fad Gateway -> assertion`

The harness expects a synthetic FFmpeg process to publish the `e2e-test` path to MediaMTX using RTMP:

```bash
ffmpeg -re -f lavfi -i testsrc=size=640x360:rate=30 -f lavfi -i sine=frequency=1000 -c:v libx264 -preset ultrafast -tune zerolatency -c:a aac -f flv rtmp://localhost:1935/e2e-test
```

With Gateway and MediaMTX running, execute:

```bash
npm test
```

Environment variables:

- `GATEWAY_URL` (default `http://localhost:8081`)
- `MEDIAMTX_API` (default `http://localhost:9997`)
- `TEST_STREAM_PATH` (default `e2e-test`)
- `E2E_TIMEOUT_MS` (default `30000`)

The test deliberately verifies both sides: Gateway must discover the named path and MediaMTX's API must independently report the same path. It does not claim success unless a real publisher has created the stream.
