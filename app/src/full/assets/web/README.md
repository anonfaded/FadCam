# FadCam Live Streaming Web Interface

This folder contains the web interface for FadCam's HLS live streaming feature.

## Files

- **index.html** - Main streaming player interface with HLS.js

## Configuration

Edit the `CONFIG` object at the top of `index.html` to customize behavior:

```javascript
const CONFIG = {
    LOG_ENABLED: false,              // Enable console logging (set true for debugging)
    LOG_IMPORTANT_ONLY: true,        // Only log critical events (errors, state changes)
    STATUS_POLL_INTERVAL: 2000,      // Check server status every 2 seconds
    STATUS_POLL_FAST: 500,           // Fast polling during initialization (0.5s)
    ERROR_RETRY_DELAY: 3000,         // Retry after 3 seconds on error
    BUFFER_CHECK_INTERVAL: 5000,     // Monitor buffer health every 5 seconds
    MAX_STARTUP_WAIT: 10000,         // Max 10s initial buffering before playback
    MIN_BUFFER_LENGTH: 2,            // Minimum 2 seconds buffered before starting
};
```

## Features

### 🔇 Controlled Logging
- **Production Mode**: Logging disabled by default to prevent browser slowdown/crashes
- **Debug Mode**: Set `LOG_ENABLED: true` to see detailed logs
- **Important Only**: `LOG_IMPORTANT_ONLY: true` shows only critical events
- **Throttled Fragment Logs**: Only logs every 10th fragment instead of all

### 🎯 Smart Status Polling
- Checks server status BEFORE loading stream
- Fast polling (0.5s) during initialization
- Slower polling (2s) when stream is running
- Clear user feedback for each state:
  - ❌ Streaming Disabled
  - ⏸️ Recording Not Started
  - ⏳ Initializing (encoder starting up)
  - ⏳ Buffering Fragments (waiting for video data)
  - ✅ Stream Ready → Automatic playback

### 🛡️ Reliable Streaming (YouTube-like)
- **Initial Buffering**: Waits for 2 seconds of buffer before starting playback
- **Large Buffer**: Keeps 20-30 seconds buffered for smooth playback
- **Auto-Recovery**: Handles network errors, media errors, and manifest refresh issues
- **Buffer Monitoring**: Detects stuck streams and stalled playback
- **Graceful Retries**: Up to 5 recovery attempts with exponential backoff
- **Jump Over Holes**: Automatically skips small buffer discontinuities (< 0.5s)

### 📊 Buffer Settings (Security Camera Optimized)
```javascript
maxBufferLength: 20         // Keep 20 seconds buffered
maxMaxBufferLength: 30      // Max 30 seconds buffer
maxBufferSize: 60MB         // 60 MB memory limit
maxBufferHole: 0.5          // Skip holes < 0.5 seconds
liveSyncDurationCount: 3    // Stay 3 fragments behind live edge
```

### 🎨 Emoji Fix
- Uses proper font fallback for emoji rendering
- Wraps emoji in `<span class="emoji">` with correct font-family

## Troubleshooting

### Buffering Issues
1. **Increase buffer size**: Raise `MIN_BUFFER_LENGTH` to 3-4 seconds
2. **Reduce latency**: Lower `liveSyncDurationCount` to 2 (trade reliability for speed)
3. **Check network**: Monitor buffer drops in console (enable logging)

### Manifest Errors
- Usually auto-recovered by retry logic (4 attempts, 10s timeout)
- If persistent, check server-side fragment generation timing

### Browser Crashes
- Ensure `LOG_ENABLED: false` in production
- Reduce `maxBufferLength` if on low-memory device
- Close other tabs/apps

### No Autoplay
- Browser security blocks autoplay with audio
- Video starts muted (`muted` attribute) to allow autoplay
- If blocked, shows "⏸️ Click to Play" message

## Performance

- **CPU**: Minimal - HLS.js uses Web Workers for decoding
- **Memory**: ~60 MB buffer (configurable via `maxBufferSize`)
- **Network**: ~500 KB/s for 720p H.265 stream
- **Logging**: Disabled by default to prevent overhead

## Development

To enable full debugging:

```javascript
const CONFIG = {
    LOG_ENABLED: true,
    LOG_IMPORTANT_ONLY: false,
    // ... other settings
};
```

Then open browser DevTools (F12) to see:
- Status checks
- Fragment loading (every 10th)
- Buffer levels
- Error details
- Recovery attempts

## Integration

The HTML file is loaded by `LiveM3U8Server.java`:

```java
InputStream htmlStream = getClass().getResourceAsStream("/com/fadcam/streaming/web/index.html");
```

Changes to `index.html` require rebuilding the APK to take effect.
