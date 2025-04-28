package com.fadcam.ui;

import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.ui.R; // Use ExoPlayer UI R

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ui.StyledPlayerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;


public class VideoPlayerActivity extends AppCompatActivity {

    private static final String TAG = "VideoPlayerActivity";

    private ExoPlayer player;
    private StyledPlayerView playerView;
    private ImageButton backButton;
    private ImageButton settingsButton;
    // REMOVED: ffwdButton, rewButton declarations

    private final CharSequence[] speedOptions = {"0.5x", "1x (Normal)", "1.5x", "2x", "3x", "4x", "6x", "8x", "10x"};
    private final float[] speedValues = {0.5f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f, 6.0f, 8.0f, 10.0f};
    private int currentSpeedIndex = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Use app's R file for app layouts/ids
        setContentView(com.fadcam.R.layout.activity_video_player);

        playerView = findViewById(com.fadcam.R.id.player_view);
        backButton = findViewById(com.fadcam.R.id.back_button);

        String videoPath = getIntent().getStringExtra("VIDEO_PATH");
        if (videoPath != null) {
            initializePlayer(videoPath);
            setupCustomSettingsAction();
            // REMOVED: call to setupCustomSeekActions();
        } else {
            Log.e(TAG, "Video path is null. Cannot initialize player.");
            Toast.makeText(this, "Error: Video not found", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setupBackButton();
    }

    private void initializePlayer(String videoPath) {
        try {
            player = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(player);

            MediaItem mediaItem = MediaItem.fromUri(Uri.parse(videoPath));
            player.setMediaItem(mediaItem);
            player.setPlaybackParameters(new PlaybackParameters(speedValues[currentSpeedIndex]));
            player.prepare();
            player.play();
            Log.d(TAG, "ExoPlayer initialized and started for path: " + videoPath);
        } catch (Exception e) {
            Log.e(TAG, "Error initializing player", e);
            Toast.makeText(this, "Error playing video", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void setupBackButton() {
        backButton.setOnClickListener(v -> finish());
    }

    private void setupCustomSettingsAction() {
        if (playerView != null) {
            // Use ExoPlayer UI Library's R file for the internal ID
            settingsButton = playerView.findViewById(R.id.exo_settings);
            if (settingsButton != null) {
                settingsButton.setOnClickListener(v -> showPlaybackSpeedDialog());
                Log.d(TAG, "Manual settings button listener attached.");
            } else {
                Log.w(TAG, "Could not find settings button (@id/exo_settings) in PlayerView.");
            }
        } else {
            Log.w(TAG, "PlayerView is null, cannot attach settings button listener.");
        }
    }

    // REMOVED: setupCustomSeekActions() method

    private void showPlaybackSpeedDialog() {
        if (player == null) {
            Log.e(TAG, "Player is null, cannot show speed dialog.");
            return;
        }
        if (playerView != null) {
            playerView.hideController();
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("Playback Speed")
                .setSingleChoiceItems(speedOptions, currentSpeedIndex, (dialog, which) -> {
                    currentSpeedIndex = which;
                    player.setPlaybackParameters(new PlaybackParameters(speedValues[which]));
                    Log.d(TAG, "Playback speed set to: " + speedOptions[which]);
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // onResume, onPause, onDestroy remain the same
    @Override
    protected void onResume() {
        super.onResume();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (player != null && !player.isPlaying() && player.getPlaybackState() == ExoPlayer.STATE_READY) {
            // player.play();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (player != null && player.isPlaying()) {
            player.pause();
            Log.d(TAG,"Player paused in onPause.");
        }
    }

    @Override
    protected void onDestroy() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
            Log.d(TAG, "ExoPlayer released.");
        }
    }
}