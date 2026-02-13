package com.fadcam.motion.domain.policy;

import com.fadcam.motion.domain.model.MotionSettings;
import com.fadcam.motion.domain.model.MotionSignal;
import com.fadcam.motion.domain.model.MotionTriggerMode;
import com.fadcam.motion.domain.state.MotionSessionState;

public class MotionPolicy {

    public boolean isTriggerSatisfied(MotionSettings settings, MotionSignal signal, MotionSessionState state) {
        float requiredScore = (state == MotionSessionState.RECORDING || state == MotionSessionState.POST_ROLL)
                ? stopThresholdFromSensitivity(settings.getSensitivity())
                : startThresholdFromSensitivity(settings.getSensitivity());
        if (signal.getMotionScore() < requiredScore) {
            return false;
        }
        return settings.getTriggerMode() == MotionTriggerMode.ANY_MOTION || signal.isPersonDetected();
    }

    public float startThresholdFromSensitivity(int sensitivity) {
        int clamped = Math.max(0, Math.min(100, sensitivity));
        // Higher sensitivity lowers threshold. Tuned for faster indoor trigger response.
        return 0.78f - (clamped / 100f) * 0.70f;
    }

    public float stopThresholdFromSensitivity(int sensitivity) {
        // Hysteresis: use a lower threshold to stay in RECORDING and reduce flapping.
        return Math.max(0.05f, startThresholdFromSensitivity(sensitivity) - 0.14f);
    }
}
