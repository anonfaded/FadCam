package com.fadcam.ui;


import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
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
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.Html;
import android.text.Spanned;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageView; // Add this
import androidx.cardview.widget.CardView; // Add this

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;

import com.fadcam.CameraType;
import com.fadcam.Constants;
import com.fadcam.Log;
import com.fadcam.R;
import com.fadcam.services.RecordingService;
import com.fadcam.RecordingState;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.ui.VideoItem; // Import VideoItem

import com.fadcam.Utils;
import com.fadcam.services.TorchService;
import com.google.android.material.button.MaterialButton;
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
import java.util.HashSet; // For combining lists
import java.util.Set;    // For combining lists
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.graphics.drawable.Drawable;

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

    private Handler tipHandler = new Handler();
    private int typingIndex = 0;
    private boolean isTypingIn = true;
    private String currentTip = "";

    private TextView tvStorageInfo;
    private TextView tvPreviewPlaceholder;
    private MaterialButton buttonStartStop;
    private MaterialButton buttonPauseResume;
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

    private MaterialButton buttonTorchSwitch;

    private CameraManager cameraManager;
    private String cameraId;
    private boolean isTorchOn = false;

    private BroadcastReceiver torchReceiver;



    // --- Fields Needed for Stats Update ---
    private SharedPreferencesManager sharedPreferencesManager;
    private ExecutorService executorService;
    private BroadcastReceiver recordingCompleteReceiver;
    private boolean isStatsReceiverRegistered = false;

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

        Log.init(requireContext());

        Log.d(TAG, "HomeFragment created.");

        // Request essential permissions on every launch
        requestEssentialPermissions();

        sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());

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

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public void onStart() {
        super.onStart();
        registerStatsReceiver(); // Register receiver when fragment starts

        if(!textureView.isAvailable()) {
            textureView.setVisibility(View.VISIBLE);
        }

        registerBroadcastOnRecordingStarted();
        registerBroadcastOnRecordingResumed();
        registerBroadcastOnRecordingPaused();
        registerBrodcastOnRecordingStopped();
        registerBroadcastOnRecordingStateCallback();

        IntentFilter[] filters = {
                new IntentFilter(Constants.BROADCAST_ON_RECORDING_STARTED),
                new IntentFilter(Constants.BROADCAST_ON_RECORDING_RESUMED),
                new IntentFilter(Constants.BROADCAST_ON_RECORDING_PAUSED),
                new IntentFilter(Constants.BROADCAST_ON_RECORDING_STOPPED),
                new IntentFilter(Constants.BROADCAST_ON_RECORDING_STATE_CALLBACK)
        };

        BroadcastReceiver[] receivers = {
                broadcastOnRecordingStarted,
                broadcastOnRecordingResumed,
                broadcastOnRecordingPaused,
                broadcastOnRecordingStopped,
                broadcastOnRecordingStateCallback
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13 and above
            for (int i = 0; i < receivers.length; i++) {
                requireContext().registerReceiver(
                        receivers[i],
                        filters[i],
                        Context.RECEIVER_EXPORTED
                );
            }
        } else {
            // Android 12 and earlier
            for (int i = 0; i < receivers.length; i++) {
                requireContext().registerReceiver(receivers[i], filters[i]);
            }
        }

        showCurrentCameraSelection();
    }

    /**
     * Displays a toast message showing the currently selected camera based on shared preferences
     */
    private void showCurrentCameraSelection() {
        CameraType currentCameraType = sharedPreferencesManager.getCameraSelection();
        String currentCameraTypeString = "";
        if (currentCameraType.equals(CameraType.FRONT)) {
            currentCameraTypeString = getString(R.string.front);
        } else if (currentCameraType.equals(CameraType.BACK)) {
            currentCameraTypeString = getString(R.string.back);
        }

//        Toast.makeText(getContext(), this.getString(R.string.current_camera) + ": " + currentCameraTypeString.toLowerCase(), Toast.LENGTH_SHORT).show();
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
                RecordingState recordingStateIntent = (RecordingState) i.getSerializableExtra(Constants.INTENT_EXTRA_RECORDING_STATE);
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
            public void onReceive(Context context, Intent i) {
                recordingStartTime = i.getLongExtra(Constants.INTENT_EXTRA_RECORDING_START_TIME, 0);
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
        
        buttonStartStop.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.button_stop)));
        buttonStartStop.setText(getString(R.string.button_stop));
        buttonStartStop.setIcon(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_stop));
        buttonStartStop.setEnabled(true);
        
        buttonPauseResume.setEnabled(true);
        buttonPauseResume.setIcon(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_pause));
        buttonCamSwitch.setEnabled(false);

        startUpdatingInfo();

        if(toast) {
            vibrateTouch();
            Toast.makeText(getContext(), R.string.video_recording_started, Toast.LENGTH_SHORT).show();
        }
    }

    private void onRecordingResumed() {
        recordingState = RecordingState.IN_PROGRESS;

        buttonPauseResume.setIcon(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_pause));
        buttonPauseResume.setEnabled(true);

        buttonStartStop.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.button_stop)));
        buttonStartStop.setText(getString(R.string.button_stop));
        buttonStartStop.setIcon(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_stop));
        buttonStartStop.setEnabled(true);

        buttonCamSwitch.setEnabled(false);

        startUpdatingInfo();
    }

    private void onRecordingPaused() {
        recordingState = RecordingState.PAUSED;

        buttonPauseResume.setIcon(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_play));
        buttonPauseResume.setEnabled(true);

        buttonCamSwitch.setEnabled(false);

        buttonStartStop.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.button_stop)));
        buttonStartStop.setText(getString(R.string.button_stop));
        buttonStartStop.setIcon(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_stop));
    }

    private void onRecordingStopped() {

        recordingState = RecordingState.NONE;

        releaseWakeLock();

        buttonStartStop.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.button_start)));
        buttonStartStop.setText(getString(R.string.button_start));
        buttonStartStop.setIcon(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_play));
        buttonStartStop.setEnabled(true);

        buttonPauseResume.setIcon(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_pause));
        buttonPauseResume.setEnabled(false);

        buttonCamSwitch.setEnabled(true);

        updatePreviewVisibility();

        stopUpdatingInfo();
    }

    @Override
    public void onStop() {
        super.onStop();
        unregisterStatsReceiver(); // Unregister receiver when fragment stops

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

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public void onResume() {
        super.onResume();

        Log.d(TAG, "HomeFragment resumed.");

        fetchRecordingState();
        // Update stats when resuming, in case file changes occurred while paused
        Log.d(TAG, "onResume: Triggering stats update.");
        updateStats();

        // Initialize the receiver if null
        if (torchReceiver == null) {
            torchReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (Constants.BROADCAST_ON_TORCH_STATE_CHANGED.equals(intent.getAction())) {
                        boolean torchState = intent.getBooleanExtra(Constants.INTENT_EXTRA_TORCH_STATE, false);
                        isTorchOn = torchState;
                        Log.d("TorchDebug", "Received broadcast - Torch state: " + torchState);
                        
                        requireActivity().runOnUiThread(() -> {
                            try {
                                buttonTorchSwitch.setIcon(AppCompatResources.getDrawable(
                                    requireContext(),
                                    R.drawable.ic_flashlight_on
                                ));
                                buttonTorchSwitch.setSelected(isTorchOn);
                                buttonTorchSwitch.setEnabled(true);
                            } catch (Exception e) {
                                Log.e("TorchDebug", "Error updating torch icon: " + e.getMessage());
                            }
                        });
                    }
                }
            };
            
            IntentFilter filter = new IntentFilter(Constants.BROADCAST_ON_TORCH_STATE_CHANGED);
            // Add the RECEIVER_NOT_EXPORTED flag for Android 13+ compatibility
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().registerReceiver(torchReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                requireContext().registerReceiver(torchReceiver, filter);
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        //locationHelper.stopLocationUpdates();
        Log.d(TAG, "HomeFragment paused.");

        // Only unregister if receiver exists
        if (torchReceiver != null) {
            try {
                requireContext().unregisterReceiver(torchReceiver);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Receiver was not registered: " + e.getMessage());
            }
        }
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
        SharedPreferences.Editor editor = sharedPreferencesManager.sharedPreferences.edit();
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
        super.onViewCreated(view, savedInstanceState);
        TorchService.setHomeFragment(this);
        
        // Add this debug code
        try {
            Drawable onIcon = AppCompatResources.getDrawable(requireContext(), R.drawable.ic_flashlight_on);
            Drawable offIcon = AppCompatResources.getDrawable(requireContext(), R.drawable.ic_flashlight_on);
            Log.d("TorchDebug", "Icon resources loaded - ON: " + (onIcon != null) + ", OFF: " + (offIcon != null));
        } catch (Exception e) {
            Log.e("TorchDebug", "Error checking icon resources: " + e.getMessage());
        }
        
        tips = requireActivity().getResources().getStringArray(R.array.tips_widget);
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

        isPreviewEnabled = sharedPreferencesManager.isPreviewEnabled();

        resetTimers();
        copyFontToInternalStorage();
        updateStorageInfo();
        updateTip();
        // Initial stats update
        Log.d(TAG, "onViewCreated: Triggering initial stats update.");
        updateStats();
        startUpdatingClock();

        // Update clock and date initially
        updateClock();

        updateTip(); // Start the tip animation
        startTipsAnimation();
        setupButtonListeners();
        setupLongPressListener();
        updatePreviewVisibility();

        buttonTorchSwitch = view.findViewById(R.id.buttonTorchSwitch);
        initializeTorch();
        setupTorchButton();
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

    // --- BroadcastReceiver Implementation ---

    private void registerStatsReceiver() {
        if (!isStatsReceiverRegistered && getContext() != null) {
            if (recordingCompleteReceiver == null) {
                recordingCompleteReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (intent != null && Constants.ACTION_RECORDING_COMPLETE.equals(intent.getAction())) {
                            android.util.Log.i(TAG, "Received ACTION_RECORDING_COMPLETE in HomeFragment, updating stats...");
                            updateStats(); // Trigger stats recalculation
                        }
                    }
                };
            }
            IntentFilter filter = new IntentFilter(Constants.ACTION_RECORDING_COMPLETE);
            ContextCompat.registerReceiver(requireContext(), recordingCompleteReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            isStatsReceiverRegistered = true;
            Log.d(TAG, "Stats ACTION_RECORDING_COMPLETE receiver registered.");
        }
    }

    private void unregisterStatsReceiver() {
        if (isStatsReceiverRegistered && recordingCompleteReceiver != null && getContext() != null) {
            try {
                requireContext().unregisterReceiver(recordingCompleteReceiver);
                isStatsReceiverRegistered = false;
                Log.d(TAG, "Stats ACTION_RECORDING_COMPLETE receiver unregistered.");
            } catch (IllegalArgumentException e) {
                Log.w(TAG,"Attempted to unregister stats receiver but it wasn't registered?");
                isStatsReceiverRegistered = false; // Ensure flag is reset
            }
        }
    }

    // --- Updated updateStats Method ---

    private void updateStats() {
        Log.d(TAG, "updateStats: Starting calculation...");
        if (executorService == null || executorService.isShutdown()) {
            Log.w(TAG,"ExecutorService not available for updateStats");
            // Reinitialize if needed or handle gracefully
            executorService = Executors.newSingleThreadExecutor();
        }

        executorService.submit(() -> {
            // --- Get current storage settings ---
            String storageMode = sharedPreferencesManager.getStorageMode();
            String customUriString = sharedPreferencesManager.getCustomStorageUri();
            List<VideoItem> primaryItems;
            List<VideoItem> tempItems;

            // --- Load File Lists (Same logic as RecordsFragment) ---
            Log.d(TAG,"updateStats BG: Loading file lists. Mode: "+storageMode);
            if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode) && customUriString != null) {
                Uri treeUri = null; try { treeUri = Uri.parse(customUriString); } catch (Exception e) { Log.e(TAG,"BG updateStats: Error parsing custom URI", e);}
                if (treeUri != null && hasSafPermission(treeUri)) {
                    primaryItems = getSafRecordsList(treeUri);
                } else {
                    Log.e(TAG,"BG updateStats: Permission error or invalid URI for custom location: "+ customUriString);
                    primaryItems = new ArrayList<>();
                }
            } else {
                primaryItems = getInternalRecordsList();
            }
            tempItems = getTempCacheRecordsList();

            // Combine (ok to run on background thread)
            List<VideoItem> combinedItems = combineVideoLists(primaryItems, tempItems);

            // --- Calculate Stats (Count and Size) ---
            int numVideos = combinedItems.size();
            long totalSizeBytes = 0;
            for (VideoItem item : combinedItems) {
                if (item != null) { // Basic null check
                    totalSizeBytes += item.size;
                }
            }

            // Format size
            String totalSizeFormatted = (getContext() != null)
                    ? Formatter.formatFileSize(getContext(), totalSizeBytes)
                    : String.format(Locale.US,"%.2f GB", totalSizeBytes / (1024.0*1024.0*1024.0)); // Fallback format

            // Prepare final text for UI
            final String statsText = String.format(Locale.getDefault(),
                    getString(R.string.mainpage_video_info), // Using your existing string resource
                    numVideos, totalSizeFormatted);
            final Spanned formattedText = Html.fromHtml(statsText, Html.FROM_HTML_MODE_LEGACY); // If your string uses HTML

            Log.d(TAG,"updateStats BG: Calculation complete. Count="+numVideos+", Size="+totalSizeFormatted);

            // --- Update UI on Main Thread ---
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (tvStats != null) {
                        tvStats.setText(formattedText); // Use the final formatted text
                        Log.d(TAG, "updateStats UI: Updated tvStats text.");
                    } else {
                        Log.w(TAG, "updateStats UI: tvStats is null.");
                    }
                });
            }
        });
    }

    // --- COPIED Helper Methods (from RecordsFragment or move to shared Utils class) ---
    // You NEED these methods here or accessible via Utils

    private boolean hasSafPermission(Uri treeUri) {
        Context context = getContext();
        if (context == null || treeUri == null) return false;
        try {
            List<UriPermission> persistedUris = context.getContentResolver().getPersistedUriPermissions();
            boolean permissionFound = false;
            for (UriPermission uriPermission : persistedUris) {
                if (uriPermission.getUri().equals(treeUri) && uriPermission.isReadPermission() && uriPermission.isWritePermission()) {
                    permissionFound = true; break;
                }
            }
            if (!permissionFound) return false;
            DocumentFile docDir = DocumentFile.fromTreeUri(context, treeUri);
            return docDir != null && docDir.canRead();
        } catch (Exception e) { Log.e(TAG, "Error checking SAF permission", e); return false; }
    }

    private List<VideoItem> getInternalRecordsList() {
        List<VideoItem> items = new ArrayList<>();
        File recordsDir = getContext() != null ? getContext().getExternalFilesDir(null) : null;
        if (recordsDir == null) { Log.e(TAG, "Context or ExternalFilesDir null in getInternalRecordsList"); return items; }
        File fadCamDir = new File(recordsDir, Constants.RECORDING_DIRECTORY);
        if (fadCamDir.exists() && fadCamDir.isDirectory()) {
            File[] files = fadCamDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith("." + Constants.RECORDING_FILE_EXTENSION) && !file.getName().startsWith("temp_")) {
                        items.add(new VideoItem(Uri.fromFile(file), file.getName(), file.length(), file.lastModified()));
                    }
                }
            }
        }
        return items;
    }

    private List<VideoItem> getSafRecordsList(Uri treeUri) {
        List<VideoItem> items = new ArrayList<>();
        Context context = getContext();
        if (context == null || treeUri == null) { Log.e(TAG,"Context or treeUri null in getSafRecordsList"); return items;}
        DocumentFile dir = DocumentFile.fromTreeUri(context, treeUri);
        if (dir != null && dir.isDirectory() && dir.canRead()) {
            try {
                for (DocumentFile file : dir.listFiles()) {
                    if (file != null && file.isFile() && file.getName() != null &&
                            (file.getName().endsWith("." + Constants.RECORDING_FILE_EXTENSION) || "video/mp4".equals(file.getType())) &&
                            !file.getName().startsWith("temp_"))
                    {
                        items.add(new VideoItem(file.getUri(), file.getName(), file.length(), file.lastModified()));
                    }
                }
            } catch (Exception e) { Log.e(TAG, "Error listing SAF files in updateStats for " + treeUri, e); }
        } else { Log.e(TAG, "Cannot read/access SAF dir in updateStats: " + treeUri); }
        return items;
    }

    private List<VideoItem> getTempCacheRecordsList() {
        List<VideoItem> items = new ArrayList<>();
        Context context = getContext();
        if (context == null) return items;
        File cacheBaseDir = context.getExternalCacheDir();
        if (cacheBaseDir == null) return items;
        File recordingTempDir = new File(cacheBaseDir, "recording_temp");
        scanDirectoryForTempVideos(recordingTempDir, items);
        // Scan other temp dirs if needed based on RecordingService logic
        return items;
    }

    private void scanDirectoryForTempVideos(File directory, List<VideoItem> items) {
        if (directory.exists() && directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().startsWith("temp_") && file.getName().endsWith("." + Constants.RECORDING_FILE_EXTENSION)) {
                        if (file.length() > 0) {
                            items.add(new VideoItem(Uri.fromFile(file), file.getName(), file.length(), file.lastModified()));
                        }
                    }
                }
            }
        }
    }

    private List<VideoItem> combineVideoLists(List<VideoItem> primary, List<VideoItem> temp) {
        List<VideoItem> combined = new ArrayList<>();
        Set<Uri> existingUris = new HashSet<>();
        for (VideoItem item : primary) { if (item != null && item.uri != null && existingUris.add(item.uri)) { combined.add(item); } }
        for (VideoItem item : temp) { if (item != null && item.uri != null && existingUris.add(item.uri)) { combined.add(item); } }
        return combined;
    }

    private void pauseRecording() {
        Log.d(TAG, "pauseRecording: Pausing video recording");

        buttonPauseResume.setIcon(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_play));
        buttonPauseResume.setEnabled(false);

        buttonCamSwitch.setEnabled(false);

        Intent stopIntent = new Intent(getActivity(), RecordingService.class);
        stopIntent.setAction(Constants.INTENT_ACTION_PAUSE_RECORDING);
        requireActivity().startService(stopIntent);
    }

    private void resumeRecording() {
        Log.d(TAG, "resumeRecording: Resuming video recording");

        buttonPauseResume.setIcon(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_pause));
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
        videoBitrate = Utils.estimateBitrate(sharedPreferencesManager.getCameraResolution(), sharedPreferencesManager.getVideoFrameRate());
        Log.d(TAG, "setVideoBitrate: Set to " + videoBitrate + " bps");
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
        AssetManager assetManager = requireContext().getAssets();
        InputStream in = null;
        OutputStream out = null;
        try {
            in = assetManager.open("ubuntu_regular.ttf");
            File outFile = new File(requireContext().getFilesDir(), "ubuntu_regular.ttf");
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
        if (sharedPreferencesManager.getCameraSelection().equals(CameraType.BACK)) {
            sharedPreferencesManager.sharedPreferences.edit().putString(Constants.PREF_CAMERA_SELECTION, CameraType.FRONT.toString()).apply();
            Log.d(TAG, "Camera set to front");
            Toast.makeText(getContext(), R.string.switched_front_camera, Toast.LENGTH_SHORT).show();
        } else {
            sharedPreferencesManager.sharedPreferences.edit().putString(Constants.PREF_CAMERA_SELECTION, CameraType.BACK.toString()).apply();
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

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        TorchService.setHomeFragment(null);
        
        // Safely unregister the torch receiver
        if (torchReceiver != null) {
            try {
                requireContext().unregisterReceiver(torchReceiver);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Torch receiver was not registered or already unregistered");
            }
            torchReceiver = null;
        }
        
        stopUpdatingInfo();
        stopUpdatingClock();
    }

    public boolean isRecording() {
        return recordingState.equals(RecordingState.IN_PROGRESS);
    }

    public boolean isPaused() {
        return recordingState.equals(RecordingState.PAUSED);
    }

    private void initializeTorch() {
        cameraManager = (CameraManager) requireContext().getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = getCameraWithFlash();
            if (cameraId == null) {
                Log.d(TAG, "No camera with flash found");
                buttonTorchSwitch.setEnabled(false);
                buttonTorchSwitch.setVisibility(View.GONE);
            } else {
                Log.d(TAG, "Flash available on camera: " + cameraId);
                buttonTorchSwitch.setEnabled(true);
                buttonTorchSwitch.setVisibility(View.VISIBLE);
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access error: " + e.getMessage());
            e.printStackTrace();
            buttonTorchSwitch.setEnabled(false);
            buttonTorchSwitch.setVisibility(View.GONE);
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void setupTorchButton() {
        buttonTorchSwitch = requireView().findViewById(R.id.buttonTorchSwitch);

        // Set default torch source if none selected
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        if (prefs.getString(Constants.PREF_SELECTED_TORCH_SOURCE, null) == null) {
            try {
                String defaultTorchId = getCameraWithFlash();
                if (defaultTorchId != null) {
                    prefs.edit()
                            .putString(Constants.PREF_SELECTED_TORCH_SOURCE, defaultTorchId)
                            .putBoolean(Constants.PREF_BOTH_TORCHES_ENABLED, false)
                            .apply();
                }
            } catch (CameraAccessException e) {
                Log.e(TAG, "Error setting default torch source: " + e.getMessage());
            }
        }

        // Setup click listener for torch toggle
        buttonTorchSwitch.setOnClickListener(v -> {
            SharedPreferencesManager sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());
            
            Intent intent;
            if (sharedPreferencesManager.isRecordingInProgress()) {
                // If recording, use RecordingService
                intent = new Intent(requireContext(), RecordingService.class);
                intent.setAction(Constants.INTENT_ACTION_TOGGLE_RECORDING_TORCH);
            } else {
                // If not recording, use TorchService
                intent = new Intent(requireContext(), TorchService.class);
                intent.setAction(Constants.INTENT_ACTION_TOGGLE_TORCH);
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(intent);
            } else {
                requireContext().startService(intent);
            }
        });

        // Setup long press listener
        buttonTorchSwitch.setOnLongClickListener(v -> {
            showTorchOptionsDialog();
            vibrateTouch();
            return true;
        });

        // Register torch state receiver
        if (torchReceiver == null) {
            try {
                // First try to unregister any existing receiver
                requireContext().unregisterReceiver(torchReceiver);
            } catch (IllegalArgumentException e) {
                // Ignore if not registered
            }
            
            torchReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (Constants.BROADCAST_ON_TORCH_STATE_CHANGED.equals(intent.getAction())) {
                        boolean torchState = intent.getBooleanExtra(Constants.INTENT_EXTRA_TORCH_STATE, false);
                        isTorchOn = torchState;
                        Log.d("TorchDebug", "Received broadcast - Torch state: " + torchState);
                        
                        requireActivity().runOnUiThread(() -> {
                            try {
                                buttonTorchSwitch.setIcon(AppCompatResources.getDrawable(
                                    requireContext(),
                                    R.drawable.ic_flashlight_on
                                ));
                                buttonTorchSwitch.setSelected(isTorchOn);
                                buttonTorchSwitch.setEnabled(true);
                            } catch (Exception e) {
                                Log.e("TorchDebug", "Error updating torch icon: " + e.getMessage());
                            }
                        });
                    }
                }
            };
            
            IntentFilter filter = new IntentFilter(Constants.BROADCAST_ON_TORCH_STATE_CHANGED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().registerReceiver(torchReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                requireContext().registerReceiver(torchReceiver, filter);
            }
        }
    }

    private void updateTorchButtonState(boolean isOn) {
        if (buttonTorchSwitch != null) {
            buttonTorchSwitch.setIcon(AppCompatResources.getDrawable(
                requireContext(),
                R.drawable.ic_flashlight_on
            ));
            buttonTorchSwitch.setEnabled(true);
        }
    }

    private void showTorchOptionsDialog() {
        // Check if recording is in progress first
        if (isRecordingInProgress()) {
            Toast.makeText(requireContext(), R.string.torch_recording_note, Toast.LENGTH_SHORT).show();
            return;
        }

        CameraManager cameraManager = (CameraManager) requireContext().getSystemService(Context.CAMERA_SERVICE);
        try {
            List<String> torchSources = new ArrayList<>();
            boolean hasMultipleTorches = false;
            boolean hasBackTorch = false;
            boolean hasFrontTorch = false;

            // Check available torch sources
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                Boolean hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                int facing = characteristics.get(CameraCharacteristics.LENS_FACING);

                if (hasFlash != null && hasFlash) {
                    torchSources.add(cameraId);
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        hasBackTorch = true;
                    } else if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        hasFrontTorch = true;
                    }
                }
            }

            hasMultipleTorches = hasBackTorch && hasFrontTorch;
            Log.d(TAG, "Torch sources found: Back=" + hasBackTorch + ", Front=" + hasFrontTorch);

            View dialogView = getLayoutInflater().inflate(R.layout.dialog_torch_options, null);
            RadioGroup torchGroup = dialogView.findViewById(R.id.torch_group);

            // Setup torch source options
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
            String currentTorchSource = prefs.getString(Constants.PREF_SELECTED_TORCH_SOURCE, null);
            boolean currentBothTorches = prefs.getBoolean(Constants.PREF_BOTH_TORCHES_ENABLED, false);

            // Add individual torch options
            for (String sourceId : torchSources) {
                RadioButton rb = new RadioButton(requireContext());
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(sourceId);
                int facing = chars.get(CameraCharacteristics.LENS_FACING);
                rb.setText(facing == CameraCharacteristics.LENS_FACING_BACK ?
                        getString(R.string.torch_back) : getString(R.string.torch_front));
                rb.setTag(sourceId);
                torchGroup.addView(rb);

                if (sourceId.equals(currentTorchSource) && !currentBothTorches) {
                    rb.setChecked(true);
                }
            }

            // Add "Both Torches" option if multiple torches available
            if (hasMultipleTorches) {
                RadioButton bothTorches = new RadioButton(requireContext());
                bothTorches.setText(R.string.torch_both);
                bothTorches.setTag("both");
                torchGroup.addView(bothTorches);

                if (currentBothTorches) {
                    bothTorches.setChecked(true);
                }
                Log.d(TAG, "Added 'Both Torches' option");
            }

            // Select first source if none selected
            if (currentTorchSource == null && !currentBothTorches && torchGroup.getChildCount() > 0) {
                ((RadioButton) torchGroup.getChildAt(0)).setChecked(true);
            }

            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.torch_options_title)
                    .setView(dialogView)
                    .setPositiveButton(R.string.torch_apply, (dialog, which) -> {
                        RadioButton selectedSource = dialogView.findViewById(torchGroup.getCheckedRadioButtonId());
                        if (selectedSource != null) {
                            String selectedSourceId = (String) selectedSource.getTag();
                            boolean isBothSelected = "both".equals(selectedSourceId);

                            // Save settings
                            SharedPreferences.Editor editor = prefs.edit();
                            editor.putBoolean(Constants.PREF_BOTH_TORCHES_ENABLED, isBothSelected);
                            if (!isBothSelected) {
                                editor.putString(Constants.PREF_SELECTED_TORCH_SOURCE, selectedSourceId);
                            }
                            editor.apply();

                            Log.d(TAG, "Saved torch settings - Both: " + isBothSelected +
                                    ", Source: " + selectedSourceId);
                        }
                    })
                    .setNegativeButton(R.string.torch_cancel, null)
                    .show();

        } catch (CameraAccessException e) {
            Log.e(TAG, "Error accessing camera: " + e.getMessage());
        }
    }

    private String getCameraWithFlash() throws CameraAccessException {
        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
            Boolean flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (flashAvailable != null && flashAvailable) {
                Log.d(TAG, "Found camera with flash: " + id);
                return id;
            }
        }
        Log.d(TAG, "No camera with flash found");
        return null;
    }

    private boolean isRecordingInProgress() {
        ActivityManager manager = (ActivityManager) requireContext().getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (RecordingService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    public void updateTorchUI(boolean isOn) {
        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                try {
                    buttonTorchSwitch.setIcon(AppCompatResources.getDrawable(
                        requireContext(),
                        R.drawable.ic_flashlight_on
                    ));
                    buttonTorchSwitch.setSelected(isOn);
                    buttonTorchSwitch.setEnabled(true);
                    Log.d("TorchDebug", "Torch UI updated, isOn: " + isOn);
                } catch (Exception e) {
                    Log.e("TorchDebug", "Error updating torch UI: " + e.getMessage());
                }
            });
        }
    }
}