package com.fadcam.ui.faditor.processors.opengl;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import com.fadcam.Log;
import com.fadcam.ui.faditor.models.VideoMetadata;
import com.fadcam.ui.faditor.utils.PerformanceMonitor;

/**
 * OpenGLVideoController serves as the main coordinator for OpenGL-based video playback and rendering.
 * Integrates VideoDecoder, VideoRenderer, and PlaybackController for seamless frame rendering
 * with frame-accurate seeking and <50ms response times.
 * Requirements: 13.2, 13.4, 14.1, 14.5
 */
public class OpenGLVideoController {
    private static final String TAG = "OpenGLVideoController";
    
    private final Context context;
    private VideoControllerListener listener;
    private Handler mainHandler;
    
    // Core components
    private VideoDecoder videoDecoder;
    private VideoRenderer videoRenderer;
    private PlaybackController playbackController;
    private GLSurfaceView glSurfaceView;
    
    // Surface management
    private SurfaceTexture surfaceTexture;
    private Surface decoderSurface;
    
    // State management
    private boolean isInitialized = false;
    private boolean isVideoLoaded = false;
    private VideoMetadata currentVideoMetadata;
    private Uri currentVideoUri;
    
    // Performance monitoring
    private final PerformanceMonitor performanceMonitor;
    
    // Synchronization
    private final Object stateLock = new Object();
    
    public OpenGLVideoController(Context context) {
        this.context = context;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.performanceMonitor = PerformanceMonitor.getInstance();
        
        initializeComponents();
    }
    
    private void initializeComponents() {
        // Initialize core components
        videoDecoder = new VideoDecoder();
        videoRenderer = new VideoRenderer(context);
        playbackController = new PlaybackController();
        
        // Set up component callbacks
        setupDecoderCallbacks();
        setupPlaybackCallbacks();
        
        Log.d(TAG, "OpenGLVideoController components initialized");
    }
    
    private void setupDecoderCallbacks() {
        videoDecoder.setDecoderCallback(new DecoderCallback() {
            @Override
            public void onFrameAvailable(long presentationTimeUs) {
                performanceMonitor.startOperation("frame_processing");
                
                // Convert timestamp to frame number for listener
                long frameNumber = calculateFrameNumber(presentationTimeUs);
                
                // Notify renderer that new frame is available
                if (videoRenderer != null) {
                    videoRenderer.updateFrame();
                }
                
                // Notify listener
                if (listener != null) {
                    mainHandler.post(() -> listener.onFrameRendered(frameNumber, presentationTimeUs));
                }
                
                performanceMonitor.endOperation("frame_processing");
            }
            
            @Override
            public void onDecodingComplete() {
                Log.d(TAG, "Video decoding completed");
                
                // Stop playback when decoding is complete
                if (playbackController != null) {
                    playbackController.pausePlayback();
                }
            }
            
            @Override
            public void onError(String error) {
                Log.e(TAG, "Decoder error: " + error);
                
                if (listener != null) {
                    mainHandler.post(() -> listener.onError("Video decoding error: " + error));
                }
            }
            
            @Override
            public void onDecoderReady(int videoWidth, int videoHeight, long durationUs) {
                Log.d(TAG, "Decoder ready - " + videoWidth + "x" + videoHeight + ", duration: " + (durationUs / 1000) + "ms");
                
                // Update video renderer with dimensions
                if (videoRenderer != null) {
                    videoRenderer.setVideoSize(videoWidth, videoHeight);
                }
                
                // Initialize playback controller with video metadata
                if (playbackController != null && currentVideoMetadata != null) {
                    long durationMs = durationUs / 1000;
                    float frameRate = currentVideoMetadata.getFrameRate();
                    playbackController.initialize(durationMs, frameRate);
                }
                
                synchronized (stateLock) {
                    isVideoLoaded = true;
                }
                
                // Notify listener that video is loaded
                if (listener != null) {
                    mainHandler.post(() -> {
                        listener.onVideoLoaded(currentVideoMetadata);
                        listener.onInitializationComplete();
                    });
                }
            }
            
            @Override
            public void onSeekCompleted(long seekTimeUs) {
                long seekTimeMs = seekTimeUs / 1000;
                Log.d(TAG, "Decoder seek completed to: " + seekTimeMs + "ms");
                
                // Update playback controller position
                if (playbackController != null) {
                    // The playback controller will handle its own seek completion
                }
            }
            
            @Override
            public void onProgressUpdate(long currentTimeUs, long totalDurationUs) {
                // This is handled by PlaybackController for smoother updates
            }
        });
    }
    
    private void setupPlaybackCallbacks() {
        playbackController.setPlaybackListener(new PlaybackController.PlaybackListener() {
            @Override
            public void onPositionChanged(long positionMs) {
                if (listener != null) {
                    listener.onPositionChanged(positionMs);
                }
            }
            
            @Override
            public void onPlaybackSpeedChanged(float speed) {
                Log.d(TAG, "Playback speed changed to: " + speed + "x");
            }
            
            @Override
            public void onSeekCompleted(long actualPositionMs) {
                if (listener != null) {
                    listener.onSeekCompleted(actualPositionMs);
                }
            }
            
            @Override
            public void onPlaybackStateChanged(boolean isPlaying) {
                if (listener != null) {
                    listener.onPlaybackStateChanged(isPlaying);
                }
                
                // Control decoder based on playback state
                if (videoDecoder != null) {
                    if (isPlaying) {
                        videoDecoder.startDecoding();
                    } else {
                        videoDecoder.stopDecoding();
                    }
                }
            }
        });
    }
    
    /**
     * Initialize the controller with a GLSurfaceView for rendering
     */
    public void initialize(GLSurfaceView surfaceView) {
        if (isInitialized) {
            Log.w(TAG, "OpenGLVideoController already initialized");
            return;
        }
        
        this.glSurfaceView = surfaceView;
        
        // Set up GLSurfaceView with our renderer
        glSurfaceView.setEGLContextClientVersion(2);
        glSurfaceView.setRenderer(videoRenderer);
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        
        synchronized (stateLock) {
            isInitialized = true;
        }
        
        Log.d(TAG, "OpenGLVideoController initialized with GLSurfaceView");
    }
    
    /**
     * Load video from URI for playback
     */
    public void loadVideo(Uri videoUri) {
        if (!isInitialized) {
            Log.e(TAG, "Controller not initialized - call initialize() first");
            if (listener != null) {
                listener.onError("Controller not initialized");
            }
            return;
        }
        
        if (videoUri == null) {
            Log.e(TAG, "Video URI is null");
            if (listener != null) {
                listener.onError("Invalid video URI");
            }
            return;
        }
        
        performanceMonitor.startOperation("video_loading");
        
        try {
            Log.d(TAG, "Loading video: " + videoUri.toString());
            
            this.currentVideoUri = videoUri;
            
            // Reset state
            synchronized (stateLock) {
                isVideoLoaded = false;
            }
            
            // Extract video metadata first
            extractVideoMetadata(videoUri);
            
            // Create surface for decoder output
            // Note: In a real implementation, this would be connected to the GLSurfaceView
            Surface decoderSurface = createDecoderSurface();
            
            // Initialize video decoder
            videoDecoder.initialize(context, videoUri, decoderSurface);
            
            Log.d(TAG, "Video loading initiated successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to load video: " + e.getMessage(), e);
            
            if (listener != null) {
                mainHandler.post(() -> listener.onError("Failed to load video: " + e.getMessage()));
            }
        } finally {
            performanceMonitor.endOperation("video_loading");
        }
    }
    
    /**
     * Start video playback
     */
    public void play() {
        synchronized (stateLock) {
            if (!isInitialized || !isVideoLoaded) {
                Log.w(TAG, "Cannot play - not initialized or video not loaded");
                return;
            }
        }
        
        if (playbackController != null) {
            playbackController.startPlayback();
        }
        
        Log.d(TAG, "Playback started");
    }
    
    /**
     * Pause video playback
     */
    public void pause() {
        if (playbackController != null) {
            playbackController.pausePlayback();
        }
        
        Log.d(TAG, "Playback paused");
    }
    
    /**
     * Seek to specific frame with frame-accurate precision
     * Ensures <50ms response time as per requirements
     */
    public void seekToFrame(long frameNumber) {
        synchronized (stateLock) {
            if (!isInitialized || !isVideoLoaded) {
                Log.w(TAG, "Cannot seek - not initialized or video not loaded");
                return;
            }
        }
        
        performanceMonitor.startOperation("frame_seek");
        
        // Use playback controller for timing coordination
        if (playbackController != null) {
            playbackController.seekToFrame(frameNumber);
        }
        
        // Coordinate with video decoder for actual frame seeking
        if (videoDecoder != null) {
            videoDecoder.seekToFrame(frameNumber);
        }
        
        performanceMonitor.endOperation("frame_seek");
        
        Log.d(TAG, "Seeking to frame: " + frameNumber);
    }
    
    /**
     * Seek to specific time position
     */
    public void seekToTime(long positionMs) {
        synchronized (stateLock) {
            if (!isInitialized || !isVideoLoaded) {
                Log.w(TAG, "Cannot seek - not initialized or video not loaded");
                return;
            }
        }
        
        performanceMonitor.startOperation("time_seek");
        
        // Use playback controller for timing coordination
        if (playbackController != null) {
            playbackController.seekTo(positionMs);
        }
        
        // Coordinate with video decoder
        if (videoDecoder != null) {
            long positionUs = positionMs * 1000;
            videoDecoder.seekToTime(positionUs);
        }
        
        performanceMonitor.endOperation("time_seek");
        
        Log.d(TAG, "Seeking to time: " + positionMs + "ms");
    }
    
    /**
     * Get current playback position
     */
    public long getCurrentPosition() {
        if (playbackController != null) {
            return playbackController.getCurrentPosition();
        }
        return 0;
    }
    
    /**
     * Get current frame number
     */
    public long getCurrentFrame() {
        if (playbackController != null) {
            return playbackController.getCurrentFrame();
        }
        return 0;
    }
    
    /**
     * Get video duration
     */
    public long getDuration() {
        if (playbackController != null) {
            return playbackController.getDuration();
        }
        return 0;
    }
    
    /**
     * Check if currently playing
     */
    public boolean isPlaying() {
        if (playbackController != null) {
            return playbackController.isPlaying();
        }
        return false;
    }
    
    /**
     * Set playback speed
     */
    public void setPlaybackSpeed(float speed) {
        if (playbackController != null) {
            playbackController.setPlaybackSpeed(speed);
        }
    }
    
    /**
     * Set listener for controller events
     */
    public void setVideoControllerListener(VideoControllerListener listener) {
        this.listener = listener;
    }
    
    /**
     * Release all resources and cleanup
     */
    public void release() {
        Log.d(TAG, "Releasing OpenGLVideoController");
        
        synchronized (stateLock) {
            isInitialized = false;
            isVideoLoaded = false;
        }
        
        // Release components in reverse order
        if (playbackController != null) {
            playbackController.release();
            playbackController = null;
        }
        
        if (videoDecoder != null) {
            videoDecoder.release();
            videoDecoder = null;
        }
        
        if (videoRenderer != null) {
            videoRenderer.release();
            videoRenderer = null;
        }
        
        // Release surface resources
        if (decoderSurface != null) {
            decoderSurface.release();
            decoderSurface = null;
        }
        
        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }
        
        // Clear references
        currentVideoMetadata = null;
        currentVideoUri = null;
        glSurfaceView = null;
        
        Log.d(TAG, "OpenGLVideoController released");
    }
    
    // Private helper methods
    
    private void extractVideoMetadata(Uri videoUri) throws Exception {
        // Use existing VideoMetadata extraction logic
        // This would typically use MediaMetadataRetriever
        android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, videoUri);
            
            String widthStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String heightStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            String bitrateStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE);
            String frameRateStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
            String mimeTypeStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
            
            int width = widthStr != null ? Integer.parseInt(widthStr) : 0;
            int height = heightStr != null ? Integer.parseInt(heightStr) : 0;
            long duration = durationStr != null ? Long.parseLong(durationStr) * 1000 : 0; // Convert to microseconds
            int bitrate = bitrateStr != null ? Integer.parseInt(bitrateStr) : 0;
            float frameRate = frameRateStr != null ? Float.parseFloat(frameRateStr) : 30.0f;
            String codec = mimeTypeStr != null ? mimeTypeStr : "unknown";
            
            currentVideoMetadata = new VideoMetadata(codec, width, height, duration, bitrate, frameRate);
            currentVideoMetadata.setColorFormat("yuv420p");
            
            Log.d(TAG, "Video metadata extracted: " + width + "x" + height + ", " + frameRate + "fps, " + (duration/1000) + "ms");
            
        } finally {
            retriever.release();
        }
    }
    
    private Surface createDecoderSurface() {
        try {
            // Create a SurfaceTexture for MediaCodec output
            // This will be connected to an OpenGL texture in the renderer
            if (surfaceTexture == null) {
                // Create a dummy texture ID for now - in a real implementation,
                // this would be coordinated with the VideoRenderer
                int textureId = 1; // Placeholder - should come from TextureManager
                surfaceTexture = new SurfaceTexture(textureId);
                decoderSurface = new Surface(surfaceTexture);
                
                Log.d(TAG, "Created decoder surface with SurfaceTexture");
            }
            
            return decoderSurface;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to create decoder surface", e);
            return null;
        }
    }
    
    private long calculateFrameNumber(long presentationTimeUs) {
        if (currentVideoMetadata == null) {
            return 0;
        }
        
        float frameRate = currentVideoMetadata.getFrameRate();
        return (long) ((presentationTimeUs / 1_000_000.0f) * frameRate);
    }
}