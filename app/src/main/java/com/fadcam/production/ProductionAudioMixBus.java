package com.fadcam.production;

import androidx.annotation.Nullable;
import java.util.concurrent.CopyOnWriteArrayList;

/** Process-local playback mix bus. Recorder microphone capture remains authoritative elsewhere. */
public final class ProductionAudioMixBus {
    public interface Listener { void onMixChanged(); }
    public static final int CAMERA_1 = 0, CAMERA_2 = 1, MEDIA = 2, MASTER = 3;
    private static final float[] LEVELS = {0.75f, 0.75f, 0.65f, 0.85f};
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private ProductionAudioMixBus() {}
    public static void subscribe(@Nullable Listener listener) { if (listener != null) LISTENERS.addIfAbsent(listener); }
    public static void unsubscribe(@Nullable Listener listener) { if (listener != null) LISTENERS.remove(listener); }
    public static synchronized void setLevel(int channel, int percent) {
        if (channel < 0 || channel >= LEVELS.length) return;
        LEVELS[channel] = clamp(percent / 100f);
        for (Listener listener : LISTENERS) {
            try { listener.onMixChanged(); } catch (RuntimeException ignored) { }
        }
    }
    public static synchronized float getLevel(int channel) { return channel < 0 || channel >= LEVELS.length ? 1f : LEVELS[channel]; }
    public static synchronized float getMediaGain() { return LEVELS[MEDIA] * LEVELS[MASTER]; }
    public static synchronized float getCameraGain(int slot) { return (slot == 2 ? LEVELS[CAMERA_2] : LEVELS[CAMERA_1]) * LEVELS[MASTER]; }
    private static float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }
}
