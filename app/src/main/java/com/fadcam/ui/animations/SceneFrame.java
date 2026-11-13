package com.fadcam.ui.animations;

/**
 * Mutable per-frame state handed to a {@link Scene}. One instance is reused by
 * the host view, so drawing never allocates a frame object.
 */
public final class SceneFrame {

    /** One-shot reveal progress, 0..1. */
    public float progress;

    /** Ambient loop phase, 0..1. Shared by every preview so they stay in step. */
    public float phase;

    /** True once the reveal has settled and the ambient loop is driving the scene. */
    public boolean ambient;

    /** Monotonic animation time in milliseconds; pauses with the host. */
    public long timeMs;

    /** Palette and chrome for this draw. */
    public SceneStyle style = SceneStyle.defaults();

    /** Smooth 0..1 wave over the ambient phase. */
    public float wave() {
        return 0.5f + 0.5f * (float) Math.sin(phase * Math.PI * 2.0);
    }

    /** Wave with extra cycles per ambient loop. */
    public float wave(float cycles) {
        return 0.5f + 0.5f * (float) Math.sin(phase * Math.PI * 2.0 * cycles);
    }

    /**
     * Recording-indicator blink. Bright at phase 0, dim at the trough, and
     * fully bright while the one-shot reveal is still playing.
     */
    public float pulse(float cycles) {
        if (!ambient) return 1f;
        return 0.22f + 0.78f * (0.5f + 0.5f * (float) Math.cos(phase * Math.PI * 2.0 * cycles));
    }
}
