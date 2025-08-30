package com.fadcam.ui.faditor.components;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.StyledPlayerView;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;

/**
 * Professional video player component with ExoPlayer integration.
 * Handles video preview and playback for the editor.
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
    private ExoPlayer exoPlayer;
    private StyledPlayerView playerView;
    private Handler positionUpdateHandler;
    private Runnable positionUpdateRunnable;
    private boolean isInitialized = false;
    
    // OpenGL rendering support
    private boolean useOpenGL = false;
    private OpenGLVideoPlayerComponent openGLPlayer;
    
    // New OpenGL video controller integration
    private com.fadcam.ui.faditor.processors.opengl.OpenGLVideoController openGLVideoController;
    
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
        
        // Determine rendering mode based on device capabilities
        useOpenGL = shouldUseOpenGL(getContext());
        Log.d(TAG, "Using OpenGL rendering: " + useOpenGL);
        
        if (useOpenGL) {
            initOpenGLPlayer();
        } else {
            initializePlayer();
        }
        
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
        
        Log.d(TAG, "VideoPlayerComponent initialized with " + (useOpenGL ? "OpenGL" : "ExoPlayer") + " rendering");
    }
    
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        Log.d(TAG, "VideoPlayerComponent layout: " + (right - left) + "x" + (bottom - top));
        
        if (playerView != null) {
            Log.d(TAG, "PlayerView visibility: " + playerView.getVisibility());
            Log.d(TAG, "PlayerView size: " + playerView.getWidth() + "x" + playerView.getHeight());
        }
    }
    
    /**
     * Enable or disable OpenGL rendering for better performance
     */
    public void setUseOpenGL(boolean useOpenGL) {
        if (this.useOpenGL == useOpenGL) {
            return; // No change
        }

        Log.d(TAG, "Switching rendering mode to: " + (useOpenGL ? "OpenGL" : "ExoPlayer"));

        this.useOpenGL = useOpenGL;

        // If we're already initialized, we need to reinitialize
        if (isInitialized) {
            release();
            init();
        }
    }
    
    /**
     * Enable the new OpenGL video controller for professional editing
     * This provides frame-accurate seeking and <50ms response times
     */
    public void enableOpenGLVideoController(boolean enable) {
        if (enable && openGLVideoController == null) {
            Log.d(TAG, "Enabling OpenGL video controller for professional editing");
            initializeOpenGLVideoController();
        } else if (!enable && openGLVideoController != null) {
            Log.d(TAG, "Disabling OpenGL video controller");
            releaseOpenGLVideoController();
        }
    }
    
    private void initializeOpenGLVideoController() {
        try {
            openGLVideoController = new com.fadcam.ui.faditor.processors.opengl.OpenGLVideoController(getContext());
            
            // Set up listener for the new controller
            openGLVideoController.setVideoControllerListener(new com.fadcam.ui.faditor.processors.opengl.VideoControllerListener() {
                @Override
                public void onVideoLoaded(com.fadcam.ui.faditor.models.VideoMetadata metadata) {
                    Log.d(TAG, "OpenGL controller - video loaded: " + metadata.getWidth() + "x" + metadata.getHeight());
                    isInitialized = true;
                    if (listener != null) {
                        listener.onVideoLoaded(metadata.getDuration() / 1000); // Convert to milliseconds
                    }
                }
                
                @Override
                public void onFrameRendered(long frameNumber, long timestampUs) {
                    // Frame rendered - can be used for performance monitoring
                }
                
                @Override
                public void onPlaybackStateChanged(boolean isPlaying) {
                    Log.d(TAG, "OpenGL controller - playback state changed: " + isPlaying);
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
                    Log.d(TAG, "OpenGL controller - seek completed: " + positionMs + "ms");
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
                    Log.e(TAG, "OpenGL controller error: " + error);
                    if (listener != null) {
                        listener.onVideoError("OpenGL video error: " + error);
                    }
                }
                
                @Override
                public void onInitializationComplete() {
                    Log.d(TAG, "OpenGL controller initialization complete");
                }
            });
            
            // Create GLSurfaceView for the controller
            android.opengl.GLSurfaceView glSurfaceView = new android.opengl.GLSurfaceView(getContext());
            glSurfaceView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ));
            
            // Initialize the controller with the surface view
            openGLVideoController.initialize(glSurfaceView);
            
            // Add the surface view to this component
            addView(glSurfaceView);
            
            Log.d(TAG, "OpenGL video controller initialized successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize OpenGL video controller", e);
            openGLVideoController = null;
        }
    }
    
    private void releaseOpenGLVideoController() {
        if (openGLVideoController != null) {
            openGLVideoController.release();
            openGLVideoController = null;
        }
    }

    /**
     * Check if OpenGL rendering should be used based on device capabilities
     */
    public static boolean shouldUseOpenGL(Context context) {
        try {
            // Check OpenGL ES version
            android.app.ActivityManager activityManager =
                (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

            // Check if device has enough memory for OpenGL rendering
            android.app.ActivityManager.MemoryInfo memoryInfo = new android.app.ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(memoryInfo);

            // Require at least 1GB of RAM for OpenGL rendering
            boolean hasEnoughMemory = memoryInfo.totalMem >= 1024 * 1024 * 1024;

            // Check if device supports OpenGL ES 2.0
            // This is a basic check - in production you'd want more thorough testing
            boolean supportsOpenGL = true; // Assume support for now

            Log.d(TAG, "Device memory: " + (memoryInfo.totalMem / 1024 / 1024) + "MB, OpenGL support: " + supportsOpenGL);

            return hasEnoughMemory && supportsOpenGL;

        } catch (Exception e) {
            Log.w(TAG, "Error checking OpenGL capabilities", e);
            return false;
        }
    }

    private void initOpenGLPlayer() {
        try {
            Log.d(TAG, "Initializing OpenGL video player...");

            openGLPlayer = new OpenGLVideoPlayerComponent(getContext());
            openGLPlayer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ));

            openGLPlayer.setVideoPlayerListener(new OpenGLVideoPlayerComponent.OpenGLVideoPlayerListener() {
                @Override
                public void onPlaybackStateChanged(boolean isPlaying) {
                    Log.d(TAG, "OpenGL player playback state changed: " + isPlaying);
                    if (listener != null) {
                        listener.onPlaybackStateChanged(isPlaying);
                    }
                }

                @Override
                public void onPositionChanged(long positionMs) {
                    Log.d(TAG, "OpenGL player position changed: " + positionMs);
                    if (listener != null) {
                        listener.onPositionChanged(positionMs);
                    }
                }

                @Override
                public void onVideoLoaded(long durationMs) {
                    Log.d(TAG, "OpenGL player video loaded, duration: " + durationMs);
                    isInitialized = true;
                    if (listener != null) {
                        listener.onVideoLoaded(durationMs);
                    }
                }

                @Override
                public void onVideoError(String error) {
                    Log.e(TAG, "OpenGL player error: " + error);

                    // If OpenGL fails, try to fall back to ExoPlayer
                    if (useOpenGL && !fallbackAttempted) {
                        Log.w(TAG, "OpenGL player failed, attempting fallback to ExoPlayer");
                        switchToExoPlayer();
                        return; // Don't propagate the error yet
                    }

                    if (listener != null) {
                        listener.onVideoError(error);
                    }
                }

                @Override
                public void onFrameRendered() {
                    // Frame rendered callback - can be used for performance monitoring
                }
            });

            addView(openGLPlayer);
            Log.d(TAG, "OpenGL video player initialized successfully");

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize OpenGL player, falling back to ExoPlayer", e);
            useOpenGL = false;
            initializePlayer();
        }
    }

    /**
     * Switch from OpenGL to ExoPlayer as fallback
     */
    private void switchToExoPlayer() {
        try {
            Log.d(TAG, "Switching from OpenGL to ExoPlayer fallback");

            // Clean up OpenGL player
            if (openGLPlayer != null) {
                removeView(openGLPlayer);
                openGLPlayer.release();
                openGLPlayer = null;
            }

            // Switch to ExoPlayer
            useOpenGL = false;

            // Initialize ExoPlayer
            initializePlayer();

            // Retry loading the current video if available
            if (currentVideoUri != null) {
                Log.d(TAG, "Retrying video load with ExoPlayer");
                loadVideoWithExoPlayer(currentVideoUri);
            }

            Log.d(TAG, "Successfully switched to ExoPlayer");

        } catch (Exception e) {
            Log.e(TAG, "Failed to switch to ExoPlayer", e);
            if (listener != null) {
                listener.onVideoError("Failed to initialize fallback player: " + e.getMessage());
            }
        }
    }

    /**
     * Switch from ExoPlayer to OpenGL
     */
    private void switchToOpenGL() {
        try {
            Log.d(TAG, "Switching from ExoPlayer to OpenGL");

            // Clean up ExoPlayer
            if (exoPlayer != null) {
                exoPlayer.release();
                exoPlayer = null;
            }
            if (playerView != null) {
                removeView(playerView);
                playerView = null;
            }

            // Switch to OpenGL
            useOpenGL = true;

            // Initialize OpenGL player
            initOpenGLPlayer();

            // Retry loading the current video if available
            if (currentVideoUri != null && openGLPlayer != null) {
                Log.d(TAG, "Retrying video load with OpenGL");
                openGLPlayer.loadVideo(currentVideoUri);
            }

            Log.d(TAG, "Successfully switched to OpenGL");

        } catch (Exception e) {
            Log.e(TAG, "Failed to switch to OpenGL", e);
            if (listener != null) {
                listener.onVideoError("Failed to initialize OpenGL player: " + e.getMessage());
            }
        }
    }

    private void initializePlayer() {
        try {
            // Create ExoPlayer instance
            exoPlayer = new ExoPlayer.Builder(getContext()).build();
            
            // Create StyledPlayerView
            playerView = new StyledPlayerView(getContext());
            playerView.setPlayer(exoPlayer);
            playerView.setUseController(false); // We'll use custom controls
            playerView.setShowBuffering(StyledPlayerView.SHOW_BUFFERING_WHEN_PLAYING);
            playerView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ));
            
            // Ensure proper video scaling
            playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
            
            // Add player view to this container
            addView(playerView);
            
            Log.d(TAG, "Player view added to container with dimensions: " + 
                  getWidth() + "x" + getHeight());
            
            // Set up player listener
            exoPlayer.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    String stateString = playbackState == Player.STATE_IDLE ? "IDLE" :
                                       playbackState == Player.STATE_BUFFERING ? "BUFFERING" :
                                       playbackState == Player.STATE_READY ? "READY" :
                                       playbackState == Player.STATE_ENDED ? "ENDED" : "UNKNOWN";
                    Log.d(TAG, "Playback state changed: " + stateString + " (" + playbackState + ")");
                    Log.d(TAG, "PlayWhenReady: " + exoPlayer.getPlayWhenReady());
                    
                    if (listener != null) {
                        boolean isPlaying = playbackState == Player.STATE_READY && exoPlayer.getPlayWhenReady();
                        Log.d(TAG, "Notifying listener - isPlaying: " + isPlaying);
                        listener.onPlaybackStateChanged(isPlaying);
                        
                        // Start/stop position updates
                        if (isPlaying) {
                            startPositionUpdates();
                        } else {
                            stopPositionUpdates();
                        }
                    }
                    
                    // Handle video loaded state
                    if (playbackState == Player.STATE_READY && !isInitialized) {
                        isInitialized = true;
                        if (listener != null) {
                            listener.onVideoLoaded(exoPlayer.getDuration());
                        }
                        Log.d(TAG, "Video loaded, duration: " + exoPlayer.getDuration() + "ms");
                    }
                }
                
                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    Log.d(TAG, "Is playing changed: " + isPlaying);
                    Log.d(TAG, "Current playback state: " + exoPlayer.getPlaybackState());
                    
                    if (listener != null) {
                        Log.d(TAG, "Notifying listener via onIsPlayingChanged - isPlaying: " + isPlaying);
                        listener.onPlaybackStateChanged(isPlaying);
                    }
                    
                    if (isPlaying) {
                        startPositionUpdates();
                    } else {
                        stopPositionUpdates();
                    }
                }
                
                @Override
                public void onPlayerError(PlaybackException error) {
                    Log.e(TAG, "ExoPlayer error: " + error.getMessage(), error);

                    // If ExoPlayer fails and we haven't tried OpenGL yet, attempt fallback
                    if (!useOpenGL && !fallbackAttempted) {
                        Log.w(TAG, "ExoPlayer failed, attempting fallback to OpenGL");
                        switchToOpenGL();
                        return; // Don't propagate the error yet
                    }

                    if (listener != null) {
                        listener.onVideoError("Playback error: " + error.getMessage());
                    }
                }
            });
            
            Log.d(TAG, "ExoPlayer initialized successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize ExoPlayer", e);
            if (listener != null) {
                listener.onVideoError("Failed to initialize player: " + e.getMessage());
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
    
    // Track current video URI for fallback retry
    private Uri currentVideoUri = null;
    private boolean fallbackAttempted = false;

    public void loadVideo(Uri videoUri) {
        Log.d(TAG, "Loading video: " + videoUri);

        if (videoUri == null) {
            Log.e(TAG, "Video URI is null");
            if (listener != null) {
                listener.onVideoError("Video URI is null");
            }
            return;
        }

        // Store URI for potential fallback retry
        currentVideoUri = videoUri;
        fallbackAttempted = false;

        try {
            // Validate URI - basic check for now
            if (videoUri == null) {
                throw new IllegalArgumentException("Video URI cannot be null");
            }

            if (openGLVideoController != null) {
                Log.d(TAG, "Loading video with OpenGL video controller");
                openGLVideoController.loadVideo(videoUri);
            } else if (useOpenGL && openGLPlayer != null) {
                Log.d(TAG, "Loading video with OpenGL player");
                openGLPlayer.loadVideo(videoUri);
            } else if (exoPlayer != null) {
                Log.d(TAG, "Loading video with ExoPlayer");
                loadVideoWithExoPlayer(videoUri);
            } else {
                Log.e(TAG, "No player available to load video");
                if (listener != null) {
                    listener.onVideoError("No player available");
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error loading video", e);
            if (listener != null) {
                listener.onVideoError("Failed to load video: " + e.getMessage());
            }
        }
    }

    /**
     * Load video with ExoPlayer
     */
    private void loadVideoWithExoPlayer(Uri videoUri) {
        // Reset initialization flag
        isInitialized = false;

        // Stop any current playback
        exoPlayer.stop();
        exoPlayer.clearMediaItems();

        // Create media source
        DefaultDataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(getContext());
        MediaSource mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(videoUri));

        // Set media source and prepare
        exoPlayer.setMediaSource(mediaSource);
        exoPlayer.prepare();

        // Ensure the player view is visible and properly sized
        if (playerView != null) {
            playerView.setVisibility(View.VISIBLE);
            playerView.requestLayout();
            Log.d(TAG, "Player view made visible and layout requested");
        }

        Log.d(TAG, "Video loading started with ExoPlayer");
    }

    /**
     * Retry loading video with fallback player
     */
    private void retryLoadWithFallback() {
        if (currentVideoUri == null || fallbackAttempted) {
            Log.w(TAG, "Cannot retry: no URI or already attempted fallback");
            return;
        }

        Log.d(TAG, "Retrying video load with fallback player");
        fallbackAttempted = true;

        if (useOpenGL) {
            // Switch to ExoPlayer
            switchToExoPlayer();
            // Load will be triggered by the switch completion
        } else {
            // Already using ExoPlayer, try OpenGL
            switchToOpenGL();
            // Load will be triggered by the switch completion
        }
    }
    
    public void seekTo(long positionMs) {
        Log.d(TAG, "Seek to: " + positionMs + "ms");

        if (openGLVideoController != null) {
            openGLVideoController.seekToTime(positionMs);
        } else if (useOpenGL && openGLPlayer != null) {
            openGLPlayer.seekTo(positionMs);
        } else if (exoPlayer != null) {
            exoPlayer.seekTo(positionMs);
            
            // Immediately notify listener of position change
            if (listener != null) {
                listener.onPositionChanged(positionMs);
            }
        }
    }
    
    /**
     * Seek to specific frame for frame-accurate editing
     */
    public void seekToFrame(long frameNumber) {
        Log.d(TAG, "Seek to frame: " + frameNumber);
        
        if (openGLVideoController != null) {
            openGLVideoController.seekToFrame(frameNumber);
        } else {
            // Fallback to time-based seeking
            // Assume 30fps for conversion (would be better to get actual frame rate)
            long positionMs = (long) ((frameNumber / 30.0f) * 1000);
            seekTo(positionMs);
        }
    }
    
    public void play() {
        Log.d(TAG, "Play requested");

        if (openGLVideoController != null) {
            openGLVideoController.play();
        } else if (useOpenGL && openGLPlayer != null) {
            openGLPlayer.play();
        } else if (exoPlayer != null) {
            Log.d(TAG, "Starting playback - current state: " + exoPlayer.getPlaybackState());
            Log.d(TAG, "Current PlayWhenReady: " + exoPlayer.getPlayWhenReady());
            exoPlayer.setPlayWhenReady(true);
            Log.d(TAG, "PlayWhenReady set to true");
        }
    }
    
    public void pause() {
        Log.d(TAG, "Pause requested");

        if (openGLVideoController != null) {
            openGLVideoController.pause();
        } else if (useOpenGL && openGLPlayer != null) {
            openGLPlayer.pause();
        } else if (exoPlayer != null) {
            Log.d(TAG, "Pausing playback - current state: " + exoPlayer.getPlaybackState());
            Log.d(TAG, "Current PlayWhenReady: " + exoPlayer.getPlayWhenReady());
            exoPlayer.setPlayWhenReady(false);
            Log.d(TAG, "PlayWhenReady set to false");
        }
    }
    
    public long getCurrentPosition() {
        if (openGLVideoController != null) {
            return openGLVideoController.getCurrentPosition();
        } else if (useOpenGL && openGLPlayer != null) {
            return openGLPlayer.getCurrentPosition();
        } else if (exoPlayer != null) {
            return exoPlayer.getCurrentPosition();
        }
        return 0;
    }
    
    /**
     * Get current frame number for frame-accurate editing
     */
    public long getCurrentFrame() {
        if (openGLVideoController != null) {
            return openGLVideoController.getCurrentFrame();
        } else {
            // Fallback calculation
            long positionMs = getCurrentPosition();
            return (long) ((positionMs / 1000.0f) * 30.0f); // Assume 30fps
        }
    }
    
    public long getDuration() {
        if (openGLVideoController != null) {
            return openGLVideoController.getDuration();
        } else if (useOpenGL && openGLPlayer != null) {
            return openGLPlayer.getDuration();
        } else if (exoPlayer != null) {
            long duration = exoPlayer.getDuration();
            return duration != com.google.android.exoplayer2.C.TIME_UNSET ? duration : 0;
        }
        return 0;
    }
    
    public boolean isPlaying() {
        if (openGLVideoController != null) {
            return openGLVideoController.isPlaying();
        } else if (useOpenGL && openGLPlayer != null) {
            return openGLPlayer.isPlaying();
        } else if (exoPlayer != null) {
            return exoPlayer.isPlaying();
        }
        return false;
    }
    
    /**
     * Release the player when the component is destroyed
     */
    public void release() {
        Log.d(TAG, "Releasing VideoPlayerComponent");
        
        stopPositionUpdates();
        
        // Release new OpenGL video controller
        releaseOpenGLVideoController();
        
        if (useOpenGL && openGLPlayer != null) {
            openGLPlayer.release();
            removeView(openGLPlayer);
            openGLPlayer = null;
        }
        
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
        
        if (playerView != null) {
            removeView(playerView);
            playerView = null;
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