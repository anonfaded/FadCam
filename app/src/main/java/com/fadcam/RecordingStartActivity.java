package com.fadcam;

import com.fadcam.FLog;
import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import com.fadcam.dualcam.service.DualCameraRecordingService;
import com.fadcam.services.RecordingService;
import com.fadcam.streaming.RtmpPublisherService;

public class RecordingStartActivity extends Activity {
    private static final String TAG = "RecordingStartActivity";
    private static final int REQUEST_RTMP_CAPTURE_PERMISSIONS = 7402;
    private static final int REQUEST_RECORDING_CAPTURE_PERMISSIONS = 7403;
    public static final String EXTRA_SHORTCUT_CAMERA_MODE = "shortcut_camera_mode";
    public static final String CAMERA_MODE_BACK = "back";
    public static final String CAMERA_MODE_FRONT = "front";
    public static final String CAMERA_MODE_CURRENT = "current";
    public static final String CAMERA_MODE_DUAL = "dual";

    private Intent pendingRtmpIntent;
    private Intent pendingRecordingIntent;
    private boolean permissionRequestInProgress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            Intent incoming = getIntent();
            if (incoming != null && RtmpPublisherService.ACTION_START.equals(incoming.getAction())) {
                startRtmpPublisher(incoming);
                return;
            }

            SharedPreferencesManager sharedPreferencesManager = SharedPreferencesManager.getInstance(this);
            // Check if recording is already in progress
            if (sharedPreferencesManager.isRecordingInProgress()) {
                Utils.showQuickToast(this, R.string.video_recording_started);
                finish();
                return;
            }

            String mode = incoming != null
                    ? incoming.getStringExtra(EXTRA_SHORTCUT_CAMERA_MODE)
                    : null;
            if (mode == null) mode = CAMERA_MODE_BACK;

            if (CAMERA_MODE_FRONT.equals(mode)) {
                sharedPreferencesManager.sharedPreferences.edit()
                        .putString(Constants.PREF_CAMERA_SELECTION, CameraType.FRONT.name())
                        .apply();
            } else if (CAMERA_MODE_BACK.equals(mode)) {
                sharedPreferencesManager.sharedPreferences.edit()
                        .putString(Constants.PREF_CAMERA_SELECTION, CameraType.BACK.name())
                        .apply();
            } else if (CAMERA_MODE_DUAL.equals(mode)) {
                sharedPreferencesManager.sharedPreferences.edit()
                        .putString(Constants.PREF_CAMERA_SELECTION, CameraType.DUAL_PIP.name())
                        .apply();
            }

            CameraType selectedCamera = sharedPreferencesManager.getCameraSelection();
            boolean shouldStartDual = CAMERA_MODE_DUAL.equals(mode)
                    || (CAMERA_MODE_CURRENT.equals(mode)
                    && selectedCamera != null
                    && selectedCamera.isDual());

            Intent startIntent;
            if (shouldStartDual) {
                startIntent = new Intent(this, DualCameraRecordingService.class);
                startIntent.setAction(Constants.INTENT_ACTION_START_DUAL_RECORDING);
            } else {
                startIntent = new Intent(this, RecordingService.class);
                startIntent.setAction(Constants.INTENT_ACTION_START_RECORDING);
            }

            if (!hasCapturePermissions()) {
                pendingRecordingIntent = startIntent;
                requestCapturePermissions(REQUEST_RECORDING_CAPTURE_PERMISSIONS);
                return;
            }

            startCaptureService(startIntent);

        } catch (Exception e) {
            FLog.e(TAG, "Error starting recording via shortcut", e);
            Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show();
        } finally {
            if (!permissionRequestInProgress) {
                moveTaskToBack(true);
                finish();
            }
        }
    }

    private boolean hasCapturePermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCapturePermissions(int requestCode) {
        permissionRequestInProgress = true;
        requestPermissions(
                new String[] {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                requestCode);
    }

    private void startCaptureService(@NonNull Intent serviceIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent);
        } else {
            startService(serviceIntent);
        }
        moveTaskToBack(true);
        finish();
    }

    private void startRtmpPublisher(@NonNull Intent incoming) {
        String endpoint = incoming.getStringExtra(RtmpPublisherService.EXTRA_ENDPOINT);
        if (endpoint == null || !endpoint.startsWith("rtmp")) {
            Toast.makeText(this, "A valid RTMP/RTMPS endpoint is required", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (!hasCapturePermissions()) {
            pendingRtmpIntent = new Intent(incoming);
            requestCapturePermissions(REQUEST_RTMP_CAPTURE_PERMISSIONS);
            return;
        }

        Intent serviceIntent = new Intent(this, RtmpPublisherService.class)
                .setAction(RtmpPublisherService.ACTION_START)
                .putExtra(RtmpPublisherService.EXTRA_ENDPOINT, endpoint);
        ContextCompat.startForegroundService(this, serviceIntent);
        Toast.makeText(this, "FadCam RTMP publisher starting", Toast.LENGTH_SHORT).show();
        moveTaskToBack(true);
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RTMP_CAPTURE_PERMISSIONS
                && requestCode != REQUEST_RECORDING_CAPTURE_PERMISSIONS) {
            return;
        }

        permissionRequestInProgress = false;
        boolean granted = hasCapturePermissions();

        if (granted) {
            if (requestCode == REQUEST_RTMP_CAPTURE_PERMISSIONS && pendingRtmpIntent != null) {
                Intent retry = pendingRtmpIntent;
                pendingRtmpIntent = null;
                startRtmpPublisher(retry);
            } else if (requestCode == REQUEST_RECORDING_CAPTURE_PERMISSIONS && pendingRecordingIntent != null) {
                Intent retry = pendingRecordingIntent;
                pendingRecordingIntent = null;
                startCaptureService(retry);
            }
        } else {
            String message = requestCode == REQUEST_RTMP_CAPTURE_PERMISSIONS
                    ? "Camera and microphone permissions are required for live publishing"
                    : "Camera and microphone permissions are required to record video";
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!permissionRequestInProgress) {
            finish();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!permissionRequestInProgress) {
            moveTaskToBack(true);
        }
    }
}
