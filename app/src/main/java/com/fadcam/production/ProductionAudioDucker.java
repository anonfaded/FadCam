package com.fadcam.production;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.media3.exoplayer.ExoPlayer;

/**
 * Broadcast-style voice ducking for the duet player.
 *
 * The microphone level comes from the same AudioRecord that is already writing
 * the final recording. This avoids opening a competing microphone capture.
 * The detector smooths the RMS level, adapts to the local noise floor, and uses
 * separate enter/exit hysteresis so background noise does not make the video
 * pump up and down between syllables.
 */
public final class ProductionAudioDucker implements ProductionAudioDuckingBus.Listener {
    public static final float DEFAULT_NORMAL_VOLUME = 1.0f;
    public static final float DEFAULT_DUCK_VOLUME = 0.22f;
    public static final float DEFAULT_THRESHOLD = 0.12f;

    private static final long ATTACK_MS = 90L;
    private static final long RELEASE_MS = 420L;
    private static final int SPEECH_ENTER_FRAMES = 2;
    private static final int SPEECH_EXIT_FRAMES = 8;
    private static final float LEVEL_SMOOTHING = 0.35f;
    private static final float NOISE_FLOOR_SMOOTHING = 0.04f;
    private static final float MIN_NOISE_MARGIN = 0.025f;
    private static final float EXIT_THRESHOLD_RATIO = 0.65f;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private boolean enabled = true;
    private boolean captureActive;
    private boolean speechActive;
    private int speechFrames;
    private int silenceFrames;
    private float smoothedLevel;
    private float noiseFloor = 0.03f;
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
        lastUpdateMs = 0L;
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
        resetDetector();
        captureActive = false;
        targetVolume = normalVolume;
        animateToTarget();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) resetDetector();
        targetVolume = enabled && captureActive && speechActive ? duckVolume : normalVolume;
        animateToTarget();
    }

    public boolean isEnabled() { return enabled; }

    public void setNormalVolume(float volume) {
        normalVolume = clamp(volume);
        if (!captureActive || !enabled || !speechActive) {
            targetVolume = normalVolume;
            animateToTarget();
        }
    }

    public float getNormalVolume() { return normalVolume; }

    public void setDuckVolume(float volume) {
        duckVolume = clamp(volume);
        if (speechActive && enabled && captureActive) {
            targetVolume = duckVolume;
            animateToTarget();
        }
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
            updateSpeechState(level);
            targetVolume = speechActive ? duckVolume : normalVolume;
            animateToTarget();
        });
    }

    @Override
    public void onCaptureState(boolean active) {
        mainHandler.post(() -> {
            captureActive = active;
            if (!active) {
                resetDetector();
                targetVolume = normalVolume;
            } else {
                resetDetector();
            }
            animateToTarget();
        });
    }

    /**
     * Converts raw RMS telemetry into a stable speech state. The noise floor only
     * follows quiet samples, so sustained speech cannot teach the detector that
     * speech is noise. Enter and exit thresholds are intentionally different.
     */
    private void updateSpeechState(float level) {
        smoothedLevel += (level - smoothedLevel) * LEVEL_SMOOTHING;

        float enterThreshold = Math.max(threshold, noiseFloor + MIN_NOISE_MARGIN);
        float exitThreshold = Math.max(enterThreshold * EXIT_THRESHOLD_RATIO,
                noiseFloor + MIN_NOISE_MARGIN * 0.5f);

        if (!speechActive && smoothedLevel < enterThreshold * 0.85f) {
            noiseFloor += (smoothedLevel - noiseFloor) * NOISE_FLOOR_SMOOTHING;
            noiseFloor = clamp(noiseFloor);
            enterThreshold = Math.max(threshold, noiseFloor + MIN_NOISE_MARGIN);
            exitThreshold = Math.max(enterThreshold * EXIT_THRESHOLD_RATIO,
                    noiseFloor + MIN_NOISE_MARGIN * 0.5f);
        }

        if (!speechActive) {
            if (smoothedLevel >= enterThreshold) {
                speechFrames++;
                silenceFrames = 0;
                if (speechFrames >= SPEECH_ENTER_FRAMES) {
                    speechActive = true;
                    speechFrames = 0;
                }
            } else {
                speechFrames = 0;
            }
        } else {
            if (smoothedLevel <= exitThreshold) {
                silenceFrames++;
                speechFrames = 0;
                if (silenceFrames >= SPEECH_EXIT_FRAMES) {
                    speechActive = false;
                    silenceFrames = 0;
                }
            } else {
                silenceFrames = 0;
            }
        }
    }

    private void resetDetector() {
        speechActive = false;
        speechFrames = 0;
        silenceFrames = 0;
        smoothedLevel = 0f;
        noiseFloor = 0.03f;
    }

    private void animateToTarget() {
        if (player == null) return;
        long now = SystemClock.uptimeMillis();
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
