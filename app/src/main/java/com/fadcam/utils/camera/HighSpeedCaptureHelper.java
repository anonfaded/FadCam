package com.fadcam.utils.camera;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.utils.DeviceHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Helper class for handling high-speed video capture with the Camera2 API.
 * Provides support for constraining high-speed sessions and determining hardware capabilities.
 */
public class HighSpeedCaptureHelper {
    private static final String TAG = "HighSpeedCaptureHelper";
    
    /**
     * Checks if high-speed video capture is supported for the desired frame rate
     * 
     * @param characteristics Camera characteristics
     * @param targetFrameRate Target frame rate (e.g., 60)
     * @return true if high-speed capture is supported for this frame rate
     */
    public static boolean isHighSpeedSupported(
            @NonNull CameraCharacteristics characteristics, 
            int targetFrameRate) {
        
        try {
            // Unify behavior across vendors: use platform-reported high-speed capabilities when present.
            
            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    
            if (map == null) {
                Log.d(TAG, "Stream configuration map is null");
                return false;
            }
            
            Size[] highSpeedSizes = map.getHighSpeedVideoSizes();
            if (highSpeedSizes == null || highSpeedSizes.length == 0) {
                Log.d(TAG, "No high speed video sizes available");
                return false;
            }
            
            // Check if any size supports the target frame rate
            for (Size size : highSpeedSizes) {
                Range<Integer>[] fpsRanges = map.getHighSpeedVideoFpsRangesFor(size);
                if (fpsRanges != null) {
                    for (Range<Integer> range : fpsRanges) {
                        if (range.getUpper() >= targetFrameRate) {
                            // Found a size/fps combination that supports our target
                            Log.d(TAG, "High speed supported: " + size.getWidth() + "x" + 
                                  size.getHeight() + " at " + range);
                            return true;
                        }
                    }
                }
            }
            
            Log.d(TAG, "No high-speed configuration found for " + targetFrameRate + " fps");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error checking high speed support", e);
            return false;
        }
    }
    
    /**
     * Gets the best high-speed video size for the target frame rate
     * 
     * @param characteristics Camera characteristics
     * @param targetFrameRate Target frame rate
     * @param preferredWidth Preferred width (0 for any)
     * @param preferredHeight Preferred height (0 for any)
     * @return Best high-speed video size, or null if not found
     */
    @Nullable
    public static Size getBestHighSpeedSize(
            @NonNull CameraCharacteristics characteristics,
            int targetFrameRate,
            int preferredWidth,
            int preferredHeight) {
            
        try {
            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return null;
            
            Size[] highSpeedSizes = map.getHighSpeedVideoSizes();
            if (highSpeedSizes == null || highSpeedSizes.length == 0) return null;
            
            // Filter sizes that support our target frame rate
            List<Size> supportedSizes = new ArrayList<>();
            
            for (Size size : highSpeedSizes) {
                Range<Integer>[] fpsRanges = map.getHighSpeedVideoFpsRangesFor(size);
                if (fpsRanges != null) {
                    for (Range<Integer> range : fpsRanges) {
                        if (range.getUpper() >= targetFrameRate) {
                            supportedSizes.add(size);
                            break;
                        }
                    }
                }
            }
            
            if (supportedSizes.isEmpty()) return null;
            
            // If we have preferred dimensions, try to find a matching size
            if (preferredWidth > 0 && preferredHeight > 0) {
                for (Size size : supportedSizes) {
                    if (size.getWidth() == preferredWidth && size.getHeight() == preferredHeight) {
                        return size;
                    }
                }
                
                // If exact match not found, find closest by resolution
                final int targetArea = preferredWidth * preferredHeight;
                Collections.sort(supportedSizes, new Comparator<Size>() {
                    @Override
                    public int compare(Size a, Size b) {
                        int areaA = a.getWidth() * a.getHeight();
                        int areaB = b.getWidth() * b.getHeight();
                        return Math.abs(areaA - targetArea) - Math.abs(areaB - targetArea);
                    }
                });
                
                return supportedSizes.get(0);
            }
            
            // Otherwise use the largest resolution
            Collections.sort(supportedSizes, new Comparator<Size>() {
                @Override
                public int compare(Size a, Size b) {
                    return b.getWidth() * b.getHeight() - a.getWidth() * a.getHeight();
                }
            });
            
            return supportedSizes.get(0);
        } catch (Exception e) {
            Log.e(TAG, "Error finding best high-speed size", e);
            return null;
        }
    }
    
    /**
     * Gets the best FPS range for high-speed recording for a given size
     * 
     * @param characteristics Camera characteristics
     * @param size The target video size
     * @param targetFrameRate The target frame rate
     * @return Best matching FPS range, or null if not found
     */
    @Nullable
    public static Range<Integer> getBestHighSpeedFpsRange(
            @NonNull CameraCharacteristics characteristics,
            @NonNull Size size,
            int targetFrameRate) {
            
        try {
            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return null;
            
            Range<Integer>[] fpsRanges = map.getHighSpeedVideoFpsRangesFor(size);
            if (fpsRanges == null || fpsRanges.length == 0) return null;
            
            // Try to find exact match first
            for (Range<Integer> range : fpsRanges) {
                if (range.getUpper() == targetFrameRate && range.getLower() == targetFrameRate) {
                    return range;
                }
            }
            
            // Then try to find range that includes our target
            for (Range<Integer> range : fpsRanges) {
                if (range.getLower() <= targetFrameRate && range.getUpper() >= targetFrameRate) {
                    return range;
                }
            }
            
            // Finally find closest range
            Range<Integer> bestRange = null;
            int minDiff = Integer.MAX_VALUE;
            
            for (Range<Integer> range : fpsRanges) {
                int diff = Math.abs(range.getUpper() - targetFrameRate);
                if (diff < minDiff) {
                    minDiff = diff;
                    bestRange = range;
                }
            }
            
            return bestRange;
        } catch (Exception e) {
            Log.e(TAG, "Error finding best high-speed FPS range", e);
            return null;
        }
    }
    
    /**
     * Configure a capture request builder for high-speed recording
     * 
     * @param cameraDevice The camera device
     * @param template Template type or null for default
     * @param targetFrameRate Target frame rate
     * @param characteristics Camera characteristics
     * @return Configured builder or null if failed
     */
    @Nullable
    public static CaptureRequest.Builder configureHighSpeedRequestBuilder(
            @NonNull CameraDevice cameraDevice,
            @Nullable Integer template,
            int targetFrameRate,
            @NonNull CameraCharacteristics characteristics) {
            
        try {
            // Create builder with appropriate template
            CaptureRequest.Builder builder;
            if (template == null) {
                builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            } else {
                builder = cameraDevice.createCaptureRequest(template);
            }
            
            // Find best FPS range for this device
            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    
            if (map == null) {
                Log.e(TAG, "Stream configuration map is null");
                return builder; // Return basic builder
            }
            
            // Set SENSOR_FRAME_DURATION for precise frame timing (essential for high FPS)
            // This is required for constrained high-speed sessions
            long frameDuration = 1_000_000_000L / targetFrameRate;
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDuration);
            Log.d(TAG, "Set SENSOR_FRAME_DURATION to " + frameDuration + " ns for " + targetFrameRate + "fps in high-speed request builder");
            
            return builder;
        } catch (Exception e) {
            Log.e(TAG, "Error configuring high-speed request builder", e);
            return null;
        }
    }
} 