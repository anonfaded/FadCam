package com.fadcam;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.exifinterface.media.ExifInterface;

import com.fadcam.dualcam.service.DualCameraRecordingService;
import com.fadcam.audio.NoiseMonitor;
import com.fadcam.network.WeatherService;
import com.fadcam.sensors.SensorDataProvider;
import com.fadcam.ui.LocationHelper;
import com.fadcam.utils.PhotoStorageHelper;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PhotoCaptureActivity extends ComponentActivity {
    public static final String EXTRA_SHORTCUT_PHOTO_CAMERA_MODE = "shortcut_photo_camera_mode";
    public static final String PHOTO_CAMERA_MODE_BACK = "back";
    public static final String PHOTO_CAMERA_MODE_FRONT = "front";
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private boolean launchedFromShortcut = false;

    private final androidx.activity.result.ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) {
                            captureSinglePhoto();
                        } else {
                            Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        launchedFromShortcut = Intent.ACTION_VIEW.equals(getIntent() != null ? getIntent().getAction() : null);
        try {
            com.fadcam.Utils.overridePendingTransitionCompat(this, 0, 0);
            if (getWindow() != null) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                getWindow().setDimAmount(0f);
                getWindow().setLayout(1, 1);
            }
        } catch (Exception ignored) {
        }

        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(this);
        if (prefs != null && prefs.isRecordingInProgress()) {
            Intent intent = new Intent(
                    this,
                    prefs.getCameraSelection() != null && prefs.getCameraSelection().isDual()
                            ? DualCameraRecordingService.class
                            : com.fadcam.services.RecordingService.class
            );
            intent.setAction(Constants.INTENT_ACTION_CAPTURE_PHOTO);
            try {
                startService(intent);
                Utils.showQuickToast(this, R.string.photo_capture_saved);
            } catch (Exception e) {
                Toast.makeText(this, R.string.photo_capture_failed, Toast.LENGTH_SHORT).show();
            }
            if (launchedFromShortcut) {
                moveTaskToBack(true);
            }
            finish();
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
            return;
        }
        captureSinglePhoto();
    }

    @SuppressWarnings("deprecation") // CameraX setTargetResolution fallback for null targetSize
    private void captureSinglePhoto() {
        ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                ProcessCameraProvider provider = providerFuture.get();
                provider.unbindAll();

                SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(this);
                Size targetSize = resolveTargetResolution(prefs);
                int targetRotation = Surface.ROTATION_0;
                if (getDisplay() != null) {
                    targetRotation = getDisplay().getRotation();
                }
                ImageCapture imageCapture;
                if (targetSize != null) {
                    androidx.camera.core.resolutionselector.ResolutionSelector sel =
                            new androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                                    .setResolutionStrategy(
                                            new androidx.camera.core.resolutionselector.ResolutionStrategy(
                                                    targetSize,
                                                    androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                                    .build();
                    imageCapture = new ImageCapture.Builder()
                            .setResolutionSelector(sel)
                            .setTargetRotation(targetRotation)
                            .setJpegQuality(88)
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .build();
                } else {
                    imageCapture = new ImageCapture.Builder()
                            .setTargetResolution(new Size(1920, 1080))
                            .setTargetRotation(targetRotation)
                            .setJpegQuality(88)
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .build();
                }

                CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;
                String shortcutMode = getIntent() != null
                        ? getIntent().getStringExtra(EXTRA_SHORTCUT_PHOTO_CAMERA_MODE)
                        : null;
                PhotoStorageHelper.ShotSource shotSource = PhotoStorageHelper.ShotSource.BACK;
                if (PHOTO_CAMERA_MODE_FRONT.equals(shortcutMode)) {
                    selector = CameraSelector.DEFAULT_FRONT_CAMERA;
                    shotSource = PhotoStorageHelper.ShotSource.SELFIE;
                } else if (PHOTO_CAMERA_MODE_BACK.equals(shortcutMode)) {
                    selector = CameraSelector.DEFAULT_BACK_CAMERA;
                } else if (prefs != null && prefs.getCameraSelection() == com.fadcam.CameraType.FRONT) {
                    selector = CameraSelector.DEFAULT_FRONT_CAMERA;
                    shotSource = PhotoStorageHelper.ShotSource.SELFIE;
                }
                provider.bindToLifecycle(this, selector, imageCapture);
                final PhotoStorageHelper.ShotSource finalShotSource = shotSource;

                File temp = File.createTempFile("fadshot_", ".jpg", getCacheDir());
                ImageCapture.OutputFileOptions opts = new ImageCapture.OutputFileOptions.Builder(temp).build();
                imageCapture.takePicture(opts, cameraExecutor, new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                        Bitmap bitmap = decodeAndOrientBitmap(temp, prefs);
                        Uri saved = null;
                        if (bitmap != null) {
                            // Gather live sensor data on-demand at capture time
                            String extras = gatherWatermarkExtras();
                            saved = PhotoStorageHelper.saveJpegBitmap(
                                    getApplicationContext(),
                                    bitmap,
                                    true,
                                    finalShotSource,
                                    extras != null && !extras.isEmpty() ? extras : null);
                            bitmap.recycle();
                        }
                        //noinspection ResultOfMethodCallIgnored
                        temp.delete();
                        final Uri finalSaved = saved;
                        runOnUiThread(() -> {
                            if (finalSaved != null) {
                                Utils.showQuickToast(PhotoCaptureActivity.this, R.string.photo_capture_saved);
                                com.fadcam.ui.RecordsFragment.requestRefresh();
                                Intent updateIntent = new Intent(Constants.ACTION_RECORDING_COMPLETE);
                                updateIntent.putExtra(Constants.EXTRA_RECORDING_SUCCESS, true);
                                updateIntent.putExtra(Constants.EXTRA_RECORDING_URI_STRING, finalSaved.toString());
                                sendBroadcast(updateIntent);
                            } else {
                                Toast.makeText(PhotoCaptureActivity.this, R.string.photo_capture_failed, Toast.LENGTH_SHORT).show();
                            }
                            finishSafely(provider);
                        });
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        runOnUiThread(() -> {
                            Toast.makeText(PhotoCaptureActivity.this, R.string.photo_capture_failed, Toast.LENGTH_SHORT).show();
                            finishSafely(provider);
                        });
                    }
                });
            } catch (Exception e) {
                Toast.makeText(this, R.string.photo_capture_failed, Toast.LENGTH_SHORT).show();
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private String gatherWatermarkExtras() {
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(this);
        if (prefs == null) return null;

        final StringBuilder sb = new StringBuilder();
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        // LocationHelper needs a Looper. Create a HandlerThread so we don't block
        // the calling thread (camera executor) and GPS callbacks can be delivered.
        android.os.HandlerThread ht = new android.os.HandlerThread("WatermarkGather");
        ht.start();
        final android.os.Handler h = new android.os.Handler(ht.getLooper());

        // Gather location first (needs GPS callback delivery via Looper).
        // When location is done (or timeout), gather sensors and noise.
        if (prefs.isLocalisationEnabled()) {
            final com.fadcam.ui.LocationHelper[] lhHolder = {null};
            h.post(new Runnable() {
                @Override public void run() {
                    final com.fadcam.ui.LocationHelper lh = new com.fadcam.ui.LocationHelper(getApplicationContext());
                    lhHolder[0] = lh;
                    final long deadline = System.currentTimeMillis() + 10000;
                    h.post(new Runnable() {
                        @Override public void run() { pollLocation(lh, deadline, prefs, sb, latch); }
                    });
                }
            });
        } else {
            h.post(() -> gatherSensorsAndFinish(prefs, sb, latch));
        }

        try { latch.await(15, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        ht.quitSafely();
        return sb.length() > 0 ? sb.toString() : null;
    }

    private void pollLocation(com.fadcam.ui.LocationHelper lh, long deadline, SharedPreferencesManager prefs,
                               StringBuilder sb, java.util.concurrent.CountDownLatch latch) {
        android.os.Handler h = new android.os.Handler(android.os.Looper.myLooper());
        org.osmdroid.util.GeoPoint pt = lh.getCurrentLocation();
        if (pt != null) {
            String format = prefs.getWatermarkLocationFormat();
            if ("address".equals(format)) {
                sb.append("Lat: ").append(String.format(java.util.Locale.US, "%.4f", pt.getLatitude()))
                  .append(", Long: ").append(String.format(java.util.Locale.US, "%.4f", pt.getLongitude()));
                com.fadcam.services.LocationGeocoder geocoder = null;
                try {
                    geocoder = new com.fadcam.services.LocationGeocoder();
                    final com.fadcam.services.LocationGeocoder finalGeocoder = geocoder;
                    geocoder.geocodeAsync(pt.getLatitude(), pt.getLongitude(), result -> {
                        if (result != null && !result.isEmpty()) {
                            if (sb.length() > 0) sb.append("\n");
                            sb.append(result.formatted);
                        }
                        finalGeocoder.shutdown();
                    });
                } catch (Exception e) {
                    if (geocoder != null) geocoder.shutdown();
                }
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            } else {
                sb.append("Lat: ").append(String.format(java.util.Locale.US, "%.4f", pt.getLatitude()))
                  .append(", Long: ").append(String.format(java.util.Locale.US, "%.4f", pt.getLongitude()));
            }
            // Grab the REAL raw fix (carries speed/altitude) before stopping.
            android.location.Location rawFix = lh.getRawLocation();
            lh.stopLocationUpdates();
            // Append UTM if enabled and location available
            if (prefs.isUtmEnabled() && pt != null) {
                String utm = com.fadcam.utils.UTMConverter.latLonToUTM(pt.getLatitude(), pt.getLongitude());
                if (utm != null && !utm.isEmpty()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(utm);
                }
            }
            gatherSensorsAndFinish(prefs, sb, latch, pt, rawFix);
        } else if (System.currentTimeMillis() < deadline) {
            h.postDelayed(() -> pollLocation(lh, deadline, prefs, sb, latch), 500);
        } else {
            lh.stopLocationUpdates();
            gatherSensorsAndFinish(prefs, sb, latch, null, null);
        }
    }

    private void gatherSensorsAndFinish(SharedPreferencesManager prefs, StringBuilder sb,
                                         java.util.concurrent.CountDownLatch latch) {
        gatherSensorsAndFinish(prefs, sb, latch, null, null);
    }

    private void gatherSensorsAndFinish(SharedPreferencesManager prefs, StringBuilder sb,
                                         java.util.concurrent.CountDownLatch latch,
                                         org.osmdroid.util.GeoPoint liveLocation) {
        gatherSensorsAndFinish(prefs, sb, latch, liveLocation, null);
    }

    private void gatherSensorsAndFinish(SharedPreferencesManager prefs, StringBuilder sb,
                                         java.util.concurrent.CountDownLatch latch,
                                         org.osmdroid.util.GeoPoint liveLocation,
                                         android.location.Location rawFix) {
        try {
            if (prefs.isSpeedEnabled() || prefs.isAltitudeEnabled() || prefs.isCompassEnabled()) {
                com.fadcam.sensors.SensorDataProvider sdp = com.fadcam.sensors.SensorDataProvider.getInstance(getApplicationContext());
                sdp.start(null);
                // Feed the REAL fix (carries speed/altitude from GPS) into the
                // provider — never a fabricated Location. If no fix is available
                // we pass null and the provider honestly reports no speed.
                if (rawFix != null) {
                    sdp.updateLocation(rawFix);
                }
                try { Thread.sleep(400); } catch (InterruptedException ignored) {}
                if (prefs.isSpeedEnabled()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append("Speed: ");
                    if (sdp.hasFreshFix()) {
                        sb.append(String.format(java.util.Locale.US, "%.0f", sdp.getSpeedKmh())).append("km/h");
                    } else {
                        sb.append("--");
                    }
                }
                if (prefs.isAltitudeEnabled()) { if (sb.length() > 0) sb.append("\n"); sb.append("Alt: ").append(String.format(java.util.Locale.US, "%.0f", sdp.getAltitude())).append("m"); }
                if (prefs.isCompassEnabled()) { if (sb.length() > 0) sb.append("\n"); sb.append("Compass: ").append(sdp.getCompassDirection()); }
                com.fadcam.sensors.SensorDataProvider.resetInstance();
            }
            if (prefs.isNoiseEnabled()) {
                com.fadcam.audio.NoiseMonitor nm = com.fadcam.audio.NoiseMonitor.getInstance();
                nm.start(getApplicationContext());
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                if (nm.isRunning()) { if (sb.length() > 0) sb.append("\n"); sb.append("Noise: ").append(nm.getReadableDb()); }
                com.fadcam.audio.NoiseMonitor.resetInstance();
            }
            if (prefs.isWeatherEnabled()) {
                com.fadcam.network.WeatherService ws = com.fadcam.network.WeatherService.getInstance(getApplicationContext());
                String w = ws.getCurrentWeather();
                if ((w == null || w.isEmpty()) && liveLocation != null) {
                    // Use WeatherService callback instead of Thread.sleep (which blocks Looper)
                    final java.util.concurrent.CountDownLatch weatherLatch = new java.util.concurrent.CountDownLatch(1);
                    ws.fetchWeather(liveLocation, (weather, wind) -> weatherLatch.countDown());
                    try { weatherLatch.await(4, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                    w = ws.getCurrentWeather();
                }
                if (w != null && !w.isEmpty()) { if (sb.length() > 0) sb.append("\n"); sb.append(w); }
                String wi = ws.getCurrentWind();
                if (wi != null && !wi.isEmpty()) { if (sb.length() > 0) sb.append("\n"); sb.append("Wind: ").append(wi); }
            }
        } finally {
            latch.countDown();
        }
    }

    private void finishSafely(@NonNull ProcessCameraProvider provider) {
        try {
            provider.unbindAll();
        } catch (Exception ignored) {
        }
        if (launchedFromShortcut) {
            moveTaskToBack(true);
            try {
                com.fadcam.Utils.overridePendingTransitionCompat(this, 0, 0);
            } catch (Exception ignored) {
            }
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdownNow();
    }

    @Nullable
    private Size resolveTargetResolution(@Nullable SharedPreferencesManager prefs) {
        if (prefs == null) {
            return new Size(1920, 1080);
        }
        Size s = prefs.getCameraResolution();
        if (s == null) {
            return new Size(1920, 1080);
        }
        int w = s.getWidth();
        int h = s.getHeight();
        String orientation = prefs.getVideoOrientation();
        if (SharedPreferencesManager.ORIENTATION_PORTRAIT.equalsIgnoreCase(orientation) && w > h) {
            return new Size(h, w);
        }
        if (SharedPreferencesManager.ORIENTATION_LANDSCAPE.equalsIgnoreCase(orientation) && h > w) {
            return new Size(h, w);
        }
        return s;
    }

    @Nullable
    private Bitmap decodeAndOrientBitmap(@NonNull File file, @Nullable SharedPreferencesManager prefs) {
        Bitmap source = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (source == null) return null;
        Bitmap oriented = applyExifRotationIfNeeded(source, file.getAbsolutePath());
        if (prefs == null) return oriented;
        String orientation = prefs.getVideoOrientation();
        if (SharedPreferencesManager.ORIENTATION_PORTRAIT.equalsIgnoreCase(orientation) && oriented.getWidth() > oriented.getHeight()) {
            Bitmap rotated = rotate(oriented, 90f);
            if (rotated != oriented && oriented != source && !oriented.isRecycled()) oriented.recycle();
            return rotated;
        }
        if (SharedPreferencesManager.ORIENTATION_LANDSCAPE.equalsIgnoreCase(orientation) && oriented.getHeight() > oriented.getWidth()) {
            Bitmap rotated = rotate(oriented, 90f);
            if (rotated != oriented && oriented != source && !oriented.isRecycled()) oriented.recycle();
            return rotated;
        }
        return oriented;
    }

    @NonNull
    private Bitmap applyExifRotationIfNeeded(@NonNull Bitmap bitmap, @NonNull String path) {
        try {
            ExifInterface exif = new ExifInterface(path);
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return rotate(bitmap, 90f);
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return rotate(bitmap, 180f);
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return rotate(bitmap, 270f);
                default:
                    return bitmap;
            }
        } catch (Exception ignored) {
            return bitmap;
        }
    }

    @NonNull
    private Bitmap rotate(@NonNull Bitmap bitmap, float degrees) {
        Matrix m = new Matrix();
        m.postRotate(degrees);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
        if (rotated != bitmap && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
        return rotated;
    }
}
