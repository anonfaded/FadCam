package com.fadcam.opengl;

/**
 * WatermarkInfoProvider provides the current watermark text (timestamp, FadCam, location, etc).
 */
public interface WatermarkInfoProvider {
    /**
     * Returns the current watermark text to be rendered.
     */
    String getWatermarkText();
} 