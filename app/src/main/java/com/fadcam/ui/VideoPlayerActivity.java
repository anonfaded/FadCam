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
            player = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(player);

            // *** FIX: Use the received Uri directly ***
            MediaItem mediaItem = MediaItem.fromUri(videoUri);

            player.setMediaItem(mediaItem);
            // Set initial playback speed
            player.setPlaybackParameters(new PlaybackParameters(speedValues[currentSpeedIndex]));
            // Apply initial mute state from prefs
            boolean muted = SharedPreferencesManager.getInstance(this).isPlaybackMuted();
            try{ player.setVolume(muted? 0f: 1f); }catch(Exception ignored){}
            player.prepare();
            player.play(); // Autoplay
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
        // Keep screen on during playback
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Optional: Resume playback if it was paused but ready (useful if app was backgrounded briefly)
        // Consider adding a check if user manually paused vs activity lifecycle pause
        if (player != null && player.getPlaybackState() == ExoPlayer.STATE_READY && !player.isPlaying()) {
            // player.play(); // Uncomment if you want auto-resume on activity resume
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Allow screen to turn off when activity is paused
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Pause playback when activity goes into background/pause state
        if (player != null && player.isPlaying()) {
            player.pause();
            Log.d(TAG, "Player paused in onPause.");
        }
    }

    @Override
    protected void onDestroy() {
        // Clear screen flags again just in case
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onDestroy();
        // *** Release the player ***
        if (player != null) {
            player.release(); // Crucial to release resources
            player = null;
            Log.i(TAG, "ExoPlayer released.");
        }
    }
    // --- End Lifecycle Management ---

    // ----- Fix Start: Add themedDialogBuilder helper -----
    private MaterialAlertDialogBuilder themedDialogBuilder() {
        int dialogTheme = R.style.ThemeOverlay_FadCam_Dialog;
        return new MaterialAlertDialogBuilder(this, dialogTheme);
    }
    // ----- Fix End: Add themedDialogBuilder helper -----
}