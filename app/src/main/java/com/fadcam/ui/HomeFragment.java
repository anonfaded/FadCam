package com.fadcam.ui;

// Android imports
import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.Html;
import android.text.Spanned;
import android.text.format.Formatter;
import android.util.Log;
import android.util.Range;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

// AndroidX imports
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

// Material Components imports
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

// FFmpegKit imports
import com.arthenica.ffmpegkit.ExecuteCallback;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.Session;

// Your app imports
import com.fadcam.Constantes;
import com.fadcam.R;
import com.fadcam.RecordingService;

// Java imports
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.time.chrono.HijrahChronology;
import java.time.chrono.HijrahDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;

public class HomeFragment extends Fragment {

    private static final String TAG = "HomeFragment";
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 100;
    private LocationHelper locationHelper;

    private long recordingStartTime;
    private long videoBitrate;

    private ExecutorService executorService = Executors.newSingleThreadExecutor();

    private RecordsAdapter adapter;

    static final String PREF_LOCATION_DATA = "location_data";

    private double latitude;
    private double longitude;

    private File tempFileBeingProcessed;

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

    private static final String QUALITY_SD = "SD";
    private static final String QUALITY_HD = "HD";
    private static final String QUALITY_FHD = "FHD";

    private TextView tvStorageInfo;
    private TextView tvPreviewPlaceholder;
    private Button buttonStartStop;
    private Button buttonPauseResume;
    private Button buttonCamSwitch;
    private boolean isPaused = false;
    private boolean isPreviewEnabled = true;

    private View cardPreview;
    private Vibrator vibrator;

    private CardView cardClock;
    private TextView tvClock, tvDateEnglish, tvDateArabic;
    private CameraManager cameraManager;
    private String cameraId;

    //FragmentActivity tipres = requireActivity();
    private TextView tvTip;
    private String[] tips;

    private int currentTipIndex = 0;

    private TextView tvStats;

    private List<String> messageQueue;
    private List<String> recentlyShownMessages;
    private Random random = new Random();
    private static final int RECENT_MESSAGE_LIMIT = 3; // Adjust as needed

    private static final int REQUEST_PERMISSIONS = 1;
    private android.os.PowerManager.WakeLock wakeLock;  // Full path for clarity

    // Important
    private void requestEssentialPermissions() {
        Log.d(TAG, "requestEssentialPermissions: Requesting essential permissions");
        String[] permissions;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // Android 11 and above
            permissions = new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.POST_NOTIFICATIONS
            };
        } else { // Below Android 11
            permissions = new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
        }

        List<String> permissionsToRequest = new ArrayList<>();
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(requireContext(), permission) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "requestEssentialPermissions: Requesting permission: " + permission);
                permissionsToRequest.add(permission);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(requireActivity(), permissionsToRequest.toArray(new String[0]), REQUEST_PERMISSIONS);
        }

        // Request to disable battery optimization
        requestBatteryOptimizationPermission();

        // Acquire wake lock when recording starts (no need to request runtime permission)
    }

    // Method to request disabling battery optimization
    private void requestBatteryOptimizationPermission() {
        android.os.PowerManager powerManager = (android.os.PowerManager) requireActivity().getSystemService(Context.POWER_SERVICE); // Full path and context adjusted
        String packageName = requireActivity().getPackageName(); // Correct package retrieval

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + packageName));
            startActivity(intent);
        }
    }

    // Call this method when the recording starts to acquire wake lock
    private void acquireWakeLock() {
        android.os.PowerManager powerManager = (android.os.PowerManager) requireActivity().getSystemService(Context.POWER_SERVICE); // Full path and context adjusted
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::RecordingLock");

        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
            Log.d(TAG, "WakeLock acquired.");
        }
    }

    // Call this when the recording ends to release wake lock
    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "WakeLock released.");
        }
    }

    private void initializeMessages() {
        messageQueue = new ArrayList<>(Arrays.asList(getResources().getStringArray(R.array.easter_eggs_array)));
        recentlyShownMessages = new ArrayList<>();
        Collections.shuffle(messageQueue); // Shuffle the list initially
    }

    private void showRandomMessage() {
        if (messageQueue == null || messageQueue.isEmpty()) {
            initializeMessages(); // Reinitialize and shuffle if queue is empty or null
        }

        // Remove recently shown messages from the queue
        messageQueue.removeAll(recentlyShownMessages);

        // Ensure there are still messages to choose from
        if (!messageQueue.isEmpty()) {
            String randomMessage = messageQueue.remove(random.nextInt(messageQueue.size()));
            tvPreviewPlaceholder.setText(randomMessage);

            // Track recently shown messages
            recentlyShownMessages.add(randomMessage);
            if (recentlyShownMessages.size() > RECENT_MESSAGE_LIMIT) {
                recentlyShownMessages.remove(0); // Remove the oldest message
            }

            // Shuffle the list again
            Collections.shuffle(messageQueue);
        } else {
            // Fallback message if no messages are available
            tvPreviewPlaceholder.setText("Oops! No messages available right now.");
        }
    }

    private void setupLongPressListener() {
        cardPreview.setOnLongClickListener(v -> {
            if (isRecording) {
                // Start scaling down animation
                cardPreview.animate()
                        .scaleX(0.9f)
                        .scaleY(0.9f)
                        .setDuration(100) // Reduced duration for quicker scale-down
                        .start();

                // Perform haptic feedback
                performHapticFeedback();

                // Execute the task immediately
                isPreviewEnabled = !isPreviewEnabled;
                updatePreviewVisibility();
                savePreviewState();
                String message = isPreviewEnabled ? "Preview enabled" : "Preview disabled";
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();

                // Scale back up quickly with a wobble effect
                cardPreview.postDelayed(() -> {
                    cardPreview.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(50) // Shorter duration for quicker scale-up
                            .start();
                }, 60); // No Delay to ensure it happens after the initial scaling down

            } else {
                // Handling when recording is not active

                // Show random funny message
                showRandomMessage();

                // Ensure the placeholder is visible
                tvPreviewPlaceholder.setVisibility(View.VISIBLE);
                tvPreviewPlaceholder.setPadding(16, tvPreviewPlaceholder.getPaddingTop(), 16, tvPreviewPlaceholder.getPaddingBottom());
                performHapticFeedback();

                // Trigger the red blinking animation
                tvPreviewPlaceholder.setBackgroundColor(Color.RED);
                tvPreviewPlaceholder.postDelayed(() -> {
                    tvPreviewPlaceholder.setBackgroundColor(Color.TRANSPARENT);
                }, 100); // Blinking duration

                // Wobble animation
                ObjectAnimator scaleXUp = ObjectAnimator.ofFloat(tvPreviewPlaceholder, "scaleX", 1.1f);
                ObjectAnimator scaleYUp = ObjectAnimator.ofFloat(tvPreviewPlaceholder, "scaleY", 1.1f);
                ObjectAnimator scaleXDown = ObjectAnimator.ofFloat(tvPreviewPlaceholder, "scaleX", 1.0f);
                ObjectAnimator scaleYDown = ObjectAnimator.ofFloat(tvPreviewPlaceholder, "scaleY", 1.0f);

                scaleXUp.setDuration(50);
                scaleYUp.setDuration(50);
                scaleXDown.setDuration(50);
                scaleYDown.setDuration(50);

                AnimatorSet wobbleSet = new AnimatorSet();
                wobbleSet.play(scaleXUp).with(scaleYUp).before(scaleXDown).before(scaleYDown);
                wobbleSet.start();
            }
            return true;
        });
    }

    private void updatePreviewVisibility() {
        if (isRecording) {
            if (isPreviewEnabled) {
                textureView.setVisibility(View.VISIBLE);
                tvPreviewPlaceholder.setVisibility(View.GONE);
            } else {
                textureView.setVisibility(View.INVISIBLE);
                tvPreviewPlaceholder.setVisibility(View.VISIBLE);
                tvPreviewPlaceholder.setText("Long press to enable preview");
            }
        } else {
            textureView.setVisibility(View.INVISIBLE);
            tvPreviewPlaceholder.setVisibility(View.VISIBLE);
            tvPreviewPlaceholder.setText(getString(R.string.ui_preview_area));
        }

        updateCameraPreview();
    }

    private void updateCameraPreview() {
        if (cameraCaptureSession != null && captureRequestBuilder != null && textureView.isAvailable()) {
            try {
                SurfaceTexture texture = textureView.getSurfaceTexture();
                if (texture == null) {
                    Log.e(TAG, "updateCameraPreview: SurfaceTexture is null");
                    return;
                }

                Surface previewSurface = new Surface(texture);

                captureRequestBuilder.removeTarget(previewSurface);
                if (isPreviewEnabled && isRecording) {
                    captureRequestBuilder.addTarget(previewSurface);
                }

                cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
            } catch (CameraAccessException e) {
                Log.e(TAG, "Error updating camera preview", e);
            }
        }
    }

    private void resetTimers() {
        recordingStartTime = SystemClock.elapsedRealtime();
        updateStorageInfo();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sharedPreferences = requireActivity().getPreferences(Context.MODE_PRIVATE);
        locationHelper = new LocationHelper(requireContext());
        cameraManager = (CameraManager) getActivity().getSystemService(Context.CAMERA_SERVICE);
        Log.d(TAG, "HomeFragment created.");

        // Request essential permissions on every launch
        requestEssentialPermissions();
    }

    @Override
    public void onStart() {
        super.onStart();
        //fetch Camera status
        String currentCameraSelection = sharedPreferences.getString(Constantes.PREF_CAMERA_SELECTION, Constantes.CAMERA_BACK);
        Toast.makeText(getContext(), this.getString(R.string.current_camera) + ": " + currentCameraSelection, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onResume() {
        super.onResume();
        locationHelper.startLocationUpdates();

        Log.d(TAG, "HomeFragment resumed.");

        IntentFilter filter = new IntentFilter("RECORDING_STATE_CHANGED");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getActivity().registerReceiver(recordingStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        }

        setupStartStopButton();

        updateStats();
    }

    @Override
    public void onPause() {
        super.onPause();
        locationHelper.stopLocationUpdates();
        Log.d(TAG, "HomeFragment paused.");

        getActivity().unregisterReceiver(recordingStateReceiver);
    }

    private String getLocationData() {
        return locationHelper.getLocationData();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Log.d(TAG, "onCreateView: Inflating fragment_home layout");
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    private void performHapticFeedback() {
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(50);
            }
        }
    }

    private void savePreviewState() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean("isPreviewEnabled", isPreviewEnabled);
        editor.apply();
    }

    // Function to use haptic feedbacks
    private void vibrateTouch() {
        // Haptic Feedback
        Vibrator vibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            VibrationEffect effect = null;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(effect);
            }
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        tips = requireActivity().getResources().getStringArray(R.array.tips_widget);
        super.onViewCreated(view, savedInstanceState);
        Log.d(TAG, "onViewCreated: Setting up UI components");

        textureView = view.findViewById(R.id.textureView);
        tvStorageInfo = view.findViewById(R.id.tvStorageInfo);
        tvPreviewPlaceholder = view.findViewById(R.id.tvPreviewPlaceholder);
        buttonStartStop = view.findViewById(R.id.buttonStartStop);
        buttonPauseResume = view.findViewById(R.id.buttonPauseResume);
        buttonCamSwitch = view.findViewById(R.id.buttonCamSwitch);
        tvTip = view.findViewById(R.id.tvTip);
        tvStats = view.findViewById(R.id.tvStats);

        // Initialize views
        cardClock = view.findViewById(R.id.cardClock);
        tvClock = view.findViewById(R.id.tvClock);
        tvDateEnglish = view.findViewById(R.id.tvDateEnglish);
        tvDateArabic = view.findViewById(R.id.tvDateArabic);

        // Set up long press listener for clock widget
        setupClockLongPressListener();

        cardPreview = view.findViewById(R.id.cardPreview);
        vibrator = (Vibrator) requireContext().getSystemService(Context.VIBRATOR_SERVICE);

        sharedPreferences = requireActivity().getPreferences(Context.MODE_PRIVATE);
        isPreviewEnabled = sharedPreferences.getBoolean("isPreviewEnabled", true);

        resetTimers();

        // Move font copying to a background thread
        executorService.execute(() -> {
            copyFontToInternalStorage();
        });

        updateStorageInfo();
        updateTip();
        updateStats();
        startUpdatingClock();

        // Update clock and date initially
        updateClock();

        updateTip(); // Start the tip animation
        startTipsAnimation();
        setupButtonListeners();
        setupLongPressListener();
        updatePreviewVisibility();
    }

    private boolean areEssentialPermissionsGranted() {
        boolean cameraGranted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean recordAudioGranted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

        boolean storageGranted;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // Android 11 and above
            storageGranted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        } else { // Below Android 11
            storageGranted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }

        return cameraGranted && recordAudioGranted && storageGranted;
    }

    private void showPermissionsInfoDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Required Permissions")
                .setMessage("This app needs camera, microphone, and storage permissions to function properly. Please enable these permissions from the app settings.")
                .setPositiveButton("OK", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void debugPermissionsStatus() {
        Log.d(TAG, "Camera permission: " +
                (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED ? "Granted" : "Denied"));
        Log.d(TAG, "Record audio permission: " +
                (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ? "Granted" : "Denied"));
        Log.d(TAG, "External storage permission: " +
                (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ? "Granted" : "Denied"));
    }

    private void setupButtonListeners() {
        buttonStartStop.setOnClickListener(v -> {
            debugPermissionsStatus();
            if (!areEssentialPermissionsGranted()) {
                debugPermissionsStatus();
                showPermissionsInfoDialog();
            } else {
                if (!isRecording) {
                    startRecording();
                } else {
                    stopRecording();
                    updateStats();
                }
            }
        });

        buttonPauseResume.setOnClickListener(v -> {
            if (isRecording) {
                if (isPaused) {
                    vibrateTouch();
                    Toast.makeText(getContext(), R.string.video_recording_resumed, Toast.LENGTH_SHORT).show();
                    resumeRecording();
                } else {
                    vibrateTouch();
                    Toast.makeText(getContext(), R.string.video_recording_paused, Toast.LENGTH_SHORT).show();
                    pauseRecording();
                }
            }
        });

        buttonCamSwitch.setOnClickListener(v -> {
            switchCamera();
        });
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

    private void setupClockLongPressListener() {
        cardClock.setOnLongClickListener(v -> {
            addWobbleAnimation(); // This will perform the wobble animation
            showDisplayOptionsDialog(); // This will show the dialog to choose display options
            return true; // Indicate the long press was handled
        });
    }

    private void addWobbleAnimation() {
        // Define the scale down and scale up values
        float scaleDown = 0.9f;
        float scaleUp = 1.0f;

        // Create animations for scaling down and scaling up
        AnimatorSet scaleDownSet = new AnimatorSet();
        scaleDownSet.playTogether(
                ObjectAnimator.ofFloat(cardClock, "scaleX", scaleDown),
                ObjectAnimator.ofFloat(cardClock, "scaleY", scaleDown)
        );
        scaleDownSet.setDuration(50); // Duration for scale down

        AnimatorSet scaleUpSet = new AnimatorSet();
        scaleUpSet.playTogether(
                ObjectAnimator.ofFloat(cardClock, "scaleX", scaleUp),
                ObjectAnimator.ofFloat(cardClock, "scaleY", scaleUp)
        );
        scaleUpSet.setDuration(70); // Duration for scale up

        AnimatorSet scaleBackSet = new AnimatorSet();
        scaleBackSet.playTogether(
                ObjectAnimator.ofFloat(cardClock, "scaleX", 1.0f),
                ObjectAnimator.ofFloat(cardClock, "scaleY", 1.0f)
        );
        scaleBackSet.setDuration(70); // Duration to snap back to original size

        // Start the animation sequence
        scaleDownSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // Start scaling up animation after scaling down
                scaleUpSet.start();
                performHapticFeedback();
            }
        });

        scaleUpSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // Start scaling back to original size after scaling up
                scaleBackSet.start();
            }
        });

        // Start the sequence with scaling down
        scaleDownSet.start();
    }

    private void showDisplayOptionsDialog() {
        new MaterialAlertDialogBuilder(getContext())
                .setTitle(getString(R.string.dialog_clock_title))
                .setSingleChoiceItems(new String[]{
                        getString(R.string.dialog_clock_timeonly),
                        getString(R.string.dialog_clock_englishtime),
                        getString(R.string.dialog_clock_Islamic_calendar)
                }, getCurrentDisplayOption(), (dialog, which) -> {
                    saveDisplayOption(which);
                    updateClock(); // Update the widget based on the selected option
                    dialog.dismiss();
                })
                .setPositiveButton("OK", null)
                .show();
    }

    private int getCurrentDisplayOption() {
        SharedPreferences prefs = getActivity().getSharedPreferences("AppPreferences", Context.MODE_PRIVATE);
        return prefs.getInt("display_option", 2); // Default to "Everything"
    }

    private void saveDisplayOption(int option) {
        SharedPreferences prefs = getActivity().getSharedPreferences("AppPreferences", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt("display_option", option);
        editor.apply();
    }

    private void showOptionsAndAnimate() {
        addWobbleAnimation();
        showDisplayOptionsDialog();
    }

    // Method to update the clock and dates
    private void updateClock() {
        SharedPreferences prefs = getActivity().getSharedPreferences("AppPreferences", Context.MODE_PRIVATE);
        int displayOption = prefs.getInt("display_option", 2); // Default to "Everything"

        // Update the time
        SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mm a", Locale.getDefault());
        String currentTime = timeFormat.format(new Date());
        tvClock.setText(currentTime);

        // Update the date in English
        SimpleDateFormat dateFormatEnglish = new SimpleDateFormat("EEE, MMM d", Locale.getDefault());
        String currentDateEnglish = dateFormatEnglish.format(new Date());

        // Update the date in Arabic (Islamic calendar)
        String currentDateArabic = "N/A";
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            HijrahDate hijrahDate = HijrahChronology.INSTANCE.dateNow();
            DateTimeFormatter dateFormatterArabic = DateTimeFormatter.ofPattern("d MMMM yyyy", new Locale("ar"));
            currentDateArabic = dateFormatterArabic.format(hijrahDate);
        }

        // Set text visibility based on user choice
        tvDateEnglish.setText(displayOption == 1 || displayOption == 2 ? currentDateEnglish : "");
        tvDateEnglish.setPadding(5, tvPreviewPlaceholder.getPaddingTop(), 5, tvPreviewPlaceholder.getPaddingBottom());

        tvDateArabic.setText(displayOption == 2 ? currentDateArabic : "");
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

        // Calculate remaining recording time based on available space and bitrate
        long remainingTime = (videoBitrate > 0) ? (bytesAvailable * 8) / videoBitrate * 2 : 0; // Double the remaining time
        // Calculate days, hours, minutes, and seconds for remaining time
        long days = remainingTime / (24 * 3600);
        long hours = (remainingTime % (24 * 3600)) / 3600;
        long minutes = (remainingTime % 3600) / 60;
        long seconds = remainingTime % 60;

        String storageInfo = String.format(Locale.getDefault(),
                getString(R.string.mainpage_storage_indicator),
                gbAvailable, gbTotal,
                getRecordingTimeEstimate(bytesAvailable, (10 * 1024 * 1024) / 2), // 50% of 10 Mbps
                getRecordingTimeEstimate(bytesAvailable, (5 * 1024 * 1024) / 2),  // 50% of 5 Mbps
                getRecordingTimeEstimate(bytesAvailable, (1024 * 1024) / 2),      // 50% of 1 Mbps
                elapsedTime / 60000, (elapsedTime / 1000) % 60,
                formatRemainingTime(days, hours, minutes, seconds)
        );

        Spanned formattedText = Html.fromHtml(storageInfo, Html.FROM_HTML_MODE_LEGACY);

        // Update UI on the main thread
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                tvStorageInfo.setText(formattedText);
            });
        }
    }

    private String formatRemainingTime(long days, long hours, long minutes, long seconds) {
        StringBuilder remainingTime = new StringBuilder();
        if (days > 0) {
            remainingTime.append(String.format(Locale.getDefault(), "<font color='#E43C3C'>%d</font><font color='#CCCCCC'> days</font> ", days));
        }
        if (hours > 0) {
            remainingTime.append(String.format(Locale.getDefault(), "<font color='#E43C3C'>%d</font><font color='#CCCCCC'>h</font> ", hours));
        }
        if (minutes > 0) {
            remainingTime.append(String.format(Locale.getDefault(), "<font color='#E43C3C'>%d</font><font color='#CCCCCC'>m</font> ", minutes));
        }
        if (seconds > 0 || remainingTime.length() == 0) {
            remainingTime.append(String.format(Locale.getDefault(), "<font color='#E43C3C'>%d</font><font color='#CCCCCC'>s</font>", seconds));
        }
        return remainingTime.toString();
    }

    private String getRecordingTimeEstimate(long availableBytes, long bitrate) {
        long recordingSeconds = (availableBytes * 8) / bitrate;
        long recordingHours = recordingSeconds / 3600;
        long recordingMinutes = (recordingSeconds % 3600) / 60;
        return String.format(Locale.getDefault(), "%d h %d min", recordingHours, recordingMinutes);
    }

    // Update storage and stats in real time while recording is started
    private void startUpdatingInfo() {
        Log.d(TAG, "startUpdatingInfo: Beginning real-time updates");
        updateInfoRunnable = new Runnable() {
            @Override
            public void run() {
                if (isRecording) {
                    updateStorageInfo();
                    updateStats();
                    handler.postDelayed(this, 1000); // Update every second
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
        // Removed the commented out line
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
                    handler.postDelayed(() -> animateTip(tips[currentTipIndex], textView, delay), 5000); // Wait 5 seconds before next tip
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
                getString(R.string.mainpage_video_info),
                numVideos, Formatter.formatFileSize(getContext(), totalSize));

        tvStats.setText(Html.fromHtml(statsText, Html.FROM_HTML_MODE_LEGACY));
    }

    private void pauseRecording() {
        Log.d(TAG, "pauseRecording: Pausing video recording");
        // Pause is handled in the RecordingService, not in the Fragment
        // Therefore, you could implement additional communication if you want to pause both cameras
        // For simplicity, this method only shows a Toast
        Toast.makeText(getContext(), "Pause functionality not implemented.", Toast.LENGTH_SHORT).show();
    }

    private void resumeRecording() {
        Log.d(TAG, "resumeRecording: Resuming video recording");
        // Similar to pauseRecording, handle in the RecordingService if necessary
        Toast.makeText(getContext(), "Resume functionality not implemented.", Toast.LENGTH_SHORT).show();
    }

    private BroadcastReceiver recordingStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("RECORDING_STATE_CHANGED".equals(intent.getAction())) {
                boolean recordingState = intent.getBooleanExtra("isRecording", false);
                if (recordingState) {
                    buttonStartStop.setText(getString(R.string.button_stop));
                    buttonStartStop.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_stop, 0, 0, 0);
                    buttonPauseResume.setEnabled(true);
                    tvPreviewPlaceholder.setVisibility(View.GONE);
                    textureView.setVisibility(View.VISIBLE);
                    isRecording = true;
                } else {
                    buttonStartStop.setText(getString(R.string.button_start));
                    buttonStartStop.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_play, 0, 0, 0);
                    buttonPauseResume.setEnabled(false);
                    tvPreviewPlaceholder.setVisibility(View.VISIBLE);
                    textureView.setVisibility(View.GONE);
                    isRecording = false;
                }
            }
        }
    };

    private void startRecording() {
        Log.d(TAG, "startRecording: Initiating dual video recording");

        // Check if already recording to prevent multiple starts
        if (isRecording) {
            Log.w(TAG, "startRecording: Already recording. Ignoring start request.");
            return;
        }

        // Acquire wake lock
        acquireWakeLock();

        resetTimers();
        setVideoBitrate();

        buttonStartStop.setText(getString(R.string.button_stop));
        buttonStartStop.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_stop, 0, 0, 0);
        buttonPauseResume.setEnabled(true);
        tvPreviewPlaceholder.setVisibility(View.GONE);
        textureView.setVisibility(View.VISIBLE);

        startUpdatingInfo();
        isRecording = true;
        updatePreviewVisibility();

        // Start the dual recording service
        Intent startIntent = new Intent(getActivity(), RecordingService.class);
        startIntent.setAction("ACTION_START_RECORDING");
        requireActivity().startService(startIntent);
    }

    private void stopRecording() {
        Log.d(TAG, "stopRecording: Stopping dual video recording");

        if (!isRecording) {
            return;
        }

        try {
            // Release wake lock
            releaseWakeLock();

            // Stop the recording service
            Intent stopIntent = new Intent(getActivity(), RecordingService.class);
            stopIntent.setAction("ACTION_STOP_RECORDING");
            requireActivity().startService(stopIntent);

            isRecording = false;
            buttonStartStop.setText(getString(R.string.button_start));
            buttonStartStop.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_play, 0, 0, 0);
            buttonPauseResume.setEnabled(false);
            tvPreviewPlaceholder.setVisibility(View.VISIBLE);
            textureView.setVisibility(View.INVISIBLE);
            stopUpdatingInfo();
            updateStorageInfo();

            // Show a message that the videos are being processed
            Toast.makeText(getContext(),
                    "Processing videos, please wait...",
                    Toast.LENGTH_LONG).show();

            // Temporarily disable buttons
            buttonStartStop.setEnabled(false);
            buttonCamSwitch.setEnabled(false);

            // Process the videos in the background
            executorService.execute(() -> {
                try {
                    processLatestVideos();

                    // Update UI on the main thread
                    new Handler(Looper.getMainLooper()).post(() -> {
                        Toast.makeText(getContext(),
                                "Videos saved successfully",
                                Toast.LENGTH_SHORT).show();
                        // Reactivate buttons
                        buttonStartStop.setEnabled(true);
                        buttonCamSwitch.setEnabled(true);
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Error processing videos: " + e.getMessage());
                    new Handler(Looper.getMainLooper()).post(() -> {
                        Toast.makeText(getContext(),
                                "Error processing videos",
                                Toast.LENGTH_SHORT).show();
                        // Reactivate buttons even if there is an error
                        buttonStartStop.setEnabled(true);
                        buttonCamSwitch.setEnabled(true);
                    });
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Error stopping recording: " + e.getMessage());
            buttonStartStop.setEnabled(true);
            buttonCamSwitch.setEnabled(true);
        }
    }

    // New method to process the latest recorded videos
    private void processLatestVideos() {
        try {
            // Wait a little longer before processing the files
            Thread.sleep(3000);

            File videoDir = new File(requireActivity().getExternalFilesDir(null), "FadCam");
            if (!videoDir.exists() || !videoDir.isDirectory()) {
                Log.w(TAG, "Video directory does not exist or is not a directory");
                return;
            }

            // Get all files
            File[] allFiles = videoDir.listFiles();
            if (allFiles == null) return;

            // Maps to keep only the most recent file of each type
            Map<String, File> latestFiles = new HashMap<>();
            Map<String, Long> latestTimes = new HashMap<>();

            // First, delete any previous temporary files that might have been left
            for (File file : allFiles) {
                String name = file.getName();
                if (name.startsWith("temp_")) {
                    String type = name.contains("front_") ? "front" : "back";
                    long modTime = file.lastModified();

                    if (!latestFiles.containsKey(type) || modTime > latestTimes.get(type)) {
                        // If there was already a previous file of this type, delete it
                        if (latestFiles.containsKey(type)) {
                            File oldFile = latestFiles.get(type);
                            if (oldFile.exists()) {
                                oldFile.delete();
                                Log.d(TAG, "Deleted older temp file: " + oldFile.getName());
                            }
                        }
                        latestFiles.put(type, file);
                        latestTimes.put(type, modTime);
                    } else {
                        // This is an older file, delete it
                        file.delete();
                        Log.d(TAG, "Deleted older temp file: " + file.getName());
                    }
                }
            }

            // Process the most recent files
            for (File file : latestFiles.values()) {
                if (file.exists()) {
                    String inputFilePath = file.getAbsolutePath();
                    String originalFileName = file.getName().replace("temp_", "");
                    String outputFilePath = file.getParent() + "/FADCAM_" + originalFileName;
                    File outputFile = new File(outputFilePath);

                    if (!outputFile.exists()) {
                        Log.d(TAG, "Processing video: " + inputFilePath);
                        addTextWatermarkToVideo(inputFilePath, outputFilePath, () -> {
                            // Delete the temporary file after processing
                            if (file.exists()) {
                                file.delete();
                                Log.d(TAG, "Deleted temp file after processing: " + file.getName());
                            }
                        });
                    } else {
                        // If the FADCAM_ file already exists, delete the temp
                        file.delete();
                        Log.d(TAG, "Deleted duplicate temp file: " + file.getName());
                    }
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in processLatestVideos: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // New method to process video and wait for it to finish
    private void processVideoAndWait(File video) {
        if (video == null || !video.exists()) {
            Log.e(TAG, "Invalid video file");
            return;
        }

        String inputFilePath = video.getAbsolutePath();
        String originalFileName = video.getName().replace("temp_", "");
        String outputFilePath = video.getParent() + "/FADCAM_" + originalFileName;
        File outputFile = new File(outputFilePath);

        if (outputFile.exists()) {
            // If the FADCAM_ file already exists, delete the temp
            video.delete();
            return;
        }

        // Create a semaphore to wait for processing to finish
        final Object lock = new Object();
        final boolean[] processingComplete = {false};

        addTextWatermarkToVideo(inputFilePath, outputFilePath, () -> {
            synchronized (lock) {
                processingComplete[0] = true;
                lock.notify();
            }
        });

        // Wait for processing to finish
        synchronized (lock) {
            try {
                while (!processingComplete[0]) {
                    lock.wait(5000); // Wait a maximum of 5 seconds
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "Processing interrupted: " + e.getMessage());
            }
        }
    }

    // New method to process an individual video
    private void processVideo(File video) {
        if (video == null || !video.exists()) {
            Log.e(TAG, "Invalid video file");
            return;
        }

        String inputFilePath = video.getAbsolutePath();
        String originalFileName = video.getName().replace("temp_", "");
        String outputFilePath = video.getParent() + "/FADCAM_" + originalFileName;
        File outputFile = new File(outputFilePath);

        if (!outputFile.exists()) {
            Log.d(TAG, "Processing video: " + inputFilePath);
            addTextWatermarkToVideo(inputFilePath, outputFilePath, null);
        } else {
            Log.d(TAG, "Output file already exists, deleting temp file");
            if (video.delete()) {
                Log.d(TAG, "Temp file deleted: " + inputFilePath);
            }
        }
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

    // Below methods are new

    private void addTextWatermarkToVideo(String inputFilePath, String outputFilePath, Runnable onComplete) {
        String fontPath = getContext().getFilesDir().getAbsolutePath() + "/ubuntu_regular.ttf";
        String watermarkText;
        String watermarkOption = getWatermarkOption();

        boolean isLocationEnabled = sharedPreferences.getBoolean(PREF_LOCATION_DATA, false);
        String locationText = isLocationEnabled ? locationHelper.getLocationData() : "";

        switch (watermarkOption) {
            case "timestamp_fadcam":
                watermarkText = "Captured by FadCam - " + getCurrentTimestamp() + (isLocationEnabled ? " " + locationText : "");
                break;
            case "timestamp":
                watermarkText = getCurrentTimestamp() + (isLocationEnabled ? " " + locationText : "");
                break;
            case "no_watermark":
                String ffmpegCommandNoWatermark = String.format("-i %s -codec copy %s", inputFilePath, outputFilePath);
                executeFFmpegCommand(ffmpegCommandNoWatermark);
                return;
            default:
                watermarkText = "Captured by FadCam - " + getCurrentTimestamp() + (isLocationEnabled ? " " + locationText : "");
                break;
        }

        // Convert the watermark text to English numerals
        watermarkText = convertArabicNumeralsToEnglish(watermarkText);

        // Get and convert the font size to English numerals
        int fontSize = getFontSizeBasedOnBitrate();
        String fontSizeStr = String.valueOf(fontSize); // No need to convert numerals here

        Log.d(TAG, "Watermark Text: " + watermarkText);
        Log.d(TAG, "Font Path: " + fontPath);
        Log.d(TAG, "Font Size: " + fontSizeStr);

        // Construct the FFmpeg command
        String ffmpegCommand = String.format(
                "-i %s -vf \"drawtext=text='%s':x=10:y=10:fontsize=%s:fontcolor=white:fontfile=%s\" -q:v 0 -codec:a copy %s",
                inputFilePath, watermarkText, fontSizeStr, fontPath, outputFilePath
        );

        // Execute the FFmpeg command asynchronously
        FFmpegKit.executeAsync(ffmpegCommand, session -> {
            if (session.getReturnCode().isSuccess()) {
                Log.d(TAG, "Watermark added successfully to: " + outputFilePath);
                // Delete the temporary file after successful processing
                File inputFile = new File(inputFilePath);
                if (inputFile.exists()) {
                    if (inputFile.delete()) {
                        Log.d(TAG, "Temp file deleted: " + inputFilePath);
                    }
                }
            } else {
                Log.e(TAG, "Failed to add watermark: " + session.getFailStackTrace());
            }

            // Call the callback when finished
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    // Add this method overload
    private void addTextWatermarkToVideo(String inputFilePath, String outputFilePath) {
        addTextWatermarkToVideo(inputFilePath, outputFilePath, null);
    }

    private int getFontSizeBasedOnBitrate() {
        int fontSize;
        int videoBitrate = getVideoBitrate(); // Ensure this method retrieves the correct bitrate based on the selected quality

        if (videoBitrate <= 1000000) {
            fontSize = 12; // SD quality
        } else if (videoBitrate == 10000000) {
            fontSize = 24; // FHD quality
        } else {
            fontSize = 16; // HD or higher quality
        }

        Log.d(TAG, "Determined Font Size: " + fontSize);
        return fontSize;
    }

    private int getVideoBitrate() {
        String selectedQuality = sharedPreferences.getString(Constantes.PREF_VIDEO_QUALITY, QUALITY_HD);
        int bitrate;
        switch (selectedQuality) {
            case QUALITY_SD:
                bitrate = 1000000; // 1 Mbps
                break;
            case QUALITY_HD:
                bitrate = 5000000; // 5 Mbps
                break;
            case QUALITY_FHD:
                bitrate = 10000000; // 10 Mbps
                break;
            default:
                bitrate = 5000000; // Default to HD
                break;
        }
        Log.d(TAG, "Selected Video Bitrate: " + bitrate + " bps");
        return bitrate;
    }

    private void executeFFmpegCommand(String ffmpegCommand) {
        Log.d(TAG, "FFmpeg Command: " + ffmpegCommand);
        FFmpegKit.executeAsync(ffmpegCommand, new ExecuteCallback() {
            @Override
            public void apply(Session session) {
                if (session.getReturnCode().isSuccess()) {
                    Log.d(TAG, "Watermark added successfully.");
                    // Notify the adapter to update the thumbnail
                    File latestVideo = getLatestVideoFile();
                    if (latestVideo != null) {
                        String videoFilePath = latestVideo.getAbsolutePath();
                        updateThumbnailInAdapter(videoFilePath);
                    }

                } else {
                    Log.e(TAG, "Failed to add watermark: " + session.getFailStackTrace());
                }
            }
        });
    }

    private void updateThumbnailInAdapter(String videoFilePath) {
        if (adapter != null) {
            adapter.notifyDataSetChanged(); // Notify adapter that data has changed
        }
    }

    private String getCurrentTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MMM/yyyy hh-mm a", Locale.ENGLISH);
        return convertArabicNumeralsToEnglish(sdf.format(new Date()));
    }

    private String convertArabicNumeralsToEnglish(String text) {
        if (text == null) return null;
        return text.replaceAll("٠", "0")
                .replaceAll("١", "1")
                .replaceAll("٢", "2")
                .replaceAll("٣", "3")
                .replaceAll("٤", "4")
                .replaceAll("٥", "5")
                .replaceAll("٦", "6")
                .replaceAll("٧", "7")
                .replaceAll("٨", "8")
                .replaceAll("٩", "9");
    }

    private String getWatermarkOption() {
        SharedPreferences sharedPreferences = requireActivity().getPreferences(Context.MODE_PRIVATE);
        return sharedPreferences.getString("watermark_option", "timestamp_fadcam");
    }

    private void copyFontToInternalStorage() {
        AssetManager assetManager = getContext().getAssets();
        try (InputStream in = assetManager.open("ubuntu_regular.ttf");
             OutputStream out = new FileOutputStream(new File(getContext().getFilesDir(), "ubuntu_regular.ttf"))) {
            copyFile(in, out);
            Log.d(TAG, "Font copied to internal storage.");
        } catch (IOException e) {
            Log.e(TAG, "Error copying font to internal storage: " + e.getMessage());
        }
    }

    public void switchCamera() {
        String currentCameraSelection = sharedPreferences.getString(Constantes.PREF_CAMERA_SELECTION, Constantes.CAMERA_BACK);
        if (currentCameraSelection.equals(Constantes.CAMERA_BACK)) {
            sharedPreferences.edit().putString(Constantes.PREF_CAMERA_SELECTION, Constantes.CAMERA_FRONT).apply();
            Log.d(TAG, "Camera set to front");
            Toast.makeText(getContext(), R.string.switched_front_camera, Toast.LENGTH_SHORT).show();
        } else {
            sharedPreferences.edit().putString(Constantes.PREF_CAMERA_SELECTION, Constantes.CAMERA_BACK).apply();
            Log.d(TAG, "Camera set to rear");
            Toast.makeText(getContext(), R.string.switched_rear_camera, Toast.LENGTH_SHORT).show();
        }
    }

    private void copyFile(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private void setupStartStopButton() {
        if (!CamcorderProfile.hasProfile(1, CamcorderProfile.QUALITY_1080P) && getCameraSelection().equals(Constantes.CAMERA_FRONT) && getCameraQuality().equals(QUALITY_FHD)) {
            buttonStartStop.setEnabled(false);
        } else if (!CamcorderProfile.hasProfile(1, CamcorderProfile.QUALITY_720P) && getCameraSelection().equals(Constantes.CAMERA_FRONT) && getCameraQuality().equals(QUALITY_HD)) {
            buttonStartStop.setEnabled(false);
        } else if (!CamcorderProfile.hasProfile(1, CamcorderProfile.QUALITY_VGA) && !CamcorderProfile.hasProfile(1, CamcorderProfile.QUALITY_480P) && getCameraSelection().equals(Constantes.CAMERA_FRONT) && getCameraQuality().equals(QUALITY_SD)) {
            buttonStartStop.setEnabled(false);
        } else if (!CamcorderProfile.hasProfile(0, CamcorderProfile.QUALITY_1080P) && getCameraSelection().equals(Constantes.CAMERA_BACK) && getCameraQuality().equals(QUALITY_FHD)) {
            buttonStartStop.setEnabled(false);
        } else if (!CamcorderProfile.hasProfile(0, CamcorderProfile.QUALITY_720P) && getCameraSelection().equals(Constantes.CAMERA_BACK) && getCameraQuality().equals(QUALITY_HD)) {
            buttonStartStop.setEnabled(false);
        } else if (!CamcorderProfile.hasProfile(0, CamcorderProfile.QUALITY_VGA) && !CamcorderProfile.hasProfile(0, CamcorderProfile.QUALITY_480P) && getCameraSelection().equals(Constantes.CAMERA_BACK) && getCameraQuality().equals(QUALITY_SD)) {
            buttonStartStop.setEnabled(false);
        } else {
            buttonStartStop.setEnabled(true);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        Log.d(TAG, "onDestroyView: Cleaning up resources");
        stopUpdatingInfo();
        stopUpdatingClock();
        releaseCamera();
        executorService.shutdown(); // Close the ExecutorService
    }

    private void setVideoBitrate() {
        String selectedQuality = sharedPreferences.getString(Constantes.PREF_VIDEO_QUALITY, QUALITY_HD);
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

    private void startMonitoring() {
        final long CHECK_INTERVAL_MS = 1000; // 1 second

        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            checkAndDeleteSpecificTempFile();
        }, 0, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private String getCameraSelection() {
        return sharedPreferences.getString(Constantes.PREF_CAMERA_SELECTION, Constantes.CAMERA_BACK);
    }

    private String getCameraQuality() {
        return sharedPreferences.getString(Constantes.PREF_VIDEO_QUALITY, QUALITY_HD);
    }

    private void checkAndDeleteSpecificTempFile() {
        if (tempFileBeingProcessed != null) {
            String tempTimestamp = extractTimestamp(tempFileBeingProcessed.getName());
            String outputFilePath = tempFileBeingProcessed.getParent() + "/FADCAM_" + tempFileBeingProcessed.getName().replace("temp_", "");
            File outputFile = new File(outputFilePath);

            if (outputFile.exists()) {
                // If the FADCAM_ file exists, delete the temp file
                if (tempFileBeingProcessed.delete()) {
                    Log.d(TAG, "Temp file deleted successfully: " + tempFileBeingProcessed.getName());
                } else {
                    Log.e(TAG, "Failed to delete temp file: " + tempFileBeingProcessed.getName());
                }
                tempFileBeingProcessed = null;
            } else {
                Log.d(TAG, "Matching FADCAM_ file not found yet. Temp file remains: " + tempFileBeingProcessed.getName());
            }
        }
    }

    private String extractTimestamp(String filename) {
        // Assuming name format: "temp_TYPE_TIMESTAMP.mp4"
        // where TYPE can be "front" or "back"
        int startIndex = filename.lastIndexOf('_') + 1;
        int endIndex = filename.lastIndexOf('.');
        if (startIndex < endIndex) {
            return filename.substring(startIndex, endIndex);
        }
        return "";
    }

    private File getLatestVideoFile() {
        File videoDir = new File(requireActivity().getExternalFilesDir(null), "FadCam");
        if (!videoDir.exists()) {
            return null;
        }

        File[] files = videoDir.listFiles((dir, name) ->
                name != null && name.startsWith("temp_"));

        if (files == null || files.length == 0) {
            return null;
        }

        // Sort by modification date (most recent first)
        Arrays.sort(files, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));

        return files[0]; // Return the most recent file
    }
}
