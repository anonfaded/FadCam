package com.fadcam.forensics.data.local.model;

public class ForensicsSnapshotWithMedia {
    public String snapshotUid;
    public String eventUid;
    public String mediaUid;
    public long capturedEpochMs;
    public long timelineMs;
    public String eventType;
    public String className;
    public float confidence;
    public String bboxNorm;
    public String imageUri;
    public String sha256;

    public String mediaUri;
    public String mediaDisplayName;
    public String linkStatus;
    public long mediaLastSeenAt;
    public boolean mediaMissing;
}
