package com.fadcam.motion.domain.detector;

import android.media.Image;

public interface PersonDetector {
    boolean detectPerson(Image image);
    boolean isAvailable();
    float getLastConfidence();
}
