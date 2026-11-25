package com.fadcam.ui.animations;

import android.graphics.Color;

/** Small, allocation-free maths shared by every animation component. */
public final class AnimationMath {

    private AnimationMath() {
    }

    /** Smoothstep between {@code start} and {@code end}, clamped to 0..1. */
    public static float seg(float p, float start, float end) {
        if (p <= start) return 0f;
        if (p >= end) return 1f;
        float t = (p - start) / (end - start);
        return t * t * (3f - 2f * t);
    }

    public static float clamp01(float value) {
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }

    public static int withAlpha(int color, int alpha) {
        return Color.argb(
                Math.max(0, Math.min(255, alpha)),
                Color.red(color), Color.green(color), Color.blue(color));
    }
}
