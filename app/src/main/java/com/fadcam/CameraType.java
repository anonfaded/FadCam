package com.fadcam;

import androidx.annotation.NonNull;

import java.io.Serializable;

public enum CameraType implements Serializable {
    FRONT(1),
    BACK(0);

    private final int cameraId;

    CameraType(int cameraId) {
        this.cameraId = cameraId;
    }

    public int getCameraId() {
        return cameraId;
    }

    @NonNull
    @Override
    public String toString() {
        return name();
    }
}
