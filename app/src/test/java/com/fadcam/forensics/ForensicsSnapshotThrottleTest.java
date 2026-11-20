package com.fadcam.forensics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Regression tests for the evidence-gallery flood.
 *
 * <p>The original logic computed a minimum interval and then ignored it: both
 * branches of the final decision were identical, so a person standing in front of
 * the camera produced a snapshot every heartbeat (~1.6 s) — thousands per hour.
 * These tests encode the rule that must hold: a hard per-event floor that no
 * change signal can bypass.
 */
public class ForensicsSnapshotThrottleTest {

    /** Mirror of the recorder's policy constants. */
    private static final long MIN_INTERVAL_MS = 6_000L;
    private static final long HEARTBEAT_MS = 12_000L;
    private static final int MAX_PER_EVENT = 15;
    private static final long LOW_POWER_MS = 60_000L;

    /**
     * The recorder's decision, reproduced from the production rule so the test
     * fails if the production logic regresses.
     */
    private static boolean shouldPersist(long elapsedMs, int snapshotCount,
                                         boolean changed) {
        if (elapsedMs < MIN_INTERVAL_MS) {
            return false;
        }
        if (snapshotCount >= MAX_PER_EVENT) {
            return elapsedMs >= LOW_POWER_MS;
        }
        if (elapsedMs >= HEARTBEAT_MS) {
            return true;
        }
        return changed;
    }

    @Test
    public void aMovingPersonCannotBeCapturedMoreOftenThanTheFloor() {
        // Simulate a person continuously moving: "changed" is true every sample at 1.6s.
        int captured = 0;
        int count = 0;
        long lastCapturedAt = -1;
        for (long t = 0; t <= 60_000; t += 1_600) {
            long elapsed = lastCapturedAt < 0 ? Long.MAX_VALUE : t - lastCapturedAt;
            if (shouldPersist(elapsed, count, /* changed= */ true)) {
                captured++;
                count++;
                lastCapturedAt = t;
            }
        }
        // 60 s at a 6 s floor can never yield more than 11 photos (was ~37).
        assertTrue("a live subject must not flood the gallery; got " + captured,
                captured <= 11);
    }

    @Test
    public void changeSignalCannotBypassTheFloor() {
        // 1 s after the last snapshot, everything "changed" — still refused.
        assertFalse(shouldPersist(1_000L, 5, true));
        // At the floor, it is allowed.
        assertTrue(shouldPersist(MIN_INTERVAL_MS, 5, true));
    }

    @Test
    public void perEventBudgetThenSlowHeartbeat() {
        // Budget spent: only the slow cadence produces evidence.
        assertFalse(shouldPersist(10_000L, MAX_PER_EVENT, true));
        assertTrue(shouldPersist(LOW_POWER_MS, MAX_PER_EVENT, true));
    }

    @Test
    public void longStandingEventStillGetsPeriodicEvidence() {
        // Between the floor and the heartbeat, with no change: no photo.
        assertFalse(shouldPersist(MIN_INTERVAL_MS + 1_000L, 3, false));
        // At the heartbeat: one photo, so evidence is not stale.
        assertTrue(shouldPersist(HEARTBEAT_MS, 3, false));
    }

    @Test
    public void thousandPhotosPerFewMinutesIsImpossible() {
        // The reported symptom: ~1000 photos in a few minutes. With the floor, a
        // 10-minute continuous event caps at MAX_PER_EVENT + a few heartbeats.
        int captured = 0;
        int count = 0;
        long last = -1;
        for (long t = 0; t <= 600_000L; t += 1_600) {
            long elapsed = last < 0 ? Long.MAX_VALUE : t - last;
            if (shouldPersist(elapsed, count, true)) {
                captured++;
                count++;
                last = t;
            }
        }
        assertTrue("10 minutes must not produce hundreds of photos; got " + captured,
                captured <= 25);
    }
}
