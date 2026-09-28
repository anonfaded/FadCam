package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ordered list of {@link Clip}s that make up the editor timeline.
 *
 * <p>For MVP (Phase 1) this holds a single clip.
 * Scales to multi-clip editing in Phase 2 without changes.</p>
 */
public class Timeline {

    @NonNull
    private final List<Clip> clips;

    /** Audio clips on the audio track (independent of video segments). */
    @NonNull
    private final List<AudioClip> audioClips;

    public Timeline() {
        this.clips = new ArrayList<>();
        this.audioClips = new ArrayList<>();
    }

    // ── Clip management ──────────────────────────────────────────────

    public void addClip(@NonNull Clip clip) {
        clips.add(clip);
    }

    public void addClip(int index, @NonNull Clip clip) {
        clips.add(index, clip);
    }

    public void removeClip(@NonNull Clip clip) {
        clips.remove(clip);
    }

    public void removeClip(int index) {
        if (index >= 0 && index < clips.size()) {
            clips.remove(index);
        }
    }

    /**
     * Returns an unmodifiable view of the clip list.
     */
    @NonNull
    public List<Clip> getClips() {
        return Collections.unmodifiableList(clips);
    }

    /**
     * Returns the clip at the given index, or null if out of bounds.
     */
    @NonNull
    public Clip getClip(int index) {
        return clips.get(index);
    }

    public int getClipCount() {
        return clips.size();
    }

    public boolean isEmpty() {
        return clips.isEmpty();
    }

    /**
     * Total duration of the timeline in milliseconds.
     * This is the maximum of the video track duration and the furthest audio clip end.
     */
    public long getTotalDurationMs() {
        long videoTotal = 0;
        for (Clip clip : clips) {
            videoTotal += clip.getTrimmedDurationMs();
        }
        long audioEnd = 0;
        for (AudioClip ac : audioClips) {
            long end = ac.getEndOnTimelineMs();
            if (end > audioEnd) audioEnd = end;
        }
        return Math.max(videoTotal, audioEnd);
    }

    /**
     * Total duration of the video track only (sum of all trimmed clip durations).
     */
    public long getVideoTrackDurationMs() {
        long total = 0;
        for (Clip clip : clips) {
            total += clip.getTrimmedDurationMs();
        }
        return total;
    }

    /**
     * Swap clip ordering (for drag-to-reorder).
     */
    public void swapClips(int fromIndex, int toIndex) {
        if (fromIndex >= 0 && fromIndex < clips.size()
                && toIndex >= 0 && toIndex < clips.size()) {
            Collections.swap(clips, fromIndex, toIndex);
        }
    }

    /**
     * Move a clip from one position to another (insert at new position).
     * This removes the clip from the old position and inserts it at the new position.
     *
     * @param fromIndex current index of the clip
     * @param toIndex   target index for the clip
     */
    public void moveClip(int fromIndex, int toIndex) {
        if (fromIndex < 0 || fromIndex >= clips.size()
                || toIndex < 0 || toIndex >= clips.size()
                || fromIndex == toIndex) return;
        Clip clip = clips.remove(fromIndex);
        clips.add(toIndex, clip);
    }

    /**
     * Split a clip at the given absolute position into two new clips.
     *
     * <p>The original clip is replaced by two clips:
     * <ul>
     *   <li>Clip A: same source, inPoint = original.inPoint, outPoint = splitPointMs</li>
     *   <li>Clip B: same source, inPoint = splitPointMs, outPoint = original.outPoint</li>
     * </ul>
     * Both inherit all effects (speed, volume, rotate, flip, crop) from the original.</p>
     *
     * @param clipIndex       index of the clip to split
     * @param splitPointMs    absolute position in the source video to split at
     * @return the index of the first part (clipA), or -1 if invalid
     */
    public int splitAt(int clipIndex, long splitPointMs) {
        if (clipIndex < 0 || clipIndex >= clips.size()) return -1;

        Clip original = clips.get(clipIndex);

        // Validate split point is within the clip's trim region (with margin)
        long minSplitMs = original.getInPointMs() + 100; // At least 100ms from start
        long maxSplitMs = original.getOutPointMs() - 100; // At least 100ms from end
        if (splitPointMs < minSplitMs || splitPointMs > maxSplitMs) return -1;

        // Create two copies with the same effects
        Clip clipA = new Clip(original);
        clipA.setOutPointMs(splitPointMs);

        Clip clipB = new Clip(original);
        clipB.setInPointMs(splitPointMs);

        // Replace original with the two parts
        clips.remove(clipIndex);
        clips.add(clipIndex, clipB);
        clips.add(clipIndex, clipA); // A goes first

        return clipIndex;
    }

    /**
     * Duplicate a clip at the given index. The copy is inserted
     * immediately after the original.
     *
     * @param clipIndex index of the clip to duplicate
     * @return the index of the new copy, or -1 if invalid
     */
    public int duplicateClip(int clipIndex) {
        if (clipIndex < 0 || clipIndex >= clips.size()) return -1;
        Clip copy = new Clip(clips.get(clipIndex));
        clips.add(clipIndex + 1, copy);
        return clipIndex + 1;
    }

    @NonNull
    @Override
    public String toString() {
        return "Timeline{clips=" + clips.size()
                + ", audioClips=" + audioClips.size()
                + ", totalMs=" + getTotalDurationMs() + "}";
    }

    // ── Audio clip management ────────────────────────────────────────

    /**
     * Add an audio clip to the audio track.
     */
    public void addAudioClip(@NonNull AudioClip audioClip) {
        audioClips.add(audioClip);
    }

    /**
     * Remove an audio clip from the audio track.
     */
    public void removeAudioClip(@NonNull AudioClip audioClip) {
        audioClips.remove(audioClip);
    }

    /**
     * Remove an audio clip by index.
     */
    public void removeAudioClip(int index) {
        if (index >= 0 && index < audioClips.size()) {
            audioClips.remove(index);
        }
    }

    /**
     * Returns an unmodifiable view of the audio clip list.
     */
    @NonNull
    public List<AudioClip> getAudioClips() {
        return Collections.unmodifiableList(audioClips);
    }

    /**
     * Returns the audio clip at the given index, or null if out of bounds.
     */
    @Nullable
    public AudioClip getAudioClip(int index) {
        if (index < 0 || index >= audioClips.size()) return null;
        return audioClips.get(index);
    }

    /**
     * Returns the number of audio clips.
     */
    public int getAudioClipCount() {
        return audioClips.size();
    }

    /**
     * Whether there are any audio clips.
     */
    public boolean hasAudioClips() {
        return !audioClips.isEmpty();
    }
}
