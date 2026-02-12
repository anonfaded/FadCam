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
import com.fadcam.utils.PhotoStorageHelper;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PhotoCaptureActivity extends ComponentActivity {
    public static final String EXTRA_SHORTCUT_PHOTO_CAMERA_MODE = "shortcut_photo_camera_mode";
    public static final String PHOTO_CAMERA_MODE_BACK = "back";
    public static final String PHOTO_CAMERA_MODE_FRONT = "front";
    private static final int RC_CAMERA = 9007;
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private boolean launchedFromShortcut = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        launchedFromShortcut = Intent.ACTION_VIEW.equals(getIntent() != null ? getIntent().getAction() : null);
        try {
            overridePendingTransition(0, 0);
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
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, RC_CAMERA);
            return;
        }
        captureSinglePhoto();
    }

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
                ImageCapture imageCapture = new ImageCapture.Builder()
                        .setTargetResolution(targetSize != null ? targetSize : new Size(1920, 1080))
                        .setTargetRotation(targetRotation)
                        .setJpegQuality(88)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

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
                            saved = PhotoStorageHelper.saveJpegBitmap(
                                    getApplicationContext(),
                                    bitmap,
                                    true,
                                    finalShotSource);
                            bitmap.recycle();
                        }
                        //noinspection ResultOfMethodCallIgnored
                        temp.delete();
                        final Uri finalSaved = saved;
                        runOnUiThread(() -> {
                            if (finalSaved != null) {
                                Utils.showQuickToast(PhotoCaptureActivity.this, R.string.photo_capture_saved);
                                com.fadcam.ui.RecordsFragment.requestRefresh();
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

    private void finishSafely(@NonNull ProcessCameraProvider provider) {
        try {
            provider.unbindAll();
        } catch (Exception ignored) {
        }
        if (launchedFromShortcut) {
            moveTaskToBack(true);
            try {
                overridePendingTransition(0, 0);
            } catch (Exception ignored) {
            }
        }
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == RC_CAMERA && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            captureSinglePhoto();
        } else {
            Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_SHORT).show();
            finish();
        }
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
