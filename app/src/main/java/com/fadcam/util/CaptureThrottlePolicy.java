package com.fadcam.util;

/**
 * Pure, generic capture-throttle policy used by the evidence gallery.
 *
 * <p>Extracted from the recorder so it can be unit-tested directly. All the
 * evidence-flood and missed-event rules live here, not in Android/DB code.
 *
 * <p>Rules, in priority order:
 * <ol>
 *   <li><b>First sighting always captures.</b> Someone who walks past in under one
 *       sample interval must still be recorded.</li>
 *   <li><b>Hard per-event floor.</b> No change signal can bypass it. A live subject
 *       always "changes", so a floor that can be bypassed is not a floor.</li>
 *   <li><b>Per-event budget.</b> After N captures, only a slow heartbeat remains, so
 *       a subject who stays for hours cannot fill the gallery.</li>
 *   <li><b>Heartbeat.</b> A long-standing event still gets periodic evidence.</li>
 *   <li><b>Meaningful change.</b> Between the floor and the heartbeat, only real
 *       movement/size/confidence/scene change earns a new photo.</li>
 * </ol>
 */
public final class CaptureThrottlePolicy {

    /** Hard floor between two captures of the same event. */
    public static final long MIN_INTERVAL_MS = 6_000L;
    /** Periodic capture for a subject that stays in frame. */
    public static final long HEARTBEAT_MS = 12_000L;
    /** Captures a single event may collect before dropping to the low-power cadence. */
    public static final int MAX_PER_EVENT = 15;
    /** Cadence once the per-event budget is spent. */
    public static final long LOW_POWER_INTERVAL_MS = 60_000L;

    /** Minimum overlap for a detection to continue an existing event. */
    public static final float IOU_MATCH_THRESHOLD = 0.25f;

    public static final float CENTER_DELTA_THRESHOLD = 0.04f;
    public static final float SIZE_DELTA_THRESHOLD = 0.06f;
    public static final float CONFIDENCE_DELTA_THRESHOLD = 0.10f;
    public static final int PHASH_HAMMING_THRESHOLD = 4;

    /**
     * Minimum spacing between any two captures for the same recording, regardless of
     * how many distinct subjects are in frame.
     *
     * <p>The per-event floor alone is not enough. When a detector re-labels the same
     * subject (person, then skateboard, then person again) each new label is a new
     * "first sighting", and first sightings always capture. A long test recording
     * produced 280 snapshots in seven minutes that way — over one snapshot every
     * 1.5 s, all of the same scene. This floor bounds the total evidence rate while
     * still letting several genuinely different subjects be recorded.
     */
    public static final long MEDIA_MIN_INTERVAL_MS = 4_000L;

    private CaptureThrottlePolicy() {
    }

    /**
     * @param elapsedMs     time since this event's last capture, or a negative value
     *                      when it has never been captured
     * @param snapshotCount captures already taken for this event
     * @param motionChanged centre/size/confidence moved beyond their thresholds
     * @param visualChanged the perceptual hash moved beyond its threshold
     * @return true when a new snapshot should be stored
     */
    public static boolean shouldCapture(long elapsedMs,
                                        int snapshotCount,
                                        boolean motionChanged,
                                        boolean visualChanged) {
        // 1. Never captured: capture immediately. This is what guarantees a fast
        //    appearance is not missed.
        if (elapsedMs < 0L) {
            return true;
        }
        // 2. Hard floor. Nothing bypasses it.
        if (elapsedMs < MIN_INTERVAL_MS) {
            return false;
        }
        // 3. Budget spent: slow heartbeat only.
        if (snapshotCount >= MAX_PER_EVENT) {
            return elapsedMs >= LOW_POWER_INTERVAL_MS;
        }
        // 4. Periodic record.
        if (elapsedMs >= HEARTBEAT_MS) {
            return true;
        }
        // 5. Between floor and heartbeat, only a meaningful change qualifies.
        return motionChanged || visualChanged;
    }

    /**
     * Intersection-over-union of two normalised boxes given as centre + size.
     * Returns 0 when they do not overlap.
     */
    public static float iou(float ax, float ay, float aw, float ah,
                            float bx, float by, float bw, float bh) {
        float ax0 = ax - aw / 2f;
        float ax1 = ax + aw / 2f;
        float ay0 = ay - ah / 2f;
        float ay1 = ay + ah / 2f;
        float bx0 = bx - bw / 2f;
        float bx1 = bx + bw / 2f;
        float by0 = by - bh / 2f;
        float by1 = by + bh / 2f;
        float ix = Math.max(0f, Math.min(ax1, bx1) - Math.max(ax0, bx0));
        float iy = Math.max(0f, Math.min(ay1, by1) - Math.max(ay0, by0));
        float inter = ix * iy;
        if (inter <= 0f) {
            return 0f;
        }
        float union = (aw * ah) + (bw * bh) - inter;
        return union <= 0f ? 0f : inter / union;
    }

    /** True when a detection at this position continues the given event. */
    public static boolean continuesEvent(float centerX, float centerY,
                                         float width, float height,
                                         float lastCenterX, float lastCenterY,
                                         float lastWidth, float lastHeight) {
        return iou(centerX, centerY, width, height,
                lastCenterX, lastCenterY, lastWidth, lastHeight) > IOU_MATCH_THRESHOLD;
    }

    /**
     * Total capture budget for one recording, applied before the per-event rules.
     *
     * @param elapsedSinceMediaCaptureMs time since the last capture in this recording,
     *                                   or a negative value when there has been none
     * @return true when another capture may be stored at all
     */
    public static boolean mediaBudgetAllows(long elapsedSinceMediaCaptureMs) {
        return elapsedSinceMediaCaptureMs < 0L
                || elapsedSinceMediaCaptureMs >= MEDIA_MIN_INTERVAL_MS;
    }
}
