package com.fadcam.forensics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import com.fadcam.util.CaptureThrottlePolicy;

import org.junit.Test;

/**
 * Edge-case coverage for the evidence-gallery capture policy.
 *
 * <p>These tests exercise the real production class ({@link CaptureThrottlePolicy}),
 * not a copy of its rules.
 *
 * <p>Original bug: the recorder computed a minimum interval and then ignored it —
 * both branches of the decision were identical — so a person standing in frame
 * produced a snapshot on almost every 1.6 s heartbeat (thousands per hour).
 */
public class ForensicsSnapshotThrottleTest {

    private static final long NEVER = -1L;

    @Test
    public void firstSightingAlwaysCapturesEvenIfInstantaneous() {
        // A subject that appears and leaves inside one floor window must still be
        // recorded. This is the fast walk-through case.
        assertTrue(CaptureThrottlePolicy.shouldCapture(
                NEVER, 0, /* motionChanged */ false, /* visualChanged */ false));
        // Also when it arrives with everything "unchanged".
        assertTrue(CaptureThrottlePolicy.shouldCapture(NEVER, 7, false, false));
    }

    @Test
    public void changeSignalCannotBypassTheHardFloor() {
        // 1 ms after the last capture, even a large change is refused.
        assertFalse(CaptureThrottlePolicy.shouldCapture(
                0L, 3, true, true));
        assertFalse(CaptureThrottlePolicy.shouldCapture(
                CaptureThrottlePolicy.MIN_INTERVAL_MS - 1, 3, true, true));
        // At the floor it is allowed.
        assertTrue(CaptureThrottlePolicy.shouldCapture(
                CaptureThrottlePolicy.MIN_INTERVAL_MS, 3, true, true));
    }

    @Test
    public void betweenFloorAndHeartbeatOnlyRealChangeQualifies() {
        long between = CaptureThrottlePolicy.MIN_INTERVAL_MS + 1_000L;
        assertFalse("no change must not produce a photo",
                CaptureThrottlePolicy.shouldCapture(between, 3, false, false));
        assertTrue("moved subject must produce a photo",
                CaptureThrottlePolicy.shouldCapture(between, 3, true, false));
        assertTrue("changed scene must produce a photo",
                CaptureThrottlePolicy.shouldCapture(between, 3, false, true));
    }

    @Test
    public void heartbeatKeepsLongStandingEventDocumented() {
        assertFalse(CaptureThrottlePolicy.shouldCapture(
                CaptureThrottlePolicy.HEARTBEAT_MS - 1, 3, false, false));
        assertTrue(CaptureThrottlePolicy.shouldCapture(
                CaptureThrottlePolicy.HEARTBEAT_MS, 3, false, false));
    }

    @Test
    public void perEventBudgetThenSlowCadence() {
        int spent = CaptureThrottlePolicy.MAX_PER_EVENT;
        // Budget spent: ordinary heartbeat no longer applies.
        assertFalse(CaptureThrottlePolicy.shouldCapture(
                CaptureThrottlePolicy.HEARTBEAT_MS, spent, true, true));
        // Only the low-power cadence does.
        assertFalse(CaptureThrottlePolicy.shouldCapture(
                CaptureThrottlePolicy.LOW_POWER_INTERVAL_MS - 1, spent, true, true));
        assertTrue(CaptureThrottlePolicy.shouldCapture(
                CaptureThrottlePolicy.LOW_POWER_INTERVAL_MS, spent, true, true));
    }

    @Test
    public void continuousMotionCannotFloodPerMinute() {
        int captured = 0;
        int count = 0;
        long last = -1;
        // 1.6 s sampling, subject always "changing".
        for (long t = 0; t <= 60_000L; t += 1_600) {
            long elapsed = last < 0 ? NEVER : t - last;
            if (CaptureThrottlePolicy.shouldCapture(elapsed, count, true, true)) {
                captured++;
                count++;
                last = t;
            }
        }
        // 60 s / 6 s floor = at most 11 captures (previously ~37).
        assertTrue("one minute produced " + captured + " photos", captured <= 11);
    }

    @Test
    public void tenMinutesOfPresenceStaysBounded() {
        int captured = 0;
        int count = 0;
        long last = -1;
        for (long t = 0; t <= 600_000L; t += 1_600) {
            long elapsed = last < 0 ? NEVER : t - last;
            if (CaptureThrottlePolicy.shouldCapture(elapsed, count, true, true)) {
                captured++;
                count++;
                last = t;
            }
        }
        // 15 budgeted captures + low-power heartbeat afterwards.
        assertTrue("10 minutes produced " + captured + " photos (was ~1000)", captured <= 30);
    }

    @Test
    public void aQuietSceneProducesAlmostNothing() {
        int captured = 0;
        int count = 0;
        long last = -1;
        for (long t = 0; t <= 600_000L; t += 1_600) {
            long elapsed = last < 0 ? NEVER : t - last;
            if (CaptureThrottlePolicy.shouldCapture(elapsed, count, false, false)) {
                captured++;
                count++;
                last = t;
            }
        }
        // First capture + one per heartbeat (12 s) = 1 + ~50 over 10 min.
        assertTrue("quiet scene produced " + captured, captured <= 55);
    }

    // ── IoU event matching ──────────────────────────────────────────────────

    @Test
    public void samePersonMovingContinuesTheSameEvent() {
        // Overlapping boxes => same event, so no new per-event budget is granted.
        assertTrue(CaptureThrottlePolicy.continuesEvent(
                0.50f, 0.50f, 0.20f, 0.40f,
                0.54f, 0.51f, 0.20f, 0.40f));
    }

    @Test
    public void smallShiftAcrossAFrameStillMatches() {
        // A person walking: 4% horizontal shift, identical size.
        assertTrue(CaptureThrottlePolicy.continuesEvent(
                0.40f, 0.50f, 0.22f, 0.42f,
                0.44f, 0.50f, 0.22f, 0.42f));
    }

    @Test
    public void twoSeparatePeopleDoNotShareAnEvent() {
        // Opposite sides of the frame, no overlap.
        assertFalse(CaptureThrottlePolicy.continuesEvent(
                0.20f, 0.50f, 0.15f, 0.30f,
                0.80f, 0.50f, 0.15f, 0.30f));
    }

    @Test
    public void aJumpedSubjectStartsANewEvent() {
        // No overlap at all => new subject, new budget (intended).
        assertFalse(CaptureThrottlePolicy.continuesEvent(
                0.10f, 0.10f, 0.10f, 0.10f,
                0.90f, 0.90f, 0.10f, 0.10f));
    }

    @Test
    public void iouIsSymmetricAndBoundsCorrectly() {
        float a = CaptureThrottlePolicy.iou(0.5f, 0.5f, 0.2f, 0.2f,
                0.5f, 0.5f, 0.2f, 0.2f);
        assertEquals("identical boxes must be 1.0", 1.0f, a, 1e-5f);
        assertEquals("no overlap must be 0.0", 0.0f,
                CaptureThrottlePolicy.iou(0.1f, 0.1f, 0.1f, 0.1f,
                        0.9f, 0.9f, 0.1f, 0.1f), 1e-6f);
        float ab = CaptureThrottlePolicy.iou(0.50f, 0.50f, 0.20f, 0.20f,
                0.55f, 0.50f, 0.20f, 0.20f);
        float ba = CaptureThrottlePolicy.iou(0.55f, 0.50f, 0.20f, 0.20f,
                0.50f, 0.50f, 0.20f, 0.20f);
        assertEquals("IoU must be symmetric", ab, ba, 1e-6f);
    }
}
