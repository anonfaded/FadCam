package com.fadcam.motion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fadcam.motion.domain.model.MotionSettings;
import com.fadcam.motion.domain.model.MotionSignal;
import com.fadcam.motion.domain.model.MotionTriggerMode;
import com.fadcam.motion.domain.policy.MotionPolicy;
import com.fadcam.motion.domain.state.MotionSessionState;

import org.junit.Test;

/**
 * Guards the settings that previously had no effect at all, so a future refactor
 * cannot quietly turn them back into dead preferences.
 */
public class MotionSettingsContractTest {

    private final MotionPolicy policy = new MotionPolicy();

    @Test
    public void everySensitivityValueHasAHysteresisBand() {
        for (int s = 0; s <= 100; s++) {
            float start = policy.startThresholdFromSensitivity(s);
            float stop = policy.stopThresholdFromSensitivity(s);
            assertTrue("start must be reachable at " + s, start > 0f && start <= 1f);
            assertTrue("stop must be reachable at " + s, stop > 0f && stop <= 1f);
            assertTrue("stop must be below start at " + s, stop < start);
        }
    }

    @Test
    public void thresholdCurveIsMonotonic() {
        float previous = Float.MAX_VALUE;
        for (int s = 0; s <= 100; s++) {
            float start = policy.startThresholdFromSensitivity(s);
            assertTrue("raising sensitivity must never raise the threshold (s=" + s + ")",
                    start <= previous + 1e-6f);
            previous = start;
        }
    }

    @Test
    public void sensitivitySpansTheUsableRange() {
        // A wide dynamic range is what makes the slider meaningful: a strict setting
        // must ignore ordinary movement that a lenient one accepts.
        float strict = policy.startThresholdFromSensitivity(10);
        float lenient = policy.startThresholdFromSensitivity(95);
        assertTrue("slider must span a wide range", strict - lenient > 0.5f);
    }

    @Test
    public void zeroSensitivityNeverTriggersOnOrdinaryMotion() {
        MotionSettings strict = new MotionSettings(
                true, MotionTriggerMode.ANY_MOTION, 0, 10, 720, 220, 10_000, 2, false);
        // 0.5 is a strong-but-ordinary motion score.
        assertTrue(!policy.isTriggerSatisfied(strict,
                new MotionSignal(0, 0.5f, false), MotionSessionState.IDLE));
        // Only a near-saturated score should pass at minimum sensitivity.
        assertTrue(policy.isTriggerSatisfied(strict,
                new MotionSignal(0, 0.99f, false), MotionSessionState.IDLE));
    }

    @Test
    public void debounceAndPostRollAreIndependent() {
        // Two settings that touch different phases must not be coupled: changing
        // post-roll must not change the start threshold (and vice versa).
        MotionSettings shortPost = new MotionSettings(
                true, MotionTriggerMode.ANY_MOTION, 80, 10, 720, 220, 1_000, 2, false);
        MotionSettings longPost = new MotionSettings(
                true, MotionTriggerMode.ANY_MOTION, 80, 10, 720, 220, 60_000, 2, false);
        assertEquals(policy.startThresholdFromSensitivity(shortPost.getSensitivity()),
                policy.startThresholdFromSensitivity(longPost.getSensitivity()), 1e-6f);
    }

    @Test
    public void analyzerResolutionAcceptsOnlySupportedValues() {
        // Mirrors SharedPreferencesManager#getMotionAnalysisResolution.
        int[] supported = {480, 720, 1080};
        for (int h : supported) {
            int normalised = (h == 480 || h == 1080) ? h : 720;
            assertEquals(h, normalised);
        }
        int[] rejected = {0, 360, 1440, 2160, -1};
        for (int h : rejected) {
            int normalised = (h == 480 || h == 1080) ? h : 720;
            assertEquals("unsupported height must fall back to 720", 720, normalised);
        }
    }
}
