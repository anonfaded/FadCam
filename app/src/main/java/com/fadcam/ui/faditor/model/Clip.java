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

    /** Playback speed multiplier (0.25 – 4.0, default 1.0). */
    private float speedMultiplier = 1.0f;

    // Future: List<Effect> effects = new ArrayList<>();

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
         float speedMultiplier) {
        this.id = id;
        this.sourceUri = sourceUri;
        this.inPointMs = inPointMs;
        this.outPointMs = outPointMs;
        this.sourceDurationMs = sourceDurationMs;
        this.speedMultiplier = speedMultiplier;
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

    /**
     * Trimmed duration in milliseconds (respects speed multiplier).
     */
    public long getTrimmedDurationMs() {
        long raw = outPointMs - inPointMs;
        return (long) (raw / speedMultiplier);
    }

    // ── Setters (for trim handle updates) ────────────────────────────

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
        this.speedMultiplier = Math.max(0.25f, Math.min(speed, 4.0f));
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
                + "}";
    }
}
