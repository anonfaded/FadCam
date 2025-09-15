package com.fadcam.ui;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
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
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.Html;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.Formatter;
import android.text.style.ForegroundColorSpan;
import android.util.Range;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView; // <<< ADD IMPORT FOR ImageView
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.cardview.widget.CardView; // Add this
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.FragmentManager; // <<< ADD IMPORT FOR FragmentManager
import androidx.fragment.app.FragmentTransaction; // <<< ADD IMPORT FOR FragmentTransaction
import com.fadcam.CameraType;
import com.fadcam.Constants;
import com.fadcam.Log;
import com.fadcam.R;
import com.fadcam.RecordingControlIntents;
import com.fadcam.RecordingState;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.Utils;
import com.fadcam.services.RecordingService;
import com.fadcam.services.TorchService;
import com.fadcam.ui.helpers.HomeFragmentHelper;
import com.fadcam.utils.DebouncedRunnable;
import com.fadcam.utils.DeviceHelper;
import com.fadcam.utils.StorageInfoCache;
import com.fadcam.utils.VideoStatsCache;
import com.google.android.material.appbar.MaterialToolbar;
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
import java.util.HashSet; // For combining lists
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.Set; // For combining lists
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HomeFragment extends BaseFragment {

    private static final String TAG = "HomeFragment";
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 100;

    // -------------- Fix Start (HomeFragment) --------------
    private HomeFragmentHelper fragmentHelper;
    // -------------- Fix Ended (HomeFragment) --------------

    // ----- Fix Start for this method(fields)-----
    private static final String[] CLOCK_COLOR_NAMES = {
        "Purple",
        "Blue",
        "Green",
        "Teal",
        "Orange",
        "Red",
        "Dark Grey",
        "App Theme Dark",
        "Amoled Gray",
        "Gold",
        "Pink",
    };
    private static final String[] CLOCK_COLOR_HEX_VALUES = {
        "#673AB7",
        "#2196F3",
        "#4CAF50",
        "#009688",
        "#FF9800",
        "#F44336",
        "#424242",
        "#302745",
        "#CCCCCC",
        "#FFD700",
        "#F06292",
    };
    // ----- Fix Ended for this method(fields)-----

    private long recordingStartTime;
    private long videoBitrate;

    private double latitude;
    private double longitude;

    private Handler handlerClock = new Handler();
    private Runnable updateInfoRunnable;
    private Runnable updateClockRunnable; // Declare here
    
    // -------------- Fix Start (debounced runnables for button clicks)-----------
    private DebouncedRunnable debouncedStartRecording;
    private DebouncedRunnable debouncedStopRecording;
    // -------------- Fix Ended (debounced runnables for button clicks)-----------

    // Storage calculation executor for background processing
    private ExecutorService executorService;

    private TextureView textureView;

    private Handler tipHandler = new Handler();
    private int typingIndex = 0;
    private boolean isTypingIn = true;
    private String currentTip = "";

    private TextView tvCameraTitle;
    private TextView tvCameraSubtitle;
    private TextView ivCameraIcon;
    private TextView tvEstimateTitle;
    private TextView tvEstimateSubtitle;
    private TextView tvSpaceTitle;
    private TextView tvSpaceSubtitle;
    // inline total will be rendered in tvSpaceTitle using spans
    private TextView tvElapsedTitle;
    private TextView tvElapsedSubtitle;
    private TextView tvRemainingTitle;
    private TextView tvRemainingSubtitle;
    private ImageView btnHamburgerMenu;
    private TextView tvPreviewPlaceholder;
    private MaterialButton buttonStartStop;
    private MaterialButton buttonPauseResume;
    private Button buttonCamSwitch;
    private boolean isPreviewEnabled = true;

    private View cardPreview;
    private Vibrator vibrator;

    private CardView cardClock;
    private TextView tvClock, tvDateEnglish, tvDateArabic;

    // FragmentActivity tipres = requireActivity();
    private String[] tips;

    private int currentTipIndex = 0;

    private TextView tvStats;

    // Recording tile controls
    private TextView tileAfToggle;
    private TextView tileExp;
    private TextView tileZoom;

    // overlay (removed - using PickerBottomSheetFragment instead)
    // private com.fadcam.ui.CompactControlOverlay compactOverlay;

    // local control state
    private boolean aeLocked = false;
    private int currentEvIndex = 0; // exposure compensation index
    private int afMode =
        android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO;

    private List<String> messageQueue;
    private List<String> recentlyShownMessages;
    private final Random random = new Random();
    private static final int RECENT_MESSAGE_LIMIT = 3; // Adjust as needed

    private static final int REQUEST_PERMISSIONS = 1;
    private android.os.PowerManager.WakeLock wakeLock;
    // private static final String PREF_FIRST_LAUNCH = "first_launch";

    private RecordingState recordingState = RecordingState.NONE;

    private BroadcastReceiver broadcastOnRecordingStarted;
    private BroadcastReceiver broadcastOnRecordingResumed;
    private BroadcastReceiver broadcastOnRecordingPaused;
    private BroadcastReceiver broadcastOnRecordingStopped;
    private BroadcastReceiver broadcastOnRecordingStateCallback;
    // ----- Fix Start for this class (HomeFragment) -----
    private BroadcastReceiver segmentCompleteStatsReceiver; // For segment completion to update stats
    // ----- Fix Ended for this class (HomeFragment) -----

    // ----- Fix Start for camera resource availability receiver -----
    private BroadcastReceiver cameraResourceAvailabilityReceiver;
    private boolean isCameraResourceAvailabilityReceiverRegistered = false;
    private boolean areCameraResourcesAvailable = true; // Default to true
    // ----- Fix End for camera resource availability receiver -----

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
    // Listener for realtime preference updates (camera/resolution/fps)
    private SharedPreferences.OnSharedPreferenceChangeListener prefsListener;
    private BroadcastReceiver recordingCompleteReceiver;
    // Cache last-known available bytes for custom storage (SD card / SAF) when we
    // cannot probe it directly
    private long lastKnownCustomAvailableBytes = -1;
    // ----- Fix Start for this class (HomeFragment) -----
    // private boolean isStatsReceiverRegistered = false; // This seemed to be for
    // the general recordingCompleteReceiver
    private boolean isSegmentCompleteStatsReceiverRegistered = false;

    // ----- Fix Ended for this class (HomeFragment) -----

    // important
    private void requestEssentialPermissions() {
        Log.d(
            TAG,
            "requestEssentialPermissions: Requesting essential permissions"
        );
        List<String> permissions;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11 and above
            permissions = new ArrayList<>(
                Arrays.asList(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            );

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
                permissions.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        } else {
            // Below Android 11
            permissions = new ArrayList<>(
                Arrays.asList(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                )
            );
        }

        List<String> permissionsToRequest = new ArrayList<>();
        for (String permission : permissions) {
            if (
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    permission
                ) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(
                    TAG,
                    "requestEssentialPermissions: Requesting permission: " +
                    permission
                );
                permissionsToRequest.add(permission);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                permissionsToRequest.toArray(new String[0]),
                REQUEST_PERMISSIONS
            );
        }

        // Request to disable battery optimization
        requestBatteryOptimizationPermission();
    }

    private void requestBatteryOptimizationPermission() {
        android.os.PowerManager powerManager =
            (android.os.PowerManager) requireActivity().getSystemService(
                Context.POWER_SERVICE
            ); // Full path and context adjusted
        String packageName = requireActivity().getPackageName(); // Correct package retrieval

        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                Intent intent = new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                );
                intent.setData(Uri.parse("package:" + packageName));
                startActivity(intent);
            }
        }
    }

    // Call this method when the recording starts to acquire wake lock
    private void acquireWakeLock() {
        android.os.PowerManager powerManager =
            (android.os.PowerManager) requireActivity().getSystemService(
                Context.POWER_SERVICE
            ); // Full path and context adjusted
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MyApp::RecordingLock"
        );

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
        messageQueue = new ArrayList<>(
            Arrays.asList(
                getResources().getStringArray(R.array.easter_eggs_array)
            )
        );
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
            String randomMessage = messageQueue.remove(
                random.nextInt(messageQueue.size())
            );
            tvPreviewPlaceholder.setPadding(
                40,
                tvPreviewPlaceholder.getPaddingTop(),
                40,
                tvPreviewPlaceholder.getPaddingBottom()
            );
            tvPreviewPlaceholder.setText(randomMessage);
            AnimatorSet animatorSet = new AnimatorSet();
            ObjectAnimator scaleDownX = ObjectAnimator.ofFloat(
                tvPreviewPlaceholder,
                "scaleX",
                0.7f
            );
            ObjectAnimator scaleDownY = ObjectAnimator.ofFloat(
                tvPreviewPlaceholder,
                "scaleY",
                0.7f
            );
            scaleDownX.setDuration(150);
            scaleDownY.setDuration(150);
            ObjectAnimator scaleUpX = ObjectAnimator.ofFloat(
                tvPreviewPlaceholder,
                "scaleX",
                1.0f
            );
            ObjectAnimator scaleUpY = ObjectAnimator.ofFloat(
                tvPreviewPlaceholder,
                "scaleY",
                1.0f
            );
            scaleUpX.setDuration(150);
            scaleUpY.setDuration(150);
            ObjectAnimator rotateLeft = ObjectAnimator.ofFloat(
                tvPreviewPlaceholder,
                "rotation",
                0f,
                -3f
            );
            rotateLeft.setDuration(80);
            ObjectAnimator rotateRight = ObjectAnimator.ofFloat(
                tvPreviewPlaceholder,
                "rotation",
                -3f,
                3f
            );
            rotateRight.setDuration(80);
            ObjectAnimator rotateCenter = ObjectAnimator.ofFloat(
                tvPreviewPlaceholder,
                "rotation",
                3f,
                0f
            );
            rotateCenter.setDuration(80);
            final Drawable originalBackground = cardPreview.getBackground();
            // ----- Fix Start: Use gray flash for AMOLED theme (avoid duplicate variable)
            // -----
            String themeName =
                sharedPreferencesManager.sharedPreferences.getString(
                    com.fadcam.Constants.PREF_APP_THEME,
                    Constants.DEFAULT_APP_THEME
                );
            boolean isAmoledLocal =
                "AMOLED".equalsIgnoreCase(themeName) ||
                "Amoled".equalsIgnoreCase(themeName) ||
                "Faded Night".equalsIgnoreCase(themeName);
            int flashColor = isAmoledLocal
                ? Color.parseColor("#232323")
                : Color.parseColor("#302745");
            ValueAnimator colorAnim = ValueAnimator.ofObject(
                new ArgbEvaluator(),
                flashColor,
                Color.RED,
                flashColor
            );
            // ----- Fix End: Use gray flash for AMOLED theme (avoid duplicate variable)
            // -----
            colorAnim.setDuration(300);
            colorAnim.addUpdateListener(animator -> {
                int color = (int) animator.getAnimatedValue();
                cardPreview.setBackgroundColor(color);
            });
            colorAnim.addListener(
                new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        cardPreview.setBackground(originalBackground);
                    }
                }
            );
            AnimatorSet bounceSet = new AnimatorSet();
            bounceSet.playTogether(scaleDownX, scaleDownY);
            AnimatorSet expandSet = new AnimatorSet();
            expandSet.playTogether(scaleUpX, scaleUpY);
            AnimatorSet wobbleSet = new AnimatorSet();
            wobbleSet.playSequentially(rotateLeft, rotateRight, rotateCenter);
            animatorSet.playSequentially(bounceSet, expandSet, wobbleSet);
            animatorSet.start();
            colorAnim.start();
            recentlyShownMessages.add(randomMessage);
            if (recentlyShownMessages.size() > RECENT_MESSAGE_LIMIT) {
                recentlyShownMessages.remove(0); // Remove the oldest message
            }
            Collections.shuffle(messageQueue);
        } else {
            tvPreviewPlaceholder.setText(
                "Oops! No messages available right now."
            );
        }
    }

    private void setupLongPressListener() {
        // Attach long-press to the preview card to preserve original behavior
        cardPreview.setOnLongClickListener(v -> {
            performHapticFeedback();
            // Reuse the extracted handler for toggling preview
            handlePreviewLongPress();
            return true;
        });
    }

    /**
     * Centralized handler for preview long-press actions.
     * This is extracted so it can be invoked from multiple input sources
     * (card long-press, texture long-press via GestureDetector).
     */
    private void handlePreviewLongPress() {
        // When not recording, show a random funny message
        if (!isRecordingOrPaused()) {
            showRandomMessage();
            return;
        }

        // Unified Card Bounce Animation (Down then Up) - only for recording mode
        AnimatorSet cardBounceAnim = new AnimatorSet();
        ObjectAnimator scaleDownX = ObjectAnimator.ofFloat(
            cardPreview,
            "scaleX",
            0.9f
        );
        ObjectAnimator scaleDownY = ObjectAnimator.ofFloat(
            cardPreview,
            "scaleY",
            0.9f
        );
        scaleDownX.setDuration(50); // Fast scale down
        scaleDownY.setDuration(50);

        ObjectAnimator scaleUpX = ObjectAnimator.ofFloat(
            cardPreview,
            "scaleX",
            1.0f
        );
        ObjectAnimator scaleUpY = ObjectAnimator.ofFloat(
            cardPreview,
            "scaleY",
            1.0f
        );
        scaleUpX.setDuration(70); // Fast rebound
        scaleUpY.setDuration(70);

        cardBounceAnim.play(scaleDownX).with(scaleDownY); // Play scale down
        cardBounceAnim.play(scaleUpX).with(scaleUpY).after(scaleDownX); // Play scale up immediately after scale down completes

        cardBounceAnim.addListener(
            new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);

                    // Core logic: toggle preview, update UI, save state (runs AFTER card bounce)
                    boolean wasEnabled = isPreviewEnabled;
                    isPreviewEnabled = !isPreviewEnabled;
                    updatePreviewVisibility(); // Main visual change for enabling/disabling preview
                    savePreviewState();

                    // If we're enabling the preview (it was disabled before), reset the TextureView
                    // to ensure we don't see any stale frames
                    if (!wasEnabled && isPreviewEnabled) {
                        resetTextureView();
                    }

                    // Surface handling logic
                    if (isRecordingOrPaused()) {
                        // Only update service if recording/paused
                        if (
                            isPreviewEnabled &&
                            textureView != null &&
                            textureView.isAvailable() &&
                            textureViewSurface != null
                        ) {
                            Log.d(
                                TAG,
                                "Preview enabled (post-anim): TextureView available, sending surface to service."
                            );
                            updateServiceWithCurrentSurface(textureViewSurface);
                        } else {
                            Log.d(
                                TAG,
                                "Preview enabled (post-anim): TextureView not yet available, will send surface on callback."
                            );
                            updateServiceWithCurrentSurface(null);
                        }
                    } else {
                        Log.d(
                            TAG,
                            "Preview disabled (post-anim): Sending null surface to service."
                        );
                        updateServiceWithCurrentSurface(null);
                    }

                    // Notify sidebar and other listeners about the changed preview state
                    try {
                        Bundle result = new Bundle();
                        result.putBoolean("preview_enabled", isPreviewEnabled);
                        getParentFragmentManager().setFragmentResult(
                            "home_sidebar_result",
                            result
                        );
                    } catch (Exception ignored) {}
                }
            }
        );
        cardBounceAnim.start();
    }

    private void updatePreviewVisibility() {
        // ----- Fix Start for this method(updatePreviewVisibility)-----
        if (!isAdded() || textureView == null || tvPreviewPlaceholder == null) {
            Log.e(
                TAG,
                "updatePreviewVisibility: Fragment not attached or views null"
            );
            return;
        }

        if (isRecording()) {
            if (isPreviewEnabled) {
                // Show preview
                textureView.setVisibility(View.VISIBLE);
                tvPreviewPlaceholder.setVisibility(View.GONE);
                Log.d(TAG, "Preview enabled and recording - showing preview");

                // Ensure surface is sent to service
                if (
                    textureViewSurface != null &&
                    textureViewSurface.isValid() &&
                    isRecordingOrPaused()
                ) {
                    updateServiceWithCurrentSurface(textureViewSurface);
                }
            } else {
                // Hide preview
                textureView.setVisibility(View.INVISIBLE);
                tvPreviewPlaceholder.setVisibility(View.VISIBLE);
                tvPreviewPlaceholder.setText("Long press to enable preview");
                Log.d(
                    TAG,
                    "Preview disabled but recording - showing placeholder"
                );

                // Send null surface to service
                updateServiceWithCurrentSurface(null);
            }
        } else {
            // Not recording, show placeholder
            textureView.setVisibility(View.INVISIBLE);
            tvPreviewPlaceholder.setVisibility(View.VISIBLE);
            tvPreviewPlaceholder.setText(getString(R.string.ui_preview_area));
            Log.d(TAG, "Not recording - showing placeholder text");
        }
        // ----- Fix Ended for this method(updatePreviewVisibility)-----
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
        // requestEssentialPermissions(); // <-- Disabled, handled in onboarding only

        sharedPreferencesManager = SharedPreferencesManager.getInstance(
            requireContext()
        );

        // Check if it's the first launch
        // boolean isFirstLaunch = sharedPreferences.getBoolean(PREF_FIRST_LAUNCH,
        // true);
        // if (isFirstLaunch) {
        // // Request essential permissions
        // requestEssentialPermissions();
        //
        // // Set first launch to false
        // sharedPreferences.edit().putBoolean(PREF_FIRST_LAUNCH, false).apply();
        // }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public void onStart() {
        super.onStart();
        Log.d(TAG, "onStart: HomeFragment");
        // Moved receiver registration here from onResume for consistency
        // and to ensure they are ready before any onResume logic might need them.
        registerBroadcastReceivers(); // Centralized registration

        // Initialize SharedPreferencesManager if null
        if (sharedPreferencesManager == null) {
            sharedPreferencesManager = SharedPreferencesManager.getInstance(
                requireContext()
            );
        }
        // Initialize ExecutorService if null or shutdown
        if (executorService == null || executorService.isShutdown()) {
            executorService = Executors.newSingleThreadExecutor();
        }

        // Register SharedPreferences listener for realtime updates to storage widget
        if (sharedPreferencesManager != null && prefsListener == null) {
            prefsListener = (sharedPreferences, key) -> {
                if (key == null) return;
                switch (key) {
                    case Constants.PREF_CAMERA_SELECTION:
                    case Constants.PREF_VIDEO_RESOLUTION_WIDTH:
                    case Constants.PREF_VIDEO_RESOLUTION_HEIGHT:
                    case Constants.PREF_VIDEO_FRAME_RATE:
                    case Constants.PREF_VIDEO_FRAME_RATE_FRONT:
                    case Constants.PREF_VIDEO_FRAME_RATE_BACK:
                    case Constants.PREF_AUDIO_BITRATE: // audio bitrate changed
                    case "bitrate_mode_custom": // custom bitrate mode toggled
                    case "bitrate_custom_value": // custom bitrate value changed (kbps)
                    case Constants.PREF_VIDEO_BITRATE:
                    case SharedPreferencesManager.PREF_VIDEO_ORIENTATION: // video orientation changed
                        refreshPrefsAndUpdateStorage();
                        break;
                    default:
                        break;
                }
            };
            sharedPreferencesManager.sharedPreferences.registerOnSharedPreferenceChangeListener(
                prefsListener
            );
        }

        // ----- Fix Start: Set default camera resource availability -----
        // Set camera resources as available by default when starting
        areCameraResourcesAvailable = true;
        // ----- Fix End: Set default camera resource availability -----

        // Fetch initial state and update UI
        fetchRecordingState(); // Get current service state
        updateStats(); // Update file count/size stats
        updateStorageInfo(); // Update available storage info
        updateClock(); // Update clock display
        startUpdatingClock(); // Start periodic clock updates
        startUpdatingInfo(); // Start periodic storage/estimate updates
        showCurrentCameraSelection(); // Show selected camera
        // Restore preview state (use centralized SharedPreferencesManager API)
        isPreviewEnabled = sharedPreferencesManager.isPreviewEnabled();
        updatePreviewVisibility();

        // ----- Fix Start for this method(onStart) -----
        // Only register segment complete stats receiver if fragment is attached
        if (
            isAdded() &&
            !isDetached() &&
            getActivity() != null &&
            !getActivity().isFinishing()
        ) {
            try {
                registerSegmentCompleteStatsReceiver(requireContext());
            } catch (IllegalStateException e) {
                Log.e(
                    TAG,
                    "Failed to register segment complete stats receiver",
                    e
                );
            }
        } else {
            Log.d(
                TAG,
                "Skipping segment stats receiver registration - fragment not in valid state"
            );
        }
        // ----- Fix Ended for this method(onStart) -----

        // ----- Fix Start: Remove duplicate registration since it's now in
        // registerBroadcastReceivers -----
        // Camera resource availability registration is now handled in
        // registerBroadcastReceivers()
        // ----- Fix End: Remove duplicate registration -----

        // Ensure we have the latest state
        fetchRecordingState();

        // ----- Update Check Bottom Sheet Start -----
        if (
            com.fadcam.ui.SettingsFragment.isAutoUpdateCheckEnabled(
                requireContext()
            ) &&
            DeviceHelper.isInternetAvailable(requireContext())
        ) {
            // Only show once per app open
            if (
                getParentFragmentManager().findFragmentByTag(
                    "UpdateAvailableBottomSheet"
                ) ==
                null
            ) {
                ExecutorService updateExecutor =
                    Executors.newSingleThreadExecutor();
                updateExecutor.execute(() -> {
                    try {
                        java.net.URL url = new java.net.URL(
                            "https://github.com/anonfaded/FadCam/releases/latest"
                        );
                        java.net.HttpURLConnection connection =
                            (java.net.HttpURLConnection) url.openConnection();
                        connection.setRequestMethod("GET");
                        connection.setInstanceFollowRedirects(false); // Do not follow redirects
                        connection.connect();
                        String location = connection.getHeaderField("Location");
                        connection.disconnect();
                        String latestVersion = null;
                        String tagUrl =
                            "https://github.com/anonfaded/FadCam/releases/latest";
                        if (location != null && location.contains("/tag/")) {
                            int tagIndex = location.lastIndexOf("/tag/");
                            tagUrl = location;
                            latestVersion = location
                                .substring(tagIndex + 5)
                                .replace("v", "")
                                .trim();
                        }
                        String currentVersion = getAppVersionForUpdates();
                        if (
                            latestVersion != null &&
                            isUpdateAvailable(currentVersion, latestVersion)
                        ) {
                            String changelog = ""; // Not available via this method
                            final String finalLatestVersion = latestVersion;
                            final String finalTagUrl = tagUrl;
                            requireActivity().runOnUiThread(() -> {
                                    // Add safety check to ensure fragment is still attached when showing bottom
                                    // sheet
                                    if (
                                        isAdded() &&
                                        !isDetached() &&
                                        getActivity() != null &&
                                        !getActivity().isFinishing()
                                    ) {
                                        try {
                                            UpdateAvailableBottomSheet.newInstance(
                                                finalLatestVersion,
                                                changelog,
                                                finalTagUrl
                                            ).show(
                                                getParentFragmentManager(),
                                                "UpdateAvailableBottomSheet"
                                            );
                                        } catch (IllegalStateException e) {
                                            // Log the error but don't crash - this can happen during language changes
                                            Log.e(
                                                TAG,
                                                "Fragment not associated with fragment manager",
                                                e
                                            );
                                        }
                                    } else {
                                        Log.d(
                                            TAG,
                                            "Update check: Fragment not in valid state to show bottom sheet"
                                        );
                                    }
                                });
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Update check failed", e);
                    }
                });
            }
        } else {
            Log.i(
                TAG,
                "Auto update check is disabled or no internet available, not showing update bottom sheet"
            );
        }
        // ----- Update Check Bottom Sheet End -----
    }

    /**
     * Displays a toast message showing the currently selected camera based on
     * shared preferences
     */
    private void showCurrentCameraSelection() {
        CameraType currentCameraType =
            sharedPreferencesManager.getCameraSelection();
        String currentCameraTypeString = "";
        if (currentCameraType.equals(CameraType.FRONT)) {
            currentCameraTypeString = getString(R.string.front);
        } else if (currentCameraType.equals(CameraType.BACK)) {
            currentCameraTypeString = getString(R.string.back);
        }

        // Toast.makeText(getContext(), this.getString(R.string.current_camera) + ": " +
        // currentCameraTypeString.toLowerCase(), Toast.LENGTH_SHORT).show();
    }

    private void fetchRecordingState() {
        Intent startIntent = new Intent(getActivity(), RecordingService.class);
        startIntent.setAction(Constants.BROADCAST_ON_RECORDING_STATE_REQUEST);
        requireActivity().startService(startIntent);
    }

    private void registerBroadcastOnRecordingStateCallback() {
        broadcastOnRecordingStateCallback = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent i) {
                RecordingState recordingStateIntent =
                    (RecordingState) i.getSerializableExtra(
                        Constants.INTENT_EXTRA_RECORDING_STATE
                    );
                if (recordingStateIntent == null) {
                    recordingStateIntent = RecordingState.NONE;
                }

                switch (recordingStateIntent) {
                    case NONE:
                        onRecordingStopped();
                        break;
                    case IN_PROGRESS:
                        if (isRecording()) {
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
                // ----- Fix Start for this method(registerBroadcastOnRecordingStarted) -----
                // Get the timestamp from the intent with current time as fallback
                long startTimeFromService = i.getLongExtra(
                    Constants.INTENT_EXTRA_RECORDING_START_TIME,
                    SystemClock.elapsedRealtime()
                );

                // Validate the timestamp - ensure it's not ridicuously old or in the future
                long currentTime = SystemClock.elapsedRealtime();

                // Check if the time from service is within a reasonable range
                // (not more than 5 seconds in the past or 1 second in the future)
                if (
                    startTimeFromService < currentTime - 5000 ||
                    startTimeFromService > currentTime + 1000
                ) {
                    Log.w(
                        TAG,
                        "Received invalid recordingStartTime from service: " +
                        startTimeFromService +
                        ", current time: " +
                        currentTime +
                        ". Using current time instead."
                    );
                    startTimeFromService = currentTime;
                }

                // Set our recording start time to the validated time from service
                recordingStartTime = startTimeFromService;
                Log.d(
                    TAG,
                    "BROADCAST_ON_RECORDING_STARTED: Set recordingStartTime=" +
                    recordingStartTime
                );
                // ----- Fix End for this method(registerBroadcastOnRecordingStarted) -----

                // Update our internal state first
                onRecordingStarted(true);

                // Force a clean surface reset when recording starts to ensure preview works
                if (textureView != null) {
                    // Try to create a new surface immediately if possible
                    if (textureView.getSurfaceTexture() != null) {
                        if (textureViewSurface != null) {
                            textureViewSurface.release();
                        }
                        textureViewSurface = new Surface(
                            textureView.getSurfaceTexture()
                        );
                        Log.d(
                            TAG,
                            "BROADCAST_ON_RECORDING_STARTED: Created new surface"
                        );
                        updateServiceWithCurrentSurface(textureViewSurface);
                    }

                    // Schedule a secondary attempt with a slight delay as backup
                    handlerClock.postDelayed(
                        () -> {
                            if (isRecording() && isPreviewEnabled) {
                                if (textureView.getSurfaceTexture() != null) {
                                    // Only recreate if needed
                                    if (
                                        textureViewSurface == null ||
                                        !textureViewSurface.isValid()
                                    ) {
                                        if (textureViewSurface != null) {
                                            textureViewSurface.release();
                                        }
                                        textureViewSurface = new Surface(
                                            textureView.getSurfaceTexture()
                                        );
                                    }
                                    updateServiceWithCurrentSurface(
                                        textureViewSurface
                                    );
                                    Log.d(
                                        TAG,
                                        "BROADCAST_ON_RECORDING_STARTED: Delayed surface creation"
                                    );
                                } else {
                                    Log.d(
                                        TAG,
                                        "BROADCAST_ON_RECORDING_STARTED: SurfaceTexture still not available after delay"
                                    );
                                }
                            }
                        },
                        200
                    ); // Slightly longer delay as a final attempt
                }
            }
        };
    }

    private void registerBroadcastOnRecordingResumed() {
        broadcastOnRecordingResumed = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent i) {
                onRecordingResumed();
            }
        };
    }

    private void registerBroadcastOnRecordingPaused() {
        broadcastOnRecordingPaused = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (isAdded()) onRecordingPaused();
            }
        };
    }

    private void registerBrodcastOnRecordingStopped() {
        broadcastOnRecordingStopped = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent i) {
                onRecordingStopped();
            }
        };
    }

    private void onRecordingStarted(boolean toast) {
        Log.d(TAG, "onRecordingStarted. Toast: " + toast);

        // ----- Fix Start for this method(onRecordingStarted) -----
        // Reset recording start time to ensure a fresh start - always use current time
        // This fixes cases where old stale timestamps might be causing incorrect
        // elapsed time
        recordingStartTime = SystemClock.elapsedRealtime();
        Log.d(
            TAG,
            "onRecordingStarted: RESET recordingStartTime=" + recordingStartTime
        );
        // ----- Fix End for this method(onRecordingStarted) -----

        recordingState = RecordingState.IN_PROGRESS;
        setUIForRecordingActive();
        if (toast) Utils.showQuickToast(
            requireContext(),
            R.string.video_recording_started
        );
        acquireWakeLock(); // Acquire wake lock
        updateStats(); // Update stats when recording starts

        // Always start the info update timer to keep elapsed time current
        startUpdatingInfo();

        // ----- Fix Start for this method(onRecordingStarted) -----
        // Always force preview enabled on first recording start
        isPreviewEnabled = true;
        savePreviewState();
        updatePreviewVisibility();

        // When recording starts, ensure we have a valid surface
        if (textureView != null) {
            // If TextureView has a valid SurfaceTexture, create a Surface from it
            if (
                textureView.isAvailable() &&
                textureView.getSurfaceTexture() != null
            ) {
                // Release any existing surface to avoid leaks
                if (textureViewSurface != null) {
                    textureViewSurface.release();
                }

                // Create a new Surface from the SurfaceTexture
                textureViewSurface = new Surface(
                    textureView.getSurfaceTexture()
                );
                Log.d(
                    TAG,
                    "onRecordingStarted: Created new surface from available TextureView"
                );

                // Send the surface to the service
                updateServiceWithCurrentSurface(textureViewSurface);
            } else {
                // If no SurfaceTexture is available, reset the TextureView to trigger creation
                Log.d(
                    TAG,
                    "onRecordingStarted: TextureView not available, forcing a reset"
                );
                resetTextureView();

                // Add a delayed retry to create and send the surface
                handlerClock.postDelayed(
                    () -> {
                        if (textureView.getSurfaceTexture() != null) {
                            textureViewSurface = new Surface(
                                textureView.getSurfaceTexture()
                            );
                            updateServiceWithCurrentSurface(textureViewSurface);
                            Log.d(
                                TAG,
                                "onRecordingStarted: Created surface after delay"
                            );
                        }
                    },
                    100
                );
            }
        }
        // ----- Fix Ended for this method(onRecordingStarted) -----
    }

    private void onRecordingResumed() {
        recordingState = RecordingState.IN_PROGRESS;

        buttonPauseResume.setIcon(
            AppCompatResources.getDrawable(
                requireContext(),
                R.drawable.ic_pause
            )
        );
        buttonPauseResume.setEnabled(true);

        buttonStartStop.setBackgroundTintList(
            ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.button_stop)
            )
        );
        buttonStartStop.setText(getString(R.string.button_stop));
        buttonStartStop.setIcon(
            AppCompatResources.getDrawable(requireContext(), R.drawable.ic_stop)
        );
        buttonStartStop.setEnabled(true);

        buttonCamSwitch.setEnabled(false);

        startUpdatingInfo();
    }

    private void onRecordingPaused() {
        recordingState = RecordingState.PAUSED;

        buttonPauseResume.setIcon(
            AppCompatResources.getDrawable(requireContext(), R.drawable.ic_play)
        );
        buttonPauseResume.setEnabled(true);

        buttonCamSwitch.setEnabled(false);

        buttonStartStop.setBackgroundTintList(
            ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.button_stop)
            )
        );
        buttonStartStop.setText(getString(R.string.button_stop));
        buttonStartStop.setIcon(
            AppCompatResources.getDrawable(requireContext(), R.drawable.ic_stop)
        );
    }

    // --- Receiver for MediaRecorder Stopped signal ---
    /**
     * Called when the BROADCAST_ON_RECORDING_STOPPED is received,
     * indicating the MediaRecorder engine has stopped and hardware resources
     * are likely released or being released immediately by the service.
     * This method resets the UI to the IDLE state.
     */
    private void onRecordingStopped() {
        // ----- Fix Start: Restructure for better state management -----
        Log.d(TAG, "onRecordingStopped broadcast received.");

        // First update the recording state
        recordingState = RecordingState.NONE;

        // Release wake lock if it was acquired
        releaseWakeLock();

        // Reset all buttons to idle state
        try {
            resetUIButtonsToIdleState();

            // Handle visual elements for preview and timers
            updatePreviewVisibility(); // Show placeholder text instead of preview
            stopUpdatingInfo(); // Stop updating storage info

            Log.d(
                TAG,
                "onRecordingStopped: UI reset to IDLE state. Background processing may continue."
            );
        } catch (Exception e) {
            Log.e(TAG, "Error in onRecordingStopped", e);
        }

        // Check if the service is actually running
        if (!isMyServiceRunning(RecordingService.class)) {
            Log.d(
                TAG,
                "RecordingService is not running, force setting recordingState to NONE"
            );
            recordingState = RecordingState.NONE;
            updateStartButtonAvailability();
        }
        // ----- Fix End: Restructure for better state management -----
    }

    // Inside HomeFragment.java

    /**
     * Safely resets the main control buttons (Start, Pause, CamSwitch, Torch)
     * and related UI elements (like preview) to their default IDLE state.
     * This means recording is stopped and the user can initiate a new one.
     * Should only be called when the fragment is attached and view is available.
     */
    private void resetUIButtonsToIdleState() {
        Log.d(TAG, "Reset UI to idle state");
        if (!isAdded() || getContext() == null || getView() == null) {
            Log.w(
                TAG,
                "resetUIButtonsToIdleState: Fragment/context unavailable"
            );
            return;
        }
        try {
            String themeName =
                sharedPreferencesManager.sharedPreferences.getString(
                    com.fadcam.Constants.PREF_APP_THEME,
                    Constants.DEFAULT_APP_THEME
                );
            boolean isAmoledLocal =
                "AMOLED".equalsIgnoreCase(themeName) ||
                "Amoled".equalsIgnoreCase(themeName) ||
                "Faded Night".equalsIgnoreCase(themeName);
            if (buttonStartStop != null) {
                buttonStartStop.setText(R.string.button_start);
                buttonStartStop.setIcon(
                    AppCompatResources.getDrawable(
                        getContext(),
                        R.drawable.ic_play
                    )
                );
                // Always use green color for start button regardless of theme
                int btnColor = Color.parseColor("#4CAF50"); // Always green
                buttonStartStop.setBackgroundTintList(
                    ColorStateList.valueOf(btnColor)
                );
                // -------------- Fix Start (ensure button is always enabled in idle state)-----------
                // Force enable the button when resetting to idle state, regardless of any debouncing
                buttonStartStop.setEnabled(true);
                buttonStartStop.setAlpha(1.0f);
                Log.d(TAG, "Start button force-enabled in resetUIButtonsToIdleState");
                // -------------- Fix Ended (ensure button is always enabled in idle state)-----------
            }
            if (buttonPauseResume != null) {
                buttonPauseResume.setVisibility(View.VISIBLE);
                buttonPauseResume.setEnabled(false);
                buttonPauseResume.setAlpha(0.5f);
                buttonPauseResume.setIcon(
                    AppCompatResources.getDrawable(
                        getContext(),
                        R.drawable.ic_pause
                    )
                );
            }
            if (buttonCamSwitch != null) {
                buttonCamSwitch.setEnabled(true);
                buttonCamSwitch.setVisibility(View.VISIBLE);
                buttonCamSwitch.setAlpha(1f);
            }
            if (buttonTorchSwitch != null) {
                buttonTorchSwitch.setEnabled(true);
                buttonTorchSwitch.setAlpha(1f);
            }
            updateStartButtonAvailability();
            Log.d(
                TAG,
                "resetUIButtonsToIdleState: All UI elements reset to idle state"
            );
        } catch (Exception e) {
            Log.e(TAG, "Error in resetUIButtonsToIdleState", e);
        }
    }

    /**
     * Updates the start button state based on camera resource availability
     */
    private void updateStartButtonAvailability() {
        if (!isAdded() || buttonStartStop == null) {
            return;
        }

        // Only update if we're in a state where the start button would normally be
        // enabled
        if (recordingState == RecordingState.NONE) {
            boolean shouldEnable = areCameraResourcesAvailable;
            buttonStartStop.setEnabled(shouldEnable);
            buttonStartStop.setAlpha(shouldEnable ? 1.0f : 0.5f);

            // Always maintain green color even when disabled
            buttonStartStop.setBackgroundTintList(
                ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            );

            if (!shouldEnable) {
                Log.d(
                    TAG,
                    "Start button disabled due to camera resources being released"
                );
            } else {
                Log.d(
                    TAG,
                    "Start button enabled as camera resources are available"
                );
            }
        }
    }

    /**
     * Helper for resetUIButtonsToIdleState to check flash without throwing checked
     * exception
     */
    private String getCameraWithFlashQuietly() {
        // Ensure cameraManager is initialized (e.g., in onViewCreated or onAttach)
        if (cameraManager == null) {
            try {
                cameraManager =
                    (CameraManager) requireContext().getSystemService(
                        Context.CAMERA_SERVICE
                    );
            } catch (Exception e) {
                Log.e(
                    TAG,
                    "Failed to get CameraManager in getCameraWithFlashQuietly",
                    e
                );
                return null;
            }
        }
        if (cameraManager == null) return null; // Check again if getSystemService failed

        try {
            // Assuming getCameraWithFlash is defined elsewhere and throws
            // CameraAccessException
            return getCameraWithFlash();
        } catch (CameraAccessException e) {
            Log.w(
                TAG,
                "CameraAccessException checking flash quietly: " +
                e.getMessage()
            ); // Changed to warning
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error checking flash quietly", e);
            return null;
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        // unregisterStatsReceiver(); // Unregister receiver when fragment stops

        Log.e(TAG, "HomeFragment stopped");

        // ----- Fix Start for this method(onStop)-----
        // Call the centralized unregister method
        unregisterBroadcastReceivers();

        // Unregister SharedPreferences listener if present
        if (sharedPreferencesManager != null && prefsListener != null) {
            try {
                sharedPreferencesManager.sharedPreferences.unregisterOnSharedPreferenceChangeListener(
                    prefsListener
                );
            } catch (Exception ignored) {}
            prefsListener = null;
        }

        // The following lines for sending surface update on stop if recording
        // should remain, as they are not related to receiver unregistration.
        if (isRecording()) {
            // isRecording() checks recordingState
            Intent recordingIntent = new Intent(
                getActivity(),
                RecordingService.class
            );
            recordingIntent.setAction(Constants.INTENT_ACTION_CHANGE_SURFACE);
            // Check if activity is still available before starting service
            if (getActivity() != null) {
                requireActivity().startService(recordingIntent);
            }
        }
        // ----- Fix Ended for this method(onStop)-----

        // ----- Fix Start for unregistering camera resource availability receiver -----
        unregisterCameraResourceAvailabilityReceiver();
        // ----- Fix End for unregistering camera resource availability receiver -----

        stopUpdatingInfo();
    }

    // --- `onResume()` Method (Simplified - focuses on fetch state) ---
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "HomeFragment resumed.");
        if (!isAdded() || getContext() == null || getActivity() == null) {
            Log.e(TAG, "onResume: Not attached!");
            return;
        }
        if (sharedPreferencesManager == null) {
            sharedPreferencesManager = SharedPreferencesManager.getInstance(
                requireContext()
            );
        }

        Log.d(
            TAG,
            "onResume: Fetching current recording state from service..."
        );
        fetchRecordingState(); // Let service callback handle UI sync

        registerBroadcastReceivers(); // Centralized registration

        // ----- Fix Start for this method(onResume)-----
        // Re-load preview state from SharedPreferences to ensure consistency
        isPreviewEnabled = sharedPreferencesManager.isPreviewEnabled();
        Log.d(
            TAG,
            "onResume: Loaded isPreviewEnabled state = " + isPreviewEnabled
        );

        // Update the preview visibility based on current state
        updatePreviewVisibility();

        // Critical: When resuming, send the appropriate surface to the service
        // This ensures preview shows correctly after app is minimized/restored
        if (
            isPreviewEnabled &&
            isRecordingOrPaused() &&
            textureViewSurface != null &&
            textureViewSurface.isValid()
        ) {
            Log.d(
                TAG,
                "onResume: Preview enabled, sending valid surface to service"
            );
            updateServiceWithCurrentSurface(textureViewSurface);
        } else if (!isPreviewEnabled || !isRecordingOrPaused()) {
            // If preview is disabled or not recording, send null surface
            Log.d(
                TAG,
                "onResume: Preview disabled or not recording, sending null surface"
            );
            updateServiceWithCurrentSurface(null);
        }
        // ----- Fix Ended for this method(onResume)-----

        Log.d(TAG, "onResume: Triggering stats update.");
        updateStats();
        updateTorchUI(isTorchOn);
    }

    // Inside HomeFragment.java

    // --- Receiver Field Declarations (Should already exist near top of
    // HomeFragment) ---
    // private BroadcastReceiver broadcastOnRecordingStarted;
    // private BroadcastReceiver broadcastOnRecordingResumed;
    // private BroadcastReceiver broadcastOnRecordingPaused;
    // private BroadcastReceiver broadcastOnRecordingStopped; // Handles UI idle
    // reset now
    // private BroadcastReceiver broadcastOnRecordingStateCallback; // Handles
    // initial sync
    // private BroadcastReceiver recordingCompleteReceiver; // Handles stats update
    // after processing
    // private BroadcastReceiver torchReceiver;

    // --- Registration Flags (Optional but recommended for robust unregistering)
    // ---
    private boolean isStateReceiversRegistered = false;
    private boolean isCompletionReceiverRegistered = false; // Renamed from isStatsReceiverRegistered
    private boolean isTorchReceiverRegistered = false;
    // Receiver for recording failure broadcasts
    private android.content.BroadcastReceiver recordingFailedReceiver;

    // --- MAIN Registration Method ---
    /**
     * Centralized method to register all necessary BroadcastReceivers for this
     * fragment.
     * Ensures initialization and calls individual registration helpers.
     * Should be called from onResume or onStart.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag") // Suppress only if targeting older SDKs AND necessary
    private void registerBroadcastReceivers() {
        Context context = requireContext();
        if (context == null) {
            Log.e(
                TAG,
                "registerBroadcastReceivers: Context is null, cannot register."
            );
            return;
        }
        Log.d(TAG, "Registering all HomeFragment broadcast receivers...");

        // Initialize if they are null (first time or after unregistration)
        initializeRecordingStateReceivers(); // Initializes all state-related receivers
        initializeRecordingCompleteReceiver();
        initializeTorchReceiver();
        // ----- Fix Start for this method(registerBroadcastReceivers) -----
        initializeSegmentCompleteStatsReceiver();
        // ----- Fix Ended for this method(registerBroadcastReceivers) -----

        // ----- Fix Start: Also initialize camera resource availability receiver -----
        initializeCameraResourceAvailabilityReceiver();
        // ----- Fix End: Also initialize camera resource availability receiver -----

        // Register them
        // ----- Fix Start for this
        // method(registerBroadcastReceivers_update_isStateReceiversRegistered_flag_logic)-----
        // registerRecordingStateReceivers now returns a boolean indicating success
        isStateReceiversRegistered = registerRecordingStateReceivers(context);
        // ----- Fix Ended for this
        // method(registerBroadcastReceivers_update_isStateReceiversRegistered_flag_logic)-----

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerRecordingCompleteReceiver(context);
        }
        registerTorchReceiver(context);
        // ----- Fix Start for this method(registerBroadcastReceivers) -----
        registerSegmentCompleteStatsReceiver(context); // Register the new one
        // Register recording failed receiver
        registerRecordingFailedReceiver(context);
        // ----- Fix Ended for this method(registerBroadcastReceivers) -----

        // ----- Fix Start: Also register the camera resource availability receiver
        // -----
        registerCameraResourceAvailabilityReceiver();
        // ----- Fix End: Also register the camera resource availability receiver -----

        // ----- Fix Start for this
        // method(registerBroadcastReceivers_update_isStateReceiversRegistered_flag_logic)-----
        // isStateReceiversRegistered = true; // Assuming
        // registerRecordingStateReceivers sets this -> Moved up and tied to actual
        // success
        // ----- Fix Ended for this
        // method(registerBroadcastReceivers_update_isStateReceiversRegistered_flag_logic)-----
        // isCompletionReceiverRegistered is managed by
        // registerRecordingCompleteReceiver
        // isTorchReceiverRegistered is managed by registerTorchReceiver
        Log.i(
            TAG,
            "All HomeFragment broadcast receivers registration attempt finished."
        );
    }

    private void registerRecordingFailedReceiver(Context context) {
        if (recordingFailedReceiver == null) {
            recordingFailedReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    if (intent == null || !isAdded()) return;
                    if (
                        Constants.ACTION_RECORDING_FAILED.equals(
                            intent.getAction()
                        )
                    ) {
                        String errorMessage = intent.getStringExtra(
                            Constants.EXTRA_ERROR_MESSAGE
                        );
                        String stackTrace = intent.getStringExtra(
                            Constants.EXTRA_STACK_TRACE
                        );
                        showRecordingFailedDialog(errorMessage, stackTrace);
                    }
                }
            };
        }
        IntentFilter filter = new IntentFilter(
            Constants.ACTION_RECORDING_FAILED
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                recordingFailedReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            );
        } else {
            ContextCompat.registerReceiver(context, recordingFailedReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        }
    }

    private void showRecordingFailedDialog(
        String errorMessage,
        String stackTrace
    ) {
        if (getContext() == null || !isAdded()) return;

        String fullErrorMessage =
            "FadCam could not start recording.\n\nPlease copy this error and report it on GitHub or Discord.\n\n" +
            "-----------------------------------\n" +
            "Error Message:\n" +
            (errorMessage != null ? errorMessage : "No message") +
            "\n\n" +
            "Device Info:\n" +
            "MANUFACTURER: " +
            Build.MANUFACTURER +
            "\n" +
            "MODEL: " +
            Build.MODEL +
            "\n" +
            "ANDROID: " +
            Build.VERSION.RELEASE +
            " (API " +
            Build.VERSION.SDK_INT +
            ")\n\n" +
            "Stack Trace:\n" +
            (stackTrace != null ? stackTrace : "No stack trace") +
            "\n-----------------------------------";

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
            requireContext()
        )
            .setTitle("Recording Failed")
            .setMessage(fullErrorMessage)
            .setPositiveButton("OK", (dialog, which) -> dialog.dismiss())
            .setNeutralButton("Copy Error", (dialog, which) -> {
                android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) requireContext().getSystemService(
                        Context.CLIPBOARD_SERVICE
                    );
                android.content.ClipData clip =
                    android.content.ClipData.newPlainText(
                        "FadCam Error",
                        fullErrorMessage
                    );
                clipboard.setPrimaryClip(clip);
                android.widget.Toast.makeText(
                    getContext(),
                    "Error copied to clipboard",
                    android.widget.Toast.LENGTH_SHORT
                ).show();
            })
            .show();
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
                    Log.d(
                        TAG,
                        "Received BROADCAST_ON_RECORDING_STARTED (New Handler)"
                    );

                    // Get timestamp from the service with current time as fallback
                    long startTimeFromService = i.getLongExtra(
                        Constants.INTENT_EXTRA_RECORDING_START_TIME,
                        SystemClock.elapsedRealtime()
                    );
                    recordingStartTime = startTimeFromService;
                    Log.d(
                        TAG,
                        "initializeRecordingStateReceivers: Setting recordingStartTime=" +
                        recordingStartTime
                    );

                    // Perform non-UI actions previously in onRecordingStarted(true)
                    acquireWakeLock();
                    setVideoBitrate();

                    // Call the main UI state updater
                    handleServiceStateUpdate(RecordingState.IN_PROGRESS);

                    // Handle the toast
                    if (isAdded() && getContext() != null) {
                        vibrateTouch();
                        // Toast.makeText(getContext(), R.string.video_recording_started,
                        // Toast.LENGTH_SHORT).show();
                    }
                }
            };
            Log.d(
                TAG,
                "Initialized broadcastOnRecordingStarted receiver (New Handler)"
            );
        }
        // Initialize Receiver for RESUME action
        if (broadcastOnRecordingResumed == null) {
            broadcastOnRecordingResumed = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    if (isAdded()) onRecordingResumed();
                }
            };
            Log.d(TAG, "Initialized broadcastOnRecordingResumed receiver");
        }
        // Initialize Receiver for PAUSE action
        if (broadcastOnRecordingPaused == null) {
            broadcastOnRecordingPaused = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    if (isAdded()) onRecordingPaused();
                }
            };
            Log.d(TAG, "Initialized broadcastOnRecordingPaused receiver");
        }
        // Initialize Receiver for STOPPED action (triggers UI Idle)
        if (broadcastOnRecordingStopped == null) {
            broadcastOnRecordingStopped = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent i) {
                    if (isAdded()) onRecordingStopped();
                }
            };
            Log.d(TAG, "Initialized broadcastOnRecordingStopped receiver");
        }
        // Initialize Receiver for SERVICE STATE CALLBACK
        if (broadcastOnRecordingStateCallback == null) {
            broadcastOnRecordingStateCallback = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent i) {
                    if (!isAdded() || i == null) return;
                    // Get the state reported by the service
                    RecordingState serviceState =
                        (RecordingState) i.getSerializableExtra(
                            Constants.INTENT_EXTRA_RECORDING_STATE
                        );
                    Log.i(
                        TAG,
                        "Received Service State Callback: " + serviceState
                    );
                    if (serviceState == null) serviceState =
                        RecordingState.NONE; // Default to NONE

                    // *** CALL the handler method ***
                    handleServiceStateUpdate(serviceState);
                }
            };
            Log.d(
                TAG,
                "Initialized broadcastOnRecordingStateCallback receiver"
            );
        }
    }

    /**
     * **Definition:** Updates the HomeFragment UI based on the definitive state
     * reported by the RecordingService's state callback.
     *
     * @param reportedState The RecordingState received from the service.
     */
    private void handleServiceStateUpdate(RecordingState reportedState) {
        if (!isAdded()) {
            // Check if fragment is attached
            Log.w(
                TAG,
                "handleServiceStateUpdate: Fragment not attached, ignoring state update: " +
                reportedState
            );
            return;
        }
        Log.i(
            TAG,
            "handleServiceStateUpdate: Applying UI for Service State = " +
            reportedState
        );

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
                Log.d(
                    TAG,
                    "handleServiceStateUpdate: Service state is NONE. Resetting UI to idle."
                );
                resetUIButtonsToIdleState();
                break;
        }
        Log.d(
            TAG,
            "handleServiceStateUpdate finished. Fragment state is now: " +
            recordingState
        );
    }

    /** Helper to set UI elements for the ACTIVE recording state */
    private void setUIForRecordingActive() {
        if (!isAdded() || getContext() == null) return;
        Log.d(TAG, "Setting UI to: ACTIVE Recording");
        try {
            // Ensure interaction buttons reflect recording
            buttonStartStop.setEnabled(true); // Enable STOP
            buttonStartStop.setBackgroundTintList(
                ContextCompat.getColorStateList(
                    requireContext(),
                    R.color.button_stop
                )
            );
            buttonStartStop.setText(getString(R.string.button_stop));
            buttonStartStop.setIcon(
                AppCompatResources.getDrawable(
                    requireContext(),
                    R.drawable.ic_stop
                )
            );

            buttonPauseResume.setEnabled(true); // Enable PAUSE
            buttonPauseResume.setIcon(
                AppCompatResources.getDrawable(
                    requireContext(),
                    R.drawable.ic_pause
                )
            );
            buttonPauseResume.setAlpha(1.0f); // Make fully visible when enabled
            buttonPauseResume.setBackgroundTintList(
                ContextCompat.getColorStateList(
                    requireContext(),
                    R.color.button_pause
                )
            );

            buttonCamSwitch.setEnabled(false); // Disable CAM SWITCH
            if (buttonTorchSwitch != null) buttonTorchSwitch.setEnabled(
                getCameraWithFlashQuietly() != null
            ); // Enable TORCH if available

            // Manage preview and timers
            updatePreviewVisibility();
            startUpdatingInfo();
        } catch (Exception e) {
            Log.e(TAG, "Error setting UI for Active state", e);
        }
    }

    /** Helper to set UI elements for the PAUSED recording state */
    private void setUIForRecordingPaused() {
        if (!isAdded() || getContext() == null) return;
        Log.d(TAG, "Setting UI to: PAUSED Recording");
        try {
            // Set buttons for Paused state (Stop ON, Resume(Play) ON, Switch OFF, Torch
            // OFF)
            buttonStartStop.setEnabled(true); // Enable STOP
            buttonStartStop.setBackgroundTintList(
                ContextCompat.getColorStateList(
                    requireContext(),
                    R.color.button_stop
                )
            );
            buttonStartStop.setText(getString(R.string.button_stop));
            buttonStartStop.setIcon(
                AppCompatResources.getDrawable(
                    requireContext(),
                    R.drawable.ic_stop
                )
            );

            buttonPauseResume.setEnabled(true); // Enable RESUME
            buttonPauseResume.setIcon(
                AppCompatResources.getDrawable(
                    requireContext(),
                    R.drawable.ic_play
                )
            ); // Show
            // Play
            // icon for
            // RESUME
            buttonPauseResume.setAlpha(1.0f); // Make fully visible when enabled
            buttonPauseResume.setBackgroundTintList(
                ContextCompat.getColorStateList(
                    requireContext(),
                    R.color.button_pause
                )
            );

            buttonCamSwitch.setEnabled(false); // Disable CAM SWITCH
            // ----- Fix Start for this method(setUIForRecordingPaused_torchButton)-----
            if (buttonTorchSwitch != null) buttonTorchSwitch.setEnabled(
                getCameraWithFlashQuietly() != null
            ); // Enable TORCH if available, even
            // when paused
            // ----- Fix Ended for this method(setUIForRecordingPaused_torchButton)-----

            // Manage preview and timers
            updatePreviewVisibility();
            stopUpdatingInfo(); // Show placeholder/last frame, stop timers
        } catch (Exception e) {
            Log.e(TAG, "Error setting UI for Paused state", e);
        }
    }

    /** Initializes the BroadcastReceiver for ACTION_RECORDING_COMPLETE */
    private void initializeRecordingCompleteReceiver() {
        if (recordingCompleteReceiver == null) {
            recordingCompleteReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (
                        !isAdded() ||
                        intent == null ||
                        intent.getAction() == null
                    ) return;
                    if (
                        Constants.ACTION_RECORDING_COMPLETE.equals(
                            intent.getAction()
                        )
                    ) {
                        Log.i(
                            TAG,
                            "<<< Received ACTION_RECORDING_COMPLETE (Processing Finished) >>>"
                        );
                        if (getView() == null) {
                            Log.w(TAG, "Completion: View null, skip stats UI");
                            return;
                        }

                        // Invalidate caches when new video is recorded
                        VideoStatsCache.invalidateStats(
                            sharedPreferencesManager
                        );
                        com.fadcam.utils.VideoSessionCache.invalidateOnNextAccess();
                        Log.d(
                            TAG,
                            "Invalidated video caches after recording completion"
                        );

                        try {
                            updateStats();
                            Log.d(TAG, "Completion: Updated stats.");
                        } catch (Exception e) {
                            Log.e(TAG, "Completion: Err update stats", e);
                        }
                    }
                }
            };
            Log.d(TAG, "Initialized recordingCompleteReceiver");
        }
    }

    /** Initializes the BroadcastReceiver for Torch State Changes */
    private void initializeTorchReceiver() {
        if (torchReceiver == null) {
            torchReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (
                        !isAdded() ||
                        getActivity() == null ||
                        intent == null ||
                        intent.getAction() == null
                    ) return;
                    if (
                        Constants.BROADCAST_ON_TORCH_STATE_CHANGED.equals(
                            intent.getAction()
                        )
                    ) {
                        isTorchOn = intent.getBooleanExtra(
                            Constants.INTENT_EXTRA_TORCH_STATE,
                            false
                        );
                        Log.d(
                            "TorchDebug",
                            "Received state update via Broadcast: " + isTorchOn
                        );
                        updateTorchUI(isTorchOn); // Update button visuals
                    }
                }
            };
            Log.d(TAG, "Initialized torchReceiver");
        }
    }

    // --- Registration Helper Methods ---

    /** Helper to register all recording state change receivers */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    // ----- Fix Start for this
    // method(registerRecordingStateReceivers_correct_signature_and_logic)-----
    private boolean registerRecordingStateReceivers(Context context) {
        // Ensure boolean return type
        // ----- Fix Ended for this
        // method(registerRecordingStateReceivers_correct_signature_and_logic)-----
        Log.d(TAG, "Registering recording state receivers...");
        if (context == null) {
            Log.e(TAG, "Context is null in registerRecordingStateReceivers");
            // ----- Fix Start for this
            // method(registerRecordingStateReceivers_return_boolean_and_check_receivers)-----
            isStateReceiversRegistered = false;
            return false;
            // ----- Fix Ended for this
            // method(registerRecordingStateReceivers_return_boolean_and_check_receivers)-----
        }

        // Ensure receivers are initialized
        initializeRecordingStateReceivers();

        // ----- Fix Start for this
        // method(registerRecordingStateReceivers_return_boolean_and_check_receivers)-----
        boolean allRegisteredSuccessfully = true;
        IntentFilter intentFilterStarted = new IntentFilter(
            Constants.BROADCAST_ON_RECORDING_STARTED
        );
        if (broadcastOnRecordingStarted != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    broadcastOnRecordingStarted,
                    intentFilterStarted,
                    Context.RECEIVER_EXPORTED
                );
            } else {
                context.registerReceiver(
                    broadcastOnRecordingStarted,
                    intentFilterStarted
                );
            }
            Log.d(TAG, "Registered broadcastOnRecordingStarted");
        } else {
            allRegisteredSuccessfully = false;
            Log.e(TAG, "broadcastOnRecordingStarted is null, not registering");
        }

        IntentFilter intentFilterResumed = new IntentFilter(
            Constants.BROADCAST_ON_RECORDING_RESUMED
        );
        if (broadcastOnRecordingResumed != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    broadcastOnRecordingResumed,
                    intentFilterResumed,
                    Context.RECEIVER_EXPORTED
                );
            } else {
                context.registerReceiver(
                    broadcastOnRecordingResumed,
                    intentFilterResumed
                );
            }
            Log.d(TAG, "Registered broadcastOnRecordingResumed");
        } else {
            allRegisteredSuccessfully = false;
            Log.e(TAG, "broadcastOnRecordingResumed is null, not registering");
        }

        IntentFilter intentFilterPaused = new IntentFilter(
            Constants.BROADCAST_ON_RECORDING_PAUSED
        );
        if (broadcastOnRecordingPaused != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    broadcastOnRecordingPaused,
                    intentFilterPaused,
                    Context.RECEIVER_EXPORTED
                );
            } else {
                context.registerReceiver(
                    broadcastOnRecordingPaused,
                    intentFilterPaused
                );
            }
            Log.d(TAG, "Registered broadcastOnRecordingPaused");
        } else {
            allRegisteredSuccessfully = false;
            Log.e(TAG, "broadcastOnRecordingPaused is null, not registering");
        }

        IntentFilter intentFilterStopped = new IntentFilter(
            Constants.BROADCAST_ON_RECORDING_STOPPED
        );
        if (broadcastOnRecordingStopped != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    broadcastOnRecordingStopped,
                    intentFilterStopped,
                    Context.RECEIVER_EXPORTED
                );
            } else {
                context.registerReceiver(
                    broadcastOnRecordingStopped,
                    intentFilterStopped
                );
            }
            Log.d(TAG, "Registered broadcastOnRecordingStopped");
        } else {
            allRegisteredSuccessfully = false;
            Log.e(TAG, "broadcastOnRecordingStopped is null, not registering");
        }

        IntentFilter intentFilterStateCallback = new IntentFilter(
            Constants.BROADCAST_ON_RECORDING_STATE_CALLBACK
        );
        if (broadcastOnRecordingStateCallback != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    broadcastOnRecordingStateCallback,
                    intentFilterStateCallback,
                    Context.RECEIVER_EXPORTED
                );
            } else {
                context.registerReceiver(
                    broadcastOnRecordingStateCallback,
                    intentFilterStateCallback
                );
            }
            Log.d(TAG, "Registered broadcastOnRecordingStateCallback");
        } else {
            allRegisteredSuccessfully = false;
            Log.e(
                TAG,
                "broadcastOnRecordingStateCallback is null, not registering"
            );
        }

        isStateReceiversRegistered = allRegisteredSuccessfully;
        if (allRegisteredSuccessfully) {
            Log.i(
                TAG,
                "All recording state receivers registered successfully."
            );
        } else {
            Log.w(
                TAG,
                "One or more recording state receivers failed to register because they were null."
            );
        }
        return allRegisteredSuccessfully;
        // ----- Fix Ended for this
        // method(registerRecordingStateReceivers_return_boolean_and_check_receivers)-----
    }

    /** Helper to register the ACTION_RECORDING_COMPLETE receiver */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerRecordingCompleteReceiver(Context context) {
        if (
            !isCompletionReceiverRegistered &&
            context != null &&
            recordingCompleteReceiver != null
        ) {
            context.registerReceiver(
                recordingCompleteReceiver,
                new IntentFilter(Constants.ACTION_RECORDING_COMPLETE),
                Context.RECEIVER_EXPORTED
            );
            isCompletionReceiverRegistered = true; // isCompletionReceiverRegistered is the correct flag here
            Log.d(TAG, "ACTION_RECORDING_COMPLETE receiver registered.");
        }
    }

    /** Helper to register the Torch receiver */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerTorchReceiver(Context context) {
        if (isTorchReceiverRegistered) return;
        if (torchReceiver == null) {
            initializeTorchReceiver();
            if (torchReceiver == null) {
                Log.e(TAG, "Cannot register: Failed init torch receiver");
                return;
            }
        }
        IntentFilter filter = new IntentFilter(
            Constants.BROADCAST_ON_TORCH_STATE_CHANGED
        );
        try {
            // ----- Fix Start for this
            // method(registerTorchReceiver_add_export_flag_for_Tiramisu)-----
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    torchReceiver,
                    filter,
                    Context.RECEIVER_EXPORTED
                ); // Or RECEIVER_NOT_EXPORTED
                // if purely internal
            } else {
                context.registerReceiver(torchReceiver, filter);
            }
            // ContextCompat.registerReceiver(context, torchReceiver, filter,
            // ContextCompat.RECEIVER_NOT_EXPORTED);
            // ----- Fix Ended for this
            // method(registerTorchReceiver_add_export_flag_for_Tiramisu)-----
            isTorchReceiverRegistered = true; // Use specific flag
            Log.d(TAG, "Torch receiver registered.");
        } catch (Exception e) {
            Log.e(TAG, "Error registering Torch Receiver", e);
            isTorchReceiverRegistered = false;
        }
    }

    // --- Ensure Unregistration Logic ---
    // Place this method in HomeFragment.java and call it from onStop()
    private void unregisterBroadcastReceivers() {
        Context context = getContext(); // Use getContext() for fragment lifecycle safety
        if (context == null) {
            Log.w(
                TAG,
                "unregisterBroadcastReceivers: Context is null, cannot unregister."
            );
            return;
        }
        Log.d(
            TAG,
            "Unregistering all HomeFragment broadcast receivers if registered..."
        );

        // ----- Fix Start for this
        // method(unregisterBroadcastReceivers_check_flags)-----
        if (isStateReceiversRegistered) {
            try {
                if (
                    broadcastOnRecordingStarted != null
                ) context.unregisterReceiver(broadcastOnRecordingStarted);
                if (
                    broadcastOnRecordingResumed != null
                ) context.unregisterReceiver(broadcastOnRecordingResumed);
                if (
                    broadcastOnRecordingPaused != null
                ) context.unregisterReceiver(broadcastOnRecordingPaused);
                if (
                    broadcastOnRecordingStopped != null
                ) context.unregisterReceiver(broadcastOnRecordingStopped);
                if (
                    broadcastOnRecordingStateCallback != null
                ) context.unregisterReceiver(broadcastOnRecordingStateCallback);
                Log.i(TAG, "Unregistered recording state receivers.");
            } catch (IllegalArgumentException e) {
                Log.w(
                    TAG,
                    "Error unregistering state receivers (already unregistered?): " +
                    e.getMessage()
                );
            }
            isStateReceiversRegistered = false;
        } else {
            Log.d(
                TAG,
                "Recording state receivers were not registered, skipping unregistration."
            );
        }

        if (isCompletionReceiverRegistered) {
            try {
                if (
                    recordingCompleteReceiver != null
                ) context.unregisterReceiver(recordingCompleteReceiver);
                Log.i(TAG, "Unregistered recordingCompleteReceiver.");
            } catch (IllegalArgumentException e) {
                Log.w(
                    TAG,
                    "Error unregistering recordingCompleteReceiver: " +
                    e.getMessage()
                );
            }
            isCompletionReceiverRegistered = false;
        }

        if (isTorchReceiverRegistered) {
            try {
                if (torchReceiver != null) context.unregisterReceiver(
                    torchReceiver
                );
                Log.i(TAG, "Unregistered torchReceiver.");
            } catch (IllegalArgumentException e) {
                Log.w(
                    TAG,
                    "Error unregistering torchReceiver: " + e.getMessage()
                );
            }
            isTorchReceiverRegistered = false;
        }

        if (isSegmentCompleteStatsReceiverRegistered) {
            try {
                if (
                    segmentCompleteStatsReceiver != null
                ) context.unregisterReceiver(segmentCompleteStatsReceiver);
                Log.i(TAG, "Unregistered segmentCompleteStatsReceiver.");
            } catch (IllegalArgumentException e) {
                Log.w(
                    TAG,
                    "Error unregistering segmentCompleteStatsReceiver: " +
                    e.getMessage()
                );
            }
            isSegmentCompleteStatsReceiverRegistered = false;
        }
        // ----- Fix Ended for this
        // method(unregisterBroadcastReceivers_check_flags)-----
        Log.i(
            TAG,
            "All HomeFragment broadcast receivers unregistration attempt finished."
        );
        // Unregister recording failed receiver
        if (recordingFailedReceiver != null) {
            try {
                requireContext().unregisterReceiver(recordingFailedReceiver);
            } catch (Exception ignore) {}
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.d(TAG, "HomeFragment paused.");
        // ----- Fix Start for this method(onPause)-----
        if (textureViewSurface != null) {
            Log.d(TAG, "onPause: Explicitly sending null surface to service");
            updateServiceWithCurrentSurface(null);
        }
        // ----- Fix Ended for this method(onPause)-----
        // locationHelper.stopLocationUpdates();

        // Only unregister if receiver exists
        if (torchReceiver != null) {
            try {
                requireContext().unregisterReceiver(torchReceiver);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Receiver was not registered: " + e.getMessage());
            }
        }
    }

    // ----- Fix Start for this method(resetTextureView)-----
    /**
     * Helper method to reset the TextureView when needed to avoid showing stale
     * frames
     * This should be called when the preview state changes, especially from
     * disabled to enabled
     */
    private void resetTextureView() {
        if (textureView == null) {
            Log.w(TAG, "resetTextureView: TextureView is null, can't reset");
            return;
        }

        Log.d(TAG, "resetTextureView: Attempting to reset TextureView");

        // First release any existing surface
        if (textureViewSurface != null) {
            textureViewSurface.release();
            textureViewSurface = null;
            Log.d(TAG, "resetTextureView: Released existing surface");
        }

        // If the TextureView is available, recreate the surface
        if (textureView.isAvailable()) {
            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            if (surfaceTexture != null) {
                textureViewSurface = new Surface(surfaceTexture);
                Log.d(
                    TAG,
                    "resetTextureView: Created new surface from existing SurfaceTexture"
                );

                // If recording and preview enabled, update service with new surface
                if (isPreviewEnabled && isRecordingOrPaused()) {
                    updateServiceWithCurrentSurface(textureViewSurface);
                    Log.d(
                        TAG,
                        "resetTextureView: Updated service with new surface"
                    );
                }
            }
        } else {
            Log.d(
                TAG,
                "resetTextureView: TextureView not available, can't create surface yet"
            );
        }
    }

    // ----- Fix Ended for this method(resetTextureView)-----

    // @Override
    // public void onRequestPermissionsResult(int requestCode, @NonNull String[]
    // permissions, @NonNull int[] grantResults) {
    // super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    // if (requestCode == REQUEST_PERMISSIONS) {
    // boolean allGranted = true;
    // for (int result : grantResults) {
    // if (result != PackageManager.PERMISSION_GRANTED) {
    // allGranted = false;
    // break;
    // }
    // }
    // if (allGranted) {
    // startRecording();
    // } else {
    // Toast.makeText(requireContext(), "Essential permissions are required to start
    // recording", Toast.LENGTH_SHORT).show();
    // }
    // }
    // }

    // private void requestLocationPermission() {
    // if (ContextCompat.checkSelfPermission(getContext(),
    // Manifest.permission.ACCESS_FINE_LOCATION) !=
    // PackageManager.PERMISSION_GRANTED) {
    // ActivityCompat.requestPermissions(getActivity(), new
    // String[]{Manifest.permission.ACCESS_FINE_LOCATION},
    // LOCATION_PERMISSION_REQUEST_CODE);
    // } else {
    // locationHelper.startLocationUpdates();
    // }
    // Log.d(TAG, "Requesting location permission.");
    // }

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        // Debug recording time issue
        debugRecordingTimeVariables();

        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    // -------------- Fix Start: Manual orientation change handling -----------
    /**
     * Called by MainActivity when orientation changes to refresh the fragment's
     * layout
     */
    public void onOrientationChanged(int orientation) {
        Log.d(
            TAG,
            "HomeFragment orientation changed to: " +
            (orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    ? "landscape"
                    : "portrait")
        );

        // Simple approach: just log the change and let Android handle it naturally
        // The issue might be that we're over-engineering the solution
        Log.d(
            TAG,
            "Orientation change detected, current resources configuration: " +
            getResources().getConfiguration().orientation
        );

        // For now, let's not force any view recreation and see if the natural Android
        // configuration change handling works better
        if (isAdded() && getView() != null) {
            // Just save and restore state without view manipulation
            saveCurrentState();

            // Post a runnable to restore state after the configuration settles
            getView().post(() -> {
                    if (isAdded()) {
                        restoreCurrentState();
                        Log.d(TAG, "State restored after orientation change");
                    }
                });
        }
    }

    /**
     * Save current fragment state before orientation change
     */
    private void saveCurrentState() {
        // Save any important state that should persist across orientation changes
        // This could include recording state, preview state, etc.
        Log.d(TAG, "Saving fragment state before orientation change");
    }

    /**
     * Restore fragment state after orientation change
     */
    private void restoreCurrentState() {
        // Restore any saved state after orientation change
        Log.d(TAG, "Restoring fragment state after orientation change");
    }

    // -------------- Fix End: Manual orientation change handling -----------

    // Debug method to help diagnose recording time issue
    private void debugRecordingTimeVariables() {
        Log.d(TAG, "======== DEBUG RECORDING TIME ========");
        Log.d(TAG, "recordingStartTime = " + recordingStartTime);
        Log.d(TAG, "currentTimeMillis = " + System.currentTimeMillis());
        Log.d(TAG, "elapsedRealtime = " + SystemClock.elapsedRealtime());
        Log.d(TAG, "recordingState = " + recordingState);
        Log.d(TAG, "isRecording() = " + isRecording());
        Log.d(TAG, "isPaused() = " + isPaused());
        Log.d(TAG, "======== END DEBUG INFO ========");
    }

    private void performHapticFeedback() {
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        50,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                );
            } else {
                vibrator.vibrate(50);
            }
        }
    }

    private void savePreviewState() {
        // ----- Fix Start for this method(savePreviewState)-----
        // Use the SharedPreferencesManager's method which uses the correct constant
        sharedPreferencesManager.setPreviewEnabled(isPreviewEnabled);
        Log.d(TAG, "Preview state saved: " + isPreviewEnabled);
        // ----- Fix Ended for this method(savePreviewState)-----
    }

    // function to use haptic feedbacks
    private void vibrateTouch() {
        // Haptic Feedback
        Vibrator vibrator = (Vibrator) requireContext().getSystemService(
            Context.VIBRATOR_SERVICE
        );
        if (vibrator != null && vibrator.hasVibrator()) {
            VibrationEffect effect = null;
            if (
                android.os.Build.VERSION.SDK_INT >=
                android.os.Build.VERSION_CODES.Q
            ) {
                effect = VibrationEffect.createPredefined(
                    VibrationEffect.EFFECT_CLICK
                );
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(effect);
            }
        }
    }

    @Override
    public void onViewCreated(
        @NonNull View view,
        @Nullable Bundle savedInstanceState
    ) {
        super.onViewCreated(view, savedInstanceState);
        com.fadcam.Log.i(TAG, "onViewCreated: method entered");

        // Initialize SharedPreferencesManager
        sharedPreferencesManager = SharedPreferencesManager.getInstance(
            requireContext()
        );

        // Initialize camera control state from saved preferences
        currentEvIndex =
            sharedPreferencesManager.getSavedExposureCompensation();
        aeLocked = sharedPreferencesManager.isAeLockedSaved();
        afMode = sharedPreferencesManager.getSavedAfMode();
        com.fadcam.Log.d(
            TAG,
            "Initialized camera control state: EV=" +
            currentEvIndex +
            ", AE Lock=" +
            aeLocked +
            ", AF Mode=" +
            afMode
        );

        // Initialize isAmoledTheme at the top of the method for use throughout
        String currentTheme =
            sharedPreferencesManager.sharedPreferences.getString(
                com.fadcam.Constants.PREF_APP_THEME,
                Constants.DEFAULT_APP_THEME
            );
        boolean isAmoledTheme =
            currentTheme != null &&
            (currentTheme.equalsIgnoreCase("AMOLED") ||
                currentTheme.equalsIgnoreCase("Amoled") ||
                currentTheme.equalsIgnoreCase("Faded Night"));

        // ----- Fix Start: Reset clock color if theme changed (always use theme
        // default) -----
        String lastTheme = sharedPreferencesManager.sharedPreferences.getString(
            "last_theme_for_clock_color",
            null
        );

        com.fadcam.Log.i(
            TAG,
            "Theme check - Current theme: [" +
            currentTheme +
            "], Last theme: [" +
            lastTheme +
            "]"
        );

        // Simple theme change detection
        if (!Objects.equals(currentTheme, lastTheme)) {
            // Theme changed - get appropriate color from SharedPreferencesManager
            // (it handles AMOLED theme special case now)
            String clockColorPref =
                sharedPreferencesManager.getClockCardColor();

            // Apply the color to the clock card
            applyClockCardColor(clockColorPref);

            // Save current theme as last theme
            sharedPreferencesManager.sharedPreferences
                .edit()
                .putString("last_theme_for_clock_color", currentTheme)
                .apply();

            // Remove the toast that shows theme changed
            com.fadcam.Log.i(
                TAG,
                "Theme changed from [" +
                (lastTheme != null ? lastTheme : "null") +
                "] to [" +
                currentTheme +
                "]. Applied color: " +
                clockColorPref
            );
        } else {
            // No theme change - just apply the current color preference
            String clockColorPref =
                sharedPreferencesManager.getClockCardColor();
            applyClockCardColor(clockColorPref);
            com.fadcam.Log.i(
                TAG,
                "Applied saved clock card color: " +
                clockColorPref +
                " for theme: " +
                currentTheme
            );
        }
        // ----- Fix End: Reset clock color if theme changed (always use theme default)
        // -----

        // Initialize ExecutorService
        if (executorService == null || executorService.isShutdown()) {
            executorService = Executors.newSingleThreadExecutor();
        }

        initializeViews(view);

        // -------------- Fix Start (initializeComponents) --------------
        // Initialize UI components using helper
        fragmentHelper = new HomeFragmentHelper(this);
        fragmentHelper.initializeComponents(view);
        // -------------- Fix Ended (initializeComponents) --------------

        // Fragment result listeners for pickers
        android.util.Log.d(
            "HomeFragment",
            "REGISTERING fragment result listener for exposure compensation"
        );
        com.fadcam.Log.d(
            TAG,
            "Registering fragment result listener for exposure compensation with key: " +
            Constants.RK_EXPOSURE_COMPENSATION
        );
        android.util.Log.d(
            "HomeFragment",
            "Using FragmentManager: " +
            getParentFragmentManager() +
            ", this fragment: " +
            this
        );
        getParentFragmentManager().setFragmentResultListener(
                Constants.RK_EXPOSURE_COMPENSATION,
                this,
                (requestKey, bundle) -> {
                    android.util.Log.d(
                        "HomeFragment",
                        "FRAGMENT RESULT RECEIVED: requestKey=" +
                        requestKey +
                        ", bundle=" +
                        bundle
                    );
                    com.fadcam.Log.d(
                        TAG,
                        "Fragment result listener triggered for exposure: requestKey=" +
                        requestKey +
                        ", bundle=" +
                        bundle
                    );
                    if (bundle == null) {
                        com.fadcam.Log.w(
                            TAG,
                            "Exposure listener received null bundle"
                        );
                        return;
                    }
                    // Slider returns an int under BUNDLE_SLIDER_VALUE; fall back to selected id
                    // string for backward compatibility
                    int sliderVal = bundle.getInt(
                        com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SLIDER_VALUE,
                        Integer.MIN_VALUE
                    );
                    com.fadcam.Log.d(
                        TAG,
                        "Exposure bundle contents: sliderVal=" +
                        sliderVal +
                        ", keys=" +
                        bundle.keySet()
                    );
                    if (sliderVal != Integer.MIN_VALUE) {
                        currentEvIndex = sliderVal;
                        // Debug: record that we received slider update (will only write to debug log
                        // when enabled)
                        com.fadcam.Log.d(
                            TAG,
                            "Received exposure slider value: index=" + sliderVal
                        );
                    } else {
                        String sel = bundle.getString(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID,
                            null
                        );
                        com.fadcam.Log.d(
                            TAG,
                            "Slider value not found, checking selected ID: " +
                            sel
                        );
                        if (sel != null) {
                            try {
                                currentEvIndex = Integer.parseInt(sel);
                            } catch (Exception ignored) {}
                        }
                    }
                    SharedPreferencesManager sp =
                        SharedPreferencesManager.getInstance(requireContext());
                    if (
                        !isMyServiceRunning(
                            com.fadcam.services.RecordingService.class
                        )
                    ) {
                        sp.setSavedExposureCompensation(currentEvIndex);
                        com.fadcam.Log.d(
                            TAG,
                            "Exposure saved to prefs via picker"
                        );
                    } else {
                        Intent i =
                            com.fadcam.RecordingControlIntents.setExposureCompensation(
                                requireContext(),
                                currentEvIndex
                            );
                        requireActivity().startService(i);
                        com.fadcam.Log.d(
                            TAG,
                            "Exposure intent sent via picker"
                        );
                    }

                    // Update exposure tile tinting based on current exposure and AE lock state
                    try {
                        if (tileExp != null) {
                            // -------------- Fix Start (exposure tile tinting - Snow Veil theme)-----------
                            // Show orange tint if AE locked or exposure compensation is not at 0
                            if (aeLocked || currentEvIndex != 0) {
                                int orange = getResources().getColor(
                                    R.color.orange_accent,
                                    requireContext().getTheme()
                                );
                                tileExp.setTextColor(orange);
                            } else {
                                // Reset to appropriate default color based on theme
                                boolean isSnowVeilTheme = "Snow Veil".equals(
                                    currentTheme
                                );

                                if (isSnowVeilTheme) {
                                    tileExp.setTextColor(
                                        Color.parseColor("#424242")
                                    ); // Dark gray for Snow Veil
                                } else {
                                    // Use theme's colorOnSurface for other themes
                                    android.util.TypedValue typedValue =
                                        new android.util.TypedValue();
                                    requireContext()
                                        .getTheme()
                                        .resolveAttribute(
                                            com.google.android.material.R.attr.colorOnSurface,
                                            typedValue,
                                            true
                                        );
                                    tileExp.setTextColor(typedValue.data);
                                }
                            }
                            // -------------- Fix Ended (exposure tile tinting - Snow Veil theme)-----------
                        }
                    } catch (Exception ignored) {}
                }
            );

        getParentFragmentManager().setFragmentResultListener(
                Constants.RK_AE_LOCK,
                this,
                (requestKey, bundle) -> {
                    if (bundle == null) return;
                    boolean state = bundle.getBoolean(
                        com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE,
                        aeLocked
                    );
                    aeLocked = state;

                    com.fadcam.Log.d(
                        TAG,
                        "AE Lock listener triggered: " +
                        state +
                        ", tileExp null? " +
                        (tileExp == null)
                    );

                    SharedPreferencesManager sp =
                        SharedPreferencesManager.getInstance(requireContext());
                    if (
                        !isMyServiceRunning(
                            com.fadcam.services.RecordingService.class
                        )
                    ) {
                        sp.setSavedAeLock(aeLocked);
                        com.fadcam.Log.d(
                            TAG,
                            "AE lock saved to prefs via picker"
                        );
                    } else {
                        Intent i =
                            com.fadcam.RecordingControlIntents.toggleAeLock(
                                requireContext(),
                                aeLocked
                            );
                        requireActivity().startService(i);
                        com.fadcam.Log.d(TAG, "AE lock intent sent via picker");
                    }
                    // Update exposure tile visual: tint orange when locked, reset when unlocked
                    try {
                        if (tileExp != null) {
                            // -------------- Fix Start (AE lock exposure tile tinting - Snow Veil
                            // theme)-----------
                            // TextView Material Icons font handling with textColor
                            if (aeLocked || currentEvIndex != 0) {
                                int orange = getResources().getColor(
                                    R.color.orange_accent,
                                    requireContext().getTheme()
                                );
                                tileExp.setTextColor(orange);
                                com.fadcam.Log.d(
                                    TAG,
                                    "Applied orange tint to exposure tile"
                                );
                            } else {
                                // Reset to appropriate default color based on theme
                                boolean isSnowVeilTheme = "Snow Veil".equals(
                                    currentTheme
                                );

                                if (isSnowVeilTheme) {
                                    tileExp.setTextColor(
                                        Color.parseColor("#424242")
                                    ); // Dark gray for Snow Veil
                                } else {
                                    // Use theme's colorOnSurface for other themes
                                    android.util.TypedValue typedValue =
                                        new android.util.TypedValue();
                                    requireContext()
                                        .getTheme()
                                        .resolveAttribute(
                                            com.google.android.material.R.attr.colorOnSurface,
                                            typedValue,
                                            true
                                        );
                                    tileExp.setTextColor(typedValue.data);
                                }
                                com.fadcam.Log.d(
                                    TAG,
                                    "Cleared tint from exposure tile"
                                );
                            }
                            // -------------- Fix Ended (AE lock exposure tile tinting - Snow Veil
                            // theme)-----------
                            // subtle scale to indicate active
                            tileExp.setScaleX(aeLocked ? 1.05f : 1f);
                            tileExp.setScaleY(aeLocked ? 1.05f : 1f);
                        }
                    } catch (Exception e) {
                        com.fadcam.Log.e(
                            TAG,
                            "Error updating exposure tile tint: " +
                            e.getMessage()
                        );
                    }
                }
            );

        getParentFragmentManager().setFragmentResultListener(
                Constants.RK_AF_MODE,
                this,
                (requestKey, bundle) -> {
                    if (bundle == null) return;
                    String sel = bundle.getString(
                        com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID,
                        null
                    );
                    if (sel == null) return;
                    try {
                        afMode = Integer.parseInt(sel);
                    } catch (Exception ignored) {}
                    SharedPreferencesManager sp =
                        SharedPreferencesManager.getInstance(requireContext());
                    if (
                        !isMyServiceRunning(
                            com.fadcam.services.RecordingService.class
                        )
                    ) {
                        sp.setSavedAfMode(afMode);
                        com.fadcam.Log.d(
                            TAG,
                            "AF mode saved to prefs via picker"
                        );
                    } else {
                        Intent i = com.fadcam.RecordingControlIntents.setAfMode(
                            requireContext(),
                            afMode
                        );
                        requireActivity().startService(i);
                        com.fadcam.Log.d(TAG, "AF mode intent sent via picker");
                    }
                    // Update AF tile icon when mode changes
                    try {
                        if (tileAfToggle != null) {
                            tileAfToggle.setText(
                                afMode ==
                                    android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                                    ? "center_focus_strong"
                                    : "center_focus_weak"
                            );
                        }
                    } catch (Exception ignored) {}
                }
            );

        // Listen for Zoom Ratio picker result
        getParentFragmentManager().setFragmentResultListener(
                Constants.RK_ZOOM_RATIO,
                this,
                (requestKey, bundle) -> {
                    if (bundle == null) return;
                    int sliderIndex = bundle.getInt(
                        com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SLIDER_VALUE,
                        -1
                    );
                    if (sliderIndex == -1) return;

                    try {
                        SharedPreferencesManager sp =
                            SharedPreferencesManager.getInstance(
                                requireContext()
                            );
                        CameraType currentCamera = sp.getCameraSelection();

                        // Rebuild zoom ratios to map index back to actual value
                        List<Float> zoomRatios = buildZoomRatioOptions(
                            currentCamera
                        );
                        if (
                            sliderIndex >= 0 && sliderIndex < zoomRatios.size()
                        ) {
                            float zoomRatio = zoomRatios.get(sliderIndex);
                            com.fadcam.Log.d(
                                TAG,
                                "Zoom slider changed: " +
                                zoomRatio +
                                "x (index: " +
                                sliderIndex +
                                ")"
                            );

                            // Save zoom ratio to preferences
                            sp.setSpecificZoomRatio(currentCamera, zoomRatio);

                            // -------------- Fix Start (zoom tile tinting - Snow Veil theme)-----------
                            // Update zoom tile tinting
                            try {
                                if (tileZoom != null) {
                                    boolean isSnowVeilTheme =
                                        "Snow Veil".equals(currentTheme);

                                    if (Math.abs(zoomRatio - 1.0f) > 0.01f) {
                                        // Not at 1.0x default
                                        tileZoom.setTextColor(
                                            ContextCompat.getColor(
                                                requireContext(),
                                                R.color.orange_accent
                                            )
                                        );
                                    } else {
                                        // Use appropriate default color based on theme
                                        if (isSnowVeilTheme) {
                                            tileZoom.setTextColor(
                                                Color.parseColor("#424242")
                                            ); // Dark gray for Snow Veil
                                        } else {
                                            tileZoom.setTextColor(
                                                ContextCompat.getColor(
                                                    requireContext(),
                                                    android.R.color.white
                                                )
                                            );
                                        }
                                    }
                                }
                                // -------------- Fix Ended (zoom tile tinting - Snow Veil theme)-----------
                            } catch (Exception ignored) {}

                            // If recording service is running, apply zoom immediately
                            if (
                                isMyServiceRunning(
                                    com.fadcam.services.RecordingService.class
                                )
                            ) {
                                Intent intent =
                                    com.fadcam.RecordingControlIntents.setZoomRatio(
                                        requireContext(),
                                        zoomRatio
                                    );
                                requireActivity().startService(intent);
                                com.fadcam.Log.d(
                                    TAG,
                                    "Zoom ratio " +
                                    zoomRatio +
                                    "x sent to recording service"
                                );
                            } else {
                                com.fadcam.Log.d(
                                    TAG,
                                    "Zoom ratio " +
                                    zoomRatio +
                                    "x saved to preferences"
                                );
                            }
                        } else {
                            com.fadcam.Log.w(
                                TAG,
                                "Invalid zoom slider index: " + sliderIndex
                            );
                        }
                    } catch (Exception e) {
                        com.fadcam.Log.w(
                            TAG,
                            "Failed to apply zoom ratio from slider index: " +
                            sliderIndex +
                            " - " +
                            e.getMessage()
                        );
                    }
                }
            );

        // Listen for preview toggles from HomeSidebarFragment (key: "home_sidebar_result")
        getParentFragmentManager().setFragmentResultListener(
                "home_sidebar_result",
                this,
                (requestKey, bundle) -> {
                    if (bundle == null) return;
                    if (!bundle.containsKey("preview_enabled")) return;
                    boolean enabled = bundle.getBoolean(
                        "preview_enabled",
                        true
                    );

                    // Remember previous state so we can perform any additional actions when enabling
                    boolean wasEnabled = isPreviewEnabled;

                    // Update local preview state, persist, and apply immediately
                    isPreviewEnabled = enabled;
                    try {
                        if (sharedPreferencesManager == null) {
                            sharedPreferencesManager =
                                SharedPreferencesManager.getInstance(
                                    requireContext()
                                );
                        }
                        sharedPreferencesManager.setPreviewEnabled(
                            isPreviewEnabled
                        );
                    } catch (Exception ignored) {}

                    // Update UI and service surface according to new state
                    updatePreviewVisibility();

                    // If we just enabled preview (was disabled before) ensure TextureView is reset
                    // to avoid showing stale frames or an invalid surface. This mirrors the same
                    // behavior used by the long-press handler which resets the TextureView when enabling.
                    if (!wasEnabled && isPreviewEnabled) {
                        try {
                            resetTextureView();
                        } catch (Exception ignored) {}
                    }

                    if (isRecordingOrPaused()) {
                        if (
                            isPreviewEnabled &&
                            textureViewSurface != null &&
                            textureViewSurface.isValid()
                        ) {
                            updateServiceWithCurrentSurface(textureViewSurface);
                        } else {
                            updateServiceWithCurrentSurface(null);
                        }
                    }
                }
            );

        setupTextureView(view);
        setupButtonListeners();
        setupLongPressListener(); // For Easter eggs on title
        setupClockLongPressListener(); // For display options on clock
        setupAppLogoLongPressListener(view); // <<< CALL NEW METHOD

        // Initialize easter egg messages and setup listener for preview placeholder
        initializeMessages();

        // ----- Fix Start: Apply dynamic theme colors to preview area cards -----
        CardView cardPreview = view.findViewById(R.id.cardPreview);
        CardView cardStats = view.findViewById(R.id.cardStats);
        CardView cardStorage = view.findViewById(R.id.cardStorage);
        // Clock card is intentionally NOT included here as it has its own color logic

        String themeName = sharedPreferencesManager.sharedPreferences.getString(
            com.fadcam.Constants.PREF_APP_THEME,
            Constants.DEFAULT_APP_THEME
        );

        int colorDialog = resolveThemeColor(R.attr.colorDialog);
        int colorButton = resolveThemeColor(R.attr.colorButton);
        int colorTransparent = android.graphics.Color.TRANSPARENT;
        int colorTextPrimary = resolveThemeColor(R.attr.colorHeading);
        int colorTextSecondary = ContextCompat.getColor(
            requireContext(),
            R.color.gray_text_light
        );

        // ----- Fix Start: Apply dynamic theme colors to preview area cards (force
        // override for AMOLED and Red, use *_surface_dark) -----
        if ("Crimson Bloom".equals(themeName)) {
            int redSurface = ContextCompat.getColor(
                requireContext(),
                R.color.red_theme_surface_dark
            );
            int redHeading = ContextCompat.getColor(
                requireContext(),
                R.color.red_theme_heading
            );
            int redTextSecondary = ContextCompat.getColor(
                requireContext(),
                R.color.red_theme_text_secondary_dark
            );
            if (cardPreview != null) cardPreview.setCardBackgroundColor(
                redSurface
            );
            if (cardStats != null) cardStats.setCardBackgroundColor(redSurface);
            if (cardStorage != null) cardStorage.setCardBackgroundColor(
                redSurface
            );
            setTextColorsRecursive(cardPreview, redHeading, redTextSecondary);
            setTextColorsRecursive(cardStats, redHeading, redTextSecondary);
            // Skip storage widget to preserve semantic colors
            // setTextColorsRecursive(cardStorage, redHeading, redTextSecondary);
        } else if ("Premium Gold".equals(themeName)) {
            int goldSurface = ContextCompat.getColor(
                requireContext(),
                R.color.gold_theme_surface_dark
            );
            int goldHeading = ContextCompat.getColor(
                requireContext(),
                R.color.gold_theme_heading
            );
            int goldTextSecondary = ContextCompat.getColor(
                requireContext(),
                R.color.gold_theme_text_secondary_dark
            );
            if (cardPreview != null) cardPreview.setCardBackgroundColor(
                goldSurface
            );
            if (cardStats != null) cardStats.setCardBackgroundColor(
                goldSurface
            );
            if (cardStorage != null) cardStorage.setCardBackgroundColor(
                goldSurface
            );
            setTextColorsRecursive(cardPreview, goldHeading, goldTextSecondary);
            setTextColorsRecursive(cardStats, goldHeading, goldTextSecondary);
            // Skip storage widget to preserve semantic colors
            // setTextColorsRecursive(cardStorage, goldHeading, goldTextSecondary);
        } else if ("Silent Forest".equals(themeName)) {
            // Silent Forest theme (green/teal)
            int forestSurface = ContextCompat.getColor(
                requireContext(),
                R.color.silentforest_theme_surface_dark
            );
            int forestHeading = ContextCompat.getColor(
                requireContext(),
                R.color.silentforest_theme_heading
            );
            int forestTextSecondary = ContextCompat.getColor(
                requireContext(),
                R.color.silentforest_theme_text_secondary_dark
            );
            if (cardPreview != null) cardPreview.setCardBackgroundColor(
                forestSurface
            );
            if (cardStats != null) cardStats.setCardBackgroundColor(
                forestSurface
            );
            if (cardStorage != null) cardStorage.setCardBackgroundColor(
                forestSurface
            );
            setTextColorsRecursive(
                cardPreview,
                forestHeading,
                forestTextSecondary
            );
            setTextColorsRecursive(
                cardStats,
                forestHeading,
                forestTextSecondary
            );
            // Skip storage widget to preserve semantic colors
            // setTextColorsRecursive(cardStorage, forestHeading, forestTextSecondary);
        } else if ("Shadow Alloy".equals(themeName)) {
            // Shadow Alloy theme (silver/metallic)
            int alloySurface = ContextCompat.getColor(
                requireContext(),
                R.color.shadowalloy_theme_surface_dark
            );
            int alloyHeading = ContextCompat.getColor(
                requireContext(),
                R.color.shadowalloy_theme_heading
            );
            int alloyTextSecondary = ContextCompat.getColor(
                requireContext(),
                R.color.shadowalloy_theme_text_secondary_dark
            );
            if (cardPreview != null) cardPreview.setCardBackgroundColor(
                alloySurface
            );
            if (cardStats != null) cardStats.setCardBackgroundColor(
                alloySurface
            );
            if (cardStorage != null) cardStorage.setCardBackgroundColor(
                alloySurface
            );
            setTextColorsRecursive(
                cardPreview,
                alloyHeading,
                alloyTextSecondary
            );
            setTextColorsRecursive(cardStats, alloyHeading, alloyTextSecondary);
            // Skip storage widget to preserve semantic colors
            // setTextColorsRecursive(cardStorage, alloyHeading, alloyTextSecondary);
        } else if ("Pookie Pink".equals(themeName)) {
            // Pookie Pink theme (pink)
            int pinkSurface = ContextCompat.getColor(
                requireContext(),
                R.color.pookiepink_theme_surface_dark
            );
            int pinkHeading = ContextCompat.getColor(
                requireContext(),
                R.color.pookiepink_theme_heading
            );
            int pinkTextSecondary = ContextCompat.getColor(
                requireContext(),
                R.color.pookiepink_theme_text_secondary_dark
            );
            if (cardPreview != null) cardPreview.setCardBackgroundColor(
                pinkSurface
            );
            if (cardStats != null) cardStats.setCardBackgroundColor(
                pinkSurface
            );
            if (cardStorage != null) cardStorage.setCardBackgroundColor(
                pinkSurface
            );
            setTextColorsRecursive(cardPreview, pinkHeading, pinkTextSecondary);
            setTextColorsRecursive(cardStats, pinkHeading, pinkTextSecondary);
            // Skip storage widget to preserve semantic colors
            // setTextColorsRecursive(cardStorage, pinkHeading, pinkTextSecondary);
        } else if ("Snow Veil".equals(themeName)) {
            // Snow Veil theme (white/light)
            // ----- Fix Start: Use darker gray for preview area in Snow Veil theme -----
            int snowSurface = ContextCompat.getColor(
                requireContext(),
                R.color.snowveil_theme_preview_area
            ); // Darker
            // gray for
            // preview
            int snowCardBackground = ContextCompat.getColor(
                requireContext(),
                R.color.snowveil_theme_card_background
            ); // White
            // for
            // other
            // cards
            // ----- Fix End: Use darker gray for preview area in Snow Veil theme -----
            int snowHeading = ContextCompat.getColor(
                requireContext(),
                R.color.snowveil_theme_text_primary
            );
            int snowTextSecondary = ContextCompat.getColor(
                requireContext(),
                R.color.snowveil_theme_text_secondary
            );

            // Preview area gets darker gray for better contrast
            if (cardPreview != null) {
                cardPreview.setCardBackgroundColor(snowSurface);
                // Also set the background of the FrameLayout inside the CardView
                View frameLayout = cardPreview.getChildAt(0);
                if (frameLayout != null) {
                    frameLayout.setBackgroundColor(snowSurface);
                }
                // Use a post-layout runnable to ensure the color is applied after all layout
                // operations
                cardPreview.post(() -> {
                    cardPreview.setCardBackgroundColor(snowSurface);
                    if (frameLayout != null) {
                        frameLayout.setBackgroundColor(snowSurface);
                    }
                });
            }

            // Other cards get white background
            if (cardStats != null) cardStats.setCardBackgroundColor(
                snowCardBackground
            );
            if (cardStorage != null) cardStorage.setCardBackgroundColor(
                snowCardBackground
            );

            // Set text colors - preview area gets white text, other cards get black text
            setTextColorsRecursive(
                cardPreview,
                Color.WHITE,
                Color.parseColor("#E0E0E0")
            ); // White text on dark gray
            setTextColorsRecursive(cardStats, snowHeading, snowTextSecondary); // Black text on white
            setTextColorsRecursive(cardStorage, snowHeading, snowTextSecondary); // Black text on white - FIXED!

            // Apply additional contrast improvements for the Snow Veil theme
            applySnowVeilThemeToUI(view);
        } else if ("Faded Night".equals(themeName)) {
            // Faded Night theme - use a proper gray instead of pure black
            int fadedNightSurface = ContextCompat.getColor(
                requireContext(),
                R.color.amoled_card
            ); // Use card color
            // instead of pure
            // black
            int fadedNightHeading = ContextCompat.getColor(
                requireContext(),
                R.color.amoled_heading
            );
            int fadedNightTextSecondary = ContextCompat.getColor(
                requireContext(),
                R.color.amoled_text_secondary_dark
            );
            if (cardPreview != null) {
                cardPreview.setCardBackgroundColor(fadedNightSurface);
                // Also set the background of the FrameLayout inside the CardView to ensure it
                // overrides the layout attribute
                View frameLayout = cardPreview.getChildAt(0);
                if (frameLayout != null) {
                    frameLayout.setBackgroundColor(fadedNightSurface);
                }
                // Use a post-layout runnable to ensure the color is applied after all layout
                // operations
                cardPreview.post(() -> {
                    cardPreview.setCardBackgroundColor(fadedNightSurface);
                    if (frameLayout != null) {
                        frameLayout.setBackgroundColor(fadedNightSurface);
                    }
                });
            }
            if (cardStats != null) cardStats.setCardBackgroundColor(
                fadedNightSurface
            );
            if (cardStorage != null) cardStorage.setCardBackgroundColor(
                fadedNightSurface
            );
            setTextColorsRecursive(
                cardPreview,
                fadedNightHeading,
                fadedNightTextSecondary
            );
            setTextColorsRecursive(
                cardStats,
                fadedNightHeading,
                fadedNightTextSecondary
            );

            // Apply Faded Night theme to recording tiles
            applyFadedNightThemeToTiles();
        } else if (isAmoledTheme) {
            // Pure AMOLED theme - keep pure black for battery saving
            int amoledSurface = ContextCompat.getColor(
                requireContext(),
                R.color.amoled_surface_dark
            );
            int amoledHeading = ContextCompat.getColor(
                requireContext(),
                R.color.amoled_heading
            );
            int amoledTextSecondary = ContextCompat.getColor(
                requireContext(),
                R.color.amoled_text_secondary_dark
            );
            if (cardPreview != null) cardPreview.setCardBackgroundColor(
                amoledSurface
            );
            if (cardStats != null) cardStats.setCardBackgroundColor(
                amoledSurface
            );
            if (cardStorage != null) cardStorage.setCardBackgroundColor(
                amoledSurface
            );
            setTextColorsRecursive(
                cardPreview,
                amoledHeading,
                amoledTextSecondary
            );
            setTextColorsRecursive(
                cardStats,
                amoledHeading,
                amoledTextSecondary
            );
            // Skip storage widget to preserve semantic colors
            // setTextColorsRecursive(cardStorage, amoledHeading, amoledTextSecondary);
        } else if ("Midnight Dusk".equals(themeName)) {
            int darkSurface = ContextCompat.getColor(
                requireContext(),
                R.color.dark_purple_bar
            );
            int darkHeading = ContextCompat.getColor(
                requireContext(),
                R.color.colorHeading
            );
            int darkTextSecondary = ContextCompat.getColor(
                requireContext(),
                R.color.gray_text_light
            );
            if (cardPreview != null) {
                cardPreview.setCardBackgroundColor(darkSurface);
                // Also set the background of the FrameLayout inside the CardView to ensure it
                // overrides the layout attribute
                View frameLayout = cardPreview.getChildAt(0);
                if (frameLayout != null) {
                    frameLayout.setBackgroundColor(darkSurface);
                }
                // Use a post-layout runnable to ensure the color is applied after all layout
                // operations
                cardPreview.post(() -> {
                    cardPreview.setCardBackgroundColor(darkSurface);
                    if (frameLayout != null) {
                        frameLayout.setBackgroundColor(darkSurface);
                    }
                });
            }
            if (cardStats != null) cardStats.setCardBackgroundColor(
                darkSurface
            );
            if (cardStorage != null) cardStorage.setCardBackgroundColor(
                darkSurface
            );
            setTextColorsRecursive(cardPreview, darkHeading, darkTextSecondary);
            setTextColorsRecursive(cardStats, darkHeading, darkTextSecondary);
            // Skip storage widget to preserve semantic colors
            // setTextColorsRecursive(cardStorage, darkHeading, darkTextSecondary);

            // Apply Midnight Dusk theme to recording tiles
            applyMidnightDuskThemeToTiles();
        } else {
            // Fallback for other themes: use dialog color for cards
            if (cardPreview != null) cardPreview.setCardBackgroundColor(
                colorDialog
            );
            if (cardStats != null) cardStats.setCardBackgroundColor(
                colorDialog
            );
            if (cardStorage != null) cardStorage.setCardBackgroundColor(
                colorDialog
            );
            setTextColorsRecursive(
                cardPreview,
                colorTextPrimary,
                colorTextSecondary
            );
            setTextColorsRecursive(
                cardStats,
                colorTextPrimary,
                colorTextSecondary
            );
            // Skip storage widget to preserve semantic colors
            // setTextColorsRecursive(cardStorage, colorTextPrimary, colorTextSecondary);
        }
        // ----- Fix End: Apply dynamic theme colors to preview area cards (force
        // override for AMOLED and Red, use *_surface_dark) -----

        // Apply theme-specific colors to mode switcher
        applyModeSwitcherTheming(themeName);

        // ----- Fix Start: Storage card theme-specific colors (don't override theme
        // colors) -----
        if (cardStorage != null) {
            if ("Crimson Bloom".equals(themeName)) {
                // Use an even darker background for Crimson Bloom theme
                cardStorage.setCardBackgroundColor(
                    ContextCompat.getColor(
                        requireContext(),
                        R.color.crimson_dark_card_background
                    )
                );
            } else if ("Premium Gold".equals(themeName)) {
                // Use the gold theme specific card background
                cardStorage.setCardBackgroundColor(
                    ContextCompat.getColor(
                        requireContext(),
                        R.color.gold_theme_card_background
                    )
                );
            } else if ("Faded Night".equals(themeName)) {
                // Keep the Faded Night theme color that was already set
                // Don't override it with dark_card_background
            } else if ("Midnight Dusk".equals(themeName)) {
                // Use consistent dark background like other themes
                cardStorage.setCardBackgroundColor(
                    ContextCompat.getColor(
                        requireContext(),
                        R.color.dark_card_background
                    )
                );
            } else if ("Snow Veil".equals(themeName)) {
                // Keep the Snow Veil theme color that was already set
                // Don't override it with dark_card_background
            } else {
                // Standard dark background for other themes that don't have specific theming
                cardStorage.setCardBackgroundColor(
                    ContextCompat.getColor(
                        requireContext(),
                        R.color.dark_card_background
                    )
                );
            }
        }
        // ----- Fix End: Storage card theme-specific colors (don't override theme
        // colors) -----

        // ----- Fix Start: Re-apply clock card color to ensure it's not affected by
        // theme styling -----
        // This ensures the clock card maintains its own independent color regardless of
        // general card styling
        String currentClockColor = sharedPreferencesManager.getClockCardColor();

        // No need for special AMOLED handling here - SharedPreferencesManager handles
        // it

        // Final application of the determined color
        applyClockCardColor(currentClockColor);
        com.fadcam.Log.i(
            TAG,
            "Final clock card color applied: " +
            currentClockColor +
            " for theme: " +
            currentTheme
        );
        // ----- Fix End: Re-apply clock card color to ensure it's not affected by theme
        // styling -----

        vibrator = (Vibrator) requireActivity().getSystemService(
            Context.VIBRATOR_SERVICE
        );
        TorchService.setHomeFragment(this);

        // Add this debug code
        try {
            Drawable onIcon = AppCompatResources.getDrawable(
                requireContext(),
                R.drawable.ic_flashlight_on
            );
            Drawable offIcon = AppCompatResources.getDrawable(
                requireContext(),
                R.drawable.ic_flashlight_on
            );
            Log.d(
                "TorchDebug",
                "Icon resources loaded - ON: " +
                (onIcon != null) +
                ", OFF: " +
                (offIcon != null)
            );
        } catch (Exception e) {
            Log.e(
                "TorchDebug",
                "Error checking icon resources: " + e.getMessage()
            );
        }

        tips = requireActivity()
            .getResources()
            .getStringArray(R.array.tips_widget);
        Log.d(TAG, "onViewCreated: Setting up UI components");

        // *** ADDED FIX: Load preview enabled state from SharedPreferences ***
        if (sharedPreferencesManager == null) {
            // Ensure manager is initialized
            sharedPreferencesManager = SharedPreferencesManager.getInstance(
                requireContext()
            );
        }
        isPreviewEnabled = sharedPreferencesManager.isPreviewEnabled(); // Initialize with saved state
        Log.d(
            TAG,
            "onViewCreated: Loaded isPreviewEnabled state = " + isPreviewEnabled
        );
        // --- END FIX ---

        // ----- Fix Start for this method(onViewCreated_resetTextureView)-----
        // If TextureView is already available, reset it to ensure clean state
        if (textureView != null && textureView.isAvailable()) {
            resetTextureView();
            Log.d(
                TAG,
                "onViewCreated: Reset TextureView to ensure clean startup state"
            );
        }
        // ----- Fix Ended for this method(onViewCreated_resetTextureView)-----

        resetTimers();
        copyFontToInternalStorage();
        updateStorageInfo();
        // Initial stats update
        Log.d(TAG, "onViewCreated: Triggering initial stats update.");
        updateStats();
        startUpdatingClock();

        // Update clock and date initially
        updateClock();

        // updateTip(); // Duplicate call? Check if startTipsAnimation is sufficient
        setupButtonListeners();
        setupLongPressListener();
        updatePreviewVisibility(); // CRUCIAL: Update visibility based on the loaded state

        buttonTorchSwitch = view.findViewById(R.id.buttonTorchSwitch);
        initializeTorch();
        setupTorchButton();

        // Setup the small recording tiles row and listeners
        setupRecordingTiles(view);

        // Apply tile theming after tiles are initialized
        String tileTheme = sharedPreferencesManager.sharedPreferences.getString(
            Constants.PREF_APP_THEME,
            Constants.DEFAULT_APP_THEME
        );
        if ("Faded Night".equals(tileTheme)) {
            applyFadedNightThemeToTiles();
        } else if ("Midnight Dusk".equals(tileTheme)) {
            applyMidnightDuskThemeToTiles();
        }

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

        // ----- Fix Start for this method(onViewCreated_kofi_support_icon) -----
        ImageButton ivKoFiSupport = view.findViewById(R.id.ivKoFiSupport);
        if (ivKoFiSupport != null) {
            ivKoFiSupport.setOnClickListener(v -> {
                // Show Ko-fi support bottom sheet
                KoFiSupportBottomSheet bottomSheet =
                    new KoFiSupportBottomSheet();
                bottomSheet.show(
                    getParentFragmentManager(),
                    "KoFiSupportBottomSheet"
                );
            });

            // Enhanced animation: left-right wiggle + slight rotation, with longer pause
            float moveDistance = 10f; // pixels to move left/right
            float rotateAngle = 15f; // degrees to rotate left/right
            long moveDuration = 160; // ms for each move
            long pauseDuration = 1500; // ms pause at center

            final Runnable[] startWiggle = new Runnable[1];
            startWiggle[0] = new Runnable() {
                @Override
                public void run() {
                    // Move right + rotate right
                    ObjectAnimator moveRight = ObjectAnimator.ofFloat(
                        ivKoFiSupport,
                        "translationX",
                        0f,
                        moveDistance
                    );
                    ObjectAnimator rotateRight = ObjectAnimator.ofFloat(
                        ivKoFiSupport,
                        "rotation",
                        0f,
                        rotateAngle
                    );
                    AnimatorSet rightSet = new AnimatorSet();
                    rightSet.playTogether(moveRight, rotateRight);
                    rightSet.setDuration(moveDuration);

                    // Move left + rotate left
                    ObjectAnimator moveLeft = ObjectAnimator.ofFloat(
                        ivKoFiSupport,
                        "translationX",
                        moveDistance,
                        -moveDistance
                    );
                    ObjectAnimator rotateLeft = ObjectAnimator.ofFloat(
                        ivKoFiSupport,
                        "rotation",
                        rotateAngle,
                        -rotateAngle
                    );
                    AnimatorSet leftSet = new AnimatorSet();
                    leftSet.playTogether(moveLeft, rotateLeft);
                    leftSet.setDuration(moveDuration * 2);

                    // Move center + rotate back to 0
                    ObjectAnimator moveCenter = ObjectAnimator.ofFloat(
                        ivKoFiSupport,
                        "translationX",
                        -moveDistance,
                        0f
                    );
                    ObjectAnimator rotateCenter = ObjectAnimator.ofFloat(
                        ivKoFiSupport,
                        "rotation",
                        -rotateAngle,
                        0f
                    );
                    AnimatorSet centerSet = new AnimatorSet();
                    centerSet.playTogether(moveCenter, rotateCenter);
                    centerSet.setDuration(moveDuration);

                    AnimatorSet wiggleSet = new AnimatorSet();
                    wiggleSet.playSequentially(rightSet, leftSet, centerSet);
                    wiggleSet.addListener(
                        new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                ivKoFiSupport.postDelayed(
                                    startWiggle[0],
                                    pauseDuration
                                );
                            }
                        }
                    );
                    wiggleSet.start();
                }
            };
            // Start the animation loop
            ivKoFiSupport.post(startWiggle[0]);
        }
        // ----- Fix Ended for this method(onViewCreated_kofi_support_icon) -----

        // ----- Fix Start: Apply theme color to top bar and buttons in HomeFragment
        // -----
        MaterialToolbar toolbar = view.findViewById(R.id.topAppBar);
        if (toolbar != null) {
            int colorTopBar = resolveThemeColor(R.attr.colorTopBar);
            toolbar.setBackgroundColor(colorTopBar);
        }
        // If you have FABs or MaterialButtons, set their background tint to colorButton
        // here
    }

    private int resolveThemeColor(int attr) {
        android.util.TypedValue typedValue = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(attr, typedValue, true);
        return typedValue.data;
    }

    // ----- Fix End: Apply theme color to top bar and buttons in HomeFragment -----

    /**
     * Show visual focus indicator at tap location
     */
    private void showFocusIndicator(float x, float y) {
        try {
            if (getView() == null) return;

            Log.d(TAG, "Showing focus indicator at: " + x + ", " + y);

            // Create focus indicator (simple circle animation)
            View focusIndicator = new View(requireContext());
            int size = (int) (80 * getResources().getDisplayMetrics().density); // 80dp - larger and more visible
            android.widget.FrameLayout.LayoutParams params =
                new android.widget.FrameLayout.LayoutParams(size, size);
            params.leftMargin = (int) (x - size / 2f);
            params.topMargin = (int) (y - size / 2f);

            // Create circular background with more prominent styling
            android.graphics.drawable.GradientDrawable drawable =
                new android.graphics.drawable.GradientDrawable();
            drawable.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            drawable.setStroke(8, 0xFFFF0000); // Bright red thick ring
            drawable.setColor(0x44FF0000); // Semi-transparent red fill
            focusIndicator.setBackground(drawable);
            focusIndicator.setLayoutParams(params);
            focusIndicator.setAlpha(0f);
            focusIndicator.setElevation(20f); // High elevation to ensure visibility

            // Find the parent layout that contains the TextureView
            ViewGroup parentLayout = (ViewGroup) textureView.getParent();
            if (parentLayout != null) {
                parentLayout.addView(focusIndicator);
                Log.d(TAG, "Added focus indicator to parent layout");
            } else {
                Log.w(TAG, "Could not find parent layout for focus indicator");
                return;
            }

            // Animate: quick fade in, scale pulse, fade out
            ObjectAnimator fadeIn = ObjectAnimator.ofFloat(
                focusIndicator,
                "alpha",
                0f,
                1f
            );
            ObjectAnimator scaleXIn = ObjectAnimator.ofFloat(
                focusIndicator,
                "scaleX",
                1.5f,
                1f
            );
            ObjectAnimator scaleYIn = ObjectAnimator.ofFloat(
                focusIndicator,
                "scaleY",
                1.5f,
                1f
            );
            ObjectAnimator scaleXOut = ObjectAnimator.ofFloat(
                focusIndicator,
                "scaleX",
                1f,
                0.8f
            );
            ObjectAnimator scaleYOut = ObjectAnimator.ofFloat(
                focusIndicator,
                "scaleY",
                1f,
                0.8f
            );
            ObjectAnimator fadeOut = ObjectAnimator.ofFloat(
                focusIndicator,
                "alpha",
                1f,
                0f
            );

            AnimatorSet animSet = new AnimatorSet();
            animSet.play(fadeIn).with(scaleXIn).with(scaleYIn);
            animSet.play(scaleXOut).with(scaleYOut).after(fadeIn);
            animSet.play(fadeOut).after(scaleXOut);

            fadeIn.setDuration(100);
            scaleXIn.setDuration(200);
            scaleYIn.setDuration(200);
            scaleXOut.setDuration(400);
            scaleYOut.setDuration(400);
            fadeOut.setDuration(200);

            animSet.addListener(
                new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        try {
                            if (parentLayout != null) {
                                parentLayout.removeView(focusIndicator);
                                Log.d(
                                    TAG,
                                    "Removed focus indicator from parent layout"
                                );
                            }
                        } catch (Exception e) {
                            Log.w(
                                TAG,
                                "Error removing focus indicator: " +
                                e.getMessage()
                            );
                        }
                    }
                }
            );

            animSet.start();
            Log.d(TAG, "Focus indicator animation started");
        } catch (Exception e) {
            Log.e(TAG, "Error showing focus indicator: " + e.getMessage(), e);
        }
    }

    private void setupTextureView(@NonNull View view) {
        textureView = view.findViewById(R.id.textureView);

        Log.d(
            TAG,
            "setupTextureView: TextureView found: " + (textureView != null)
        );
        if (textureView != null) {
            Log.d(
                TAG,
                "TextureView dimensions: " +
                textureView.getWidth() +
                "x" +
                textureView.getHeight()
            );
            Log.d(
                TAG,
                "TextureView visibility: " + textureView.getVisibility()
            );
            Log.d(TAG, "TextureView clickable: " + textureView.isClickable());
            Log.d(TAG, "TextureView enabled: " + textureView.isEnabled());
        }

        // Check if the placeholder TextView is interfering
        TextView tvPreviewPlaceholder = view.findViewById(
            R.id.tvPreviewPlaceholder
        );
        if (tvPreviewPlaceholder != null) {
            Log.d(
                TAG,
                "Preview placeholder visibility: " +
                tvPreviewPlaceholder.getVisibility()
            );
            Log.d(
                TAG,
                "Preview placeholder clickable: " +
                tvPreviewPlaceholder.isClickable()
            );

            // Make sure placeholder doesn't intercept touches
            tvPreviewPlaceholder.setClickable(false);
            tvPreviewPlaceholder.setFocusable(false);
        }

        // Setup tap-to-focus and long-press on the preview using GestureDetector for robust handling
        final android.view.GestureDetector gestureDetector =
            new android.view.GestureDetector(
                requireContext(),
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapUp(MotionEvent e) {
                        if (
                            !isRecordingOrPaused() || textureView == null
                        ) return false;
                        float x = e.getX();
                        float y = e.getY();
                        int width = textureView.getWidth();
                        int height = textureView.getHeight();
                        if (width <= 0 || height <= 0) return false;

                        float normalizedX = x / width;
                        float normalizedY = y / height;
                        Log.d(
                            TAG,
                            "GestureDetector tap: normalized=" +
                            normalizedX +
                            "," +
                            normalizedY
                        );

                        Intent tapToFocusIntent =
                            RecordingControlIntents.tapToFocus(
                                requireContext(),
                                normalizedX,
                                normalizedY
                            );
                        requireContext().startService(tapToFocusIntent);
                        showFocusIndicator(x, y);
                        return true;
                    }

                    @Override
                    public void onLongPress(MotionEvent e) {
                        performHapticFeedback();
                        // Use the shared handler to toggle preview/animate
                        handlePreviewLongPress();
                    }

                    @Override
                    public boolean onDown(MotionEvent e) {
                        // Must return true to receive subsequent events (including long press)
                        return true;
                    }
                }
            );

        textureView.setOnTouchListener((v, event) -> {
            try {
                boolean handled = gestureDetector.onTouchEvent(event);
                // For ACTION_UP/ACTION_CANCEL ensure any pending long-press is completed by the detector
                if (
                    event.getAction() == MotionEvent.ACTION_UP ||
                    event.getAction() == MotionEvent.ACTION_CANCEL
                ) {
                    // Allow detector to finalize; no-op here
                }
                return handled;
            } catch (Exception ex) {
                Log.w(
                    TAG,
                    "Gesture handling failed, falling back to older touch logic: " +
                    (ex != null ? ex.getMessage() : "")
                );
                // Fallback: existing logic for tap-to-focus on ACTION_UP
                if (
                    event.getAction() == MotionEvent.ACTION_UP &&
                    isRecordingOrPaused()
                ) {
                    float x = event.getX();
                    float y = event.getY();
                    int width = v.getWidth();
                    int height = v.getHeight();
                    if (width > 0 && height > 0) {
                        float normalizedX = x / width;
                        float normalizedY = y / height;
                        Intent tapToFocusIntent =
                            RecordingControlIntents.tapToFocus(
                                requireContext(),
                                normalizedX,
                                normalizedY
                            );
                        requireContext().startService(tapToFocusIntent);
                        showFocusIndicator(x, y);
                        return true;
                    }
                }
                return false;
            }
        });

        // Also try setting clickable and focusable
        textureView.setClickable(true);
        textureView.setFocusable(true);
        textureView.setEnabled(true);

        // Add a simple click listener as a test
        textureView.setOnClickListener(v -> {
            Log.d(TAG, "TextureView OnClickListener triggered!");
            if (isRecordingOrPaused()) {
                Toast.makeText(
                    requireContext(),
                    "TextureView clicked! (fallback)",
                    Toast.LENGTH_SHORT
                ).show();
            }
        });

        // Also set up touch listener on parent FrameLayout as backup
        View parentFrame = (View) textureView.getParent();
        if (parentFrame instanceof FrameLayout) {
            Log.d(
                TAG,
                "Setting up touch listener on parent FrameLayout as backup"
            );
            parentFrame.setOnTouchListener((v, event) -> {
                Log.d(
                    TAG,
                    "FrameLayout touch event: action=" + event.getAction()
                );
                if (
                    event.getAction() == MotionEvent.ACTION_UP &&
                    isRecordingOrPaused()
                ) {
                    // Forward to TextureView's touch listener
                    return textureView.dispatchTouchEvent(event);
                }
                return false;
            });
        }

        Log.d(
            TAG,
            "TextureView touch listener and click listener set up successfully"
        );

        textureView.setSurfaceTextureListener(
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(
                    @NonNull SurfaceTexture surfaceTexture,
                    int width,
                    int height
                ) {
                    Log.d(
                        TAG,
                        "onSurfaceTextureAvailable: SurfaceTexture is now available. Size: " +
                        width +
                        "x" +
                        height
                    );
                    if (textureViewSurface != null) {
                        textureViewSurface.release();
                    }
                    textureViewSurface = new Surface(surfaceTexture);
                    Log.d(
                        TAG,
                        "onSurfaceTextureAvailable: Created new surface from texture"
                    );
                    if (isPreviewEnabled && isRecordingOrPaused()) {
                        Log.d(
                            TAG,
                            "onSurfaceTextureAvailable: Recording in progress, sending surface to service"
                        );
                        updateServiceWithCurrentSurface(
                            textureViewSurface,
                            width,
                            height
                        );
                    } else {
                        Log.d(
                            TAG,
                            "onSurfaceTextureAvailable: Not recording or preview disabled, surface ready for later use"
                        );
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(
                    @NonNull SurfaceTexture surfaceTexture,
                    int width,
                    int height
                ) {
                    Log.d(
                        TAG,
                        "onSurfaceTextureSizeChanged: Size changed to " +
                        width +
                        "x" +
                        height
                    );
                    if (
                        isPreviewEnabled &&
                        isRecordingOrPaused() &&
                        textureViewSurface != null &&
                        textureViewSurface.isValid()
                    ) {
                        Log.d(
                            TAG,
                            "onSurfaceTextureSizeChanged: Updating surface dimensions"
                        );
                        updateServiceWithCurrentSurface(
                            textureViewSurface,
                            width,
                            height
                        );
                    }
                }

                @Override
                public boolean onSurfaceTextureDestroyed(
                    @NonNull SurfaceTexture surface
                ) {
                    Log.d(
                        TAG,
                        "onSurfaceTextureDestroyed: SurfaceTexture is being destroyed."
                    );
                    if (textureViewSurface != null) {
                        if (isRecordingOrPaused()) {
                            Log.d(
                                TAG,
                                "onSurfaceTextureDestroyed: Recording active, sending null surface to service."
                            );
                            updateServiceWithCurrentSurface(null);
                        }
                        textureViewSurface.release();
                        textureViewSurface = null;
                        Log.d(
                            TAG,
                            "onSurfaceTextureDestroyed: Released local textureViewSurface."
                        );
                    }
                    return true; // Surface is released by the listener
                }

                @Override
                public void onSurfaceTextureUpdated(
                    @NonNull SurfaceTexture surface
                ) {
                    // This gets called every frame, so don't log here
                }
            }
        );
    }

    // -------------- Fix Start (setupButtonListeners with debouncing)-----------
    private void setupButtonListeners() {
        // Initialize debounced runnables for start/stop actions to prevent rapid clicking
        if (debouncedStartRecording == null) {
            debouncedStartRecording = new DebouncedRunnable(() -> {
                if (recordingState.equals(RecordingState.NONE)) {
                    startRecording();
                }
            }, 1000); // 1 second debounce
        }

        if (debouncedStopRecording == null) {
            debouncedStopRecording = new DebouncedRunnable(() -> {
                if (!recordingState.equals(RecordingState.NONE)) {
                    stopRecording();
                    updateStats();
                }
            }, 500); // 0.5 second debounce for stop (shorter for responsiveness)
        }

        buttonStartStop.setOnClickListener(v -> {
            // ----- Fix Start: Add debug logs and ensure recordingState is correct -----
            Log.d(
                TAG,
                "Start/Stop button clicked. recordingState=" +
                recordingState +
                ", enabled=" +
                buttonStartStop.isEnabled()
            );

            // Prevent rapid clicking by temporarily disabling the button
            if (!buttonStartStop.isEnabled()) {
                Log.d(TAG, "Button click ignored - button disabled");
                return;
            }

            // If service is not running, force recordingState to NONE
            if (!isMyServiceRunning(RecordingService.class)) {
                Log.d(
                    TAG,
                    "Service not running, forcing recordingState to NONE"
                );
                recordingState = RecordingState.NONE;
            }
            // ----- Fix End: Add debug logs and ensure recordingState is correct -----

            // Temporarily disable button to prevent rapid clicks
            buttonStartStop.setEnabled(false);
            
            // Re-enable after a short delay
            handlerClock.postDelayed(() -> {
                if (buttonStartStop != null && isAdded()) {
                    buttonStartStop.setEnabled(true);
                    Log.d(TAG, "Button re-enabled after cooldown period");
                }
            }, 1500); // 1.5 second cooldown

            if (recordingState.equals(RecordingState.NONE)) {
                debouncedStartRecording.run();
            } else {
                debouncedStopRecording.run();
            }
        });
    // -------------- Fix Ended (setupButtonListeners with debouncing)-----------

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
        // Permission checks removed; handled by onboarding

        // Force reset recording state if service is not running
        if (!isMyServiceRunning(RecordingService.class)) {
            Log.d(TAG, "Service not running, forcing recordingState to NONE");
            recordingState = RecordingState.NONE;
        }

        if (isMyServiceRunning(RecordingService.class)) {
            Log.w(
                TAG,
                "Start requested, but service appears to be already running or starting. Current state: " +
                recordingState
            );
            // Query the service for its actual state if unsure
            Intent queryIntent = new Intent(
                getContext(),
                RecordingService.class
            );
            queryIntent.setAction(
                Constants.BROADCAST_ON_RECORDING_STATE_REQUEST
            );
            ContextCompat.startForegroundService(getContext(), queryIntent);
            // UI should update based on the broadcast from the service
            return; // Don't try to start again if it might be running
        }

        Log.d(TAG, "startRecording: Starting RecordingService.");
        Intent serviceIntent = new Intent(getContext(), RecordingService.class);
        serviceIntent.setAction(Constants.INTENT_ACTION_START_RECORDING);

        // ----- Fix Start for this method(startRecording_passTorchState)-----
        // Pass current torch state (from HomeFragment's perspective) to the service
        // The service will use this to set the initial FLASH_MODE in its CaptureRequest
        // if it starts successfully.
        Log.d(TAG, "Passing initial torch state to service: " + isTorchOn);
        serviceIntent.putExtra(
            Constants.INTENT_EXTRA_INITIAL_TORCH_STATE,
            isTorchOn
        );
        // ----- Fix Ended for this method(startRecording_passTorchState)-----

        // Pass the surface if preview is enabled and surface is valid
        if (
            isPreviewEnabled &&
            textureViewSurface != null &&
            textureViewSurface.isValid()
        ) {
            Log.d(TAG, "Preview enabled, passing valid surface to service.");
            serviceIntent.putExtra("SURFACE", textureViewSurface);
        } else {
            Log.w(
                TAG,
                "Preview disabled or surface invalid. Service will start without preview surface."
            );
            serviceIntent.putExtra("SURFACE", (Surface) null); // Explicitly pass null
        }

        ContextCompat.startForegroundService(getContext(), serviceIntent);
        // UI state changes will be handled by broadcast receivers
        // setUIForRecordingActive(); // Move UI update to onRecordingStarted broadcast
        // receiver
        Log.d(TAG, "startRecording: RecordingService start initiated.");
    }

    // Inside HomeFragment.java

    /**
     * Helper method to disable buttons typically unavailable during
     * recording initiation, stopping transitions, or active recording/pausing.
     * Checks if views exist before modifying them.
     */
    private void disableInteractionButtons() {
        Log.d(TAG, "Attempting to disable interaction buttons.");
        if (!isAdded() || getView() == null) {
            // Extra check for view availability
            Log.w(
                TAG,
                "disableInteractionButtons: View not available, cannot disable buttons."
            );
            return;
        }
        try {
            // Null checks are crucial inside helper methods
            if (buttonStartStop != null) {
                buttonStartStop.setEnabled(false);
                Log.v(TAG, "Disabled: Start/Stop Button"); // Verbose log
            } else Log.w(
                TAG,
                "buttonStartStop is null in disableInteractionButtons"
            );

            if (buttonPauseResume != null) {
                buttonPauseResume.setEnabled(false);
                Log.v(TAG, "Disabled: Pause/Resume Button");
            } else Log.w(
                TAG,
                "buttonPauseResume is null in disableInteractionButtons"
            );

            if (buttonCamSwitch != null) {
                buttonCamSwitch.setEnabled(false);
                Log.v(TAG, "Disabled: Camera Switch Button");
            } else Log.w(
                TAG,
                "buttonCamSwitch is null in disableInteractionButtons"
            );

            // if (buttonTorchSwitch != null) {
            // buttonTorchSwitch.setEnabled(false); // Also disable torch during these
            // states
            // Log.v(TAG,"Disabled: Torch Button");
            // } else Log.w(TAG,"buttonTorchSwitch is null in disableInteractionButtons");

            Log.d(TAG, "Interaction buttons disabled.");
        } catch (Exception e) {
            // Catch potential NPE or other issues if views are somehow null unexpectedly
            Log.e(TAG, "Error occurred while disabling interaction buttons", e);
        }
    }

    private void updateRecordingSurface() {
        SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();

        Intent startIntent = new Intent(getActivity(), RecordingService.class);
        startIntent.setAction(Constants.INTENT_ACTION_CHANGE_SURFACE);

        if (surfaceTexture != null) {
            startIntent.putExtra("SURFACE", new Surface(surfaceTexture));
        }

        requireActivity().startService(startIntent);
    }

    private void startUpdatingClock() {
        updateClockRunnable = new Runnable() {
            @Override
            public void run() {
                if (isAdded()) {
                    updateClock();
                    handlerClock.postDelayed(this, 1000);
                }
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
        scaleDownSet.addListener(
            new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    // Start scaling up animation after scaling down
                    scaleUpSet.start();
                    performHapticFeedback();
                }
            }
        );

        scaleUpSet.addListener(
            new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    // Start scaling back to original size after scaling up
                    scaleBackSet.start();
                }
            }
        );

        // Start the sequence with scaling down
        scaleDownSet.start();
    }

    private void showClockAppearanceDialog() {
        final String[] appearanceOptions = {
            "Change Clock Display",
            "Change Clock Color",
        };
        int white = ContextCompat.getColor(
            requireContext(),
            android.R.color.white
        );
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
            requireContext(),
            android.R.layout.simple_list_item_1,
            appearanceOptions
        ) {
            @Override
            public View getView(
                int position,
                View convertView,
                ViewGroup parent
            ) {
                View view = super.getView(position, convertView, parent);
                TextView text1 = view.findViewById(android.R.id.text1);
                if (text1 != null) text1.setTextColor(white);
                return view;
            }
        };
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
            requireContext(),
            R.style.ThemeOverlay_FadCam_Dialog
        )
            .setTitle("Clock Appearance")
            .setAdapter(adapter, (dialog, which) -> {
                if (which == 0) {
                    // Change Clock Display
                    showDisplayOptionsDialog();
                } else if (which == 1) {
                    // Change Clock Color
                    showClockColorChooserDialog();
                }
            })
            .setNegativeButton(R.string.universal_cancel, null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(dialogInterface -> {
            // Set button text color to white
            dialog
                .getButton(AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(Color.WHITE);
        });
        dialog.show();
    }

    private void showDisplayOptionsDialog() {
        final String[] items = {
            getString(R.string.dialog_clock_timeonly),
            getString(R.string.dialog_clock_englishtime),
            getString(R.string.dialog_clock_Islamic_calendar),
        };
        int currentOption = getCurrentDisplayOption();
        int white = ContextCompat.getColor(
            requireContext(),
            android.R.color.white
        );
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
            requireContext(),
            android.R.layout.simple_list_item_single_choice,
            items
        ) {
            @Override
            public View getView(
                int position,
                View convertView,
                ViewGroup parent
            ) {
                View view = super.getView(position, convertView, parent);
                TextView text1 = view.findViewById(android.R.id.text1);
                if (text1 != null) text1.setTextColor(white);
                return view;
            }
        };
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
            requireContext(),
            R.style.ThemeOverlay_FadCam_Dialog
        )
            .setTitle(getString(R.string.dialog_clock_title))
            .setSingleChoiceItems(adapter, currentOption, (dialog, which) -> {
                saveDisplayOption(which);
                updateClock();
                dialog.dismiss();
            })
            .setNegativeButton(R.string.universal_cancel, null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(dialogInterface -> {
            // Set button text color to white
            dialog
                .getButton(AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(Color.WHITE);
        });
        dialog.show();
    }

    private void showClockColorChooserDialog() {
        String currentSelectedColorHex =
            sharedPreferencesManager.getClockCardColor();
        int currentSelectedColorIndex = -1;
        for (int i = 0; i < CLOCK_COLOR_HEX_VALUES.length; i++) {
            if (
                CLOCK_COLOR_HEX_VALUES[i].equalsIgnoreCase(
                    currentSelectedColorHex
                )
            ) {
                currentSelectedColorIndex = i;
                break;
            }
        }
        int white = ContextCompat.getColor(
            requireContext(),
            android.R.color.white
        );
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
            requireContext(),
            android.R.layout.simple_list_item_single_choice,
            CLOCK_COLOR_NAMES
        ) {
            @Override
            public View getView(
                int position,
                View convertView,
                ViewGroup parent
            ) {
                View view = super.getView(position, convertView, parent);
                TextView text1 = view.findViewById(android.R.id.text1);
                if (text1 != null) {
                    text1.setTextColor(white);
                    // ----- Fix Start: Add color circle before color name -----
                    int size = (int) (text1.getTextSize() * 1.2f);
                    GradientDrawable circle = new GradientDrawable();
                    circle.setShape(GradientDrawable.OVAL);
                    circle.setColor(
                        Color.parseColor(CLOCK_COLOR_HEX_VALUES[position])
                    );
                    circle.setSize(size, size);
                    // Set as left drawable
                    text1.setCompoundDrawablesWithIntrinsicBounds(
                        circle,
                        null,
                        null,
                        null
                    );
                    text1.setCompoundDrawablePadding(24);
                    // ----- Fix End: Add color circle before color name -----
                }
                return view;
            }
        };
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
            requireContext(),
            R.style.ThemeOverlay_FadCam_Dialog
        )
            .setTitle("Choose Clock Background Color")
            .setSingleChoiceItems(
                adapter,
                currentSelectedColorIndex,
                (dialog, which) -> {
                    String selectedColorHex = CLOCK_COLOR_HEX_VALUES[which];
                    sharedPreferencesManager.setClockCardColor(
                        selectedColorHex
                    );
                    applyClockCardColor(selectedColorHex);

                    // Update clock text colors based on background brightness
                    int selectedColor = Color.parseColor(selectedColorHex);
                    boolean isLightColor = isLightColor(selectedColor);

                    // Set text colors based on background brightness
                    if (isLightColor) {
                        tvClock.setTextColor(Color.BLACK);
                        tvDateEnglish.setTextColor(Color.BLACK);
                        tvDateArabic.setTextColor(Color.BLACK);
                    } else {
                        tvClock.setTextColor(Color.WHITE);
                        tvDateEnglish.setTextColor(Color.WHITE);
                        tvDateArabic.setTextColor(Color.WHITE);
                    }

                    Log.d(
                        TAG,
                        "User selected clock color: " +
                        CLOCK_COLOR_NAMES[which] +
                        " (" +
                        selectedColorHex +
                        ")"
                    );
                    dialog.dismiss();
                }
            )
            .setNegativeButton(R.string.universal_cancel, null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(dialogInterface -> {
            // Set button text color to white
            dialog
                .getButton(AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(Color.WHITE);
        });
        dialog.show();
    }

    /**
     * Determines if a color is light or dark.
     *
     * @param color The color to check
     * @return true if the color is light, false if dark
     */
    private boolean isLightColor(int color) {
        // Calculate the perceived brightness using the formula
        // (0.299*R + 0.587*G + 0.114*B)
        double brightness =
            (Color.red(color) * 0.299) +
            (Color.green(color) * 0.587) +
            (Color.blue(color) * 0.114);
        // If the brightness is greater than 160, consider it a light color
        return brightness > 160;
    }

    private int getCurrentDisplayOption() {
        return requireActivity()
            .getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
            .getInt("display_option", 2); // Default to "Everything"
    }

    private void saveDisplayOption(int option) {
        SharedPreferences.Editor editor = requireActivity()
            .getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
            .edit();
        editor.putInt("display_option", option);
        editor.apply();
    }

    private void showOptionsAndAnimate() {
        addWobbleAnimation();
        showDisplayOptionsDialog();
    }

    // Method to update the clock and dates
    private void updateClock() {
        if (!isAdded()) return; // Prevent crash if fragment is not attached
        SharedPreferences prefs = requireActivity().getSharedPreferences(
            "AppPreferences",
            Context.MODE_PRIVATE
        );
        int displayOption = prefs.getInt("display_option", 2); // Default to "Everything"

        // Update the time
        SimpleDateFormat timeFormat = new SimpleDateFormat(
            "hh:mm a",
            Locale.getDefault()
        );
        String currentTime = timeFormat.format(new Date());
        tvClock.setText(currentTime);

        // Get the current clock background color and determine if it's light or dark
        int backgroundColor = -1;
        if (cardClock != null) {
            backgroundColor =
                ((ColorStateList) cardClock.getCardBackgroundColor()).getDefaultColor();
        }

        // Set text colors based on background brightness and theme settings
        if (backgroundColor != -1) {
            // Use background color to determine text color
            boolean isLightBackground = isLightColor(backgroundColor);
            int textColor = isLightBackground ? Color.BLACK : Color.WHITE;

            tvClock.setTextColor(textColor);
            tvDateEnglish.setTextColor(textColor);
            tvDateArabic.setTextColor(textColor);

            Log.d(
                TAG,
                "updateClock: Applied text color based on clock background: " +
                (isLightBackground ? "BLACK" : "WHITE")
            );
        } else {
            // Fallback to theme-based coloring if clock background color can't be
            // determined
            String currentTheme =
                sharedPreferencesManager.sharedPreferences.getString(
                    com.fadcam.Constants.PREF_APP_THEME,
                    Constants.DEFAULT_APP_THEME
                );
            if ("Crimson Bloom".equals(currentTheme)) {
                // For Crimson Bloom theme, force white text for better visibility against red
                // background
                tvClock.setTextColor(Color.WHITE);
                tvDateEnglish.setTextColor(Color.WHITE);
                tvDateArabic.setTextColor(Color.WHITE);
            } else if ("Premium Gold".equals(currentTheme)) {
                // For Premium Gold theme, force black text for better visibility against gold
                // background
                tvClock.setTextColor(Color.BLACK);
                tvDateEnglish.setTextColor(Color.BLACK);
                tvDateArabic.setTextColor(Color.BLACK);
            } else {
                // For other dark themes, use white text
                tvClock.setTextColor(Color.WHITE);
                tvDateEnglish.setTextColor(Color.WHITE);
                tvDateArabic.setTextColor(Color.WHITE);
            }
        }

        // Update the date in English
        SimpleDateFormat dateFormatEnglish = new SimpleDateFormat(
            "EEE, MMM d",
            Locale.getDefault()
        );
        String currentDateEnglish = dateFormatEnglish.format(new Date());

        // Update the date in Arabic (Islamic calendar)
        String currentDateArabic = "N/A";
        if (
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
        ) {
            HijrahDate hijrahDate = HijrahChronology.INSTANCE.dateNow();
            DateTimeFormatter dateFormatterArabic = DateTimeFormatter.ofPattern(
                "d MMMM yyyy",
                new Locale("ar")
            );
            currentDateArabic = dateFormatterArabic.format(hijrahDate);
        }

        // Set text visibility based on user choice
        tvDateEnglish.setText(
            displayOption == 1 || displayOption == 2 ? currentDateEnglish : ""
        );
        tvDateEnglish.setPadding(
            5,
            tvPreviewPlaceholder.getPaddingTop(),
            5,
            tvPreviewPlaceholder.getPaddingBottom()
        );

        tvDateArabic.setText(displayOption == 2 ? currentDateArabic : "");
    }

    private void updateStorageInfo() {
        Log.d(TAG, "updateStorageInfo: Updating storage information");

        // -------------- Fix Start (updateStorageInfo) - Professional storage caching
        // -----------

        // Step 1: Try to use cached storage info for instant display
        StorageInfoCache.StorageInfo cachedInfo =
            StorageInfoCache.getCachedStorageInfo();
        if (cachedInfo != null) {
            Log.d(TAG, "Using cached storage info - displaying instantly");
            updateStorageUiWithCachedInfo(cachedInfo);
            return;
        }

        // Step 2: Calculate fresh storage info in background to avoid blocking UI
        if (executorService == null || executorService.isShutdown()) {
            executorService = Executors.newSingleThreadExecutor();
        }

        executorService.submit(() -> {
            try {
                // Calculate fresh storage information
                StorageInfoCache.StorageInfo storageInfo =
                    StorageInfoCache.calculateAndCacheStorageInfo(
                        requireContext(),
                        sharedPreferencesManager
                    );

                // Update UI on main thread
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() ->
                        updateStorageUiWithCachedInfo(storageInfo)
                    );
                }
            } catch (Exception e) {
                Log.e(TAG, "Error calculating storage info", e);
            }
        });

        // -------------- Fix End (updateStorageInfo) -----------
    }

    /**
     * Updates storage UI with cached storage information for instant display
     */
    private void updateStorageUiWithCachedInfo(
        StorageInfoCache.StorageInfo storageInfo
    ) {
        // Calculate elapsed time and estimates
        long elapsedTime = 0;
        long estimatedBytesUsed = 0;

        if (isRecording() || isPaused()) {
            // Check if recordingStartTime is valid
            if (recordingStartTime <= 0) {
                recordingStartTime = SystemClock.elapsedRealtime();
                Log.w(
                    TAG,
                    "updateStorageInfo: Invalid recordingStartTime detected, resetting to current time"
                );
            }

            elapsedTime = Math.max(
                0,
                SystemClock.elapsedRealtime() - recordingStartTime
            );

            // Calculate estimated bytes used during recording
            long effectiveBitrate = 0;
            try {
                long videoComponent =
                    sharedPreferencesManager.getCurrentBitrate();
                long audioComponent =
                    sharedPreferencesManager.getAudioBitrate();
                effectiveBitrate = videoComponent + audioComponent;
            } catch (Exception ex) {
                try {
                    long videoComponent = videoBitrate > 0
                        ? videoBitrate
                        : Utils.estimateBitrate(
                            sharedPreferencesManager.getCameraResolution(),
                            sharedPreferencesManager.getVideoFrameRate()
                        );
                    long audioComponent =
                        sharedPreferencesManager.getAudioBitrate();
                    effectiveBitrate = videoComponent + audioComponent;
                } catch (Exception ignore) {
                    effectiveBitrate = 0;
                }
            }

            if (elapsedTime > 0 && effectiveBitrate > 0) {
                estimatedBytesUsed = (elapsedTime * effectiveBitrate) / 8000;
                estimatedBytesUsed = Math.min(
                    estimatedBytesUsed,
                    storageInfo.availableBytes
                );
            }

            videoBitrate = Math.max(
                0,
                effectiveBitrate - sharedPreferencesManager.getAudioBitrate()
            );
        } else {
            recordingStartTime = 0;
        }

        // Adjust available bytes for recording
        long adjustedAvailable =
            storageInfo.availableBytes - estimatedBytesUsed;

        // Skip subtracting if using custom removable storage that can't be probed
        if (
            storageInfo.usingCustomStorage &&
            !storageInfo.customIsOnPrimary &&
            adjustedAvailable < 0
        ) {
            adjustedAvailable = storageInfo.availableBytes;
            Log.d(
                TAG,
                "updateStorageInfo: Skipping estimated bytes subtraction for removable custom storage"
            );
        }

        adjustedAvailable = Math.max(0, adjustedAvailable);
        double gbAvailable = Math.max(
            0,
            adjustedAvailable / (1024.0 * 1024.0 * 1024.0)
        );
        double gbTotal = storageInfo.getTotalGB();

        // -------------- Fix Start (updateStorageUiWithCachedInfo - remaining time
        // calculation)-----------
        // Calculate remaining recording time - only when recording is active
        long remainingTime = 0;
        long days = 0,
            hours = 0,
            minutes = 0,
            seconds = 0;

        if (isRecording() || isPaused()) {
            // Only calculate remaining time when recording is active
            long audioBps = 0;
            try {
                audioBps = sharedPreferencesManager.getAudioBitrate();
            } catch (Exception ignore) {}
            long effectiveForRemaining =
                Math.max(0L, videoBitrate) + Math.max(0L, audioBps);
            if (effectiveForRemaining > 0) {
                remainingTime = (adjustedAvailable * 8) / effectiveForRemaining;
            }
            remainingTime = Math.max(0, remainingTime);

            // Calculate time components
            days = remainingTime / (24 * 3600);
            hours = (remainingTime % (24 * 3600)) / 3600;
            minutes = (remainingTime % 3600) / 60;
            seconds = remainingTime % 60;
        }
        // When not recording, all time components remain 0 (showing 00:00)
        // -------------- Fix Ended (updateStorageUiWithCachedInfo - remaining time
        // calculation)-----------

        long elapsedMinutes = elapsedTime / 60000;
        long elapsedSeconds = (elapsedTime / 1000) % 60;

        // Prepare UI data
        android.util.Size selectedRes =
            sharedPreferencesManager.getCameraResolution();
        int selectedFps = sharedPreferencesManager.getVideoFrameRate();
        long selectedBitrate;
        try {
            boolean customMode =
                sharedPreferencesManager.sharedPreferences.getBoolean(
                    "bitrate_mode_custom",
                    false
                );
            if (customMode) {
                int customKbps =
                    sharedPreferencesManager.sharedPreferences.getInt(
                        "bitrate_custom_value",
                        16000
                    );
                selectedBitrate = (long) customKbps * 1000L;
            } else {
                selectedBitrate = Utils.estimateBitrate(
                    selectedRes,
                    selectedFps
                );
            }
        } catch (Exception e) {
            selectedBitrate = Utils.estimateBitrate(selectedRes, selectedFps);
        }
        String selectedEstimate = getRecordingTimeEstimate(
            adjustedAvailable,
            selectedBitrate
        );

        String cameraLabel = "";
        try {
            com.fadcam.CameraType camType =
                sharedPreferencesManager.getCameraSelection();
            cameraLabel = camType == com.fadcam.CameraType.FRONT
                ? getString(R.string.mainpage_camera_front)
                : getString(R.string.mainpage_camera_back);
        } catch (Exception ignored) {
            cameraLabel = "";
        }

        final String finalCameraLabel = cameraLabel;
        final String qualityText = getResolutionDisplayName(selectedRes);
        final String fpsText = String.format(
            Locale.getDefault(),
            "%dfps",
            selectedFps
        );

        // Get orientation setting
        String orientationText = "";
        try {
            String orientation = sharedPreferencesManager.getVideoOrientation();
            if (
                SharedPreferencesManager.ORIENTATION_LANDSCAPE.equals(
                    orientation
                )
            ) {
                orientationText = "Landscape";
            } else {
                orientationText = "Portrait";
            }
        } catch (Exception ignored) {
            orientationText = "Portrait"; // Default fallback
        }

        final String cameraSubtitle =
            qualityText + " • " + fpsText + " • " + orientationText;

        Locale numberFormatLocale = (Locale.getDefault() != null &&
                "fr".equalsIgnoreCase(Locale.getDefault().getLanguage()))
            ? Locale.US
            : Locale.getDefault();
        final String availableSpace = String.format(
            numberFormatLocale,
            "%.2f GB",
            gbAvailable
        );
        final String finalSelectedEstimate = selectedEstimate;
        final String elapsedTimeText = String.format(
            Locale.getDefault(),
            "%02d:%02d",
            elapsedMinutes,
            elapsedSeconds
        );
        final String remainingTimeText = formatRemainingTime(
            days,
            hours,
            minutes,
            seconds
        );

        // Update UI on main thread
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                    // Camera row
                    if (tvCameraTitle != null) tvCameraTitle.setText(
                        finalCameraLabel
                    );
                    if (tvCameraSubtitle != null) tvCameraSubtitle.setText(
                        cameraSubtitle
                    );

                    // Update camera icon
                    try {
                        com.fadcam.CameraType camType =
                            sharedPreferencesManager.getCameraSelection();
                        if (ivCameraIcon != null) {
                            ivCameraIcon.setText(
                                camType == com.fadcam.CameraType.FRONT
                                    ? "camera_front"
                                    : "camera_alt"
                            );
                        }
                    } catch (Exception ignored) {}

                    // Estimate row
                    if (tvEstimateTitle != null) tvEstimateTitle.setText(
                        finalSelectedEstimate
                    );
                    if (tvEstimateSubtitle != null) tvEstimateSubtitle.setText(
                        "Estimated time"
                    );

                    // Space row with styled text
                    if (tvSpaceTitle != null) {
                        try {
                            String avail = availableSpace;
                            String totalStr = String.format(
                                numberFormatLocale,
                                "%.2f GB",
                                gbTotal
                            );
                            String combined = avail + " / " + totalStr;
                            android.text.SpannableString ss =
                                new android.text.SpannableString(combined);

                            int slashIndex = combined.indexOf("/");
                            int start = (slashIndex >= 0)
                                ? slashIndex
                                : (combined.indexOf("/ ") + 2);
                            int end = combined.length();
                            if (start >= 0 && end > start) {
                                ss.setSpan(
                                    new android.text.style.ForegroundColorSpan(
                                        android.graphics.Color.parseColor(
                                            "#9E9E9E"
                                        )
                                    ),
                                    start,
                                    end,
                                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                );
                                ss.setSpan(
                                    new android.text.style.RelativeSizeSpan(
                                        0.85f
                                    ),
                                    start,
                                    end,
                                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                );
                            }
                            tvSpaceTitle.setText(ss);
                        } catch (Exception e) {
                            tvSpaceTitle.setText(availableSpace);
                        }
                    }
                    if (tvSpaceSubtitle != null) tvSpaceSubtitle.setText(
                        "Available space"
                    );

                    // Elapsed row
                    if (tvElapsedTitle != null) tvElapsedTitle.setText(
                        elapsedTimeText
                    );
                    if (tvElapsedSubtitle != null) tvElapsedSubtitle.setText(
                        "Elapsed time"
                    );

                    // Remaining row
                    if (tvRemainingTitle != null) tvRemainingTitle.setText(
                        remainingTimeText
                    );
                    if (
                        tvRemainingSubtitle != null
                    ) tvRemainingSubtitle.setText("Remaining time");
                });
        }
    }

    // -------------- Fix Start (formatRemainingTime)-----------
    private String formatRemainingTime(
        long days,
        long hours,
        long minutes,
        long seconds
    ) {
        // When not recording, show 00:00 format like elapsed time for consistency
        if (days == 0 && hours == 0 && minutes == 0 && seconds == 0) {
            return "00:00";
        }

        StringBuilder remainingTime = new StringBuilder();
        if (days > 0) {
            remainingTime.append(
                String.format(Locale.getDefault(), "%d days ", days)
            );
        }
        if (hours > 0) {
            remainingTime.append(
                String.format(Locale.getDefault(), "%dh ", hours)
            );
        }
        if (minutes > 0) {
            remainingTime.append(
                String.format(Locale.getDefault(), "%dm ", minutes)
            );
        }
        if (seconds > 0 || remainingTime.length() == 0) {
            remainingTime.append(
                String.format(Locale.getDefault(), "%ds", seconds)
            );
        }
        return remainingTime.toString().trim();
    }

    // -------------- Fix Ended (formatRemainingTime)-----------

    /**
     * Converts a resolution Size to a friendly display name (e.g., "FHD", "4K",
     * "HD")
     */
    private String getResolutionDisplayName(Size resolution) {
        if (resolution == null) {
            return "Unknown";
        }

        String resKey = resolution.getWidth() + "x" + resolution.getHeight();
        String[] resolutionKeys = getResources().getStringArray(
            R.array.video_resolutions_keys
        );
        String[] resolutionValues = getResources().getStringArray(
            R.array.video_resolutions_values
        );

        // Look for exact match in our resolution mapping
        for (
            int i = 0;
            i < resolutionKeys.length && i < resolutionValues.length;
            i++
        ) {
            if (resolutionKeys[i].equals(resKey)) {
                return resolutionValues[i];
            }
        }

        // If no exact match found, return a friendly approximation
        int width = resolution.getWidth();
        int height = resolution.getHeight();

        if (width >= 7680 && height >= 4320) return "8K";
        else if (width >= 3840 && height >= 2160) return "4K";
        else if (width >= 2560 && height >= 1440) return "2K";
        else if (width >= 1920 && height >= 1080) return "FHD";
        else if (width >= 1280 && height >= 720) return "HD";
        else return "SD";
    }

    private String getRecordingTimeEstimate(long availableBytes, long bitrate) {
        // Prevent division by zero
        if (bitrate <= 0) {
            return "∞ h ∞ min"; // Infinite time if bitrate is zero
        }

        // Calculate seconds, handling potential overflow
        long recordingSeconds;
        try {
            recordingSeconds = (availableBytes * 8) / bitrate;
        } catch (Exception e) {
            Log.e(TAG, "Error calculating recording time estimate", e);
            recordingSeconds = 0;
        }

        // Ensure non-negative values
        recordingSeconds = Math.max(0, recordingSeconds);

        long recordingHours = recordingSeconds / 3600;
        long recordingMinutes = (recordingSeconds % 3600) / 60;

        return String.format(
            Locale.getDefault(),
            "%d h %d min",
            recordingHours,
            recordingMinutes
        );
    }

    /**
     * Re-read relevant shared preferences and refresh the storage widget and
     * related UI.
     * Called from the SharedPreferences listener to ensure changes
     * (camera/resolution/fps)
     * are reflected immediately on the Home screen.
     */
    private void refreshPrefsAndUpdateStorage() {
        Log.d(
            TAG,
            "refreshPrefsAndUpdateStorage: preference change detected, refreshing UI"
        );

        // Ensure fragment is attached before attempting UI updates
        if (!isAdded() || getActivity() == null) {
            Log.w(
                TAG,
                "refreshPrefsAndUpdateStorage: fragment not added or activity null, skipping refresh"
            );
            return;
        }

        // Ensure SharedPreferencesManager is available
        try {
            if (sharedPreferencesManager == null) {
                sharedPreferencesManager = SharedPreferencesManager.getInstance(
                    requireContext()
                );
            }
        } catch (Exception e) {
            Log.e(
                TAG,
                "refreshPrefsAndUpdateStorage: failed to obtain SharedPreferencesManager",
                e
            );
        }

        // Update UI on the main thread
        getActivity().runOnUiThread(() -> {
                try {
                    // Re-evaluate storage widget values and update UI
                    updateStorageInfo();

                    // Update camera indicator & preview visibility which may depend on camera
                    // selection
                    try {
                        showCurrentCameraSelection();
                    } catch (Exception ignored) {}
                    try {
                        updatePreviewVisibility();
                    } catch (Exception ignored) {}

                    // Also trigger stats recalculation in background (file counts / sizes)
                    try {
                        updateStats();
                    } catch (Exception ignored) {}
                } catch (Exception e) {
                    Log.e(
                        TAG,
                        "refreshPrefsAndUpdateStorage: error updating UI",
                        e
                    );
                }
            });
    }

    // update storage and stats in real time while recording is started
    private void startUpdatingInfo() {
        // Cancel any existing runnable first
        if (updateInfoRunnable != null) {
            handlerClock.removeCallbacks(updateInfoRunnable);
            updateInfoRunnable = null;
        }

        // Create a new runnable
        updateInfoRunnable = new Runnable() {
            private int updateCounter = 0; // Counter for less frequent stats updates

            @Override
            public void run() {
                if ((isRecording() || isPaused()) && isAdded()) {
                    // Check if we have a valid recording start time
                    if (recordingStartTime <= 0) {
                        // Try to get the current system time as fallback
                        recordingStartTime = SystemClock.elapsedRealtime();
                        Log.w(
                            TAG,
                            "startUpdatingInfo: Invalid recordingStartTime detected, resetting to current time: " +
                            recordingStartTime
                        );
                    }

                    // Always update storage info (lightweight)
                    updateStorageInfo();

                    // Update stats every 5 seconds during recording to avoid performance impact
                    updateCounter++;
                    if (updateCounter >= 5) {
                        Log.d(
                            TAG,
                            "Update timer: Refreshing stats during recording (every 5s)"
                        );
                        updateStats();
                        updateCounter = 0;
                    }

                    handlerClock.postDelayed(this, 1000); // Update every second
                } else {
                    Log.d(
                        TAG,
                        "Update timer: Not recording or fragment detached, stopping updates"
                    );
                    stopUpdatingInfo(); // Clean up if recording state changed
                }
            }
        };

        // Post immediately to start updates
        handlerClock.post(updateInfoRunnable);
        Log.d(
            TAG,
            "startUpdatingInfo: Started real-time storage/stats updates"
        );
    }

    private void stopUpdatingInfo() {
        Log.d(TAG, "stopUpdatingInfo: Stopping real-time updates");
        if (updateInfoRunnable != null) {
            handlerClock.removeCallbacks(updateInfoRunnable);
            updateInfoRunnable = null;
        }
    }

    // --- Public method to refresh stats (can be called from other fragments) ---

    /**
     * Public method to refresh the stats widget
     * Can be called from other fragments when they need to update the home stats
     */
    public void refreshStats() {
        if (isAdded()) {
            // Clear stats cache to ensure fresh data
            VideoStatsCache.invalidateStats(sharedPreferencesManager);
            Log.i(
                TAG,
                "refreshStats: Clearing stats cache and updating stats."
            );
            updateStats();
        }
    }

    // --- Updated updateStats Method ---

    private void updateStats() {
        Log.d(TAG, "updateStats: Starting calculation...");

        // -------------- Fix Start (updateStats) - Live stats during recording
        // -----------

        // Check if recording is in progress - if so, force fresh calculation for live
        // updates
        boolean isRecording = sharedPreferencesManager.isRecordingInProgress();

        // Step 1: Try to display cached stats instantly (only if not recording)
        if (!isRecording) {
            VideoStatsCache.VideoStats cachedStats =
                VideoStatsCache.getCachedStats(sharedPreferencesManager);
            if (cachedStats != null && cachedStats.isValid()) {
                Log.d(
                    TAG,
                    "Using cached stats for instant display: " +
                    cachedStats.videoCount +
                    " videos, " +
                    cachedStats.totalSizeMB +
                    "MB"
                );
                updateStatsUI(cachedStats.videoCount, cachedStats.totalSizeMB);
                return; // Show cached data instantly, no need to recalculate unless invalidated
            }
        } else {
            Log.d(
                TAG,
                "Recording in progress - forcing fresh stats calculation for live updates"
            );
        }

        Log.d(TAG, "No valid cached stats found - calculating fresh stats");

        // Step 2: Calculate fresh stats in background
        if (executorService == null || executorService.isShutdown()) {
            Log.w(TAG, "ExecutorService not available for updateStats");
            // Reinitialize if needed or handle gracefully
            executorService = Executors.newSingleThreadExecutor();
        }

        executorService.submit(() -> {
            // --- Get current storage settings ---
            String storageMode = sharedPreferencesManager.getStorageMode();
            String customUriString =
                sharedPreferencesManager.getCustomStorageUri();
            List<VideoItem> primaryItems;
            List<VideoItem> tempItems;

            // --- Try to use shared session cache first (from VideoSessionCache) ---
            Log.d(TAG, "updateStats BG: Checking for shared session cache...");
            if (com.fadcam.utils.VideoSessionCache.isSessionCacheValid()) {
                Log.d(
                    TAG,
                    "updateStats BG: Using shared session cache (" +
                    com.fadcam.utils.VideoSessionCache.getSessionCachedVideos().size() +
                    " items) - eliminating duplicate scanning"
                );
                primaryItems = new ArrayList<>(
                    com.fadcam.utils.VideoSessionCache.getSessionCachedVideos()
                );
                tempItems = getTempCacheRecordsList();

                // CRITICAL FIX: Since we're using shared cache, populate it for future
                // cross-fragment use
                com.fadcam.utils.VideoSessionCache.updateSessionCache(
                    primaryItems
                );
            } else {
                // --- Load File Lists (Same logic as RecordsFragment) ---
                Log.d(
                    TAG,
                    "updateStats BG: Loading file lists. Mode: " + storageMode
                );
                if (
                    SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(
                        storageMode
                    ) &&
                    customUriString != null
                ) {
                    Uri treeUri = null;
                    try {
                        treeUri = Uri.parse(customUriString);
                    } catch (Exception e) {
                        Log.e(
                            TAG,
                            "BG updateStats: Error parsing custom URI",
                            e
                        );
                    }
                    if (treeUri != null && hasSafPermission(treeUri)) {
                        primaryItems = getSafRecordsListProgressive(
                            treeUri,
                            null
                        ); // No UI callback for stats
                    } else {
                        Log.e(
                            TAG,
                            "BG updateStats: Permission error or invalid URI for custom location: " +
                            customUriString
                        );
                        primaryItems = new ArrayList<>();
                    }
                } else {
                    primaryItems = getInternalRecordsList();
                }
                tempItems = getTempCacheRecordsList();

                // Update shared session cache for other fragments
                com.fadcam.utils.VideoSessionCache.updateSessionCache(
                    primaryItems
                );
            }

            // Combine (ok to run on background thread)
            List<VideoItem> combinedItems = combineVideoLists(
                primaryItems,
                tempItems
            );

            // --- Calculate Stats (Count and Size) ---
            int numVideos = combinedItems.size();
            long totalSizeBytes = 0;
            for (VideoItem item : combinedItems) {
                if (item != null) {
                    // Basic null check
                    totalSizeBytes += item.size;
                }
            }

            // Convert to MB for caching
            long totalSizeMB = totalSizeBytes / (1024 * 1024);

            // Format size for display
            String totalSizeFormatted = (getContext() != null)
                ? Formatter.formatFileSize(getContext(), totalSizeBytes)
                : String.format(
                    Locale.US,
                    "%.2f GB",
                    totalSizeBytes / (1024.0 * 1024.0 * 1024.0)
                ); // Fallback
            // format

            Log.d(
                TAG,
                "updateStats BG: Calculation complete. Count=" +
                numVideos +
                ", Size=" +
                totalSizeFormatted
            );

            // --- Cache the fresh stats and video list for cross-fragment synchronization
            // ---
            VideoStatsCache.updateStats(
                sharedPreferencesManager,
                numVideos,
                totalSizeMB
            );

            // CRITICAL FIX: Update shared session cache to prevent RecordsFragment from
            // rescanning
            if (!com.fadcam.utils.VideoSessionCache.isSessionCacheValid()) {
                com.fadcam.utils.VideoSessionCache.updateSessionCache(
                    primaryItems
                );
                com.fadcam.utils.VideoSessionCache.setCachedVideoCount(
                    numVideos
                );
                Log.d(
                    TAG,
                    "Updated session cache from HomeFragment to prevent duplicate scanning in RecordsFragment"
                );
            }

            Log.d(
                TAG,
                "Updated stats cache with fresh data - cross-fragment synchronization complete"
            );

            // --- Update UI on Main Thread ---
            updateStatsUI(numVideos, totalSizeMB);
        });

        // -------------- Fix End (updateStats) -----------
    }

    /**
     * Updates the stats UI with video count and size information
     *
     * @param numVideos   Total number of videos
     * @param totalSizeMB Total size in MB
     */
    private void updateStatsUI(int numVideos, long totalSizeMB) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                    if (tvStats == null) {
                        Log.w(TAG, "updateStatsUI: tvStats is null.");
                        return;
                    }

                    long totalSizeBytes = totalSizeMB * 1024 * 1024;
                    String totalSizeFormatted = (getContext() != null)
                        ? Formatter.formatFileSize(getContext(), totalSizeBytes)
                        : String.format(
                            Locale.US,
                            "%.2f GB",
                            totalSizeBytes / (1024.0 * 1024.0 * 1024.0)
                        );

                    // Get current theme
                    String currentTheme =
                        sharedPreferencesManager.sharedPreferences.getString(
                            com.fadcam.Constants.PREF_APP_THEME,
                            Constants.DEFAULT_APP_THEME
                        );
                    boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);

                    String statsText;
                    if (isSnowVeilTheme) {
                        // Create a custom black text version for Snow Veil theme
                        statsText =
                            "\n    " +
                            "<font color='#000000' style='font-size:12sp;'><b>Videos: </b></font>" +
                            "<font color='#333333' style='font-size:11sp;'>" +
                            numVideos +
                            "</font><br>" +
                            "<font color='#000000' style='font-size:12sp;'><b>Used: </font>" +
                            "<font color='#333333' style='font-size:11sp;'>" +
                            totalSizeFormatted +
                            "</font>" +
                            "\n";
                    } else {
                        // Use the standard resource for other themes
                        statsText = String.format(
                            Locale.getDefault(),
                            getString(R.string.mainpage_video_info), // Using your existing string resource
                            numVideos,
                            totalSizeFormatted
                        );
                    }

                    Spanned formattedText = Html.fromHtml(
                        statsText,
                        Html.FROM_HTML_MODE_LEGACY
                    );
                    tvStats.setText(formattedText);

                    Log.d(
                        TAG,
                        "updateStatsUI: Updated tvStats - " +
                        numVideos +
                        " videos, " +
                        totalSizeFormatted
                    );
                });
        }
    }

    // --- COPIED Helper Methods (from RecordsFragment or move to shared Utils
    // class) ---
    // You NEED these methods here or accessible via Utils

    private boolean hasSafPermission(Uri treeUri) {
        Context context = getContext();
        if (context == null || treeUri == null) return false;
        try {
            List<UriPermission> persistedUris = context
                .getContentResolver()
                .getPersistedUriPermissions();
            boolean permissionFound = false;
            for (UriPermission uriPermission : persistedUris) {
                if (
                    uriPermission.getUri().equals(treeUri) &&
                    uriPermission.isReadPermission() &&
                    uriPermission.isWritePermission()
                ) {
                    permissionFound = true;
                    break;
                }
            }
            if (!permissionFound) return false;
            DocumentFile docDir = DocumentFile.fromTreeUri(context, treeUri);
            return docDir != null && docDir.canRead();
        } catch (Exception e) {
            Log.e(TAG, "Error checking SAF permission", e);
            return false;
        }
    }

    private List<VideoItem> getInternalRecordsList() {
        List<VideoItem> items = new ArrayList<>();
        File recordsDir = getContext() != null
            ? getContext().getExternalFilesDir(null)
            : null;
        if (recordsDir == null) {
            Log.e(
                TAG,
                "Context or ExternalFilesDir null in getInternalRecordsList"
            );
            return items;
        }
        File fadCamDir = new File(recordsDir, Constants.RECORDING_DIRECTORY);
        if (fadCamDir.exists() && fadCamDir.isDirectory()) {
            File[] files = fadCamDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (
                        file.isFile() &&
                        file
                            .getName()
                            .endsWith(
                                "." + Constants.RECORDING_FILE_EXTENSION
                            ) &&
                        !file.getName().startsWith("temp_")
                    ) {
                        items.add(
                            new VideoItem(
                                Uri.fromFile(file),
                                file.getName(),
                                file.length(),
                                file.lastModified()
                            )
                        );
                    }
                }
            }
        }
        return items;
    }

    // -------------- Fix Start (getSafRecordsListProgressive)-----------

    /**
     * Progressive SAF directory scanning with chunked processing to avoid blocking
     * main thread.
     * Processes files in small batches with delays between chunks for better
     * performance.
     *
     * @param treeUri  The SAF directory URI to scan
     * @param callback Optional callback for UI updates (can be null for background
     *                 operations)
     * @return List of VideoItem objects found in the directory
     */
    private List<VideoItem> getSafRecordsListProgressive(
        Uri treeUri,
        ProgressCallback callback
    ) {
        List<VideoItem> items = new ArrayList<>();
        Context context = getContext();
        if (context == null || treeUri == null) {
            Log.e(
                TAG,
                "Context or treeUri null in getSafRecordsListProgressive"
            );
            return items;
        }

        DocumentFile dir = DocumentFile.fromTreeUri(context, treeUri);
        if (dir == null || !dir.isDirectory() || !dir.canRead()) {
            Log.e(
                TAG,
                "Cannot read/access SAF dir in getSafRecordsListProgressive: " +
                treeUri
            );
            return items;
        }

        try {
            DocumentFile[] allFiles = dir.listFiles();
            if (allFiles == null) {
                Log.w(TAG, "No files found in SAF directory: " + treeUri);
                return items;
            }

            Log.d(
                TAG,
                "Progressive SAF scan starting: " +
                allFiles.length +
                " files to process"
            );

            // Process files in chunks to avoid blocking
            final int CHUNK_SIZE = 10;
            final int CHUNK_DELAY_MS = 10;

            for (int i = 0; i < allFiles.length; i += CHUNK_SIZE) {
                int endIndex = Math.min(i + CHUNK_SIZE, allFiles.length);

                // Process chunk
                for (int j = i; j < endIndex; j++) {
                    DocumentFile file = allFiles[j];
                    if (
                        file != null &&
                        file.isFile() &&
                        file.getName() != null &&
                        (file
                                .getName()
                                .endsWith(
                                    "." + Constants.RECORDING_FILE_EXTENSION
                                ) ||
                            "video/mp4".equals(file.getType())) &&
                        !file.getName().startsWith("temp_")
                    ) {
                        items.add(
                            new VideoItem(
                                file.getUri(),
                                file.getName(),
                                file.length(),
                                file.lastModified()
                            )
                        );
                    }
                }

                // Notify progress if callback provided
                if (callback != null) {
                    final int currentProgress = endIndex;
                    final int totalFiles = allFiles.length;
                    callback.onProgress(currentProgress, totalFiles);
                }

                // Small delay between chunks to prevent blocking
                if (endIndex < allFiles.length) {
                    try {
                        Thread.sleep(CHUNK_DELAY_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        Log.w(TAG, "Progressive SAF scan interrupted");
                        break;
                    }
                }
            }

            Log.d(
                TAG,
                "Progressive SAF scan completed: " +
                items.size() +
                " video items found"
            );
        } catch (Exception e) {
            Log.e(TAG, "Error in progressive SAF scan for " + treeUri, e);
        }
        return items;
    }

    // Keep old method for backward compatibility but mark as deprecated
    @Deprecated
    private List<VideoItem> getSafRecordsList(Uri treeUri) {
        // Redirect to progressive version without callback
        return getSafRecordsListProgressive(treeUri, null);
    }

    // -------------- Fix Ended (getSafRecordsListProgressive)-----------

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

    private void scanDirectoryForTempVideos(
        File directory,
        List<VideoItem> items
    ) {
        if (directory.exists() && directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (
                        file.isFile() &&
                        file.getName().startsWith("temp_") &&
                        file
                            .getName()
                            .endsWith("." + Constants.RECORDING_FILE_EXTENSION)
                    ) {
                        if (file.length() > 0) {
                            items.add(
                                new VideoItem(
                                    Uri.fromFile(file),
                                    file.getName(),
                                    file.length(),
                                    file.lastModified()
                                )
                            );
                        }
                    }
                }
            }
        }
    }

    private List<VideoItem> combineVideoLists(
        List<VideoItem> primary,
        List<VideoItem> temp
    ) {
        List<VideoItem> combined = new ArrayList<>();
        Set<Uri> existingUris = new HashSet<>();
        for (VideoItem item : primary) {
            if (
                item != null && item.uri != null && existingUris.add(item.uri)
            ) {
                combined.add(item);
            }
        }
        for (VideoItem item : temp) {
            if (
                item != null && item.uri != null && existingUris.add(item.uri)
            ) {
                combined.add(item);
            }
        }
        return combined;
    }

    private void pauseRecording() {
        Log.d(TAG, "pauseRecording: Pausing video recording");

        buttonPauseResume.setIcon(
            AppCompatResources.getDrawable(requireContext(), R.drawable.ic_play)
        );
        buttonPauseResume.setEnabled(false);

        buttonCamSwitch.setEnabled(false);

        Intent stopIntent = new Intent(getActivity(), RecordingService.class);
        stopIntent.setAction(Constants.INTENT_ACTION_PAUSE_RECORDING);
        requireActivity().startService(stopIntent);
    }

    private void resumeRecording() {
        Log.d(TAG, "resumeRecording: Resuming video recording");

        buttonPauseResume.setIcon(
            AppCompatResources.getDrawable(
                requireContext(),
                R.drawable.ic_pause
            )
        );
        buttonPauseResume.setEnabled(false);

        buttonCamSwitch.setEnabled(false);

        SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();

        Intent recordingServiceIntent = new Intent(
            getActivity(),
            RecordingService.class
        );
        recordingServiceIntent.setAction(
            Constants.INTENT_ACTION_RESUME_RECORDING
        );
        if (surfaceTexture != null) {
            recordingServiceIntent.putExtra(
                "SURFACE",
                new Surface(surfaceTexture)
            );
        }
        requireActivity().startService(recordingServiceIntent);
    }

    private void setupRecordingTiles(View root) {
        try {
            tileAfToggle = root.findViewById(R.id.tile_af_toggle);
            tileExp = root.findViewById(R.id.tile_exp);
            tileZoom = root.findViewById(R.id.tile_zoom);

            // Initialize AF tile icon from saved afMode and apply Material Icons typeface
            try {
                if (tileAfToggle != null) {
                    // Load Material Icons typeface for ligatures
                    android.graphics.Typeface materialIconsTypeface = null;
                    try {
                        materialIconsTypeface =
                            androidx.core.content.res.ResourcesCompat.getFont(
                                requireContext(),
                                R.font.materialicons
                            );
                    } catch (Exception e) {
                        materialIconsTypeface =
                            android.graphics.Typeface.DEFAULT;
                    }
                    tileAfToggle.setTypeface(materialIconsTypeface);
                    tileAfToggle.setTextSize(
                        android.util.TypedValue.COMPLEX_UNIT_SP,
                        24
                    ); // Match zoom icon size
                    tileAfToggle.setText(
                        afMode ==
                            android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                            ? "center_focus_strong"
                            : "center_focus_weak"
                    );

                    // -------------- Fix Start (initial AF toggle tinting - Snow Veil
                    // theme)-----------
                    // Set appropriate color based on theme
                    String currentTheme =
                        sharedPreferencesManager.sharedPreferences.getString(
                            Constants.PREF_APP_THEME,
                            Constants.DEFAULT_APP_THEME
                        );
                    boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);

                    if (isSnowVeilTheme) {
                        tileAfToggle.setTextColor(Color.parseColor("#424242")); // Dark gray for Snow Veil
                    } else {
                        // Use theme's colorOnSurface for other themes
                        android.util.TypedValue typedValue =
                            new android.util.TypedValue();
                        requireContext()
                            .getTheme()
                            .resolveAttribute(
                                com.google.android.material.R.attr.colorOnSurface,
                                typedValue,
                                true
                            );
                        tileAfToggle.setTextColor(typedValue.data);
                    }
                    // -------------- Fix Ended (initial AF toggle tinting - Snow Veil
                    // theme)-----------
                }
            } catch (Exception ignored) {}
            // Apply initial exposure tile adjustments - now it's a TextView with compound
            // drawable
            try {
                if (tileExp != null) {
                    // Set drawable size to match other icons
                    android.graphics.drawable.Drawable[] drawables =
                        tileExp.getCompoundDrawables();
                    if (drawables[1] != null) {
                        // drawableTop
                        drawables[1].setBounds(0, 0, 48, 48); // 24dp * 2 for density
                        tileExp.setCompoundDrawables(
                            null,
                            drawables[1],
                            null,
                            null
                        );
                    }

                    // CRITICAL: Apply initial exposure tint based on saved preferences
                    SharedPreferencesManager sp =
                        SharedPreferencesManager.getInstance(requireContext());
                    int savedEvIndex = sp.getSavedExposureCompensation();
                    boolean savedAeLock = sp.isAeLockedSaved();

                    // -------------- Fix Start (initial exposure tile tinting - Snow Veil
                    // theme)-----------
                    // Apply orange tint if AE locked or exposure compensation is not at 0
                    if (savedAeLock || savedEvIndex != 0) {
                        int orange = getResources().getColor(
                            R.color.orange_accent,
                            requireContext().getTheme()
                        );
                        tileExp.setTextColor(orange);
                        // subtle scale to indicate active
                        tileExp.setScaleX(savedAeLock ? 1.05f : 1f);
                        tileExp.setScaleY(savedAeLock ? 1.05f : 1f);
                        com.fadcam.Log.d(
                            TAG,
                            "Applied initial orange tint to exposure tile (EV=" +
                            savedEvIndex +
                            ", AeLock=" +
                            savedAeLock +
                            ")"
                        );
                    } else {
                        // Reset to appropriate default color based on theme
                        String currentTheme =
                            sharedPreferencesManager.sharedPreferences.getString(
                                Constants.PREF_APP_THEME,
                                Constants.DEFAULT_APP_THEME
                            );
                        boolean isSnowVeilTheme = "Snow Veil".equals(
                            currentTheme
                        );

                        if (isSnowVeilTheme) {
                            tileExp.setTextColor(Color.parseColor("#424242")); // Dark gray for Snow Veil
                        } else {
                            // Use theme's colorOnSurface for other themes
                            android.util.TypedValue typedValue =
                                new android.util.TypedValue();
                            requireContext()
                                .getTheme()
                                .resolveAttribute(
                                    com.google.android.material.R.attr.colorOnSurface,
                                    typedValue,
                                    true
                                );
                            tileExp.setTextColor(typedValue.data);
                        }
                        tileExp.setScaleX(1f);
                        tileExp.setScaleY(1f);
                        com.fadcam.Log.d(
                            TAG,
                            "Applied initial default tint to exposure tile"
                        );
                    }
                    // -------------- Fix Ended (initial exposure tile tinting - Snow Veil
                    // theme)-----------
                }
            } catch (Exception ignored) {}

            // Initialize zoom tile with proper tinting
            try {
                if (tileZoom != null) {
                    android.graphics.Typeface materialIconsTypeface = null;
                    try {
                        materialIconsTypeface =
                            androidx.core.content.res.ResourcesCompat.getFont(
                                requireContext(),
                                R.font.materialicons
                            );
                    } catch (Exception e) {
                        materialIconsTypeface =
                            android.graphics.Typeface.DEFAULT;
                    }
                    tileZoom.setTypeface(materialIconsTypeface);
                    tileZoom.setText(getString(R.string.icon_zoom_in_ligature));
                    tileZoom.setTextSize(
                        android.util.TypedValue.COMPLEX_UNIT_SP,
                        24
                    );

                    // -------------- Fix Start (initial zoom tile tinting - Snow Veil
                    // theme)-----------
                    // Apply orange tint if zoom is not at default (1.0x)
                    SharedPreferencesManager sp =
                        SharedPreferencesManager.getInstance(requireContext());
                    CameraType currentCamera = sp.getCameraSelection();
                    float currentZoom = sp.getSpecificZoomRatio(currentCamera);
                    String currentTheme =
                        sharedPreferencesManager.sharedPreferences.getString(
                            Constants.PREF_APP_THEME,
                            Constants.DEFAULT_APP_THEME
                        );
                    boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);

                    if (Math.abs(currentZoom - 1.0f) > 0.01f) {
                        // Not at 1.0x default
                        tileZoom.setTextColor(
                            ContextCompat.getColor(
                                requireContext(),
                                R.color.orange_accent
                            )
                        );
                    } else {
                        // Use appropriate default color based on theme
                        if (isSnowVeilTheme) {
                            tileZoom.setTextColor(Color.parseColor("#424242")); // Dark gray for Snow Veil
                        } else {
                            tileZoom.setTextColor(
                                ContextCompat.getColor(
                                    requireContext(),
                                    android.R.color.white
                                )
                            );
                        }
                    }
                    // -------------- Fix Ended (initial zoom tile tinting - Snow Veil
                    // theme)-----------
                }
            } catch (Exception ignored) {}

            tileAfToggle.setOnClickListener(v -> {
                // show overlay to pick AF mode
                com.fadcam.Log.d(
                    TAG,
                    "AF tile clicked. Opening AF mode picker"
                );
                ArrayList<com.fadcam.ui.picker.OptionItem> afItems =
                    new ArrayList<>();
                // Keep only Continuous (enabled) and Manual (locked) to match common camera app
                // UX.
                afItems.add(
                    new com.fadcam.ui.picker.OptionItem(
                        String.valueOf(
                            android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                        ),
                        getString(R.string.af_continuous_title),
                        getString(R.string.af_continuous_description),
                        null, // colorInt
                        null, // iconResId (we use ligature instead)
                        null, // trailingIconResId
                        null, // hasSwitch
                        null, // switchState
                        "center_focus_strong" // Material icon ligature for focus
                    )
                );

                afItems.add(
                    new com.fadcam.ui.picker.OptionItem(
                        String.valueOf(
                            android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_OFF
                        ),
                        getString(R.string.af_manual_title),
                        getString(R.string.af_manual_description),
                        null,
                        null,
                        null,
                        null,
                        null,
                        "lock" // Material icon ligature for locked focus
                    )
                );
                com.fadcam.ui.picker.PickerBottomSheetFragment afSheet =
                    com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
                        getString(R.string.af_mode_title),
                        afItems,
                        String.valueOf(afMode),
                        Constants.RK_AF_MODE,
                        getString(R.string.af_picker_helper)
                    );
                afSheet.show(getParentFragmentManager(), "af_mode_sheet");
            });

            // AE lock control moved into Exposure slider sheet (handled below)

            tileExp.setOnClickListener(v -> {
                com.fadcam.Log.d(
                    TAG,
                    "Exposure tile clicked. Opening slider exposure picker"
                );
                android.util.Log.d(
                    "HomeFragment",
                    "EXPOSURE TILE CLICKED - creating picker"
                );
                int min = -4,
                    max = 4,
                    step = 1;
                float stepFloat = 1f;
                try {
                    if (cameraManager != null && cameraId != null) {
                        CameraCharacteristics chars =
                            cameraManager.getCameraCharacteristics(cameraId);
                        android.util.Range<Integer> range = chars.get(
                            CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE
                        );
                        if (range != null) {
                            min = range.getLower();
                            max = range.getUpper();
                        }
                        // CONTROL_AE_COMPENSATION_STEP is provided as a Rational in
                        // CameraCharacteristics
                        android.util.Rational stepRat = chars.get(
                            CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP
                        );
                        if (stepRat != null) {
                            stepFloat = stepRat.floatValue();
                        }
                    }
                } catch (Exception ignored) {}
                com.fadcam.ui.picker.PickerBottomSheetFragment evSlider =
                    com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceSliderWithSwitch(
                        getString(R.string.exposure_title),
                        min,
                        max,
                        step,
                        stepFloat,
                        currentEvIndex,
                        Constants.RK_EXPOSURE_COMPENSATION,
                        getString(R.string.ae_lock_helper),
                        getString(R.string.ae_lock_switch_label),
                        aeLocked
                    );
                android.util.Log.d(
                    "HomeFragment",
                    "EXPOSURE PICKER CREATED - showing dialog"
                );
                evSlider.show(getParentFragmentManager(), "ev_slider_sheet");
            });

            tileZoom.setOnClickListener(v -> {
                com.fadcam.Log.d(
                    TAG,
                    "Zoom tile clicked. Opening zoom slider picker"
                );

                SharedPreferencesManager sp =
                    SharedPreferencesManager.getInstance(requireContext());
                CameraType currentCamera = sp.getCameraSelection();

                // Build zoom options using the same logic as VideoSettingsFragment
                List<Float> zoomRatios = buildZoomRatioOptions(currentCamera);
                if (zoomRatios.isEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.zoom_not_available_toast),
                        Toast.LENGTH_SHORT
                    ).show();
                    return;
                }

                float currentZoom = sp.getSpecificZoomRatio(currentCamera);

                com.fadcam.ui.picker.PickerBottomSheetFragment zoomSlider =
                    com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceSliderZoom(
                        getString(R.string.zoom_slider_title),
                        zoomRatios,
                        currentZoom,
                        Constants.RK_ZOOM_RATIO,
                        getString(R.string.zoom_slider_helper)
                    );
                zoomSlider.show(
                    getParentFragmentManager(),
                    "zoom_slider_sheet"
                );
            });
        } catch (Exception e) {
            com.fadcam.Log.w(
                TAG,
                "setupRecordingTiles: UI not available or error: " +
                e.getMessage()
            );
        }
    }

    /**
     * Build zoom ratio options for the given camera type.
     * Uses the same logic as VideoSettingsFragment to ensure consistency.
     */
    private List<Float> buildZoomRatioOptions(CameraType cam) {
        List<Float> list = new ArrayList<>();
        float max = getHardwareSupportedMaxZoomRatio(cam);

        // Add from 0.5x up to max in 0.5 increments; ensure 1.0 included
        for (float z = 0.5f; z <= max + 0.001f; z += 0.5f) {
            list.add(((float) Math.round(z * 10)) / 10f);
        }
        if (!list.contains(1.0f)) {
            list.add(1.0f);
        }
        Collections.sort(list);
        return list;
    }

    /**
     * Get hardware supported maximum zoom ratio for the given camera type.
     * Uses the same logic as VideoSettingsFragment.
     */
    private float getHardwareSupportedMaxZoomRatio(CameraType cam) {
        final float defaultMaxZoom = 5.0f; // legacy default
        Context ctx = getContext();
        if (ctx == null) return defaultMaxZoom;

        try {
            CameraManager manager = (CameraManager) ctx.getSystemService(
                Context.CAMERA_SERVICE
            );
            String id = getActualCameraIdForType(cam);
            if (id == null) return defaultMaxZoom;

            CameraCharacteristics ch = manager.getCameraCharacteristics(id);
            if (Build.VERSION.SDK_INT >= 30) {
                Range<Float> range = ch.get(
                    CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE
                );
                if (range != null) {
                    return range.getUpper();
                }
            }
        } catch (Exception e) {
            com.fadcam.Log.w(TAG, "Zoom ratio query failed: " + e.getMessage());
        }
        return defaultMaxZoom;
    }

    /**
     * Get the actual camera ID for the given camera type.
     * Helper method for zoom functionality.
     */
    private String getActualCameraIdForType(CameraType cameraType) {
        try {
            if (cameraManager != null) {
                if (cameraType == CameraType.FRONT) {
                    // Find front camera
                    for (String id : cameraManager.getCameraIdList()) {
                        CameraCharacteristics chars =
                            cameraManager.getCameraCharacteristics(id);
                        Integer facing = chars.get(
                            CameraCharacteristics.LENS_FACING
                        );
                        if (
                            facing != null &&
                            facing == CameraCharacteristics.LENS_FACING_FRONT
                        ) {
                            return id;
                        }
                    }
                } else {
                    // Find back camera - use selected back camera if available
                    SharedPreferencesManager sp =
                        SharedPreferencesManager.getInstance(requireContext());
                    String selectedBackId = sp.getSelectedBackCameraId();
                    if (selectedBackId != null) {
                        return selectedBackId;
                    }
                    // Fallback to first available back camera
                    for (String id : cameraManager.getCameraIdList()) {
                        CameraCharacteristics chars =
                            cameraManager.getCameraCharacteristics(id);
                        Integer facing = chars.get(
                            CameraCharacteristics.LENS_FACING
                        );
                        if (
                            facing != null &&
                            facing == CameraCharacteristics.LENS_FACING_BACK
                        ) {
                            return id;
                        }
                    }
                }
            }
        } catch (Exception e) {
            com.fadcam.Log.w(
                TAG,
                "getActualCameraIdForType failed: " + e.getMessage()
            );
        }
        return null;
    }

    // Zoom methods removed - using navigation to settings instead

    // Zoom methods removed - using navigation to settings instead

    // Helper method removed - using navigation to settings instead

    private void setVideoBitrate() {
        try {
            videoBitrate = sharedPreferencesManager.getCurrentBitrate();
        } catch (Exception e) {
            videoBitrate = Utils.estimateBitrate(
                sharedPreferencesManager.getCameraResolution(),
                sharedPreferencesManager.getVideoFrameRate()
            );
        }
        Log.d(TAG, "setVideoBitrate: Set to " + videoBitrate + " bps");
    }

    // --- Stop Recording ---
    private void stopRecording() {
        if (!isAdded() || getActivity() == null) {
            Log.w(TAG, "Stop: Not attached");
            return;
        }
        if (recordingState == RecordingState.NONE) {
            Log.w(TAG, "Stop clicked but state NONE?");
            return;
        } // Prevent multi-stop

        Log.i(TAG, ">> stopRecording user action");
        disableInteractionButtons();
        Log.d(TAG, "stopRecording: Btns disabled.");

        Intent stopIntent = new Intent(getActivity(), RecordingService.class);
        stopIntent.setAction(Constants.INTENT_ACTION_STOP_RECORDING);
        try {
            requireActivity().startService(stopIntent);
            Log.i(TAG, "Sent STOP intent.");
        } catch (Exception e) {
            Log.e(TAG, "Error sending STOP intent: ", e);
            Toast.makeText(
                getContext(),
                "Error stop svc",
                Toast.LENGTH_SHORT
            ).show();
            resetUIButtonsToIdleState();
            Log.d(TAG, "stopIntent fail: UI Reset.");
        }
        vibrateTouch();
    }

    private void copyFontToInternalStorage() {
        AssetManager assetManager = requireContext().getAssets();
        InputStream in = null;
        OutputStream out = null;
        try {
            in = assetManager.open("ubuntu_regular.ttf");
            File outFile = new File(
                requireContext().getFilesDir(),
                "ubuntu_regular.ttf"
            );
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
        if (
            sharedPreferencesManager
                .getCameraSelection()
                .equals(CameraType.BACK)
        ) {
            sharedPreferencesManager.sharedPreferences
                .edit()
                .putString(
                    Constants.PREF_CAMERA_SELECTION,
                    CameraType.FRONT.toString()
                )
                .apply();
            Log.d(TAG, "Camera set to front");
            Toast.makeText(
                getContext(),
                R.string.switched_front_camera,
                Toast.LENGTH_SHORT
            ).show();
        } else {
            sharedPreferencesManager.sharedPreferences
                .edit()
                .putString(
                    Constants.PREF_CAMERA_SELECTION,
                    CameraType.BACK.toString()
                )
                .apply();
            Log.d(TAG, "Camera set to rear");
            Toast.makeText(
                getContext(),
                R.string.switched_rear_camera,
                Toast.LENGTH_SHORT
            ).show();
        }
    }

    // private class LocationHelper implements LocationListener {
    //
    // private LocationManager locationManager;
    // private double latitude;
    // private double longitude;
    //
    // public LocationHelper(LocationManager locationManager) {
    // this.locationManager = locationManager;
    // }
    //
    // public void startListening() {
    // locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0,
    // this);
    // }
    //
    // @Override
    // public void onLocationChanged(Location location) {
    // latitude = location.getLatitude();
    // longitude = location.getLongitude();
    // }
    //
    // public String getLocationText() {
    // return String.format(Locale.getDefault(), "%.2f, %.2f", latitude, longitude);
    // }
    // }

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
                Log.w(
                    TAG,
                    "Torch receiver was not registered or already unregistered"
                );
            }
            torchReceiver = null;
        }

        // Clean up executor service
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            executorService = null;
        }

        stopUpdatingInfo();
        stopUpdatingClock();

        // -------------- Fix Start (cleanup) --------------
        // Clean up helper
        if (fragmentHelper != null) {
            fragmentHelper.onDestroy();
            fragmentHelper = null;
        }
        
        // Clean up debounced runnables
        if (debouncedStartRecording != null) {
            debouncedStartRecording.cancel();
            debouncedStartRecording = null;
        }
        if (debouncedStopRecording != null) {
            debouncedStopRecording.cancel();
            debouncedStopRecording = null;
        }
        // -------------- Fix Ended (cleanup) --------------
    }

    public boolean isRecording() {
        return recordingState.equals(RecordingState.IN_PROGRESS);
    }

    public boolean isPaused() {
        return recordingState.equals(RecordingState.PAUSED);
    }

    private void initializeTorch() {
        cameraManager = (CameraManager) requireContext().getSystemService(
            Context.CAMERA_SERVICE
        );
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
        // updateTorchButtonState(isTorchOn); // This might be called too early,
        // consider moving or ensuring isTorchOn is accurate

        buttonTorchSwitch.setOnClickListener(v -> {
            // ----- Fix Start for this method(setupTorchButton_onClick)-----
            if (!isAdded() || getContext() == null) {
                Log.w(
                    TAG,
                    "Torch button clicked, but fragment not fully ready."
                );
                return;
            }

            if (isRecordingOrPaused()) {
                Log.d(
                    TAG,
                    "Recording active. Sending toggle intent to RecordingService. Current isTorchOn (UI state): " +
                    isTorchOn
                );
                Intent serviceIntent = new Intent(
                    getContext(),
                    RecordingService.class
                );
                serviceIntent.setAction(
                    Constants.INTENT_ACTION_TOGGLE_RECORDING_TORCH
                );
                try {
                    ContextCompat.startForegroundService(
                        getContext(),
                        serviceIntent
                    );
                } catch (Exception e) {
                    Log.e(
                        TAG,
                        "Error starting RecordingService for torch toggle",
                        e
                    );
                }
            } else {
                Log.d(
                    TAG,
                    "Not recording. Toggling torch directly. Current isTorchOn: " +
                    isTorchOn
                );
                // Assuming toggleTorch() correctly handles CameraManager.setTorchMode,
                // updates isTorchOn field, and calls updateTorchUI().
                toggleTorch();
            }
            // ----- Fix Ended for this method(setupTorchButton_onClick)-----
        });

        // Initialize and register the broadcast receiver for torch state changes from
        // the service
        // This is important for when the service toggles the torch (e.g., during
        // recording)
        // and the UI needs to reflect that change.
        if (torchReceiver == null) {
            // Ensure it's initialized only once
            initializeTorchReceiver();
        }
        if (getContext() != null && !isTorchReceiverRegistered) {
            // Check context and registration status
            // ----- Fix Start for this
            // method(setupTorchButton_correct_torch_receiver_registration)-----
            IntentFilter filter = new IntentFilter(
                Constants.BROADCAST_ON_TORCH_STATE_CHANGED
            );
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().registerReceiver(
                    torchReceiver,
                    filter,
                    Context.RECEIVER_EXPORTED
                ); // Or
                // RECEIVER_NOT_EXPORTED
                // if strictly
                // internal
            } else {
                requireContext().registerReceiver(torchReceiver, filter);
            }
            // ContextCompat.registerReceiver(context, torchReceiver, filter,
            // ContextCompat.RECEIVER_NOT_EXPORTED);
            // ----- Fix Ended for this
            // method(setupTorchButton_correct_torch_receiver_registration)-----
            isTorchReceiverRegistered = true;
            Log.d(
                TAG,
                "Torch state change receiver registered in setupTorchButton."
            );
        }
        // Fetch initial torch state and update UI (if not already handled by
        // onResume/onStart)
        // This part might need careful placement depending on overall lifecycle
        // management
        // For now, assuming 'isTorchOn' is correctly initialized elsewhere (e.g.
        // initializeTorch())
        // and 'updateTorchUI' correctly updates the button.
        // updateTorchUI(isTorchOn); // This updates the button drawable
    }

    // Ensure toggleTorch method exists and correctly uses
    // CameraManager.setTorchMode
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
            Log.d(
                TAG,
                "Torch toggled directly via CameraManager. New state: " +
                isTorchOn +
                " for camera " +
                currentCameraId
            );
            updateTorchUI(isTorchOn); // Update button appearance and any other UI
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to toggle torch directly", e);
            isTorchOn = !isTorchOn; // Revert state on error
            updateTorchUI(isTorchOn); // Update UI back
            Utils.showQuickToast(getContext(), "Error toggling torch.");
        } catch (IllegalArgumentException e) {
            Log.e(
                TAG,
                "Failed to toggle torch: Camera device " +
                currentCameraId +
                " is no longer connected or available.",
                e
            );
            // This can happen if the camera is closed or in use by another app.
            // Reset torch state and UI.
            isTorchOn = false;
            updateTorchUI(false);
            Utils.showQuickToast(getContext(), "Torch unavailable.");
        }
    }

    private String getCameraIdForTorch() {
        // This method should return the ID of the camera that HomeFragment
        // is currently configured to use for its general operations (like preview if it
        // had one, or torch).
        // It might be based on SharedPreferencesManager.getCameraSelection() and
        // SharedPreferencesManager.getSelectedBackCameraId()
        // This is a simplified placeholder. You need to ensure this returns the
        // correct, active camera ID.
        if (this.cameraId != null) return this.cameraId; // If HomeFragment already has a determined primary camera ID

        // Fallback or more complex logic to determine the appropriate camera ID:
        try {
            if (cameraManager == null && getContext() != null) {
                cameraManager = (CameraManager) getContext().getSystemService(
                    Context.CAMERA_SERVICE
                );
            }
            if (cameraManager == null) return null;

            CameraType selectedType =
                sharedPreferencesManager.getCameraSelection();
            if (selectedType == CameraType.FRONT) {
                for (String id : cameraManager.getCameraIdList()) {
                    CameraCharacteristics characteristics =
                        cameraManager.getCameraCharacteristics(id);
                    Integer facing = characteristics.get(
                        CameraCharacteristics.LENS_FACING
                    );
                    Boolean flashAvailable = characteristics.get(
                        CameraCharacteristics.FLASH_INFO_AVAILABLE
                    );
                    if (
                        facing != null &&
                        facing == CameraCharacteristics.LENS_FACING_FRONT &&
                        flashAvailable != null &&
                        flashAvailable
                    ) {
                        return id;
                    }
                }
            } else {
                // BACK
                String preferredBackId =
                    sharedPreferencesManager.getSelectedBackCameraId();
                if (preferredBackId != null) {
                    CameraCharacteristics characteristics =
                        cameraManager.getCameraCharacteristics(preferredBackId);
                    Integer facing = characteristics.get(
                        CameraCharacteristics.LENS_FACING
                    );
                    Boolean flashAvailable = characteristics.get(
                        CameraCharacteristics.FLASH_INFO_AVAILABLE
                    );
                    if (
                        facing != null &&
                        facing == CameraCharacteristics.LENS_FACING_BACK &&
                        flashAvailable != null &&
                        flashAvailable
                    ) {
                        return preferredBackId;
                    }
                }
                // Fallback to default back camera if preferred is not suitable or not found
                for (String id : cameraManager.getCameraIdList()) {
                    CameraCharacteristics characteristics =
                        cameraManager.getCameraCharacteristics(id);
                    Integer facing = characteristics.get(
                        CameraCharacteristics.LENS_FACING
                    );
                    Boolean flashAvailable = characteristics.get(
                        CameraCharacteristics.FLASH_INFO_AVAILABLE
                    );
                    if (
                        facing != null &&
                        facing == CameraCharacteristics.LENS_FACING_BACK &&
                        flashAvailable != null &&
                        flashAvailable
                    ) {
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
            buttonTorchSwitch.setIcon(
                AppCompatResources.getDrawable(
                    requireContext(),
                    R.drawable.ic_flashlight_on
                )
            );
            buttonTorchSwitch.setEnabled(true);
        }
    }

    private void showTorchOptionsDialog() {
        // Check if recording is in progress first
        if (isRecordingInProgress()) {
            Toast.makeText(
                requireContext(),
                R.string.torch_recording_note,
                Toast.LENGTH_SHORT
            ).show();
            return;
        }

        CameraManager cameraManager =
            (CameraManager) requireContext().getSystemService(
                Context.CAMERA_SERVICE
            );
        try {
            List<String> torchSources = new ArrayList<>();
            boolean hasMultipleTorches = false;
            boolean hasBackTorch = false;
            boolean hasFrontTorch = false;

            // Check available torch sources
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics =
                    cameraManager.getCameraCharacteristics(cameraId);
                Boolean hasFlash = characteristics.get(
                    CameraCharacteristics.FLASH_INFO_AVAILABLE
                );
                int facing = characteristics.get(
                    CameraCharacteristics.LENS_FACING
                );

                if (hasFlash != null && hasFlash) {
                    torchSources.add(cameraId);
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        hasBackTorch = true;
                    } else if (
                        facing == CameraCharacteristics.LENS_FACING_FRONT
                    ) {
                        hasFrontTorch = true;
                    }
                }
            }

            hasMultipleTorches = hasBackTorch && hasFrontTorch;
            Log.d(
                TAG,
                "Torch sources found: Back=" +
                hasBackTorch +
                ", Front=" +
                hasFrontTorch
            );

            View dialogView = getLayoutInflater().inflate(
                R.layout.dialog_torch_options,
                null
            );
            RadioGroup torchGroup = dialogView.findViewById(R.id.torch_group);

            // Setup torch source options
            SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(requireContext());
            String currentTorchSource = prefs.getString(
                Constants.PREF_SELECTED_TORCH_SOURCE,
                null
            );
            boolean currentBothTorches = prefs.getBoolean(
                Constants.PREF_BOTH_TORCHES_ENABLED,
                false
            );

            // Add individual torch options
            for (String sourceId : torchSources) {
                RadioButton rb = new RadioButton(requireContext());
                CameraCharacteristics chars =
                    cameraManager.getCameraCharacteristics(sourceId);
                int facing = chars.get(CameraCharacteristics.LENS_FACING);
                rb.setText(
                    facing == CameraCharacteristics.LENS_FACING_BACK
                        ? getString(R.string.torch_back)
                        : getString(R.string.torch_front)
                );
                rb.setTag(sourceId);
                torchGroup.addView(rb);

                if (
                    sourceId.equals(currentTorchSource) && !currentBothTorches
                ) {
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
            if (
                currentTorchSource == null &&
                !currentBothTorches &&
                torchGroup.getChildCount() > 0
            ) {
                ((RadioButton) torchGroup.getChildAt(0)).setChecked(true);
            }

            new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.torch_options_title)
                .setView(dialogView)
                .setPositiveButton(R.string.torch_apply, (dialog, which) -> {
                    RadioButton selectedSource = dialogView.findViewById(
                        torchGroup.getCheckedRadioButtonId()
                    );
                    if (selectedSource != null) {
                        String selectedSourceId =
                            (String) selectedSource.getTag();
                        boolean isBothSelected = "both".equals(
                            selectedSourceId
                        );

                        // Save settings
                        SharedPreferences.Editor editor = prefs.edit();
                        editor.putBoolean(
                            Constants.PREF_BOTH_TORCHES_ENABLED,
                            isBothSelected
                        );
                        if (!isBothSelected) {
                            editor.putString(
                                Constants.PREF_SELECTED_TORCH_SOURCE,
                                selectedSourceId
                            );
                        }
                        editor.apply();

                        Log.d(
                            TAG,
                            "Saved torch settings - Both: " +
                            isBothSelected +
                            ", Source: " +
                            selectedSourceId
                        );
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
            CameraCharacteristics characteristics =
                cameraManager.getCameraCharacteristics(id);
            Boolean flashAvailable = characteristics.get(
                CameraCharacteristics.FLASH_INFO_AVAILABLE
            );
            if (flashAvailable != null && flashAvailable) {
                Log.d(TAG, "Found camera with flash: " + id);
                return id;
            }
        }
        Log.d(TAG, "No camera with flash found");
        return null;
    }

    private boolean isRecordingInProgress() {
        ActivityManager manager =
            (ActivityManager) requireContext().getSystemService(
                Context.ACTIVITY_SERVICE
            );
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(
            Integer.MAX_VALUE
        )) {
            if (
                RecordingService.class.getName().equals(
                    service.service.getClassName()
                )
            ) {
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
                            Log.w(
                                "TorchDebug",
                                "updateTorchUI: buttonTorchSwitch is null, cannot update."
                            );
                            return;
                        }

                        buttonTorchSwitch.setIcon(
                            AppCompatResources.getDrawable(
                                requireContext(),
                                R.drawable.ic_flashlight_on // Icon itself might not need to change, selector handles tint
                            )
                        );
                        buttonTorchSwitch.setSelected(isOn); // This controls the visual feedback (e.g., tint)

                        // Store the torch state
                        isTorchOn = isOn;

                        // Check if we're in Snow Veil theme and reapply special tinting
                        String currentTheme =
                            sharedPreferencesManager.sharedPreferences.getString(
                                com.fadcam.Constants.PREF_APP_THEME,
                                Constants.DEFAULT_APP_THEME
                            );
                        if ("Snow Veil".equals(currentTheme)) {
                            // For Snow Veil theme, we need to manually handle the icon tint
                            if (isOn) {
                                // Use yellow/amber color for ON state
                                buttonTorchSwitch.setIconTint(
                                    ColorStateList.valueOf(
                                        Color.parseColor("#FFC107")
                                    )
                                );
                            } else {
                                // Use black for OFF state
                                buttonTorchSwitch.setIconTint(
                                    ColorStateList.valueOf(Color.BLACK)
                                );
                            }
                        }

                        // DO NOT set enabled state here; it's handled by recording state UI methods.
                        Log.d(
                            "TorchDebug",
                            "Torch UI updated (selected state): " +
                            isOn +
                            ", Enabled: " +
                            buttonTorchSwitch.isEnabled()
                        );
                    } catch (Exception e) {
                        Log.e(
                            "TorchDebug",
                            "Error updating torch UI: " + e.getMessage()
                        );
                    }
                });
        }
    }

    private void setupAppLogoLongPressListener(View view) {
        ImageView appLogo = view.findViewById(R.id.ivAppTitle);
        if (appLogo != null) {
            appLogo.setOnLongClickListener(v -> {
                performHapticFeedback();
                Log.i(
                    TAG,
                    "App logo long-pressed. Navigating to TrashFragment manually."
                );
                // Navigate to TrashFragment manually
                try {
                    TrashFragment trashFragment = new TrashFragment();
                    FragmentManager fragmentManager =
                        getParentFragmentManager(); // Use getParentFragmentManager
                    FragmentTransaction fragmentTransaction =
                        fragmentManager.beginTransaction();

                    // Add fade in animation
                    fragmentTransaction.setCustomAnimations(
                        android.R.animator.fade_in,
                        android.R.animator.fade_out,
                        android.R.animator.fade_in,
                        android.R.animator.fade_out
                    );

                    // Make the overlay container visible with a fade effect
                    View overlayContainer = requireActivity().findViewById(
                        R.id.overlay_fragment_container
                    );
                    if (overlayContainer != null) {
                        // First make it visible but transparent
                        overlayContainer.setVisibility(View.VISIBLE);
                        overlayContainer.setAlpha(0f);

                        // Then animate to fully visible
                        overlayContainer
                            .animate()
                            .alpha(1f)
                            .setDuration(250)
                            .setListener(null);
                    } else {
                        Log.e(
                            TAG,
                            "R.id.overlay_fragment_container not found in MainActivity! Cannot show TrashFragment."
                        );
                        Toast.makeText(
                            getContext(),
                            "Error opening trash (container not found).",
                            Toast.LENGTH_SHORT
                        ).show();
                        return true; // Consume click but don't proceed
                    }

                    fragmentTransaction.replace(
                        R.id.overlay_fragment_container,
                        trashFragment
                    );
                    fragmentTransaction.addToBackStack(null);
                    fragmentTransaction.commit();
                } catch (Exception e) {
                    Log.e(TAG, "Manual navigation to TrashFragment failed.", e);
                    Toast.makeText(
                        getContext(),
                        "Error opening trash.",
                        Toast.LENGTH_SHORT
                    ).show();
                }
                return true; // Consume the long click
            });
        }
    }

    // ----- Fix Start for this class (HomeFragment) -----
    private void initializeViews(View view) {
        Log.d(TAG, "initializeViews: Finding UI elements.");
        tvCameraTitle = view.findViewById(R.id.tvCameraTitle);
        tvCameraSubtitle = view.findViewById(R.id.tvCameraSubtitle);
        ivCameraIcon = view.findViewById(R.id.ivCameraIcon);
        tvEstimateTitle = view.findViewById(R.id.tvEstimateTitle);
        tvEstimateSubtitle = view.findViewById(R.id.tvEstimateSubtitle);
        tvSpaceTitle = view.findViewById(R.id.tvSpaceTitle);
        tvSpaceSubtitle = view.findViewById(R.id.tvSpaceSubtitle);
        // ...existing code...
        tvElapsedTitle = view.findViewById(R.id.tvElapsedTitle);
        tvElapsedSubtitle = view.findViewById(R.id.tvElapsedSubtitle);
        tvRemainingTitle = view.findViewById(R.id.tvRemainingTitle);
        tvRemainingSubtitle = view.findViewById(R.id.tvRemainingSubtitle);
        btnHamburgerMenu = view.findViewById(R.id.btnHamburgerMenu);

        // Set up hamburger menu click handler
        if (btnHamburgerMenu != null) {
            btnHamburgerMenu.setOnClickListener(v -> {
                HomeSidebarFragment sidebar = HomeSidebarFragment.newInstance();
                sidebar.show(getParentFragmentManager(), "HomeSidebar");
            });
        }

        tvPreviewPlaceholder = view.findViewById(R.id.tvPreviewPlaceholder);
        buttonStartStop = view.findViewById(R.id.buttonStartStop);
        buttonPauseResume = view.findViewById(R.id.buttonPauseResume);
        buttonCamSwitch = view.findViewById(R.id.buttonCamSwitch);
        cardPreview = view.findViewById(R.id.cardPreview); // Assuming R.id.cardPreview exists
        vibrator = (Vibrator) requireActivity().getSystemService(
            Context.VIBRATOR_SERVICE
        );

        // Initialize pause button to be visibly disabled from the start
        if (buttonPauseResume != null) {
            buttonPauseResume.setEnabled(false);
            buttonPauseResume.setAlpha(0.5f);
        }

        // Clock related views
        cardClock = view.findViewById(R.id.cardClock); // Corrected ID
        tvClock = view.findViewById(R.id.tvClock);
        tvDateEnglish = view.findViewById(R.id.tvDateEnglish);
        tvDateArabic = view.findViewById(R.id.tvDateArabic);

        // Stats view
        tvStats = view.findViewById(R.id.tvStats); // Assuming R.id.tvStats for video count/size

        // Torch button (already initialized elsewhere, but good to have it
        // consistently)
        buttonTorchSwitch = view.findViewById(R.id.buttonTorchSwitch);

        // Compact overlay handling removed: we now use PickerBottomSheetFragment for
        // controls.

        // Initialize other views as needed here.
        // textureView is handled by setupTextureView
    }

    // ----- Fix Ended for this class (HomeFragment) -----

    // ----- Fix Start for this method(isRecordingOrPaused)-----
    private boolean isRecordingOrPaused() {
        return (
            recordingState == RecordingState.IN_PROGRESS ||
            recordingState == RecordingState.PAUSED
        );
    }

    // ----- Fix Ended for this method(isRecordingOrPaused)-----

    // ----- Fix Start for this method(updateServiceWithCurrentSurface)-----
    // This method replaces/refines the old updateRecordingSurface
    private void updateServiceWithCurrentSurface(
        @Nullable Surface surfaceToUse
    ) {
        updateServiceWithCurrentSurface(surfaceToUse, -1, -1);
    }

    private void updateServiceWithCurrentSurface(
        @Nullable Surface surfaceToUse,
        int width,
        int height
    ) {
        if (!isAdded() || getContext() == null) {
            Log.w(
                TAG,
                "updateServiceWithCurrentSurface: Fragment not added or context is null, cannot send surface update."
            );
            return;
        }

        Intent intent = new Intent(getContext(), RecordingService.class);
        intent.setAction(Constants.INTENT_ACTION_CHANGE_SURFACE);
        if (surfaceToUse != null && surfaceToUse.isValid()) {
            intent.putExtra("SURFACE", surfaceToUse);

            // Also send surface dimensions if available
            if (width > 0 && height > 0) {
                intent.putExtra("SURFACE_WIDTH", width);
                intent.putExtra("SURFACE_HEIGHT", height);
                Log.d(
                    TAG,
                    "updateServiceWithCurrentSurface: Sending new VALID surface to RecordingService with dimensions " +
                    width +
                    "x" +
                    height
                );
            } else {
                Log.d(
                    TAG,
                    "updateServiceWithCurrentSurface: Sending new VALID surface to RecordingService."
                );
            }
        } else {
            intent.putExtra("SURFACE", (Surface) null);
            Log.d(
                TAG,
                "updateServiceWithCurrentSurface: Sending NULL surface to RecordingService (preview disabled or surface invalid/destroyed)."
            );
        }

        // Use requireContext() for starting service if preferred and appropriate for
        // fragment version
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
        ActivityManager manager =
            (ActivityManager) getContext().getSystemService(
                Context.ACTIVITY_SERVICE
            );
        if (manager == null) {
            return false;
        }
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(
            Integer.MAX_VALUE
        )) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    // ----- Fix Ended for this class (HomeFragment) -----

    // ----- Fix Start for this class (HomeFragment_clock_color_picker) -----
    private void applyClockCardColor(String colorHex) {
        if (
            cardClock != null &&
            colorHex != null &&
            tvClock != null &&
            tvDateEnglish != null &&
            tvDateArabic != null
        ) {
            try {
                // Parse the color and apply background immediately
                int backgroundColor = Color.parseColor(colorHex);
                cardClock.setCardBackgroundColor(backgroundColor);

                // Determine if the background color is light or dark
                boolean isLightBackground = isLightColor(backgroundColor);

                // Set text colors IMMEDIATELY based on background brightness for better
                // contrast
                int textColor = isLightBackground ? Color.BLACK : Color.WHITE;

                // Apply text colors directly without delay
                tvClock.setTextColor(textColor);
                tvDateEnglish.setTextColor(textColor);
                tvDateArabic.setTextColor(textColor);

                // Force redraw
                cardClock.invalidate();

                com.fadcam.Log.i(
                    TAG,
                    "Applied clock card color: " +
                    colorHex +
                    " with text color: " +
                    (isLightBackground ? "BLACK" : "WHITE")
                );
            } catch (IllegalArgumentException e) {
                com.fadcam.Log.e(
                    TAG,
                    "Invalid color hex for clock card: " + colorHex,
                    e
                );
                // Optionally apply default color if parse fails
                cardClock.setCardBackgroundColor(
                    Color.parseColor(
                        SharedPreferencesManager.DEFAULT_CLOCK_CARD_COLOR
                    )
                );
                com.fadcam.Log.i(
                    TAG,
                    "Fallback to default color: " +
                    SharedPreferencesManager.DEFAULT_CLOCK_CARD_COLOR
                );
            }
        } else {
            com.fadcam.Log.w(
                TAG,
                "Cannot apply clock color - missing views: cardClock=" +
                (cardClock != null) +
                ", tvClock=" +
                (tvClock != null) +
                ", tvDateEnglish=" +
                (tvDateEnglish != null) +
                ", tvDateArabic=" +
                (tvDateArabic != null) +
                ", colorHex=" +
                colorHex
            );
        }
    }

    /**
     * Override the onBackPressed method from BaseFragment
     */
    @Override
    protected boolean onBackPressed() {
        // Handle any special cases for the HomeFragment's back button
        if (isRecordingOrPaused()) {
            // If recording is in progress, show a confirmation dialog
            new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Recording in Progress")
                .setMessage("Do you want to stop recording and exit?")
                .setPositiveButton("Stop and Exit", (dialog, which) -> {
                    stopRecording();
                    // Allow normal back behavior after stopping recording
                    requireActivity()
                        .getOnBackPressedDispatcher()
                        .onBackPressed();
                })
                .setNegativeButton("Continue Recording", null)
                .show();
            return true; // We handled the back press
        }

        // For normal cases, let the base implementation handle it
        return false;
    }

    // ----- Fix Start for camera resource availability methods -----
    /**
     * Initializes the receiver for camera resource availability status updates
     */
    private void initializeCameraResourceAvailabilityReceiver() {
        if (cameraResourceAvailabilityReceiver == null) {
            cameraResourceAvailabilityReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (
                        !isAdded() ||
                        intent == null ||
                        !Constants.ACTION_CAMERA_RESOURCE_AVAILABILITY.equals(
                            intent.getAction()
                        )
                    ) {
                        return;
                    }

                    boolean isAvailable = intent.getBooleanExtra(
                        Constants.EXTRA_CAMERA_RESOURCES_AVAILABLE,
                        true
                    );

                    areCameraResourcesAvailable = isAvailable;
                    updateStartButtonAvailability();

                    Log.d(
                        TAG,
                        "Received camera resource availability status: " +
                        isAvailable
                    );
                }
            };
        }
    }

    /**
     * Registers the camera resource availability receiver
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerCameraResourceAvailabilityReceiver() {
        if (
            isCameraResourceAvailabilityReceiverRegistered ||
            getContext() == null
        ) {
            return;
        }

        initializeCameraResourceAvailabilityReceiver();
        if (cameraResourceAvailabilityReceiver == null) {
            Log.e(
                TAG,
                "Cannot register: Failed to initialize camera resource availability receiver"
            );
            return;
        }

        IntentFilter filter = new IntentFilter(
            Constants.ACTION_CAMERA_RESOURCE_AVAILABILITY
        );
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().registerReceiver(
                    cameraResourceAvailabilityReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                );
            } else {
                requireContext().registerReceiver(
                    cameraResourceAvailabilityReceiver,
                    filter
                );
            }
            isCameraResourceAvailabilityReceiverRegistered = true;
            Log.d(TAG, "Camera resource availability receiver registered");
        } catch (Exception e) {
            Log.e(
                TAG,
                "Error registering camera resource availability receiver",
                e
            );
        }
    }

    /**
     * Unregisters the camera resource availability receiver
     */
    private void unregisterCameraResourceAvailabilityReceiver() {
        if (
            !isCameraResourceAvailabilityReceiverRegistered ||
            getContext() == null
        ) {
            return;
        }

        try {
            requireContext().unregisterReceiver(
                cameraResourceAvailabilityReceiver
            );
            isCameraResourceAvailabilityReceiverRegistered = false;
            Log.d(TAG, "Camera resource availability receiver unregistered");
        } catch (IllegalArgumentException e) {
            Log.w(
                TAG,
                "Error unregistering camera resource availability receiver: " +
                e.getMessage()
            );
        }
    }

    // ----- Fix End for camera resource availability methods -----

    // ----- Fix Start for this class (HomeFragment) -----
    private void initializeSegmentCompleteStatsReceiver() {
        if (segmentCompleteStatsReceiver == null) {
            segmentCompleteStatsReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (
                        intent != null &&
                        Constants.ACTION_RECORDING_SEGMENT_COMPLETE.equals(
                            intent.getAction()
                        )
                    ) {
                        Log.d(
                            TAG,
                            "Segment complete, updating stats from HomeFragment."
                        );
                        if (isAdded()) {
                            // Ensure fragment is still attached
                            updateStats();
                        }
                    }
                }
            };
        }
    }

    // ----- Fix Ended for this class (HomeFragment) -----

    // ----- Fix Start for this class (HomeFragment) -----
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerSegmentCompleteStatsReceiver(Context context) {
        // First check if fragment is attached to prevent IllegalStateException
        if (!isAdded() || isDetached()) {
            Log.e(
                TAG,
                "Fragment not attached or is detached, cannot register segmentCompleteStatsReceiver"
            );
            isSegmentCompleteStatsReceiverRegistered = false;
            return;
        }

        if (context == null) {
            Log.e(
                TAG,
                "Context is null, cannot register segmentCompleteStatsReceiver"
            );
            isSegmentCompleteStatsReceiverRegistered = false;
            return;
        }

        try {
            initializeSegmentCompleteStatsReceiver(); // Ensure it's initialized

            if (segmentCompleteStatsReceiver != null) {
                IntentFilter filter = new IntentFilter(
                    Constants.ACTION_RECORDING_SEGMENT_COMPLETE
                );
                // Add receiver export flag for Android 13+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(
                        segmentCompleteStatsReceiver,
                        filter,
                        Context.RECEIVER_NOT_EXPORTED
                    );
                } else {
                    context.registerReceiver(
                        segmentCompleteStatsReceiver,
                        filter
                    );
                }
                isSegmentCompleteStatsReceiverRegistered = true;
                Log.d(TAG, "Registered segmentCompleteStatsReceiver.");
            } else {
                isSegmentCompleteStatsReceiverRegistered = false;
                Log.e(
                    TAG,
                    "segmentCompleteStatsReceiver is null, not registering."
                );
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "Fragment not associated with fragment manager", e);
            isSegmentCompleteStatsReceiverRegistered = false;
        }
    }

    // ----- Fix Ended for this class (HomeFragment) -----

    // ----- Fix Start for this method(onHiddenChanged)-----
    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        Log.d(
            TAG,
            "onHiddenChanged: Fragment " + (hidden ? "hidden" : "shown")
        );
        // ----- Fix Start for this method(onHiddenChanged)-----
        if (!hidden) {
            if (
                isPreviewEnabled &&
                isRecordingOrPaused() &&
                textureViewSurface != null &&
                textureViewSurface.isValid()
            ) {
                Log.d(
                    TAG,
                    "onHiddenChanged: Preview enabled, sending valid surface to service"
                );
                updateServiceWithCurrentSurface(textureViewSurface);
            } else if (!isPreviewEnabled || !isRecordingOrPaused()) {
                Log.d(
                    TAG,
                    "onHiddenChanged: Preview disabled or not recording, sending null surface"
                );
                updateServiceWithCurrentSurface(null);
            }
        } else {
            if (isRecordingOrPaused()) {
                Log.d(
                    TAG,
                    "onHiddenChanged: Fragment hidden while recording, sending null surface"
                );
                updateServiceWithCurrentSurface(null);
            }
        }
        // ----- Fix Ended for this method(onHiddenChanged)-----
    }

    // ----- Fix Ended for this method(onHiddenChanged)-----

    // ----- Fix Start: Add setTextColorsRecursive helper for dynamic theming -----
    private void setTextColorsRecursive(View view, int primary, int secondary) {
        if (view == null) return;
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            CharSequence text = tv.getText();
            if (
                text != null && text.length() > 0 && (tv.getTextSize() >= 16f)
            ) {
                tv.setTextColor(primary);
            } else {
                tv.setTextColor(secondary);
            }
        } else if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                setTextColorsRecursive(vg.getChildAt(i), primary, secondary);
            }
        }
    }

    // ----- Fix End: Add setTextColorsRecursive helper for dynamic theming -----

    // ----- Fix Start: Add method to get default clock color for theme -----
    private String getDefaultClockColorForTheme(String themeName) {
        com.fadcam.Log.i(
            TAG,
            "getDefaultClockColorForTheme called with themeName=[" +
            themeName +
            "]"
        );

        String result;
        // Check for AMOLED theme first (prioritize this check)
        if (
            "AMOLED".equals(themeName) ||
            "Faded Night".equals(themeName) ||
            "Amoled".equals(themeName) ||
            "amoled".equals(themeName)
        ) {
            result = CLOCK_COLOR_HEX_VALUES[6]; // Dark Grey (#424242)
            com.fadcam.Log.i(
                TAG,
                "AMOLED theme match, using Dark Grey: " + result
            );

            // Extra check: force reset the saved color for any AMOLED theme variant
            String savedColor = sharedPreferencesManager.getClockCardColor();
            if ("#673AB7".equals(savedColor)) {
                // If it's still the default purple
                sharedPreferencesManager.setClockCardColor("#424242"); // Force set to Dark Grey
                Toast.makeText(
                    requireContext(),
                    "Applied Dark Grey for AMOLED theme",
                    Toast.LENGTH_SHORT
                ).show();
                com.fadcam.Log.i(
                    TAG,
                    "FORCE RESET: Changed saved clock color from Purple to Dark Grey for AMOLED"
                );
            }
        } else if ("Crimson Bloom".equals(themeName)) {
            result = CLOCK_COLOR_HEX_VALUES[5]; // Red (#F44336)
            com.fadcam.Log.i(
                TAG,
                "Crimson Bloom theme match, using Red: " + result
            );
        } else if ("Premium Gold".equals(themeName)) {
            result = CLOCK_COLOR_HEX_VALUES[9]; // Gold (#FFD700)
            com.fadcam.Log.i(
                TAG,
                "Premium Gold theme match, using Gold: " + result
            );
        } else if ("Midnight Dusk".equals(themeName)) {
            result = CLOCK_COLOR_HEX_VALUES[0]; // Purple (#673AB7)
            com.fadcam.Log.i(
                TAG,
                "Midnight Dusk theme match, using Purple: " + result
            );
        } else if ("Blue Ocean".equals(themeName)) {
            result = CLOCK_COLOR_HEX_VALUES[1]; // Blue (#2196F3)
            com.fadcam.Log.i(
                TAG,
                "Blue Ocean theme match, using Blue: " + result
            );
        } else if ("Green Fields".equals(themeName)) {
            result = CLOCK_COLOR_HEX_VALUES[2]; // Green (#4CAF50)
            com.fadcam.Log.i(
                TAG,
                "Green Fields theme match, using Green: " + result
            );
        } else if ("Teal Dream".equals(themeName)) {
            result = CLOCK_COLOR_HEX_VALUES[3]; // Teal (#009688)
            com.fadcam.Log.i(
                TAG,
                "Teal Dream theme match, using Teal: " + result
            );
        } else if ("Orange Sunset".equals(themeName)) {
            result = CLOCK_COLOR_HEX_VALUES[4]; // Orange (#FF9800)
            com.fadcam.Log.i(
                TAG,
                "Orange Sunset theme match, using Orange: " + result
            );
        } else if ("Dark Grey".equals(themeName)) {
            result = CLOCK_COLOR_HEX_VALUES[6]; // Dark Grey (#424242)
            com.fadcam.Log.i(
                TAG,
                "Dark Grey theme match, using Dark Grey: " + result
            );
        } else if ("Silent Forest".equals(themeName)) {
            result = CLOCK_COLOR_HEX_VALUES[2]; // Green (#4CAF50)
            com.fadcam.Log.i(
                TAG,
                "Silent Forest theme match, using Green: " + result
            );
        } else if ("Shadow Alloy".equals(themeName)) {
            result = CLOCK_COLOR_HEX_VALUES[8]; // Amoled Gray closest to silver
            com.fadcam.Log.i(
                TAG,
                "Shadow Alloy theme match, using Silver-ish: " + result
            );
        } else if ("Pookie Pink".equals(themeName)) {
            result = CLOCK_COLOR_HEX_VALUES[10]; // Pink (#F06292)
            com.fadcam.Log.i(
                TAG,
                "Pookie Pink theme match, using Pink: " + result
            );
        } else {
            // Fallback to default
            result = CLOCK_COLOR_HEX_VALUES[0]; // Default to Purple (#673AB7)
            com.fadcam.Log.w(
                TAG,
                "No specific theme match found for [" +
                themeName +
                "], defaulting to Purple"
            );
        }

        com.fadcam.Log.i(
            TAG,
            "Final default clock color for theme [" + themeName + "]: " + result
        );
        return result;
    }

    // ----- Fix End: Add method to get default clock color for theme -----

    // ----- Fix Start: Add Snow Veil theme UI adjustments -----
    /**
     * Applies Snow Veil theme UI adjustments to improve contrast
     */
    private void applySnowVeilThemeToUI(View rootView) {
        // Selectively tint only essential buttons, not all icons
        applyButtonTinting();

        // Ensure text in cards has proper contrast
        ensureCardTextContrast(rootView);

        // Apply Snow Veil specific fixes
        applySnowVeilSpecificFixes(rootView);
    }

    /**
     * Apply Snow Veil theme specific fixes for text contrast and colors
     */
    private void applySnowVeilSpecificFixes(View rootView) {
        // Find storage card and ensure all text is black
        CardView cardStorage = rootView.findViewById(R.id.cardStorage);
        if (cardStorage != null) {
            // Force all text in storage card to black for better contrast on white
            // background
            setTextColorsRecursive(
                cardStorage,
                Color.BLACK,
                Color.parseColor("#424242")
            );
        }

        // Find any other text views that might need contrast fixes
        // This ensures all text is properly visible on the light theme
        View[] textContainers = {
            rootView.findViewById(R.id.cardStats),
            rootView.findViewById(R.id.cardClock),
        };

        for (View container : textContainers) {
            if (container != null) {
                setTextColorsRecursive(
                    container,
                    Color.BLACK,
                    Color.parseColor("#424242")
                );
            }
        }
    }

    /**
     * Apply tinting only to main action buttons
     */
    private void applyButtonTinting() {
        // Only tint the main action buttons that we already have references to
        // This avoids searching for IDs that might not exist

        // Start/Stop button
        if (buttonStartStop != null) {
            buttonStartStop.setTextColor(Color.BLACK);
            buttonStartStop.setIconTint(ColorStateList.valueOf(Color.BLACK));
        }

        // Pause/Resume button
        if (buttonPauseResume != null) {
            buttonPauseResume.setTextColor(Color.BLACK);
            buttonPauseResume.setIconTint(ColorStateList.valueOf(Color.BLACK));
        }

        // Torch switch button - special handling for on/off state
        if (buttonTorchSwitch != null) {
            buttonTorchSwitch.setTextColor(Color.BLACK);

            // Only set black icon tint if torch is OFF
            // For ON state, preserve the yellow color by not setting a black tint
            if (!isTorchOn) {
                buttonTorchSwitch.setIconTint(
                    ColorStateList.valueOf(Color.BLACK)
                );
            } else {
                // Use yellow/amber color for the torch when it's ON
                buttonTorchSwitch.setIconTint(
                    ColorStateList.valueOf(Color.parseColor("#FFC107"))
                );
            }
        }

        // Camera switch button
        if (buttonCamSwitch != null) {
            buttonCamSwitch.setTextColor(Color.BLACK);
        }

        // -------------- Fix Start (applyButtonTinting - recording tiles)-----------
        // Recording tiles - apply dark colors for Snow Veil theme visibility
        if (tileAfToggle != null) {
            // Use dark gray instead of black for better visibility on light background
            tileAfToggle.setTextColor(Color.parseColor("#424242"));
        }

        if (tileExp != null) {
            // Check if exposure is active (orange tint should be preserved)
            if (aeLocked || currentEvIndex != 0) {
                // Keep orange tint for active state
                tileExp.setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        R.color.orange_accent
                    )
                );
            } else {
                // Use dark gray for inactive state
                tileExp.setTextColor(Color.parseColor("#424242"));
            }
        }

        if (tileZoom != null) {
            // Check if zoom is active (not at 1.0x)
            SharedPreferencesManager sp = SharedPreferencesManager.getInstance(
                requireContext()
            );
            CameraType currentCamera = sp.getCameraSelection();
            float currentZoom = sp.getSpecificZoomRatio(currentCamera);

            if (Math.abs(currentZoom - 1.0f) > 0.01f) {
                // Keep orange tint for active zoom
                tileZoom.setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        R.color.orange_accent
                    )
                );
            } else {
                // Use dark gray for default zoom
                tileZoom.setTextColor(Color.parseColor("#424242"));
            }
        }
        // -------------- Fix Ended (applyButtonTinting - recording tiles)-----------
    }

    /**
     * Apply Faded Night theme styling to recording control tiles
     */
    private void applyFadedNightThemeToTiles() {
        // Define the Faded Night surface color (same as used for cards)
        int fadedNightSurface = Color.parseColor("#181A1B");

        // Create a custom drawable with ripple effect for Faded Night theme
        android.graphics.drawable.GradientDrawable shape =
            new android.graphics.drawable.GradientDrawable();
        shape.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        shape.setColor(fadedNightSurface);
        shape.setCornerRadius(8 * getResources().getDisplayMetrics().density); // 8dp in pixels

        // Create ripple drawable with the custom background
        android.graphics.drawable.RippleDrawable rippleDrawable =
            new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#33FFFFFF")
                ), // Ripple color
                // (semi-transparent white)
                shape, // Background
                null // Mask (null means use background as mask)
            );

        // Apply the custom drawable to recording tiles
        if (tileAfToggle != null) {
            tileAfToggle.setBackground(rippleDrawable);
        }
        if (tileExp != null) {
            // Create a new instance for each tile to avoid sharing the same drawable
            android.graphics.drawable.GradientDrawable expShape =
                new android.graphics.drawable.GradientDrawable();
            expShape.setShape(
                android.graphics.drawable.GradientDrawable.RECTANGLE
            );
            expShape.setColor(fadedNightSurface);
            expShape.setCornerRadius(
                8 * getResources().getDisplayMetrics().density
            );

            android.graphics.drawable.RippleDrawable expRipple =
                new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(
                        Color.parseColor("#33FFFFFF")
                    ),
                    expShape,
                    null
                );
            tileExp.setBackground(expRipple);
        }
        if (tileZoom != null) {
            // Create a new instance for each tile to avoid sharing the same drawable
            android.graphics.drawable.GradientDrawable zoomShape =
                new android.graphics.drawable.GradientDrawable();
            zoomShape.setShape(
                android.graphics.drawable.GradientDrawable.RECTANGLE
            );
            zoomShape.setColor(fadedNightSurface);
            zoomShape.setCornerRadius(
                8 * getResources().getDisplayMetrics().density
            );

            android.graphics.drawable.RippleDrawable zoomRipple =
                new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(
                        Color.parseColor("#33FFFFFF")
                    ),
                    zoomShape,
                    null
                );
            tileZoom.setBackground(zoomRipple);
        }
    }

    /**
     * Apply Midnight Dusk theme styling to recording control tiles
     */
    private void applyMidnightDuskThemeToTiles() {
        // Define the Midnight Dusk surface color (same as used for cards)
        int midnightDuskSurface = ContextCompat.getColor(
            requireContext(),
            R.color.dark_purple_bar
        );

        // Create a custom drawable with ripple effect for Midnight Dusk theme
        android.graphics.drawable.GradientDrawable shape =
            new android.graphics.drawable.GradientDrawable();
        shape.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        shape.setColor(midnightDuskSurface);
        shape.setCornerRadius(8 * getResources().getDisplayMetrics().density); // 8dp in pixels

        // Create ripple drawable with the custom background
        android.graphics.drawable.RippleDrawable rippleDrawable =
            new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#33FFFFFF")
                ), // Ripple color
                // (semi-transparent white)
                shape, // Background
                null // Mask (null means use background as mask)
            );

        // Apply the custom drawable to recording tiles
        if (tileAfToggle != null) {
            tileAfToggle.setBackground(rippleDrawable);
        }
        if (tileExp != null) {
            // Create a new instance for each tile to avoid sharing the same drawable
            android.graphics.drawable.GradientDrawable expShape =
                new android.graphics.drawable.GradientDrawable();
            expShape.setShape(
                android.graphics.drawable.GradientDrawable.RECTANGLE
            );
            expShape.setColor(midnightDuskSurface);
            expShape.setCornerRadius(
                8 * getResources().getDisplayMetrics().density
            );

            android.graphics.drawable.RippleDrawable expRipple =
                new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(
                        Color.parseColor("#33FFFFFF")
                    ),
                    expShape,
                    null
                );
            tileExp.setBackground(expRipple);
        }
        if (tileZoom != null) {
            // Create a new instance for each tile to avoid sharing the same drawable
            android.graphics.drawable.GradientDrawable zoomShape =
                new android.graphics.drawable.GradientDrawable();
            zoomShape.setShape(
                android.graphics.drawable.GradientDrawable.RECTANGLE
            );
            zoomShape.setColor(midnightDuskSurface);
            zoomShape.setCornerRadius(
                8 * getResources().getDisplayMetrics().density
            );

            android.graphics.drawable.RippleDrawable zoomRipple =
                new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(
                        Color.parseColor("#33FFFFFF")
                    ),
                    zoomShape,
                    null
                );
            tileZoom.setBackground(zoomRipple);
        }
    }

    /**
     * Apply theme-specific colors to the mode switcher (FadCam, FadRec, FadMic)
     */
    private void applyModeSwitcherTheming(String themeName) {
        try {
            // Find the active segment (FadCam)
            View segmentFadCam = getView().findViewById(R.id.segment_fadcam);
            if (segmentFadCam == null) return;

            // Determine the theme color and text color for the active segment
            int activeColor;
            int textColor;
            switch (themeName) {
                case "Crimson Bloom":
                    activeColor = ContextCompat.getColor(
                        requireContext(),
                        R.color.red_theme_primary
                    );
                    textColor = Color.WHITE; // Red background with white text
                    break;
                case "Premium Gold":
                    activeColor = ContextCompat.getColor(
                        requireContext(),
                        R.color.gold_theme_primary
                    );
                    textColor = Color.BLACK; // Bright gold background with black text
                    break;
                case "Silent Forest":
                    activeColor = ContextCompat.getColor(
                        requireContext(),
                        R.color.silentforest_theme_primary
                    );
                    textColor = Color.WHITE; // Green background with white text
                    break;
                case "Shadow Alloy":
                    activeColor = ContextCompat.getColor(
                        requireContext(),
                        R.color.shadowalloy_theme_primary
                    );
                    textColor = Color.BLACK; // Bright silver background with black text
                    break;
                case "Pookie Pink":
                    activeColor = ContextCompat.getColor(
                        requireContext(),
                        R.color.pookiepink_theme_primary
                    );
                    textColor = Color.BLACK; // Bright pink background with black text
                    break;
                case "Snow Veil":
                    activeColor = ContextCompat.getColor(
                        requireContext(),
                        R.color.snowveil_theme_primary
                    );
                    textColor = Color.WHITE; // Gray background with white text
                    break;
                case "Midnight Dusk":
                    // Use the same purple color as the clock card
                    activeColor = Color.parseColor("#673AB7"); // Same purple as clock card
                    textColor = Color.WHITE; // Purple background with white text
                    break;
                case "Faded Night":
                    // Use the original red color for Faded Night
                    activeColor = ContextCompat.getColor(
                        requireContext(),
                        R.color.redPastel
                    );
                    textColor = Color.WHITE; // Red background with white text
                    break;
                case "AMOLED":
                    // For AMOLED theme, use a lighter accent color
                    activeColor = ContextCompat.getColor(
                        requireContext(),
                        R.color.amoled_accent
                    );
                    textColor = Color.WHITE; // Dark background with white text
                    break;
                default:
                    // Default to the original red color for unknown themes
                    activeColor = ContextCompat.getColor(
                        requireContext(),
                        R.color.redPastel
                    );
                    textColor = Color.WHITE;
                    break;
            }

            // Create a new background drawable with the theme color
            GradientDrawable activeBackground = new GradientDrawable();
            activeBackground.setShape(GradientDrawable.RECTANGLE);
            activeBackground.setColor(activeColor);
            activeBackground.setCornerRadius(
                14 * getResources().getDisplayMetrics().density
            ); // 14dp radius

            // Apply the themed background to the active segment
            segmentFadCam.setBackground(activeBackground);

            // Find and update the text color for the active segment
            TextView textFadCam = segmentFadCam.findViewById(R.id.text_fadcam);
            if (textFadCam != null) {
                textFadCam.setTextColor(textColor);
            }
        } catch (Exception e) {
            // Silently handle any errors to avoid crashes
            android.util.Log.w(
                "HomeFragment",
                "Error applying mode switcher theming",
                e
            );
        }
    }

    /**
     * Ensure text in cards has proper contrast with focused handling for video
     * states card
     */
    private void ensureCardTextContrast(View rootView) {
        // Find cards by their actual IDs from the layout
        CardView cardStats = rootView.findViewById(R.id.cardStats);
        CardView cardStorage = rootView.findViewById(R.id.cardStorage);
        CardView cardClock = rootView.findViewById(R.id.cardClock);

        // Stats card text - this card shows videos space info below the clock
        if (cardStats != null) {
            // Force all text in the stats card to black, including headings
            forceForceMakeAllTextBlack(cardStats);

            // Extra handling for the HTML/styled text in tvStats which might need special
            // handling
            if (tvStats != null) {
                // For Snow Veil theme, we need to override the HTML color codes
                if (tvStats.getText() != null) {
                    // Get current stats data
                    String currentText = tvStats.getText().toString();

                    // Extract the numbers from the current text
                    int videoCount = 0;
                    String usedSpace = "";
                    try {
                        // Try to parse out the values from the formatted text
                        String[] lines = currentText.split("\\n");
                        for (String line : lines) {
                            if (line.contains("Videos:")) {
                                // Extract number after "Videos:"
                                String[] parts = line.trim().split(":");
                                if (parts.length > 1) {
                                    videoCount = Integer.parseInt(
                                        parts[1].trim()
                                    );
                                }
                            } else if (line.contains("Used Space:")) {
                                // Extract text after "Used Space:"
                                String[] parts = line.trim().split(":");
                                if (parts.length > 1) {
                                    usedSpace = parts[1].trim();
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.e(
                            TAG,
                            "Error parsing stats text: " + e.getMessage()
                        );
                    }

                    // Create a custom black text version for Snow Veil theme
                    String blackStatsText =
                        "\n    " +
                        "<font color='#000000' style='font-size:12sp;'><b>Videos: </b></font>" +
                        "<font color='#333333' style='font-size:11sp;'>" +
                        videoCount +
                        "</font><br>" +
                        "<font color='#000000' style='font-size:12sp;'><b>Used: </font>" +
                        "<font color='#333333' style='font-size:11sp;'>" +
                        usedSpace +
                        "</font>" +
                        "\n";

                    // Apply the black text version
                    tvStats.setText(
                        Html.fromHtml(
                            blackStatsText,
                            Html.FROM_HTML_MODE_LEGACY
                        )
                    );
                }
            }
        }

        // Storage info card text - use white text since it has dark background
        if (cardStorage != null) {
            makeStorageCardTextWhite(cardStorage);
        }

        // Direct access to known TextViews for clock
        if (tvClock != null) {
            tvClock.setTextColor(Color.BLACK);
        }

        if (tvDateEnglish != null) {
            tvDateEnglish.setTextColor(Color.BLACK);
        }

        if (tvDateArabic != null) {
            tvDateArabic.setTextColor(Color.BLACK);
        }

        // Update colors for storage widget TextViews - use white for dark background
        if (tvCameraTitle != null) tvCameraTitle.setTextColor(Color.WHITE);
        if (tvCameraSubtitle != null) tvCameraSubtitle.setTextColor(
            Color.WHITE
        );
        if (tvEstimateTitle != null) tvEstimateTitle.setTextColor(Color.WHITE);
        if (tvEstimateSubtitle != null) tvEstimateSubtitle.setTextColor(
            Color.WHITE
        );
        if (tvSpaceTitle != null) tvSpaceTitle.setTextColor(Color.WHITE);
        if (tvSpaceSubtitle != null) tvSpaceSubtitle.setTextColor(Color.WHITE);
        if (tvElapsedTitle != null) tvElapsedTitle.setTextColor(Color.WHITE);
        if (tvElapsedSubtitle != null) tvElapsedSubtitle.setTextColor(
            Color.WHITE
        );
        if (tvRemainingTitle != null) tvRemainingTitle.setTextColor(
            Color.WHITE
        );
        if (tvRemainingSubtitle != null) tvRemainingSubtitle.setTextColor(
            Color.WHITE
        );

        // -------------- Fix Start (ensureCardTextContrast - icons)-----------
        // Keep semantic colors for icons but ensure they're visible on light background
        if (ivCameraIcon != null) {
            // Keep green color for camera icon but use a darker shade for better contrast
            ivCameraIcon.setTextColor(Color.parseColor("#2E7D32")); // Darker green for better contrast
        }

        // Handle other icons by finding them in the storage card
        if (cardStorage != null) {
            findAndColorIconsByText(cardStorage);
        }
        // -------------- Fix Ended (ensureCardTextContrast - icons)-----------
    }

    /**
     * Helper method to find and color icon TextViews by their text content
     */
    private void findAndColorIconsByText(ViewGroup viewGroup) {
        try {
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                View child = viewGroup.getChildAt(i);
                if (child instanceof TextView) {
                    TextView textView = (TextView) child;
                    String text = textView.getText().toString();

                    // Apply semantic colors based on icon text
                    switch (text) {
                        case "timer":
                            textView.setTextColor(Color.parseColor("#D32F2F")); // Red for timer
                            break;
                        case "access_time":
                            textView.setTextColor(Color.parseColor("#F57C00")); // Orange for hourglass/estimated time
                            break;
                        case "play_arrow":
                            textView.setTextColor(Color.parseColor("#4CAF50")); // Green for play/elapsed time
                            break;
                        case "folder":
                            textView.setTextColor(Color.parseColor("#616161")); // Gray for folder/storage
                            break;
                        case "database":
                            textView.setTextColor(Color.parseColor("#1976D2")); // Blue for database/storage
                            break;
                    }
                } else if (child instanceof ViewGroup) {
                    findAndColorIconsByText((ViewGroup) child);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error coloring icons: " + e.getMessage());
        }
    }

    /**
     * Helper method to make storage card text white for dark background
     */
    private void makeStorageCardTextWhite(ViewGroup viewGroup) {
        try {
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                View child = viewGroup.getChildAt(i);
                if (child instanceof TextView) {
                    TextView textView = (TextView) child;
                    String text = textView.getText().toString();

                    // Skip icon TextViews (they have their own colors)
                    if (
                        text.equals("timer") ||
                        text.equals("access_time") ||
                        text.equals("play_arrow") ||
                        text.equals("folder") ||
                        text.equals("database") ||
                        text.equals("camera_alt")
                    ) {
                        continue; // Skip icons, they're handled separately
                    }

                    // Make regular text white for dark background
                    textView.setTextColor(Color.WHITE);

                    // Handle if the text has any spans (HTML formatting)
                    CharSequence textContent = textView.getText();
                    if (textContent instanceof Spanned) {
                        // Create a new SpannableString that preserves formatting but forces white color
                        SpannableString newText = new SpannableString(
                            textContent
                        );
                        newText.setSpan(
                            new ForegroundColorSpan(Color.WHITE),
                            0,
                            newText.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        );
                        textView.setText(newText);
                    }
                } else if (child instanceof ViewGroup) {
                    makeStorageCardTextWhite((ViewGroup) child);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error making storage text white: " + e.getMessage());
        }
    }

    /**
     * Helper method to make all text in a ViewGroup black - more aggressive version
     */
    private void forceForceMakeAllTextBlack(ViewGroup viewGroup) {
        try {
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                View child = viewGroup.getChildAt(i);
                if (child instanceof TextView) {
                    TextView textView = (TextView) child;
                    textView.setTextColor(Color.BLACK);

                    // Handle if the text has any spans (HTML formatting)
                    CharSequence text = textView.getText();
                    if (text instanceof Spanned) {
                        // Create a new SpannableString that preserves formatting but forces black color
                        SpannableString newText = new SpannableString(text);
                        newText.setSpan(
                            new ForegroundColorSpan(Color.BLACK),
                            0,
                            newText.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        );
                        textView.setText(newText);
                    }
                } else if (child instanceof ViewGroup) {
                    forceForceMakeAllTextBlack((ViewGroup) child);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error making text black: " + e.getMessage());
        }
    }

    // ----- Fix End: Add Snow Veil theme UI adjustments -----

    // Utility method to get the current app version (versionName)
    private String getAppVersionForUpdates() {
        try {
            android.content.pm.PackageManager pm =
                requireActivity().getPackageManager();
            android.content.pm.PackageInfo pInfo = pm.getPackageInfo(
                requireActivity().getPackageName(),
                0
            );
            return pInfo.versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return "N/A";
        }
    }

    // Utility method to compare versions and determine if an update is available
    private boolean isUpdateAvailable(
        String currentVersion,
        String latestVersion
    ) {
        boolean currentIsBeta = currentVersion.contains("beta");
        currentVersion = currentVersion.replace("-beta", "");
        String[] current = currentVersion.split("\\.");
        String[] latest = latestVersion.split("\\.");
        for (int i = 0; i < Math.min(current.length, latest.length); i++) {
            int currentPart = Integer.parseInt(current[i]);
            int latestPart = Integer.parseInt(latest[i]);
            if (latestPart > currentPart) {
                return true;
            } else if (latestPart < currentPart) {
                return false;
            }
        }
        return (
            latest.length > current.length ||
            (latest.length == current.length && currentIsBeta)
        );
    }

    // -------------- Fix Start (ProgressCallback Interface)-----------
    /**
     * Callback interface for progressive loading operations to update UI
     * incrementally.
     */
    public interface ProgressCallback {
        /**
         * Called when progress is made during file loading.
         *
         * @param current Current number of files processed
         * @param total   Total number of files to process
         */
        void onProgress(int current, int total);
    }
    // -------------- Fix Ended (ProgressCallback Interface)-----------
}
