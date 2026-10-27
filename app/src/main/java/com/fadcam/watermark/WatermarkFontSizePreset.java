package com.fadcam.watermark;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * User-selectable multiplier applied to the resolution-aware watermark text size.
 */
public enum WatermarkFontSizePreset {
    SMALL("small", 0.8f),
    MEDIUM("medium", 1.0f),
    LARGE("large", 1.4f),
    EXTRA_LARGE("extra_large", 1.8f);

    private final String preferenceValue;
    private final float scale;

    WatermarkFontSizePreset(@NonNull String preferenceValue, float scale) {
        this.preferenceValue = preferenceValue;
        this.scale = scale;
    }

    @NonNull
    public String getPreferenceValue() {
        return preferenceValue;
    }

    public float scale(float baseTextSize) {
        return baseTextSize * scale;
    }

    @NonNull
    public static WatermarkFontSizePreset fromPreference(@Nullable String value) {
        for (WatermarkFontSizePreset preset : values()) {
            if (preset.preferenceValue.equals(value)) {
                return preset;
            }
        }
        return MEDIUM;
    }
}
