package com.fadcam;

/**
 * Session-scoped elapsed-realtime countdown for a foreground recording service.
 *
 * <p>The controller deliberately has no Activity or platform alarm dependency. A
 * monotonically increasing session generation prevents a removed or delayed callback
 * from affecting a later recording.</p>
 */
public final class RecordingDurationLimitController {
    public interface Scheduler {
        long elapsedRealtime();

        void postDelayed(Runnable runnable, long delayMs);

        void removeCallbacks(Runnable runnable);
    }

    public interface LimitProvider {
        long getLimitMs();
    }

    private final Scheduler scheduler;
    private final LimitProvider limitProvider;
    private final Runnable onLimitReached;

    private boolean sessionActive;
    private boolean counting;
    private long sessionGeneration;
    private long scheduleGeneration;
    private long accumulatedRecordingMs;
    private long countingStartedAtMs;
    private Runnable scheduledCallback;

    public RecordingDurationLimitController(
            Scheduler scheduler,
            LimitProvider limitProvider,
            Runnable onLimitReached) {
        this.scheduler = scheduler;
        this.limitProvider = limitProvider;
        this.onLimitReached = onLimitReached;
    }

    /** Starts a new recording session and invalidates every callback from an older one. */
    public synchronized long startSession() {
        invalidateScheduleLocked();
        sessionGeneration++;
        sessionActive = true;
        counting = true;
        accumulatedRecordingMs = 0L;
        countingStartedAtMs = scheduler.elapsedRealtime();
        return scheduleLocked();
    }

    /** Stops the current session and invalidates its pending callback. */
    public synchronized boolean stopSession() {
        boolean hadSession = sessionActive || scheduledCallback != null;
        sessionActive = false;
        counting = false;
        accumulatedRecordingMs = 0L;
        sessionGeneration++;
        invalidateScheduleLocked();
        return hadSession;
    }

    /** Pauses the countdown so the limit follows recorded media time, not paused time. */
    public synchronized boolean pauseSession() {
        if (!sessionActive || !counting) {
            return false;
        }
        accumulatedRecordingMs = getElapsedRecordingMsLocked();
        counting = false;
        invalidateScheduleLocked();
        return true;
    }

    /** Resumes a paused session without resetting its accumulated recording time. */
    public synchronized boolean resumeSession() {
        if (!sessionActive || counting) {
            return false;
        }
        counting = true;
        countingStartedAtMs = scheduler.elapsedRealtime();
        scheduleLocked();
        return true;
    }

    /** Re-evaluates the current session after the user changes the configured limit. */
    public synchronized boolean onLimitChanged() {
        if (!sessionActive) {
            return false;
        }
        invalidateScheduleLocked();
        if (counting) {
            scheduleLocked();
        }
        return true;
    }

    private long scheduleLocked() {
        invalidateScheduleLocked();
        long limitMs = Math.max(0L, limitProvider.getLimitMs());
        if (!sessionActive || !counting || limitMs == 0L) {
            return limitMs;
        }

        long elapsedMs = getElapsedRecordingMsLocked();
        long remainingMs = elapsedMs >= limitMs ? 0L : limitMs - elapsedMs;
        long expectedSession = sessionGeneration;
        long expectedSchedule = scheduleGeneration;
        Runnable callback = () -> handleTimeout(expectedSession, expectedSchedule);
        scheduledCallback = callback;
        scheduler.postDelayed(callback, remainingMs);
        return limitMs;
    }

    private void handleTimeout(long expectedSession, long expectedSchedule) {
        boolean notifyLimitReached = false;
        synchronized (this) {
            if (!sessionActive
                    || !counting
                    || expectedSession != sessionGeneration
                    || expectedSchedule != scheduleGeneration) {
                return;
            }

            scheduledCallback = null;
            long limitMs = Math.max(0L, limitProvider.getLimitMs());
            if (limitMs == 0L) {
                return;
            }

            long elapsedMs = getElapsedRecordingMsLocked();
            if (elapsedMs < limitMs) {
                scheduleLocked();
                return;
            }

            sessionActive = false;
            counting = false;
            sessionGeneration++;
            scheduleGeneration++;
            notifyLimitReached = true;
        }

        if (notifyLimitReached) {
            onLimitReached.run();
        }
    }

    private long getElapsedRecordingMsLocked() {
        if (!counting) {
            return accumulatedRecordingMs;
        }
        long nowMs = scheduler.elapsedRealtime();
        long deltaMs = nowMs >= countingStartedAtMs ? nowMs - countingStartedAtMs : 0L;
        if (Long.MAX_VALUE - accumulatedRecordingMs < deltaMs) {
            return Long.MAX_VALUE;
        }
        return accumulatedRecordingMs + deltaMs;
    }

    private void invalidateScheduleLocked() {
        scheduleGeneration++;
        if (scheduledCallback != null) {
            scheduler.removeCallbacks(scheduledCallback);
            scheduledCallback = null;
        }
    }
}
