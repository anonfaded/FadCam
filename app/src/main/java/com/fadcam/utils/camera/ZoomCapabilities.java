package com.fadcam.utils.camera;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.util.Range;

import com.fadcam.CameraType;
import com.fadcam.FLog;
import com.fadcam.SharedPreferencesManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Central camera-zoom capability queries + one-shot diagnostics.
 *
 * <p>Zoom below 1.0x is only possible when the opened camera advertises a
 * {@code CONTROL_ZOOM_RATIO_RANGE} whose lower bound is below 1.0, or when a
 * separate ultra-wide back camera is exposed in {@link CameraManager#getCameraIdList()}.
 * Some vendors (notably Samsung on many A-series phones) hide the ultra-wide
 * sensor behind a package whitelist in the camera service, so it is neither
 * listed nor reachable through the standard API.
 */
public final class ZoomCapabilities {

    private static final String TAG = "ZoomCaps";
    private static final float FALLBACK_MAX_ZOOM = 5.0f;
    private static final AtomicBoolean REPORT_LOGGED = new AtomicBoolean(false);

    /**
     * Samsung vendor camera IDs reported by stock-camera logs on A-series devices.
     * We only query their characteristics (never open them); a successful read
     * tells us whether a hidden sensor exists on this firmware.
     */
    private static final String[] HIDDEN_ID_PROBES = {
            "2", "3", "4", "20", "21", "50", "52", "54"
    };

    private ZoomCapabilities() {
    }

    /**
     * Builds the zoom slider values: fine 0.1 steps from 0.5x to 1.0x, then the
     * legacy 0.5 steps above 1.0x. The 0.5–0.9 values are kept even on phones
     * that cannot honour them; the picker shows a banner explaining the limit.
     */
    public static List<Float> buildZoomRatioOptions(float maxZoom) {
        List<Float> ratios = new ArrayList<>();
        for (int i = 5; i <= 9; i++) {
            ratios.add(i / 10f);
        }
        ratios.add(1.0f);
        for (float z = 1.5f; z <= maxZoom + 0.001f; z += 0.5f) {
            ratios.add(Math.round(z * 10f) / 10f);
        }
        float roundedMax = Math.round(maxZoom * 10f) / 10f;
        if (roundedMax > 1.0f && !ratios.contains(roundedMax)) {
            ratios.add(roundedMax);
        }
        Collections.sort(ratios);
        return ratios;
    }

    /** Hardware minimum for the currently selected camera, or 1.0 when unknown. */
    public static float minZoom(Context context, CameraType type) {
        CameraManager manager = manager(context);
        String cameraId = resolveCameraId(context, manager, type);
        if (manager == null || cameraId == null) {
            return 1.0f;
        }
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Range<Float> range = characteristics.get(
                        CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                if (range != null && range.getLower() != null) {
                    return range.getLower();
                }
            }
        } catch (Throwable t) {
            // Some older Samsung HALs throw AssertionError for this key.
            FLog.w(TAG, "minZoom failed for camera " + cameraId + ": "
                    + t.getClass().getSimpleName());
        }
        return 1.0f;
    }

    /** Hardware maximum for the currently selected camera, or 5.0 when unknown. */
    public static float maxZoom(Context context, CameraType type) {
        CameraManager manager = manager(context);
        String cameraId = resolveCameraId(context, manager, type);
        if (manager == null || cameraId == null) {
            return FALLBACK_MAX_ZOOM;
        }
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Range<Float> range = characteristics.get(
                        CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                if (range != null && range.getUpper() != null) {
                    return range.getUpper();
                }
            }
        } catch (Throwable t) {
            FLog.w(TAG, "maxZoom failed for camera " + cameraId + ": "
                    + t.getClass().getSimpleName());
        }
        return FALLBACK_MAX_ZOOM;
    }

    /** True when the selected camera can widen its view below 1.0x. */
    public static boolean supportsZoomOut(Context context, CameraType type) {
        return minZoom(context, type) < 0.999f;
    }

    /**
     * Banner is only useful when the selected back camera cannot zoom out and
     * no separate ultra-wide back camera is exposed to the app. If a wide lens
     * is selectable, the lens picker is the right fix, not this banner.
     */
    public static boolean shouldShowZoomOutBanner(Context context, CameraType type) {
        if (type == CameraType.FRONT) {
            return false;
        }
        if (supportsZoomOut(context, type)) {
            return false;
        }
        return !hasSeparateWideBackCamera(context, type);
    }

    /**
     * One-shot capability report. Runs on a short-lived background thread so
     * opening the zoom sheet never blocks on camera-service binder calls.
     */
    public static void logCameraReportOnce(Context context) {
        if (!REPORT_LOGGED.compareAndSet(false, true)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                logCameraReport(appContext);
            } catch (Throwable t) {
                FLog.w(TAG, "camera capability report failed: "
                        + t.getClass().getSimpleName());
            }
        }, "zoom-caps-report").start();
    }

    /** One-line decision log for each zoom sheet open. */
    public static void logZoomDecision(Context context, CameraType type, boolean banner) {
        CameraManager manager = manager(context);
        String cameraId = resolveCameraId(context, manager, type);
        FLog.i(TAG, "zoomSheet camera=" + type
                + " id=" + cameraId
                + " min=" + minZoom(context, type)
                + " max=" + maxZoom(context, type)
                + " wideBackCamera=" + hasSeparateWideBackCamera(context, type)
                + " banner=" + banner);
    }

    private static void logCameraReport(Context context) {
        CameraManager manager = manager(context);
        FLog.i(TAG, "device=" + Build.MANUFACTURER + " " + Build.MODEL
                + " sdk=" + Build.VERSION.SDK_INT
                + " package=" + context.getPackageName());
        if (manager == null) {
            FLog.w(TAG, "camera manager unavailable");
            return;
        }

        String[] publicIds = cameraIds(manager);
        FLog.i(TAG, "publicCameraIds=" + Arrays.toString(publicIds));
        for (String id : publicIds) {
            logCamera(manager, id, false);
        }

        // Hidden vendor IDs: success means the sensor exists on this firmware.
        // Failures are expected and intentionally not logged, to keep the dump small.
        for (String id : HIDDEN_ID_PROBES) {
            if (Arrays.asList(publicIds).contains(id)) {
                continue;
            }
            logCamera(manager, id, true);
        }

        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(context);
        CameraType type = prefs.getCameraSelection();
        FLog.i(TAG, "selected camera=" + type
                + " id=" + resolveCameraId(context, manager, type)
                + " min=" + minZoom(context, type)
                + " max=" + maxZoom(context, type)
                + " wideBackCamera=" + hasSeparateWideBackCamera(context, type)
                + " banner=" + shouldShowZoomOutBanner(context, type));
    }

    private static void logCamera(CameraManager manager, String cameraId, boolean hiddenProbe) {
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            float[] focalLengths = characteristics.get(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);

            String zoomRange;
            try {
                Range<Float> range = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        ? characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                        : null;
                zoomRange = String.valueOf(range);
            } catch (Throwable t) {
                zoomRange = "error:" + t.getClass().getSimpleName();
            }

            int[] capabilities = characteristics.get(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            boolean logicalMultiCamera = capabilities != null
                    && contains(capabilities,
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA);

            String physicalIds = "n/a";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    Set<String> ids = characteristics.getPhysicalCameraIds();
                    physicalIds = String.valueOf(ids);
                } catch (Throwable t) {
                    physicalIds = "error:" + t.getClass().getSimpleName();
                }
            }

            FLog.i(TAG, (hiddenProbe ? "hiddenProbe" : "camera")
                    + " id=" + cameraId
                    + " facing=" + facing
                    + " focal=" + Arrays.toString(focalLengths)
                    + " zoomRange=" + zoomRange
                    + " logicalMultiCamera=" + logicalMultiCamera
                    + " physicalIds=" + physicalIds);
        } catch (Throwable t) {
            if (!hiddenProbe) {
                FLog.w(TAG, "camera id=" + cameraId + " read failed: "
                        + t.getClass().getSimpleName());
            }
        }
    }

    private static boolean hasSeparateWideBackCamera(Context context, CameraType type) {
        if (type == CameraType.FRONT) {
            return false;
        }
        CameraManager manager = manager(context);
        if (manager == null) {
            return false;
        }
        String selectedId = resolveCameraId(context, manager, type);
        float selectedFocal = focalLength(manager, selectedId);
        if (selectedFocal <= 0f) {
            return false;
        }

        List<String> backIds = new ArrayList<>();
        float minFocal = Float.MAX_VALUE;
        for (String id : cameraIds(manager)) {
            Integer facing = facing(manager, id);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                backIds.add(id);
                float focal = focalLength(manager, id);
                if (focal > 0f && focal < minFocal) {
                    minFocal = focal;
                }
            }
        }
        if (backIds.size() <= 1) {
            return false;
        }
        // Already on the widest exposed back lens.
        if (selectedFocal <= minFocal + 0.01f) {
            return false;
        }
        for (String id : backIds) {
            if (id.equals(selectedId)) {
                continue;
            }
            float focal = focalLength(manager, id);
            // A meaningfully shorter focal length indicates an ultra-wide lens;
            // macro cameras sit much closer to the main lens.
            if (focal > 0f && focal < selectedFocal * 0.7f) {
                return true;
            }
        }
        return false;
    }

    private static CameraManager manager(Context context) {
        if (context == null) {
            return null;
        }
        return (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    }

    private static String[] cameraIds(CameraManager manager) {
        try {
            return manager.getCameraIdList();
        } catch (CameraAccessException | RuntimeException e) {
            FLog.w(TAG, "getCameraIdList failed: " + e.getClass().getSimpleName());
            return new String[0];
        }
    }

    private static String resolveCameraId(Context context, CameraManager manager, CameraType type) {
        if (manager == null) {
            return null;
        }
        String[] ids = cameraIds(manager);
        if (type == CameraType.FRONT) {
            for (String id : ids) {
                Integer facing = facing(manager, id);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    return id;
                }
            }
            return null;
        }

        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(context);
        String selectedBackId = prefs.getSelectedBackCameraId();
        // The stored id can be stale, a vendor default ("0") that does not exist on
        // this device, or even a front-facing id; only trust it when the camera
        // service lists it *and* it faces back.
        if (selectedBackId != null && Arrays.asList(ids).contains(selectedBackId)) {
            Integer selectedFacing = facing(manager, selectedBackId);
            if (selectedFacing != null
                    && selectedFacing == CameraCharacteristics.LENS_FACING_BACK) {
                return selectedBackId;
            }
        }
        for (String id : ids) {
            Integer facing = facing(manager, id);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        return null;
    }

    private static Integer facing(CameraManager manager, String cameraId) {
        try {
            return manager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.LENS_FACING);
        } catch (Throwable t) {
            return null;
        }
    }

    private static float focalLength(CameraManager manager, String cameraId) {
        if (cameraId == null) {
            return -1f;
        }
        try {
            float[] focalLengths = manager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (focalLengths != null && focalLengths.length > 0) {
                return focalLengths[0];
            }
        } catch (Throwable ignored) {
        }
        return -1f;
    }

    private static boolean contains(int[] values, int target) {
        for (int value : values) {
            if (value == target) {
                return true;
            }
        }
        return false;
    }
}
