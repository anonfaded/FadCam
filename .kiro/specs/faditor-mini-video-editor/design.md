# Design Document

## Overview

The Faditor Mini video editor will be implemented as a comprehensive replacement for the existing placeholder FaditorMiniFragment. The design leverages the existing FFmpeg AAR dependency and follows the app's established architectural patterns, including the use of ExoPlayer for video playback, Material Design 3 theming, and modular component organization.

The editor will provide a streamlined interface for basic video editing operations while maintaining consistency with FadCam's design system and user experience patterns.

## Architecture

### Component Structure
```
com.fadcam.ui.faditor/
├── FaditorMiniFragment.java (project browser - main tab screen)
├── FaditorEditorFragment.java (dedicated full-screen editor)
├── components/
│   ├── VideoPlayerComponent.java (professional video preview with controls)
│   ├── TimelineComponent.java (professional timeline with zoom and scrubbing)
│   ├── ToolbarComponent.java (professional editing toolbar with Material 3 icons)
│   ├── ProjectBrowserComponent.java (project grid/list with recent projects)
│   ├── ProjectCreationComponent.java (new project creation interface)
│   ├── ControlsComponent.java (professional playback controls)
│   └── ProgressComponent.java (processing progress overlay)
├── models/
│   ├── VideoProject.java (JSON-serializable project with complete state)
│   ├── EditOperation.java (represents editing operations)
│   ├── ProjectMetadata.java (project info for browser and indexing)
│   ├── TimelineState.java (timeline zoom, position, and view state)
│   └── EditorState.java (complete editor state for auto-save/restore)
├── persistence/
│   ├── ProjectManager.java (JSON project persistence and management)
│   ├── ProjectDatabase.java (Room database for project indexing)
│   ├── ProjectSerializer.java (JSON serialization/deserialization)
│   ├── AutoSaveManager.java (handles continuous auto-saving)
│   └── MediaReferenceManager.java (manages media file references and paths)
├── processors/
│   ├── OpenGLVideoProcessor.java (primary OpenGL-based video processing)
│   └── VideoExporter.java (manages export operations with MediaCodec)
├── opengl/
│   ├── OpenGLVideoController.java (main video playback coordinator)
│   ├── VideoDecoder.java (MediaCodec hardware decoder wrapper)
│   ├── VideoRenderer.java (OpenGL ES frame rendering)
│   ├── TextureManager.java (texture pool and memory management)
│   ├── ShaderManager.java (shader compilation and management)
│   ├── SurfaceManager.java (OpenGL surface handling)
│   ├── FrameProcessor.java (frame manipulation and effects)
│   └── PlaybackController.java (playback timing and synchronization)
└── utils/
    ├── VideoFileUtils.java (file operations and validation)
    ├── TimelineUtils.java (timeline calculations and formatting)
    ├── PerformanceMonitor.java (performance tracking and optimization)
    ├── NavigationUtils.java (handles fragment navigation and transitions)
    └── UIUtils.java (Material 3 UI helpers and animations)
```

### Data Flow
1. **Project Browser**: User opens Faditor Mini tab → FaditorMiniFragment shows ProjectBrowserComponent with recent projects
2. **Project Selection**: User selects existing project or creates new → NavigationUtils opens FaditorEditorFragment in full-screen
3. **Editor Initialization**: FaditorEditorFragment loads → ProjectManager restores complete EditorState → All components initialized
4. **Video Loading**: VideoProject loaded → OpenGLVideoController initializes → VideoDecoder prepares MediaCodec → VideoRenderer sets up OpenGL surface
5. **Frame Rendering**: VideoDecoder outputs to OpenGL surface → TextureManager handles GPU textures → VideoRenderer displays frames
6. **Timeline Interaction**: User scrubs timeline → PlaybackController seeks to frame → VideoDecoder renders specific frame → Smooth GPU rendering
7. **Editing**: User interacts with professional timeline → EditOperation created → AutoSaveManager saves changes within 5 seconds
8. **Processing**: User confirms operation → OpenGLVideoProcessor executes with GPU acceleration → Progress shown via ProgressComponent
9. **Export**: Processing complete → VideoExporter saves result with MediaCodec → Project updated → Success feedback displayed
10. **Navigation**: User taps back/leave → AutoSaveManager saves immediately → NavigationUtils returns to FaditorMiniFragment
11. **Persistence**: All changes continuously auto-saved → Room database indexes projects → JSON files store complete project state

### Performance Architecture
- **Pure OpenGL Pipeline**: All video preview and playback uses OpenGL ES without ExoPlayer dependency
- **Hardware Decoding**: MediaCodec with direct OpenGL surface output for zero-copy frame rendering
- **Modular Components**: Clean separation of concerns with focused, maintainable components under 300 lines each
- **GPU Memory Management**: Efficient texture pooling and memory optimization for 4K video support
- **Frame-Accurate Seeking**: <50ms seek times with on-demand frame decoding and GPU rendering
- **Smart Processing**: Choose between lossless stream copying and GPU-accelerated re-encoding for exports

## Components and Interfaces

### FaditorMiniFragment (Project Browser)
**Purpose**: Main project browser screen shown in the Faditor Mini tab
**Key Responsibilities**:
- Displays recent projects with thumbnails and metadata
- Handles new project creation workflow
- Manages project import/export operations
- Provides project search and organization features

**Interface**:
```java
public class FaditorMiniFragment extends BaseFragment {
    private ProjectBrowserComponent projectBrowser;
    private ProjectCreationComponent projectCreation;
    private ProjectManager projectManager;
    
    // Project management
    public void onProjectSelected(String projectId);
    public void onNewProjectRequested();
    public void onProjectDeleted(String projectId);
    public void onProjectImported(File projectFile);
    
    // Navigation
    private void openEditor(String projectId);
    private void showProjectCreation();
}
```

### FaditorEditorFragment (Full-Screen Editor)
**Purpose**: Dedicated full-screen video editing interface
**Key Responsibilities**:
- Provides maximum screen space for professional editing
- Manages complete editor state and auto-saving
- Handles all video editing operations and tools
- Manages navigation back to project browser

**Interface**:
```java
public class FaditorEditorFragment extends BaseFragment {
    private VideoPlayerComponent videoPlayer;
    private TimelineComponent timeline;
    private ToolbarComponent toolbar;
    private ControlsComponent controls;
    private ProgressComponent progressOverlay;
    private AutoSaveManager autoSaveManager;
    private VideoProject currentProject;
    private EditorState editorState;
    
    // Editor lifecycle
    public void loadProject(String projectId);
    public void saveAndExit();
    public void onBackPressed(); // Handle back navigation
    
    // Editing operations
    public void onEditOperationRequested(EditOperation operation);
    public void onExportRequested(ExportSettings settings);
    public void onToolSelected(ToolbarComponent.Tool tool);
    
    // Auto-save integration
    private void scheduleAutoSave();
    private void performImmediateSave();
}
```

### AutoSaveManager
**Purpose**: Handles continuous auto-saving and state recovery
**Key Responsibilities**:
- Monitors editor state changes and triggers saves
- Provides immediate save on navigation or interruption
- Handles crash recovery and state restoration
- Manages save timing and performance optimization

**Interface**:
```java
public class AutoSaveManager {
    public interface AutoSaveListener {
        void onAutoSaveStarted();
        void onAutoSaveCompleted();
        void onAutoSaveError(String error);
    }
    
    private static final int AUTO_SAVE_DELAY_MS = 5000; // 5 seconds
    
    public void startAutoSave(VideoProject project, EditorState state);
    public void stopAutoSave();
    public void saveImmediately();
    public void scheduleAutoSave();
    public void setAutoSaveListener(AutoSaveListener listener);
    
    // State change detection
    public void onProjectModified();
    public void onTimelineChanged();
    public void onToolChanged();
}
```

### VideoPlayerComponent
**Purpose**: Handles video preview and playback using pure OpenGL rendering
**Key Responsibilities**:
- OpenGL-based video frame rendering and playback control
- Frame-accurate seeking with GPU acceleration
- Coordinates with MediaCodec for hardware decoding
- Manages OpenGL surface and texture rendering

**Interface**:
```java
public class VideoPlayerComponent {
    private OpenGLVideoController videoController;
    private GLSurfaceView glSurfaceView;
    private VideoDecoder decoder;
    private VideoRenderer renderer;
    
    public void loadVideo(Uri videoUri);
    public void seekToFrame(long frameNumber);
    public void seekToTime(long positionMs);
    public void play();
    public void pause();
    public long getCurrentPosition();
    public long getDuration();
    public int getTotalFrames();
    public void setFrameUpdateListener(FrameUpdateListener listener);
}
```

### TimelineComponent (Enhanced)
**Purpose**: Professional timeline with zoom, scrubbing, and frame-accurate editing
**Key Responsibilities**:
- Professional timeline display with zoom controls and frame markers
- Draggable trim handles with snap-to-frame precision
- Timeline scrubbing with smooth preview updates
- Multi-track support for future expansion
- Waveform visualization for audio tracks

**Interface**:
```java
public class TimelineComponent extends View {
    private long videoDuration;
    private long trimStart;
    private long trimEnd;
    private long currentPosition;
    private float zoomLevel;
    private TimelineState state;
    
    public void setVideoDuration(long duration);
    public void setTrimRange(long start, long end);
    public void setCurrentPosition(long position);
    public void setZoomLevel(float zoom);
    public void enableFrameSnapping(boolean enabled);
    public TrimRange getTrimRange();
    public TimelineState getState();
    public void restoreState(TimelineState state);
}
```

### ProjectBrowserComponent
**Purpose**: Professional project management interface
**Key Responsibilities**:
- Display grid/list of recent projects with thumbnails
- Project search and filtering capabilities
- Project creation, renaming, and deletion
- Import/export of .fadproj files
- Project metadata display (duration, last modified, etc.)

**Interface**:
```java
public class ProjectBrowserComponent extends RecyclerView {
    public interface ProjectBrowserListener {
        void onProjectSelected(VideoProject project);
        void onNewProjectRequested();
        void onProjectDeleted(String projectId);
        void onProjectRenamed(String projectId, String newName);
    }
    
    public void loadProjects();
    public void refreshProjects();
    public void setViewMode(ViewMode mode); // GRID, LIST
    public void setProjectBrowserListener(ProjectBrowserListener listener);
}
```

### ToolbarComponent
**Purpose**: Professional editing toolbar with Material 3 design
**Key Responsibilities**:
- Material 3 icon-based tool selection
- Tool state management and visual feedback
- Contextual tool options and settings
- Smooth animations and transitions

**Interface**:
```java
public class ToolbarComponent extends LinearLayout {
    public enum Tool { SELECT, TRIM, SPLIT, EFFECTS, AUDIO }
    
    public interface ToolbarListener {
        void onToolSelected(Tool tool);
        void onToolAction(Tool tool, Bundle parameters);
    }
    
    public void setSelectedTool(Tool tool);
    public void setToolEnabled(Tool tool, boolean enabled);
    public void setToolbarListener(ToolbarListener listener);
}
```

### ProjectManager
**Purpose**: Handles all project persistence and management operations
**Key Responsibilities**:
- JSON serialization/deserialization of projects
- File system management for project folders
- Room database operations for project indexing
- Media reference validation and path management

**Interface**:
```java
public class ProjectManager {
    public interface ProjectCallback {
        void onProjectSaved(String projectId);
        void onProjectLoaded(VideoProject project);
        void onError(String errorMessage);
    }
    
    public void saveProject(VideoProject project, ProjectCallback callback);
    public void loadProject(String projectId, ProjectCallback callback);
    public void deleteProject(String projectId, ProjectCallback callback);
    public void exportProject(String projectId, File exportPath, ProjectCallback callback);
    public void importProject(File projectFile, ProjectCallback callback);
    public LiveData<List<ProjectMetadata>> getRecentProjects();
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

### OpenGL Video Components

#### OpenGLVideoController
**Purpose**: Main coordinator for OpenGL-based video playback and rendering
**Key Responsibilities**:
- Orchestrates all OpenGL video components
- Manages playback state and timing
- Coordinates frame-accurate seeking and rendering

**Interface**:
```java
public class OpenGLVideoController {
    public interface VideoControllerListener {
        void onVideoLoaded(VideoMetadata metadata);
        void onFrameRendered(long frameNumber, long timestampUs);
        void onPlaybackStateChanged(boolean isPlaying);
        void onSeekCompleted(long positionMs);
        void onError(String error);
    }
    
    public void initialize(GLSurfaceView surfaceView);
    public void loadVideo(Uri videoUri);
    public void play();
    public void pause();
    public void seekToFrame(long frameNumber);
    public void seekToTime(long positionMs);
    public void release();
    public void setVideoControllerListener(VideoControllerListener listener);
}
```

#### VideoDecoder
**Purpose**: MediaCodec wrapper for hardware video decoding with OpenGL output
**Key Responsibilities**:
- Hardware-accelerated video decoding using MediaCodec
- Direct output to OpenGL surface for zero-copy rendering
- Frame-accurate seeking and extraction

**Interface**:
```java
public class VideoDecoder {
    public interface DecoderCallback {
        void onFrameAvailable(long presentationTimeUs);
        void onDecodingComplete();
        void onError(String error);
    }
    
    public void initialize(Uri videoUri, Surface outputSurface);
    public void seekToFrame(long frameNumber);
    public void extractFrame(long timestampUs);
    public VideoMetadata getVideoMetadata();
    public void release();
    public void setDecoderCallback(DecoderCallback callback);
}
```

#### VideoRenderer
**Purpose**: OpenGL ES rendering engine for video frames
**Key Responsibilities**:
- Renders video frames to OpenGL surface
- Handles texture transformations and scaling
- Manages rendering pipeline and frame presentation

**Interface**:
```java
public class VideoRenderer implements GLSurfaceView.Renderer {
    public void setVideoTexture(int textureId);
    public void updateFrame();
    public void setDisplaySize(int width, int height);
    public void setVideoSize(int width, int height);
    public void applyTransformation(float[] transformMatrix);
    public void release();
}
```

#### TextureManager
**Purpose**: Efficient GPU texture memory management
**Key Responsibilities**:
- Texture pool management for frame buffers
- GPU memory optimization and cleanup
- Texture binding and state management

**Interface**:
```java
public class TextureManager {
    public int createVideoTexture();
    public void bindTexture(int textureId);
    public void updateTexture(int textureId, byte[] frameData);
    public void releaseTexture(int textureId);
    public void releaseAll();
    public int getAvailableTextureMemory();
}
```

#### ShaderManager
**Purpose**: OpenGL shader compilation and management
**Key Responsibilities**:
- Compiles and caches video rendering shaders
- Manages shader programs and uniforms
- Provides optimized shaders for different video formats

**Interface**:
```java
public class ShaderManager {
    public int createVideoShaderProgram();
    public void useProgram(int programId);
    public void setUniform(int programId, String name, float[] values);
    public void setUniform(int programId, String name, int value);
    public void releaseProgram(int programId);
    public void releaseAll();
}
```

#### PlaybackController
**Purpose**: Manages playback timing and synchronization
**Key Responsibilities**:
- Synchronizes video frames with timeline position
- Handles playback speed and frame rate control
- Manages smooth seeking and scrubbing

**Interface**:
```java
public class PlaybackController {
    public interface PlaybackListener {
        void onPositionChanged(long positionMs);
        void onPlaybackSpeedChanged(float speed);
    }
    
    public void startPlayback();
    public void pausePlayback();
    public void setPlaybackSpeed(float speed);
    public void seekTo(long positionMs);
    public long getCurrentPosition();
    public void setPlaybackListener(PlaybackListener listener);
}
```

## Data Models

### VideoProject (Enhanced)
**Purpose**: JSON-serializable project with complete editing state
```java
public class VideoProject {
    private String projectId;
    private String projectName;
    private long createdAt;
    private long lastModified;
    private Uri originalVideoUri;
    private String originalVideoPath; // For JSON serialization
    private File workingFile;
    private long duration;
    private VideoMetadata metadata;
    private List<EditOperation> operations;
    private TrimRange currentTrim;
    private TimelineState timelineState;
    private ProcessingCapabilities capabilities;
    private Map<String, Object> customData; // For future extensions
    
    // JSON serialization
    public String toJson();
    public static VideoProject fromJson(String json);
    
    // State management
    public boolean hasUnsavedChanges();
    public void addOperation(EditOperation operation);
    public boolean canProcessLossless();
    public ProcessingMethod getOptimalProcessingMethod();
    public void updateLastModified();
    
    // Media reference management
    public boolean validateMediaReferences();
    public void updateMediaPaths(Map<String, String> pathMapping);
}
```

### ProjectMetadata
**Purpose**: Lightweight project info for browser and indexing
```java
@Entity(tableName = "projects")
public class ProjectMetadata {
    @PrimaryKey
    private String projectId;
    private String projectName;
    private String thumbnailPath;
    private long createdAt;
    private long lastModified;
    private long duration;
    private String originalVideoName;
    private long fileSize;
    private boolean hasUnsavedChanges;
    
    // Room database getters/setters
}
```

### TimelineState
**Purpose**: Preserves timeline view and interaction state
```java
public class TimelineState {
    private float zoomLevel;
    private long viewportStart;
    private long viewportEnd;
    private long playheadPosition;
    private boolean frameSnappingEnabled;
    private ViewMode viewMode; // OVERVIEW, DETAILED, FRAME_ACCURATE
    
    // State serialization for project persistence
    public JSONObject toJson();
    public static TimelineState fromJson(JSONObject json);
}
```

### EditorState
**Purpose**: Complete editor state for auto-save and restoration
```java
public class EditorState {
    private String selectedTool; // Current tool selection
    private TimelineState timelineState;
    private boolean isPlaying;
    private long lastPlayPosition;
    private Map<String, Object> toolSettings; // Tool-specific settings
    private List<String> undoStack;
    private List<String> redoStack;
    private long lastModified;
    private boolean hasUnsavedChanges;
    
    // Auto-save integration
    public void markModified();
    public boolean needsSaving();
    public JSONObject toJson();
    public static EditorState fromJson(JSONObject json);
    
    // State management
    public void updateFromEditor(FaditorEditorFragment editor);
    public void applyToEditor(FaditorEditorFragment editor);
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

## Project Persistence Architecture

### Storage Strategy (Hybrid Approach)
Following industry best practices similar to CapCut and other professional editors:

1. **JSON Project Files**: Each project stored as `project.json` with complete timeline data
2. **Room Database**: Fast indexing and searching of project metadata
3. **Media References**: URI-based references with path validation and recovery
4. **Project Folders**: Organized file structure for each project

### File Structure
```
/Android/data/com.fadcam/files/projects/
├── project_001/
│   ├── project.json          # Complete project data
│   ├── thumbnail.jpg         # Project thumbnail
│   ├── cache/               # Temporary processing files
│   └── exports/             # Exported videos
├── project_002/
│   └── ...
└── shared/
    ├── templates/           # Project templates
    └── presets/            # Export presets
```

### JSON Project Schema
```json
{
  "version": "1.0",
  "projectId": "uuid",
  "projectName": "My Video Edit",
  "createdAt": 1640995200000,
  "lastModified": 1640995800000,
  "media": {
    "originalVideo": {
      "uri": "content://...",
      "path": "/storage/...",
      "metadata": { "duration": 30000, "width": 1920, "height": 1080 }
    }
  },
  "timeline": {
    "duration": 30000,
    "trimStart": 5000,
    "trimEnd": 25000,
    "zoomLevel": 1.0,
    "viewportStart": 0,
    "operations": [
      {
        "type": "TRIM",
        "startTime": 5000,
        "endTime": 25000,
        "parameters": {}
      }
    ]
  },
  "settings": {
    "frameSnapping": true,
    "autoSave": true
  }
}
```

### Database Schema (Room)
```sql
CREATE TABLE projects (
    projectId TEXT PRIMARY KEY,
    projectName TEXT NOT NULL,
    thumbnailPath TEXT,
    createdAt INTEGER NOT NULL,
    lastModified INTEGER NOT NULL,
    duration INTEGER NOT NULL,
    originalVideoName TEXT,
    fileSize INTEGER,
    hasUnsavedChanges INTEGER DEFAULT 0
);

CREATE INDEX idx_projects_lastModified ON projects(lastModified DESC);
CREATE INDEX idx_projects_name ON projects(projectName);
```

### Auto-Save and Recovery
- **Auto-save**: Save project JSON every 30 seconds during editing
- **Crash Recovery**: Detect incomplete operations and offer recovery
- **Media Validation**: Check media file availability on project load
- **Path Recovery**: Handle moved/renamed media files with user assistance

## Professional UI Design System

### Material 3 Integration
- **Dynamic Color**: Support Material You theming with user wallpaper colors
- **Component Library**: Use Material 3 components (Sliders, FABs, Cards, etc.)
- **Motion**: Implement Material 3 motion patterns and transitions
- **Typography**: Use Material 3 typography scale with proper hierarchy

### Two-Screen Architecture

#### Project Browser Screen (FaditorMiniFragment)
```
┌─────────────────────────────────────────────────────────┐
│ Header Bar (Material 3 TopAppBar) + [New Project]     │
├─────────────────────────────────────────────────────────┤
│ Search Bar (Material 3 SearchView)                     │
├─────────────────────────────────────────────────────────┤
│                                                         │
│        Recent Projects Grid/List                       │
│   ┌─────────┐ ┌─────────┐ ┌─────────┐                 │
│   │ Project │ │ Project │ │ Project │                 │
│   │ Thumb   │ │ Thumb   │ │ Thumb   │                 │
│   │ Title   │ │ Title   │ │ Title   │                 │
│   │ Date    │ │ Date    │ │ Date    │                 │
│   └─────────┘ └─────────┘ └─────────┘                 │
│                                                         │
│ [Import Project] [Create New] [Templates]              │
├─────────────────────────────────────────────────────────┤
│ Bottom Navigation (App-wide)                           │
└─────────────────────────────────────────────────────────┘
```

#### Full-Screen Editor (FaditorEditorFragment)
```
┌─────────────────────────────────────────────────────────┐
│ Editor Header [← Back] [Project Name] [Save] [Export]  │
├─────────────────────────────────────────────────────────┤
│ Toolbar [Select] [Trim] [Split] [Effects] [Audio]     │
├─────────────────────────────────────────────────────────┤
│                                                         │
│           Video Preview Area (Full Width)              │
│        (Professional Player Controls)                   │
│                                                         │
├─────────────────────────────────────────────────────────┤
│ Timeline Area (Professional Timeline)                  │
│ ├─ Zoom Controls & Frame Counter                       │
│ ├─ Timeline Track with Waveform                       │
│ └─ Playhead and Trim Handles                          │
├─────────────────────────────────────────────────────────┤
│ Properties Panel (Context-sensitive tool options)     │
└─────────────────────────────────────────────────────────┘
```

### Icon System
- **Material Symbols**: Use Material Symbols for all tools and actions
- **Consistent Sizing**: 24dp icons with proper touch targets (48dp minimum)
- **State Indication**: Clear selected/active states with Material 3 state layers
- **Accessibility**: Proper content descriptions and semantic labels

### Animation and Feedback
- **Smooth Transitions**: Material 3 motion tokens for all state changes
- **Progress Indicators**: Linear and circular progress with proper timing
- **Haptic Feedback**: Subtle vibration for important interactions
- **Visual Feedback**: State layers and ripple effects for all interactive elements

### Testing Commands
For development and testing, use the following Gradle commands:
```bash
# Compile and install debug build
.\gradlew.bat compileDebugJavaWithJavac installDebug

# Run specific tests
.\gradlew.bat testDebugUnitTest

# Generate test coverage report
.\gradlew.bat jacocoTestReport
```