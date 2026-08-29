package com.fadcam.streaming;

/**
 * Deterministic, bounded exponential backoff for transient RTMP failures.
 *
 * <p>{@code maxAttempts} is the number of reconnect attempts permitted. Each
 * successful connection should call {@link #reset()} so a later outage gets a
 * fresh retry budget. Delays are capped without overflowing {@code long}.</p>
 */
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

    /** Resets the retry budget after a successful connection or a new session. */
    public synchronized void reset() {
        attempts = 0;
    }

    /** Returns the delay for the next reconnect attempt, or -1 when exhausted. */
    public synchronized long nextDelayMs() {
        if (attempts >= maxAttempts) return -1L;

        long delay = initialDelayMs;
        for (int i = 0; i < attempts; i++) {
            if (delay >= maxDelayMs) {
                delay = maxDelayMs;
                break;
            }
            // Avoid overflow while preserving the exponential sequence.
            if (delay > maxDelayMs / 2L) {
                delay = maxDelayMs;
                break;
            }
            delay *= 2L;
        }

        attempts++;
        return Math.min(delay, maxDelayMs);
    }

    public synchronized int getAttempts() {
        return attempts;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public long getMaxDelayMs() {
        return maxDelayMs;
    }
}
