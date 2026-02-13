package com.fadcam.motion.domain.detector;

import android.media.Image;

/**
 * Placeholder person detector for Sprint 1.
 * Returns false until TFLite integration is added in Sprint 2.
 */
public class NoOpPersonDetector implements PersonDetector {
    @Override
    public boolean detectPerson(Image image) {
        return false;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public float getLastConfidence() {
        return 0f;
    }
}
