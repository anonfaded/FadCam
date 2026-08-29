package com.fadcam.streaming.model;

/**
 * Represents an authentication session held only in process memory.
 *
 * The bearer token is intentionally not serializable: session credentials must
 * never be persisted, placed in intents/URLs, or emitted through diagnostics.
 */
public final class SessionToken {
    private final String token;
    private final long createdAtMs;
    private final long expiresAtMs;
    private final String deviceInfo;

    public SessionToken(String token, long createdAtMs, long expiresAtMs, String deviceInfo) {
        this.token = token;
        this.createdAtMs = createdAtMs;
        this.expiresAtMs = expiresAtMs;
        this.deviceInfo = deviceInfo == null ? "unknown" : deviceInfo;
    }

    public SessionToken(String token, long createdAtMs, long expiresAtMs) {
        this(token, createdAtMs, expiresAtMs, "unknown");
    }

    public String getToken() {
        return token;
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }

    public long getExpiresAtMs() {
        return expiresAtMs;
    }

    public String getDeviceInfo() {
        return deviceInfo;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAtMs;
    }

    public boolean isValid() {
        return !isExpired() && token != null && !token.isEmpty();
    }
}
