package com.fadcam.ui.faditor.player;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.content.Context;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
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

    /** LoudnessEnhancer for volume amplification above 100%. */
    @Nullable
    private LoudnessEnhancer loudnessEnhancer;

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
            FLog.d(TAG, "Playback state: " + stateStr + ", pendingSeek=" + pendingSeekMs);

            if (playbackState == Player.STATE_READY && pendingSeekMs >= 0) {
                if (player != null) {
                    FLog.d(TAG, "Executing pending seek to " + pendingSeekMs + "ms (absolute)");
                    player.seekTo(pendingSeekMs);
                }
                pendingSeekMs = -1;
            }
        }

        @Override
        public void onPlayerError(@NonNull androidx.media3.common.PlaybackException error) {
            FLog.e(TAG, "Player error: " + error.getMessage()
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
            FLog.d(TAG, "Trim bounds updated (seek): in=" + trimStartMs
                    + " out=" + trimEndMs
                    + " duration=" + (trimEndMs - trimStartMs) + "ms");
        } else {
            // Player not ready yet, queue the seek
            pendingSeekMs = trimStartMs;
            FLog.d(TAG, "Trim bounds updated (queued): in=" + trimStartMs
                    + " out=" + trimEndMs);
        }
    }

    /**
     * Update ONLY the trim-end bound without seeking or pausing the player.
     *
     * <p>Used by duration correction so the corrected end-point is respected
     * going forward, without disrupting the current playback position.
     * Contrast with {@link #updateTrimBounds} which also seeks to trimStart.</p>
     *
     * @param newTrimEndMs the corrected out-point in absolute source milliseconds
     */
    public void updateTrimEndOnly(long newTrimEndMs) {
        this.trimEndMs = newTrimEndMs;
        if (currentClip != null) {
            this.currentClip.setOutPointMs(newTrimEndMs);
        }
        FLog.d(TAG, "Trim end updated (no seek): trimEnd=" + newTrimEndMs + "ms");
    }

    public void play() {
        if (player != null) {
            long pos = player.getCurrentPosition();
            // Restart from trim start only if strictly BEFORE start or AFTER end.
            // Using > (not >=) so that a position exactly at trimEndMs (which happens
            // after a split where the playhead sits on the cut boundary) does NOT
            // incorrectly reset to position 0.
            if (pos < trimStartMs || pos > trimEndMs) {
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
     * Set the player volume.
     *
     * <p>For values 0.0 – 1.0, uses ExoPlayer's native volume control.
     * For values above 1.0, sets native volume to 1.0 and uses
     * {@link LoudnessEnhancer} to amplify beyond 100% (like VLC).</p>
     *
     * @param volume 0.0 = muted, 1.0 = normal, 2.0 = 200%
     */
    public void setVolume(float volume) {
        if (player == null) return;

        float clampedVolume = Math.max(0f, volume);

        if (clampedVolume <= 1.0f) {
            // Normal range: use ExoPlayer's native volume
            player.setVolume(clampedVolume);
            setLoudnessGain(0);
        } else {
            // Above 100%: max out native volume, use LoudnessEnhancer for extra gain
            player.setVolume(1.0f);
            // Convert volume factor to gain in millibels: dB = 20*log10(v), mB = dB*100
            int gainMb = (int) (2000.0 * Math.log10(clampedVolume));
            setLoudnessGain(gainMb);
        }
        FLog.d(TAG, "Volume set to " + volume
                + " (native=" + Math.min(clampedVolume, 1.0f)
                + ", loudnessGainMb=" + (clampedVolume > 1.0f
                    ? (int) (2000.0 * Math.log10(clampedVolume)) : 0) + ")");
    }

    /**
     * Apply loudness gain via {@link LoudnessEnhancer}.
     * Creates the enhancer lazily on first use.
     *
     * @param gainMb gain in millibels (0 = no boost)
     */
    private void setLoudnessGain(int gainMb) {
        if (player == null) return;

        try {
            if (loudnessEnhancer == null) {
                int audioSessionId = player.getAudioSessionId();
                if (audioSessionId == 0) {
                    FLog.w(TAG, "Audio session ID is 0, cannot create LoudnessEnhancer");
                    return;
                }
                loudnessEnhancer = new LoudnessEnhancer(audioSessionId);
            }
            loudnessEnhancer.setTargetGain(gainMb);
            loudnessEnhancer.setEnabled(gainMb > 0);
        } catch (Exception e) {
            FLog.e(TAG, "Failed to set loudness gain", e);
        }
    }

    /**
     * Set the playback speed for preview (does not affect export).
     *
     * @param speed multiplier (e.g. 0.5 = half speed, 2.0 = double speed)
     */
    public void setPlaybackSpeed(float speed) {
        if (player != null) {
            player.setPlaybackParameters(
                    new androidx.media3.common.PlaybackParameters(speed));
            FLog.d(TAG, "Playback speed set to " + speed + "x");
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
        // Seek is valid in READY, BUFFERING, and ENDED.
        // In ENDED, calling seekTo() revives the player back to BUFFERING → READY
        // so the new frame renders.  Previously we only queued a pendingSeekMs here,
        // which never fired (STATE_READY never fires again from ENDED without a new
        // seekTo), leaving the player stuck at the last frame and causing
        // "plays from 0" when play() was pressed after the user scrubbed.
        if (state == Player.STATE_READY
                || state == Player.STATE_BUFFERING
                || state == Player.STATE_ENDED) {
            player.seekTo(absoluteMs);
            pendingSeekMs = -1;
            FLog.d(TAG, "Seek to " + positionMs + "ms (rel) / " + absoluteMs + "ms (abs)"
                    + " state=" + state);
        } else {
            pendingSeekMs = absoluteMs;
            FLog.d(TAG, "Seek to " + positionMs + "ms (queued, state=" + state + ")");
        }
    }

    /**
     * Seek to an absolute position in the source video.
     * Used for live scrub/trim preview where the position is already
     * in absolute terms (not relative to trim start).
     *
     * @param absoluteMs absolute position in the source video (milliseconds)
     */
    public void seekToAbsolute(long absoluteMs) {
        if (player == null) return;
        absoluteMs = Math.max(0, absoluteMs);

        int state = player.getPlaybackState();
        if (state == Player.STATE_READY
                || state == Player.STATE_BUFFERING
                || state == Player.STATE_ENDED) {
            player.seekTo(absoluteMs);
            pendingSeekMs = -1;
        } else {
            pendingSeekMs = absoluteMs;
            FLog.d(TAG, "Seek absolute queued: " + absoluteMs + "ms (state=" + state + ")");
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
     * Returns whether the player will play when ready (user intent to play).
     * More reliable than isPlaying() which returns false during buffering.
     */
    public boolean getPlayWhenReady() {
        return player != null && player.getPlayWhenReady();
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
            // Use keyframe-accurate seeking for scrubbing: jumps to the nearest
            // sync frame instantly instead of decoding every intermediate frame.
            // This makes live preview during timeline drag fast and responsive.
            player.setSeekParameters(SeekParameters.CLOSEST_SYNC);
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

                // Re-apply volume from clip state.  A newly created ExoPlayer
                // defaults to volume 1.0 — if the clip was muted (e.g. after audio
                // extraction) we must enforce that here, otherwise the user hears
                // double audio after a lifecycle resume.
                float vol = currentClip.isAudioMuted() ? 0f : currentClip.getVolumeLevel();
                setVolume(vol);
                FLog.d(TAG, "Restored volume from clip state: vol=" + vol
                        + ", muted=" + currentClip.isAudioMuted());
            }

            FLog.d(TAG, "ExoPlayer initialized");
        } catch (Exception e) {
            FLog.e(TAG, "Failed to initialize ExoPlayer", e);
        }
    }

    /**
     * Prepare the player with a plain MediaItem (no ClippingConfiguration).
     * Seeks to trimStartMs once ready.
     *
     * <p>Converts content:// URIs to file:// when possible so ExoPlayer
     * uses {@code FileDataSource} (supports random-access seeking) instead
     * of {@code ContentDataSource} (which cannot seek in fMP4).</p>
     */
    private void preparePlayer(@NonNull Clip clip) {
        if (player == null) return;

        Uri sourceUri = clip.getSourceUri();
        Uri resolvedUri = resolveFileUri(sourceUri);

        FLog.d(TAG, "Preparing clip: originalUri=" + sourceUri
                + " resolvedUri=" + resolvedUri
                + " trimIn=" + trimStartMs
                + " trimOut=" + trimEndMs
                + " sourceDur=" + clip.getSourceDurationMs());

        MediaItem mediaItem = new MediaItem.Builder()
                .setUri(resolvedUri)
                .build();

        player.setMediaItem(mediaItem);
        player.prepare();
        needsPrepare = false;

        // Queue seek to trim start after prepare completes
        if (trimStartMs > 0) {
            pendingSeekMs = trimStartMs;
        }
    }

    /**
     * Resolve a content:// URI to a file:// URI when possible.
     *
     * <p>SAF content:// URIs use {@code ContentDataSource} in ExoPlayer,
     * which does NOT support random-access seeking. For fragmented MP4
     * recorded by FadCam, this means seeks silently fail. Converting to
     * file:// enables {@code FileDataSource} with proper seeking.</p>
     *
     * <p>Handles both internal storage ("primary" → /storage/emulated/0)
     * and SD card ("ABCD-1234" → /storage/ABCD-1234) mount points.</p>
     *
     * @param uri the original URI (may be content://, file://, or other)
     * @return file:// URI if the file exists on disk, otherwise the original URI
     */
    @NonNull
    private Uri resolveFileUri(@NonNull Uri uri) {
        // Already a file URI — nothing to do
        if ("file".equals(uri.getScheme())) {
            return uri;
        }

        // Parse SAF content:// URI — handles both primary and SD card storage IDs
        // URI path format: /document/STORAGE_ID:relative/path/to/file.mp4
        // STORAGE_ID is "primary" for internal storage or e.g. "ABCD-1234" for SD card
        String uriPath = uri.getPath();
        if (uriPath != null && uriPath.contains(":")) {
            try {
                int lastColon = uriPath.lastIndexOf(':');
                String encodedRel = uriPath.substring(lastColon + 1);
                String relativePath = java.net.URLDecoder.decode(encodedRel, "UTF-8");

                // Storage ID is the path segment immediately before the colon
                String beforeColon = uriPath.substring(0, lastColon);
                int lastSlash = beforeColon.lastIndexOf('/');
                String encodedId = lastSlash >= 0
                        ? beforeColon.substring(lastSlash + 1) : beforeColon;
                String storageId = java.net.URLDecoder.decode(encodedId, "UTF-8");

                // Map storage ID → mount point
                String mountPoint = "primary".equalsIgnoreCase(storageId)
                        ? "/storage/emulated/0"
                        : "/storage/" + storageId;

                java.io.File f = new java.io.File(mountPoint + "/" + relativePath);
                if (f.exists() && f.canRead()) {
                    Uri fileUri = Uri.fromFile(f);
                    FLog.d(TAG, "Resolved content:// → file:// (storageId='" + storageId + "'): " + fileUri);
                    return fileUri;
                } else {
                    FLog.w(TAG, "Resolved path does not exist: " + f.getAbsolutePath());
                }
            } catch (Exception e) {
                FLog.w(TAG, "Failed to parse SAF URI path: " + uri, e);
            }
        }

        // Fallback: use original URI (ContentDataSource, limited seeking)
        FLog.w(TAG, "Cannot resolve to file URI, using content:// (seeking may fail): " + uri);
        return uri;
    }

    private void releasePlayer() {
        if (loudnessEnhancer != null) {
            try {
                loudnessEnhancer.release();
            } catch (Exception e) {
                FLog.w(TAG, "Failed to release LoudnessEnhancer", e);
            }
            loudnessEnhancer = null;
        }
        if (player != null) {
            playWhenReady = player.getPlayWhenReady();
            lastPosition = Math.max(0, player.getCurrentPosition() - trimStartMs);
            player.removeListener(internalListener);
            player.release();
            player = null;
            pendingSeekMs = -1;
            needsPrepare = true;
            FLog.d(TAG, "ExoPlayer released");
        }
    }
}
