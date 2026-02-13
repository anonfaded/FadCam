package com.fadcam.motion.domain.model;

public class MotionSettings {
    private final boolean enabled;
    private final MotionTriggerMode triggerMode;
    private final int sensitivity;
    private final int analysisFps;
    private final int debounceMs;
    private final int postRollMs;
    private final int preRollSeconds;
    private final boolean lowFpsFallbackEnabled;
    private final int lowFpsTarget;

    public MotionSettings(
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

    public boolean isEnabled() {
        return enabled;
    }

    public MotionTriggerMode getTriggerMode() {
        return triggerMode;
    }

    public int getSensitivity() {
        return sensitivity;
    }

    public int getAnalysisFps() {
        return analysisFps;
    }

    public int getDebounceMs() {
        return debounceMs;
    }

    public int getPostRollMs() {
        return postRollMs;
    }

    public int getPreRollSeconds() {
        return preRollSeconds;
    }

    public boolean isLowFpsFallbackEnabled() {
        return lowFpsFallbackEnabled;
    }

    public int getLowFpsTarget() {
        return lowFpsTarget;
    }
}
