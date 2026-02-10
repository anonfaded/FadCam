package com.fadcam.ui.faditor.model;

import android.net.Uri;

import androidx.annotation.NonNull;

import java.util.UUID;

/**
 * Represents a single audio clip on the audio track in the editor timeline.
 *
 * <p>Audio clips exist on a separate track from video segments and can be
 * independently positioned, trimmed, and dragged along the timeline.</p>
 *
 * <p>Key concepts:</p>
 * <ul>
 *   <li>{@link #sourceUri} – the audio file on disk (extracted AAC, or user-supplied).</li>
 *   <li>{@link #inPointMs} / {@link #outPointMs} – trim bounds within the audio source.</li>
 *   <li>{@link #offsetMs} – where this clip begins on the project timeline
 *       (0 = aligned with the very start of the video track).</li>
 *   <li>{@link #waveform} – downsampled amplitude array for timeline visualisation.</li>
 * </ul>
 */
public class AudioClip {

    @NonNull
    private final String id;

    /** URI of the audio source file. */
    @NonNull
    private Uri sourceUri;

    /** Total duration of the source audio file in milliseconds. */
    private long sourceDurationMs;

    /** Trim start within the source audio (ms). */
    private long inPointMs;

    /** Trim end within the source audio (ms). */
    private long outPointMs;

    /**
     * Offset from the beginning of the project timeline (ms).
     * Positive = the audio starts that many ms into the project.
     * Allows the user to drag the audio left/right to align with video.
     */
    private long offsetMs;

    /** Volume multiplier (0.0 – 2.0, default 1.0). */
    private float volumeLevel = 1.0f;

    /** Whether audio is muted. */
    private boolean muted = false;

    /**
     * Downsampled amplitude data for waveform visualisation.
     * Each value is 0–255 representing the peak amplitude for that sample window.
     * Null until waveform extraction has been performed.
     */
    private int[] waveform;

    /** User-visible label (e.g. "Extracted audio", "voice-note.mp3"). */
    @NonNull
    private String label = "Audio";

    // ── Constructors ─────────────────────────────────────────────────

    /**
     * Create a new AudioClip.
     *
     * @param sourceUri      URI of the audio file
     * @param durationMs     total duration of the audio source
     */
    public AudioClip(@NonNull Uri sourceUri, long durationMs) {
        this.id = UUID.randomUUID().toString();
        this.sourceUri = sourceUri;
        this.sourceDurationMs = durationMs;
        this.inPointMs = 0;
        this.outPointMs = durationMs;
        this.offsetMs = 0;
    }

    /**
     * Copy constructor.
     */
    public AudioClip(@NonNull AudioClip other) {
        this.id = UUID.randomUUID().toString();
        this.sourceUri = other.sourceUri;
        this.sourceDurationMs = other.sourceDurationMs;
        this.inPointMs = other.inPointMs;
        this.outPointMs = other.outPointMs;
        this.offsetMs = other.offsetMs;
        this.volumeLevel = other.volumeLevel;
        this.muted = other.muted;
        this.label = other.label;
        // Waveform data is shared (immutable int array after extraction)
        this.waveform = other.waveform;
    }

    // ── Getters ──────────────────────────────────────────────────────

    @NonNull
    public String getId() { return id; }

    @NonNull
    public Uri getSourceUri() { return sourceUri; }

    public long getSourceDurationMs() { return sourceDurationMs; }

    public long getInPointMs() { return inPointMs; }

    public long getOutPointMs() { return outPointMs; }

    public long getOffsetMs() { return offsetMs; }

    public float getVolumeLevel() { return volumeLevel; }

    public boolean isMuted() { return muted; }

    public int[] getWaveform() { return waveform; }

    @NonNull
    public String getLabel() { return label; }

    /**
     * Trimmed duration of this audio clip in milliseconds.
     */
    public long getTrimmedDurationMs() {
        return outPointMs - inPointMs;
    }

    /**
     * End position on the project timeline (offset + trimmed duration).
     */
    public long getEndOnTimelineMs() {
        return offsetMs + getTrimmedDurationMs();
    }

    // ── Setters ──────────────────────────────────────────────────────

    public void setSourceUri(@NonNull Uri uri) { this.sourceUri = uri; }

    public void setSourceDurationMs(long ms) { this.sourceDurationMs = ms; }

    public void setInPointMs(long ms) { this.inPointMs = Math.max(0, ms); }

    public void setOutPointMs(long ms) {
        this.outPointMs = Math.min(ms, sourceDurationMs);
    }

    public void setOffsetMs(long ms) { this.offsetMs = Math.max(0, ms); }

    public void setVolumeLevel(float level) {
        this.volumeLevel = Math.max(0f, Math.min(level, 2.0f));
    }

    public void setMuted(boolean muted) { this.muted = muted; }

    public void setWaveform(int[] waveform) { this.waveform = waveform; }

    public void setLabel(@NonNull String label) { this.label = label; }

    // ── Utility ──────────────────────────────────────────────────────

    @NonNull
    @Override
    public String toString() {
        return "AudioClip{id=" + id
                + ", duration=" + sourceDurationMs
                + "ms, trim=[" + inPointMs + "–" + outPointMs + "]"
                + ", offset=" + offsetMs
                + "ms, label=" + label + "}";
    }
}
