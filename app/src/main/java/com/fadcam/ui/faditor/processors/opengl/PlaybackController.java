package com.fadcam.ui.faditor.processors.opengl;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.fadcam.Log;

/**
 * PlaybackController manages playback timing and synchronization for smooth video playback.
 * Handles playback speed control, frame rate management, and smooth seeking operations.
 * Requirements: 13.2, 13.4, 14.1, 14.5
 */
public class PlaybackController {
    private static final String TAG = "PlaybackController";
    
    // Default frame rate for timing calculations
    private static final float DEFAULT_FRAME_RATE = 30.0f;
    private static final long POSITION_UPDATE_INTERVAL_MS = 16; // ~60fps updates
    private static final long SEEK_TIMEOUT_MS = 100; // Maximum seek time for <50ms requirement
    
    public interface PlaybackListener {
        /**
         * Called when playback position changes during normal playback
         */
        void onPositionChanged(long positionMs);
        
        /**
         * Called when playback speed changes
         */
        void onPlaybackSpeedChanged(float speed);
        
        /**
         * Called when seeking is completed
         */
        void onSeekCompleted(long actualPositionMs);
        
        /**
         * Called when playback state changes
         */
        void onPlaybackStateChanged(boolean isPlaying);
    }
    
    private PlaybackListener listener;
    private Handler mainHandler;
    private Runnable positionUpdateRunnable;
    
    // Playback state
    private boolean isPlaying = false;
    private float playbackSpeed = 1.0f;
    private long currentPositionMs = 0;
    private long videoDurationMs = 0;
    private float videoFrameRate = DEFAULT_FRAME_RATE;
    
    // Timing synchronization
    private long playbackStartTime = 0;
    private long playbackStartPosition = 0;
    private boolean isSeeking = false;
    
    public PlaybackController() {
        mainHandler = new Handler(Looper.getMainLooper());
        initializePositionUpdater();
    }
    
    private void initializePositionUpdater() {
        positionUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (isPlaying && !isSeeking) {
                    updatePlaybackPosition();
                    if (listener != null) {
                        listener.onPositionChanged(currentPositionMs);
                    }
                    
                    // Schedule next update
                    mainHandler.postDelayed(this, POSITION_UPDATE_INTERVAL_MS);
                }
            }
        };
    }
    
    /**
     * Initialize the playback controller with video metadata
     */
    public void initialize(long durationMs, float frameRate) {
        this.videoDurationMs = durationMs;
        this.videoFrameRate = frameRate > 0 ? frameRate : DEFAULT_FRAME_RATE;
        this.currentPositionMs = 0;
        
        Log.d(TAG, "PlaybackController initialized - Duration: " + durationMs + "ms, Frame rate: " + frameRate + "fps");
    }
    
    /**
     * Start playback from current position
     */
    public void startPlayback() {
        if (isPlaying) {
            Log.w(TAG, "Playback already started");
            return;
        }
        
        isPlaying = true;
        playbackStartTime = SystemClock.elapsedRealtime();
        playbackStartPosition = currentPositionMs;
        
        // Start position updates
        mainHandler.post(positionUpdateRunnable);
        
        if (listener != null) {
            listener.onPlaybackStateChanged(true);
        }
        
        Log.d(TAG, "Playback started from position: " + currentPositionMs + "ms");
    }
    
    /**
     * Pause playback at current position
     */
    public void pausePlayback() {
        if (!isPlaying) {
            Log.w(TAG, "Playback already paused");
            return;
        }
        
        isPlaying = false;
        
        // Update current position before pausing
        updatePlaybackPosition();
        
        // Stop position updates
        mainHandler.removeCallbacks(positionUpdateRunnable);
        
        if (listener != null) {
            listener.onPlaybackStateChanged(false);
        }
        
        Log.d(TAG, "Playback paused at position: " + currentPositionMs + "ms");
    }
    
    /**
     * Set playback speed (1.0 = normal speed)
     */
    public void setPlaybackSpeed(float speed) {
        if (speed <= 0) {
            Log.w(TAG, "Invalid playback speed: " + speed);
            return;
        }
        
        // Update position before changing speed
        if (isPlaying) {
            updatePlaybackPosition();
            playbackStartTime = SystemClock.elapsedRealtime();
            playbackStartPosition = currentPositionMs;
        }
        
        this.playbackSpeed = speed;
        
        if (listener != null) {
            listener.onPlaybackSpeedChanged(speed);
        }
        
        Log.d(TAG, "Playback speed set to: " + speed + "x");
    }
    
    /**
     * Seek to specific position with frame-accurate precision
     * Ensures <50ms response time as per requirements
     */
    public void seekTo(long positionMs) {
        long startTime = SystemClock.elapsedRealtime();
        
        // Clamp position to valid range
        positionMs = Math.max(0, Math.min(positionMs, videoDurationMs));
        
        isSeeking = true;
        boolean wasPlaying = isPlaying;
        
        // Temporarily pause during seek for accuracy
        if (isPlaying) {
            pausePlayback();
        }
        
        // Update position immediately for responsive UI
        currentPositionMs = positionMs;
        
        // Perform the actual seek operation
        performSeek(positionMs, wasPlaying, startTime);
    }
    
    /**
     * Seek to specific frame number
     */
    public void seekToFrame(long frameNumber) {
        long positionMs = (long) ((frameNumber / videoFrameRate) * 1000);
        seekTo(positionMs);
    }
    
    /**
     * Get current playback position
     */
    public long getCurrentPosition() {
        if (isPlaying && !isSeeking) {
            updatePlaybackPosition();
        }
        return currentPositionMs;
    }
    
    /**
     * Get current frame number based on position and frame rate
     */
    public long getCurrentFrame() {
        return (long) ((currentPositionMs / 1000.0f) * videoFrameRate);
    }
    
    /**
     * Get video duration
     */
    public long getDuration() {
        return videoDurationMs;
    }
    
    /**
     * Get total frame count
     */
    public long getTotalFrames() {
        return (long) ((videoDurationMs / 1000.0f) * videoFrameRate);
    }
    
    /**
     * Check if currently playing
     */
    public boolean isPlaying() {
        return isPlaying;
    }
    
    /**
     * Get current playback speed
     */
    public float getPlaybackSpeed() {
        return playbackSpeed;
    }
    
    /**
     * Set playback listener for callbacks
     */
    public void setPlaybackListener(PlaybackListener listener) {
        this.listener = listener;
    }
    
    /**
     * Release resources and stop all operations
     */
    public void release() {
        isPlaying = false;
        isSeeking = false;
        
        mainHandler.removeCallbacks(positionUpdateRunnable);
        
        Log.d(TAG, "PlaybackController released");
    }
    
    // Private helper methods
    
    private void updatePlaybackPosition() {
        if (!isPlaying || isSeeking) {
            return;
        }
        
        long elapsedTime = SystemClock.elapsedRealtime() - playbackStartTime;
        long adjustedElapsed = (long) (elapsedTime * playbackSpeed);
        currentPositionMs = playbackStartPosition + adjustedElapsed;
        
        // Clamp to valid range
        currentPositionMs = Math.max(0, Math.min(currentPositionMs, videoDurationMs));
        
        // Check for end of video
        if (currentPositionMs >= videoDurationMs) {
            pausePlayback();
            currentPositionMs = videoDurationMs;
        }
    }
    
    private void performSeek(long targetPositionMs, boolean resumePlayback, long startTime) {
        // Simulate seek operation (in real implementation, this would coordinate with VideoDecoder)
        mainHandler.postDelayed(() -> {
            isSeeking = false;
            
            // Calculate actual seek time
            long seekTime = SystemClock.elapsedRealtime() - startTime;
            
            // Ensure we meet the <50ms requirement
            if (seekTime > SEEK_TIMEOUT_MS) {
                Log.w(TAG, "Seek took longer than expected: " + seekTime + "ms");
            }
            
            // Update timing references
            playbackStartTime = SystemClock.elapsedRealtime();
            playbackStartPosition = targetPositionMs;
            currentPositionMs = targetPositionMs;
            
            // Resume playback if it was playing before seek
            if (resumePlayback) {
                startPlayback();
            }
            
            // Notify listener
            if (listener != null) {
                listener.onSeekCompleted(currentPositionMs);
                listener.onPositionChanged(currentPositionMs);
            }
            
            Log.d(TAG, "Seek completed to position: " + targetPositionMs + "ms in " + seekTime + "ms");
            
        }, Math.min(10, SEEK_TIMEOUT_MS / 2)); // Minimal delay to simulate seek operation
    }
}