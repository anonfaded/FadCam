package com.fadcam.ui.miniapps;

import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;

import com.fadcam.FLog;
import com.fadcam.Constants;

/**
 * TorchManager - Singleton pattern for app-wide torch state management
 * Industry standard approach: single shared instance ensures all parts of the app
 * (UI, notification receiver, etc.) access the same state without duplication.
 * 
 * Handles LED torch control, screen brightness management, and flash patterns.
 */
public class TorchManager {

    private final Context context;
    private final CameraManager cameraManager;
    private final Handler mainHandler;
    private String torchCameraId;
    private boolean isTorchOn;
    private float screenBrightnessLevel = 1.0f;
    private FlashPattern currentPattern = FlashPattern.STEADY;
    private PatternHandler patternHandler;
    private CameraManager.TorchCallback torchCallback;

    public enum FlashPattern {
        STEADY(0),
        STROBE(1),
        SOS(2);

        public final int value;

        FlashPattern(int value) {
            this.value = value;
        }
    }

    public interface TorchStateListener {
        void onTorchStateChanged(boolean isOn);

        void onBrightnessChanged(float brightness);

        void onPatternChanged(FlashPattern pattern);

        void onError(String message);
    }

    private TorchStateListener listener;
    private static final String PREF_TORCH_PATTERN = "torch_tool_pattern";

    // Singleton instance - shared across app
    private static volatile TorchManager instance;
    private static final Object LOCK = new Object();

    /**
     * Get singleton instance. Thread-safe using double-checked locking.
     * Industry standard pattern for app-wide state management.
     */
    public static TorchManager getInstance(Context context) {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new TorchManager(context);
                }
            }
        }
        return instance;
    }

    /**
     * Private constructor - use getInstance() instead
     */
    private TorchManager(Context context) {
        this.context = context.getApplicationContext();
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.isTorchOn = false;
        initTorchCamera();
        loadSavedPattern();
        registerTorchCallback();
    }

    private void loadSavedPattern() {
        int savedValue = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(PREF_TORCH_PATTERN, FlashPattern.STEADY.value);
        for (FlashPattern p : FlashPattern.values()) {
            if (p.value == savedValue) {
                currentPattern = p;
                return;
            }
        }
        currentPattern = FlashPattern.STEADY;
    }

    private void initTorchCamera() {
        try {
            String[] cameraIdList = cameraManager.getCameraIdList();
            for (String cameraId : cameraIdList) {
                android.hardware.camera2.CameraCharacteristics characteristics =
                    cameraManager.getCameraCharacteristics(cameraId);
                Boolean hasFlash = characteristics.get(
                    android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(hasFlash)) {
                    this.torchCameraId = cameraId;
                    break;
                }
            }
        } catch (CameraAccessException e) {
            FLog.e("TorchManager", "Failed to initialize torch camera", e);
            if (listener != null) {
                listener.onError("Camera access failed");
            }
        }
    }

    /**
     * Register a TorchCallback to detect torch state changes from external sources
     * (system quick settings tile, other apps). Without this callback, the app's
     * cached isTorchOn state goes stale when the torch is turned off externally.
     *
     * {@link CameraManager.TorchCallback#onTorchModeChanged} fires whenever the
     * torch mode changes — whether by our app, the system QS tile, or another app.
     */
    private void registerTorchCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            torchCallback = new CameraManager.TorchCallback() {
                @Override
                public void onTorchModeChanged(String cameraId, boolean enabled) {
                    if (torchCameraId != null && torchCameraId.equals(cameraId)) {
                        handleExternalTorchChange(enabled);
                    }
                }

                @Override
                public void onTorchModeUnavailable(String cameraId) {
                    if (torchCameraId != null && torchCameraId.equals(cameraId)) {
                        handleExternalTorchUnavailable();
                    }
                }
            };
            cameraManager.registerTorchCallback(torchCallback, mainHandler);
        }
    }

    /**
     * Called when the torch mode is changed externally (system QS, another app).
     * During pattern execution (STROBE/SOS), the pattern handler toggles the flash
     * rapidly — we skip processing to avoid interfering with pattern state.
     */
    private void handleExternalTorchChange(boolean enabled) {
        // During pattern execution, the pattern handler manages the flash state.
        // Skip to avoid corrupting pattern state with false syncs.
        if (currentPattern != FlashPattern.STEADY && patternHandler != null) {
            return;
        }

        if (this.isTorchOn != enabled) {
            this.isTorchOn = enabled;

            // If torch was turned off externally, stop any running pattern
            if (!enabled && patternHandler != null) {
                patternHandler.stop();
                patternHandler = null;
            }

            // Sync state to SharedPreferences (both files for consistency)
            saveTorchStateToPrefs(enabled);
            broadcastTorchState(enabled);

            if (listener != null) {
                listener.onTorchStateChanged(enabled);
            }
        }
    }

    /**
     * Called when the torch mode becomes unavailable (camera resources busy, etc.).
     * If our torch was on, it was forcibly turned off — update state accordingly.
     */
    private void handleExternalTorchUnavailable() {
        if (this.isTorchOn) {
            this.isTorchOn = false;

            if (patternHandler != null) {
                patternHandler.stop();
                patternHandler = null;
            }

            saveTorchStateToPrefs(false);
            broadcastTorchState(false);

            if (listener != null) {
                listener.onTorchStateChanged(false);
            }
        }
    }

    /**
     * Save torch state to SharedPreferences so all components see the correct state:
     * - Constants.PREFS_NAME ("app_prefs"): used by SharedPreferencesManager, HomeFragment
     * - Default shared prefs: used by RemoteStreamManager (the "API")
     */
    private void saveTorchStateToPrefs(boolean enabled) {
        // Save to app_prefs (used by HomeFragment via SharedPreferencesManager)
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(Constants.PREF_TORCH_STATE, enabled)
            .apply();
        // Also save to default prefs (used by RemoteStreamManager API)
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(Constants.PREF_TORCH_STATE, enabled)
            .apply();
    }

    /**
     * Broadcast torch state change so all UI components (HomeFragment, etc.) stay in sync.
     */
    private void broadcastTorchState(boolean enabled) {
        Intent broadcast = new Intent(Constants.BROADCAST_ON_TORCH_STATE_CHANGED)
            .putExtra(Constants.INTENT_EXTRA_TORCH_STATE, enabled);
        context.sendBroadcast(broadcast);
    }

    public void setStateListener(TorchStateListener listener) {
        this.listener = listener;
    }

    /**
     * Turn the LED torch on or off
     */
    public void setTorchEnabled(boolean enabled) {
        if (torchCameraId == null) {
            if (listener != null) {
                listener.onError("Torch not available on this device");
            }
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cameraManager.setTorchMode(torchCameraId, enabled);
                this.isTorchOn = enabled;

                if (!enabled) {
                    // Turning off: stop any pattern
                    if (patternHandler != null) {
                        patternHandler.stop();
                        patternHandler = null;
                    }
                } else {
                    // Turning on: apply current pattern if non-STEADY
                    if (currentPattern != FlashPattern.STEADY) {
                        patternHandler = new PatternHandler(currentPattern);
                        patternHandler.start();
                    }
                }
                
                // Sync state to SharedPreferences (both files for consistency)
                saveTorchStateToPrefs(enabled);
                broadcastTorchState(enabled);

                if (listener != null) {
                    listener.onTorchStateChanged(enabled);
                }
            }
        } catch (CameraAccessException e) {
            FLog.e("TorchManager", "Failed to set torch mode", e);
            if (listener != null) {
                listener.onError("Failed to control torch");
            }
        }
    }

    /**
     * Set screen brightness as an alternative light source (0.0 to 1.0)
     */
    public void setScreenBrightness(float brightness) {
        brightness = Math.max(0.0f, Math.min(1.0f, brightness));
        this.screenBrightnessLevel = brightness;

        if (listener != null) {
            listener.onBrightnessChanged(brightness);
        }
    }

    public float getScreenBrightness() {
        return screenBrightnessLevel;
    }

    /**
     * Set flash pattern. Selection is saved to prefs.
     * If torch is currently on, the new pattern is applied immediately.
     * If torch is off, pattern is saved for when torch turns on next time.
     */
    public void setFlashPattern(FlashPattern pattern) {
        this.currentPattern = pattern;

        // Save pattern to prefs for persistence
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_TORCH_PATTERN, pattern.value)
            .apply();

        // Capture user intent BEFORE stopping (PatternHandler.stop() no longer changes isTorchOn)
        boolean wasOn = isTorchOn;

        // Stop any existing pattern handler (safe: stop() only cancels callbacks)
        if (patternHandler != null) {
            patternHandler.stop();
            patternHandler = null;
        }

        // Apply new pattern only if torch was on before the switch
        if (wasOn) {
            if (pattern == FlashPattern.STEADY) {
                // Restore steady (ensure torch is on)
                try {
                    if (torchCameraId != null) {
                        cameraManager.setTorchMode(torchCameraId, true);
                        this.isTorchOn = true;
                    }
                } catch (CameraAccessException e) {
                    FLog.e("TorchManager", "Failed to restore steady", e);
                }
            } else {
                patternHandler = new PatternHandler(pattern);
                patternHandler.start();
            }
        }

        if (listener != null) {
            listener.onPatternChanged(pattern);
        }
    }

    public boolean isTorchOn() {
        return isTorchOn;
    }

    public FlashPattern getCurrentPattern() {
        return currentPattern;
    }

    public void release() {
        if (patternHandler != null) {
            patternHandler.stop();
        }
        try {
            if (isTorchOn && torchCameraId != null) {
                setTorchEnabled(false);
            }
        } catch (Exception e) {
            FLog.e("TorchManager", "Error releasing torch", e);
        }
    }

    /**
     * Inner class to handle flash patterns with timing
     */
    private class PatternHandler {
        private final FlashPattern pattern;
        private boolean isRunning = false;
        private final Handler handler = new Handler(Looper.getMainLooper());

        PatternHandler(FlashPattern pattern) {
            this.pattern = pattern;
        }

        void start() {
            isRunning = true;
            executePattern();
        }

        void stop() {
            isRunning = false;
            handler.removeCallbacksAndMessages(null);
            // Hardware state is managed by TorchManager, not PatternHandler
        }

        private void executePattern() {
            if (!isRunning) return;

            switch (pattern) {
                case STEADY:
                    // Steady light - no pattern execution needed
                    break;
                case STROBE:
                    strobePattern();
                    break;
                case SOS:
                    sosPattern();
                    break;
            }
        }

        private void strobePattern() {
            // Rapid on-off: 100ms on, 100ms off
            flash(100, 100);
        }

        private void sosPattern() {
            // S: 3 short (200ms on, 100ms off)
            // O: 3 long (500ms on, 100ms off)
            // S: 3 short (200ms on, 100ms off)
            flashSequence(new long[]{
                200, 100, 200, 100, 200, 200, // S
                500, 100, 500, 100, 500, 200, // O
                200, 100, 200, 100, 200, 500  // S
            });
        }

        private void flash(long onMs, long offMs) {
            if (!isRunning) return;

            handler.postDelayed(() -> {
                if (!isRunning) return;
                try {
                    if (torchCameraId != null) {
                        cameraManager.setTorchMode(torchCameraId, false);
                    }
                } catch (CameraAccessException e) {
                    FLog.e("TorchManager", "Flash off failed", e);
                }

                handler.postDelayed(() -> {
                    if (!isRunning) return;
                    try {
                        if (torchCameraId != null) {
                            cameraManager.setTorchMode(torchCameraId, true);
                        }
                    } catch (CameraAccessException e) {
                        FLog.e("TorchManager", "Flash on failed", e);
                    }
                    flash(onMs, offMs); // Loop
                }, offMs);
            }, onMs);
        }

        private void flashSequence(long[] timings) {
            if (!isRunning || timings.length == 0) return;

            boolean shouldBeOn = true;
            long cumulativeTime = 0;

            for (long timing : timings) {
                final boolean finalShouldBeOn = shouldBeOn;
                final long delay = cumulativeTime;

                handler.postDelayed(() -> {
                    if (!isRunning) return;
                    try {
                        if (torchCameraId != null) {
                            cameraManager.setTorchMode(torchCameraId, finalShouldBeOn);
                        }
                    } catch (CameraAccessException e) {
                        FLog.e("TorchManager", "Flash sequence failed", e);
                    }
                }, delay);

                cumulativeTime += timing;
                shouldBeOn = !shouldBeOn; // Toggle for next iteration
            }

            // Loop the pattern
            handler.postDelayed(this::executePattern, cumulativeTime + 500);
        }
    }
}

