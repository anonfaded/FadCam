package com.fadcam.utils.camera.vendor;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.util.Log;
import android.util.Range;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Helper class to apply Samsung-specific camera frame rate settings, often using vendor keys.
 * This is crucial for achieving 60fps and higher on some Samsung devices that do not fully
 * support standard high-speed capture sessions for all resolutions, or require specific
 * vendor-defined parameters.
 */
public class SamsungFrameRateHelper {

    private static final String TAG = "SamsungFrameRateHelper";

    // Samsung-specific vendor keys. These are undocumented and can change with firmware updates.
    // They are typically found via reverse engineering or Samsung's internal documentation/SDKs.
    private static final String KEY_MOTION_RECORDING_MODE = "samsung.android.control.motionRecordingMode";
    private static final String KEY_RECORDING_MOTION_SPEED_MODE = "samsung.android.control.recordingMotionSpeedMode";
    private static final String KEY_RECORDER_FPS = "vendor.samsung.recorder.fps";
    private static final String KEY_HIGH_FRAME_RATE_MODE = "vendor.samsung.parameter.high_frame_rate_mode";
    private static final String KEY_RECORDING_MAX_FPS = "samsung.android.control.recordingMaxFps";
    private static final String KEY_RECORDING_MIN_FPS = "samsung.android.control.recordingMinFps";

    /**
     * Defines Samsung device 60fps compatibility status
     */
    public enum SamsungFpsStatus {
        FULLY_COMPATIBLE,        // Works well with standard session and vendor keys
        REQUIRES_VENDOR_KEYS,    // Requires Samsung vendor keys for 60fps (i.e., use high-speed session)
        KNOWN_INCOMPATIBLE,      // Known to not work with 60fps
        UNKNOWN,                 // Status unknown/untested
        HIGH_SPEED_COMPATIBLE    // Specifically for devices known to work best with high-speed sessions (e.g., SM-G990E)
    }

    /**
     * Applies Samsung-specific frame rate settings to the CaptureRequest.Builder.
     * This method attempts to use undocumented vendor keys to control frame rate for 60fps+.
     *
     * @param builder The CaptureRequest.Builder to modify.
     * @param targetFrameRate The desired frame rate (e.g., 60).
     */
    // -------------- Fix Start for this method(applyFrameRateSettings)-----------
    public static void applyFrameRateSettings(CaptureRequest.Builder builder, int targetFrameRate, @Nullable CameraCharacteristics characteristics) {
        if (builder == null) {
            Log.e(TAG, "CaptureRequest.Builder is null, cannot apply Samsung frame rate settings.");
            return;
        }
        // Do NOT override CONTROL_AE_TARGET_FPS_RANGE here; let the caller choose a supported range.
        // Apply Samsung-specific vendor keys conservatively and only when supported by the platform.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // API 29+
            try {
                // Helper to set a vendor key only if characteristics indicate support (best effort)
                final java.util.function.BiConsumer<String, Integer> safeSetIntKey = (keyName, value) -> {
                    try {
                        CaptureRequest.Key<Integer> key = new CaptureRequest.Key<>(keyName, Integer.class);
                        if (characteristics == null || isKeySupported(characteristics, key)) {
                            builder.set(key, value);
                            Log.d(TAG, "Applied vendor key " + keyName + "=" + value);
                        } else {
                            Log.d(TAG, "Vendor key not supported per characteristics: " + keyName);
                        }
                    } catch (Throwable t) {
                        Log.d(TAG, "Skipping vendor key due to error: " + keyName + ", " + t.getMessage());
                    }
                };

                // Avoid enabling Samsung motion/variable speed modes; prefer constant frame pacing.
                safeSetIntKey.accept(KEY_MOTION_RECORDING_MODE, 0);
                safeSetIntKey.accept(KEY_RECORDING_MOTION_SPEED_MODE, 0);

                // Direct FPS hints (if honored on this model)
                safeSetIntKey.accept(KEY_RECORDER_FPS, targetFrameRate);
                safeSetIntKey.accept(KEY_RECORDING_MAX_FPS, targetFrameRate);
                safeSetIntKey.accept(KEY_RECORDING_MIN_FPS, targetFrameRate);

                if (targetFrameRate >= 60) {
                    // Prefer constant frame rate if available
                    safeSetIntKey.accept("samsung.android.control.constantFrameRate", 1);
                    // Do not force-disable HFR mode universally; only set when supported and needed.
                    // Some models require leaving HFR mode alone for 60fps to engage properly.
                }
            } catch (Exception e) {
                Log.w(TAG, "Samsung vendor key application skipped: " + e.getMessage());
            }
        } else {
            Log.d(TAG, "API < 29: Skipping Samsung vendor keys to avoid compatibility issues");
        }
    }
    // -------------- Fix Ended for this method(applyFrameRateSettings)-----------

    /**
     * Determines the Samsung FPS compatibility status for the current device model.
     * This helps in deciding whether to use standard capture session with vendor keys
     * or a constrained high-speed capture session for 60fps+.
     *
     * @return The SamsungFpsStatus for the current device.
     */
    public static SamsungFpsStatus getDeviceFpsStatus() {
    // -------------- Fix Start for this method(getDeviceFpsStatus)-----------
    String model = Build.MODEL == null ? "" : Build.MODEL.toLowerCase(Locale.ROOT);

        // Known fully compatible devices - These will prioritize standard session with vendor keys (if supported)
        List<String> fullyCompatibleModels = Arrays.asList(
            "sm-g988", "sm-g986", "sm-g985", "sm-g981", "sm-g980", // S20 series
            "sm-n981", "sm-n986", "sm-n985", // Note 20 series
            "sm-g781", // S20 FE
            "sm-s908", "sm-s906", "sm-s901", // S22 series
            "sm-g991", "sm-g996", "sm-g998", // S21 series
            "sm-g975", "sm-g973", "sm-g970", // S10 series
            "sm-g965", "sm-g960"  // S9 series
        );

        // Devices specifically requiring constrained high-speed session (e.g., S21 FE for 60fps+)
        // This includes some S21 FE variants that struggle with vendor keys in standard session.
        List<String> requiresConstrainedHighSpeedModels = Arrays.asList(
            "sm-g990"  // S21 FE (specifically for Exynos variants that struggle with vendor keys in standard session if not SM-G990E)
        );

        // Known incompatible devices - SM-G990E moved here as its vendor keys are not supported.
        List<String> knownIncompatibleModels = Arrays.asList(
            "sm-g950", "sm-g955", // S8 series
            "sm-j",     // J series
            "sm-g990e"  // S21 FE Exynos - Vendor keys for 60fps are NOT supported.
        );

        // Check model against known device lists
        for (String prefix : fullyCompatibleModels) {
            if (model.contains(prefix)) {
                return SamsungFpsStatus.FULLY_COMPATIBLE;
            }
        }

        for (String prefix : requiresConstrainedHighSpeedModels) {
            if (model.contains(prefix)) {
                return SamsungFpsStatus.REQUIRES_VENDOR_KEYS;
            }
        }

        for (String prefix : knownIncompatibleModels) {
            if (model.contains(prefix)) {
                return SamsungFpsStatus.KNOWN_INCOMPATIBLE;
            }
        }

    return SamsungFpsStatus.UNKNOWN;
    // -------------- Fix Ended for this method(getDeviceFpsStatus)-----------
    }

    /**
     * Apply Samsung-specific frame rate settings to a capture session
     *
     * @param session The capture session
     * @param builder The request builder
     * @param targetFrameRate The desired frame rate
     * @param characteristics The CameraCharacteristics to check against.
     * @return True if successfully applied
     */
    public static boolean configureSessionFrameRate(
            @NonNull CameraCaptureSession session,
            @NonNull CaptureRequest.Builder builder,
            int targetFrameRate,
            @NonNull CameraCharacteristics characteristics) {
            
        try {
            // Caller should have set AE range already; only apply vendor keys here.
            applyFrameRateSettings(builder, targetFrameRate, characteristics);
            
            // Start the repeating request
            session.setRepeatingRequest(builder.build(), null, null);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to configure Samsung session with frame rate " + targetFrameRate, e);
            return false;
        }
    }

    /**
     * Checks if a given CaptureRequest.Key is supported by the camera characteristics.
     * This is crucial to avoid crashing when setting unsupported vendor tags.
     *
     * @param characteristics The CameraCharacteristics to check against.
     * @param key The CaptureRequest.Key to check.
     * @return True if the key is supported, false otherwise.
     */
    private static boolean isKeySupported(@NonNull CameraCharacteristics characteristics, @NonNull CaptureRequest.Key<?> key) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { // API 28+ for getAvailableCaptureRequestKeys
            try {
                // Using getAvailableCaptureRequestKeys is the robust way to check for support
                return characteristics.getAvailableCaptureRequestKeys().contains(key);
            } catch (Exception e) {
                // Log and return false if there's an error getting keys
                Log.e(TAG, "Error checking key support for " + key.getName() + ": " + e.getMessage());
                return false;
            }
        } else {
            // For older APIs, we can't reliably check if a vendor key is supported dynamically.
            // We have to rely on trial-and-error or a hardcoded list.
            // For now, assume it's not supported unless explicitly known.
            Log.w(TAG, "Dynamic key support check not available on API < 28. Assuming " + key.getName() + " is NOT supported.");
            return false; // Safest default for older APIs
        }
    }
} 