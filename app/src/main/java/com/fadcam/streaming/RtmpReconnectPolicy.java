package com.fadcam.streaming;

/** Bounded exponential backoff for transient RTMP failures. */
public final class RtmpReconnectPolicy {
    private final int maxAttempts;
    private final long initialDelayMs;
    private final long maxDelayMs;
    private int attempts;

    public RtmpReconnectPolicy() {
        this(8, 1_000L, 60_000L);
    }

    public RtmpReconnectPolicy(int maxAttempts, long initialDelayMs, long maxDelayMs) {
        if (maxAttempts < 1 || initialDelayMs < 1 || maxDelayMs < initialDelayMs) {
            throw new IllegalArgumentException("Invalid reconnect policy");
        }
        this.maxAttempts = maxAttempts;
        this.initialDelayMs = initialDelayMs;
        this.maxDelayMs = maxDelayMs;
    }

    public synchronized void reset() { attempts = 0; }

    /** Returns the next delay, or -1 after the retry budget is exhausted. */
    public synchronized long nextDelayMs() {
        if (attempts >= maxAttempts) return -1L;
        long delay = initialDelayMs;
        for (int i = 1; i < attempts; i++) {
            if (delay >= maxDelayMs / 2L) { delay = maxDelayMs; break; }
            delay *= 2L;
        }
        attempts++;
        return Math.min(delay, maxDelayMs);
    }

    public synchronized int getAttempts() { return attempts; }
    public int getMaxAttempts() { return maxAttempts; }
}
