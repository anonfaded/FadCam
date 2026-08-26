package com.fadcam.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.Window;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.fadcam.Constants;
import com.fadcam.RecordingStartActivity;
import com.fadcam.RecordingStopActivity;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Real implementations for every production tool formerly marked Coming Soon. */
public final class ProductionFeatureHubDialog extends Dialog implements SensorEventListener, LocationListener {
    private static final int REQ_LOCATION = 8701;
    private static final int REQ_AUDIO = 8702;
    private static final int REQ_ACTIVITY = 8703;
    private static final String PROFILE_PREFS = "production_profiles";
    private static final String TOOLS_PREFS = "production_tools";

    private final String feature;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SensorManager sensorManager;
    private LocationManager locationManager;
    private TextView value;
    private TextView status;
    private float[] accel;
    private float[] magnetic;
    private float[] gyro;
    private float stepBase = -1f;
    private float steps = -1f;
    private AudioRecord audio;
    private Thread audioThread;
    private volatile boolean audioRunning;
    private boolean audioTrigger;
    private boolean thermalTripped;
    private boolean parkingPending;

    private ProductionFeatureHubDialog(@NonNull Activity host, @NonNull String feature) {
        super(host);
        this.feature = feature;
    }

    public static void show(@NonNull Activity host, @NonNull String feature) {
        new ProductionFeatureHubDialog(host, feature).show();
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window w = getWindow();
        if (w != null) w.setBackgroundDrawableResource(android.R.color.transparent);
        ScrollView scroll = new ScrollView(getContext());
        scroll.setBackgroundColor(Color.BLACK);
        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        scroll.addView(root);
        root.addView(text(title(), 24, Color.WHITE));
        status = text("Ready", 14, 0xFFBDBDBD);
        root.addView(status);
        value = text("", 30, 0xFFFF5252);
        value.setGravity(Gravity.CENTER);
        root.addView(value, lp(-1, -2));
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content);
        Button close = button("Close");
        root.addView(close);
        close.setOnClickListener(v -> dismiss());
        setContentView(scroll);
        open(content);
    }

    private String title() {
        if (feature.isEmpty()) return "Production tool";
        String s = feature.replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void open(LinearLayout c) {
        switch (feature) {
            case "thermal": thermal(c); break;
            case "audio": audioVision(c); break;
            case "schedule": schedule(c); break;
            case "profiles": profiles(c); break;
            case "sound": soundMeter(c); break;
            case "sensors": sensors(c); break;
            case "speed": speed(c); break;
            case "clinometer": clinometer(c); break;
            case "compass": compass(c); break;
            case "pedometer": pedometer(c); break;
            case "metal": metal(c); break;
            case "parking": parking(c); break;
            case "qr": qr(c); break;
            default: status.setText("Unknown production feature");
        }
    }

    private void thermal(LinearLayout c) {
        status.setText("Monitors battery temperature and safely stops an active recording at the threshold.");
        EditText limit = edit("Emergency threshold °C", "45");
        c.addView(limit);
        Button enable = button("Enable Thermal Guardian");
        c.addView(enable);
        enable.setOnClickListener(v -> {
            thermalTripped = false;
            handler.removeCallbacksAndMessages(null);
            status.setText("Thermal Guardian active");
            Runnable monitor = new Runnable() {
                @Override public void run() {
                    Intent battery = getContext().registerReceiver(null,
                            new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    int raw = battery == null ? -1 : battery.getIntExtra(
                            android.os.BatteryManager.EXTRA_TEMPERATURE, -1);
                    if (raw > 0) {
                        float temp = raw / 10f;
                        value.setText(String.format(Locale.US, "%.1f °C", temp));
                        if (!thermalTripped && temp >= parseFloat(limit.getText().toString(), 45f)
                                && recording()) {
                            thermalTripped = true;
                            try {
                                getContext().startActivity(new Intent(getContext(), RecordingStopActivity.class));
                                status.setText("Recording stopped: thermal threshold reached");
                            } catch (Exception e) {
                                status.setText("Thermal limit reached; recording could not be stopped");
                            }
                        }
                    } else {
                        value.setText("Temperature unavailable");
                    }
                    if (!thermalTripped) handler.postDelayed(this, 2000L);
                }
            };
            handler.post(monitor);
        });
    }

    private void audioVision(LinearLayout c) {
        status.setText("Starts recording when microphone level reaches the selected relative threshold.");
        EditText threshold = edit("Trigger level (relative dB)", "65");
        c.addView(threshold);
        Button arm = button("Arm Audio Vision");
        c.addView(arm);
        arm.setOnClickListener(v -> {
            audioTrigger = true;
            startAudio(parseFloat(threshold.getText().toString(), 65f));
        });
    }

    private void soundMeter(LinearLayout c) {
        status.setText("Live relative microphone level; this is not calibrated dB SPL.");
        Button start = button("Start Sound Meter");
        c.addView(start);
        start.setOnClickListener(v -> {
            audioTrigger = false;
            startAudio(0f);
        });
    }

    private void startAudio(float threshold) {
        if (ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            status.setText("Microphone permission required; allow it and tap again");
            ActivityCompat.requestPermissions((Activity) getContext(),
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        stopAudio();
        int min = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            status.setText("Microphone unavailable");
            return;
        }
        try {
            audio = new AudioRecord(MediaRecorder.AudioSource.MIC, 44100,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min * 2, 4096));
            audio.startRecording();
        } catch (Exception e) {
            stopAudio();
            status.setText("Microphone unavailable");
            return;
        }
        audioRunning = true;
        final float trigger = threshold;
        audioThread = new Thread(() -> {
            short[] buffer = new short[min];
            boolean fired = false;
            while (audioRunning && audio != null) {
                int n = audio.read(buffer, 0, buffer.length);
                if (n <= 0) continue;
                double sum = 0d;
                for (int i = 0; i < n; i++) sum += buffer[i] * (double) buffer[i];
                double rms = Math.sqrt(sum / n);
                double db = rms <= 1d ? 0d : 20d * Math.log10(rms / 32768d) + 90d;
                handler.post(() -> value.setText(String.format(Locale.US, "%.1f dB", db)));
                if (audioTrigger && !fired && db >= trigger) {
                    fired = true;
                    handler.post(this::startRecording);
                }
            }
        }, "FadCam-FeatureAudio");
        audioThread.start();
        status.setText(audioTrigger ? "Audio Vision armed" : "Sound Meter running");
    }

    private void startRecording() {
        audioTrigger = false;
        if (recording()) {
            status.setText("Recording is already running");
            return;
        }
        try {
            getContext().startActivity(new Intent(getContext(), RecordingStartActivity.class));
            status.setText("Recording started by Audio Vision");
        } catch (Exception e) {
            status.setText("Could not start recording");
        }
    }

    private void schedule(LinearLayout c) {
        status.setText("One-time future start and stop schedule using Android alarms.");
        DatePicker date = new DatePicker(getContext());
        c.addView(date, lp(-1, dp(175)));
        TimePicker from = new TimePicker(getContext());
        from.setIs24HourView(true);
        c.addView(from, lp(-1, dp(70)));
        TimePicker to = new TimePicker(getContext());
        to.setIs24HourView(true);
        c.addView(to, lp(-1, dp(70)));
        Button schedule = button("Schedule Recording");
        c.addView(schedule);
        schedule.setOnClickListener(v -> {
            Calendar start = Calendar.getInstance();
            start.set(date.getYear(), date.getMonth(), date.getDayOfMonth(),
                    from.getHour(), from.getMinute(), 0);
            start.set(Calendar.MILLISECOND, 0);
            Calendar stop = (Calendar) start.clone();
            stop.set(Calendar.HOUR_OF_DAY, to.getHour());
            stop.set(Calendar.MINUTE, to.getMinute());
            stop.set(Calendar.SECOND, 0);
            stop.set(Calendar.MILLISECOND, 0);
            if (stop.getTimeInMillis() <= start.getTimeInMillis()) stop.add(Calendar.DAY_OF_YEAR, 1);
            if (start.getTimeInMillis() <= System.currentTimeMillis()) {
                status.setText("Choose a future start time");
                return;
            }
            if (!canScheduleExactAlarms()) {
                status.setText("Allow Alarms & reminders, then tap Schedule again");
                requestExactAlarmPermission();
                return;
            }
            boolean startOk = scheduleAlarm(start.getTimeInMillis(), true);
            boolean stopOk = scheduleAlarm(stop.getTimeInMillis(), false);
            status.setText(startOk && stopOk ? "Start and stop alarms scheduled" : "Could not schedule recording");
        });
    }

    private boolean canScheduleExactAlarms() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager manager = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
        return manager != null && manager.canScheduleExactAlarms();
    }

    private void requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
            intent.setData(Uri.parse("package:" + getContext().getPackageName()));
            getContext().startActivity(intent);
        } catch (Exception e) {
            status.setText("Open Settings > Special app access > Alarms & reminders");
        }
    }

    private boolean scheduleAlarm(long when, boolean start) {
        AlarmManager manager = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return false;
        Intent intent = new Intent(getContext(), start ? RecordingStartActivity.class : RecordingStopActivity.class);
        int requestCode = (int) ((when / 60000L) % 100000L) + (start ? 100000 : 200000);
        PendingIntent pending = PendingIntent.getActivity(getContext(), requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pending);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                manager.setExact(AlarmManager.RTC_WAKEUP, when, pending);
            } else {
                manager.set(AlarmManager.RTC_WAKEUP, when, pending);
            }
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    private void profiles(LinearLayout c) {
        status.setText("Save and restore typed recording preferences locally.");
        EditText name = edit("Profile name", "studio");
        c.addView(name);
        Button save = button("Save Current Profile");
        Button load = button("Load Profile");
        c.addView(save);
        c.addView(load);
        save.setOnClickListener(v -> {
            String n = name.getText().toString().trim();
            if (n.isEmpty()) { status.setText("Enter a profile name"); return; }
            status.setText(saveProfile(n) ? "Saved " + n : "Could not save profile");
        });
        load.setOnClickListener(v -> {
            String n = name.getText().toString().trim();
            if (n.isEmpty()) { status.setText("Enter a profile name"); return; }
            status.setText(loadProfile(n) ? "Loaded " + n : "Profile not found or invalid");
        });
    }

    private boolean saveProfile(String name) {
        try {
            SharedPreferences source = getContext().getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
            JSONObject root = new JSONObject();
            for (Map.Entry<String, ?> entry : source.getAll().entrySet()) {
                String key = entry.getKey();
                String lower = key.toLowerCase(Locale.US);
                if (lower.contains("recording_in_progress") || lower.contains("session") || lower.contains("lock_unlocked")) continue;
                Object o = entry.getValue();
                JSONObject item = new JSONObject();
                if (o instanceof Boolean) { item.put("type", "boolean"); item.put("value", o); }
                else if (o instanceof Integer) { item.put("type", "int"); item.put("value", o); }
                else if (o instanceof Long) { item.put("type", "long"); item.put("value", o); }
                else if (o instanceof Float) { item.put("type", "float"); item.put("value", o); }
                else if (o instanceof String) { item.put("type", "string"); item.put("value", o); }
                else if (o instanceof Set) {
                    JSONArray array = new JSONArray();
                    for (Object x : (Set<?>) o) array.put(String.valueOf(x));
                    item.put("type", "set"); item.put("value", array);
                } else continue;
                root.put(key, item);
            }
            getContext().getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE).edit().putString(name, root.toString()).apply();
            return true;
        } catch (Exception e) { return false; }
    }

    private boolean loadProfile(String name) {
        String json = getContext().getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE).getString(name, null);
        if (json == null) return false;
        try {
            JSONObject root = new JSONObject(json);
            SharedPreferences.Editor editor = getContext().getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit();
            java.util.Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject item = root.getJSONObject(key);
                switch (item.getString("type")) {
                    case "boolean": editor.putBoolean(key, item.getBoolean("value")); break;
                    case "int": editor.putInt(key, item.getInt("value")); break;
                    case "long": editor.putLong(key, item.getLong("value")); break;
                    case "float": editor.putFloat(key, (float) item.getDouble("value")); break;
                    case "string": editor.putString(key, item.getString("value")); break;
                    case "set":
                        JSONArray a = item.getJSONArray("value");
                        Set<String> values = new HashSet<>();
                        for (int i = 0; i < a.length(); i++) values.add(a.getString(i));
                        editor.putStringSet(key, values);
                        break;
                    default: break;
                }
            }
            editor.apply();
            return true;
        } catch (Exception e) { return false; }
    }

    private void sensors(LinearLayout c) {
        status.setText("Live accelerometer, gyroscope and magnetometer data.");
        register(Sensor.TYPE_ACCELEROMETER);
        register(Sensor.TYPE_GYROSCOPE);
        register(Sensor.TYPE_MAGNETIC_FIELD);
        value.setText("Waiting for sensor data…");
        value.setTextSize(14);
    }

    private void speed(LinearLayout c) {
        status.setText("GPS speed");
        if (!requestLocation()) return;
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this);
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 1f, this);
        } catch (SecurityException e) { status.setText("Location permission unavailable"); }
    }

    private void clinometer(LinearLayout c) { status.setText("Accelerometer pitch and roll"); register(Sensor.TYPE_ACCELEROMETER); }
    private void compass(LinearLayout c) { status.setText("Magnetic heading"); register(Sensor.TYPE_ACCELEROMETER); register(Sensor.TYPE_MAGNETIC_FIELD); }

    private void pedometer(LinearLayout c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            status.setText("Activity recognition permission required; allow it and tap again");
            ActivityCompat.requestPermissions((Activity) getContext(), new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, REQ_ACTIVITY);
            return;
        }
        status.setText("Hardware step counter");
        register(Sensor.TYPE_STEP_COUNTER);
    }

    private void metal(LinearLayout c) { status.setText("Magnetic field strength"); register(Sensor.TYPE_MAGNETIC_FIELD); }

    private void parking(LinearLayout c) {
        status.setText("Save a parking position and open it in a map app.");
        Button save = button("Save Current Location");
        Button show = button("Show Saved Marker");
        c.addView(save); c.addView(show);
        save.setOnClickListener(v -> {
            if (!requestLocation()) return;
            try {
                Location l = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (l == null && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) l = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (l != null) saveParking(l);
                else { parkingPending = true; status.setText("Waiting for GPS fix…"); locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this); }
            } catch (SecurityException e) { status.setText("Location permission unavailable"); }
        });
        show.setOnClickListener(v -> {
            SharedPreferences p = getContext().getSharedPreferences(TOOLS_PREFS, Context.MODE_PRIVATE);
            if (!p.contains("parking_lat") || !p.contains("parking_lon")) { status.setText("No parking marker saved"); return; }
            double lat = Double.longBitsToDouble(p.getLong("parking_lat", 0));
            double lon = Double.longBitsToDouble(p.getLong("parking_lon", 0));
            try { getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.US, "geo:%f,%f?q=%f,%f", lat, lon, lat, lon)))); }
            catch (Exception e) { status.setText(String.format(Locale.US, "Saved %.5f, %.5f", lat, lon)); }
        });
    }

    private void saveParking(Location l) {
        parkingPending = false;
        getContext().getSharedPreferences(TOOLS_PREFS, Context.MODE_PRIVATE).edit()
                .putLong("parking_lat", Double.doubleToLongBits(l.getLatitude()))
                .putLong("parking_lon", Double.doubleToLongBits(l.getLongitude()))
                .putFloat("parking_accuracy", l.getAccuracy()).apply();
        status.setText(String.format(Locale.US, "Parking saved (±%.0f m)", l.getAccuracy()));
    }

    private void qr(LinearLayout c) {
        status.setText("Offline QR generator");
        EditText input = edit("Text / URL / contact", ""); c.addView(input);
        Button generate = button("Generate QR"); c.addView(generate);
        ImageView image = new ImageView(getContext()); image.setAdjustViewBounds(true); c.addView(image, lp(-1, dp(320)));
        generate.setOnClickListener(v -> {
            try {
                String content = input.getText().toString().trim();
                if (content.isEmpty()) { status.setText("Enter content first"); return; }
                Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
                hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
                hints.put(EncodeHintType.MARGIN, 2);
                BitMatrix matrix = new MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, 700, 700, hints);
                Bitmap bitmap = Bitmap.createBitmap(700, 700, Bitmap.Config.ARGB_8888);
                for (int x = 0; x < 700; x++) for (int y = 0; y < 700; y++) bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                image.setImageBitmap(bitmap); status.setText("QR generated locally");
            } catch (Exception e) { status.setText("Could not generate QR"); }
        });
    }

    private boolean requestLocation() {
        if (ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions((Activity) getContext(), new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return false;
        }
        if (locationManager == null) locationManager = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
        return locationManager != null;
    }

    private void register(int type) {
        SensorManager sm = getSensors();
        if (sm == null) { status.setText("Sensors unavailable"); return; }
        Sensor s = sm.getDefaultSensor(type);
        if (s == null) { status.setText("Required sensor unavailable"); return; }
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME);
    }

    private SensorManager getSensors() {
        if (sensorManager == null) sensorManager = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
        return sensorManager;
    }

    private boolean recording() {
        return getContext().getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(Constants.PREF_IS_RECORDING_IN_PROGRESS, false);
    }

    @Override public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) accel = e.values.clone();
        else if (e.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) magnetic = e.values.clone();
        else if (e.sensor.getType() == Sensor.TYPE_GYROSCOPE) gyro = e.values.clone();
        else if (e.sensor.getType() == Sensor.TYPE_STEP_COUNTER) { if (stepBase < 0f) stepBase = e.values[0]; steps = Math.max(0f, e.values[0] - stepBase); }

        if ("metal".equals(feature) && magnetic != null) {
            float m = (float) Math.sqrt(magnetic[0] * magnetic[0] + magnetic[1] * magnetic[1] + magnetic[2] * magnetic[2]);
            value.setText(String.format(Locale.US, "%.1f µT", m));
        } else if ("clinometer".equals(feature) && accel != null) {
            double pitch = Math.toDegrees(Math.atan2(-accel[0], Math.hypot(accel[1], accel[2])));
            double roll = Math.toDegrees(Math.atan2(accel[1], accel[2]));
            value.setText(String.format(Locale.US, "Pitch %+.1f°\nRoll %+.1f°", pitch, roll));
        } else if ("compass".equals(feature) && accel != null && magnetic != null) {
            float[] r = new float[9], i = new float[9];
            if (SensorManager.getRotationMatrix(r, i, accel, magnetic)) {
                float[] o = new float[3]; SensorManager.getOrientation(r, o);
                double d = Math.toDegrees(o[0]); if (d < 0) d += 360;
                value.setText(String.format(Locale.US, "%.0f°", d));
            }
        } else if ("sensors".equals(feature)) {
            value.setText("ACC " + vector(accel) + "\nGYRO " + vector(gyro) + "\nMAG " + vector(magnetic));
            value.setTextSize(14);
        } else if ("pedometer".equals(feature) && steps >= 0f) {
            value.setText(String.format(Locale.US, "%.0f steps", steps));
        }
    }

    private String vector(float[] v) { return v == null ? "n/a" : String.format(Locale.US, "%+.2f,%+.2f,%+.2f", v[0], v[1], v[2]); }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    @Override public void onLocationChanged(@NonNull Location location) {
        if ("speed".equals(feature)) {
            value.setText(String.format(Locale.US, "%.1f km/h", location.hasSpeed() ? location.getSpeed() * 3.6f : 0f));
            status.setText(String.format(Locale.US, "GPS accuracy %.0f m", location.getAccuracy()));
        }
        if (parkingPending) saveParking(location);
    }
    @Override public void onProviderEnabled(@NonNull String provider) { }
    @Override public void onProviderDisabled(@NonNull String provider) { if ("speed".equals(feature)) status.setText("GPS provider disabled"); }
    @Override public void onStatusChanged(String provider, int statusCode, Bundle extras) { }

    private EditText edit(String hint, String valueText) { EditText e = new EditText(getContext()); e.setHint(hint); e.setText(valueText); e.setTextColor(Color.WHITE); e.setHintTextColor(Color.GRAY); return e; }
    private TextView text(String s, int size, int color) { TextView t = new TextView(getContext()); t.setText(s); t.setTextSize(size); t.setTextColor(color); t.setPadding(0, dp(8), 0, dp(8)); return t; }
    private Button button(String label) { Button b = new Button(getContext()); b.setText(label); b.setAllCaps(false); b.setTextColor(Color.WHITE); return b; }
    private LinearLayout.LayoutParams lp(int width, int height) { return new LinearLayout.LayoutParams(width, height); }
    private int dp(int value) { return Math.round(value * getContext().getResources().getDisplayMetrics().density); }
    private float parseFloat(String raw, float fallback) { try { return Float.parseFloat(raw.trim()); } catch (Exception e) { return fallback; } }
    private void stopAudio() { audioRunning = false; try { if (audio != null) audio.stop(); } catch (Exception ignored) { } try { if (audio != null) audio.release(); } catch (Exception ignored) { } audio = null; if (audioThread != null) audioThread.interrupt(); audioThread = null; }
    @Override public void dismiss() { stopAudio(); if (sensorManager != null) sensorManager.unregisterListener(this); if (locationManager != null) try { locationManager.removeUpdates(this); } catch (SecurityException ignored) { } handler.removeCallbacksAndMessages(null); super.dismiss(); }
}
