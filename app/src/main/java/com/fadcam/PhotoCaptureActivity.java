package com.fadcam;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.fadcam.dualcam.service.DualCameraRecordingService;
import com.fadcam.utils.PhotoStorageHelper;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PhotoCaptureActivity extends ComponentActivity {
    private static final int RC_CAMERA = 9007;
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
            moveTaskToBack(true);
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

                ImageCapture imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;
                provider.bindToLifecycle(this, selector, imageCapture);

                File temp = File.createTempFile("fadshot_", ".jpg", getCacheDir());
                ImageCapture.OutputFileOptions opts = new ImageCapture.OutputFileOptions.Builder(temp).build();
                imageCapture.takePicture(opts, cameraExecutor, new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                        Bitmap bitmap = BitmapFactory.decodeFile(temp.getAbsolutePath());
                        Uri saved = null;
                        if (bitmap != null) {
                            saved = PhotoStorageHelper.saveJpegBitmap(getApplicationContext(), bitmap);
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
        moveTaskToBack(true);
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
}
