package com.fadcam.utils.camera;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.utils.DeviceHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Helper class for detecting and configuring frame rates across different device manufacturers.
 * Provides optimized handling for Samsung and Huawei/Honor devices.
 */
public class FrameRateHelper {
    private static final String TAG = "FrameRateHelper";
    
    // Maximum reasonable frame rate to prevent UI showing unrealistic values (like 4032)
    private static final int MAX_REASONABLE_FRAME_RATE = 240;

    /**
     * Gets the list of hardware-supported frame rates for a given camera
     * 
     * @param characteristics Camera characteristics
     * @return List of supported frame rates
     */
    public static List<Integer> getHardwareSupportedFrameRates(CameraCharacteristics characteristics) {
        List<Integer> supportedRates = new ArrayList<>();
        
        try {
            // Standard Camera2 API approach
            Range<Integer>[] fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (fpsRanges != null) {
                for (Range<Integer> range : fpsRanges) {
                    // Only add frame rates that can be sustained (lower == upper)
                    // or where upper limit is actually achievable
                    if (range.getLower() == range.getUpper() || 
                        (range.getUpper() >= 30 && range.getLower() >= 15)) {
                        supportedRates.add(range.getUpper());
                    }
                }
            }
            
            // Check for high-speed video ranges
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null && map.getHighSpeedVideoSizes() != null) {
                for (Size size : map.getHighSpeedVideoSizes()) {
                    Range<Integer>[] highSpeedRanges = map.getHighSpeedVideoFpsRangesFor(size);
                    if (highSpeedRanges != null) {
                        for (Range<Integer> range : highSpeedRanges) {
                            supportedRates.add(range.getUpper());
                        }
                    }
                }
            }
            
            // Use device-specific approaches when necessary
            if (DeviceHelper.isSamsung()) {
                addSamsungFrameRates(characteristics, supportedRates);
            } else if (DeviceHelper.isHuawei()) {
                addHuaweiFrameRates(characteristics, supportedRates);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error detecting supported frame rates", e);
        }
        
        // Remove duplicates and sort
        Set<Integer> uniqueRates = new TreeSet<>(supportedRates);
        supportedRates = new ArrayList<>(uniqueRates);
        
        return filterFrameRates(supportedRates);
    }
    
    /**
     * Find the best matching FPS range for the target frame rate
     * 
     * @param characteristics Camera characteristics
     * @param targetFps The desired frame rate
     * @return The best matching FPS range, or fallback to a default range
     */
    public static Range<Integer> findBestFpsRange(CameraCharacteristics characteristics, int targetFps) {
        Range<Integer> bestRange = new Range<>(30, 30); // Default fallback
        
        try {
            Range<Integer>[] fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (fpsRanges == null || fpsRanges.length == 0) {
                Log.w(TAG, "No FPS ranges available, using default");
                return bestRange;
            }
            
            int bestDiff = Integer.MAX_VALUE;
            
            for (Range<Integer> range : fpsRanges) {
                // Exact match is perfect
                if (range.getLower() <= targetFps && range.getUpper() >= targetFps) {
                    // Prefer smaller ranges centered around our target
                    int rangeDiff = range.getUpper() - targetFps + targetFps - range.getLower();
                    if (rangeDiff < bestDiff) {
                        bestDiff = rangeDiff;
                        bestRange = range;
                        
                        // If we found an exact matching range, we can stop
                        if (range.getLower() == targetFps && range.getUpper() == targetFps) {
                            return range;
                        }
                    }
                }
            }
            
            // If we didn't find a range containing our target, find the closest one
            if (bestDiff == Integer.MAX_VALUE) {
                for (Range<Integer> range : fpsRanges) {
                    // Find range with upper bound closest to target
                    int diff = Math.abs(range.getUpper() - targetFps);
                    if (diff < bestDiff) {
                        bestDiff = diff;
                        bestRange = range;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error finding best FPS range", e);
        }
        
        return bestRange;
    }
    
    /**
     * Filter frame rates to a reasonable list for display in the UI
     * 
     * @param allRates All detected frame rates
     * @return Filtered list of usable frame rates
     */
    private static List<Integer> filterFrameRates(List<Integer> allRates) {
        // If the list is small enough, just return it sorted
        if (allRates.size() <= 15) {
            Collections.sort(allRates);
            return allRates;
        }
        
        Log.w(TAG, "Large number of frame rates detected (" + allRates.size() + "), filtering to keep useful values");
        
        // Filter to keep standard values + higher FPS values
        Set<Integer> filteredRates = new TreeSet<>();
        
        // Important standard rates to always include if supported
        int[] standardRates = {24, 25, 30, 50, 60, 90, 120, 240};
        for (int rate : standardRates) {
            if (allRates.contains(rate)) {
                filteredRates.add(rate);
            }
        }
        
        // Add values from the original list, but cap at MAX_REASONABLE_FRAME_RATE
        for (Integer rate : allRates) {
            if (rate <= MAX_REASONABLE_FRAME_RATE) {
                filteredRates.add(rate);
            } else {
                Log.w(TAG, "Filtered out unreasonable frame rate: " + rate);
            }
        }
        
        return new ArrayList<>(filteredRates);
    }
    
    /**
     * Add Samsung-specific frame rates using vendor extensions
     * 
     * @param characteristics Camera characteristics
     * @param supportedRates List to add detected rates to
     */
    private static void addSamsungFrameRates(CameraCharacteristics characteristics, List<Integer> supportedRates) {
        try {
            // Samsung devices often support 60fps even when not reported through standard APIs
            if (!supportedRates.contains(60)) {
                supportedRates.add(60);
                Log.d(TAG, "Added 60fps for Samsung device");
            }
            
            // Some Samsung phones support high frame rates that may not be reported
            if (DeviceHelper.isHighEndDevice() && !supportedRates.contains(120)) {
                supportedRates.add(120);
                Log.d(TAG, "Added 120fps for high-end Samsung device");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error adding Samsung frame rates", e);
        }
    }
    
    /**
     * Add Huawei/Honor-specific frame rates
     * 
     * @param characteristics Camera characteristics
     * @param supportedRates List to add detected rates to
     */
    private static void addHuaweiFrameRates(CameraCharacteristics characteristics, List<Integer> supportedRates) {
        try {
            // Some Huawei/Honor devices support 60fps but don't report it properly
            // Their devices often need specific resolution/fps combinations
            if (!supportedRates.contains(60)) {
                // Only add it if this is likely a device that supports it
                if (DeviceHelper.isHighEndDevice()) {
                    supportedRates.add(60);
                    Log.d(TAG, "Added 60fps for high-end Huawei device");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error adding Huawei frame rates", e);
        }
    }
    
    /**
     * Applies vendor-specific frame rate settings to a capture request builder
     *
     * @param builder The capture request builder to modify
     * @param targetFrameRate The target frame rate to set
     * @param characteristics Camera characteristics for the current camera
     * @return True if vendor-specific settings were applied
     */
    public static boolean applyVendorSpecificFrameRate(
            @NonNull CaptureRequest.Builder builder,
            int targetFrameRate,
            @Nullable CameraCharacteristics characteristics) {
        
        boolean appliedVendorSettings = false;
        
        // Standard Camera2 API setting (should be applied regardless)
        Range<Integer> fpsRange = new Range<>(targetFrameRate, targetFrameRate);
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
        
        try {
            // Samsung-specific logic
            if (DeviceHelper.isSamsung()) {
                appliedVendorSettings = applySamsungFrameRate(builder, targetFrameRate);
            }
            // Huawei-specific logic
            else if (DeviceHelper.isHuawei()) {
                appliedVendorSettings = applyHuaweiFrameRate(builder, targetFrameRate);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error applying vendor-specific frame rate settings", e);
        }
        
        return appliedVendorSettings;
    }
    
    /**
     * Apply Samsung-specific frame rate settings
     */
    private static boolean applySamsungFrameRate(CaptureRequest.Builder builder, int targetFrameRate) {
        try {
            // Samsung vendor keys for FPS control based on GCam report
            CaptureRequest.Key<Integer> samsungMaxFpsKey = 
                new CaptureRequest.Key<>("samsung.android.control.recordingMaxFps", Integer.class);
            CaptureRequest.Key<Integer> samsungMinFpsKey = 
                new CaptureRequest.Key<>("samsung.android.control.recordingMinFps", Integer.class);
            
            // Set Samsung-specific FPS values
            builder.set(samsungMaxFpsKey, targetFrameRate);
            builder.set(samsungMinFpsKey, targetFrameRate);
            
            Log.d(TAG, "Applied Samsung-specific frame rate settings for " + targetFrameRate + "fps");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply Samsung frame rate settings: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Apply Huawei-specific frame rate settings
     */
    private static boolean applyHuaweiFrameRate(CaptureRequest.Builder builder, int targetFrameRate) {
        try {
            // Huawei vendor keys for FPS control
            // Note: This is experimental as the exact keys are not documented
            
            // Some Huawei devices might use these keys (found through analysis)
            try {
                CaptureRequest.Key<Integer> huaweiFrameRateKey = 
                    new CaptureRequest.Key<>("huawei.android.control.captureFrameRate", Integer.class);
                builder.set(huaweiFrameRateKey, targetFrameRate);
                Log.d(TAG, "Applied Huawei-specific frame rate setting (captureFrameRate) for " + targetFrameRate + "fps");
            } catch (Exception e) {
                Log.d(TAG, "Huawei captureFrameRate key not available");
            }
            
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply Huawei frame rate settings: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Checks if the device model is known to support 60fps video recording
     * 
     * @return True if the device is likely to support 60fps recording
     */
    public static boolean isDeviceLikelyToSupport60fps() {
        // Most mid-range and high-end devices since 2018 support 60fps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) { // Android 7.0+
            if (DeviceHelper.isHighEndDevice()) {
                return true;
            }
            
            // Samsung flagship devices typically support high frame rates
            if (DeviceHelper.isSamsung() && DeviceHelper.isHighEndDevice()) {
                return true;
            }
        }
        
        return false;
    }
} 