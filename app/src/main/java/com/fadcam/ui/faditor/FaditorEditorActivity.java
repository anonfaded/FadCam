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
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.media3.common.Player;
import androidx.media3.transformer.ExportResult;
import androidx.media3.ui.PlayerView;

import com.fadcam.R;
import com.fadcam.ui.faditor.export.ExportManager;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.player.FaditorPlayerManager;
import com.fadcam.ui.faditor.timeline.TimelineView;
import com.fadcam.ui.faditor.util.TimeFormatter;

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

    // ── Views ────────────────────────────────────────────────────────
    private PlayerView playerView;
    private TimelineView timelineView;
    private ImageButton btnPlayPause;
    private TextView timeCurrent;
    private TextView timeTotal;
    private View exportProgressOverlay;
    private TextView exportProgressText;

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

        // Full-screen, edge-to-edge dark editor
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(0xFF1A1A1A);
        getWindow().setNavigationBarColor(0xFF1A1A1A);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_faditor_editor);

        // Parse video URI from intent
        Uri videoUri = parseVideoUri();
        if (videoUri == null) {
            Log.e(TAG, "No video URI provided");
            Toast.makeText(this, R.string.faditor_error_no_video, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Initialize components
        initViews();
        initProject(videoUri);
        initPlayer();
        initTimeline();
        initToolbar();
        initExport();
        initBackHandler();

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
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        playheadHandler.removeCallbacks(playheadUpdater);
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

        // Close button
        findViewById(R.id.btn_close).setOnClickListener(v -> handleClose());
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
            }
        });
    }

    private void initTimeline() {
        Clip clip = project.getTimeline().getClip(0);
        timelineView.setTrimFromClip(clip);

        // Listen for trim handle changes
        timelineView.setTrimChangeListener(new TimelineView.TrimChangeListener() {
            @Override
            public void onTrimChanged(float startFraction, float endFraction) {
                // Update clip trim points while dragging
                Clip c = project.getTimeline().getClip(0);
                long duration = c.getSourceDurationMs();
                c.setInPointMs((long) (startFraction * duration));
                c.setOutPointMs((long) (endFraction * duration));

                // Update time display
                long trimmedDuration = c.getOutPointMs() - c.getInPointMs();
                timeTotal.setText(TimeFormatter.formatAuto(trimmedDuration));
            }

            @Override
            public void onTrimFinished(float startFraction, float endFraction) {
                // Update player clip bounds when handle released
                Clip c = project.getTimeline().getClip(0);
                playerManager.updateTrimBounds(c);
                project.touch();
                Log.d(TAG, "Trim updated: " + c.getInPointMs() + " – " + c.getOutPointMs());
            }
        });

        // Listen for playhead seeks on timeline
        timelineView.setPlayheadChangeListener(fraction -> {
            Clip c = project.getTimeline().getClip(0);
            long seekPos = (long) (fraction * c.getSourceDurationMs()) - c.getInPointMs();
            seekPos = Math.max(0, seekPos);
            playerManager.seekTo(seekPos);
        });
    }

    private void initToolbar() {
        // Play/Pause button
        btnPlayPause.setOnClickListener(v -> {
            if (playerManager.isPlaying()) {
                playerManager.pause();
            } else {
                playerManager.play();
            }
        });
    }

    private void initExport() {
        exportManager = new ExportManager(this);

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

    private void updatePlayPauseButton(boolean isPlaying) {
        btnPlayPause.setImageResource(isPlaying
                ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play);
    }

    private void updatePlayheadPosition() {
        if (playerManager == null || project == null || project.getTimeline().isEmpty()) return;

        Clip clip = project.getTimeline().getClip(0);
        long position = playerManager.getCurrentPosition();
        long duration = clip.getSourceDurationMs();

        if (duration <= 0) return;

        // Position is relative to clip start (clipping config offsets it)
        float fraction = (float) (clip.getInPointMs() + position) / duration;
        timelineView.setPlayheadFraction(fraction);

        timeCurrent.setText(TimeFormatter.formatAuto(position));
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
        finish();
    }

    // ── Utility ──────────────────────────────────────────────────────

    private long getVideoDuration(@NonNull Uri videoUri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, videoUri);
            String durationStr = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                return Long.parseLong(durationStr);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting video duration", e);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
        return -1;
    }
}
