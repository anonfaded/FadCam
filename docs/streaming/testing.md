# Testing: Live HLS Streaming

## Quick Start

1. **Start recording** in FadCam
2. **Find device IP**: `adb shell ifconfig | grep inet`
3. **Open browser**: `http://DEVICE_IP:8080/`
4. **Watch stream** in embedded player

## Testing Methods

### Browser (hls.js player)
Navigate to `http://DEVICE_IP:8080/` - includes web-based HLS player with debug logs.

### VLC Player
- **Media** → **Open Network Stream**
- URL: `http://DEVICE_IP:8080/live.m3u8`
- Click **Play**

### FFmpeg
```bash
ffprobe http://DEVICE_IP:8080/live.m3u8
ffmpeg -i http://DEVICE_IP:8080/live.m3u8 -t 10 -c copy output.mp4
```

## Troubleshooting

| Problem | Cause | Solution |
|---------|-------|----------|
| 503 Service Unavailable | Init segment not ready | Wait 5-10 seconds |
| No connection | Wrong IP or network | Check device IP: `adb shell ifconfig` |
| Buffering/stuttering | Network latency | Check WiFi signal |
| No segments available | Circular buffer empty | Wait for 2+ fragments to buffer |

## Monitor Fragment Flow

```bash
adb logcat -s "RemoteStreamManager" | grep -E "Fragment|Init"
```

Should show:
```
📦 Received init segment: 1126 bytes
🎬 Fragment #1 buffered (1307 KB) [1/10 slots]
🎬 Fragment #2 buffered (1293 KB) [2/10 slots]
```

## Check M3U8 Format

```bash
curl http://DEVICE_IP:8080/live.m3u8
```

Should return valid HLS playlist with `#EXT-X-MAP` pointing to `/init.mp4`.

## Performance

- **Connection time:** <1 second
- **First frame:** 3-4 seconds (2 fragments + buffers)
- **Latency:** ~2000ms per fragment
- **Bandwidth:** ~2-3 Mbps per viewer
