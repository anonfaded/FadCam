package com.fadcam.ui.faditor;

import android.content.ContentResolver;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.Player;
import androidx.media3.transformer.ExportResult;
import androidx.media3.ui.PlayerView;

import com.fadcam.R;
import com.fadcam.Constants;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.playback.FragmentedMp4Remuxer;
import com.fadcam.ui.faditor.export.ExportManager;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.player.FaditorPlayerManager;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.fadcam.ui.faditor.timeline.TimelineView;
import com.fadcam.ui.faditor.util.TimeFormatter;

import java.io.File;

/**
 * Full-screen video editor Activity for Faditor Mini.
 *
 * <p>Receives a video URI via intent data, creates an in-memory project,
 * and provides trim + export functionality using Media3.</p>
 */
public class FaditorEditorActivity extends AppCompatActivity {

    private static final String TAG = "FaditorEditor";

    /** Intent extra key for the video URI string. */
    public static final String EXTRA_VIDEO_URI = "faditor_video_uri";

    // ── Core components ──────────────────────────────────────────────
    private FaditorProject project;
    private FaditorPlayerManager playerManager;
    private ExportManager exportManager;
    private SharedPreferencesManager prefsManager;
    private FragmentedMp4Remuxer remuxer;

    // ── Views ────────────────────────────────────────────────────────
    private PlayerView playerView;
    private TimelineView timelineView;
    private TextView btnPlayPause;
    private TextView timeCurrent;
    private TextView timeTotal;
    private View exportProgressOverlay;
    private TextView exportProgressText;
    private View remuxProgressOverlay;
    private TextView remuxProgressText;

    // ── Tool buttons ─────────────────────────────────────────────────
    private View toolTrim;
    private View toolSpeed;
    private View toolMute;
    private TextView toolMuteIcon;
    private TextView toolMuteLabel;
    private TextView toolSpeedLabel;

    // ── Persistence ──────────────────────────────────────────────────
    private ProjectStorage projectStorage;
    private final Handler autoSaveHandler = new Handler(Looper.getMainLooper());
    private static final long AUTO_SAVE_DELAY_MS = 3000;
    private final Runnable autoSaveRunnable = () -> {
        if (project != null && projectStorage != null) {
            projectStorage.save(project);
            Log.d(TAG, "Project auto-saved");
        }
    };

    /** True until we receive ExoPlayer's real duration and correct the project. */
    private boolean durationCorrectionPending = true;

    /**
     * True while the user is actively touching/dragging the timeline (playhead or trim).
     * Prevents {@link #updatePlayheadPosition()} from overwriting the drag position.
     */
    private boolean userDragging = false;

    /**
     * Remembers if the player was playing when a drag started,
     * so we can resume after the drag finishes.
     */
    private boolean wasPlayingBeforeDrag = false;

    /** Tracks the last playhead fraction set by the user (drag or trim). */
    private float lastUserPlayheadFraction = 0f;

    // ── Playhead sync ────────────────────────────────────────────────
    private final Handler playheadHandler = new Handler(Looper.getMainLooper());
    private static final long PLAYHEAD_UPDATE_INTERVAL_MS = 50;

    private final Runnable playheadUpdater = new Runnable() {
        @Override
        public void run() {
            updatePlayheadPosition();
            playheadHandler.postDelayed(this, PLAYHEAD_UPDATE_INTERVAL_MS);
        }
    };

    // ── Lifecycle ────────────────────────────────────────────────────

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen on while editing
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_faditor_editor);

        // Initialize preferences and remuxer
        prefsManager = SharedPreferencesManager.getInstance(this);
        remuxer = new FragmentedMp4Remuxer(this);

        // Parse video URI from intent
        Uri videoUri = parseVideoUri();
        if (videoUri == null) {
            Log.e(TAG, "No video URI provided");
            Toast.makeText(this, R.string.faditor_error_no_video, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Initialize components
        projectStorage = new ProjectStorage(this);
        initViews();

        // Check if video needs remuxing for seekable playback
        attemptRemuxAndLoad(videoUri);

        Log.d(TAG, "Editor opened with: " + videoUri);
    }

    @Override
    protected void onResume() {
        super.onResume();
        playheadHandler.post(playheadUpdater);
    }

    @Override
    protected void onPause() {
        super.onPause();
        playheadHandler.removeCallbacks(playheadUpdater);
        // Save project on pause (e.g. user switches away)
        saveProjectNow();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        playheadHandler.removeCallbacks(playheadUpdater);
        autoSaveHandler.removeCallbacks(autoSaveRunnable);
        saveProjectNow();
        if (exportManager != null && exportManager.isExporting()) {
            exportManager.cancel();
        }
    }

    // ── Initialization ───────────────────────────────────────────────

    @Nullable
    private Uri parseVideoUri() {
        // Try intent data first, then extra
        Uri uri = getIntent().getData();
        if (uri == null) {
            String uriStr = getIntent().getStringExtra(EXTRA_VIDEO_URI);
            if (uriStr != null) {
                uri = Uri.parse(uriStr);
            }
        }
        return uri;
    }

    private void initViews() {
        playerView = findViewById(R.id.player_view);
        timelineView = findViewById(R.id.timeline_view);
        btnPlayPause = findViewById(R.id.btn_play_pause);
        timeCurrent = findViewById(R.id.time_current);
        timeTotal = findViewById(R.id.time_total);
        exportProgressOverlay = findViewById(R.id.export_progress_overlay);
        exportProgressText = findViewById(R.id.export_progress_text);
        remuxProgressOverlay = findViewById(R.id.remux_progress_overlay);
        remuxProgressText = findViewById(R.id.remux_progress_text);

        // Tool buttons
        toolTrim = findViewById(R.id.tool_trim);
        toolSpeed = findViewById(R.id.tool_speed);
        toolMute = findViewById(R.id.tool_mute);
        toolMuteIcon = findViewById(R.id.tool_mute_icon);
        toolMuteLabel = findViewById(R.id.tool_mute_label);
        toolSpeedLabel = findViewById(R.id.tool_speed_label);

        // Close button
        findViewById(R.id.btn_close).setOnClickListener(v -> handleClose());
    }

    /**
     * Attempt to remux a fragmented MP4 for seekable preview, then proceed
     * with project initialisation. If the file doesn't need remuxing (or is
     * not a local file) we skip straight to loading.
     */
    private void attemptRemuxAndLoad(@NonNull Uri videoUri) {
        File sourceFile = resolveToFile(videoUri);

        if (sourceFile != null && remuxer.needsRemux(sourceFile)) {
            // Already remuxed?
            if (remuxer.hasRemuxedVersion(sourceFile)) {
                File remuxed = remuxer.getRemuxedFile(sourceFile);
                Log.d(TAG, "Using cached remuxed file: " + remuxed.getName());
                continueLoadWithUri(Uri.fromFile(remuxed), videoUri);
                return;
            }

            // Show progress and remux async
            showRemuxProgress();
            Log.i(TAG, "Remuxing fragmented MP4 for seekable preview…");

            remuxer.remuxAsync(sourceFile, new FragmentedMp4Remuxer.RemuxCallback() {
                @Override
                public void onRemuxComplete(boolean success, String outputPath) {
                    runOnUiThread(() -> {
                        hideRemuxProgress();
                        if (success && outputPath != null) {
                            Log.i(TAG, "Remux complete: " + outputPath);
                            continueLoadWithUri(
                                    Uri.fromFile(new File(outputPath)), videoUri);
                        } else {
                            Log.w(TAG, "Remux failed, loading original (seeking may not work)");
                            continueLoadWithUri(videoUri, videoUri);
                        }
                    });
                }

                @Override
                public void onRemuxProgress(int percent) {
                    runOnUiThread(() -> {
                        if (remuxProgressText != null) {
                            remuxProgressText.setText(
                                    getString(R.string.faditor_remuxing_percent, percent));
                        }
                    });
                }
            });
        } else {
            // No remux needed / not a local file
            continueLoadWithUri(videoUri, videoUri);
        }
    }

    /**
     * Finish editor initialisation after (optional) remux is done.
     *
     * @param playUri   URI to use for preview playback (may be remuxed)
     * @param exportUri original URI kept for export (same content, original container)
     */
    private void continueLoadWithUri(@NonNull Uri playUri, @NonNull Uri exportUri) {
        initProject(playUri);
        initPlayer();
        initTimeline();
        initToolbar();
        initExport();
        initBackHandler();
    }

    /**
     * Try to resolve a URI to a local {@link File}.
     * Handles file:// URIs and SAF content:// URIs (best-effort reconstruction).
     *
     * @return the File if resolvable, or null
     */
    @Nullable
    private File resolveToFile(@NonNull Uri uri) {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            return new File(uri.getPath());
        }
        // Best-effort: reconstruct from SAF content:// path
        String path = uri.getPath();
        if (path != null && path.contains(":")) {
            int lastColon = path.lastIndexOf(':');
            if (lastColon >= 0 && lastColon < path.length() - 1) {
                String rel = path.substring(lastColon + 1);
                File f = new File("/storage/emulated/0/" + rel);
                if (f.exists() && f.canRead()) {
                    return f;
                }
            }
        }
        return null;
    }

    private void showRemuxProgress() {
        if (remuxProgressOverlay != null) {
            remuxProgressOverlay.setVisibility(View.VISIBLE);
            remuxProgressOverlay.setAlpha(0f);
            remuxProgressOverlay.animate().alpha(1f).setDuration(200).start();
        }
    }

    private void hideRemuxProgress() {
        if (remuxProgressOverlay != null) {
            remuxProgressOverlay.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                remuxProgressOverlay.setVisibility(View.GONE);
            }).start();
        }
    }

    private void initProject(@NonNull Uri videoUri) {
        // Get video duration
        long durationMs = getVideoDuration(videoUri);
        if (durationMs <= 0) {
            Log.w(TAG, "Could not determine video duration, using fallback");
            durationMs = 60_000; // Fallback 1 minute
        }

        // Create project with single clip
        project = new FaditorProject("Untitled");
        Clip clip = new Clip(videoUri, durationMs);
        project.getTimeline().addClip(clip);

        // Update total time display
        timeTotal.setText(TimeFormatter.formatAuto(durationMs));

        Log.d(TAG, "Project created: duration=" + durationMs + "ms");
    }

    private void initPlayer() {
        playerManager = new FaditorPlayerManager(this);
        getLifecycle().addObserver(playerManager);
        playerManager.setPlayerView(playerView);

        // Load the clip
        Clip clip = project.getTimeline().getClip(0);
        playerManager.loadClip(clip);

        // Listen for playback state changes
        playerManager.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updatePlayPauseButton(isPlaying);
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED) {
                    updatePlayPauseButton(false);
                }
                // Once ExoPlayer is READY, correct the duration if needed.
                // MediaMetadataRetriever is unreliable for fragmented MP4;
                // ExoPlayer parses actual content and reports the real duration.
                if (playbackState == Player.STATE_READY && durationCorrectionPending) {
                    durationCorrectionPending = false;
                    correctDurationFromPlayer();
                }
            }
        });
    }

    private void initTimeline() {
        Clip clip = project.getTimeline().getClip(0);
        timelineView.setTrimFromClip(clip);

        Log.d(TAG, "Timeline initialized: sourceDuration=" + clip.getSourceDurationMs()
                + "ms, in=" + clip.getInPointMs() + ", out=" + clip.getOutPointMs());

        // Listen for trim handle changes
        timelineView.setTrimChangeListener(new TimelineView.TrimChangeListener() {
            @Override
            public void onTrimChanged(float startFraction, float endFraction, boolean isLeftHandle) {
                // On first drag event: remember play state and pause
                if (!userDragging) {
                    wasPlayingBeforeDrag = playerManager.isPlaying();
                    if (wasPlayingBeforeDrag) playerManager.pause();
                    userDragging = true;
                }

                // Update clip trim points while dragging (live feedback)
                Clip c = project.getTimeline().getClip(0);
                long duration = c.getSourceDurationMs();
                c.setInPointMs((long) (startFraction * duration));
                c.setOutPointMs((long) (endFraction * duration));

                // Seek to the handle being dragged — live frame preview
                long seekAbsoluteMs = isLeftHandle ? c.getInPointMs() : c.getOutPointMs();
                playerManager.seekToAbsolute(seekAbsoluteMs);

                // Move playhead to handle position
                float handleFraction = isLeftHandle ? startFraction : endFraction;
                lastUserPlayheadFraction = handleFraction;
                timelineView.setPlayheadFraction(handleFraction);

                // Update time display to show trimmed region duration
                long trimmedDuration = c.getOutPointMs() - c.getInPointMs();
                timeTotal.setText(TimeFormatter.formatAuto(trimmedDuration));

                // Show relative position at the handle
                long relativePos = isLeftHandle ? 0 : trimmedDuration;
                timeCurrent.setText(TimeFormatter.formatAuto(relativePos));

                Log.v(TAG, "Trim dragging (" + (isLeftHandle ? "left" : "right") + "): in="
                        + c.getInPointMs() + " out=" + c.getOutPointMs()
                        + " seekTo=" + seekAbsoluteMs + " trimDur=" + trimmedDuration);
            }

            @Override
            public void onTrimFinished(float startFraction, float endFraction) {
                // Update player clip bounds when handle released
                Clip c = project.getTimeline().getClip(0);

                Log.d(TAG, "Trim finished: in=" + c.getInPointMs()
                        + " out=" + c.getOutPointMs()
                        + " trimDur=" + (c.getOutPointMs() - c.getInPointMs()));

                // Reset playhead to start of trimmed region
                lastUserPlayheadFraction = startFraction;
                timelineView.setPlayheadFraction(startFraction);
                timeCurrent.setText(TimeFormatter.formatAuto(0));

                // Update player trim bounds (no re-prepare)
                playerManager.updateTrimBounds(c);
                project.touch();
                scheduleAutoSave();

                // Clear drag state after short delay
                playheadHandler.postDelayed(() -> userDragging = false, 200);
            }
        });

        // Listen for playhead seeks on timeline
        // Only update time display during drag; seek player on release.
        timelineView.setPlayheadChangeListener(new TimelineView.PlayheadChangeListener() {
            @Override
            public void onPlayheadSeeked(float fraction) {
                // On first drag event: remember play state and pause
                if (!userDragging) {
                    wasPlayingBeforeDrag = playerManager.isPlaying();
                    if (wasPlayingBeforeDrag) playerManager.pause();
                    userDragging = true;
                }

                Clip c = project.getTimeline().getClip(0);
                long sourceDuration = c.getSourceDurationMs();
                if (sourceDuration <= 0) return;

                // fraction is in terms of full source duration
                long absoluteMs = (long) (fraction * sourceDuration);
                // Clamp to within trim bounds
                absoluteMs = Math.max(c.getInPointMs(), Math.min(absoluteMs, c.getOutPointMs()));

                // Track user's intended position
                lastUserPlayheadFraction = fraction;

                // Live seek the player — shows the frame at this position
                playerManager.seekToAbsolute(absoluteMs);

                // Update time display
                long seekPos = absoluteMs - c.getInPointMs();
                timeCurrent.setText(TimeFormatter.formatAuto(seekPos));

                Log.v(TAG, "Playhead scrub: fraction=" + fraction
                        + " absoluteMs=" + absoluteMs
                        + " seekPos=" + seekPos);
            }

            @Override
            public void onPlayheadDragFinished() {
                Log.d(TAG, "Playhead released at fraction=" + lastUserPlayheadFraction
                        + " wasPlaying=" + wasPlayingBeforeDrag);

                // Resume playback if it was playing before the drag
                if (wasPlayingBeforeDrag) {
                    playerManager.play();
                    wasPlayingBeforeDrag = false;
                }

                // Clear drag state after short delay
                playheadHandler.postDelayed(() -> userDragging = false, 200);
            }
        });
    }

    /** Available speed presets for the speed picker. */
    private static final float[] SPEED_PRESETS = {
        0.1f, 0.25f, 0.5f, 0.75f,
        1f,
        1.25f, 1.5f, 2f, 3f, 4f, 5f, 8f, 10f
    };

    private void initToolbar() {
        // Play/Pause button
        btnPlayPause.setOnClickListener(v -> {
            if (playerManager.isPlaying()) {
                playerManager.pause();
            } else {
                Clip c = project.getTimeline().getClip(0);
                Log.d(TAG, "Play pressed: trimIn=" + c.getInPointMs()
                        + " trimOut=" + c.getOutPointMs()
                        + " trimDur=" + (c.getOutPointMs() - c.getInPointMs()));
                playerManager.play();
            }
        });

        // Mute toggle
        toolMute.setOnClickListener(v -> toggleMute());

        // Speed picker
        toolSpeed.setOnClickListener(v -> showSpeedPicker());

        // Sync UI to existing clip state (e.g. reopened project)
        Clip clip = project.getTimeline().getClip(0);
        updateMuteUI(clip.isAudioMuted());
        updateSpeedUI(clip.getSpeedMultiplier());
    }

    // ── Mute ─────────────────────────────────────────────────────────

    private void toggleMute() {
        Clip clip = project.getTimeline().getClip(0);
        boolean newMuted = !clip.isAudioMuted();
        clip.setAudioMuted(newMuted);
        updateMuteUI(newMuted);

        // Update preview volume instantly
        playerManager.setVolume(newMuted ? 0f : 1f);
        scheduleAutoSave();
    }

    private void updateMuteUI(boolean muted) {
        if (toolMuteIcon != null) {
            toolMuteIcon.setText(muted ? "volume_off" : "volume_up");
            int color = muted ? 0xFF4CAF50 : 0xFF888888;
            toolMuteIcon.setTextColor(color);
            if (toolMuteLabel != null) {
                toolMuteLabel.setTextColor(color);
            }
        }
    }

    // ── Speed ────────────────────────────────────────────────────────

    private void showSpeedPicker() {
        Clip clip = project.getTimeline().getClip(0);
        float currentSpeed = clip.getSpeedMultiplier();

        String[] labels = new String[SPEED_PRESETS.length];
        int checkedIndex = 0;
        for (int i = 0; i < SPEED_PRESETS.length; i++) {
            labels[i] = formatSpeed(SPEED_PRESETS[i]);
            if (Math.abs(SPEED_PRESETS[i] - currentSpeed) < 0.001f) {
                checkedIndex = i;
            }
        }

        new androidx.appcompat.app.AlertDialog.Builder(this, R.style.CustomBottomSheetDialogTheme)
                .setTitle(R.string.faditor_speed_title)
                .setSingleChoiceItems(labels, checkedIndex, (dialog, which) -> {
                    float speed = SPEED_PRESETS[which];
                    clip.setSpeedMultiplier(speed);
                    updateSpeedUI(speed);
                    playerManager.setPlaybackSpeed(speed);
                    scheduleAutoSave();
                    dialog.dismiss();
                })
                .show();
    }

    private void updateSpeedUI(float speed) {
        if (toolSpeedLabel != null) {
            toolSpeedLabel.setText(formatSpeed(speed));
            int color = Math.abs(speed - 1f) < 0.001f ? 0xFF888888 : 0xFF4CAF50;
            toolSpeedLabel.setTextColor(color);
            TextView icon = findViewById(R.id.tool_speed_icon);
            if (icon != null) icon.setTextColor(color);
        }
    }

    private String formatSpeed(float speed) {
        if (speed == (int) speed) {
            return (int) speed + "x";
        }
        return String.format(java.util.Locale.US, "%.2gx", speed);
    }

    private void initExport() {
        exportManager = new ExportManager(this, prefsManager);

        // Export button
        findViewById(R.id.btn_export).setOnClickListener(v -> startExport());

        // Cancel export button
        findViewById(R.id.btn_cancel_export).setOnClickListener(v -> {
            exportManager.cancel();
            hideExportProgress();
            Toast.makeText(this, R.string.faditor_export_cancelled, Toast.LENGTH_SHORT).show();
        });

        exportManager.setExportListener(new ExportManager.ExportListener() {
            @Override
            public void onExportStarted(@NonNull String outputPath) {
                runOnUiThread(() -> showExportProgress());
            }

            @Override
            public void onExportProgress(float progress) {
                runOnUiThread(() -> {
                    if (exportProgressText != null) {
                        int percent = (int) (progress * 100);
                        exportProgressText.setText(
                                getString(R.string.faditor_exporting_percent, percent));
                    }
                });
            }

            @Override
            public void onExportCompleted(@NonNull String outputPath, @NonNull ExportResult result) {
                runOnUiThread(() -> {
                    hideExportProgress();
                    Toast.makeText(FaditorEditorActivity.this,
                            getString(R.string.faditor_export_success),
                            Toast.LENGTH_LONG).show();
                    Log.d(TAG, "Export saved to: " + outputPath);
                });
            }

            @Override
            public void onExportError(@NonNull Exception error) {
                runOnUiThread(() -> {
                    hideExportProgress();
                    Toast.makeText(FaditorEditorActivity.this,
                            getString(R.string.faditor_export_error, error.getMessage()),
                            Toast.LENGTH_LONG).show();
                    Log.e(TAG, "Export failed", error);
                });
            }
        });
    }

    private void initBackHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleClose();
            }
        });
    }

    // ── Playback helpers ─────────────────────────────────────────────

    /**
     * Correct the project's source duration using ExoPlayer's actual reported duration.
     * Both MMR and FFprobeKit can disagree with ExoPlayer for edge-case files;
     * if ExoPlayer reports significantly different, prefer ExoPlayer's value.
     */
    private void correctDurationFromPlayer() {
        long playerDurationMs = playerManager.getSourceDuration();
        if (playerDurationMs <= 0) return;

        Clip clip = project.getTimeline().getClip(0);
        long storedDuration = clip.getSourceDurationMs();

        // Only correct if ExoPlayer reports SHORTER duration.
        // For fragmented MP4, ExoPlayer may report the container duration
        // (e.g. 60 000 ms) while FFprobe reports the actual content duration
        // (e.g. 4 096 ms). We trust FFprobe for "longer" values.
        if (playerDurationMs < storedDuration && (storedDuration - playerDurationMs) > 500) {
            Log.w(TAG, "Duration correction (shorter): stored=" + storedDuration
                    + "ms → ExoPlayer=" + playerDurationMs + "ms");

            clip.setSourceDurationMs(playerDurationMs);
            clip.setInPointMs(0);
            clip.setOutPointMs(playerDurationMs);

            // Refresh UI with corrected duration
            timeTotal.setText(TimeFormatter.formatAuto(playerDurationMs));
            timeCurrent.setText(TimeFormatter.formatAuto(0));
            timelineView.setTrimFromClip(clip);
            timelineView.setPlayheadFraction(0f);

            // Update player trim bounds (no re-prepare needed)
            playerManager.updateTrimBounds(clip);
        } else {
            Log.d(TAG, "Duration OK: stored=" + storedDuration
                    + "ms, ExoPlayer=" + playerDurationMs + "ms (keeping stored)");
        }
    }

    private void updatePlayPauseButton(boolean isPlaying) {
        btnPlayPause.setText(isPlaying ? "pause" : "play_arrow");
    }

    private void updatePlayheadPosition() {
        if (playerManager == null || project == null || project.getTimeline().isEmpty()) return;

        Clip clip = project.getTimeline().getClip(0);
        long sourceDuration = clip.getSourceDurationMs();
        if (sourceDuration <= 0) return;

        // Don't overwrite playhead while user is dragging
        if (userDragging) return;

        // ── While PLAYING: read player position and drive the playhead ──
        if (playerManager.isPlaying()) {
            // Enforce trim end — pause if playback reached out-point
            if (playerManager.isAtTrimEnd()) {
                playerManager.pause();
                long trimmedDur = clip.getOutPointMs() - clip.getInPointMs();
                float endFraction = (float) clip.getOutPointMs() / sourceDuration;
                lastUserPlayheadFraction = endFraction;
                timelineView.setPlayheadFraction(endFraction);
                timeCurrent.setText(TimeFormatter.formatAuto(trimmedDur));
                updatePlayPauseButton(false);
                Log.d(TAG, "Playback stopped at trim end");
                return;
            }

            long position = playerManager.getCurrentPosition(); // 0-based within trim
            long absoluteMs = clip.getInPointMs() + position;
            float fraction = (float) absoluteMs / sourceDuration;
            fraction = Math.max(0f, Math.min(fraction, 1f));

            lastUserPlayheadFraction = fraction;
            timelineView.setPlayheadFraction(fraction);
            timeCurrent.setText(TimeFormatter.formatAuto(position));
        }
        // ── While PAUSED: do NOT overwrite the user's playhead position ──
        // The playhead stays wherever the user last placed it or where
        // playback stopped. This prevents snapping back because the player
        // may not have actually seeked (content:// / fMP4 limitation).
    }

    // ── Export helpers ────────────────────────────────────────────────

    private void startExport() {
        if (exportManager.isExporting()) {
            Toast.makeText(this, R.string.faditor_export_in_progress, Toast.LENGTH_SHORT).show();
            return;
        }

        // Pause playback during export
        playerManager.pause();
        exportManager.export(project);
    }

    private void showExportProgress() {
        if (exportProgressOverlay != null) {
            exportProgressOverlay.setVisibility(View.VISIBLE);
            exportProgressOverlay.setAlpha(0f);
            exportProgressOverlay.animate().alpha(1f).setDuration(200).start();
        }
    }

    private void hideExportProgress() {
        if (exportProgressOverlay != null) {
            exportProgressOverlay.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                exportProgressOverlay.setVisibility(View.GONE);
            }).start();
        }
    }

    // ── Navigation ───────────────────────────────────────────────────

    private void handleClose() {
        if (exportManager != null && exportManager.isExporting()) {
            // Don't close while exporting
            Toast.makeText(this, R.string.faditor_export_in_progress, Toast.LENGTH_SHORT).show();
            return;
        }
        saveProjectNow();
        finish();
    }

    // ── Persistence ──────────────────────────────────────────────────

    /**
     * Schedule a debounced auto-save (resets timer on each call).
     */
    private void scheduleAutoSave() {
        autoSaveHandler.removeCallbacks(autoSaveRunnable);
        autoSaveHandler.postDelayed(autoSaveRunnable, AUTO_SAVE_DELAY_MS);
    }

    /**
     * Save project immediately (blocking on current thread, fast for small JSON).
     */
    private void saveProjectNow() {
        if (project != null && projectStorage != null) {
            autoSaveHandler.removeCallbacks(autoSaveRunnable);
            projectStorage.save(project);
            Log.d(TAG, "Project saved: " + project.getId());
        }
    }

    // ── Utility ──────────────────────────────────────────────────────

    /**
     * Get video duration using FFprobeKit (reliable for fragmented MP4),
     * with MediaMetadataRetriever as fallback.
     */
    private long getVideoDuration(@NonNull Uri videoUri) {
        // ── FFprobeKit (primary — reliable for fMP4) ────────────
        try {
            String filePath = getFFprobePathForUri(videoUri);
            com.arthenica.ffmpegkit.MediaInformationSession session =
                    com.arthenica.ffmpegkit.FFprobeKit.getMediaInformation(filePath);
            com.arthenica.ffmpegkit.MediaInformation info = session.getMediaInformation();
            if (info != null) {
                String durationStr = info.getDuration();
                if (durationStr != null) {
                    double durationSec = Double.parseDouble(durationStr);
                    long durationMs = (long) (durationSec * 1000);
                    Log.d(TAG, "Duration from FFprobe: " + durationMs + "ms");
                    return durationMs;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "FFprobe duration failed", e);
        }

        // ── MediaMetadataRetriever fallback ──────────────────────
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            if ("file".equals(videoUri.getScheme()) && videoUri.getPath() != null) {
                retriever.setDataSource(videoUri.getPath());
            } else {
                retriever.setDataSource(this, videoUri);
            }
            String durationStr = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                long ms = Long.parseLong(durationStr);
                Log.d(TAG, "Duration from MMR fallback: " + ms + "ms");
                return ms;
            }
        } catch (Exception e) {
            Log.e(TAG, "MMR duration failed", e);
        } finally {
            if (retriever != null) {
                try { retriever.release(); } catch (Exception ignored) { }
            }
        }

        return -1;
    }

    /**
     * Build a file path suitable for FFprobeKit.
     * Reconstructs /storage/emulated/0 path for SAF content:// URIs.
     */
    @NonNull
    private static String getFFprobePathForUri(@NonNull Uri uri) {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            return uri.getPath();
        }
        String path = uri.getPath();
        if (path != null && path.contains(":")) {
            int lastColon = path.lastIndexOf(':');
            if (lastColon >= 0 && lastColon < path.length() - 1) {
                String rel = path.substring(lastColon + 1);
                String reconstructed = "/storage/emulated/0/" + rel;
                java.io.File f = new java.io.File(reconstructed);
                if (f.exists() && f.canRead()) {
                    Log.d(TAG, "FFprobe using reconstructed path: " + reconstructed);
                    return reconstructed;
                }
            }
        }
        return "saf:" + uri.toString();
    }
}
