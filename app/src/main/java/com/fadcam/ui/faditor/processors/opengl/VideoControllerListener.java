package com.fadcam.ui.faditor.processors.opengl;

import com.fadcam.ui.faditor.models.VideoMetadata;

/**
 * Listener interface for OpenGLVideoController events and component communication.
 * Provides callbacks for video loading, playback state changes, frame rendering, and error handling.
 * Requirements: 13.2, 13.4, 14.1, 14.5
 */
public interface VideoControllerListener {
    
    /**
     * Called when video is successfully loaded and ready for playback.
     * This indicates that all video metadata has been extracted and the decoder is initialized.
     * 
     * @param metadata Complete video metadata including dimensions, duration, frame rate, etc.
     */
    void onVideoLoaded(VideoMetadata metadata);
    
    /**
     * Called when a frame is rendered to the OpenGL surface.
     * This callback provides frame-accurate timing information for synchronization.
     * 
     * @param frameNumber The sequential frame number that was rendered
     * @param timestampUs The presentation timestamp of the frame in microseconds
     */
    void onFrameRendered(long frameNumber, long timestampUs);
    
    /**
     * Called when playback state changes between playing and paused.
     * 
     * @param isPlaying true if video is currently playing, false if paused
     */
    void onPlaybackStateChanged(boolean isPlaying);
    
    /**
     * Called when a seek operation completes successfully.
     * This indicates that the video has been positioned at the requested time/frame.
     * 
     * @param positionMs The actual position after seeking in milliseconds
     */
    void onSeekCompleted(long positionMs);
    
    /**
     * Called periodically during playback to report current position.
     * This provides smooth position updates for UI synchronization.
     * 
     * @param positionMs Current playback position in milliseconds
     */
    void onPositionChanged(long positionMs);
    
    /**
     * Called when an error occurs in any component of the video system.
     * This includes decoder errors, rendering errors, or initialization failures.
     * 
     * @param error Descriptive error message explaining what went wrong
     */
    void onError(String error);
    
    /**
     * Called when video loading and initialization is completely finished.
     * This indicates that the video is ready for all operations including playback and seeking.
     */
    void onInitializationComplete();
    
    /**
     * Called when video loading progress updates are available.
     * This can be used to show loading progress to the user.
     * 
     * @param progress Loading progress as a percentage (0-100)
     */
    default void onLoadingProgress(int progress) {
        // Default empty implementation for optional callback
    }
    
    /**
     * Called when the video reaches the end during playback.
     * This indicates that playback has naturally completed.
     */
    default void onPlaybackComplete() {
        // Default empty implementation for optional callback
    }
    
    /**
     * Called when video format or properties change during playback.
     * This is rare but can happen with adaptive streaming or format changes.
     * 
     * @param newMetadata Updated video metadata with new properties
     */
    default void onVideoFormatChanged(VideoMetadata newMetadata) {
        // Default empty implementation for optional callback
    }
}