package com.fadcam.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.text.Html;
import android.text.Spanned;
import android.text.format.Formatter;
import android.util.Log;
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
import android.os.SystemClock;

import java.time.format.DateTimeFormatter;
import java.time.chrono.HijrahChronology;
import java.time.chrono.HijrahDate;


public class HomeFragment extends Fragment {

    private static final String TAG = "HomeFragment";

    private long recordingStartTime;
    private long videoBitrate;

    private Handler handler = new Handler();
    private Runnable updateInfoRunnable;
    private Runnable updateClockRunnable; // Declare here

    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private MediaRecorder mediaRecorder;
    private boolean isRecording = false;
    private TextureView textureView;
    private SharedPreferences sharedPreferences;

    private Handler tipHandler = new Handler();
    private int typingIndex = 0;
    private boolean isTypingIn = true;
    private String currentTip = "";

    private static final String PREF_VIDEO_QUALITY = "video_quality";
    private static final String QUALITY_SD = "SD";
    private static final String QUALITY_HD = "HD";
    private static final String QUALITY_FHD = "FHD";

    private TextView tvStorageInfo;
    private TextView tvPreviewPlaceholder;
    private Button buttonStartStop;
    private Button buttonPauseResume;
    private boolean isPaused = false;
    private boolean isPreviewEnabled = true;

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
    private TextView tvClock;
    private TextView tvDateEnglish;
    private TextView tvDateArabic;

    private void setupLongPressListener() {
        textureView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (isRecording) {
                    isPreviewEnabled = !isPreviewEnabled;
                    updatePreviewVisibility();
                    String message = isPreviewEnabled ? "Preview enabled" : "Preview disabled";
                    Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                    return true;
                }
                return false;
            }
        });
    }

    private void updatePreviewVisibility() {
        if (cameraCaptureSession != null && captureRequestBuilder != null && textureView.isAvailable()) {
            try {
                SurfaceTexture texture = textureView.getSurfaceTexture();
                if (texture == null) {
                    Log.e(TAG, "updatePreviewVisibility: SurfaceTexture is null");
                    return;
                }

                Surface previewSurface = new Surface(texture);

                if (isPreviewEnabled) {
                    captureRequestBuilder.addTarget(previewSurface);
                    textureView.setVisibility(View.VISIBLE);
                } else {
                    captureRequestBuilder.removeTarget(previewSurface);
                    textureView.setVisibility(View.INVISIBLE);
                }

                cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);

                // Don't forget to close the Surface when we're done with it
                previewSurface.release();
            } catch (CameraAccessException e) {
                Log.e(TAG, "Error updating preview visibility", e);
            }
        } else {
            Log.e(TAG, "updatePreviewVisibility: Camera session or builder is null, or TextureView is not available");
        }
    }

    private void resetTimers() {
        recordingStartTime = SystemClock.elapsedRealtime();
        updateStorageInfo();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Log.d(TAG, "onCreateView: Inflating fragment_home layout");
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Log.d(TAG, "onViewCreated: Setting up UI components");

        textureView = view.findViewById(R.id.textureView);
        tvStorageInfo = view.findViewById(R.id.tvStorageInfo);
        tvPreviewPlaceholder = view.findViewById(R.id.tvPreviewPlaceholder);
        buttonStartStop = view.findViewById(R.id.buttonStartStop);
        buttonPauseResume = view.findViewById(R.id.buttonPauseResume);
        tvTip = view.findViewById(R.id.tvTip);
        tvStats = view.findViewById(R.id.tvStats);

        tvClock = view.findViewById(R.id.tvClock);
        tvDateEnglish = view.findViewById(R.id.tvDateEnglish);
        tvDateArabic = view.findViewById(R.id.tvDateArabic);

        sharedPreferences = requireActivity().getPreferences(Context.MODE_PRIVATE);

        resetTimers();
        updateStorageInfo();
        updateTip();
        updateStats();
        startUpdatingClock();

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
        updateTip(); // Start the tip animation
        startTipsAnimation();
        setupLongPressListener();
    }

    private void startUpdatingClock() {
        updateClockRunnable = new Runnable() {
            @Override
            public void run() {
                updateClock();
                handler.postDelayed(this, 1000); // Update every second
            }
        };
        handler.post(updateClockRunnable);
    }
    // Method to stop updating the clock
    private void stopUpdatingClock() {
        if (updateClockRunnable != null) {
            handler.removeCallbacks(updateClockRunnable);
            updateClockRunnable = null;
        }
    }

    // Method to update the clock and dates
    private void updateClock() {
        // Update the time in HH:mm AM/PM format
        SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mm a", Locale.getDefault());
        String currentTime = timeFormat.format(new Date());
        tvClock.setText(currentTime);

        // Update the date in English
        SimpleDateFormat dateFormatEnglish = new SimpleDateFormat("EEE, MMM d", Locale.getDefault());
        String currentDateEnglish = dateFormatEnglish.format(new Date());
        tvDateEnglish.setText(currentDateEnglish);

        // Update the date in Arabic (Islamic calendar)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            HijrahDate hijrahDate = HijrahChronology.INSTANCE.dateNow();
            DateTimeFormatter dateFormatterArabic = DateTimeFormatter.ofPattern("d MMMM yyyy", new Locale("ar"));
            String currentDateArabic = dateFormatterArabic.format(hijrahDate);
            tvDateArabic.setText(currentDateArabic);
        } else {
            tvDateArabic.setText("N/A"); // Placeholder for devices below API 26
        }
    }


    private void updateStorageInfo() {
        Log.d(TAG, "updateStorageInfo: Updating storage information");
        StatFs stat = new StatFs(Environment.getExternalStorageDirectory().getPath());
        long bytesAvailable = stat.getAvailableBytes();
        long bytesTotal = stat.getTotalBytes();

        double gbAvailable = bytesAvailable / (1024.0 * 1024.0 * 1024.0);
        double gbTotal = bytesTotal / (1024.0 * 1024.0 * 1024.0);

        long elapsedTime = SystemClock.elapsedRealtime() - recordingStartTime;
        long estimatedBytesUsed = (elapsedTime * videoBitrate) / 8000; // Convert ms and bits to bytes

        // Update available space based on estimated bytes used
        bytesAvailable -= estimatedBytesUsed;
        gbAvailable = Math.max(0, bytesAvailable / (1024.0 * 1024.0 * 1024.0));

        long remainingTime = (videoBitrate > 0) ? (bytesAvailable * 8) / videoBitrate : 0;

        String storageInfo = String.format(Locale.getDefault(),
                "<font color='#FFFFFF' style='font-size:16sp;'><b>Available:</b></font><br>" +
                        "<font color='#CCCCCC' style='font-size:14sp;'>%.2f GB / %.2f GB</font><br><br>" +
                        "<font color='#FFFFFF' style='font-size:16sp;'><b>Record time (est.):</b></font><br>" +
                        "<font color='#CCCCCC' style='font-size:14sp;'>FHD: %s<br>HD: %s<br>SD: %s</font><br><br>" +
                        "<font color='#FFFFFF' style='font-size:16sp;'><b>Elapsed time:</b></font><br>" +
                        "<font color='#77DD77' style='font-size:14sp;'>%02d:%02d</font><br>" +
                        "<font color='#FFFFFF' style='font-size:16sp;'><b>Remaining time:</b></font><br>" +
                        "<font color='#E43C3C' style='font-size:14sp;'>%02d:%02d</font>",
                gbAvailable, gbTotal,
                getRecordingTimeEstimate(bytesAvailable, 10 * 1024 * 1024),
                getRecordingTimeEstimate(bytesAvailable, 5 * 1024 * 1024),
                getRecordingTimeEstimate(bytesAvailable, 1024 * 1024),
                elapsedTime / 60000, (elapsedTime / 1000) % 60,
                remainingTime / 60, remainingTime % 60
        );

        Spanned formattedText = Html.fromHtml(storageInfo, Html.FROM_HTML_MODE_LEGACY);

        // Update UI on the main thread
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                tvStorageInfo.setText(formattedText);
            });
        }
    }

    private String getRecordingTimeEstimate(long availableBytes, long bitrate) {
        long recordingSeconds = (availableBytes * 8) / bitrate;
        long recordingHours = recordingSeconds / 3600;
        long recordingMinutes = (recordingSeconds % 3600) / 60;
        return String.format(Locale.getDefault(), "%d h %d min", recordingHours, recordingMinutes);
    }

    private void startUpdatingInfo() {
        Log.d(TAG, "startUpdatingInfo: Beginning real-time updates");
        updateInfoRunnable = new Runnable() {
            @Override
            public void run() {
                if (isRecording) {
                    updateStorageInfo();
                    handler.postDelayed(this, 3000); // Update every 3 seconds
                }
            }
        };
        handler.post(updateInfoRunnable);
    }

    private void stopUpdatingInfo() {
        Log.d(TAG, "stopUpdatingInfo: Stopping real-time updates");
        if (updateInfoRunnable != null) {
            handler.removeCallbacks(updateInfoRunnable);
            updateInfoRunnable = null;
        }
    }

    private void startTipsAnimation() {
        if (tips.length > 0) {
            animateTip(tips[currentTipIndex], tvTip, 100); // Adjust delay as needed
        }
    }
    private void updateTip() {
        currentTip = tips[currentTipIndex];
        typingIndex = 0;
        isTypingIn = true;
//        animateTip(); this line is giving errors so i commented it
    }
    private void animateTip(String fullText, TextView textView, int delay) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final int[] index = {0};

        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                if (index[0] <= fullText.length()) {
                    textView.setText(fullText.substring(0, index[0]));
                    index[0]++;
                    handler.postDelayed(this, 40); // add delay in typing the tips
                } else {
                    currentTipIndex = (currentTipIndex + 1) % tips.length;
                    handler.postDelayed(() -> animateTip(tips[currentTipIndex], textView, delay), 5000); // Wait 2 seconds before next tip
                }
            }
        };

        handler.post(runnable);
    }


    private void updateStats() {
        Log.d(TAG, "updateStats: Updating video statistics");
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
                "<font color='#FFFFFF' style='font-size:12sp;'><b>Videos: </b></font>" +
                        "<font color='#D3D3D3' style='font-size:11sp;'>%d</font><br>" +
                        "<font color='#FFFFFF' style='font-size:12sp;'><b>Used Space:</b></font><br>" +
                        "<font color='#D3D3D3' style='font-size:11sp;'>%s</font>",
                numVideos, Formatter.formatFileSize(getContext(), totalSize));

        tvStats.setText(Html.fromHtml(statsText, Html.FROM_HTML_MODE_LEGACY));
    }

    private void pauseRecording() {
        Log.d(TAG, "pauseRecording: Pausing video recording");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mediaRecorder.pause();
            isPaused = true;
            buttonPauseResume.setText("Resume");
            buttonPauseResume.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_play, 0, 0, 0);
        }
    }

    private void resumeRecording() {
        Log.d(TAG, "resumeRecording: Resuming video recording");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mediaRecorder.resume();
            isPaused = false;
            buttonPauseResume.setText("Pause");
            buttonPauseResume.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_pause, 0, 0, 0);
        }
    }

    private void startRecording() {
        Log.d(TAG, "startRecording: Initiating video recording");
        if (!isRecording) {
            resetTimers();
            if (cameraDevice == null) {
                openCamera();
            } else {
                startRecordingVideo();
            }
            recordingStartTime = SystemClock.elapsedRealtime();
            setVideoBitrate();

            buttonStartStop.setText("Stop");
            buttonStartStop.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_stop, 0, 0, 0);
            buttonPauseResume.setEnabled(true);
            tvPreviewPlaceholder.setVisibility(View.GONE);
            textureView.setVisibility(View.VISIBLE);

            startUpdatingInfo();
            isRecording = true;
        }
    }

    private void setVideoBitrate() {
        String selectedQuality = sharedPreferences.getString(PREF_VIDEO_QUALITY, QUALITY_HD);
        switch (selectedQuality) {
            case QUALITY_SD:
                videoBitrate = 1000000; // 1 Mbps
                break;
            case QUALITY_HD:
                videoBitrate = 5000000; // 5 Mbps
                break;
            case QUALITY_FHD:
                videoBitrate = 10000000; // 10 Mbps
                break;
            default:
                videoBitrate = 5000000; // Default to HD
                break;
        }
        Log.d(TAG, "setVideoBitrate: Set to " + videoBitrate + " bps");
    }

    private String getCameraSelection() {
        return sharedPreferences.getString("camera_selection", "back");
    }

    private void openCamera() {
        Log.d(TAG, "openCamera: Opening camera");
        CameraManager manager = (CameraManager) getActivity().getSystemService(Context.CAMERA_SERVICE);
        try {
            String[] cameraIdList = manager.getCameraIdList();
            String cameraId = getCameraSelection().equals("front") ? cameraIdList[1] : cameraIdList[0];
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    Log.d(TAG, "onOpened: Camera opened successfully");
                    cameraDevice = camera;
                    startRecordingVideo();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    Log.w(TAG, "onDisconnected: Camera disconnected");
                    cameraDevice.close();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "onError: Camera error: " + error);
                    cameraDevice.close();
                    cameraDevice = null;
                }
            }, null);
        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "openCamera: Error opening camera", e);
            e.printStackTrace();
        }
    }

    private void startRecordingVideo() {
        Log.d(TAG, "startRecordingVideo: Setting up video recording preview area");
        if (null == cameraDevice || !textureView.isAvailable() || !Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            Log.e(TAG, "startRecordingVideo: Unable to start recording due to missing prerequisites");
            return;
        }
        try {
            setupMediaRecorder();
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(720, 1080);
            Surface previewSurface = new Surface(texture);
            Surface recorderSurface = mediaRecorder.getSurface();
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
// below line of code is to show the preview screen.
//            captureRequestBuilder.addTarget(previewSurface);
            if (isPreviewEnabled) {
                captureRequestBuilder.addTarget(previewSurface);
            }
            captureRequestBuilder.addTarget(recorderSurface);

            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, recorderSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            Log.d(TAG, "onConfigured: Camera capture session configured");
                            HomeFragment.this.cameraCaptureSession = cameraCaptureSession;
                            try {
                                cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "onConfigured: Error setting repeating request", e);
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
                            Log.e(TAG, "onConfigureFailed: Failed to configure camera capture session");
                            Toast.makeText(getContext(), "Failed to start recording", Toast.LENGTH_SHORT).show();
                        }
                    }, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "startRecordingVideo: Camera access exception", e);
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
            mediaRecorder.setOrientationHint(90);
            mediaRecorder.prepare();
        } catch (IOException e) {
            Log.e(TAG, "setupMediaRecorder: Error setting up media recorder", e);
            e.printStackTrace();
        }
    }

    private void stopRecording() {
        Log.d(TAG, "stopRecording: Stopping video recording");
        if (isRecording) {
            try {
                cameraCaptureSession.stopRepeating();
                cameraCaptureSession.abortCaptures();
                mediaRecorder.stop();
                mediaRecorder.reset();
                Toast.makeText(getContext(), "Recording stopped", Toast.LENGTH_SHORT).show();
            } catch (CameraAccessException | IllegalStateException e) {
                Log.e(TAG, "stopRecording: Error stopping recording", e);
                e.printStackTrace();
            }
            releaseCamera();
            isRecording = false;
            buttonStartStop.setText("Start");
            buttonStartStop.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_play, 0, 0, 0);
            buttonPauseResume.setEnabled(false);
            tvPreviewPlaceholder.setVisibility(View.VISIBLE);
            textureView.setVisibility(View.INVISIBLE);
            stopUpdatingInfo();
            updateStorageInfo(); // Final update with actual values
        }
        isPreviewEnabled = true;
    }

    private void releaseCamera() {
        Log.d(TAG, "releaseCamera: Releasing camera resources");
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
        Log.d(TAG, "onDestroyView: Cleaning up resources");
        stopUpdatingInfo();
        releaseCamera();
    }
}