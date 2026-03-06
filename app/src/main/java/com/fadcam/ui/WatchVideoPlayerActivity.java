package com.fadcam.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.fadcam.R;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Minimal watch-optimised video player.
 *
 * <p>Accepts the video URI via the {@code "extra_uri"} string extra (same key as
 * {@link VideoPlayerActivity}) so the caller does not need to know which player
 * implementation is being used.</p>
 *
 * <p>Controls overlay fades in on tap and auto-hides after 3 seconds.
 * Back button and play/pause are the only interactive elements.</p>
 */
public class WatchVideoPlayerActivity extends AppCompatActivity {

    private static final String TAG = "WatchVideoPlayer";

    /** Same intent key used by VideoPlayerActivity for cross-compatibility. */
    public static final String EXTRA_URI = "extra_uri";

    private static final long CONTROLS_HIDE_DELAY_MS = 3_000L;

    // ── ExoPlayer ───────────────────────────────────────────────────────────

    private ExoPlayer player;

    // ── UI ──────────────────────────────────────────────────────────────────

    private PlayerView playerView;
    private View controlsOverlay;
    private ImageView ivPlayPause;
    private SeekBar seekBar;
    private TextView tvPosition;
    private TextView tvTitle;

    // ── State ───────────────────────────────────────────────────────────────

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideControlsRunnable = this::hideControls;
    private boolean controlsVisible = false;
    /** True while user is dragging the seek bar — pause position updates. */
    private boolean seekBarDragging = false;

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen on while playing
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_watch_video_player);

        playerView     = findViewById(R.id.watch_player_view);
        controlsOverlay = findViewById(R.id.watch_player_controls);
        ivPlayPause    = findViewById(R.id.watch_player_play_pause);
        seekBar        = findViewById(R.id.watch_player_seek);
        tvPosition     = findViewById(R.id.watch_player_position);
        tvTitle        = findViewById(R.id.watch_player_title);
        final View ivBack = findViewById(R.id.watch_player_back);

        // Back button
        ivBack.setOnClickListener(v -> finish());

        // Play / pause toggle
        ivPlayPause.setOnClickListener(v -> {
            if (player == null) return;
            if (player.isPlaying()) {
                player.pause();
            } else {
                player.play();
            }
            scheduleHideControls();
        });

        // Seek bar
        if (seekBar != null) {
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && player != null) {
                        final long dur = player.getDuration();
                        if (dur > 0) {
                            player.seekTo(dur * progress / 1000L);
                        }
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {
                    seekBarDragging = true;
                    cancelHideControls(); // keep controls visible while dragging
                }
                @Override public void onStopTrackingTouch(SeekBar seekBar) {
                    seekBarDragging = false;
                    scheduleHideControls();
                }
            });
        }

        // Tap anywhere on player → show/hide controls
        playerView.setOnClickListener(v -> {
            if (controlsVisible) {
                hideControls();
            } else {
                showControls();
            }
        });

        // Extract URI from intent
        final String uriString = getIntent().getStringExtra(EXTRA_URI);
        if (uriString == null || uriString.isEmpty()) {
            Log.e(TAG, "No URI to play — finishing");
            Toast.makeText(this, "No video URI", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Log.d(TAG, "Playing video: " + uriString);

        // Derive short title from URI path
        final String rawPath = Uri.parse(uriString).getLastPathSegment();
        if (tvTitle != null && rawPath != null) {
            final int dotPos = rawPath.lastIndexOf('.');
            tvTitle.setText(dotPos > 0 ? rawPath.substring(0, dotPos) : rawPath);
        }

        try {
            initPlayer(Uri.parse(uriString));
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize player: " + e.getMessage(), e);
            Toast.makeText(this, "Cannot play video: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }

        // Show controls briefly at startup
        showControls();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (player != null) {
            player.play();
            Log.d(TAG, "Player started");
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) {
            player.pause();
            Log.d(TAG, "Player paused");
        }
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacks(hideControlsRunnable);
        releasePlayer();
        super.onDestroy();
    }

    // ── Player ──────────────────────────────────────────────────────────────

    private void initPlayer(@NonNull Uri uri) {
        try {
            Log.d(TAG, "Initializing player with URI: " + uri);
            Log.d(TAG, "URI scheme: " + uri.getScheme());
            Log.d(TAG, "URI path: " + uri.getPath());
            
            player = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(player);

            // Create MediaItem from URI
            MediaItem mediaItem = MediaItem.fromUri(uri);
            player.setMediaItem(mediaItem);
            player.prepare();
            player.setPlayWhenReady(true);

            Log.d(TAG, "Player initialized, preparing...");

            player.addListener(new Player.Listener() {
                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    Log.d(TAG, "Playing state changed: " + isPlaying);
                    updatePlayPauseButton(isPlaying);
                }

                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    String stateStr;
                    switch (playbackState) {
                        case Player.STATE_IDLE: stateStr = "IDLE"; break;
                        case Player.STATE_BUFFERING: stateStr = "BUFFERING"; break;
                        case Player.STATE_READY: stateStr = "READY"; break;
                        case Player.STATE_ENDED: stateStr = "ENDED"; break;
                        default: stateStr = "UNKNOWN"; break;
                    }
                    Log.d(TAG, "Playback state: " + stateStr);
                    
                    if (playbackState == Player.STATE_READY) {
                        Log.d(TAG, "Player ready! Duration: " + player.getDuration() + "ms");
                    } else if (playbackState == Player.STATE_ENDED) {
                        showControls();
                    }
                }

                @Override
                public void onPlayerError(@NonNull androidx.media3.common.PlaybackException error) {
                    Log.e(TAG, "Player error: " + error.getMessage() + ", errorCode: " + error.errorCode);
                    Toast.makeText(WatchVideoPlayerActivity.this, 
                        "Playback error: " + error.getMessage(), 
                        Toast.LENGTH_LONG).show();
                }
            });

            // Update position label every 500 ms
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    updatePositionLabel();
                    if (!seekBarDragging) updateSeekBar();
                    uiHandler.postDelayed(this, 500);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize player: " + e.getMessage(), e);
            throw e;
        }
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    // ── Controls ─────────────────────────────────────────────────────────────

    private void showControls() {
        cancelHideControls();
        controlsOverlay.animate()
                .alpha(1f)
                .setDuration(180)
                .setListener(null)
                .start();
        controlsOverlay.setVisibility(View.VISIBLE);
        controlsVisible = true;
        scheduleHideControls();
    }

    private void hideControls() {
        controlsOverlay.animate()
                .alpha(0f)
                .setDuration(250)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        controlsOverlay.setVisibility(View.GONE);
                        controlsVisible = false;
                    }
                })
                .start();
    }

    private void scheduleHideControls() {
        cancelHideControls();
        uiHandler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY_MS);
    }

    private void cancelHideControls() {
        uiHandler.removeCallbacks(hideControlsRunnable);
    }

    private void updatePlayPauseButton(boolean playing) {
        if (ivPlayPause == null) return;
        ivPlayPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private void updatePositionLabel() {
        if (player == null || tvPosition == null) return;
        final long pos = player.getCurrentPosition();
        final long dur = player.getDuration();
        tvPosition.setText(formatTime(pos) + " / " + (dur > 0 ? formatTime(dur) : "--:--"));
    }

    private void updateSeekBar() {
        if (player == null || seekBar == null) return;
        final long pos = player.getCurrentPosition();
        final long dur = player.getDuration();
        if (dur > 0) {
            seekBar.setProgress((int) (pos * 1000L / dur));
        }
    }

    @NonNull
    private static String formatTime(long ms) {
        final long minutes = TimeUnit.MILLISECONDS.toMinutes(ms);
        final long seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60;
        if (minutes >= 60) {
            return String.format(Locale.US, "%d:%02d:%02d",
                    TimeUnit.MILLISECONDS.toHours(ms), minutes % 60, seconds);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }
}
