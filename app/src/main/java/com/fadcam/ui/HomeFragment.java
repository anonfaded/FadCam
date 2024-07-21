package com.fadcam.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.fadcam.R;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class HomeFragment extends Fragment {

    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private MediaRecorder mediaRecorder;
    private boolean isRecording = false;
    private TextureView textureView;


    private TextView tvStorageInfo;
    private TextView tvPreviewPlaceholder;
    private Button buttonStartStop;
    private Button buttonPauseResume;
    private boolean isPaused = false;


    private void updateStorageInfo() {
        File externalStorageDir = Environment.getExternalStorageDirectory();
        long availableSpace = externalStorageDir.getFreeSpace();
        long totalSpace = externalStorageDir.getTotalSpace();

        long availableGB = availableSpace / (1024 * 1024 * 1024);
        long totalGB = totalSpace / (1024 * 1024 * 1024);

        tvStorageInfo.setText(String.format("Storage: %d GB / %d GB", availableGB, totalGB));
    }
    private void pauseRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mediaRecorder.pause();
            isPaused = true;
            buttonPauseResume.setText("Resume");
            buttonPauseResume.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_play, 0, 0, 0);
        }
    }
    private void resumeRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mediaRecorder.resume();
            isPaused = false;
            buttonPauseResume.setText("Pause");
            buttonPauseResume.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_pause, 0, 0, 0);
        }
    }



    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);

        textureView = view.findViewById(R.id.textureView);
        tvStorageInfo = view.findViewById(R.id.tvStorageInfo);
        tvPreviewPlaceholder = view.findViewById(R.id.tvPreviewPlaceholder);
        buttonStartStop = view.findViewById(R.id.buttonStartStop);
        buttonPauseResume = view.findViewById(R.id.buttonPauseResume);

        updateStorageInfo();

        buttonStartStop.setOnClickListener(v -> {
            if (!isRecording) {
                startRecording();
            } else {
                stopRecording();
            }
        });

        buttonPauseResume.setOnClickListener(v -> {
            if (isRecording) {
                if (isPaused) {
                    resumeRecording();
                } else {
                    pauseRecording();
                }
            }
        });

        return view;
    }

    private void startRecording() {
        if (!isRecording) {
            if (cameraDevice == null) {
                openCamera();
            } else {
                startRecordingVideo();
            }
            buttonStartStop.setText("Stop");
            buttonStartStop.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_stop, 0, 0, 0);
            buttonPauseResume.setEnabled(true);
            tvPreviewPlaceholder.setVisibility(View.GONE);
        }
    }

    private String getCameraSelection() {
        SharedPreferences sharedPreferences = getActivity().getPreferences(Context.MODE_PRIVATE);
        return sharedPreferences.getString("camera_selection", "back");
    }

    private void openCamera() {
        CameraManager manager = (CameraManager) getActivity().getSystemService(Context.CAMERA_SERVICE);
        try {
            String[] cameraIdList = manager.getCameraIdList();
            String cameraId = getCameraSelection().equals("front") ? cameraIdList[1] : cameraIdList[0];
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    startRecordingVideo();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    cameraDevice.close();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    cameraDevice.close();
                    cameraDevice = null;
                }
            }, null);
        } catch (CameraAccessException | SecurityException e) {
            e.printStackTrace();
        }
    }



    private void startRecordingVideo() {
        if (null == cameraDevice || !textureView.isAvailable() || !Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            return;
        }
        try {
            setupMediaRecorder();
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(1280, 720);
            Surface previewSurface = new Surface(texture);
            Surface recorderSurface = mediaRecorder.getSurface();
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            captureRequestBuilder.addTarget(previewSurface);
            captureRequestBuilder.addTarget(recorderSurface);

            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, recorderSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            HomeFragment.this.cameraCaptureSession = cameraCaptureSession;
                            try {
                                cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                            mediaRecorder.start();
                            getActivity().runOnUiThread(() -> {
                                isRecording = true;
                                Toast.makeText(getContext(), "Recording started", Toast.LENGTH_SHORT).show();
                            });
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Toast.makeText(getContext(), "Failed to start recording", Toast.LENGTH_SHORT).show();
                        }
                    }, null);
        } catch (CameraAccessException | IOException e) {
            e.printStackTrace();
        }
    }

    private void setupMediaRecorder() throws IOException {
        File videoDir = new File(getActivity().getExternalFilesDir(null), "FadCam");
        if (!videoDir.exists()) {
            videoDir.mkdirs();
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd_hh_mm_ssa", Locale.getDefault()).format(new Date());
        String videoFilePath = videoDir.getAbsolutePath() + "/FADCAM_" + timestamp + ".mp4";

        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setOutputFile(videoFilePath);
        mediaRecorder.setVideoEncodingBitRate(10000000);
        mediaRecorder.setVideoFrameRate(30);
        mediaRecorder.setVideoSize(1280, 720);
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.prepare();
    }

    private void stopRecording() {
        if (isRecording) {
            isRecording = false;
            isPaused = false;
            mediaRecorder.stop();
            mediaRecorder.reset();
            mediaRecorder.release();
            mediaRecorder = null;
            if (cameraCaptureSession != null) {
                cameraCaptureSession.close();
                cameraCaptureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            buttonStartStop.setText("Start");
            buttonStartStop.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_play, 0, 0, 0);
            buttonPauseResume.setEnabled(false);
            buttonPauseResume.setText("Pause");
            buttonPauseResume.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_pause, 0, 0, 0);
            tvPreviewPlaceholder.setVisibility(View.VISIBLE);
            Toast.makeText(getContext(), "Recording stopped", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (mediaRecorder != null) {
            mediaRecorder.release();
            mediaRecorder = null;
        }
    }
}