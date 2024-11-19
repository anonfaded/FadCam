package com.fadcam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RecordingService extends Service {
    private static final String CHANNEL_ID = "RecordingServiceChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final String TAG = "RecordingService";

    private boolean isRecording = false;
    private CameraDevice frontCamera;
    private CameraDevice backCamera;
    private MediaRecorder frontRecorder;
    private MediaRecorder backRecorder;
    private CameraCaptureSession frontSession;
    private CameraCaptureSession backSession;
    private String frontVideoPath;
    private String backVideoPath;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    /**
     * Handles recording start and stop actions.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case "ACTION_START_RECORDING":
                    startDualRecording();
                    break;
                case "ACTION_STOP_RECORDING":
                    stopDualRecording();
                    break;
                default:
                    Log.w(TAG, "Unknown action: " + intent.getAction());
            }
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startDualRecording() {
        if (isRecording) {
            Log.w(TAG, "startDualRecording: Already recording. Ignoring start request.");
            return;
        }
        isRecording = true;
        startForegroundService();

        SharedPreferences sharedPreferences = getSharedPreferences("com.fadcam_preferences", MODE_PRIVATE);
        String cameraSelection = sharedPreferences.getString(Constantes.PREF_CAMERA_SELECTION, Constantes.CAMERA_BACK);
        Log.d(TAG, "Camera selection from preferences: " + cameraSelection);

        setupAndStartRecorders(cameraSelection);
        openCameras(cameraSelection);

        broadcastRecordingState(true);
    }

    private void setupAndStartRecorders(String cameraSelection) {
        Log.d(TAG, "Setting up recorders for camera: " + cameraSelection);

        // Clear existing resources first
        if (frontRecorder != null) {
            frontRecorder.release();
            frontRecorder = null;
        }
        if (backRecorder != null) {
            backRecorder.release();
            backRecorder = null;
        }

        switch (cameraSelection) {
            case Constantes.CAMERA_FRONT:
                frontVideoPath = generateVideoPath(true);
                frontRecorder = setupMediaRecorder(frontVideoPath, true);
                Log.d(TAG, "Front camera recorder setup completed");
                break;
            case Constantes.CAMERA_BACK:
                backVideoPath = generateVideoPath(false);
                backRecorder = setupMediaRecorder(backVideoPath, false);
                Log.d(TAG, "Back camera recorder setup completed");
                break;
            case Constantes.CAMERA_DUAL:
                // In dual mode, set up both recorders
                frontVideoPath = generateVideoPath(true);
                backVideoPath = generateVideoPath(false);
                frontRecorder = setupMediaRecorder(frontVideoPath, true);
                backRecorder = setupMediaRecorder(backVideoPath, false);
                Log.d(TAG, "Dual camera recorder setup completed");
                break;
            default:
                Log.e(TAG, "Unknown camera selection: " + cameraSelection);
                break;
        }
    }

    private MediaRecorder setupMediaRecorder(String outputPath, boolean isFrontCamera) {
        MediaRecorder recorder = new MediaRecorder();
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setVideoEncodingBitRate(8000000);
            recorder.setVideoFrameRate(30);
            recorder.setVideoSize(1920, 1080);
            recorder.setOutputFile(outputPath);

            // Set orientation based on camera
            if (isFrontCamera) {
                recorder.setOrientationHint(270);
            } else {
                recorder.setOrientationHint(90);
            }

            recorder.prepare();
            return recorder;
        } catch (IOException e) {
            Log.e(TAG, "Error preparing MediaRecorder: " + e.getMessage());
            return null;
        }
    }

    private String generateVideoPath(boolean isFrontCamera) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        File mediaDir = new File(getExternalFilesDir(null), "FadCam");
        if (!mediaDir.exists()) {
            mediaDir.mkdirs();
        }
        String prefix = isFrontCamera ? "temp_front_" : "temp_back_";
        return new File(mediaDir, prefix + timestamp + ".mp4").getAbsolutePath();
    }

    private void openCameras(String cameraSelection) {
        Log.d(TAG, "Opening cameras for mode: " + cameraSelection);

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if (cameraSelection.equals(Constantes.CAMERA_FRONT) ||
                    cameraSelection.equals(Constantes.CAMERA_DUAL)) {
                openFrontCamera(manager);
                Log.d(TAG, "Front camera opened");
            }

            if (cameraSelection.equals(Constantes.CAMERA_BACK) ||
                    cameraSelection.equals(Constantes.CAMERA_DUAL)) {
                openBackCamera(manager);
                Log.d(TAG, "Back camera opened");
            }
        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "Error accessing cameras: " + e.getMessage());
        }
    }

    private void openFrontCamera(CameraManager manager) throws CameraAccessException {
        String frontCameraId = getFrontCameraId(manager);
        if (frontCameraId != null) {
            manager.openCamera(frontCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    frontCamera = camera;
                    if (frontRecorder != null) {
                        createCaptureSession(camera, frontRecorder.getSurface(), true);
                    }
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                }
            }, null);
        }
    }

    private void openBackCamera(CameraManager manager) throws CameraAccessException {
        String backCameraId = getBackCameraId(manager);
        if (backCameraId != null) {
            manager.openCamera(backCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    backCamera = camera;
                    if (backRecorder != null) {
                        createCaptureSession(camera, backRecorder.getSurface(), false);
                    }
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                }
            }, null);
        }
    }

    private void createCaptureSession(CameraDevice camera, Surface surface, boolean isFrontCamera) {
        try {
            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(surface);

            camera.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    try {
                        CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                        builder.addTarget(surface);
                        session.setRepeatingRequest(builder.build(), null, null);

                        if (isFrontCamera) {
                            frontSession = session;
                            frontRecorder.start();
                            Log.d(TAG, "Front recorder started.");
                        } else {
                            backSession = session;
                            backRecorder.start();
                            Log.d(TAG, "Back recorder started.");
                        }
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Error creating capture request: " + e.getMessage());
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "Failed to configure camera session.");
                }
            }, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error creating capture session: " + e.getMessage());
        }
    }

    private void stopDualRecording() {
        if (!isRecording) {
            Log.w(TAG, "stopDualRecording: Not currently recording. Ignoring stop request.");
            return;
        }
        isRecording = false;

        try {
            // Stop and release front camera resources
            if (frontRecorder != null) {
                try {
                    frontRecorder.stop();
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Error stopping front recorder: " + e.getMessage());
                }
                frontRecorder.release();
                frontRecorder = null;
                Log.d(TAG, "Front recorder stopped and released.");
            }

            // Stop and release back camera resources
            if (backRecorder != null) {
                try {
                    backRecorder.stop();
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Error stopping back recorder: " + e.getMessage());
                }
                backRecorder.release();
                backRecorder = null;
                Log.d(TAG, "Back recorder stopped and released.");
            }

            // Close camera sessions
            if (frontSession != null) {
                frontSession.close();
                frontSession = null;
                Log.d(TAG, "Front camera session closed.");
            }
            if (backSession != null) {
                backSession.close();
                backSession = null;
                Log.d(TAG, "Back camera session closed.");
            }

            // Close camera devices
            if (frontCamera != null) {
                frontCamera.close();
                frontCamera = null;
                Log.d(TAG, "Front camera closed.");
            }
            if (backCamera != null) {
                backCamera.close();
                backCamera = null;
                Log.d(TAG, "Back camera closed.");
            }

            stopForeground(true);
            stopSelf();

            // Communicate to HomeFragment that recording has finished
            broadcastRecordingState(false);

        } catch (Exception e) {
            Log.e(TAG, "Error stopping recording: " + e.getMessage());
        }
    }

    private String getFrontCameraId(CameraManager manager) throws CameraAccessException {
        for (String cameraId : manager.getCameraIdList()) {
            if (manager.getCameraCharacteristics(cameraId).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                    == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) {
                return cameraId;
            }
        }
        return null;
    }

    private String getBackCameraId(CameraManager manager) throws CameraAccessException {
        for (String cameraId : manager.getCameraIdList()) {
            if (manager.getCameraCharacteristics(cameraId).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                    == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
                return cameraId;
            }
        }
        return null;
    }

    private void startForegroundService() {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Dual Recording")
                .setContentText("Recording with both cameras")
                .setSmallIcon(R.drawable.unknown_icon3) // Make sure you have this icon in your resources
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Dual Recording Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopDualRecording();
    }

    /**
     * Broadcasts to inform HomeFragment about the recording state.
     *
     * @param recordingState true if recording, false if not.
     */
    private void broadcastRecordingState(boolean recordingState) {
        Intent intent = new Intent("RECORDING_STATE_CHANGED");
        intent.putExtra("isRecording", recordingState);
        sendBroadcast(intent);
    }
}
