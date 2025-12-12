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
 * Helper for detecting and configuring reasonable frame rates.
 * Huawei-specific logic removed; limited Samsung handling retained.
 */
public class FrameRateHelper {
    private static final String TAG = "FrameRateHelper";
    private static final int MAX_REASONABLE_FRAME_RATE = 240;

    public static List<Integer> getHardwareSupportedFrameRates(CameraCharacteristics characteristics) {
        List<Integer> supportedRates = new ArrayList<>();
        try {
            Range<Integer>[] fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (fpsRanges != null) {
                for (Range<Integer> range : fpsRanges) {
                    if (range.getLower() == range.getUpper() || (range.getUpper() >= 30 && range.getLower() >= 15)) {
                        supportedRates.add(range.getUpper());
                    }
                }
            }

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

            if (DeviceHelper.isSamsung()) {
                addSamsungFrameRates(supportedRates);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error detecting supported frame rates", e);
        }

        Set<Integer> uniqueRates = new TreeSet<>(supportedRates);
        supportedRates = new ArrayList<>(uniqueRates);
        return filterFrameRates(supportedRates);
    }

    public static Range<Integer> findBestFpsRange(CameraCharacteristics characteristics, int targetFps) {
        Range<Integer> bestRange = new Range<>(30, 30);
        try {
            Range<Integer>[] fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (fpsRanges == null || fpsRanges.length == 0) {
                Log.w(TAG, "No FPS ranges available, using default");
                return bestRange;
            }

            int bestDiff = Integer.MAX_VALUE;
            for (Range<Integer> range : fpsRanges) {
                if (range.getLower() <= targetFps && range.getUpper() >= targetFps) {
                    int rangeDiff = range.getUpper() - targetFps + targetFps - range.getLower();
                    if (rangeDiff < bestDiff) {
                        bestDiff = rangeDiff;
                        bestRange = range;
                        if (range.getLower() == targetFps && range.getUpper() == targetFps) {
                            return range;
                        }
                    }
                }
            }

            if (bestDiff == Integer.MAX_VALUE) {
                for (Range<Integer> range : fpsRanges) {
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

    private static List<Integer> filterFrameRates(List<Integer> allRates) {
        if (allRates.size() <= 15) {
            Collections.sort(allRates);
            return allRates;
        }
        Log.w(TAG, "Large number of frame rates detected (" + allRates.size() + "), filtering");
        Set<Integer> filteredRates = new TreeSet<>();
        int[] standardRates = {24, 25, 30, 50, 60, 90, 120, 240};
        for (int rate : standardRates) {
            if (allRates.contains(rate)) filteredRates.add(rate);
        }
        for (Integer rate : allRates) {
            if (rate <= MAX_REASONABLE_FRAME_RATE) filteredRates.add(rate);
        }
        return new ArrayList<>(filteredRates);
    }

    private static void addSamsungFrameRates(List<Integer> supportedRates) {
        try {
            if (!supportedRates.contains(60)) {
                supportedRates.add(60);
                Log.d(TAG, "Added 60fps for Samsung device");
            }
            if (DeviceHelper.isHighEndDevice() && !supportedRates.contains(120)) {
                supportedRates.add(120);
                Log.d(TAG, "Added 120fps for high-end Samsung device");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error adding Samsung frame rates", e);
        }
    }

    public static boolean applyVendorSpecificFrameRate(
            @NonNull CaptureRequest.Builder builder,
            int targetFrameRate,
            @Nullable CameraCharacteristics characteristics) {
        boolean appliedVendorSettings = false;
        Range<Integer> fpsRange = new Range<>(targetFrameRate, targetFrameRate);
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
        try {
            if (DeviceHelper.isSamsung()) {
                appliedVendorSettings = applySamsungFrameRate(builder, targetFrameRate);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error applying vendor-specific frame rate settings", e);
        }
        return appliedVendorSettings;
    }

    private static boolean applySamsungFrameRate(CaptureRequest.Builder builder, int targetFrameRate) {
        try {
            CaptureRequest.Key<Integer> samsungMaxFpsKey =
                    new CaptureRequest.Key<>("samsung.android.control.recordingMaxFps", Integer.class);
            CaptureRequest.Key<Integer> samsungMinFpsKey =
                    new CaptureRequest.Key<>("samsung.android.control.recordingMinFps", Integer.class);
            builder.set(samsungMaxFpsKey, targetFrameRate);
            builder.set(samsungMinFpsKey, targetFrameRate);
            Log.d(TAG, "Applied Samsung-specific frame rate settings for " + targetFrameRate + "fps");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply Samsung frame rate settings: " + e.getMessage());
            return false;
        }
    }

    public static boolean isDeviceLikelyToSupport60fps() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (DeviceHelper.isHighEndDevice()) return true;
            if (DeviceHelper.isSamsung() && DeviceHelper.isHighEndDevice()) return true;
        }
        return false;
    }
}