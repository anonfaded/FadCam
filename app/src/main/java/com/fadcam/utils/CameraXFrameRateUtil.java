package com.fadcam.utils;

import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.media.CamcorderProfile;
import android.util.Log;
import android.util.Range;

import androidx.annotation.NonNull;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.UseCase;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.fadcam.Constants;
import com.fadcam.CameraType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Utility class to handle framerate detection using CameraX API
 */
public class CameraXFrameRateUtil {
    private static final String TAG = "CameraXFrameRateUtil";
    private static final Executor executor = Executors.newSingleThreadExecutor();

    /**
     * Gets all supported framerates for a specific camera type using CameraX API
     *
     * @param context    The application context
     * @param cameraType The camera type (FRONT or BACK)
     * @return List of supported framerates, sorted in ascending order. Returns default [30] on error.
     */
    @ExperimentalCamera2Interop
    public static List<Integer> getHardwareSupportedFrameRates(@NonNull Context context, CameraType cameraType) {
        Log.i(TAG, "=== Getting Hardware Supported FPS for CameraType: " + cameraType + " using CameraX API ===");
        final List<Integer> defaultRateList = Collections.singletonList(Constants.DEFAULT_VIDEO_FRAME_RATE);

        try {
            ProcessCameraProvider cameraProvider = ProcessCameraProvider.getInstance(context).get();
            if (cameraProvider == null) {
                Log.e(TAG, "Failed to get camera provider");
                return defaultRateList;
            }

            // Select camera based on type
            CameraSelector cameraSelector = (cameraType == CameraType.FRONT) ?
                    CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;

            // Get the camera info
            List<CameraInfo> cameraInfos = cameraProvider.getAvailableCameraInfos();
            if (cameraInfos.isEmpty()) {
                Log.e(TAG, "No cameras available");
                return defaultRateList;
            }

            // First unbind any existing use cases to ensure we can access the camera
            cameraProvider.unbindAll();
            
            // Find camera matching our selector
            CameraInfo targetCamera = null;
            try {
                List<CameraInfo> filteredCameras = cameraSelector.filter(cameraInfos);
                if (!filteredCameras.isEmpty()) {
                    targetCamera = filteredCameras.get(0);
                    Log.d(TAG, "Found camera matching selector: " + cameraType);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error filtering cameras: " + e.getMessage());
            }

            if (targetCamera == null) {
                Log.e(TAG, "Could not find camera matching selector: " + cameraType);
                return defaultRateList;
            }

            // Get Camera2 interop to access FPS ranges
            Camera2CameraInfo camera2Info = Camera2CameraInfo.from(targetCamera);
            String cameraId = camera2Info.getCameraId();

            // Create a set to store all possible framerates
            Set<Integer> framerates = new TreeSet<>(); // TreeSet automatically sorts

            // First check for higher framerates in CamcorderProfiles
            int maxProfileFps = 30; // Default assumption
            
            try {
                // Check all quality levels for max framerates
                int cameraIdInt = Integer.parseInt(cameraId);
                int[] qualities = {
                    CamcorderProfile.QUALITY_HIGH, CamcorderProfile.QUALITY_2160P,
                    CamcorderProfile.QUALITY_1080P, CamcorderProfile.QUALITY_720P
                };
                
                for (int quality : qualities) {
                    if (CamcorderProfile.hasProfile(cameraIdInt, quality)) {
                        CamcorderProfile profile = CamcorderProfile.get(cameraIdInt, quality);
                        if (profile != null && profile.videoFrameRate > maxProfileFps) {
                            maxProfileFps = profile.videoFrameRate;
                            Log.d(TAG, "Found higher framerate " + maxProfileFps + 
                                  " in CamcorderProfile quality " + quality);
                        }
                    }
                }
                
                // Check for high speed profiles
                if (CamcorderProfile.hasProfile(cameraIdInt, CamcorderProfile.QUALITY_HIGH_SPEED_HIGH)) {
                    CamcorderProfile profile = CamcorderProfile.get(cameraIdInt, CamcorderProfile.QUALITY_HIGH_SPEED_HIGH);
                    if (profile != null && profile.videoFrameRate > maxProfileFps) {
                        maxProfileFps = profile.videoFrameRate;
                        Log.d(TAG, "Found high-speed framerate " + maxProfileFps + " in QUALITY_HIGH_SPEED_HIGH");
                    }
                }
                
                if (CamcorderProfile.hasProfile(cameraIdInt, CamcorderProfile.QUALITY_HIGH_SPEED_1080P)) {
                    CamcorderProfile profile = CamcorderProfile.get(cameraIdInt, CamcorderProfile.QUALITY_HIGH_SPEED_1080P);
                    if (profile != null && profile.videoFrameRate > maxProfileFps) {
                        maxProfileFps = profile.videoFrameRate;
                        Log.d(TAG, "Found high-speed framerate " + maxProfileFps + " in QUALITY_HIGH_SPEED_1080P");
                    }
                }
                
                if (CamcorderProfile.hasProfile(cameraIdInt, CamcorderProfile.QUALITY_HIGH_SPEED_720P)) {
                    CamcorderProfile profile = CamcorderProfile.get(cameraIdInt, CamcorderProfile.QUALITY_HIGH_SPEED_720P);
                    if (profile != null && profile.videoFrameRate > maxProfileFps) {
                        maxProfileFps = profile.videoFrameRate;
                        Log.d(TAG, "Found high-speed framerate " + maxProfileFps + " in QUALITY_HIGH_SPEED_720P");
                    }
                }
                
                Log.d(TAG, "Maximum framerate found in CamcorderProfiles: " + maxProfileFps);
            } catch (NumberFormatException e) {
                Log.w(TAG, "Could not parse camera ID as integer: " + cameraId);
            } catch (Exception e) {
                Log.w(TAG, "Error checking CamcorderProfiles: " + e.getMessage());
            }
            
            // Get the FPS ranges from camera characteristics using the Camera2 API directly
            Range<Integer>[] fpsRanges = null;
            try {
                CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                android.hardware.camera2.CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
                fpsRanges = cameraCharacteristics.get(
                        android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                
                Log.d(TAG, "Successfully retrieved FPS ranges using Camera2 API for camera ID: " + cameraId);
            } catch (Exception e) {
                Log.e(TAG, "Error getting camera characteristics using Camera2 API: " + e.getMessage(), e);
                // Continue with empty fpsRanges, will be handled below
            }
            
            if (fpsRanges == null || fpsRanges.length == 0) {
                Log.w(TAG, "No FPS ranges reported by hardware for camera " + cameraId + ". Using CamcorderProfile.");
                
                // Create some basic framerates based on CamcorderProfile information
                for (int fps = 10; fps <= maxProfileFps; fps += 5) {
                    if (fps <= 30 || fps % 30 == 0) { // Include all multiples of 30 over 30fps
                        framerates.add(fps);
                    }
                }
                
                // Add standard framerates
                int[] standardRates = {24, 25, 30, 60, 90, 120};
                for (int rate : standardRates) {
                    if (rate <= maxProfileFps) {
                        framerates.add(rate);
                    }
                }
            } else {
                Log.d(TAG, "Processing " + fpsRanges.length + " FPS ranges from CameraX");
                
                // Process each range to get ALL supported framerates
                for (Range<Integer> range : fpsRanges) {
                    if (range != null) {
                        int lower = range.getLower();
                        int upper = range.getUpper();
                        
                        Log.d(TAG, "Processing range " + lower + "-" + upper);
                        
                        // For most devices, framerates are available at discrete steps (usually 1fps)
                        // Add ALL integer values within the range
                        for (int fps = lower; fps <= upper; fps++) {
                            framerates.add(fps);
                        }
                    }
                }
                
                // If CamcorderProfile reported higher framerates than Camera2 API, add those too
                if (maxProfileFps > 30) {
                    Log.d(TAG, "Adding higher framerates from CamcorderProfile");
                    
                    // Add standard high framerates if they're supported by the profile
                    int[] highRates = {60, 90, 120, 240};
                    for (int rate : highRates) {
                        if (rate <= maxProfileFps) {
                            framerates.add(rate);
                            Log.d(TAG, "Added " + rate + "fps from CamcorderProfile");
                        }
                    }
                }
            }
            
            // Ensure we have at least one value (the default)
            if (framerates.isEmpty()) {
                Log.e(TAG, "No valid framerates found. Adding default: " + Constants.DEFAULT_VIDEO_FRAME_RATE);
                framerates.add(Constants.DEFAULT_VIDEO_FRAME_RATE);
            }
            
            // Convert to list and ensure the list is sorted (which TreeSet already does)
            List<Integer> finalSupportedRates = new ArrayList<>(framerates);
            
            // If the list is too large, filter to keep just common/useful values
            if (finalSupportedRates.size() > 20) {
                Log.w(TAG, "Large number of framerates detected (" + finalSupportedRates.size() + 
                      "), keeping only useful values for UI");
                
                // Filter to keep standard values + any higher FPS values
                Set<Integer> filteredRates = new TreeSet<>();
                
                // Important standard rates to always include if supported
                int[] standardRates = {24, 25, 30, 50, 60, 90, 120, 240};
                for (int rate : standardRates) {
                    if (framerates.contains(rate)) {
                        filteredRates.add(rate);
                    }
                }
                
                // Also include significant non-standard rates
                for (int fps : framerates) {
                    // Include rates divisible by 5 (e.g., 5, 10, 15, 20, 25...)
                    if (fps % 5 == 0 && fps <= 60) {
                        filteredRates.add(fps);
                    }
                    // Include all higher framerates (e.g., 72, 90, 120, etc.)
                    else if (fps > 60) {
                        filteredRates.add(fps);
                    }
                }
                
                // If we've excluded the default rate by accident, add it back
                if (!filteredRates.contains(Constants.DEFAULT_VIDEO_FRAME_RATE) && 
                    framerates.contains(Constants.DEFAULT_VIDEO_FRAME_RATE)) {
                    filteredRates.add(Constants.DEFAULT_VIDEO_FRAME_RATE);
                }
                
                // Replace the full list with our filtered list
                finalSupportedRates = new ArrayList<>(filteredRates);
                Log.d(TAG, "Filtered to " + finalSupportedRates.size() + " useful framerates");
            }
            
            // Log the final list of framerates
            StringBuilder ratesStr = new StringBuilder();
            for (Integer rate : finalSupportedRates) {
                ratesStr.append(rate).append(", ");
            }
            Log.d(TAG, "Final supported framerates: " + ratesStr);
            
            return finalSupportedRates;
            
        } catch (ExecutionException | InterruptedException e) {
            Log.e(TAG, "Failed to get camera provider", e);
            return defaultRateList;
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error in getHardwareSupportedFrameRates", e);
            return defaultRateList;
        }
    }
} 