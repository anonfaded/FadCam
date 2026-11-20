package com.fadcam.motion.domain.model;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.fadcam.Constants;
import com.fadcam.SharedPreferencesManager;

/**
 * Cached snapshot of the Motion Lab settings the recording engine reads on every
 * analysed frame.
 *
 * <p>Reading {@link SharedPreferences} for every frame is wasteful (the analysis
 * loop runs at the camera rate); caching them behind a single
 * {@link SharedPreferences.OnSharedPreferenceChangeListener} keeps the hot path
 * to plain field reads while still applying edits instantly — the listener fires
 * the moment the setting changes, with no polling.
 */
public final class MotionRuntimeSettings {

    private final SharedPreferences prefs;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener;

    private volatile int sensitivity;
    private volatile int analysisFps;
    private volatile boolean enabled;
    private volatile boolean debugUiActive;
    private volatile int analysisResolution;
    private volatile int debounceMs;
    private volatile int postRollMs;
    private volatile int preRollSeconds;
    private volatile boolean autoTorchEnabled;
    private volatile String triggerMode;

    public MotionRuntimeSettings(@NonNull SharedPreferencesManager manager) {
        this.prefs = manager.sharedPreferences;
        reload();
        this.listener = (sharedPreferences, key) -> {
            if (key == null
                    || Constants.PREF_MOTION_SENSITIVITY.equals(key)
                    || Constants.PREF_MOTION_ANALYSIS_FPS.equals(key)
                    || Constants.PREF_MOTION_MODE_ENABLED.equals(key)
                    || Constants.PREF_MOTION_DEBUG_UI_ACTIVE.equals(key)
                    || Constants.PREF_MOTION_ANALYSIS_RESOLUTION.equals(key)
                    || Constants.PREF_MOTION_DEBOUNCE_MS.equals(key)
                    || Constants.PREF_MOTION_POST_ROLL_MS.equals(key)
                    || Constants.PREF_MOTION_PRE_ROLL_SECONDS.equals(key)
                    || Constants.PREF_MOTION_AUTO_TORCH_ENABLED.equals(key)
                    || Constants.PREF_MOTION_TRIGGER_MODE.equals(key)) {
                reload();
            }
        };
        this.prefs.registerOnSharedPreferenceChangeListener(listener);
    }

    private void reload() {
        sensitivity = Math.max(0, Math.min(100,
                prefs.getInt(Constants.PREF_MOTION_SENSITIVITY, 80)));
        int fps = prefs.getInt(Constants.PREF_MOTION_ANALYSIS_FPS, 10);
        analysisFps = Math.max(1, Math.min(15, fps <= 0 ? 10 : fps));
        enabled = prefs.getBoolean(Constants.PREF_MOTION_MODE_ENABLED, false);
        debugUiActive = prefs.getBoolean(Constants.PREF_MOTION_DEBUG_UI_ACTIVE, false);
        int h = prefs.getInt(Constants.PREF_MOTION_ANALYSIS_RESOLUTION, 720);
        analysisResolution = (h == 480 || h == 1080) ? h : 720;
        debounceMs = Math.max(0, prefs.getInt(Constants.PREF_MOTION_DEBOUNCE_MS, 220));
        postRollMs = Math.max(0, prefs.getInt(Constants.PREF_MOTION_POST_ROLL_MS, 10000));
        preRollSeconds = Math.max(0, Math.min(10,
                prefs.getInt(Constants.PREF_MOTION_PRE_ROLL_SECONDS, 2)));
        autoTorchEnabled = prefs.getBoolean(Constants.PREF_MOTION_AUTO_TORCH_ENABLED, false);
        triggerMode = prefs.getString(Constants.PREF_MOTION_TRIGGER_MODE, "any_motion");
    }

    public int getSensitivity() {
        return sensitivity;
    }

    public int getAnalysisFps() {
        return analysisFps;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getDebounceMs() {
        return debounceMs;
    }

    public int getPostRollMs() {
        return postRollMs;
    }

    public int getPreRollSeconds() {
        return preRollSeconds;
    }

    public boolean isAutoTorchEnabled() {
        return autoTorchEnabled;
    }

    public String getTriggerMode() {
        return triggerMode;
    }

    /** Analyzer frame height (width follows the camera aspect). */
    public int getAnalysisResolution() {
        return analysisResolution;
    }

    /** True while the Motion Lab screen is visible and wants analysis frames. */
    public boolean isDebugUiActive() {
        return debugUiActive;
    }

    public void release() {
        try {
            prefs.unregisterOnSharedPreferenceChangeListener(listener);
        } catch (Exception ignored) {
        }
    }
}
