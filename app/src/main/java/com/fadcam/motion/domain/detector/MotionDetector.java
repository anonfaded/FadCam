package com.fadcam.motion.domain.detector;

import android.media.Image;

public interface MotionDetector {
    float detectScore(Image image);

    default void setSensitivity(int sensitivity) {
    }
}
