package com.fadcam.production;

/**
 * Process-local audio mix bus for the production UI. The recorder remains the
 * owner of microphone capture; this bus only controls playback-side gain.
 * Levels are normalized to 0..1 and are safe to update from the UI thread.
 */
public final class ProductionAudioMixBus {
    public static final int CAMERA_1 = 0;
    public static final int CAMERA_2 = 1;
    public static final int MEDIA = 2;
    public static final int MASTER = 3;

    private static final float[] LEVELS = {0.75f, 0.75f, 0.65f, 0.85f};

    private ProductionAudioMixBus() {}

    public static synchronized void setLevel(int channel, int percent) {
        if (channel < 0 || channel >= LEVELS.length) return;
        LEVELS[channel] = clamp(percent / 100f);
    }

    public static synchronized float getLevel(int channel) {
        if (channel < 0 || channel >= LEVELS.length) return 1f;
        return LEVELS[channel];
    }

    public static synchronized float getMediaGain() {
        return LEVELS[MEDIA] * LEVELS[MASTER];
    }

    public static synchronized float getCameraGain(int slot) {
        return (slot == 2 ? LEVELS[CAMERA_2] : LEVELS[CAMERA_1]) * LEVELS[MASTER];
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
