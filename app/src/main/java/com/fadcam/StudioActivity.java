package com.fadcam;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.view.CameraController;
import androidx.camera.view.LifecycleCameraController;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.fadcam.streaming.RemoteStreamManager;
import com.fadcam.streaming.RemoteStreamService;

import java.util.ArrayList;
import java.util.List;

/**
 * Professional FadCam production-room dashboard.
 *
 * The Studio is an orchestration layer over FadCam's existing production
 * engines. It never invents camera, recording, or streaming state.
 */
public class StudioActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST = 7101;

    private PreviewView previewView;
    private ImageView programSnapshot;
    private TextView liveStatus;
    private TextView recordingStatus;
    private TextView streamStatus;
    private TextView activeCameraLabel;
    private TextView transitionLabel;
    private TextView graphicsOverlay;
    private TextView programGraphics;
    private TextView audioStatus;
    private TextView replayStatus;

    private LifecycleCameraController cameraController;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean frontCamera;
    private boolean programIsFront;
    private boolean programFrozen;
    private boolean recording;
    private boolean streaming;
    private String graphicsText = "FADCAM • LIVE";

    private final Runnable snapshotRunnable = new Runnable() {
        @Override
        public void run() {
            // Before the first TAKE, preview and program mirror the real camera.
            // After TAKE, the program frame remains frozen until the next transition.
            if (!programFrozen && previewView != null && programSnapshot != null) {
                Bitmap bitmap = previewView.getBitmap();
                if (bitmap != null && !bitmap.isRecycled()) {
                    programSnapshot.setImageBitmap(bitmap);
                }
            }
            handler.postDelayed(this, 250L);
        }
    };

    private final Runnable stateRunnable = new Runnable() {
        @Override
        public void run() {
            refreshEngineState();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_studio);

        previewView = findViewById(R.id.studio_preview);
        programSnapshot = findViewById(R.id.studio_program_snapshot);
        liveStatus = findViewById(R.id.studio_live_status);
        recordingStatus = findViewById(R.id.studio_record_status);
        streamStatus = findViewById(R.id.studio_stream_status);
        activeCameraLabel = findViewById(R.id.studio_active_camera);
        transitionLabel = findViewById(R.id.studio_transition_label);
        graphicsOverlay = findViewById(R.id.studio_graphics_overlay);
        programGraphics = findViewById(R.id.studio_program_graphics);
        audioStatus = findViewById(R.id.studio_audio_status);
        replayStatus = findViewById(R.id.studio_replay_status);

        configureSwitcher();
        configureProductionControls();
        configureGraphics();
        configureAudioRouting();
        configureReplay();
        configureRemoteControl();

        if (hasCapturePermissions()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                    PERMISSION_REQUEST);
        }

        refreshEngineState();
        handler.post(stateRunnable);
    }

    private boolean hasCapturePermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startCamera() {
        cameraController = new LifecycleCameraController(this);
        cameraController.setEnabledUseCases(CameraController.IMAGE_CAPTURE);
        cameraController.setCameraSelector(frontCamera
                ? CameraSelector.DEFAULT_FRONT_CAMERA
                : CameraSelector.DEFAULT_BACK_CAMERA);
        cameraController.bindToLifecycle(this);
        previewView.setController(cameraController);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        updateCameraLabels();
        handler.removeCallbacks(snapshotRunnable);
        handler.post(snapshotRunnable);
    }

    private void configureSwitcher() {
        int[] ids = {
                R.id.studio_cam1, R.id.studio_cam2, R.id.studio_cam3, R.id.studio_cam4,
                R.id.studio_cam5, R.id.studio_cam6, R.id.studio_cam7, R.id.studio_cam8
        };
        for (int i = 0; i < ids.length; i++) {
            final int cameraNumber = i + 1;
            View tile = findViewById(ids[i]);
            if (tile != null) tile.setOnClickListener(v -> selectCamera(cameraNumber));
        }
        findViewById(R.id.studio_cut).setOnClickListener(v -> takeProgramLive("CUT"));
        findViewById(R.id.studio_fade).setOnClickListener(v -> takeProgramLive("FADE"));
        findViewById(R.id.studio_mix).setOnClickListener(v -> takeProgramLive("MIX"));
    }

    private void selectCamera(int cameraNumber) {
        if (cameraNumber != 1 && cameraNumber != 2) {
            Toast.makeText(this, "CAM " + cameraNumber + " is not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        frontCamera = cameraNumber == 2;
        if (cameraController == null) return;
        try {
            cameraController.setCameraSelector(frontCamera
                    ? CameraSelector.DEFAULT_FRONT_CAMERA
                    : CameraSelector.DEFAULT_BACK_CAMERA);
            updateCameraLabels();
            transitionLabel.setText("CAM " + cameraNumber + " PREVIEW");
        } catch (IllegalStateException e) {
            Toast.makeText(this, "Selected camera is unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    /** Takes the current real preview frame to program with a real transition. */
    private void takeProgramLive(String transition) {
        if (programSnapshot == null || previewView == null) return;
        Bitmap bitmap = previewView.getBitmap();
        if (bitmap == null) {
            Toast.makeText(this, "Program frame is not ready", Toast.LENGTH_SHORT).show();
            return;
        }
        programFrozen = true;
        if ("FADE".equals(transition)) {
            programSnapshot.animate().alpha(0f).setDuration(160).withEndAction(() -> {
                programSnapshot.setImageBitmap(bitmap);
                programSnapshot.animate().alpha(1f).setDuration(220).start();
            }).start();
        } else if ("MIX".equals(transition)) {
            programSnapshot.setAlpha(0.35f);
            programSnapshot.setImageBitmap(bitmap);
            programSnapshot.animate().alpha(1f).setDuration(350).start();
        } else {
            programSnapshot.setAlpha(1f);
            programSnapshot.setImageBitmap(bitmap);
        }
        programIsFront = frontCamera;
        transitionLabel.setText(transition + " • PROGRAM CAM " + (programIsFront ? "2" : "1"));
        graphicsOverlay.bringToFront();
        programGraphics.bringToFront();
    }

    private void configureProductionControls() {
        findViewById(R.id.studio_record_button).setOnClickListener(v -> toggleRecording());
        findViewById(R.id.studio_stream_button).setOnClickListener(v -> toggleStreaming());
        findViewById(R.id.studio_back_to_camera).setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });
    }

    /** Connects Studio RECORD directly to FadCam's existing RecordingService. */
    private void toggleRecording() {
        if (!hasCapturePermissions()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                    PERMISSION_REQUEST);
            return;
        }
        Intent intent = new Intent(this, com.fadcam.services.RecordingService.class);
        intent.setAction(recording ? Constants.INTENT_ACTION_STOP_RECORDING
                : Constants.INTENT_ACTION_START_RECORDING);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        recording = !recording;
        updateRecordStatus();
    }

    /** Connects Studio STREAM to the existing authenticated/local HLS service. */
    private void toggleStreaming() {
        if (!streaming) {
            try {
                RemoteStreamManager.getInstance().setContext(this);
                Intent intent = new Intent(this, RemoteStreamService.class);
                startForegroundService(intent);
                streaming = true;
                streamStatus.setText("● STREAM • STARTING HLS");
                Toast.makeText(this, "Live HLS server starting", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                streamStatus.setText("○ STREAM • ERROR");
                Toast.makeText(this, "Streaming could not start: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else {
            try {
                stopService(new Intent(this, RemoteStreamService.class));
            } catch (Exception ignored) {
            }
            streaming = false;
            streamStatus.setText("○ STREAM • IDLE");
        }
    }

    private void refreshEngineState() {
        try {
            recording = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
                    .getBoolean(Constants.PREF_IS_RECORDING_IN_PROGRESS, recording);
        } catch (Exception ignored) {
        }
        try {
            streaming = RemoteStreamManager.getInstance().isStreamingEnabled();
        } catch (Exception ignored) {
        }
        updateRecordStatus();
        streamStatus.setText(streaming ? "● STREAM • LIVE HLS" : "○ STREAM • IDLE");
    }

    private void updateRecordStatus() {
        recordingStatus.setText(recording ? "● REC • ACTIVE" : "○ REC • IDLE");
        liveStatus.setText(recording ? "LIVE • RECORDING" : "LIVE • STANDBY");
    }

    private void configureGraphics() {
        graphicsOverlay.setText(graphicsText);
        programGraphics.setText(graphicsText);
        findViewById(R.id.studio_graphics_button).setOnClickListener(v -> editGraphics());
    }

    private void editGraphics() {
        final EditText input = new EditText(this);
        input.setSingleLine(false);
        input.setText(graphicsText);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Program Graphic")
                .setMessage("This title is applied to the Studio preview and program surfaces.")
                .setView(input)
                .setPositiveButton("APPLY", (dialog, which) -> {
                    graphicsText = input.getText().toString().trim();
                    if (graphicsText.isEmpty()) graphicsText = "FADCAM • LIVE";
                    graphicsOverlay.setText(graphicsText);
                    programGraphics.setText(graphicsText);
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    /** Uses Android's real connected audio-device inventory for production input selection. */
    private void configureAudioRouting() {
        findViewById(R.id.studio_audio_button).setOnClickListener(v -> showAudioDevices());
    }

    private void showAudioDevices() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        List<AudioDeviceInfo> devices = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                devices.add(device);
            }
        }
        if (devices.isEmpty()) {
            audioStatus.setText("AUDIO • DEFAULT MIC");
            Toast.makeText(this, "No external input device is connected", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) labels[i] = audioDeviceName(devices.get(i));
        new AlertDialog.Builder(this)
                .setTitle("Audio Input Routing")
                .setItems(labels, (dialog, which) -> selectAudioDevice(devices.get(which)))
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void selectAudioDevice(AudioDeviceInfo device) {
        // Persist the selected physical input for the recording engine/settings layer.
        // MediaRecorder's input routing is device/OEM controlled; we do not falsely
        // claim that an input route was applied when Android exposes no such setter.
        getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE).edit()
                .putString(Constants.PREF_AUDIO_INPUT_DEVICE_NAME, audioDeviceName(device))
                .apply();
        audioStatus.setText("AUDIO • " + audioDeviceName(device) + " • SELECTED");
    }

    private String audioDeviceName(AudioDeviceInfo device) {
        CharSequence name = device.getProductName();
        return name == null || name.length() == 0 ? "Input " + device.getId() : name.toString();
    }

    private void configureReplay() {
        findViewById(R.id.studio_replay_button).setOnClickListener(v -> openLatestRecording());
    }

    /** Opens the newest saved video using Android's actual media playback stack. */
    private void openLatestRecording() {
        ContentResolver resolver = getContentResolver();
        String[] projection = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.MIME_TYPE};
        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        try (Cursor cursor = resolver.query(collection, projection, null, null,
                MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID));
                Uri video = Uri.withAppendedPath(collection, String.valueOf(id));
                Intent view = new Intent(Intent.ACTION_VIEW, video);
                view.setDataAndType(video, "video/*");
                view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(view);
                replayStatus.setText("REPLAY • OPENED LATEST");
                return;
            }
        } catch (Exception e) {
            Toast.makeText(this, "Unable to open replay: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        replayStatus.setText("REPLAY • NO RECORDINGS");
    }

    /** Opens FadCam's existing authenticated Remote tab for remote control. */
    private void configureRemoteControl() {
        findViewById(R.id.studio_remote_button).setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("navigate_to_tab", 2);
            startActivity(intent);
        });
    }

    private void updateCameraLabels() {
        int number = frontCamera ? 2 : 1;
        liveStatus.setText(recording ? "LIVE • RECORDING" : "LIVE • CAM " + number);
        activeCameraLabel.setText(frontCamera ? "CAM 2 • FRONT" : "CAM 1 • BACK");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST && hasCapturePermissions()) {
            startCamera();
        } else if (requestCode == PERMISSION_REQUEST) {
            liveStatus.setText("CAMERA / AUDIO PERMISSION REQUIRED");
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (cameraController != null) cameraController.unbind();
        super.onDestroy();
    }
}
