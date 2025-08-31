package com.fadcam.ui.faditor.components;

import android.content.Context;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.processors.opengl.OpenGLVideoController;
import com.fadcam.ui.faditor.processors.opengl.VideoControllerListener;
import com.fadcam.ui.faditor.models.VideoMetadata;

/**
 * Professional video player component with OpenGL video system integration.
 * Handles video preview and playback for the editor using pure OpenGL rendering.
 * Requirements: 13.1, 13.7, 14.3
 */
public class VideoPlayerComponent extends FrameLayout {
    
    private static final String TAG = "VideoPlayerComponent";
    
    public interface VideoPlayerListener {
        void onPlaybackStateChanged(boolean isPlaying);
        void onPositionChanged(long positionMs);
        void onVideoLoaded(long durationMs);
        void onVideoError(String error);
    }
    
    private VideoPlayerListener listener;
    private Handler positionUpdateHandler;
    private Runnable positionUpdateRunnable;
    private boolean isInitialized = false;
    
    // OpenGL video system components
    private OpenGLVideoController openGLVideoController;
    private GLSurfaceView glSurfaceView;
    
    public VideoPlayerComponent(@NonNull Context context) {
        super(context);
        init();
    }
    
    public VideoPlayerComponent(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public VideoPlayerComponent(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    private void init() {
        setBackgroundColor(0xFF000000); // Black background
        
        Log.d(TAG, "Initializing VideoPlayerComponent with OpenGL video system");
        
        // Initialize OpenGL video system
        initializeOpenGLVideoSystem();
        
        // Initialize position update handler
        positionUpdateHandler = new Handler(Looper.getMainLooper());
        positionUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (listener != null && isInitialized) {
                    long position = getCurrentPosition();
                    listener.onPositionChanged(position);
                    
                    // Continue updating if still playing
                    if (isPlaying()) {
                        positionUpdateHandler.postDelayed(this, 100); // Update every 100ms
                    }
                }
            }
        };
        
        Log.d(TAG, "VideoPlayerComponent initialized with OpenGL video system");
    }
    
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        Log.d(TAG, "VideoPlayerComponent layout: " + (right - left) + "x" + (bottom - top));
        
        if (glSurfaceView != null) {
            Log.d(TAG, "GLSurfaceView visibility: " + glSurfaceView.getVisibility());
            Log.d(TAG, "GLSurfaceView size: " + glSurfaceView.getWidth() + "x" + glSurfaceView.getHeight());
        }
    }
    
    /**
     * Initialize the OpenGL video system for professional editing
     * This provides frame-accurate seeking and <50ms response times
     */
    private void initializeOpenGLVideoSystem() {
        try {
            Log.d(TAG, "Initializing OpenGL video system");
            
            // Create OpenGL video controller
            openGLVideoController = new OpenGLVideoController(getContext());
            
            // Set up listener for the controller
            openGLVideoController.setVideoControllerListener(new VideoControllerListener() {
                @Override
                public void onVideoLoaded(VideoMetadata metadata) {
                    Log.d(TAG, "OpenGL video loaded: " + metadata.getWidth() + "x" + metadata.getHeight() + 
                          ", duration: " + (metadata.getDuration() / 1000) + "ms");
                    isInitialized = true;
                    if (listener != null) {
                        listener.onVideoLoaded(metadata.getDuration() / 1000); // Convert to milliseconds
                    }
                }
                
                @Override
                public void onFrameRendered(long frameNumber, long timestampUs) {
                    // Frame rendered - can be used for performance monitoring
                    // This provides frame-accurate timing information
                }
                
                @Override
                public void onPlaybackStateChanged(boolean isPlaying) {
                    Log.d(TAG, "OpenGL playback state changed: " + isPlaying);
                    if (listener != null) {
                        listener.onPlaybackStateChanged(isPlaying);
                    }
                    
                    if (isPlaying) {
                        startPositionUpdates();
                    } else {
                        stopPositionUpdates();
                    }
                }
                
                @Override
                public void onSeekCompleted(long positionMs) {
                    Log.d(TAG, "OpenGL seek completed: " + positionMs + "ms");
                    if (listener != null) {
                        listener.onPositionChanged(positionMs);
                    }
                }
                
                @Override
                public void onPositionChanged(long positionMs) {
                    if (listener != null) {
                        listener.onPositionChanged(positionMs);
                    }
                }
                
                @Override
                public void onError(String error) {
                    Log.e(TAG, "OpenGL video system error: " + error);
                    if (listener != null) {
                        listener.onVideoError("OpenGL video error: " + error);
                    }
                }
                
                @Override
                public void onInitializationComplete() {
                    Log.d(TAG, "OpenGL video system initialization complete");
                }
            });
            
            // Create GLSurfaceView for OpenGL rendering
            glSurfaceView = new GLSurfaceView(getContext());
            glSurfaceView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ));
            
            // Initialize the controller with the surface view
            openGLVideoController.initialize(glSurfaceView);
            
            // Add the surface view to this component
            addView(glSurfaceView);
            
            Log.d(TAG, "OpenGL video system initialized successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize OpenGL video system", e);
            if (listener != null) {
                listener.onVideoError("Failed to initialize OpenGL video system: " + e.getMessage());
            }
        }
    }








    
    private void startPositionUpdates() {
        stopPositionUpdates(); // Stop any existing updates
        positionUpdateHandler.post(positionUpdateRunnable);
    }
    
    private void stopPositionUpdates() {
        positionUpdateHandler.removeCallbacks(positionUpdateRunnable);
    }
    
    public void setVideoPlayerListener(VideoPlayerListener listener) {
        this.listener = listener;
    }

    public void loadVideo(Uri videoUri) {
        Log.d(TAG, "Loading video with OpenGL video system: " + videoUri);

        if (videoUri == null) {
            Log.e(TAG, "Video URI is null");
            if (listener != null) {
                listener.onVideoError("Video URI is null");
            }
            return;
        }

        try {
            if (openGLVideoController != null) {
                Log.d(TAG, "Loading video with OpenGL video controller");
                openGLVideoController.loadVideo(videoUri);
            } else {
                Log.e(TAG, "OpenGL video controller not initialized");
                if (listener != null) {
                    listener.onVideoError("OpenGL video system not initialized");
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error loading video with OpenGL system", e);
            if (listener != null) {
                listener.onVideoError("Failed to load video: " + e.getMessage());
            }
        }
    }
    
    /**
     * Load video from project media asset (uses proxy if available for smooth editing)
     */
    public void loadProjectMedia(String projectId, String mediaId, 
                               com.fadcam.ui.faditor.persistence.ProjectManager projectManager) {
        Log.d(TAG, "Loading project media: " + mediaId + " from project: " + projectId);
        
        try {
            // Get the playback URI (proxy if available, otherwise original)
            Uri playbackUri = projectManager.getPlaybackUri(projectId, mediaId);
            
            if (playbackUri == null) {
                Log.e(TAG, "No playback URI found for media asset: " + mediaId);
                if (listener != null) {
                    listener.onVideoError("Media asset not found: " + mediaId);
                }
                return;
            }
            
            Log.d(TAG, "Using playback URI: " + playbackUri);
            loadVideo(playbackUri);
            
        } catch (Exception e) {
            Log.e(TAG, "Error loading project media", e);
            if (listener != null) {
                listener.onVideoError("Failed to load project media: " + e.getMessage());
            }
        }
    }


    
    public void seekTo(long positionMs) {
        Log.d(TAG, "Seek to: " + positionMs + "ms");

        if (openGLVideoController != null) {
            openGLVideoController.seekToTime(positionMs);
        } else {
            Log.w(TAG, "Cannot seek: OpenGL video controller not initialized");
        }
    }
    
    /**
     * Seek to specific frame for frame-accurate editing
     * Provides <50ms response time as per requirements
     */
    public void seekToFrame(long frameNumber) {
        Log.d(TAG, "Seek to frame: " + frameNumber);
        
        if (openGLVideoController != null) {
            openGLVideoController.seekToFrame(frameNumber);
        } else {
            Log.w(TAG, "Cannot seek to frame: OpenGL video controller not initialized");
        }
    }
    
    public void play() {
        Log.d(TAG, "Play requested");

        if (openGLVideoController != null) {
            openGLVideoController.play();
        } else {
            Log.w(TAG, "Cannot play: OpenGL video controller not initialized");
        }
    }
    
    public void pause() {
        Log.d(TAG, "Pause requested");

        if (openGLVideoController != null) {
            openGLVideoController.pause();
        } else {
            Log.w(TAG, "Cannot pause: OpenGL video controller not initialized");
        }
    }
    
    public long getCurrentPosition() {
        if (openGLVideoController != null) {
            return openGLVideoController.getCurrentPosition();
        }
        return 0;
    }
    
    /**
     * Get current frame number for frame-accurate editing
     */
    public long getCurrentFrame() {
        if (openGLVideoController != null) {
            return openGLVideoController.getCurrentFrame();
        }
        return 0;
    }
    
    public long getDuration() {
        if (openGLVideoController != null) {
            return openGLVideoController.getDuration();
        }
        return 0;
    }
    
    public boolean isPlaying() {
        if (openGLVideoController != null) {
            return openGLVideoController.isPlaying();
        }
        return false;
    }
    
    /**
     * Release the OpenGL video system when the component is destroyed
     */
    public void release() {
        Log.d(TAG, "Releasing VideoPlayerComponent");
        
        stopPositionUpdates();
        
        // Release OpenGL video controller
        if (openGLVideoController != null) {
            openGLVideoController.release();
            openGLVideoController = null;
        }
        
        // Remove GLSurfaceView
        if (glSurfaceView != null) {
            removeView(glSurfaceView);
            glSurfaceView = null;
        }
        
        isInitialized = false;
        Log.d(TAG, "VideoPlayerComponent released");
    }
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        release();
    }
}