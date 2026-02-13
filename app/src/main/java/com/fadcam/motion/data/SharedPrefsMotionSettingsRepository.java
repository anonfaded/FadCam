package com.fadcam.motion.data;

import com.fadcam.SharedPreferencesManager;
import com.fadcam.motion.domain.model.MotionSettings;
import com.fadcam.motion.domain.model.MotionTriggerMode;

public class SharedPrefsMotionSettingsRepository implements MotionSettingsRepository {

    private final SharedPreferencesManager prefs;

    public SharedPrefsMotionSettingsRepository(SharedPreferencesManager prefs) {
        this.prefs = prefs;
    }

    @Override
    public MotionSettings getSettings() {
        return new MotionSettings(
            prefs.isMotionModeEnabled(),
            MotionTriggerMode.fromValue(prefs.getMotionTriggerMode()),
            prefs.getMotionSensitivity(),
            prefs.getMotionAnalysisFps(),
            prefs.getMotionDebounceMs(),
            prefs.getMotionPostRollMs(),
            prefs.getMotionPreRollSeconds(),
            prefs.isMotionAutoTorchEnabled()
        );
    }

    @Override
    public void setEnabled(boolean enabled) {
        prefs.setMotionModeEnabled(enabled);
    }

    @Override
    public void setTriggerMode(MotionTriggerMode mode) {
        prefs.setMotionTriggerMode(mode.getValue());
    }

    @Override
    public void setSensitivity(int sensitivity) {
        prefs.setMotionSensitivity(sensitivity);
    }

    @Override
    public void setAnalysisFps(int fps) {
        prefs.setMotionAnalysisFps(fps);
    }

    @Override
    public void setDebounceMs(int debounceMs) {
        prefs.setMotionDebounceMs(debounceMs);
    }

    @Override
    public void setPostRollMs(int postRollMs) {
        prefs.setMotionPostRollMs(postRollMs);
    }

    @Override
    public void setPreRollSeconds(int seconds) {
        prefs.setMotionPreRollSeconds(seconds);
    }

    @Override
    public void setAutoTorchEnabled(boolean enabled) {
        prefs.setMotionAutoTorchEnabled(enabled);
    }
}
