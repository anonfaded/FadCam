package com.fadcam.motion.domain.policy;

import com.fadcam.motion.domain.model.MotionSettings;
import com.fadcam.motion.domain.model.MotionSignal;
import com.fadcam.motion.domain.model.MotionTriggerMode;

public class MotionPolicy {

    public boolean isTriggerSatisfied(MotionSettings settings, MotionSignal signal) {
        float requiredScore = scoreThresholdFromSensitivity(settings.getSensitivity());
        if (signal.getMotionScore() < requiredScore) {
            return false;
        }
        return settings.getTriggerMode() == MotionTriggerMode.ANY_MOTION || signal.isPersonDetected();
    }

    public float scoreThresholdFromSensitivity(int sensitivity) {
        int clamped = Math.max(0, Math.min(100, sensitivity));
        // Higher sensitivity lowers threshold.
        return 0.85f - (clamped / 100f) * 0.75f;
    }
}
