package com.fadcam.sensors;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.fadcam.FLog;

public class SensorDataProvider implements SensorEventListener {
    private static final String TAG = "SensorDataProvider";
    private static SensorDataProvider instance;

    private static final int BEARING_MIN_DISTANCE_M = 2;
    private static final float BEARING_SMOOTHING_ALPHA = 0.4f;

    private final SensorManager sensorManager;
    private final Sensor accelerometer;
    private final Sensor magnetometer;
    private final Sensor rotationVectorSensor;

    private float[] gravity;
    private float[] geomagnetic;
    private float[] rotationMatrix = new float[9];
    private float[] orientation = new float[3];
    private float[] rotationVectorValues;

    private float sensorAzimuth = -1f;
    private boolean compassDataReceived = false;
    private boolean sensorsRegistered = false;

    private Location currentLocation;
    private Location previousLocation;
    private long lastLocationTime = 0;
    private long previousLocationTime = 0;
    private float currentSpeedMs = 0f;
    private long lastLocationUpdateTime = 0;
    private float smoothedBearing = -1f;
    private boolean hasBearingFromGps = false;

    private HandlerThread sensorThread;
    private Handler sensorHandler;

    private SensorDataProvider(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

        sensorThread = new HandlerThread("SensorThread");
        sensorThread.start();
        sensorHandler = new Handler(sensorThread.getLooper());

        FLog.d(TAG, "Sensors available: accelerometer=" + (accelerometer != null)
                + ", magnetometer=" + (magnetometer != null)
                + ", rotationVector=" + (rotationVectorSensor != null));
    }

    public static synchronized SensorDataProvider getInstance(Context context) {
        if (instance == null) {
            instance = new SensorDataProvider(context.getApplicationContext());
        }
        return instance;
    }

    public static synchronized void resetInstance() {
        if (instance != null) {
            instance.stop();
            if (instance.sensorThread != null) {
                instance.sensorThread.quitSafely();
                instance.sensorThread = null;
            }
            instance = null;
        }
    }

    public void start(Location initialLocation) {
        if (sensorsRegistered) {
            FLog.d(TAG, "Sensors already registered, skipping");
            return;
        }

        if (initialLocation != null) {
            currentLocation = initialLocation;
            lastLocationTime = System.currentTimeMillis();
        }

        if (rotationVectorSensor != null) {
            boolean ok = sensorManager.registerListener(this, rotationVectorSensor,
                    SensorManager.SENSOR_DELAY_UI, sensorHandler);
            FLog.d(TAG, "Rotation vector sensor registered: " + ok);
        } else {
            if (accelerometer != null) {
                boolean ok = sensorManager.registerListener(this, accelerometer,
                        SensorManager.SENSOR_DELAY_UI, sensorHandler);
                FLog.d(TAG, "Accelerometer registered: " + ok);
            }
            if (magnetometer != null) {
                boolean ok = sensorManager.registerListener(this, magnetometer,
                        SensorManager.SENSOR_DELAY_UI, sensorHandler);
                FLog.d(TAG, "Magnetometer registered: " + ok);
            }
        }

        sensorsRegistered = true;
        FLog.d(TAG, "Sensors started - compass source: "
                + (rotationVectorSensor != null ? "rotationVector"
                : (magnetometer != null ? "accel+mag" : "GPS bearing only")));
    }

    public void stop() {
        if (sensorsRegistered) {
            sensorManager.unregisterListener(this);
            sensorsRegistered = false;
            compassDataReceived = false;
            currentSpeedMs = 0f;
            smoothedBearing = -1f;
            hasBearingFromGps = false;
            sensorAzimuth = -1f;
            FLog.d(TAG, "Sensors stopped and state reset");
        }
    }

    public void updateLocation(Location location) {
        if (location == null) return;

        previousLocation = currentLocation;
        previousLocationTime = lastLocationTime;
        currentLocation = location;
        lastLocationTime = System.currentTimeMillis();

        updateSpeedFromGps();
        updateBearingFromGps();

        FLog.d(TAG, "Location: lat=" + String.format("%.4f", location.getLatitude())
                + ", lon=" + String.format("%.4f", location.getLongitude())
                + ", speed=" + String.format("%.1f", currentSpeedMs * 3.6f) + "km/h"
                + " (hasSpeed=" + location.hasSpeed()
                + ", gps=" + String.format("%.1f", location.getSpeed() * 3.6f) + "km/h)"
                + ", bearing=" + String.format("%.0f", smoothedBearing)
                + " (hasBearing=" + location.hasBearing()
                + ", gps=" + String.format("%.0f", location.getBearing()) + ")"
                + ", alt=" + String.format("%.1f", location.getAltitude()) + "m"
                + ", acc=" + (location.hasAccuracy()
                        ? String.format("%.0f", location.getAccuracy()) + "m" : "none"));
    }

    private void updateSpeedFromGps() {
        if (currentLocation == null) return;

        if (currentLocation.hasSpeed()) {
            currentSpeedMs = currentLocation.getSpeed();
        } else if (previousLocation != null && previousLocationTime > 0) {
            float distance = previousLocation.distanceTo(currentLocation);
            long timeDeltaMs = lastLocationTime - previousLocationTime;
            if (timeDeltaMs > 100 && timeDeltaMs < 10000) {
                currentSpeedMs = distance / (timeDeltaMs / 1000f);
            }
        } else {
            currentSpeedMs = 0f;
        }

        lastLocationUpdateTime = System.currentTimeMillis();
    }

    private void updateBearingFromGps() {
        if (currentLocation == null) return;

        float bearing = -1f;

        if (currentLocation.hasBearing()) {
            bearing = currentLocation.getBearing();
        }

        if (bearing < 0 && previousLocation != null && previousLocationTime > 0) {
            float distance = previousLocation.distanceTo(currentLocation);
            if (distance >= BEARING_MIN_DISTANCE_M) {
                bearing = previousLocation.bearingTo(currentLocation);
                if (bearing < 0) bearing += 360f;
            }
        }

        if (bearing < 0) return;

        if (smoothedBearing < 0) {
            smoothedBearing = bearing;
        } else {
            float diff = bearing - smoothedBearing;
            if (diff > 180f) diff -= 360f;
            if (diff < -180f) diff += 360f;
            smoothedBearing = (smoothedBearing + diff * BEARING_SMOOTHING_ALPHA + 360f) % 360f;
        }
        hasBearingFromGps = true;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();

        if (type == Sensor.TYPE_ROTATION_VECTOR) {
            rotationVectorValues = event.values.clone();
            if (rotationVectorValues != null) {
                float[] rot = new float[9];
                SensorManager.getRotationMatrixFromVector(rot, rotationVectorValues);
                SensorManager.getOrientation(rot, orientation);
                sensorAzimuth = (float) Math.toDegrees(orientation[0]);
                sensorAzimuth = (sensorAzimuth + 360f) % 360f;
                if (!compassDataReceived) {
                    compassDataReceived = true;
                    FLog.d(TAG, "First compass reading from rotation vector: " + sensorAzimuth + "°");
                }
            }
            return;
        }

        if (type == Sensor.TYPE_ACCELEROMETER) {
            gravity = lowPassFilter(event.values.clone(), gravity);
        } else if (type == Sensor.TYPE_MAGNETIC_FIELD) {
            geomagnetic = lowPassFilter(event.values.clone(), geomagnetic);
        }

        if (gravity != null && geomagnetic != null) {
            boolean success = SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic);
            if (success) {
                SensorManager.getOrientation(rotationMatrix, orientation);
                sensorAzimuth = (float) Math.toDegrees(orientation[0]);
                sensorAzimuth = (sensorAzimuth + 360f) % 360f;
                if (!compassDataReceived) {
                    compassDataReceived = true;
                    FLog.d(TAG, "First compass reading from accel+mag: " + sensorAzimuth + "°");
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        FLog.d(TAG, "Sensor accuracy: " + sensor.getName() + " -> " + accuracy);
    }

    private float[] lowPassFilter(float[] input, float[] output) {
        if (output == null) return input;
        final float alpha = 0.15f;
        for (int i = 0; i < input.length; i++) {
            output[i] = output[i] + alpha * (input[i] - output[i]);
        }
        return output;
    }

    public float getSpeedKmh() {
        return currentSpeedMs * 3.6f;
    }

    public double getAltitude() {
        if (currentLocation != null) {
            return currentLocation.getAltitude();
        }
        return 0f;
    }

    public float getAccuracy() {
        if (currentLocation != null && currentLocation.hasAccuracy()) {
            return currentLocation.getAccuracy();
        }
        return 0f;
    }

    public String getCompassDirection() {
        float azimuth = getCompassAzimuth();
        int degrees = (int) azimuth;
        String direction;
        if (degrees >= 337 || degrees < 23) direction = "N";
        else if (degrees >= 23 && degrees < 67) direction = "NE";
        else if (degrees >= 67 && degrees < 113) direction = "E";
        else if (degrees >= 113 && degrees < 157) direction = "SE";
        else if (degrees >= 157 && degrees < 203) direction = "S";
        else if (degrees >= 203 && degrees < 247) direction = "SW";
        else if (degrees >= 247 && degrees < 293) direction = "W";
        else direction = "NW";

        String source = compassDataReceived ? "sensor" : (hasBearingFromGps ? "gps" : "none");
        // Compass log removed for performance
        return degrees + "° " + direction;
    }

    private float getCompassAzimuth() {
        if (compassDataReceived && sensorAzimuth >= 0) {
            return sensorAzimuth;
        }
        if (hasBearingFromGps && smoothedBearing >= 0) {
            return smoothedBearing;
        }
        return 0f;
    }

    public boolean hasCompassSensor() {
        return accelerometer != null && magnetometer != null;
    }

    public boolean hasCompassData() {
        return compassDataReceived || hasBearingFromGps;
    }

    public String getCompassSource() {
        if (compassDataReceived) return "sensor";
        if (hasBearingFromGps) return "gps";
        return "none";
    }
}
