# Design Document

## Overview

The Faditor Mini video editor will be implemented as a comprehensive replacement for the existing placeholder FaditorMiniFragment. The design leverages the existing FFmpeg AAR dependency and follows the app's established architectural patterns, including the use of ExoPlayer for video playback, Material Design 3 theming, and modular component organization.

The editor will provide a streamlined interface for basic video editing operations while maintaining consistency with FadCam's design system and user experience patterns.

## Architecture

### Component Structure
```
com.fadcam.ui.faditor/
├── FaditorMiniFragment.java (main fragment - updated)
├── components/
│   ├── VideoPlayerComponent.java (video preview and playback)
│   ├── TimelineComponent.java (video timeline with trim handles)
│   ├── ControlsComponent.java (play/pause/export controls)
│   └── ProgressComponent.java (processing progress overlay)
├── models/
│   ├── VideoProject.java (represents current editing session)
│   └── EditOperation.java (represents editing operations)
├── processors/
│   ├── OpenGLVideoProcessor.java (primary OpenGL-based video processing)
│   └── VideoExporter.java (manages export operations with MediaCodec)
├── opengl/
│   ├── VideoRenderer.java (OpenGL ES video rendering)
│   ├── VideoTexture.java (video frame texture management)
│   ├── ShaderProgram.java (OpenGL shader programs)
│   ├── GLVideoProcessor.java (GPU-accelerated video operations)
│   └── MediaCodecIntegration.java (hardware encoder integration)
└── utils/
    ├── VideoFileUtils.java (file operations and validation)
    ├── TimelineUtils.java (timeline calculations and formatting)
    └── PerformanceMonitor.java (performance tracking and optimization)
```

### Data Flow
1. **Video Selection**: User selects video → VideoFileUtils validates → VideoProject created
2. **Preview**: VideoProject loaded → VideoPlayerComponent displays with ExoPlayer + OpenGL rendering
3. **Editing**: User interacts with TimelineComponent → EditOperation created → VideoProject updated
4. **Processing**: User confirms operation → OpenGLVideoProcessor executes with GPU acceleration → Progress shown via ProgressComponent
5. **Export**: Processing complete → VideoExporter saves result with MediaCodec → Success feedback displayed

### Performance Architecture
- **Pure OpenGL Pipeline**: All video processing uses OpenGL ES for maximum performance
- **Smart Processing**: Choose between lossless stream copying and GPU-accelerated re-encoding
- **Memory Management**: Efficient texture management and frame buffer optimization
- **Background Processing**: Non-blocking GPU operations with progress feedback
- **MediaCodec Integration**: Hardware encoding for final output when re-encoding is needed

## Components and Interfaces

### FaditorMiniFragment (Updated)
**Purpose**: Main container fragment that orchestrates the video editing interface
**Key Responsibilities**:
- Manages fragment lifecycle and state
- Coordinates between child components
- Handles file picker integration
- Manages theme consistency with app design system

**Interface**:
```java
public class FaditorMiniFragment extends BaseFragment {
    private VideoPlayerComponent videoPlayer;
    private TimelineComponent timeline;
    private ControlsComponent controls;
    private ProgressComponent progressOverlay;
    private VideoProject currentProject;
    
    // Lifecycle methods
    public void onVideoSelected(Uri videoUri);
    public void onEditOperationRequested(EditOperation operation);
    public void onExportRequested(ExportSettings settings);
}
```

### VideoPlayerComponent
**Purpose**: Handles video preview and playback using ExoPlayer
**Key Responsibilities**:
- Video playback control (play/pause/seek)
- Displays current video frame
- Syncs with timeline position
- Handles video metadata display

**Interface**:
```java
public class VideoPlayerComponent {
    private ExoPlayer player;
    private StyledPlayerView playerView;
    
    public void loadVideo(Uri videoUri);
    public void seekTo(long positionMs);
    public void play();
    public void pause();
    public long getCurrentPosition();
    public long getDuration();
}
```

### TimelineComponent
**Purpose**: Interactive timeline with trim handles for video editing
**Key Responsibilities**:
- Displays video timeline with duration markers
- Provides draggable trim handles for start/end selection
- Shows current playback position
- Calculates and validates trim ranges

**Interface**:
```java
public class TimelineComponent extends View {
    private long videoDuration;
    private long trimStart;
    private long trimEnd;
    private long currentPosition;
    
    public void setVideoDuration(long duration);
    public void setTrimRange(long start, long end);
    public void setCurrentPosition(long position);
    public TrimRange getTrimRange();
}
```

### OpenGLVideoProcessor
**Purpose**: Primary video processing engine using OpenGL ES and MediaCodec
**Key Responsibilities**:
- GPU-accelerated video frame processing and rendering
- Lossless video trimming via stream copying when possible
- Hardware encoder integration (MediaCodec) for re-encoding operations
- Real-time preview rendering with smooth seeking

**Interface**:
```java
public class OpenGLVideoProcessor {
    public interface ProcessingCallback {
        void onProgress(int percentage);
        void onSuccess(File outputFile);
        void onError(String errorMessage);
    }
    
    public void trimVideoLossless(File inputFile, long startMs, long endMs, 
                                 File outputFile, ProcessingCallback callback);
    public void trimVideoWithReencoding(File inputFile, long startMs, long endMs, 
                                       File outputFile, ProcessingCallback callback);
    public boolean canProcessLossless(File videoFile, EditOperation operation);
}
```

### MediaCodecIntegration
**Purpose**: Handles hardware encoding integration with OpenGL
**Key Responsibilities**:
- Surface-to-surface encoding for GPU-processed frames
- Optimal encoder selection based on device capabilities
- Quality and performance optimization

**Interface**:
```java
public class MediaCodecIntegration {
    public void setupEncoder(VideoMetadata inputMetadata, File outputFile);
    public Surface getInputSurface();
    public void encodeFrame(long presentationTimeUs);
    public void finishEncoding();
}
```

## Data Models

### VideoProject
**Purpose**: Represents the current editing session state
```java
public class VideoProject {
    private Uri originalVideoUri;
    private File workingFile;
    private long duration;
    private VideoMetadata metadata;
    private List<EditOperation> operations;
    private TrimRange currentTrim;
    private ProcessingCapabilities capabilities;
    
    // Getters and setters
    public boolean hasUnsavedChanges();
    public void addOperation(EditOperation operation);
    public boolean canProcessLossless();
    public ProcessingMethod getOptimalProcessingMethod();
}
```

### EditOperation
**Purpose**: Represents a single editing operation
```java
public class EditOperation {
    public enum Type { TRIM }
    
    private Type type;
    private long startTime;
    private long endTime;
    private boolean requiresReencoding;
    private Map<String, Object> parameters;
    
    public boolean isLosslessCompatible();
}
```

### VideoMetadata
**Purpose**: Represents video file properties and encoding information
```java
public class VideoMetadata {
    private String codec;
    private int width;
    private int height;
    private long duration;
    private int bitrate;
    private float frameRate;
    private String colorFormat;
    
    public boolean isLosslessTrimCompatible();
    public boolean requiresReencoding(EditOperation operation);
}
```

## Error Handling

### Video Loading Errors
- **Unsupported Format**: Display user-friendly message with supported formats list
- **File Access**: Handle permission issues and provide guidance
- **Corrupted File**: Detect and report file integrity issues

### Processing Errors
- **FFmpeg Failures**: Parse FFmpeg error output and provide meaningful messages
- **Storage Issues**: Handle insufficient space and write permission errors
- **Cancellation**: Allow users to cancel long-running operations

### Recovery Mechanisms
- **Auto-save**: Periodically save project state
- **Temporary Files**: Clean up on app restart
- **Graceful Degradation**: Fallback to original video if processing fails

## Testing Strategy

### Unit Testing
- **FFmpegProcessor**: Mock FFmpeg operations and test command generation
- **TimelineUtils**: Test time calculations and formatting
- **VideoFileUtils**: Test file validation and metadata extraction

### Integration Testing
- **Video Loading**: Test with various video formats and sizes
- **Trim Operations**: Verify accuracy of trim operations
- **Export Process**: Test complete workflow from selection to export

### UI Testing
- **Timeline Interaction**: Test drag operations and position updates
- **Player Controls**: Verify play/pause/seek functionality
- **Progress Feedback**: Test progress indicators during processing

### Performance Testing
- **Large Files**: Test with 4K videos and 60+ minute durations
- **Memory Usage**: Monitor GPU memory and texture allocation efficiency
- **OpenGL Performance**: Verify consistent GPU acceleration across device ranges
- **Background Processing**: Test stability during GPU-accelerated operations
- **Lossless Operations**: Verify stream copying performance for compatible edits
- **Real-time Preview**: Measure frame rendering performance and seek times (target <100ms seeks)

## Design System Integration

### Theme Consistency
- Use existing theme attributes (`colorTopBar`, `colorButton`, etc.)
- Respect dark/light mode and custom theme colors
- Apply consistent typography using `@font/ubuntu_regular`

### Component Styling
- **Header Bar**: Match existing fragment headers with `colorTopBar` background
- **Buttons**: Use Material Design 3 button styles with theme colors
- **Progress Indicators**: Use app's existing progress bar styling
- **Timeline**: Custom component following Material Design guidelines

### Layout Patterns
- **Responsive Design**: Support different screen sizes and orientations
- **Accessibility**: Proper content descriptions and touch targets
- **Spacing**: Use consistent margins and padding from existing layouts

### String Resources
All user-facing text will use string resources for internationalization:
- `faditor_select_video` - "Select Video"
- `faditor_trim_video` - "Trim Video"
- `faditor_export_video` - "Export Video"
- `faditor_processing` - "Processing video..."
- `faditor_export_success` - "Video exported successfully"
- `faditor_error_unsupported_format` - "Unsupported video format"
- `faditor_error_processing_failed` - "Video processing failed"
#
# OpenGL Integration

### Hardware Acceleration Strategy
The editor will leverage the existing OpenGL infrastructure in FadCam for optimal performance:

1. **Lossless Trimming**: For simple trim operations, use stream copying to avoid re-encoding
2. **GPU Rendering**: Use OpenGL ES for real-time video preview and frame manipulation
3. **Hardware Encoding**: Integrate with MediaCodec for hardware-accelerated encoding when re-encoding is necessary
4. **Pure OpenGL Pipeline**: All processing uses GPU acceleration for maximum performance and consistency

### OpenGL Components

#### VideoRenderer
- Renders video frames to OpenGL textures
- Handles color space conversion and scaling
- Provides smooth seeking and playback

#### GLVideoProcessor
- Performs video operations directly on GPU
- Manages frame buffers and texture memory
- Coordinates with MediaCodec for encoding

#### Performance Optimizations
- **Texture Pooling**: Reuse texture objects to minimize allocation overhead
- **Asynchronous Processing**: Non-blocking GPU operations with callback notifications
- **Memory Management**: Efficient cleanup of GPU resources
- **Frame Skipping**: Intelligent frame dropping during heavy processing to maintain responsiveness

### Integration with Existing OpenGL Code
The editor will extend the existing OpenGL infrastructure in `com.fadcam.opengl` package, reusing established patterns for:
- Shader management and compilation
- Texture handling and memory management
- Surface rendering and display
- Performance monitoring and optimization