package com.fadcam.ui;

import android.net.Uri;
import android.os.Bundle;
import android.util.Log; // Use standard Android Log
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.Toast;
import android.widget.TextView;
import java.text.DecimalFormat;
import java.util.Locale;
import android.widget.ArrayAdapter;
import android.view.ViewGroup;
import java.util.ArrayList;

import androidx.appcompat.app.AppCompatActivity;
import android.annotation.SuppressLint;

import com.fadcam.R;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.C; // For Playback Speed
import com.google.android.exoplayer2.ui.StyledPlayerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.Constants;
import com.fadcam.ui.picker.PickerBottomSheetFragment;
import com.fadcam.ui.picker.OptionItem;


public class VideoPlayerActivity extends AppCompatActivity {

    private static final String TAG = "VideoPlayerActivity";

    private ExoPlayer player;
    private StyledPlayerView playerView;
    private android.view.GestureDetector gestureDetector;
    private android.view.ScaleGestureDetector scaleGestureDetector;
    private float currentScale = 1.0f;
    private float currentTranslationX = 0f;
    private float currentTranslationY = 0f;
    private boolean isPinching = false;
    private ImageButton resetZoomButton;
    private static final String ACTION_PLAYBACK_POSITION_UPDATED = "com.fadcam.ACTION_PLAYBACK_POSITION_UPDATED";
    private static final String EXTRA_URI = "extra_uri";
    private static final String EXTRA_POSITION_MS = "extra_position_ms";
    private ImageButton backButton;
    private ImageButton settingsButton; // For playback speed
    private TextView quickSpeedOverlay;
    private SharedPreferencesManager spm;
    private com.fadcam.ui.custom.AudioWaveformView audioWaveformView;
    private android.net.Uri currentVideoUri;
    // Periodic resume-save handler
    private final android.os.Handler resumeSaveHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable resumeSaveRunnable = new Runnable() {
        private long lastSavedPos = -1;
        @Override
        public void run() {
            try {
                if (player == null) return;
                boolean isPlaying = false;
                try { isPlaying = player.isPlaying(); } catch (Exception ignored) {}
                if (isPlaying) {
                    long pos = player.getCurrentPosition();
                    if (Math.abs(pos - lastSavedPos) > 1000) { // only save if moved >1s
                        com.fadcam.playback.PlayerHolder holder = com.fadcam.playback.PlayerHolder.getInstance();
                        Uri cur = holder.getCurrentUri();
                        String filename = null;
                        try { filename = getFileName(cur); } catch (Exception ignored) {}
                        SharedPreferencesManager spmLoc = SharedPreferencesManager.getInstance(VideoPlayerActivity.this);
                        if (cur != null) spmLoc.setSavedPlaybackPositionMs(cur.toString(), pos);
                        if (filename != null && !filename.isEmpty()) spmLoc.setSavedPlaybackPositionMsByFilename(filename, pos);
                        lastSavedPos = pos;
                        Log.d(TAG, "Periodic save position: " + pos + " for " + (cur!=null?cur:filename));
                    }
                }
            } catch (Exception e) { Log.w(TAG, "Error during periodic resume save", e); }
            // Re-post
            resumeSaveHandler.postDelayed(this, 5000);
        }
    };
    // -------------- Fix Start for field(video_settings_result_keys)-----------
    private static final String RK_VIDEO_SETTINGS = "rk_video_settings";
    private static final String RK_PLAYBACK_SPEED = "rk_playback_speed";
    private static final String RK_QUICK_SPEED = "rk_quick_speed";
    private static final String RK_KEEP_SCREEN_ON = "rk_keep_screen_on";
    private static final String RK_BACKGROUND_PLAYBACK = "rk_background_playback";
    // -------------- Fix Ended for field(video_settings_result_keys)-----------

    // Playback speed options (include more slow-speed choices 0.5 -> 0.9)
    private final CharSequence[] speedOptions = {"0.5x", "0.6x", "0.7x", "0.8x", "0.9x", "1x (Normal)", "1.5x", "2x", "3x", "4x", "6x", "8x", "10x"};
    private final float[] speedValues = {0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f, 6.0f, 8.0f, 10.0f};
    private int currentSpeedIndex = 5; // Index for 1.0x speed in updated speedValues
    // Guard to keep single-tap from being treated as part of double-tap or re-triggering control toggles
    private long lastSingleTapTime = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Initialize SharedPreferencesManager for theme
        SharedPreferencesManager sharedPreferencesManager = SharedPreferencesManager.getInstance(this);
        String savedTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);

        if ("Crimson Bloom".equals(savedTheme)) {
            setTheme(R.style.Theme_FadCam_Red);
        } else if ("Faded Night".equals(savedTheme)) {
            setTheme(R.style.Theme_FadCam_Amoled);
        } else {
            setTheme(R.style.Base_Theme_FadCam);
        }

        super.onCreate(savedInstanceState);
        setContentView(com.fadcam.R.layout.activity_video_player);

        playerView = findViewById(com.fadcam.R.id.player_view);
        backButton = findViewById(com.fadcam.R.id.back_button);
        resetZoomButton = findViewById(com.fadcam.R.id.reset_zoom_button);

        // *** FIX: Get the video URI using getData() ***
        Uri videoUri = getIntent().getData();
        this.currentVideoUri = videoUri; // Store for later use

        if (videoUri != null) {
            Log.i(TAG, "Received video URI: " + videoUri.toString());
            spm = SharedPreferencesManager.getInstance(this);
            // Read default playback speed from prefs (if set) and adjust currentSpeedIndex
            float defSpd = sharedPreferencesManager.sharedPreferences.getFloat("pref_default_playback_speed", speedValues[currentSpeedIndex]);
            for(int i=0;i<speedValues.length;i++){ if(Math.abs(speedValues[i]-defSpd)<0.001f){ currentSpeedIndex = i; break; } }
            initializePlayer(videoUri); // Pass the Uri directly
            setupCustomSettingsAction();
            setupQuickSpeedSettings();
            setupResetZoomButton();
            setupPressAndHoldFor2x();
        } else {
            // Log error and finish if URI is missing
            Log.e(TAG, "Video URI is null. Intent Data: " + getIntent().getDataString() +". Cannot initialize player.");
            Toast.makeText(this, "Error: Video URI not found", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        setupBackButton();

        // Ensure the system status bar matches the header/back-button area color for this activity
        try {
            int statusBarColor = resolveThemeColor(R.attr.colorTopBar);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().setStatusBarColor(statusBarColor);
        } catch (Exception e) { Log.w(TAG, "Failed to set status bar color", e); }

        // ----- Fix Start: Programmatically set seekbar colors for dynamic theming -----
        // ExoPlayer's XML attributes may not always apply theme attributes at runtime, so set them here
        View timeBar = playerView.findViewById(com.google.android.exoplayer2.ui.R.id.exo_progress);
        if (timeBar instanceof com.google.android.exoplayer2.ui.DefaultTimeBar) {
            com.google.android.exoplayer2.ui.DefaultTimeBar bar = (com.google.android.exoplayer2.ui.DefaultTimeBar) timeBar;
            
            // Set colors programmatically for modern look
            int played = resolveThemeColor(R.attr.colorButton);
            int unplayed = android.graphics.Color.parseColor("#40FFFFFF"); // Semi-transparent white for unplayed
            int buffered = android.graphics.Color.parseColor("#60FFFFFF"); // More opaque white for buffered
            int scrubber = resolveThemeColor(R.attr.colorButton);
            bar.setPlayedColor(played);
            bar.setUnplayedColor(unplayed);
            bar.setBufferedColor(buffered);
            bar.setScrubberColor(scrubber);
        }
        // ----- Fix End: Programmatically set seekbar colors for dynamic theming -----
        
        // Setup animated play/pause button and audio waveform
        setupAnimatedControls();
        
        // Setup gesture detector for double-tap seek
        gestureDetector = new android.view.GestureDetector(this, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(android.view.MotionEvent e) {
                try {
                    // Toggle controller visibility on single tap using a safe visibility check
                    if (playerView != null) {
                        boolean visible = isControllerVisibleSafe();
                        if (visible) playerView.hideController(); else playerView.showController();
                        // record time so ACTION_UP handling can avoid double-processing
                        lastSingleTapTime = System.currentTimeMillis();
                    }
                    return true;
                } catch (Exception ex) { Log.w(TAG, "singleTap error", ex); return true; }
            }

            @Override
            public boolean onDoubleTap(android.view.MotionEvent e) {
                try {
                    float x = e.getX();
                    int w = playerView.getWidth();
                    if (w <= 0) return true;
                    boolean forward = x > (w / 2f);
                    int seekSec = spm != null ? spm.getPlayerSeekSeconds() : Constants.DEFAULT_PLAYER_SEEK_SECONDS;
                    int seekMs = seekSec * 1000;
                    long current = player != null ? player.getCurrentPosition() : 0L;
                    long target = forward ? current + seekMs : current - seekMs;
                    if (target < 0) target = 0;
                    if (player != null) player.seekTo(target);
                    // ensure controller is hidden for double-tap seek UX (no toggling)
                    try { playerView.hideController(); } catch (Exception ignored) {}
                    // show overlay and save position immediately
                    showDoubleTapOverlay(forward);
                    // Save & broadcast immediately
                    saveCurrentPlaybackPosition();
                    // avoid single-tap handling for a short grace period
                    lastSingleTapTime = System.currentTimeMillis();
                    return true;
                } catch (Exception ex) { Log.w(TAG, "doubleTap error", ex); return true; }
            }
        });

        // Setup scale gesture detector for pinch-to-zoom
        scaleGestureDetector = new android.view.ScaleGestureDetector(this, new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(android.view.ScaleGestureDetector detector) {
                isPinching = true; // Set pinching state to disable press-and-hold
                return true;
            }
            
            @Override
            public boolean onScale(android.view.ScaleGestureDetector detector) {
                currentScale *= detector.getScaleFactor();
                // Constrain scale between 0.5x and 3.0x
                currentScale = Math.max(0.5f, Math.min(currentScale, 3.0f));
                
                // Apply scale only to video surface, not the entire player view with controls
                applyVideoTransform();
                
                // Show reset button if zoomed
                updateResetZoomButtonVisibility();
                
                // Add haptic feedback for smooth zooming
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        playerView.performHapticFeedback(android.view.HapticFeedbackConstants.GESTURE_START);
                    }
                } catch (Exception ignored) {}
                
                return true;
            }

            @Override
            public void onScaleEnd(android.view.ScaleGestureDetector detector) {
                isPinching = false; // Reset pinching state
                
                // Snap to 1.0x if close to normal scale
                if (Math.abs(currentScale - 1.0f) < 0.1f) {
                    resetVideoTransform();
                }
            }
        });

    // Do not set a simple touch listener here; setupPressAndHoldFor2x() will install a combined listener
    // that handles single-tap, double-tap and press-and-hold behaviors together.
    }
    // Press-and-hold behavior: while pressed, ramp to quick speed; on release, revert to previous speed
    private void setupPressAndHoldFor2x() {
        if (playerView == null) return;
        quickSpeedOverlay = findViewById(R.id.quick_speed_overlay);
        final int LONG_PRESS_MS = 220; // threshold for long-press
        final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        final boolean[] isLongPress = {false};

        // Combined touch listener: single tap toggles controller, double-tap seeks (no controller show), long-press quick-speed
        final int touchSlop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        final float[] downXY = new float[2];
        final float[] lastPanXY = new float[2];
        final boolean[] pendingTap = new boolean[]{false};
        playerView.setOnTouchListener((v, ev) -> {
            boolean gdHandled = false;
            boolean scaleHandled = false;
            boolean gestureConsumed = false;
            
            try {
                // Handle scale gestures first
                scaleHandled = scaleGestureDetector.onTouchEvent(ev);
                
                // Only handle other gestures if not currently in a scale gesture
                if (!scaleGestureDetector.isInProgress() && !isPinching && ev.getPointerCount() == 1) {
                    // Feed events to gestureDetector for single/double tap detection
                    gdHandled = gestureDetector.onTouchEvent(ev);
                }
            } catch (Exception ignored) {}

            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: {
                    isLongPress[0] = false;
                    pendingTap[0] = true;
                    downXY[0] = ev.getX(); downXY[1] = ev.getY();
                    lastPanXY[0] = ev.getX(); lastPanXY[1] = ev.getY();
                    
                    // Only start long-press timer for single finger and not zoomed
                    if (ev.getPointerCount() == 1 && !isPinching && currentScale <= 1.1f) {
                        handler.postDelayed(() -> {
                            // On long-press - only if still single finger and not pinching/zoomed
                            if (ev.getPointerCount() <= 1 && !isPinching && currentScale <= 1.1f) {
                                isLongPress[0] = true;
                                if (player != null) {
                                    float start = player.getPlaybackParameters().speed;
                                    float target = spm != null ? spm.getQuickSpeed() : Constants.DEFAULT_QUICK_SPEED;
                                    animatePlaybackSpeed(start, target, 200);
                                    showQuickOverlay(true);
                                    try { playerView.hideController(); } catch (Exception ignored) {}
                                }
                            }
                        }, LONG_PRESS_MS);
                    }
                } break;

                case android.view.MotionEvent.ACTION_POINTER_DOWN: {
                    // Multi-touch detected - disable long-press and enable pinch mode
                    isPinching = true;
                    handler.removeCallbacksAndMessages(null);
                    // Update last pan position for multi-touch panning
                    if (ev.getPointerCount() >= 2) {
                        lastPanXY[0] = (ev.getX(0) + ev.getX(1)) / 2f;
                        lastPanXY[1] = (ev.getY(0) + ev.getY(1)) / 2f;
                    }
                } break;

                case android.view.MotionEvent.ACTION_MOVE: {
                    // Advanced panning logic for both single and multi-finger
                    if (currentScale > 1.0f) {
                        float currentX = 0f, currentY = 0f;
                        boolean canPan = false;
                        
                        if (ev.getPointerCount() == 1 && !scaleGestureDetector.isInProgress()) {
                            // Single finger panning
                            currentX = ev.getX();
                            currentY = ev.getY();
                            canPan = true;
                        } else if (ev.getPointerCount() >= 2 && !scaleGestureDetector.isInProgress()) {
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
                            android.view.View contentFrame = playerView.findViewById(com.google.android.exoplayer2.ui.R.id.exo_content_frame);
                            if (contentFrame != null) {
                                float screenWidth = playerView.getWidth();
                                float screenHeight = playerView.getHeight();
                                
                                // Allow panning to show corners - much more generous boundaries
                                float maxTranslationX = (screenWidth * (currentScale - 1.0f)) / 2f;
                                float maxTranslationY = (screenHeight * (currentScale - 1.0f)) / 2f;
                                
                                currentTranslationX = Math.max(-maxTranslationX, Math.min(currentTranslationX, maxTranslationX));
                                currentTranslationY = Math.max(-maxTranslationY, Math.min(currentTranslationY, maxTranslationY));
                            }
                            
                            applyVideoTransform();
                            lastPanXY[0] = currentX;
                            lastPanXY[1] = currentY;
                            
                            // Disable other gestures when actively panning
                            pendingTap[0] = false;
                            return true;
                        }
                    }
                    
                    // Regular move handling for touch slop detection (only for single touch when not zoomed)
                    if (pendingTap[0] && ev.getPointerCount() == 1 && currentScale <= 1.1f) {
                        float dx = Math.abs(ev.getX() - downXY[0]);
                        float dy = Math.abs(ev.getY() - downXY[1]);
                        if (dx > touchSlop || dy > touchSlop) {
                            pendingTap[0] = false;
                            // Cancel long press if significant movement detected
                            handler.removeCallbacksAndMessages(null);
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
                } break;

                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    handler.removeCallbacksAndMessages(null);
                    
                    // Reset pinching state when all fingers are lifted
                    if (ev.getPointerCount() <= 1) {
                        isPinching = false;
                    }
                    
                    if (isLongPress[0] && !isPinching) {
                        if (player != null) {
                            float revertSpeed = speedValues[currentSpeedIndex];
                            animatePlaybackSpeed(player.getPlaybackParameters().speed, revertSpeed, 200);
                        }
                        showQuickOverlay(false);
                        isLongPress[0] = false;
                    }
                } break;
                
                case android.view.MotionEvent.ACTION_POINTER_UP: {
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
                            lastPanXY[0] = (ev.getX(index1) + ev.getX(index2)) / 2f;
                            lastPanXY[1] = (ev.getY(index1) + ev.getY(index2)) / 2f;
                        }
                    } else if (remainingPointers == 1) {
                        int remainingIndex = ev.getActionIndex() == 0 ? 1 : 0;
                        lastPanXY[0] = ev.getX(remainingIndex);
                        lastPanXY[1] = ev.getY(remainingIndex);
                    }
                } break;
            }

            // If the gesture detector or scale detector handled the event, consume it so PlayerView doesn't also toggle the controller
            if (gdHandled || scaleHandled) return true;
            return false;
        });
    }

    // Recreate the quick speed settings hook: long-pressing the settings button opens quick speed picker
    private void setupQuickSpeedSettings() {
        if (settingsButton != null) {
            settingsButton.setOnLongClickListener(v -> {
                showQuickSpeedPickerSheet();
                return true;
            });
        }
    }

    // -------------- Fix Start for this method(showQuickSpeedPickerSheet)-----------
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
                items.add(new OptionItem(id, title, null, null, null, null, null, null, "bolt", null, null, null));
        }
        float current = spm != null ? spm.getQuickSpeed() : Constants.DEFAULT_QUICK_SPEED;
        String selectedId = "spd_" + current;
            PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstance(getString(R.string.quick_speed_title), items, selectedId, RK_QUICK_SPEED, getString(R.string.quick_speed_helper));
        getSupportFragmentManager().setFragmentResultListener(RK_QUICK_SPEED, this, (key, bundle) -> {
            String selId = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (selId != null && selId.startsWith("spd_")) {
                try {
                    float val = Float.parseFloat(selId.substring(4));
                    if (spm != null) spm.setQuickSpeed(val);
                } catch (NumberFormatException ignored) {}
            }
        });
        sheet.show(getSupportFragmentManager(), "quick_speed_sheet");
    }
    // -------------- Fix Ended for this method(showQuickSpeedPickerSheet)-----------

    // -------------- Fix Start for method(showAudioWaveformSwitchSheet)-----------
    private void showAudioWaveformSwitchSheet() {
        final String RK = "rk_waveform_switch";
        boolean enabled = spm != null ? spm.isAudioWaveformEnabled() : true;
        
        getSupportFragmentManager().setFragmentResultListener(RK, this, (key, bundle) -> {
            if(bundle.containsKey(PickerBottomSheetFragment.BUNDLE_SWITCH_STATE)){
                boolean state = bundle.getBoolean(PickerBottomSheetFragment.BUNDLE_SWITCH_STATE);
                if (spm != null) {
                    spm.setAudioWaveformEnabled(state);
                    
                    // Update waveform visibility immediately
                    com.fadcam.ui.custom.AudioWaveformView waveformView = playerView.findViewById(R.id.audio_waveform);
                    if (waveformView != null) {
                        waveformView.setVisibility(state ? View.VISIBLE : View.GONE);
                    }
                    
                    Log.d(TAG, "Audio waveform " + (state ? "enabled" : "disabled"));
                }
            }
        });

        ArrayList<OptionItem> items = new ArrayList<>(); // No options needed, switch only
        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstanceWithSwitch(
            "Audio Waveform", items, "", RK, 
            "Controls whether the audio waveform visualization appears on the progress bar during video playback.",
            "Enable Audio Waveform", enabled
        );
        sheet.show(getSupportFragmentManager(), "waveform_switch_sheet");
    }
    // -------------- Fix Ended for method(showAudioWaveformSwitchSheet)-----------

    // Animate playback speed from start to target over duration ms
    private void animatePlaybackSpeed(float start, float target, int durationMs) {
        if (player == null) return;
        try {
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(start, target);
            va.setDuration(durationMs);
            va.addUpdateListener(animation -> {
                float v = (float) animation.getAnimatedValue();
                try { player.setPlaybackParameters(new PlaybackParameters(v)); } catch (Exception ignored) {}
            });
            va.start();
        } catch (Exception ignored) {}
    }

    private void showQuickOverlay(boolean show) {
        if (quickSpeedOverlay == null) return;
        if (show) {
            float quick = spm != null ? spm.getQuickSpeed() : Constants.DEFAULT_QUICK_SPEED;
            // Format like "2x" or "1.5x" without unnecessary decimals
            String formatted;
            try {
                DecimalFormat df = new DecimalFormat("#.#");
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
            quickSpeedOverlay.animate().alpha(0f).setDuration(120).withEndAction(() -> quickSpeedOverlay.setVisibility(View.GONE)).start();
        }
    }

    // *** FIX: Modified method signature to accept Uri ***
    private void initializePlayer(Uri videoUri) {
        try {
            // Use shared player so service + activity stay in sync
            com.fadcam.playback.PlayerHolder holder = com.fadcam.playback.PlayerHolder.getInstance();
            player = holder.getOrCreate(this);
            playerView.setPlayer(player);
            holder.setMediaIfNeeded(videoUri);
            // Set initial playback speed
            player.setPlaybackParameters(new PlaybackParameters(speedValues[currentSpeedIndex]));
            // Apply initial mute state from prefs
            boolean muted = SharedPreferencesManager.getInstance(this).isPlaybackMuted();
            try{ player.setVolume(muted? 0f: 1f); }catch(Exception ignored){}
            // Autoplay (prepared in holder if needed)
            // Restore saved playback position (resume) if available
            try {
                String uriStr = videoUri.toString();
                String filename = getFileName(videoUri);
                long savedMs = SharedPreferencesManager.getInstance(this).getSavedPlaybackPositionMsWithFilenameFallback(uriStr, filename);
                if (savedMs > 0) {
                    player.seekTo(savedMs);
                }
            } catch (Exception ignored) {}
            player.play();
            // Listen for seeks so we can save immediately and notify adapters
            try {
                player.addListener(new com.google.android.exoplayer2.Player.Listener() {
                    public void onSeekProcessed() {
                        // Save and broadcast immediately when user finishes a seek
                        saveCurrentPlaybackPosition();
                    }
                });
            } catch (Exception ignored) {}
            // Wire center rewind/forward buttons to use same seek logic
            try {
                View controls = playerView.findViewById(com.google.android.exoplayer2.ui.R.id.exo_center_controls);
                if (controls != null) {
                    View rew = controls.findViewById(com.google.android.exoplayer2.ui.R.id.exo_rew);
                    View ffwd = controls.findViewById(com.google.android.exoplayer2.ui.R.id.exo_ffwd);
                    if (rew != null) rew.setOnClickListener(v -> {
                        try { int s = spm!=null? spm.getPlayerSeekSeconds(): Constants.DEFAULT_PLAYER_SEEK_SECONDS; long cur = player.getCurrentPosition(); long target = Math.max(0, cur - s*1000L); player.seekTo(target); showDoubleTapOverlay(false); saveCurrentPlaybackPosition(); } catch (Exception ignored) {}
                    });
                    if (ffwd != null) ffwd.setOnClickListener(v -> {
                        try { int s = spm!=null? spm.getPlayerSeekSeconds(): Constants.DEFAULT_PLAYER_SEEK_SECONDS; long cur = player.getCurrentPosition(); long dur = player.getDuration(); long target = Math.min(dur==com.google.android.exoplayer2.C.TIME_UNSET? Long.MAX_VALUE: dur, cur + s*1000L); if(dur!=com.google.android.exoplayer2.C.TIME_UNSET) player.seekTo(Math.min(target, dur)); else player.seekTo(target); showDoubleTapOverlay(true); saveCurrentPlaybackPosition(); } catch (Exception ignored) {}
                    });
                }
            } catch (Exception ignored) {}
            // Start periodic saves
            resumeSaveHandler.postDelayed(resumeSaveRunnable, 5000);
            Log.i(TAG, "ExoPlayer initialized and started for URI: " + videoUri);

        } catch (Exception e) { // Catch potential exceptions during initialization
            Log.e(TAG, "Error initializing player for URI: " + videoUri, e);
            Toast.makeText(this, "Error playing video", Toast.LENGTH_SHORT).show();
            finish(); // Close activity if player fails to initialize
        }
    }

    // Save current position to prefs keyed by current media URI

                // Get file name from URI (Helper) - used for filename fallback when saving/resuming
                @SuppressLint("Range") // Suppress lint check for getColumnIndexOrThrow
                private String getFileName(Uri uri) {
                    if (this == null || uri == null) return null;
                    String result = null;
                    if ("content".equals(uri.getScheme())) {
                        try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                            if (cursor != null && cursor.moveToFirst()) {
                                int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                                if (nameIndex != -1) {
                                    result = cursor.getString(nameIndex);
                                }
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Could not query display name for content URI: " + uri, e);
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
            com.fadcam.playback.PlayerHolder holder = com.fadcam.playback.PlayerHolder.getInstance();
            Uri cur = holder.getCurrentUri();
            if (cur != null) {
                // Save both URI-keyed and filename-keyed fallback
                SharedPreferencesManager spmLoc = SharedPreferencesManager.getInstance(this);
                spmLoc.setSavedPlaybackPositionMs(cur.toString(), pos);
                try { String fn = getFileName(cur); if (fn != null && !fn.isEmpty()) spmLoc.setSavedPlaybackPositionMsByFilename(fn, pos); } catch (Exception ignored) {}
                // Broadcast update so UI components can refresh immediately
                try {
                    android.content.Intent i = new android.content.Intent(ACTION_PLAYBACK_POSITION_UPDATED);
                    i.putExtra(EXTRA_URI, cur.toString());
                    i.putExtra(EXTRA_POSITION_MS, pos);
                    androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(i);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to save playback position", e);
        }
    }

    // Safe check for controller visibility: query the controller view inside StyledPlayerView
    private boolean isControllerVisibleSafe() {
        try {
            if (playerView == null) return false;
            View controller = playerView.findViewById(com.google.android.exoplayer2.ui.R.id.exo_controller);
            if (controller != null) return controller.getVisibility() == View.VISIBLE;
            // Fallback: try DefaultTimeBar visibility instead
            View timeBar = playerView.findViewById(com.google.android.exoplayer2.ui.R.id.exo_progress);
            if (timeBar != null) return timeBar.getVisibility() == View.VISIBLE;
        } catch (Exception ignored) {}
        return false;
    }

    private void setupBackButton() {
        backButton.setOnClickListener(v -> finish());
    }

    // --- Playback Speed Controls ---
    private void setupCustomSettingsAction() {
        if (playerView != null) {
            // Find settings button within the PlayerView's layout using ExoPlayer's ID
            // Need to import com.google.android.exoplayer2.ui.R specifically for this ID
            settingsButton = playerView.findViewById(com.google.android.exoplayer2.ui.R.id.exo_settings);
            if (settingsButton != null) {
                settingsButton.setOnClickListener(v -> showVideoSettingsSheet());
                Log.d(TAG, "Custom settings button listener attached to @id/exo_settings.");
            } else {
                // This can happen if the overridden layout exo_styled_player_control_view.xml
                // doesn't include an ImageButton with the id @id/exo_settings
                Log.w(TAG, "Could not find settings button (@id/exo_settings) within PlayerView. Ensure it exists in your custom controller layout.");
            }
        } else {
            Log.w(TAG, "PlayerView is null, cannot attach settings button listener.");
        }
    }

    // -------------- Fix Start for this method(showVideoSettingsSheet)-----------
    private void showVideoSettingsSheet() {
        ArrayList<OptionItem> items = new ArrayList<>();
        // Row: Playback Speed (show current runtime speed label)
    String playbackSubtitle = String.valueOf(speedOptions[currentSpeedIndex]);
        items.add(new OptionItem("row_playback_speed", getString(R.string.playback_speed_label), playbackSubtitle, null, null, null, null, null, "speed", null, null, null));
        // Row: Quick Speed
        float quick = spm != null ? spm.getQuickSpeed() : Constants.DEFAULT_QUICK_SPEED;
        String quickSubtitle;
        try { java.text.DecimalFormat df = new java.text.DecimalFormat("#.#"); quickSubtitle = df.format(quick) + "x"; } catch (Exception e) { quickSubtitle = quick + "x"; }
        items.add(new OptionItem("row_quick_speed", getString(R.string.quick_speed_title), quickSubtitle, null, null, null, null, null, "bolt", null, null, null));
        // Row: Mute playback (handled as a switch via a separate sheet)
        boolean mutedPref = SharedPreferencesManager.getInstance(this).isPlaybackMuted();
        String muteSubtitle = mutedPref ? getString(R.string.universal_enable) : getString(R.string.universal_disable);
        items.add(new OptionItem("row_mute_playback", getString(R.string.mute_playback_title), muteSubtitle, null, null, null, null, null, "volume_off", null, null, null));
    // Row: Keep screen awake
    boolean keepOn = SharedPreferencesManager.getInstance(this).isPlayerKeepScreenOn();
    String keepOnSubtitle = keepOn ? getString(R.string.universal_enable) : getString(R.string.universal_disable);
    items.add(new OptionItem("row_keep_screen_on", getString(R.string.keep_screen_on_title), keepOnSubtitle, null, null, null, null, null, "visibility", null, null, null));
    // Row: Background playback
    boolean bg = SharedPreferencesManager.getInstance(this).isBackgroundPlaybackEnabled();
    String bgSubtitle = bg ? getString(R.string.universal_enable) : getString(R.string.universal_disable);
    items.add(new OptionItem("row_background_playback", getString(R.string.background_playback_title), bgSubtitle, null, null, null, null, null, "play_circle", null, null, null));
    // Row: Background playback timer
    int timerSec = SharedPreferencesManager.getInstance(this).getBackgroundPlaybackTimerSeconds();
    String timerSubtitle;
    if(timerSec==0) timerSubtitle = getString(R.string.timer_off_short);
    else if(timerSec<60) timerSubtitle = getString(R.string.timer_seconds_short, timerSec);
    else if(timerSec<3600) timerSubtitle = getString(R.string.timer_minutes_short, timerSec/60);
    else timerSubtitle = getString(R.string.timer_hours_short, timerSec/3600);
    boolean bgEnabled = SharedPreferencesManager.getInstance(this).isBackgroundPlaybackEnabled();
    items.add(new OptionItem("row_background_playback_timer", getString(R.string.background_playback_timer_title_short), timerSubtitle, null, null, null, null, null, "timer", null, null, !bgEnabled));
    // Row: Seek amount (in seconds)
    int seekSecMain = spm != null ? spm.getPlayerSeekSeconds() : Constants.DEFAULT_PLAYER_SEEK_SECONDS;
    items.add(new OptionItem("row_seek_amount", getString(R.string.seek_amount_title), getString(R.string.seek_amount_subtitle, seekSecMain), null, null, null, null, null, "replay_10", null, null, null));

    // Row: Audio waveform visualization
    boolean waveformEnabled = spm != null ? spm.isAudioWaveformEnabled() : true;
    items.add(new OptionItem("row_audio_waveform", "Audio Waveform", waveformEnabled ? "Enabled" : "Disabled", null, null, null, null, waveformEnabled, "graphic_eq", null, null, null));

    String helper = getString(R.string.video_player_settings_helper_player);
        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstance(getString(R.string.video_player_settings_title), items, null, RK_VIDEO_SETTINGS, helper);
    // Clear any previous listener for this request key to avoid duplicate/cross-sheet callbacks
    try{ getSupportFragmentManager().clearFragmentResultListener(RK_VIDEO_SETTINGS); } catch (Exception ignored) {}
    getSupportFragmentManager().setFragmentResultListener(RK_VIDEO_SETTINGS, this, (key, bundle) -> {
            String sel = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
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
                // Show timer picker directly from activity (mirrors VideoPlayerSettingsFragment implementation)
                final String RK = "rk_vps_background_playback_timer";
                java.util.ArrayList<OptionItem> itemsTimer = new java.util.ArrayList<>();
                itemsTimer.add(new OptionItem("t_off", getString(R.string.timer_off), null, null, null, null, null, null, "block", null, null, null));
                itemsTimer.add(new OptionItem("t_30s", getString(R.string.timer_30_seconds), null, null, null, null, null, null, "timer", null, null, null));
                itemsTimer.add(new OptionItem("t_1m", getString(R.string.timer_1_minute), null, null, null, null, null, null, "timer", null, null, null));
                itemsTimer.add(new OptionItem("t_5m", getString(R.string.timer_5_minutes), null, null, null, null, null, null, "timer", null, null, null));
                itemsTimer.add(new OptionItem("t_15m", getString(R.string.timer_15_minutes), null, null, null, null, null, null, "timer", null, null, null));
                itemsTimer.add(new OptionItem("t_30m", getString(R.string.timer_30_minutes), null, null, null, null, null, null, "timer", null, null, null));
                itemsTimer.add(new OptionItem("t_1h", getString(R.string.timer_1_hour), null, null, null, null, null, null, "timer", null, null, null));
                itemsTimer.add(new OptionItem("t_custom", getString(R.string.timer_custom_label), null, null, null, null, null, null, "edit", null, null, null));
                int cur = SharedPreferencesManager.getInstance(this).getBackgroundPlaybackTimerSeconds();
                String selId = "t_off";
                if (cur == 30) selId = "t_30s";
                else if (cur == 60) selId = "t_1m";
                else if (cur == 300) selId = "t_5m";
                else if (cur == 900) selId = "t_15m";
                else if (cur == 1800) selId = "t_30m";
                else if (cur == 3600) selId = "t_1h";
                else if (cur > 0) selId = "t_custom"; // show Custom checked for non-preset values
                PickerBottomSheetFragment sheetTimer = PickerBottomSheetFragment.newInstance(getString(R.string.background_playback_timer_title), itemsTimer, selId, RK, getString(R.string.background_playback_timer_helper));
                getSupportFragmentManager().setFragmentResultListener(RK, this, (rkRes, resBundle) -> {
                    String sel2 = resBundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                    if (sel2 == null) return;
                        if ("t_custom".equals(sel2)){
                        final String RK_NUM = "rk_vps_background_playback_timer_custom";
            com.fadcam.ui.picker.NumberInputBottomSheetFragment num = com.fadcam.ui.picker.NumberInputBottomSheetFragment.newInstance(
                getString(R.string.timer_custom_title), 1, 86400, 60, getString(R.string.timer_custom_placeholder), 5, 3600,
                getString(R.string.timer_custom_low_hint), getString(R.string.timer_custom_high_hint), RK_NUM);
            android.os.Bundle _b = num.getArguments()!=null? num.getArguments() : new android.os.Bundle();
            _b.putString(com.fadcam.ui.picker.NumberInputBottomSheetFragment.ARG_DESCRIPTION, getString(R.string.timer_custom_description));
            _b.putBoolean(com.fadcam.ui.picker.NumberInputBottomSheetFragment.ARG_ENABLE_TIMER_CALC, true);
            num.setArguments(_b);
                        // Add seek-specific description
                        android.os.Bundle _nb = num.getArguments()!=null? num.getArguments() : new android.os.Bundle();
                        _nb.putString(com.fadcam.ui.picker.NumberInputBottomSheetFragment.ARG_DESCRIPTION, getString(R.string.seek_amount_helper));
                        num.setArguments(_nb);
                        getSupportFragmentManager().setFragmentResultListener(RK_NUM, this, (rkN, nb) -> {
                            int minutes = nb.getInt(com.fadcam.ui.picker.NumberInputBottomSheetFragment.RESULT_NUMBER, 0);
                                    if(minutes>0){ int seconds = minutes * 60; SharedPreferencesManager.getInstance(this).setBackgroundPlaybackTimerSeconds(seconds);
                                        // Update inline subtitle if settings sheet is still visible
                                        View root = findViewById(android.R.id.content);
                                        if(root!=null){ TextView sub = root.findViewById(R.id.sub_background_playback_timer); if(sub!=null){ sub.setText(minutes<60? getString(R.string.timer_minutes_short, minutes): getString(R.string.timer_hours_short, minutes/60)); } }
                                    }
                        });
                        // Ensure the parent picker is dismissed before showing the numeric sheet to avoid view overlap
                        try{ if(sheetTimer != null && sheetTimer.isAdded()) sheetTimer.dismissAllowingStateLoss(); } catch(Exception ignored){}
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            try{ num.show(getSupportFragmentManager(), "video_background_playback_timer_custom_sheet"); } catch(Exception ignored){}
                        }, 200);
                        return;
                    }
                    int seconds = 0;
                    switch (sel2) {
                        case "t_30s": seconds = 30; break;
                        case "t_1m": seconds = 60; break;
                        case "t_5m": seconds = 300; break;
                        case "t_15m": seconds = 900; break;
                        case "t_30m": seconds = 1800; break;
                        case "t_1h": seconds = 3600; break;
                        default: seconds = 0; break;
                    }
                    SharedPreferencesManager.getInstance(this).setBackgroundPlaybackTimerSeconds(seconds);
                    View root = findViewById(android.R.id.content);
                    if(root!=null){ TextView sub = root.findViewById(R.id.sub_background_playback_timer); if(sub!=null){ if(seconds==0) sub.setText(getString(R.string.timer_off_short)); else if(seconds<60) sub.setText(getString(R.string.timer_seconds_short, seconds)); else if(seconds<3600) sub.setText(getString(R.string.timer_minutes_short, seconds/60)); else sub.setText(getString(R.string.timer_hours_short, seconds/3600)); } }
                });
                sheetTimer.show(getSupportFragmentManager(), "video_background_playback_timer_sheet");
            }
            else if ("row_seek_amount".equals(sel)){
                // Show seek amount picker directly in player settings
                final String RK = "rk_vps_seek_amount_activity";
                java.util.ArrayList<OptionItem> itemsSeek = new java.util.ArrayList<>();
                itemsSeek.add(new OptionItem("s_5", "5s", null, null, null, null, null, null, "replay_10", null, null, null));
                itemsSeek.add(new OptionItem("s_10", "10s", null, null, null, null, null, null, "replay_10", null, null, null));
                itemsSeek.add(new OptionItem("s_15", "15s", null, null, null, null, null, null, "replay_10", null, null, null));
                itemsSeek.add(new OptionItem("s_30", "30s", null, null, null, null, null, null, "replay_10", null, null, null));
                itemsSeek.add(new OptionItem("s_custom", getString(R.string.seek_amount_custom), null, null, null, null, null, null, "edit", null, null, null));
                int curS = SharedPreferencesManager.getInstance(this).getPlayerSeekSeconds();
                String selId = "s_" + curS; if(curS!=5 && curS!=10 && curS!=15 && curS!=30) selId = "s_custom";
                PickerBottomSheetFragment sheetSeek = PickerBottomSheetFragment.newInstance(getString(R.string.seek_amount_title), itemsSeek, selId, RK, getString(R.string.seek_amount_helper));
                try{ getSupportFragmentManager().clearFragmentResultListener(RK); } catch (Exception ignored) {}
                getSupportFragmentManager().setFragmentResultListener(RK, this, (rkRes, resBundle) -> {
                    String s = resBundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                    if(s==null) return;
                        if("s_custom".equals(s)){
                        final String RK_NUM = "rk_vps_seek_amount_custom_activity";
                        com.fadcam.ui.picker.NumberInputBottomSheetFragment num = com.fadcam.ui.picker.NumberInputBottomSheetFragment.newInstance(
                            getString(R.string.seek_amount_custom_title), 1, 300, 10, getString(R.string.universal_enter_number), 5, 60,
                            getString(R.string.seek_amount_custom_low_hint), getString(R.string.seek_amount_custom_high_hint), RK_NUM);
                        try{ getSupportFragmentManager().clearFragmentResultListener(RK_NUM); } catch (Exception ignored) {}
                        getSupportFragmentManager().setFragmentResultListener(RK_NUM, this, (rkN, nb) -> {
                            int val = nb.getInt(com.fadcam.ui.picker.NumberInputBottomSheetFragment.RESULT_NUMBER, 0);
                            if(val>0){ SharedPreferencesManager.getInstance(this).setPlayerSeekSeconds(val);
                                View root = findViewById(android.R.id.content);
                                if(root!=null){ TextView sub = root.findViewById(R.id.sub_seek_amount); if(sub!=null) sub.setText(getString(R.string.seek_amount_subtitle, val)); }
                            }
                        });
                        // Dismiss parent picker first to avoid UI overlap
                        try{ sheetSeek.dismissAllowingStateLoss(); } catch(Exception ignored){}
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            try{ num.show(getSupportFragmentManager(), "video_seek_amount_custom_sheet"); } catch(Exception ignored){}
                        }, 180);
                        return;
                    }
                    if(s.startsWith("s_")){
                        try{ int v = Integer.parseInt(s.substring(2)); SharedPreferencesManager.getInstance(this).setPlayerSeekSeconds(v);
                            View root = findViewById(android.R.id.content);
                            if(root!=null){ TextView sub = root.findViewById(R.id.sub_seek_amount); if(sub!=null) sub.setText(getString(R.string.seek_amount_subtitle, v)); }
                        }catch(NumberFormatException ignored){}
                    }
                });
                sheetSeek.show(getSupportFragmentManager(), "video_seek_amount_sheet");
            } else if ("row_audio_waveform".equals(sel)) {
                // Toggle audio waveform visibility
                showAudioWaveformSwitchSheet();
            }
        });
        sheet.show(getSupportFragmentManager(), "video_settings_sheet");
    }
    // -------------- Fix Ended for this method(showVideoSettingsSheet)-----------

    // -------------- Fix Start for this method(showPlaybackSpeedPickerSheet)-----------
    private void showPlaybackSpeedPickerSheet() {
        if (player == null) return;
        ArrayList<OptionItem> items = new ArrayList<>();
        for (int i = 0; i < speedValues.length; i++) {
            String id = "spd_" + speedValues[i];
            String title = String.valueOf(speedOptions[i]);
            items.add(new OptionItem(id, title, null, null, null, null, null, null, "speed", null, null, null));
        }
        String selectedId = "spd_" + speedValues[currentSpeedIndex];
    PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstance(getString(R.string.playback_speed_title), items, selectedId, RK_PLAYBACK_SPEED, getString(R.string.playback_speed_helper_player));
        getSupportFragmentManager().setFragmentResultListener(RK_PLAYBACK_SPEED, this, (key, bundle) -> {
            String selId = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (selId != null && selId.startsWith("spd_")) {
                try {
                    float val = Float.parseFloat(selId.substring(4));
                    int newIndex = currentSpeedIndex;
                    for (int i = 0; i < speedValues.length; i++) { if (Math.abs(speedValues[i] - val) < 0.001f) { newIndex = i; break; } }
                    currentSpeedIndex = newIndex;
                    player.setPlaybackParameters(new PlaybackParameters(val));
                    Log.i(TAG, "Playback speed set to: " + val + "x");
                } catch (NumberFormatException ignored) {}
            }
        });
        sheet.show(getSupportFragmentManager(), "playback_speed_sheet");
    }
    private void showMuteSwitchSheet(){
        final String RK = "rk_video_mute_switch";
        boolean enabled = SharedPreferencesManager.getInstance(this).isPlaybackMuted();
        getSupportFragmentManager().setFragmentResultListener(RK, this, (k,b)->{
            if(b.containsKey(PickerBottomSheetFragment.BUNDLE_SWITCH_STATE)){
                boolean state = b.getBoolean(PickerBottomSheetFragment.BUNDLE_SWITCH_STATE);
                SharedPreferencesManager.getInstance(this).setPlaybackMuted(state);
                applyMutedStateToPlayer(state);
                // Update subtitle in the main settings sheet if it's still visible
                // (the row subtitle in this sheet is static; we refresh when reopening)
            }
        });
    String helper = getString(R.string.mute_playback_helper_picker);
        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstanceWithSwitch(
                getString(R.string.mute_playback_title), new ArrayList<>(), null, RK, helper, getString(R.string.mute_playback_title), enabled);
        sheet.show(getSupportFragmentManager(), "video_mute_switch_sheet");
    }

    private void showKeepScreenOnSwitchSheet(){
        boolean enabled = SharedPreferencesManager.getInstance(this).isPlayerKeepScreenOn();
        getSupportFragmentManager().setFragmentResultListener(RK_KEEP_SCREEN_ON, this, (k,b)->{
            if(b.containsKey(PickerBottomSheetFragment.BUNDLE_SWITCH_STATE)){
                boolean state = b.getBoolean(PickerBottomSheetFragment.BUNDLE_SWITCH_STATE);
                SharedPreferencesManager.getInstance(this).setPlayerKeepScreenOn(state);
                // Apply immediately
                if (state) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            }
        });
        String helper = getString(R.string.keep_screen_on_helper_picker);
        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstanceWithSwitch(
                getString(R.string.keep_screen_on_title), new ArrayList<>(), null, RK_KEEP_SCREEN_ON, helper,
                getString(R.string.keep_screen_on_title), enabled);
        sheet.show(getSupportFragmentManager(), "video_keep_screen_on_switch_sheet");
    }

    private void showBackgroundPlaybackSwitchSheet(){
        boolean enabled = SharedPreferencesManager.getInstance(this).isBackgroundPlaybackEnabled();
        getSupportFragmentManager().setFragmentResultListener(RK_BACKGROUND_PLAYBACK, this, (k,b)->{
            if(b.containsKey(PickerBottomSheetFragment.BUNDLE_SWITCH_STATE)){
                boolean state = b.getBoolean(PickerBottomSheetFragment.BUNDLE_SWITCH_STATE);
                SharedPreferencesManager.getInstance(this).setBackgroundPlaybackEnabled(state);
                // If turned on and we're already playing, keep behavior is applied onPause via service handoff.
                // If turned off, ensure we stop background service when leaving.
            }
        });
        String helper = getString(R.string.background_playback_helper_picker);
    java.util.ArrayList<String> deps = new java.util.ArrayList<>(); deps.add("row_background_playback_timer");
    PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstanceWithSwitchDependencies(
        getString(R.string.background_playback_title), new ArrayList<>(), null, RK_BACKGROUND_PLAYBACK, helper,
        getString(R.string.background_playback_title), enabled, deps);
        sheet.show(getSupportFragmentManager(), "video_background_playback_switch_sheet");
    }

    private void applyMutedStateToPlayer(boolean muted){
        try{
            if(player!=null){ player.setVolume(muted? 0f: 1f); }
        }catch(Exception ignored){}
    }
    // -------------- Fix Ended for this method(showPlaybackSpeedPickerSheet)-----------

    // ----- Fix Start: Add resolveThemeColor helper -----
    private int resolveThemeColor(int attr) {
        android.util.TypedValue typedValue = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, typedValue, true);
        return typedValue.data;
    }
    // ----- Fix End: Add resolveThemeColor helper -----

    // --- Lifecycle Management ---
    @Override
    protected void onResume() {
        super.onResume();
        // Keep screen on during playback if enabled in settings
        boolean keepAwake = SharedPreferencesManager.getInstance(this).isPlayerKeepScreenOn();
        if (keepAwake) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        // Re-attach shared player to the view when coming to foreground
        if (playerView != null) {
            com.fadcam.playback.PlayerHolder holder = com.fadcam.playback.PlayerHolder.getInstance();
            ExoPlayer p = holder.getOrCreate(this);
            playerView.setPlayer(p);
        }
        // Optional: Resume playback if it was paused but ready (useful if app was backgrounded briefly)
        // Consider adding a check if user manually paused vs activity lifecycle pause
        if (player != null && player.getPlaybackState() == ExoPlayer.STATE_READY && !player.isPlaying()) {
            // player.play(); // Uncomment if you want auto-resume on activity resume
        }

        // -------------- Fix Start for this method(onResume)-----------
        // Request notification permission on Android 13+
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    androidx.core.app.ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1010);
                }
            }
        } catch (Exception ignored) {}

    // Do not start the background service while activity is in foreground; handoff occurs onPause only.
        // -------------- Fix Ended for this method(onResume)-----------
    }

    // Show a temporary overlay on double-tap side (reuse a simple TextView overlay in layout or create one)
    private void showDoubleTapOverlay(boolean forward) {
        try {
            View root = findViewById(android.R.id.content);
            if (root == null) return;
            // Always use a dedicated double-tap overlay view (do NOT reuse quickSpeedOverlay)
            View overlay = root.findViewWithTag("double_tap_overlay");
            if (overlay == null) {
                android.widget.TextView tv = new android.widget.TextView(this);
                tv.setTag("double_tap_overlay");
                // Match quick-speed styling: white bold text, 16sp, rounded dark background
                tv.setTextColor(android.graphics.Color.WHITE);
                tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
                // Use the same rounded background drawable used by quick speed overlay
                try { tv.setBackgroundResource(R.drawable.quick_speed_bg); } catch (Exception ignored) {}
                // Same content paddings as quick_speed_overlay in XML (12dp horizontal, 6dp vertical)
                int hpad = (int) (12 * getResources().getDisplayMetrics().density);
                int vpad = (int) (6 * getResources().getDisplayMetrics().density);
                tv.setPadding(hpad, vpad, hpad, vpad);
                tv.setGravity(android.view.Gravity.CENTER);
                android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
                // Equal side margins so left and right overlays are symmetric
                int sideMargin = (int) (16 * getResources().getDisplayMetrics().density);
                lp.setMarginStart(sideMargin);
                lp.setMarginEnd(sideMargin);
                lp.gravity = android.view.Gravity.CENTER_VERTICAL | (forward ? android.view.Gravity.END : android.view.Gravity.START);
                final android.widget.TextView tvf = tv;
                root.post(() -> ((android.widget.FrameLayout) root).addView(tvf, lp));
                overlay = tv;
            }
                final View overlayView = overlay;
            if (overlayView instanceof android.widget.TextView) {
                int seekSec = spm != null ? spm.getPlayerSeekSeconds() : Constants.DEFAULT_PLAYER_SEEK_SECONDS;
                ((android.widget.TextView) overlayView).setText(forward ? "+"+seekSec+"s" : "-"+seekSec+"s");
                // Ensure font size and weight match quick overlay
                ((android.widget.TextView) overlayView).setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
                ((android.widget.TextView) overlayView).setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            }
            // Position: update layout gravity so plus is on right, minus on left
            try {
                android.view.ViewParent p = overlayView.getParent();
                if (p instanceof android.widget.FrameLayout) {
                    android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) overlayView.getLayoutParams();
                    // Respect the created margins; update gravity and ensure symmetric margins
                    if (forward) lp.gravity = android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END;
                    else lp.gravity = android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START;
                    int sideMargin = (int) (16 * getResources().getDisplayMetrics().density);
                    lp.setMarginStart(sideMargin);
                    lp.setMarginEnd(sideMargin);
                    overlayView.setLayoutParams(lp);
                }
            } catch (Exception ignored) {}

            overlayView.setVisibility(View.VISIBLE);
            overlayView.setAlpha(0f);
            overlayView.animate().alpha(1f).setDuration(80).withEndAction(() -> overlayView.postDelayed(() -> overlayView.animate().alpha(0f).setDuration(250).withEndAction(() -> overlayView.setVisibility(View.GONE)), 500)).start();
        } catch (Exception ignored) {}
    }

    @Override
    protected void onPause() {
        super.onPause();
    // Allow screen to turn off when activity is paused
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Pause playback when activity goes into background/pause state
        // -------------- Fix Start for this method(onPause)-----------
        if (player != null) {
            boolean bg = SharedPreferencesManager.getInstance(this).isBackgroundPlaybackEnabled();
            boolean isPlaying = false;
            try { isPlaying = player.isPlaying(); } catch (Exception ignored) {}
            if (bg && isPlaying && hasPostNotificationsPermission()) {
                // Only handoff to background service if currently playing
                try {
            android.content.Intent svc = new android.content.Intent(this, com.fadcam.services.PlaybackService.class)
                            .setAction(com.fadcam.services.PlaybackService.ACTION_START)
                            .putExtra(com.fadcam.services.PlaybackService.EXTRA_URI, getIntent().getData())
                            .putExtra(com.fadcam.services.PlaybackService.EXTRA_SPEED, player.getPlaybackParameters().speed)
                            .putExtra(com.fadcam.services.PlaybackService.EXTRA_MUTED, player.getVolume() == 0f)
                .putExtra(com.fadcam.services.PlaybackService.EXTRA_POSITION_MS, player.getCurrentPosition())
                .putExtra(com.fadcam.services.PlaybackService.EXTRA_PLAY_WHEN_READY, true)
                .putExtra(com.fadcam.services.PlaybackService.EXTRA_FORCE_SHOW_NOTIFICATION, true);
            androidx.core.content.ContextCompat.startForegroundService(this, svc);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to start background playback service", e);
                }
            } else {
                // Not playing or background playback disabled: ensure playback is paused and service stopped
                try { if (isPlaying) player.pause(); } catch (Exception ignored) {}
                try {
                    android.content.Intent stop = new android.content.Intent(this, com.fadcam.services.PlaybackService.class)
                            .setAction(com.fadcam.services.PlaybackService.ACTION_STOP);
                    startService(stop);
                } catch (Exception ignored) {}
                Log.d(TAG, "Background service stopped (no background playback or paused).");
            }
        }
        // -------------- Fix Ended for this method(onPause)-----------
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
        
        super.onDestroy();
        // *** Release the player ***
    // Do not release the shared player here; service may be using it
    try { if (player != null) player.pause(); } catch (Exception ignored) {}
    player = null;
        // Stop background playback service when activity is destroyed (if pref disabled)
    try {
        android.content.Intent stop = new android.content.Intent(this, com.fadcam.services.PlaybackService.class)
            .setAction(com.fadcam.services.PlaybackService.ACTION_STOP);
        startService(stop);
    } catch (Exception ignored) {}
    }
    // --- End Lifecycle Management ---

    // ----- Fix Start: Add themedDialogBuilder helper -----
    private MaterialAlertDialogBuilder themedDialogBuilder() {
        int dialogTheme = R.style.ThemeOverlay_FadCam_Dialog;
        return new MaterialAlertDialogBuilder(this, dialogTheme);
    }
    // ----- Fix End: Add themedDialogBuilder helper -----

    // -------------- Fix Start for helper(hasPostNotificationsPermission)-----------
    /**
     * Returns true if either SDK < 33 or POST_NOTIFICATIONS permission is granted.
     */
    private boolean hasPostNotificationsPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return true;
        try {
            return androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }
    // -------------- Fix Ended for helper(hasPostNotificationsPermission)-----------
    
    // -------------- Fix Start for method(setupAnimatedControls)-----------
    private void setupAnimatedControls() {
        try {
            // Set up periodic waveform updates during playback
            setupWaveformUpdates();
            
            // Set up player state listener for waveform updates only
            if (player != null) {
                player.addListener(new com.google.android.exoplayer2.Player.Listener() {
                    @Override
                    public void onIsPlayingChanged(boolean isPlaying) {
                        updateWaveformProgress();
                    }
                });
            }
            
            // Set initial waveform visibility based on user preference
            com.fadcam.ui.custom.AudioWaveformView waveformView = playerView.findViewById(R.id.audio_waveform);
            if (waveformView != null) {
                boolean enabled = spm != null ? spm.isAudioWaveformEnabled() : true;
                waveformView.setVisibility(enabled ? View.VISIBLE : View.GONE);
                
                // Start real audio analysis if we have a video URI
                android.net.Uri currentUri = getCurrentVideoUri();
                if (currentUri != null) {
                    Log.d(TAG, "Starting real audio analysis for: " + currentUri);
                    waveformView.analyzeAudioFromVideo(currentUri);
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
                waveformHandler = new android.os.Handler(android.os.Looper.getMainLooper());
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
            
            com.fadcam.ui.custom.AudioWaveformView waveformView = playerView.findViewById(R.id.audio_waveform);
            if (waveformView != null && player != null && waveformView.getVisibility() == View.VISIBLE) {
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
    // -------------- Fix Ended for method(setupAnimatedControls)-----------
    
    // -------------- Fix Start for method(setupResetZoomButton)-----------
    private void setupResetZoomButton() {
        if (resetZoomButton != null) {
            resetZoomButton.setOnClickListener(v -> resetVideoTransform());
        }
    }
    // -------------- Fix Ended for method(setupResetZoomButton)-----------
    
    // -------------- Fix Start for method(applyVideoTransform)-----------
    private void applyVideoTransform() {
        if (playerView != null) {
            try {
                // Find the content frame to scale only video, not controls
                android.view.View contentFrame = playerView.findViewById(com.google.android.exoplayer2.ui.R.id.exo_content_frame);
                if (contentFrame != null) {
                    contentFrame.setScaleX(currentScale);
                    contentFrame.setScaleY(currentScale);
                    contentFrame.setTranslationX(currentTranslationX);
                    contentFrame.setTranslationY(currentTranslationY);
                } else {
                    // Fallback: try to find shutter or surface view
                    android.view.View shutter = playerView.findViewById(com.google.android.exoplayer2.ui.R.id.exo_shutter);
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
    // -------------- Fix Ended for method(applyVideoTransform)-----------
    
    // -------------- Fix Start for method(resetVideoTransform)-----------
    private void resetVideoTransform() {
        currentScale = 1.0f;
        currentTranslationX = 0f;
        currentTranslationY = 0f;
        
        if (playerView != null) {
            try {
                android.view.View contentFrame = playerView.findViewById(com.google.android.exoplayer2.ui.R.id.exo_content_frame);
                if (contentFrame != null) {
                    contentFrame.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .translationX(0f)
                        .translationY(0f)
                        .setDuration(200)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();
                } else {
                    android.view.View shutter = playerView.findViewById(com.google.android.exoplayer2.ui.R.id.exo_shutter);
                    if (shutter != null) {
                        shutter.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .translationX(0f)
                            .translationY(0f)
                            .setDuration(200)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .start();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error resetting video transform", e);
            }
        }
        
        updateResetZoomButtonVisibility();
    }
    // -------------- Fix Ended for method(resetVideoTransform)-----------
    
    // -------------- Fix Start for method(updateResetZoomButtonVisibility)-----------
    private void updateResetZoomButtonVisibility() {
        if (resetZoomButton != null) {
            if (Math.abs(currentScale - 1.0f) > 0.1f || Math.abs(currentTranslationX) > 10f || Math.abs(currentTranslationY) > 10f) {
                resetZoomButton.setVisibility(android.view.View.VISIBLE);
            } else {
                resetZoomButton.setVisibility(android.view.View.GONE);
            }
        }
    }
    // -------------- Fix Ended for method(updateResetZoomButtonVisibility)-----------
}
