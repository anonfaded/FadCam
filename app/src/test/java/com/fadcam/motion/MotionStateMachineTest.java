package com.fadcam.motion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fadcam.motion.domain.model.MotionSettings;
import com.fadcam.motion.domain.model.MotionSignal;
import com.fadcam.motion.domain.model.MotionTriggerMode;
import com.fadcam.motion.domain.policy.MotionPolicy;
import com.fadcam.motion.domain.state.MotionSessionState;
import com.fadcam.motion.domain.state.MotionStateMachine;
import com.fadcam.motion.domain.state.MotionStateMachine.TransitionAction;

import org.junit.Test;

/**
 * Verifies that debounce, post-roll and the arming rules actually drive the
 * transitions — i.e. that the Motion Lab settings change behaviour rather than
 * only being stored.
 */
public class MotionStateMachineTest {

    private static final float STRONG = 0.95f;
    private static final float NOTHING = 0.0f;

    private static MotionSettings settings(int debounceMs, int postRollMs) {
        return new MotionSettings(
                true, MotionTriggerMode.ANY_MOTION, 90, 10, 720,
                debounceMs, postRollMs, 2, false);
    }

    /** Feeds a steady signal covering [fromMs, toMs) at 100 ms steps. */
    private static int feed(MotionStateMachine machine, MotionSettings s,
                            float score, int fromMs, int toMs) {
        int last = fromMs;
        for (int t = fromMs; t < toMs; t += 100) {
            machine.onSignal(s, new MotionSignal(t, score, false));
            last = t;
        }
        return last;
    }

    @Test
    public void debounceDelaysTheStart() {
        MotionSettings fast = settings(0, 5_000);
        MotionSettings slow = settings(1_000, 5_000);

        MotionStateMachine fastMachine = new MotionStateMachine(new MotionPolicy());
        MotionStateMachine slowMachine = new MotionStateMachine(new MotionPolicy());

        // 500 ms of continuous motion: only the 0 ms debounce machine may fire.
        int firedFast = 0;
        int firedSlow = 0;
        for (int t = 0; t <= 500; t += 100) {
            MotionSignal sig = new MotionSignal(t, STRONG, false);
            if (fastMachine.onSignal(fast, sig) == TransitionAction.START_RECORDING) firedFast++;
            if (slowMachine.onSignal(slow, sig) == TransitionAction.START_RECORDING) firedSlow++;
        }
        assertEquals("0 ms debounce must start immediately-ish", 1, firedFast);
        assertEquals("1000 ms debounce must not fire within 500 ms", 0, firedSlow);
    }

    @Test
    public void longEnoughDebounceEventuallyStarts() {
        MotionSettings s = settings(400, 5_000);
        MotionStateMachine machine = new MotionStateMachine(new MotionPolicy());
        boolean started = false;
        for (int t = 0; t <= 2_000; t += 100) {
            if (machine.onSignal(s, new MotionSignal(t, STRONG, false))
                    == TransitionAction.START_RECORDING) {
                started = true;
                break;
            }
        }
        assertTrue("motion held past the debounce must start a clip", started);
        assertEquals(MotionSessionState.RECORDING, machine.getState());
    }

    @Test
    public void postRollKeepsRecordingAfterMotionStops() {
        MotionSettings s = settings(0, 2_000);
        MotionStateMachine machine = new MotionStateMachine(new MotionPolicy());

        // Start recording.
        machine.onSignal(s, new MotionSignal(0, STRONG, false));
        machine.onSignal(s, new MotionSignal(100, STRONG, false));
        assertEquals(MotionSessionState.RECORDING, machine.getState());

        // Motion stops at t=200. Post-roll is 2 s, so it must not stop before ~2.2 s.
        assertTrue(machine.onSignal(s, new MotionSignal(200, NOTHING, false))
                != TransitionAction.STOP_RECORDING);
        assertTrue(machine.onSignal(s, new MotionSignal(1_500, NOTHING, false))
                != TransitionAction.STOP_RECORDING);
        assertTrue("before post-roll expires it must still be recording/paused-out",
                machine.getState() != MotionSessionState.IDLE);
    }

    @Test
    public void recordingStopsOnlyAfterPostRollAndMinimumClip() {
        MotionSettings s = settings(0, 500);
        MotionStateMachine machine = new MotionStateMachine(new MotionPolicy());

        boolean stopped = false;
        int stopAt = -1;
        // Motion for the first second, then silence for 10 s.
        for (int t = 0; t <= 11_000; t += 100) {
            float score = t <= 1_000 ? STRONG : NOTHING;
            if (machine.onSignal(s, new MotionSignal(t, score, false))
                    == TransitionAction.STOP_RECORDING) {
                stopped = true;
                stopAt = t;
                break;
            }
        }
        assertTrue("a session must eventually stop", stopped);
        assertTrue("stop must not happen before the minimum clip length",
                stopAt >= MotionStateMachineTestDefaults.MIN_CLIP_MS);
        assertEquals(MotionSessionState.IDLE, machine.getState());
    }

    @Test
    public void shortDropoutDoesNotEndTheClip() {
        MotionSettings s = settings(300, 2_000);
        MotionStateMachine machine = new MotionStateMachine(new MotionPolicy());
        for (int t = 0; t <= 500; t += 100) {
            machine.onSignal(s, new MotionSignal(t, STRONG, false));
        }
        assertEquals(MotionSessionState.RECORDING, machine.getState());
        // One single dropped frame (100 ms) must not push it out of RECORDING.
        assertEquals(TransitionAction.NONE,
                machine.onSignal(s, new MotionSignal(600, NOTHING, false)));
        assertEquals(MotionSessionState.RECORDING, machine.getState());
    }

    @Test
    public void longerPostRollDelaysTheStop() {
        int shortStop = stopTimeFor(1_000);
        int longStop = stopTimeFor(4_000);
        assertTrue("a longer post-roll must delay the stop (short=" + shortStop
                        + ", long=" + longStop + ")",
                longStop > shortStop);
    }

    /** Returns the timestamp at which STOP_RECORDING is emitted, or -1. */
    private int stopTimeFor(int postRollMs) {
        MotionSettings s = settings(0, postRollMs);
        MotionStateMachine machine = new MotionStateMachine(new MotionPolicy());
        for (int t = 0; t <= 30_000; t += 100) {
            float score = t <= 1_000 ? STRONG : NOTHING;
            if (machine.onSignal(s, new MotionSignal(t, score, false))
                    == TransitionAction.STOP_RECORDING) {
                return t;
            }
        }
        return -1;
    }

    /** Mirrors the state machine's documented minimum clip duration. */
    private static final class MotionStateMachineTestDefaults {
        static final int MIN_CLIP_MS = 2_500;
    }
}
