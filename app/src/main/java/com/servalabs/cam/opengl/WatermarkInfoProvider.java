package com.servalabs.cam.opengl;

/**
 * WatermarkInfoProvider provides the current watermark text (timestamp, ServaCam, location, etc).
 */
public interface WatermarkInfoProvider {
    /**
     * Returns the current watermark text to be rendered.
     */
    String getWatermarkText();
} 