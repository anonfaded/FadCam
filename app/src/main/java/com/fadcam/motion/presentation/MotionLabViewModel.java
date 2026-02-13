package com.fadcam.motion.presentation;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.fadcam.motion.data.MotionSettingsRepository;
import com.fadcam.motion.domain.model.MotionSettings;
import com.fadcam.motion.domain.model.MotionTriggerMode;

public class MotionLabViewModel extends ViewModel {

    private final MotionSettingsRepository repository;
    private final MutableLiveData<MotionLabViewState> state = new MutableLiveData<>();

    public MotionLabViewModel(MotionSettingsRepository repository) {
        this.repository = repository;
        load();
    }

    public LiveData<MotionLabViewState> getState() {
        return state;
    }

    public void load() {
        MotionSettings s = repository.getSettings();
        state.setValue(new MotionLabViewState(
            s.isEnabled(),
            s.getTriggerMode(),
            s.getSensitivity(),
            s.getAnalysisFps(),
            s.getDebounceMs(),
            s.getPostRollMs(),
            s.getPreRollSeconds(),
            s.isAutoTorchEnabled()
        ));
    }

    public void onEnabledChanged(boolean enabled) {
        repository.setEnabled(enabled);
        load();
    }

    public void onTriggerModeChanged(MotionTriggerMode mode) {
        repository.setTriggerMode(mode);
        load();
    }

    public void onSensitivityChanged(int sensitivity) {
        repository.setSensitivity(sensitivity);
        load();
    }

    public void onAnalysisFpsChanged(int fps) {
        repository.setAnalysisFps(fps);
        load();
    }

    public void onDebounceMsChanged(int debounceMs) {
        repository.setDebounceMs(debounceMs);
        load();
    }

    public void onPostRollMsChanged(int postRollMs) {
        repository.setPostRollMs(postRollMs);
        load();
    }

    public void onPreRollSecondsChanged(int seconds) {
        repository.setPreRollSeconds(seconds);
        load();
    }

    public void onAutoTorchChanged(boolean enabled) {
        repository.setAutoTorchEnabled(enabled);
        load();
    }
}
