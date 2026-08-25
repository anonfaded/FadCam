package com.fadcam.production;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.exoplayer.ExoPlayer;

/**
 * Broadcast-style voice ducking for the duet player.
 *
 * The microphone level comes from the same AudioRecord that is already writing
 * the final recording. This avoids opening a competing microphone capture.
 * Attack is fast so speech gets priority; release is deliberately slower so
 * the playback does not pump up between syllables.
 */
public final class ProductionAudioDucker implements ProductionAudioDuckingBus.Listener {
    public static final float DEFAULT_NORMAL_VOLUME = 1.0f;
    public static final float DEFAULT_DUCK_VOLUME = 0.22f;
    public static final float DEFAULT_THRESHOLD = 0.12f;

    private static final long ATTACK_MS = 90L;
    private static final long RELEASE_MS = 420L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private boolean enabled = true;
    private boolean captureActive;
    private float normalVolume = DEFAULT_NORMAL_VOLUME;
    private float duckVolume = DEFAULT_DUCK_VOLUME;
    private float threshold = DEFAULT_THRESHOLD;
    private float currentVolume = DEFAULT_NORMAL_VOLUME;
    private float targetVolume = DEFAULT_NORMAL_VOLUME;
    private long lastUpdateMs;
    private boolean subscribed;

    public void attach(@NonNull ExoPlayer player) {
        detachPlayer();
        this.player = player;
        this.currentVolume = clamp(normalVolume);
        this.targetVolume = this.currentVolume;
        applyVolume(this.currentVolume);
    }

    public void detachPlayer() {
        player = null;
    }

    public void start() {
        if (!subscribed) {
            ProductionAudioDuckingBus.subscribe(this);
            subscribed = true;
        }
    }

    public void stop() {
        if (subscribed) {
            ProductionAudioDuckingBus.unsubscribe(this);
            subscribed = false;
        }
        captureActive = false;
        targetVolume = normalVolume;
        animateToTarget();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        targetVolume = enabled && captureActive && currentVolume < normalVolume
                ? currentVolume : normalVolume;
        if (!enabled) targetVolume = normalVolume;
        animateToTarget();
    }

    public boolean isEnabled() { return enabled; }

    public void setNormalVolume(float volume) {
        normalVolume = clamp(volume);
        if (!captureActive || !enabled) {
            targetVolume = normalVolume;
            animateToTarget();
        }
    }

    public float getNormalVolume() { return normalVolume; }

    public void setDuckVolume(float volume) {
        duckVolume = clamp(volume);
    }

    public float getDuckVolume() { return duckVolume; }

    public void setThreshold(float threshold) {
        this.threshold = clamp(threshold);
    }

    public float getThreshold() { return threshold; }

    @Override
    public void onMicLevel(float normalizedLevel) {
        if (!enabled || !captureActive) return;
        final float level = clamp(normalizedLevel);
        mainHandler.post(() -> {
            if (!enabled || !captureActive) return;
            targetVolume = level >= threshold ? duckVolume : normalVolume;
            animateToTarget();
        });
    }

    @Override
    public void onCaptureState(boolean active) {
        mainHandler.post(() -> {
            captureActive = active;
            targetVolume = enabled && active ? targetVolume : normalVolume;
            if (!active) targetVolume = normalVolume;
            animateToTarget();
        });
    }

    private void animateToTarget() {
        if (player == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        long elapsed = lastUpdateMs == 0L ? 16L : Math.max(1L, now - lastUpdateMs);
        lastUpdateMs = now;
        float target = clamp(targetVolume);
        float duration = target < currentVolume ? ATTACK_MS : RELEASE_MS;
        float step = Math.min(1f, elapsed / duration);
        currentVolume += (target - currentVolume) * step;
        if (Math.abs(target - currentVolume) < 0.005f) currentVolume = target;
        applyVolume(currentVolume);

        if (Math.abs(target - currentVolume) >= 0.005f) {
            mainHandler.postDelayed(this::animateToTarget, 16L);
        }
    }

    private void applyVolume(float volume) {
        ExoPlayer p = player;
        if (p != null) p.setVolume(clamp(volume));
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
