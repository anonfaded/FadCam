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
import android.os.StatFs;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
    private SharedPreferences sharedPreferences;

    private static final String PREF_VIDEO_QUALITY = "video_quality";
    private static final String QUALITY_SD = "SD";
    private static final String QUALITY_HD = "HD";
    private static final String QUALITY_FHD = "FHD";

    private TextView tvStorageInfo;
    private TextView tvPreviewPlaceholder;
    private Button buttonStartStop;
    private Button buttonPauseResume;
    private boolean isPaused = false;

    private TextView tvTip;
    private String[] tips = {
            "Ensure good lighting for better video quality",
            "Use a tripod or stable surface for steady footage",
            "Clean your camera lens before recording",
            "Frame your shot before hitting record",
            "Consider using an external microphone for better audio"
    };
    private int currentTipIndex = 0;

    private TextView tvStats;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        textureView = view.findViewById(R.id.textureView);
        tvStorageInfo = view.findViewById(R.id.tvStorageInfo);
        tvPreviewPlaceholder = view.findViewById(R.id.tvPreviewPlaceholder);
        buttonStartStop = view.findViewById(R.id.buttonStartStop);
        buttonPauseResume = view.findViewById(R.id.buttonPauseResume);
        tvTip = view.findViewById(R.id.tvTip);
        tvStats = view.findViewById(R.id.tvStats);

        sharedPreferences = requireActivity().getPreferences(Context.MODE_PRIVATE);

        updateStorageInfo();
        updateTip();
        updateStats();

        buttonStartStop.setOnClickListener(v -> {
            if (!isRecording) {
                startRecording();
            } else {
                stopRecording();
                updateStats();
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
    }

    private void updateStorageInfo() {
        StatFs stat = new StatFs(Environment.getExternalStorageDirectory().getPath());
        long bytesAvailable = stat.getAvailableBytes();
        long bytesTotal = stat.getTotalBytes();

        double gbAvailable = bytesAvailable / (1024.0 * 1024.0 * 1024.0);
        double gbTotal = bytesTotal / (1024.0 * 1024.0 * 1024.0);

        long bitrate = 10 * 1024 * 1024;
        long recordingSeconds = (bytesAvailable * 8) / bitrate;
        long recordingHours = recordingSeconds / 3600;
        long recordingMinutes = (recordingSeconds % 3600) / 60;

        String storageInfo = String.format(Locale.getDefault(),
                "Available: \n  %.2f GB / %.2f GB\n\n" +
                        "Record time (est.): \n  %d h %d min",
                gbAvailable, gbTotal, recordingHours, recordingMinutes);
        tvStorageInfo.setText(storageInfo);
    }

    private void updateTip() {
        tvTip.setText(tips[currentTipIndex]);
        currentTipIndex = (currentTipIndex + 1) % tips.length;
        tvTip.postDelayed(this::updateTip, 6000); // Change tip every 6 seconds
    }

    private void updateStats() {
        File recordsDir = new File(getContext().getExternalFilesDir(null), "FadCam");
        int numVideos = 0;
        long totalSize = 0;

        if (recordsDir.exists()) {
            File[] files = recordsDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith(".mp4")) {
                        numVideos++;
                        totalSize += file.length();
                    }
                }
            }
        }

        String statsText = String.format(Locale.getDefault(),
                "Videos: %d%nUsed Space: %n%s",
                numVideos, Formatter.formatFileSize(getContext(), totalSize));
        tvStats.setText(statsText);
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
            textureView.setVisibility(View.VISIBLE);
        }
    }

    private String getCameraSelection() {
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
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void setupMediaRecorder() {
        try {
            File videoDir = new File(requireActivity().getExternalFilesDir(null), "FadCam");
            if (!videoDir.exists()) {
                videoDir.mkdirs();
            }
            String timestamp = new SimpleDateFormat("yyyyMMdd_hh_mm_ssa", Locale.getDefault()).format(new Date());
            File videoFile = new File(videoDir, "FADCAM_" + timestamp + ".mp4");

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setOutputFile(videoFile.getAbsolutePath());

            String selectedQuality = sharedPreferences.getString(PREF_VIDEO_QUALITY, QUALITY_HD);
            switch (selectedQuality) {
                case QUALITY_SD:
                    mediaRecorder.setVideoSize(640, 480);
                    mediaRecorder.setVideoEncodingBitRate(1000000); // 1 Mbps
                    mediaRecorder.setVideoFrameRate(30);
                    break;
                case QUALITY_HD:
                    mediaRecorder.setVideoSize(1280, 720);
                    mediaRecorder.setVideoEncodingBitRate(5000000); // 5 Mbps
                    mediaRecorder.setVideoFrameRate(30);
                    break;
                case QUALITY_FHD:
                    mediaRecorder.setVideoSize(1920, 1080);
                    mediaRecorder.setVideoEncodingBitRate(10000000); // 10 Mbps
                    mediaRecorder.setVideoFrameRate(30);
                    break;
                default:
                    mediaRecorder.setVideoSize(1280, 720);
                    mediaRecorder.setVideoEncodingBitRate(5000000); // 5 Mbps
                    mediaRecorder.setVideoFrameRate(30);
                    break;
            }

            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.prepare();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void stopRecording() {
        if (isRecording) {
            try {
                cameraCaptureSession.stopRepeating();
                cameraCaptureSession.abortCaptures();
                mediaRecorder.stop();
                mediaRecorder.reset();
                Toast.makeText(getContext(), "Recording stopped", Toast.LENGTH_SHORT).show();
            } catch (CameraAccessException | IllegalStateException e) {
                e.printStackTrace();
            }
            releaseCamera();
            isRecording = false;
            buttonStartStop.setText("Start");
            buttonStartStop.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_play, 0, 0, 0);
            buttonPauseResume.setEnabled(false);
            tvPreviewPlaceholder.setVisibility(View.VISIBLE);
            textureView.setVisibility(View.INVISIBLE);
        }
    }

    private void releaseCamera() {
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (mediaRecorder != null) {
            mediaRecorder.release();
            mediaRecorder = null;
        }
        cameraCaptureSession = null;
        captureRequestBuilder = null;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        releaseCamera();
    }
}
