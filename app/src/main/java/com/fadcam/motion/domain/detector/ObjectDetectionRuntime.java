package com.fadcam.motion.domain.detector;

import android.content.Context;

import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.FeatureRegistry;
import com.fadcam.SharedPreferencesManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Process-wide owner of the object-detection model.
 *
 * <p>The model is not tied to a recording. It starts when the feature that needs it
 * is enabled (person-confirmed motion trigger, forensic evidence collection, or the
 * forensic live overlay) and is released when none of those need it. Loading at
 * feature-enable time — not at recording start — means the camera session never
 * waits for a 56 MB transformer to compile.
 *
 * <p>Only one model instance exists per process. Callers never construct the
 * detector themselves; they read {@link #get()} and treat null as "not ready yet".
 * Frames are dropped until the model is ready, which is correct: a recording must
 * never wait for inference.
 *
 * <p>All state transitions happen on one loader thread, so a disable that races a
 * load closes the freshly loaded model instead of leaking it.
 */
public final class ObjectDetectionRuntime {

    private static final String TAG = "ObjectDetectionRuntime";

    public enum State {
        /** No feature needs the model. */
        IDLE,
        /** The model is being created on the loader thread. */
        LOADING,
        /** The model is ready and {@link #get()} returns it. */
        READY,
        /** This device/build cannot run the model; the reason is user-facing. */
        UNAVAILABLE
    }

    private static final Object LOCK = new Object();
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "object-model-loader");
        t.setDaemon(true);
        return t;
    });

    private static volatile State state = State.IDLE;
    private static volatile AiObjectDetector detector;
    @Nullable
    private static volatile String unavailableReason;
    private static volatile boolean needed;
    /** Inference cost measured once at load; used to refuse the feature on slow GPUs. */
    private static volatile long lastInferenceMs = -1L;
    private static volatile String tooSlowReason;
    /** Above this, live analysis cannot keep up with a camera feed. */
    private static final long SLOW_DEVICE_INFERENCE_MS = 400L;
    /** Probe runs at load; the median of these is the steady-state figure. */
    private static final int PROBE_RUNS = 5;

    private ObjectDetectionRuntime() {
    }

    /**
     * True when at least one enabled feature reads detections. Motion must be on:
     * both the person-confirmed trigger and forensic evidence are fed by the motion
     * analysis loop, so without motion the model would have no consumer.
     */
    public static boolean isNeeded(Context context) {
        if (context == null) {
            return false;
        }
        SharedPreferencesManager prefs =
                SharedPreferencesManager.getInstance(context.getApplicationContext());
        if (!prefs.isMotionModeEnabled()) {
            return false;
        }
        boolean personGated = "person_confirmed".equals(prefs.getMotionTriggerMode());
        boolean forensics = prefs.isDigitalForensicsEnabled()
                && (prefs.isDfEvidenceCollectionEnabled() || prefs.isDfOverlayEnabled());
        return personGated || forensics;
    }

    /** Loads the model when a feature needs it, releases it when none do. */
    public static void sync(Context context) {
        if (isNeeded(context)) {
            ensureStarted(context);
        } else {
            shutdown();
        }
    }

    /** Starts the model if needed. Safe to call repeatedly; never blocks. */
    public static void ensureStarted(Context context) {
        if (context == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        synchronized (LOCK) {
            needed = true;
            if (state == State.LOADING || state == State.READY || state == State.UNAVAILABLE) {
                return;
            }
            state = State.LOADING;
            unavailableReason = null;
            FLog.i(TAG, "Loading object model");
            LOADER.execute(() -> load(app));
        }
    }

    private static void load(Context app) {
        AiObjectDetector created = null;
        String reason = null;
        try {
            created = FeatureRegistry.features().createAiDetector(app);
            if (created == null) {
                reason = "Object detection is not available in this build.";
            } else if (!created.isAvailable()) {
                reason = created.unavailableReason();
                if (reason == null || reason.isEmpty()) {
                    reason = "Object detection is unavailable on this device.";
                }
            }
        } catch (Throwable t) {
            FLog.e(TAG, "Object model failed to load", t);
            reason = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        }

        synchronized (LOCK) {
            if (!needed) {
                // The feature was disabled while the model was compiling. Release it
                // instead of keeping a model nobody asked for.
                if (created != null) {
                    created.close();
                }
                detector = null;
                state = State.IDLE;
                unavailableReason = null;
                FLog.i(TAG, "Object model load finished after disable; released");
                return;
            }
            if (created != null && created.isAvailable()) {
                detector = created;
                state = State.READY;
                unavailableReason = null;
                FLog.i(TAG, "Object model ready");

                // Measure the model on this device while it is still off the hot
                // path, so a GPU that cannot sustain the feature is known before a
                // recording starts rather than discovered as lag in the field.
                // The first run pays for shader compilation and pipeline setup, so a
                // single sample measures warm-up, not steady state. Run a few and keep
                // the median so a genuinely slow GPU is distinguished from a slow
                // first call.
                long[] samples = new long[PROBE_RUNS];
                for (int i = 0; i < PROBE_RUNS; i++) {
                    long probeStart = android.os.SystemClock.elapsedRealtime();
                    try {
                        created.detect(com.fadcam.motion.domain.detector.AiObjectDetector.FramePacket
                                .syntheticWarmup(384, 384));
                    } catch (Throwable t) {
                        FLog.w(TAG, "Object model warmup probe failed", t);
                    }
                    samples[i] = android.os.SystemClock.elapsedRealtime() - probeStart;
                }
                java.util.Arrays.sort(samples);
                long probeMs = samples[PROBE_RUNS / 2];
                lastInferenceMs = probeMs;
                FLog.i(TAG, "Object model steady-state inference: " + probeMs + " ms"
                        + " (samples=" + java.util.Arrays.toString(samples) + ")");
                if (probeMs > SLOW_DEVICE_INFERENCE_MS) {
                    tooSlowReason = "This phone's GPU needs about " + probeMs
                            + " ms per detection, which is too slow for live analysis. "
                            + "Person detection is unavailable, but motion detection "
                            + "still works.";
                    FLog.w(TAG, "Object model too slow on this device: " + probeMs + " ms");

                }
            } else {
                if (created != null) {
                    created.close();
                }
                detector = null;
                state = State.UNAVAILABLE;
                unavailableReason = reason;
                FLog.w(TAG, "Object model unavailable: " + reason);
            }
        }
    }

    /** Releases the model. Safe to call when it is not loaded. */
    public static void shutdown() {
        AiObjectDetector toClose;
        synchronized (LOCK) {
            needed = false;
            toClose = detector;
            detector = null;
            if (state != State.LOADING) {
                state = State.IDLE;
            }
            unavailableReason = null;
        }
        if (toClose != null) {
            LOADER.execute(() -> {
                try {
                    toClose.close();
                } catch (Throwable t) {
                    FLog.w(TAG, "Failed to close object model", t);
                }
                FLog.i(TAG, "Object model released");
            });
        }
    }

    /** The ready detector, or null while loading / unavailable / disabled. */
    @Nullable
    public static AiObjectDetector get() {
        return state == State.READY ? detector : null;
    }

    public static State getState() {
        return state;
    }

    @Nullable
    public static String getUnavailableReason() {
        return unavailableReason;
    }

    public static boolean isReady() {
        return state == State.READY;
    }

    /** Measured inference cost on this device, or -1 before the model has loaded. */
    public static long getLastInferenceMs() {
        return lastInferenceMs;
    }

    /**
     * Non-null when the model runs but is too slow for live analysis on this device.
     * The caller must surface this instead of silently degrading.
     */
    @Nullable
    public static String getTooSlowReason() {
        return tooSlowReason;
    }
}
