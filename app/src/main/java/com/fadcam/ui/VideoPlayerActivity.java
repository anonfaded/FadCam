package com.fadcam.ui;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log; // Use standard Android Log
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.ui.picker.OptionItem;
import com.fadcam.ui.picker.PickerBottomSheetFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Locale;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.provider.Settings;

public class VideoPlayerActivity extends AppCompatActivity {

    private static final String TAG = "VideoPlayerActivity";

    private ExoPlayer player;
    private PlayerView playerView;
    private android.view.GestureDetector gestureDetector;
    private android.view.ScaleGestureDetector scaleGestureDetector;
    private float currentScale = 1.0f;
    private float currentTranslationX = 0f;
    private float currentTranslationY = 0f;
    private boolean isPinching = false;
    private ImageButton resetZoomButton;
    // Shared preferences runtime sync helpers
    private android.content.SharedPreferences appPrefs;
    private android.content.SharedPreferences.OnSharedPreferenceChangeListener prefsListener;
    private static final String ACTION_PLAYBACK_POSITION_UPDATED =
        "com.fadcam.ACTION_PLAYBACK_POSITION_UPDATED";
    private static final String EXTRA_URI = "extra_uri";
    private static final String EXTRA_POSITION_MS = "extra_position_ms";
    private ImageButton backButton;
    private ImageButton infoButton;
    private ImageButton settingsButton; // For playback speed
    private TextView quickSpeedOverlay;
    private SharedPreferencesManager spm;
    private com.fadcam.ui.custom.AudioWaveformView audioWaveformView;
    private android.net.Uri currentVideoUri;
    private com.google.android.material.slider.Slider timeSlider;
    // Cached duration for fragmented MP4s where player.getDuration() returns TIME_UNSET
    private long cachedDurationMs = C.TIME_UNSET;
    // Gesture/interaction helpers
    private AudioManager audioManager;
    private int maxStreamVolume = 0;
    private int initialStreamVolume = 0;
    private float initialBrightness = -1f; // 0..1
    private float lastGestureBrightness = -1f; // persisted across gesture sessions in-memory
    private int gestureMode = 0; // 0 = none, 1 = seek, 2 = volume, 3 = brightness
    private long seekStartPosition = 0L;
    private float gestureStartX = 0f;
    private float gestureStartY = 0f;
    private int lastSeekDirection = 1; // 1 = right/forward, -1 = left/rewind
    private android.os.Handler controlsHideHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable controlsHideRunnable = null;
    // Controller enforcement during scrubbing
    private android.os.Handler scrubControllerEnforceHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable scrubControllerEnforceRunnable = null;
    // Periodic resume-save handler
    private final android.os.Handler resumeSaveHandler = new android.os.Handler(
        android.os.Looper.getMainLooper()
    );
    private final Runnable resumeSaveRunnable = new Runnable() {
        private long lastSavedPos = -1;

        @Override
        public void run() {
            try {
                if (player == null) return;
                boolean isPlaying = false;
                try {
                    isPlaying = player.isPlaying();
                } catch (Exception ignored) {}
                if (isPlaying) {
                    long pos = player.getCurrentPosition();
                    if (Math.abs(pos - lastSavedPos) > 1000) {
                        // only save if moved >1s
                        com.fadcam.playback.PlayerHolder holder =
                            com.fadcam.playback.PlayerHolder.getInstance();
                        Uri cur = holder.getCurrentUri();
                        String filename = null;
                        try {
                            filename = getFileName(cur);
                        } catch (Exception ignored) {}
                        SharedPreferencesManager spmLoc =
                            SharedPreferencesManager.getInstance(
                                VideoPlayerActivity.this
                            );
                        if (cur != null) spmLoc.setSavedPlaybackPositionMs(
                            cur.toString(),
                            pos
                        );
                        if (
                            filename != null && !filename.isEmpty()
                        ) spmLoc.setSavedPlaybackPositionMsByFilename(
                            filename,
                            pos
                        );
                        lastSavedPos = pos;
                        Log.d(
                            TAG,
                            "Periodic save position: " +
                            pos +
                            " for " +
                            (cur != null ? cur : filename)
                        );
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error during periodic resume save", e);
            }
            // Re-post
            resumeSaveHandler.postDelayed(this, 5000);
        }
    };
    private static final String RK_VIDEO_SETTINGS = "rk_video_settings";
    private static final String RK_PLAYBACK_SPEED = "rk_playback_speed";
    private static final String RK_QUICK_SPEED = "rk_quick_speed";
    private static final String RK_KEEP_SCREEN_ON = "rk_keep_screen_on";
    private static final String RK_BACKGROUND_PLAYBACK =
        "rk_background_playback";

    // Playback speed options (include more slow-speed choices 0.25 -> 0.9)
    private final CharSequence[] speedOptions = {
        "0.25x",
        "0.5x",
        "0.6x",
        "0.7x",
        "0.8x",
        "0.9x",
        "1x (Normal)",
        "1.5x",
        "2x",
        "3x",
        "4x",
        "6x",
        "8x",
        "10x",
    };
    private final float[] speedValues = {
        0.25f,
        0.5f,
        0.6f,
        0.7f,
        0.8f,
        0.9f,
        1.0f,
        1.5f,
        2.0f,
        3.0f,
        4.0f,
        6.0f,
        8.0f,
        10.0f,
    };
    private int currentSpeedIndex = 6; // Index for 1.0x speed in updated speedValues (after adding 0.25x)
    // Guard to keep single-tap from being treated as part of double-tap or
    // re-triggering control toggles
    private long lastSingleTapTime = 0L;
    // Keep track of controller auto-hide timeout and scrubbing state
    private int controllerAutoHideMs = -1; // 0 means never hide
    private volatile boolean isTimebarScrubbing = false;
    private volatile long scrubEndTimeMs = 0; // Timestamp when scrubbing ended, for grace period
    private volatile long lastSeekPositionMs = -1; // Last seek position to prevent slider reset
    private static final long SCRUB_GRACE_PERIOD_MS = 1000L; // 1 second grace period after scrub

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Initialize SharedPreferencesManager for theme
        SharedPreferencesManager sharedPreferencesManager =
            SharedPreferencesManager.getInstance(this);
        String savedTheme =
            sharedPreferencesManager.sharedPreferences.getString(
                Constants.PREF_APP_THEME,
                Constants.DEFAULT_APP_THEME
            );

        if ("Crimson Bloom".equals(savedTheme)) {
            setTheme(R.style.Theme_FadCam_Red);
        } else if ("Faded Night".equals(savedTheme)) {
            setTheme(R.style.Theme_FadCam_Amoled);
        } else {
            setTheme(R.style.Base_Theme_FadCam);
        }

        super.onCreate(savedInstanceState);
        setContentView(com.fadcam.R.layout.activity_video_player);
        
        // Ensure hardware volume buttons control media (STREAM_MUSIC) volume
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        playerView = findViewById(com.fadcam.R.id.player_view);
        backButton = findViewById(com.fadcam.R.id.back_button);
        infoButton = findViewById(com.fadcam.R.id.info_button);
        resetZoomButton = findViewById(com.fadcam.R.id.reset_zoom_button);
        // Wire Material time slider if present in control layout
        try {
            timeSlider = playerView.findViewById(R.id.material_time_slider);
        } catch (Exception ignored) {}

    // Top buttons (back and info) should be visible by default and hide together with controls
    try { 
        backButton.setVisibility(View.VISIBLE); 
        infoButton.setVisibility(View.VISIBLE); 
    } catch (Exception ignored) {}
        // Init audio manager for volume gestures
        try {
            audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (audioManager != null) maxStreamVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        } catch (Exception ignored) {}

        // *** FIX: Get the video URI using getData() ***
        Uri videoUri = getIntent().getData();
        this.currentVideoUri = videoUri; // Store for later use

        if (videoUri != null) {
            Log.i(TAG, "Received video URI: " + videoUri.toString());
            spm = SharedPreferencesManager.getInstance(this);
            // Read default playback speed from prefs (if set) and adjust currentSpeedIndex
            float defSpd = sharedPreferencesManager.sharedPreferences.getFloat(
                "pref_default_playback_speed",
                speedValues[currentSpeedIndex]
            );
            for (int i = 0; i < speedValues.length; i++) {
                if (Math.abs(speedValues[i] - defSpd) < 0.001f) {
                    currentSpeedIndex = i;
                    break;
                }
            }
            initializePlayer(videoUri); // Pass the Uri directly
            setupCustomSettingsAction();
            setupQuickSpeedSettings();
            setupResetZoomButton();
            setupPressAndHoldFor2x();
        } else {
            // Log error and finish if URI is missing
            Log.e(
                TAG,
                "Video URI is null. Intent Data: " +
                getIntent().getDataString() +
                ". Cannot initialize player."
            );
            Toast.makeText(
                this,
                "Error: Video URI not found",
                Toast.LENGTH_LONG
            ).show();
            finish();
            return;
        }

        setupBackButton();
        setupInfoButton();

        // Ensure the system status bar matches the header/back-button area color for
        // this activity
        try {
            int statusBarColor = resolveThemeColor(R.attr.colorTopBar);
            getWindow().clearFlags(
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
            );
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
            );
            getWindow().setStatusBarColor(statusBarColor);
        } catch (Exception e) {
            Log.w(TAG, "Failed to set status bar color", e);
        }

        // Configure Material Slider colors and behavior if available
        if (timeSlider != null) {
            try {
                int red = androidx.core.content.ContextCompat.getColor(this, R.color.redPastel);
                int white40 = android.graphics.Color.parseColor("#40FFFFFF");
                int white60 = android.graphics.Color.parseColor("#60FFFFFF");
                timeSlider.setTrackActiveTintList(android.content.res.ColorStateList.valueOf(red));
                timeSlider.setTrackInactiveTintList(android.content.res.ColorStateList.valueOf(white40));
                timeSlider.setHaloTintList(android.content.res.ColorStateList.valueOf(white60));
                timeSlider.setThumbTintList(android.content.res.ColorStateList.valueOf(red));
                // Ensure baseline range is sane
                try {
                    timeSlider.setValueFrom(0f);
                    if (timeSlider.getValueTo() < 1f) timeSlider.setValueTo(1f);
                    // Clamp current value into range
                    float v = timeSlider.getValue();
                    if (v < 0f) timeSlider.setValue(0f);
                    else if (v > timeSlider.getValueTo()) timeSlider.setValue(timeSlider.getValueTo());
                } catch (Exception ignored) {}
            } catch (Exception ignored) {}

            // Keep controller visible while scrubbing via slider
            // Clear any existing listeners first to prevent duplicates causing slider jumping
            try {
                timeSlider.clearOnSliderTouchListeners();
                timeSlider.clearOnChangeListeners();
            } catch (Exception e) {
                Log.w(TAG, "Error clearing slider listeners", e);
            }
            
            timeSlider.addOnSliderTouchListener(new com.google.android.material.slider.Slider.OnSliderTouchListener() {
                @Override
                public void onStartTrackingTouch(com.google.android.material.slider.Slider slider) {
                    // Debounce protection - ignore if already scrubbing
                    if (isTimebarScrubbing) {
                        return;
                    }
                    
                    isTimebarScrubbing = true;
                    android.util.Log.d("VideoPlayerActivity", "Started scrubbing");
                    
                    // IMPORTANT: Don't call showController() here - it causes layout changes
                    // that can interrupt touch tracking and make the slider snap back!
                    // Just prevent auto-hide by setting timeout to 0
                    try {
                        if (playerView != null) {
                            playerView.setControllerShowTimeoutMs(0);
                        }
                        if (controlsHideRunnable != null) {
                            controlsHideHandler.removeCallbacks(controlsHideRunnable);
                        }
                    } catch (Exception ignored) {}
                }

                @Override
                public void onStopTrackingTouch(com.google.android.material.slider.Slider slider) {
                    // Debounce protection - ignore if not scrubbing
                    if (!isTimebarScrubbing) {
                        return;
                    }
                    
                    isTimebarScrubbing = false;
                    scrubEndTimeMs = System.currentTimeMillis(); // Start grace period
                    android.util.Log.d("VideoPlayerActivity", "Stopped scrubbing");
                    
                    try {
                        if (playerView != null) {
                            // Restore auto-hide timeout
                            if (controllerAutoHideMs >= 0) {
                                playerView.setControllerShowTimeoutMs(controllerAutoHideMs);
                            }
                            // Schedule hide with delay - don't call showController immediately
                            int t = SharedPreferencesManager.getInstance(VideoPlayerActivity.this).getPlayerControlsTimeoutSeconds();
                            if (t > 0) {
                                if (controlsHideRunnable != null) {
                                    controlsHideHandler.removeCallbacks(controlsHideRunnable);
                                }
                                controlsHideRunnable = () -> {
                                    if (isTimebarScrubbing) return;
                                    try {
                                        playerView.hideController();
                                        backButton.setVisibility(View.GONE);
                                        infoButton.setVisibility(View.GONE);
                                    } catch (Exception ignored) {}
                                };
                                controlsHideHandler.postDelayed(controlsHideRunnable, t * 1000L);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            });

            // Real-time scrubbing: throttle seek calls while dragging
            timeSlider.addOnChangeListener(new com.google.android.material.slider.Slider.OnChangeListener() {
                private long lastSeekUptime = 0L;
                @Override
                public void onValueChange(com.google.android.material.slider.Slider slider, float value, boolean fromUser) {
                    if (!fromUser || player == null) return;
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastSeekUptime < 50) return; // throttle ~20fps
                    lastSeekUptime = now;
                    try {
                        long dur = player.getDuration();
                        // Use cached duration as fallback for fragmented MP4s
                        if ((dur <= 0 || dur == C.TIME_UNSET) && cachedDurationMs > 0) {
                            dur = cachedDurationMs;
                        }
                        if (dur > 0 && dur != C.TIME_UNSET) {
                            // Slider value is in milliseconds (0..duration). Seek directly using value.
                            long clamped = Math.max(0L, Math.min(dur, (long) value));
                            lastSeekPositionMs = clamped; // Remember seek position to prevent reset
                            // Use PlayerHolder's seekToPosition for fragmented MP4 compatibility
                            com.fadcam.playback.PlayerHolder holder = com.fadcam.playback.PlayerHolder.getInstance();
                            holder.seekToPosition(clamped);
                            Log.d(TAG, "Seeking to: " + clamped + "ms (slider value: " + value + ")");
                        } else {
                            Log.w(TAG, "Cannot seek - no valid duration available");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error during seek", e);
                    }
                }
            });
        }

        // Setup animated play/pause button and audio waveform
        setupAnimatedControls();

        // Setup gesture detector for double-tap seek
        gestureDetector = new android.view.GestureDetector(
            this,
            new android.view.GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onSingleTapConfirmed(
                    android.view.MotionEvent e
                ) {
                    try {
                        // Toggle controller & back-button visibility on single tap
                        toggleControllerAndBackButton();
                        // record time so ACTION_UP handling can avoid double-processing
                        lastSingleTapTime = System.currentTimeMillis();
                        return true;
                    } catch (Exception ex) {
                        Log.w(TAG, "singleTap error", ex);
                        return true;
                    }
                }

                @Override
                public boolean onDoubleTap(android.view.MotionEvent e) {
                    try {
                        float x = e.getX();
                        int w = playerView.getWidth();
                        if (w <= 0) return true;
                        boolean forward = x > (w / 2f);
                        int seekSec = spm != null
                            ? spm.getPlayerSeekSeconds()
                            : Constants.DEFAULT_PLAYER_SEEK_SECONDS;
                        int seekMs = seekSec * 1000;
                        long current = player != null
                            ? player.getCurrentPosition()
                            : 0L;
                        long target = forward
                            ? current + seekMs
                            : current - seekMs;
                        if (target < 0) target = 0;
                        if (player != null) player.seekTo(target);
                        // ensure controller is hidden for double-tap seek UX (no toggling)
                        try { if (!isTimebarScrubbing) playerView.hideController(); } catch (Exception ignored) {}
                        // show overlay and save position immediately
                        showDoubleTapOverlay(forward);
                        // Save & broadcast immediately
                        saveCurrentPlaybackPosition();
                        // avoid single-tap handling for a short grace period
                        lastSingleTapTime = System.currentTimeMillis();
                        return true;
                    } catch (Exception ex) {
                        Log.w(TAG, "doubleTap error", ex);
                        return true;
                    }
                }
            }
        );

        // Setup scale gesture detector for pinch-to-zoom
        scaleGestureDetector = new android.view.ScaleGestureDetector(
            this,
            new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScaleBegin(
                    android.view.ScaleGestureDetector detector
                ) {
                    isPinching = true; // Set pinching state to disable press-and-hold
                    return true;
                }

                @Override
                public boolean onScale(
                    android.view.ScaleGestureDetector detector
                ) {
                    currentScale *= detector.getScaleFactor();
                    // Constrain scale between 0.5x and 3.0x
                    currentScale = Math.max(0.5f, Math.min(currentScale, 3.0f));

                    // Apply scale only to video surface, not the entire player view with controls
                    applyVideoTransform();

                    // Show reset button if zoomed
                    updateResetZoomButtonVisibility();

                    // Add haptic feedback for smooth zooming
                    try {
                        if (
                            android.os.Build.VERSION.SDK_INT >=
                            android.os.Build.VERSION_CODES.R
                        ) {
                            playerView.performHapticFeedback(
                                android.view.HapticFeedbackConstants.GESTURE_START
                            );
                        }
                    } catch (Exception ignored) {}

                    return true;
                }

                @Override
                public void onScaleEnd(
                    android.view.ScaleGestureDetector detector
                ) {
                    isPinching = false; // Reset pinching state

                    // Snap to 1.0x if close to normal scale
                    if (Math.abs(currentScale - 1.0f) < 0.1f) {
                        resetVideoTransform();
                    }
                }
            }
        );

        // Hide old ExoPlayer time bar if present (we now use Material Slider)
        try {
            View legacyTimeBar = playerView.findViewById(androidx.media3.ui.R.id.exo_progress);
            if (legacyTimeBar != null) legacyTimeBar.setVisibility(View.GONE);
        } catch (Exception ignored) {}

        // Do not set a simple touch listener here; setupPressAndHoldFor2x() will
        // install a combined listener
        // that handles single-tap, double-tap and press-and-hold behaviors together.
    }

    // Press-and-hold behavior: while pressed, ramp to quick speed; on release,
    // revert to previous speed
    private void setupPressAndHoldFor2x() {
        if (playerView == null) return;
        quickSpeedOverlay = findViewById(R.id.quick_speed_overlay);
        final int LONG_PRESS_MS = 220; // threshold for long-press
        final android.os.Handler handler = new android.os.Handler(
            android.os.Looper.getMainLooper()
        );
        final boolean[] isLongPress = { false };
        final Runnable[] longPressRunnable = new Runnable[1];

        // Combined touch listener: single tap toggles controller, double-tap seeks (no
        // controller show), long-press quick-speed
        final int touchSlop = android.view.ViewConfiguration.get(
            this
        ).getScaledTouchSlop();
        final float[] downXY = new float[2];
        final float[] lastPanXY = new float[2];
        final boolean[] pendingTap = new boolean[] { false };
        playerView.setOnTouchListener((v, ev) -> {
            // CRITICAL: If user is scrubbing the slider, do NOT process gestures at all!
            // The gesture detector can steal touch events and cause the slider to snap back.
            if (isTimebarScrubbing) {
                return false; // Let the slider handle the touch event
            }
            
            // Also check if the touch is on the slider area - avoid processing gestures there
            // This catches the initial ACTION_DOWN before isTimebarScrubbing is set
            if (timeSlider != null && isTouchOnView(timeSlider, ev)) {
                return false; // Let the slider handle the touch event
            }
            
            boolean gdHandled = false;
            boolean scaleHandled = false;
            boolean gestureConsumed = false;

            try {
                // Handle scale gestures first
                scaleHandled = scaleGestureDetector.onTouchEvent(ev);

                // Only handle other gestures if not currently in a scale gesture
                if (
                    !scaleGestureDetector.isInProgress() &&
                    !isPinching &&
                    ev.getPointerCount() == 1
                ) {
                    // Feed events to gestureDetector for single/double tap detection
                    gdHandled = gestureDetector.onTouchEvent(ev);
                }
            } catch (Exception ignored) {}

            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    {
                        // Cancel scheduled hide to prevent hiding while user starts interaction
                        try { if (controlsHideRunnable != null) controlsHideHandler.removeCallbacks(controlsHideRunnable); } catch (Exception ignored) {}
                        isLongPress[0] = false;
                        pendingTap[0] = true;
                        downXY[0] = ev.getX();
                        downXY[1] = ev.getY();
                        lastPanXY[0] = ev.getX();
                        lastPanXY[1] = ev.getY();

                        // Only start long-press timer for single finger and not zoomed
                        if (
                            ev.getPointerCount() == 1 &&
                            !isPinching &&
                            currentScale <= 1.1f
                        ) {
                            // Capture canonical starting values immediately so swipes use them
                            try {
                                gestureMode = 0;
                                gestureStartX = ev.getX();
                                gestureStartY = ev.getY();
                                if (audioManager != null) initialStreamVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                                try {
                                    int sysBright = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
                                    initialBrightness = Math.max(0f, Math.min(1f, sysBright / 255f));
                                } catch (Exception be) {
                                    try {
                                        float b = getWindow().getAttributes().screenBrightness;
                                        if (b < 0f) b = 0.5f;
                                        initialBrightness = b;
                                    } catch (Exception ignored) { initialBrightness = 0.5f; }
                                }
                                if (player != null) seekStartPosition = player.getCurrentPosition();
                            } catch (Exception ignored) {}

                            // Schedule a cancellable long-press runnable (quick-speed)
                            longPressRunnable[0] = new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        if (!pendingTap[0] || isPinching || currentScale > 1.1f) return;
                                        isLongPress[0] = true;
                                        if (player != null) {
                                            float start = player.getPlaybackParameters().speed;
                                            float target = spm != null ? spm.getQuickSpeed() : Constants.DEFAULT_QUICK_SPEED;
                                            animatePlaybackSpeed(start, target, 200);
                                            showQuickOverlay(true);
                                            try { if (!isTimebarScrubbing) playerView.hideController(); } catch (Exception ignored) {}
                                        }
                                    } catch (Exception ignored) {}
                                }
                            };
                            handler.postDelayed(longPressRunnable[0], LONG_PRESS_MS);
                        }
                    }
                    break;
                case android.view.MotionEvent.ACTION_POINTER_DOWN:
                    {
                        // Multi-touch detected - disable long-press and enable pinch mode
                        isPinching = true;
                        handler.removeCallbacksAndMessages(null);
                        // Update last pan position for multi-touch panning
                        if (ev.getPointerCount() >= 2) {
                            lastPanXY[0] = (ev.getX(0) + ev.getX(1)) / 2f;
                            lastPanXY[1] = (ev.getY(0) + ev.getY(1)) / 2f;
                        }
                    }
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    {
                        // Gesture handling for single-finger swipes (seek horizontal, volume/brightness vertical)
                        try {
                            if (
                                ev.getPointerCount() == 1 &&
                                !scaleGestureDetector.isInProgress() &&
                                currentScale <= 1.1f
                            ) {
                                float dx = ev.getX() - gestureStartX;
                                float dy = ev.getY() - gestureStartY;
                                float adx = Math.abs(dx);
                                float ady = Math.abs(dy);
                                int w = playerView.getWidth();
                                int h = playerView.getHeight();
                                if (gestureMode == 0) {
                                    // Simplify: as soon as we detect movement beyond a small
                                    // threshold, decide direction (horizontal/vertical) and
                                    // use the start X to determine left/right half.
                                    final int MINI_SLOP = Math.max(1, touchSlop / 3);
                                    if (adx > MINI_SLOP || ady > MINI_SLOP) {
                                        if (Math.abs(dy) > Math.abs(dx)) {
                                            // vertical swipe — left half = brightness, right half = volume
                                            if (gestureStartX > (w / 2f)) gestureMode = 2; // volume
                                            else gestureMode = 3; // brightness
                                        } else {
                                            // horizontal swipe — seek
                                            gestureMode = 1;
                                        }
                                        // Movement indicates a deliberate gesture; cancel long-press
                                        try { if (longPressRunnable[0] != null) handler.removeCallbacks(longPressRunnable[0]); } catch (Exception ignored) {}
                                        pendingTap[0] = false;
                                    }
                                }
                                if (gestureMode == 1) {
                                    int pxPerSecond = Math.max(20, Math.round(w / 8f));
                                    long deltaMs = (long) ((dx / pxPerSecond) * 1000L);
                                    // Track seek direction by dx sign so we can show arrow even when
                                    // secondsDelta rounds to 0 initially.
                                    try { lastSeekDirection = dx >= 0f ? 1 : -1; } catch (Exception ignored) {}
                                    long target = seekStartPosition + deltaMs;
                                    long dur = player != null ? player.getDuration() : C.TIME_UNSET;
                                    if (dur != C.TIME_UNSET) target = Math.max(0L, Math.min(dur, target));
                                    else target = Math.max(0L, target);
                                    if (player != null) player.seekTo(target);
                                    showSeekOverlay((int) (deltaMs / 1000L));
                                    pendingTap[0] = false;
                                    // Cancel pending long-press quick-speed
                                    try { if (longPressRunnable[0] != null) handler.removeCallbacks(longPressRunnable[0]); } catch (Exception ignored) {}
                                    return true;
                                } else if (gestureMode == 2) {
                                    if (audioManager != null && maxStreamVolume > 0) {
                                        float pct = -dy / (float) h;
                                        int newVol = Math.max(0, Math.min(maxStreamVolume, initialStreamVolume + Math.round(pct * maxStreamVolume)));
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0);
                                        int percent = Math.round((newVol / (float) maxStreamVolume) * 100f);
                                        showVolumeOverlay(percent);
                                        pendingTap[0] = false;
                                        try { if (longPressRunnable[0] != null) handler.removeCallbacks(longPressRunnable[0]); } catch (Exception ignored) {}
                                        return true;
                                    }
                                } else if (gestureMode == 3) {
                                    try {
                                        float pct = -dy / (float) h;
                                        float base = lastGestureBrightness >= 0f ? lastGestureBrightness : initialBrightness;
                                        float nb = Math.max(0f, Math.min(1f, base + pct));
                                        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
                                        lp.screenBrightness = nb;
                                        getWindow().setAttributes(lp);
                                        int percent = Math.round(nb * 100f);
                                        showBrightnessOverlay(percent);
                                        pendingTap[0] = false;
                                        try { if (longPressRunnable[0] != null) handler.removeCallbacks(longPressRunnable[0]); } catch (Exception ignored) {}
                                        return true;
                                    } catch (Exception ignored) {}
                                }
                            }
                        } catch (Exception ignored) {}
                        // Advanced panning logic for both single and multi-finger
                        if (currentScale > 1.0f) {
                            float currentX = 0f,
                                currentY = 0f;
                            boolean canPan = false;

                            if (
                                ev.getPointerCount() == 1 &&
                                !scaleGestureDetector.isInProgress()
                            ) {
                                // Single finger panning
                                currentX = ev.getX();
                                currentY = ev.getY();
                                canPan = true;
                            } else if (
                                ev.getPointerCount() >= 2 &&
                                !scaleGestureDetector.isInProgress()
                            ) {
                                // Two finger panning (center point)
                                currentX = (ev.getX(0) + ev.getX(1)) / 2f;
                                currentY = (ev.getY(0) + ev.getY(1)) / 2f;
                                canPan = true;
                            }

                            if (canPan) {
                                float deltaX = currentX - lastPanXY[0];
                                float deltaY = currentY - lastPanXY[1];

                                currentTranslationX += deltaX;
                                currentTranslationY += deltaY;

                                // Expanded boundaries - calculate based on screen size and scale
                                android.view.View contentFrame =
                                    playerView.findViewById(
                                        androidx.media3.ui.R.id.exo_content_frame
                                    );
                                if (contentFrame != null) {
                                    float screenWidth = playerView.getWidth();
                                    float screenHeight = playerView.getHeight();

                                    // Allow panning to show corners - much more generous boundaries
                                    float maxTranslationX =
                                        (screenWidth * (currentScale - 1.0f)) /
                                        2f;
                                    float maxTranslationY =
                                        (screenHeight * (currentScale - 1.0f)) /
                                        2f;

                                    currentTranslationX = Math.max(
                                        -maxTranslationX,
                                        Math.min(
                                            currentTranslationX,
                                            maxTranslationX
                                        )
                                    );
                                    currentTranslationY = Math.max(
                                        -maxTranslationY,
                                        Math.min(
                                            currentTranslationY,
                                            maxTranslationY
                                        )
                                    );
                                }

                                applyVideoTransform();
                                lastPanXY[0] = currentX;
                                lastPanXY[1] = currentY;

                                // Disable other gestures when actively panning
                                pendingTap[0] = false;
                                return true;
                            }
                        }

                        // Regular move handling for touch slop detection (only for single touch when
                        // not zoomed)
                        if (
                            pendingTap[0] &&
                            ev.getPointerCount() == 1 &&
                            currentScale <= 1.1f
                        ) {
                            float dx = Math.abs(ev.getX() - downXY[0]);
                            float dy = Math.abs(ev.getY() - downXY[1]);
                            if (dx > touchSlop || dy > touchSlop) {
                                pendingTap[0] = false;
                                // Cancel long press if significant movement detected
                                try { if (longPressRunnable[0] != null) handler.removeCallbacks(longPressRunnable[0]); } catch (Exception ignored) {}
                            }
                        }

                        // Update last pan position for next iteration
                        if (ev.getPointerCount() == 1) {
                            lastPanXY[0] = ev.getX();
                            lastPanXY[1] = ev.getY();
                        } else if (ev.getPointerCount() >= 2) {
                            lastPanXY[0] = (ev.getX(0) + ev.getX(1)) / 2f;
                            lastPanXY[1] = (ev.getY(0) + ev.getY(1)) / 2f;
                        }
                    }
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    {
                        try { if (longPressRunnable[0] != null) handler.removeCallbacks(longPressRunnable[0]); } catch (Exception ignored) {}

                        // Reset pinching state when all fingers are lifted
                        if (ev.getPointerCount() <= 1) {
                            isPinching = false;
                        }

                        if (isLongPress[0] && !isPinching) {
                            if (player != null) {
                                float revertSpeed =
                                    speedValues[currentSpeedIndex];
                                animatePlaybackSpeed(
                                    player.getPlaybackParameters().speed,
                                    revertSpeed,
                                    200
                                );
                            }
                            showQuickOverlay(false);
                            isLongPress[0] = false;
                        }
                        // End any active gesture overlays (hide will animate out)
                        try {
                            if (gestureMode == 1) hideSeekOverlay();
                            else if (gestureMode == 2) hideVolumeOverlay();
                            else if (gestureMode == 3) {
                                hideBrightnessOverlay();
                                // Persist last brightness so next gesture starts from here
                                try {
                                    View root = findViewById(android.R.id.content);
                                    View ov = root != null ? root.findViewWithTag("gesture_brightness_overlay") : null;
                                    if (ov != null) {
                                        TextView tv = ov.findViewById(R.id.overlay_text);
                                        if (tv != null) {
                                            String txt = tv.getText() != null ? tv.getText().toString().replace("%", "") : null;
                                            if (txt != null && !txt.isEmpty()) {
                                                try {
                                                    int p = Integer.parseInt(txt.trim());
                                                    lastGestureBrightness = Math.max(0f, Math.min(1f, p / 100f));
                                                } catch (NumberFormatException ignored) {}
                                            }
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                        } catch (Exception ignored) {}
                        gestureMode = 0;
                    }
                    break;
                case android.view.MotionEvent.ACTION_POINTER_UP:
                    {
                        // Handle pointer up - check remaining pointers
                        int remainingPointers = ev.getPointerCount() - 1;
                        if (remainingPointers <= 1) {
                            // Less than 2 pointers remaining, disable pinch mode
                            isPinching = false;
                        }

                        // Update pan position for remaining pointers
                        if (remainingPointers >= 2) {
                            int index1 = ev.getActionIndex() == 0 ? 1 : 0;
                            int index2 = ev.getActionIndex() <= 1 ? 2 : 1;
                            if (index2 < ev.getPointerCount()) {
                                lastPanXY[0] =
                                    (ev.getX(index1) + ev.getX(index2)) / 2f;
                                lastPanXY[1] =
                                    (ev.getY(index1) + ev.getY(index2)) / 2f;
                            }
                        } else if (remainingPointers == 1) {
                            int remainingIndex = ev.getActionIndex() == 0
                                ? 1
                                : 0;
                            lastPanXY[0] = ev.getX(remainingIndex);
                            lastPanXY[1] = ev.getY(remainingIndex);
                        }
                    }
                    break;
            }

            // If the gesture detector or scale detector handled the event, consume it so
            // PlayerView doesn't also toggle the controller
            if (gdHandled || scaleHandled) return true;
            return false;
        });
    }

    // Recreate the quick speed settings hook: long-pressing the settings button
    // opens quick speed picker
    private void setupQuickSpeedSettings() {
        if (settingsButton != null) {
            settingsButton.setOnLongClickListener(v -> {
                showQuickSpeedPickerSheet();
                return true;
            });
        }
    }

    // method(showQuickSpeedPickerSheet)-----------
    private void showQuickSpeedPickerSheet() {
        ArrayList<OptionItem> items = new ArrayList<>();
        for (int i = 0; i < speedValues.length; i++) {
            float v = speedValues[i];
            String id = "spd_" + v;
            String title;
            if (Math.abs(v - 2.0f) < 0.001f) {
                title = getString(R.string.quick_speed_option_default); // 2x (Default)
            } else {
                title = String.valueOf(speedOptions[i]);
            }
            items.add(
                new OptionItem(
                    id,
                    title,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "bolt",
                    null,
                    null,
                    null
                )
            );
        }
        float current = spm != null
            ? spm.getQuickSpeed()
            : Constants.DEFAULT_QUICK_SPEED;
        String selectedId = "spd_" + current;
        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstance(
            getString(R.string.quick_speed_title),
            items,
            selectedId,
            RK_QUICK_SPEED,
            getString(R.string.quick_speed_helper)
        );
        getSupportFragmentManager().setFragmentResultListener(
                RK_QUICK_SPEED,
                this,
                (key, bundle) -> {
                    String selId = bundle.getString(
                        PickerBottomSheetFragment.BUNDLE_SELECTED_ID
                    );
                    if (selId != null && selId.startsWith("spd_")) {
                        try {
                            float val = Float.parseFloat(selId.substring(4));
                            if (spm != null) spm.setQuickSpeed(val);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            );
        sheet.show(getSupportFragmentManager(), "quick_speed_sheet");
    }

    // method(showQuickSpeedPickerSheet)-----------

    private void showAudioWaveformSwitchSheet() {
        final String RK = "rk_waveform_switch";
        boolean enabled = spm != null ? spm.isAudioWaveformEnabled() : true;

        getSupportFragmentManager().setFragmentResultListener(
                RK,
                this,
                (key, bundle) -> {
                    if (
                        bundle.containsKey(
                            PickerBottomSheetFragment.BUNDLE_SWITCH_STATE
                        )
                    ) {
                        boolean state = bundle.getBoolean(
                            PickerBottomSheetFragment.BUNDLE_SWITCH_STATE
                        );
                        if (spm != null) {
                            spm.setAudioWaveformEnabled(state);

                            // Update waveform visibility immediately
                            com.fadcam.ui.custom.AudioWaveformView waveformView =
                                playerView.findViewById(R.id.audio_waveform);
                            if (waveformView != null) {
                                waveformView.setVisibility(
                                    state ? View.VISIBLE : View.GONE
                                );
                            }

                            Log.d(
                                TAG,
                                "Audio waveform " +
                                (state ? "enabled" : "disabled")
                            );
                        }
                    }
                }
            );

        ArrayList<OptionItem> items = new ArrayList<>(); // No options needed, switch only
        PickerBottomSheetFragment sheet =
            PickerBottomSheetFragment.newInstanceWithSwitch(
                "Audio Waveform",
                items,
                "",
                RK,
                "Controls whether the audio waveform visualization appears on the progress bar during video playback.",
                "Enable Audio Waveform",
                enabled
            );
        sheet.show(getSupportFragmentManager(), "waveform_switch_sheet");
    }


    // Animate playback speed from start to target over duration ms
    private void animatePlaybackSpeed(
        float start,
        float target,
        int durationMs
    ) {
        if (player == null) return;
        try {
            android.animation.ValueAnimator va =
                android.animation.ValueAnimator.ofFloat(start, target);
            va.setDuration(durationMs);
            va.addUpdateListener(animation -> {
                float v = (float) animation.getAnimatedValue();
                try {
                    player.setPlaybackParameters(new PlaybackParameters(v));
                } catch (Exception ignored) {}
            });
            va.start();
        } catch (Exception ignored) {}
    }

    private void showQuickOverlay(boolean show) {
        if (quickSpeedOverlay == null) return;
        if (show) {
            float quick = spm != null
                ? spm.getQuickSpeed()
                : Constants.DEFAULT_QUICK_SPEED;
            // Format like "2x" or "1.5x" without unnecessary decimals
            String formatted;
            try {
                DecimalFormat df = new DecimalFormat("#.##");
                df.setDecimalSeparatorAlwaysShown(false);
                formatted = df.format(quick) + "x";
            } catch (Exception e) {
                formatted = String.format(Locale.US, "%sx", quick);
            }
            quickSpeedOverlay.setText(formatted);
            quickSpeedOverlay.setVisibility(View.VISIBLE);
            quickSpeedOverlay.setAlpha(0f);
            quickSpeedOverlay.animate().alpha(1f).setDuration(120).start();
        } else {
            quickSpeedOverlay
                .animate()
                .alpha(0f)
                .setDuration(120)
                .withEndAction(() -> quickSpeedOverlay.setVisibility(View.GONE))
                .start();
        }
    }

    // Track the actual playback URI (for legacy compatibility)
    private Uri actualPlaybackUri;

    // *** FIX: Modified method signature to accept Uri ***
    private void initializePlayer(Uri videoUri) {
        try {
            // VLC-like fMP4 seeking is now handled automatically by PlayerHolder
            // using SeekableFragmentedMp4MediaSourceFactory (no remuxing needed)
            // The factory pre-scans fMP4 files to build a fragment index for seeking
            actualPlaybackUri = videoUri;

            // Check and log device volume levels
            AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (audioManager != null) {
                int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                boolean isMusicMuted = currentVolume == 0;
                boolean isMusicMutedFlg = audioManager.isStreamMute(AudioManager.STREAM_MUSIC);
                int ringVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING);
                int alarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
                int notifVolume = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION);

                Log.d(
                    TAG,
                    "AudioState: MUSIC=" + currentVolume + "/" + maxVolume +
                        ", musicMutedVolZero=" + isMusicMuted +
                        ", musicMutedFlag=" + isMusicMutedFlg +
                        ", RING=" + ringVolume +
                        ", ALARM=" + alarmVolume +
                        ", NOTIF=" + notifVolume
                );

                if (currentVolume == 0 || isMusicMutedFlg) {
                    Log.w(TAG, "WARNING: Device MUSIC stream is at 0 or muted flag set!");
                    // Don't show toast - could be misleading on some devices
                }
            } else {
                Log.w(TAG, "AudioManager is NULL - cannot inspect device audio state");
            }
            
            // Use shared player so service + activity stay in sync
            com.fadcam.playback.PlayerHolder holder =
                com.fadcam.playback.PlayerHolder.getInstance();
            player = holder.getOrCreate(this);
            playerView.setPlayer(player);
            // Apply controller auto-hide timeout from prefs (0 = never hide) and register runtime listener
            try {
                if (spm == null) spm = SharedPreferencesManager.getInstance(
                    this
                );
                appPrefs = spm.sharedPreferences;
                int timeoutSec = spm.getPlayerControlsTimeoutSeconds();
                if (playerView != null) {
                    if (timeoutSec == 0) {
                        // 0 disables auto-hide (controller stays visible)
                        controllerAutoHideMs = 0;
                        playerView.setControllerShowTimeoutMs(0);
                    } else {
                        controllerAutoHideMs = timeoutSec * 1000;
                        playerView.setControllerShowTimeoutMs(timeoutSec * 1000);
                    }
                }
                // Register a listener to update controller timeout immediately when prefs change
                prefsListener = (sharedPreferences, key) -> {
                    if (
                        Constants.PREF_PLAYER_CONTROLS_TIMEOUT_SECONDS.equals(
                            key
                        )
                    ) {
                        try {
                            int t = spm.getPlayerControlsTimeoutSeconds();
                            if (playerView != null) {
                                if (t == 0) {
                                    controllerAutoHideMs = 0;
                                    playerView.setControllerShowTimeoutMs(0);
                                } else {
                                    controllerAutoHideMs = t * 1000;
                                    playerView.setControllerShowTimeoutMs(controllerAutoHideMs);
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                };
                if (appPrefs != null && prefsListener != null) {
                    appPrefs.registerOnSharedPreferenceChangeListener(
                        prefsListener
                    );
                }
            } catch (Exception ignored) {}
            // Use the actual playback URI (may be remuxed version for seeking support)
            Uri uriForPlayer = actualPlaybackUri != null ? actualPlaybackUri : videoUri;
            Log.i(TAG, "initializePlayer() calling holder.setMediaIfNeeded for URI=" + uriForPlayer);
            holder.setMediaIfNeeded(uriForPlayer);
            // Set initial playback speed
            float initialSpeed = speedValues[currentSpeedIndex];
            Log.d(TAG, "Setting initial playback speed to " + initialSpeed + "x");
            player.setPlaybackParameters(
                new PlaybackParameters(initialSpeed)
            );
            // FORCE volume to 1.0 for debugging - ignore mute preference temporarily
            boolean muted = SharedPreferencesManager.getInstance(
                this
            ).isPlaybackMuted();
            try {
                // Always use volume 1.0 for debugging
                float volume = 1f;
                player.setVolume(volume);
                Log.d(TAG, "Player volume FORCED to 1.0 (mutedPref was=" + muted + ")");
            } catch (Exception e) {
                Log.e(TAG, "Failed to set player volume", e);
            }
            // Autoplay (prepared in holder if needed)
            // Restore saved playback position (resume) if available
            try {
                String uriStr = videoUri.toString();
                String filename = getFileName(videoUri);
                long savedMs = SharedPreferencesManager.getInstance(
                    this
                ).getSavedPlaybackPositionMsWithFilenameFallback(
                    uriStr,
                    filename
                );
                Log.d(
                    TAG,
                    "Resume check: uri=" + uriStr +
                        ", filename=" + filename +
                        ", savedPositionMs=" + savedMs
                );
                if (savedMs > 0) {
                    Log.d(TAG, "Seeking to saved position on init: " + savedMs + "ms");
                    player.seekTo(savedMs);
                }
            } catch (Exception ignored) {}
            Log.d(TAG, "Calling player.play() after initialization");
            player.play();
            // Listen for seeks so we can save immediately and notify adapters
            try {
                player.addListener(
                    new Player.Listener() {
                        public void onSeekProcessed() {
                            // Save and broadcast immediately when user finishes a seek
                            Log.d(TAG, "onSeekProcessed() - saving current playback position");
                            saveCurrentPlaybackPosition();
                        }
                        @Override
                        public void onEvents(Player p, Player.Events events) {
                            // Update slider maximum on timeline changes - BUT NOT WHILE SCRUBBING
                            // Modifying slider during scrubbing causes the thumb to jump/snap back
                            // Also respect grace period after scrubbing ends
                            boolean inGracePeriod = (System.currentTimeMillis() - scrubEndTimeMs) < SCRUB_GRACE_PERIOD_MS;
                            if (timeSlider != null && !isTimebarScrubbing && !inGracePeriod) {
                                try {
                                    long d = p.getDuration();
                                    if (d > 0 && d != C.TIME_UNSET) {
                                        float newMax = (float) d;
                                        // Only update if significantly different (100ms+) to avoid unnecessary changes
                                        if (Math.abs(timeSlider.getValueTo() - newMax) > 100f) {
                                            // First update max, then clamp and update value if needed
                                            timeSlider.setValueTo(newMax);
                                            float curVal = timeSlider.getValue();
                                            if (curVal > newMax) timeSlider.setValue(newMax);
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                        @Override
                        public void onTracksChanged(androidx.media3.common.Tracks tracks) {
                            // Log track information for debugging
                            Log.d(TAG, "onTracksChanged(): groups=" + tracks.getGroups().size());
                            for (int i = 0; i < tracks.getGroups().size(); i++) {
                                androidx.media3.common.Tracks.Group group = tracks.getGroups().get(i);
                                String type = group.getType() == C.TRACK_TYPE_AUDIO ? "AUDIO" :
                                              group.getType() == C.TRACK_TYPE_VIDEO ? "VIDEO" : "OTHER";
                                Log.d(
                                    TAG,
                                    "TrackGroup[" + i + "] type=" + type +
                                        ", selected=" + group.isSelected() +
                                        ", length=" + group.length
                                );
                                for (int t = 0; t < group.length; t++) {
                                    androidx.media3.common.Format fmt = group.getTrackFormat(t);
                                    boolean selected = group.isTrackSelected(t);
                                    Log.d(
                                        TAG,
                                        "  └ track#" + t +
                                            " selected=" + selected +
                                            ", mime=" + fmt.sampleMimeType +
                                            ", id=" + fmt.id +
                                            ", language=" + fmt.language +
                                            ", channelCount=" + fmt.channelCount +
                                            ", sampleRate=" + fmt.sampleRate +
                                            ", bitrate=" + fmt.bitrate
                                    );
                                }
                            }
                        }
                        @Override
                        public void onPlayerError(androidx.media3.common.PlaybackException error) {
                            Log.e(TAG, "Player error: " + error.getMessage(), error);
                        }
                        @Override
                        public void onAudioSessionIdChanged(int audioSessionId) {
                            Log.d(TAG, "onAudioSessionIdChanged(): " + audioSessionId);
                        }
                        @Override
                        public void onVolumeChanged(float volume) {
                            Log.d(TAG, "onVolumeChanged(): " + volume);
                        }
                        @Override
                        public void onPlaybackStateChanged(int state) {
                            String stateName;
                            switch (state) {
                                case Player.STATE_IDLE: stateName = "IDLE"; break;
                                case Player.STATE_BUFFERING: stateName = "BUFFERING"; break;
                                case Player.STATE_READY: stateName = "READY"; break;
                                case Player.STATE_ENDED: stateName = "ENDED"; break;
                                default: stateName = "UNKNOWN(" + state + ")"; break;
                            }
                            Log.i(TAG, "onPlaybackStateChanged(): " + stateName);
                            // When ready after seeking, clear lastSeekPositionMs to sync with actual player position
                            // This handles fragmented MP4s where seek lands on keyframe, not exact position
                            if (state == Player.STATE_READY && lastSeekPositionMs >= 0 && player != null) {
                                long actualPos = player.getCurrentPosition();
                                Log.d(TAG, "  Seek completed: requested=" + lastSeekPositionMs + 
                                           "ms, actual=" + actualPos + "ms (keyframe alignment)");
                                lastSeekPositionMs = -1; // Clear to use actual player position
                            }
                            // When ready, log player audio state
                            if (state == Player.STATE_READY && player != null) {
                                Log.i(TAG, "  Player READY - volume=" + player.getVolume() + 
                                           ", audioSessionId=" + player.getAudioSessionId() +
                                           ", playWhenReady=" + player.getPlayWhenReady());
                            }
                        }
                        @Override
                        public void onIsPlayingChanged(boolean isPlaying) {
                            Log.i(TAG, "onIsPlayingChanged(): " + isPlaying);
                            if (isPlaying && player != null) {
                                Log.i(TAG, "  Playback STARTED - volume=" + player.getVolume() + 
                                           ", audioSessionId=" + player.getAudioSessionId());
                            }
                        }
                        @Override
                        public void onRenderedFirstFrame() {
                            Log.i(TAG, "onRenderedFirstFrame() - first video frame visible");
                        }
                    }
                );
            } catch (Exception ignored) {}
            // Wire center rewind/forward buttons to use same seek logic
            try {
                View controls = playerView.findViewById(
                    androidx.media3.ui.R.id.exo_center_controls
                );
                if (controls != null) {
                    View rew = controls.findViewById(
                        androidx.media3.ui.R.id.exo_rew
                    );
                    View ffwd = controls.findViewById(
                        androidx.media3.ui.R.id.exo_ffwd
                    );
                    if (rew != null) rew.setOnClickListener(v -> {
                        try {
                            int s = spm != null
                                ? spm.getPlayerSeekSeconds()
                                : Constants.DEFAULT_PLAYER_SEEK_SECONDS;
                            long cur = player.getCurrentPosition();
                            long target = Math.max(0, cur - s * 1000L);
                            player.seekTo(target);
                            showDoubleTapOverlay(false);
                            saveCurrentPlaybackPosition();
                        } catch (Exception ignored) {}
                    });
                    if (ffwd != null) ffwd.setOnClickListener(v -> {
                        try {
                            int s = spm != null
                                ? spm.getPlayerSeekSeconds()
                                : Constants.DEFAULT_PLAYER_SEEK_SECONDS;
                            long cur = player.getCurrentPosition();
                            long dur = player.getDuration();
                            long target = Math.min(
                                dur ==
                                    C.TIME_UNSET
                                    ? Long.MAX_VALUE
                                    : dur,
                                cur + s * 1000L
                            );
                            if (
                                dur !=
                                C.TIME_UNSET
                            ) player.seekTo(Math.min(target, dur));
                            else player.seekTo(target);
                            showDoubleTapOverlay(true);
                            saveCurrentPlaybackPosition();
                        } catch (Exception ignored) {}
                    });
                }
            } catch (Exception ignored) {}
            // Start periodic saves
            resumeSaveHandler.postDelayed(resumeSaveRunnable, 5000);
            // Fetch cached duration for fragmented MP4s where player doesn't report duration
            fetchCachedDuration(videoUri);
            
            // Start periodic slider updates when not scrubbing
            try {
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                Runnable r = new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // Check if we're scrubbing OR in grace period after scrubbing ends
                            boolean inGracePeriod = (System.currentTimeMillis() - scrubEndTimeMs) < SCRUB_GRACE_PERIOD_MS;
                            
                            if (isTimebarScrubbing || inGracePeriod) {
                                // Skip update while scrubbing or in grace period
                                h.postDelayed(this, 200);
                                return;
                            }
                            
                            if (player != null && timeSlider != null) {
                                long dur = player.getDuration();
                                // Use cached duration as fallback for fragmented MP4s
                                if ((dur <= 0 || dur == C.TIME_UNSET) && cachedDurationMs > 0) {
                                    dur = cachedDurationMs;
                                }
                                if (dur > 0 && dur != C.TIME_UNSET) {
                                    float newMax = (float) dur;
                                    // Update max first - only if significantly different
                                    if (Math.abs(timeSlider.getValueTo() - newMax) > 100f) {
                                        timeSlider.setValueTo(newMax);
                                    }
                                    long pos = player.getCurrentPosition();
                                    // Use last seek position only during BUFFERING to show user's intended position
                                    // Once READY, onPlaybackStateChanged clears lastSeekPositionMs for accurate sync
                                    if (lastSeekPositionMs >= 0 && player.getPlaybackState() == Player.STATE_BUFFERING) {
                                        pos = lastSeekPositionMs;
                                        Log.d(TAG, "Buffering: showing seek target " + lastSeekPositionMs + "ms");
                                    }
                                    float targetVal = (float) Math.max(0L, Math.min((long) newMax, pos));
                                    // Avoid feedback loop by only setting if significantly changed
                                    float cur = timeSlider.getValue();
                                    if (targetVal > timeSlider.getValueTo()) targetVal = timeSlider.getValueTo();
                                    // Only update if position changed by more than 200ms to reduce jitter
                                    if (Math.abs(cur - targetVal) > 200f) {
                                        timeSlider.setValue(targetVal);
                                    }
                                } else {
                                    Log.w(TAG, "Duration still unknown: player=" + player.getDuration() + ", cached=" + cachedDurationMs);
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error in periodic slider update", e);
                        }
                        h.postDelayed(this, 200);
                    }
                };
                h.post(r);
            } catch (Exception ignored) {}
            Log.i(
                TAG,
                "ExoPlayer initialized and started for URI: " + videoUri
            );
        } catch (Exception e) {
            // Catch potential exceptions during initialization
            Log.e(TAG, "Error initializing player for URI: " + videoUri, e);
            Toast.makeText(
                this,
                "Error playing video",
                Toast.LENGTH_SHORT
            ).show();
            finish(); // Close activity if player fails to initialize
        }
    }

    /**
     * Fetches video duration using MediaMetadataRetriever as fallback for fragmented MP4s
     * where ExoPlayer's getDuration() returns TIME_UNSET.
     * Runs on background thread to avoid blocking UI.
     */
    private void fetchCachedDuration(Uri uri) {
        if (uri == null) return;
        new Thread(() -> {
            MediaMetadataRetriever retriever = null;
            try {
                retriever = new MediaMetadataRetriever();
                retriever.setDataSource(VideoPlayerActivity.this, uri);
                String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (durationStr != null && !durationStr.isEmpty()) {
                    long durationMs = Long.parseLong(durationStr);
                    if (durationMs > 0) {
                        cachedDurationMs = durationMs;
                        Log.d(TAG, "Cached duration fetched via MediaMetadataRetriever: " + durationMs + "ms");
                        // Update slider max on UI thread if needed
                        runOnUiThread(() -> {
                            try {
                                // Don't update slider during scrubbing or grace period
                                boolean inGracePeriod = (System.currentTimeMillis() - scrubEndTimeMs) < SCRUB_GRACE_PERIOD_MS;
                                if (timeSlider != null && !isTimebarScrubbing && !inGracePeriod && timeSlider.getValueTo() < durationMs) {
                                    timeSlider.setValueTo((float) durationMs);
                                    Log.d(TAG, "Slider max updated from cached duration: " + durationMs);
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "Error updating slider max from cached duration", e);
                            }
                        });
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error fetching duration via MediaMetadataRetriever", e);
            } finally {
                if (retriever != null) {
                    try {
                        retriever.release();
                    } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    // Save current position to prefs keyed by current media URI

    // Get file name from URI (Helper) - used for filename fallback when
    // saving/resuming
    @SuppressLint("Range") // Suppress lint check for getColumnIndexOrThrow
    private String getFileName(Uri uri) {
        if (this == null || uri == null) return null;
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (
                android.database.Cursor cursor = getContentResolver().query(
                    uri,
                    null,
                    null,
                    null,
                    null
                )
            ) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(
                        android.provider.OpenableColumns.DISPLAY_NAME
                    );
                    if (nameIndex != -1) {
                        result = cursor.getString(nameIndex);
                    }
                }
            } catch (Exception e) {
                Log.w(
                    TAG,
                    "Could not query display name for content URI: " + uri,
                    e
                );
            }
        }
        if (result == null) {
            String path = uri.getPath();
            if (path != null) {
                int cut = path.lastIndexOf('/');
                if (cut != -1) result = path.substring(cut + 1);
                else result = path;
            }
        }
        return result;
    }

    private void saveCurrentPlaybackPosition() {
        try {
            if (player == null) return;
            long pos = player.getCurrentPosition();
            com.fadcam.playback.PlayerHolder holder =
                com.fadcam.playback.PlayerHolder.getInstance();
            Uri cur = holder.getCurrentUri();
            if (cur != null) {
                // Save both URI-keyed and filename-keyed fallback
                SharedPreferencesManager spmLoc =
                    SharedPreferencesManager.getInstance(this);
                spmLoc.setSavedPlaybackPositionMs(cur.toString(), pos);
                try {
                    String fn = getFileName(cur);
                    if (
                        fn != null && !fn.isEmpty()
                    ) spmLoc.setSavedPlaybackPositionMsByFilename(fn, pos);
                } catch (Exception ignored) {}
                // Broadcast update so UI components can refresh immediately
                try {
                    android.content.Intent i = new android.content.Intent(
                        ACTION_PLAYBACK_POSITION_UPDATED
                    );
                    i.putExtra(EXTRA_URI, cur.toString());
                    i.putExtra(EXTRA_POSITION_MS, pos);
                    androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(
                        this
                    ).sendBroadcast(i);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to save playback position", e);
        }
    }

    // Safe check for controller visibility: query the controller view inside
    // StyledPlayerView
    private boolean isControllerVisibleSafe() {
        try {
            if (playerView == null) return false;
            View controller = playerView.findViewById(
                androidx.media3.ui.R.id.exo_controller
            );
            if (controller != null) return (
                controller.getVisibility() == View.VISIBLE
            );
            // Fallback: try DefaultTimeBar visibility instead
            View timeBar = playerView.findViewById(
                androidx.media3.ui.R.id.exo_progress
            );
            if (timeBar != null) return timeBar.getVisibility() == View.VISIBLE;
        } catch (Exception ignored) {}
        return false;
    }

    private void setupBackButton() {
        backButton.setOnClickListener(v -> finish());
    }

    private void setupInfoButton() {
        if (infoButton != null) {
            infoButton.setOnClickListener(v -> {
                PlayerInfoBottomSheetFragment sheet = PlayerInfoBottomSheetFragment.newInstance();
                sheet.show(getSupportFragmentManager(), "player_info_sheet");
            });
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Enforce controller visibility during scrubbing by listening to visibility changes
        try {
            if (playerView != null) {
                playerView.setControllerVisibilityListener(new PlayerView.ControllerVisibilityListener() {
                    @Override
                    public void onVisibilityChanged(int visibility) {
                        try {
                            if (isTimebarScrubbing) {
                                android.util.Log.w("VideoPlayerActivity", "ControllerVisibilityListener: visibility changed to " + 
                                    (visibility == View.VISIBLE ? "VISIBLE" : "HIDDEN") + " during scrubbing - forcing visible");
                                // Force controller visible during scrubbing regardless of any hide attempts
                                playerView.setControllerShowTimeoutMs(0);
                                playerView.showController();
                                if (backButton != null) backButton.setVisibility(View.VISIBLE);
                                if (infoButton != null) infoButton.setVisibility(View.VISIBLE);
                            } else {
                                if (backButton != null) backButton.setVisibility(visibility == View.VISIBLE ? View.VISIBLE : View.GONE);
                                if (infoButton != null) infoButton.setVisibility(visibility == View.VISIBLE ? View.VISIBLE : View.GONE);
                            }
                        } catch (Exception ignored) {}
                    }
                });
            }
        } catch (Exception ignored) {}
    }

    // --- Playback Speed Controls ---
    private void setupCustomSettingsAction() {
        if (playerView != null) {
            // Find settings button within the PlayerView's layout using Media3's ID
            settingsButton = playerView.findViewById(
                androidx.media3.ui.R.id.exo_settings
            );
            if (settingsButton != null) {
                settingsButton.setOnClickListener(v ->
                    showVideoSettingsSheet()
                );
                Log.d(
                    TAG,
                    "Custom settings button listener attached to @id/exo_settings."
                );
            } else {
                // This can happen if the overridden layout exo_styled_player_control_view.xml
                // doesn't include an ImageButton with the id @id/exo_settings
                Log.w(
                    TAG,
                    "Could not find settings button (@id/exo_settings) within PlayerView. Ensure it exists in your custom controller layout."
                );
            }
        } else {
            Log.w(
                TAG,
                "PlayerView is null, cannot attach settings button listener."
            );
        }
    }

    private void showVideoSettingsSheet() {
        ArrayList<OptionItem> items = new ArrayList<>();
        // Row: Playback Speed (show current runtime speed label)
        String playbackSubtitle = String.valueOf(
            speedOptions[currentSpeedIndex]
        );
        items.add(
            new OptionItem(
                "row_playback_speed",
                getString(R.string.playback_speed_label),
                playbackSubtitle,
                null,
                null,
                null,
                null,
                null,
                "speed",
                null,
                null,
                null
            )
        );
        // Row: Quick Speed
        float quick = spm != null
            ? spm.getQuickSpeed()
            : Constants.DEFAULT_QUICK_SPEED;
        String quickSubtitle;
        try {
            java.text.DecimalFormat df = new java.text.DecimalFormat("#.##");
            quickSubtitle = df.format(quick) + "x";
        } catch (Exception e) {
            quickSubtitle = quick + "x";
        }
        items.add(
            new OptionItem(
                "row_quick_speed",
                getString(R.string.quick_speed_title),
                quickSubtitle,
                null,
                null,
                null,
                null,
                null,
                "bolt",
                null,
                null,
                null
            )
        );
        // Row: Mute playback (handled as a switch via a separate sheet)
        boolean mutedPref = SharedPreferencesManager.getInstance(
            this
        ).isPlaybackMuted();
        String muteSubtitle = mutedPref
            ? getString(R.string.universal_enable)
            : getString(R.string.universal_disable);
        items.add(
            new OptionItem(
                "row_mute_playback",
                getString(R.string.mute_playback_title),
                muteSubtitle,
                null,
                null,
                null,
                null,
                null,
                "volume_off",
                null,
                null,
                null
            )
        );
        // Row: Keep screen awake
        boolean keepOn = SharedPreferencesManager.getInstance(
            this
        ).isPlayerKeepScreenOn();
        String keepOnSubtitle = keepOn
            ? getString(R.string.universal_enable)
            : getString(R.string.universal_disable);
        items.add(
            new OptionItem(
                "row_keep_screen_on",
                getString(R.string.keep_screen_on_title),
                keepOnSubtitle,
                null,
                null,
                null,
                null,
                null,
                "visibility",
                null,
                null,
                null
            )
        );
        // Row: Background playback
        boolean bg = SharedPreferencesManager.getInstance(
            this
        ).isBackgroundPlaybackEnabled();
        String bgSubtitle = bg
            ? getString(R.string.universal_enable)
            : getString(R.string.universal_disable);
        items.add(
            new OptionItem(
                "row_background_playback",
                getString(R.string.background_playback_title),
                bgSubtitle,
                null,
                null,
                null,
                null,
                null,
                "play_circle",
                null,
                null,
                null
            )
        );
        // Row: Background playback timer
        int timerSec = SharedPreferencesManager.getInstance(
            this
        ).getBackgroundPlaybackTimerSeconds();
        String timerSubtitle;
        if (timerSec == 0) timerSubtitle = getString(R.string.timer_off_short);
        else if (timerSec < 60) timerSubtitle = getString(
            R.string.timer_seconds_short,
            timerSec
        );
        else if (timerSec < 3600) timerSubtitle = getString(
            R.string.timer_minutes_short,
            timerSec / 60
        );
        else timerSubtitle = getString(
            R.string.timer_hours_short,
            timerSec / 3600
        );
        boolean bgEnabled = SharedPreferencesManager.getInstance(
            this
        ).isBackgroundPlaybackEnabled();
        items.add(
            new OptionItem(
                "row_background_playback_timer",
                getString(R.string.background_playback_timer_title_short),
                timerSubtitle,
                null,
                null,
                null,
                null,
                null,
                "timer",
                null,
                null,
                !bgEnabled
            )
        );
        // Row: Seek amount (in seconds)
        int seekSecMain = spm != null
            ? spm.getPlayerSeekSeconds()
            : Constants.DEFAULT_PLAYER_SEEK_SECONDS;
        items.add(
            new OptionItem(
                "row_seek_amount",
                getString(R.string.seek_amount_title),
                getString(R.string.seek_amount_subtitle, seekSecMain),
                null,
                null,
                null,
                null,
                null,
                "replay_10",
                null,
                null,
                null
            )
        );

        // Row: Audio waveform visualization
        boolean waveformEnabled = spm != null
            ? spm.isAudioWaveformEnabled()
            : true;
        items.add(
            new OptionItem(
                "row_audio_waveform",
                "Audio Waveform",
                waveformEnabled ? "Enabled" : "Disabled",
                null,
                null,
                null,
                null,
                waveformEnabled,
                "graphic_eq",
                null,
                null,
                null
            )
        );

        // Row: Controls hide delay (in seconds). Mirror the Settings fragment option.
        int ctrlTimeout = SharedPreferencesManager.getInstance(this).getPlayerControlsTimeoutSeconds();
        String ctrlSubtitle;
        if (ctrlTimeout == 0) ctrlSubtitle = getString(R.string.timer_off_short);
        else if (ctrlTimeout < 60) ctrlSubtitle = getString(R.string.timer_seconds_short, ctrlTimeout);
        else if (ctrlTimeout < 3600) ctrlSubtitle = getString(R.string.timer_minutes_short, ctrlTimeout / 60);
        else ctrlSubtitle = getString(R.string.timer_hours_short, ctrlTimeout / 3600);
        items.add(
            new OptionItem(
                "row_controller_timeout",
                getString(R.string.controls_hide_delay_title),
                ctrlSubtitle,
                null,
                null,
                null,
                null,
                null,
                "timer",
                null,
                null,
                null
            )
        );

        String helper = getString(R.string.video_player_settings_helper_player);
        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstance(
            getString(R.string.video_player_settings_title),
            items,
            null,
            RK_VIDEO_SETTINGS,
            helper
        );
        // Clear any previous listener for this request key to avoid
        // duplicate/cross-sheet callbacks
        try {
            getSupportFragmentManager().clearFragmentResultListener(
                RK_VIDEO_SETTINGS
            );
        } catch (Exception ignored) {}
        getSupportFragmentManager().setFragmentResultListener(
                RK_VIDEO_SETTINGS,
                this,
                (key, bundle) -> {
                    String sel = bundle.getString(
                        PickerBottomSheetFragment.BUNDLE_SELECTED_ID
                    );
                    if ("row_playback_speed".equals(sel)) {
                        showPlaybackSpeedPickerSheet();
                    } else if ("row_quick_speed".equals(sel)) {
                        showQuickSpeedPickerSheet();
                    } else if ("row_mute_playback".equals(sel)) {
                        showMuteSwitchSheet();
                    } else if ("row_keep_screen_on".equals(sel)) {
                        showKeepScreenOnSwitchSheet();
                    } else if ("row_background_playback".equals(sel)) {
                        showBackgroundPlaybackSwitchSheet();
                    } else if ("row_background_playback_timer".equals(sel)) {
                        // Show timer picker directly from activity (mirrors VideoPlayerSettingsFragment
                        // implementation)
                        final String RK = "rk_vps_background_playback_timer";
                        java.util.ArrayList<OptionItem> itemsTimer =
                            new java.util.ArrayList<>();
                        itemsTimer.add(
                            new OptionItem(
                                "t_off",
                                getString(R.string.timer_off),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "block",
                                null,
                                null,
                                null
                            )
                        );
                        itemsTimer.add(
                            new OptionItem(
                                "t_30s",
                                getString(R.string.timer_30_seconds),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "timer",
                                null,
                                null,
                                null
                            )
                        );
                        itemsTimer.add(
                            new OptionItem(
                                "t_1m",
                                getString(R.string.timer_1_minute),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "timer",
                                null,
                                null,
                                null
                            )
                        );
                        itemsTimer.add(
                            new OptionItem(
                                "t_5m",
                                getString(R.string.timer_5_minutes),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "timer",
                                null,
                                null,
                                null
                            )
                        );
                        itemsTimer.add(
                            new OptionItem(
                                "t_15m",
                                getString(R.string.timer_15_minutes),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "timer",
                                null,
                                null,
                                null
                            )
                        );
                        itemsTimer.add(
                            new OptionItem(
                                "t_30m",
                                getString(R.string.timer_30_minutes),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "timer",
                                null,
                                null,
                                null
                            )
                        );
                        itemsTimer.add(
                            new OptionItem(
                                "t_1h",
                                getString(R.string.timer_1_hour),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "timer",
                                null,
                                null,
                                null
                            )
                        );
                        itemsTimer.add(
                            new OptionItem(
                                "t_custom",
                                getString(R.string.timer_custom_label),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "edit",
                                null,
                                null,
                                null
                            )
                        );
                        int cur = SharedPreferencesManager.getInstance(
                            this
                        ).getBackgroundPlaybackTimerSeconds();
                        String selId = "t_off";
                        if (cur == 30) selId = "t_30s";
                        else if (cur == 60) selId = "t_1m";
                        else if (cur == 300) selId = "t_5m";
                        else if (cur == 900) selId = "t_15m";
                        else if (cur == 1800) selId = "t_30m";
                        else if (cur == 3600) selId = "t_1h";
                        else if (cur > 0) selId = "t_custom"; // show Custom checked for non-preset values
                        PickerBottomSheetFragment sheetTimer =
                            PickerBottomSheetFragment.newInstance(
                                getString(
                                    R.string.background_playback_timer_title
                                ),
                                itemsTimer,
                                selId,
                                RK,
                                getString(
                                    R.string.background_playback_timer_helper
                                )
                            );
                        getSupportFragmentManager().setFragmentResultListener(
                                RK,
                                this,
                                (rkRes, resBundle) -> {
                                    String sel2 = resBundle.getString(
                                        PickerBottomSheetFragment.BUNDLE_SELECTED_ID
                                    );
                                    if (sel2 == null) return;
                                    if ("t_custom".equals(sel2)) {
                                        final String RK_NUM =
                                            "rk_vps_background_playback_timer_custom";
                                        com.fadcam.ui.picker.NumberInputBottomSheetFragment num =
                                            com.fadcam.ui.picker.NumberInputBottomSheetFragment.newInstance(
                                                getString(
                                                    R.string.timer_custom_title
                                                ),
                                                1,
                                                86400,
                                                60,
                                                getString(
                                                    R.string.timer_custom_placeholder
                                                ),
                                                5,
                                                3600,
                                                getString(
                                                    R.string.timer_custom_low_hint
                                                ),
                                                getString(
                                                    R.string.timer_custom_high_hint
                                                ),
                                                RK_NUM
                                            );
                                        android.os.Bundle _b =
                                            num.getArguments() != null
                                                ? num.getArguments()
                                                : new android.os.Bundle();
                                        _b.putString(
                                            com.fadcam.ui.picker.NumberInputBottomSheetFragment.ARG_DESCRIPTION,
                                            getString(
                                                R.string.timer_custom_description
                                            )
                                        );
                                        _b.putBoolean(
                                            com.fadcam.ui.picker.NumberInputBottomSheetFragment.ARG_ENABLE_TIMER_CALC,
                                            true
                                        );
                                        num.setArguments(_b);
                                        // Add seek-specific description
                                        android.os.Bundle _nb =
                                            num.getArguments() != null
                                                ? num.getArguments()
                                                : new android.os.Bundle();
                                        _nb.putString(
                                            com.fadcam.ui.picker.NumberInputBottomSheetFragment.ARG_DESCRIPTION,
                                            getString(
                                                R.string.seek_amount_helper
                                            )
                                        );
                                        num.setArguments(_nb);
                                        getSupportFragmentManager().setFragmentResultListener(
                                                RK_NUM,
                                                this,
                                                (rkN, nb) -> {
                                                    int minutes = nb.getInt(
                                                        com.fadcam.ui.picker.NumberInputBottomSheetFragment.RESULT_NUMBER,
                                                        0
                                                    );
                                                    if (minutes > 0) {
                                                        int seconds =
                                                            minutes * 60;
                                                        SharedPreferencesManager.getInstance(
                                                            this
                                                        ).setBackgroundPlaybackTimerSeconds(
                                                            seconds
                                                        );
                                                        // Update inline subtitle if settings sheet is still visible
                                                        View root =
                                                            findViewById(
                                                                android.R.id.content
                                                            );
                                                        if (root != null) {
                                                            TextView sub =
                                                                root.findViewById(
                                                                    R.id.sub_background_playback_timer
                                                                );
                                                            if (sub != null) {
                                                                sub.setText(
                                                                    minutes < 60
                                                                        ? getString(
                                                                            R.string.timer_minutes_short,
                                                                            minutes
                                                                        )
                                                                        : getString(
                                                                            R.string.timer_hours_short,
                                                                            minutes /
                                                                            60
                                                                        )
                                                                );
                                                            }
                                                        }
                                                    }
                                                }
                                            );
                                        // Ensure the parent picker is dismissed before showing the numeric sheet to
                                        // avoid view overlap
                                        try {
                                            if (
                                                sheetTimer != null &&
                                                sheetTimer.isAdded()
                                            ) sheetTimer.dismissAllowingStateLoss();
                                        } catch (Exception ignored) {}
                                        new android.os.Handler(
                                            android.os.Looper.getMainLooper()
                                        ).postDelayed(
                                                () -> {
                                                    try {
                                                        num.show(
                                                            getSupportFragmentManager(),
                                                            "video_background_playback_timer_custom_sheet"
                                                        );
                                                    } catch (
                                                        Exception ignored
                                                    ) {}
                                                },
                                                200
                                            );
                                        return;
                                    }
                                    int seconds = 0;
                                    switch (sel2) {
                                        case "t_30s":
                                            seconds = 30;
                                            break;
                                        case "t_1m":
                                            seconds = 60;
                                            break;
                                        case "t_5m":
                                            seconds = 300;
                                            break;
                                        case "t_15m":
                                            seconds = 900;
                                            break;
                                        case "t_30m":
                                            seconds = 1800;
                                            break;
                                        case "t_1h":
                                            seconds = 3600;
                                            break;
                                        default:
                                            seconds = 0;
                                            break;
                                    }
                                    SharedPreferencesManager.getInstance(
                                        this
                                    ).setBackgroundPlaybackTimerSeconds(
                                        seconds
                                    );
                                    View root = findViewById(
                                        android.R.id.content
                                    );
                                    if (root != null) {
                                        TextView sub = root.findViewById(
                                            R.id.sub_background_playback_timer
                                        );
                                        if (sub != null) {
                                            if (seconds == 0) sub.setText(
                                                getString(
                                                    R.string.timer_off_short
                                                )
                                            );
                                            else if (seconds < 60) sub.setText(
                                                getString(
                                                    R.string.timer_seconds_short,
                                                    seconds
                                                )
                                            );
                                            else if (
                                                seconds < 3600
                                            ) sub.setText(
                                                getString(
                                                    R.string.timer_minutes_short,
                                                    seconds / 60
                                                )
                                            );
                                            else sub.setText(
                                                getString(
                                                    R.string.timer_hours_short,
                                                    seconds / 3600
                                                )
                                            );
                                        }
                                    }
                                }
                            );
                        sheetTimer.show(
                            getSupportFragmentManager(),
                            "video_background_playback_timer_sheet"
                        );
                    } else if ("row_controller_timeout".equals(sel)) {
                        // Mirror the controller timeout picker from VideoPlayerSettingsFragment
                        final String RK = "rk_vps_controller_timeout";
                        java.util.ArrayList<OptionItem> itemsCtrl = new java.util.ArrayList<>();
                        itemsCtrl.add(new OptionItem("c_off", getString(R.string.timer_off), null, null, null, null, null, null, "block", null, null, null));
                        itemsCtrl.add(new OptionItem("c_3s", "3s", null, null, null, null, null, null, "timer", null, null, null));
                        itemsCtrl.add(new OptionItem("c_5s", "5s", null, null, null, null, null, null, "timer", null, null, null));
                        itemsCtrl.add(new OptionItem("c_10s", "10s", null, null, null, null, null, null, "timer", null, null, null));
                        itemsCtrl.add(new OptionItem("c_15s", "15s", null, null, null, null, null, null, "timer", null, null, null));
                        itemsCtrl.add(new OptionItem("c_30s", "30s", null, null, null, null, null, null, "timer", null, null, null));
                        itemsCtrl.add(new OptionItem("c_custom", getString(R.string.timer_custom_label), null, null, null, null, null, null, "edit", null, null, null));
                        int cur = SharedPreferencesManager.getInstance(this).getPlayerControlsTimeoutSeconds();
                        String selId = "c_off";
                        if (cur == 3) selId = "c_3s";
                        else if (cur == 5) selId = "c_5s";
                        else if (cur == 10) selId = "c_10s";
                        else if (cur == 15) selId = "c_15s";
                        else if (cur == 30) selId = "c_30s";
                        else if (cur > 0) selId = "c_custom";
                        PickerBottomSheetFragment sheetCtrl = PickerBottomSheetFragment.newInstance(getString(R.string.controls_hide_delay_title), itemsCtrl, selId, RK, getString(R.string.controls_hide_delay_helper));
                        if (sheetCtrl.getArguments() == null) sheetCtrl.setArguments(new android.os.Bundle());
                        sheetCtrl.getArguments().putBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.ARG_STRICT_ITEMS_ONLY, true);
                        try { getSupportFragmentManager().clearFragmentResultListener(RK); } catch (Exception ignored) {}
                        getSupportFragmentManager().setFragmentResultListener(RK, this, (rkRes, b) -> {
                            String s = b.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                            if (s == null) return;
                            if ("c_custom".equals(s)) {
                                final String RK_NUM = "rk_vps_controller_timeout_custom";
                                com.fadcam.ui.picker.NumberInputBottomSheetFragment num = com.fadcam.ui.picker.NumberInputBottomSheetFragment.newInstance(getString(R.string.timer_custom_title), 1, 86400, 1, getString(R.string.universal_enter_number), 5, 60, getString(R.string.timer_custom_low_hint), getString(R.string.timer_custom_high_hint), RK_NUM);
                                android.os.Bundle _b = num.getArguments() != null ? num.getArguments() : new android.os.Bundle();
                                _b.putString(com.fadcam.ui.picker.NumberInputBottomSheetFragment.ARG_DESCRIPTION, getString(R.string.controls_hide_delay_helper));
                                num.setArguments(_b);
                                try { getSupportFragmentManager().clearFragmentResultListener(RK_NUM); } catch (Exception ignored) {}
                                getSupportFragmentManager().setFragmentResultListener(RK_NUM, this, (rkn, nb) -> {
                                    int val = nb.getInt(com.fadcam.ui.picker.NumberInputBottomSheetFragment.RESULT_NUMBER, 0);
                                    if (val > 0) {
                                        SharedPreferencesManager.getInstance(this).setPlayerControlsTimeoutSeconds(val);
                                        View root = findViewById(android.R.id.content);
                                        if (root != null) {
                                            TextView sub = root.findViewById(R.id.sub_controller_timeout);
                                            if (sub != null) sub.setText(val < 60 ? getString(R.string.timer_seconds_short, val) : (val < 3600 ? getString(R.string.timer_minutes_short, val / 60) : getString(R.string.timer_hours_short, val / 3600)));
                                        }
                                    }
                                });
                                try { sheetCtrl.dismissAllowingStateLoss(); } catch (Exception ignored) {}
                                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                    try { num.show(getSupportFragmentManager(), "controller_timeout_custom_sheet"); } catch (Exception ignored) {}
                                }, 180);
                                return;
                            }
                            if (s.startsWith("c_")) {
                                int seconds = 0;
                                switch (s) {
                                    case "c_off": seconds = 0; break;
                                    case "c_3s": seconds = 3; break;
                                    case "c_5s": seconds = 5; break;
                                    case "c_10s": seconds = 10; break;
                                    case "c_15s": seconds = 15; break;
                                    case "c_30s": seconds = 30; break;
                                    default: seconds = 0; break;
                                }
                                SharedPreferencesManager.getInstance(this).setPlayerControlsTimeoutSeconds(seconds);
                                View root = findViewById(android.R.id.content);
                                if (root != null) {
                                    TextView sub = root.findViewById(R.id.sub_controller_timeout);
                                    if (sub != null) {
                                        if (seconds == 0) sub.setText(getString(R.string.timer_off_short));
                                        else if (seconds < 60) sub.setText(getString(R.string.timer_seconds_short, seconds));
                                        else if (seconds < 3600) sub.setText(getString(R.string.timer_minutes_short, seconds / 60));
                                        else sub.setText(getString(R.string.timer_hours_short, seconds / 3600));
                                    }
                                }
                            }
                        });
                        sheetCtrl.show(getSupportFragmentManager(), "vps_controller_timeout_sheet");
                    } else if ("row_seek_amount".equals(sel)) {
                        // Show seek amount picker directly in player settings
                        final String RK = "rk_vps_seek_amount_activity";
                        java.util.ArrayList<OptionItem> itemsSeek =
                            new java.util.ArrayList<>();
                        itemsSeek.add(
                            new OptionItem(
                                "s_5",
                                "5s",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "replay_10",
                                null,
                                null,
                                null
                            )
                        );
                        itemsSeek.add(
                            new OptionItem(
                                "s_10",
                                "10s",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "replay_10",
                                null,
                                null,
                                null
                            )
                        );
                        itemsSeek.add(
                            new OptionItem(
                                "s_15",
                                "15s",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "replay_10",
                                null,
                                null,
                                null
                            )
                        );
                        itemsSeek.add(
                            new OptionItem(
                                "s_30",
                                "30s",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "replay_10",
                                null,
                                null,
                                null
                            )
                        );
                        itemsSeek.add(
                            new OptionItem(
                                "s_custom",
                                getString(R.string.seek_amount_custom),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "edit",
                                null,
                                null,
                                null
                            )
                        );
                        int curS = SharedPreferencesManager.getInstance(
                            this
                        ).getPlayerSeekSeconds();
                        String selId = "s_" + curS;
                        if (
                            curS != 5 && curS != 10 && curS != 15 && curS != 30
                        ) selId = "s_custom";
                        PickerBottomSheetFragment sheetSeek =
                            PickerBottomSheetFragment.newInstance(
                                getString(R.string.seek_amount_title),
                                itemsSeek,
                                selId,
                                RK,
                                getString(R.string.seek_amount_helper)
                            );
                        try {
                            getSupportFragmentManager().clearFragmentResultListener(
                                RK
                            );
                        } catch (Exception ignored) {}
                        getSupportFragmentManager().setFragmentResultListener(
                                RK,
                                this,
                                (rkRes, resBundle) -> {
                                    String s = resBundle.getString(
                                        PickerBottomSheetFragment.BUNDLE_SELECTED_ID
                                    );
                                    if (s == null) return;
                                    if ("s_custom".equals(s)) {
                                        final String RK_NUM =
                                            "rk_vps_seek_amount_custom_activity";
                                        com.fadcam.ui.picker.NumberInputBottomSheetFragment num =
                                            com.fadcam.ui.picker.NumberInputBottomSheetFragment.newInstance(
                                                getString(
                                                    R.string.seek_amount_custom_title
                                                ),
                                                1,
                                                300,
                                                10,
                                                getString(
                                                    R.string.universal_enter_number
                                                ),
                                                5,
                                                60,
                                                getString(
                                                    R.string.seek_amount_custom_low_hint
                                                ),
                                                getString(
                                                    R.string.seek_amount_custom_high_hint
                                                ),
                                                RK_NUM
                                            );
                                        try {
                                            getSupportFragmentManager().clearFragmentResultListener(
                                                RK_NUM
                                            );
                                        } catch (Exception ignored) {}
                                        getSupportFragmentManager().setFragmentResultListener(
                                                RK_NUM,
                                                this,
                                                (rkN, nb) -> {
                                                    int val = nb.getInt(
                                                        com.fadcam.ui.picker.NumberInputBottomSheetFragment.RESULT_NUMBER,
                                                        0
                                                    );
                                                    if (val > 0) {
                                                        SharedPreferencesManager.getInstance(
                                                            this
                                                        ).setPlayerSeekSeconds(
                                                            val
                                                        );
                                                        View root =
                                                            findViewById(
                                                                android.R.id.content
                                                            );
                                                        if (root != null) {
                                                            TextView sub =
                                                                root.findViewById(
                                                                    R.id.sub_seek_amount
                                                                );
                                                            if (
                                                                sub != null
                                                            ) sub.setText(
                                                                getString(
                                                                    R.string.seek_amount_subtitle,
                                                                    val
                                                                )
                                                            );
                                                        }
                                                    }
                                                }
                                            );
                                        // Dismiss parent picker first to avoid UI overlap
                                        try {
                                            sheetSeek.dismissAllowingStateLoss();
                                        } catch (Exception ignored) {}
                                        new android.os.Handler(
                                            android.os.Looper.getMainLooper()
                                        ).postDelayed(
                                                () -> {
                                                    try {
                                                        num.show(
                                                            getSupportFragmentManager(),
                                                            "video_seek_amount_custom_sheet"
                                                        );
                                                    } catch (
                                                        Exception ignored
                                                    ) {}
                                                },
                                                180
                                            );
                                        return;
                                    }
                                    if (s.startsWith("s_")) {
                                        try {
                                            int v = Integer.parseInt(
                                                s.substring(2)
                                            );
                                            SharedPreferencesManager.getInstance(
                                                this
                                            ).setPlayerSeekSeconds(v);
                                            View root = findViewById(
                                                android.R.id.content
                                            );
                                            if (root != null) {
                                                TextView sub =
                                                    root.findViewById(
                                                        R.id.sub_seek_amount
                                                    );
                                                if (sub != null) sub.setText(
                                                    getString(
                                                        R.string.seek_amount_subtitle,
                                                        v
                                                    )
                                                );
                                            }
                                        } catch (
                                            NumberFormatException ignored
                                        ) {}
                                    }
                                }
                            );
                        sheetSeek.show(
                            getSupportFragmentManager(),
                            "video_seek_amount_sheet"
                        );
                    } else if ("row_audio_waveform".equals(sel)) {
                        // Toggle audio waveform visibility
                        showAudioWaveformSwitchSheet();
                    }
                }
            );
        sheet.show(getSupportFragmentManager(), "video_settings_sheet");
    }


    // method(showPlaybackSpeedPickerSheet)-----------
    private void showPlaybackSpeedPickerSheet() {
        if (player == null) return;
        ArrayList<OptionItem> items = new ArrayList<>();
        for (int i = 0; i < speedValues.length; i++) {
            String id = "spd_" + speedValues[i];
            String title = String.valueOf(speedOptions[i]);
            items.add(
                new OptionItem(
                    id,
                    title,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "speed",
                    null,
                    null,
                    null
                )
            );
        }
        String selectedId = "spd_" + speedValues[currentSpeedIndex];
        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstance(
            getString(R.string.playback_speed_title),
            items,
            selectedId,
            RK_PLAYBACK_SPEED,
            getString(R.string.playback_speed_helper_player)
        );
        getSupportFragmentManager().setFragmentResultListener(
                RK_PLAYBACK_SPEED,
                this,
                (key, bundle) -> {
                    String selId = bundle.getString(
                        PickerBottomSheetFragment.BUNDLE_SELECTED_ID
                    );
                    if (selId != null && selId.startsWith("spd_")) {
                        try {
                            float val = Float.parseFloat(selId.substring(4));
                            int newIndex = currentSpeedIndex;
                            for (int i = 0; i < speedValues.length; i++) {
                                if (Math.abs(speedValues[i] - val) < 0.001f) {
                                    newIndex = i;
                                    break;
                                }
                            }
                            currentSpeedIndex = newIndex;
                            player.setPlaybackParameters(
                                new PlaybackParameters(val)
                            );
                            Log.i(TAG, "Playback speed set to: " + val + "x");
                        } catch (NumberFormatException ignored) {}
                    }
                }
            );
        sheet.show(getSupportFragmentManager(), "playback_speed_sheet");
    }

    private void showMuteSwitchSheet() {
        final String RK = "rk_video_mute_switch";
        boolean enabled = SharedPreferencesManager.getInstance(
            this
        ).isPlaybackMuted();
        getSupportFragmentManager().setFragmentResultListener(
                RK,
                this,
                (k, b) -> {
                    if (
                        b.containsKey(
                            PickerBottomSheetFragment.BUNDLE_SWITCH_STATE
                        )
                    ) {
                        boolean state = b.getBoolean(
                            PickerBottomSheetFragment.BUNDLE_SWITCH_STATE
                        );
                        SharedPreferencesManager.getInstance(
                            this
                        ).setPlaybackMuted(state);
                        applyMutedStateToPlayer(state);
                        // Update subtitle in the main settings sheet if it's still visible
                        // (the row subtitle in this sheet is static; we refresh when reopening)
                    }
                }
            );
        String helper = getString(R.string.mute_playback_helper_picker);
        PickerBottomSheetFragment sheet =
            PickerBottomSheetFragment.newInstanceWithSwitch(
                getString(R.string.mute_playback_title),
                new ArrayList<>(),
                null,
                RK,
                helper,
                getString(R.string.mute_playback_title),
                enabled
            );
        sheet.show(getSupportFragmentManager(), "video_mute_switch_sheet");
    }

    private void showKeepScreenOnSwitchSheet() {
        boolean enabled = SharedPreferencesManager.getInstance(
            this
        ).isPlayerKeepScreenOn();
        getSupportFragmentManager().setFragmentResultListener(
                RK_KEEP_SCREEN_ON,
                this,
                (k, b) -> {
                    if (
                        b.containsKey(
                            PickerBottomSheetFragment.BUNDLE_SWITCH_STATE
                        )
                    ) {
                        boolean state = b.getBoolean(
                            PickerBottomSheetFragment.BUNDLE_SWITCH_STATE
                        );
                        SharedPreferencesManager.getInstance(
                            this
                        ).setPlayerKeepScreenOn(state);
                        // Apply immediately
                        if (state) {
                            getWindow().addFlags(
                                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            );
                        } else {
                            getWindow().clearFlags(
                                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            );
                        }
                    }
                }
            );
        String helper = getString(R.string.keep_screen_on_helper_picker);
        PickerBottomSheetFragment sheet =
            PickerBottomSheetFragment.newInstanceWithSwitch(
                getString(R.string.keep_screen_on_title),
                new ArrayList<>(),
                null,
                RK_KEEP_SCREEN_ON,
                helper,
                getString(R.string.keep_screen_on_title),
                enabled
            );
        sheet.show(
            getSupportFragmentManager(),
            "video_keep_screen_on_switch_sheet"
        );
    }

    private void showBackgroundPlaybackSwitchSheet() {
        boolean enabled = SharedPreferencesManager.getInstance(
            this
        ).isBackgroundPlaybackEnabled();
        getSupportFragmentManager().setFragmentResultListener(
                RK_BACKGROUND_PLAYBACK,
                this,
                (k, b) -> {
                    if (
                        b.containsKey(
                            PickerBottomSheetFragment.BUNDLE_SWITCH_STATE
                        )
                    ) {
                        boolean state = b.getBoolean(
                            PickerBottomSheetFragment.BUNDLE_SWITCH_STATE
                        );
                        SharedPreferencesManager.getInstance(
                            this
                        ).setBackgroundPlaybackEnabled(state);
                        // If turned on and we're already playing, keep behavior is applied onPause via
                        // service handoff.
                        // If turned off, ensure we stop background service when leaving.
                    }
                }
            );
        String helper = getString(R.string.background_playback_helper_picker);
        java.util.ArrayList<String> deps = new java.util.ArrayList<>();
        deps.add("row_background_playback_timer");
        PickerBottomSheetFragment sheet =
            PickerBottomSheetFragment.newInstanceWithSwitchDependencies(
                getString(R.string.background_playback_title),
                new ArrayList<>(),
                null,
                RK_BACKGROUND_PLAYBACK,
                helper,
                getString(R.string.background_playback_title),
                enabled,
                deps
            );
        sheet.show(
            getSupportFragmentManager(),
            "video_background_playback_switch_sheet"
        );
    }

    private void applyMutedStateToPlayer(boolean muted) {
        try {
            if (player != null) {
                player.setVolume(muted ? 0f : 1f);
            }
        } catch (Exception ignored) {}
    }

    // method(showPlaybackSpeedPickerSheet)-----------

    private int resolveThemeColor(int attr) {
        android.util.TypedValue typedValue = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, typedValue, true);
        return typedValue.data;
    }


    // --- Lifecycle Management ---
    @Override
    protected void onResume() {
        super.onResume();
        // Keep screen on during playback if enabled in settings
        boolean keepAwake = SharedPreferencesManager.getInstance(
            this
        ).isPlayerKeepScreenOn();
        if (keepAwake) {
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            );
        } else {
            getWindow().clearFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            );
        }
        // Re-attach shared player to the view when coming to foreground
        if (playerView != null) {
            com.fadcam.playback.PlayerHolder holder =
                com.fadcam.playback.PlayerHolder.getInstance();
            ExoPlayer p = holder.getOrCreate(this);
            playerView.setPlayer(p);
        }
        // Optional: Resume playback if it was paused but ready (useful if app was
        // backgrounded briefly)
        // Consider adding a check if user manually paused vs activity lifecycle pause
        if (
            player != null &&
            player.getPlaybackState() == ExoPlayer.STATE_READY &&
            !player.isPlaying()
        ) {
            // player.play(); // Uncomment if you want auto-resume on activity resume
        }

        // Request notification permission on Android 13+
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                if (
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    androidx.core.app.ActivityCompat.requestPermissions(
                        this,
                        new String[] {
                            android.Manifest.permission.POST_NOTIFICATIONS,
                        },
                        1010
                    );
                }
            }
        } catch (Exception ignored) {}

        // Enter immersive fullscreen (hide status and navigation bars)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                final android.view.WindowInsetsController wic = getWindow().getInsetsController();
                if (wic != null) {
                    wic.hide(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
                    wic.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                final View decor = getWindow().getDecorView();
                decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                );
            }
        } catch (Exception ignored) {}

        // Do not start the background service while activity is in foreground; handoff
        // occurs onPause only.
    }

    // Show a temporary overlay on double-tap side (reuse a simple TextView overlay
    // in layout or create one)
    private void showDoubleTapOverlay(boolean forward) {
        try {
            View root = findViewById(android.R.id.content);
            if (root == null) return;
            // Always use a dedicated double-tap overlay view (do NOT reuse
            // quickSpeedOverlay)
            View overlay = root.findViewWithTag("double_tap_overlay");
            if (overlay == null) {
                android.widget.TextView tv = new android.widget.TextView(this);
                tv.setTag("double_tap_overlay");
                // Match quick-speed styling: white bold text, 16sp, rounded dark background
                tv.setTextColor(android.graphics.Color.WHITE);
                tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
                // Use the same rounded background drawable used by quick speed overlay
                try {
                    tv.setBackgroundResource(R.drawable.quick_speed_bg);
                } catch (Exception ignored) {}
                // Same content paddings as quick_speed_overlay in XML (12dp horizontal, 6dp
                // vertical)
                int hpad = (int) (12 *
                    getResources().getDisplayMetrics().density);
                int vpad = (int) (6 *
                    getResources().getDisplayMetrics().density);
                tv.setPadding(hpad, vpad, hpad, vpad);
                tv.setGravity(android.view.Gravity.CENTER);
                android.widget.FrameLayout.LayoutParams lp =
                    new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                    );
                // Equal side margins so left and right overlays are symmetric
                int sideMargin = (int) (16 *
                    getResources().getDisplayMetrics().density);
                lp.setMarginStart(sideMargin);
                lp.setMarginEnd(sideMargin);
                lp.gravity =
                    android.view.Gravity.CENTER_VERTICAL |
                    (forward
                            ? android.view.Gravity.END
                            : android.view.Gravity.START);
                final android.widget.TextView tvf = tv;
                root.post(() ->
                    ((android.widget.FrameLayout) root).addView(tvf, lp)
                );
                overlay = tv;
            }
            final View overlayView = overlay;
            if (overlayView instanceof android.widget.TextView) {
                int seekSec = spm != null
                    ? spm.getPlayerSeekSeconds()
                    : Constants.DEFAULT_PLAYER_SEEK_SECONDS;
                ((android.widget.TextView) overlayView).setText(
                    forward ? "+" + seekSec + "s" : "-" + seekSec + "s"
                );
                // Ensure font size and weight match quick overlay
                ((android.widget.TextView) overlayView).setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_SP,
                    16
                );
                ((android.widget.TextView) overlayView).setTypeface(
                    android.graphics.Typeface.DEFAULT_BOLD
                );
            }
            // Position: update layout gravity so plus is on right, minus on left
            try {
                android.view.ViewParent p = overlayView.getParent();
                if (p instanceof android.widget.FrameLayout) {
                    android.widget.FrameLayout.LayoutParams lp =
                        (android.widget.FrameLayout.LayoutParams) overlayView.getLayoutParams();
                    // Respect the created margins; update gravity and ensure symmetric margins
                    if (forward) lp.gravity =
                        android.view.Gravity.CENTER_VERTICAL |
                        android.view.Gravity.END;
                    else lp.gravity =
                        android.view.Gravity.CENTER_VERTICAL |
                        android.view.Gravity.START;
                    int sideMargin = (int) (16 *
                        getResources().getDisplayMetrics().density);
                    lp.setMarginStart(sideMargin);
                    lp.setMarginEnd(sideMargin);
                    overlayView.setLayoutParams(lp);
                }
            } catch (Exception ignored) {}

            overlayView.setVisibility(View.VISIBLE);
            overlayView.setAlpha(0f);
            overlayView
                .animate()
                .alpha(1f)
                .setDuration(80)
                .withEndAction(() ->
                    overlayView.postDelayed(
                        () ->
                            overlayView
                                .animate()
                                .alpha(0f)
                                .setDuration(250)
                                .withEndAction(() ->
                                    overlayView.setVisibility(View.GONE)
                                ),
                        500
                    )
                )
                .start();
        } catch (Exception ignored) {}
    }

        // Toggle controller and back-button together; back button is only visible when controller is visible
        private void toggleControllerAndBackButton() {
            try {
                if (playerView == null) return;
                boolean visible = isControllerVisibleSafe();
                if (visible) {
                    if (isTimebarScrubbing) return; // don't allow hiding while scrubbing
                    playerView.hideController();
                    try { 
                        backButton.setVisibility(View.GONE);
                        infoButton.setVisibility(View.GONE);
                    } catch (Exception ignored) {}
                    // Cancel any auto-hide runnable
                    if (controlsHideRunnable != null) controlsHideHandler.removeCallbacks(controlsHideRunnable);
                } else {
                    playerView.showController();
                    try { 
                        backButton.setVisibility(View.VISIBLE);
                        infoButton.setVisibility(View.VISIBLE);
                    } catch (Exception ignored) {}
                    // Schedule hide according to prefs
                    int t = SharedPreferencesManager.getInstance(this).getPlayerControlsTimeoutSeconds();
                    if (t > 0) {
                        if (controlsHideRunnable != null) controlsHideHandler.removeCallbacks(controlsHideRunnable);
                        controlsHideRunnable = () -> {
                            if (isTimebarScrubbing) {
                                // If scrubbing resumed before timer, reschedule once instead of hiding
                                int t2 = SharedPreferencesManager.getInstance(this).getPlayerControlsTimeoutSeconds();
                                if (t2 > 0) controlsHideHandler.postDelayed(controlsHideRunnable, t2 * 1000L);
                                return;
                            }
                            try { 
                                playerView.hideController();
                                backButton.setVisibility(View.GONE);
                                infoButton.setVisibility(View.GONE);
                            } catch (Exception ignored) {}
                        };
                        controlsHideHandler.postDelayed(controlsHideRunnable, t * 1000L);
                    }
                }
            } catch (Exception ignored) {}
        }

        // Simple transient overlays using a small inflated layout (icon + text)
        private android.view.View ensureGestureOverlay(String tag, int gravity, int marginStart, int marginEnd) {
            try {
                final View root = findViewById(android.R.id.content);
                if (root == null) return null;
                android.view.View existing = root.findViewWithTag(tag);
                if (existing != null) return existing;
                // Inflate with root as parent (no attach) to pick up correct styling
                final android.view.View overlay = getLayoutInflater().inflate(R.layout.gesture_overlay, (ViewGroup) root, false);
                overlay.setTag(tag);
                final android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
                lp.gravity = gravity;
                lp.setMarginStart(marginStart);
                lp.setMarginEnd(marginEnd);
                final android.widget.FrameLayout.LayoutParams _lp = lp;
                final View _overlay = overlay;
                // Add to the window decor's content frame to ensure proper stacking (usually a FrameLayout)
                final ViewGroup decor = (ViewGroup) getWindow().getDecorView().findViewById(android.R.id.content);
                if (decor != null) {
                    try {
                        // Add synchronously so first show call immediately affects the attached view
                        if (_overlay.getParent() == null) {
                            _overlay.setVisibility(View.GONE);
                            decor.addView(_overlay, _lp);
                            _overlay.bringToFront();
                            try { _overlay.setElevation(100f); } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {}
                }
                return overlay;
            } catch (Exception ignored) {}
            return null;
        }

        private void showSeekOverlay(int secondsDelta) {
            try {
                String tag = "gesture_seek_overlay";
                // Place seek overlay at the top center so it doesn't collide with
                // the player's main controls in the center of the screen.
                int topMargin = (int) (48 * getResources().getDisplayMetrics().density);
                // Use zero start/end margins so the overlay is perfectly centered horizontally.
                android.view.View v = ensureGestureOverlay(tag, android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL, 0, 0);
                // If the overlay was just created or found, update its layout params to add a top margin
                try {
                    if (v != null && v.getLayoutParams() instanceof android.widget.FrameLayout.LayoutParams) {
                        android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) v.getLayoutParams();
                        lp.topMargin = topMargin;
                        v.setLayoutParams(lp);
                    }
                } catch (Exception ignored) {}
                if (v == null) return;
                android.widget.ImageView iv = v.findViewById(R.id.overlay_icon);
                android.widget.TextView tv = v.findViewById(R.id.overlay_text);
                // Use forward/back icons depending on sign; when secondsDelta is 0 use
                // the last detected swipe direction to choose orientation immediately.
                if (secondsDelta > 0) iv.setImageResource(R.drawable.ic_fast_forward_24);
                else if (secondsDelta < 0) iv.setImageResource(R.drawable.ic_fast_rewind_24);
                else {
                    if (lastSeekDirection >= 0) iv.setImageResource(R.drawable.ic_fast_forward_24);
                    else iv.setImageResource(R.drawable.ic_fast_rewind_24);
                }
                tv.setText((secondsDelta >= 0 ? "+" : "") + secondsDelta + "s");
                // Show with quick fade-in and keep visible until hideSeekOverlay() is called
                // Cancel any running animations to avoid flicker
                try { v.animate().cancel(); } catch (Exception ignored) {}
                // Show immediately (no animated fade-in) so continuous MOVE will make the
                // overlay visible without needing motion to stop.
                v.setVisibility(View.VISIBLE);
                v.setAlpha(1f);
            } catch (Exception ignored) {}
        }

        private void hideSeekOverlay() {
            try {
                View root = findViewById(android.R.id.content);
                if (root == null) return;
                View v = root.findViewWithTag("gesture_seek_overlay");
                if (v != null) {
                    try { v.animate().cancel(); } catch (Exception ignored) {}
                    v.animate().alpha(0f).setDuration(300).withEndAction(() -> v.setVisibility(View.GONE)).start();
                }
            } catch (Exception ignored) {}
        }

    private void showVolumeOverlay(int percent) {
            try {
                Log.d(TAG, "showVolumeOverlay percent=" + percent);
                String tag = "gesture_volume_overlay";
                int margin = (int) (16 * getResources().getDisplayMetrics().density);
                android.view.View v = ensureGestureOverlay(tag, android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL, 0, margin);
                if (v == null) return;
        android.widget.ImageView iv = v.findViewById(R.id.overlay_icon);
        android.widget.TextView tv = v.findViewById(R.id.overlay_text);
                // Choose icon based on volume level
                if (percent <= 0) iv.setImageResource(R.drawable.ic_volume_off_24);
                else iv.setImageResource(R.drawable.ic_volume_up_24);
                // Tint: danger red when >60, white otherwise
                try {
                    if (percent > 60) iv.setImageTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.RED));
                    else iv.setImageTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE));
                } catch (Exception ignored) {}
                tv.setText(percent + "%");
                // Show and keep visible until hideVolumeOverlay is called
                // Cancel any ongoing animations and show immediately so MOVE shows overlay
                try { v.animate().cancel(); } catch (Exception ignored) {}
                v.setVisibility(View.VISIBLE);
                v.setAlpha(1f);
                Log.d(TAG, "showVolumeOverlay done, view visible=" + (v.getVisibility()==View.VISIBLE));
            } catch (Exception ignored) {}
        }

        private void hideVolumeOverlay() {
            try {
                View root = findViewById(android.R.id.content);
                if (root == null) return;
                View v = root.findViewWithTag("gesture_volume_overlay");
                if (v != null) {
                    try { v.animate().cancel(); } catch (Exception ignored) {}
                    v.animate().alpha(0f).setDuration(300).withEndAction(() -> v.setVisibility(View.GONE)).start();
                }
            } catch (Exception ignored) {}
        }

        private void showBrightnessOverlay(int percent) {
            try {
                Log.d(TAG, "showBrightnessOverlay percent=" + percent);
                String tag = "gesture_brightness_overlay";
                int margin = (int) (16 * getResources().getDisplayMetrics().density);
                android.view.View v = ensureGestureOverlay(tag, android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL, margin, 0);
                if (v == null) return;
                android.widget.ImageView iv = v.findViewById(R.id.overlay_icon);
                android.widget.TextView tv = v.findViewById(R.id.overlay_text);
                // Use a lightbulb icon for brightness
                iv.setImageResource(R.drawable.ic_lightbulb);
                // Tint brightness: red when >80, yellow 30-80, no tint under 30%
                try {
                    if (percent > 80) {
                        iv.setImageTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.RED));
                    } else if (percent >= 30) {
                        int yellow = android.graphics.Color.rgb(255, 204, 51);
                        iv.setImageTintList(android.content.res.ColorStateList.valueOf(yellow));
                    } else {
                        // remove tint for low brightness
                        iv.setImageTintList(null);
                    }
                } catch (Exception ignored) {}
                tv.setText(percent + "%");
                // Show and keep until hideBrightnessOverlay called
                // Cancel any ongoing animations and show immediately so MOVE shows overlay
                try { v.animate().cancel(); } catch (Exception ignored) {}
                v.setVisibility(View.VISIBLE);
                v.setAlpha(1f);
                Log.d(TAG, "showBrightnessOverlay done, view visible=" + (v.getVisibility()==View.VISIBLE));
            } catch (Exception ignored) {}
        }

        private void hideBrightnessOverlay() {
            try {
                View root = findViewById(android.R.id.content);
                if (root == null) return;
                View v = root.findViewWithTag("gesture_brightness_overlay");
                if (v != null) {
                    try { v.animate().cancel(); } catch (Exception ignored) {}
                    v.animate().alpha(0f).setDuration(300).withEndAction(() -> v.setVisibility(View.GONE)).start();
                }
            } catch (Exception ignored) {}
        }


    @Override
    protected void onPause() {
        super.onPause();
        // Allow screen to turn off when activity is paused
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Pause playback when activity goes into background/pause state
        if (player != null) {
            boolean bg = SharedPreferencesManager.getInstance(
                this
            ).isBackgroundPlaybackEnabled();
            boolean isPlaying = false;
            try {
                isPlaying = player.isPlaying();
            } catch (Exception ignored) {}
            if (bg && isPlaying && hasPostNotificationsPermission()) {
                // Only handoff to background service if currently playing
                try {
                    android.content.Intent svc = new android.content.Intent(
                        this,
                        com.fadcam.services.PlaybackService.class
                    )
                        .setAction(
                            com.fadcam.services.PlaybackService.ACTION_START
                        )
                        .putExtra(
                            com.fadcam.services.PlaybackService.EXTRA_URI,
                            getIntent().getData()
                        )
                        .putExtra(
                            com.fadcam.services.PlaybackService.EXTRA_SPEED,
                            player.getPlaybackParameters().speed
                        )
                        .putExtra(
                            com.fadcam.services.PlaybackService.EXTRA_MUTED,
                            player.getVolume() == 0f
                        )
                        .putExtra(
                            com.fadcam.services.PlaybackService.EXTRA_POSITION_MS,
                            player.getCurrentPosition()
                        )
                        .putExtra(
                            com.fadcam.services.PlaybackService.EXTRA_PLAY_WHEN_READY,
                            true
                        )
                        .putExtra(
                            com.fadcam.services.PlaybackService.EXTRA_FORCE_SHOW_NOTIFICATION,
                            true
                        );
                    androidx.core.content.ContextCompat.startForegroundService(
                        this,
                        svc
                    );
                } catch (Exception e) {
                    Log.w(
                        TAG,
                        "Failed to start background playback service",
                        e
                    );
                }
            } else {
                // Not playing or background playback disabled: ensure playback is paused and
                // service stopped
                try {
                    if (isPlaying) player.pause();
                } catch (Exception ignored) {}
                try {
                    android.content.Intent stop = new android.content.Intent(
                        this,
                        com.fadcam.services.PlaybackService.class
                    ).setAction(
                        com.fadcam.services.PlaybackService.ACTION_STOP
                    );
                    startService(stop);
                } catch (Exception ignored) {}
                Log.d(
                    TAG,
                    "Background service stopped (no background playback or paused)."
                );
            }
        }
        // Persist current playback position when pausing
        saveCurrentPlaybackPosition();
        // Stop periodic saves while paused
        resumeSaveHandler.removeCallbacks(resumeSaveRunnable);
        // Detach view to let service own playback surface-less
        if (playerView != null) playerView.setPlayer(null);
    }

    @Override
    protected void onDestroy() {
        // Clear screen flags again just in case
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Persist current playback position prior to destruction
        saveCurrentPlaybackPosition();

        // Clean up waveform analysis
        if (audioWaveformView != null) {
            audioWaveformView.cleanup();
            audioWaveformView = null;
        }

        // Clean up scrub controller enforcement
        stopScrubControllerEnforcement();

        super.onDestroy();
        // *** Release the player ***
        // Do not release the shared player here; service may be using it
        try {
            if (player != null) player.pause();
        } catch (Exception ignored) {}
        player = null;
        // Stop background playback service when activity is destroyed (if pref
        // disabled)
        try {
            android.content.Intent stop = new android.content.Intent(
                this,
                com.fadcam.services.PlaybackService.class
            ).setAction(com.fadcam.services.PlaybackService.ACTION_STOP);
            startService(stop);
        } catch (Exception ignored) {}
        // Unregister SharedPreferences listener if it was registered
        try {
            if (spm != null && prefsListener != null) {
                spm.sharedPreferences.unregisterOnSharedPreferenceChangeListener(
                    prefsListener
                );
                prefsListener = null;
            }
        } catch (Exception ignored) {}
    } // --- End onDestroy ---

    // --- End Lifecycle Management ---

    private MaterialAlertDialogBuilder themedDialogBuilder() {
        int dialogTheme = R.style.ThemeOverlay_FadCam_Dialog;
        return new MaterialAlertDialogBuilder(this, dialogTheme);
    }


    // helper(hasPostNotificationsPermission)-----------
    /**
     * Returns true if either SDK < 33 or POST_NOTIFICATIONS permission is granted.
     */
    private boolean hasPostNotificationsPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return true;
        try {
            return (
                androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            );
        } catch (Exception e) {
            return false;
        }
    }

    // helper(hasPostNotificationsPermission)-----------

    private void setupAnimatedControls() {
        try {
            // Set up periodic waveform updates during playback
            setupWaveformUpdates();

            // Set up player state listener for waveform updates only
            if (player != null) {
                player.addListener(
                    new Player.Listener() {
                        @Override
                        public void onIsPlayingChanged(boolean isPlaying) {
                            updateWaveformProgress();
                        }
                    }
                );
            }

            // Set initial waveform visibility based on user preference
            com.fadcam.ui.custom.AudioWaveformView waveformView =
                playerView.findViewById(R.id.audio_waveform);
            if (waveformView != null) {
                boolean enabled = spm != null
                    ? spm.isAudioWaveformEnabled()
                    : true;
                waveformView.setVisibility(enabled ? View.VISIBLE : View.GONE);

                // Start real audio analysis if we have a video URI
                android.net.Uri currentUri = getCurrentVideoUri();
                if (currentUri != null) {
                    Log.i(
                        TAG,
                        "Starting real audio analysis for URI: " + currentUri +
                        " (scheme=" + currentUri.getScheme() + ")"
                    );
                    waveformView.analyzeAudioFromVideo(currentUri);
                } else {
                    Log.w(TAG, "No current video URI for waveform analysis");
                }
            }

            // Store waveform view reference for later use
            audioWaveformView = waveformView;
        } catch (Exception e) {
            Log.w(TAG, "Error setting up controls", e);
        }
    }

    private android.os.Handler waveformHandler;

    /**
     * Get the current video URI for audio analysis
     */
    private android.net.Uri getCurrentVideoUri() {
        return currentVideoUri;
    }

    private Runnable waveformUpdateRunnable;

    private void setupWaveformUpdates() {
        try {
            if (waveformHandler == null) {
                waveformHandler = new android.os.Handler(
                    android.os.Looper.getMainLooper()
                );
            }

            if (waveformUpdateRunnable == null) {
                waveformUpdateRunnable = new Runnable() {
                    @Override
                    public void run() {
                        try {
                            updateWaveformProgress();
                            // Update every 100ms for smooth waveform animation
                            if (waveformHandler != null) {
                                waveformHandler.postDelayed(this, 100);
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Error in waveform update runnable", e);
                        }
                    }
                };
            }

            waveformHandler.post(waveformUpdateRunnable);
        } catch (Exception e) {
            Log.w(TAG, "Error setting up waveform updates", e);
        }
    }

    private void updateWaveformProgress() {
        try {
            // Only update if waveform is enabled
            boolean enabled = spm != null ? spm.isAudioWaveformEnabled() : true;
            if (!enabled) return;

            com.fadcam.ui.custom.AudioWaveformView waveformView =
                playerView.findViewById(R.id.audio_waveform);
            if (
                waveformView != null &&
                player != null &&
                waveformView.getVisibility() == View.VISIBLE
            ) {
                long position = player.getCurrentPosition();
                long duration = player.getDuration();
                if (duration > 0) {
                    float progress = (float) position / duration;
                    waveformView.setProgress(progress);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error updating waveform progress", e);
        }
    }


    private void setupResetZoomButton() {
        if (resetZoomButton != null) {
            resetZoomButton.setOnClickListener(v -> resetVideoTransform());
        }
    }


    private void applyVideoTransform() {
        if (playerView != null) {
            try {
                // Find the content frame to scale only video, not controls
                android.view.View contentFrame = playerView.findViewById(
                    androidx.media3.ui.R.id.exo_content_frame
                );
                if (contentFrame != null) {
                    contentFrame.setScaleX(currentScale);
                    contentFrame.setScaleY(currentScale);
                    contentFrame.setTranslationX(currentTranslationX);
                    contentFrame.setTranslationY(currentTranslationY);
                } else {
                    // Fallback: try to find shutter or surface view
                    android.view.View shutter = playerView.findViewById(
                        androidx.media3.ui.R.id.exo_shutter
                    );
                    if (shutter != null) {
                        shutter.setScaleX(currentScale);
                        shutter.setScaleY(currentScale);
                        shutter.setTranslationX(currentTranslationX);
                        shutter.setTranslationY(currentTranslationY);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error applying video transform", e);
            }
        }
    }


    private void resetVideoTransform() {
        currentScale = 1.0f;
        currentTranslationX = 0f;
        currentTranslationY = 0f;

        if (playerView != null) {
            try {
                android.view.View contentFrame = playerView.findViewById(
                    androidx.media3.ui.R.id.exo_content_frame
                );
                if (contentFrame != null) {
                    contentFrame
                        .animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .translationX(0f)
                        .translationY(0f)
                        .setDuration(200)
                        .setInterpolator(
                            new android.view.animation.DecelerateInterpolator()
                        )
                        .start();
                } else {
                    android.view.View shutter = playerView.findViewById(
                        androidx.media3.ui.R.id.exo_shutter
                    );
                    if (shutter != null) {
                        shutter
                            .animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .translationX(0f)
                            .translationY(0f)
                            .setDuration(200)
                            .setInterpolator(
                                new android.view.animation.DecelerateInterpolator()
                            )
                            .start();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error resetting video transform", e);
            }
        }

        updateResetZoomButtonVisibility();
    }


    // method(updateResetZoomButtonVisibility)-----------
    private void updateResetZoomButtonVisibility() {
        if (resetZoomButton != null) {
            if (
                Math.abs(currentScale - 1.0f) > 0.1f ||
                Math.abs(currentTranslationX) > 10f ||
                Math.abs(currentTranslationY) > 10f
            ) {
                resetZoomButton.setVisibility(android.view.View.VISIBLE);
            } else {
                resetZoomButton.setVisibility(android.view.View.GONE);
            }
        }
    }
    // method(updateResetZoomButtonVisibility)-----------

    // -------------- Scrub Controller Enforcement Methods -----------
    private void startScrubControllerEnforcement() {
        stopScrubControllerEnforcement(); // stop any existing enforcement
        if (scrubControllerEnforceRunnable == null) {
            scrubControllerEnforceRunnable = new Runnable() {
                @Override
                public void run() {
                    if (!isTimebarScrubbing) return; // stop if scrubbing ended
                    
                    try {
                        if (playerView != null && !playerView.isControllerFullyVisible()) {
                            android.util.Log.w("VideoPlayerActivity", "Controller became hidden during scrubbing - forcing it visible");
                            playerView.setControllerShowTimeoutMs(0);
                            playerView.showController();
                            if (backButton != null) backButton.setVisibility(View.VISIBLE);
                            if (infoButton != null) infoButton.setVisibility(View.VISIBLE);
                        }
                    } catch (Exception e) {
                        android.util.Log.e("VideoPlayerActivity", "Error in scrub controller enforcement", e);
                    }
                    
                    // Schedule next check if still scrubbing
                    if (isTimebarScrubbing && scrubControllerEnforceHandler != null) {
                        scrubControllerEnforceHandler.postDelayed(this, 100); // Check every 100ms
                    }
                }
            };
        }
        scrubControllerEnforceHandler.post(scrubControllerEnforceRunnable);
    }
    
    private void stopScrubControllerEnforcement() {
        if (scrubControllerEnforceRunnable != null && scrubControllerEnforceHandler != null) {
            scrubControllerEnforceHandler.removeCallbacks(scrubControllerEnforceRunnable);
        }
    }
    // -------------- End Scrub Controller Enforcement Methods -----------
    
    /**
     * Check if a MotionEvent occurred within the bounds of a given view.
     * This is used to detect if a touch is on the slider to avoid gesture processing conflicts.
     */
    private boolean isTouchOnView(View view, android.view.MotionEvent event) {
        if (view == null) return false;
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        float rawX = event.getRawX();
        float rawY = event.getRawY();
        int viewLeft = location[0];
        int viewTop = location[1];
        int viewRight = viewLeft + view.getWidth();
        int viewBottom = viewTop + view.getHeight();
        return rawX >= viewLeft && rawX <= viewRight && rawY >= viewTop && rawY <= viewBottom;
    }
}
