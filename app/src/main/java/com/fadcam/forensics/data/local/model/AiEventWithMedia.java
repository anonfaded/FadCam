package com.fadcam.forensics.data.local.model;

public class AiEventWithMedia {
    public String eventUid;
    public String mediaUid;
    public String eventType;
    public long startMs;
    public long endMs;
    public float confidence;
    public String bboxNorm;
    public int priority;
    public String thumbnailRef;
    public long detectedAtEpochMs;

    public String mediaUri;
    public String mediaDisplayName;
    public String linkStatus;
    public long mediaLastSeenAt;
}
