package com.fadcam.ui.faditor.composition;

import android.content.Context;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.CompositionPlayer;

import com.fadcam.FLog;

/**
 * Hosts the Media3 {@link CompositionPlayer} used to preview a {@link Composition}.
 *
 * <p>Player positions are already project-timeline positions, so callers pass and read
 * milliseconds directly. Keeping the player and its output surface behind this class
 * isolates the experimental player API to one file.</p>
 */
public final class FaditorCompositionPlayer {

    private static final String TAG = "FaditorCompositionPlayer";

    @Nullable
    private CompositionPlayer player;

    @Nullable
    private Composition composition;

    @Nullable
    private SurfaceView surfaceView;

    private boolean released;

    private final Context context;

    public FaditorCompositionPlayer(@NonNull Context context) {
        // The player outlives configuration changes; hold the application context.
        this.context = context.getApplicationContext();
        // Media3's EventLogger writes every seek/state change at DEBUG. Keep warnings and
        // errors, which is all the editor needs in normal operation.
        androidx.media3.common.util.Log.setLogLevel(
                androidx.media3.common.util.Log.LOG_LEVEL_WARNING);
    }

    /** Creates the player lazily; safe to call repeatedly. */
    @Nullable
    public CompositionPlayer getPlayer() {
        if (released) return null;
        if (player == null) {
            try {
                player = new CompositionPlayer.Builder(context)
                        // Replayable cache makes paused seeks and effect rebuilds render
                        // the exact frame instead of leaving an old one on screen.
                        .experimentalSetEnableReplayableCache(true)
                        .build();
                if (surfaceView != null) {
                    player.setVideoSurfaceView(surfaceView);
                }
                FLog.d(TAG, "CompositionPlayer created");
            } catch (Exception e) {
                FLog.e(TAG, "Failed to create CompositionPlayer", e);
                return null;
            }
        }
        return player;
    }

    /**
     * Attaches the surface the player renders into. The player owns the holder callbacks
     * and follows the view's lifecycle from here.
     */
    public void bindSurfaceView(@NonNull SurfaceView view) {
        if (surfaceView == view) return;
        unbindSurfaceView();
        surfaceView = view;
        if (player != null) player.setVideoSurfaceView(view);
    }

    /** Detaches the surface and clears the player output. */
    public void unbindSurfaceView() {
        if (surfaceView != null && player != null) {
            player.setVideoSurfaceView(null);
        }
        surfaceView = null;
    }

    /**
     * Replaces the played composition.
     *
     * @param composition the composition to present, or null to clear
     * @param startMs     timeline position to resume from
     * @param play        true to start playing immediately
     */
    public void setComposition(@Nullable Composition composition, long startMs, boolean play) {
        this.composition = composition;
        CompositionPlayer p = getPlayer();
        if (p == null) return;
        if (composition == null) {
            p.stop();
            p.clearMediaItems();
            return;
        }
        p.setComposition(composition);
        p.prepare();
        if (startMs > 0) p.seekTo(startMs);
        p.setPlayWhenReady(play);
        if (!play) redraw();
    }

    /** The composition currently loaded, if any. */
    @Nullable
    public Composition getComposition() {
        return composition;
    }

    /** Current position in project-timeline milliseconds. */
    public long getPositionMs() {
        return player != null ? Math.max(0L, player.getCurrentPosition()) : 0L;
    }

    /** Total presented duration reported by the player, or 0 when unknown. */
    public long getDurationMs() {
        if (player == null) return 0L;
        long duration = player.getDuration();
        return duration == androidx.media3.common.C.TIME_UNSET ? 0L : Math.max(0L, duration);
    }

    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    /** True while playback is requested, including the buffering window. */
    public boolean isPlayingOrBuffering() {
        if (player == null) return false;
        return player.isPlaying()
                || (player.getPlayWhenReady()
                && player.getPlaybackState() == Player.STATE_BUFFERING);
    }

    public boolean getPlayWhenReady() {
        return player != null && player.getPlayWhenReady();
    }

    /** Current player state ({@link Player#STATE_IDLE} … {@link Player#STATE_ENDED}). */
    public int getPlaybackState() {
        return player != null ? player.getPlaybackState() : Player.STATE_IDLE;
    }

    public void play() {
        if (player != null) player.play();
    }

    public void pause() {
        if (player != null) player.pause();
    }

    /**
     * Optimises the player for many rapid seeks while the user drags the playhead.
     * Playback is suppressed during scrubbing; disable it before playing again.
     */
    public void setScrubbingModeEnabled(boolean enabled) {
        if (player != null) player.setScrubbingModeEnabled(enabled);
    }

    /** Seek to a timeline position in ms. */
    public void seekTo(long timelineMs) {
        if (player == null) return;
        player.seekTo(Math.max(0L, timelineMs));
        if (!player.getPlayWhenReady()) redraw();
    }

    /** Re-renders the current frame, e.g. after the surface was recreated. */
    public void redraw() {
        if (player == null) return;
        try {
            player.experimentalRedrawLastFrame();
        } catch (IllegalStateException ignored) {
            // Nothing renderable yet.
        }
    }

    /** Adds a listener for state/position callbacks. */
    public void addListener(@NonNull Player.Listener listener) {
        CompositionPlayer p = getPlayer();
        if (p != null) p.addListener(listener);
    }

    /** The size the composition is rendered at, or unknown. */
    @NonNull
    public VideoSize getVideoSize() {
        return player != null ? player.getVideoSize() : VideoSize.UNKNOWN;
    }

    /** Releases decoders and surfaces. Call from the host's onDestroy. */
    public void release() {
        released = true;
        unbindSurfaceView();
        if (player != null) {
            player.release();
            player = null;
            FLog.d(TAG, "CompositionPlayer released");
        }
        composition = null;
    }
}
