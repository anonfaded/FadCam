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
 * Checks whether the current device can attempt dual-camera (PiP) recording.
 *
 * <p>The check is intentionally <b>permissive</b>: it only requires that both a
 * front-facing and a back-facing camera exist. The strict {@code
 * getConcurrentCameraIds()} API (API 30+) is used as an <em>optional</em> hint
 * — if available and reports a valid concurrent set, we cache those IDs.
 * Otherwise we fall back to the first front/back camera IDs.
 *
 * <p>If the hardware truly cannot handle concurrent streams the
 * {@link com.fadcam.dualcam.service.DualCameraRecordingService} will fail
 * gracefully and broadcast an error to the UI.
 *
 * <p>This class is read-only and does <b>not</b> modify any existing camera code.
 * Results are cached after the first query for performance.
 */
public class DualCameraCapability {

    private static final String TAG = "DualCameraCapability";

    private final Context appContext;

    // Cached results (computed once, thread-safe via volatile)
    private volatile Boolean cachedSupport = null;
    private volatile String cachedFrontId = null;
    private volatile String cachedBackId = null;
    private volatile String cachedUnsupportedReason = null;
    /** {@code true} when the strict concurrent-camera API confirms support. */
    private volatile boolean concurrentApiConfirmed = false;

    /**
     * @param context Any context; application context is extracted internally.
     */
    public DualCameraCapability(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if this device has both a front and a back camera
     * and can <em>attempt</em> dual-camera recording.
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
     * Returns {@code true} if the strict {@code getConcurrentCameraIds()}
     * API confirmed concurrent support. If {@code false} the feature may
     * still work at runtime; it just wasn't confirmed in advance.
     */
    public boolean isConcurrentApiConfirmed() {
        if (cachedSupport == null) evaluate();
        return concurrentApiConfirmed;
    }

    /**
     * Forces re-evaluation (e.g. after a camera subsystem restart).
     */
    public void invalidateCache() {
        cachedSupport = null;
        cachedFrontId = null;
        cachedBackId = null;
        cachedUnsupportedReason = null;
        concurrentApiConfirmed = false;
    }

    // ── Internal ───────────────────────────────────────────────────────────

    private synchronized void evaluate() {
        // Double-check inside synchronized block
        if (cachedSupport != null) return;

        CameraManager cameraManager = (CameraManager) appContext.getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            fail("Camera service unavailable");
            return;
        }

        try {
            // 1. Find front + back camera IDs
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

            // 2. Try the strict concurrent API if available (API 30+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    @SuppressWarnings("unchecked")
                    Set<Set<String>> concurrentSets = cameraManager.getConcurrentCameraIds();
                    if (concurrentSets != null && !concurrentSets.isEmpty()) {
                        Log.d(TAG, "Concurrent camera sets reported: " + concurrentSets.size());
                        for (Set<String> cameraSet : concurrentSets) {
                            Log.d(TAG, "  Set: " + cameraSet);
                            if (cameraSet.contains(frontId) && cameraSet.contains(backId)) {
                                Log.i(TAG, "✅ Concurrent API confirmed dual camera support");
                                concurrentApiConfirmed = true;
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "getConcurrentCameraIds() call failed — proceeding with best-effort", e);
                }
            }

            // 3. We have front + back — allow the user to attempt dual recording
            cachedFrontId = frontId;
            cachedBackId = backId;
            cachedUnsupportedReason = null;
            cachedSupport = true;
            Log.i(TAG, "✅ Dual camera available (concurrent API confirmed: " + concurrentApiConfirmed + ")");

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
