package com.fadcam.dualcam;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Set;

/**
 * Checks whether the current device supports concurrent front + back camera operation
 * required for dual-camera (PiP) recording.
 *
 * <p>This class is read-only and does <b>not</b> modify any existing camera code.
 * Results are cached after the first query for performance.
 *
 * <h3>Requirements</h3>
 * <ul>
 *   <li>Android 11+ (API 30) — {@code CameraManager.getConcurrentCameraIds()} API</li>
 *   <li>Both front and back cameras available</li>
 *   <li>Hardware support for concurrent streams (OEM-dependent)</li>
 * </ul>
 */
public class DualCameraCapability {

    private static final String TAG = "DualCameraCapability";

    private final Context appContext;

    // Cached results (computed once, thread-safe via volatile)
    private volatile Boolean cachedSupport = null;
    private volatile String cachedFrontId = null;
    private volatile String cachedBackId = null;
    private volatile String cachedUnsupportedReason = null;

    /**
     * @param context Any context; application context is extracted internally.
     */
    public DualCameraCapability(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if this device supports concurrent front + back camera recording.
     * The result is cached after the first call.
     */
    public boolean isSupported() {
        if (cachedSupport != null) return cachedSupport;
        evaluate();
        return cachedSupport;
    }

    /**
     * Returns a user-friendly reason why dual camera is not supported,
     * or {@code null} if it <em>is</em> supported.
     */
    @Nullable
    public String getUnsupportedReason() {
        if (cachedSupport == null) evaluate();
        return cachedUnsupportedReason;
    }

    /**
     * Returns the camera ID for the front-facing camera that participates in
     * the concurrent set, or {@code null} if dual camera is not supported.
     */
    @Nullable
    public String getConcurrentFrontCameraId() {
        if (cachedSupport == null) evaluate();
        return cachedFrontId;
    }

    /**
     * Returns the camera ID for the back-facing camera that participates in
     * the concurrent set, or {@code null} if dual camera is not supported.
     */
    @Nullable
    public String getConcurrentBackCameraId() {
        if (cachedSupport == null) evaluate();
        return cachedBackId;
    }

    /**
     * Forces re-evaluation (e.g. after a camera subsystem restart).
     */
    public void invalidateCache() {
        cachedSupport = null;
        cachedFrontId = null;
        cachedBackId = null;
        cachedUnsupportedReason = null;
    }

    // ── Internal ───────────────────────────────────────────────────────────

    private synchronized void evaluate() {
        // Double-check inside synchronized block
        if (cachedSupport != null) return;

        // 1. API level check
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            fail("Dual camera requires Android 11 or newer (current: API " + Build.VERSION.SDK_INT + ")");
            return;
        }

        // 2. Get CameraManager
        CameraManager cameraManager = (CameraManager) appContext.getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            fail("Camera service unavailable");
            return;
        }

        try {
            // 3. Check concurrent camera sets (API 30+)
            @SuppressWarnings("unchecked")
            Set<Set<String>> concurrentSets = cameraManager.getConcurrentCameraIds();
            if (concurrentSets == null || concurrentSets.isEmpty()) {
                fail("Device does not report any concurrent camera combinations");
                Log.d(TAG, "getConcurrentCameraIds() returned empty set");
                return;
            }

            Log.d(TAG, "Concurrent camera sets reported: " + concurrentSets.size());
            for (Set<String> s : concurrentSets) {
                Log.d(TAG, "  Set: " + s);
            }

            // 4. Find front + back camera IDs from the regular camera list
            String frontId = findCameraIdByFacing(cameraManager, CameraCharacteristics.LENS_FACING_FRONT);
            String backId = findCameraIdByFacing(cameraManager, CameraCharacteristics.LENS_FACING_BACK);

            if (frontId == null) {
                fail("No front-facing camera found on device");
                return;
            }
            if (backId == null) {
                fail("No back-facing camera found on device");
                return;
            }

            Log.d(TAG, "Front camera ID: " + frontId + ", Back camera ID: " + backId);

            // 5. Check if any concurrent set contains BOTH front and back
            for (Set<String> cameraSet : concurrentSets) {
                if (cameraSet.contains(frontId) && cameraSet.contains(backId)) {
                    Log.i(TAG, "✅ Dual camera supported! Concurrent set: " + cameraSet);
                    cachedFrontId = frontId;
                    cachedBackId = backId;
                    cachedUnsupportedReason = null;
                    cachedSupport = true;
                    return;
                }
            }

            // 6. Front and back exist but aren't in a concurrent set
            fail("Front and back cameras exist but device does not support concurrent operation");

        } catch (CameraAccessException e) {
            Log.e(TAG, "CameraAccessException during dual camera evaluation", e);
            fail("Camera access error: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error during dual camera evaluation", e);
            fail("Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Finds the first camera ID matching the given {@code lensFacing}.
     *
     * @param lensFacing {@link CameraCharacteristics#LENS_FACING_FRONT} or
     *                   {@link CameraCharacteristics#LENS_FACING_BACK}
     * @return camera ID string, or {@code null} if not found
     */
    @Nullable
    private String findCameraIdByFacing(@NonNull CameraManager manager, int lensFacing)
            throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            try {
                CameraCharacteristics chars = manager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == lensFacing) {
                    return id;
                }
            } catch (Exception e) {
                Log.w(TAG, "Error checking characteristics for camera " + id, e);
            }
        }
        return null;
    }

    /** Sets cached result to unsupported with the given reason. */
    private void fail(@NonNull String reason) {
        cachedSupport = false;
        cachedFrontId = null;
        cachedBackId = null;
        cachedUnsupportedReason = reason;
        Log.d(TAG, "❌ Dual camera not supported: " + reason);
    }
}
