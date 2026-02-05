package com.fadcam;

import androidx.annotation.NonNull;

import java.io.Serializable;

public enum CameraType implements Serializable {
    FRONT(1),
    BACK(0),
    /**
     * Dual camera mode — records both front and back simultaneously
     * with a Picture-in-Picture (PiP) composite.
     * The camera ID is meaningless here; dual camera uses concurrent
     * camera IDs resolved by {@link com.fadcam.dualcam.DualCameraCapability}.
     */
    DUAL_PIP(2);

    private final int cameraId;

    CameraType(int cameraId) {
        this.cameraId = cameraId;
    }

    public int getCameraId() {
        return cameraId;
    }

    /** Returns {@code true} if this type uses dual concurrent cameras. */
    public boolean isDual() {
        return this == DUAL_PIP;
    }

    @NonNull
    @Override
    public String toString() {
        return name();
    }
}
