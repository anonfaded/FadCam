package com.fadcam.forensics.service;

import android.graphics.Matrix;

import androidx.annotation.NonNull;

/**
 * Normalized transform for evidence snapshots, shared by writer/viewer paths.
 */
public final class FrameOrientationTransform {

    public final int rotationDegrees;
    public final boolean mirrorHorizontally;
    public final boolean mirrorVertically;

    public FrameOrientationTransform(int rotationDegrees, boolean mirrorHorizontally, boolean mirrorVertically) {
        this.rotationDegrees = normalize(rotationDegrees);
        this.mirrorHorizontally = mirrorHorizontally;
        this.mirrorVertically = mirrorVertically;
    }

    @NonNull
    public Matrix toMatrix(int width, int height) {
        Matrix matrix = new Matrix();
        if (mirrorHorizontally || mirrorVertically) {
            float sx = mirrorHorizontally ? -1f : 1f;
            float sy = mirrorVertically ? -1f : 1f;
            matrix.postScale(sx, sy, width * 0.5f, height * 0.5f);
        }
        if (rotationDegrees != 0) {
            matrix.postRotate(rotationDegrees, width * 0.5f, height * 0.5f);
        }
        return matrix;
    }

    private static int normalize(int degrees) {
        int value = degrees % 360;
        if (value < 0) value += 360;
        return (value / 90) * 90;
    }
}
