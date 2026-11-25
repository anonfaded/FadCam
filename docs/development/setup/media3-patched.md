# Setup: Patched Media3 for Live HLS Streaming

FadCam uses a patched version of AndroidX Media3 for live HLS streaming support with real-time fragment callbacks.

## Quick Setup (Required for Building)

The patched Media3 library is required to build FadCam. Choose one option:

### Option 1: Use /tmp (Recommended for Linux/Mac)

```bash
git clone --depth 1 https://github.com/anonfaded/media3-patched.git /tmp/media3-patched
```

### Option 2: Custom Location

1. Clone to your preferred location:
```bash
git clone --depth 1 https://github.com/anonfaded/media3-patched.git ~/libs/media3-patched
```

2. Add to `local.properties`:
```properties
media3.patched.path=/home/youruser/libs/media3-patched
```

### Option 3: Windows Users

```bash
git clone --depth 1 https://github.com/anonfaded/media3-patched.git C:\libs\media3-patched
```

Then add to `local.properties`:
```properties
media3.patched.path=C:\\libs\\media3-patched
```

## What This Does

The patched Media3 library provides:
- **Real-time fragment callbacks** for live HLS streaming
- **ProcessedSegment API** that delivers fMP4 fragments as they're muxed
- **MSE-compliant output** for browser compatibility
- **No file polling needed** - fragments delivered via callback

## Build Verification

After cloning, build FadCam:
```bash
./gradlew assembleDefaultDebug installDefaultDebug
```

If you see warnings about Media3 not found, check your path configuration.

## Upstream Source

This is a fork of: https://github.com/albrast/media (feat/live-fragmented-muxer branch)

Changes are merged into the default branch of: https://github.com/anonfaded/media3-patched
