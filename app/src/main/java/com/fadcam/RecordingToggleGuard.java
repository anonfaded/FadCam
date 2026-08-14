package com.fadcam;

/**
 * Process-local guard that suppresses duplicate recording toggle requests.
 */
final class RecordingToggleGuard {
    static final long MIN_TOGGLE_INTERVAL_MS = 1000L;

    private long lastAcceptedAt = Long.MIN_VALUE;

    synchronized boolean tryAcquire(long elapsedRealtimeMs) {
        if (lastAcceptedAt != Long.MIN_VALUE
                && elapsedRealtimeMs >= lastAcceptedAt
                && elapsedRealtimeMs - lastAcceptedAt < MIN_TOGGLE_INTERVAL_MS) {
            return false;
        }
        lastAcceptedAt = elapsedRealtimeMs;
        return true;
    }
}
