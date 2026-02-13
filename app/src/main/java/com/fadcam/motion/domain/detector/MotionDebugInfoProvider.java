package com.fadcam.motion.domain.detector;

public interface MotionDebugInfoProvider {
    float getLastChangedAreaRatio();

    float getLastStrongAreaRatio();

    float getLastMeanDelta();

    float getLastBackgroundDelta();

    float getLastMaxDelta();

    boolean isLastGlobalMotionSuppressed();
}
