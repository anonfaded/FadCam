package com.fadcam.utils.camera.vendor;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.util.Log;
import android.util.Range;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.List;

/**
 * Helper class for handling Samsung-specific camera frame rate settings
 * Based on analysis of Google Camera (GCam) implementation
 */
public class SamsungFrameRateHelper {
    private static final String TAG = "SamsungFrameRateHelper";
    
    // Samsung vendor-specific keys
    private static final String KEY_RECORDING_MAX_FPS = "samsung.android.control.recordingMaxFps";
    private static final String KEY_RECORDING_MIN_FPS = "samsung.android.control.recordingMinFps";
    private static final String KEY_MOTION_RECORDING_MODE = "samsung.android.control.motionRecordingMode";
    private static final String KEY_RECORDING_MOTION_SPEED_MODE = "samsung.android.control.recordingMotionSpeedMode";
    
    /**
     * Defines Samsung device 60fps compatibility status
     */
    public enum SamsungFpsStatus {
        FULLY_COMPATIBLE,        // Known to fully support 60fps without special handling
        REQUIRES_VENDOR_KEYS,    // Requires Samsung vendor keys for 60fps
        KNOWN_INCOMPATIBLE,      // Known to not work with 60fps
        UNKNOWN                  // Status unknown/untested
    }
    
    /**
     * Apply Samsung-specific frame rate settings to a capture request builder
     *
     * @param builder The request builder to modify
     * @param targetFrameRate The desired frame rate
     * @return True if Samsung-specific settings were applied
     */
    public static boolean applyFrameRateSettings(
            @NonNull CaptureRequest.Builder builder,
            int targetFrameRate) {
            
        try {
            Log.d(TAG, "Applying Samsung-specific frame rate keys for " + targetFrameRate + " fps");
            
            // Create Samsung-specific keys
            CaptureRequest.Key<Integer> samsungMaxFpsKey = 
                new CaptureRequest.Key<>(KEY_RECORDING_MAX_FPS, Integer.class);
            CaptureRequest.Key<Integer> samsungMinFpsKey = 
                new CaptureRequest.Key<>(KEY_RECORDING_MIN_FPS, Integer.class);
                
            // Apply Samsung-specific frame rate settings
            builder.set(samsungMaxFpsKey, targetFrameRate);
            builder.set(samsungMinFpsKey, targetFrameRate);
            Log.d(TAG, "Set Samsung min/max FPS keys to " + targetFrameRate);
            
            // For 60fps+ on newer devices, set motion recording modes
            if (targetFrameRate >= 60) {
                try {
                    CaptureRequest.Key<Integer> motionRecordingModeKey = 
                        new CaptureRequest.Key<>(KEY_MOTION_RECORDING_MODE, Integer.class);
                    builder.set(motionRecordingModeKey, 1); // 1 = enable high-speed recording
                    Log.d(TAG, "Set Samsung motion recording mode key to 1 (enabled)");
                } catch (Exception e) {
                    // Key may not be available on all Samsung devices
                    Log.d(TAG, "Samsung motion recording mode key not supported: " + e.getMessage());
                }
                
                try {
                    CaptureRequest.Key<Integer> motionSpeedModeKey = 
                        new CaptureRequest.Key<>(KEY_RECORDING_MOTION_SPEED_MODE, Integer.class);
                    
                    // Set appropriate motion speed mode based on frame rate
                    int motionSpeedMode = (targetFrameRate == 60) ? 1 : 
                                         (targetFrameRate == 120) ? 2 : 
                                         (targetFrameRate == 240) ? 3 : 1;
                    builder.set(motionSpeedModeKey, motionSpeedMode);
                    Log.d(TAG, "Set Samsung motion speed mode key to " + motionSpeedMode + 
                          " for " + targetFrameRate + "fps");
                } catch (Exception e) {
                    // Key may not be available on all Samsung devices
                    Log.d(TAG, "Samsung motion speed mode key not supported: " + e.getMessage());
                }
                
                // Try additional Samsung keys that might be used on newer models (S21/S22/S23)
                try {
                    // Some newer Samsung models might use different key names
                    CaptureRequest.Key<Integer> recordingFpsKey = 
                        new CaptureRequest.Key<>("vendor.samsung.recorder.fps", Integer.class);
                    builder.set(recordingFpsKey, targetFrameRate);
                    Log.d(TAG, "Set Samsung vendor.samsung.recorder.fps key to " + targetFrameRate);
                } catch (Exception e) {
                    // Key may not be available
                    Log.d(TAG, "Samsung recorder.fps key not supported: " + e.getMessage());
                }
                
                try {
                    // Another possible key for high frame rate mode
                    CaptureRequest.Key<Integer> highFrameRateModeKey = 
                        new CaptureRequest.Key<>("vendor.samsung.parameter.high_frame_rate_mode", Integer.class);
                    builder.set(highFrameRateModeKey, 1); // 1 = enabled
                    Log.d(TAG, "Set Samsung high_frame_rate_mode key to 1 (enabled)");
                } catch (Exception e) {
                    // Key may not be available
                    Log.d(TAG, "Samsung high_frame_rate_mode key not supported: " + e.getMessage());
                }
            }
            
            // Always also set the standard Camera2 API FPS range for better compatibility
            Range<Integer> fpsRange = new Range<>(targetFrameRate, targetFrameRate);
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
            Log.d(TAG, "Also set standard Camera2 FPS range to " + fpsRange);
            
            Log.d(TAG, "Successfully applied all applicable Samsung-specific frame rate settings");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply Samsung frame rate settings", e);
            return false;
        }
    }
    
    /**
     * Apply Samsung-specific frame rate settings to a capture session
     *
     * @param session The capture session
     * @param builder The request builder
     * @param targetFrameRate The desired frame rate
     * @return True if successfully applied
     */
    public static boolean configureSessionFrameRate(
            @NonNull CameraCaptureSession session,
            @NonNull CaptureRequest.Builder builder,
            int targetFrameRate) {
            
        try {
            // Apply the standard FPS range first
            Range<Integer> fpsRange = new Range<>(targetFrameRate, targetFrameRate);
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
            
            // Apply Samsung-specific keys
            applyFrameRateSettings(builder, targetFrameRate);
            
            // Start the repeating request
            session.setRepeatingRequest(builder.build(), null, null);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to configure Samsung session with frame rate " + targetFrameRate, e);
            return false;
        }
    }
    
    /**
     * Determine if the current Samsung device is compatible with 60fps recording
     *
     * @return The compatibility status for this device
     */
    public static SamsungFpsStatus getDeviceFpsStatus() {
        String model = Build.MODEL.toLowerCase();
        
        // Known fully compatible devices
        List<String> fullyCompatibleModels = Arrays.asList(
            "sm-g988", // S20 Ultra
            "sm-g986", // S20+
            "sm-g985", // S20+ (different variant)
            "sm-g981", // S20
            "sm-g980", // S20 (different variant)
            "sm-n981", // Note 20
            "sm-n986", // Note 20+
            "sm-n985", // Note 20 Ultra
            "sm-g781", // S20 FE
            "sm-s908", // S22 Ultra
            "sm-s906", // S22+
            "sm-s901"  // S22
        );
        
        // Devices requiring special vendor key handling
        List<String> vendorKeyModels = Arrays.asList(
            "sm-g991", // S21
            "sm-g996", // S21+
            "sm-g998", // S21 Ultra
            "sm-g990", // S21 FE
            "sm-g975", // S10+
            "sm-g973", // S10
            "sm-g970", // S10e
            "sm-g965", // S9+
            "sm-g960"  // S9
        );
        
        // Known incompatible devices
        List<String> knownIncompatibleModels = Arrays.asList(
            "sm-g950", // S8
            "sm-g955", // S8+
            "sm-j"     // J series
        );
        
        // Check model against known device lists
        for (String prefix : fullyCompatibleModels) {
            if (model.contains(prefix)) {
                return SamsungFpsStatus.FULLY_COMPATIBLE;
            }
        }
        
        for (String prefix : vendorKeyModels) {
            if (model.contains(prefix)) {
                return SamsungFpsStatus.REQUIRES_VENDOR_KEYS;
            }
        }
        
        for (String prefix : knownIncompatibleModels) {
            if (model.contains(prefix)) {
                return SamsungFpsStatus.KNOWN_INCOMPATIBLE;
            }
        }
        
        // Default to "unknown" for devices not in our database
        return SamsungFpsStatus.UNKNOWN;
    }
} 