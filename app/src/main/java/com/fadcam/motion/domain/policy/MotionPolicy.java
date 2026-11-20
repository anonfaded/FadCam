package com.fadcam.motion.domain.policy;

import com.fadcam.motion.domain.model.MotionSettings;
import com.fadcam.motion.domain.model.MotionSignal;
import com.fadcam.motion.domain.model.MotionTriggerMode;
import com.fadcam.motion.domain.state.MotionSessionState;

public class MotionPolicy {

    public boolean isTriggerSatisfied(MotionSettings settings, MotionSignal signal, MotionSessionState state) {
        float requiredScore;
        if (state == MotionSessionState.RECORDING
                || state == MotionSessionState.POST_ROLL
                || state == MotionSessionState.PENDING) {
            // While arming/holding, use stop threshold so short dips do not cancel a valid trigger.
            requiredScore = stopThresholdFromSensitivity(settings.getSensitivity());
        } else {
            requiredScore = startThresholdFromSensitivity(settings.getSensitivity());
        }
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
        // Hysteresis: keep some separation from the start threshold, but not so low
        // that quiet-scene noise keeps a session stuck in RECORDING.
        float start = startThresholdFromSensitivity(sensitivity);
        // Hysteresis band: 0.05 normally, narrowing only near the top of the
        // sensitivity range. The absolute floor (0.06) sits below the lowest possible
        // start threshold (0.08), so "stop" can never become stricter than "start".
        // That inversion is what broke high-sensitivity sessions: at sensitivity 100
        // the old 0.12 floor sat above the 0.08 start threshold, so a clip stopped
        // the instant it began and nothing was ever recorded.
        return Math.max(0.06f, start - 0.05f);
    }
}
