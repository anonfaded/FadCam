package com.fadcam.motion.domain.detector;

public interface MotionDebugInfoProvider {
    float getLastChangedAreaRatio();

    float getLastStrongAreaRatio();

    float getLastMeanDelta();

    float getLastBackgroundDelta();

    float getLastMaxDelta();

    boolean isLastGlobalMotionSuppressed();

    default float getLastMotionCenterX() {
        return 0.5f;
    }

    default float getLastMotionCenterY() {
        return 0.5f;
    }

    /** Number of significant foreground blobs in the last frame (0 when unavailable). */
    default int getLastBlobCount() {
        return 0;
    }

    /** Significant blob rectangles in the detector's working frame. */
    default java.util.List<android.graphics.Rect> getLastBlobRects() {
        return java.util.Collections.emptyList();
    }

    /** Detector working width (0 when unavailable). */
    default int getWorkingWidth() {
        return 0;
    }

    /** Detector working height (0 when unavailable). */
    default int getWorkingHeight() {
        return 0;
    }

    /** Current minimum accepted blob area (fraction of frame). */
    default float getMinBlobAreaRatio() {
        return 0f;
    }
}
