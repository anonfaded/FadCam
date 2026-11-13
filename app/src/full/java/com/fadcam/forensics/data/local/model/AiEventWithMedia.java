package com.fadcam.forensics.data.local.model;

public class AiEventWithMedia {
    public String eventUid;
    public String mediaUid;
    public String eventType;
    public String className;
    public long startMs;
    public long endMs;
    public float confidence;
    public String bboxNorm;
    public int priority;
    public String thumbnailRef;
    public long detectedAtEpochMs;
    public String status;
    public long firstSeenEpochMs;
    public long lastSeenEpochMs;
    public int sampleCount;
    public float peakConfidence;
    public boolean mediaMissing;
    public String alertState;
    public String alertChannel;

    public String mediaUri;
    public String mediaDisplayName;
    public String linkStatus;
    public long mediaLastSeenAt;
}
