package com.fadcam;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
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

/**
 * Professional FadCam production-room dashboard.
 *
 * This first studio milestone deliberately keeps the broadcast surface honest:
 * CAM 1 is a real CameraX preview, CAM 2 can switch to the front camera, and
 * disconnected sources are clearly marked instead of showing fake video.
 * Recording/streaming engines remain owned by the existing production services
 * and will be connected in the next implementation milestone.
 */
public class StudioActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_REQUEST = 7101;

    private PreviewView previewView;
    private ImageView programSnapshot;
    private TextView liveStatus;
    private TextView recordingStatus;
    private TextView streamStatus;
    private TextView activeCameraLabel;
    private LifecycleCameraController cameraController;
    private final Handler snapshotHandler = new Handler(Looper.getMainLooper());
    private boolean frontCamera = false;

    private final Runnable snapshotRunnable = new Runnable() {
        @Override
        public void run() {
            if (previewView != null && programSnapshot != null) {
                Bitmap bitmap = previewView.getBitmap();
                if (bitmap != null && !bitmap.isRecycled()) {
                    programSnapshot.setImageBitmap(bitmap);
                }
            }
            snapshotHandler.postDelayed(this, 250L);
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

        configureSwitcher();
        configureProductionControls();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST);
        }
    }

    private void startCamera() {
        cameraController = new LifecycleCameraController(this);
        cameraController.setEnabledUseCases(CameraController.IMAGE_CAPTURE);
        cameraController.bindToLifecycle(this);
        cameraController.setCameraSelector(frontCamera
                ? CameraSelector.DEFAULT_FRONT_CAMERA
                : CameraSelector.DEFAULT_BACK_CAMERA);
        previewView.setController(cameraController);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        liveStatus.setText("LIVE • CAM 1");
        activeCameraLabel.setText(frontCamera ? "CAM 2 • FRONT" : "CAM 1 • BACK");
        snapshotHandler.removeCallbacks(snapshotRunnable);
        snapshotHandler.post(snapshotRunnable);
    }

    private void configureSwitcher() {
        int[] ids = {
                R.id.studio_cam1, R.id.studio_cam2, R.id.studio_cam3, R.id.studio_cam4,
                R.id.studio_cam5, R.id.studio_cam6, R.id.studio_cam7, R.id.studio_cam8
        };
        for (int i = 0; i < ids.length; i++) {
            final int cameraNumber = i + 1;
            View tile = findViewById(ids[i]);
            if (tile != null) {
                tile.setOnClickListener(v -> selectCamera(cameraNumber));
            }
        }

        findViewById(R.id.studio_cut).setOnClickListener(v -> showTransition("CUT"));
        findViewById(R.id.studio_fade).setOnClickListener(v -> showTransition("FADE"));
        findViewById(R.id.studio_mix).setOnClickListener(v -> showTransition("MIX"));
    }

    private void selectCamera(int cameraNumber) {
        if (cameraNumber == 1 || cameraNumber == 2) {
            frontCamera = cameraNumber == 2;
            if (cameraController != null) {
                cameraController.setCameraSelector(frontCamera
                        ? CameraSelector.DEFAULT_FRONT_CAMERA
                        : CameraSelector.DEFAULT_BACK_CAMERA);
            }
            activeCameraLabel.setText(frontCamera ? "CAM 2 • FRONT" : "CAM 1 • BACK");
            liveStatus.setText("LIVE • CAM " + cameraNumber);
        } else {
            Toast.makeText(this, "CAM " + cameraNumber + " is not connected yet", Toast.LENGTH_SHORT).show();
        }
    }

    private void showTransition(String transition) {
        TextView transitionLabel = findViewById(R.id.studio_transition_label);
        transitionLabel.setText(transition + " READY");
    }

    private void configureProductionControls() {
        findViewById(R.id.studio_record_button).setOnClickListener(v -> {
            recordingStatus.setText("● REC • READY FOR ENGINE LINK");
            Toast.makeText(this, "Recording engine connection is the next milestone", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.studio_stream_button).setOnClickListener(v -> {
            streamStatus.setText("● STREAM • READY");
            Toast.makeText(this, "Streaming destinations are ready for service integration", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.studio_back_to_camera).setOnClickListener(v -> {
            startActivity(new android.content.Intent(this, MainActivity.class));
            finish();
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            liveStatus.setText("CAMERA PERMISSION REQUIRED");
        }
    }

    @Override
    protected void onDestroy() {
        snapshotHandler.removeCallbacksAndMessages(null);
        if (cameraController != null) {
            cameraController.unbind();
        }
        super.onDestroy();
    }
}
