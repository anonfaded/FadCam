package com.fadcam.watermark;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WatermarkFontSizePresetTest {

    @Test
    public void fromPreferenceReturnsMatchingPreset() {
        assertEquals(
                WatermarkFontSizePreset.EXTRA_LARGE,
                WatermarkFontSizePreset.fromPreference("extra_large")
        );
    }

    @Test
    public void fromPreferenceFallsBackToMedium() {
        assertEquals(WatermarkFontSizePreset.MEDIUM, WatermarkFontSizePreset.fromPreference(null));
        assertEquals(WatermarkFontSizePreset.MEDIUM, WatermarkFontSizePreset.fromPreference("unknown"));
    }

    @Test
    public void scaleAppliesPresetMultiplier() {
        assertEquals(16f, WatermarkFontSizePreset.SMALL.scale(20f), 0.001f);
        assertEquals(20f, WatermarkFontSizePreset.MEDIUM.scale(20f), 0.001f);
        assertEquals(28f, WatermarkFontSizePreset.LARGE.scale(20f), 0.001f);
        assertEquals(36f, WatermarkFontSizePreset.EXTRA_LARGE.scale(20f), 0.001f);
    }
}
