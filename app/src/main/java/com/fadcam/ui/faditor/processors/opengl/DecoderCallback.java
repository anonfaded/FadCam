package com.fadcam.ui.faditor.processors.opengl;

/**
 * Callback interface for video decoder events and frame availability notifications.
 * This interface provides callbacks for various decoder states and frame processing events.
 */
public interface DecoderCallback {
    
    /**
     * Called when a new video frame is available for rendering.
     * This callback is triggered when MediaCodec has decoded a frame and it's ready for OpenGL processing.
     * 
     * @param presentationTimeUs The presentation timestamp of the frame in microseconds
     */
    void onFrameAvailable(long presentationTimeUs);
    
    /**
     * Called when video decoding has completed successfully.
     * This indicates that all frames have been processed and the decoder is ready to be released.
     */
    void onDecodingComplete();
    
    /**
     * Called when an error occurs during decoding.
     * 
     * @param error A descriptive error message explaining what went wrong
     */
    void onError(String error);
    
    /**
     * Called when the decoder has been successfully initialized and is ready to decode frames.
     * 
     * @param videoWidth The width of the video in pixels
     * @param videoHeight The height of the video in pixels
     * @param durationUs The total duration of the video in microseconds
     */
    void onDecoderReady(int videoWidth, int videoHeight, long durationUs);
    
    /**
     * Called when a seek operation has completed.
     * This callback indicates that the decoder has successfully seeked to the requested position.
     * 
     * @param seekTimeUs The actual time position after seeking in microseconds
     */
    void onSeekCompleted(long seekTimeUs);
    
    /**
     * Called periodically during decoding to report progress.
     * This can be used to update UI progress indicators during long operations.
     * 
     * @param currentTimeUs Current playback position in microseconds
     * @param totalDurationUs Total video duration in microseconds
     */
    void onProgressUpdate(long currentTimeUs, long totalDurationUs);
}