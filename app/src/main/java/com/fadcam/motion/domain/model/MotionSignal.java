package com.fadcam.motion.domain.model;

public class MotionSignal {
    private final long timestampMs;
    private final float motionScore;
    private final boolean personDetected;

    public MotionSignal(long timestampMs, float motionScore, boolean personDetected) {
        this.timestampMs = timestampMs;
        this.motionScore = motionScore;
        this.personDetected = personDetected;
    }

    public long getTimestampMs() {
        return timestampMs;
    }

    public float getMotionScore() {
        return motionScore;
    }

    public boolean isPersonDetected() {
        return personDetected;
    }
}
