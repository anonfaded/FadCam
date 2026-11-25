package com.fadcam.ui.faditor.model;

/**
 * Configuration for video export.
 * Encapsulates resolution, quality, and format choices.
 */
public class ExportSettings {

    /** Output resolution presets. */
    public enum Resolution {
        /** Keep source resolution (no scaling). */
        ORIGINAL,
        /** 1920 x 1080 */
        FHD_1080P,
        /** 1280 x 720 */
        HD_720P,
        /** 854 x 480 */
        SD_480P
    }

    /** Export quality level (maps to encoder bitrate). */
    public enum Quality {
        HIGH,
        MEDIUM,
        LOW
    }

    /** Container format. */
    public enum Format {
        MP4,
        WEBM
    }

    private Resolution resolution = Resolution.ORIGINAL;
    private Quality quality = Quality.HIGH;
    private Format format = Format.MP4;

    // ── Getters ──────────────────────────────────────────────────────

    public Resolution getResolution() {
        return resolution;
    }

    public Quality getQuality() {
        return quality;
    }

    public Format getFormat() {
        return format;
    }

    // ── Setters ──────────────────────────────────────────────────────

    public void setResolution(Resolution resolution) {
        this.resolution = resolution;
    }

    public void setQuality(Quality quality) {
        this.quality = quality;
    }

    public void setFormat(Format format) {
        this.format = format;
    }

    @Override
    public String toString() {
        return "ExportSettings{" + resolution + ", " + quality + ", " + format + "}";
    }
}
