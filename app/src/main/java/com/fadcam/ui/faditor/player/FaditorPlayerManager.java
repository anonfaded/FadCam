package com.fadcam.ui.faditor.player;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.fadcam.ui.faditor.model.Clip;

/**
 * Manages ExoPlayer lifecycle for the Faditor editor.
 *
 * <p>Binds to an Activity lifecycle to auto-pause on background and release on destroy.
 * Handles single-clip playback with ClippingConfiguration for trim preview.</p>
 */
public class FaditorPlayerManager implements DefaultLifecycleObserver {

    private static final String TAG = "FaditorPlayerManager";

    @Nullable
    private ExoPlayer player;

    @Nullable
    private PlayerView playerView;

    @NonNull
    private final Context context;

    @Nullable
    private Clip currentClip;

    private boolean playWhenReady = false;
    private long lastPosition = 0;

    public FaditorPlayerManager(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        initializePlayer();
    }

    @Override
    public void onResume(@NonNull LifecycleOwner owner) {
        if (player == null) {
            initializePlayer();
        }
    }

    @Override
    public void onPause(@NonNull LifecycleOwner owner) {
        if (player != null) {
            playWhenReady = player.getPlayWhenReady();
            lastPosition = player.getCurrentPosition();
            player.pause();
        }
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        releasePlayer();
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        releasePlayer();
    }

    // ── Public API ───────────────────────────────────────────────────

    /**
     * Bind to a PlayerView for rendering.
     */
    public void setPlayerView(@NonNull PlayerView view) {
        this.playerView = view;
        if (player != null) {
            view.setPlayer(player);
        }
    }

    /**
     * Load a clip for playback. Applies ClippingConfiguration for trim preview.
     */
    public void loadClip(@NonNull Clip clip) {
        this.currentClip = clip;
        if (player == null) {
            initializePlayer();
        }
        applyClipToPlayer(clip);
    }

    /**
     * Update trim bounds without reloading the entire clip.
     * Called when trim handles are dragged.
     */
    public void updateTrimBounds(@NonNull Clip clip) {
        this.currentClip = clip;
        if (player != null) {
            long currentPos = player.getCurrentPosition();
            applyClipToPlayer(clip);
            // Try to restore position within new bounds
            long clampedPos = Math.max(clip.getInPointMs(),
                    Math.min(currentPos, clip.getOutPointMs()));
            player.seekTo(clampedPos - clip.getInPointMs());
        }
    }

    public void play() {
        if (player != null) {
            player.play();
        }
    }

    public void pause() {
        if (player != null) {
            player.pause();
        }
    }

    public void seekTo(long positionMs) {
        if (player != null) {
            player.seekTo(positionMs);
        }
    }

    public long getCurrentPosition() {
        return player != null ? player.getCurrentPosition() : 0;
    }

    public long getDuration() {
        return player != null ? player.getDuration() : 0;
    }

    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    /**
     * Get the raw ExoPlayer instance (for advanced listeners).
     */
    @Nullable
    public ExoPlayer getPlayer() {
        return player;
    }

    /**
     * Add a Player.Listener for playback events.
     */
    public void addListener(@NonNull Player.Listener listener) {
        if (player != null) {
            player.addListener(listener);
        }
    }

    // ── Internal ─────────────────────────────────────────────────────

    private void initializePlayer() {
        if (player != null) return;

        try {
            player = new ExoPlayer.Builder(context).build();
            player.setRepeatMode(Player.REPEAT_MODE_OFF);

            if (playerView != null) {
                playerView.setPlayer(player);
            }

            // Reload current clip if we had one
            if (currentClip != null) {
                applyClipToPlayer(currentClip);
                if (lastPosition > 0) {
                    player.seekTo(lastPosition);
                }
                player.setPlayWhenReady(playWhenReady);
            }

            Log.d(TAG, "ExoPlayer initialized");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize ExoPlayer", e);
        }
    }

    private void applyClipToPlayer(@NonNull Clip clip) {
        if (player == null) return;

        MediaItem.ClippingConfiguration clipping = new MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(clip.getInPointMs())
                .setEndPositionMs(clip.getOutPointMs())
                .build();

        MediaItem mediaItem = new MediaItem.Builder()
                .setUri(clip.getSourceUri())
                .setClippingConfiguration(clipping)
                .build();

        player.setMediaItem(mediaItem);
        player.prepare();

        Log.d(TAG, "Clip loaded: " + clip);
    }

    private void releasePlayer() {
        if (player != null) {
            playWhenReady = player.getPlayWhenReady();
            lastPosition = player.getCurrentPosition();
            player.release();
            player = null;
            Log.d(TAG, "ExoPlayer released");
        }
    }
}
