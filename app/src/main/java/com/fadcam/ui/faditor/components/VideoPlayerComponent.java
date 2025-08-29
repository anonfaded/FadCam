package com.fadcam.ui.faditor.components;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.video.VideoSize;
import com.google.android.exoplayer2.ui.StyledPlayerView;

import com.fadcam.opengl.grafika.FullFrameRect;
import com.fadcam.opengl.grafika.GlUtil;
import com.fadcam.opengl.grafika.Texture2dProgram;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Video player component for the Faditor Mini editor.
 * Handles video preview and playback using ExoPlayer with OpenGL rendering.
 * Integrates with existing OpenGL infrastructure for hardware acceleration.
 */
public class VideoPlayerComponent extends FrameLayout {
    
    private static final String TAG = "VideoPlayerComponent";
    
    private ExoPlayer player;
    private GLSurfaceView glSurfaceView;
    private VideoRenderer renderer;
    private VideoPlayerListener listener;
    private Handler mainHandler;
    
    // Playback state
    private boolean isInitialized = false;
    private boolean isPlaying = false;
    private long currentPosition = 0;
    private long duration = 0;
    private Uri currentVideoUri;
    
    // Performance monitoring
    private long lastSeekTime = 0;
    private long seekStartTime = 0;
    
    // Position update tracking
    private final Runnable positionUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (player != null && isPlaying && listener != null) {
                long newPosition = player.getCurrentPosition();
                if (Math.abs(newPosition - currentPosition) > 100) { // Update if changed by >100ms
                    currentPosition = newPosition;
                    listener.onPositionChanged(currentPosition);
                }
                mainHandler.postDelayed(this, 100); // Update every 100ms for smooth seeking
            }
        }
    };
    
    public interface VideoPlayerListener {
        void onPositionChanged(long positionMs);
        void onDurationChanged(long durationMs);
        void onPlaybackStateChanged(boolean isPlaying);
        void onError(String errorMessage);
        void onVideoSizeChanged(int width, int height);
    }
    
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
        mainHandler = new Handler(Looper.getMainLooper());
        
        // Create GLSurfaceView for OpenGL rendering
        glSurfaceView = new GLSurfaceView(getContext());
        glSurfaceView.setEGLContextClientVersion(2);
        
        // Create renderer with OpenGL integration
        renderer = new VideoRenderer();
        glSurfaceView.setRenderer(renderer);
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        
        // Add GLSurfaceView to this container
        addView(glSurfaceView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        
        // Initialize ExoPlayer
        initializePlayer();
        
        Log.d(TAG, "VideoPlayerComponent initialized with OpenGL rendering");
    }
    
    private void initializePlayer() {
        if (player != null) {
            return;
        }
        
        try {
            player = new ExoPlayer.Builder(getContext()).build();
            
            // Set up player listeners
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    boolean wasPlaying = isPlaying;
                    isPlaying = playbackState == Player.STATE_READY && player.getPlayWhenReady();
                    
                    if (wasPlaying != isPlaying && listener != null) {
                        listener.onPlaybackStateChanged(isPlaying);
                    }
                    
                    // Start/stop position updates
                    if (isPlaying) {
                        mainHandler.post(positionUpdateRunnable);
                    } else {
                        mainHandler.removeCallbacks(positionUpdateRunnable);
                    }
                    
                    Log.d(TAG, "Playback state changed: " + playbackState + ", playing: " + isPlaying);
                }
                
                @Override
                public void onPlayerError(PlaybackException error) {
                    Log.e(TAG, "ExoPlayer error: " + error.getMessage(), error);
                    if (listener != null) {
                        listener.onError("Playback error: " + error.getMessage());
                    }
                }
                
                @Override
                public void onVideoSizeChanged(VideoSize videoSize) {
                    Log.d(TAG, "Video size changed: " + videoSize.width + "x" + videoSize.height);
                    if (listener != null) {
                        listener.onVideoSizeChanged(videoSize.width, videoSize.height);
                    }
                    
                    // Update renderer with new video dimensions
                    if (renderer != null) {
                        renderer.setVideoSize(videoSize.width, videoSize.height);
                    }
                }
            });
            
            isInitialized = true;
            Log.d(TAG, "ExoPlayer initialized successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize ExoPlayer", e);
            if (listener != null) {
                listener.onError("Failed to initialize video player: " + e.getMessage());
            }
        }
    }
    
    /**
     * Load a video from the given URI
     */
    public void loadVideo(Uri videoUri) {
        if (!isInitialized || player == null) {
            Log.e(TAG, "Player not initialized");
            if (listener != null) {
                listener.onError("Player not initialized");
            }
            return;
        }
        
        try {
            currentVideoUri = videoUri;
            
            // Create media item and set to player
            MediaItem mediaItem = MediaItem.fromUri(videoUri);
            player.setMediaItem(mediaItem);
            player.prepare();
            
            // Set up video output surface
            if (renderer != null && renderer.getVideoSurface() != null) {
                player.setVideoSurface(renderer.getVideoSurface());
                Log.d(TAG, "Video surface set to ExoPlayer");
            }
            
            // Update duration when ready
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    if (playbackState == Player.STATE_READY) {
                        duration = player.getDuration();
                        if (listener != null && duration > 0) {
                            listener.onDurationChanged(duration);
                        }
                        Log.d(TAG, "Video loaded, duration: " + duration + "ms");
                    }
                }
            });
            
            Log.d(TAG, "Video loaded: " + videoUri.toString());
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to load video", e);
            if (listener != null) {
                listener.onError("Failed to load video: " + e.getMessage());
            }
        }
    }
    
    /**
     * Seek to a specific position in the video
     * Optimized for <100ms response time as per requirements
     */
    public void seekTo(long positionMs) {
        if (player == null) {
            Log.w(TAG, "Cannot seek: player not initialized");
            return;
        }
        
        try {
            // Performance monitoring - start timing
            seekStartTime = System.currentTimeMillis();
            
            // Clamp position to valid range
            long clampedPosition = Math.max(0, Math.min(positionMs, duration));
            
            // Use ExoPlayer's optimized seeking
            player.seekTo(clampedPosition);
            currentPosition = clampedPosition;
            
            // Trigger immediate render update for responsive seeking
            if (glSurfaceView != null) {
                glSurfaceView.requestRender();
            }
            
            // Notify listener immediately for UI responsiveness
            if (listener != null) {
                listener.onPositionChanged(currentPosition);
            }
            
            // Performance monitoring - measure seek time
            long seekTime = System.currentTimeMillis() - seekStartTime;
            if (seekTime > 100) {
                Log.w(TAG, "Seek took " + seekTime + "ms (target: <100ms)");
            } else {
                Log.d(TAG, "Seek completed in " + seekTime + "ms (position: " + clampedPosition + "ms)");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to seek", e);
            if (listener != null) {
                listener.onError("Seek failed: " + e.getMessage());
            }
        }
    }
    
    /**
     * Start video playback
     */
    public void play() {
        if (player == null) {
            Log.w(TAG, "Cannot play: player not initialized");
            return;
        }
        
        try {
            player.setPlayWhenReady(true);
            Log.d(TAG, "Playback started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start playback", e);
            if (listener != null) {
                listener.onError("Failed to start playback: " + e.getMessage());
            }
        }
    }
    
    /**
     * Pause video playback
     */
    public void pause() {
        if (player == null) {
            Log.w(TAG, "Cannot pause: player not initialized");
            return;
        }
        
        try {
            player.setPlayWhenReady(false);
            Log.d(TAG, "Playback paused");
        } catch (Exception e) {
            Log.e(TAG, "Failed to pause playback", e);
            if (listener != null) {
                listener.onError("Failed to pause playback: " + e.getMessage());
            }
        }
    }
    
    /**
     * Get current playback position
     */
    public long getCurrentPosition() {
        if (player != null) {
            currentPosition = player.getCurrentPosition();
        }
        return currentPosition;
    }
    
    /**
     * Get video duration
     */
    public long getDuration() {
        if (player != null && player.getDuration() > 0) {
            duration = player.getDuration();
        }
        return duration;
    }
    
    /**
     * Check if video is currently playing
     */
    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }
    
    /**
     * Check if a video is loaded and ready for playback
     */
    public boolean isVideoLoaded() {
        return player != null && player.getPlaybackState() != Player.STATE_IDLE && currentVideoUri != null;
    }
    
    /**
     * Get the currently loaded video URI
     */
    public Uri getCurrentVideoUri() {
        return currentVideoUri;
    }
    
    /**
     * Toggle play/pause state
     */
    public void togglePlayPause() {
        if (isPlaying()) {
            pause();
        } else {
            play();
        }
    }
    
    /**
     * Get playback progress as a percentage (0.0 to 1.0)
     */
    public float getPlaybackProgress() {
        if (duration <= 0) {
            return 0.0f;
        }
        return Math.min(1.0f, Math.max(0.0f, (float) getCurrentPosition() / duration));
    }
    
    /**
     * Seek to a percentage of the video duration (0.0 to 1.0)
     */
    public void seekToProgress(float progress) {
        if (duration > 0) {
            long targetPosition = (long) (progress * duration);
            seekTo(targetPosition);
        }
    }
    
    /**
     * Set listener for video player events
     */
    public void setVideoPlayerListener(VideoPlayerListener listener) {
        this.listener = listener;
    }
    
    /**
     * Release resources when component is destroyed
     */
    public void release() {
        Log.d(TAG, "Releasing VideoPlayerComponent resources");
        
        // Stop position updates
        if (mainHandler != null) {
            mainHandler.removeCallbacks(positionUpdateRunnable);
        }
        
        // Release ExoPlayer
        if (player != null) {
            try {
                player.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing ExoPlayer", e);
            }
            player = null;
        }
        
        // Release renderer
        if (renderer != null) {
            renderer.release();
            renderer = null;
        }
        
        isInitialized = false;
        isPlaying = false;
        currentPosition = 0;
        duration = 0;
        
        Log.d(TAG, "VideoPlayerComponent resources released");
    }
    
    /**
     * OpenGL renderer for hardware-accelerated video rendering
     * Integrates with existing OpenGL infrastructure from com.fadcam.opengl
     */
    private static class VideoRenderer implements GLSurfaceView.Renderer {
        
        private static final String TAG = "VideoRenderer";
        
        private FullFrameRect fullFrameBlit;
        private int textureId;
        private SurfaceTexture surfaceTexture;
        private Surface videoSurface;
        
        private final float[] mvpMatrix = new float[16];
        private final float[] texMatrix = new float[16];
        
        private boolean frameAvailable = false;
        private final Object frameSyncObject = new Object();
        
        private int videoWidth = 0;
        private int videoHeight = 0;
        private int surfaceWidth = 0;
        private int surfaceHeight = 0;
        
        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            Log.d(TAG, "OpenGL surface created");
            
            try {
                // Log OpenGL version info for debugging
                GlUtil.logVersionInfo();
                
                // Set up OpenGL state
                GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                GLES20.glDisable(GLES20.GL_DEPTH_TEST);
                GLES20.glDisable(GLES20.GL_CULL_FACE);
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                
                // Check for OpenGL errors
                GlUtil.checkGlError("Initial GL setup");
                
                // Create texture renderer using existing grafika infrastructure
                fullFrameBlit = new FullFrameRect(
                    new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT)
                );
                
                // Create texture for video frames
                textureId = fullFrameBlit.createTextureObject();
                GlUtil.checkGlError("Texture creation");
                
                // Create SurfaceTexture for ExoPlayer output
                surfaceTexture = new SurfaceTexture(textureId);
                surfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                    @Override
                    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                        synchronized (frameSyncObject) {
                            frameAvailable = true;
                            frameSyncObject.notifyAll();
                        }
                    }
                });
                
                // Create Surface for ExoPlayer
                videoSurface = new Surface(surfaceTexture);
                
                Log.d(TAG, "OpenGL renderer initialized successfully with texture ID: " + textureId);
                
            } catch (Exception e) {
                Log.e(TAG, "Error initializing OpenGL renderer", e);
                throw new RuntimeException("Failed to initialize OpenGL renderer", e);
            }
        }
        
        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            Log.d(TAG, "Surface changed: " + width + "x" + height);
            
            surfaceWidth = width;
            surfaceHeight = height;
            
            GLES20.glViewport(0, 0, width, height);
            updateMvpMatrix();
        }
        
        @Override
        public void onDrawFrame(GL10 gl) {
            try {
                // Clear the frame
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                
                // Check for new frame with timeout to avoid blocking
                synchronized (frameSyncObject) {
                    if (!frameAvailable) {
                        // No new frame available, just clear and return
                        return;
                    }
                    frameAvailable = false;
                }
                
                // Update texture with new frame
                if (surfaceTexture != null) {
                    try {
                        surfaceTexture.updateTexImage();
                        surfaceTexture.getTransformMatrix(texMatrix);
                    } catch (Exception e) {
                        Log.w(TAG, "Error updating texture image", e);
                        return;
                    }
                }
                
                // Draw the video frame
                if (fullFrameBlit != null && textureId != 0) {
                    fullFrameBlit.drawFrame(textureId, texMatrix);
                }
                
                // Check for OpenGL errors periodically (not every frame for performance)
                if (System.currentTimeMillis() % 1000 < 16) { // Roughly once per second
                    GlUtil.checkGlError("onDrawFrame");
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error in onDrawFrame", e);
            }
        }
        
        private void updateMvpMatrix() {
            Matrix.setIdentityM(mvpMatrix, 0);
            
            if (videoWidth > 0 && videoHeight > 0 && surfaceWidth > 0 && surfaceHeight > 0) {
                // Calculate aspect ratios
                float videoAspect = (float) videoWidth / videoHeight;
                float surfaceAspect = (float) surfaceWidth / surfaceHeight;
                
                // Scale to fit while maintaining aspect ratio
                if (videoAspect > surfaceAspect) {
                    // Video is wider - fit to width
                    float scale = surfaceAspect / videoAspect;
                    Matrix.scaleM(mvpMatrix, 0, 1.0f, scale, 1.0f);
                } else {
                    // Video is taller - fit to height
                    float scale = videoAspect / surfaceAspect;
                    Matrix.scaleM(mvpMatrix, 0, scale, 1.0f, 1.0f);
                }
            }
        }
        
        public void setVideoSize(int width, int height) {
            videoWidth = width;
            videoHeight = height;
            updateMvpMatrix();
        }
        
        public Surface getVideoSurface() {
            return videoSurface;
        }
        
        public void release() {
            Log.d(TAG, "Releasing renderer resources");
            
            if (videoSurface != null) {
                videoSurface.release();
                videoSurface = null;
            }
            
            if (surfaceTexture != null) {
                surfaceTexture.release();
                surfaceTexture = null;
            }
            
            if (fullFrameBlit != null) {
                fullFrameBlit.release();
                fullFrameBlit = null;
            }
        }
    }
}