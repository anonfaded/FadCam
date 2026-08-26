package com.fadcam.production;

import androidx.annotation.Nullable;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-local bridge between FadRec's real microphone capture loop and the
 * TV-production audio ducking UI. The recorder owns the microphone; the UI only
 * receives level telemetry, so we never open a second AudioRecord and risk
 * competing for the mic on Android.
 *
 * Verification marker: voice-ducking wiring is intentionally driven by the
 * recorder's existing PCM stream, not a second microphone capture.
 */
public final class ProductionAudioDuckingBus {
    public interface Listener {
        void onMicLevel(float normalizedLevel);
        void onCaptureState(boolean active);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private ProductionAudioDuckingBus() {}

    public static void subscribe(@Nullable Listener listener) {
        if (listener != null) LISTENERS.addIfAbsent(listener);
    }

    public static void unsubscribe(@Nullable Listener listener) {
        if (listener != null) LISTENERS.remove(listener);
    }

    /** Publishes an RMS value from the existing recorder-owned PCM buffer. */
    public static void publishRms(int rms) {
        // Speech-level RMS varies substantially by microphone/device. This mapping
        // deliberately saturates gradually rather than treating one fixed dB value
        // as universal. The ducking controller adds hysteresis and smoothing.
        float normalized = Math.min(1f, Math.max(0f, rms / 6000f));
        for (Listener listener : LISTENERS) {
            try {
                listener.onMicLevel(normalized);
            } catch (RuntimeException ignored) {
                // A stale UI listener must never interrupt the audio thread.
            }
        }
    }

    public static void publishCaptureState(boolean active) {
        for (Listener listener : LISTENERS) {
            try {
                listener.onCaptureState(active);
            } catch (RuntimeException ignored) {
                // Never let UI lifecycle errors reach the recorder thread.
            }
        }
    }
}
