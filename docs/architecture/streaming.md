# Live HLS Streaming Architecture

## How It Works

FadCam streams live video via HLS using a patched Media3 library that provides real-time fragment callbacks - no file polling needed.

```
Camera → GLRecordingPipeline → FragmentedMp4Muxer → Callback
                                                        ↓
                                              RemoteStreamManager
                                              (circular buffer)
                                                        ↓
                                              LiveM3U8Server
                                              (HTTP streaming)
                                                        ↓
                                              Browser/VLC Player
```

## Key Components

| Component | Purpose |
|-----------|---------|
| **FragmentedMp4MuxerWrapper** | Muxes video into fMP4 fragments with real-time callbacks |
| **ProcessedSegment** | Media3 object containing init segment or media fragment |
| **RemoteStreamManager** | Circular buffer (10 fragments) for HLS streaming |
| **LiveM3U8Server** | HTTP server serving M3U8 playlists and fragments |

## Fragment Flow

1. **Init Segment** (ftyp + moov) sent first
2. **Media Fragments** (moof + mdat) sent every 2 seconds
3. **Buffer** stores latest 10 fragments (~20 seconds of video)
4. **HTTP Server** serves `/live.m3u8` and `/seg-{id}.m4s`
5. **Player** streams video from device

## Streaming Quality

- **Codec:** H.264 video, AAC audio
- **Resolution:** 1080p @ 30fps
- **Fragment Duration:** 2 seconds
- **Latency:** 3-4 seconds (2x fragment + buffer)
- **Bitrate:** 8-12 Mbps

## Files

- `FragmentedMp4MuxerWrapper.java` - Muxer with callback integration
- `RemoteStreamManager.java` - Fragment buffer & HLS state
- `LiveM3U8Server.java` - HTTP endpoint for streaming
- `GLRecordingPipeline.java` - Camera → Muxer integration
