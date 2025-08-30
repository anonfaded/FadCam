# OpenGL Video Decoding Infrastructure

This package provides a comprehensive OpenGL-based video decoding infrastructure for the Faditor Mini video editor. The implementation focuses on hardware-accelerated video decoding with MediaCodec and OpenGL surface output for optimal performance.

## Components Overview

### Core Components

1. **VideoDecoder** - Main decoder class for MediaCodec hardware decoding
2. **DecoderCallback** - Interface for decoder event notifications
3. **VideoFormatValidator** - Utility for format validation and codec support detection
4. **VideoDecoderTest** - Comprehensive test suite for validation
5. **VideoDecoderExample** - Practical usage examples

## Key Features

- **Hardware Acceleration**: Uses MediaCodec with OpenGL surface output for zero-copy rendering
- **Frame-Accurate Seeking**: Supports precise seeking to specific frames or timestamps
- **Format Support**: Handles H.264, H.265, VP9, and other common video formats
- **Performance Optimized**: Designed for smooth 60fps timeline scrubbing and real-time preview
- **Error Handling**: Comprehensive error handling and recovery mechanisms
- **Metadata Extraction**: Extracts complete video metadata including resolution, duration, bitrate

## Usage Examples

### Basic Video Loading

```java
VideoDecoder decoder = new VideoDecoder();
decoder.setDecoderCallback(new DecoderCallback() {
    @Override
    public void onDecoderReady(int width, int height, long durationUs) {
        Log.d(TAG, "Video ready: " + width + "x" + height);
    }

    @Override
    public void onFrameAvailable(long presentationTimeUs) {
        // Frame is ready for rendering
    }

    @Override
    public void onError(String error) {
        Log.e(TAG, "Decoder error: " + error);
    }

    // ... other callback methods
});

try {
    decoder.initialize(videoUri, openglSurface);
    VideoMetadata metadata = decoder.getVideoMetadata();
} catch (IOException e) {
    Log.e(TAG, "Failed to initialize decoder: " + e.getMessage());
}
```

### Frame-Accurate Seeking

```java
// Seek to specific timestamp (in microseconds)
decoder.seekToTime(5_000_000); // Seek to 5 seconds

// Seek to specific frame number
decoder.seekToFrame(150); // Seek to frame 150

// Extract single frame for thumbnail
decoder.extractFrame(10_000_000); // Extract frame at 10 seconds
```

### Continuous Playback

```java
// Start continuous decoding for playback
decoder.startDecoding();

// Stop decoding
decoder.stopDecoding();

// Always release when done
decoder.release();
```

### Format Validation

```java
VideoFormatValidator.VideoFormatInfo formatInfo =
    VideoFormatValidator.validateVideoFile(videoUri);

if (formatInfo.isValid) {
    Log.d(TAG, "Format: " + formatInfo.mimeType);
    Log.d(TAG, "Resolution: " + formatInfo.getResolutionString());
    Log.d(TAG, "Hardware decoder: " + formatInfo.hasHardwareDecoder);
    Log.d(TAG, "Lossless operations: " + formatInfo.supportsLosslessOperations);
} else {
    Log.e(TAG, "Invalid format: " + formatInfo.errorMessage);
}
```

## Architecture Details

### Threading Model

- **Main Thread**: Initialization and public API calls
- **Decoder Thread**: Background thread for MediaCodec operations
- **Callback Thread**: Callbacks are executed on the decoder thread

### Memory Management

- Automatic cleanup of MediaCodec and MediaExtractor resources
- Proper surface management for OpenGL rendering
- Thread-safe resource release

### Performance Considerations

- **Zero-Copy Rendering**: Direct MediaCodec to OpenGL surface output
- **Hardware Acceleration**: Utilizes device GPU for decoding when available
- **Efficient Seeking**: Optimized seeking algorithm for frame-accurate positioning
- **Memory Optimization**: Minimal memory footprint with proper resource management

## Supported Formats

### Video Codecs

- H.264 (AVC) - Primary support with hardware acceleration
- H.265 (HEVC) - Modern codec with better compression
- VP9 - Web-optimized codec
- VP8 - Legacy web codec support
- MPEG-4 - Basic support
- H.263 - Legacy support

### Container Formats

- MP4 - Primary container with lossless operation support
- MOV - Apple container format
- M4V - iTunes video format
- AVI - Legacy container (limited support)
- MKV - Matroska container

## Error Handling

The decoder provides comprehensive error handling for common scenarios:

- **Unsupported Format**: Clear error messages for unsupported video formats
- **File Access**: Handles permission and file access issues
- **Hardware Limitations**: Graceful fallback when hardware acceleration is unavailable
- **Corrupted Files**: Detects and reports file integrity issues
- **Resource Exhaustion**: Proper handling of memory and resource constraints

## Testing

Use the provided test classes to validate functionality:

```java
// Run comprehensive tests
boolean allTestsPassed = VideoDecoderTest.runAllTests(videoUri, surface);

// Run specific tests
boolean initTest = VideoDecoderTest.testInitialization(videoUri, surface);
boolean seekTest = VideoDecoderTest.testSeeking(videoUri, surface);
boolean frameTest = VideoDecoderTest.testFrameExtraction(videoUri, surface);
```

## Integration with Faditor Mini

This decoder infrastructure is designed to integrate seamlessly with the Faditor Mini video editor:

1. **Timeline Component**: Provides frame-accurate seeking for timeline scrubbing
2. **Video Player**: Enables smooth playback with hardware acceleration
3. **Thumbnail Generation**: Supports efficient thumbnail extraction
4. **Export Pipeline**: Integrates with video processing for optimal performance

## Performance Benchmarks

Expected performance characteristics:

- **Initialization**: < 200ms for typical video files
- **Seeking**: < 50ms for frame-accurate seeks
- **Frame Extraction**: < 100ms for single frame extraction
- **Memory Usage**: < 50MB for 4K video processing
- **Timeline Scrubbing**: 60fps smooth scrubbing support

## Requirements Compliance

This implementation satisfies the following requirements:

- **Requirement 13.1**: Pure OpenGL ES rendering without ExoPlayer dependency
- **Requirement 13.6**: Common video format support (H.264, H.265, VP9)
- **Requirement 14.2**: MediaCodec hardware decoding with OpenGL surface output

## Future Enhancements

Planned improvements for future versions:

- **Multi-track Support**: Support for multiple video tracks
- **Audio Synchronization**: Audio track handling for complete A/V sync
- **Advanced Codecs**: Support for AV1 and other emerging codecs
- **HDR Support**: High Dynamic Range video processing
- **Variable Frame Rate**: Support for VFR video content

## Troubleshooting

Common issues and solutions:

1. **Initialization Fails**: Check video format compatibility and file permissions
2. **Seeking Inaccurate**: Verify video has proper keyframe structure
3. **Performance Issues**: Ensure hardware acceleration is available
4. **Memory Leaks**: Always call `release()` when decoder is no longer needed
5. **Threading Issues**: Ensure all operations are called from appropriate threads

For detailed examples and advanced usage patterns, refer to the `VideoDecoderExample` class.
