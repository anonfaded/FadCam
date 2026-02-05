package com.fadcam.dualcam.ui;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentManager;

import com.fadcam.SharedPreferencesManager;
import com.fadcam.dualcam.DualCameraCapability;

/**
 * Helper class for toggling dual camera mode on/off from any UI surface
 * (HomeFragment, settings, etc.).
 *
 * <p>Encapsulates the capability check + preference flip + optional
 * settings bottom sheet launch. Keeps UI fragments slim.
 */
public class DualCameraToggleHelper {

    private static final String TAG = "DualCamToggle";

    private final Context appContext;
    private final SharedPreferencesManager prefs;
    private DualCameraCapability capability;

    public DualCameraToggleHelper(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = SharedPreferencesManager.getInstance(appContext);
    }

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the device supports dual camera recording.
     */
    public boolean isDeviceSupported() {
        return getCapability().isSupported();
    }

    /**
     * Returns human-readable reason if device is not supported, or {@code null}.
     */
    public String getUnsupportedReason() {
        return getCapability().getUnsupportedReason();
    }

    /**
     * Toggles dual camera mode.
     *
     * @param fragmentManager Required to show the settings bottom sheet when
     *                        enabling dual camera for the first time.
     * @return {@code true} if dual camera mode is now <em>enabled</em>.
     */
    public boolean toggle(@NonNull FragmentManager fragmentManager) {
        if (!isDeviceSupported()) {
            String reason = getUnsupportedReason();
            Log.w(TAG, "Dual camera not supported: " + reason);
            Toast.makeText(appContext, reason != null ? reason
                    : "Dual camera not supported on this device", Toast.LENGTH_LONG).show();
            return false;
        }

        boolean currentlyEnabled = prefs.isDualCameraModeEnabled();
        boolean newState = !currentlyEnabled;
        prefs.setDualCameraModeEnabled(newState);

        Log.d(TAG, "Dual camera mode toggled: " + currentlyEnabled + " → " + newState);

        if (newState) {
            // Show settings bottom sheet when enabling
            try {
                DualCameraSettingsBottomSheet sheet = DualCameraSettingsBottomSheet.newInstance();
                sheet.show(fragmentManager, DualCameraSettingsBottomSheet.TAG);
            } catch (Exception e) {
                Log.w(TAG, "Could not show dual camera settings", e);
            }
        }

        return newState;
    }

    /**
     * Enables dual camera mode without showing the settings sheet.
     * Useful for programmatic activation.
     */
    public void enable() {
        if (!isDeviceSupported()) return;
        prefs.setDualCameraModeEnabled(true);
    }

    /**
     * Disables dual camera mode.
     */
    public void disable() {
        prefs.setDualCameraModeEnabled(false);
    }

    /**
     * @return Current dual camera mode state from preferences.
     */
    public boolean isEnabled() {
        return prefs.isDualCameraModeEnabled();
    }

    // ── Internal ───────────────────────────────────────────────────────

    private DualCameraCapability getCapability() {
        if (capability == null) {
            capability = new DualCameraCapability(appContext);
        }
        return capability;
    }
}
