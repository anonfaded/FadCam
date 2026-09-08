package com.fadcam.ui.faditor.model;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a single video segment in the editor timeline.
 * Maps directly to a Media3 MediaItem with ClippingConfiguration.
 *
 * <p>Immutable-style: use setters sparingly; prefer creating new instances
 * or using the Builder when scaling to multi-clip editing.</p>
 */
public class Clip {

    @NonNull
    private final String id;

    @NonNull
    private final Uri sourceUri;

    /** Start position within the source video (milliseconds). */
    private long inPointMs;

    /** End position within the source video (milliseconds). */
    private long outPointMs;

    /** Original (un-trimmed) duration of the source video (milliseconds). */
    private long sourceDurationMs;

    /** Playback speed multiplier (0.1 – 10.0, default 1.0). */
    private float speedMultiplier = 1.0f;

    /** Whether audio is muted for this clip. */
    private boolean audioMuted = false;

    /** Volume level (0.0 = silence, 1.0 = original, 2.0 = 200%). */
    private float volumeLevel = 1.0f;

    /** Rotation in degrees: 0, 90, 180, 270. */
    private int rotationDegrees = 0;

    /** Whether the video is flipped horizontally (mirror). */
    private boolean flipHorizontal = false;

    /** Whether the video is flipped vertically. */
    private boolean flipVertical = false;

    /**
     * Crop aspect ratio preset key.
     * Values: "none", "16:9", "9:16", "4:3", "3:4", "1:1", "21:9", "custom"
     */
    @NonNull
    private String cropPreset = "none";

    /**
     * Custom crop bounds (normalised 0.0 – 1.0).
     * Only used when {@link #cropPreset} is "custom".
     */
    private float cropLeft = 0f;
    private float cropTop = 0f;
    private float cropRight = 1f;
    private float cropBottom = 1f;

    /**
     * Whether this clip is a still image (not a video).
     * Image clips have a fixed playback duration (e.g. 5 seconds)
     * and display as a single still frame during preview/export.
     */
    private boolean imageClip = false;

    /**
     * Create a new Clip from a video URI.
     *
     * @param sourceUri        URI of the source video file
     * @param sourceDurationMs total duration of the source video in ms
     */
    public Clip(@NonNull Uri sourceUri, long sourceDurationMs) {
        this.id = UUID.randomUUID().toString();
        this.sourceUri = sourceUri;
        this.inPointMs = 0;
        this.outPointMs = sourceDurationMs;
        this.sourceDurationMs = sourceDurationMs;
    }

    /**
     * Constructor for deserialization / cloning.
     */
    public Clip(@NonNull String id, @NonNull Uri sourceUri,
         long inPointMs, long outPointMs, long sourceDurationMs,
         float speedMultiplier, boolean audioMuted, float volumeLevel,
         int rotationDegrees, boolean flipHorizontal, boolean flipVertical,
         @NonNull String cropPreset,
         float cropLeft, float cropTop, float cropRight, float cropBottom) {
        this.id = id;
        this.sourceUri = sourceUri;
        this.inPointMs = inPointMs;
        this.outPointMs = outPointMs;
        this.sourceDurationMs = sourceDurationMs;
        this.speedMultiplier = speedMultiplier;
        this.audioMuted = audioMuted;
        this.volumeLevel = volumeLevel;
        this.rotationDegrees = rotationDegrees;
        this.flipHorizontal = flipHorizontal;
        this.flipVertical = flipVertical;
        this.cropPreset = cropPreset;
        this.cropLeft = cropLeft;
        this.cropTop = cropTop;
        this.cropRight = cropRight;
        this.cropBottom = cropBottom;
    }

    /**
     * Deep copy constructor. Creates a new clip with a fresh ID
     * but identical source, trim, and all effect settings.
     *
     * @param other the clip to copy
     */
    public Clip(@NonNull Clip other) {
        this.id = UUID.randomUUID().toString();
        this.sourceUri = other.sourceUri;
        this.inPointMs = other.inPointMs;
        this.outPointMs = other.outPointMs;
        this.sourceDurationMs = other.sourceDurationMs;
        this.speedMultiplier = other.speedMultiplier;
        this.audioMuted = other.audioMuted;
        this.volumeLevel = other.volumeLevel;
        this.rotationDegrees = other.rotationDegrees;
        this.flipHorizontal = other.flipHorizontal;
        this.flipVertical = other.flipVertical;
        this.cropPreset = other.cropPreset;
        this.cropLeft = other.cropLeft;
        this.cropTop = other.cropTop;
        this.cropRight = other.cropRight;
        this.cropBottom = other.cropBottom;
        this.imageClip = other.imageClip;
    }

    // ── Getters ──────────────────────────────────────────────────────

    @NonNull
    public String getId() {
        return id;
    }

    @NonNull
    public Uri getSourceUri() {
        return sourceUri;
    }

    public long getInPointMs() {
        return inPointMs;
    }

    public long getOutPointMs() {
        return outPointMs;
    }

    public long getSourceDurationMs() {
        return sourceDurationMs;
    }

    public float getSpeedMultiplier() {
        return speedMultiplier;
    }

    public boolean isAudioMuted() {
        return audioMuted;
    }

    public float getVolumeLevel() {
        return volumeLevel;
    }

    public int getRotationDegrees() {
        return rotationDegrees;
    }

    public boolean isFlipHorizontal() {
        return flipHorizontal;
    }

    public boolean isFlipVertical() {
        return flipVertical;
    }

    @NonNull
    public String getCropPreset() {
        return cropPreset;
    }

    public float getCropLeft() { return cropLeft; }
    public float getCropTop() { return cropTop; }
    public float getCropRight() { return cropRight; }
    public float getCropBottom() { return cropBottom; }

    /**
     * Whether this clip represents a still image rather than a video.
     */
    public boolean isImageClip() {
        return imageClip;
    }

    /**
     * Trimmed duration in milliseconds (respects speed multiplier).
     */
    public long getTrimmedDurationMs() {
        long raw = outPointMs - inPointMs;
        return (long) (raw / speedMultiplier);
    }

    // ── Setters (for trim handle updates) ────────────────────────────

    /**
     * Set the rotation in degrees (clamped to 0, 90, 180, 270).
     *
     * @param degrees rotation angle; rounded to nearest 90°
     */
    public void setRotationDegrees(int degrees) {
        this.rotationDegrees = ((degrees % 360) + 360) % 360;
        // Snap to nearest 90
        this.rotationDegrees = (Math.round(this.rotationDegrees / 90f) * 90) % 360;
    }

    public void setFlipHorizontal(boolean flip) {
        this.flipHorizontal = flip;
    }

    public void setFlipVertical(boolean flip) {
        this.flipVertical = flip;
    }

    /**
     * Set the crop preset.
     *
     * @param preset one of "none", "16:9", "9:16", "4:3", "3:4", "1:1", "21:9", "custom"
     */
    public void setCropPreset(@NonNull String preset) {
        this.cropPreset = preset;
    }

    /**
     * Set custom crop bounds (normalised 0.0 – 1.0).
     * Automatically sets cropPreset to "custom".
     */
    public void setCustomCropBounds(float left, float top, float right, float bottom) {
        this.cropPreset = "custom";
        this.cropLeft = Math.max(0f, Math.min(left, 1f));
        this.cropTop = Math.max(0f, Math.min(top, 1f));
        this.cropRight = Math.max(0f, Math.min(right, 1f));
        this.cropBottom = Math.max(0f, Math.min(bottom, 1f));
    }

    /**
     * Set the in-point (start trim position).
     *
     * @param ms milliseconds from start of source; clamped to [0, outPointMs)
     */
    public void setInPointMs(long ms) {
        this.inPointMs = Math.max(0, Math.min(ms, outPointMs - 1));
    }

    /**
     * Set the out-point (end trim position).
     *
     * @param ms milliseconds from start of source; clamped to (inPointMs, sourceDurationMs]
     */
    public void setOutPointMs(long ms) {
        this.outPointMs = Math.max(inPointMs + 1, Math.min(ms, sourceDurationMs));
    }

    public void setSpeedMultiplier(float speed) {
        this.speedMultiplier = Math.max(0.1f, Math.min(speed, 10.0f));
    }

    public void setAudioMuted(boolean muted) {
        this.audioMuted = muted;
    }

    public void setVolumeLevel(float level) {
        this.volumeLevel = Math.max(0f, Math.min(level, 2.0f));
    }

    /**
     * Mark this clip as a still image clip (not video).
     *
     * @param imageClip true if this clip represents a still image
     */
    public void setImageClip(boolean imageClip) {
        this.imageClip = imageClip;
    }

    /**
     * Correct the source duration (e.g. after ExoPlayer reports the real duration
     * for fragmented MP4 files where MediaMetadataRetriever was inaccurate).
     *
     * @param ms corrected source duration in milliseconds
     */
    public void setSourceDurationMs(long ms) {
        this.sourceDurationMs = Math.max(1, ms);
    }

    @NonNull
    @Override
    public String toString() {
        return "Clip{id=" + id
                + ", in=" + inPointMs
                + ", out=" + outPointMs
                + ", speed=" + speedMultiplier
                + ", muted=" + audioMuted
                + ", rot=" + rotationDegrees
                + ", flipH=" + flipHorizontal
                + ", flipV=" + flipVertical
                + ", crop=" + cropPreset
                + "}";
    }}
