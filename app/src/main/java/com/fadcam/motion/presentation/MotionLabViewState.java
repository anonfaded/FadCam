package com.fadcam.motion.presentation;

import com.fadcam.motion.domain.model.MotionTriggerMode;

public class MotionLabViewState {
    public final boolean enabled;
    public final MotionTriggerMode triggerMode;
    public final int sensitivity;
    public final int analysisFps;
    public final int debounceMs;
    public final int postRollMs;
    public final int preRollSeconds;
    public final boolean lowFpsFallbackEnabled;
    public final int lowFpsTarget;

    public MotionLabViewState(
        boolean enabled,
        MotionTriggerMode triggerMode,
        int sensitivity,
        int analysisFps,
        int debounceMs,
        int postRollMs,
        int preRollSeconds,
        boolean lowFpsFallbackEnabled,
        int lowFpsTarget
    ) {
        this.enabled = enabled;
        this.triggerMode = triggerMode;
        this.sensitivity = sensitivity;
        this.analysisFps = analysisFps;
        this.debounceMs = debounceMs;
        this.postRollMs = postRollMs;
        this.preRollSeconds = preRollSeconds;
        this.lowFpsFallbackEnabled = lowFpsFallbackEnabled;
        this.lowFpsTarget = lowFpsTarget;
    }
}
