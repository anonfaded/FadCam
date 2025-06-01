package com.fadcam.ui;

import android.net.Uri;
import android.os.Bundle;
import android.util.Log; // Use standard Android Log
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.Toast;
import android.widget.TextView;
import android.widget.ArrayAdapter;
import android.view.ViewGroup;

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


public class VideoPlayerActivity extends AppCompatActivity {

    private static final String TAG = "VideoPlayerActivity";

    private ExoPlayer player;
    private StyledPlayerView playerView;
    private ImageButton backButton;
    private ImageButton settingsButton; // For playback speed

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
            initializePlayer(videoUri); // Pass the Uri directly
            setupCustomSettingsAction();
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
                settingsButton.setOnClickListener(v -> showPlaybackSpeedDialog());
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

    // ----- Fix Start: Use themed dialog for playback speed with white text for radio items -----
    private void showPlaybackSpeedDialog() {
        if (player == null) {
            Log.e(TAG, "Player is null, cannot show speed dialog.");
            return;
        }
        if (playerView != null) {
            playerView.hideController(); // Hide controller during dialog interaction
        }

        // Custom ArrayAdapter to force white text for radio items
        int white = getResources().getColor(android.R.color.white);
        ArrayAdapter<CharSequence> adapter = new ArrayAdapter<CharSequence>(this, android.R.layout.simple_list_item_single_choice, speedOptions) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView text1 = view.findViewById(android.R.id.text1);
                if (text1 != null) text1.setTextColor(white);
                return view;
            }
        };

        themedDialogBuilder()
                .setTitle("Playback Speed")
                .setSingleChoiceItems(adapter, currentSpeedIndex, (dialog, which) -> {
                    currentSpeedIndex = which;
                    player.setPlaybackParameters(new PlaybackParameters(speedValues[which]));
                    Log.i(TAG, "Playback speed set to: " + speedOptions[which]);
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    if (playerView != null) playerView.showController();
                })
                .setOnDismissListener(dialog -> {
                    if (playerView != null) playerView.showController();
                })
                .show();
    }
    // ----- Fix End: Use themed dialog for playback speed with white text for radio items -----

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