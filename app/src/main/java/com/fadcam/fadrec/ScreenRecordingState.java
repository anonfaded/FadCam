package com.fadcam.fadrec;

/**
 * Represents the current state of screen recording in the FadRec feature.
 * Follows the same pattern as RecordingState for camera recording.
 */
public enum ScreenRecordingState {
    /**
     * No screen recording is in progress
     */
    NONE,

    /**
     * Screen recording is actively in progress
     */
    IN_PROGRESS,

    /**
     * Screen recording is paused
     */
    PAUSED,

    /**
     * Screen recording is in the process of stopping
     */
    STOPPING
}
