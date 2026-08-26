package com.fadcam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class RecordingDurationLimitControllerTest {
    @Test
    public void configuredDurationNeverSchedulesAHardStop() {
        AtomicInteger scheduled = new AtomicInteger();
        AtomicInteger stopped = new AtomicInteger();

        RecordingDurationLimitController controller = new RecordingDurationLimitController(
                new RecordingDurationLimitController.Scheduler() {
                    @Override public long elapsedRealtime() { return 0L; }
                    @Override public void postDelayed(Runnable runnable, long delayMs) { scheduled.incrementAndGet(); }
                    @Override public void removeCallbacks(Runnable runnable) { }
                },
                () -> 5L * 60L * 1000L,
                stopped::incrementAndGet);

        assertEquals(0L, controller.startSession());
        assertTrue(controller.onLimitChanged());
        assertEquals(0, scheduled.get());
        assertEquals(0, stopped.get());
    }

    @Test
    public void pauseAndResumeDoNotCreateAHiddenCountdown() {
        AtomicInteger scheduled = new AtomicInteger();
        RecordingDurationLimitController controller = new RecordingDurationLimitController(
                new RecordingDurationLimitController.Scheduler() {
                    @Override public long elapsedRealtime() { return 1234L; }
                    @Override public void postDelayed(Runnable runnable, long delayMs) { scheduled.incrementAndGet(); }
                    @Override public void removeCallbacks(Runnable runnable) { }
                },
                () -> 60_000L,
                () -> { throw new AssertionError("recording must not be stopped by duration"); });

        controller.startSession();
        assertTrue(controller.pauseSession());
        assertTrue(controller.resumeSession());
        assertEquals(0, scheduled.get());
    }
}
