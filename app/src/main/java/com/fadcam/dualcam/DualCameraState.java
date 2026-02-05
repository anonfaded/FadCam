package com.fadcam.dualcam;

import java.io.Serializable;

/**
 * Recording state for dual camera mode.
 * Separate from {@link com.fadcam.RecordingState} to avoid coupling with single-camera logic.
 */
public enum DualCameraState implements Serializable {

    /** Dual camera mode is off; using single camera via RecordingService. */
    DISABLED,

    /** Opening both cameras (front + back). */
    INITIALIZING,

    /** Both cameras open, showing preview, not yet recording. */
    PREVIEW_ONLY,

    /** Both cameras recording to single composited output. */
    RECORDING,

    /** Recording paused (both cameras remain open). */
    PAUSED,

    /** Error state — requires user action or graceful fallback to single camera. */
    ERROR
}
