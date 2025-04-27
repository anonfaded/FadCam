package com.fadcam.ui;

import android.net.Uri;
import android.os.Bundle;
import android.widget.ImageButton;

import android.util.Log; // Add Log import
import android.view.WindowManager; // Add WindowManager import
import android.widget.Toast;

import androidx.annotation.NonNull; // Import NonNull if needed by override

import androidx.appcompat.app.AppCompatActivity;

import com.fadcam.R;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.ui.StyledPlayerView;

public class VideoPlayerActivity extends AppCompatActivity {
    
    // Add a TAG for logging (consistent with project rules)
    private static final String TAG = "VideoPlayerActivity";

    private ExoPlayer player;
    private StyledPlayerView playerView;
    private ImageButton backButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_player);

        playerView = findViewById(R.id.player_view);
        backButton = findViewById(R.id.back_button);

        String videoPath = getIntent().getStringExtra("VIDEO_PATH");
        if (videoPath != null) {
            initializePlayer(videoPath);
        } else {
            Log.e(TAG, "Video path is null. Cannot initialize player.");
            // Optionally show an error message or finish the activity
            finish(); // Finish if no video path is provided
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
            player.prepare();
            player.play();
            Log.d(TAG, "ExoPlayer initialized and started for path: " + videoPath);
        } catch (Exception e) {
            Log.e(TAG, "Error initializing player", e);
            // Show error to user or handle appropriately
            Toast.makeText(this, "Error playing video", Toast.LENGTH_SHORT).show(); // Example user feedback
            finish(); // Close activity if player fails
        }
    }

    private void setupBackButton() {
        backButton.setOnClickListener(v -> finish());
    }


    // --- Lifecycle Methods for Screen Awake ---

    @Override
    protected void onResume() {
        super.onResume();
        // Add the flag to keep the screen on when the activity is visible
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Log.d(TAG, "FLAG_KEEP_SCREEN_ON added.");
        // Resume playback if player exists and was paused (optional, depends on desired behavior)
        if (player != null && !player.isPlaying()) {
            // player.play(); // Uncomment if you want video to auto-resume
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Clear the flag when the activity is no longer in the foreground
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Log.d(TAG, "FLAG_KEEP_SCREEN_ON cleared.");
        // Pause playback when activity pauses (good practice)
        if (player != null && player.isPlaying()) {
            player.pause();
        }
    }

    // --- End Lifecycle Methods ---

    @Override
    protected void onDestroy() {
        // Ensure flag is cleared during destruction as a final cleanup
        // (Although onPause should have already cleared it)
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Log.d(TAG, "FLAG_KEEP_SCREEN_ON cleared in onDestroy as safety measure.");

        super.onDestroy();
        if (player != null) {
            player.release(); // Release player resources
            player = null;
            Log.d(TAG, "ExoPlayer released.");
        }
    }
}