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
    private ImageButton backButton;
    private ImageButton settingsButton; // For playback speed
    private TextView quickSpeedOverlay;
    private SharedPreferencesManager spm;
    // -------------- Fix Start for field(video_settings_result_keys)-----------
    private static final String RK_VIDEO_SETTINGS = "rk_video_settings";
    private static final String RK_PLAYBACK_SPEED = "rk_playback_speed";
    private static final String RK_QUICK_SPEED = "rk_quick_speed";
    private static final String RK_KEEP_SCREEN_ON = "rk_keep_screen_on";
    private static final String RK_BACKGROUND_PLAYBACK = "rk_background_playback";
    // -------------- Fix Ended for field(video_settings_result_keys)-----------

    // Playback speed options
    private final CharSequence[] speedOptions = {"0.5x", "1x (Normal)", "1.5x", "2x", "3x", "4x", "6x", "8x", "10x"};
    private final float[] speedValues = {0.5f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f, 6.0f, 8.0f, 10.0f};
    private int currentSpeedIndex = 1; // Index for 1.0x speed

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

        // *** FIX: Get the video URI using getData() ***
        Uri videoUri = getIntent().getData();

        if (videoUri != null) {
            Log.i(TAG, "Received video URI: " + videoUri.toString());
            spm = SharedPreferencesManager.getInstance(this);
            // Read default playback speed from prefs (if set) and adjust currentSpeedIndex
            float defSpd = sharedPreferencesManager.sharedPreferences.getFloat("pref_default_playback_speed", speedValues[currentSpeedIndex]);
            for(int i=0;i<speedValues.length;i++){ if(Math.abs(speedValues[i]-defSpd)<0.001f){ currentSpeedIndex = i; break; } }
            initializePlayer(videoUri); // Pass the Uri directly
            setupCustomSettingsAction();
            setupQuickSpeedSettings();
            setupPressAndHoldFor2x();
        } else {
            // Log error and finish if URI is missing
            Log.e(TAG, "Video URI is null. Intent Data: " + getIntent().getDataString() +". Cannot initialize player.");
            Toast.makeText(this, "Error: Video URI not found", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        setupBackButton();

        // ----- Fix Start: Programmatically set seekbar colors for dynamic theming -----
        // ExoPlayer's XML attributes may not always apply theme attributes at runtime, so set them here
        View timeBar = playerView.findViewById(com.google.android.exoplayer2.ui.R.id.exo_progress);
        if (timeBar instanceof com.google.android.exoplayer2.ui.DefaultTimeBar) {
            com.google.android.exoplayer2.ui.DefaultTimeBar bar = (com.google.android.exoplayer2.ui.DefaultTimeBar) timeBar;
            int played = resolveThemeColor(R.attr.colorButton);
            int unplayed = resolveThemeColor(R.attr.colorDialog);
            int buffered = resolveThemeColor(R.attr.colorHeading);
            int scrubber = resolveThemeColor(R.attr.colorButton);
            bar.setPlayedColor(played);
            bar.setUnplayedColor(unplayed);
            bar.setBufferedColor(buffered);
            bar.setScrubberColor(scrubber);
//            bar.setTouchTargetHeight((int) (getResources().getDisplayMetrics().density * 32));
        }
        // ----- Fix End: Programmatically set seekbar colors for dynamic theming -----
    }
    // Press-and-hold behavior: while pressed, ramp to quick speed; on release, revert to previous speed
    private void setupPressAndHoldFor2x() {
        if (playerView == null) return;
        quickSpeedOverlay = findViewById(R.id.quick_speed_overlay);
        final int LONG_PRESS_MS = 220; // threshold for long-press
        final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        final boolean[] isLongPress = {false};
        final Runnable longPressRunnable = () -> {
            isLongPress[0] = true;
            if (player != null) {
                float start = player.getPlaybackParameters().speed;
                float target = spm != null ? spm.getQuickSpeed() : 2.0f;
                animatePlaybackSpeed(start, target, 200);
                showQuickOverlay(true);
                playerView.hideController();
            }
        };

        playerView.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    isLongPress[0] = false;
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
                    break;
                case android.view.MotionEvent.ACTION_POINTER_DOWN:
                    // treat multi-touch as long-press
                    handler.post(longPressRunnable);
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_POINTER_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longPressRunnable);
                    if (isLongPress[0]) {
                        // Was a long-press; revert without showing controller
                        if (player != null) {
                            float revertSpeed = speedValues[currentSpeedIndex];
                            animatePlaybackSpeed(player.getPlaybackParameters().speed, revertSpeed, 200);
                        }
                        showQuickOverlay(false);
                        isLongPress[0] = false;
                        // Consume event to avoid controller showing due to tap release
                        return true;
                    }
                    // Not a long-press; allow normal behavior (taps show controller)
                    break;
            }
            return false;
        });
    }

    // Show a picker to set quick-speed via long-press on settings button
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
            player.play(); // Autoplay (prepared in holder if needed)
            Log.i(TAG, "ExoPlayer initialized and started for URI: " + videoUri);

        } catch (Exception e) { // Catch potential exceptions during initialization
            Log.e(TAG, "Error initializing player for URI: " + videoUri, e);
            Toast.makeText(this, "Error playing video", Toast.LENGTH_SHORT).show();
            finish(); // Close activity if player fails to initialize
        }
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
    String helper = getString(R.string.video_player_settings_helper_player);
        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstance(getString(R.string.video_player_settings_title), items, null, RK_VIDEO_SETTINGS, helper);
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
        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstanceWithSwitch(
                getString(R.string.background_playback_title), new ArrayList<>(), null, RK_BACKGROUND_PLAYBACK, helper,
                getString(R.string.background_playback_title), enabled);
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
    // Do not start background service while activity is in foreground; we'll handoff onPause if needed.
        // -------------- Fix Ended for this method(onResume)-----------
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
            android.content.Intent svc = new android.content.Intent(this, com.fadcam.services.BackgroundPlaybackService.class)
                            .setAction(com.fadcam.services.BackgroundPlaybackService.ACTION_START)
                            .putExtra(com.fadcam.services.BackgroundPlaybackService.EXTRA_URI, getIntent().getData())
                            .putExtra(com.fadcam.services.BackgroundPlaybackService.EXTRA_SPEED, player.getPlaybackParameters().speed)
                            .putExtra(com.fadcam.services.BackgroundPlaybackService.EXTRA_MUTED, player.getVolume() == 0f)
                .putExtra(com.fadcam.services.BackgroundPlaybackService.EXTRA_POSITION_MS, player.getCurrentPosition())
                .putExtra(com.fadcam.services.BackgroundPlaybackService.EXTRA_PLAY_WHEN_READY, true)
                .putExtra(com.fadcam.services.BackgroundPlaybackService.EXTRA_FORCE_SHOW_NOTIFICATION, true);
            androidx.core.content.ContextCompat.startForegroundService(this, svc);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to start background playback service", e);
                }
            } else {
                // Not playing or background playback disabled: ensure playback is paused and service stopped
                try { if (isPlaying) player.pause(); } catch (Exception ignored) {}
                try {
                    android.content.Intent stop = new android.content.Intent(this, com.fadcam.services.BackgroundPlaybackService.class)
                            .setAction(com.fadcam.services.BackgroundPlaybackService.ACTION_STOP);
                    startService(stop);
                } catch (Exception ignored) {}
                Log.d(TAG, "Background service stopped (no background playback or paused).");
            }
        }
        // -------------- Fix Ended for this method(onPause)-----------
    // Detach view to let service own playback surface-less
    if (playerView != null) playerView.setPlayer(null);
    }

    @Override
    protected void onDestroy() {
    // Clear screen flags again just in case
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onDestroy();
        // *** Release the player ***
    // Do not release the shared player here; service may be using it
    try { if (player != null) player.pause(); } catch (Exception ignored) {}
    player = null;
        // Stop background playback service when activity is destroyed (if pref disabled)
        try {
            android.content.Intent stop = new android.content.Intent(this, com.fadcam.services.BackgroundPlaybackService.class)
                    .setAction(com.fadcam.services.BackgroundPlaybackService.ACTION_STOP);
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
}
