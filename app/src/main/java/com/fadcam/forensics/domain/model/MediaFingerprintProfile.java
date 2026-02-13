package com.fadcam.forensics.domain.model;

public class MediaFingerprintProfile {
    public final String exactFingerprint;
    public final String visualFingerprint;
    public final long sizeBytes;
    public final long durationMs;

    public MediaFingerprintProfile(String exactFingerprint, String visualFingerprint, long sizeBytes, long durationMs) {
        this.exactFingerprint = exactFingerprint;
        this.visualFingerprint = visualFingerprint;
        this.sizeBytes = sizeBytes;
        this.durationMs = durationMs;
    }
}
