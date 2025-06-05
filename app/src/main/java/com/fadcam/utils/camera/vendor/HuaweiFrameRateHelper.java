package com.fadcam.utils.camera.vendor;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import androidx.annotation.NonNull;

/**
 * Helper class for handling Huawei/Honor specific camera frame rate settings
 */
public class HuaweiFrameRateHelper {
    private static final String TAG = "HuaweiFrameRateHelper";
    
    // Huawei vendor keys (experimental, based on limited documentation)
    private static final String KEY_CAPTURE_FRAME_RATE = "huawei.android.control.captureFrameRate";
    private static final String KEY_VIDEO_HIGH_FRAME_RATE = "huawei.android.control.videoHighFrameRate";
    private static final String KEY_SUPER_HIGH_RESOLUTION_MODE = "huawei.android.control.superHighResolutionMode";
    
    /**
     * Apply Huawei/Honor-specific frame rate settings to a capture request builder
     *
     * @param builder The request builder to modify
     * @param targetFrameRate The desired frame rate
     * @return True if Huawei-specific settings were applied
     */
    public static boolean applyFrameRateSettings(
            @NonNull CaptureRequest.Builder builder,
            int targetFrameRate) {
            
        boolean anyApplied = false;
        
        try {
            // Try the captureFrameRate key first
            try {
                CaptureRequest.Key<Integer> captureFrameRateKey = 
                    new CaptureRequest.Key<>(KEY_CAPTURE_FRAME_RATE, Integer.class);
                builder.set(captureFrameRateKey, targetFrameRate);
                Log.d(TAG, "Applied Huawei captureFrameRate key: " + targetFrameRate);
                anyApplied = true;
            } catch (Exception e) {
                Log.d(TAG, "Huawei captureFrameRate key not available on this device");
            }
            
            // Try the videoHighFrameRate key for 60fps+
            if (targetFrameRate >= 60) {
                try {
                    CaptureRequest.Key<Integer> videoHighFrameRateKey = 
                        new CaptureRequest.Key<>(KEY_VIDEO_HIGH_FRAME_RATE, Integer.class);
                    builder.set(videoHighFrameRateKey, 1); // Enable high frame rate mode
                    Log.d(TAG, "Applied Huawei videoHighFrameRate key");
                    anyApplied = true;
                } catch (Exception e) {
                    Log.d(TAG, "Huawei videoHighFrameRate key not available on this device");
                }
            }
            
            // For 60fps, we may need to disable super high resolution mode on some models
            if (targetFrameRate >= 60) {
                try {
                    CaptureRequest.Key<Integer> superHighResolutionKey = 
                        new CaptureRequest.Key<>(KEY_SUPER_HIGH_RESOLUTION_MODE, Integer.class);
                    builder.set(superHighResolutionKey, 0); // Disable super high resolution
                    Log.d(TAG, "Disabled Huawei super high resolution mode for high fps");
                    anyApplied = true;
                } catch (Exception e) {
                    Log.d(TAG, "Huawei superHighResolutionMode key not available on this device");
                }
            }
            
            // Always set the standard Camera2 API FPS range
            Range<Integer> fpsRange = new Range<>(targetFrameRate, targetFrameRate);
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
            
            if (anyApplied) {
                Log.d(TAG, "Successfully applied Huawei-specific frame rate settings");
            } else {
                Log.d(TAG, "No Huawei-specific keys were applied, using standard API only");
            }
            
            return anyApplied;
        } catch (Exception e) {
            Log.e(TAG, "Error applying Huawei frame rate settings", e);
            return false;
        }
    }
    
    /**
     * Checks if the given resolution/fps combination is likely to work on Huawei devices
     *
     * @param size The video size
     * @param targetFrameRate The target frame rate
     * @return True if the combination is likely to work
     */
    public static boolean isSupportedResolutionFpsCombination(
            @NonNull Size size, 
            int targetFrameRate) {
            
        int width = size.getWidth();
        int height = size.getHeight();
        
        // Many Huawei devices don't support 60fps at 4K
        if (targetFrameRate >= 60 && (width >= 3840 || height >= 3840)) {
            Log.d(TAG, "Huawei devices typically don't support 60fps at 4K resolution");
            return false;
        }
        
        // Older Huawei devices may limit 60fps to 1080p or lower
        String model = Build.MODEL.toLowerCase();
        boolean isOlderDevice = model.contains("p10") || model.contains("mate 10") || 
                               model.contains("p9") || model.contains("honor 8");
        
        if (isOlderDevice && targetFrameRate >= 60 && (width > 1920 || height > 1920)) {
            Log.d(TAG, "Older Huawei devices typically limit 60fps to 1080p or lower");
            return false;
        }
        
        return true;
    }
    
    /**
     * Get the recommended maximum resolution for a target frame rate on Huawei devices
     *
     * @param targetFrameRate The target frame rate
     * @return The max recommended resolution, or null for no restriction
     */
    public static Size getRecommendedMaxResolution(int targetFrameRate) {
        if (targetFrameRate <= 30) {
            // 30fps or lower can use any resolution
            return null;
        } else if (targetFrameRate <= 60) {
            // 60fps typically limited to 1080p on most Huawei devices
            return new Size(1920, 1080);
        } else {
            // Higher frame rates typically limited to 720p
            return new Size(1280, 720);
        }
    }
} 