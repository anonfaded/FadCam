package com.fadcam.ui;


import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import java.util.ArrayList;
import java.util.List;



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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.arthenica.ffmpegkit.ExecuteCallback;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.Session;
import com.fadcam.Constantes;
import com.fadcam.R;
import com.fadcam.RecordingService;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

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
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class HomeFragment extends Fragment {

    private static final String TAG = "HomeFragment";
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 100;
    private LocationHelper locationHelper;

    private long recordingStartTime;
    private long videoBitrate;

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
//    private static final String PREF_FIRST_LAUNCH = "first_launch";

    // important
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
//    private WakeLock wakeLock;

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

        // Check if it's the first launch
//        boolean isFirstLaunch = sharedPreferences.getBoolean(PREF_FIRST_LAUNCH, true);
//        if (isFirstLaunch) {
//            // Request essential permissions
//            requestEssentialPermissions();
//
//            // Set first launch to false
//            sharedPreferences.edit().putBoolean(PREF_FIRST_LAUNCH, false).apply();
//        }

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

//    @Override
//    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
//        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
//        if (requestCode == REQUEST_PERMISSIONS) {
//            boolean allGranted = true;
//            for (int result : grantResults) {
//                if (result != PackageManager.PERMISSION_GRANTED) {
//                    allGranted = false;
//                    break;
//                }
//            }
//            if (allGranted) {
//                startRecording();
//            } else {
//                Toast.makeText(requireContext(), "Essential permissions are required to start recording", Toast.LENGTH_SHORT).show();
//            }
//        }
//    }


//    private void requestLocationPermission() {
//        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
//            ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION_REQUEST_CODE);
//        } else {
//            locationHelper.startLocationUpdates();
//        }
//        Log.d(TAG, "Requesting location permission.");
//    }


    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
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

    //    function to use haptic feedbacks
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

        tvClock = view.findViewById(R.id.tvClock);
        tvDateEnglish = view.findViewById(R.id.tvDateEnglish);
        tvDateArabic = view.findViewById(R.id.tvDateArabic);


        cardPreview = view.findViewById(R.id.cardPreview);
        vibrator = (Vibrator) requireContext().getSystemService(Context.VIBRATOR_SERVICE);

        sharedPreferences = requireActivity().getPreferences(Context.MODE_PRIVATE);
        isPreviewEnabled = sharedPreferences.getBoolean("isPreviewEnabled", true);

        resetTimers();
        copyFontToInternalStorage();
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
                .setTitle("Permissions Required")
                .setMessage("This app needs camera, microphone, and storage permissions to function properly. Please enable these permissions from the app settings.")
                .setPositiveButton("OK", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void debugPermissionsStatus() {
        Log.d(TAG, "Camera permission: " +
                (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED ? "Granted" : "Denied"));
        Log.d(TAG, "Record Audio permission: " +
                (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ? "Granted" : "Denied"));
        Log.d(TAG, "Write External Storage permission: " +
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
        long remainingTime = (videoBitrate > 0) ? (bytesAvailable * 8) / videoBitrate * 2 : 0; // Double the remaining time        // Calculate days, hours, minutes, and seconds for remaining time
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
            remainingTime.append(String.format(Locale.getDefault(), "<font color='#E43C3C'>%d</font><font color='#CCCCCC'>days</font> ", days));
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

    //    update storage and stats in real time while recording is started
    private void startUpdatingInfo() {
        Log.d(TAG, "startUpdatingInfo: Beginning real-time updates");
        updateInfoRunnable = new Runnable() {
            @Override
            public void run() {
                if (isRecording) {
                    updateStorageInfo();
                    updateStats();
                    handler.postDelayed(this, 1000); // Update every 3 seconds
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
                getString(R.string.mainpage_video_info),
                numVideos, Formatter.formatFileSize(getContext(), totalSize));

        tvStats.setText(Html.fromHtml(statsText, Html.FROM_HTML_MODE_LEGACY));
    }

    private void pauseRecording() {
        Log.d(TAG, "pauseRecording: Pausing video recording");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mediaRecorder.pause();
            isPaused = true;
            buttonPauseResume.setText(R.string.button_resume);
            buttonPauseResume.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_play, 0, 0, 0);
        }
    }

    private void resumeRecording() {
        Log.d(TAG, "resumeRecording: Resuming video recording");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mediaRecorder.resume();
            isPaused = false;
            buttonPauseResume.setText(getString(R.string.button_pause));
            buttonPauseResume.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_pause, 0, 0, 0);
        }
    }


    private BroadcastReceiver recordingStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("RECORDING_STATE_CHANGED".equals(intent.getAction())) {
                boolean isRecording = intent.getBooleanExtra("isRecording", false);
                if (isRecording) {
                    buttonStartStop.setText(getString(R.string.button_stop));
                    buttonStartStop.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_stop, 0, 0, 0);
                    buttonPauseResume.setEnabled(true);
                    tvPreviewPlaceholder.setVisibility(View.GONE);
                    textureView.setVisibility(View.VISIBLE);
                } else {
                    buttonStartStop.setText(getString(R.string.button_start));
                    buttonStartStop.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_play, 0, 0, 0);
                    buttonPauseResume.setEnabled(false);
                    tvPreviewPlaceholder.setVisibility(View.VISIBLE);
                    textureView.setVisibility(View.GONE);
                }
            }
        }
    };


    private void startRecording() {
        Log.d(TAG, "startRecording: Initiating video recording from home fragment");

        // Acquire wake lock to prevent the device from sleeping
        acquireWakeLock();

        // Set up the camera and MediaRecorder here
        if (!isRecording) {
            resetTimers();
            if (cameraDevice == null) {
                openCamera();
            } else {
                startRecordingVideo();
            }
            recordingStartTime = SystemClock.elapsedRealtime();
            setVideoBitrate();

            buttonStartStop.setText(getString(R.string.button_stop));
            buttonStartStop.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_stop, 0, 0, 0);
            buttonPauseResume.setEnabled(true);
            tvPreviewPlaceholder.setVisibility(View.GONE);
            textureView.setVisibility(View.VISIBLE);

            startUpdatingInfo();
            isRecording = true;
            updatePreviewVisibility();

            // Start the recording service
            Intent startIntent = new Intent(getActivity(), RecordingService.class);
            startIntent.setAction("ACTION_START_RECORDING");
            getActivity().startService(startIntent);
        }
    }


//recording service section
//    private void startRecording() {
//        Log.d(TAG, "startRecording: Initiating video recording from home fragment");
//
//        Intent startIntent = new Intent(getActivity(), RecordingService.class);
//        startIntent.setAction("ACTION_START_RECORDING");
//        getActivity().startService(startIntent);
//
//        if (!isRecording) {
//            resetTimers();
//            if (cameraDevice == null) {
//                openCamera();
//            } else {
//                startRecordingVideo();
//            }
//            recordingStartTime = SystemClock.elapsedRealtime();
//            setVideoBitrate();
//
//            buttonStartStop.setText("Stop");
//            buttonStartStop.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_stop, 0, 0, 0);
//            buttonPauseResume.setEnabled(true);
//            tvPreviewPlaceholder.setVisibility(View.GONE);
//            textureView.setVisibility(View.VISIBLE);
//
//            startUpdatingInfo();
//            isRecording = true;
//            updatePreviewVisibility();
//        }
//    }

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

    private String getCameraSelection() {
        return sharedPreferences.getString(Constantes.PREF_CAMERA_SELECTION, Constantes.CAMERA_BACK);
    }

    private String getCameraQuality() {
        return sharedPreferences.getString(Constantes.PREF_VIDEO_QUALITY, QUALITY_HD);
    }

    private void closeCamera() {
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    private void openCamera() {
        Log.d(TAG, "openCamera: Opening camera");
        CameraManager manager = (CameraManager) getActivity().getSystemService(Context.CAMERA_SERVICE);
        try {
            String[] cameraIdList = manager.getCameraIdList();
            cameraId = getCameraSelection().equals(Constantes.CAMERA_FRONT) ? cameraIdList[1] : cameraIdList[0];
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
        buttonCamSwitch.setEnabled(false);

        // Check if TextureView is available before starting recording
        if (!textureView.isAvailable()) {
            tvPreviewPlaceholder.setVisibility(View.VISIBLE);
            textureView.setVisibility(View.VISIBLE);
            openCamera();
            Log.e(TAG, "startRecordingVideo: TextureView is now available             550");
        }

        if (null == cameraDevice || !textureView.isAvailable() || !Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            Log.e(TAG, "startRecordingVideo: Unable to start recording due to missing prerequisites");
            return;
        }
        try {
            Log.e(TAG, "startRecordingVideo: TextureView found, success             556+");
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

            int selectedFramerate = sharedPreferences.getInt(Constantes.PREF_VIDEO_FRAMERATE, Constantes.DEFAULT_VIDEO_FRAMERATE);

            // Define framerate
            Range<Integer> fpsRange = Range.create(selectedFramerate, selectedFramerate);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);

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
                                // Haptic Feedback
                                vibrateTouch();
                                isRecording = true;
                                Toast.makeText(getContext(), R.string.video_recording_started, Toast.LENGTH_SHORT).show();
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
        /*
         * This method sets up the MediaRecorder for video recording.
         * It creates a directory for saving videos if it doesn't exist,
         * generates a timestamp-based filename, and configures the
         * MediaRecorder with the appropriate settings based on the
         * selected video quality (SD, HD, FHD). It reduces bitrates
         * by 50% using the HEVC (H.265) encoder for efficient compression
         * without significantly affecting video quality.
         *
         * - SD: 640x480 @ 0.5 Mbps
         * - HD: 1280x720 @ 2.5 Mbps
         * - FHD: 1920x1080 @ 5 Mbps
         *
         * It also adjusts the frame rate, sets audio settings, and configures
         * the orientation based on the camera selection (front or rear).
         */

        try {
            // Create directory for saving videos if it doesn't exist
            File videoDir = new File(requireActivity().getExternalFilesDir(null), "FadCam");
            if (!videoDir.exists()) {
                videoDir.mkdirs();
            }

            // Generate a timestamp-based filename for the video
            String timestamp = new SimpleDateFormat("yyyyMMdd_hh_mm_ssa", Locale.getDefault()).format(new Date());
            File videoFile = new File(videoDir, "temp_" + timestamp + ".mp4");

            // Initialize MediaRecorder
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setOutputFile(videoFile.getAbsolutePath());

            // Select video quality and adjust size and bitrate
            String selectedQuality = sharedPreferences.getString(Constantes.PREF_VIDEO_QUALITY, QUALITY_HD);
            switch (selectedQuality) {
                case QUALITY_SD:
                    // SD: 640x480 resolution, 0.5 Mbps (50% of original 1 Mbps)
                    mediaRecorder.setVideoSize(640, 480);
                    mediaRecorder.setVideoEncodingBitRate(500000);
                    break;
                case QUALITY_HD:
                    // HD: 1280x720 resolution, 2.5 Mbps (50% of original 5 Mbps)
                    mediaRecorder.setVideoSize(1280, 720);
                    mediaRecorder.setVideoEncodingBitRate(2500000);
                    break;
                case QUALITY_FHD:
                    // FHD: 1920x1080 resolution, 5 Mbps (50% of original 10 Mbps)
                    mediaRecorder.setVideoSize(1920, 1080);
                    mediaRecorder.setVideoEncodingBitRate(5000000);
                    break;
                default:
                    // Default to HD settings
                    mediaRecorder.setVideoSize(1280, 720);
                    mediaRecorder.setVideoEncodingBitRate(2500000);
                    break;
            }

            // Set frame rate and capture rate
            int selectedFramerate = sharedPreferences.getInt(Constantes.PREF_VIDEO_FRAMERATE, Constantes.DEFAULT_VIDEO_FRAMERATE);
            mediaRecorder.setVideoFrameRate(selectedFramerate);
            mediaRecorder.setCaptureRate(selectedFramerate);

            // Audio settings: high-quality audio
            mediaRecorder.setAudioEncodingBitRate(384000);
            mediaRecorder.setAudioSamplingRate(48000);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);

            // Set video encoder to HEVC (H.265) for better compression
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.HEVC);

            // Set orientation based on camera selection
            if (getCameraSelection().equals(Constantes.CAMERA_FRONT)) {
                mediaRecorder.setOrientationHint(270);
            } else {
                mediaRecorder.setOrientationHint(90);
            }

            // Prepare MediaRecorder
            mediaRecorder.prepare();

        } catch (IOException e) {
            Log.e(TAG, "setupMediaRecorder: Error setting up media recorder", e);
            e.printStackTrace();
        }
    }


    private String extractTimestamp(String filename) {
        // Assuming filename format is "prefix_TIMESTAMP.mp4"
        // Example: "temp_20240730_01_39_26PM.mp4"
        // Extracting timestamp part: "20240730_01_39_26PM"
        int startIndex = filename.indexOf('_') + 1;
        int endIndex = filename.lastIndexOf('.');
        return filename.substring(startIndex, endIndex);
    }


    private void checkAndDeleteSpecificTempFile() {
        if (tempFileBeingProcessed != null) {
            String tempTimestamp = extractTimestamp(tempFileBeingProcessed.getName());

            // Construct FADCAM_ filename with the same timestamp
            String outputFilePath = tempFileBeingProcessed.getParent() + "/FADCAM_" + tempFileBeingProcessed.getName().replace("temp_", "");
            File outputFile = new File(outputFilePath);

            // Check if the FADCAM_ file exists
            if (outputFile.exists()) {
                // Delete temp file
                if (tempFileBeingProcessed.delete()) {
                    Log.d(TAG, "Temp file deleted successfully.");
                } else {
                    Log.e(TAG, "Failed to delete temp file.");
                }
                // Reset tempFileBeingProcessed to null after deletion
                tempFileBeingProcessed = null;
            } else {
                // FADCAM_ file does not exist yet
                Log.d(TAG, "Matching FADCAM_ file not found. Temp file remains.");
            }
        }
    }


    private void startMonitoring() {
        final long CHECK_INTERVAL_MS = 1000; // 1 second

        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            checkAndDeleteSpecificTempFile();
        }, 0, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }


    private void stopRecording() {
        Log.d(TAG, "stopRecording: Stopping video recording");

        // Release wake lock when recording stops
        releaseWakeLock();

        // Stop the recording service
        Intent stopIntent = new Intent(getActivity(), RecordingService.class);
        stopIntent.setAction("ACTION_STOP_RECORDING");
        getActivity().startService(stopIntent);

        if (isRecording) {
            try {
                cameraCaptureSession.stopRepeating();
                cameraCaptureSession.abortCaptures();
                releaseCamera();
                vibrateTouch();
                Toast.makeText(getContext(), R.string.video_recording_stopped, Toast.LENGTH_SHORT).show();

                // Add watermarking here if necessary
                // Get the latest video file
                File latestVideoFile = getLatestVideoFile();
                if (latestVideoFile != null) {
                    String inputFilePath = latestVideoFile.getAbsolutePath();
                    String originalFileName = latestVideoFile.getName().replace("temp_", "");
                    String outputFilePath = latestVideoFile.getParent() + "/FADCAM_" + originalFileName;
                    Log.d(TAG, "Watermarking: Input file path: " + inputFilePath);
                    Log.d(TAG, "Watermarking: Output file path: " + outputFilePath);

                    tempFileBeingProcessed = latestVideoFile;
                    addTextWatermarkToVideo(inputFilePath, outputFilePath);
                } else {
                    Log.e(TAG, "No video file found.");
                }

                isRecording = false;
                buttonStartStop.setText(getString(R.string.button_start));
                buttonStartStop.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_play, 0, 0, 0);
                buttonPauseResume.setEnabled(false);
                tvPreviewPlaceholder.setVisibility(View.VISIBLE);
                textureView.setVisibility(View.INVISIBLE);
                stopUpdatingInfo();
                updateStorageInfo();
            } catch (CameraAccessException | IllegalStateException e) {
                Log.e(TAG, "stopRecording: Error stopping recording", e);
                e.printStackTrace();
            }
            isRecording = false;
            updatePreviewVisibility();
        }
        buttonCamSwitch.setEnabled(true);
    }


//recording service section
//    private void stopRecording() {
//        Log.d(TAG, "stopRecording: Stopping video recording");
//
//        Intent stopIntent = new Intent(getActivity(), RecordingService.class);
//        stopIntent.setAction("ACTION_STOP_RECORDING");
//        getActivity().startService(stopIntent);
//
//        if (isRecording) {
//            try {
//                cameraCaptureSession.stopRepeating();
//                cameraCaptureSession.abortCaptures();
//                mediaRecorder.stop();
//                mediaRecorder.reset();
//                // Haptic Feedback
//                vibrateTouch();
//                Toast.makeText(getContext(), "Recording stopped", Toast.LENGTH_SHORT).show();
//            } catch (CameraAccessException | IllegalStateException e) {
//                Log.e(TAG, "stopRecording: Error stopping recording", e);
//                e.printStackTrace();
//            }
//// below lines are new
//            // Add watermarking here
//            // Get the latest video file
//            File latestVideoFile = getLatestVideoFile();
//            if (latestVideoFile != null) {
//                // Prepare file paths
//                String inputFilePath = latestVideoFile.getAbsolutePath();
//
//                // Remove 'temp_' prefix from the file name to get the original name
//                String originalFileName = latestVideoFile.getName().replace("temp_", "");
//
//                // Create output file path with 'FADCAM_' prefix
//                String outputFilePath = latestVideoFile.getParent() + "/FADCAM_" + originalFileName;
//                Log.d(TAG, "Watermarking: Input file path: " + inputFilePath);
//                Log.d(TAG, "Watermarking: Output file path: " + outputFilePath);
//
//                // Track the temp file being processed
//                tempFileBeingProcessed = latestVideoFile;
//
//                // Add text watermark to the recorded video
//                addTextWatermarkToVideo(inputFilePath, outputFilePath);
//            } else {
//                Log.e(TAG, "No video file found.");
//            }
//
//
//            releaseCamera();
//            isRecording = false;
//            buttonStartStop.setText("Start");
//            buttonStartStop.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_play, 0, 0, 0);
//            buttonPauseResume.setEnabled(false);
//            tvPreviewPlaceholder.setVisibility(View.VISIBLE);
//            textureView.setVisibility(View.INVISIBLE);
//            stopUpdatingInfo();
//            updateStorageInfo(); // Final update with actual values
//
//            // Start monitoring temp files
////            startMonitoring();
//        }
//        isRecording = false;
//        updatePreviewVisibility();
//    }

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

// below methods are new

    private File getLatestVideoFile() {
        File videoDir = new File(requireActivity().getExternalFilesDir(null), "FadCam");
        File[] files = videoDir.listFiles();
        if (files == null || files.length == 0) {
            return null;
        }

        // Sort files by last modified date
        Arrays.sort(files, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
        return files[0]; // Return the most recently modified file
    }


    private void addTextWatermarkToVideo(String inputFilePath, String outputFilePath) {
        String fontPath = getContext().getFilesDir().getAbsolutePath() + "/ubuntu_regular.ttf";
        String watermarkText;
        String watermarkOption = getWatermarkOption();

        boolean isLocationEnabled = sharedPreferences.getBoolean(PREF_LOCATION_DATA, false);
        String locationText = isLocationEnabled ? locationHelper.getLocationData() : "";

        switch (watermarkOption) {
            case "timestamp_fadcam":
                watermarkText = "Captured by FadCam - " + getCurrentTimestamp() + (isLocationEnabled ? "" + locationText : "");
                break;
            case "timestamp":
                watermarkText = getCurrentTimestamp() + (isLocationEnabled ? "" + locationText : "");
                break;
            case "no_watermark":
                String ffmpegCommandNoWatermark = String.format("-i %s -codec copy %s", inputFilePath, outputFilePath);
                executeFFmpegCommand(ffmpegCommandNoWatermark);
                return;
            default:
                watermarkText = "Captured by FadCam - " + getCurrentTimestamp() + (isLocationEnabled ? "" + locationText : "");
                break;
        }

        // Convert the watermark text to English numerals
        watermarkText = convertArabicNumeralsToEnglish(watermarkText);

        // Get and convert the font size to English numerals
        int fontSize = getFontSizeBasedOnBitrate();
        String fontSizeStr = convertArabicNumeralsToEnglish(String.valueOf(fontSize));

        Log.d(TAG, "Watermark Text: " + watermarkText);
        Log.d(TAG, "Font Path: " + fontPath);
        Log.d(TAG, "Font Size: " + fontSizeStr);

        // Construct the FFmpeg command
        String ffmpegCommand = String.format(
                "-i %s -vf \"drawtext=text='%s':x=10:y=10:fontsize=%s:fontcolor=white:fontfile=%s\" -q:v 0 -codec:a copy %s",
                inputFilePath, watermarkText, fontSizeStr, fontPath, outputFilePath
        );

        executeFFmpegCommand(ffmpegCommand);
    }



    private int getFontSizeBasedOnBitrate() {
        int fontSize;
        int videoBitrate = getVideoBitrate(); // Ensure this method retrieves the correct bitrate based on the selected quality

        if (videoBitrate <= 1000000) {
            fontSize = 12; //SD quality
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
                    // Start monitoring temp files
                    startMonitoring();

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
        InputStream in = null;
        OutputStream out = null;
        try {
            in = assetManager.open("ubuntu_regular.ttf");
            File outFile = new File(getContext().getFilesDir(), "ubuntu_regular.ttf");
            out = new FileOutputStream(outFile);
            copyFile(in, out);
            Log.d(TAG, "Font copied to internal storage.");
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    // NO-OP
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    // NO-OP
                }
            }
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

//    private class LocationHelper implements LocationListener {
//
//        private LocationManager locationManager;
//        private double latitude;
//        private double longitude;
//
//        public LocationHelper(LocationManager locationManager) {
//            this.locationManager = locationManager;
//        }
//
//        public void startListening() {
//            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, this);
//        }
//
//        @Override
//        public void onLocationChanged(Location location) {
//            latitude = location.getLatitude();
//            longitude = location.getLongitude();
//        }
//
//        public String getLocationText() {
//            return String.format(Locale.getDefault(), "%.2f, %.2f", latitude, longitude);
//        }
//    }


    private void copyFile(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private void setupStartStopButton()
    {
        if (!CamcorderProfile.hasProfile(1, CamcorderProfile.QUALITY_1080P) && getCameraSelection().equals(Constantes.CAMERA_FRONT) && getCameraQuality().equals(QUALITY_FHD)) {
            buttonStartStop.setEnabled(false);
        }
        else if (!CamcorderProfile.hasProfile(1, CamcorderProfile.QUALITY_720P) && getCameraSelection().equals(Constantes.CAMERA_FRONT) && getCameraQuality().equals(QUALITY_HD)) {
            buttonStartStop.setEnabled(false);
        }
        else if (!CamcorderProfile.hasProfile(1, CamcorderProfile.QUALITY_VGA) && !CamcorderProfile.hasProfile(1, CamcorderProfile.QUALITY_480P) && getCameraSelection().equals(Constantes.CAMERA_FRONT) && getCameraQuality().equals(QUALITY_SD)) {
            buttonStartStop.setEnabled(false);
        }
        else if (!CamcorderProfile.hasProfile(0, CamcorderProfile.QUALITY_1080P) && getCameraSelection().equals(Constantes.CAMERA_BACK) && getCameraQuality().equals(QUALITY_FHD)) {
            buttonStartStop.setEnabled(false);
        }
        else if (!CamcorderProfile.hasProfile(0, CamcorderProfile.QUALITY_720P) && getCameraSelection().equals(Constantes.CAMERA_BACK) && getCameraQuality().equals(QUALITY_HD)) {
            buttonStartStop.setEnabled(false);
        }
        else if (!CamcorderProfile.hasProfile(0, CamcorderProfile.QUALITY_VGA) && !CamcorderProfile.hasProfile(0, CamcorderProfile.QUALITY_480P) && getCameraSelection().equals(Constantes.CAMERA_BACK) && getCameraQuality().equals(QUALITY_SD)) {
            buttonStartStop.setEnabled(false);
        }
        else {
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
    }
}