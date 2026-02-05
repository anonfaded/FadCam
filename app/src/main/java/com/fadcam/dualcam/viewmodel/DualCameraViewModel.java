package com.fadcam.dualcam.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.fadcam.SharedPreferencesManager;
import com.fadcam.dualcam.DualCameraCapability;
import com.fadcam.dualcam.DualCameraConfig;
import com.fadcam.dualcam.DualCameraState;

/**
 * MVVM ViewModel for dual camera mode.
 *
 * <p>Exposes observable state for the UI (LiveData) and coordinates
 * preference reads/writes. Does <b>not</b> interact directly with
 * cameras or services — that's the responsibility of the fragment/activity
 * sending intents to {@link com.fadcam.dualcam.service.DualCameraRecordingService}.
 *
 * <p>Survives configuration changes (orientation, etc.).
 */
public class DualCameraViewModel extends AndroidViewModel {

    private static final String TAG = "DualCamVM";

    private final SharedPreferencesManager prefs;
    private final DualCameraCapability capability;

    // ── Observable state ───────────────────────────────────────────────

    private final MutableLiveData<Boolean> dualModeEnabled = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> deviceSupported = new MutableLiveData<>(false);
    private final MutableLiveData<String> unsupportedReason = new MutableLiveData<>(null);
    private final MutableLiveData<DualCameraState> dualState = new MutableLiveData<>(DualCameraState.DISABLED);
    private final MutableLiveData<DualCameraConfig> currentConfig = new MutableLiveData<>();

    // ════════════════════════════════════════════════════════════════════
    // CONSTRUCTION
    // ════════════════════════════════════════════════════════════════════

    public DualCameraViewModel(@NonNull Application application) {
        super(application);
        prefs = SharedPreferencesManager.getInstance(application);
        capability = new DualCameraCapability(application);

        // Initialise from current state
        refresh();
    }

    // ════════════════════════════════════════════════════════════════════
    // PUBLIC OBSERVABLES
    // ════════════════════════════════════════════════════════════════════

    /** Whether dual camera mode is enabled by the user. */
    @NonNull
    public LiveData<Boolean> isDualModeEnabled() {
        return dualModeEnabled;
    }

    /** Whether the device supports concurrent front + back cameras. */
    @NonNull
    public LiveData<Boolean> isDeviceSupported() {
        return deviceSupported;
    }

    /** Reason string if device is not supported (null if supported). */
    @NonNull
    public LiveData<String> getUnsupportedReason() {
        return unsupportedReason;
    }

    /** Current dual camera state (DISABLED, RECORDING, etc.). */
    @NonNull
    public LiveData<DualCameraState> getDualState() {
        return dualState;
    }

    /** Current PiP configuration. */
    @NonNull
    public LiveData<DualCameraConfig> getCurrentConfig() {
        return currentConfig;
    }

    // ════════════════════════════════════════════════════════════════════
    // PUBLIC ACTIONS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Refreshes all observables from preferences and device capability.
     * Call when returning from settings or after config changes.
     */
    public void refresh() {
        dualModeEnabled.setValue(prefs.isDualCameraModeEnabled());
        deviceSupported.setValue(capability.isSupported());
        unsupportedReason.setValue(capability.getUnsupportedReason());
        currentConfig.setValue(prefs.getDualCameraConfig());
    }

    /**
     * Toggles dual camera mode.
     *
     * @return {@code true} if now enabled, {@code false} if disabled or unsupported.
     */
    public boolean toggleDualMode() {
        if (!capability.isSupported()) {
            return false;
        }

        boolean newState = !prefs.isDualCameraModeEnabled();
        prefs.setDualCameraModeEnabled(newState);
        dualModeEnabled.setValue(newState);

        if (!newState) {
            dualState.setValue(DualCameraState.DISABLED);
        }

        return newState;
    }

    /**
     * Updates the dual camera state (called from broadcast receiver).
     */
    public void updateState(@NonNull DualCameraState newState) {
        dualState.setValue(newState);
    }

    /**
     * Updates PiP configuration and persists it.
     */
    public void updateConfig(@NonNull DualCameraConfig config) {
        prefs.saveDualCameraConfig(config);
        currentConfig.setValue(config);
    }

    /**
     * Swap primary/secondary camera in config.
     */
    public void swapPrimaryCamera() {
        DualCameraConfig config = currentConfig.getValue();
        if (config == null) config = DualCameraConfig.defaultConfig();

        DualCameraConfig.PrimaryCamera newPrimary =
                (config.getPrimaryCamera() == DualCameraConfig.PrimaryCamera.BACK)
                        ? DualCameraConfig.PrimaryCamera.FRONT
                        : DualCameraConfig.PrimaryCamera.BACK;

        DualCameraConfig newConfig = new DualCameraConfig.Builder(config)
                .primaryCamera(newPrimary)
                .build();

        updateConfig(newConfig);
    }

    // ════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ════════════════════════════════════════════════════════════════════

    @Override
    protected void onCleared() {
        super.onCleared();
        // No references to clear — prefs and capability are application-scoped
    }
}
