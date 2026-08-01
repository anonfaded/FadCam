package com.fadcam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class RecordingDurationLimitControllerTest {
    @Test
    public void countdownBeginsOnlyAfterConfirmedSessionStart() {
        FakeScheduler scheduler = new FakeScheduler();
        long[] limitMs = {50L};
        int[] stops = {0};
        RecordingDurationLimitController controller = createController(
                scheduler, limitMs, stops);

        scheduler.advanceBy(500L);
        assertEquals(0, stops[0]);

        assertEquals(50L, controller.startSession());
        scheduler.advanceBy(49L);
        assertEquals(0, stops[0]);
        scheduler.advanceBy(1L);
        assertEquals(1, stops[0]);
    }

    @Test
    public void noLimitSchedulesNothingAndShorterLimitStopsCurrentSession() {
        FakeScheduler scheduler = new FakeScheduler();
        long[] limitMs = {0L};
        int[] stops = {0};
        RecordingDurationLimitController controller = createController(
                scheduler, limitMs, stops);

        assertEquals(0L, controller.startSession());
        assertEquals(0, scheduler.pendingTaskCount());
        scheduler.advanceBy(10_000L);
        assertEquals(0, stops[0]);

        limitMs[0] = 50L;
        assertTrue(controller.onLimitChanged());
        scheduler.advanceBy(0L);
        assertEquals(1, stops[0]);
    }

    @Test
    public void changingLimitDoesNotResetElapsedRecordingTime() {
        FakeScheduler scheduler = new FakeScheduler();
        long[] limitMs = {100L};
        int[] stops = {0};
        RecordingDurationLimitController controller = createController(
                scheduler, limitMs, stops);

        controller.startSession();
        scheduler.advanceBy(40L);
        assertTrue(controller.onLimitChanged());
        scheduler.advanceBy(59L);
        assertEquals(0, stops[0]);
        scheduler.advanceBy(1L);
        assertEquals(1, stops[0]);
    }

    @Test
    public void pausedTimeIsExcludedFromRecordingDuration() {
        FakeScheduler scheduler = new FakeScheduler();
        long[] limitMs = {100L};
        int[] stops = {0};
        RecordingDurationLimitController controller = createController(
                scheduler, limitMs, stops);

        controller.startSession();
        scheduler.advanceBy(40L);
        assertTrue(controller.pauseSession());
        scheduler.advanceBy(1_000L);
        assertEquals(0, stops[0]);

        assertTrue(controller.resumeSession());
        scheduler.advanceBy(59L);
        assertEquals(0, stops[0]);
        scheduler.advanceBy(1L);
        assertEquals(1, stops[0]);
    }

    @Test
    public void manualStopInvalidatesCallbackBeforeLaterSession() {
        FakeScheduler scheduler = new FakeScheduler();
        long[] limitMs = {50L};
        int[] stops = {0};
        RecordingDurationLimitController controller = createController(
                scheduler, limitMs, stops);

        controller.startSession();
        Runnable staleCallback = scheduler.lastScheduledRunnable();
        assertTrue(controller.stopSession());
        controller.startSession();

        staleCallback.run();
        assertEquals(0, stops[0]);
        scheduler.advanceBy(50L);
        assertEquals(1, stops[0]);
    }

    @Test
    public void switchingToNoLimitInvalidatesAlreadyDequeuedCallback() {
        FakeScheduler scheduler = new FakeScheduler();
        long[] limitMs = {50L};
        int[] stops = {0};
        RecordingDurationLimitController controller = createController(
                scheduler, limitMs, stops);

        controller.startSession();
        Runnable staleCallback = scheduler.lastScheduledRunnable();
        limitMs[0] = 0L;
        assertTrue(controller.onLimitChanged());

        staleCallback.run();
        scheduler.advanceBy(500L);
        assertEquals(0, stops[0]);
        assertEquals(0, scheduler.pendingTaskCount());
    }

    private RecordingDurationLimitController createController(
            FakeScheduler scheduler,
            long[] limitMs,
            int[] stops) {
        return new RecordingDurationLimitController(
                scheduler,
                () -> limitMs[0],
                () -> stops[0]++);
    }

    private static final class FakeScheduler
            implements RecordingDurationLimitController.Scheduler {
        private final List<ScheduledTask> tasks = new ArrayList<>();
        private long nowMs;
        private Runnable lastScheduledRunnable;

        @Override
        public long elapsedRealtime() {
            return nowMs;
        }

        @Override
        public void postDelayed(Runnable runnable, long delayMs) {
            lastScheduledRunnable = runnable;
            tasks.add(new ScheduledTask(runnable, nowMs + Math.max(0L, delayMs)));
        }

        @Override
        public void removeCallbacks(Runnable runnable) {
            for (ScheduledTask task : tasks) {
                if (task.runnable == runnable) {
                    task.cancelled = true;
                }
            }
        }

        void advanceBy(long deltaMs) {
            nowMs += deltaMs;
            boolean ranTask;
            do {
                ranTask = false;
                for (ScheduledTask task : new ArrayList<>(tasks)) {
                    if (!task.cancelled && task.dueAtMs <= nowMs) {
                        task.cancelled = true;
                        task.runnable.run();
                        ranTask = true;
                    }
                }
            } while (ranTask);
        }

        int pendingTaskCount() {
            int count = 0;
            for (ScheduledTask task : tasks) {
                if (!task.cancelled) {
                    count++;
                }
            }
            return count;
        }

        Runnable lastScheduledRunnable() {
            return lastScheduledRunnable;
        }
    }

    private static final class ScheduledTask {
        private final Runnable runnable;
        private final long dueAtMs;
        private boolean cancelled;

        ScheduledTask(Runnable runnable, long dueAtMs) {
            this.runnable = runnable;
            this.dueAtMs = dueAtMs;
        }
    }
}
