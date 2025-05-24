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

import androidx.cardview.widget.CardView; // Add this

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
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
import android.widget.ImageView; // <<< ADD IMPORT FOR ImageView
import androidx.fragment.app.FragmentManager; // <<< ADD IMPORT FOR FragmentManager
import androidx.fragment.app.FragmentTransaction; // <<< ADD IMPORT FOR FragmentTransaction

public class HomeFragment extends Fragment {

    private static final String TAG = "HomeFragment";
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 100;

    // ----- Fix Start for this method(fields)-----
    private static final String[] CLOCK_COLOR_NAMES = {"Purple", "Blue", "Green", "Teal", "Orange", "Red", "Dark Grey", "App Theme Dark"};
    private static final String[] CLOCK_COLOR_HEX_VALUES = {"#673AB7", "#2196F3", "#4CAF50", "#009688", "#FF9800", "#F44336", "#424242", "#302745"};
    // ----- Fix Ended for this method(fields)-----

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

    // ----- Fix Start for this method(fields)-----
    private Surface textureViewSurface; // To hold the Surface from TextureView
    // ----- Fix Ended for this method(fields)-----


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
            // ----- Fix Start for this method(setupLongPressListener_SequentialAnimation)-----
            // 1. Perform haptic feedback
            performHapticFeedback();

            // 2. Unified Card Bounce Animation (Down then Up)
            AnimatorSet cardBounceAnim = new AnimatorSet();
            ObjectAnimator scaleDownX = ObjectAnimator.ofFloat(cardPreview, "scaleX", 0.9f);
            ObjectAnimator scaleDownY = ObjectAnimator.ofFloat(cardPreview, "scaleY", 0.9f);
            scaleDownX.setDuration(50); // Fast scale down
            scaleDownY.setDuration(50);

            ObjectAnimator scaleUpX = ObjectAnimator.ofFloat(cardPreview, "scaleX", 1.0f);
            ObjectAnimator scaleUpY = ObjectAnimator.ofFloat(cardPreview, "scaleY", 1.0f);
            scaleUpX.setDuration(70); // Fast rebound
            scaleUpY.setDuration(70);

            cardBounceAnim.play(scaleDownX).with(scaleDownY); // Play scale down
            cardBounceAnim.play(scaleUpX).with(scaleUpY).after(scaleDownX); // Play scale up immediately after scale down completes

            cardBounceAnim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);

                    // 3. Core logic: toggle preview, update UI, save state (runs AFTER card bounce)
                    isPreviewEnabled = !isPreviewEnabled;
                    updatePreviewVisibility(); // This is the main visual change for enabling/disabling preview
                    savePreviewState();

                    // 4. Surface handling logic OR placeholder animations (also runs AFTER card bounce)
                    if (isRecordingOrPaused()) { // Only update service if recording/paused
                        if (isPreviewEnabled) {
                            if (textureView != null && textureView.isAvailable() && textureViewSurface != null) {
                                Log.d(TAG, "Preview enabled (post-anim): TextureView available, sending surface to service.");
                                updateServiceWithCurrentSurface(textureViewSurface);
                            } else {
                                Log.d(TAG, "Preview enabled (post-anim): TextureView not yet available, will send surface on callback.");
                            }
                        } else {
                            Log.d(TAG, "Preview disabled (post-anim): Sending null surface to service.");
                            updateServiceWithCurrentSurface(null);
                        }
                    } else {
                        // Logic for when NOT recording/paused: Show random message and animate placeholder
                        Log.d(TAG, "Long press on preview (post-anim) while not recording/paused. Applying placeholder animations.");
                        showRandomMessage(); // Show random message on the placeholder

                        if (tvPreviewPlaceholder != null) {
                            tvPreviewPlaceholder.setVisibility(View.VISIBLE); // Ensure placeholder is visible

                            // Red blinking animation for placeholder
                            tvPreviewPlaceholder.setBackgroundColor(Color.RED);
                            tvPreviewPlaceholder.postDelayed(() -> {
                                if (tvPreviewPlaceholder != null) { // Check again in case fragment is destroyed
                                   tvPreviewPlaceholder.setBackgroundColor(Color.TRANSPARENT);
                                }
                            }, 100); // Blink duration

                            // Wobble animation for placeholder
                            ObjectAnimator pScaleXUp = ObjectAnimator.ofFloat(tvPreviewPlaceholder, "scaleX", 1.1f);
                            ObjectAnimator pScaleYUp = ObjectAnimator.ofFloat(tvPreviewPlaceholder, "scaleY", 1.1f);
                            ObjectAnimator pScaleXDown = ObjectAnimator.ofFloat(tvPreviewPlaceholder, "scaleX", 1.0f);
                            ObjectAnimator pScaleYDown = ObjectAnimator.ofFloat(tvPreviewPlaceholder, "scaleY", 1.0f);
                            pScaleXUp.setDuration(50);
                            pScaleYUp.setDuration(50);
                            pScaleXDown.setDuration(50);
                            pScaleYDown.setDuration(50);
                            AnimatorSet wobbleSet = new AnimatorSet();
                            wobbleSet.play(pScaleXUp).with(pScaleYUp).before(pScaleXDown).before(pScaleYDown);
                            wobbleSet.start();
                        }
                    }
                }
            });
            cardBounceAnim.start(); // Start the card bounce animation
            // ----- Fix Ended for this method(setupLongPressListener_SequentialAnimation)-----
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
                // Call the existing onRecordingStarted method FIRST
                // This updates internal state and button UI
                onRecordingStarted(true); // Pass true for the toast as before

                // *** ADDED FIX: Explicitly update surface and preview state AFTER confirming start ***
                updateRecordingSurface();   // Send the current surface to the service
                updatePreviewVisibility();  // Update visibility based on isPreviewEnabled flag
                Log.d(TAG, "BROADCAST_ON_RECORDING_STARTED received: Sent surface update and refreshed preview visibility.");
                // --- END FIX ---
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

    // --- Receiver for MediaRecorder Stopped signal ---
    /**
     * Called when the BROADCAST_ON_RECORDING_STOPPED is received,
     * indicating the MediaRecorder engine has stopped and hardware resources
     * are likely released or being released immediately by the service.
     * This method resets the UI to the IDLE state.
     */
    private void onRecordingStopped() {
        Log.i(TAG, "<<< Received BROADCAST_ON_RECORDING_STOPPED >>>");
        if (!isAdded() || getContext() == null || getView() == null) {
            Log.w(TAG, "onRecordingStopped received but fragment not ready.");
            // Update local state even if UI isn't updated yet
            recordingState = RecordingState.NONE;
            return;
        }

        // Update local state FIRST
        recordingState = RecordingState.NONE;
        Log.d(TAG, "onRecordingStopped: Local state set to NONE.");

        releaseWakeLock(); // Release wake lock

        // --- RESET UI TO IDLE STATE ---
        try {
            Log.d(TAG,"onRecordingStopped: Calling resetUIButtonsToIdleState...");
            resetUIButtonsToIdleState(); // Re-enables Start button etc.
        } catch (Exception e){
            Log.e(TAG, "onRecordingStopped: Error calling resetUIButtonsToIdleState", e);
        }

        // Stop updating timers/info display
        stopUpdatingInfo();

        // --- FIX: Removed the irrelevant toast message ---
        // Utils.showQuickToast(requireContext(), R.string.video_recording_stopped); // <<< REMOVED THIS LINE
        // --- End FIX ---

        Log.d(TAG, "onRecordingStopped: UI reset to IDLE. Background processing may continue.");
    }

    // Inside HomeFragment.java

    /**
     * Safely resets the main control buttons (Start, Pause, CamSwitch, Torch)
     * and related UI elements (like preview) to their default IDLE state.
     * This means recording is stopped and the user can initiate a new one.
     * Should only be called when the fragment is attached and view is available.
     */
    private void resetUIButtonsToIdleState() {
        Log.d(TAG, ">>> resetUIButtonsToIdleState: Resetting UI to IDLE state <<<");
        // Guard against running if fragment/context isn't ready
        if (!isAdded() || getContext() == null || getView() == null) {
            Log.w(TAG, "resetUIButtonsToIdleState: Cannot reset UI - Fragment not attached, context/view is null.");
            return;
        }

        try {
            // --- Start/Stop Button ---
            if (buttonStartStop != null) {
                buttonStartStop.setEnabled(true); // Enable starting
                buttonStartStop.setText(getString(R.string.button_start)); // Set text to "Start"
                buttonStartStop.setIcon(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_play)); // Set icon to Play
                // Ensure background tint is Green for start
                buttonStartStop.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.button_start)));
                Log.d(TAG, "Reset Start/Stop button to IDLE.");
            } else {
                Log.w(TAG, "resetUIButtonsToIdleState: buttonStartStop is null!");
            }

            // --- Pause/Resume Button ---
            if (buttonPauseResume != null) {
                buttonPauseResume.setEnabled(false); // Disable pausing/resuming when idle
                buttonPauseResume.setIcon(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_pause)); // Reset icon to Pause
                Log.d(TAG, "Reset Pause/Resume button to IDLE.");
            } else {
                Log.w(TAG, "resetUIButtonsToIdleState: buttonPauseResume is null!");
            }

            // --- Camera Switch Button ---
            if (buttonCamSwitch != null) {
                buttonCamSwitch.setEnabled(true); // Enable camera switching when idle
                Log.d(TAG, "Reset Camera Switch button to IDLE.");
            } else {
                Log.w(TAG, "resetUIButtonsToIdleState: buttonCamSwitch is null!");
            }

            // --- Torch Button ---
            if (buttonTorchSwitch != null) {
                // Check flash availability again, just in case
                // initializeTorch(); // Can be redundant, checking is lighter
                boolean flashAvailable = (cameraManager != null && getCameraWithFlashQuietly() != null);
                buttonTorchSwitch.setEnabled(flashAvailable); // Enable only if flash is actually available
                // The torch 'selected' state (on/off icon tint) is handled by updateTorchUI based on its broadcast receiver
                Log.d(TAG, "Reset Torch button enabled state (based on flash availability): " + flashAvailable);
            } else {
                Log.w(TAG, "resetUIButtonsToIdleState: buttonTorchSwitch is null!");
            }

            // --- Preview Area ---
            // Ensure placeholder is visible and texture view is hidden when idle
            updatePreviewVisibility(); // This method handles the logic based on recordingState == NONE
            Log.d(TAG, "Reset Preview Visibility.");

            Log.i(TAG, ">>> UI successfully reset to IDLE state. <<<");

        } catch (IllegalStateException e) {
            // Can happen if requireContext() is called after fragment detached
            Log.e(TAG, "resetUIButtonsToIdleState: IllegalStateException resetting UI (Fragment likely detached)", e);
        } catch (Exception e) {
            // Catch any other unexpected errors during UI update
            Log.e(TAG, "resetUIButtonsToIdleState: Unexpected error resetting buttons/UI", e);
        }
    } // End resetUIButtonsToIdleState

    /** Helper for resetUIButtonsToIdleState to check flash without throwing checked exception */
    private String getCameraWithFlashQuietly() {
        // Ensure cameraManager is initialized (e.g., in onViewCreated or onAttach)
        if(cameraManager == null) {
            try {
                cameraManager = (CameraManager) requireContext().getSystemService(Context.CAMERA_SERVICE);
            } catch (Exception e) {
                Log.e(TAG,"Failed to get CameraManager in getCameraWithFlashQuietly", e);
                return null;
            }
        }
        if(cameraManager == null) return null; // Check again if getSystemService failed

        try {
            // Assuming getCameraWithFlash is defined elsewhere and throws CameraAccessException
            return getCameraWithFlash();
        } catch (CameraAccessException e){
            Log.w(TAG,"CameraAccessException checking flash quietly: " + e.getMessage()); // Changed to warning
            return null;
        } catch (Exception e) {
            Log.e(TAG,"Unexpected error checking flash quietly", e);
            return null;
        }
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

    // --- `onResume()` Method (Simplified - focuses on fetch state) ---
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "HomeFragment resumed.");
        if (!isAdded() || getContext() == null || getActivity() == null) { Log.e(TAG,"onResume: Not attached!"); return; }
        if (sharedPreferencesManager == null) { sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext()); }

        Log.d(TAG, "onResume: Fetching current recording state from service...");
        fetchRecordingState(); // Let service callback handle UI sync

        registerBroadcastReceivers(); // Centralized registration

        Log.d(TAG, "onResume: Triggering stats update.");
        updateStats();
        updateTorchUI(isTorchOn);
        updatePreviewVisibility(); // ADDED: Ensure preview visibility is correctly set on resume
    }

    // Inside HomeFragment.java

    // --- Receiver Field Declarations (Should already exist near top of HomeFragment) ---
    // private BroadcastReceiver broadcastOnRecordingStarted;
    // private BroadcastReceiver broadcastOnRecordingResumed;
    // private BroadcastReceiver broadcastOnRecordingPaused;
    // private BroadcastReceiver broadcastOnRecordingStopped; // Handles UI idle reset now
    // private BroadcastReceiver broadcastOnRecordingStateCallback; // Handles initial sync
    // private BroadcastReceiver recordingCompleteReceiver; // Handles stats update after processing
    // private BroadcastReceiver torchReceiver;

    // --- Registration Flags (Optional but recommended for robust unregistering) ---
    private boolean isStateReceiversRegistered = false;
    private boolean isCompletionReceiverRegistered = false; // Renamed from isStatsReceiverRegistered
    private boolean isTorchReceiverRegistered = false;


    // --- MAIN Registration Method ---
    /**
     * Centralized method to register all necessary BroadcastReceivers for this fragment.
     * Ensures initialization and calls individual registration helpers.
     * Should be called from onResume or onStart.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag") // Suppress only if targeting older SDKs AND necessary
    private void registerBroadcastReceivers() {
        if (!isAdded() || getContext() == null) {
            Log.e(TAG,"Cannot register receivers, fragment not attached or context null.");
            return;
        }
        Context safeContext = requireContext();
        Log.i(TAG, "Registering ALL Broadcast Receivers...");

        // --- 1. Initialize ALL receiver instances ---
        initializeRecordingStateReceivers();   // Defines state receivers if null
        initializeRecordingCompleteReceiver(); // Defines completion receiver if null
        initializeTorchReceiver();             // Defines torch receiver if null
        // NOTE: No initialize for ResourcesReleased receiver as it's removed in this logic path


        // --- 2. Register the Receivers ---
        registerRecordingStateReceivers(safeContext);      // Registers: START, RESUME, PAUSE, STOPPED, STATE_CALLBACK
        registerRecordingCompleteReceiver(safeContext);    // Registers: ACTION_RECORDING_COMPLETE
        registerTorchReceiver(safeContext);            // Registers: BROADCAST_ON_TORCH_STATE_CHANGED

        Log.i(TAG, "Finished registering receivers.");
    }

    // --- Initialization Helper Methods ---

    /** Initializes the BroadcastReceiver instances for recording state changes */
    private void initializeRecordingStateReceivers() {
        // Initialize Receiver for START action
        if (broadcastOnRecordingStarted == null) {
            broadcastOnRecordingStarted = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent i) {
                    if (!isAdded() || i == null) return;
                    Log.d(TAG, "Received BROADCAST_ON_RECORDING_STARTED (New Handler)");
                    
                    recordingStartTime = i.getLongExtra(Constants.INTENT_EXTRA_RECORDING_START_TIME, SystemClock.elapsedRealtime());
                    
                    // Perform non-UI actions previously in onRecordingStarted(true)
                    acquireWakeLock();
                    setVideoBitrate();
                    
                    // Call the main UI state updater
                    handleServiceStateUpdate(RecordingState.IN_PROGRESS); 

                    // Handle the toast
                    if(isAdded() && getContext() != null) { 
                       vibrateTouch();
                       Toast.makeText(getContext(), R.string.video_recording_started, Toast.LENGTH_SHORT).show();
                    }
                }
            };
            Log.d(TAG,"Initialized broadcastOnRecordingStarted receiver (New Handler)");
        }
        // Initialize Receiver for RESUME action
        if (broadcastOnRecordingResumed == null) {
            broadcastOnRecordingResumed = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) { if(isAdded()) onRecordingResumed(); }
            };
            Log.d(TAG,"Initialized broadcastOnRecordingResumed receiver");
        }
        // Initialize Receiver for PAUSE action
        if (broadcastOnRecordingPaused == null) {
            broadcastOnRecordingPaused = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) { if(isAdded()) onRecordingPaused(); }
            };
            Log.d(TAG,"Initialized broadcastOnRecordingPaused receiver");
        }
        // Initialize Receiver for STOPPED action (triggers UI Idle)
        if (broadcastOnRecordingStopped == null) {
            broadcastOnRecordingStopped = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent i) { if(isAdded()) onRecordingStopped(); }
            };
            Log.d(TAG,"Initialized broadcastOnRecordingStopped receiver");
        }
        // Initialize Receiver for SERVICE STATE CALLBACK
        if (broadcastOnRecordingStateCallback == null) {
            broadcastOnRecordingStateCallback = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent i) {
                    if (!isAdded() || i == null) return;
                    // Get the state reported by the service
                    RecordingState serviceState = (RecordingState) i.getSerializableExtra(Constants.INTENT_EXTRA_RECORDING_STATE);
                    Log.i(TAG, "Received Service State Callback: " + serviceState);
                    if (serviceState == null) serviceState = RecordingState.NONE; // Default to NONE

                    // *** CALL the handler method ***
                    handleServiceStateUpdate(serviceState);
                }
            };
            Log.d(TAG,"Initialized broadcastOnRecordingStateCallback receiver");
        }
    }



    /**
     * **Definition:** Updates the HomeFragment UI based on the definitive state
     * reported by the RecordingService's state callback.
     * @param reportedState The RecordingState received from the service.
     */
    private void handleServiceStateUpdate(RecordingState reportedState) {
        if (!isAdded()) { // Check if fragment is attached
            Log.w(TAG, "handleServiceStateUpdate: Fragment not attached, ignoring state update: " + reportedState);
            return;
        }
        Log.i(TAG, "handleServiceStateUpdate: Applying UI for Service State = " + reportedState);

        // Update the local recording state variable
        recordingState = reportedState;

        // Update UI elements based on the state
        switch (reportedState) {
            case IN_PROGRESS:
                setUIForRecordingActive(); // Call helper to set Stop/Pause buttons etc.
                break;
            case PAUSED:
                setUIForRecordingPaused(); // Call helper to set Stop/Resume buttons etc.
                break;
            case NONE:
            default:
                // Service state is NONE. Recording is stopped.
                // UI *should* be idle unless background processing is happening
                // for a *previous* video. Reset UI directly here as this confirms
                // the *current* recording attempt is definitely stopped.
                Log.d(TAG, "handleServiceStateUpdate: Service state is NONE. Resetting UI to idle.");
                resetUIButtonsToIdleState();
                break;
        }
        Log.d(TAG, "handleServiceStateUpdate finished. Fragment state is now: " + recordingState);
    }

    /** Helper to set UI elements for the ACTIVE recording state */
    private void setUIForRecordingActive() {
        if(!isAdded() || getContext() == null) return;
        Log.d(TAG,"Setting UI to: ACTIVE Recording");
        try{
            // Ensure interaction buttons reflect recording
            buttonStartStop.setEnabled(true); // Enable STOP
            buttonStartStop.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.button_stop));
            buttonStartStop.setText(getString(R.string.button_stop));
            buttonStartStop.setIcon(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_stop));

            buttonPauseResume.setEnabled(true); // Enable PAUSE
            buttonPauseResume.setIcon(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_pause));

            buttonCamSwitch.setEnabled(false); // Disable CAM SWITCH
            if(buttonTorchSwitch != null) buttonTorchSwitch.setEnabled(getCameraWithFlashQuietly() != null); // Enable TORCH if available

            // Manage preview and timers
            updatePreviewVisibility(); startUpdatingInfo();
        } catch(Exception e){ Log.e(TAG,"Error setting UI for Active state", e); }
    }

    /** Helper to set UI elements for the PAUSED recording state */
    private void setUIForRecordingPaused() {
        if(!isAdded() || getContext() == null) return;
        Log.d(TAG,"Setting UI to: PAUSED Recording");
        try{
            // Set buttons for Paused state (Stop ON, Resume(Play) ON, Switch OFF, Torch OFF)
            buttonStartStop.setEnabled(true); // Enable STOP
            buttonStartStop.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.button_stop));
            buttonStartStop.setText(getString(R.string.button_stop));
            buttonStartStop.setIcon(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_stop));

            buttonPauseResume.setEnabled(true); // Enable RESUME
            buttonPauseResume.setIcon(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_play)); // Show Play icon for RESUME

            buttonCamSwitch.setEnabled(false); // Disable CAM SWITCH
            // ----- Fix Start for this method(setUIForRecordingPaused_torchButton)-----
            if(buttonTorchSwitch != null) buttonTorchSwitch.setEnabled(getCameraWithFlashQuietly() != null); // Enable TORCH if available, even when paused
            // ----- Fix Ended for this method(setUIForRecordingPaused_torchButton)-----

            // Manage preview and timers
            updatePreviewVisibility(); stopUpdatingInfo(); // Show placeholder/last frame, stop timers
        } catch(Exception e){ Log.e(TAG,"Error setting UI for Paused state", e); }
    }

    /** Initializes the BroadcastReceiver for ACTION_RECORDING_COMPLETE */
    private void initializeRecordingCompleteReceiver() {
        if (recordingCompleteReceiver == null) {
            recordingCompleteReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (!isAdded() || intent == null || intent.getAction() == null) return;
                    if (Constants.ACTION_RECORDING_COMPLETE.equals(intent.getAction())) {
                        Log.i(TAG, "<<< Received ACTION_RECORDING_COMPLETE (Processing Finished) >>>");
                        if(getView() == null) { Log.w(TAG,"Completion: View null, skip stats UI"); return; }
                        try { updateStats(); Log.d(TAG,"Completion: Updated stats."); }
                        catch (Exception e) { Log.e(TAG, "Completion: Err update stats", e);}
                    }
                }
            };
            Log.d(TAG,"Initialized recordingCompleteReceiver");
        }
    }

    /** Initializes the BroadcastReceiver for Torch State Changes */
    private void initializeTorchReceiver() {
        if (torchReceiver == null) {
            torchReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (!isAdded() || getActivity()==null || intent == null || intent.getAction() == null) return;
                    if (Constants.BROADCAST_ON_TORCH_STATE_CHANGED.equals(intent.getAction())) {
                        isTorchOn = intent.getBooleanExtra(Constants.INTENT_EXTRA_TORCH_STATE, false);
                        Log.d("TorchDebug", "Received state update via Broadcast: " + isTorchOn);
                        updateTorchUI(isTorchOn); // Update button visuals
                    }
                }
            };
            Log.d(TAG,"Initialized torchReceiver");
        }
    }

    // --- Registration Helper Methods ---

    /** Helper to register all recording state change receivers */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerRecordingStateReceivers(Context context){
        if(isStateReceiversRegistered) return; // Prevent double registration
        try {
            IntentFilter startedFilter = new IntentFilter(Constants.BROADCAST_ON_RECORDING_STARTED);
            IntentFilter resumedFilter = new IntentFilter(Constants.BROADCAST_ON_RECORDING_RESUMED);
            IntentFilter pausedFilter = new IntentFilter(Constants.BROADCAST_ON_RECORDING_PAUSED);
            IntentFilter stoppedFilter = new IntentFilter(Constants.BROADCAST_ON_RECORDING_STOPPED);
            IntentFilter callbackFilter = new IntentFilter(Constants.BROADCAST_ON_RECORDING_STATE_CALLBACK);

            // Ensure receiver instances exist before registering
            if (broadcastOnRecordingStarted == null) initializeRecordingStateReceivers(); // Check individual receivers too...
            if (broadcastOnRecordingResumed == null) initializeRecordingStateReceivers();
            if (broadcastOnRecordingPaused == null) initializeRecordingStateReceivers();
            if (broadcastOnRecordingStopped == null) initializeRecordingStateReceivers();
            if (broadcastOnRecordingStateCallback == null) initializeRecordingStateReceivers();

            // Perform registration only if instances are valid
            if(broadcastOnRecordingStarted!=null) ContextCompat.registerReceiver(context, broadcastOnRecordingStarted, startedFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
            if(broadcastOnRecordingResumed!=null) ContextCompat.registerReceiver(context, broadcastOnRecordingResumed, resumedFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
            if(broadcastOnRecordingPaused!=null) ContextCompat.registerReceiver(context, broadcastOnRecordingPaused, pausedFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
            if(broadcastOnRecordingStopped!=null) ContextCompat.registerReceiver(context, broadcastOnRecordingStopped, stoppedFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
            if(broadcastOnRecordingStateCallback!=null) ContextCompat.registerReceiver(context, broadcastOnRecordingStateCallback, callbackFilter, ContextCompat.RECEIVER_NOT_EXPORTED);

            isStateReceiversRegistered = true; // Mark as registered
            Log.d(TAG,"Registered ALL recording state receivers.");
        } catch(Exception e) {
            Log.e(TAG,"Error registering recording state receivers", e);
            isStateReceiversRegistered = false; // Reset flag on error
        }
    }

    /** Helper to register the ACTION_RECORDING_COMPLETE receiver */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerRecordingCompleteReceiver(Context context) {
        if (isCompletionReceiverRegistered) return;
        if (recordingCompleteReceiver == null) {
            initializeRecordingCompleteReceiver();
            if (recordingCompleteReceiver == null) {Log.e(TAG,"Cannot register: Failed to initialize completion receiver!"); return;}
        }
        IntentFilter filter = new IntentFilter(Constants.ACTION_RECORDING_COMPLETE);
        try {
            ContextCompat.registerReceiver(context, recordingCompleteReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            isCompletionReceiverRegistered = true; // Use specific flag
            Log.d(TAG, "ACTION_RECORDING_COMPLETE receiver registered.");
        } catch (Exception e) { Log.e(TAG, "Error registering ACTION_RECORDING_COMPLETE receiver", e); isCompletionReceiverRegistered = false; }
    }

    /** Helper to register the Torch receiver */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerTorchReceiver(Context context) {
        if (isTorchReceiverRegistered) return;
        if (torchReceiver == null) {
            initializeTorchReceiver();
            if (torchReceiver == null) {Log.e(TAG,"Cannot register: Failed init torch receiver"); return;}
        }
        IntentFilter filter = new IntentFilter(Constants.BROADCAST_ON_TORCH_STATE_CHANGED);
        try{
            ContextCompat.registerReceiver(context, torchReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            isTorchReceiverRegistered = true; // Use specific flag
            Log.d(TAG,"Torch receiver registered.");
        } catch (Exception e) { Log.e(TAG, "Error registering Torch Receiver", e); isTorchReceiverRegistered = false;}
    }

    // --- Ensure Unregistration Logic ---
    // Place this method in HomeFragment.java and call it from onStop()
    private void unregisterBroadcastReceivers() {
        if (getContext() == null) { Log.w(TAG,"Cannot unregister, context null."); return; }
        Context safeContext = requireContext();
        Log.i(TAG, "Unregistering ALL Broadcast Receivers...");

        // Unregister State Receivers
        if (isStateReceiversRegistered) { // Check flag before trying
            BroadcastReceiver[] stateReceivers = { broadcastOnRecordingStarted, broadcastOnRecordingResumed, broadcastOnRecordingPaused, broadcastOnRecordingStopped, broadcastOnRecordingStateCallback };
            for (BroadcastReceiver receiver : stateReceivers) { if (receiver != null) try { safeContext.unregisterReceiver(receiver); } catch (IllegalArgumentException e) { /* Ignore */ } }
            isStateReceiversRegistered = false; // Reset flag
            Log.d(TAG,"Unregistered recording state receivers.");
        } else Log.d(TAG,"State receivers already unregistered.");


        // Unregister Completion Receiver
        if (isCompletionReceiverRegistered && recordingCompleteReceiver != null) {
            try { safeContext.unregisterReceiver(recordingCompleteReceiver); Log.d(TAG,"Unregistered Completion receiver."); } catch (IllegalArgumentException e) { /* Ignore */ }
            isCompletionReceiverRegistered = false; // Reset flag
        }

        // Unregister Torch Receiver
        if (isTorchReceiverRegistered && torchReceiver != null) {
            try { safeContext.unregisterReceiver(torchReceiver); Log.d(TAG,"Unregistered Torch receiver."); } catch (IllegalArgumentException e) { /* Ignore */ }
            isTorchReceiverRegistered = false; // Reset flag
            torchReceiver = null; // Nullify to ensure re-initialization in onResume
        }
        Log.i(TAG, "Finished unregistering receivers.");
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
        Log.d(TAG, "onViewCreated: View created.");

        // Initialize SharedPreferencesManager
        sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());

        // Initialize ExecutorService
        if (executorService == null || executorService.isShutdown()) {
            executorService = Executors.newSingleThreadExecutor();
        }

        initializeViews(view);
        setupTextureView(view);
        setupButtonListeners();
        setupLongPressListener(); // For Easter eggs on title
        setupClockLongPressListener(); // For display options on clock
        setupAppLogoLongPressListener(view); // <<< CALL NEW METHOD

        vibrator = (Vibrator) requireActivity().getSystemService(Context.VIBRATOR_SERVICE);
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

        // *** ADDED FIX: Load preview enabled state from SharedPreferences ***
        if (sharedPreferencesManager == null) { // Ensure manager is initialized
            sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());
        }
        isPreviewEnabled = sharedPreferencesManager.isPreviewEnabled(); // Initialize with saved state
        Log.d(TAG, "onViewCreated: Loaded isPreviewEnabled state = " + isPreviewEnabled);
        // --- END FIX ---

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

        // updateTip(); // Duplicate call? Check if startTipsAnimation is sufficient
        startTipsAnimation();
        setupButtonListeners();
        setupLongPressListener();
        updatePreviewVisibility(); // CRUCIAL: Update visibility based on the loaded state

        buttonTorchSwitch = view.findViewById(R.id.buttonTorchSwitch);
        initializeTorch();
        setupTorchButton();

        // ----- Fix Start for this method(onViewCreated)-----
        // Apply saved clock card color
        applyClockCardColor(sharedPreferencesManager.getClockCardColor());
        // ----- Fix Ended for this method(onViewCreated)-----

        // Attempt to find camera with flash
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

    private void setupTextureView(@NonNull View view) {
        textureView = view.findViewById(R.id.textureView);
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
                Log.d(TAG, "onSurfaceTextureAvailable: SurfaceTexture is now available.");
                // ----- Fix Start for this method(onSurfaceTextureAvailable)-----
                textureViewSurface = new Surface(surfaceTexture);
                if (isPreviewEnabled && isRecordingOrPaused()) {
                    Log.d(TAG, "onSurfaceTextureAvailable: Preview enabled and recording active, sending surface to service.");
                    updateServiceWithCurrentSurface(textureViewSurface);
                } else {
                    Log.d(TAG, "onSurfaceTextureAvailable: Preview not enabled or not recording/paused, not sending surface yet.");
                }
                // ----- Fix Ended for this method(onSurfaceTextureAvailable)-----
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                Log.d(TAG, "onSurfaceTextureDestroyed: SurfaceTexture is being destroyed.");
                // ----- Fix Start for this method(onSurfaceTextureDestroyed)-----
                if (textureViewSurface != null) {
                    if (isRecordingOrPaused()) {
                        Log.d(TAG, "onSurfaceTextureDestroyed: Recording active, sending null surface to service.");
                        updateServiceWithCurrentSurface(null);
                    }
                    textureViewSurface.release();
                    textureViewSurface = null;
                    Log.d(TAG, "onSurfaceTextureDestroyed: Released local textureViewSurface.");
                }
                // ----- Fix Ended for this method(onSurfaceTextureDestroyed)-----
                return true; // Surface is released by the listener
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

    // --- Start Recording ---
    // Inside HomeFragment.java
    private void startRecording() {
        if (getContext() == null) {
            Log.e(TAG, "Context is null, cannot start recording.");
            return;
        }
        performHapticFeedback();

        if (!areEssentialPermissionsGranted()) {
            Log.w(TAG, "Essential permissions not granted. Cannot start recording.");
            // ----- Fix Start for this method(startRecording_correctString)-----
            Utils.showQuickToast(getContext(), getString(R.string.essential_permissions_missing));
            // ----- Fix Ended for this method(startRecording_correctString)-----
            requestEssentialPermissions(); // Re-trigger permission request
            return;
        }

        // Check for SAF permission before starting recording if custom storage is selected
        String storageMode = sharedPreferencesManager.getStorageMode();
        if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode)) {
            String customUriString = sharedPreferencesManager.getCustomStorageUri();
            if (customUriString == null || !hasSafPermission(Uri.parse(customUriString))) {
                // ----- Fix Start for this method(startRecording_safPermissionString)-----
                Utils.showQuickToast(getContext(), getString(R.string.saf_permission_missing_dialog_instructions));
                // ----- Fix Ended for this method(startRecording_safPermissionString)-----
                // Guide user to grant permission via settings or a dedicated button
                // Potentially open a dialog or navigate to settings fragment
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.saf_permission_missing_dialog_title))
                        .setMessage(getString(R.string.saf_permission_missing_dialog_message_for_start_recording))
                        .setPositiveButton(getString(R.string.grant_permission_button), (dialog, which) -> {
                            // Intent to open document tree, user selects directory
                            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                            // Optionally, you can suggest a starting URI
                            // intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, someDefaultUri);
                            startActivityForResult(intent, Constants.REQUEST_CODE_OPEN_DOCUMENT_TREE_FOR_SAF);
                        })
                        .setNegativeButton(getString(R.string.universal_cancel), null)
                        .show();
                return;
            }
        }

        if (isMyServiceRunning(RecordingService.class)) {
            Log.w(TAG, "Start requested, but service appears to be already running or starting. Current state: " + recordingState);
            // Query the service for its actual state if unsure
            Intent queryIntent = new Intent(getContext(), RecordingService.class);
            queryIntent.setAction(Constants.BROADCAST_ON_RECORDING_STATE_REQUEST);
            ContextCompat.startForegroundService(getContext(), queryIntent);
            // UI should update based on the broadcast from the service
            return; // Don't try to start again if it might be running
        }

        Log.d(TAG, "startRecording: Starting RecordingService.");
        Intent serviceIntent = new Intent(getContext(), RecordingService.class);
        serviceIntent.setAction(Constants.INTENT_ACTION_START_RECORDING);

        // ----- Fix Start for this method(startRecording_passTorchState)-----
        // Pass current torch state (from HomeFragment's perspective) to the service
        // The service will use this to set the initial FLASH_MODE in its CaptureRequest if it starts successfully.
        Log.d(TAG, "Passing initial torch state to service: " + isTorchOn);
        serviceIntent.putExtra(Constants.INTENT_EXTRA_INITIAL_TORCH_STATE, isTorchOn);
        // ----- Fix Ended for this method(startRecording_passTorchState)-----

        // Pass the surface if preview is enabled and surface is valid
        if (isPreviewEnabled && textureViewSurface != null && textureViewSurface.isValid()) {
            Log.d(TAG, "Preview enabled, passing valid surface to service.");
            serviceIntent.putExtra("SURFACE", textureViewSurface);
        } else {
            Log.w(TAG, "Preview disabled or surface invalid. Service will start without preview surface.");
            serviceIntent.putExtra("SURFACE", (Surface) null); // Explicitly pass null
        }

        ContextCompat.startForegroundService(getContext(), serviceIntent);
        // UI state changes will be handled by broadcast receivers
        // setUIForRecordingActive(); // Move UI update to onRecordingStarted broadcast receiver
        Log.d(TAG, "startRecording: RecordingService start initiated.");
    }

    // Inside HomeFragment.java

    /**
     * Helper method to disable buttons typically unavailable during
     * recording initiation, stopping transitions, or active recording/pausing.
     * Checks if views exist before modifying them.
     */
    private void disableInteractionButtons() {
        Log.d(TAG,"Attempting to disable interaction buttons.");
        if (!isAdded() || getView() == null) { // Extra check for view availability
            Log.w(TAG, "disableInteractionButtons: View not available, cannot disable buttons.");
            return;
        }
        try {
            // Null checks are crucial inside helper methods
            if (buttonStartStop != null) {
                buttonStartStop.setEnabled(false);
                Log.v(TAG,"Disabled: Start/Stop Button"); // Verbose log
            } else Log.w(TAG,"buttonStartStop is null in disableInteractionButtons");

            if (buttonPauseResume != null) {
                buttonPauseResume.setEnabled(false);
                Log.v(TAG,"Disabled: Pause/Resume Button");
            } else Log.w(TAG,"buttonPauseResume is null in disableInteractionButtons");

            if (buttonCamSwitch != null) {
                buttonCamSwitch.setEnabled(false);
                Log.v(TAG,"Disabled: Camera Switch Button");
            } else Log.w(TAG,"buttonCamSwitch is null in disableInteractionButtons");

            // if (buttonTorchSwitch != null) {
            //     buttonTorchSwitch.setEnabled(false); // Also disable torch during these states
            //     Log.v(TAG,"Disabled: Torch Button");
            // } else Log.w(TAG,"buttonTorchSwitch is null in disableInteractionButtons");

            Log.d(TAG,"Interaction buttons disabled.");

        } catch (Exception e) {
            // Catch potential NPE or other issues if views are somehow null unexpectedly
            Log.e(TAG, "Error occurred while disabling interaction buttons", e);
        }
    }

    private void updateRecordingSurface()
    {
        SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();

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
        if (cardClock != null) {
            cardClock.setOnLongClickListener(v -> {
                performHapticFeedback();
                // ----- Fix Start for this method(setupClockLongPressListener)-----
                // Show existing display options and new color chooser
                showClockAppearanceDialog();
                // ----- Fix Ended for this method(setupClockLongPressListener)-----
                return true;
            });
        }
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
        // ----- Fix Start for this method(showDisplayOptionsDialog_revert)-----
        final String[] items = {
                getString(R.string.dialog_clock_timeonly),
                getString(R.string.dialog_clock_englishtime),
                getString(R.string.dialog_clock_Islamic_calendar) // This was likely intended to be the "Everything" option
        };
        // The mapping of options to array indices for `setSingleChoiceItems` is:
        // 0 -> Time Only
        // 1 -> Time and English Date (Day/Month)
        // 2 -> Time, English Date, and Hijri Date (Everything)
        int currentOption = getCurrentDisplayOption(); // This returns 0, 1, or 2

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.dialog_clock_title))
                .setSingleChoiceItems(items, currentOption, (dialog, which) -> {
                    saveDisplayOption(which); // Save the selected index (0, 1, or 2)
                    updateClock(); // Update the widget based on the selected option
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.universal_cancel, null) // Changed from OK to Cancel, and removed positive button action
                .show();
        // ----- Fix Ended for this method(showDisplayOptionsDialog_revert)-----
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

    // --- Stop Recording ---
    private void stopRecording() {
        if (!isAdded() || getActivity() == null) { Log.w(TAG,"Stop: Not attached"); return; }
        if(recordingState == RecordingState.NONE){ Log.w(TAG,"Stop clicked but state NONE?"); return; } // Prevent multi-stop

        Log.i(TAG, ">> stopRecording user action");
        disableInteractionButtons(); Log.d(TAG,"stopRecording: Btns disabled.");

        Intent stopIntent = new Intent(getActivity(), RecordingService.class);
        stopIntent.setAction(Constants.INTENT_ACTION_STOP_RECORDING);
        try {
            requireActivity().startService(stopIntent); Log.i(TAG, "Sent STOP intent.");
        } catch (Exception e) {
            Log.e(TAG, "Error sending STOP intent: ", e); Toast.makeText(getContext(),"Error stop svc", Toast.LENGTH_SHORT).show();
            resetUIButtonsToIdleState(); Log.d(TAG, "stopIntent fail: UI Reset.");
        }
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
        if (buttonTorchSwitch == null) {
            Log.e(TAG, "buttonTorchSwitch is null, cannot set up listener.");
            return;
        }

        // Initial state update
        // updateTorchButtonState(isTorchOn); // This might be called too early, consider moving or ensuring isTorchOn is accurate

        buttonTorchSwitch.setOnClickListener(v -> {
            // ----- Fix Start for this method(setupTorchButton_onClick)-----
            if (!isAdded() || getContext() == null) {
                Log.w(TAG, "Torch button clicked, but fragment not fully ready.");
                return;
            }

            if (isRecordingOrPaused()) {
                Log.d(TAG, "Recording active. Sending toggle intent to RecordingService. Current isTorchOn (UI state): " + isTorchOn);
                Intent serviceIntent = new Intent(getContext(), RecordingService.class);
                serviceIntent.setAction(Constants.INTENT_ACTION_TOGGLE_RECORDING_TORCH);
                try {
                    ContextCompat.startForegroundService(getContext(), serviceIntent);
                } catch (Exception e) {
                    Log.e(TAG, "Error starting RecordingService for torch toggle", e);
                }
            } else {
                Log.d(TAG, "Not recording. Toggling torch directly. Current isTorchOn: " + isTorchOn);
                // Assuming toggleTorch() correctly handles CameraManager.setTorchMode,
                // updates isTorchOn field, and calls updateTorchUI().
                toggleTorch();
            }
            // ----- Fix Ended for this method(setupTorchButton_onClick)-----
        });

        // Initialize and register the broadcast receiver for torch state changes from the service
        // This is important for when the service toggles the torch (e.g., during recording)
        // and the UI needs to reflect that change.
        if (torchReceiver == null) { // Ensure it's initialized only once
            initializeTorchReceiver();
        }
        if (getContext() != null && !isTorchReceiverRegistered) { // Check context and registration status
            ContextCompat.registerReceiver(
                    requireContext(),
                    torchReceiver,
                    new IntentFilter(Constants.BROADCAST_ON_TORCH_STATE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED
            );
            isTorchReceiverRegistered = true;
            Log.d(TAG, "Torch state change receiver registered in setupTorchButton.");
        }
         // Fetch initial torch state and update UI (if not already handled by onResume/onStart)
        // This part might need careful placement depending on overall lifecycle management
        // For now, assuming 'isTorchOn' is correctly initialized elsewhere (e.g. initializeTorch())
        // and 'updateTorchUI' correctly updates the button.
        // updateTorchUI(isTorchOn); // This updates the button drawable
    }

    // Ensure toggleTorch method exists and correctly uses CameraManager.setTorchMode
    // and updates the local isTorchOn variable and calls updateTorchUI.
    // Example (ensure your actual toggleTorch matches this logic):
    private void toggleTorch() {
        if (cameraManager == null) {
            Log.e(TAG, "CameraManager not available to toggle torch.");
            return;
        }
        String currentCameraId = getCameraIdForTorch(); // Helper to get current camera ID
        if (currentCameraId == null) {
            Log.e(TAG, "No valid camera ID found for torch.");
            return;
        }

        try {
            isTorchOn = !isTorchOn;
            cameraManager.setTorchMode(currentCameraId, isTorchOn);
            Log.d(TAG, "Torch toggled directly via CameraManager. New state: " + isTorchOn + " for camera " + currentCameraId);
            updateTorchUI(isTorchOn); // Update button appearance and any other UI
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to toggle torch directly", e);
            isTorchOn = !isTorchOn; // Revert state on error
            updateTorchUI(isTorchOn); // Update UI back
            Utils.showQuickToast(getContext(), "Error toggling torch.");
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to toggle torch: Camera device " + currentCameraId + " is no longer connected or available.",e);
            // This can happen if the camera is closed or in use by another app.
            // Reset torch state and UI.
            isTorchOn = false;
            updateTorchUI(false);
            Utils.showQuickToast(getContext(), "Torch unavailable.");
        }
    }

    private String getCameraIdForTorch() {
        // This method should return the ID of the camera that HomeFragment
        // is currently configured to use for its general operations (like preview if it had one, or torch).
        // It might be based on SharedPreferencesManager.getCameraSelection() and SharedPreferencesManager.getSelectedBackCameraId()
        // This is a simplified placeholder. You need to ensure this returns the correct, active camera ID.
        if(this.cameraId != null) return this.cameraId; // If HomeFragment already has a determined primary camera ID

        // Fallback or more complex logic to determine the appropriate camera ID:
        try {
            if (cameraManager == null && getContext() != null) {
                 cameraManager = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
            }
            if (cameraManager == null) return null;

            CameraType selectedType = sharedPreferencesManager.getCameraSelection();
            if (selectedType == CameraType.FRONT) {
                for (String id : cameraManager.getCameraIdList()) {
                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                    Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    Boolean flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT && flashAvailable != null && flashAvailable) {
                        return id;
                    }
                }
            } else { // BACK
                String preferredBackId = sharedPreferencesManager.getSelectedBackCameraId();
                if(preferredBackId != null){
                     CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(preferredBackId);
                     Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                     Boolean flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                     if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK && flashAvailable != null && flashAvailable) {
                         return preferredBackId;
                     }
                }
                // Fallback to default back camera if preferred is not suitable or not found
                for (String id : cameraManager.getCameraIdList()) {
                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                    Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    Boolean flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK && flashAvailable != null && flashAvailable) {
                        return id; // Return first available back camera with flash
                    }
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error accessing camera for torch ID", e);
        }
        return null; // No suitable camera found
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
                    // Ensure buttonTorchSwitch is not null
                    if (buttonTorchSwitch == null) {
                        Log.w("TorchDebug", "updateTorchUI: buttonTorchSwitch is null, cannot update.");
                        return;
                    }

                    buttonTorchSwitch.setIcon(AppCompatResources.getDrawable(
                        requireContext(),
                        R.drawable.ic_flashlight_on // Icon itself might not need to change, selector handles tint
                    ));
                    buttonTorchSwitch.setSelected(isOn); // This controls the visual feedback (e.g., tint)
                    // DO NOT set enabled state here; it's handled by recording state UI methods.
                    Log.d("TorchDebug", "Torch UI updated (selected state): " + isOn + ", Enabled: " + buttonTorchSwitch.isEnabled());
                } catch (Exception e) {
                    Log.e("TorchDebug", "Error updating torch UI: " + e.getMessage());
                }
            });
        }
    }

    private void setupAppLogoLongPressListener(View view) {
        ImageView appLogo = view.findViewById(R.id.ivAppTitle);
        if (appLogo != null) {
            appLogo.setOnLongClickListener(v -> {
                performHapticFeedback();
                Log.i(TAG, "App logo long-pressed. Navigating to TrashFragment manually.");
                // ----- Fix Start for this method (setupAppLogoLongPressListener) -----
                // Navigate to TrashFragment manually
                try {
                    TrashFragment trashFragment = new TrashFragment();
                    FragmentManager fragmentManager = getParentFragmentManager(); // Use getParentFragmentManager
                    FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();

                    // ----- Fix Start for this navigation block -----
                    // Make the overlay container visible
                    View overlayContainer = requireActivity().findViewById(R.id.overlay_fragment_container);
                    if (overlayContainer != null) {
                        overlayContainer.setVisibility(View.VISIBLE);
                    } else {
                        Log.e(TAG, "R.id.overlay_fragment_container not found in MainActivity! Cannot show TrashFragment.");
                        Toast.makeText(getContext(), "Error opening trash (container not found).", Toast.LENGTH_SHORT).show();
                        return true; // Consume click but don't proceed
                    }

                    fragmentTransaction.replace(R.id.overlay_fragment_container, trashFragment);
                    // ----- Fix Ended for this navigation block -----
                    fragmentTransaction.addToBackStack(null); 
                    fragmentTransaction.commit();
                } catch (Exception e) {
                    Log.e(TAG, "Manual navigation to TrashFragment failed.", e);
                    Toast.makeText(getContext(), "Error opening trash.", Toast.LENGTH_SHORT).show();
                }
                // ----- Fix Ended for this method (setupAppLogoLongPressListener) -----
                return true; // Consume the long click
            });
        }
    }

    // ----- Fix Start for this class (HomeFragment) -----
    private void initializeViews(View view) {
        Log.d(TAG, "initializeViews: Finding UI elements.");
        tvStorageInfo = view.findViewById(R.id.tvStorageInfo);
        tvPreviewPlaceholder = view.findViewById(R.id.tvPreviewPlaceholder);
        buttonStartStop = view.findViewById(R.id.buttonStartStop);
        buttonPauseResume = view.findViewById(R.id.buttonPauseResume);
        buttonCamSwitch = view.findViewById(R.id.buttonCamSwitch);
        cardPreview = view.findViewById(R.id.cardPreview); // Assuming R.id.cardPreview exists
        vibrator = (Vibrator) requireActivity().getSystemService(Context.VIBRATOR_SERVICE);

        // Clock related views
        cardClock = view.findViewById(R.id.cardClock); // Corrected ID
        tvClock = view.findViewById(R.id.tvClock);
        tvDateEnglish = view.findViewById(R.id.tvDateEnglish);
        tvDateArabic = view.findViewById(R.id.tvDateArabic);

        // Tip view
        tvTip = view.findViewById(R.id.tvTip);

        // Stats view
        tvStats = view.findViewById(R.id.tvStats); // Assuming R.id.tvStats for video count/size

        // Torch button (already initialized elsewhere, but good to have it consistently)
        buttonTorchSwitch = view.findViewById(R.id.buttonTorchSwitch);

        // Initialize other views as needed here.
        // textureView is handled by setupTextureView
    }
    // ----- Fix Ended for this class (HomeFragment) -----

    // ----- Fix Start for this method(isRecordingOrPaused)-----
    private boolean isRecordingOrPaused() {
        return recordingState == RecordingState.IN_PROGRESS || recordingState == RecordingState.PAUSED;
    }
    // ----- Fix Ended for this method(isRecordingOrPaused)-----

    // ----- Fix Start for this method(updateServiceWithCurrentSurface)-----
    // This method replaces/refines the old updateRecordingSurface
    private void updateServiceWithCurrentSurface(@Nullable Surface surfaceToUse) {
        if (!isAdded() || getContext() == null) {
            Log.w(TAG, "updateServiceWithCurrentSurface: Fragment not added or context is null, cannot send surface update.");
            return;
        }

        Intent intent = new Intent(getContext(), RecordingService.class);
        intent.setAction(Constants.INTENT_ACTION_CHANGE_SURFACE);
        if (surfaceToUse != null && surfaceToUse.isValid()) {
            intent.putExtra("SURFACE", surfaceToUse);
            Log.d(TAG, "updateServiceWithCurrentSurface: Sending new VALID surface to RecordingService.");
        } else {
            intent.putExtra("SURFACE", (Surface) null); 
            Log.d(TAG, "updateServiceWithCurrentSurface: Sending NULL surface to RecordingService (preview disabled or surface invalid/destroyed).");
        }
        // Use requireContext() for starting service if preferred and appropriate for fragment version
        Context context = getContext();
        if (context != null) {
            context.startService(intent);
        }
    }
    // ----- Fix Ended for this method(updateServiceWithCurrentSurface)-----

    // ----- Fix Start for this class (HomeFragment) -----
    private boolean isMyServiceRunning(Class<?> serviceClass) {
        if (getContext() == null) {
            return false;
        }
        ActivityManager manager = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) {
            return false;
        }
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }
    // ----- Fix Ended for this class (HomeFragment) -----

    // ----- Fix Start for this class (HomeFragment_clock_color_picker) -----
    private void applyClockCardColor(String colorHex) {
        if (cardClock != null && colorHex != null) {
            try {
                cardClock.setCardBackgroundColor(Color.parseColor(colorHex));
                Log.d(TAG, "Applied clock card color: " + colorHex);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Invalid color hex for clock card: " + colorHex, e);
                // Optionally apply default color if parse fails
                cardClock.setCardBackgroundColor(Color.parseColor(SharedPreferencesManager.DEFAULT_CLOCK_CARD_COLOR));
            }
        }
    }

    private void showClockAppearanceDialog() {
        final String[] appearanceOptions = {"Change Clock Display", "Change Clock Color"};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Clock Appearance")
                .setItems(appearanceOptions, (dialog, which) -> {
                    if (which == 0) { // Change Clock Display
                        showDisplayOptionsDialog();
                    } else if (which == 1) { // Change Clock Color
                        showClockColorChooserDialog();
                    }
                })
                .setNegativeButton(R.string.universal_cancel, null)
                .show();
    }

    private void showClockColorChooserDialog() {
        String currentSelectedColorHex = sharedPreferencesManager.getClockCardColor();
        int currentSelectedColorIndex = -1;
        for (int i = 0; i < CLOCK_COLOR_HEX_VALUES.length; i++) {
            if (CLOCK_COLOR_HEX_VALUES[i].equalsIgnoreCase(currentSelectedColorHex)) {
                currentSelectedColorIndex = i;
                break;
            }
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Choose Clock Background Color")
                .setSingleChoiceItems(CLOCK_COLOR_NAMES, currentSelectedColorIndex, (dialog, which) -> {
                    String selectedColorHex = CLOCK_COLOR_HEX_VALUES[which];
                    sharedPreferencesManager.setClockCardColor(selectedColorHex);
                    applyClockCardColor(selectedColorHex);
                    Log.d(TAG, "User selected clock color: " + CLOCK_COLOR_NAMES[which] + " (" + selectedColorHex + ")");
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.universal_cancel, null)
                .show();
    }
    // ----- Fix Ended for this class (HomeFragment_clock_color_picker) -----
}