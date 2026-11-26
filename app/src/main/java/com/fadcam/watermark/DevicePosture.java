package com.fadcam.watermark;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import androidx.annotation.Nullable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One-shot physical posture reading.
 *
 * <p>Auto needs to know how the phone is held at the instant capture starts, and nothing more.
 * A long-lived {@code OrientationEventListener} would keep the accelerometer open the whole time
 * the preview is on screen; Android's own guidance is to unregister sensor listeners as soon as
 * they are no longer needed, and that the system never does it for you.</p>
 *
 * <p>So this registers the accelerometer, waits briefly for the first event, and unregisters
 * again — a few milliseconds per recording session, no background drain.</p>
 */
public final class DevicePosture {

    /** Long enough for a first sample on any real device, short enough to be imperceptible. */
    private static final long READ_TIMEOUT_MS = 400L;

    private DevicePosture() {
    }

    /**
     * Official mapping from the CameraX orientation guide: a 90-degree band around each
     * rotation, with the left side up mapping to {@link Surface#ROTATION_270}.
     */
    static int toSurfaceRotation(int orientation) {
        if (orientation >= 45 && orientation < 135) {
            return Surface.ROTATION_270;
        } else if (orientation >= 135 && orientation < 225) {
            return Surface.ROTATION_180;
        } else if (orientation >= 225 && orientation < 315) {
            return Surface.ROTATION_90;
        }
        return Surface.ROTATION_0;
    }

    /**
     * Reads the posture once, blocking for at most {@link #READ_TIMEOUT_MS}.
     *
     * @return {@code true} when the phone is held sideways, {@code false} when upright, or
     *         {@code null} when no reading arrived (no accelerometer, or the phone is flat).
     *         Callers must treat {@code null} as "no opinion" rather than guessing.
     */
    @Nullable
    public Boolean readLandscapeNow(Context context) {
        int[] rotation = new int[]{-1};
        CountDownLatch latch = new CountDownLatch(1);

        HandlerThread thread = new HandlerThread("device-posture");
        thread.start();
        SensorManager sensorManager = null;
        SensorEventListener listener = null;
        try {
            sensorManager = (SensorManager) context.getApplicationContext()
                    .getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager == null) {
                return null;
            }
            Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accelerometer == null) {
                return null;
            }
            listener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    if (event.values == null || event.values.length < 3) {
                        return;
                    }
                    // Same maths the platform's OrientationEventListener performs, applied to a
                    // single sample: gravity's direction over the X/Y axes gives the band.
                    float x = event.values[0];
                    float y = event.values[1];
                    double angle = Math.toDegrees(Math.atan2(-x, y));
                    if (angle < 0) {
                        angle += 360;
                    }
                    rotation[0] = toSurfaceRotation((int) Math.round(angle));
                    latch.countDown();
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {
                }
            };
            sensorManager.registerListener(listener, accelerometer,
                    SensorManager.SENSOR_DELAY_NORMAL, 0, new Handler(thread.getLooper()));
            latch.await(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (sensorManager != null && listener != null) {
                // Android never unregisters for you; leaving it registered would drain battery.
                try {
                    sensorManager.unregisterListener(listener);
                } catch (Throwable ignored) {
                }
            }
            thread.quitSafely();
        }

        int rot = rotation[0];
        if (rot < 0) {
            return null;
        }
        return rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270;
    }

    /** Stateless helper; kept for call-site symmetry. */
    public static DevicePosture get(Context context) {
        return new DevicePosture();
    }
}
