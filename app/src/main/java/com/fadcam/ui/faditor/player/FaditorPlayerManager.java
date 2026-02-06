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

    /** Pending seek after player becomes READY (e.g. after trim bounds change). */
    private long pendingSeekMs = -1;

    /** Internal listener for handling pending seeks after prepare(). */
    private final Player.Listener internalListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int playbackState) {
            String stateStr;
            switch (playbackState) {
                case Player.STATE_IDLE: stateStr = "IDLE"; break;
                case Player.STATE_BUFFERING: stateStr = "BUFFERING"; break;
                case Player.STATE_READY: stateStr = "READY"; break;
                case Player.STATE_ENDED: stateStr = "ENDED"; break;
                default: stateStr = "UNKNOWN(" + playbackState + ")";
            }
            Log.d(TAG, "Playback state: " + stateStr + ", pendingSeek=" + pendingSeekMs);

            if (playbackState == Player.STATE_READY && pendingSeekMs >= 0) {
                if (player != null) {
                    Log.d(TAG, "Executing pending seek to " + pendingSeekMs + "ms");
                    player.seekTo(pendingSeekMs);
                }
                pendingSeekMs = -1;
            }
        }

        @Override
        public void onPlayerError(@NonNull androidx.media3.common.PlaybackException error) {
            Log.e(TAG, "Player error: " + error.getMessage()
                    + ", code=" + error.errorCode, error);
        }
    };

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
     * Update trim bounds after handles are released.
     *
     * <p>Pauses playback, reloads the clip with new clipping bounds,
     * and seeks to the start of the new trimmed region once ready.</p>
     */
    public void updateTrimBounds(@NonNull Clip clip) {
        this.currentClip = clip;
        if (player == null) return;

        // Save current playback state
        boolean wasPlaying = player.isPlaying();
        player.setPlayWhenReady(false);

        // Queue a seek-to-start once the new media is prepared
        pendingSeekMs = 0;
        applyClipToPlayer(clip);

        // After prepare, restore play if was playing
        if (wasPlaying) {
            player.setPlayWhenReady(true);
        }

        Log.d(TAG, "Trim bounds updated: in=" + clip.getInPointMs()
                + " out=" + clip.getOutPointMs()
                + " duration=" + (clip.getOutPointMs() - clip.getInPointMs()) + "ms");
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

    /**
     * Seek to a position (0-based within the clipped region).
     * If the player is not yet ready, the seek is queued.
     */
    public void seekTo(long positionMs) {
        if (player == null) return;
        int state = player.getPlaybackState();
        if (state == Player.STATE_READY || state == Player.STATE_BUFFERING) {
            player.seekTo(positionMs);
            pendingSeekMs = -1;
            Log.d(TAG, "Seek to " + positionMs + "ms (immediate)");
        } else {
            pendingSeekMs = positionMs;
            Log.d(TAG, "Seek to " + positionMs + "ms (queued, state="
                    + state + ")");
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
     * Check if the player is in a ready state for playback.
     */
    public boolean isReady() {
        return player != null && player.getPlaybackState() == Player.STATE_READY;
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
            player.addListener(internalListener);

            if (playerView != null) {
                playerView.setPlayer(player);
            }

            // Reload current clip if we had one
            if (currentClip != null) {
                applyClipToPlayer(currentClip);
                if (lastPosition > 0) {
                    pendingSeekMs = lastPosition;
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

        Log.d(TAG, "Applying clip: uri=" + clip.getSourceUri()
                + " in=" + clip.getInPointMs()
                + " out=" + clip.getOutPointMs()
                + " sourceDur=" + clip.getSourceDurationMs());

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
    }

    private void releasePlayer() {
        if (player != null) {
            playWhenReady = player.getPlayWhenReady();
            lastPosition = player.getCurrentPosition();
            player.removeListener(internalListener);
            player.release();
            player = null;
            pendingSeekMs = -1;
            Log.d(TAG, "ExoPlayer released");
        }
    }
}
