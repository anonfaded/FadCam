package com.fadcam.motion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fadcam.motion.domain.model.MotionSettings;
import com.fadcam.motion.domain.model.MotionSignal;
import com.fadcam.motion.domain.model.MotionTriggerMode;
import com.fadcam.motion.domain.policy.MotionPolicy;
import com.fadcam.motion.domain.state.MotionSessionState;

import org.junit.Test;

/** Verifies the sensitivity → threshold mapping and the trigger rules. */
public class MotionPolicyTest {

    private final MotionPolicy policy = new MotionPolicy();

    private static MotionSettings settings(int sensitivity, MotionTriggerMode mode) {
        return new MotionSettings(
                true, mode, sensitivity, 10, 720, 220, 10_000, 2, false);
    }

    private static MotionSignal signal(long ts, float score, boolean person) {
        return new MotionSignal(ts, score, person);
    }

    @Test
    public void startThresholdFallsAsSensitivityRises() {
        float low = policy.startThresholdFromSensitivity(0);
        float mid = policy.startThresholdFromSensitivity(50);
        float high = policy.startThresholdFromSensitivity(100);
        assertTrue("0 should be strictest", low > mid);
        assertTrue("100 should be most permissive", mid > high);
        // Documented endpoints: 0.78 at 0, 0.08 at 100.
        assertEquals(0.78f, low, 0.0001f);
        assertEquals(0.08f, high, 0.0001f);
    }

    @Test
    public void sensitivityIsClampedToValidRange() {
        assertEquals(policy.startThresholdFromSensitivity(0),
                policy.startThresholdFromSensitivity(-50), 0.0001f);
        assertEquals(policy.startThresholdFromSensitivity(100),
                policy.startThresholdFromSensitivity(500), 0.0001f);
    }

    @Test
    public void stopThresholdIsBelowStartAndHasFloor() {
        for (int s = 0; s <= 100; s++) {
            float start = policy.startThresholdFromSensitivity(s);
            float stop = policy.stopThresholdFromSensitivity(s);
            assertTrue("stop must be lower than start at sensitivity " + s, stop < start);
            // Hysteresis must be a real band, not a hairline: otherwise a single
            // alternating frame would flap the session.
            assertTrue("stop must leave room below start at sensitivity " + s,
                    start - stop >= 0.015f);
            assertTrue("stop must keep a noise floor", stop >= 0.06f);
        }
    }

    @Test
    public void idleUsesStartThreshold() {
        MotionSettings s = settings(50, MotionTriggerMode.ANY_MOTION);
        float start = policy.startThresholdFromSensitivity(50);
        assertTrue(policy.isTriggerSatisfied(s, signal(0, start + 0.01f, false),
                MotionSessionState.IDLE));
        assertFalse(policy.isTriggerSatisfied(s, signal(0, start - 0.01f, false),
                MotionSessionState.IDLE));
    }

    @Test
    public void recordingUsesStopThresholdSoShortDipsDoNotCancel() {
        MotionSettings s = settings(50, MotionTriggerMode.ANY_MOTION);
        float stop = policy.stopThresholdFromSensitivity(50);
        float start = policy.startThresholdFromSensitivity(50);
        // Between stop and start: must keep the session alive while recording.
        float between = (stop + start) / 2f;
        assertTrue(policy.isTriggerSatisfied(s, signal(0, between, false),
                MotionSessionState.RECORDING));
        // Same score at IDLE would not have started a new clip.
        assertFalse(policy.isTriggerSatisfied(s, signal(0, between, false),
                MotionSessionState.IDLE));
    }

    @Test
    public void personConfirmedModeRequiresPerson() {
        MotionSettings s = settings(90, MotionTriggerMode.PERSON_CONFIRMED);
        MotionSignal highMotionNoPerson = signal(0, 0.99f, false);
        MotionSignal person = signal(0, 0.99f, true);
        assertFalse(policy.isTriggerSatisfied(s, highMotionNoPerson, MotionSessionState.IDLE));
        assertTrue(policy.isTriggerSatisfied(s, person, MotionSessionState.IDLE));
    }

    @Test
    public void anyMotionModeIgnoresPersonFlag() {
        MotionSettings s = settings(90, MotionTriggerMode.ANY_MOTION);
        assertTrue(policy.isTriggerSatisfied(s, signal(0, 0.99f, false),
                MotionSessionState.IDLE));
    }

    @Test
    public void higherSensitivityStartsOnWeakerMotion() {
        MotionSignal weak = signal(0, 0.30f, false);
        MotionSettings strict = settings(10, MotionTriggerMode.ANY_MOTION);
        MotionSettings lenient = settings(95, MotionTriggerMode.ANY_MOTION);
        assertFalse("low sensitivity must ignore weak motion",
                policy.isTriggerSatisfied(strict, weak, MotionSessionState.IDLE));
        assertTrue("high sensitivity must accept weak motion",
                policy.isTriggerSatisfied(lenient, weak, MotionSessionState.IDLE));
    }
}
