package com.fadcam;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RecordingToggleGuardTest {

    @Test
    public void rejectsInvocationInsideGuardWindow() {
        RecordingToggleGuard guard = new RecordingToggleGuard();

        assertTrue(guard.tryAcquire(10_000L));
        assertFalse(guard.tryAcquire(10_999L));
    }

    @Test
    public void acceptsInvocationAtGuardWindowBoundary() {
        RecordingToggleGuard guard = new RecordingToggleGuard();

        assertTrue(guard.tryAcquire(10_000L));
        assertTrue(guard.tryAcquire(
                10_000L + RecordingToggleGuard.MIN_TOGGLE_INTERVAL_MS));
    }

    @Test
    public void acceptsInvocationAfterElapsedRealtimeReset() {
        RecordingToggleGuard guard = new RecordingToggleGuard();

        assertTrue(guard.tryAcquire(10_000L));
        assertTrue(guard.tryAcquire(100L));
    }
}
