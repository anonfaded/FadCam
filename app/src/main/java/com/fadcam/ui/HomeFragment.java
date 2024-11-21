package com.fadcam.ui;


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
import android.media.CamcorderProfile;
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

import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.RecordingService;
import com.fadcam.RecordingState;
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

public class HomeFragment extends Fragment {

    private static final String TAG = "HomeFragment";
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 100;

    private long recordingStartTime;
    private long videoBitrate;

    private double latitude;
    private double longitude;

    private Handler handlerClock = new Handler();
    private Runnable updateInfoRunnable;
    private Runnable updateClockRunnable; // Declare here

    private TextureView textureView;
    private SharedPreferences sharedPreferences;

    private Handler tipHandler = new Handler();
    private int typingIndex = 0;
    private boolean isTypingIn = true;
    private String currentTip = "";

    private TextView tvStorageInfo;
    private TextView tvPreviewPlaceholder;
    private Button buttonStartStop;
    private Button buttonPauseResume;
    private Button buttonCamSwitch;
    private boolean isPreviewEnabled = true;

    private View cardPreview;
    private Vibrator vibrator;

    private CardView cardClock;
    private TextView tvClock, tvDateEnglish, tvDateArabic;

    //FragmentActivity tipres = requireActivity();
    private TextView tvTip;
    private String[] tips;

    private int currentTipIndex = 0;

    private TextView tvStats;

    private List<String> messageQueue;
    private List<String> recentlyShownMessages;
    private final Random random = new Random();
    private static final int RECENT_MESSAGE_LIMIT = 3; // Adjust as needed

    private static final int REQUEST_PERMISSIONS = 1;
    private android.os.PowerManager.WakeLock wakeLock;
//    private static final String PREF_FIRST_LAUNCH = "first_launch";

    private RecordingState recordingState = RecordingState.NONE;

    private BroadcastReceiver broadcastOnRecordingStarted;
    private BroadcastReceiver broadcastOnRecordingResumed;
    private BroadcastReceiver broadcastOnRecordingPaused;
    private BroadcastReceiver broadcastOnRecordingStopped;
    private BroadcastReceiver broadcastOnRecordingStateCallback;

    // important
    private void requestEssentialPermissions() {
        Log.d(TAG, "requestEssentialPermissions: Requesting essential permissions");
        List<String> permissions;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // Android 11 and above
            permissions = new ArrayList<>(Arrays.asList(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            ));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
                permissions.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        } else { // Below Android 11
            permissions = new ArrayList<>(Arrays.asList(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            ));
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
    }

    private void requestBatteryOptimizationPermission() {
        android.os.PowerManager powerManager = (android.os.PowerManager) requireActivity().getSystemService(Context.POWER_SERVICE); // Full path and context adjusted
        String packageName = requireActivity().getPackageName(); // Correct package retrieval

        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + packageName));
                startActivity(intent);
            }
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
            if (isRecording()) {
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
        if (isRecording()) {
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
    }

    private void resetTimers() {
        recordingStartTime = SystemClock.elapsedRealtime();
        updateStorageInfo();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sharedPreferences = requireActivity().getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);

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

        if(!textureView.isAvailable()) {
            textureView.setVisibility(View.VISIBLE);
        }

        registerBroadcastOnRecordingStarted();
        registerBroadcastOnRecordingResumed();
        registerBroadcastOnRecordingPaused();
        registerBrodcastOnRecordingStopped();
        registerBroadcastOnRecordingStateCallback();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 11 and above
            requireActivity().registerReceiver(broadcastOnRecordingStarted, new IntentFilter(Constants.BROADCAST_ON_RECORDING_STARTED), Context.RECEIVER_EXPORTED);
            requireActivity().registerReceiver(broadcastOnRecordingResumed, new IntentFilter(Constants.BROADCAST_ON_RECORDING_RESUMED), Context.RECEIVER_EXPORTED);
            requireActivity().registerReceiver(broadcastOnRecordingPaused, new IntentFilter(Constants.BROADCAST_ON_RECORDING_PAUSED), Context.RECEIVER_EXPORTED);
            requireActivity().registerReceiver(broadcastOnRecordingStopped, new IntentFilter(Constants.BROADCAST_ON_RECORDING_STOPPED), Context.RECEIVER_EXPORTED);
            requireActivity().registerReceiver(broadcastOnRecordingStateCallback, new IntentFilter(Constants.BROADCAST_ON_RECORDING_STATE_CALLBACK), Context.RECEIVER_EXPORTED);
        } else {
            // Below Android 11
            requireActivity().registerReceiver(broadcastOnRecordingStarted, new IntentFilter(Constants.BROADCAST_ON_RECORDING_STARTED));
            requireActivity().registerReceiver(broadcastOnRecordingResumed, new IntentFilter(Constants.BROADCAST_ON_RECORDING_RESUMED));
            requireActivity().registerReceiver(broadcastOnRecordingPaused, new IntentFilter(Constants.BROADCAST_ON_RECORDING_PAUSED));
            requireActivity().registerReceiver(broadcastOnRecordingStopped, new IntentFilter(Constants.BROADCAST_ON_RECORDING_STOPPED));
            requireActivity().registerReceiver(broadcastOnRecordingStateCallback, new IntentFilter(Constants.BROADCAST_ON_RECORDING_STATE_CALLBACK));
        }

        //fetch Camera status
        String currentCameraSelection = getCameraSelection();
        Toast.makeText(getContext(), this.getString(R.string.current_camera) + ": " + currentCameraSelection, Toast.LENGTH_SHORT).show();
    }

    private void fetchRecordingState()
    {
        Intent startIntent = new Intent(getActivity(), RecordingService.class);
        startIntent.setAction(Constants.BROADCAST_ON_RECORDING_STATE_REQUEST);
        requireActivity().startService(startIntent);
    }

    private void registerBroadcastOnRecordingStateCallback() {
        broadcastOnRecordingStateCallback = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent i)
            {
                RecordingState recordingStateIntent = (RecordingState) i.getSerializableExtra(Constants.BROADCAST_EXTRA_RECORDING_STATE);
                if (recordingStateIntent == null) {
                    recordingStateIntent = RecordingState.NONE;
                }

                switch(recordingStateIntent) {
                    case NONE:
                        onRecordingStopped();
                        break;
                    case IN_PROGRESS:
                        if(isRecording()) {
                            updateRecordingSurface();
                        } else {
                            onRecordingStarted(false);
                            updateRecordingSurface();
                        }
                        break;
                    case PAUSED:
                        onRecordingPaused();
                        break;
                }

                recordingState = recordingStateIntent;
            }
        };
    }

    private void registerBroadcastOnRecordingStarted() {
        broadcastOnRecordingStarted = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent i)
            {
                recordingStartTime = i.getLongExtra(Constants.BROADCAST_EXTRA_RECORDING_START_TIME, 0);

                onRecordingStarted(true);
            }
        };
    }

    private void registerBroadcastOnRecordingResumed() {
        broadcastOnRecordingResumed = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent i)
            {
                onRecordingResumed();
            }
        };
    }

    private void registerBroadcastOnRecordingPaused() {
        broadcastOnRecordingPaused = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent i)
            {
                onRecordingPaused();
            }
        };
    }

    private void registerBrodcastOnRecordingStopped() {
        broadcastOnRecordingStopped = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent i)
            {
                onRecordingStopped();
            }
        };
    }

    private void onRecordingStarted(boolean toast) {
        recordingState = RecordingState.IN_PROGRESS;

        acquireWakeLock();

        setVideoBitrate();

        buttonStartStop.setText(getString(R.string.button_stop));
        buttonStartStop.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_stop, 0, 0, 0);
        buttonStartStop.setEnabled(true);
        buttonPauseResume.setEnabled(true);
        buttonCamSwitch.setEnabled(false);

        startUpdatingInfo();

        if(toast) {
            vibrateTouch();
            Toast.makeText(getContext(), R.string.video_recording_started, Toast.LENGTH_SHORT).show();
        }
    }

    private void onRecordingResumed()
    {
        recordingState = RecordingState.IN_PROGRESS;

        buttonPauseResume.setText(getString(R.string.button_pause));
        buttonPauseResume.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_pause, 0, 0, 0);
        buttonPauseResume.setEnabled(true);

        buttonStartStop.setText(getString(R.string.button_stop));
        buttonStartStop.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_stop, 0, 0, 0);
        buttonStartStop.setEnabled(true);

        buttonCamSwitch.setEnabled(false);

        startUpdatingInfo();
    }

    private void onRecordingPaused()
    {
        recordingState = RecordingState.PAUSED;

        buttonPauseResume.setText(getString(R.string.button_resume));
        buttonPauseResume.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_play, 0, 0, 0);
        buttonPauseResume.setEnabled(true);

        buttonCamSwitch.setEnabled(false);

        buttonStartStop.setText(getString(R.string.button_stop));
        buttonStartStop.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_stop, 0, 0, 0);
    }

    private void onRecordingStopped() {

        recordingState = RecordingState.NONE;

        releaseWakeLock();

        buttonStartStop.setText(getString(R.string.button_start));
        buttonStartStop.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_play, 0, 0, 0);

        setupStartStopButton();

        buttonPauseResume.setText(getString(R.string.button_pause));
        buttonPauseResume.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_pause, 0, 0, 0);
        buttonPauseResume.setEnabled(false);

        buttonCamSwitch.setEnabled(true);

        updatePreviewVisibility();

        stopUpdatingInfo();
    }

    @Override
    public void onStop() {
        super.onStop();

        Log.e(TAG, "HomeFragment stopped");

        if(isRecording()) {
            Intent recordingIntent = new Intent(getActivity(), RecordingService.class);
            recordingIntent.setAction(Constants.INTENT_ACTION_CHANGE_SURFACE);
            requireActivity().startService(recordingIntent);
        }

        requireActivity().unregisterReceiver(broadcastOnRecordingStarted);
        requireActivity().unregisterReceiver(broadcastOnRecordingResumed);
        requireActivity().unregisterReceiver(broadcastOnRecordingPaused);
        requireActivity().unregisterReceiver(broadcastOnRecordingStopped);
        requireActivity().unregisterReceiver(broadcastOnRecordingStateCallback);
    }

    @Override
    public void onResume() {
        super.onResume();

        Log.d(TAG, "HomeFragment resumed.");

        setupStartStopButton();
        fetchRecordingState();

        updateStats();
    }

    @Override
    public void onPause() {
        super.onPause();
        //locationHelper.stopLocationUpdates();
        Log.d(TAG, "HomeFragment paused.");
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
        Vibrator vibrator = (Vibrator) requireContext().getSystemService(Context.VIBRATOR_SERVICE);
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

        setupTextureView(view);

        tvStorageInfo = view.findViewById(R.id.tvStorageInfo);
        tvPreviewPlaceholder = view.findViewById(R.id.tvPreviewPlaceholder);
        tvPreviewPlaceholder.setVisibility(View.VISIBLE);
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

        sharedPreferences = requireActivity().getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        isPreviewEnabled = sharedPreferences.getBoolean(Constants.PREF_IS_PREVIEW_ENABLED, true);

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

    private void setupTextureView(@NonNull View view) {
        textureView = view.findViewById(R.id.textureView);
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
                surfaceTexture.setDefaultBufferSize(720, 1080);
                textureView.setVisibility(View.INVISIBLE);

                fetchRecordingState();
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
        });
    }

    private boolean areEssentialPermissionsGranted() {
        boolean cameraGranted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean recordAudioGranted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

        boolean storageGranted;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // Android 11 and above
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13 and above
                storageGranted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            } else {
                // Below Android 13
                storageGranted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            }
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
                if (recordingState.equals(RecordingState.NONE)) {
                    startRecording();
                } else {
                    stopRecording();
                    updateStats();
                }
            }
        });

        buttonPauseResume.setOnClickListener(v -> {
            if (isPaused()) {
                vibrateTouch();
                resumeRecording();
            } else {
                vibrateTouch();
                pauseRecording();
            }
        });

        buttonCamSwitch.setOnClickListener(v -> {
            switchCamera();
        });
    }

    private void startRecording() {
        SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();

        tvPreviewPlaceholder.setVisibility(View.GONE);
        textureView.setVisibility(View.VISIBLE);

        buttonStartStop.setEnabled(false);
        buttonCamSwitch.setEnabled(false);

        Intent startIntent = new Intent(getActivity(), RecordingService.class);
        startIntent.setAction(Constants.INTENT_ACTION_START_RECORDING);

        if(surfaceTexture != null) {
            startIntent.putExtra("SURFACE", new Surface(surfaceTexture));
        }

        requireActivity().startService(startIntent);
    }

    private void updateRecordingSurface()
    {
        SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();

        tvPreviewPlaceholder.setVisibility(View.GONE);
        textureView.setVisibility(View.VISIBLE);

        Intent startIntent = new Intent(getActivity(), RecordingService.class);
        startIntent.setAction(Constants.INTENT_ACTION_CHANGE_SURFACE);

        if(surfaceTexture != null) {
            startIntent.putExtra("SURFACE", new Surface(surfaceTexture));
        }

        requireActivity().startService(startIntent);
    }

    private void startUpdatingClock() {
        updateClockRunnable = new Runnable() {
            @Override
            public void run() {
                updateClock();
                handlerClock.postDelayed(this, 1000); // Update every second
            }
        };
        handlerClock.post(updateClockRunnable);
    }

    // Method to stop updating the clock
    private void stopUpdatingClock() {
        if (updateClockRunnable != null) {
            handlerClock.removeCallbacks(updateClockRunnable);
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
        new MaterialAlertDialogBuilder(requireContext())
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
        return requireActivity().getSharedPreferences("AppPreferences", Context.MODE_PRIVATE).getInt("display_option", 2); // Default to "Everything"
    }

    private void saveDisplayOption(int option) {
        SharedPreferences.Editor editor = requireActivity().getSharedPreferences("AppPreferences", Context.MODE_PRIVATE).edit();
        editor.putInt("display_option", option);
        editor.apply();
    }

    private void showOptionsAndAnimate() {
        addWobbleAnimation();
        showDisplayOptionsDialog();
    }

    // Method to update the clock and dates
    private void updateClock() {
        SharedPreferences prefs = requireActivity().getSharedPreferences("AppPreferences", Context.MODE_PRIVATE);
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
        updateInfoRunnable = new Runnable() {
            @Override
            public void run() {
                if (isRecording() && isAdded()) {
                    updateStorageInfo();
                    updateStats();
                    handlerClock.postDelayed(this, 1000); // Update every second
                }
            }
        };
        handlerClock.post(updateInfoRunnable);
    }

    private void stopUpdatingInfo() {
        Log.d(TAG, "stopUpdatingInfo: Stopping real-time updates");
        if (updateInfoRunnable != null) {
            handlerClock.removeCallbacks(updateInfoRunnable);
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
        // animateTip(); this line is giving errors so i commented it
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
        File recordsDir = new File(requireContext().getExternalFilesDir(null), Constants.RECORDING_DIRECTORY);
        int numVideos = 0;
        long totalSize = 0;

        if (recordsDir.exists()) {
            File[] files = recordsDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith("." + Constants.RECORDING_FILE_EXTENSION)) {
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

        buttonPauseResume.setText(R.string.button_resume);
        buttonPauseResume.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_play, 0, 0, 0);

        buttonCamSwitch.setEnabled(false);

        Intent stopIntent = new Intent(getActivity(), RecordingService.class);
        stopIntent.setAction(Constants.INTENT_ACTION_PAUSE_RECORDING);
        requireActivity().startService(stopIntent);
    }

    private void resumeRecording() {
        Log.d(TAG, "resumeRecording: Resuming video recording");

        buttonPauseResume.setText(getString(R.string.button_pause));
        buttonPauseResume.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_pause, 0, 0, 0);
        buttonPauseResume.setEnabled(false);

        buttonCamSwitch.setEnabled(false);

        SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();

        Intent recordingServiceIntent = new Intent(getActivity(), RecordingService.class);
        recordingServiceIntent.setAction(Constants.INTENT_ACTION_RESUME_RECORDING);
        if(surfaceTexture != null) {
            recordingServiceIntent.putExtra("SURFACE", new Surface(surfaceTexture));
        }
        requireActivity().startService(recordingServiceIntent);
    }

    private void setVideoBitrate() {
        String selectedQuality = sharedPreferences.getString(Constants.PREF_VIDEO_QUALITY, Constants.QUALITY_HD);
        switch (selectedQuality) {
            case Constants.QUALITY_SD:
                videoBitrate = 1000000; // 1 Mbps
                break;
            case Constants.QUALITY_HD:
                videoBitrate = 5000000; // 5 Mbps
                break;
            case Constants.QUALITY_FHD:
                videoBitrate = 10000000; // 10 Mbps
                break;
            default:
                videoBitrate = 5000000; // Default to HD
                break;
        }
        Log.d(TAG, "setVideoBitrate: Set to " + videoBitrate + " bps");
    }

    private String getCameraSelection() {
        return sharedPreferences.getString(Constants.PREF_CAMERA_SELECTION, Constants.CAMERA_BACK);
    }

    private String getCameraQuality() {
        return sharedPreferences.getString(Constants.PREF_VIDEO_QUALITY, Constants.QUALITY_HD);
    }

    private void stopRecording() {
        Log.d(TAG, "stopRecording: Stopping video recording");

        // Release wake lock when recording stops
        releaseWakeLock();

        buttonPauseResume.setEnabled(false);
        buttonStartStop.setEnabled(false);
        buttonCamSwitch.setEnabled(false);

        // Stop the recording service
        Intent stopIntent = new Intent(getActivity(), RecordingService.class);
        stopIntent.setAction(Constants.INTENT_ACTION_STOP_RECORDING);
        requireActivity().startService(stopIntent);

        vibrateTouch();
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
        String currentCameraSelection = sharedPreferences.getString(Constants.PREF_CAMERA_SELECTION, Constants.CAMERA_BACK);
        if (currentCameraSelection.equals(Constants.CAMERA_BACK)) {
            sharedPreferences.edit().putString(Constants.PREF_CAMERA_SELECTION, Constants.CAMERA_FRONT).apply();
            Log.d(TAG, "Camera set to front");
            Toast.makeText(getContext(), R.string.switched_front_camera, Toast.LENGTH_SHORT).show();
        } else {
            sharedPreferences.edit().putString(Constants.PREF_CAMERA_SELECTION, Constants.CAMERA_BACK).apply();
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
        if (!CamcorderProfile.hasProfile(1, CamcorderProfile.QUALITY_1080P) && getCameraSelection().equals(Constants.CAMERA_FRONT) && getCameraQuality().equals(Constants.QUALITY_FHD)) {
            buttonStartStop.setEnabled(false);
        }
        else if (!CamcorderProfile.hasProfile(1, CamcorderProfile.QUALITY_720P) && getCameraSelection().equals(Constants.CAMERA_FRONT) && getCameraQuality().equals(Constants.QUALITY_HD)) {
            buttonStartStop.setEnabled(false);
        }
        else if (!CamcorderProfile.hasProfile(1, CamcorderProfile.QUALITY_VGA) && !CamcorderProfile.hasProfile(1, CamcorderProfile.QUALITY_480P) && getCameraSelection().equals(Constants.CAMERA_FRONT) && getCameraQuality().equals(Constants.QUALITY_SD)) {
            buttonStartStop.setEnabled(false);
        }
        else if (!CamcorderProfile.hasProfile(0, CamcorderProfile.QUALITY_1080P) && getCameraSelection().equals(Constants.CAMERA_BACK) && getCameraQuality().equals(Constants.QUALITY_FHD)) {
            buttonStartStop.setEnabled(false);
        }
        else if (!CamcorderProfile.hasProfile(0, CamcorderProfile.QUALITY_720P) && getCameraSelection().equals(Constants.CAMERA_BACK) && getCameraQuality().equals(Constants.QUALITY_HD)) {
            buttonStartStop.setEnabled(false);
        }
        else if (!CamcorderProfile.hasProfile(0, CamcorderProfile.QUALITY_VGA) && !CamcorderProfile.hasProfile(0, CamcorderProfile.QUALITY_480P) && getCameraSelection().equals(Constants.CAMERA_BACK) && getCameraQuality().equals(Constants.QUALITY_SD)) {
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
    }

    public boolean isRecording() {
        return recordingState.equals(RecordingState.IN_PROGRESS);
    }

    public boolean isPaused() {
        return recordingState.equals(RecordingState.PAUSED);
    }
}