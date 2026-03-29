package com.fadcam.ui;

import com.fadcam.Log;
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
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.text.Html;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.Formatter;
import android.text.style.ForegroundColorSpan;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
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
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.cardview.widget.CardView; // Add this
import androidx.core.app.ActivityCompat;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.FragmentManager; // <<< ADD IMPORT FOR FragmentManager
import androidx.fragment.app.FragmentTransaction; // <<< ADD IMPORT FOR FragmentTransaction
import com.google.android.material.card.MaterialCardView;
import com.fadcam.CameraType;
import com.fadcam.MainActivity;
import com.fadcam.Constants;
import com.fadcam.FLog;
import com.fadcam.R;
import com.fadcam.RecordingControlIntents;
import com.fadcam.RecordingState;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.Utils;
import com.fadcam.VideoCodec;
import com.fadcam.services.RecordingService;
import com.fadcam.services.TorchService;
import com.fadcam.dualcam.service.DualCameraRecordingService;
import com.fadcam.streaming.RemoteStreamManager;
import com.fadcam.ui.helpers.HomeFragmentHelper;
import com.fadcam.utils.DebouncedRunnable;
import com.fadcam.utils.DeviceHelper;
import com.fadcam.utils.ServiceStartPolicy;
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
    private static final String ELAPSED_ALIGNMENT_RESULT_KEY = "home_elapsed_alignment_picker";
    private static final String ELAPSED_SIZE_RESULT_KEY = "home_elapsed_size_picker";
    private static final String ELAPSED_FONT_RESULT_KEY = "home_elapsed_font_picker";
    private static final String ELAPSED_FLAG_RESULT_KEY = "home_elapsed_flag_picker";
    private static final String ELAPSED_BACKGROUND_RESULT_KEY = "home_elapsed_background_picker";
    private static final String ELAPSED_CUSTOMIZE_RESULT_KEY = "home_elapsed_customize_picker";
    private static final String STORAGE_INDICATOR_STYLE_RESULT_KEY = "home_storage_indicator_style_picker";
    private static final String STORAGE_CUSTOMIZE_RESULT_KEY = "home_storage_customize_picker";
    private static final String STORAGE_TOTAL_RESULT_KEY = "home_storage_total_picker";
    private static final String TIME_LEFT_COLOR_RESULT_KEY = "home_time_left_color_picker";
    private static final String CLOCK_CUSTOMIZE_RESULT_KEY = "home_clock_customize_picker";
    private static final String CLOCK_DISPLAY_RESULT_KEY = "home_clock_display_picker";
    private static final String CLOCK_COLOR_RESULT_KEY = "home_clock_color_picker";
    private static final String CLOCK_HOUR_FORMAT_RESULT_KEY = "home_clock_hour_format_picker";
    private static final String ELAPSED_ALIGNMENT_CENTER = "center";
    private static final String ELAPSED_ALIGNMENT_START = "start";
    private static final String ELAPSED_SIZE_SMALL = "small";
    private static final String ELAPSED_SIZE_MEDIUM = "medium";
    private static final String ELAPSED_SIZE_LARGE = "large";
    private static final String ELAPSED_FONT_UBUNTU = "ubuntu";
    private static final String ELAPSED_FONT_MONOSPACE = "monospace";
    private static final String ELAPSED_FONT_DOTO = "doto";
    private static final String ELAPSED_FLAG_SHOW = "show";
    private static final String ELAPSED_FLAG_HIDE = "hide";
    private static final String ELAPSED_BACKGROUND_TRANSPARENT = "transparent";
    private static final String ELAPSED_BACKGROUND_BLACK = "black";
    private static final String ELAPSED_BACKGROUND_WHITE = "white";
    private static final String STORAGE_INDICATOR_RING = "ring";
    private static final String STORAGE_INDICATOR_MICRO_PILL = "micro_pill_bar";
    private static final String STORAGE_INDICATOR_VERTICAL_BAR = "vertical_bar";
    private static final String STORAGE_TOTAL_VISIBLE = "visible";
    private static final String STORAGE_TOTAL_HIDDEN = "hidden";
    private static final String CLOCK_HOUR_FORMAT_12 = "12h";
    private static final String CLOCK_HOUR_FORMAT_24 = "24h";

    private HomeFragmentHelper fragmentHelper;

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
    
    // OPTIMIZATION: Clock color calculation caching (Phase 3)
    private int lastClockBackgroundColor = -1;
    private int cachedClockTextColor = Color.WHITE;
    private long lastClockColorCalcTime = 0;
    private static final long CLOCK_COLOR_CACHE_MS = 1000; // Recalculate every 1 second

    private long recordingStartTime;
    private long videoBitrate;

    private double latitude;
    private double longitude;

    private Handler handlerClock = new Handler();
    private Runnable updateInfoRunnable;
    private Runnable updateClockRunnable; // Declare here
    
    private DebouncedRunnable debouncedStartRecording;
    private DebouncedRunnable debouncedStopRecording;

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
    private ImageView ivEstimateIcon;
    private TextView tvSpaceTitle;
    private TextView tvSpaceSubtitle;
    private com.fadcam.ui.utils.StorageProgressRingView storageProgressRing;
    // inline total will be rendered in tvSpaceTitle using spans
    
    /**
     * Changed from private to protected to allow FadRecHomeFragment to update timer displays.
     */
    protected TextView tvElapsedTitle;
    protected TextView tvElapsedSubtitle;
    protected TextView tvRemainingTitle;
    protected TextView tvRemainingSubtitle;
    private MaterialCardView cardElapsedHero;
    private ConstraintLayout homeRootLayout;
    private ImageView tvElapsedStateIcon;
    private ImageView ivElapsedAccent;
    private LinearLayout layoutElapsedContent;
    private LinearLayout layoutElapsedMetaRow;
    private View rowStorageAvailable;
    private View rowEstimateTime;
    private boolean cameraRowUiInitialized = false;
    private View layoutCards;
    private View layoutCardRailSection;
    private View leftPanel;
    private View rightPanel;
    private View cardRailTogglePortrait;
    private View cardRailToggleLandscape;
    private ImageView ivCardRailTogglePortrait;
    private ImageView ivCardRailToggleLandscape;
    
    private ImageView btnHamburgerMenu;
    private View hamburgerBadgeDot;
    private ImageView ivAppTitle; // App logo in header
    private TextView tvPreviewPlaceholder;
    private TextView tvPreviewHint; // Hint text for long press to enable preview
    
    /**
     * Changed from private to protected to allow FadRecHomeFragment to access in overridden methods.
     */
    protected MaterialButton buttonStartStop;
    protected MaterialButton buttonPauseResume;
    protected Button buttonCamSwitch;
    protected MaterialButton buttonTorchSwitch;
    protected MaterialButton buttonMirrorSwitch;
    
    private boolean isPreviewEnabled = true;
    private boolean isPreviewOnlyActive = false;
    private boolean isPreviewOnlyStartPending = false;
    private long lastPreviewOnlyToggleDispatchMs = 0L;
    private static final long PREVIEW_ONLY_TOGGLE_DEBOUNCE_MS = 500L;
    private long previewOnlyStartPendingDeadlineMs = 0L;
    private android.view.ScaleGestureDetector previewScaleGestureDetector;
    private float previewPinchZoomRatio = 1.0f;
    private long previewZoomDispatchMs = 0L;
    private float previewUiScale = 1.0f;
    private float previewUiPanX = 0f;
    private float previewUiPanY = 0f;
    private boolean isPanningPreview = false;
    private float previewLastTouchX = 0f;
    private float previewLastTouchY = 0f;
    private boolean previewLongPressTriggered = false;
    private final Handler previewLongPressHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingPreviewLongPressRunnable;
    private final Handler previewOnlyStartHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingPreviewOnlyStartTimeoutRunnable;
    private View containerPreviewZoomHud;
    private TextView textPreviewZoomHud;
    private View containerPreviewZoomMap;
    private View viewPreviewZoomMapViewport;
    private MaterialButton btnPreviewZoomReset;

    private View cardPreview;
    private View btnFullscreenPreview;
    private View btnCaptureShotPreview;
    private boolean isLaunchingPhotoCapture = false;
    private Vibrator vibrator;
    private ImageView ivBubbleBackground; // Rotating bubble shape behind camera icon (legacy, null after layout update)
    private android.view.animation.Animation bubbleRotationAnimation; // Animation instance to preserve state across tab switches

    // ── Preview Avatar (replaces bubble + CCTV icon) ──────────────────────────
    private ImageView ivPreviewAvatar;
    private ImageView ivPreviewEyeOverlay;
    private View zzzHomeBadgeGroup;
    private View tvHomeZ1, tvHomeZ2, tvHomeZ3;
    private ValueAnimator homeBreathingAnimator;
    private ObjectAnimator homeAvatarFloatAnim;  // gentle bob up/down for sleeping avatar
    private final List<ObjectAnimator> homeFloatingZAnims = new ArrayList<>();
    private final Handler homeBlinkHandler = new Handler(Looper.getMainLooper());
    private Runnable homeBlinkRunnable;
    private final Random homeBlinkRandom = new Random();
    private boolean homeAvatarLastEnabled = false;

    // ── Header Logo Avatar blink loop ────────────────────────────────────────
    private final Handler headerBlinkHandler = new Handler(Looper.getMainLooper());
    private Runnable headerBlinkRunnable;
    private final Random headerBlinkRandom = new Random();
    /** When true, the next updatePreviewVisibility() call will animate the avatar↔preview crossfade. */
    private boolean animateNextPreviewTransition = false;
    /**
     * True while the iris-close (Preview→Avatar) circular reveal is in flight.
     * During this window, {@link #updatePreviewVisibility()} must NOT touch
     * textureView or flAvatar — the service's "recording stopped" broadcast fires
     * ~50 ms after the animation starts and would otherwise kill it mid-reveal.
     */
    private boolean isPreviewCloseAnimating = false;
    /** True while the Avatar→Preview iris-open animation is playing (~980 ms). Blocks re-entrant
     *  updatePreviewVisibility() calls (e.g. the service's "started" broadcast arriving ~50 ms
     *  later) from resetting textureView alpha or avatar visibility mid-animation. */
    private boolean isPreviewOpenAnimating = false;
    /** True once the iris-open postDelayed fires and we are waiting for the first camera frame
     *  to arrive in onSurfaceTextureUpdated before performing the circular reveal.  This avoids
     *  revealing a blank/black TextureView during the first-start service-init hitch. */
    private volatile boolean pendingIrisOpenReveal = false;
    /**
     * Tracks whether the header logo slide-up animation has already played this
     * process session.  Static so it survives fragment recreation (e.g. switching
     * app modes) but resets on cold-start — exactly the desired UX.
     */
    private static boolean sLogoAnimationPlayed = false;
    /** Slow alpha pulse on the moon/stars ambiance ImageView while avatar is sleeping. */
    private ObjectAnimator ambianceTwinkleAnim = null;
    /** Sun wake-up ambiance ImageView (ic_wake_sun) — shown when avatar wakes before preview opens. */
    private ImageView ivWakeSun;
    // ── End Preview Avatar fields ──────────────────────────────────────────────

    private CardView cardClock;
    private TextView tvClock, tvDateEnglish, tvDateArabic;
    private LinearLayout layoutClockInner;
    private LinearLayout layoutClockContent;
    protected String latestElapsedDisplay = "00:00";

    // FragmentActivity tipres = requireActivity();
    private String[] tips;

    private int currentTipIndex = 0;

    /** Animated value showing video count (label is static in layout) */
    private TextView tvVideoCount;
    /** Animated value showing total recording size (label is static in layout) */
    private TextView tvVideoSize;
    /** Static view showing "/ XX.X GB" total device storage after tvSpaceTitle */
    private TextView tvSpaceTotal;

    // Track last known good values for stats to prevent 0-value display during scans
    /**
     * Last known video stats for fallback when 0 is detected (race condition)
     */
    private long lastKnownVideoCount = 0;
    private long lastKnownSizeMB = 0;

    /**
     * Last animated stats for smooth transitions
     */
    private int lastAnimatedVideoCount = 0;
    private long lastAnimatedSizeMB = 0;

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
    // deleted WakeLock field
    // private static final String PREF_FIRST_LAUNCH = "first_launch";

    private RecordingState recordingState = RecordingState.NONE;

    private BroadcastReceiver broadcastOnRecordingStarted;
    private BroadcastReceiver broadcastOnRecordingResumed;
    private BroadcastReceiver broadcastOnRecordingPaused;
    private BroadcastReceiver broadcastOnRecordingStopped;
    private BroadcastReceiver broadcastOnRecordingStateCallback;
    private BroadcastReceiver broadcastOnPreviewOnlyStarted;
    private BroadcastReceiver broadcastOnPreviewOnlyStopped;
    private BroadcastReceiver segmentCompleteStatsReceiver; // For segment completion to update stats

    // Camera switch broadcast receivers
    private BroadcastReceiver broadcastOnCameraSwitchStarted;
    private BroadcastReceiver broadcastOnCameraSwitchComplete;
    private BroadcastReceiver broadcastOnCameraSwitchFailed;
    private BroadcastReceiver broadcastOnMirrorChanged;
    private BroadcastReceiver broadcastOnZoomChanged;
    private BroadcastReceiver broadcastOnExposureChanged;
    private volatile boolean isCameraSwitchInProgress = false;
    private volatile long lastCameraSwitchCompleteTime = 0; // Debounce duplicate toasts
    private volatile long lastCameraSwitchTime = 0; // Track when switch completed to prevent button disable

    // ── Dual Camera ────────────────────────────────────────────────────
    /** {@code true} while a dual-camera recording session is active. */
    private volatile boolean isDualRecordingActive = false;
    /**
     * Guard flag: set before launching fullscreen preview to prevent
     * {@link #onPause()} from sending a null surface to the service.
     */
    private volatile boolean isLaunchingFullscreen = false;

    /**
     * Guard flag: set when returning from fullscreen to prevent
     * {@link #onSurfaceTextureDestroyed()} from sending null surface
     * during texture recreation. Cleared when new surface is ready.
     */
    private volatile boolean isReturningFromFullscreen = false;
    private BroadcastReceiver broadcastOnDualRecordingStarted;
    private BroadcastReceiver broadcastOnDualRecordingStopped;
    private BroadcastReceiver broadcastOnDualRecordingPaused;
    private BroadcastReceiver broadcastOnDualRecordingResumed;
    private BroadcastReceiver broadcastOnDualCameraError;
    private BroadcastReceiver broadcastOnDualCamerasSwapped;
    private boolean isDualBroadcastsRegistered = false;

    private BroadcastReceiver cameraResourceAvailabilityReceiver;
    private boolean isCameraResourceAvailabilityReceiverRegistered = false;
    private boolean areCameraResourcesAvailable = true; // Default to true
    private boolean hasRequiredRecordingHardware = true;
    private boolean isHardwareSupportToastShown = false;

    // buttonTorchSwitch declaration moved to line 195 (changed to protected)

    private CameraManager cameraManager;
    private String cameraId;
    private boolean isTorchOn = false;

    private BroadcastReceiver torchReceiver;

    private Surface textureViewSurface; // To hold the Surface from TextureView

    // --- Fields Needed for Stats Update ---
    private SharedPreferencesManager sharedPreferencesManager;
    // Listener for realtime preference updates (camera/resolution/fps)
    private SharedPreferences.OnSharedPreferenceChangeListener prefsListener;
    private BroadcastReceiver recordingCompleteReceiver;
    // Cache last-known available bytes for custom storage (SD card / SAF) when we
    // cannot probe it directly
    private long lastKnownCustomAvailableBytes = -1;
    // private boolean isStatsReceiverRegistered = false; // This seemed to be for
    // the general recordingCompleteReceiver
    private boolean isSegmentCompleteStatsReceiverRegistered = false;


    // important
    private void requestEssentialPermissions() {
        FLog.d(
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
                FLog.d(
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

    // deleted acquireWakeLock and releaseWakeLock methods

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
        if (previewUiScale > 1.001f) {
            FLog.d(TAG, "Ignoring long-press toggle while zoom/pan gesture mode is active");
            return;
        }
        // Unified Card Bounce Animation (Down then Up)
        AnimatorSet cardBounceAnim = new AnimatorSet();
        ObjectAnimator scaleDownX = ObjectAnimator.ofFloat(
            cardPreview,
            "scaleX",
            0.97f
        );
        ObjectAnimator scaleDownY = ObjectAnimator.ofFloat(
            cardPreview,
            "scaleY",
            0.97f
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

                    if (handleModeSpecificPreviewLongPress()) {
                        return;
                    }

                    if (!isRecordingOrPaused()) {
                        if (!isAdded() || getContext() == null) {
                            return;
                        }
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastPreviewOnlyToggleDispatchMs < PREVIEW_ONLY_TOGGLE_DEBOUNCE_MS) {
                            FLog.d(TAG, "Ignoring preview-only toggle due to debounce window");
                            return;
                        }
                        lastPreviewOnlyToggleDispatchMs = now;

                        CameraType cameraType = sharedPreferencesManager.getCameraSelection();
                        if (cameraType == null || cameraType.isDual()) {
                            Utils.showQuickToast(getContext(), R.string.preview_dual_not_supported);
                            return;
                        }
                        Intent previewIntent = new Intent(getContext(), RecordingService.class);
                        boolean shouldStopPreviewOnly = isPreviewOnlyActive
                                && textureView != null
                                && textureView.getVisibility() == View.VISIBLE;
                        previewIntent.setAction(shouldStopPreviewOnly
                                ? Constants.INTENT_ACTION_STOP_PREVIEW_ONLY
                                : Constants.INTENT_ACTION_START_PREVIEW_ONLY);
                        if (!shouldStopPreviewOnly) {
                            isPreviewOnlyStartPending = true;
                            previewOnlyStartPendingDeadlineMs = SystemClock.elapsedRealtime() + 1800L;
                            isPreviewEnabled = true;
                            resetTextureView(); // avoid stale last frame from previous recording session
                            ensureTextureViewSurfaceForPreviewStart();
                            if (textureViewSurface != null && textureViewSurface.isValid()) {
                                previewIntent.putExtra("SURFACE", textureViewSurface);
                                previewIntent.putExtra("SURFACE_WIDTH", textureView.getWidth());
                                previewIntent.putExtra("SURFACE_HEIGHT", textureView.getHeight());
                            }
                            schedulePreviewOnlySurfacePushBurst();
                            schedulePreviewOnlyStartTimeout();
                        } else {
                            clearPreviewOnlyPendingState(true);
                            isPreviewOnlyActive = false;
                        }
                        ServiceStartPolicy.startRecordingAction(requireContext(), previewIntent);
                        if (!shouldStopPreviewOnly) {
                            schedulePreviewOnlySurfacePushBurst();
                        }
                        animateNextPreviewTransition = true;
                        updatePreviewVisibility();
                        return;
                    }

                    // Core logic: toggle preview, update UI, save state (runs AFTER card bounce)
                    boolean wasEnabled = isPreviewEnabled;
                    isPreviewEnabled = !isPreviewEnabled;
                    animateNextPreviewTransition = true;
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
                            FLog.d(
                                TAG,
                                "Preview enabled (post-anim): TextureView available, sending surface to service."
                            );
                            updateServiceWithCurrentSurface(textureViewSurface);
                        } else {
                            FLog.d(
                                TAG,
                                "Preview enabled (post-anim): TextureView not yet available, will send surface on callback."
                            );
                            updateServiceWithCurrentSurface(null);
                        }
                    } else {
                        FLog.d(
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
        if (!isAdded() || textureView == null || tvPreviewPlaceholder == null) {
            FLog.e(
                TAG,
                "updatePreviewVisibility: Fragment not attached or views null"
            );
            return;
        }

        if (usesModeSpecificPreviewBehavior()) {
            updateModeSpecificPreviewVisibility();
            return;
        }

        // During the iris-close (Preview→Avatar) circular reveal (~480ms), ALL updatePreviewVisibility
        // calls must be ignored.  The service's "recording stopped" broadcast fires ~50ms into the
        // animation and would otherwise hide textureView (losing the camera background) and make
        // flAvatar visible immediately — cancelling the reveal mid-flight.
        if (isPreviewCloseAnimating) return;
        if (isPreviewOpenAnimating) return;

        if (isRecording() || isPaused() || isPreviewOnlyActive) {
            if (isPreviewEnabled) {
                // Show preview
                textureView.setVisibility(View.VISIBLE);
                // If animating, keep textureView transparent until the circular reveal plays
                if (animateNextPreviewTransition) textureView.setAlpha(0f);
                tvPreviewPlaceholder.setVisibility(View.GONE);
                setHintVisibilityAnimated(false);
                applyPreviewTransform();
                FLog.d(TAG, "Preview enabled and recording - showing preview");

                // Ensure surface is sent to service
                if (
                    textureViewSurface != null &&
                    textureViewSurface.isValid() &&
                    (isRecordingOrPaused() || isPreviewOnlyActive)
                ) {
                    updateServiceWithCurrentSurface(textureViewSurface);
                }
            } else {
                // Hide preview but show hint text (using layered icons with hint)
                textureView.setVisibility(View.INVISIBLE);
                resetPreviewTransform();
                tvPreviewPlaceholder.setVisibility(View.GONE); // Keep hidden
                setHintVisibilityAnimated(true);
                FLog.d(
                    TAG,
                    "Preview disabled but recording - showing hint text"
                );

                // Send null surface to service
                updateServiceWithCurrentSurface(null);
            }
        } else {
            boolean pendingStillFresh = isPreviewOnlyStartPending
                    && SystemClock.elapsedRealtime() < previewOnlyStartPendingDeadlineMs;
            if (isPreviewOnlyStartPending && !pendingStillFresh) {
                isPreviewOnlyStartPending = false;
                previewOnlyStartPendingDeadlineMs = 0L;
            }
            if (pendingStillFresh && isPreviewEnabled) {
                textureView.setVisibility(View.VISIBLE);
                if (animateNextPreviewTransition) textureView.setAlpha(0f);
                tvPreviewPlaceholder.setVisibility(View.GONE);
                if (tvPreviewHint != null) tvPreviewHint.setText(getPreviewEnableHintResId());
                setHintVisibilityAnimated(true);
                schedulePreviewOnlyStartTimeout();
                if (textureViewSurface != null && textureViewSurface.isValid()) {
                    updateServiceWithCurrentSurface(textureViewSurface, textureView.getWidth(), textureView.getHeight());
                }
                updateFullscreenButtonVisibility();
                return;
            }
            // Not recording, keep placeholder and hint hidden (using layered icons only)
            textureView.setVisibility(View.INVISIBLE);
            resetPreviewTransform();
            tvPreviewPlaceholder.setVisibility(View.GONE); // Keep hidden
            if (tvPreviewHint != null) tvPreviewHint.setText(getPreviewEnableHintResId());
            setHintVisibilityAnimated(true);
            FLog.d(TAG, "Not recording - showing hint because area is reactive");
        }

        // Show/hide the preview avatar container (complement TextureView visibility)
        View flAvatar = getView() != null ? getView().findViewById(R.id.fl_preview_avatar) : null;
        if (flAvatar != null) {
            boolean livePreviewShowing = textureView != null && textureView.getVisibility() == View.VISIBLE;
            if (animateNextPreviewTransition) {
                animateNextPreviewTransition = false;
                boolean avatarCurrentlyVisible = flAvatar.getVisibility() == View.VISIBLE;
                if (livePreviewShowing) {
                    // Ensure avatar is in a fully visible/reset state before wake animation,
                    // even if it was previously hidden (INVISIBLE) by a prior enable cycle.
                    if (!avatarCurrentlyVisible) {
                        flAvatar.setVisibility(View.VISIBLE);
                        flAvatar.setAlpha(1f);
                        flAvatar.setScaleX(1f);
                        flAvatar.setScaleY(1f);
                        flAvatar.setEnabled(true);
                    }
                    // Guard: isPreviewOpenAnimating blocks the service's "started" broadcast
                    // (~50ms later) from calling updatePreviewVisibility() and overwriting
                    // textureView alpha / avatar visibility mid-animation.
                    isPreviewOpenAnimating = true;
                    // Step 1: Wake the avatar (wake-up AVD ~420ms: eyes open + brighten)
                    applyHomeAvatarState(true, true);
                    // Step 2: After wake animation, shrink avatar out + iris-open camera
                    final View capturedAvatar = flAvatar;
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (!isAdded() || capturedAvatar == null || textureView == null) return;
                        capturedAvatar.animate().cancel();
                        capturedAvatar.animate()
                            .alpha(0f).scaleX(0.72f).scaleY(0.72f)
                            .setDuration(280)
                            .setInterpolator(new android.view.animation.AccelerateInterpolator())
                            .withEndAction(() -> {
                                // Use INVISIBLE (not GONE) so the view retains its layout
                                // dimensions — required for createCircularReveal on the
                                // reverse (Preview→Avatar) path.  Disable to avoid
                                // intercepting touches on the preview beneath it.
                                capturedAvatar.setVisibility(View.INVISIBLE);
                                capturedAvatar.setEnabled(false);
                                capturedAvatar.setAlpha(1f);
                                capturedAvatar.setScaleX(1f);
                                capturedAvatar.setScaleY(1f);
                            }).start();
                        // Iris-open circular reveal on TextureView.
                        // We wait for the first actual camera frame via onSurfaceTextureUpdated
                        // rather than revealing immediately — avoids showing a blank TextureView
                        // during the first-start service-init hitch.
                        pendingIrisOpenReveal = true;
                        // Fallback: if no camera frame arrives within 2.5 s, reveal anyway.
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            if (pendingIrisOpenReveal && isAdded()) {
                                pendingIrisOpenReveal = false;
                                performIrisOpenReveal();
                            }
                        }, 2500);
                    }, 480);
                } else if (!livePreviewShowing && !avatarCurrentlyVisible) {
                    // Preview → Avatar: mirror of the iris-open animation.
                    //
                    // IMPORTANT: textureView renders ON TOP of fl_preview_avatar in the
                    // FrameLayout (declared after it in XML).  Running createCircularReveal
                    // on fl_preview_avatar would be completely hidden.  Instead, contract
                    // textureView from maxRadius→0 — the camera feed "closes like an iris",
                    // revealing the sleeping avatar underneath.
                    //
                    // Guard: isPreviewCloseAnimating prevents the service's "stopped"
                    // broadcast (~50ms later) from calling updatePreviewVisibility() and
                    // hiding textureView/replacing flAvatar mid-animation.
                    isPreviewCloseAnimating = true;

                    final View capturedFlAvatar = flAvatar;

                    // Show avatar in AWAKE state before fl_preview_avatar becomes visible —
                    // keeps the "awake → asleep" narrative: the avatar wakes for the preview,
                    // then the preview closes and the avatar falls asleep again.
                    stopBubbleRotation();
                    applyHomeAvatarState(true, false); // instant awake (no animation)

                    // Show fl_preview_avatar now (on bottom layer) so avatar is ready
                    capturedFlAvatar.setEnabled(true);
                    capturedFlAvatar.setAlpha(1f);
                    capturedFlAvatar.setScaleX(1f);
                    capturedFlAvatar.setScaleY(1f);
                    capturedFlAvatar.setVisibility(View.VISIBLE);

                    // IMPORTANT: line 809 already set textureView INVISIBLE (the normal "not recording"
                    // path runs before we get here).  Restore it to VISIBLE now so the camera's frozen
                    // last frame is visible for the iris-close animation to contract over.
                    if (textureView != null) {
                        textureView.setVisibility(View.VISIBLE);
                        textureView.setAlpha(1f);
                    }

                    // Contract textureView from maxRadius → 0 (iris-close).
                    if (textureView != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        final int tvW = textureView.getWidth();
                        final int tvH = textureView.getHeight();
                        if (tvW > 0) {
                            final int cx = tvW / 2, cy = tvH / 2;
                            final float maxR = (float) Math.hypot(cx, cy);
                            Animator irisClose = android.view.ViewAnimationUtils.createCircularReveal(
                                    textureView, cx, cy, maxR, 0f);
                            irisClose.setDuration(480);
                            irisClose.setInterpolator(new android.view.animation.AccelerateInterpolator(1.3f));
                            irisClose.addListener(new AnimatorListenerAdapter() {
                                @Override public void onAnimationEnd(Animator a) {
                                    isPreviewCloseAnimating = false;
                                    if (textureView != null) textureView.setVisibility(View.INVISIBLE);
                                    if (!isAdded()) return;
                                    // Brief hold so user sees the awake avatar before sleeping.
                                    // ~650ms matches the sun spin-in duration (520ms + small buffer).
                                    final View anchor = ivPreviewAvatar;
                                    if (anchor != null) {
                                        anchor.postDelayed(() -> {
                                            if (isAdded()) applyHomeAvatarState(false, true);
                                        }, 650);
                                    } else {
                                        applyHomeAvatarState(false, true);
                                    }
                                }
                            });
                            irisClose.start();
                        } else {
                            isPreviewCloseAnimating = false;
                            textureView.setVisibility(View.INVISIBLE);
                        }
                    } else {
                        // Fallback: fade textureView out
                        if (textureView != null) {
                            textureView.animate().alpha(0f).setDuration(340)
                                .withEndAction(() -> {
                                    isPreviewCloseAnimating = false;
                                    textureView.setVisibility(View.INVISIBLE);
                                    textureView.setAlpha(1f);
                                    if (isAdded()) applyHomeAvatarState(false, true);
                                }).start();
                        } else {
                            isPreviewCloseAnimating = false;
                        }
                    }
                } else {
                    // Neither animated condition fired (e.g. state is already correct).
                    // Use INVISIBLE (not GONE) so the view keeps its layout dimensions
                    // for a future createCircularReveal on close.
                    if (livePreviewShowing) {
                        flAvatar.setVisibility(View.INVISIBLE);
                        flAvatar.setEnabled(false);
                    } else {
                        flAvatar.setVisibility(View.VISIBLE);
                        flAvatar.setEnabled(true);
                    }
                }
            } else {
                // Non-animated path: same INVISIBLE rule.
                if (livePreviewShowing) {
                    flAvatar.setVisibility(View.INVISIBLE);
                    flAvatar.setEnabled(false);
                } else {
                    flAvatar.setVisibility(View.VISIBLE);
                    flAvatar.setEnabled(true);
                }
            }
        }

        // Show fullscreen button only when preview is active and recording
        updateFullscreenButtonVisibility();
    }

    /**
     * Performs the iris-open circular reveal on textureView.
     * Called from onSurfaceTextureUpdated (first camera frame) or from a timeout fallback,
     * so the reveal only starts when the camera feed is actually available.
     */
    private void performIrisOpenReveal() {
        if (textureView == null || !isAdded()) {
            isPreviewOpenAnimating = false;
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            int cx = textureView.getWidth() / 2;
            int cy = textureView.getHeight() / 2;
            float maxR = (float) Math.hypot(cx, cy);
            Animator reveal = android.view.ViewAnimationUtils.createCircularReveal(
                    textureView, cx, cy, 0f, maxR);
            reveal.setDuration(500);
            reveal.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
            reveal.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator a) {
                    if (textureView != null) textureView.setAlpha(1f);
                }
                @Override
                public void onAnimationEnd(Animator a) {
                    isPreviewOpenAnimating = false;
                }
            });
            reveal.start();
        } else {
            textureView.animate().alpha(1f).setDuration(450)
                    .withEndAction(() -> isPreviewOpenAnimating = false).start();
        }
    }

    /**
     * Smoothly shows or hides the preview hint text with a fade + scale animation.
     * Replaces bare setVisibility calls so the label appears/disappears in vibe with the
     * preview iris-reveal and avatar transitions.
     *
     * @param show true to fade the hint in, false to fade it out.
     */
    private void setHintVisibilityAnimated(boolean show) {
        if (tvPreviewHint == null) return;
        if (show) {
            if (tvPreviewHint.getVisibility() == View.VISIBLE && tvPreviewHint.getAlpha() >= 0.99f) return;
            tvPreviewHint.animate().cancel();
            tvPreviewHint.setAlpha(0f);
            tvPreviewHint.setScaleX(0.88f);
            tvPreviewHint.setScaleY(0.88f);
            tvPreviewHint.setVisibility(View.VISIBLE);
            tvPreviewHint.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(380)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                .start();
        } else {
            if (tvPreviewHint.getVisibility() != View.VISIBLE) return;
            tvPreviewHint.animate().cancel();
            tvPreviewHint.animate()
                .alpha(0f).scaleX(0.88f).scaleY(0.88f)
                .setDuration(260)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    if (tvPreviewHint != null) {
                        tvPreviewHint.setVisibility(View.GONE);
                        tvPreviewHint.setAlpha(1f);
                        tvPreviewHint.setScaleX(1f);
                        tvPreviewHint.setScaleY(1f);
                    }
                })
                .start();
        }
    }

    private void resetTimers() {
        // Avoid blindly resetting if we are in the middle of an existing recording.
        if (isRecording() || isPaused()) {
            FLog.w(
                TAG,
                "resetTimers: Suppressed reset while recording. recordingStartTime=" + recordingStartTime
            );
            return;
        }
        recordingStartTime = SystemClock.elapsedRealtime();
        FLog.d(TAG, "resetTimers: Set fresh recordingStartTime=" + recordingStartTime);
        updateStorageInfo();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.init(requireContext());

        FLog.d(TAG, "[FragmentLifecycle] onCreate: HomeFragment being created, savedInstanceState=" + (savedInstanceState == null ? "null" : "exists"));

        // Request essential permissions on every launch
        // requestEssentialPermissions(); // <-- Disabled, handled in onboarding only

        sharedPreferencesManager = SharedPreferencesManager.getInstance(
            requireContext()
        );

        // Note: No need to restore recordingStartTime here.
        // Service is the single source of truth and will broadcast it via
        // BROADCAST_ON_RECORDING_STATE_CALLBACK when we call fetchRecordingState() in onResume().

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
        FLog.d(TAG, "onStart: HomeFragment");
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

        // Evaluate hardware capabilities (important for watches with limited hardware).
        hasRequiredRecordingHardware = hasRequiredRecordingHardware();
        areCameraResourcesAvailable = hasRequiredRecordingHardware;
        isHardwareSupportToastShown = false;

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
                FLog.e(
                    TAG,
                    "Failed to register segment complete stats receiver",
                    e
                );
            }
        } else {
            FLog.d(
                TAG,
                "Skipping segment stats receiver registration - fragment not in valid state"
            );
        }

        // registerBroadcastReceivers -----
        // Camera resource availability registration is now handled in
        // registerBroadcastReceivers()

        // Ensure we have the latest state
        fetchRecordingState();
        updateStartButtonAvailability();
        if (!hasRequiredRecordingHardware) {
            showUnsupportedHardwareMessage();
        }

        // ----- Update Check Bottom Sheet Start -----
        if (
            com.fadcam.SharedPreferencesManager.isAutoUpdateCheckEnabled(
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
                                            FLog.e(
                                                TAG,
                                                "Fragment not associated with fragment manager",
                                                e
                                            );
                                        }
                                    } else {
                                        FLog.d(
                                            TAG,
                                            "Update check: Fragment not in valid state to show bottom sheet"
                                        );
                                    }
                                });
                        }
                    } catch (Exception e) {
                        FLog.e(TAG, "Update check failed", e);
                    }
                });
            }
        } else {
            FLog.i(
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
        } else if (currentCameraType.equals(CameraType.DUAL_PIP)) {
            currentCameraTypeString = getString(R.string.button_settings_cam_dual);
        }

        // Toast.makeText(getContext(), this.getString(R.string.current_camera) + ": " +
        // currentCameraTypeString.toLowerCase(), Toast.LENGTH_SHORT).show();
    }

    protected void fetchRecordingState() {
        // ── Dual Camera: if service is running, restore dual state ─────
        if (isMyServiceRunning(DualCameraRecordingService.class)) {
            FLog.d(TAG, "fetchRecordingState: DualCameraRecordingService is running");
            isDualRecordingActive = true;
            if (recordingState == RecordingState.NONE) {
                recordingState = RecordingState.IN_PROGRESS;
                setUIForRecordingActive();
            }
            return;
        } else {
            // Service is not running — clear dual flag if it was set
            if (isDualRecordingActive) {
                FLog.d(TAG, "fetchRecordingState: DualCameraRecordingService not running, clearing dual flag");
                isDualRecordingActive = false;
            }
        }

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

                // Note: Timer value is read directly from SharedPreferences by update methods
                // No need to extract from broadcast - service writes to SharedPreferences

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
                    case WAITING_FOR_CAMERA:
                        // Camera taken by another app - recording continues with black frames
                        // Show appropriate UI state
                        setUIForWaitingForCamera();
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
                // Note: Timer value is written to SharedPreferences by service
                // Fragment reads it directly when calculating elapsed time
                FLog.d(TAG, "✅ BROADCAST_ON_RECORDING_STARTED received");

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
                        FLog.d(
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
                                    FLog.d(
                                        TAG,
                                        "BROADCAST_ON_RECORDING_STARTED: Delayed surface creation"
                                    );
                                } else {
                                    FLog.d(
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
        FLog.d(TAG, "📍 onRecordingStarted(toast=" + toast + ") - Timer managed by service in SharedPreferences");
        // Note: Timer value (recordingStartTime) is managed by RecordingService
        // Fragment reads it from SharedPreferences when calculating elapsed time
        // This method only updates UI state

        recordingState = RecordingState.IN_PROGRESS;
        setUIForRecordingActive();
        if (toast) Utils.showQuickToast(
            requireContext(),
            R.string.video_recording_started
        );
        // WakeLock moved to service
        
        // OPTIMIZATION: Disable debug logging during recording to save CPU/battery
        Log.setRecordingActive(true);
        
        updateStats(); // Update stats when recording starts

        // Always start the info update timer to keep elapsed time current
        startUpdatingInfo();

        // Respect user's preview preference; only flag animation for preview transitions
        animateNextPreviewTransition = true;
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
                FLog.d(
                    TAG,
                    "onRecordingStarted: Created new surface from available TextureView"
                );

                // Send the surface to the service
                updateServiceWithCurrentSurface(textureViewSurface);
            } else {
                // If no SurfaceTexture is available, reset the TextureView to trigger creation
                FLog.d(
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
                            FLog.d(
                                TAG,
                                "onRecordingStarted: Created surface after delay"
                            );
                        }
                    },
                    100
                );
            }
        }
    }

    private void onRecordingResumed() {
        recordingState = RecordingState.IN_PROGRESS;

        buttonPauseResume.setIcon(
            AppCompatResources.getDrawable(
                requireContext(),
                R.drawable.pause_rounded
            )
        );
        buttonPauseResume.setEnabled(true);

        applyButtonTransition(buttonStartStop, getString(R.string.button_stop),
                AppCompatResources.getDrawable(requireContext(), R.drawable.stop_rounded), () -> {
            buttonStartStop.setBackgroundTintList(
                ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.button_stop)
                )
            );
            buttonStartStop.setEnabled(true);
        });
        updateStartStopButtonForFoldedState();

        // Re-enable camera switch button (may have been disabled during WAITING_FOR_CAMERA)
        if (buttonCamSwitch != null) {
            buttonCamSwitch.setEnabled(true);
            buttonCamSwitch.setAlpha(1.0f);
        }

        // Re-enable torch button (may have been disabled during WAITING_FOR_CAMERA)
        if (buttonTorchSwitch != null) {
            buttonTorchSwitch.setEnabled(true);
            buttonTorchSwitch.setAlpha(1.0f);
        }

        // Restore preview visibility — respect the user's saved preference.
        // Do NOT force-enable: if the user explicitly disabled preview before pausing,
        // it should remain disabled after resuming.
        updatePreviewVisibility();

        startUpdatingInfo();
        updateStorageInfo();
    }

    private void onRecordingPaused() {
        recordingState = RecordingState.PAUSED;

        buttonPauseResume.setIcon(
            AppCompatResources.getDrawable(requireContext(), R.drawable.play_button_rounded)
        );
        buttonPauseResume.setEnabled(true);

        // Keep camera switch button ENABLED for live switching even when paused
        // Don't disable it here

        applyButtonTransition(buttonStartStop, getString(R.string.button_stop),
                AppCompatResources.getDrawable(requireContext(), R.drawable.stop_rounded), () -> {
            buttonStartStop.setBackgroundTintList(
                ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.button_stop)
                )
            );
        });
        updateStartStopButtonForFoldedState();
        updateStorageInfo();
        updateElapsedHeroAppearance();
    }

    // --- Receiver for MediaRecorder Stopped signal ---
    /**
     * Called when the BROADCAST_ON_RECORDING_STOPPED is received,
     * indicating the MediaRecorder engine has stopped and hardware resources
     * are likely released or being released immediately by the service.
     * This method resets the UI to the IDLE state.
     */
    private void onRecordingStopped() {
        FLog.d(TAG, "onRecordingStopped broadcast received.");

        // OPTIMIZATION: Re-enable debug logging now that recording has stopped
        Log.setRecordingActive(false);

        // Clear dual recording flag
        isDualRecordingActive = false;

        // First update the recording state
        recordingState = RecordingState.NONE;
        
        // Reset timer (service will have cleared its value too)
        recordingStartTime = 0;
        FLog.d(TAG, "onRecordingStopped: Reset recordingStartTime to 0");

        // Release wake lock if it was acquired
        // WakeLock moved to service

        // Reset all buttons to idle state
        try {
            resetUIButtonsToIdleState();

            // Handle visual elements for preview and timers
            // If preview was showing during recording, animate the iris-close transition
            // (camera feed contracts like an iris, revealing the sleeping avatar).
            if (isPreviewEnabled && textureView != null
                    && textureView.getVisibility() == View.VISIBLE) {
                pendingIrisOpenReveal = false; // cancel any pending iris-open
                isPreviewOpenAnimating = false; // ensure open guard doesn't block the close anim
                animateNextPreviewTransition = true;
            }
            updatePreviewVisibility(); // Triggers iris-close or direct hide
            stopUpdatingInfo(); // Stop updating storage info

            FLog.d(
                TAG,
                "onRecordingStopped: UI reset to IDLE state. Background processing may continue."
            );
        } catch (Exception e) {
            FLog.e(TAG, "Error in onRecordingStopped", e);
        }

        // Check if the service is actually running
        if (!isMyServiceRunning(RecordingService.class)) {
            FLog.d(
                TAG,
                "RecordingService is not running, force setting recordingState to NONE"
            );
            recordingState = RecordingState.NONE;
            updateStartButtonAvailability();
        }

        // Refresh stats: invalidate cache + update Room DB with new video,
        // then update UI so the Home card shows the correct count/size.
        VideoStatsCache.invalidateStats(sharedPreferencesManager);
        com.fadcam.data.VideoIndexRepository.getInstance(requireContext()).invalidateIndex();
        updateStats(); // Will query Room DB (fast) then background delta scan updates it
    }

    // Inside HomeFragment.java

    /**
     * Safely resets the main control buttons (Start, Pause, CamSwitch, Torch)
     * and related UI elements (like preview) to their default IDLE state.
     * This means recording is stopped and the user can initiate a new one.
     * Should only be called when the fragment is attached and view is available.
     * 
     * Note: Changed from private to protected to allow FadRecHomeFragment to override
     * with screen recording-specific behavior.
     */
    protected void resetUIButtonsToIdleState() {
        FLog.d(TAG, "Reset UI to idle state");
        if (!isAdded() || getContext() == null || getView() == null) {
            FLog.w(
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
                applyButtonTransition(buttonStartStop, getString(R.string.button_start),
                        AppCompatResources.getDrawable(getContext(), R.drawable.play_button_rounded), () -> {
                    // Always use green color for start button regardless of theme
                    int btnColor = Color.parseColor("#4CAF50"); // Always green
                    buttonStartStop.setBackgroundTintList(
                        ColorStateList.valueOf(btnColor)
                    );
                    // Force enable the button when resetting to idle state, regardless of any debouncing
                    buttonStartStop.setEnabled(true);
                    buttonStartStop.setAlpha(1.0f);
                });
                updateStartStopButtonForFoldedState();
                FLog.d(TAG, "Start button force-enabled in resetUIButtonsToIdleState");
            }
            if (buttonPauseResume != null) {
                buttonPauseResume.setVisibility(View.VISIBLE);
                buttonPauseResume.setEnabled(false);
                buttonPauseResume.setAlpha(0.5f);
                buttonPauseResume.setIcon(
                    AppCompatResources.getDrawable(
                        getContext(),
                        R.drawable.pause_rounded
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
            FLog.d(
                TAG,
                "resetUIButtonsToIdleState: All UI elements reset to idle state"
            );
        } catch (Exception e) {
            FLog.e(TAG, "Error in resetUIButtonsToIdleState", e);
        }
    }

    /**
     * Updates the start button state based on camera resource availability.
     * 
     * Note: Changed from private to protected to allow FadRecHomeFragment to override
     * with screen recording-specific behavior.
     */
    protected void updateStartButtonAvailability() {
        if (!isAdded() || buttonStartStop == null) {
            return;
        }

        // Only update if we're in a state where the start button would normally be
        // enabled
        if (recordingState == RecordingState.NONE) {
            boolean shouldEnable = areCameraResourcesAvailable && hasRequiredRecordingHardware;
            buttonStartStop.setEnabled(shouldEnable);
            buttonStartStop.setAlpha(shouldEnable ? 1.0f : 0.5f);

            // Always maintain green color even when disabled
            buttonStartStop.setBackgroundTintList(
                ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            );

            if (!shouldEnable) {
                FLog.d(
                    TAG,
                    "Start button disabled due to camera resources/hardware availability"
                );
            } else {
                FLog.d(
                    TAG,
                    "Start button enabled as camera resources are available"
                );
            }
        }
    }

    private boolean hasRequiredRecordingHardware() {
        if (!isAdded() || getContext() == null) {
            return true;
        }
        try {
            PackageManager pm = requireContext().getPackageManager();
            boolean hasCamera =
                pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) ||
                pm.hasSystemFeature(PackageManager.FEATURE_CAMERA);
            boolean hasMic = pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE);
            return hasCamera && hasMic;
        } catch (Exception e) {
            FLog.w(
                TAG,
                "Unable to check required recording hardware: " + e.getMessage()
            );
            return true;
        }
    }

    private boolean ensureRecordingHardwareSupported() {
        if (!hasRequiredRecordingHardware) {
            showUnsupportedHardwareMessage();
            updateStartButtonAvailability();
            return false;
        }
        return true;
    }

    private void showUnsupportedHardwareMessage() {
        if (!isAdded() || getContext() == null || isHardwareSupportToastShown) {
            return;
        }
        isHardwareSupportToastShown = true;
        Toast.makeText(
            requireContext(),
            getString(R.string.watch_recording_hardware_not_supported),
            Toast.LENGTH_LONG
        ).show();
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
                FLog.e(
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
            FLog.w(
                TAG,
                "CameraAccessException checking flash quietly: " +
                e.getMessage()
            ); // Changed to warning
            return null;
        } catch (Exception e) {
            FLog.e(TAG, "Unexpected error checking flash quietly", e);
            return null;
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        // unregisterStatsReceiver(); // Unregister receiver when fragment stops

        FLog.e(TAG, "HomeFragment stopped");

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

        // Surface update is handled properly at line ~2828 via updateServiceWithCurrentSurface()
        // which correctly routes to RecordingService or DualCameraRecordingService
        // and includes the IS_FULLSCREEN_TRANSITION flag when needed

        unregisterCameraResourceAvailabilityReceiver();

        stopUpdatingInfo();
    }

    // --- `onResume()` Method (Simplified - focuses on fetch state) ---
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public void onResume() {
        super.onResume();
        FLog.d(TAG, "[FragmentLifecycle] onResume: Fragment resuming, isAdded=" + isAdded() + ", isVisible=" + isVisible() + ", isHidden=" + isHidden());
        if (!isAdded() || getContext() == null || getActivity() == null) {
            FLog.e(TAG, "[FragmentLifecycle] onResume: Not attached!");
            return;
        }
        
        // With hide/show navigation, onResume is called for ALL fragments when app returns
        // from background. Only perform heavy ops if this fragment is actually visible.
        if (isHidden()) {
            FLog.d(TAG, "onResume: Fragment is hidden, skipping heavy operations");
            return;
        }
        
        performResumeOperations();
    }

    /**
     * Shared logic for resuming fragment operations.
     * Called from both onResume (when visible) and onHiddenChanged(false).
     */
    private void performResumeOperations() {
        if (sharedPreferencesManager == null) {
            sharedPreferencesManager = SharedPreferencesManager.getInstance(
                requireContext()
            );
        }
        if (fragmentHelper != null) {
            fragmentHelper.syncModeSwitcherToCurrentPreference();
        }
        // Always clear stale "starting preview" pending state when Home becomes visible.
        // Service callback is the source of truth and will re-assert active preview state.
        clearPreviewOnlyPendingState(true);

        registerBroadcastReceivers(); // Centralized registration
        
        // Resume update handlers when fragment returns to foreground
        resumeUpdateHandlers();

        // Refresh header logo style in case user changed it in settings
        applyHeaderLogoStyle();

        // Note: No need to restore recordingStartTime from SharedPreferences.
        // We'll fetch state from service which will broadcast the correct start time.
        FLog.d(TAG, "onResume: Fetching recording state from service (source of truth)...");
        fetchRecordingState(); // Service will broadcast state + start time via callback

        // Re-load preview state from SharedPreferences to ensure consistency
        isPreviewEnabled = sharedPreferencesManager.isPreviewEnabled();
        syncPreviewZoomStateFromPrefs(false);
        FLog.d(
            TAG,
            "onResume: Loaded isPreviewEnabled state = " + isPreviewEnabled
        );

        // Note: recordingStartTime already restored earlier (before fetchRecordingState)
        // to prevent reset when onRecordingStarted is called

        // Update the preview visibility based on current state
        updatePreviewVisibility();

        // Critical: When resuming, send the appropriate surface to the service
        // This ensures preview shows correctly after app is minimized/restored
        if (isPreviewEnabled && (isRecordingOrPaused() || isPreviewOnlyActive || isPreviewOnlyStartPending)) {
            // Attempt to recover surface if it was lost
            if (textureViewSurface == null || !textureViewSurface.isValid()) {
                FLog.d(TAG, "onResume: Surface null/invalid, attempting recovery via resetTextureView");
                resetTextureView();
            }
            if (textureViewSurface != null && textureViewSurface.isValid()) {
                FLog.d(TAG, "onResume: Preview enabled, sending valid surface to service");
                updateServiceWithCurrentSurface(textureViewSurface);
                if (isPreviewOnlyStartPending) {
                    schedulePreviewOnlySurfacePushBurst();
                    schedulePreviewOnlyStartTimeout();
                }
            } else {
                FLog.d(TAG, "onResume: Surface still unavailable after reset — onSurfaceTextureAvailable will handle it");
                if (isPreviewOnlyStartPending && !isPreviewOnlyActive) {
                    schedulePreviewOnlyStartTimeout();
                }
            }
        } else if (!isPreviewEnabled || (!isRecordingOrPaused() && !isPreviewOnlyActive)) {
            // If preview is disabled or not recording, send null surface
            FLog.d(
                TAG,
                "onResume: Preview disabled or not recording, sending null surface"
            );
            updateServiceWithCurrentSurface(null);
        }

        FLog.d(TAG, "onResume: Triggering stats update.");
        // Always invalidate cache on resume so we query Room DB for fresh data.
        // This ensures stats reflect any changes made in Records tab or after recording.
        VideoStatsCache.invalidateStats(sharedPreferencesManager);
        updateStats();
        isTorchOn = sharedPreferencesManager.sharedPreferences.getBoolean(Constants.PREF_TORCH_STATE, false);
        updateTorchUI(isTorchOn);
        updateMirrorButtonVisibilityAndState();
        if (isRecordingOrPaused() || isPreviewOnlyActive) {
            pushFrontMirrorToService(sharedPreferencesManager.isFrontVideoMirrorEnabled());
        }

        // Start bubble rotation animation when visible (battery optimization)
        startBubbleRotation();
        isLaunchingPhotoCapture = false;
    }

    private void clearPreviewOnlyPendingState(boolean resetHintText) {
        isPreviewOnlyStartPending = false;
        previewOnlyStartPendingDeadlineMs = 0L;
        if (pendingPreviewOnlyStartTimeoutRunnable != null) {
            previewOnlyStartHandler.removeCallbacks(pendingPreviewOnlyStartTimeoutRunnable);
        }
        if (resetHintText && tvPreviewHint != null && !isRecordingOrPaused() && !isPreviewOnlyActive) {
            tvPreviewHint.setText(getPreviewEnableHintResId());
            setHintVisibilityAnimated(true);
        }
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
    private boolean isRecordingFailedReceiverRegistered = false; // Guard for double registration
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
    protected void registerBroadcastReceivers() {
        Context context = requireContext();
        if (context == null) {
            FLog.e(
                TAG,
                "registerBroadcastReceivers: Context is null, cannot register."
            );
            return;
        }
        // OPTIMIZATION: Removed generic initialization log (not needed during recording)
        // FLog.d(TAG, "Registering all HomeFragment broadcast receivers...");

        // Initialize if they are null (first time or after unregistration)
        initializeRecordingStateReceivers(); // Initializes all state-related receivers
        initializeRecordingCompleteReceiver();
        initializeTorchReceiver();
        initializeSegmentCompleteStatsReceiver();

        initializeCameraResourceAvailabilityReceiver();

        // Register them
        // method(registerBroadcastReceivers_update_isStateReceiversRegistered_flag_logic)-----
        // registerRecordingStateReceivers now returns a boolean indicating success
        isStateReceiversRegistered = registerRecordingStateReceivers(context);
        // method(registerBroadcastReceivers_update_isStateReceiversRegistered_flag_logic)-----

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerRecordingCompleteReceiver(context);
        }
        registerTorchReceiver(context);
        registerSegmentCompleteStatsReceiver(context); // Register the new one
        // Register recording failed receiver
        registerRecordingFailedReceiver(context);
        
        // Register camera switch receivers
        registerCameraSwitchReceivers(context);

        // Register dual camera broadcast receivers
        registerDualCameraBroadcastReceivers();

        // -----
        registerCameraResourceAvailabilityReceiver();

        // method(registerBroadcastReceivers_update_isStateReceiversRegistered_flag_logic)-----
        // isStateReceiversRegistered = true; // Assuming
        // registerRecordingStateReceivers sets this -> Moved up and tied to actual
        // success
        // method(registerBroadcastReceivers_update_isStateReceiversRegistered_flag_logic)-----
        // isCompletionReceiverRegistered is managed by
        // registerRecordingCompleteReceiver
        // isTorchReceiverRegistered is managed by registerTorchReceiver
        // OPTIMIZATION: Removed generic completion log (reduced from 2x/sec to 0 during recording)
        // FLog.i(
        //     TAG,
        //     "All HomeFragment broadcast receivers registration attempt finished."
        // );
    }

    private void registerRecordingFailedReceiver(Context context) {
        // Guard: Don't register twice
        if (isRecordingFailedReceiverRegistered) {
            FLog.d(TAG, "Recording failed receiver already registered, skipping.");
            return;
        }
        
        if (context == null) {
            FLog.e(TAG, "Context is null, cannot register recording failed receiver");
            return;
        }
        
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
        
        try {
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
            isRecordingFailedReceiverRegistered = true;
            FLog.d(TAG, "Recording failed receiver registered.");
        } catch (IllegalArgumentException e) {
            FLog.w(TAG, "Error registering recording failed receiver: " + e.getMessage());
            isRecordingFailedReceiverRegistered = false;
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
                    FLog.d(
                        TAG,
                        "Received BROADCAST_ON_RECORDING_STARTED (New Handler)"
                    );

                    // Get timestamp from the service with current time as fallback
                    long startTimeFromService = i.getLongExtra(
                        Constants.INTENT_EXTRA_RECORDING_START_TIME,
                        0L
                    );
                    if (startTimeFromService > 0L) {
                        recordingStartTime = startTimeFromService;
                        FLog.d(
                            TAG,
                            "initializeRecordingStateReceivers: Setting recordingStartTime=" +
                            recordingStartTime
                        );
                    }

                    // Perform non-UI actions previously in onRecordingStarted(true)
                    // WakeLock moved to service
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
            FLog.d(
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
            FLog.d(TAG, "Initialized broadcastOnRecordingResumed receiver");
        }
        // Initialize Receiver for PAUSE action
        if (broadcastOnRecordingPaused == null) {
            broadcastOnRecordingPaused = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    if (isAdded()) onRecordingPaused();
                }
            };
            FLog.d(TAG, "Initialized broadcastOnRecordingPaused receiver");
        }
        // Initialize Receiver for STOPPED action (triggers UI Idle)
        if (broadcastOnRecordingStopped == null) {
            broadcastOnRecordingStopped = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent i) {
                    if (isAdded()) onRecordingStopped();
                }
            };
            FLog.d(TAG, "Initialized broadcastOnRecordingStopped receiver");
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
                    isPreviewOnlyActive = i.getBooleanExtra(
                        Constants.EXTRA_PREVIEW_ONLY_ACTIVE,
                        false
                    );
                    FLog.i(
                        TAG,
                        "Received Service State Callback: " + serviceState + ", previewOnly=" + isPreviewOnlyActive
                    );
                    if (serviceState == null) serviceState =
                        RecordingState.NONE; // Default to NONE

                    long callbackStartTime = i.getLongExtra(
                        Constants.INTENT_EXTRA_RECORDING_START_TIME,
                        0L
                    );
                    if (callbackStartTime > 0L) {
                        recordingStartTime = callbackStartTime;
                    }

                    // *** CALL the handler method ***
                    handleServiceStateUpdate(serviceState);
                    updatePreviewVisibility();
                }
            };
            FLog.d(
                TAG,
                "Initialized broadcastOnRecordingStateCallback receiver"
            );
        }
        if (broadcastOnPreviewOnlyStarted == null) {
            broadcastOnPreviewOnlyStarted = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent i) {
                    isPreviewOnlyStartPending = false;
                    previewOnlyStartPendingDeadlineMs = 0L;
                    if (pendingPreviewOnlyStartTimeoutRunnable != null) {
                        previewOnlyStartHandler.removeCallbacks(pendingPreviewOnlyStartTimeoutRunnable);
                    }
                    isPreviewOnlyActive = true;
                    isPreviewEnabled = true;
                    updatePreviewVisibility();
                    resetTextureView();
                    if (textureViewSurface != null && textureViewSurface.isValid()) {
                        updateServiceWithCurrentSurface(textureViewSurface);
                        if (textureView != null) {
                            textureView.postDelayed(() -> {
                                if (textureViewSurface != null && textureViewSurface.isValid()) {
                                    updateServiceWithCurrentSurface(textureViewSurface);
                                }
                            }, 120L);
                        }
                    }
                }
            };
        }
        if (broadcastOnPreviewOnlyStopped == null) {
            broadcastOnPreviewOnlyStopped = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent i) {
                    isPreviewOnlyStartPending = false;
                    previewOnlyStartPendingDeadlineMs = 0L;
                    if (pendingPreviewOnlyStartTimeoutRunnable != null) {
                        previewOnlyStartHandler.removeCallbacks(pendingPreviewOnlyStartTimeoutRunnable);
                    }
                    isPreviewOnlyActive = false;
                    updatePreviewVisibility();
                    updateServiceWithCurrentSurface(null);
                    updateMainSwipeGestureGate(false);
                }
            };
        }
    }

    // ── Dual Camera Broadcast Receivers ────────────────────────────────

    /**
     * Initialises broadcast receivers for dual-camera recording events.
     * These mirror the pattern used by single-camera receivers.
     */
    private void initializeDualCameraBroadcastReceivers() {
        if (broadcastOnDualRecordingStarted == null) {
            broadcastOnDualRecordingStarted = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent i) {
                    if (!isAdded()) return;
                    FLog.d(TAG, "✅ DUAL_RECORDING_STARTED received");
                    isDualRecordingActive = true;
                    recordingState = RecordingState.IN_PROGRESS;
                    setUIForRecordingActive();
                    if (getContext() != null) {
                        Utils.showQuickToast(requireContext(), R.string.dual_recording_started);
                    }
                    vibrateTouch();
                }
            };
        }
        if (broadcastOnDualRecordingStopped == null) {
            broadcastOnDualRecordingStopped = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent i) {
                    if (!isAdded()) return;
                    FLog.d(TAG, "⏹️ DUAL_RECORDING_STOPPED received");
                    isDualRecordingActive = false;
                    onRecordingStopped();
                    if (getContext() != null) {
                        Utils.showQuickToast(requireContext(), R.string.dual_recording_stopped);
                    }
                }
            };
        }
        if (broadcastOnDualRecordingPaused == null) {
            broadcastOnDualRecordingPaused = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent i) {
                    if (!isAdded()) return;
                    FLog.d(TAG, "⏸️ DUAL_RECORDING_PAUSED received");
                    onRecordingPaused();
                }
            };
        }
        if (broadcastOnDualRecordingResumed == null) {
            broadcastOnDualRecordingResumed = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent i) {
                    if (!isAdded()) return;
                    FLog.d(TAG, "▶️ DUAL_RECORDING_RESUMED received");
                    onRecordingResumed();
                }
            };
        }
        if (broadcastOnDualCameraError == null) {
            broadcastOnDualCameraError = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent i) {
                    if (!isAdded()) return;
                    String reason = i != null ? i.getStringExtra("error_reason") : "Unknown error";
                    FLog.e(TAG, "❌ DUAL_CAMERA_ERROR: " + reason);
                    isDualRecordingActive = false;
                    onRecordingStopped();
                    if (getContext() != null) {
                        Toast.makeText(getContext(),
                                getString(R.string.dual_camera_error, reason),
                                Toast.LENGTH_LONG).show();
                    }
                }
            };
        }
        if (broadcastOnDualCamerasSwapped == null) {
            broadcastOnDualCamerasSwapped = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent i) {
                    if (!isAdded()) return;
                    FLog.d(TAG, "🔄 DUAL_CAMERAS_SWAPPED received");
                    if (getContext() != null) {
                        Utils.showQuickToast(requireContext(), R.string.dual_cameras_swapped);
                    }
                }
            };
        }
        FLog.d(TAG, "Initialized dual camera broadcast receivers");
    }

    /**
     * Registers dual-camera broadcast receivers with the application context.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerDualCameraBroadcastReceivers() {
        if (isDualBroadcastsRegistered) return;
        Context ctx = getContext();
        if (ctx == null) return;

        initializeDualCameraBroadcastReceivers();

        String[] actions = {
            Constants.BROADCAST_ON_DUAL_RECORDING_STARTED,
            Constants.BROADCAST_ON_DUAL_RECORDING_STOPPED,
            Constants.BROADCAST_ON_DUAL_RECORDING_PAUSED,
            Constants.BROADCAST_ON_DUAL_RECORDING_RESUMED,
            Constants.BROADCAST_ON_DUAL_CAMERA_ERROR,
            Constants.BROADCAST_ON_DUAL_CAMERAS_SWAPPED
        };
        BroadcastReceiver[] receivers = {
            broadcastOnDualRecordingStarted,
            broadcastOnDualRecordingStopped,
            broadcastOnDualRecordingPaused,
            broadcastOnDualRecordingResumed,
            broadcastOnDualCameraError,
            broadcastOnDualCamerasSwapped
        };

        for (int idx = 0; idx < actions.length; idx++) {
            IntentFilter filter = new IntentFilter(actions[idx]);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(receivers[idx], filter, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(receivers[idx], filter);
            }
        }
        isDualBroadcastsRegistered = true;
        FLog.d(TAG, "Dual camera broadcast receivers registered");
    }

    /**
     * Unregisters dual-camera broadcast receivers.
     */
    private void unregisterDualCameraBroadcastReceivers() {
        if (!isDualBroadcastsRegistered) return;
        Context ctx = getContext();
        if (ctx == null) return;

        BroadcastReceiver[] receivers = {
            broadcastOnDualRecordingStarted,
            broadcastOnDualRecordingStopped,
            broadcastOnDualRecordingPaused,
            broadcastOnDualRecordingResumed,
            broadcastOnDualCameraError,
            broadcastOnDualCamerasSwapped
        };
        for (BroadcastReceiver r : receivers) {
            if (r != null) {
                try { ctx.unregisterReceiver(r); } catch (IllegalArgumentException ignored) {}
            }
        }
        isDualBroadcastsRegistered = false;
        FLog.d(TAG, "Dual camera broadcast receivers unregistered");
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
            FLog.w(
                TAG,
                "handleServiceStateUpdate: Fragment not attached, ignoring state update: " +
                reportedState
            );
            return;
        }
        FLog.i(
            TAG,
            "handleServiceStateUpdate: Applying UI for Service State = " +
            reportedState
        );

        // Update the local recording state variable
        RecordingState previousState = recordingState;
        recordingState = reportedState;

        // Update UI elements based on the state
        switch (reportedState) {
            case IN_PROGRESS:
                // On a fresh recording start (not re-delivering the same state), respect user's preview
                // preference and flag the transition for the avatar → preview animation.
                if (previousState != RecordingState.IN_PROGRESS) {
                    // Only set animation flag; respect user's saved preview preference
                    animateNextPreviewTransition = true;
                }
                setUIForRecordingActive(); // Call helper to set Stop/Pause buttons etc.
                break;
            case PAUSED:
                setUIForRecordingPaused(); // Call helper to set Stop/Resume buttons etc.
                break;
            case WAITING_FOR_CAMERA:
                // Camera was taken by another app, recording continues with black frames
                // Show UI similar to paused but indicate camera is being recaptured
                setUIForWaitingForCamera();
                break;
            case NONE:
            default:
                // Service state is NONE. Recording is stopped.
                // UI *should* be idle unless background processing is happening
                // for a *previous* video. Reset UI directly here as this confirms
                // the *current* recording attempt is definitely stopped.
                if (!isPreviewOnlyActive) {
                    isPreviewOnlyStartPending = false;
                    previewOnlyStartPendingDeadlineMs = 0L;
                    if (pendingPreviewOnlyStartTimeoutRunnable != null) {
                        previewOnlyStartHandler.removeCallbacks(pendingPreviewOnlyStartTimeoutRunnable);
                    }
                }
                FLog.d(
                    TAG,
                    "handleServiceStateUpdate: Service state is NONE. Resetting UI to idle."
                );
                resetUIButtonsToIdleState();
                break;
        }
        FLog.d(
            TAG,
            "handleServiceStateUpdate finished. Fragment state is now: " +
            recordingState
        );
    }

    /** Helper to set UI elements for the ACTIVE recording state */
    private void setUIForRecordingActive() {
        if (!isAdded() || getContext() == null) return;
        FLog.d(TAG, "Setting UI to: ACTIVE Recording");
        try {
            // Ensure interaction buttons reflect recording
            applyButtonTransition(buttonStartStop, getString(R.string.button_stop),
                    AppCompatResources.getDrawable(requireContext(), R.drawable.stop_rounded), () -> {
                buttonStartStop.setEnabled(true); // Enable STOP
                buttonStartStop.setBackgroundTintList(
                    ContextCompat.getColorStateList(
                        requireContext(),
                        R.color.button_stop
                    )
                );
            });

            buttonPauseResume.setEnabled(true); // Enable PAUSE
            buttonPauseResume.setIcon(
                AppCompatResources.getDrawable(
                    requireContext(),
                    R.drawable.pause_rounded
                )
            );
            buttonPauseResume.setAlpha(1.0f); // Make fully visible when enabled
            buttonPauseResume.setBackgroundTintList(
                ContextCompat.getColorStateList(
                    requireContext(),
                    R.color.button_pause
                )
            );

            // Keep button enabled ALWAYS during recording (for live camera switching)
            // Even if state updates happen right after a switch, keep it enabled
            if (buttonCamSwitch != null) {
                buttonCamSwitch.setEnabled(true);
            }
            if (buttonTorchSwitch != null) {
                boolean hasFlash = getCameraWithFlashQuietly() != null;
                buttonTorchSwitch.setEnabled(hasFlash);
                buttonTorchSwitch.setAlpha(hasFlash ? 1.0f : 0.5f);
            }

            // Manage preview and timers
            updatePreviewVisibility();
            startUpdatingInfo();
            updateStorageInfo();
        } catch (Exception e) {
            FLog.e(TAG, "Error setting UI for Active state", e);
        }
    }

    /** Helper to set UI elements for the PAUSED recording state */
    private void setUIForRecordingPaused() {
        if (!isAdded() || getContext() == null) return;
        FLog.d(TAG, "Setting UI to: PAUSED Recording");
        try {
            // Set buttons for Paused state (Stop ON, Resume(Play) ON, Switch OFF, Torch
            // OFF)
            applyButtonTransition(buttonStartStop, getString(R.string.button_stop),
                    AppCompatResources.getDrawable(requireContext(), R.drawable.stop_rounded), () -> {
                buttonStartStop.setEnabled(true); // Enable STOP
                buttonStartStop.setBackgroundTintList(
                    ContextCompat.getColorStateList(
                        requireContext(),
                        R.color.button_stop
                    )
                );
            });

            buttonPauseResume.setEnabled(true); // Enable RESUME
            buttonPauseResume.setIcon(
                AppCompatResources.getDrawable(
                    requireContext(),
                    R.drawable.play_button_rounded
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

            // Keep button enabled ALWAYS during recording (for live camera switching)
            // Even during pause, allow camera switch to resume recording
            if (buttonCamSwitch != null) {
                buttonCamSwitch.setEnabled(true);
            }
            if (buttonTorchSwitch != null) {
                boolean hasFlash = getCameraWithFlashQuietly() != null;
                buttonTorchSwitch.setEnabled(hasFlash);
                buttonTorchSwitch.setAlpha(hasFlash ? 1.0f : 0.5f);
            }

            // Manage preview and timers
            updatePreviewVisibility();
            stopUpdatingInfo(); // Show placeholder/last frame, stop timers
            updateStorageInfo();
            updateElapsedHeroAppearance();
        } catch (Exception e) {
            FLog.e(TAG, "Error setting UI for Paused state", e);
        }
    }

    /**
     * Helper to set UI elements for the WAITING_FOR_CAMERA state.
     * This state occurs when another app has taken the camera during recording.
     * Recording continues with black frames while attempting to recapture camera.
     */
    private void setUIForWaitingForCamera() {
        if (!isAdded() || getContext() == null) return;
        FLog.d(TAG, "Setting UI to: WAITING_FOR_CAMERA (camera interrupted)");
        try {
            // Similar to PAUSED state but indicates camera is being recaptured
            applyButtonTransition(buttonStartStop, getString(R.string.button_stop),
                    AppCompatResources.getDrawable(requireContext(), R.drawable.stop_rounded), () -> {
                buttonStartStop.setEnabled(true); // Enable STOP (user can still stop recording)
                buttonStartStop.setBackgroundTintList(
                    ContextCompat.getColorStateList(
                        requireContext(),
                        R.color.button_stop
                    )
                );
            });

            // Disable pause button during camera interruption (doesn't make sense)
            buttonPauseResume.setEnabled(false);
            buttonPauseResume.setAlpha(0.5f);
            buttonPauseResume.setIcon(
                AppCompatResources.getDrawable(
                    requireContext(),
                    R.drawable.pause_rounded
                )
            );

            // Disable camera switch (camera not available)
            if (buttonCamSwitch != null) {
                buttonCamSwitch.setEnabled(false);
                buttonCamSwitch.setAlpha(0.5f);
            }
            // Disable torch (camera not available)
            if (buttonTorchSwitch != null) {
                buttonTorchSwitch.setEnabled(false);
                buttonTorchSwitch.setAlpha(0.5f);
            }

            // Keep updating timer since recording is still ongoing (black frames)
            updatePreviewVisibility();
            // Keep timer running - recording is still in progress
            if (updateInfoRunnable == null) {
                startUpdatingInfo();
            }
        } catch (Exception e) {
            FLog.e(TAG, "Error setting UI for WaitingForCamera state", e);
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
                        FLog.i(
                            TAG,
                            "<<< Received ACTION_RECORDING_COMPLETE (Processing Finished) >>>"
                        );
                        if (getView() == null) {
                            FLog.w(TAG, "Completion: View null, skip stats UI");
                            return;
                        }

                        // Invalidate caches when new video is recorded
                        VideoStatsCache.invalidateStats(
                            sharedPreferencesManager
                        );
                        com.fadcam.utils.VideoSessionCache.invalidateOnNextAccess();
                        FLog.d(
                            TAG,
                            "Invalidated video caches after recording completion"
                        );

                        try {
                            updateStats();
                            FLog.d(TAG, "Completion: Updated stats.");
                        } catch (Exception e) {
                            FLog.e(TAG, "Completion: Err update stats", e);
                        }
                    }
                }
            };
            FLog.d(TAG, "Initialized recordingCompleteReceiver");
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
                        FLog.d(
                            "TorchDebug",
                            "Received state update via Broadcast: " + isTorchOn
                        );
                        updateTorchUI(isTorchOn); // Update button visuals
                    }
                }
            };
            FLog.d(TAG, "Initialized torchReceiver");
        }
    }

    // --- Registration Helper Methods ---

    /** Helper to register all recording state change receivers */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    // method(registerRecordingStateReceivers_correct_signature_and_logic)-----
    private boolean registerRecordingStateReceivers(Context context) {
        // Ensure boolean return type
        // method(registerRecordingStateReceivers_correct_signature_and_logic)-----
        // OPTIMIZATION: Removed generic initialization log (called 2x/sec during recording)
        // FLog.d(TAG, "Registering recording state receivers...");
        
        // Guard: Don't register twice
        if (isStateReceiversRegistered) {
            // OPTIMIZATION: Removed redundant already-registered log
            // FLog.d(TAG, "Recording state receivers already registered, skipping.");
            return true;
        }
        
        if (context == null) {
            FLog.e(TAG, "Context is null in registerRecordingStateReceivers");
            // method(registerRecordingStateReceivers_return_boolean_and_check_receivers)-----
            isStateReceiversRegistered = false;
            return false;
            // method(registerRecordingStateReceivers_return_boolean_and_check_receivers)-----
        }

        // Ensure receivers are initialized
        initializeRecordingStateReceivers();

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
            // OPTIMIZATION: Removed generic receiver registration log
            // FLog.d(TAG, "Registered broadcastOnRecordingStarted");
        } else {
            allRegisteredSuccessfully = false;
            FLog.e(TAG, "broadcastOnRecordingStarted is null, not registering");
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
            // OPTIMIZATION: Removed generic receiver registration log
            // FLog.d(TAG, "Registered broadcastOnRecordingResumed");
        } else {
            allRegisteredSuccessfully = false;
            FLog.e(TAG, "broadcastOnRecordingResumed is null, not registering");
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
            // OPTIMIZATION: Removed generic receiver registration log
            // FLog.d(TAG, "Registered broadcastOnRecordingPaused");
        } else {
            allRegisteredSuccessfully = false;
            FLog.e(TAG, "broadcastOnRecordingPaused is null, not registering");
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
            // OPTIMIZATION: Removed generic receiver registration log
            // FLog.d(TAG, "Registered broadcastOnRecordingStopped");
        } else {
            allRegisteredSuccessfully = false;
            FLog.e(TAG, "broadcastOnRecordingStopped is null, not registering");
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
            // OPTIMIZATION: Removed generic receiver registration log
            // FLog.d(TAG, "Registered broadcastOnRecordingStateCallback");
        } else {
            allRegisteredSuccessfully = false;
            FLog.e(
                TAG,
                "broadcastOnRecordingStateCallback is null, not registering"
            );
        }

        IntentFilter intentFilterPreviewOnlyStarted = new IntentFilter(
            Constants.BROADCAST_ON_PREVIEW_ONLY_STARTED
        );
        if (broadcastOnPreviewOnlyStarted != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    broadcastOnPreviewOnlyStarted,
                    intentFilterPreviewOnlyStarted,
                    Context.RECEIVER_EXPORTED
                );
            } else {
                context.registerReceiver(
                    broadcastOnPreviewOnlyStarted,
                    intentFilterPreviewOnlyStarted
                );
            }
        } else {
            allRegisteredSuccessfully = false;
            FLog.e(TAG, "broadcastOnPreviewOnlyStarted is null, not registering");
        }

        IntentFilter intentFilterPreviewOnlyStopped = new IntentFilter(
            Constants.BROADCAST_ON_PREVIEW_ONLY_STOPPED
        );
        if (broadcastOnPreviewOnlyStopped != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    broadcastOnPreviewOnlyStopped,
                    intentFilterPreviewOnlyStopped,
                    Context.RECEIVER_EXPORTED
                );
            } else {
                context.registerReceiver(
                    broadcastOnPreviewOnlyStopped,
                    intentFilterPreviewOnlyStopped
                );
            }
        } else {
            allRegisteredSuccessfully = false;
            FLog.e(TAG, "broadcastOnPreviewOnlyStopped is null, not registering");
        }

        isStateReceiversRegistered = allRegisteredSuccessfully;
        if (allRegisteredSuccessfully) {
            FLog.i(
                TAG,
                "All recording state receivers registered successfully."
            );
        } else {
            FLog.w(
                TAG,
                "One or more recording state receivers failed to register because they were null."
            );
        }
        return allRegisteredSuccessfully;
        // method(registerRecordingStateReceivers_return_boolean_and_check_receivers)-----
    }

    /** Helper to register the ACTION_RECORDING_COMPLETE receiver */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerRecordingCompleteReceiver(Context context) {
        // Guard: Don't register twice
        if (isCompletionReceiverRegistered) {
            FLog.d(TAG, "Recording complete receiver already registered, skipping.");
            return;
        }
        
        if (
            context != null &&
            recordingCompleteReceiver != null
        ) {
            try {
                context.registerReceiver(
                    recordingCompleteReceiver,
                    new IntentFilter(Constants.ACTION_RECORDING_COMPLETE),
                    Context.RECEIVER_EXPORTED
                );
                isCompletionReceiverRegistered = true; // isCompletionReceiverRegistered is the correct flag here
                FLog.d(TAG, "ACTION_RECORDING_COMPLETE receiver registered.");
            } catch (IllegalArgumentException e) {
                FLog.w(TAG, "Error registering ACTION_RECORDING_COMPLETE receiver: " + e.getMessage());
                isCompletionReceiverRegistered = false;
            }
        }
    }

    /** Helper to register the Torch receiver */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerTorchReceiver(Context context) {
        if (isTorchReceiverRegistered) return;
        if (torchReceiver == null) {
            initializeTorchReceiver();
            if (torchReceiver == null) {
                FLog.e(TAG, "Cannot register: Failed init torch receiver");
                return;
            }
        }
        IntentFilter filter = new IntentFilter(
            Constants.BROADCAST_ON_TORCH_STATE_CHANGED
        );
        try {
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
            // method(registerTorchReceiver_add_export_flag_for_Tiramisu)-----
            isTorchReceiverRegistered = true; // Use specific flag
            FLog.d(TAG, "Torch receiver registered.");
        } catch (Exception e) {
            FLog.e(TAG, "Error registering Torch Receiver", e);
            isTorchReceiverRegistered = false;
        }
    }

    /**
     * Initializes camera switch broadcast receivers for live camera switching feedback.
     */
    private void initializeCameraSwitchReceivers() {
        // Receiver for camera switch start
        broadcastOnCameraSwitchStarted = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String fromType = intent.getStringExtra(Constants.BROADCAST_EXTRA_CAMERA_TYPE_FROM);
                String toType = intent.getStringExtra(Constants.BROADCAST_EXTRA_CAMERA_TYPE_TO);
                FLog.d(TAG, "📹 BROADCAST_ON_CAMERA_SWITCH_STARTED: " + fromType + " → " + toType);
                
                isCameraSwitchInProgress = true;
                // Keep button ENABLED during switch for responsive UI
                // The flag prevents multiple simultaneous switches
            }
        };

        // Receiver for camera switch complete
        broadcastOnCameraSwitchComplete = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String fromType = intent.getStringExtra(Constants.BROADCAST_EXTRA_CAMERA_TYPE_FROM);
                String toType = intent.getStringExtra(Constants.BROADCAST_EXTRA_CAMERA_TYPE_TO);
                FLog.d(TAG, "✅ BROADCAST_ON_CAMERA_SWITCH_COMPLETE: " + fromType + " → " + toType);
                
                isCameraSwitchInProgress = false;
                lastCameraSwitchTime = System.currentTimeMillis(); // Track when switch completed
                if (buttonCamSwitch != null) {
                    buttonCamSwitch.setEnabled(true);
                }
                updateMirrorButtonVisibilityAndState();
                if ((isPreviewOnlyActive || isRecordingOrPaused()) && isPreviewEnabled) {
                    if (textureView != null && textureView.isAvailable()) {
                        resetTextureView();
                        if (textureViewSurface != null && textureViewSurface.isValid()) {
                            updateServiceWithCurrentSurface(textureViewSurface);
                        }
                    }
                }
                
                // Show success toast - debounce duplicates (ignore if within 500ms of last)
                long now = System.currentTimeMillis();
                if (now - lastCameraSwitchCompleteTime > 500) {
                    lastCameraSwitchCompleteTime = now;
                    String message = "Camera switched to " + (toType.equals("FRONT") ? "front" : "rear");
                    Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                }
            }
        };

        // Receiver for camera switch failure
        broadcastOnCameraSwitchFailed = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String errorReason = intent.getStringExtra(Constants.BROADCAST_EXTRA_SWITCH_ERROR_REASON);
                String attemptedType = intent.getStringExtra(Constants.BROADCAST_EXTRA_CAMERA_TYPE_ATTEMPTED);
                FLog.e(TAG, "❌ BROADCAST_ON_CAMERA_SWITCH_FAILED: " + errorReason + " (attempted: " + attemptedType + ")");
                
                isCameraSwitchInProgress = false;
                if (buttonCamSwitch != null) {
                    buttonCamSwitch.setEnabled(true);
                }
                
                // Show error toast - debounce duplicates (ignore if within 500ms of last)
                long now = System.currentTimeMillis();
                if (now - lastCameraSwitchCompleteTime > 500) {
                    lastCameraSwitchCompleteTime = now;
                    String message = "Camera switch failed: " + errorReason;
                    Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
                }
            }
        };

        // Receiver: mirror state changed from web dashboard
        broadcastOnMirrorChanged = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                FLog.d(TAG, "📡 BROADCAST_ON_MIRROR_CHANGED received");
                updateMirrorButtonVisibilityAndState();
            }
        };

        // Receiver: zoom / pan changed from web dashboard
        broadcastOnZoomChanged = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                float ratio = intent.getFloatExtra(Constants.EXTRA_BROADCAST_ZOOM_RATIO, -1f);
                float panX  = intent.getFloatExtra(Constants.EXTRA_BROADCAST_PAN_X, 0f);
                float panY  = intent.getFloatExtra(Constants.EXTRA_BROADCAST_PAN_Y, 0f);
                FLog.d(TAG, "📡 BROADCAST_ON_ZOOM_CHANGED ratio=" + ratio + " pan=" + panX + "," + panY);
                if (ratio > 0f) {
                    previewPinchZoomRatio = ratio;
                    previewUiScale = Math.max(1.0f, Math.min(4.0f, ratio));
                }
                applyNormalizedPreviewPan(panX, panY);
                applyPreviewTransform();
                updatePreviewZoomHudUi(previewPinchZoomRatio);
                refreshZoomTileTintFromState();
            }
        };

        broadcastOnExposureChanged = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                currentEvIndex = intent.getIntExtra(
                    Constants.EXTRA_BROADCAST_EXPOSURE_COMPENSATION,
                    sharedPreferencesManager.getSavedExposureCompensation()
                );
                refreshExposureTileTintFromState();
            }
        };
    }

    /**
     * Registers camera switch broadcast receivers for live camera switching feedback.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerCameraSwitchReceivers(Context context) {
        if (broadcastOnCameraSwitchStarted == null 
                || broadcastOnCameraSwitchComplete == null 
                || broadcastOnCameraSwitchFailed == null) {
            initializeCameraSwitchReceivers();
        }

        try {
            IntentFilter startFilter = new IntentFilter(Constants.BROADCAST_ON_CAMERA_SWITCH_STARTED);
            IntentFilter completeFilter = new IntentFilter(Constants.BROADCAST_ON_CAMERA_SWITCH_COMPLETE);
            IntentFilter failedFilter = new IntentFilter(Constants.BROADCAST_ON_CAMERA_SWITCH_FAILED);
            IntentFilter mirrorFilter = new IntentFilter(Constants.BROADCAST_ON_MIRROR_CHANGED);
            IntentFilter zoomFilter = new IntentFilter(Constants.BROADCAST_ON_ZOOM_CHANGED);
            IntentFilter exposureFilter = new IntentFilter(Constants.BROADCAST_ON_EXPOSURE_CHANGED);

            androidx.localbroadcastmanager.content.LocalBroadcastManager lbm =
                    androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context);
            lbm.registerReceiver(broadcastOnCameraSwitchStarted, startFilter);
            lbm.registerReceiver(broadcastOnCameraSwitchComplete, completeFilter);
            lbm.registerReceiver(broadcastOnCameraSwitchFailed, failedFilter);
            lbm.registerReceiver(broadcastOnMirrorChanged, mirrorFilter);
            lbm.registerReceiver(broadcastOnZoomChanged, zoomFilter);
            lbm.registerReceiver(broadcastOnExposureChanged, exposureFilter);
            FLog.d(TAG, "Camera switch + control receivers registered successfully");
        } catch (Exception e) {
            FLog.e(TAG, "Error registering camera switch receivers", e);
        }
    }

    /**
     * Unregisters camera switch broadcast receivers.
     */
    private void unregisterCameraSwitchReceivers(Context context) {
        try {
            if (broadcastOnCameraSwitchStarted != null) {
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context).unregisterReceiver(broadcastOnCameraSwitchStarted);
            }
            if (broadcastOnCameraSwitchComplete != null) {
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context).unregisterReceiver(broadcastOnCameraSwitchComplete);
            }
            if (broadcastOnCameraSwitchFailed != null) {
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context).unregisterReceiver(broadcastOnCameraSwitchFailed);
            }
            if (broadcastOnMirrorChanged != null) {
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context).unregisterReceiver(broadcastOnMirrorChanged);
            }
            if (broadcastOnZoomChanged != null) {
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context).unregisterReceiver(broadcastOnZoomChanged);
            }
            if (broadcastOnExposureChanged != null) {
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context).unregisterReceiver(broadcastOnExposureChanged);
            }
            FLog.d(TAG, "Camera switch + control receivers unregistered");
        } catch (Exception e) {
            FLog.w(TAG, "Error unregistering camera switch receivers: " + e.getMessage());
        }
    }

    // --- Ensure Unregistration Logic ---
    // Place this method in HomeFragment.java and call it from onStop()
    private void unregisterBroadcastReceivers() {
        Context context = getContext(); // Use getContext() for fragment lifecycle safety
        if (context == null) {
            FLog.w(
                TAG,
                "unregisterBroadcastReceivers: Context is null, cannot unregister."
            );
            return;
        }
        FLog.d(
            TAG,
            "Unregistering all HomeFragment broadcast receivers if registered..."
        );

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
                if (
                    broadcastOnPreviewOnlyStarted != null
                ) context.unregisterReceiver(broadcastOnPreviewOnlyStarted);
                if (
                    broadcastOnPreviewOnlyStopped != null
                ) context.unregisterReceiver(broadcastOnPreviewOnlyStopped);
                FLog.i(TAG, "Unregistered recording state receivers.");
            } catch (IllegalArgumentException e) {
                FLog.w(
                    TAG,
                    "Error unregistering state receivers (already unregistered?): " +
                    e.getMessage()
                );
            }
            isStateReceiversRegistered = false;
        } else {
            FLog.d(
                TAG,
                "Recording state receivers were not registered, skipping unregistration."
            );
        }

        if (isCompletionReceiverRegistered) {
            try {
                if (
                    recordingCompleteReceiver != null
                ) context.unregisterReceiver(recordingCompleteReceiver);
                FLog.i(TAG, "Unregistered recordingCompleteReceiver.");
            } catch (IllegalArgumentException e) {
                FLog.w(
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
                FLog.i(TAG, "Unregistered torchReceiver.");
            } catch (IllegalArgumentException e) {
                FLog.w(
                    TAG,
                    "Error unregistering torchReceiver: " + e.getMessage()
                );
            }
            isTorchReceiverRegistered = false;
        }

        // Unregister camera switch receivers
        unregisterCameraSwitchReceivers(context);

        // Unregister dual camera broadcast receivers
        unregisterDualCameraBroadcastReceivers();

        if (isSegmentCompleteStatsReceiverRegistered) {
            try {
                if (
                    segmentCompleteStatsReceiver != null
                ) context.unregisterReceiver(segmentCompleteStatsReceiver);
                FLog.i(TAG, "Unregistered segmentCompleteStatsReceiver.");
            } catch (IllegalArgumentException e) {
                FLog.w(
                    TAG,
                    "Error unregistering segmentCompleteStatsReceiver: " +
                    e.getMessage()
                );
            }
            isSegmentCompleteStatsReceiverRegistered = false;
        }
        // method(unregisterBroadcastReceivers_check_flags)-----
        FLog.i(
            TAG,
            "All HomeFragment broadcast receivers unregistration attempt finished."
        );
        // Unregister recording failed receiver
        if (isRecordingFailedReceiverRegistered && recordingFailedReceiver != null) {
            try {
                requireContext().unregisterReceiver(recordingFailedReceiver);
                isRecordingFailedReceiverRegistered = false;
                FLog.d(TAG, "Unregistered recordingFailedReceiver.");
            } catch (IllegalArgumentException e) {
                FLog.w(TAG, "Error unregistering recordingFailedReceiver: " + e.getMessage());
                isRecordingFailedReceiverRegistered = false;
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        FLog.d(TAG, "HomeFragment paused.");
        updateMainSwipeGestureGate(false);
        if (!isRecordingOrPaused() && (isPreviewOnlyActive || isPreviewOnlyStartPending)) {
            dispatchStopPreviewOnly();
            clearPreviewOnlyPendingState(true);
            isPreviewOnlyActive = false;
            updatePreviewVisibility();
        }
        if (pendingPreviewOnlyStartTimeoutRunnable != null && !isPreviewOnlyStartPending) {
            previewOnlyStartHandler.removeCallbacks(pendingPreviewOnlyStartTimeoutRunnable);
        }

        // Stop bubble rotation animation to save battery
        if (!isLaunchingPhotoCapture) {
            stopBubbleRotation();
        }

        if (textureViewSurface != null && !isReturningFromFullscreen) {
            FLog.d(TAG, "onPause: Explicitly sending null surface to service");
            updateServiceWithCurrentSurface(null, -1, -1, isLaunchingFullscreen);
        } else if (isReturningFromFullscreen) {
            FLog.d(TAG, "onPause: Skipping null surface — returning from fullscreen, new surface incoming");
        }
        // locationHelper.stopLocationUpdates();

        // Only unregister torch receiver if it was actually registered and flag is set
        // This prevents IllegalArgumentException crashes from double-unregistration
        if (isTorchReceiverRegistered && torchReceiver != null) {
            try {
                requireContext().unregisterReceiver(torchReceiver);
                isTorchReceiverRegistered = false;
                FLog.d(TAG, "Unregistered torchReceiver in onPause");
            } catch (IllegalArgumentException e) {
                FLog.w(TAG, "Receiver was not registered: " + e.getMessage());
                isTorchReceiverRegistered = false;
            }
        }
        
        // Pause update handlers to save battery when app is backgrounded
        pauseUpdateHandlers();
    }

    /**
     * Helper method to reset the TextureView when needed to avoid showing stale
     * frames
     * This should be called when the preview state changes, especially from
     * disabled to enabled
     */
    private void resetTextureView() {
        if (textureView == null) {
            FLog.w(TAG, "resetTextureView: TextureView is null, can't reset");
            return;
        }

        FLog.d(TAG, "resetTextureView: Attempting to reset TextureView");

        // First release any existing surface
        if (textureViewSurface != null) {
            textureViewSurface.release();
            textureViewSurface = null;
            FLog.d(TAG, "resetTextureView: Released existing surface");
        }

        // If the TextureView is available, recreate the surface
        if (textureView.isAvailable()) {
            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            if (surfaceTexture != null) {
                textureViewSurface = new Surface(surfaceTexture);
                applyPreviewTransform();
                FLog.d(
                    TAG,
                    "resetTextureView: Created new surface from existing SurfaceTexture"
                );

                // If recording and preview enabled, update service with new surface
                if (isPreviewEnabled && (isRecordingOrPaused() || isPreviewOnlyActive || isPreviewOnlyStartPending)) {
                    updateServiceWithCurrentSurface(textureViewSurface);
                    FLog.d(
                        TAG,
                        "resetTextureView: Updated service with new surface"
                    );
                }
            }
        } else {
            FLog.d(
                TAG,
                "resetTextureView: TextureView not available, can't create surface yet"
            );
        }
    }


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
    // FLog.d(TAG, "Requesting location permission.");
    // }

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        FLog.d(TAG, "[FragmentLifecycle] onCreateView: Inflating layout, container=" + (container == null ? "null" : "exists"));
        
        // Debug recording time issue
        debugRecordingTimeVariables();

        View view = inflater.inflate(R.layout.fragment_home, container, false);
        FLog.d(TAG, "[FragmentLifecycle] onCreateView: Layout inflated successfully");
        return view;
    }

    /**
     * Called by MainActivity when orientation changes to refresh the fragment's
     * layout
     */
    public void onOrientationChanged(int orientation) {
        FLog.d(
            TAG,
            "HomeFragment orientation changed to: " +
            (orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    ? "landscape"
                    : "portrait")
        );

        // Simple approach: just log the change and let Android handle it naturally
        // The issue might be that we're over-engineering the solution
        FLog.d(
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
                        FLog.d(TAG, "State restored after orientation change");
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
        FLog.d(TAG, "Saving fragment state before orientation change");
    }

    /**
     * Restore fragment state after orientation change
     */
    private void restoreCurrentState() {
        // Restore any saved state after orientation change
        FLog.d(TAG, "Restoring fragment state after orientation change");
    }


    // Debug method to help diagnose recording time issue
    private void debugRecordingTimeVariables() {
        FLog.d(TAG, "======== DEBUG RECORDING TIME ========");
        FLog.d(TAG, "recordingStartTime = " + recordingStartTime);
        FLog.d(TAG, "currentTimeMillis = " + System.currentTimeMillis());
        FLog.d(TAG, "elapsedRealtime = " + SystemClock.elapsedRealtime());
        FLog.d(TAG, "recordingState = " + recordingState);
        FLog.d(TAG, "isRecording() = " + isRecording());
        FLog.d(TAG, "isPaused() = " + isPaused());
        FLog.d(TAG, "======== END DEBUG INFO ========");
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
        // Use the SharedPreferencesManager's method which uses the correct constant
        sharedPreferencesManager.setPreviewEnabled(isPreviewEnabled);
        FLog.d(TAG, "Preview state saved: " + isPreviewEnabled);
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
        FLog.i(TAG, "[FragmentLifecycle] onViewCreated: Starting view setup, view is attached: " + view.isAttachedToWindow());

        // Initialize SharedPreferencesManager
        sharedPreferencesManager = SharedPreferencesManager.getInstance(
            requireContext()
        );

        // Initialize views first
        initializeViews(view);

        // Trigger logo reveal animation on fresh start
        if (savedInstanceState == null) {
            startLogoRevealAnimation();
        }

        // Apply header logo style preference (default text vs animated avatar)
        applyHeaderLogoStyle();

        // Initialize camera control state from saved preferences
        currentEvIndex =
            sharedPreferencesManager.getSavedExposureCompensation();
        aeLocked = sharedPreferencesManager.isAeLockedSaved();
        afMode = sharedPreferencesManager.getSavedAfMode();
        FLog.d(
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

        // default) -----
        String lastTheme = sharedPreferencesManager.sharedPreferences.getString(
            "last_theme_for_clock_color",
            null
        );

        FLog.i(
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
            FLog.i(
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
            FLog.i(
                TAG,
                "Applied saved clock card color: " +
                clockColorPref +
                " for theme: " +
                currentTheme
            );
        }
        // -----

        // Initialize ExecutorService
        if (executorService == null || executorService.isShutdown()) {
            executorService = Executors.newSingleThreadExecutor();
        }

        // Initialize UI components using helper
        fragmentHelper = new HomeFragmentHelper(this);
        fragmentHelper.initializeComponents(view);

        // Fragment result listeners for pickers
        FLog.d(
            "HomeFragment",
            "REGISTERING fragment result listener for exposure compensation"
        );
        FLog.d(
            TAG,
            "Registering fragment result listener for exposure compensation with key: " +
            Constants.RK_EXPOSURE_COMPENSATION
        );
        FLog.d(
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
                    FLog.d(
                        "HomeFragment",
                        "FRAGMENT RESULT RECEIVED: requestKey=" +
                        requestKey +
                        ", bundle=" +
                        bundle
                    );
                    FLog.d(
                        TAG,
                        "Fragment result listener triggered for exposure: requestKey=" +
                        requestKey +
                        ", bundle=" +
                        bundle
                    );
                    if (bundle == null) {
                        FLog.w(
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
                    FLog.d(
                        TAG,
                        "Exposure bundle contents: sliderVal=" +
                        sliderVal +
                        ", keys=" +
                        bundle.keySet()
                    );
                    if (sliderVal != Integer.MIN_VALUE) {
                        if (aeLocked) {
                            FLog.d(
                                TAG,
                                "Ignoring exposure slider update while AE lock is enabled"
                            );
                            return;
                        }
                        currentEvIndex = sliderVal;
                        // Debug: record that we received slider update (will only write to debug log
                        // when enabled)
                        FLog.d(
                            TAG,
                            "Received exposure slider value: index=" + sliderVal
                        );
                    } else {
                        String sel = bundle.getString(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID,
                            null
                        );
                        FLog.d(
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
                    sp.setSavedExposureCompensation(currentEvIndex);
                    if (
                        !isMyServiceRunning(
                            com.fadcam.services.RecordingService.class
                        )
                    ) {
                        FLog.d(
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
                        FLog.d(
                            TAG,
                            "Exposure intent sent via picker"
                        );
                    }

                    // Update exposure tile tinting based on current exposure and AE lock state
                    try {
                        if (tileExp != null) {
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

                    FLog.d(
                        TAG,
                        "AE Lock listener triggered: " +
                        state +
                        ", tileExp null? " +
                        (tileExp == null)
                    );

                    SharedPreferencesManager sp =
                        SharedPreferencesManager.getInstance(requireContext());
                    sp.setSavedAeLock(aeLocked);
                    if (
                        !isMyServiceRunning(
                            com.fadcam.services.RecordingService.class
                        )
                    ) {
                        FLog.d(
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
                        FLog.d(TAG, "AE lock intent sent via picker");
                    }
                    // Update exposure tile visual: tint orange when locked, reset when unlocked
                    try {
                        if (tileExp != null) {
                            // theme)-----------
                            // TextView Material Icons font handling with textColor
                            if (aeLocked || currentEvIndex != 0) {
                                int orange = getResources().getColor(
                                    R.color.orange_accent,
                                    requireContext().getTheme()
                                );
                                tileExp.setTextColor(orange);
                                FLog.d(
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
                                FLog.d(
                                    TAG,
                                    "Cleared tint from exposure tile"
                                );
                            }
                            // theme)-----------
                            // subtle scale to indicate active
                            tileExp.setScaleX(aeLocked ? 1.05f : 1f);
                            tileExp.setScaleY(aeLocked ? 1.05f : 1f);
                        }
                    } catch (Exception e) {
                        FLog.e(
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
                        FLog.d(
                            TAG,
                            "AF mode saved to prefs via picker"
                        );
                    } else {
                        Intent i = com.fadcam.RecordingControlIntents.setAfMode(
                            requireContext(),
                            afMode
                        );
                        requireActivity().startService(i);
                        FLog.d(TAG, "AF mode intent sent via picker");
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
                            FLog.d(
                                TAG,
                                "Zoom slider changed: " +
                                zoomRatio +
                                "x (index: " +
                                sliderIndex +
                                ")"
                            );

                            // Save zoom ratio to preferences
                            sp.setSpecificZoomRatio(currentCamera, zoomRatio);

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
                                FLog.d(
                                    TAG,
                                    "Zoom ratio " +
                                    zoomRatio +
                                    "x sent to recording service"
                                );
                            } else {
                                FLog.d(
                                    TAG,
                                    "Zoom ratio " +
                                    zoomRatio +
                                    "x saved to preferences"
                                );
                            }

                            // Sync UI scale and update map/HUD
                            previewPinchZoomRatio = zoomRatio;
                            previewUiScale = Math.max(1.0f, Math.min(4.0f, zoomRatio));
                            applyPreviewTransform();
                            updatePreviewZoomHudUi(zoomRatio);
                        } else {
                            FLog.w(
                                TAG,
                                "Invalid zoom slider index: " + sliderIndex
                            );
                        }
                    } catch (Exception e) {
                        FLog.w(
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
                    if (bundle.containsKey("preview_quick_actions_always_visible")) {
                        updateFullscreenButtonVisibility();
                    }
                    if (!bundle.containsKey("preview_enabled")) return;
                    boolean enabled = bundle.getBoolean("preview_enabled", true);

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

                    if (isRecordingOrPaused() || isPreviewOnlyActive) {
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
        setupLongPressListener(); // Re-enabled for preview toggle during recording
        setupClockLongPressListener(); // For display options on clock
        setupAppLogoLongPressListener(view); // <<< CALL NEW METHOD

        // Initialize easter egg messages and setup listener for preview placeholder
        initializeMessages();

        CardView cardPreview = view.findViewById(R.id.cardPreview);

        String themeName = sharedPreferencesManager.sharedPreferences.getString(
            com.fadcam.Constants.PREF_APP_THEME,
            Constants.DEFAULT_APP_THEME
        );

        if (cardPreview != null) {
            cardPreview.setCardBackgroundColor(Color.TRANSPARENT);
        }

        applyModeSwitcherTheming(themeName);

        if ("Snow Veil".equals(themeName)) {
            applySnowVeilThemeToUI(view);
        }

        setupStatsCardNavigation(view);

        // theme styling -----
        // This ensures the clock card maintains its own independent color regardless of
        // general card styling
        String currentClockColor = sharedPreferencesManager.getClockCardColor();

        // No need for special AMOLED handling here - SharedPreferencesManager handles
        // it

        // Final application of the determined color
        applyClockCardColor(currentClockColor);
        FLog.i(
            TAG,
            "Final clock card color applied: " +
            currentClockColor +
            " for theme: " +
            currentTheme
        );
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
            FLog.d(
                "TorchDebug",
                "Icon resources loaded - ON: " +
                (onIcon != null) +
                ", OFF: " +
                (offIcon != null)
            );
        } catch (Exception e) {
            FLog.e(
                "TorchDebug",
                "Error checking icon resources: " + e.getMessage()
            );
        }

        tips = requireActivity()
            .getResources()
            .getStringArray(R.array.tips_widget);
        FLog.d(TAG, "onViewCreated: Setting up UI components");

        // *** ADDED FIX: Load preview enabled state from SharedPreferences ***
        if (sharedPreferencesManager == null) {
            // Ensure manager is initialized
            sharedPreferencesManager = SharedPreferencesManager.getInstance(
                requireContext()
            );
        }
        isPreviewEnabled = sharedPreferencesManager.isPreviewEnabled(); // Initialize with saved state
        FLog.d(
            TAG,
            "onViewCreated: Loaded isPreviewEnabled state = " + isPreviewEnabled
        );
        // --- END FIX ---

        // If TextureView is already available, reset it to ensure clean state
        if (textureView != null && textureView.isAvailable()) {
            resetTextureView();
            FLog.d(
                TAG,
                "onViewCreated: Reset TextureView to ensure clean startup state"
            );
        }

        resetTimers();
        copyFontToInternalStorage();
        updateStorageInfo();
        // Initial stats update
        FLog.d(TAG, "onViewCreated: Triggering initial stats update.");
        updateStats();
        // NOTE: Clock update is started in onStart(), not here, to avoid duplicate handlers
        // startUpdatingClock(); // Removed - handled in onStart()

        // Update clock and date initially
        updateClock();

        // updateTip(); // Duplicate call? Check if startTipsAnimation is sufficient
        setupButtonListeners();
        setupLongPressListener(); // Re-enabled for preview toggle during recording
        updatePreviewVisibility(); // CRUCIAL: Update visibility based on the loaded state

        buttonTorchSwitch = view.findViewById(R.id.buttonTorchSwitch);
        initializeTorch();
        setupTorchButton();

        // Setup the small recording tiles row and listeners
        setupRecordingTiles(view);

        // Setup lifecycle observer for background/foreground handling
        setupLifecycleObserver();

        // Attempt to find camera with flash
        try {
            cameraId = getCameraWithFlash();
            if (cameraId == null) {
                FLog.d(TAG, "No camera with flash found");
                buttonTorchSwitch.setEnabled(false);
                buttonTorchSwitch.setVisibility(View.GONE);
            } else {
                FLog.d(TAG, "Flash available on camera: " + cameraId);
                buttonTorchSwitch.setEnabled(true);
                buttonTorchSwitch.setVisibility(View.VISIBLE);
            }
        } catch (CameraAccessException e) {
            FLog.e(TAG, "Camera access error: " + e.getMessage());
            e.printStackTrace();
            buttonTorchSwitch.setEnabled(false);
            buttonTorchSwitch.setVisibility(View.GONE);
        }

        View btnGetPro = view.findViewById(R.id.btnGetPro);
        View proBadgeDot = view.findViewById(R.id.proBadgeDot);
        
        if (btnGetPro != null) {
            // Update badge visibility based on pro feature state
            if (proBadgeDot != null) {
                try {
                    MainActivity mainActivity = (MainActivity) getActivity();
                    if (mainActivity != null && mainActivity.shouldShowProBadge()) {
                        proBadgeDot.setVisibility(View.VISIBLE);
                    } else {
                        proBadgeDot.setVisibility(View.GONE);
                    }
                } catch (Exception e) {
                    FLog.e(TAG, "Error checking pro badge state", e);
                }
            }
            
            btnGetPro.setOnClickListener(v -> {
                // Mark pro feature as seen when clicking Pro button
                try {
                    com.fadcam.ui.utils.NewFeatureManager.markFeatureAsSeen(requireContext(), "pro");
                    // Hide badge after clicking
                    if (proBadgeDot != null) {
                        proBadgeDot.setVisibility(View.GONE);
                    }
                } catch (Exception e) {
                    FLog.e(TAG, "Error marking pro as seen", e);
                }
                
                // Open FadCam Pro Activity
                Intent intent = new Intent(getActivity(), FadCamProActivity.class);
                startActivity(intent);
            });
            // ShimmerTextView handles its own shimmer animation
        }

        // -----
        View headerBar = view.findViewById(R.id.header_bar);
        if (headerBar != null) {
            // Use drawable background for consistency with other tabs (portrait home, records, etc)
            int colorTopBar = resolveThemeColor(R.attr.colorTopBar);
            // Create drawable with correct color
            android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
            drawable.setColor(colorTopBar);
            drawable.setCornerRadii(new float[]{0, 0, 0, 0, 24*getResources().getDisplayMetrics().density, 24*getResources().getDisplayMetrics().density, 24*getResources().getDisplayMetrics().density, 24*getResources().getDisplayMetrics().density});
            headerBar.setBackground(drawable);
        }
        // If you have FABs or MaterialButtons, set their background tint to colorButton
        // here
    }

    private int resolveThemeColor(int attr) {
        android.util.TypedValue typedValue = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(attr, typedValue, true);
        return typedValue.data;
    }


    /**
     * Show visual focus indicator at tap location
     */
    private void showFocusIndicator(float x, float y) {
        try {
            if (getView() == null) return;

            FLog.d(TAG, "Showing focus indicator at: " + x + ", " + y);

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
                FLog.d(TAG, "Added focus indicator to parent layout");
            } else {
                FLog.w(TAG, "Could not find parent layout for focus indicator");
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
                                FLog.d(
                                    TAG,
                                    "Removed focus indicator from parent layout"
                                );
                            }
                        } catch (Exception e) {
                            FLog.w(
                                TAG,
                                "Error removing focus indicator: " +
                                e.getMessage()
                            );
                        }
                    }
                }
            );

            animSet.start();
            FLog.d(TAG, "Focus indicator animation started");
        } catch (Exception e) {
            FLog.e(TAG, "Error showing focus indicator: " + e.getMessage(), e);
        }
    }

    private void setupTextureView(@NonNull View view) {
        textureView = view.findViewById(R.id.textureView);

        FLog.d(
            TAG,
            "setupTextureView: TextureView found: " + (textureView != null)
        );
        if (textureView != null) {
            FLog.d(
                TAG,
                "TextureView dimensions: " +
                textureView.getWidth() +
                "x" +
                textureView.getHeight()
            );
            FLog.d(
                TAG,
                "TextureView visibility: " + textureView.getVisibility()
            );
            FLog.d(TAG, "TextureView clickable: " + textureView.isClickable());
            FLog.d(TAG, "TextureView enabled: " + textureView.isEnabled());
        }

        // Check if the placeholder TextView is interfering
        TextView tvPreviewPlaceholder = view.findViewById(
            R.id.tvPreviewPlaceholder
        );
        if (tvPreviewPlaceholder != null) {
            FLog.d(
                TAG,
                "Preview placeholder visibility: " +
                tvPreviewPlaceholder.getVisibility()
            );
            FLog.d(
                TAG,
                "Preview placeholder clickable: " +
                tvPreviewPlaceholder.isClickable()
            );

            // Make sure placeholder doesn't intercept touches
            tvPreviewPlaceholder.setClickable(false);
            tvPreviewPlaceholder.setFocusable(false);
        }

        syncPreviewZoomStateFromPrefs(false);
        previewScaleGestureDetector = new android.view.ScaleGestureDetector(
            requireContext(),
            new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(android.view.ScaleGestureDetector detector) {
                    applyPreviewPinchZoom(detector.getScaleFactor());
                    return true;
                }
            }
        );

        textureView.setOnTouchListener((v, event) -> {
            try {
                final int action = event.getActionMasked();
                final float touchSlop = android.view.ViewConfiguration.get(requireContext()).getScaledTouchSlop();
                final boolean zoomGestureLock = previewUiScale > 1.001f;
                updateMainSwipeGestureGate(zoomGestureLock || isPanningPreview);
                requestPreviewParentIntercept(v, zoomGestureLock || action == MotionEvent.ACTION_POINTER_DOWN);
                if (previewScaleGestureDetector != null) {
                    previewScaleGestureDetector.onTouchEvent(event);
                    if (previewScaleGestureDetector.isInProgress()) {
                        if (pendingPreviewLongPressRunnable != null) {
                            previewLongPressHandler.removeCallbacks(pendingPreviewLongPressRunnable);
                        }
                        updateMainSwipeGestureGate(zoomGestureLock);
                        isPanningPreview = false;
                        return true;
                    }
                }
                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        isPanningPreview = false;
                        previewLongPressTriggered = false;
                        updateMainSwipeGestureGate(zoomGestureLock);
                        previewLastTouchX = event.getX();
                        previewLastTouchY = event.getY();
                        pendingPreviewLongPressRunnable = () -> {
                            previewLongPressTriggered = true;
                            performHapticFeedback();
                            handlePreviewLongPress();
                        };
                        if (!zoomGestureLock) {
                            previewLongPressHandler.postDelayed(pendingPreviewLongPressRunnable, 420L);
                        }
                        break;
                    case MotionEvent.ACTION_MOVE:
                        float travelDx = event.getX() - previewLastTouchX;
                        float travelDy = event.getY() - previewLastTouchY;
                        if (Math.abs(travelDx) > touchSlop || Math.abs(travelDy) > touchSlop) {
                            if (pendingPreviewLongPressRunnable != null) {
                                previewLongPressHandler.removeCallbacks(pendingPreviewLongPressRunnable);
                            }
                        }
                        if (previewUiScale > 1.001f) {
                            float dx = travelDx;
                            float dy = travelDy;
                            if (Math.abs(dx) > 1f || Math.abs(dy) > 1f) {
                                isPanningPreview = true;
                                updateMainSwipeGestureGate(true);
                                previewUiPanX += dx;
                                previewUiPanY += dy;
                                applyPreviewTransform();
                                previewLastTouchX = event.getX();
                                previewLastTouchY = event.getY();
                                return true;
                            }
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (pendingPreviewLongPressRunnable != null) {
                            previewLongPressHandler.removeCallbacks(pendingPreviewLongPressRunnable);
                        }
                        updateMainSwipeGestureGate(false);
                        requestPreviewParentIntercept(v, false);
                        if (previewLongPressTriggered) {
                            previewLongPressTriggered = false;
                            isPanningPreview = false;
                            return true;
                        }
                        if (isPanningPreview) {
                            isPanningPreview = false;
                            dispatchCurrentPreviewZoomPan();
                            return true;
                        }
                        if ((isRecordingOrPaused() || isPreviewOnlyActive) && textureView != null) {
                            float x = event.getX();
                            float y = event.getY();
                            int width = textureView.getWidth();
                            int height = textureView.getHeight();
                            if (width > 0 && height > 0) {
                                float normalizedX = x / width;
                                float normalizedY = y / height;
                                Intent tapToFocusIntent = RecordingControlIntents.tapToFocus(
                                    requireContext(),
                                    normalizedX,
                                    normalizedY
                                );
                                requireContext().startService(tapToFocusIntent);
                                showFocusIndicator(x, y);
                                return true;
                            }
                        }
                        break;
                    default:
                        break;
                }
                return true;
            } catch (Exception ex) {
                FLog.w(
                    TAG,
                    "Gesture handling failed: " +
                    (ex != null ? ex.getMessage() : "")
                );
                return false;
            }
        });

        // Also try setting clickable and focusable
        textureView.setClickable(true);
        textureView.setFocusable(true);
        textureView.setEnabled(true);

        // Add a simple click listener as a test
        textureView.setOnClickListener(v -> {
            FLog.d(TAG, "TextureView OnClickListener triggered!");
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
            FLog.d(
                TAG,
                "Setting up touch listener on parent FrameLayout as backup"
            );
            parentFrame.setOnTouchListener((v, event) -> {
                FLog.d(
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

        FLog.d(
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
                    FLog.d(
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
                    FLog.d(
                        TAG,
                        "onSurfaceTextureAvailable: Created new surface from texture"
                    );
                    // Clear the returning flag now that surface is ready
                    if (isReturningFromFullscreen) {
                        FLog.d(TAG, "onSurfaceTextureAvailable: Clearing isReturningFromFullscreen flag");
                        isReturningFromFullscreen = false;
                    }
                    if (isPreviewEnabled && (isRecordingOrPaused() || isPreviewOnlyActive || isPreviewOnlyStartPending)) {
                        FLog.d(
                            TAG,
                            "onSurfaceTextureAvailable: Preview active, sending surface to service"
                        );
                        updateServiceWithCurrentSurface(
                            textureViewSurface,
                            width,
                            height
                        );
                    } else {
                        FLog.d(
                            TAG,
                            "onSurfaceTextureAvailable: Not recording or preview disabled, surface ready for later use"
                        );
                    }
                    onModeSpecificPreviewSurfaceChanged(textureViewSurface, width, height);
                }

                @Override
                public void onSurfaceTextureSizeChanged(
                    @NonNull SurfaceTexture surface,
                    int width,
                    int height
                ) {
                    FLog.d(
                        TAG,
                        "onSurfaceTextureSizeChanged: Size changed to " +
                        width +
                        "x" +
                        height
                    );
                    if (
                        isPreviewEnabled &&
                        (isRecordingOrPaused() || isPreviewOnlyActive || isPreviewOnlyStartPending) &&
                        textureViewSurface != null &&
                        textureViewSurface.isValid()
                    ) {
                        FLog.d(
                            TAG,
                            "onSurfaceTextureSizeChanged: Updating surface dimensions"
                        );
                        updateServiceWithCurrentSurface(
                            textureViewSurface,
                            width,
                            height
                        );
                    }
                    onModeSpecificPreviewSurfaceChanged(textureViewSurface, width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(
                    @NonNull SurfaceTexture surface
                ) {
                    FLog.d(
                        TAG,
                        "onSurfaceTextureDestroyed: SurfaceTexture is being destroyed."
                    );
                    if (textureViewSurface != null) {
                        // Only send null if we're not returning from fullscreen.
                        // During fullscreen return, texture is destroyed/recreated rapidly, and
                        // sending null causes "Surface lost" dummy surface creation.
                        if ((isRecordingOrPaused() || isPreviewOnlyActive) && !isReturningFromFullscreen) {
                            FLog.d(
                                TAG,
                                "onSurfaceTextureDestroyed: Recording active, sending null surface to service."
                            );
                            updateServiceWithCurrentSurface(null);
                        } else if (isReturningFromFullscreen) {
                            FLog.d(
                                TAG,
                                "onSurfaceTextureDestroyed: Skipping null surface — returning from fullscreen"
                            );
                        }
                        textureViewSurface.release();
                        textureViewSurface = null;
                        FLog.d(
                            TAG,
                            "onSurfaceTextureDestroyed: Released local textureViewSurface."
                        );
                    }
                    onModeSpecificPreviewSurfaceChanged(null, -1, -1);
                    return true; // Surface is released by the listener
                }

                @Override
                public void onSurfaceTextureUpdated(
                    @NonNull SurfaceTexture surface
                ) {
                    // Trigger the deferred iris-open reveal on the first camera frame.
                    if (pendingIrisOpenReveal) {
                        pendingIrisOpenReveal = false; // atomic-enough: one thread reads, sets back
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                            if (isAdded()) performIrisOpenReveal();
                            else isPreviewOpenAnimating = false;
                        });
                    }
                }
            }
        );
    }

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
            FLog.d(
                TAG,
                "Start/Stop button clicked. recordingState=" +
                recordingState +
                ", enabled=" +
                buttonStartStop.isEnabled()
            );

            // Prevent rapid clicking by temporarily disabling the button
            if (!buttonStartStop.isEnabled()) {
                FLog.d(TAG, "Button click ignored - button disabled");
                return;
            }

            // If service is not running, force recordingState to NONE
            if (!isMyServiceRunning(RecordingService.class)
                    && !isMyServiceRunning(DualCameraRecordingService.class)) {
                FLog.d(
                    TAG,
                    "Service not running, forcing recordingState to NONE"
                );
                recordingState = RecordingState.NONE;
                isDualRecordingActive = false;
            }

            // Temporarily disable button to prevent rapid clicks
            buttonStartStop.setEnabled(false);
            
            // Re-enable after a short delay
            handlerClock.postDelayed(() -> {
                if (buttonStartStop != null && isAdded()) {
                    buttonStartStop.setEnabled(true);
                    FLog.d(TAG, "Button re-enabled after cooldown period");
                }
            }, 1500); // 1.5 second cooldown

            if (recordingState.equals(RecordingState.NONE)) {
                if (isCardRailCurrentlyFolded()) {
                    applyButtonTransition(buttonStartStop, getString(R.string.button_stop),
                            AppCompatResources.getDrawable(requireContext(), R.drawable.stop_rounded), () -> {
                        buttonStartStop.setBackgroundTintList(
                                ContextCompat.getColorStateList(requireContext(), R.color.button_stop)
                        );
                        buttonStartStop.setAlpha(1.0f);
                    });
                } else {
                    animateButtonTransition(buttonStartStop, getString(R.string.button_stop),
                            AppCompatResources.getDrawable(requireContext(), R.drawable.stop_rounded), () -> {
                        buttonStartStop.setBackgroundTintList(
                                ContextCompat.getColorStateList(requireContext(), R.color.button_stop)
                        );
                        buttonStartStop.setAlpha(1.0f);
                    }, true);
                }
                debouncedStartRecording.run();
            } else {
                if (isCardRailCurrentlyFolded()) {
                    applyButtonTransition(buttonStartStop, getString(R.string.button_start),
                            AppCompatResources.getDrawable(requireContext(), R.drawable.play_button_rounded), () -> {
                        buttonStartStop.setBackgroundTintList(
                                ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                        );
                        buttonStartStop.setAlpha(1.0f);
                    });
                } else {
                    animateButtonTransition(buttonStartStop, getString(R.string.button_start),
                            AppCompatResources.getDrawable(requireContext(), R.drawable.play_button_rounded), () -> {
                        buttonStartStop.setBackgroundTintList(
                                ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                        );
                        buttonStartStop.setAlpha(1.0f);
                    }, false);
                }
                debouncedStopRecording.run();
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

        if (buttonMirrorSwitch != null) {
            buttonMirrorSwitch.setOnClickListener(v -> {
                boolean enabled = !sharedPreferencesManager.isFrontVideoMirrorEnabled();
                sharedPreferencesManager.setFrontVideoMirrorEnabled(enabled);
                pushFrontMirrorToService(enabled);
                updateMirrorButtonVisibilityAndState();
            });
        }
    }

    private void pushFrontMirrorToService(boolean enabled) {
        if (!isAdded() || getContext() == null) return;
        if (!isRecordingOrPaused() && !isMyServiceRunning(RecordingService.class)) {
            return;
        }
        Intent intent = new Intent(getContext(), RecordingService.class);
        intent.setAction(Constants.INTENT_ACTION_SET_FRONT_VIDEO_MIRROR);
        intent.putExtra(Constants.EXTRA_FRONT_VIDEO_MIRROR_ENABLED, enabled);
        try {
            ServiceStartPolicy.startRecordingAction(requireContext(), intent);
        } catch (Exception e) {
            FLog.w(TAG, "Failed to push mirror preference to service: " + e.getMessage());
        }
    }

    protected void updateMirrorButtonVisibilityAndState() {
        if (buttonMirrorSwitch == null || sharedPreferencesManager == null) return;
        CameraType selectedCamera = sharedPreferencesManager.getCameraSelection();
        boolean shouldShow =
            selectedCamera == CameraType.FRONT &&
            !selectedCamera.isDual() &&
            !getClass().getName().contains("FadRecHomeFragment");

        buttonMirrorSwitch.setVisibility(shouldShow ? View.VISIBLE : View.GONE);
        if (!shouldShow) {
            return;
        }

        boolean enabled = sharedPreferencesManager.isFrontVideoMirrorEnabled();
        buttonMirrorSwitch.setEnabled(true);
        buttonMirrorSwitch.setAlpha(1.0f);
        buttonMirrorSwitch.setContentDescription(
            getString(enabled ? R.string.front_video_mirror_disable : R.string.front_video_mirror_enable)
        );
        buttonMirrorSwitch.setBackgroundTintList(
            ColorStateList.valueOf(enabled ? ContextCompat.getColor(requireContext(), R.color.button_stop) : 0xFF3A3A3A)
        );
        buttonMirrorSwitch.setIconTint(ColorStateList.valueOf(Color.WHITE));
    }

    // --- Start Recording ---
    // Inside HomeFragment.java
    private void startRecording() {
        if (getContext() == null) {
            FLog.e(TAG, "Context is null, cannot start recording.");
            return;
        }
        if (!ensureRecordingHardwareSupported()) {
            FLog.w(TAG, "startRecording blocked: required recording hardware missing");
            return;
        }
        performHapticFeedback();
        // Permission checks removed; handled by onboarding

        // ── Dual Camera path ──────────────────────────────────────────
        CameraType selectedCam = sharedPreferencesManager.getCameraSelection();
        if (selectedCam == CameraType.DUAL_PIP) {
            startDualRecording();
            return;
        }

        // Check if codec is HEVC - browsers don't support HEVC for HLS live streaming
        // Only validate if streaming is actually enabled (server running)
        if (RemoteStreamManager.getInstance().isStreamingEnabled()) {
            VideoCodec selectedCodec = sharedPreferencesManager.getVideoCodec();
            if (selectedCodec == VideoCodec.HEVC) {
                // HEVC is not browser-compatible for HLS streaming
                // Show Material Dialog with option to auto-switch to AVC
                showCodecIncompatibilityDialog();
                return; // Block recording start
            }
        }

        // Force reset recording state if service is not running
        if (!isMyServiceRunning(RecordingService.class)) {
            FLog.d(TAG, "Service not running, forcing recordingState to NONE");
            recordingState = RecordingState.NONE;
        }

        boolean serviceRunning = isMyServiceRunning(RecordingService.class);
        boolean allowStartFromPreviewOnly =
            serviceRunning && isPreviewOnlyActive;
        if (serviceRunning && !allowStartFromPreviewOnly) {
            FLog.w(
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
            ServiceStartPolicy.startRecordingAction(requireContext(), queryIntent);
            // UI should update based on the broadcast from the service
            return; // Don't try to start again if it might be running
        }

        FLog.d(TAG, "startRecording: Starting RecordingService.");
        Intent serviceIntent = new Intent(getContext(), RecordingService.class);
        serviceIntent.setAction(Constants.INTENT_ACTION_START_RECORDING);

        // Pass current torch state (from HomeFragment's perspective) to the service
        // The service will use this to set the initial FLASH_MODE in its CaptureRequest
        // if it starts successfully.
        FLog.d(TAG, "Passing initial torch state to service: " + isTorchOn);
        serviceIntent.putExtra(
            Constants.INTENT_EXTRA_INITIAL_TORCH_STATE,
            isTorchOn
        );

        // Pass the surface if preview is enabled and surface is valid
        if (
            isPreviewEnabled &&
            textureViewSurface != null &&
            textureViewSurface.isValid()
        ) {
            FLog.d(TAG, "Preview enabled, passing valid surface to service.");
            serviceIntent.putExtra("SURFACE", textureViewSurface);
        } else {
            FLog.w(
                TAG,
                "Preview disabled or surface invalid. Service will start without preview surface."
            );
            serviceIntent.putExtra("SURFACE", (Surface) null); // Explicitly pass null
        }

        ServiceStartPolicy.startRecordingAction(requireContext(), serviceIntent);
        // UI state changes will be handled by broadcast receivers
        // setUIForRecordingActive(); // Move UI update to onRecordingStarted broadcast
        // receiver
        FLog.d(TAG, "startRecording: RecordingService start initiated.");
    }

    // ── Dual Camera Recording ─────────────────────────────────────────

    /**
     * Starts the {@link DualCameraRecordingService} for simultaneous
     * front + back PiP recording.
     */
    private void startDualRecording() {
        if (getContext() == null) {
            FLog.e(TAG, "startDualRecording: Context null");
            return;
        }
        if (!ensureRecordingHardwareSupported()) {
            FLog.w(TAG, "startDualRecording blocked: required recording hardware missing");
            return;
        }

        // Prevent starting if either recording service is already running
        if (isMyServiceRunning(DualCameraRecordingService.class)) {
            FLog.w(TAG, "DualCameraRecordingService already running");
            return;
        }
        if (isMyServiceRunning(RecordingService.class)) {
            FLog.w(TAG, "RecordingService already running, cannot start dual recording");
            return;
        }

        FLog.d(TAG, "startDualRecording: Starting DualCameraRecordingService");
        isDualRecordingActive = true;

        Intent intent = new Intent(getContext(), DualCameraRecordingService.class);
        intent.setAction(Constants.INTENT_ACTION_START_DUAL_RECORDING);
        ServiceStartPolicy.startRecordingAction(requireContext(), intent);

        FLog.d(TAG, "startDualRecording: DualCameraRecordingService start initiated.");
    }

    /**
     * Stops the running dual-camera recording service.
     */
    private void stopDualRecording() {
        if (getContext() == null) {
            FLog.w(TAG, "stopDualRecording: Context null");
            return;
        }
        FLog.i(TAG, ">> stopDualRecording user action");
        disableInteractionButtons();

        Intent intent = new Intent(getContext(), DualCameraRecordingService.class);
        intent.setAction(Constants.INTENT_ACTION_STOP_DUAL_RECORDING);
        try {
            getContext().startService(intent);
            FLog.i(TAG, "Sent STOP_DUAL intent.");
        } catch (Exception e) {
            FLog.e(TAG, "Error sending STOP_DUAL intent: ", e);
            Toast.makeText(getContext(), "Error stopping dual recording", Toast.LENGTH_SHORT).show();
            resetUIButtonsToIdleState();
        }
        vibrateTouch();
    }

    /**
     * Pauses the running dual-camera recording.
     */
    private void pauseDualRecording() {
        FLog.d(TAG, "pauseDualRecording: Pausing dual video recording");
        buttonPauseResume.setIcon(
                AppCompatResources.getDrawable(requireContext(), R.drawable.play_button_rounded));
        buttonPauseResume.setEnabled(false);

        Intent intent = new Intent(getContext(), DualCameraRecordingService.class);
        intent.setAction(Constants.INTENT_ACTION_PAUSE_DUAL_RECORDING);
        requireActivity().startService(intent);
    }

    /**
     * Resumes a paused dual-camera recording.
     */
    private void resumeDualRecording() {
        FLog.d(TAG, "resumeDualRecording: Resuming dual video recording");
        buttonPauseResume.setIcon(
                AppCompatResources.getDrawable(requireContext(), R.drawable.pause_rounded));
        buttonPauseResume.setEnabled(false);

        Intent intent = new Intent(getContext(), DualCameraRecordingService.class);
        intent.setAction(Constants.INTENT_ACTION_RESUME_DUAL_RECORDING);
        requireActivity().startService(intent);
    }

    /**
     * Swaps primary/secondary cameras in dual-camera mode (while recording).
     */
    private void swapDualCameras() {
        FLog.d(TAG, "swapDualCameras: Swapping cameras in dual mode");
        Intent intent = new Intent(getContext(), DualCameraRecordingService.class);
        intent.setAction(Constants.INTENT_ACTION_SWAP_DUAL_CAMERAS);
        requireActivity().startService(intent);
        vibrateTouch();
    }

    /**
     * Shows a Material Dialog when HEVC codec is selected but live streaming requires AVC.
     * Offers user to automatically switch to AVC codec and start recording.
     */
    private void showCodecIncompatibilityDialog() {
        if (getContext() == null) return;

        new MaterialAlertDialogBuilder(getContext())
                .setTitle(getString(R.string.codec_hevc_incompatible_title))
                .setMessage(getString(R.string.codec_hevc_incompatible_message))
                .setPositiveButton(getString(R.string.codec_switch_and_record), (dialog, which) -> {
                    // Auto-switch codec to AVC
                    FLog.d(TAG, "User requested auto-switch from HEVC to AVC codec");
                    sharedPreferencesManager.sharedPreferences.edit()
                        .putString(Constants.PREF_VIDEO_CODEC, VideoCodec.AVC.toString())
                        .apply();
                    
                    // Show confirmation toast
                    Toast.makeText(
                        getContext(),
                        getString(R.string.codec_switched_to_h264),
                        Toast.LENGTH_SHORT
                    ).show();
                    
                    // Proceed with recording start
                    proceedWithRecordingStart();
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    FLog.d(TAG, "User cancelled recording due to codec incompatibility");
                    dialog.dismiss();
                })
                .setCancelable(true)
                .show();
    }

    /**
     * Continue with recording start after codec validation passes.
     * Contains the actual recording initialization logic.
     */
    private void proceedWithRecordingStart() {
        if (!ensureRecordingHardwareSupported()) {
            FLog.w(TAG, "proceedWithRecordingStart blocked: required recording hardware missing");
            return;
        }
        // Force reset recording state if service is not running
        if (!isMyServiceRunning(RecordingService.class)) {
            FLog.d(TAG, "Service not running, forcing recordingState to NONE");
            recordingState = RecordingState.NONE;
        }

        boolean serviceRunning = isMyServiceRunning(RecordingService.class);
        boolean allowStartFromPreviewOnly =
            serviceRunning && isPreviewOnlyActive;
        if (serviceRunning && !allowStartFromPreviewOnly) {
            FLog.w(
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
            ServiceStartPolicy.startRecordingAction(requireContext(), queryIntent);
            // UI should update based on the broadcast from the service
            return; // Don't try to start again if it might be running
        }

        FLog.d(TAG, "startRecording: Starting RecordingService.");
        Intent serviceIntent = new Intent(getContext(), RecordingService.class);
        serviceIntent.setAction(Constants.INTENT_ACTION_START_RECORDING);

        // Pass current torch state (from HomeFragment's perspective) to the service
        // The service will use this to set the initial FLASH_MODE in its CaptureRequest
        // if it starts successfully.
        FLog.d(TAG, "Passing initial torch state to service: " + isTorchOn);
        serviceIntent.putExtra(
            Constants.INTENT_EXTRA_INITIAL_TORCH_STATE,
            isTorchOn
        );

        // Pass the surface if preview is enabled and surface is valid
        if (
            isPreviewEnabled &&
            textureViewSurface != null &&
            textureViewSurface.isValid()
        ) {
            FLog.d(TAG, "Preview enabled, passing valid surface to service.");
            serviceIntent.putExtra("SURFACE", textureViewSurface);
        } else {
            FLog.w(
                TAG,
                "Preview disabled or surface invalid. Service will start without preview surface."
            );
            serviceIntent.putExtra("SURFACE", (Surface) null); // Explicitly pass null
        }

        ServiceStartPolicy.startRecordingAction(requireContext(), serviceIntent);
        // UI state changes will be handled by broadcast receivers
        // setUIForRecordingActive(); // Move UI update to onRecordingStarted broadcast
        // receiver
        FLog.d(TAG, "startRecording: RecordingService start initiated.");
    }

    // Inside HomeFragment.java

    /**
     * Helper method to disable buttons typically unavailable during
     * recording initiation, stopping transitions, or active recording/pausing.
     * Checks if views exist before modifying them.
     */
    private void disableInteractionButtons() {
        FLog.d(TAG, "Attempting to disable interaction buttons.");
        if (!isAdded() || getView() == null) {
            // Extra check for view availability
            FLog.w(
                TAG,
                "disableInteractionButtons: View not available, cannot disable buttons."
            );
            return;
        }
        try {
            // Null checks are crucial inside helper methods
            if (buttonStartStop != null) {
                buttonStartStop.setEnabled(false);
                FLog.v(TAG, "Disabled: Start/Stop Button"); // Verbose log
            } else FLog.w(
                TAG,
                "buttonStartStop is null in disableInteractionButtons"
            );

            if (buttonPauseResume != null) {
                buttonPauseResume.setEnabled(false);
                FLog.v(TAG, "Disabled: Pause/Resume Button");
            } else FLog.w(
                TAG,
                "buttonPauseResume is null in disableInteractionButtons"
            );

            if (buttonCamSwitch != null) {
                // Keep camera switch button ENABLED during recording for live switching
                buttonCamSwitch.setEnabled(true);
                FLog.v(TAG, "Camera Switch Button: ENABLED (for live switching)");
            } else FLog.w(
                TAG,
                "buttonCamSwitch is null in disableInteractionButtons"
            );

            // if (buttonTorchSwitch != null) {
            // buttonTorchSwitch.setEnabled(false); // Also disable torch during these
            // states
            // FLog.v(TAG,"Disabled: Torch Button");
            // } else FLog.w(TAG,"buttonTorchSwitch is null in disableInteractionButtons");

            FLog.d(TAG, "Interaction buttons disabled.");
        } catch (Exception e) {
            // Catch potential NPE or other issues if views are somehow null unexpectedly
            FLog.e(TAG, "Error occurred while disabling interaction buttons", e);
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
                animatePressBounce(v, () -> {
                    performHapticFeedback();
                    showClockAppearanceDialog();
                });
                return true;
            });
        }
    }

    private void animatePressBounce(@NonNull View target, @Nullable Runnable endAction) {
        target.animate().cancel();
        AnimatorSet bounce = new AnimatorSet();
        ObjectAnimator scaleDownX = ObjectAnimator.ofFloat(target, "scaleX", 0.97f);
        ObjectAnimator scaleDownY = ObjectAnimator.ofFloat(target, "scaleY", 0.97f);
        scaleDownX.setDuration(50);
        scaleDownY.setDuration(50);
        ObjectAnimator scaleUpX = ObjectAnimator.ofFloat(target, "scaleX", 1.0f);
        ObjectAnimator scaleUpY = ObjectAnimator.ofFloat(target, "scaleY", 1.0f);
        scaleUpX.setDuration(70);
        scaleUpY.setDuration(70);
        bounce.play(scaleDownX).with(scaleDownY);
        bounce.play(scaleUpX).with(scaleUpY).after(scaleDownX);
        bounce.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                target.setScaleX(1f);
                target.setScaleY(1f);
                if (endAction != null) endAction.run();
            }
        });
        bounce.start();
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
        if (!isAdded()) return;

        ArrayList<com.fadcam.ui.picker.OptionItem> items = new ArrayList<>();
        items.add(new com.fadcam.ui.picker.OptionItem(
                "clock_display",
                getString(R.string.home_clock_display_option),
                getString(R.string.home_clock_display_option_desc),
                null, null, R.drawable.ic_arrow_right, null, null, "schedule"));
        items.add(new com.fadcam.ui.picker.OptionItem(
                "clock_color",
                getString(R.string.home_clock_color_option),
                getString(R.string.home_clock_color_option_desc),
                null, null, R.drawable.ic_arrow_right, null, null, "palette"));
        items.add(new com.fadcam.ui.picker.OptionItem(
                "clock_hour_format",
                getString(R.string.home_clock_hour_format_option),
                getString(R.string.home_clock_hour_format_option_desc),
                null, null, R.drawable.ic_arrow_right, null, null, "schedule"));

        getParentFragmentManager().setFragmentResultListener(
                CLOCK_CUSTOMIZE_RESULT_KEY,
                getViewLifecycleOwner(),
                (key, bundle) -> {
                    if (bundle == null) return;
                    String selected = bundle.getString(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID,
                            "");
                    if ("clock_display".equals(selected)) {
                        showDisplayOptionsDialog();
                    } else if ("clock_color".equals(selected)) {
                        showClockColorChooserDialog();
                    } else if ("clock_hour_format".equals(selected)) {
                        showClockHourFormatSheet();
                    }
                });

        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
                com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceGradient(
                        getString(R.string.home_clock_customize_title),
                        items,
                        null,
                        CLOCK_CUSTOMIZE_RESULT_KEY,
                        getString(R.string.home_clock_customize_helper),
                        true);
        Bundle args = sheet.getArguments();
        if (args != null) {
            args.putBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.ARG_HIDE_CHECK, true);
        }
        sheet.show(getParentFragmentManager(), "home_clock_customize_sheet");
    }

    private void showDisplayOptionsDialog() {
        if (!isAdded()) return;

        final String[] labels = {
            getString(R.string.dialog_clock_timeonly),
            getString(R.string.dialog_clock_englishtime),
            getString(R.string.dialog_clock_Islamic_calendar),
        };
        final String[] ids = {"clock_time_only", "clock_english", "clock_islamic"};
        int currentOption = getCurrentDisplayOption();
        String selectedId = ids[Math.max(0, Math.min(currentOption, ids.length - 1))];

        ArrayList<com.fadcam.ui.picker.OptionItem> items = new ArrayList<>();
        items.add(new com.fadcam.ui.picker.OptionItem(ids[0], labels[0], getString(R.string.home_clock_display_timeonly_desc), null, null, null, null, null, "schedule"));
        items.add(new com.fadcam.ui.picker.OptionItem(ids[1], labels[1], getString(R.string.home_clock_display_english_desc), null, null, null, null, null, "calendar_month"));
        items.add(new com.fadcam.ui.picker.OptionItem(ids[2], labels[2], getString(R.string.home_clock_display_islamic_desc), null, null, null, null, null, "event"));

        getParentFragmentManager().setFragmentResultListener(
                CLOCK_DISPLAY_RESULT_KEY,
                getViewLifecycleOwner(),
                (key, bundle) -> {
                    if (bundle == null) return;
                    String selected = bundle.getString(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID,
                            ids[0]);
                    int which = Arrays.asList(ids).indexOf(selected);
                    if (which < 0) which = 0;
                    saveDisplayOption(which);
                    updateClock();
                });

        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
                com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceGradient(
                        getString(R.string.home_clock_display_option),
                        items,
                        selectedId,
                        CLOCK_DISPLAY_RESULT_KEY,
                        getString(R.string.home_clock_display_helper),
                        true);
        Bundle args = sheet.getArguments();
        if (args != null) {
            args.putBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.ARG_HIDE_CHECK, true);
        }
        sheet.show(getParentFragmentManager(), "home_clock_display_sheet");
    }

    private void showClockColorChooserDialog() {
        if (!isAdded()) return;

        String currentSelectedColorHex = sharedPreferencesManager.getClockCardColor();
        ArrayList<com.fadcam.ui.picker.OptionItem> items = new ArrayList<>();
        for (int i = 0; i < CLOCK_COLOR_HEX_VALUES.length; i++) {
            items.add(new com.fadcam.ui.picker.OptionItem(
                    CLOCK_COLOR_HEX_VALUES[i],
                    CLOCK_COLOR_NAMES[i],
                    getString(R.string.home_clock_color_choice_desc, CLOCK_COLOR_HEX_VALUES[i]),
                    Color.parseColor(CLOCK_COLOR_HEX_VALUES[i]),
                    null, null, null, null, null));
        }

        getParentFragmentManager().setFragmentResultListener(
                CLOCK_COLOR_RESULT_KEY,
                getViewLifecycleOwner(),
                (key, bundle) -> {
                    if (bundle == null) return;
                    String selectedColorHex = bundle.getString(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID,
                            currentSelectedColorHex);
                    sharedPreferencesManager.setClockCardColor(selectedColorHex);
                    applyClockCardColor(selectedColorHex);

                    int selectedColor = Color.parseColor(selectedColorHex);
                    boolean isLightColor = isLightColor(selectedColor);
                    if (isLightColor) {
                        tvClock.setTextColor(Color.BLACK);
                        tvDateEnglish.setTextColor(Color.BLACK);
                        tvDateArabic.setTextColor(Color.BLACK);
                    } else {
                        tvClock.setTextColor(Color.WHITE);
                        tvDateEnglish.setTextColor(Color.WHITE);
                        tvDateArabic.setTextColor(Color.WHITE);
                    }
                    updateClock();
                });

        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
                com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceGradient(
                        getString(R.string.home_clock_color_option),
                        items,
                        currentSelectedColorHex,
                        CLOCK_COLOR_RESULT_KEY,
                        getString(R.string.home_clock_color_helper),
                        true);
        Bundle args = sheet.getArguments();
        if (args != null) {
            args.putBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.ARG_HIDE_CHECK, true);
        }
        sheet.show(getParentFragmentManager(), "home_clock_color_sheet");
    }

    private void showClockHourFormatSheet() {
        if (!isAdded()) return;

        ArrayList<com.fadcam.ui.picker.OptionItem> items = new ArrayList<>();
        items.add(new com.fadcam.ui.picker.OptionItem(
                CLOCK_HOUR_FORMAT_12,
                getString(R.string.home_clock_hour_format_12),
                getString(R.string.home_clock_hour_format_12_desc),
                null, null, null, null, null, "schedule"));
        items.add(new com.fadcam.ui.picker.OptionItem(
                CLOCK_HOUR_FORMAT_24,
                getString(R.string.home_clock_hour_format_24),
                getString(R.string.home_clock_hour_format_24_desc),
                null, null, null, null, null, "schedule"));

        String selectedId = getCurrentClockHourFormat();

        getParentFragmentManager().setFragmentResultListener(
                CLOCK_HOUR_FORMAT_RESULT_KEY,
                getViewLifecycleOwner(),
                (key, bundle) -> {
                    if (bundle == null) return;
                    String selected = bundle.getString(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID,
                            CLOCK_HOUR_FORMAT_12);
                    saveClockHourFormat(selected);
                    updateClock();
                });

        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
                com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceGradient(
                        getString(R.string.home_clock_hour_format_option),
                        items,
                        selectedId,
                        CLOCK_HOUR_FORMAT_RESULT_KEY,
                        getString(R.string.home_clock_hour_format_helper),
                        true);
        Bundle args = sheet.getArguments();
        if (args != null) {
            args.putBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.ARG_HIDE_CHECK, true);
        }
        sheet.show(getParentFragmentManager(), "home_clock_hour_format_sheet");
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

    private int dpToPxInt(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private boolean isLandscapeMode() {
        return getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    private int getCurrentDisplayOption() {
        return requireActivity()
            .getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
            .getInt("display_option", 2); // Default to "Everything"
    }

    private String getCurrentClockHourFormat() {
        return requireActivity()
            .getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
            .getString(Constants.PREF_HOME_CLOCK_HOUR_FORMAT, CLOCK_HOUR_FORMAT_12);
    }

    private void saveDisplayOption(int option) {
        SharedPreferences.Editor editor = requireActivity()
            .getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
            .edit();
        editor.putInt("display_option", option);
        editor.apply();
    }

    private void saveClockHourFormat(@NonNull String format) {
        SharedPreferences.Editor editor = requireActivity()
            .getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
            .edit();
        editor.putString(Constants.PREF_HOME_CLOCK_HOUR_FORMAT, format);
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
        String hourFormatPref = prefs.getString(
            Constants.PREF_HOME_CLOCK_HOUR_FORMAT,
            CLOCK_HOUR_FORMAT_12
        );

        // Update the time
        SimpleDateFormat timeFormat = new SimpleDateFormat(
            CLOCK_HOUR_FORMAT_24.equals(hourFormatPref) ? "HH:mm" : "hh:mm a",
            Locale.getDefault()
        );
        String currentTime = timeFormat.format(new Date());
        if (tvClock instanceof com.fadcam.ui.utils.AnimatedTextView) {
            ((com.fadcam.ui.utils.AnimatedTextView) tvClock).animateSlot(currentTime, 400);
        } else {
            tvClock.setText(currentTime);
        }

        // OPTIMIZATION: Cache color calculation (only recalculate every 1 second)
        // This reduces expensive isLightColor() calls from 1/sec to 0.001/sec
        long currentTime_ms = System.currentTimeMillis();
        int textColor = cachedClockTextColor;
        
        if (cardClock != null && (currentTime_ms - lastClockColorCalcTime) >= CLOCK_COLOR_CACHE_MS) {
            int backgroundColor = ((ColorStateList) cardClock.getCardBackgroundColor()).getDefaultColor();
            
            // Only recalculate if background color changed
            if (backgroundColor != lastClockBackgroundColor) {
                lastClockBackgroundColor = backgroundColor;
                boolean isLightBackground = isLightColor(backgroundColor);
                textColor = isLightBackground ? Color.BLACK : Color.WHITE;
                cachedClockTextColor = textColor;
                lastClockColorCalcTime = currentTime_ms;
            }
        } else if (cardClock == null) {
            // Fallback to theme-based coloring if card is null
            String currentTheme = sharedPreferencesManager.sharedPreferences.getString(
                com.fadcam.Constants.PREF_APP_THEME,
                Constants.DEFAULT_APP_THEME
            );
            if ("Crimson Bloom".equals(currentTheme)) {
                textColor = Color.WHITE;
            } else if ("Premium Gold".equals(currentTheme)) {
                textColor = Color.BLACK;
            } else {
                textColor = Color.WHITE;
            }
            cachedClockTextColor = textColor;
        }
        
        tvClock.setTextColor(textColor);
        tvDateEnglish.setTextColor(textColor);
        tvDateArabic.setTextColor(textColor);

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
        String displayDateEnglish = displayOption == 1 || displayOption == 2 ? currentDateEnglish : "";
        String displayDateArabic = displayOption == 2 ? currentDateArabic : "";

        boolean showEnglish = displayOption == 1 || displayOption == 2;
        boolean showArabic = displayOption == 2;
        tvDateEnglish.setVisibility(showEnglish ? View.VISIBLE : View.GONE);
        tvDateArabic.setVisibility(showArabic ? View.VISIBLE : View.GONE);

        if (displayOption == 0) {
            tvClock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, isLandscapeMode() ? 18f : 16f);
            tvClock.setPadding(0, 0, 0, 0);
        } else if (displayOption == 1) {
            tvClock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, isLandscapeMode() ? 16f : 14f);
            tvClock.setPadding(0, 0, 0, 0);
        } else {
            tvClock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, isLandscapeMode() ? 15f : 13f);
            tvClock.setPadding(0, 0, 0, 0);
        }

        if (layoutClockInner != null) {
            int horizontalPadding = dpToPxInt(4);
            int verticalPadding = displayOption == 0 ? dpToPxInt(4) : dpToPxInt(3);
            layoutClockInner.setPadding(
                    horizontalPadding,
                    verticalPadding,
                    horizontalPadding,
                    verticalPadding);
            layoutClockInner.setGravity(Gravity.CENTER_VERTICAL);
        }
        if (layoutClockContent != null) {
            layoutClockContent.setGravity(Gravity.CENTER_VERTICAL);
            int contentVerticalPadding = displayOption == 0 ? dpToPxInt(1) : 0;
            layoutClockContent.setPadding(0, contentVerticalPadding, 0, contentVerticalPadding);
            layoutClockContent.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        }

        if (tvDateEnglish instanceof com.fadcam.ui.utils.AnimatedTextView) {
            ((com.fadcam.ui.utils.AnimatedTextView) tvDateEnglish).animateSlot(displayDateEnglish, 400);
        } else {
            tvDateEnglish.setText(displayDateEnglish);
        }
        tvDateEnglish.setPadding(0, 0, 0, 0);
        tvDateEnglish.setSingleLine(true);
        tvDateEnglish.setEllipsize(null);
        tvDateEnglish.setHorizontallyScrolling(false);
        tvDateEnglish.setGravity(Gravity.START);

        if (tvDateArabic instanceof com.fadcam.ui.utils.AnimatedTextView) {
            ((com.fadcam.ui.utils.AnimatedTextView) tvDateArabic).animateSlot(displayDateArabic, 400);
        } else {
            tvDateArabic.setText(displayDateArabic);
        }
        tvDateArabic.setPadding(0, 0, 0, 0);
        tvDateArabic.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        tvDateArabic.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_END);
        tvDateArabic.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        tvDateArabic.setTextDirection(View.TEXT_DIRECTION_RTL);
        ViewGroup.LayoutParams arabicParams = tvDateArabic.getLayoutParams();
        if (arabicParams instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) arabicParams).gravity = Gravity.END;
            tvDateArabic.setLayoutParams(arabicParams);
        }
    }

    /**
     * Update storage info and timer displays.
     * Changed from private to protected to allow FadRecHomeFragment to override with screen recording logic.
     */
    protected void updateStorageInfo() {
        // NOTE: Logging here is minimized to reduce spam during 1s update loop in startUpdatingInfo()

        // -----------

        // Step 1: Try to use cached storage info for instant display
        StorageInfoCache.StorageInfo cachedInfo =
            StorageInfoCache.getCachedStorageInfo();
        if (cachedInfo != null) {
            // Silent - called every 1 second during recording; no frequent logging
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
                FLog.e(TAG, "Error calculating storage info", e);
            }
        });

    }

    protected boolean suppressDefaultCameraRowUpdates() {
        return false;
    }

    protected boolean suppressDefaultElapsedRowUpdates() {
        return false;
    }

    protected boolean usesModeSpecificPreviewBehavior() {
        return false;
    }

    @StringRes
    protected int getPreviewEnableHintResId() {
        return R.string.preview_enable_hint;
    }

    /**
     * Allows mode-specific fragments to intercept preview long-press without
     * forking the entire HomeFragment preview interaction flow.
     */
    protected boolean handleModeSpecificPreviewLongPress() {
        return false;
    }

    protected void updateModeSpecificPreviewVisibility() {
    }

    protected void onModeSpecificPreviewSurfaceChanged(@Nullable Surface surface, int width, int height) {
    }

    protected boolean isModePausedForElapsedAppearance() {
        return isPaused();
    }

    protected boolean isModeRecordingForElapsedAppearance() {
        return isRecording();
    }

    protected void refreshElapsedHeroAppearance() {
        updateElapsedHeroAppearance();
    }

    protected void requestAnimateNextPreviewTransition() {
        animateNextPreviewTransition = true;
    }

    protected boolean consumeAnimateNextPreviewTransition() {
        boolean shouldAnimate = animateNextPreviewTransition;
        animateNextPreviewTransition = false;
        return shouldAnimate;
    }

    protected void setPreviewOpenAnimating(boolean animating) {
        isPreviewOpenAnimating = animating;
    }

    protected void setPreviewCloseAnimating(boolean animating) {
        isPreviewCloseAnimating = animating;
    }

    protected void setPendingPreviewReveal(boolean pending) {
        pendingIrisOpenReveal = pending;
    }

    protected void runHomeAvatarState(boolean enabled, boolean animate) {
        applyHomeAvatarState(enabled, animate);
    }

    protected void runHintVisibilityAnimated(boolean show) {
        setHintVisibilityAnimated(show);
    }

    protected void runIrisOpenReveal() {
        performIrisOpenReveal();
    }

    /**
     * Updates storage UI with cached storage information for instant display
     */
    private void updateStorageUiWithCachedInfo(
        StorageInfoCache.StorageInfo storageInfo
    ) {
        // Check if fragment is still attached to context
        if (!isAdded()) {
            return;
        }

        // Calculate elapsed time and estimates
        long elapsedTime = 0;
        long estimatedBytesUsed = 0;

        if (isRecording() || isPaused()) {
            // Always read from SharedPreferences - service is the source of truth
            android.content.SharedPreferences prefs = sharedPreferencesManager.sharedPreferences;
            long serviceStartTime = prefs.getLong(
                Constants.PREF_RECORDING_START_TIME,
                0
            );
            long pauseStartedAt = prefs.getLong(
                Constants.PREF_RECORDING_PAUSE_STARTED_AT,
                0
            );
            long accumulatedPausedDuration = prefs.getLong(
                Constants.PREF_RECORDING_ACCUMULATED_PAUSED_DURATION,
                0
            );
            
            if (serviceStartTime > 0) {
                long anchorTime = (isPaused() && pauseStartedAt > 0L)
                    ? pauseStartedAt
                    : SystemClock.elapsedRealtime();
                elapsedTime = Math.max(
                    0,
                    anchorTime - serviceStartTime - accumulatedPausedDuration
                );
                // Verbose timer logging removed - called too frequently (every 1s during recording)
            } else {
                FLog.e(TAG, "❌ Timer: Service start time is ZERO in SharedPreferences!");
                elapsedTime = 0;
            }

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
        }
        // Note: Do NOT reset recordingStartTime here!
        // Only service broadcasts should set this value.
        // Resetting here causes race condition during orientation changes.

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
            FLog.d(
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
            if (camType == com.fadcam.CameraType.FRONT) {
                cameraLabel = getString(R.string.mainpage_camera_front);
            } else if (camType.isDual()) {
                cameraLabel = getString(R.string.button_settings_cam_dual);
            } else {
                cameraLabel = getString(R.string.mainpage_camera_back);
            }
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
        final float availableFraction = gbTotal > 0d
            ? (float) Math.max(0d, Math.min(1d, gbAvailable / gbTotal))
            : 0f;
        final boolean showLiveRemaining = isRecording() || isPaused();
        final String finalTimeLeftText = showLiveRemaining
            ? formatRemainingTime(days, hours, minutes, seconds)
            : selectedEstimate;
        final String elapsedTimeText = String.format(
            Locale.getDefault(),
            "%02d:%02d",
            elapsedMinutes,
            elapsedSeconds
        );
        latestElapsedDisplay = elapsedTimeText;

        // Update UI on main thread
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                    boolean animateCameraRow = cameraRowUiInitialized;
                    if (!suppressDefaultCameraRowUpdates()) {
                        // Camera row
                        if (tvCameraTitle != null) {
                            String oldCamTitle = tvCameraTitle.getText() != null ? tvCameraTitle.getText().toString() : "";
                            if (!oldCamTitle.equals(finalCameraLabel)) {
                                if (animateCameraRow && tvCameraTitle instanceof com.fadcam.ui.utils.AnimatedTextView) {
                                    ((com.fadcam.ui.utils.AnimatedTextView) tvCameraTitle).animateSlotFull(finalCameraLabel, 400);
                                } else {
                                    tvCameraTitle.setText(finalCameraLabel);
                                }
                            }
                        }
                        if (tvCameraSubtitle != null) {
                            String oldCamSub = tvCameraSubtitle.getText() != null ? tvCameraSubtitle.getText().toString() : "";
                            if (!oldCamSub.equals(cameraSubtitle)) {
                                if (animateCameraRow && tvCameraSubtitle instanceof com.fadcam.ui.utils.AnimatedTextView) {
                                    ((com.fadcam.ui.utils.AnimatedTextView) tvCameraSubtitle).animateSlotFull(cameraSubtitle, 400);
                                } else {
                                    tvCameraSubtitle.setText(cameraSubtitle);
                                }
                            }
                        }
    
                        // Update camera icon
                        try {
                            com.fadcam.CameraType camType =
                                sharedPreferencesManager.getCameraSelection();
                            if (ivCameraIcon != null) {
                                String newIconText;
                                if (camType == com.fadcam.CameraType.FRONT) {
                                    newIconText = "camera_front";
                                } else if (camType.isDual()) {
                                    newIconText = "switch_video";
                                } else {
                                    newIconText = "camera_alt";
                                }
                                String oldIconText = ivCameraIcon.getText() != null
                                        ? ivCameraIcon.getText().toString() : "";
                                if (!oldIconText.equals(newIconText)) {
                                    if (animateCameraRow && ivCameraIcon instanceof com.fadcam.ui.utils.AnimatedTextView) {
                                        ((com.fadcam.ui.utils.AnimatedTextView) ivCameraIcon)
                                                .animateCrossfade(newIconText, 300);
                                    } else {
                                        ivCameraIcon.setText(newIconText);
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                    cameraRowUiInitialized = true;

                    // Time-left row
                    if (tvEstimateTitle != null) {
                        String oldEstimate = tvEstimateTitle.getText() != null ? tvEstimateTitle.getText().toString() : "";
                        if (!oldEstimate.equals(finalTimeLeftText)) {
                            if (tvEstimateTitle instanceof com.fadcam.ui.utils.AnimatedTextView) {
                                ((com.fadcam.ui.utils.AnimatedTextView) tvEstimateTitle).animateSlotDown(finalTimeLeftText, 400);
                            } else {
                                tvEstimateTitle.setText(finalTimeLeftText);
                            }
                        }
                    }
                    if (tvEstimateSubtitle != null) tvEstimateSubtitle.setText(
                        getString(R.string.recording_time_left)
                    );
                    applyTimeLeftAccentPreference();

                    // Space row — value only on tvSpaceTitle, static total on tvSpaceTotal.
                    // Available space decreases during recording, so we animate DOWN.
                    if (tvSpaceTitle != null) {
                        String oldSpaceValue = tvSpaceTitle.getText() != null ? tvSpaceTitle.getText().toString() : "";
                        if (!oldSpaceValue.equals(availableSpace)) {
                            if (tvSpaceTitle instanceof com.fadcam.ui.utils.AnimatedTextView) {
                                ((com.fadcam.ui.utils.AnimatedTextView) tvSpaceTitle).animateSlotDown(availableSpace, 400);
                            } else {
                                tvSpaceTitle.setText(availableSpace);
                            }
                        }
                    }
                    if (tvSpaceTotal != null) {
                        // Show static "/ XX.X GB" — set once, never animate.
                        String totalStr = String.format(numberFormatLocale, "/ %.2f GB", gbTotal);
                        tvSpaceTotal.setText(totalStr);
                    }
                    applyStorageTotalVisibilityPreference();
                    if (tvSpaceSubtitle != null) {
                        String newSubtitle = getString(R.string.storage_available_space);
                        if (tvSpaceSubtitle instanceof com.fadcam.ui.utils.AnimatedTextView) {
                            ((com.fadcam.ui.utils.AnimatedTextView) tvSpaceSubtitle).animateSlot(newSubtitle, 400);
                        } else {
                            tvSpaceSubtitle.setText(newSubtitle);
                        }
                    }
                    if (storageProgressRing != null) {
                        storageProgressRing.setProgress(availableFraction);
                    }
                    applyStorageIndicatorStylePreference();

                    if (!suppressDefaultElapsedRowUpdates()) {
                        // Elapsed row — time increases, animate UP.
                        if (tvElapsedTitle != null) {
                            String oldElapsed = tvElapsedTitle.getText() != null ? tvElapsedTitle.getText().toString() : "";
                            if (!oldElapsed.equals(elapsedTimeText)) {
                                if (tvElapsedTitle instanceof com.fadcam.ui.utils.AnimatedTextView) {
                                    ((com.fadcam.ui.utils.AnimatedTextView) tvElapsedTitle).animateSlot(elapsedTimeText, 400);
                                } else {
                                    tvElapsedTitle.setText(elapsedTimeText);
                                }
                            }
                        }
                        if (tvElapsedSubtitle != null) {
                            String newElapsedSub = getString(R.string.recording_elapsed_time);
                            if (tvElapsedSubtitle instanceof com.fadcam.ui.utils.AnimatedTextView) {
                                ((com.fadcam.ui.utils.AnimatedTextView) tvElapsedSubtitle).animateSlot(newElapsedSub, 400);
                            } else {
                                tvElapsedSubtitle.setText(newElapsedSub);
                            }
                        }
                        updateElapsedHeroAppearance();
                        applyElapsedAlignmentPreference();
                        applyElapsedSizePreference();
                        applyElapsedFontPreference();
                        applyElapsedFlagPreference();
                    }
                    if (!suppressDefaultElapsedRowUpdates()) {
                        updateStartStopButtonForFoldedState();
                    }

                });
        }
    }

    /**
     * Transitions the Start/Stop button between states.
     *
     * <p>The button icon and background tint are applied immediately via {@code applyChanges}.
     * If the button is an {@link com.fadcam.ui.utils.AnimatedMaterialButton}, the text label
     * animates with a slot-machine slide.  Otherwise {@link MaterialButton#setText} is called
     * directly.
     *
     * @param button       The target button.
     * @param newText      New label text.
     * @param applyChanges Runnable that sets icon, backgroundTint, enabled state, etc.
     * @param slideUp      {@code true} → label slides UP (Start → Stop); {@code false} → DOWN.
     */
    protected void applyButtonTransition(
            @NonNull MaterialButton button,
            @NonNull CharSequence newText,
            @Nullable android.graphics.drawable.Drawable newIcon,
            @NonNull Runnable applyOtherChanges) {
        // State-sync paths should be deterministic and non-animated. These methods run during
        // lifecycle restores, service callbacks, and tab switches; animating here causes the
        // play/stop icon to blink when returning to Home even though the user did not click.
        applyOtherChanges.run();
        if (button instanceof com.fadcam.ui.utils.AnimatedMaterialButton) {
            ((com.fadcam.ui.utils.AnimatedMaterialButton) button).cancelAnimation();
        }
        if (newIcon != null || button.getIcon() != null) {
            button.setIcon(newIcon);
        }
        button.setText(newText);
    }

    protected void animateButtonTransition(
            @NonNull MaterialButton button,
            @NonNull CharSequence newText,
            @Nullable android.graphics.drawable.Drawable newIcon,
            @NonNull Runnable applyOtherChanges,
            boolean slideUp) {
        applyOtherChanges.run();

        Drawable currentIcon = button.getIcon();
        boolean iconChanged = !areEquivalentDrawables(currentIcon, newIcon);

        if (button instanceof com.fadcam.ui.utils.AnimatedMaterialButton) {
            com.fadcam.ui.utils.AnimatedMaterialButton animated =
                    (com.fadcam.ui.utils.AnimatedMaterialButton) button;
            if (iconChanged) {
                animated.animateIcon(newIcon, 300);
            }
            CharSequence current = animated.getText();
            if (current == null || !current.toString().equals(newText.toString())) {
                if (slideUp) {
                    animated.animateSlotFull(newText, 300);
                } else {
                    animated.animateSlotFullDown(newText, 300);
                }
            }
        } else {
            if (iconChanged) {
                button.setIcon(newIcon);
            }
            button.setText(newText);
        }
    }

    private boolean areEquivalentDrawables(@Nullable Drawable current, @Nullable Drawable next) {
        if (current == next) {
            return true;
        }
        if (current == null || next == null) {
            return false;
        }

        Drawable.ConstantState currentState = current.getConstantState();
        Drawable.ConstantState nextState = next.getConstantState();
        if (currentState != null && nextState != null) {
            return currentState.equals(nextState);
        }

        return current.getClass().equals(next.getClass())
                && current.getIntrinsicWidth() == next.getIntrinsicWidth()
                && current.getIntrinsicHeight() == next.getIntrinsicHeight();
    }

    private String formatRemainingTime(
        long days,
        long hours,
        long minutes,
        long seconds
    ) {
        StringBuilder remainingTime = new StringBuilder();
        if (days > 0) {
            remainingTime.append(
                String.format(Locale.getDefault(), "%dd ", days)
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
            return "\u221e";
        }

        // Calculate seconds, handling potential overflow
        long recordingSeconds;
        try {
            recordingSeconds = (availableBytes * 8) / bitrate;
        } catch (Exception e) {
            FLog.e(TAG, "Error calculating recording time estimate", e);
            recordingSeconds = 0;
        }

        // Ensure non-negative values
        recordingSeconds = Math.max(0, recordingSeconds);

        long recordingDays = recordingSeconds / (24 * 3600);
        long recordingHours = (recordingSeconds % (24 * 3600)) / 3600;
        long recordingMinutes = (recordingSeconds % 3600) / 60;
        long remainingSeconds = recordingSeconds % 60;
        return formatRemainingTime(
            recordingDays,
            recordingHours,
            recordingMinutes,
            remainingSeconds
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
        FLog.d(
            TAG,
            "refreshPrefsAndUpdateStorage: preference change detected, refreshing UI"
        );

        // Ensure fragment is attached before attempting UI updates
        if (!isAdded() || getActivity() == null) {
            FLog.w(
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
            FLog.e(
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
                    FLog.e(
                        TAG,
                        "refreshPrefsAndUpdateStorage: error updating UI",
                        e
                    );
                }
            });
    }

    // update storage and stats in real time while recording is started
    private void startUpdatingInfo() {
        // IMPORTANT: Check if already running to prevent duplicate handlers
        // Similar fix to clock update - prevent calling startUpdatingInfo twice in lifecycle
        if (updateInfoRunnable != null) {
            // Already running - don't create a second handler
            return;
        }

        // Create a new runnable
        updateInfoRunnable = new Runnable() {
            private int updateCounter = 0; // Counter for less frequent stats updates

            @Override
            public void run() {
                boolean isFragmentAdded = isAdded();
                boolean isRecordingActive = isRecording();
                boolean isPausedActive = isPaused();
                boolean shouldUpdate = (isRecordingActive || isPausedActive) && isFragmentAdded;
                
                if (shouldUpdate) {
                    // Always update storage info (lightweight)
                    // Timer calculation now reads directly from SharedPreferences in updateStorageUiWithCachedInfo
                    updateStorageInfo();

                    // Update stats every 3 seconds during recording for live feedback
                    updateCounter++;
                    if (updateCounter >= 3) {
                        FLog.d(
                            TAG,
                            "📊 Stats Update Trigger: counter=" + updateCounter + 
                            " (should call updateStats() every 3s for live data)"
                        );
                        updateStats();
                        updateCounter = 0;
                    } else {
                        FLog.d(TAG, "⏱️  Storage update only (counter=" + updateCounter + "/3)");
                    }

                    handlerClock.postDelayed(this, 1000); // Update storage every second
                } else {
                    FLog.d(
                        TAG,
                        "⛔ Update Loop Stopped: isAdded=" + isFragmentAdded + 
                        ", isRecording=" + isRecordingActive + 
                        ", isPaused=" + isPausedActive
                    );
                    stopUpdatingInfo(); // Clean up if recording state changed
                }
            }
        };

        // Post immediately to start updates
        handlerClock.post(updateInfoRunnable);
        FLog.d(TAG, "startUpdatingInfo: Started real-time storage/stats updates (1s intervals)");
    }

    private void stopUpdatingInfo() {
        if (updateInfoRunnable != null) {
            handlerClock.removeCallbacks(updateInfoRunnable);
            updateInfoRunnable = null;
            // Only log when we actually stop (not at every opportunity)
            FLog.d(TAG, "stopUpdatingInfo: Stopped real-time storage/stats updates");
        }
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Lifecycle Management - Pause/Resume handlers when app backgrounded
    // ────────────────────────────────────────────────────────────────────────────────
    
    /**
     * Setup lifecycle observer to pause/resume update handlers.
     * Prevents battery drain when app is backgrounded.
     */
    private void setupLifecycleObserver() {
        getLifecycle().addObserver(new androidx.lifecycle.LifecycleEventObserver() {
            @Override
            public void onStateChanged(@NonNull androidx.lifecycle.LifecycleOwner source, 
                                       @NonNull androidx.lifecycle.Lifecycle.Event event) {
                if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                    FLog.d(TAG, "[LifecycleObserver] Fragment paused - pausing update handlers");
                    pauseUpdateHandlers();
                } else if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    FLog.d(TAG, "[LifecycleObserver] Fragment resumed - resuming update handlers");
                    resumeUpdateHandlers();
                } else if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) {
                    FLog.d(TAG, "[LifecycleObserver] Fragment destroyed - cleaning up handlers");
                    cleanupUpdateHandlers();
                }
            }
        });
    }
    
    /**
     * Pause all update handlers (clock, storage, stats) when fragment is backgrounded.
     * This prevents unnecessary CPU/battery drain while app is not visible.
     */
    private void pauseUpdateHandlers() {
        FLog.d(TAG, "pauseUpdateHandlers: Pausing clock and info handlers");
        
        // Stop clock updates
        if (updateClockRunnable != null) {
            handlerClock.removeCallbacks(updateClockRunnable);
            FLog.d(TAG, "pauseUpdateHandlers: Clock handler paused");
        }
        
        // Stop storage + stats updates
        if (updateInfoRunnable != null) {
            handlerClock.removeCallbacks(updateInfoRunnable);
            FLog.d(TAG, "pauseUpdateHandlers: Info handler (storage + stats) paused");
        }
        
        // Pause background executor for file scanning
        if (executorService != null && !executorService.isShutdown()) {
            FLog.d(TAG, "pauseUpdateHandlers: Shutting down executor service");
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                    FLog.w(TAG, "pauseUpdateHandlers: Executor service shutdown timeout");
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
            executorService = null;
        }
    }
    
    /**
     * Resume all update handlers when fragment is foreground again.
     * Only resumes if recording or paused.
     */
    private void resumeUpdateHandlers() {
        FLog.d(TAG, "resumeUpdateHandlers: Attempting to resume handlers");
        
        if (!isAdded()) {
            FLog.w(TAG, "resumeUpdateHandlers: Fragment not attached, skipping");
            return;
        }
        
        // Resume clock updates
        if (isRecording() || isPaused()) {
            if (updateClockRunnable != null) {
                handlerClock.post(updateClockRunnable);
                FLog.d(TAG, "resumeUpdateHandlers: Clock handler resumed");
            } else {
                startUpdatingClock();
            }
            
            // Resume storage + stats updates
            if (updateInfoRunnable != null) {
                handlerClock.post(updateInfoRunnable);
                FLog.d(TAG, "resumeUpdateHandlers: Info handler (storage + stats) resumed");
            } else {
                startUpdatingInfo();
            }
        } else {
            FLog.d(TAG, "resumeUpdateHandlers: Not recording or paused, handlers remain stopped");
        }
        
        // Re-create executor service if needed
        if (executorService == null || executorService.isShutdown()) {
            executorService = java.util.concurrent.Executors.newSingleThreadExecutor();
            FLog.d(TAG, "resumeUpdateHandlers: Executor service re-created");
        }
    }
    
    /**
     * Cleanup all handlers before fragment is destroyed.
     * Ensures no handler leaks or dangling background tasks.
     */
    private void cleanupUpdateHandlers() {
        FLog.d(TAG, "cleanupUpdateHandlers: Cleaning up all handlers");
        
        // Remove all pending clock callbacks
        if (updateClockRunnable != null) {
            handlerClock.removeCallbacks(updateClockRunnable);
            updateClockRunnable = null;
            FLog.d(TAG, "cleanupUpdateHandlers: Clock handler cleaned");
        }
        
        // Remove all pending info callbacks
        if (updateInfoRunnable != null) {
            handlerClock.removeCallbacks(updateInfoRunnable);
            updateInfoRunnable = null;
            FLog.d(TAG, "cleanupUpdateHandlers: Info handler cleaned");
        }
        
        // Shutdown executor service completely
        if (executorService != null && !executorService.isShutdown()) {
            FLog.d(TAG, "cleanupUpdateHandlers: Shutting down executor service");
            try {
                executorService.shutdown();
                if (!executorService.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    List<java.lang.Runnable> remaining = executorService.shutdownNow();
                    FLog.w(TAG, "cleanupUpdateHandlers: Force shutdown, " + remaining.size() + 
                           " tasks not executed");
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
            executorService = null;
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
            FLog.i(
                TAG,
                "refreshStats: Clearing stats cache and updating stats."
            );
            updateStats();
        }
    }

    // --- Updated updateStats Method ---

    private void updateStats() {
        FLog.d(TAG, "updateStats: Starting calculation...");

        // -----------

        // Check if recording is in progress - if so, force fresh calculation for live
        // updates
        boolean isRecording = sharedPreferencesManager.isRecordingInProgress();

        // Step 1: Try to display cached stats instantly (only if not recording)
        if (!isRecording) {
            VideoStatsCache.VideoStats cachedStats =
                VideoStatsCache.getCachedStats(sharedPreferencesManager);
            if (cachedStats != null && cachedStats.isValid()) {
                FLog.d(
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
            FLog.d(
                TAG,
                "Recording in progress - forcing fresh stats calculation for live updates"
            );
        }

        FLog.d(TAG, "No valid cached stats found - calculating fresh stats");

        // Step 2: Calculate fresh stats in background
        if (executorService == null || executorService.isShutdown()) {
            FLog.w(TAG, "ExecutorService not available for updateStats");
            executorService = Executors.newSingleThreadExecutor();
        }

        executorService.submit(() -> {
            // --- Primary source: Room DB (always, regardless of recording state) ---
            try {
                com.fadcam.data.VideoIndexRepository repo =
                    com.fadcam.data.VideoIndexRepository.getInstance(requireContext());
                long[] quickStats = repo.getQuickStats();
                int dbCount = (int) quickStats[0];
                long dbTotalBytes = quickStats[1];
                long dbTotalMB = dbTotalBytes / (1024 * 1024);

                if (isRecording) {
                    // Add the live size of the in-progress segment so "Used" updates in real time.
                    // ActiveRecordingStats.getActiveFileSizeBytes() is cheap (File.length() or a
                    // SAF ContentResolver query — both well under 1 ms on the background thread).
                    long activeBytes = com.fadcam.ActiveRecordingStats.getActiveFileSizeBytes(requireContext());
                    long activeMB = activeBytes / (1024 * 1024);
                    long totalMBWithActive = dbTotalMB + activeMB;
                    FLog.d(TAG, "updateStats BG: Recording — DB=" + dbTotalMB + "MB + live=" + activeMB + "MB = " + totalMBWithActive + "MB, count=" + dbCount);
                    VideoStatsCache.updateStats(sharedPreferencesManager, dbCount, totalMBWithActive);
                    updateStatsUI(dbCount, totalMBWithActive);
                    return;
                }

                FLog.d(TAG, "updateStats BG: Room DB stats: " + dbCount + " videos, " + dbTotalMB + "MB");
                VideoStatsCache.updateStats(sharedPreferencesManager, dbCount, dbTotalMB);
                updateStatsUI(dbCount, dbTotalMB);

                // Not recording: also run delta scan if index was invalidated
                // (e.g. immediately after recording stopped, to pick up the new file).
                if (repo.isIndexInvalidated()) {
                    FLog.d(TAG, "updateStats BG: Index invalidated, running delta scan to pick up new files");
                    repo.getVideos(sharedPreferencesManager); // Consumes invalidation flag + delta scans
                    long[] freshStats = repo.getQuickStats();
                    int freshCount = (int) freshStats[0];
                    long freshMB = freshStats[1] / (1024 * 1024);
                    if (freshCount != dbCount || freshMB != dbTotalMB) {
                        FLog.d(TAG, "updateStats BG: Delta scan found changes: " + freshCount + " videos, " + freshMB + "MB");
                        VideoStatsCache.updateStats(sharedPreferencesManager, freshCount, freshMB);
                        updateStatsUI(freshCount, freshMB);
                    }
                }
                return; // DB is the source of truth — done
            } catch (Exception e) {
                FLog.w(TAG, "updateStats BG: Room DB query failed, falling back to scan: " + e.getMessage());
            }

            // --- Fallback: Full scan (only if Room DB failed) ---
            String storageMode = sharedPreferencesManager.getStorageMode();
            String customUriString =
                sharedPreferencesManager.getCustomStorageUri();
            List<VideoItem> primaryItems;

            // --- Try to use shared session cache first (from VideoSessionCache) ---
            // IMPORTANT: Skip cache during active recording to ensure fresh file scans
            // for live size updates. Only use cache when not recording.
            FLog.d(TAG, "updateStats BG: Checking for shared session cache... (isRecording=" + isRecording + ")");
            if (com.fadcam.utils.VideoSessionCache.isSessionCacheValid() && !isRecording) {
                FLog.d(
                    TAG,
                    "updateStats BG: Using shared session cache (" +
                    com.fadcam.utils.VideoSessionCache.getSessionCachedVideos().size() +
                    " items) - eliminating duplicate scanning"
                );
                primaryItems = new ArrayList<>(
                    com.fadcam.utils.VideoSessionCache.getSessionCachedVideos()
                );

                // CRITICAL FIX: Since we're using shared cache, populate it for future
                // cross-fragment use
                com.fadcam.utils.VideoSessionCache.updateSessionCache(
                    primaryItems
                );
            } else {
                if (isRecording) {
                    FLog.d(TAG, "updateStats BG: Skipping session cache during active recording - forcing fresh file scan for live updates");
                }
                // --- Load File Lists (Same logic as RecordsFragment) ---
                FLog.d(
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
                        FLog.e(
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
                        FLog.e(
                            TAG,
                            "BG updateStats: Permission error or invalid URI for custom location: " +
                            customUriString
                        );
                        primaryItems = new ArrayList<>();
                    }
                } else {
                    primaryItems = getInternalRecordsList();
                }

                // Update shared session cache for other fragments
                com.fadcam.utils.VideoSessionCache.updateSessionCache(
                    primaryItems
                );
            }

            // Use only primary items (temp files are deprecated - OpenGL pipeline no longer uses temp_ prefix)
            List<VideoItem> combinedItems = primaryItems;

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

            FLog.d(
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
                FLog.d(
                    TAG,
                    "Updated session cache from HomeFragment to prevent duplicate scanning in RecordsFragment"
                );
            }

            FLog.d(
                TAG,
                "Updated stats cache with fresh data - cross-fragment synchronization complete"
            );

            // --- Update UI on Main Thread ---
            updateStatsUI(numVideos, totalSizeMB);
        });

    }

    /**
     * Updates the stats UI with video count and size information
     *
     * @param numVideos   Total number of videos
     * @param totalSizeMB Total size in MB
     */
    private void updateStatsUI(int numVideos, long totalSizeMB) {
        if (getActivity() != null) {
            final int[] videoCount = {numVideos};
            final long[] sizeInMB = {totalSizeMB};

            FLog.d(TAG, "updateStatsUI CALLED: Input values = " + videoCount[0] + " videos, " + sizeInMB[0] + "MB");

            getActivity().runOnUiThread(() -> {
                if (tvVideoCount == null && tvVideoSize == null) {
                    FLog.w(TAG, "updateStatsUI: stat views are null, skipping.");
                    return;
                }

                // Defensive 0-value check: never show 0 if previous was >0
                if (videoCount[0] == 0 && lastKnownVideoCount > 0) {
                    FLog.w(TAG, "updateStatsUI: Detected 0 videos but previous was " +
                           lastKnownVideoCount + " - suppressing possible race condition");
                    videoCount[0] = (int) lastKnownVideoCount;
                }
                if (sizeInMB[0] == 0 && lastKnownSizeMB > 0) {
                    FLog.w(TAG, "updateStatsUI: Detected 0 MB but previous was " +
                           lastKnownSizeMB + " - suppressing possible race condition");
                    sizeInMB[0] = lastKnownSizeMB;
                }

                if (videoCount[0] > 0) lastKnownVideoCount = videoCount[0];
                if (sizeInMB[0] > 0) lastKnownSizeMB = sizeInMB[0];

                long totalSizeBytes = sizeInMB[0] * 1024L * 1024L;
                String totalSizeFormatted = (getContext() != null)
                    ? Formatter.formatFileSize(getContext(), totalSizeBytes)
                    : String.format(Locale.US, "%.2f GB", totalSizeBytes / (1024.0 * 1024.0 * 1024.0));

                lastAnimatedVideoCount = videoCount[0];
                lastAnimatedSizeMB = sizeInMB[0];

                // Video count — increases, animate UP.
                String countText = String.valueOf(videoCount[0]);
                if (tvVideoCount instanceof com.fadcam.ui.utils.AnimatedTextView) {
                    ((com.fadcam.ui.utils.AnimatedTextView) tvVideoCount).animateSlot(countText, 400);
                } else if (tvVideoCount != null) {
                    tvVideoCount.setText(countText);
                }

                // Used size — increases, animate UP.
                if (tvVideoSize instanceof com.fadcam.ui.utils.AnimatedTextView) {
                    ((com.fadcam.ui.utils.AnimatedTextView) tvVideoSize).animateSlot(totalSizeFormatted, 400);
                } else if (tvVideoSize != null) {
                    tvVideoSize.setText(totalSizeFormatted);
                }

                FLog.d(TAG, "updateStatsUI: ✅ Updated stats - " + videoCount[0] + " videos, " + totalSizeFormatted);
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
            FLog.e(TAG, "Error checking SAF permission", e);
            return false;
        }
    }

    private List<VideoItem> getInternalRecordsList() {
        List<VideoItem> items = new ArrayList<>();
        File recordsDir = getContext() != null
            ? getContext().getExternalFilesDir(null)
            : null;
        if (recordsDir == null) {
            FLog.e(
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
            FLog.e(
                TAG,
                "Context or treeUri null in getSafRecordsListProgressive"
            );
            return items;
        }

        FLog.d(TAG, "SAF scan: Starting scan for URI: " + treeUri);

        DocumentFile dir = DocumentFile.fromTreeUri(context, treeUri);
        if (dir == null || !dir.isDirectory() || !dir.canRead()) {
            FLog.e(
                TAG,
                "Cannot read/access SAF dir in getSafRecordsListProgressive: " +
                treeUri
            );
            return items;
        }

        try {
            DocumentFile[] allFiles = dir.listFiles();
            if (allFiles == null) {
                FLog.w(TAG, "No files found in SAF directory: " + treeUri);
                return items;
            }

            FLog.d(
                TAG,
                "Progressive SAF scan starting: " +
                allFiles.length +
                " files to process in " + dir.getName()
            );

            // Process files in chunks to avoid blocking
            final int CHUNK_SIZE = 10;
            final int CHUNK_DELAY_MS = 10;

            for (int i = 0; i < allFiles.length; i += CHUNK_SIZE) {
                int endIndex = Math.min(i + CHUNK_SIZE, allFiles.length);

                // Process chunk
                for (int j = i; j < endIndex; j++) {
                    DocumentFile file = allFiles[j];
                    if (file != null && file.isFile()) {
                        String fileName = file.getName();
                        String mimeType = file.getType();
                        boolean isValidExtension = fileName != null && (
                            fileName.endsWith("." + Constants.RECORDING_FILE_EXTENSION) ||
                            "video/mp4".equals(mimeType)
                        );
                        
                        // Debug logging: show why each file was accepted or rejected
                        if (!isValidExtension) {
                            FLog.d(TAG, "SAF scan: Skipped '" + fileName + "' - extension=" + isValidExtension + ", mime=" + mimeType);
                        }
                        
                        if (isValidExtension) {
                            items.add(
                                new VideoItem(
                                    file.getUri(),
                                    fileName,
                                    file.length(),
                                    file.lastModified()
                                )
                            );
                            FLog.d(TAG, "SAF scan: Added '" + fileName + "' (" + file.length() + " bytes)");
                        }
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
                        FLog.w(TAG, "Progressive SAF scan interrupted");
                        break;
                    }
                }
            }

            FLog.d(
                TAG,
                "Progressive SAF scan completed: " +
                items.size() +
                " video items found (out of " +
                allFiles.length +
                " total files)"
            );
        } catch (Exception e) {
            FLog.e(TAG, "Error in progressive SAF scan for " + treeUri, e);
        }
        return items;
    }

    // Keep old method for backward compatibility but mark as deprecated
    @Deprecated
    private List<VideoItem> getSafRecordsList(Uri treeUri) {
        // Redirect to progressive version without callback
        return getSafRecordsListProgressive(treeUri, null);
    }




    private void pauseRecording() {
        FLog.d(TAG, "pauseRecording: Pausing video recording");

        // ── Dual Camera path ──────────────────────────────────────────
        if (isDualRecordingActive) {
            pauseDualRecording();
            return;
        }

        buttonPauseResume.setIcon(
            AppCompatResources.getDrawable(requireContext(), R.drawable.play_button_rounded)
        );
        buttonPauseResume.setEnabled(false);

        // Keep camera switch button ENABLED for live switching during pause
        // Don't disable it here

        Intent stopIntent = new Intent(getActivity(), RecordingService.class);
        stopIntent.setAction(Constants.INTENT_ACTION_PAUSE_RECORDING);
        requireActivity().startService(stopIntent);
    }

    private void resumeRecording() {
        FLog.d(TAG, "resumeRecording: Resuming video recording");

        // ── Dual Camera path ──────────────────────────────────────────
        if (isDualRecordingActive) {
            resumeDualRecording();
            return;
        }

        buttonPauseResume.setIcon(
            AppCompatResources.getDrawable(
                requireContext(),
                R.drawable.pause_rounded
            )
        );
        buttonPauseResume.setEnabled(false);

        // Keep camera switch button ENABLED for live switching during resume
        // Don't disable it here

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
                        FLog.d(
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
                        FLog.d(
                            TAG,
                            "Applied initial default tint to exposure tile"
                        );
                    }
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
                    // theme)-----------
                }
            } catch (Exception ignored) {}

            tileAfToggle.setOnClickListener(v -> {
                // show overlay to pick AF mode
                FLog.d(
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
                // Reload exposure value from SharedPreferences before showing picker
                // This ensures UI reflects any changes made by web/RecordingService
                currentEvIndex =
                    sharedPreferencesManager.getSavedExposureCompensation();
                FLog.d(
                    TAG,
                    "Exposure tile clicked. Opening slider exposure picker"
                );
                FLog.d(
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
                        sharedPreferencesManager.setExposureCompensationRange(
                            min,
                            max
                        );
                        sharedPreferencesManager.setExposureCompensationStep(
                            stepFloat
                        );
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
                FLog.d(
                    "HomeFragment",
                    "EXPOSURE PICKER CREATED - showing dialog"
                );
                evSlider.show(getParentFragmentManager(), "ev_slider_sheet");
            });

            tileZoom.setOnClickListener(v -> {
                FLog.d(
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
            FLog.w(
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

        for (float z = 0.5f; z <= max + 0.001f; z += 0.5f) {
            list.add(((float) Math.round(z * 10)) / 10f);
        }
        if (!list.contains(1.0f)) {
            list.add(1.0f);
        }
        Collections.sort(list);
        return list;
    }

    private void applyPreviewPinchZoom(float scaleFactor) {
        if (!isAdded() || getContext() == null) return;
        CameraType cam = sharedPreferencesManager.getCameraSelection();
        if (cam == null || cam.isDual()) return;

        float minZoom = 0.5f;
        float maxZoom = getHardwareSupportedMaxZoomRatio(cam);
        float base = previewPinchZoomRatio > 0f
            ? previewPinchZoomRatio
            : sharedPreferencesManager.getSpecificZoomRatio(cam);
        float next = Math.max(minZoom, Math.min(maxZoom, base * scaleFactor));
        previewPinchZoomRatio = next;
        previewUiScale = Math.max(1.0f, Math.min(4.0f, next));
        applyPreviewTransform();
        sharedPreferencesManager.setSpecificZoomRatio(cam, next);
        updatePreviewZoomHudUi(next);

        long now = System.currentTimeMillis();
        if (now - previewZoomDispatchMs < 60L) return;
        previewZoomDispatchMs = now;

        try {
            Intent zoomIntent = RecordingControlIntents.setZoomRatio(requireContext(), next);
            zoomIntent.setClass(requireContext(), RecordingService.class);
            requireContext().startService(zoomIntent);
        } catch (Exception e) {
            FLog.w(TAG, "Failed to dispatch pinch zoom update: " + e.getMessage());
        }
    }

    private void applyPreviewTransform() {
        if (textureView == null) return;
        float w = textureView.getWidth();
        float h = textureView.getHeight();
        if (w <= 0f || h <= 0f) return;

        float maxPanX = (w * (previewUiScale - 1f)) / 2f;
        float maxPanY = (h * (previewUiScale - 1f)) / 2f;
        previewUiPanX = Math.max(-maxPanX, Math.min(maxPanX, previewUiPanX));
        previewUiPanY = Math.max(-maxPanY, Math.min(maxPanY, previewUiPanY));

        android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.postScale(previewUiScale, previewUiScale, w / 2f, h / 2f);
        matrix.postTranslate(previewUiPanX, previewUiPanY);
        textureView.setTransform(matrix);
        updatePreviewZoomMapUi();
    }

    private void applyNormalizedPreviewPan(float normalizedPanX, float normalizedPanY) {
        float clampedPanX = Math.max(-1.0f, Math.min(1.0f, normalizedPanX));
        float clampedPanY = Math.max(-1.0f, Math.min(1.0f, normalizedPanY));
        if (textureView == null || previewUiScale <= 1.001f) {
            previewUiPanX = 0f;
            previewUiPanY = 0f;
            return;
        }

        float width = textureView.getWidth();
        float height = textureView.getHeight();
        float maxPanX = (width * (previewUiScale - 1f)) / 2f;
        float maxPanY = (height * (previewUiScale - 1f)) / 2f;
        previewUiPanX = -clampedPanX * maxPanX;
        previewUiPanY = -clampedPanY * maxPanY;
    }

    private float getCurrentNormalizedPreviewPanX() {
        if (textureView == null || previewUiScale <= 1.001f) {
            return 0f;
        }
        float maxPanX = (textureView.getWidth() * (previewUiScale - 1f)) / 2f;
        if (maxPanX <= 0f) {
            return 0f;
        }
        return Math.max(-1.0f, Math.min(1.0f, -previewUiPanX / maxPanX));
    }

    private float getCurrentNormalizedPreviewPanY() {
        if (textureView == null || previewUiScale <= 1.001f) {
            return 0f;
        }
        float maxPanY = (textureView.getHeight() * (previewUiScale - 1f)) / 2f;
        if (maxPanY <= 0f) {
            return 0f;
        }
        return Math.max(-1.0f, Math.min(1.0f, -previewUiPanY / maxPanY));
    }

    private void dispatchCurrentPreviewZoomPan() {
        if (sharedPreferencesManager == null) {
            return;
        }

        CameraType cam = sharedPreferencesManager.getCameraSelection();
        float normalizedPanX = getCurrentNormalizedPreviewPanX();
        float normalizedPanY = getCurrentNormalizedPreviewPanY();
        sharedPreferencesManager.setSpecificZoomRatio(cam, previewPinchZoomRatio);
        sharedPreferencesManager.setSpecificPan(cam, normalizedPanX, normalizedPanY);
        refreshZoomTileTintFromState();

        if (!isMyServiceRunning(com.fadcam.services.RecordingService.class)) {
            return;
        }

        try {
            Intent zoomIntent = com.fadcam.RecordingControlIntents.setZoomRatio(
                requireContext(),
                previewPinchZoomRatio,
                normalizedPanX,
                normalizedPanY
            );
            requireActivity().startService(zoomIntent);
        } catch (Exception e) {
            FLog.w(TAG, "Failed to dispatch preview pan update: " + e.getMessage());
        }
    }

    private void resetPreviewTransform() {
        previewUiScale = 1.0f;
        previewUiPanX = 0f;
        previewUiPanY = 0f;
        if (textureView != null) {
            textureView.setTransform(null);
        }
        updatePreviewZoomHudUi(1.0f);
    }

    private void requestPreviewParentIntercept(View child, boolean disallow) {
        if (child == null) return;
        android.view.ViewParent parent = child.getParent();
        while (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow);
            parent = parent.getParent();
        }
    }

    private void updateMainSwipeGestureGate(boolean isGestureActive) {
        if (!(getActivity() instanceof MainActivity)) return;
        ((MainActivity) getActivity()).setPreviewGestureInProgress(isGestureActive, previewPinchZoomRatio);
    }

    private void ensureTextureViewSurfaceForPreviewStart() {
        if (textureView == null || !textureView.isAvailable()) {
            return;
        }
        SurfaceTexture st = textureView.getSurfaceTexture();
        if (st == null) {
            return;
        }
        if (textureViewSurface != null) {
            try {
                textureViewSurface.release();
            } catch (Exception ignored) {
            }
        }
        textureViewSurface = new Surface(st);
    }

    private void schedulePreviewOnlySurfacePushBurst() {
        if (!isPreviewOnlyStartPending || !isAdded()) return;
        ensureTextureViewSurfaceForPreviewStart();
        if (textureView == null || textureViewSurface == null || !textureViewSurface.isValid()) return;
        updateServiceWithCurrentSurface(textureViewSurface, textureView.getWidth(), textureView.getHeight());
        textureView.postDelayed(() -> {
            if (isPreviewOnlyStartPending && textureViewSurface != null && textureViewSurface.isValid()) {
                updateServiceWithCurrentSurface(textureViewSurface, textureView.getWidth(), textureView.getHeight());
            }
        }, 160L);
        textureView.postDelayed(() -> {
            if (isPreviewOnlyStartPending && textureViewSurface != null && textureViewSurface.isValid()) {
                updateServiceWithCurrentSurface(textureViewSurface, textureView.getWidth(), textureView.getHeight());
            }
        }, 420L);
        textureView.postDelayed(() -> {
            if (isPreviewOnlyStartPending && textureViewSurface != null && textureViewSurface.isValid()) {
                updateServiceWithCurrentSurface(textureViewSurface, textureView.getWidth(), textureView.getHeight());
            }
        }, 760L);
    }

    private void schedulePreviewOnlyStartTimeout() {
        if (!isPreviewOnlyStartPending) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (previewOnlyStartPendingDeadlineMs <= 0L) {
            previewOnlyStartPendingDeadlineMs = now + 1800L;
        }
        long remainingMs = previewOnlyStartPendingDeadlineMs - now;
        if (remainingMs <= 0L) {
            isPreviewOnlyStartPending = false;
            previewOnlyStartPendingDeadlineMs = 0L;
            if (tvPreviewHint != null) {
                tvPreviewHint.setText(getPreviewEnableHintResId());
            }
            setHintVisibilityAnimated(true);
            return;
        }
        if (pendingPreviewOnlyStartTimeoutRunnable != null) {
            previewOnlyStartHandler.removeCallbacks(pendingPreviewOnlyStartTimeoutRunnable);
        }
        pendingPreviewOnlyStartTimeoutRunnable = () -> {
            if (!isAdded() || !isPreviewOnlyStartPending || isPreviewOnlyActive) {
                return;
            }
            long remaining = previewOnlyStartPendingDeadlineMs - SystemClock.elapsedRealtime();
            if (remaining > 0L) {
                previewOnlyStartHandler.postDelayed(pendingPreviewOnlyStartTimeoutRunnable, Math.min(remaining, 350L));
                return;
            }
            isPreviewOnlyStartPending = false;
            previewOnlyStartPendingDeadlineMs = 0L;
            if (tvPreviewHint != null) {
                tvPreviewHint.setText(getPreviewEnableHintResId());
            }
            setHintVisibilityAnimated(true);
            Toast.makeText(requireContext(), "Preview could not start. Try long-press again.", Toast.LENGTH_SHORT).show();
        };
        previewOnlyStartHandler.postDelayed(
                pendingPreviewOnlyStartTimeoutRunnable,
                Math.min(remainingMs, 1200L));
    }

    private void setupPreviewZoomHud() {
        if (btnPreviewZoomReset != null) {
            btnPreviewZoomReset.setOnClickListener(v -> {
                CameraType cam = sharedPreferencesManager.getCameraSelection();
                previewPinchZoomRatio = 1.0f;
                previewUiScale = 1.0f;
                previewUiPanX = 0f;
                previewUiPanY = 0f;
                sharedPreferencesManager.setSpecificZoomRatio(cam, 1.0f);
                applyPreviewTransform();
                updatePreviewZoomHudUi(1.0f);
                try {
                    Intent zoomIntent = RecordingControlIntents.setZoomRatio(requireContext(), 1.0f);
                    zoomIntent.setClass(requireContext(), RecordingService.class);
                    requireContext().startService(zoomIntent);
                } catch (Exception e) {
                    FLog.w(TAG, "Failed to dispatch zoom reset: " + e.getMessage());
                }
            });
        }
        updatePreviewZoomHudUi(previewPinchZoomRatio);
    }

    private void updatePreviewZoomHudUi(float zoomRatio) {
        if (containerPreviewZoomHud == null || textPreviewZoomHud == null) return;
        textPreviewZoomHud.setText(String.format(java.util.Locale.getDefault(), "%.1fx", zoomRatio));
        boolean show = zoomRatio > 1.01f;
        containerPreviewZoomHud.setVisibility(show ? View.VISIBLE : View.GONE);
        if (btnPreviewZoomReset != null) {
            btnPreviewZoomReset.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        updatePreviewZoomMapUi();
    }

    private void updatePreviewZoomMapUi() {
        if (containerPreviewZoomMap == null || viewPreviewZoomMapViewport == null || textureView == null) return;
        if (containerPreviewZoomMap instanceof ViewGroup) {
            ((ViewGroup) containerPreviewZoomMap).setClipChildren(true);
            ((ViewGroup) containerPreviewZoomMap).setClipToPadding(true);
        }
        int mapW;
        int mapH;
        String orientation = sharedPreferencesManager.getVideoOrientation();
        boolean portrait = orientation == null || !orientation.toLowerCase(java.util.Locale.US).contains("landscape");
        float density = getResources().getDisplayMetrics().density;
        if (portrait) {
            mapW = Math.round(42f * density);
            mapH = Math.round(56f * density);
        } else {
            mapW = Math.round(56f * density);
            mapH = Math.round(42f * density);
        }
        ViewGroup.LayoutParams mapLp = containerPreviewZoomMap.getLayoutParams();
        if (mapLp != null && (mapLp.width != mapW || mapLp.height != mapH)) {
            mapLp.width = mapW;
            mapLp.height = mapH;
            containerPreviewZoomMap.setLayoutParams(mapLp);
        }

        int actualMapW = containerPreviewZoomMap.getWidth() > 0 ? containerPreviewZoomMap.getWidth() : mapW;
        int actualMapH = containerPreviewZoomMap.getHeight() > 0 ? containerPreviewZoomMap.getHeight() : mapH;

        float scale = Math.max(1.0f, previewUiScale);
        int vpW = Math.max(8, Math.min(actualMapW, Math.round(actualMapW / scale)));
        int vpH = Math.max(8, Math.min(actualMapH, Math.round(actualMapH / scale)));
        ViewGroup.LayoutParams vpLp = viewPreviewZoomMapViewport.getLayoutParams();
        if (vpLp != null && (vpLp.width != vpW || vpLp.height != vpH)) {
            vpLp.width = vpW;
            vpLp.height = vpH;
            viewPreviewZoomMapViewport.setLayoutParams(vpLp);
        }

        float viewW = textureView.getWidth();
        float viewH = textureView.getHeight();
        float maxPanX = (viewW * (scale - 1f)) / 2f;
        float maxPanY = (viewH * (scale - 1f)) / 2f;
        float nx = 0.5f;
        float ny = 0.5f;
        if (maxPanX > 0f) {
            nx = (maxPanX - previewUiPanX) / (2f * maxPanX);
        }
        if (maxPanY > 0f) {
            ny = (maxPanY - previewUiPanY) / (2f * maxPanY);
        }
        nx = Math.max(0f, Math.min(1f, nx));
        ny = Math.max(0f, Math.min(1f, ny));
        float tx = (actualMapW - vpW) * nx;
        float ty = (actualMapH - vpH) * ny;
        tx = Math.max(0f, Math.min(Math.max(0f, actualMapW - vpW), tx));
        ty = Math.max(0f, Math.min(Math.max(0f, actualMapH - vpH), ty));
        viewPreviewZoomMapViewport.setTranslationX(tx);
        viewPreviewZoomMapViewport.setTranslationY(ty);
    }

    private void syncPreviewZoomStateFromPrefs(boolean forceResetPan) {
        if (sharedPreferencesManager == null) return;
        CameraType cam = sharedPreferencesManager.getCameraSelection();
        if (cam == null) return;
        float savedZoom = sharedPreferencesManager.getSpecificZoomRatio(cam);
        float savedPanX = sharedPreferencesManager.getSpecificPanX(cam);
        float savedPanY = sharedPreferencesManager.getSpecificPanY(cam);
        previewPinchZoomRatio = savedZoom;
        previewUiScale = Math.max(1.0f, Math.min(4.0f, savedZoom));
        if (forceResetPan || previewUiScale <= 1.001f) {
            previewUiPanX = 0f;
            previewUiPanY = 0f;
            isPanningPreview = false;
            previewLongPressTriggered = false;
            updateMainSwipeGestureGate(false);
        } else {
            applyNormalizedPreviewPan(savedPanX, savedPanY);
        }
        applyPreviewTransform();
        updatePreviewZoomHudUi(previewPinchZoomRatio);
        refreshZoomTileTintFromState();
    }

    private void refreshExposureTileTintFromState() {
        if (tileExp == null || !isAdded()) {
            return;
        }

        boolean isSnowVeilTheme = "Snow Veil".equals(
            sharedPreferencesManager.sharedPreferences.getString(
                Constants.PREF_APP_THEME,
                Constants.DEFAULT_APP_THEME
            )
        );

        if (aeLocked || currentEvIndex != 0) {
            tileExp.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.orange_accent)
            );
        } else if (isSnowVeilTheme) {
            tileExp.setTextColor(Color.parseColor("#424242"));
        } else {
            android.util.TypedValue typedValue = new android.util.TypedValue();
            requireContext().getTheme().resolveAttribute(
                com.google.android.material.R.attr.colorOnSurface,
                typedValue,
                true
            );
            tileExp.setTextColor(typedValue.data);
        }

        tileExp.setScaleX(aeLocked ? 1.05f : 1f);
        tileExp.setScaleY(aeLocked ? 1.05f : 1f);
    }

    private void refreshZoomTileTintFromState() {
        if (tileZoom == null || !isAdded() || sharedPreferencesManager == null) {
            return;
        }

        CameraType currentCamera = sharedPreferencesManager.getCameraSelection();
        float currentZoom = sharedPreferencesManager.getSpecificZoomRatio(currentCamera);
        boolean isSnowVeilTheme = "Snow Veil".equals(
            sharedPreferencesManager.sharedPreferences.getString(
                Constants.PREF_APP_THEME,
                Constants.DEFAULT_APP_THEME
            )
        );

        if (Math.abs(currentZoom - 1.0f) > 0.01f) {
            tileZoom.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.orange_accent)
            );
        } else if (isSnowVeilTheme) {
            tileZoom.setTextColor(Color.parseColor("#424242"));
        } else {
            android.util.TypedValue typedValue = new android.util.TypedValue();
            requireContext().getTheme().resolveAttribute(
                com.google.android.material.R.attr.colorOnSurface,
                typedValue,
                true
            );
            tileZoom.setTextColor(typedValue.data);
        }
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
            FLog.w(TAG, "Zoom ratio query failed: " + e.getMessage());
        }
        return defaultMaxZoom;
    }

    /**
     * Get the actual camera ID for the given camera type.
     * Helper method for zoom functionality.
     */
    private String getActualCameraIdForType(CameraType cameraType) {
        // DUAL_PIP doesn't map to a single camera — fall back to BACK for capability queries
        if (cameraType != null && cameraType.isDual()) {
            cameraType = CameraType.BACK;
        }
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
            FLog.w(
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
        FLog.d(TAG, "setVideoBitrate: Set to " + videoBitrate + " bps");
    }

    // --- Stop Recording ---
    private void stopRecording() {
        if (!isAdded() || getActivity() == null) {
            FLog.w(TAG, "Stop: Not attached");
            return;
        }
        if (recordingState == RecordingState.NONE) {
            FLog.w(TAG, "Stop clicked but state NONE?");
            return;
        } // Prevent multi-stop

        // ── Dual Camera path ──────────────────────────────────────────
        if (isDualRecordingActive) {
            stopDualRecording();
            return;
        }

        FLog.i(TAG, ">> stopRecording user action");
        disableInteractionButtons();
        FLog.d(TAG, "stopRecording: Btns disabled.");

        Intent stopIntent = new Intent(getActivity(), RecordingService.class);
        stopIntent.setAction(Constants.INTENT_ACTION_STOP_RECORDING);
        try {
            requireActivity().startService(stopIntent);
            FLog.i(TAG, "Sent STOP intent.");
        } catch (Exception e) {
            FLog.e(TAG, "Error sending STOP intent: ", e);
            Toast.makeText(
                getContext(),
                "Error stop svc",
                Toast.LENGTH_SHORT
            ).show();
            resetUIButtonsToIdleState();
            FLog.d(TAG, "stopIntent fail: UI Reset.");
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
            FLog.d(TAG, "Font copied to internal storage.");
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
        // Prevent multiple simultaneous camera switches
        if (isCameraSwitchInProgress) {
            FLog.w(TAG, "Camera switch already in progress, ignoring duplicate request");
            return;
        }

        // ── Dual Camera path — swap PiP cameras ──────────────────────
        if (isDualRecordingActive) {
            swapDualCameras();
            Toast.makeText(getContext(),
                    getString(R.string.dual_cameras_swapped),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // Determine target camera type (opposite of current)
        CameraType currentType = sharedPreferencesManager.getCameraSelection();

        // DUAL_PIP toggle is handled by swapDualCameras() above — if we reach here with
        // DUAL_PIP it means the dual check didn't match (e.g. stale state). Ignore the tap.
        if (currentType.isDual()) {
            FLog.w(TAG, "toggleCamera: DUAL_PIP reached single-camera toggle path — ignoring");
            return;
        }

        CameraType targetType = (currentType == CameraType.BACK) ? CameraType.FRONT : CameraType.BACK;

        // Check if recording or preview-only is active
        if (isRecording() || isPreviewOnlyActive) {
            // Live switch during recording: send Intent to RecordingService
            FLog.d(TAG, "Recording active, initiating live camera switch: " + currentType + " → " + targetType);
            
            isCameraSwitchInProgress = true; // Set flag to prevent duplicate requests
            Intent switchIntent = new Intent(getActivity(), RecordingService.class);
            switchIntent.setAction(Constants.INTENT_ACTION_SWITCH_CAMERA);
            switchIntent.putExtra(Constants.INTENT_EXTRA_CAMERA_TYPE_SWITCH, targetType.toString());
            
            if (getActivity() != null) {
                getActivity().startService(switchIntent);
            } else {
                FLog.e(TAG, "Activity context is null, cannot send camera switch intent");
                Toast.makeText(getContext(), "Error: Cannot access activity context", Toast.LENGTH_SHORT).show();
                isCameraSwitchInProgress = false;
                return;
            }

            // Show feedback - keep button ENABLED so user can see it's responsive
            vibrateTouch();
            Toast.makeText(getContext(),
                    "Switching to " + (targetType == CameraType.FRONT ? "front" : "rear") + " camera...",
                    Toast.LENGTH_SHORT).show();
            FLog.d(TAG, "Camera switch intent sent, button stays enabled for responsiveness");
        } else {
            // Not recording: update preference only (old behavior)
            FLog.d(TAG, "Not recording, updating camera preference: " + currentType + " → " + targetType);
            
            sharedPreferencesManager.sharedPreferences
                .edit()
                .putString(
                    Constants.PREF_CAMERA_SELECTION,
                    targetType.toString()
                )
                .apply();

            String message = (targetType == CameraType.FRONT) 
                ? getString(R.string.switched_front_camera)
                : getString(R.string.switched_rear_camera);
            
            FLog.d(TAG, "Camera preference updated to " + targetType);
            vibrateTouch();
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            updateMirrorButtonVisibilityAndState();
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
        // Clean up bubble rotation animation (legacy - ivBubbleBackground now null)
        if (ivBubbleBackground != null) {
            ivBubbleBackground.clearAnimation();
            bubbleRotationAnimation = null;
        }

        // Clean up preview avatar animators
        stopBubbleRotation(); // calls stopHomeBlinkLoop + breathing + floating
        stopHeaderBlinkLoop(); // clean up header logo blink loop
        
        super.onDestroyView();
        TorchService.setHomeFragment(null);

        // Safely unregister the torch receiver
        if (torchReceiver != null) {
            try {
                requireContext().unregisterReceiver(torchReceiver);
            } catch (IllegalArgumentException e) {
                FLog.w(
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

        // Clean up all update handlers to prevent leaks
        cleanupUpdateHandlers();
        updateMainSwipeGestureGate(false);
        if (pendingPreviewOnlyStartTimeoutRunnable != null) {
            previewOnlyStartHandler.removeCallbacks(pendingPreviewOnlyStartTimeoutRunnable);
        }

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
                FLog.d(TAG, "No camera with flash found");
                buttonTorchSwitch.setEnabled(false);
                buttonTorchSwitch.setVisibility(View.GONE);
            } else {
                FLog.d(TAG, "Flash available on camera: " + cameraId);
                buttonTorchSwitch.setEnabled(true);
                buttonTorchSwitch.setVisibility(View.VISIBLE);
            }
        } catch (CameraAccessException e) {
            FLog.e(TAG, "Camera access error: " + e.getMessage());
            e.printStackTrace();
            buttonTorchSwitch.setEnabled(false);
            buttonTorchSwitch.setVisibility(View.GONE);
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void setupTorchButton() {
        if (buttonTorchSwitch == null) {
            FLog.e(TAG, "buttonTorchSwitch is null, cannot set up listener.");
            return;
        }

        // Initial state update
        // updateTorchButtonState(isTorchOn); // This might be called too early,
        // consider moving or ensuring isTorchOn is accurate

        buttonTorchSwitch.setOnClickListener(v -> {
            if (!isAdded() || getContext() == null) {
                FLog.w(
                    TAG,
                    "Torch button clicked, but fragment not fully ready."
                );
                return;
            }

            if (isRecordingOrPaused() || isPreviewOnlyActive) {
                boolean dualRunning = isMyServiceRunning(DualCameraRecordingService.class);
                FLog.d(
                    TAG,
                    "Recording active. Sending toggle intent. dualRunning=" + dualRunning + ", isTorchOn=" +
                    isTorchOn
                );
                Intent serviceIntent = new Intent(
                    getContext(),
                    dualRunning ? DualCameraRecordingService.class : RecordingService.class
                );
                serviceIntent.setAction(
                    Constants.INTENT_ACTION_TOGGLE_RECORDING_TORCH
                );
                try {
                    ServiceStartPolicy.startRecordingAction(requireContext(), serviceIntent);
                } catch (Exception e) {
                    FLog.e(
                        TAG,
                        "Error starting RecordingService for torch toggle",
                        e
                    );
                }
            } else {
                FLog.d(
                    TAG,
                    "Not recording. Toggling torch directly. Current isTorchOn: " +
                    isTorchOn
                );
                // Assuming toggleTorch() correctly handles CameraManager.setTorchMode,
                // updates isTorchOn field, and calls updateTorchUI().
                toggleTorch();
            }
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
            // method(setupTorchButton_correct_torch_receiver_registration)-----
            isTorchReceiverRegistered = true;
            FLog.d(
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
            FLog.e(TAG, "CameraManager not available to toggle torch.");
            return;
        }
        String currentCameraId = getCameraIdForTorch(); // Helper to get current camera ID
        if (currentCameraId == null) {
            FLog.e(TAG, "No valid camera ID found for torch.");
            return;
        }

        try {
            isTorchOn = !isTorchOn;
            cameraManager.setTorchMode(currentCameraId, isTorchOn);
            
            // Update SharedPreferences so RemoteStreamManager can read current torch state
            android.content.SharedPreferences prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(getContext());
            prefs.edit()
                .putBoolean(Constants.PREF_TORCH_STATE, isTorchOn)
                .apply();
            
            FLog.d(
                TAG,
                "Torch toggled directly via CameraManager. New state: " +
                isTorchOn +
                " for camera " +
                currentCameraId
            );
            updateTorchUI(isTorchOn); // Update button appearance and any other UI
        } catch (CameraAccessException e) {
            FLog.e(TAG, "Failed to toggle torch directly", e);
            isTorchOn = !isTorchOn; // Revert state on error
            updateTorchUI(isTorchOn); // Update UI back
            Utils.showQuickToast(getContext(), "Error toggling torch.");
        } catch (IllegalArgumentException e) {
            FLog.e(
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
            FLog.e(TAG, "Error accessing camera for torch ID", e);
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
            FLog.d(
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
                FLog.d(TAG, "Added 'Both Torches' option");
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

                        FLog.d(
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
            FLog.e(TAG, "Error accessing camera: " + e.getMessage());
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
                FLog.d(TAG, "Found camera with flash: " + id);
                return id;
            }
        }
        FLog.d(TAG, "No camera with flash found");
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
                || DualCameraRecordingService.class.getName().equals(
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
                            FLog.w(
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
                        FLog.d(
                            "TorchDebug",
                            "Torch UI updated (selected state): " +
                            isOn +
                            ", Enabled: " +
                            buttonTorchSwitch.isEnabled()
                        );
                    } catch (Exception e) {
                        FLog.e(
                            "TorchDebug",
                            "Error updating torch UI: " + e.getMessage()
                        );
                    }
                });
        }
    }

    private void setupAppLogoLongPressListener(View view) {
        if (ivAppTitle != null) {
            ivAppTitle.setOnLongClickListener(v -> {
                performHapticFeedback();
                FLog.i(
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
                        FLog.e(
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
                    FLog.e(TAG, "Manual navigation to TrashFragment failed.", e);
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

    /**
     * Plays a nice slide-up reveal animation for the app logo in the header.
     * Matches the timing and style of the bottom navigation dock reveal.
     */
    private void startLogoRevealAnimation() {
        if (ivAppTitle == null) return;

        // Only animate once per process session — skip if already played
        // (e.g. returning after switching to FadRec/other mode).
        if (sLogoAnimationPlayed) {
            ivAppTitle.setAlpha(1f);
            ivAppTitle.setTranslationY(0f);
            return;
        }

        // Respect system animation scale (accessibility)
        if (isAnimationDisabled(ivAppTitle)) {
            ivAppTitle.setAlpha(1f);
            ivAppTitle.setTranslationY(0f);
            sLogoAnimationPlayed = true;
            return;
        }

        // Initial hidden state
        ivAppTitle.setAlpha(0f);

        // Defer until layout to ensure precise positioning, though SLIDE_DP is fixed
        ivAppTitle.post(() -> {
            if (!isAdded()) return;
            
            float density = getResources().getDisplayMetrics().density;
            float slideY = 18f * density; // Match DockRevealAnimator.SLIDE_DP

            ivAppTitle.setTranslationY(slideY);
            ivAppTitle.setAlpha(0f);

            sLogoAnimationPlayed = true; // Mark before starting — prevents double-fire

            ivAppTitle.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(450) // Match DockRevealAnimator.EXPAND_DURATION
                    .setStartDelay(50) // Match DockRevealAnimator.START_DELAY_MS
                    .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 1.0f, 0.3f, 1.0f))
                    .start();
        });
    }

    /**
     * Returns true if the user has disabled animations via accessibility or
     * developer options (global animation scale = 0).
     */
    private boolean isAnimationDisabled(View view) {
        try {
            android.content.ContentResolver cr = view.getContext().getContentResolver();
            float scale = android.provider.Settings.Global.getFloat(cr,
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            return scale == 0f;
        } catch (Exception ignored) {
            return false;
        }
    }

    // ── Header Logo Style (Default FadCam text vs Animated Avatar) ──────────

    /**
     * Reads the header logo preference and swaps the header ImageView between
     * the static FadCam logo and a small animated avatar that blinks.
     * Safe to call repeatedly (e.g. on resume) — only mutates when the pref changes.
     */
    private void applyHeaderLogoStyle() {
        if (ivAppTitle == null || sharedPreferencesManager == null) return;
        String style = sharedPreferencesManager.sharedPreferences.getString(
                Constants.PREF_HEADER_LOGO_STYLE, Constants.HEADER_LOGO_DEFAULT);

        if (Constants.HEADER_LOGO_AVATAR.equals(style)) {
            // Show the avatar idle drawable (respects eye color) and start blink loop.
            ivAppTitle.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            ivAppTitle.setImageResource(resolveHomeDrawable(RES_IDLE));
            startHeaderBlinkLoop();
        } else {
            // Restore default FadCam text logo.
            stopHeaderBlinkLoop();
            ivAppTitle.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            ivAppTitle.setImageResource(R.drawable.menu_icon_unknown);
        }
    }

    /** Starts a random blink loop on the header logo avatar. */
    private void startHeaderBlinkLoop() {
        stopHeaderBlinkLoop();
        // Initial delay before first blink
        scheduleNextHeaderBlink(2000 + headerBlinkRandom.nextInt(2000));
    }

    private void scheduleNextHeaderBlink(long delayMs) {
        headerBlinkRunnable = () -> {
            if (ivAppTitle == null || !ivAppTitle.isAttachedToWindow()) return;
            // Only blink when still in avatar mode
            String style = (sharedPreferencesManager != null)
                    ? sharedPreferencesManager.sharedPreferences.getString(
                            Constants.PREF_HEADER_LOGO_STYLE, Constants.HEADER_LOGO_DEFAULT)
                    : Constants.HEADER_LOGO_DEFAULT;
            if (!Constants.HEADER_LOGO_AVATAR.equals(style)) return;

            ivAppTitle.setImageResource(resolveHomeDrawable(RES_BLINK));
            android.graphics.drawable.Drawable d = ivAppTitle.getDrawable();
            if (d instanceof android.graphics.drawable.Animatable2) {
                ((android.graphics.drawable.Animatable2) d).registerAnimationCallback(
                    new android.graphics.drawable.Animatable2.AnimationCallback() {
                        @Override public void onAnimationEnd(android.graphics.drawable.Drawable drawable) {
                            if (ivAppTitle != null && ivAppTitle.isAttachedToWindow()) {
                                ivAppTitle.setImageResource(resolveHomeDrawable(RES_IDLE));
                                scheduleNextHeaderBlink(3000 + headerBlinkRandom.nextInt(3000));
                            }
                        }
                    });
                ((android.graphics.drawable.Animatable2) d).start();
            } else if (d instanceof android.graphics.drawable.Animatable) {
                ((android.graphics.drawable.Animatable) d).start();
                ivAppTitle.postDelayed(() -> {
                    if (ivAppTitle != null && ivAppTitle.isAttachedToWindow()) {
                        ivAppTitle.setImageResource(resolveHomeDrawable(RES_IDLE));
                        scheduleNextHeaderBlink(3000 + headerBlinkRandom.nextInt(3000));
                    }
                }, 260);
            }
        };
        headerBlinkHandler.postDelayed(headerBlinkRunnable, delayMs);
    }

    /** Stops the header blink loop. */
    private void stopHeaderBlinkLoop() {
        if (headerBlinkRunnable != null) {
            headerBlinkHandler.removeCallbacks(headerBlinkRunnable);
            headerBlinkRunnable = null;
        }
    }

    /**
     * Wires the "Stats" card to navigate to the Records tab.
     * Applies only to Home and is safe across configuration changes.
     */
    private void setupStatsCardNavigation(@NonNull View root) {
        try {
            CardView stats = root.findViewById(R.id.cardStats);
            if (stats == null) return;
            stats.setClickable(true);
            stats.setFocusable(true);
            stats.setOnClickListener(v -> {
                animatePressBounce(v, () -> {
                    performHapticFeedback();
                    try {
                        if (getActivity() instanceof com.fadcam.MainActivity) {
                            com.fadcam.MainActivity act = (com.fadcam.MainActivity) getActivity();
                            act.switchFragment(1, true);
                        }
                    } catch (Exception e) {
                        FLog.e(TAG, "Failed to navigate to Records from Stats card", e);
                    }
                });
            });
        } catch (Exception e) {
            FLog.e(TAG, "setupStatsCardNavigation error", e);
        }
    }

    private void initializeViews(View view) {
        FLog.d(TAG, "initializeViews: Finding UI elements.");
        homeRootLayout = (view instanceof ConstraintLayout) ? (ConstraintLayout) view : null;
        tvCameraTitle = view.findViewById(R.id.tvCameraTitle);
        tvCameraSubtitle = view.findViewById(R.id.tvCameraSubtitle);
        ivCameraIcon = view.findViewById(R.id.ivCameraIcon);
        tvEstimateTitle = view.findViewById(R.id.tvEstimateTitle);
        tvEstimateSubtitle = view.findViewById(R.id.tvEstimateSubtitle);
        ivEstimateIcon = view.findViewById(R.id.ivEstimateIcon);
        tvSpaceTitle = view.findViewById(R.id.tvSpaceTitle);
        tvSpaceSubtitle = view.findViewById(R.id.tvSpaceSubtitle);
        storageProgressRing = view.findViewById(R.id.storageProgressRing);
        // ...existing code...
        tvElapsedTitle = view.findViewById(R.id.tvElapsedTitle);
        tvElapsedSubtitle = view.findViewById(R.id.tvElapsedSubtitle);
        cardElapsedHero = view.findViewById(R.id.cardElapsedHero);
        tvElapsedStateIcon = view.findViewById(R.id.tvElapsedStateIcon);
        ivElapsedAccent = view.findViewById(R.id.ivElapsedAccent);
        layoutElapsedContent = view.findViewById(R.id.layoutElapsedContent);
        layoutElapsedMetaRow = view.findViewById(R.id.layoutElapsedMetaRow);
        rowStorageAvailable = view.findViewById(R.id.rowStorageAvailable);
        rowEstimateTime = view.findViewById(R.id.rowEstimateTime);
        cameraRowUiInitialized = false;
        layoutCards = view.findViewById(R.id.layoutCards);
        layoutCardRailSection = view.findViewById(R.id.layoutCardRailSection);
        leftPanel = view.findViewById(R.id.leftPanel);
        rightPanel = view.findViewById(R.id.rightPanel);
        cardRailTogglePortrait = view.findViewById(R.id.cardRailTogglePortrait);
        cardRailToggleLandscape = view.findViewById(R.id.cardRailToggleLandscape);
        ivCardRailTogglePortrait = view.findViewById(R.id.ivCardRailTogglePortrait);
        ivCardRailToggleLandscape = view.findViewById(R.id.ivCardRailToggleLandscape);
        tvRemainingTitle = null;
        tvRemainingSubtitle = null;
        btnHamburgerMenu = view.findViewById(R.id.btnHamburgerMenu);
        hamburgerBadgeDot = view.findViewById(R.id.hamburgerBadgeDot);
        ivAppTitle = view.findViewById(R.id.ivAppTitle);
        // Set up header logo click handler for Privacy Black Mode
        if (ivAppTitle != null) {
            ivAppTitle.setOnClickListener(v -> {
                if (sharedPreferencesManager.isPrivacyBlackModeEnabled()) {
                    Intent intent = new Intent(requireContext(), PrivacyBlackActivity.class);
                    startActivity(intent);
                } else {
                    Toast.makeText(requireContext(), R.string.privacy_black_enable_needed_hint, Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Update hamburger badge visibility
        updateHamburgerBadgeVisibility();

        // Set up hamburger menu click handler
        if (btnHamburgerMenu != null) {
            btnHamburgerMenu.setOnClickListener(v -> {
                HomeSidebarFragment sidebar = HomeSidebarFragment.newInstance();
                sidebar.show(getParentFragmentManager(), "HomeSidebar");
            });
        }

        tvPreviewPlaceholder = view.findViewById(R.id.tvPreviewPlaceholder);
        tvPreviewHint = view.findViewById(R.id.tvPreviewHint);
        buttonStartStop = view.findViewById(R.id.buttonStartStop);
        buttonPauseResume = view.findViewById(R.id.buttonPauseResume);
        buttonCamSwitch = view.findViewById(R.id.buttonCamSwitch);
        buttonMirrorSwitch = view.findViewById(R.id.buttonMirrorSwitch);
        cardPreview = view.findViewById(R.id.cardPreview); // Assuming R.id.cardPreview exists
        btnFullscreenPreview = view.findViewById(R.id.btnFullscreenPreview);
        btnCaptureShotPreview = view.findViewById(R.id.btnCaptureShotPreview);
        containerPreviewZoomHud = view.findViewById(R.id.containerPreviewZoomHud);
        textPreviewZoomHud = view.findViewById(R.id.textPreviewZoomHud);
        containerPreviewZoomMap = view.findViewById(R.id.containerPreviewZoomMap);
        viewPreviewZoomMapViewport = view.findViewById(R.id.viewPreviewZoomMapViewport);
        btnPreviewZoomReset = view.findViewById(R.id.btnPreviewZoomReset);
        setupFullscreenButton();
        setupCaptureShotButton();
        setupPreviewZoomHud();
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
        layoutClockInner = view.findViewById(R.id.layoutClockInner);
        layoutClockContent = view.findViewById(R.id.layoutClockContent);

        // Stats views — split label/value layout
        tvVideoCount = view.findViewById(R.id.tvVideoCount);
        tvVideoSize = view.findViewById(R.id.tvVideoSize);
        tvSpaceTotal = view.findViewById(R.id.tvSpaceTotal);
        setupHomeCustomizationListeners();
        applyElapsedAlignmentPreference();
        applyElapsedSizePreference();
        applyElapsedFontPreference();
        applyElapsedFlagPreference();
        applyStorageIndicatorStylePreference();
        applyStorageTotalVisibilityPreference();
        applyTimeLeftAccentPreference();
        updateElapsedHeroAppearance();
        applyCardRailFoldedState(false);
        setupCardRailToggle();

        // Torch button (already initialized elsewhere, but good to have it
        // consistently)
        buttonTorchSwitch = view.findViewById(R.id.buttonTorchSwitch);
        updateMirrorButtonVisibilityAndState();

        // Initialize rotating bubble background (now replaced by avatar — will be null)
        ivBubbleBackground = view.findViewById(R.id.ivBubbleBackground);

        // Bind preview avatar views (replace bubble + CCTV icon)
        ivPreviewAvatar = view.findViewById(R.id.iv_preview_avatar);
        ivPreviewEyeOverlay = view.findViewById(R.id.iv_preview_eye_overlay);
        ivWakeSun = view.findViewById(R.id.iv_wake_sun);
        zzzHomeBadgeGroup = view.findViewById(R.id.zzz_home_badge_group);
        tvHomeZ1 = view.findViewById(R.id.tv_home_zzz_1);
        tvHomeZ2 = view.findViewById(R.id.tv_home_zzz_2);
        tvHomeZ3 = view.findViewById(R.id.tv_home_zzz_3);

        // Compact overlay handling removed: we now use PickerBottomSheetFragment for
        // controls.

        // Initialize other views as needed here.
        // textureView is handled by setupTextureView
    }

    /**
     * Called in onResume to initialise or resume the preview-area avatar animation.
     * Replaces the old bubble-rotation animation; keeps the same call-sites.
     * Home avatar is ALWAYS sleeping — it only shows when the camera feed is not visible,
     * so there is no "awake" state needed here.
     */
    private void startBubbleRotation() {
        if (ivPreviewAvatar == null) return;
        applyHomeAvatarState(false, false); // always sleeping in home fragment
    }

    /**
     * Suspends avatar animations (call on onPause / tab hidden).
     * Keeps the visual state — animations resume on next startBubbleRotation().
     */
    private void stopBubbleRotation() {
        stopHomeBlinkLoop();
        if (ambianceTwinkleAnim != null) { ambianceTwinkleAnim.cancel(); ambianceTwinkleAnim = null; }
        if (ivWakeSun != null) { ivWakeSun.animate().cancel(); }
        if (homeBreathingAnimator != null) { homeBreathingAnimator.cancel(); homeBreathingAnimator = null; }
        if (homeAvatarFloatAnim != null) { homeAvatarFloatAnim.cancel(); homeAvatarFloatAnim = null; }
        if (ivPreviewAvatar != null) { ivPreviewAvatar.setScaleX(1f); ivPreviewAvatar.setScaleY(1f); ivPreviewAvatar.setTranslationY(0f); }
        for (ObjectAnimator a : homeFloatingZAnims) a.cancel();
        homeFloatingZAnims.clear();
    }

    // ── Preview Avatar Animation Logic ───────────────────────────────────────

    /**
     * Applies awake (enabled=true) or sleeping (enabled=false) state to the home
     * preview avatar, mirroring the sidebar toggle behaviour.
     *
     * @param enabled true = awake (toggle_on_idle + blink loop)
     * @param animate true = play transition animations; false = instant state
     */
    private void applyHomeAvatarState(boolean enabled, boolean animate) {
        if (ivPreviewAvatar == null) return;
        homeAvatarLastEnabled = enabled;

        if (enabled) {
            stopHomeBlinkLoop();
            stopHomeBreathing();
            ivPreviewAvatar.setAlpha(1.0f);
            // Cancel twinkle + animate moon out, spin sun in
            if (ambianceTwinkleAnim != null) { ambianceTwinkleAnim.cancel(); ambianceTwinkleAnim = null; }
            if (getView() != null) {
                View ivAmbiance = getView().findViewById(R.id.iv_sleep_ambiance);
                if (ivAmbiance != null) {
                    ivAmbiance.animate().cancel();
                    ivAmbiance.animate().alpha(0f).scaleX(0.75f).scaleY(0.75f)
                        .setDuration(280)
                        .setInterpolator(new android.view.animation.AccelerateInterpolator())
                        .start();
                }
                if (ivWakeSun != null) {
                    ivWakeSun.animate().cancel();
                    ivWakeSun.setAlpha(0f);
                    ivWakeSun.setScaleX(0.2f);
                    ivWakeSun.setScaleY(0.2f);
                    ivWakeSun.setRotation(-30f);
                    ivWakeSun.animate()
                        .alpha(1f).scaleX(1f).scaleY(1f).rotation(0f)
                        .setDuration(520)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.5f))
                        .start();
                }
            }
            // Hide zzz
            if (zzzHomeBadgeGroup != null) {
                if (animate && zzzHomeBadgeGroup.getVisibility() == View.VISIBLE) {
                    zzzHomeBadgeGroup.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                        zzzHomeBadgeGroup.setVisibility(View.GONE);
                        zzzHomeBadgeGroup.setAlpha(1f);
                        resetHomeZLetters();
                    }).start();
                } else {
                    zzzHomeBadgeGroup.setVisibility(View.GONE);
                    resetHomeZLetters();
                }
            }
            if (animate) {
                // Wake-up AVD → idle + blink
                ivPreviewAvatar.setImageResource(resolveHomeDrawable(RES_WAKE));
                // Post-delay ensures drawable is fully loaded before animation starts
                ivPreviewAvatar.post(() -> {
                    android.graphics.drawable.Drawable d = ivPreviewAvatar.getDrawable();
                    if (d instanceof android.graphics.drawable.Animatable2) {
                        ((android.graphics.drawable.Animatable2) d).clearAnimationCallbacks();
                        ((android.graphics.drawable.Animatable2) d).registerAnimationCallback(
                            new android.graphics.drawable.Animatable2.AnimationCallback() {
                                @Override public void onAnimationEnd(android.graphics.drawable.Drawable drawable) {
                                    if (ivPreviewAvatar != null && ivPreviewAvatar.isAttachedToWindow()) {
                                        ivPreviewAvatar.setImageResource(resolveHomeDrawable(RES_IDLE));
                                        startHomeBlinkLoop();
                                    }
                                }
                            });
                        ((android.graphics.drawable.Animatable2) d).start();
                    } else if (d instanceof android.graphics.drawable.Animatable) {
                        ((android.graphics.drawable.Animatable) d).start();
                        ivPreviewAvatar.postDelayed(() -> {
                            if (ivPreviewAvatar != null && ivPreviewAvatar.isAttachedToWindow()) {
                                ivPreviewAvatar.setImageResource(resolveHomeDrawable(RES_IDLE));
                                startHomeBlinkLoop();
                            }
                        }, 480);
                    }
                });
            } else {
                ivPreviewAvatar.setImageResource(resolveHomeDrawable(RES_IDLE));
                startHomeBlinkLoop();
            }

        } else {
            stopHomeBlinkLoop();
            // NOTE: startHomeBreathing() is intentionally NOT called here.
            // It is called inside afterOff (after the sleep AVD finishes) so the breathing
            // alpha-pulse does not immediately dim the avatar and mask the sleep animation.
            // Sun fades out (if visible), moon rises back in with slow twinkle
            if (getView() != null) {
                View ivAmbiance = getView().findViewById(R.id.iv_sleep_ambiance);
                // Dismiss sun
                if (ivWakeSun != null && ivWakeSun.getAlpha() > 0.02f) {
                    ivWakeSun.animate().cancel();
                    ivWakeSun.animate().alpha(0f).scaleX(0.4f).scaleY(0.4f).rotation(20f)
                        .setDuration(220)
                        .setInterpolator(new android.view.animation.AccelerateInterpolator())
                        .start();
                }
                // Moon rises back in (or starts twinkle directly if already visible)
                if (ivAmbiance != null) {
                    ivAmbiance.animate().cancel();
                    if (ivAmbiance.getAlpha() < 0.1f) {
                        // Was hidden by wake animation — animate back in
                        ivAmbiance.setScaleX(0.8f);
                        ivAmbiance.setScaleY(0.8f);
                        ivAmbiance.animate().alpha(0.55f).scaleX(1f).scaleY(1f)
                            .setDuration(380)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.2f))
                            .withEndAction(() -> {
                                if (!isAdded() || getView() == null || ivAmbiance.getAlpha() < 0.1f) return;
                                if (ambianceTwinkleAnim != null) ambianceTwinkleAnim.cancel();
                                ambianceTwinkleAnim = ObjectAnimator.ofFloat(ivAmbiance, "alpha", 0.55f, 1.0f);
                                ambianceTwinkleAnim.setDuration(3500);
                                ambianceTwinkleAnim.setRepeatCount(ObjectAnimator.INFINITE);
                                ambianceTwinkleAnim.setRepeatMode(ObjectAnimator.REVERSE);
                                ambianceTwinkleAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                                ambianceTwinkleAnim.start();
                            }).start();
                    } else {
                        // Already visible — reset scale and just start twinkle
                        ivAmbiance.setScaleX(1f);
                        ivAmbiance.setScaleY(1f);
                        if (ambianceTwinkleAnim != null) ambianceTwinkleAnim.cancel();
                        ambianceTwinkleAnim = ObjectAnimator.ofFloat(ivAmbiance, "alpha", 0.55f, 1.0f);
                        ambianceTwinkleAnim.setDuration(3500);
                        ambianceTwinkleAnim.setRepeatCount(ObjectAnimator.INFINITE);
                        ambianceTwinkleAnim.setRepeatMode(ObjectAnimator.REVERSE);
                        ambianceTwinkleAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                        ambianceTwinkleAnim.start();
                    }
                }
            }

            if (animate) {
                ivPreviewAvatar.setAlpha(1f); // ensure full brightness before sleep AVD plays
                ivPreviewAvatar.setImageResource(resolveHomeDrawable(RES_SLEEP));
                // Post-delay ensures drawable is fully loaded before animation starts
                ivPreviewAvatar.post(() -> {
                    android.graphics.drawable.Drawable d = ivPreviewAvatar.getDrawable();
                    Runnable afterOff = () -> {
                        if (ivPreviewAvatar == null || !ivPreviewAvatar.isAttachedToWindow()) return;
                        ivPreviewAvatar.setImageResource(R.drawable.toggle_off);
                        startHomeBreathing(); // start breathing AFTER animation completes
                        showHomeZzzLetters(true);
                    };
                    if (d instanceof android.graphics.drawable.Animatable2) {
                        ((android.graphics.drawable.Animatable2) d).clearAnimationCallbacks();
                        ((android.graphics.drawable.Animatable2) d).registerAnimationCallback(
                            new android.graphics.drawable.Animatable2.AnimationCallback() {
                                @Override public void onAnimationEnd(android.graphics.drawable.Drawable drawable) { afterOff.run(); }
                            });
                        ((android.graphics.drawable.Animatable2) d).start();
                    } else if (d instanceof android.graphics.drawable.Animatable) {
                        ((android.graphics.drawable.Animatable) d).start();
                        ivPreviewAvatar.postDelayed(afterOff, 480);
                    } else {
                        afterOff.run();
                    }
                });
            } else {
                ivPreviewAvatar.setImageResource(R.drawable.toggle_off);
                startHomeBreathing();
                showHomeZzzLetters(false);
            }
        }
    }

    private void showHomeZzzLetters(boolean animate) {
        if (zzzHomeBadgeGroup == null) return;
        stopHomeFloatingZAnims();
        if (animate) {
            resetHomeZLetters();
            View[] zs = {tvHomeZ1, tvHomeZ2, tvHomeZ3};
            for (View z : zs) { if (z != null) { z.setAlpha(0f); z.setScaleX(0.1f); z.setScaleY(0.1f); z.setTranslationY(8f); } }
            zzzHomeBadgeGroup.setVisibility(View.VISIBLE);
            long delay = 130;
            for (View z : zs) {
                if (z == null) continue;
                z.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setStartDelay(delay).setDuration(290)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(2.5f)).start();
                delay += 115;
            }
            zzzHomeBadgeGroup.postDelayed(() -> startHomeFloatingZAnims(), 700);
        } else {
            zzzHomeBadgeGroup.setVisibility(View.VISIBLE);
            resetHomeZLetters();
            startHomeFloatingZAnims();
        }
    }

    private void resetHomeZLetters() {
        for (View z : new View[]{tvHomeZ1, tvHomeZ2, tvHomeZ3}) {
            if (z == null) continue;
            z.setAlpha(1f); z.setScaleX(1f); z.setScaleY(1f); z.setTranslationY(0f);
        }
    }

    private void startHomeBreathing() {
        if (homeBreathingAnimator != null) homeBreathingAnimator.cancel();
        if (ivPreviewAvatar == null) return;

        // Alpha + scale pulse: gentle "inhale/exhale" feel
        homeBreathingAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
        homeBreathingAnimator.setDuration(2600);
        homeBreathingAnimator.setRepeatCount(ValueAnimator.INFINITE);
        homeBreathingAnimator.setRepeatMode(ValueAnimator.REVERSE);
        homeBreathingAnimator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        homeBreathingAnimator.addUpdateListener(a -> {
            if (ivPreviewAvatar == null || !ivPreviewAvatar.isAttachedToWindow()) return;
            float t = (float) a.getAnimatedValue();             // 0 → 1
            float alpha = 0.62f + 0.30f * t;                    // 0.62 ↔ 0.92
            float scale = 0.96f + 0.04f * t;                    // 0.96 ↔ 1.00
            ivPreviewAvatar.setAlpha(alpha);
            ivPreviewAvatar.setScaleX(scale);
            ivPreviewAvatar.setScaleY(scale);
        });
        homeBreathingAnimator.start();

        // Gentle floating bob up/down
        if (homeAvatarFloatAnim != null) homeAvatarFloatAnim.cancel();
        homeAvatarFloatAnim = ObjectAnimator.ofFloat(ivPreviewAvatar, "translationY", 0f, -9f);
        homeAvatarFloatAnim.setDuration(3400);
        homeAvatarFloatAnim.setRepeatCount(ObjectAnimator.INFINITE);
        homeAvatarFloatAnim.setRepeatMode(ObjectAnimator.REVERSE);
        homeAvatarFloatAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        homeAvatarFloatAnim.start();
    }

    private void stopHomeBreathing() {
        if (homeBreathingAnimator != null) { homeBreathingAnimator.cancel(); homeBreathingAnimator = null; }
        if (homeAvatarFloatAnim != null) { homeAvatarFloatAnim.cancel(); homeAvatarFloatAnim = null; }
        if (ivPreviewAvatar != null) {
            ivPreviewAvatar.setAlpha(1.0f);
            ivPreviewAvatar.setScaleX(1.0f);
            ivPreviewAvatar.setScaleY(1.0f);
            ivPreviewAvatar.setTranslationY(0f);
        }
    }

    private void startHomeFloatingZAnims() {
        stopHomeFloatingZAnims();
        View[] zs = {tvHomeZ1, tvHomeZ2, tvHomeZ3};
        long[] durations = {1600, 1900, 2200};
        float[] amps = {5f, 6f, 7f};
        for (int i = 0; i < zs.length; i++) {
            if (zs[i] == null) continue;
            ObjectAnimator a = ObjectAnimator.ofFloat(zs[i], "translationY", 0f, -amps[i]);
            a.setDuration(durations[i]); a.setRepeatCount(ObjectAnimator.INFINITE);
            a.setRepeatMode(ObjectAnimator.REVERSE);
            a.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            a.start(); homeFloatingZAnims.add(a);
        }
    }

    private void stopHomeFloatingZAnims() {
        for (ObjectAnimator a : homeFloatingZAnims) a.cancel();
        homeFloatingZAnims.clear();
    }

    // ── Home Avatar Color Drawable Resolution ──────────────────────────────────

    private static final int RES_IDLE = 0, RES_BLINK = 1, RES_WAKE = 2, RES_SLEEP = 3;
    private static final int[] HOME_DEFAULT_DRAWABLES = {
        R.drawable.toggle_on_idle, R.drawable.toggle_on_blink,
        R.drawable.toggle_on_anim, R.drawable.toggle_off_anim
    };
    /** Maps eye-color ARGB ints → [idle, blink, wake, sleep] drawable resource IDs. */
    private static final java.util.Map<Integer, int[]> HOME_EYE_COLOR_DRAWABLES;
    static {
        java.util.Map<Integer, int[]> m = new java.util.HashMap<>();
        m.put(0xFFFF1744, new int[]{ R.drawable.toggle_on_idle_ruby,    R.drawable.toggle_on_blink_ruby,    R.drawable.toggle_on_anim_ruby,    R.drawable.toggle_off_anim_ruby });
        m.put(0xFF00E5FF, new int[]{ R.drawable.toggle_on_idle_cyan,    R.drawable.toggle_on_blink_cyan,    R.drawable.toggle_on_anim_cyan,    R.drawable.toggle_off_anim_cyan });
        m.put(0xFFD500F9, new int[]{ R.drawable.toggle_on_idle_violet,  R.drawable.toggle_on_blink_violet,  R.drawable.toggle_on_anim_violet,  R.drawable.toggle_off_anim_violet });
        m.put(0xFF2979FF, new int[]{ R.drawable.toggle_on_idle_cobalt,  R.drawable.toggle_on_blink_cobalt,  R.drawable.toggle_on_anim_cobalt,  R.drawable.toggle_off_anim_cobalt });
        m.put(0xFFFFD740, new int[]{ R.drawable.toggle_on_idle_amber,   R.drawable.toggle_on_blink_amber,   R.drawable.toggle_on_anim_amber,   R.drawable.toggle_off_anim_amber });
        m.put(0xFF00E676, new int[]{ R.drawable.toggle_on_idle_lime,    R.drawable.toggle_on_blink_lime,    R.drawable.toggle_on_anim_lime,    R.drawable.toggle_off_anim_lime });
        m.put(0xFFF50057, new int[]{ R.drawable.toggle_on_idle_magenta, R.drawable.toggle_on_blink_magenta, R.drawable.toggle_on_anim_magenta, R.drawable.toggle_off_anim_magenta });
        HOME_EYE_COLOR_DRAWABLES = java.util.Collections.unmodifiableMap(m);
    }

    /**
     * Returns the drawable resource for the given animation state,
     * choosing the color-specific variant when a custom eye color is set.
     */
    private int resolveHomeDrawable(int resIndex) {
        if (sharedPreferencesManager == null) return HOME_DEFAULT_DRAWABLES[resIndex];
        int eyeColor = sharedPreferencesManager.sharedPreferences.getInt(
                Constants.PREF_AVATAR_EYE_COLOR, Constants.DEFAULT_AVATAR_EYE_COLOR);
        int[] res = HOME_EYE_COLOR_DRAWABLES.get(eyeColor);
        return res != null ? res[resIndex] : HOME_DEFAULT_DRAWABLES[resIndex];
    }

    private void startHomeBlinkLoop() {
        stopHomeBlinkLoop();
        scheduleNextHomeBlink(2500 + homeBlinkRandom.nextInt(2000));
    }

    private void scheduleNextHomeBlink(long delayMs) {
        homeBlinkRunnable = () -> {
            if (ivPreviewAvatar == null || !ivPreviewAvatar.isAttachedToWindow() || !homeAvatarLastEnabled) return;
            ivPreviewAvatar.setImageResource(resolveHomeDrawable(RES_BLINK));
            android.graphics.drawable.Drawable d = ivPreviewAvatar.getDrawable();
            if (d instanceof android.graphics.drawable.Animatable2) {
                android.graphics.drawable.Animatable2 avd2 = (android.graphics.drawable.Animatable2) d;
                avd2.clearAnimationCallbacks(); // prevent stale callback accumulation
                avd2.registerAnimationCallback(
                    new android.graphics.drawable.Animatable2.AnimationCallback() {
                        @Override public void onAnimationEnd(android.graphics.drawable.Drawable drawable) {
                            // Guard: don't reset to idle if we've already transitioned to sleep
                            if (ivPreviewAvatar != null && ivPreviewAvatar.isAttachedToWindow() && homeAvatarLastEnabled) {
                                ivPreviewAvatar.setImageResource(resolveHomeDrawable(RES_IDLE));
                                scheduleNextHomeBlink(3000 + homeBlinkRandom.nextInt(2500));
                            }
                        }
                    });
                avd2.start();
            } else if (d instanceof android.graphics.drawable.Animatable) {
                ((android.graphics.drawable.Animatable) d).start();
                homeBlinkHandler.postDelayed(() -> {
                    if (ivPreviewAvatar != null && ivPreviewAvatar.isAttachedToWindow() && homeAvatarLastEnabled) {
                        ivPreviewAvatar.setImageResource(resolveHomeDrawable(RES_IDLE));
                        scheduleNextHomeBlink(3000 + homeBlinkRandom.nextInt(2500));
                    }
                }, 260);
            }
        };
        homeBlinkHandler.postDelayed(homeBlinkRunnable, delayMs);
    }

    private void stopHomeBlinkLoop() {
        if (homeBlinkRunnable != null) { homeBlinkHandler.removeCallbacks(homeBlinkRunnable); homeBlinkRunnable = null; }
        // Stop and clear any blink AVD currently playing so its onAnimationEnd cannot fire
        // and reset the drawable back to idle after we've already transitioned to sleep.
        if (ivPreviewAvatar != null) {
            android.graphics.drawable.Drawable cur = ivPreviewAvatar.getDrawable();
            if (cur instanceof android.graphics.drawable.Animatable2) {
                ((android.graphics.drawable.Animatable2) cur).clearAnimationCallbacks();
                ((android.graphics.drawable.Animatable2) cur).stop();
            } else if (cur instanceof android.graphics.drawable.Animatable) {
                ((android.graphics.drawable.Animatable) cur).stop();
            }
        }
    }

    // ── End Preview Avatar Animation Logic ───────────────────────────────────



    // ─── Fullscreen Preview ──────────────────────────────────────────────────

    /**
     * Wires the fullscreen preview button: launches {@link FullscreenPreviewActivity}
     * when tapped, and re-sends the HomeFragment surface when the user returns.
     */
    private void setupFullscreenButton() {
        if (btnFullscreenPreview == null) return;

        btnFullscreenPreview.setOnClickListener(v -> {
            // Launch fullscreen preview — the activity will take over the preview surface
            Intent intent = new Intent(requireContext(), FullscreenPreviewActivity.class);
            isLaunchingFullscreen = true;
            fullscreenLauncher.launch(intent);
        });
    }

    private void setupCaptureShotButton() {
        if (btnCaptureShotPreview == null) return;
        btnCaptureShotPreview.setOnClickListener(v -> captureShotFromCurrentPreview());
    }

    private void captureShotFromCurrentPreview() {
        if (isRecordingOrPaused() || isPreviewOnlyActive || isPreviewOnlyStartPending) {
            Intent intent = new Intent(
                    requireContext(),
                    isDualRecordingActive ? DualCameraRecordingService.class : RecordingService.class
            );
            intent.setAction(Constants.INTENT_ACTION_CAPTURE_PHOTO);
            requireContext().startService(intent);
            return;
        }
        if (!isRecordingOrPaused()) {
            try {
                isLaunchingPhotoCapture = true;
                Intent shotIntent = new Intent(requireContext(), com.fadcam.PhotoCaptureActivity.class);
                shotIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                startActivity(shotIntent);
            } catch (Exception e) {
                isLaunchingPhotoCapture = false;
                Toast.makeText(requireContext(), R.string.photo_capture_failed, Toast.LENGTH_SHORT).show();
            }
            return;
        }
    }

    /**
     * Activity result launcher for returning from fullscreen preview.
     * Re-sends the HomeFragment TextureView surface to the recording service.
     */
    private final androidx.activity.result.ActivityResultLauncher<Intent> fullscreenLauncher =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        // Fullscreen activity finished — push our surface immediately.
                        // FullscreenPreview does NOT send null on destroy, so there
                        // is no race condition. We just need to reclaim the preview.
                        isLaunchingFullscreen = false;
                        isReturningFromFullscreen = true;
                        resetTextureView();
                        syncPreviewZoomStateFromPrefs(true);
                        // Safety retry: if TextureView wasn't ready yet (e.g. it
                        // was recreated), onSurfaceTextureAvailable handles it.
                        // But if it IS available and the first reset didn't take
                        // (debounce / timing), retry once more.
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .postDelayed(() -> {
                                    if (textureViewSurface == null
                                            || !textureViewSurface.isValid()) {
                                        resetTextureView();
                                    }
                                    syncPreviewZoomStateFromPrefs(true);
                                    // Clear the guard flag after surface should be stable
                                    isReturningFromFullscreen = false;
                                }, 600);
                    });

    /**
     * Update fullscreen button visibility based on the "preview quick actions" setting.
     * When "always visible" is ON  → show regardless of recording state.
     * When "always visible" is OFF → show only while recording/paused.
     */
    private void updateFullscreenButtonVisibility() {
        if (btnFullscreenPreview == null) return;
        boolean alwaysVisible = false;
        try {
            if (sharedPreferencesManager != null) {
                alwaysVisible = sharedPreferencesManager.isPreviewQuickActionsAlwaysVisible();
            }
        } catch (Exception ignored) {
        }
        boolean show = alwaysVisible || isRecordingOrPaused();
        btnFullscreenPreview.setVisibility(show ? View.VISIBLE : View.GONE);
        if (btnCaptureShotPreview != null) {
            btnCaptureShotPreview.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private boolean isRecordingOrPaused() {
        return (
            recordingState == RecordingState.IN_PROGRESS ||
            recordingState == RecordingState.PAUSED
        );
    }


    // This method replaces/refines the old updateRecordingSurface
    private void updateServiceWithCurrentSurface(
        @Nullable Surface surfaceToUse
    ) {
        updateServiceWithCurrentSurface(surfaceToUse, -1, -1, false);
    }

    private void updateServiceWithCurrentSurface(
        @Nullable Surface surfaceToUse,
        int width,
        int height
    ) {
        updateServiceWithCurrentSurface(surfaceToUse, width, height, false);
    }

    private void updateServiceWithCurrentSurface(
        @Nullable Surface surfaceToUse,
        int width,
        int height,
        boolean isFullscreenTransition
    ) {
        if (!isAdded() || getContext() == null) {
            FLog.w(
                TAG,
                "updateServiceWithCurrentSurface: Fragment not added or context is null, cannot send surface update."
            );
            return;
        }

        // Avoid waking RecordingService with ACTION_CHANGE_SURFACE when nothing is active.
        // This prevents service churn (create/destroy loops) while idle.
        boolean shouldSyncSingleService =
            isRecordingOrPaused() ||
            isPreviewOnlyActive ||
            isPreviewOnlyStartPending ||
            isMyServiceRunning(RecordingService.class) ||
            isFullscreenTransition;
        if (!isDualRecordingActive && !shouldSyncSingleService) {
            FLog.d(TAG, "updateServiceWithCurrentSurface: Skipping surface sync while idle");
            return;
        }

        // When dual camera recording is active, send the surface to
        // DualCameraRecordingService instead of RecordingService.
        if (isDualRecordingActive) {
            Intent dualIntent = new Intent(getContext(),
                    com.fadcam.dualcam.service.DualCameraRecordingService.class);
            dualIntent.setAction(Constants.INTENT_ACTION_CHANGE_SURFACE);
            if (surfaceToUse != null && surfaceToUse.isValid()) {
                dualIntent.putExtra("SURFACE", surfaceToUse);
                if (width > 0 && height > 0) {
                    dualIntent.putExtra("SURFACE_WIDTH", width);
                    dualIntent.putExtra("SURFACE_HEIGHT", height);
                }
                // Mark as fullscreen transition when exiting fullscreen with valid surface
                if (isReturningFromFullscreen) {
                    dualIntent.putExtra("IS_FULLSCREEN_TRANSITION", true);
                    FLog.d(TAG, "Sending VALID surface with FULLSCREEN return flag to DualCam");
                }
            } else {
                dualIntent.putExtra("SURFACE", (Surface) null);
                if (isFullscreenTransition) {
                    dualIntent.putExtra("IS_FULLSCREEN_TRANSITION", true);
                }
            }
            Context ctx = getContext();
            if (ctx != null) {
                ctx.startService(dualIntent);
            }
            FLog.d(TAG, "updateServiceWithCurrentSurface: Sent to DualCameraRecordingService");
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
                // Mark as fullscreen transition when returning with valid surface
                if (isReturningFromFullscreen) {
                    intent.putExtra("IS_FULLSCREEN_TRANSITION", true);
                    FLog.d(TAG, "Sending VALID surface with FULLSCREEN return flag");
                }
                FLog.d(
                    TAG,
                    "updateServiceWithCurrentSurface: Sending new VALID surface to RecordingService with dimensions " +
                    width +
                    "x" +
                    height
                );
            } else {
                // Mark as fullscreen return if coming back with valid surface but no dimensions yet
                if (isReturningFromFullscreen) {
                    intent.putExtra("IS_FULLSCREEN_TRANSITION", true);
                    FLog.d(TAG, "Sending VALID surface with FULLSCREEN return flag (no dimensions)");
                }
                FLog.d(
                    TAG,
                    "updateServiceWithCurrentSurface: Sending new VALID surface to RecordingService."
                );
            }
        } else {
            intent.putExtra("SURFACE", (Surface) null);
            if (isFullscreenTransition) {
                intent.putExtra("IS_FULLSCREEN_TRANSITION", true);
                FLog.d(TAG, "updateServiceWithCurrentSurface: Sending NULL surface with FULLSCREEN_TRANSITION flag");
            } else {
                FLog.d(TAG, "updateServiceWithCurrentSurface: Sending NULL surface to RecordingService (preview disabled or surface invalid/destroyed).");
            }
        }

        // Use requireContext() for starting service if preferred and appropriate for
        // fragment version
        Context context = getContext();
        if (context != null) {
            context.startService(intent);
        }
    }


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

                FLog.i(
                    TAG,
                    "Applied clock card color: " +
                    colorHex +
                    " with text color: " +
                    (isLightBackground ? "BLACK" : "WHITE")
                );
            } catch (IllegalArgumentException e) {
                FLog.e(
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
                FLog.i(
                    TAG,
                    "Fallback to default color: " +
                    SharedPreferencesManager.DEFAULT_CLOCK_CARD_COLOR
                );
            }
        } else {
            FLog.w(
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

                    areCameraResourcesAvailable = isAvailable && hasRequiredRecordingHardware;
                    updateStartButtonAvailability();

                    FLog.d(
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
            FLog.e(
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
            FLog.d(TAG, "Camera resource availability receiver registered");
        } catch (Exception e) {
            FLog.e(
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
            FLog.d(TAG, "Camera resource availability receiver unregistered");
        } catch (IllegalArgumentException e) {
            FLog.w(
                TAG,
                "Error unregistering camera resource availability receiver: " +
                e.getMessage()
            );
        }
    }


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
                        FLog.d(
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


    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerSegmentCompleteStatsReceiver(Context context) {
        // Guard: Don't register twice
        if (isSegmentCompleteStatsReceiverRegistered) {
            FLog.d(TAG, "Segment complete stats receiver already registered, skipping.");
            return;
        }
        
        // First check if fragment is attached to prevent IllegalStateException
        if (!isAdded() || isDetached()) {
            FLog.e(
                TAG,
                "Fragment not attached or is detached, cannot register segmentCompleteStatsReceiver"
            );
            isSegmentCompleteStatsReceiverRegistered = false;
            return;
        }

        if (context == null) {
            FLog.e(
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
                // OPTIMIZATION: Removed generic receiver registration log
                // FLog.d(TAG, "Registered segmentCompleteStatsReceiver.");
            } else {
                isSegmentCompleteStatsReceiverRegistered = false;
                FLog.e(
                    TAG,
                    "segmentCompleteStatsReceiver is null, not registering."
                );
            }
        } catch (IllegalStateException e) {
            FLog.e(TAG, "Fragment not associated with fragment manager", e);
            isSegmentCompleteStatsReceiverRegistered = false;
        }
    }


    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        FLog.d(TAG, "onHiddenChanged: Fragment " + (hidden ? "hidden" : "shown") + ", isResumed=" + isResumed());
        
        if (!isAdded() || getContext() == null || getActivity() == null) {
            return;
        }
        
        if (!hidden) {
            // Reset stale pending text/state on tab return; real state comes from service callback.
            clearPreviewOnlyPendingState(true);
            // Tab switched back — resume all operations
            if (isResumed()) {
                performResumeOperations();
            }
            
            // Camera surface handling
            if (
                isPreviewEnabled &&
                (isRecordingOrPaused() || isPreviewOnlyActive || isPreviewOnlyStartPending) &&
                textureViewSurface != null &&
                textureViewSurface.isValid()
            ) {
                FLog.d(TAG, "onHiddenChanged: Preview enabled, sending valid surface to service");
                updateServiceWithCurrentSurface(textureViewSurface);
            } else if (!isPreviewEnabled || (!isRecordingOrPaused() && !isPreviewOnlyActive && !isPreviewOnlyStartPending)) {
                FLog.d(TAG, "onHiddenChanged: Preview disabled or not recording, sending null surface");
                updateServiceWithCurrentSurface(null);
            }
            if (isPreviewOnlyStartPending && !isPreviewOnlyActive) {
                schedulePreviewOnlyStartTimeout();
            }
        } else {
            // Tab switched away — pause heavy operations
            pauseUpdateHandlers();
            if (!isLaunchingPhotoCapture) {
                stopBubbleRotation();
            }
            if (!isRecordingOrPaused() && (isPreviewOnlyActive || isPreviewOnlyStartPending)) {
                dispatchStopPreviewOnly();
                clearPreviewOnlyPendingState(true);
                isPreviewOnlyActive = false;
                updatePreviewVisibility();
            } else if (isPreviewOnlyStartPending && !isPreviewOnlyActive) {
                clearPreviewOnlyPendingState(false);
            }
            
            if (isRecordingOrPaused() || isPreviewOnlyActive) {
                FLog.d(TAG, "onHiddenChanged: Fragment hidden while recording, sending null surface");
                updateServiceWithCurrentSurface(null);
            }
        }
    }

    private void dispatchStopPreviewOnly() {
        if (!isAdded() || getContext() == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastPreviewOnlyToggleDispatchMs < PREVIEW_ONLY_TOGGLE_DEBOUNCE_MS) {
            FLog.d(TAG, "dispatchStopPreviewOnly: skipped by debounce");
            return;
        }
        lastPreviewOnlyToggleDispatchMs = now;
        try {
            Intent stopIntent = new Intent(requireContext(), RecordingService.class);
            stopIntent.setAction(Constants.INTENT_ACTION_STOP_PREVIEW_ONLY);
            ServiceStartPolicy.startRecordingAction(requireContext(), stopIntent);
            FLog.d(TAG, "dispatchStopPreviewOnly: sent stop preview-only intent");
        } catch (Exception e) {
            FLog.w(TAG, "dispatchStopPreviewOnly: failed to dispatch: " + e.getMessage());
        }
    }


    private void setTextColorsRecursive(View view, int primary, int secondary) {
        if (view == null) return;
        // Skip views tagged "preserve_color" (e.g. zzZ badge letters) — they have
        // semantic colors that must not be overridden by theme recoloring.
        if ("preserve_color".equals(view.getTag())) return;
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


    private String getDefaultClockColorForTheme(String themeName) {
        FLog.i(
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
            FLog.i(
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
                FLog.i(
                    TAG,
                    "FORCE RESET: Changed saved clock color from Purple to Dark Grey for AMOLED"
                );
            }
        } else if ("Crimson Bloom".equals(themeName)) {
            result = CLOCK_COLOR_HEX_VALUES[5]; // Red (#F44336)
            FLog.i(
                TAG,
                "Crimson Bloom theme match, using Red: " + result
            );
        } else if ("Premium Gold".equals(themeName)) {
            result = CLOCK_COLOR_HEX_VALUES[9]; // Gold (#FFD700)
            FLog.i(
                TAG,
                "Premium Gold theme match, using Gold: " + result
            );
        } else if ("Midnight Dusk".equals(themeName)) {
            result = CLOCK_COLOR_HEX_VALUES[0]; // Purple (#673AB7)
            FLog.i(
                TAG,
                "Midnight Dusk theme match, using Purple: " + result
            );
        } else if ("Blue Ocean".equals(themeName)) {
            result = CLOCK_COLOR_HEX_VALUES[1]; // Blue (#2196F3)
            FLog.i(
                TAG,
                "Blue Ocean theme match, using Blue: " + result
            );
        } else if ("Green Fields".equals(themeName)) {
            result = CLOCK_COLOR_HEX_VALUES[2]; // Green (#4CAF50)
            FLog.i(
                TAG,
                "Green Fields theme match, using Green: " + result
            );
        } else if ("Teal Dream".equals(themeName)) {
            result = CLOCK_COLOR_HEX_VALUES[3]; // Teal (#009688)
            FLog.i(
                TAG,
                "Teal Dream theme match, using Teal: " + result
            );
        } else if ("Orange Sunset".equals(themeName)) {
            result = CLOCK_COLOR_HEX_VALUES[4]; // Orange (#FF9800)
            FLog.i(
                TAG,
                "Orange Sunset theme match, using Orange: " + result
            );
        } else if ("Dark Grey".equals(themeName)) {
            result = CLOCK_COLOR_HEX_VALUES[6]; // Dark Grey (#424242)
            FLog.i(
                TAG,
                "Dark Grey theme match, using Dark Grey: " + result
            );
        } else if ("Silent Forest".equals(themeName)) {
            result = CLOCK_COLOR_HEX_VALUES[2]; // Green (#4CAF50)
            FLog.i(
                TAG,
                "Silent Forest theme match, using Green: " + result
            );
        } else if ("Shadow Alloy".equals(themeName)) {
            result = CLOCK_COLOR_HEX_VALUES[8]; // Amoled Gray closest to silver
            FLog.i(
                TAG,
                "Shadow Alloy theme match, using Silver-ish: " + result
            );
        } else if ("Pookie Pink".equals(themeName)) {
            result = CLOCK_COLOR_HEX_VALUES[10]; // Pink (#F06292)
            FLog.i(
                TAG,
                "Pookie Pink theme match, using Pink: " + result
            );
        } else {
            // Fallback to default
            result = CLOCK_COLOR_HEX_VALUES[0]; // Purple (#673AB7)
            FLog.w(
                TAG,
                "No specific theme match found for [" +
                themeName +
                "], defaulting to Purple"
            );
        }

        FLog.i(
            TAG,
            "Final default clock color for theme [" + themeName + "]: " + result
        );
        return result;
    }


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
            View root = getView();
            if (root == null) return;

            View activeIndicator = root.findViewById(R.id.segment_active_indicator);
            if (activeIndicator != null) {
                activeIndicator.setBackgroundResource(R.drawable.segment_active_background);
            }

            View segmentFadCam = root.findViewById(R.id.segment_fadcam);
            View segmentFadRec = root.findViewById(R.id.segment_fadrec);
            View segmentFadMic = root.findViewById(R.id.segment_fadmic);

            if (segmentFadCam != null) segmentFadCam.setBackground(null);
            if (segmentFadRec != null) segmentFadRec.setBackground(null);
            if (segmentFadMic != null) segmentFadMic.setBackground(null);
        } catch (Exception e) {
            FLog.w(
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

        // Stats card text — tvVideoCount and tvVideoSize preserve their XML colors
        // via android:tag="preserve_color" so no extra colour handling is needed here.
        if (cardStats != null) {
            // Force all text in the stats card to black (Snow Veil theme — white background).
            forceForceMakeAllTextBlack(cardStats);
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
        applyTimeLeftAccentPreference();
        if (tvEstimateSubtitle != null) tvEstimateSubtitle.setTextColor(
            Color.parseColor("#B0B0B0")
        );
        if (tvSpaceTitle != null) tvSpaceTitle.setTextColor(Color.WHITE);
        if (tvSpaceSubtitle != null) tvSpaceSubtitle.setTextColor(Color.WHITE);
        updateElapsedHeroAppearance();

        // Keep semantic colors for icons but ensure they're visible on light background
        if (ivCameraIcon != null) {
            // Keep green color for camera icon but use a darker shade for better contrast
            ivCameraIcon.setTextColor(Color.parseColor("#2E7D32")); // Darker green for better contrast
        }

        // Handle other icons by finding them in the storage card
        if (cardStorage != null) {
            findAndColorIconsByText(cardStorage);
        }
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
            FLog.e(TAG, "Error coloring icons: " + e.getMessage());
        }
    }

    private void setupHomeCustomizationListeners() {
        if (cardElapsedHero != null) {
            cardElapsedHero.setOnLongClickListener(v -> {
                animatePressBounce(v, () -> {
                    performHapticFeedback();
                    showElapsedCustomizeSheet();
                });
                return true;
            });
        }

        if (rowStorageAvailable != null) {
            rowStorageAvailable.setOnClickListener(v -> {
                // Keep the row visually pressable so ripple feedback works with long press.
            });
            rowStorageAvailable.setOnLongClickListener(v -> {
                animatePressBounce(v, () -> {
                    performHapticFeedback();
                    showStorageCustomizeSheet();
                });
                return true;
            });
        }

        if (rowEstimateTime != null) {
            rowEstimateTime.setOnClickListener(v -> {
                // Keep the row visually pressable so ripple feedback works with long press.
            });
            rowEstimateTime.setOnLongClickListener(v -> {
                animatePressBounce(v, () -> {
                    performHapticFeedback();
                    showTimeLeftColorSheet();
                });
                return true;
            });
        }
    }

    private void setupCardRailToggle() {
        View.OnClickListener listener = v -> {
            performHapticFeedback();
            boolean folded = sharedPreferencesManager != null
                    && sharedPreferencesManager.sharedPreferences.getBoolean(
                    Constants.PREF_HOME_CARD_RAIL_FOLDED,
                    false);
            boolean next = !folded;
            if (sharedPreferencesManager != null) {
                sharedPreferencesManager.sharedPreferences.edit()
                        .putBoolean(Constants.PREF_HOME_CARD_RAIL_FOLDED, next)
                        .apply();
            }
            applyCardRailFoldedState(true);
        };
        if (cardRailTogglePortrait != null) {
            cardRailTogglePortrait.setOnClickListener(listener);
        }
        if (cardRailToggleLandscape != null) {
            cardRailToggleLandscape.setOnClickListener(listener);
        }
    }

    protected boolean isCardRailCurrentlyFolded() {
        return sharedPreferencesManager != null
                && sharedPreferencesManager.sharedPreferences.getBoolean(
                Constants.PREF_HOME_CARD_RAIL_FOLDED,
                false);
    }

    protected void updateStartStopButtonForFoldedState() {
        if (buttonStartStop == null || !isAdded()) {
            return;
        }

        boolean folded = isCardRailCurrentlyFolded();
        boolean showTimerOnButton = folded && (isRecording() || isPaused());
        CharSequence currentText = buttonStartStop.getText();
        String currentValue = currentText != null ? currentText.toString() : "";
        boolean currentShowsTimer = currentValue.matches("\\d{2}:\\d{2}");

        if (showTimerOnButton) {
            buttonStartStop.setIcon(AppCompatResources.getDrawable(
                    requireContext(),
                    R.drawable.stop_rounded));
            if (!currentValue.equals(latestElapsedDisplay)) {
                if (buttonStartStop instanceof com.fadcam.ui.utils.AnimatedMaterialButton
                        && currentShowsTimer) {
                    // Timer updates should behave like the elapsed card: only the
                    // changed digits move while the stable prefix/suffix stay put.
                    ((com.fadcam.ui.utils.AnimatedMaterialButton) buttonStartStop)
                            .animateSlot(latestElapsedDisplay, 400);
                } else {
                    buttonStartStop.setText(latestElapsedDisplay);
                }
            }
            return;
        }

        if (!currentShowsTimer) {
            return;
        }

        android.graphics.drawable.Drawable icon = AppCompatResources.getDrawable(
                requireContext(),
                recordingState == RecordingState.NONE
                        ? R.drawable.play_button_rounded
                        : R.drawable.stop_rounded);
        buttonStartStop.setIcon(icon);
        buttonStartStop.setText(recordingState == RecordingState.NONE
                ? getString(R.string.button_start)
                : getString(R.string.button_stop));
    }

    private void applyCardRailFoldedState(boolean animate) {
        boolean folded = isCardRailCurrentlyFolded();

        if (homeRootLayout != null && animate) {
            AutoTransition transition = new AutoTransition();
            transition.setDuration(220);
            TransitionManager.beginDelayedTransition(homeRootLayout, transition);
        }

        if (isLandscapeMode()) {
            if (leftPanel != null) {
                leftPanel.setVisibility(folded ? View.GONE : View.VISIBLE);
            }
            applyLandscapeFoldConstraints(folded);
            if (ivCardRailToggleLandscape != null) {
                ivCardRailToggleLandscape.animate().cancel();
                if (animate) {
                    ivCardRailToggleLandscape.animate()
                            .rotation(folded ? 0f : 180f)
                            .setDuration(220)
                            .start();
                } else {
                    ivCardRailToggleLandscape.setRotation(folded ? 0f : 180f);
                }
            }
            if (cardRailToggleLandscape != null) {
                cardRailToggleLandscape.setVisibility(View.VISIBLE);
                cardRailToggleLandscape.setAlpha(0.9f);
            }
            if (cardRailTogglePortrait != null) {
                cardRailTogglePortrait.setVisibility(View.GONE);
            }
        } else {
            if (layoutCardRailSection != null) {
                layoutCardRailSection.setVisibility(folded ? View.GONE : View.VISIBLE);
            } else if (layoutCards != null) {
                layoutCards.setVisibility(folded ? View.GONE : View.VISIBLE);
            }
            if (ivCardRailTogglePortrait != null) {
                ivCardRailTogglePortrait.animate().cancel();
                if (animate) {
                    ivCardRailTogglePortrait.animate()
                            .rotation(folded ? 0f : 180f)
                            .setDuration(220)
                            .start();
                } else {
                    ivCardRailTogglePortrait.setRotation(folded ? 0f : 180f);
                }
            }
            if (cardRailTogglePortrait != null) {
                cardRailTogglePortrait.setVisibility(View.VISIBLE);
                cardRailTogglePortrait.setTranslationY(0f);
                cardRailTogglePortrait.setAlpha(0.9f);
            }
            if (cardRailToggleLandscape != null) {
                cardRailToggleLandscape.setVisibility(View.GONE);
            }
        }
        updateStartStopButtonForFoldedState();
    }

    private void applyLandscapeFoldConstraints(boolean folded) {
        if (homeRootLayout == null || leftPanel == null || rightPanel == null || cardRailToggleLandscape == null) {
            return;
        }
        ConstraintSet set = new ConstraintSet();
        set.clone(homeRootLayout);
        set.clear(R.id.leftPanel, ConstraintSet.END);
        set.clear(R.id.cardRailToggleLandscape, ConstraintSet.START);
        set.clear(R.id.cardRailToggleLandscape, ConstraintSet.END);
        set.clear(R.id.rightPanel, ConstraintSet.START);

        set.connect(R.id.leftPanel, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, dpToPxInt(6));
        set.connect(R.id.leftPanel, ConstraintSet.TOP, R.id.header_bar, ConstraintSet.BOTTOM, dpToPxInt(4));
        set.connect(R.id.leftPanel, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, dpToPxInt(72));

        set.connect(R.id.cardRailToggleLandscape, ConstraintSet.TOP, R.id.header_bar, ConstraintSet.BOTTOM, dpToPxInt(4));
        set.connect(R.id.cardRailToggleLandscape, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, dpToPxInt(72));
        set.connect(R.id.rightPanel, ConstraintSet.TOP, R.id.header_bar, ConstraintSet.BOTTOM, dpToPxInt(4));
        set.connect(R.id.rightPanel, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, 0);
        set.connect(R.id.rightPanel, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, dpToPxInt(6));

        if (folded) {
            set.connect(R.id.cardRailToggleLandscape, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, dpToPxInt(6));
        } else {
            set.connect(R.id.leftPanel, ConstraintSet.END, R.id.cardRailToggleLandscape, ConstraintSet.START, 0);
            set.connect(R.id.cardRailToggleLandscape, ConstraintSet.START, R.id.leftPanel, ConstraintSet.END, 0);
        }
        set.connect(R.id.cardRailToggleLandscape, ConstraintSet.END, R.id.rightPanel, ConstraintSet.START, dpToPxInt(3));
        set.connect(R.id.rightPanel, ConstraintSet.START, R.id.cardRailToggleLandscape, ConstraintSet.END, dpToPxInt(3));
        set.applyTo(homeRootLayout);
    }

    private void showElapsedCustomizeSheet() {
        if (!isAdded() || getActivity() == null) return;

        ArrayList<com.fadcam.ui.picker.OptionItem> items = new ArrayList<>();
        items.add(new com.fadcam.ui.picker.OptionItem(
                "elapsed_alignment",
                getString(R.string.home_elapsed_alignment_option),
                getString(R.string.home_elapsed_alignment_option_desc),
                null, null, R.drawable.ic_arrow_right, null, null, "format_align_left"));
        items.add(new com.fadcam.ui.picker.OptionItem(
                "elapsed_size",
                getString(R.string.home_elapsed_size_option),
                getString(R.string.home_elapsed_size_option_desc),
                null, null, R.drawable.ic_arrow_right, null, null, "format_size"));
        items.add(new com.fadcam.ui.picker.OptionItem(
                "elapsed_font",
                getString(R.string.home_elapsed_font_option),
                getString(R.string.home_elapsed_font_option_desc),
                null, null, R.drawable.ic_arrow_right, null, null, "font_download"));
        items.add(new com.fadcam.ui.picker.OptionItem(
                "elapsed_flag",
                getString(R.string.home_elapsed_flag_option),
                getString(R.string.home_elapsed_flag_option_desc),
                null, null, null, true,
                sharedPreferencesManager != null && sharedPreferencesManager.sharedPreferences.getBoolean(Constants.PREF_HOME_ELAPSED_SHOW_FLAG, true),
                "flag"));
        items.add(new com.fadcam.ui.picker.OptionItem(
                "elapsed_background",
                getString(R.string.home_elapsed_background_option),
                getString(R.string.home_elapsed_background_option_desc),
                null, null, R.drawable.ic_arrow_right, null, null, "crop_portrait"));

        getParentFragmentManager().setFragmentResultListener(
                ELAPSED_CUSTOMIZE_RESULT_KEY,
                getViewLifecycleOwner(),
                (key, bundle) -> {
                    if (bundle == null) return;
                    String selected = bundle.getString(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID,
                            "");
                    if ("elapsed_alignment".equals(selected)) {
                        showElapsedAlignmentSheet();
                    } else if ("elapsed_size".equals(selected)) {
                        showElapsedSizeSheet();
                    } else if ("elapsed_font".equals(selected)) {
                        showElapsedFontSheet();
                    } else if ("elapsed_flag".equals(selected)) {
                        boolean enabled = bundle.getBoolean(
                                com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE,
                                true);
                        if (sharedPreferencesManager != null) {
                            sharedPreferencesManager.sharedPreferences.edit()
                                    .putBoolean(Constants.PREF_HOME_ELAPSED_SHOW_FLAG, enabled)
                                    .apply();
                        }
                        applyElapsedFlagPreference();
                    } else if ("elapsed_background".equals(selected)) {
                        showElapsedBackgroundSheet();
                    }
                });

        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
                com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceGradient(
                        getString(R.string.home_elapsed_customize_title),
                        items,
                        null,
                        ELAPSED_CUSTOMIZE_RESULT_KEY,
                        getString(R.string.home_elapsed_customize_helper),
                        true);
        Bundle args = sheet.getArguments();
        if (args != null) {
            args.putBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.ARG_HIDE_CHECK, true);
        }
        sheet.show(getParentFragmentManager(), "home_elapsed_customize_sheet");
    }

    private void showElapsedAlignmentSheet() {
        if (!isAdded() || getActivity() == null) return;

        ArrayList<com.fadcam.ui.picker.OptionItem> items = new ArrayList<>();
        items.add(new com.fadcam.ui.picker.OptionItem(
                ELAPSED_ALIGNMENT_CENTER,
                getString(R.string.home_elapsed_align_center),
                getString(R.string.home_elapsed_align_center_desc),
                null, null, null, null, null, "format_align_center"));
        items.add(new com.fadcam.ui.picker.OptionItem(
                ELAPSED_ALIGNMENT_START,
                getString(R.string.home_elapsed_align_left),
                getString(R.string.home_elapsed_align_left_desc),
                null, null, null, null, null, "format_align_left"));

        String selectedId = sharedPreferencesManager != null
                ? sharedPreferencesManager.sharedPreferences.getString(
                        Constants.PREF_HOME_ELAPSED_ALIGNMENT,
                        ELAPSED_ALIGNMENT_CENTER)
                : ELAPSED_ALIGNMENT_CENTER;

        getParentFragmentManager().setFragmentResultListener(
                ELAPSED_ALIGNMENT_RESULT_KEY,
                getViewLifecycleOwner(),
                (key, bundle) -> {
                    if (bundle == null) return;
                    String selected = bundle.getString(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID,
                            ELAPSED_ALIGNMENT_CENTER);
                    if (sharedPreferencesManager != null) {
                        sharedPreferencesManager.sharedPreferences.edit()
                                .putString(Constants.PREF_HOME_ELAPSED_ALIGNMENT, selected)
                                .apply();
                    }
                    applyElapsedAlignmentPreference();
                });

        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
                com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceGradient(
                        getString(R.string.home_elapsed_alignment_option),
                        items,
                        selectedId,
                        ELAPSED_ALIGNMENT_RESULT_KEY,
                        getString(R.string.home_elapsed_alignment_helper),
                        true);
        Bundle args = sheet.getArguments();
        if (args != null) {
            args.putBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.ARG_HIDE_CHECK, true);
        }
        sheet.show(getParentFragmentManager(), "home_elapsed_alignment_sheet");
    }

    private void showElapsedSizeSheet() {
        if (!isAdded() || getActivity() == null) return;

        ArrayList<com.fadcam.ui.picker.OptionItem> items = new ArrayList<>();
        items.add(new com.fadcam.ui.picker.OptionItem(
                ELAPSED_SIZE_SMALL,
                getString(R.string.home_elapsed_size_small),
                getString(R.string.home_elapsed_size_small_desc),
                null, null, null, null, null, "short_text"));
        items.add(new com.fadcam.ui.picker.OptionItem(
                ELAPSED_SIZE_MEDIUM,
                getString(R.string.home_elapsed_size_medium),
                getString(R.string.home_elapsed_size_medium_desc),
                null, null, null, null, null, "format_size"));
        items.add(new com.fadcam.ui.picker.OptionItem(
                ELAPSED_SIZE_LARGE,
                getString(R.string.home_elapsed_size_large),
                getString(R.string.home_elapsed_size_large_desc),
                null, null, null, null, null, "title"));

        String selectedId = sharedPreferencesManager != null
                ? sharedPreferencesManager.sharedPreferences.getString(
                        Constants.PREF_HOME_ELAPSED_SIZE,
                        ELAPSED_SIZE_MEDIUM)
                : ELAPSED_SIZE_MEDIUM;

        getParentFragmentManager().setFragmentResultListener(
                ELAPSED_SIZE_RESULT_KEY,
                getViewLifecycleOwner(),
                (key, bundle) -> {
                    if (bundle == null) return;
                    String selected = bundle.getString(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID,
                            ELAPSED_SIZE_MEDIUM);
                    if (sharedPreferencesManager != null) {
                        sharedPreferencesManager.sharedPreferences.edit()
                                .putString(Constants.PREF_HOME_ELAPSED_SIZE, selected)
                                .apply();
                    }
                    applyElapsedSizePreference();
                });

        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
                com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceGradient(
                        getString(R.string.home_elapsed_size_option),
                        items,
                        selectedId,
                        ELAPSED_SIZE_RESULT_KEY,
                        getString(R.string.home_elapsed_size_helper),
                        true);
        Bundle args = sheet.getArguments();
        if (args != null) {
            args.putBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.ARG_HIDE_CHECK, true);
        }
        sheet.show(getParentFragmentManager(), "home_elapsed_size_sheet");
    }

    private void showElapsedFontSheet() {
        if (!isAdded() || getActivity() == null) return;

        ArrayList<com.fadcam.ui.picker.OptionItem> items = new ArrayList<>();
        items.add(new com.fadcam.ui.picker.OptionItem(
                ELAPSED_FONT_UBUNTU,
                getString(R.string.home_elapsed_font_ubuntu),
                getString(R.string.home_elapsed_font_ubuntu_desc),
                null, null, null, null, null, "font_download"));
        items.add(new com.fadcam.ui.picker.OptionItem(
                ELAPSED_FONT_MONOSPACE,
                getString(R.string.home_elapsed_font_monospace),
                getString(R.string.home_elapsed_font_monospace_desc),
                null, null, null, null, null, "code"));
        items.add(new com.fadcam.ui.picker.OptionItem(
                ELAPSED_FONT_DOTO,
                getString(R.string.home_elapsed_font_doto),
                getString(R.string.home_elapsed_font_doto_desc),
                null, null, null, null, null, "auto_awesome"));

        String selectedId = sharedPreferencesManager != null
                ? sharedPreferencesManager.sharedPreferences.getString(
                        Constants.PREF_HOME_ELAPSED_FONT,
                        ELAPSED_FONT_UBUNTU)
                : ELAPSED_FONT_UBUNTU;

        getParentFragmentManager().setFragmentResultListener(
                ELAPSED_FONT_RESULT_KEY,
                getViewLifecycleOwner(),
                (key, bundle) -> {
                    if (bundle == null) return;
                    String selected = bundle.getString(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID,
                            ELAPSED_FONT_UBUNTU);
                    if (sharedPreferencesManager != null) {
                        sharedPreferencesManager.sharedPreferences.edit()
                                .putString(Constants.PREF_HOME_ELAPSED_FONT, selected)
                                .apply();
                    }
                    applyElapsedFontPreference();
                });

        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
                com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceGradient(
                        getString(R.string.home_elapsed_font_option),
                        items,
                        selectedId,
                        ELAPSED_FONT_RESULT_KEY,
                        getString(R.string.home_elapsed_font_helper),
                        true);
        Bundle args = sheet.getArguments();
        if (args != null) {
            args.putBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.ARG_HIDE_CHECK, true);
        }
        sheet.show(getParentFragmentManager(), "home_elapsed_font_sheet");
    }

    private void showElapsedFlagSheet() {
        if (!isAdded() || getActivity() == null) return;

        ArrayList<com.fadcam.ui.picker.OptionItem> items = new ArrayList<>();
        items.add(new com.fadcam.ui.picker.OptionItem(
                ELAPSED_FLAG_SHOW,
                getString(R.string.home_elapsed_flag_show),
                getString(R.string.home_elapsed_flag_show_desc),
                null, null, null, null, null, "flag"));
        items.add(new com.fadcam.ui.picker.OptionItem(
                ELAPSED_FLAG_HIDE,
                getString(R.string.home_elapsed_flag_hide),
                getString(R.string.home_elapsed_flag_hide_desc),
                null, null, null, null, null, "hide_image"));

        String selectedId = sharedPreferencesManager != null
                ? (sharedPreferencesManager.sharedPreferences.getBoolean(
                        Constants.PREF_HOME_ELAPSED_SHOW_FLAG, true)
                        ? ELAPSED_FLAG_SHOW : ELAPSED_FLAG_HIDE)
                : ELAPSED_FLAG_SHOW;

        getParentFragmentManager().setFragmentResultListener(
                ELAPSED_FLAG_RESULT_KEY,
                getViewLifecycleOwner(),
                (key, bundle) -> {
                    if (bundle == null) return;
                    String selected = bundle.getString(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID,
                            ELAPSED_FLAG_SHOW);
                    if (sharedPreferencesManager != null) {
                        sharedPreferencesManager.sharedPreferences.edit()
                                .putBoolean(Constants.PREF_HOME_ELAPSED_SHOW_FLAG, ELAPSED_FLAG_SHOW.equals(selected))
                                .apply();
                    }
                    applyElapsedFlagPreference();
                });

        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
                com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceGradient(
                        getString(R.string.home_elapsed_flag_option),
                        items,
                        selectedId,
                        ELAPSED_FLAG_RESULT_KEY,
                        getString(R.string.home_elapsed_flag_helper),
                        true);
        Bundle args = sheet.getArguments();
        if (args != null) {
            args.putBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.ARG_HIDE_CHECK, true);
        }
        sheet.show(getParentFragmentManager(), "home_elapsed_flag_sheet");
    }

    private void showElapsedBackgroundSheet() {
        if (!isAdded() || getActivity() == null) return;

        ArrayList<com.fadcam.ui.picker.OptionItem> items = new ArrayList<>();
        items.add(new com.fadcam.ui.picker.OptionItem(
                ELAPSED_BACKGROUND_BLACK,
                getString(R.string.home_elapsed_background_black),
                getString(R.string.home_elapsed_background_black_desc),
                null, null, null, null, null, "crop_portrait"));
        items.add(new com.fadcam.ui.picker.OptionItem(
                ELAPSED_BACKGROUND_WHITE,
                getString(R.string.home_elapsed_background_white),
                getString(R.string.home_elapsed_background_white_desc),
                null, null, null, null, null, "crop_portrait"));
        items.add(new com.fadcam.ui.picker.OptionItem(
                ELAPSED_BACKGROUND_TRANSPARENT,
                getString(R.string.home_elapsed_background_transparent),
                getString(R.string.home_elapsed_background_transparent_desc),
                null, null, null, null, null, "texture"));

        String selectedId = sharedPreferencesManager != null
                ? sharedPreferencesManager.sharedPreferences.getString(
                        Constants.PREF_HOME_ELAPSED_BACKGROUND,
                        ELAPSED_BACKGROUND_BLACK)
                : ELAPSED_BACKGROUND_BLACK;

        getParentFragmentManager().setFragmentResultListener(
                ELAPSED_BACKGROUND_RESULT_KEY,
                getViewLifecycleOwner(),
                (key, bundle) -> {
                    if (bundle == null) return;
                    String selected = bundle.getString(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID,
                            ELAPSED_BACKGROUND_BLACK);
                    if (sharedPreferencesManager != null) {
                        sharedPreferencesManager.sharedPreferences.edit()
                                .putString(Constants.PREF_HOME_ELAPSED_BACKGROUND, selected)
                                .apply();
                    }
                    updateElapsedHeroAppearance();
                });

        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
                com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceGradient(
                        getString(R.string.home_elapsed_background_option),
                        items,
                        selectedId,
                        ELAPSED_BACKGROUND_RESULT_KEY,
                        getString(R.string.home_elapsed_background_helper),
                        true);
        Bundle args = sheet.getArguments();
        if (args != null) {
            args.putBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.ARG_HIDE_CHECK, true);
        }
        sheet.show(getParentFragmentManager(), "home_elapsed_background_sheet");
    }

    private void showStorageCustomizeSheet() {
        if (!isAdded() || getActivity() == null) return;

        ArrayList<com.fadcam.ui.picker.OptionItem> items = new ArrayList<>();
        items.add(new com.fadcam.ui.picker.OptionItem(
                "storage_style",
                getString(R.string.home_storage_indicator_option),
                getString(R.string.home_storage_indicator_option_desc),
                null, null, R.drawable.ic_arrow_right, null, null, "donut_large"));
        items.add(new com.fadcam.ui.picker.OptionItem(
                "storage_total",
                getString(R.string.home_storage_total_option),
                getString(R.string.home_storage_total_option_desc),
                null, null, null, true,
                sharedPreferencesManager != null && sharedPreferencesManager.sharedPreferences.getBoolean(Constants.PREF_HOME_STORAGE_SHOW_TOTAL, true),
                "storage"));

        getParentFragmentManager().setFragmentResultListener(
                STORAGE_CUSTOMIZE_RESULT_KEY,
                getViewLifecycleOwner(),
                (key, bundle) -> {
                    if (bundle == null) return;
                    String selected = bundle.getString(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID,
                            "");
                    if ("storage_style".equals(selected)) {
                        showStorageIndicatorStyleSheet();
                    } else if ("storage_total".equals(selected)) {
                        boolean enabled = bundle.getBoolean(
                                com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE,
                                true);
                        if (sharedPreferencesManager != null) {
                            sharedPreferencesManager.sharedPreferences.edit()
                                    .putBoolean(Constants.PREF_HOME_STORAGE_SHOW_TOTAL, enabled)
                                    .apply();
                        }
                        applyStorageTotalVisibilityPreference();
                    }
                });

        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
                com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceGradient(
                        getString(R.string.home_storage_customize_title),
                        items,
                        null,
                        STORAGE_CUSTOMIZE_RESULT_KEY,
                        getString(R.string.home_storage_customize_helper),
                        true);
        Bundle args = sheet.getArguments();
        if (args != null) {
            args.putBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.ARG_HIDE_CHECK, true);
        }
        sheet.show(getParentFragmentManager(), "home_storage_customize_sheet");
    }

    private void showStorageIndicatorStyleSheet() {
        if (!isAdded() || getActivity() == null) return;

        ArrayList<com.fadcam.ui.picker.OptionItem> items = new ArrayList<>();
        items.add(new com.fadcam.ui.picker.OptionItem(
                STORAGE_INDICATOR_RING,
                getString(R.string.home_storage_indicator_ring),
                getString(R.string.home_storage_indicator_ring_desc),
                null, null, null, null, null, "donut_large"));
        items.add(new com.fadcam.ui.picker.OptionItem(
                STORAGE_INDICATOR_MICRO_PILL,
                getString(R.string.home_storage_indicator_bar),
                getString(R.string.home_storage_indicator_bar_desc),
                null, null, null, null, null, "view_stream"));
        items.add(new com.fadcam.ui.picker.OptionItem(
                STORAGE_INDICATOR_VERTICAL_BAR,
                getString(R.string.home_storage_indicator_vertical),
                getString(R.string.home_storage_indicator_vertical_desc),
                null, null, null, null, null, "align_vertical_bottom"));

        String selectedId = sharedPreferencesManager != null
                ? sharedPreferencesManager.sharedPreferences.getString(
                        Constants.PREF_HOME_STORAGE_INDICATOR_STYLE,
                        STORAGE_INDICATOR_RING)
                : STORAGE_INDICATOR_RING;

        getParentFragmentManager().setFragmentResultListener(
                STORAGE_INDICATOR_STYLE_RESULT_KEY,
                getViewLifecycleOwner(),
                (key, bundle) -> {
                    if (bundle == null) return;
                    String selected = bundle.getString(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID,
                            STORAGE_INDICATOR_RING);
                    if (sharedPreferencesManager != null) {
                        sharedPreferencesManager.sharedPreferences.edit()
                                .putString(Constants.PREF_HOME_STORAGE_INDICATOR_STYLE, selected)
                                .apply();
                    }
                    applyStorageIndicatorStylePreference();
                });

        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
                com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceGradient(
                        getString(R.string.home_storage_indicator_option),
                        items,
                        selectedId,
                        STORAGE_INDICATOR_STYLE_RESULT_KEY,
                        getString(R.string.home_storage_indicator_helper),
                        true);
        Bundle args = sheet.getArguments();
        if (args != null) {
            args.putBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.ARG_HIDE_CHECK, true);
        }
        sheet.show(getParentFragmentManager(), "home_storage_indicator_style_sheet");
    }

    private void showStorageTotalSheet() {
        if (!isAdded() || getActivity() == null) return;

        ArrayList<com.fadcam.ui.picker.OptionItem> items = new ArrayList<>();
        items.add(new com.fadcam.ui.picker.OptionItem(
                STORAGE_TOTAL_VISIBLE,
                getString(R.string.home_storage_total_show),
                getString(R.string.home_storage_total_show_desc),
                null, null, null, null, null, "visibility"));
        items.add(new com.fadcam.ui.picker.OptionItem(
                STORAGE_TOTAL_HIDDEN,
                getString(R.string.home_storage_total_hide),
                getString(R.string.home_storage_total_hide_desc),
                null, null, null, null, null, "visibility_off"));

        String selectedId = sharedPreferencesManager != null
                ? (sharedPreferencesManager.sharedPreferences.getBoolean(
                        Constants.PREF_HOME_STORAGE_SHOW_TOTAL, true)
                        ? STORAGE_TOTAL_VISIBLE : STORAGE_TOTAL_HIDDEN)
                : STORAGE_TOTAL_VISIBLE;

        getParentFragmentManager().setFragmentResultListener(
                STORAGE_TOTAL_RESULT_KEY,
                getViewLifecycleOwner(),
                (key, bundle) -> {
                    if (bundle == null) return;
                    String selected = bundle.getString(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID,
                            STORAGE_TOTAL_VISIBLE);
                    if (sharedPreferencesManager != null) {
                        sharedPreferencesManager.sharedPreferences.edit()
                                .putBoolean(Constants.PREF_HOME_STORAGE_SHOW_TOTAL, STORAGE_TOTAL_VISIBLE.equals(selected))
                                .apply();
                    }
                    applyStorageTotalVisibilityPreference();
                });

        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
                com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceGradient(
                        getString(R.string.home_storage_total_option),
                        items,
                        selectedId,
                        STORAGE_TOTAL_RESULT_KEY,
                        getString(R.string.home_storage_total_helper),
                        true);
        Bundle args = sheet.getArguments();
        if (args != null) {
            args.putBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.ARG_HIDE_CHECK, true);
        }
        sheet.show(getParentFragmentManager(), "home_storage_total_sheet");
    }

    private void showTimeLeftColorSheet() {
        if (!isAdded() || getActivity() == null) return;

        ArrayList<com.fadcam.ui.picker.OptionItem> items = new ArrayList<>();
        String currentHex = sharedPreferencesManager != null
                ? sharedPreferencesManager.sharedPreferences.getString(
                        Constants.PREF_HOME_TIME_LEFT_COLOR,
                        "#F44336")
                : "#F44336";
        String selectedId = currentHex;
        for (int i = 0; i < CLOCK_COLOR_HEX_VALUES.length; i++) {
            items.add(new com.fadcam.ui.picker.OptionItem(
                    CLOCK_COLOR_HEX_VALUES[i],
                    CLOCK_COLOR_NAMES[i],
                    getString(R.string.home_time_left_color_choice_desc, CLOCK_COLOR_HEX_VALUES[i]),
                    Color.parseColor(CLOCK_COLOR_HEX_VALUES[i]),
                    null, null, null, null, null));
        }

        getParentFragmentManager().setFragmentResultListener(
                TIME_LEFT_COLOR_RESULT_KEY,
                getViewLifecycleOwner(),
                (key, bundle) -> {
                    if (bundle == null) return;
                    String selected = bundle.getString(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID,
                            "#F44336");
                    if (sharedPreferencesManager != null) {
                        sharedPreferencesManager.sharedPreferences.edit()
                                .putString(Constants.PREF_HOME_TIME_LEFT_COLOR, selected)
                                .apply();
                    }
                    applyTimeLeftAccentPreference();
                });

        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
                com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceGradient(
                        getString(R.string.home_time_left_color_title),
                        items,
                        selectedId,
                        TIME_LEFT_COLOR_RESULT_KEY,
                        getString(R.string.home_time_left_color_helper),
                        true);
        Bundle args = sheet.getArguments();
        if (args != null) {
            args.putBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.ARG_HIDE_CHECK, true);
        }
        sheet.show(getParentFragmentManager(), "home_time_left_color_sheet");
    }

    private void applyElapsedAlignmentPreference() {
        if (sharedPreferencesManager == null || layoutElapsedContent == null) {
            return;
        }

        String alignment = sharedPreferencesManager.sharedPreferences.getString(
                Constants.PREF_HOME_ELAPSED_ALIGNMENT,
                ELAPSED_ALIGNMENT_CENTER);
        boolean startAligned = ELAPSED_ALIGNMENT_START.equals(alignment);
        int startInset = startAligned ? dpToPxInt(18) : 0;

        layoutElapsedContent.setGravity(startAligned ? Gravity.START : Gravity.CENTER_HORIZONTAL);
        layoutElapsedContent.setPadding(startInset, 0, 0, 0);

        if (layoutElapsedMetaRow != null) {
            layoutElapsedMetaRow.setGravity(startAligned
                    ? (Gravity.START | Gravity.CENTER_VERTICAL)
                    : Gravity.CENTER);
        }

        if (tvElapsedTitle != null) {
            tvElapsedTitle.setGravity(startAligned ? Gravity.START : Gravity.CENTER);
            ViewGroup.LayoutParams params = tvElapsedTitle.getLayoutParams();
            if (params instanceof LinearLayout.LayoutParams) {
                ((LinearLayout.LayoutParams) params).gravity = startAligned ? Gravity.START : Gravity.CENTER_HORIZONTAL;
                tvElapsedTitle.setLayoutParams(params);
            }
            tvElapsedTitle.setTextAlignment(startAligned ? View.TEXT_ALIGNMENT_VIEW_START : View.TEXT_ALIGNMENT_CENTER);
        }

        if (tvElapsedSubtitle != null) {
            tvElapsedSubtitle.setTextAlignment(startAligned ? View.TEXT_ALIGNMENT_VIEW_START : View.TEXT_ALIGNMENT_CENTER);
        }
    }

    private void applyElapsedSizePreference() {
        if (sharedPreferencesManager == null || tvElapsedTitle == null) {
            return;
        }

        String size = sharedPreferencesManager.sharedPreferences.getString(
                Constants.PREF_HOME_ELAPSED_SIZE,
                ELAPSED_SIZE_MEDIUM);
        float textSizeSp;
        if (ELAPSED_SIZE_SMALL.equals(size)) {
            textSizeSp = isLandscapeMode() ? 16f : 18f;
        } else if (ELAPSED_SIZE_LARGE.equals(size)) {
            textSizeSp = isLandscapeMode() ? 26f : 29f;
        } else {
            textSizeSp = isLandscapeMode() ? 21f : 23f;
        }
        tvElapsedTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSizeSp);
    }

    private void applyElapsedFontPreference() {
        if (sharedPreferencesManager == null || tvElapsedTitle == null) {
            return;
        }
        String fontPref = sharedPreferencesManager.sharedPreferences.getString(
                Constants.PREF_HOME_ELAPSED_FONT,
                ELAPSED_FONT_UBUNTU);
        if (ELAPSED_FONT_MONOSPACE.equals(fontPref)) {
            tvElapsedTitle.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        } else if (ELAPSED_FONT_DOTO.equals(fontPref)) {
            try {
                android.graphics.Typeface doto = android.graphics.Typeface.createFromAsset(
                        requireContext().getAssets(),
                        "doto.ttf");
                tvElapsedTitle.setTypeface(doto, android.graphics.Typeface.BOLD);
            } catch (Exception e) {
                android.graphics.Typeface ubuntu = ResourcesCompat.getFont(requireContext(), R.font.ubuntu_regular);
                if (ubuntu != null) {
                    tvElapsedTitle.setTypeface(ubuntu, android.graphics.Typeface.BOLD);
                }
            }
        } else {
            android.graphics.Typeface ubuntu = ResourcesCompat.getFont(requireContext(), R.font.ubuntu_regular);
            if (ubuntu != null) {
                tvElapsedTitle.setTypeface(ubuntu, android.graphics.Typeface.BOLD);
            }
        }
    }

    private void applyElapsedFlagPreference() {
        if (sharedPreferencesManager == null || ivElapsedAccent == null) {
            return;
        }
        boolean showFlag = sharedPreferencesManager.sharedPreferences.getBoolean(
                Constants.PREF_HOME_ELAPSED_SHOW_FLAG,
                true);
        ivElapsedAccent.setVisibility(showFlag ? View.VISIBLE : View.GONE);
        if (cardElapsedHero != null) {
            cardElapsedHero.setMinimumHeight(dpToPxInt(isLandscapeMode() ? 46 : 48));
        }
    }

    private void applyStorageIndicatorStylePreference() {
        if (sharedPreferencesManager == null || storageProgressRing == null) {
            return;
        }

        String style = sharedPreferencesManager.sharedPreferences.getString(
                Constants.PREF_HOME_STORAGE_INDICATOR_STYLE,
                STORAGE_INDICATOR_RING);
        int viewStyle;
        if (STORAGE_INDICATOR_MICRO_PILL.equals(style)) {
            viewStyle = com.fadcam.ui.utils.StorageProgressRingView.STYLE_MICRO_PILL_BAR;
        } else if (STORAGE_INDICATOR_VERTICAL_BAR.equals(style)) {
            viewStyle = com.fadcam.ui.utils.StorageProgressRingView.STYLE_VERTICAL_BAR;
        } else {
            viewStyle = com.fadcam.ui.utils.StorageProgressRingView.STYLE_RING;
        }
        storageProgressRing.setIndicatorStyle(viewStyle);
    }

    private void applyStorageTotalVisibilityPreference() {
        if (sharedPreferencesManager == null || tvSpaceTotal == null) {
            return;
        }
        boolean showTotal = sharedPreferencesManager.sharedPreferences.getBoolean(
                Constants.PREF_HOME_STORAGE_SHOW_TOTAL,
                true);
        tvSpaceTotal.setVisibility(showTotal ? View.VISIBLE : View.GONE);
    }

    private void applyTimeLeftAccentPreference() {
        if (sharedPreferencesManager == null) {
            return;
        }
        String colorHex = sharedPreferencesManager.sharedPreferences.getString(
                Constants.PREF_HOME_TIME_LEFT_COLOR,
                "#F44336");
        int accentColor = Color.parseColor(colorHex);
        if (tvEstimateTitle != null) {
            tvEstimateTitle.setTextColor(accentColor);
        }
        if (ivEstimateIcon != null) {
            ivEstimateIcon.setColorFilter(accentColor);
        }
    }

    private void updateElapsedHeroAppearance() {
        if (cardElapsedHero == null) {
            return;
        }

        String backgroundPref = sharedPreferencesManager != null
                ? sharedPreferencesManager.sharedPreferences.getString(
                        Constants.PREF_HOME_ELAPSED_BACKGROUND,
                        ELAPSED_BACKGROUND_BLACK)
                : ELAPSED_BACKGROUND_BLACK;
        boolean useBlackCard = ELAPSED_BACKGROUND_BLACK.equals(backgroundPref);
        boolean useWhiteCard = ELAPSED_BACKGROUND_WHITE.equals(backgroundPref);

        int backgroundColor;
        int strokeColor;
        int railStrokeColor = resolveThemeColor(R.attr.homeRailCardBorder);
        int titleColor;
        int subtitleColor;
        int iconColor;
        int stateIconRes;

        if (isModePausedForElapsedAppearance()) {
            backgroundColor = useBlackCard
                    ? Color.parseColor("#E6000000")
                    : useWhiteCard ? Color.parseColor("#F7F3EE") : Color.TRANSPARENT;
            strokeColor = useBlackCard || useWhiteCard ? railStrokeColor : Color.TRANSPARENT;
            titleColor = useWhiteCard ? Color.parseColor("#6C4B12") : Color.parseColor("#FFF6E2");
            subtitleColor = useWhiteCard ? Color.parseColor("#A07024") : Color.parseColor("#D8B06C");
            iconColor = useWhiteCard ? Color.parseColor("#A07024") : Color.parseColor("#C8923A");
            stateIconRes = R.drawable.pause_rounded;
        } else if (isModeRecordingForElapsedAppearance()) {
            backgroundColor = useBlackCard
                    ? Color.parseColor("#E6000000")
                    : useWhiteCard ? Color.parseColor("#F7FBF8") : Color.TRANSPARENT;
            strokeColor = useBlackCard || useWhiteCard ? railStrokeColor : Color.TRANSPARENT;
            titleColor = useWhiteCard ? Color.parseColor("#165B36") : Color.parseColor("#EDFFF4");
            subtitleColor = useWhiteCard ? Color.parseColor("#2F8A5A") : Color.parseColor("#8DE0AC");
            iconColor = useWhiteCard ? Color.parseColor("#2F8A5A") : Color.parseColor("#56C889");
            stateIconRes = R.drawable.play_button_rounded;
        } else {
            backgroundColor = useBlackCard
                    ? Color.parseColor("#E6000000")
                    : useWhiteCard ? Color.parseColor("#F4F6F8") : Color.TRANSPARENT;
            strokeColor = useBlackCard || useWhiteCard ? railStrokeColor : Color.TRANSPARENT;
            titleColor = useWhiteCard ? Color.parseColor("#33424C") : Color.parseColor("#C9D5DE");
            subtitleColor = useWhiteCard ? Color.parseColor("#667682") : Color.parseColor("#90A4AE");
            iconColor = useWhiteCard ? Color.parseColor("#667682") : Color.parseColor("#6B7C88");
            stateIconRes = R.drawable.play_button_rounded;
        }

        cardElapsedHero.setCardBackgroundColor(backgroundColor);
        cardElapsedHero.setStrokeColor(strokeColor);
        cardElapsedHero.setStrokeWidth((useBlackCard || useWhiteCard) ? dpToPxInt(1) : 0);
        cardElapsedHero.setRadius(dpToPxInt(12));
        cardElapsedHero.setCardElevation((useBlackCard || useWhiteCard) ? dpToPxInt(4) : 0);
        cardElapsedHero.setMinimumHeight(dpToPxInt(isLandscapeMode() ? 46 : 48));

        if (tvElapsedTitle != null) {
            tvElapsedTitle.setTextColor(titleColor);
        }
        if (tvElapsedSubtitle != null) {
            tvElapsedSubtitle.setTextColor(subtitleColor);
        }
        if (tvElapsedStateIcon != null) {
            tvElapsedStateIcon.setImageResource(stateIconRes);
            tvElapsedStateIcon.setColorFilter(iconColor);
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
            FLog.e(TAG, "Error making storage text white: " + e.getMessage());
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
            FLog.e(TAG, "Error making text black: " + e.getMessage());
        }
    }


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

    /**
     * Updates hamburger menu badge visibility based on "What's New" feature status.
     * Shows green badge dot if the user hasn't seen the What's New feature yet.
     */
    private void updateHamburgerBadgeVisibility() {
        if (hamburgerBadgeDot != null) {
            try {
                boolean showBadge = com.fadcam.ui.utils.NewFeatureManager.shouldShowBadge(requireContext(), "whats_new");
                hamburgerBadgeDot.setVisibility(showBadge ? View.VISIBLE : View.GONE);
            } catch (Exception e) {
                FLog.e(TAG, "Error updating hamburger badge visibility", e);
            }
        }
    }
}
