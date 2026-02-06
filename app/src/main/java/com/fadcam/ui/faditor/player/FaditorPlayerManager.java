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
 * Handles single-clip playback with manual trim bounds (no ClippingConfiguration)
 * to support fragmented MP4 and SAF content:// URIs reliably.</p>
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

    // ── Manual trim bounds (replaces ClippingConfiguration) ──────────
    private long trimStartMs = 0;
    private long trimEndMs = Long.MAX_VALUE;

    /** Pending seek after player becomes READY (e.g. after prepare). */
    private long pendingSeekMs = -1;

    /** Whether the media source needs re-preparation (URI changed). */
    private boolean needsPrepare = true;

    /** Internal listener for handling pending seeks and playback state. */
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
                    Log.d(TAG, "Executing pending seek to " + pendingSeekMs + "ms (absolute)");
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
     * Load a clip for playback. No ClippingConfiguration — trim bounds
     * are managed manually via seek + position check so fragmented MP4
     * and non-seekable content:// sources work reliably.
     */
    public void loadClip(@NonNull Clip clip) {
        this.currentClip = clip;
        this.trimStartMs = clip.getInPointMs();
        this.trimEndMs = clip.getOutPointMs();
        this.needsPrepare = true;

        if (player == null) {
            initializePlayer();
        }
        preparePlayer(clip);
    }

    /**
     * Update trim bounds after handles are released.
     *
     * <p>Does NOT re-prepare the player. Simply updates internal bounds
     * and seeks to the start of the new trimmed region.</p>
     */
    public void updateTrimBounds(@NonNull Clip clip) {
        this.currentClip = clip;
        this.trimStartMs = clip.getInPointMs();
        this.trimEndMs = clip.getOutPointMs();

        if (player == null) return;

        // Seek to beginning of new trimmed region
        int state = player.getPlaybackState();
        if (state == Player.STATE_READY || state == Player.STATE_BUFFERING) {
            player.seekTo(trimStartMs);
            player.setPlayWhenReady(false);
            Log.d(TAG, "Trim bounds updated (seek): in=" + trimStartMs
                    + " out=" + trimEndMs
                    + " duration=" + (trimEndMs - trimStartMs) + "ms");
        } else {
            // Player not ready yet, queue the seek
            pendingSeekMs = trimStartMs;
            Log.d(TAG, "Trim bounds updated (queued): in=" + trimStartMs
                    + " out=" + trimEndMs);
        }
    }

    public void play() {
        if (player != null) {
            // Ensure we start from trim start if at/beyond trim end
            long pos = player.getCurrentPosition();
            if (pos < trimStartMs || pos >= trimEndMs) {
                player.seekTo(trimStartMs);
            }
            player.play();
        }
    }

    public void pause() {
        if (player != null) {
            player.pause();
        }
    }

    /**
     * Seek to a position (0-based within the trimmed region).
     * Internally converted to absolute position.
     */
    public void seekTo(long positionMs) {
        if (player == null) return;
        long absoluteMs = trimStartMs + positionMs;
        absoluteMs = Math.max(trimStartMs, Math.min(absoluteMs, trimEndMs));

        int state = player.getPlaybackState();
        if (state == Player.STATE_READY || state == Player.STATE_BUFFERING) {
            player.seekTo(absoluteMs);
            pendingSeekMs = -1;
            Log.d(TAG, "Seek to " + positionMs + "ms (rel) / " + absoluteMs + "ms (abs)");
        } else {
            pendingSeekMs = absoluteMs;
            Log.d(TAG, "Seek to " + positionMs + "ms (queued, state=" + state + ")");
        }
    }

    /**
     * Get current playback position relative to trim start (0-based).
     */
    public long getCurrentPosition() {
        if (player == null) return 0;
        long rawPos = player.getCurrentPosition();
        return Math.max(0, rawPos - trimStartMs);
    }

    /**
     * Get the raw ExoPlayer source duration (total, un-trimmed).
     * Returns the actual content duration as reported by ExoPlayer.
     */
    public long getSourceDuration() {
        return player != null ? player.getDuration() : 0;
    }

    /**
     * Get the trimmed duration (outPoint - inPoint).
     */
    public long getDuration() {
        return trimEndMs - trimStartMs;
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
     * Check if playback has reached the trim end point.
     * Call this periodically and pause if true.
     *
     * @return true if the player is at or beyond the trim end
     */
    public boolean isAtTrimEnd() {
        if (player == null) return false;
        long pos = player.getCurrentPosition();
        return pos >= trimEndMs;
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
            if (currentClip != null && needsPrepare) {
                preparePlayer(currentClip);
                if (lastPosition > 0) {
                    pendingSeekMs = trimStartMs + lastPosition;
                }
                player.setPlayWhenReady(playWhenReady);
            }

            Log.d(TAG, "ExoPlayer initialized");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize ExoPlayer", e);
        }
    }

    /**
     * Prepare the player with a plain MediaItem (no ClippingConfiguration).
     * Seeks to trimStartMs once ready.
     */
    private void preparePlayer(@NonNull Clip clip) {
        if (player == null) return;

        Log.d(TAG, "Preparing clip: uri=" + clip.getSourceUri()
                + " trimIn=" + trimStartMs
                + " trimOut=" + trimEndMs
                + " sourceDur=" + clip.getSourceDurationMs());

        // NO ClippingConfiguration — fragmented MP4 may not be seekable
        MediaItem mediaItem = new MediaItem.Builder()
                .setUri(clip.getSourceUri())
                .build();

        player.setMediaItem(mediaItem);
        player.prepare();
        needsPrepare = false;

        // Queue seek to trim start after prepare completes
        if (trimStartMs > 0) {
            pendingSeekMs = trimStartMs;
        }
    }

    private void releasePlayer() {
        if (player != null) {
            playWhenReady = player.getPlayWhenReady();
            lastPosition = Math.max(0, player.getCurrentPosition() - trimStartMs);
            player.removeListener(internalListener);
            player.release();
            player = null;
            pendingSeekMs = -1;
            needsPrepare = true;
            Log.d(TAG, "ExoPlayer released");
        }
    }
}
