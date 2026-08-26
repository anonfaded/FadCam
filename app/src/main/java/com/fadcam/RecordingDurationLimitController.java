package com.fadcam;

/**
 * Session-scoped recording-duration controller.
 *
 * <p>Production recording is intentionally not stopped by a wall-clock
 * duration. A recording may continue until the operator stops it or the
 * selected storage cannot accept more data. This compatibility boundary is
 * retained so older callers and settings do not break.</p>
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

    /** Compatibility listener; no countdown ticks are emitted in unlimited mode. */
    public interface RemainingTickListener {
        void onRemainingSecond(int remainingSeconds);
    }

    @SuppressWarnings("unused")
    private final Scheduler scheduler;
    @SuppressWarnings("unused")
    private final LimitProvider limitProvider;
    @SuppressWarnings("unused")
    private final Runnable onLimitReached;
    @SuppressWarnings("unused")
    private RemainingTickListener remainingTickListener;
    private boolean sessionActive;

    public RecordingDurationLimitController(
            Scheduler scheduler,
            LimitProvider limitProvider,
            Runnable onLimitReached) {
        this.scheduler = scheduler;
        this.limitProvider = limitProvider;
        this.onLimitReached = onLimitReached;
    }

    /** Starts a recording session without installing any duration timeout. */
    public synchronized long startSession() {
        sessionActive = true;
        return 0L;
    }

    /** Compatibility hook; intentionally never schedules a countdown. */
    public synchronized void setRemainingTickListener(RemainingTickListener listener) {
        this.remainingTickListener = listener;
    }

    /** Stops this controller session; it does not stop the recording. */
    public synchronized boolean stopSession() {
        boolean hadSession = sessionActive;
        sessionActive = false;
        return hadSession;
    }

    /** Pausing never consumes a duration budget. */
    public synchronized boolean pauseSession() {
        return sessionActive;
    }

    /** Resuming never reinstalls a duration timeout. */
    public synchronized boolean resumeSession() {
        return sessionActive;
    }

    /** Duration preferences no longer control whether a production recording stops. */
    public synchronized boolean onLimitChanged() {
        return sessionActive;
    }
}
