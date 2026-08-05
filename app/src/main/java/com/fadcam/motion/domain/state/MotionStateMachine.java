package com.fadcam.motion.domain.state;

import com.fadcam.motion.domain.model.MotionSettings;
import com.fadcam.motion.domain.model.MotionSignal;
import com.fadcam.motion.domain.policy.MotionPolicy;

public class MotionStateMachine {
    private static final long DEFAULT_MIN_CLIP_MS = 2500L;
    private static final long DEFAULT_COOLDOWN_MS = 1500L;
    private static final long DEFAULT_PENDING_LOSS_GRACE_MS = 1000L;

    public enum TransitionAction {
        NONE,
        START_RECORDING,
        STOP_RECORDING
    }

    private final MotionPolicy policy;
    private MotionSessionState state = MotionSessionState.IDLE;
    private long pendingSinceMs = -1L;
    private long pendingLastTriggeredAtMs = -1L;
    private long postRollUntilMs = -1L;
    private long postRollTriggeredSinceMs = -1L; // debounce POST_ROLL → RECORDING
    private long recordingLossSinceMs = -1L;     // debounce RECORDING → POST_ROLL
    private long recordingStartedAtMs = -1L;
    private long cooldownUntilMs = -1L;

    public MotionStateMachine(MotionPolicy policy) {
        this.policy = policy;
    }

    public MotionSessionState getState() {
        return state;
    }

    public TransitionAction onSignal(MotionSettings settings, MotionSignal signal) {
        boolean triggered = policy.isTriggerSatisfied(settings, signal, state);
        switch (state) {
            case IDLE:
                if (cooldownUntilMs > 0 && signal.getTimestampMs() < cooldownUntilMs) {
                    return TransitionAction.NONE;
                }
                if (triggered) {
                    state = MotionSessionState.PENDING;
                    pendingSinceMs = signal.getTimestampMs();
                    pendingLastTriggeredAtMs = signal.getTimestampMs();
                }
                return TransitionAction.NONE;

            case PENDING:
                if (triggered) {
                    pendingLastTriggeredAtMs = signal.getTimestampMs();
                } else if (pendingLastTriggeredAtMs > 0
                        && signal.getTimestampMs() - pendingLastTriggeredAtMs <= DEFAULT_PENDING_LOSS_GRACE_MS) {
                    return TransitionAction.NONE;
                } else {
                    resetToIdle();
                    return TransitionAction.NONE;
                }
                if (pendingSinceMs > 0 && signal.getTimestampMs() - pendingSinceMs >= settings.getDebounceMs()) {
                    state = MotionSessionState.RECORDING;
                    recordingStartedAtMs = signal.getTimestampMs();
                    return TransitionAction.START_RECORDING;
                }
                return TransitionAction.NONE;

            case RECORDING:
                if (triggered) {
                    recordingLossSinceMs = -1L;
                    return TransitionAction.NONE;
                }
                // Apply debounce to RECORDING → POST_ROLL so a brief motion dip
                // (single-frame noise) does not start the pause cascade.
                if (recordingLossSinceMs < 0) {
                    recordingLossSinceMs = signal.getTimestampMs();
                }
                if (signal.getTimestampMs() - recordingLossSinceMs < settings.getDebounceMs()) {
                    return TransitionAction.NONE;
                }
                state = MotionSessionState.POST_ROLL;
                postRollUntilMs = signal.getTimestampMs() + settings.getPostRollMs();
                recordingLossSinceMs = -1L;
                return TransitionAction.NONE;

            case POST_ROLL:
                if (triggered) {
                    // Apply debounceMs to POST_ROLL → RECORDING to prevent rapid
                    // state toggling from brief motion false-positives while recording
                    // is paused.
                    if (postRollTriggeredSinceMs < 0) {
                        postRollTriggeredSinceMs = signal.getTimestampMs();
                    }
                    if (signal.getTimestampMs() - postRollTriggeredSinceMs >= settings.getDebounceMs()) {
                        state = MotionSessionState.RECORDING;
                        postRollUntilMs = -1L;
                        postRollTriggeredSinceMs = -1L;
                    }
                    return TransitionAction.NONE;
                }
                postRollTriggeredSinceMs = -1L;
                if (postRollUntilMs > 0 && signal.getTimestampMs() >= postRollUntilMs) {
                    if (recordingStartedAtMs > 0
                            && signal.getTimestampMs() - recordingStartedAtMs < DEFAULT_MIN_CLIP_MS) {
                        // Respect minimum clip duration to avoid rapid flap.
                        postRollUntilMs = recordingStartedAtMs + DEFAULT_MIN_CLIP_MS;
                        return TransitionAction.NONE;
                    }
                    cooldownUntilMs = signal.getTimestampMs() + DEFAULT_COOLDOWN_MS;
                    resetToIdle();
                    return TransitionAction.STOP_RECORDING;
                }
                return TransitionAction.NONE;
            default:
                return TransitionAction.NONE;
        }
    }

    public void resetToIdle() {
        state = MotionSessionState.IDLE;
        pendingSinceMs = -1L;
        pendingLastTriggeredAtMs = -1L;
        postRollUntilMs = -1L;
        postRollTriggeredSinceMs = -1L;
        recordingLossSinceMs = -1L;
        recordingStartedAtMs = -1L;
    }
}
