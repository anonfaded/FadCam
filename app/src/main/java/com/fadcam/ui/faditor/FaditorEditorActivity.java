package com.fadcam.ui.faditor;

import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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
import com.fadcam.ui.faditor.timeline.EditorTimelineView;
import com.fadcam.ui.faditor.model.Timeline;
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

    /** Default duration for still image clips (milliseconds). */
    private static final long IMAGE_CLIP_DURATION_MS = 5000;

    // ── Core components ──────────────────────────────────────────────
    private FaditorProject project;
    private FaditorPlayerManager playerManager;
    private ExportManager exportManager;
    private SharedPreferencesManager prefsManager;
    private FragmentedMp4Remuxer remuxer;

    // ── Asset pickers ────────────────────────────────────────────────
    private ActivityResultLauncher<Intent> imagePickerLauncher;
    private ActivityResultLauncher<Intent> videoPickerLauncher;

    // ── Views ────────────────────────────────────────────────────────
    private PlayerView playerView;
    private View playerContainer;
    private EditorTimelineView editorTimeline;
    private TextView btnPlayPause;
    private TextView timeCurrent;
    private TextView timeTotal;
    private View exportProgressOverlay;
    private TextView exportProgressText;
    private View remuxProgressOverlay;
    private TextView remuxProgressText;
    private com.fadcam.ui.faditor.crop.CropOverlayView cropOverlay;
    private android.widget.ImageView imagePreview;

    // ── Tool buttons ─────────────────────────────────────────────────
    private View toolTrim;
    private View toolSpeed;
    private View toolMute;
    private TextView toolMuteIcon;
    private TextView toolMuteLabel;
    private TextView toolSpeedLabel;
    private View toolRotate;
    private TextView toolRotateIcon;
    private TextView toolRotateLabel;
    private View toolFlip;
    private TextView toolFlipIcon;
    private TextView toolFlipLabel;
    private View toolCrop;
    private TextView toolCropIcon;
    private TextView toolCropLabel;
    private View toolCanvas;
    private TextView toolCanvasIcon;
    private TextView toolCanvasLabel;

    // ── Multi-segment state ──────────────────────────────────────────
    /** Index of the currently selected clip/segment in the timeline. */
    private int selectedClipIndex = 0;

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

    // ── Image clip playback ──────────────────────────────────────────
    /** True while an image clip is being "played" via internal timer. */
    private boolean imagePlaybackActive = false;
    /** System time (ms) when image playback started, for computing elapsed. */
    private long imagePlaybackStartSystemMs = 0;
    /** Position within the image clip when playback started (ms). */
    private long imagePlaybackStartOffsetMs = 0;

    // ── Playhead sync ────────────────────────────────────────────────
    private final Handler playheadHandler = new Handler(Looper.getMainLooper());
    private static final long PLAYHEAD_UPDATE_INTERVAL_MS = 50;

    private final Runnable playheadUpdater = new Runnable() {
        @Override
        public void run() {
            try {
                updatePlayheadPosition();
            } catch (Exception e) {
                Log.e(TAG, "Error in updatePlayheadPosition", e);
            }
            playheadHandler.postDelayed(this, PLAYHEAD_UPDATE_INTERVAL_MS);
        }
    };

    // ── Segment helpers ──────────────────────────────────────────────

    /**
     * Returns the currently selected clip.
     * Falls back to clip 0 if the index is out of range.
     */
    @NonNull
    private Clip getSelectedClip() {
        int count = project.getTimeline().getClipCount();
        if (selectedClipIndex < 0 || selectedClipIndex >= count) {
            selectedClipIndex = 0;
        }
        return project.getTimeline().getClip(selectedClipIndex);
    }

    /**
     * Select a segment by index, update the timeline and toolbar.
     *
     * @param index the segment index to select (-1 to deselect)
     */
    private void selectSegment(int index) {
        int count = project.getTimeline().getClipCount();
        
        // Handle deselection
        if (index < 0) {
            selectedClipIndex = -1;
            editorTimeline.setTimeline(project.getTimeline(), -1);
            Log.d(TAG, "Deselected all segments");
            return;
        }
        
        // Validate index
        if (index >= count) return;
        
        // Track if this is a reselection of the same segment
        boolean isReselection = (index == selectedClipIndex);
        
        selectedClipIndex = index;
        Clip clip = getSelectedClip();

        // Sync timeline view to the selected segment's trim
        editorTimeline.setTrimFromClip(clip);

        // Sync toolbar tools to the selected segment's settings
        updateVolumeUI(clip.getVolumeLevel(), clip.isAudioMuted());
        updateSpeedUI(clip.getSpeedMultiplier());
        updateRotateUI(clip.getRotationDegrees());
        updateFlipUI(clip.isFlipHorizontal(), clip.isFlipVertical());
        updateCropUI(clip.getCropPreset());

        // Only reload and reset playhead if selecting a different segment
        if (!isReselection) {
            // Stop any active image playback timer
            imagePlaybackActive = false;

            // Preserve current playhead position within the new segment
            long currentPlayheadMs = editorTimeline.getPlayheadPositionMs();
            long segStartMs = editorTimeline.getSegmentStartTimeMs(index);
            long effectiveMs = clip.getOutPointMs() - clip.getInPointMs();
            if (clip.getSpeedMultiplier() > 0) {
                effectiveMs = (long)(effectiveMs / clip.getSpeedMultiplier());
            }
            long localMs = Math.max(0, Math.min(currentPlayheadMs - segStartMs, effectiveMs));
            // Convert local (effective) ms to source position
            long sourcePositionMs = clip.getInPointMs() + (long)(localMs * clip.getSpeedMultiplier());
            sourcePositionMs = Math.max(clip.getInPointMs(), Math.min(sourcePositionMs, clip.getOutPointMs()));
            float sourceFrac = clip.getSourceDurationMs() > 0
                    ? (float) sourcePositionMs / clip.getSourceDurationMs() : 0f;

            if (clip.isImageClip()) {
                // Image clip: show image preview, hide video player
                showImagePreview(clip.getSourceUri());
            } else {
                // Video clip: hide image preview, show video player
                hideImagePreview();

                // Load the selected segment in the player
                playerManager.loadClip(clip);
                playerManager.setVolume(clip.isAudioMuted() ? 0f : clip.getVolumeLevel());
                playerManager.setPlaybackSpeed(clip.getSpeedMultiplier());
                updatePreviewTransforms();
            }

            // Set playhead to preserved position (not segment start)
            editorTimeline.setPlayheadFraction(sourceFrac);
            
            long seekPositionMs = sourcePositionMs - clip.getInPointMs();
            if (!clip.isImageClip()) {
                // Only seek ExoPlayer for video clips
                playerManager.seekTo(seekPositionMs);
            }
            updateCurrentTimeDisplay(seekPositionMs);
        }

        refreshTotalTimeDisplay();

        Log.d(TAG, "Selected segment " + index + "/" + count
                + " id=" + clip.getId()
                + " in=" + clip.getInPointMs() + " out=" + clip.getOutPointMs()
                + " isReselection=" + isReselection);
    
        // Update segment overview
        editorTimeline.setTimeline(project.getTimeline(), selectedClipIndex);
        ;
    }
    // ── Lifecycle ────────────────────────────────────────────────────

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Register asset picker launchers (must be before onStart)
        registerAssetPickers();

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
        playerContainer = findViewById(R.id.player_container);
        cropOverlay = findViewById(R.id.crop_overlay);
        imagePreview = findViewById(R.id.image_preview);
        editorTimeline = findViewById(R.id.editor_timeline_view);
        editorTimeline.setOnSegmentActionListener(new EditorTimelineView.OnSegmentActionListener() {
            @Override
            public void onSegmentSelected(int index) {
                selectSegment(index);
            }

            @Override
            public void onTrimChanged(int segmentIndex, float startFraction, float endFraction, boolean isLeft) {
                userDragging = true;
                Clip clip = getSelectedClip();
                if (clip == null) return;
                long duration = clip.getSourceDurationMs();
                clip.setInPointMs((long)(startFraction * duration));
                clip.setOutPointMs((long)(endFraction * duration));
                // View handles its own visual update during drag.
                // setTrimFromClip is called in onTrimFinished for the final commit.
                updateCurrentTimeDisplay(0);
                refreshTotalTimeDisplay();
            }

            @Override
            public void onTrimFinished(int segmentIndex, float startFraction, float endFraction) {
                userDragging = false;
                Clip clip = getSelectedClip();
                if (clip == null) return;
                long duration = clip.getSourceDurationMs();
                clip.setInPointMs((long)(startFraction * duration));
                clip.setOutPointMs((long)(endFraction * duration));
                editorTimeline.setTrimFromClip(clip);
                if (!clip.isImageClip()) {
                    playerManager.updateTrimBounds(clip);
                }
                updateCurrentTimeDisplay(0);
                refreshTotalTimeDisplay();
                saveProjectNow();
            }

            @Override
            public void onPlayheadSeeked(int segmentIndex, float fractionInSegment) {
                userDragging = true;
                // Get clip directly — DON'T call selectSegment() during scrubbing.
                // selectSegment triggers setPlayheadFraction → centerPlayhead which
                // modifies scrollOffsetPx, causing a feedback loop that makes the
                // timeline bounce between segments.
                Timeline tl = project.getTimeline();
                if (segmentIndex < 0 || segmentIndex >= tl.getClipCount()) return;
                Clip clip = tl.getClip(segmentIndex);
                if (clip == null) return;

                // If crossing to a different segment, load the new clip in the player
                // (needed for correct seek bounds) but skip all scroll-altering calls
                if (segmentIndex != selectedClipIndex) {
                    selectedClipIndex = segmentIndex;
                    if (clip.isImageClip()) {
                        // Image clip: show image preview instead of loading into ExoPlayer
                        showImagePreview(clip.getSourceUri());
                        stopImagePlayback();
                    } else {
                        // Video clip: load into ExoPlayer as usual
                        hideImagePreview();
                        playerManager.loadClip(clip);
                        playerManager.setVolume(clip.isAudioMuted() ? 0f : clip.getVolumeLevel());
                        playerManager.setPlaybackSpeed(clip.getSpeedMultiplier());
                        updatePreviewTransforms();
                    }
                }

                // fractionInSegment is fraction of FULL source duration,
                // convert back to source time then compute relative-to-trim-start position
                long sourceMs = (long)(fractionInSegment * clip.getSourceDurationMs());
                long trimDuration = clip.getOutPointMs() - clip.getInPointMs();
                long seekPosition = Math.max(0, Math.min(sourceMs - clip.getInPointMs(), trimDuration));
                if (!clip.isImageClip()) {
                    // Only seek ExoPlayer for video clips
                    playerManager.seekTo(seekPosition);
                } else {
                    // For image clips: store the seek position for playback resumption
                    imagePlaybackStartOffsetMs = seekPosition;
                }
                // Update time display during drag (show absolute project position)
                updateCurrentTimeDisplay(seekPosition);
            }

            @Override
            public void onPlayheadDragFinished() {
                userDragging = false;
                // After scrubbing completes, fully sync the UI to the current segment
                // (safe now since no scroll gesture is active)
                if (selectedClipIndex >= 0 && selectedClipIndex < project.getTimeline().getClipCount()) {
                    selectSegment(selectedClipIndex);
                }
            }

            @Override
            public void onSegmentReordered(int fromIndex, int toIndex) {
                Timeline tl = project.getTimeline();
                tl.moveClip(fromIndex, toIndex);
                selectSegment(toIndex);
                saveProjectNow();
            }

            @Override
            public void onReorderModeChanged(boolean entering) {
                // Hide/show bottom toolbar during reorder mode
                // Skip timeline_scroll (first HorizontalScrollView) — target the bottom toolbar
                HorizontalScrollView toolbar = null;
                LinearLayout controlsSection = findViewById(R.id.controls_section);
                if (controlsSection != null) {
                    for (int i = 0; i < controlsSection.getChildCount(); i++) {
                        View child = controlsSection.getChildAt(i);
                        if (child instanceof HorizontalScrollView
                                && child.getId() != R.id.timeline_scroll) {
                            toolbar = (HorizontalScrollView) child;
                            break;
                        }
                    }
                }
                if (toolbar != null) {
                    toolbar.setVisibility(entering ? View.GONE : View.VISIBLE);
                }
            }
        });
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
        toolRotate = findViewById(R.id.tool_rotate);
        toolRotateIcon = findViewById(R.id.tool_rotate_icon);
        toolRotateLabel = findViewById(R.id.tool_rotate_label);
        toolFlip = findViewById(R.id.tool_flip);
        toolFlipIcon = findViewById(R.id.tool_flip_icon);
        toolFlipLabel = findViewById(R.id.tool_flip_label);
        toolCrop = findViewById(R.id.tool_crop);
        toolCropIcon = findViewById(R.id.tool_crop_icon);
        toolCropLabel = findViewById(R.id.tool_crop_label);
        toolCanvas = findViewById(R.id.tool_canvas);
        toolCanvasIcon = findViewById(R.id.tool_canvas_icon);
        toolCanvasLabel = findViewById(R.id.tool_canvas_label);

        // Segment tools
        findViewById(R.id.tool_split).setOnClickListener(v -> splitAtPlayhead());
        findViewById(R.id.tool_delete).setOnClickListener(v -> deleteSelectedSegment());
        findViewById(R.id.tool_duplicate).setOnClickListener(v -> duplicateSelectedSegment());
        findViewById(R.id.tool_add_asset).setOnClickListener(v -> showAddAssetPicker());

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

        // Update total time display (project-wide duration)
        refreshTotalTimeDisplay();

        Log.d(TAG, "Project created: duration=" + durationMs + "ms");
    }

    private void initPlayer() {
        playerManager = new FaditorPlayerManager(this);
        getLifecycle().addObserver(playerManager);
        playerManager.setPlayerView(playerView);

        // Load the clip
        Clip clip = getSelectedClip();
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

        // Sync initial volume and speed from clip state
        playerManager.setVolume(clip.isAudioMuted() ? 0f : clip.getVolumeLevel());
        playerManager.setPlaybackSpeed(clip.getSpeedMultiplier());

        // Sync initial volume and speed from clip state
        playerManager.setVolume(clip.isAudioMuted() ? 0f : clip.getVolumeLevel());
        playerManager.setPlaybackSpeed(clip.getSpeedMultiplier());
    }

    private void initTimeline() {
        Clip clip = getSelectedClip();
        editorTimeline.setTrimFromClip(clip);
        editorTimeline.setTimeline(project.getTimeline(), selectedClipIndex);

        Log.d(TAG, "Timeline initialized: sourceDuration=" + clip.getSourceDurationMs()
                + "ms, in=" + clip.getInPointMs() + ", out=" + clip.getOutPointMs());
    }

    private void initToolbar() {
        // Play/Pause button
        btnPlayPause.setOnClickListener(v -> {
            Clip c = getSelectedClip();
            if (c.isImageClip()) {
                // Image clip: toggle internal timer playback
                if (imagePlaybackActive) {
                    // Pause: record current position
                    imagePlaybackStartOffsetMs = getImagePlaybackPositionMs();
                    stopImagePlayback();
                } else {
                    // Compute current position from playhead
                    long currentPos = imagePlaybackStartOffsetMs;
                    long clipDuration = c.getTrimmedDurationMs();
                    if (currentPos >= clipDuration) {
                        // At end: restart from beginning
                        currentPos = 0;
                    }
                    startImagePlayback(currentPos);
                }
            } else {
                // Video clip: use ExoPlayer
                if (playerManager.isPlaying()) {
                    playerManager.pause();
                } else {
                    playerManager.play();
                }
            }
        });

        // Volume control (replaces simple mute toggle)
        toolMute.setOnClickListener(v -> showVolumeControl());

        // Speed slider
        toolSpeed.setOnClickListener(v -> showSpeedSlider());

        // Rotate (cycles 0 -> 90 -> 180 -> 270 -> 0)
        toolRotate.setOnClickListener(v -> rotateNext());

        // Flip picker
        toolFlip.setOnClickListener(v -> showFlipPicker());

        // Crop picker
        toolCrop.setOnClickListener(v -> showCropPicker());

        // Canvas picker (project-level aspect ratio)
        toolCanvas.setOnClickListener(v -> showCanvasPicker());

        // Sync UI to existing clip state (e.g. reopened project)
        Clip clip = getSelectedClip();
        updateVolumeUI(clip.getVolumeLevel(), clip.isAudioMuted());
        updateSpeedUI(clip.getSpeedMultiplier());
        updateRotateUI(clip.getRotationDegrees());
        updateFlipUI(clip.isFlipHorizontal(), clip.isFlipVertical());
        updateCropUI(clip.getCropPreset());
        updateCanvasUI(project.getCanvasPreset());

        // Apply live preview transforms (delayed to ensure player view is laid out)
        playerView.post(() -> {
            updatePreviewTransforms();
            applyCanvasPreview(project.getCanvasPreset());
        });

        // Restore free crop overlay if previously active
        if ("custom".equals(clip.getCropPreset())) {
            playerView.post(this::activateFreeCrop);
        }
    }

    // ── Volume ────────────────────────────────────────────────────────

    private void showVolumeControl() {
        Clip clip = getSelectedClip();

        VolumeControlBottomSheet sheet = VolumeControlBottomSheet.newInstance(
                clip.getVolumeLevel(), clip.isAudioMuted());
        sheet.setCallback((volume, muted) -> {
            clip.setVolumeLevel(volume);
            clip.setAudioMuted(muted);
            updateVolumeUI(volume, muted);
            playerManager.setVolume(muted ? 0f : volume);
            scheduleAutoSave();
        });
        sheet.show(getSupportFragmentManager(), "volumeControl");
    }

    private void updateVolumeUI(float volume, boolean muted) {
        if (toolMuteIcon != null) {
            String icon;
            if (muted) {
                icon = "volume_off";
            } else if (volume < 0.01f) {
                icon = "volume_mute";
            } else if (volume <= 0.5f) {
                icon = "volume_down";
            } else {
                icon = "volume_up";
            }
            toolMuteIcon.setText(icon);

            int color;
            if (muted) {
                color = 0xFFF44336; // red
            } else if (volume > 1.01f) {
                color = 0xFFF44336; // red for overdrive
            } else if (Math.abs(volume - 1f) < 0.01f) {
                color = 0xFF888888; // default gray
            } else {
                color = 0xFF4CAF50; // green for modified
            }
            toolMuteIcon.setTextColor(color);

            if (toolMuteLabel != null) {
                if (muted) {
                    toolMuteLabel.setText(R.string.faditor_tool_muted);
                } else {
                    int pct = Math.round(volume * 100f);
                    toolMuteLabel.setText(pct == 100
                            ? getString(R.string.faditor_tool_volume)
                            : pct + "%");
                }
                toolMuteLabel.setTextColor(color);
            }
        }
    }

    // ── Speed ────────────────────────────────────────────────────────

    private void showSpeedSlider() {
        Clip clip = getSelectedClip();
        SpeedSliderBottomSheet sheet = SpeedSliderBottomSheet.newInstance(clip.getSpeedMultiplier());
        sheet.setCallback(speed -> {
            clip.setSpeedMultiplier(speed);
            playerManager.setPlaybackSpeed(speed);
            updateSpeedUI(speed);
            scheduleAutoSave();
        });
        sheet.show(getSupportFragmentManager(), "speed_slider");
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
        if (speed == (int) speed) return (int) speed + "x";
        return String.format(java.util.Locale.US, "%.2gx", speed);
    }

    // ── Rotate ───────────────────────────────────────────────────────

    private void rotateNext() {
        Clip clip = getSelectedClip();
        int newDeg = (clip.getRotationDegrees() + 90) % 360;
        clip.setRotationDegrees(newDeg);
        updateRotateUI(newDeg);
        updatePreviewTransforms();
        scheduleAutoSave();
    }

    private void updateRotateUI(int degrees) {
        boolean active = degrees != 0;
        int color = active ? 0xFF4CAF50 : 0xFF888888;
        if (toolRotateIcon != null) {
            toolRotateIcon.setTextColor(color);
            // Visually rotate the icon to show current rotation
            toolRotateIcon.setRotation(degrees);
        }
        if (toolRotateLabel != null) {
            toolRotateLabel.setText(active ? (degrees + "\u00B0") : getString(R.string.faditor_tool_rotate));
            toolRotateLabel.setTextColor(color);
        }
    }

    // ── Flip ─────────────────────────────────────────────────────────

    private void showFlipPicker() {
        Clip clip = getSelectedClip();
        FlipPickerBottomSheet sheet = FlipPickerBottomSheet.newInstance(
                clip.isFlipHorizontal(), clip.isFlipVertical());
        sheet.setCallback((flipH, flipV) -> {
            clip.setFlipHorizontal(flipH);
            clip.setFlipVertical(flipV);
            updateFlipUI(flipH, flipV);
            updatePreviewTransforms();
            scheduleAutoSave();
        });
        sheet.show(getSupportFragmentManager(), "flipPicker");
    }

    private void updateFlipUI(boolean flipH, boolean flipV) {
        boolean active = flipH || flipV;
        int color = active ? 0xFF4CAF50 : 0xFF888888;
        if (toolFlipIcon != null) {
            toolFlipIcon.setTextColor(color);
            // Mirror the icon when horizontally flipped
            toolFlipIcon.setScaleX(flipH ? -1f : 1f);
            toolFlipIcon.setScaleY(flipV ? -1f : 1f);
        }
        if (toolFlipLabel != null) {
            String label;
            if (flipH && flipV) label = "H+V";
            else if (flipH) label = "H";
            else if (flipV) label = "V";
            else label = getString(R.string.faditor_tool_flip);
            toolFlipLabel.setText(label);
            toolFlipLabel.setTextColor(color);
        }
    }

    // ── Crop ─────────────────────────────────────────────────────────

    private void showCropPicker() {
        Clip clip = getSelectedClip();
        CropPickerBottomSheet sheet = CropPickerBottomSheet.newInstance(clip.getCropPreset());
        sheet.setCallback(preset -> {
            if ("custom".equals(preset)) {
                // Activate free crop overlay
                clip.setCropPreset("custom");
                activateFreeCrop();
            } else {
                // Deactivate free crop overlay if active
                if (cropOverlay != null && cropOverlay.isActive()) {
                    cropOverlay.deactivate();
                }
                clip.setCropPreset(preset);
                // Reset custom bounds when using a preset
                if (!"none".equals(preset)) {
                    // Store the preset bounds for later reference
                    clip.setCustomCropBounds(0f, 0f, 1f, 1f);
                }
            }
            updateCropUI(clip.getCropPreset());
            updatePreviewTransforms();
            scheduleAutoSave();
        });
        sheet.show(getSupportFragmentManager(), "cropPicker");
    }

    /**
     * Activate the free crop overlay on top of the video preview.
     */
    private void activateFreeCrop() {
        if (cropOverlay == null) return;

        // Compute the video content rect inside the player view
        // PlayerView with resize_mode="fit" centres the video
        playerView.post(() -> {
            android.graphics.RectF videoRect = computeVideoContentRect();
            cropOverlay.setVideoContentRect(videoRect);

            Clip clip = getSelectedClip();
            if ("custom".equals(clip.getCropPreset())
                    && (clip.getCropLeft() > 0 || clip.getCropTop() > 0
                    || clip.getCropRight() < 1 || clip.getCropBottom() < 1)) {
                // Restore previous custom crop bounds
                cropOverlay.activate(
                        clip.getCropLeft(), clip.getCropTop(),
                        clip.getCropRight(), clip.getCropBottom());
            } else {
                cropOverlay.activate();
            }

            cropOverlay.setOnCropChangeListener((left, top, right, bottom) -> {
                clip.setCustomCropBounds(left, top, right, bottom);
                scheduleAutoSave();
            });
        });
    }

    /**
     * Compute where the video content is rendered inside the PlayerView.
     * Accounts for letterboxing (fit mode).
     */
    @NonNull
    private android.graphics.RectF computeVideoContentRect() {
        int viewW = playerView.getWidth();
        int viewH = playerView.getHeight();

        // Try to get actual video dimensions
        if (playerManager != null && playerManager.getPlayer() != null) {
            androidx.media3.common.VideoSize videoSize =
                    playerManager.getPlayer().getVideoSize();
            int videoW = videoSize.width;
            int videoH = videoSize.height;

            if (videoW > 0 && videoH > 0) {
                float videoAspect = (float) videoW / videoH;
                float viewAspect = (float) viewW / viewH;

                float renderW, renderH;
                if (videoAspect > viewAspect) {
                    // Video is wider — letterbox top/bottom
                    renderW = viewW;
                    renderH = viewW / videoAspect;
                } else {
                    // Video is taller — pillarbox left/right
                    renderH = viewH;
                    renderW = viewH * videoAspect;
                }

                float left = (viewW - renderW) / 2f;
                float top = (viewH - renderH) / 2f;
                return new android.graphics.RectF(left, top, left + renderW, top + renderH);
            }
        }

        // Fallback: assume full view
        return new android.graphics.RectF(0, 0, viewW, viewH);
    }

    private void updateCropUI(@NonNull String preset) {
        boolean active = !"none".equals(preset);
        int color = active ? 0xFF4CAF50 : 0xFF888888;
        if (toolCropIcon != null) {
            toolCropIcon.setTextColor(color);
        }
        if (toolCropLabel != null) {
            String label;
            if ("custom".equals(preset)) {
                label = getString(R.string.faditor_tool_crop_free);
            } else if (active) {
                label = preset;
            } else {
                label = getString(R.string.faditor_tool_crop);
            }
            toolCropLabel.setText(label);
            toolCropLabel.setTextColor(color);
        }
    }

    // ── Canvas ────────────────────────────────────────────────────────

    /**
     * Shows the canvas aspect ratio picker bottom sheet.
     */
    private void showCanvasPicker() {
        CanvasPickerBottomSheet sheet =
                CanvasPickerBottomSheet.newInstance(project.getCanvasPreset());
        sheet.setCallback(preset -> {
            project.setCanvasPreset(preset);
            updateCanvasUI(preset);
            applyCanvasPreview(preset);
            saveProjectNow();

            // Format label for toast
            String displayLabel = preset.replace("_", ":");
            if ("original".equals(preset)) displayLabel = "Original";
            Toast.makeText(this, getString(R.string.faditor_canvas_applied, displayLabel), Toast.LENGTH_SHORT).show();
        });
        sheet.show(getSupportFragmentManager(), "canvas_picker");
    }

    /**
     * Updates the canvas tool button UI to reflect the current preset.
     */
    private void updateCanvasUI(@NonNull String preset) {
        boolean active = !"original".equals(preset);
        int color = active ? 0xFF4CAF50 : 0xFF888888;
        if (toolCanvasIcon != null) {
            toolCanvasIcon.setTextColor(color);
        }
        if (toolCanvasLabel != null) {
            String label = active ? preset.replace("_", ":") :
                    getString(R.string.faditor_tool_canvas);
            toolCanvasLabel.setText(label);
            toolCanvasLabel.setTextColor(color);
        }
    }

    /**
     * Adjust player container to preview the canvas aspect ratio.
     * When a canvas preset is active, constrains PlayerView to the target aspect ratio
     * so black bars are visible around the video.
     */
    private void applyCanvasPreview(@NonNull String preset) {
        if ("original".equals(preset)) {
            // Reset to fill container
            ViewGroup.LayoutParams lp = playerView.getLayoutParams();
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            playerView.setLayoutParams(lp);

            ViewGroup.LayoutParams ipLp = imagePreview.getLayoutParams();
            ipLp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            ipLp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            imagePreview.setLayoutParams(ipLp);
            return;
        }

        // Get first clip's dimensions to compute canvas size
        Clip firstClip = project.getTimeline().getClip(0);
        int srcW = getVideoWidth(firstClip);
        int srcH = getVideoHeight(firstClip);
        if (srcW <= 0 || srcH <= 0) return;

        int[] canvas = CanvasPickerBottomSheet.resolveCanvasDimensions(preset, srcW, srcH);
        float canvasAspect = (float) canvas[0] / canvas[1];

        // Apply aspect ratio constraint on PlayerView within the container
        FrameLayout container = findViewById(R.id.player_container);
        int containerW = container.getWidth();
        int containerH = container.getHeight();
        if (containerW <= 0 || containerH <= 0) return;

        float containerAspect = (float) containerW / containerH;
        int targetW, targetH;
        if (canvasAspect > containerAspect) {
            // Canvas is wider: fit to width
            targetW = containerW;
            targetH = Math.round(containerW / canvasAspect);
        } else {
            // Canvas is taller: fit to height
            targetH = containerH;
            targetW = Math.round(containerH * canvasAspect);
        }

        // Constrain PlayerView
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(targetW, targetH);
        lp.gravity = android.view.Gravity.CENTER;
        playerView.setLayoutParams(lp);

        // Constrain ImagePreview too
        FrameLayout.LayoutParams ipLp = new FrameLayout.LayoutParams(targetW, targetH);
        ipLp.gravity = android.view.Gravity.CENTER;
        imagePreview.setLayoutParams(ipLp);
    }

    /**
     * Gets video width from clip using MediaMetadataRetriever.
     */
    private int getVideoWidth(@NonNull Clip clip) {
        if (clip.isImageClip()) {
            try (InputStream is = getContentResolver().openInputStream(clip.getSourceUri())) {
                android.graphics.BitmapFactory.Options opts =
                        new android.graphics.BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeStream(is, null, opts);
                return opts.outWidth;
            } catch (Exception e) { return 0; }
        }
        try {
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            r.setDataSource(this, clip.getSourceUri());
            String w = r.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            r.release();
            return w != null ? Integer.parseInt(w) : 0;
        } catch (Exception e) { return 0; }
    }

    /**
     * Gets video height from clip using MediaMetadataRetriever.
     */
    private int getVideoHeight(@NonNull Clip clip) {
        if (clip.isImageClip()) {
            try (InputStream is = getContentResolver().openInputStream(clip.getSourceUri())) {
                android.graphics.BitmapFactory.Options opts =
                        new android.graphics.BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeStream(is, null, opts);
                return opts.outHeight;
            } catch (Exception e) { return 0; }
        }
        try {
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            r.setDataSource(this, clip.getSourceUri());
            String h = r.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            r.release();
            return h != null ? Integer.parseInt(h) : 0;
        } catch (Exception e) { return 0; }
    }

    // ── Live Preview Transforms ──────────────────────────────────────

    /**
     * Apply rotation, flip, and crop transforms to the PlayerView for live preview.
     */
    private void updatePreviewTransforms() {
        if (project == null || project.getTimeline().isEmpty()) return;
        Clip clip = getSelectedClip();

        int degrees = clip.getRotationDegrees();
        boolean flipH = clip.isFlipHorizontal();
        boolean flipV = clip.isFlipVertical();

        // Apply rotation
        playerView.setRotation(degrees);

        // Calculate scale for 90/270 rotation (video dimensions swap)
        float flipScaleX = flipH ? -1f : 1f;
        float flipScaleY = flipV ? -1f : 1f;

        if (degrees == 90 || degrees == 270) {
            // When rotated 90 or 270, the video overflows its container.
            // Scale down so rotated content fits within the original bounds.
            int w = playerView.getWidth();
            int h = playerView.getHeight();
            if (w > 0 && h > 0) {
                float scale = Math.min((float) w / h, (float) h / w);
                playerView.setScaleX(flipScaleX * scale);
                playerView.setScaleY(flipScaleY * scale);
            } else {
                // Fallback: apply without scale adjustment
                playerView.setScaleX(flipScaleX);
                playerView.setScaleY(flipScaleY);
            }
        } else {
            playerView.setScaleX(flipScaleX);
            playerView.setScaleY(flipScaleY);
        }

        // Crop preview: apply clip bounds on the PlayerView
        String cropPreset = clip.getCropPreset();
        if ("custom".equals(cropPreset)) {
            // Free crop: the overlay handles visual feedback
            // Don't clip the player view — the overlay shows the crop
        } else if (!"none".equals(cropPreset)) {
            // For preset crops, we could clip the view but it's complex
            // with rotation. The exported result is what matters.
        }
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

                    // Notify Records tab to auto-refresh when user navigates back
                    com.fadcam.ui.RecordsFragment.requestRefresh();
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

        Clip clip = getSelectedClip();
        // Image clips have a fixed duration; no correction needed
        if (clip.isImageClip()) return;

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
            refreshTotalTimeDisplay();
            updateCurrentTimeDisplay(0);
            editorTimeline.setTrimFromClip(clip);
            editorTimeline.setPlayheadFraction(0f);

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

    // ── Time display helpers ─────────────────────────────────────────

    /**
     * Refresh the total time display to show full project duration.
     * Should be called whenever clips are added, removed, trimmed, or speed-changed.
     */
    private void refreshTotalTimeDisplay() {
        if (project == null || project.getTimeline().isEmpty()) return;
        long totalMs = project.getTimeline().getTotalDurationMs();
        timeTotal.setText(TimeFormatter.formatAuto(totalMs));
    }

    /**
     * Compute the absolute playhead position within the entire project timeline.
     * This sums up durations of all preceding segments plus the position within the current one.
     *
     * @param positionInCurrentSegmentMs 0-based position within the current clip's trimmed region
     * @return absolute position in the full project timeline
     */
    private long getAbsolutePlayheadMs(long positionInCurrentSegmentMs) {
        Timeline tl = project.getTimeline();
        long absoluteMs = 0;
        for (int i = 0; i < selectedClipIndex && i < tl.getClipCount(); i++) {
            absoluteMs += tl.getClip(i).getTrimmedDurationMs();
        }
        absoluteMs += positionInCurrentSegmentMs;
        return absoluteMs;
    }

    /**
     * Update the current time display with the absolute playhead position.
     *
     * @param positionInCurrentSegmentMs 0-based position within the current clip's trimmed region
     */
    private void updateCurrentTimeDisplay(long positionInCurrentSegmentMs) {
        long absoluteMs = getAbsolutePlayheadMs(positionInCurrentSegmentMs);
        timeCurrent.setText(TimeFormatter.formatAuto(absoluteMs));
    }

    // ── Image preview helpers ────────────────────────────────────────

    /**
     * Show the image preview overlay and hide the video player.
     *
     * @param imageUri URI of the image to display
     */
    private void showImagePreview(@NonNull Uri imageUri) {
        if (imagePreview == null) return;
        playerView.setVisibility(View.INVISIBLE);
        imagePreview.setVisibility(View.VISIBLE);
        // Use Glide for efficient image loading
        com.bumptech.glide.Glide.with(this)
                .load(imageUri)
                .into(imagePreview);
    }

    /**
     * Hide the image preview overlay and restore the video player.
     */
    private void hideImagePreview() {
        if (imagePreview == null) return;
        imagePreview.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);
    }

    /**
     * Start image clip playback (internal timer, no ExoPlayer).
     *
     * @param startOffsetMs position within the image clip to start from (0-based)
     */
    private void startImagePlayback(long startOffsetMs) {
        imagePlaybackActive = true;
        imagePlaybackStartOffsetMs = startOffsetMs;
        imagePlaybackStartSystemMs = System.currentTimeMillis();
        updatePlayPauseButton(true);
    }

    /**
     * Stop image clip playback timer.
     */
    private void stopImagePlayback() {
        imagePlaybackActive = false;
        updatePlayPauseButton(false);
    }

    /**
     * Get the current elapsed position (ms) within the image clip during playback.
     *
     * @return elapsed position in ms, or 0 if not playing
     */
    private long getImagePlaybackPositionMs() {
        if (!imagePlaybackActive) return imagePlaybackStartOffsetMs;
        long elapsed = System.currentTimeMillis() - imagePlaybackStartSystemMs;
        return imagePlaybackStartOffsetMs + elapsed;
    }

    private void updatePlayheadPosition() {
        if (playerManager == null || project == null || project.getTimeline().isEmpty()) return;

        Clip clip = getSelectedClip();
        long sourceDuration = clip.getSourceDurationMs();
        if (sourceDuration <= 0) return;

        // Don't overwrite playhead while user is dragging
        if (userDragging) return;

        // ── Image clip playback (internal timer) ─────────────────────
        if (clip.isImageClip() && imagePlaybackActive) {
            long positionMs = getImagePlaybackPositionMs();
            long clipDuration = clip.getTrimmedDurationMs();

            if (positionMs >= clipDuration) {
                // Image clip reached its end — auto-advance or pause
                stopImagePlayback();
                Timeline timeline = project.getTimeline();
                int nextIndex = selectedClipIndex + 1;
                if (nextIndex < timeline.getClipCount()) {
                    advanceToSegment(nextIndex, true);
                } else {
                    // Last segment — set playhead to end
                    float endFraction = (float) clip.getOutPointMs() / sourceDuration;
                    lastUserPlayheadFraction = endFraction;
                    editorTimeline.setPlayheadFraction(endFraction);
                    long totalMs = project.getTimeline().getTotalDurationMs();
                    timeCurrent.setText(TimeFormatter.formatAuto(totalMs));
                    Log.d(TAG, "Image playback stopped at last segment end");
                }
                return;
            }

            // Convert position to source fraction for timeline view
            float fraction = (float) (clip.getInPointMs() + positionMs) / sourceDuration;
            fraction = Math.max(0f, Math.min(fraction, 1f));
            lastUserPlayheadFraction = fraction;
            editorTimeline.setPlayheadFraction(fraction);
            updateCurrentTimeDisplay(positionMs);
            return;
        }

        // ── Video clip playback (ExoPlayer) ──────────────────────────
        // Skip ExoPlayer polling for image clips (they don't have media loaded)
        if (clip.isImageClip()) return;

        // Use getPlayWhenReady() instead of isPlaying() to handle buffering state at position 0
        boolean isPlaying = playerManager.getPlayWhenReady();
        
        if (isPlaying) {
            long currentPos = playerManager.getCurrentPosition();
            boolean isAtEnd = playerManager.isAtTrimEnd();
            
            // Enforce trim end — auto-advance to next segment or pause
            if (isAtEnd) {
                Timeline timeline = project.getTimeline();
                int nextIndex = selectedClipIndex + 1;
                if (nextIndex < timeline.getClipCount()) {
                    advanceToSegment(nextIndex, true);
                } else {
                    // Last segment reached its end — pause
                    playerManager.pause();
                    long trimmedDur = clip.getOutPointMs() - clip.getInPointMs();
                    float endFraction = (float) clip.getOutPointMs() / sourceDuration;
                    lastUserPlayheadFraction = endFraction;
                    editorTimeline.setPlayheadFraction(endFraction);
                    // Show total project duration as current (at end)
                    long totalMs = project.getTimeline().getTotalDurationMs();
                    timeCurrent.setText(TimeFormatter.formatAuto(totalMs));
                    updatePlayPauseButton(false);
                    Log.d(TAG, "Playback stopped at last segment end");
                }
                return;
            }

            long position = playerManager.getCurrentPosition(); // 0-based within trim
            long absoluteMs = clip.getInPointMs() + position;
            float fraction = (float) absoluteMs / sourceDuration;
            fraction = Math.max(0f, Math.min(fraction, 1f));

            lastUserPlayheadFraction = fraction;
            editorTimeline.setPlayheadFraction(fraction);
            updateCurrentTimeDisplay(position);
        }
        // ── While PAUSED: do NOT overwrite the user's playhead position ──
    }

    /**
     * Auto-advance to a new segment during playback, handling both image and video clips.
     *
     * @param nextIndex     the segment index to advance to
     * @param autoPlay      whether to start playback immediately
     */
    private void advanceToSegment(int nextIndex, boolean autoPlay) {
        Log.d(TAG, "Auto-advancing to segment " + nextIndex);
        Timeline timeline = project.getTimeline();
        selectedClipIndex = nextIndex;
        Clip nextClip = getSelectedClip();
        editorTimeline.setTimeline(timeline, selectedClipIndex);
        editorTimeline.setTrimFromClip(nextClip);

        // Sync toolbar to new segment
        updateVolumeUI(nextClip.getVolumeLevel(), nextClip.isAudioMuted());
        updateSpeedUI(nextClip.getSpeedMultiplier());
        updateRotateUI(nextClip.getRotationDegrees());
        updateFlipUI(nextClip.isFlipHorizontal(), nextClip.isFlipVertical());
        updateCropUI(nextClip.getCropPreset());

        if (nextClip.isImageClip()) {
            // Image clip: show image preview and start timer
            showImagePreview(nextClip.getSourceUri());
            if (autoPlay) {
                startImagePlayback(0);
            }
        } else {
            // Video clip: load and play
            hideImagePreview();
            playerManager.loadClip(nextClip);
            playerManager.setVolume(nextClip.isAudioMuted() ? 0f : nextClip.getVolumeLevel());
            playerManager.setPlaybackSpeed(nextClip.getSpeedMultiplier());
            updatePreviewTransforms();
            if (autoPlay) {
                playerManager.play();
            }
        }

        float startFraction = (float) nextClip.getInPointMs() / nextClip.getSourceDurationMs();
        editorTimeline.setPlayheadFraction(startFraction);
        updateCurrentTimeDisplay(0);
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


    // ── Add Asset ──────────────────────────────────────────────────

    /**
     * Registers ActivityResultLaunchers for image and video asset picking.
     * Must be called early in onCreate (before onStart).
     */
    private void registerAssetPickers() {
        imagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            onImageAssetPicked(uri);
                        }
                    }
                });

        videoPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            onVideoAssetPicked(uri);
                        }
                    }
                });
    }

    /**
     * Show a bottom sheet letting the user choose between adding an image or video asset.
     */
    private void showAddAssetPicker() {
        AddAssetBottomSheet sheet = AddAssetBottomSheet.newInstance();
        sheet.setCallback(isImage -> {
            if (isImage) {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("image/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                imagePickerLauncher.launch(intent);
            } else {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("video/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                videoPickerLauncher.launch(intent);
            }
        });
        sheet.show(getSupportFragmentManager(), "addAsset");
    }

    /**
     * Handle a picked image URI: create a still-image clip (5 seconds)
     * and add it to the timeline after the currently selected segment.
     */
    private void onImageAssetPicked(@NonNull Uri imageUri) {
        try {
            // Take persistable permission so the URI stays valid across sessions
            try {
                getContentResolver().takePersistableUriPermission(
                        imageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException e) {
                Log.w(TAG, "Could not take persistable URI permission", e);
            }

            Clip imageClip = new Clip(imageUri, IMAGE_CLIP_DURATION_MS);
            imageClip.setImageClip(true);
            imageClip.setAudioMuted(true); // Images have no audio

            // Insert after the currently selected segment
            Timeline timeline = project.getTimeline();
            int insertIndex = selectedClipIndex + 1;
            timeline.addClip(insertIndex, imageClip);

            // Select the new clip
            selectSegment(insertIndex);
            refreshTotalTimeDisplay();
            saveProjectNow();

            Toast.makeText(this, R.string.faditor_asset_added, Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Image asset added at index " + insertIndex
                    + " duration=" + IMAGE_CLIP_DURATION_MS + "ms uri=" + imageUri);
        } catch (Exception e) {
            Log.e(TAG, "Failed to add image asset", e);
            Toast.makeText(this, R.string.faditor_asset_error, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Handle a picked video URI: determine its duration, create a clip,
     * and add it to the timeline after the currently selected segment.
     */
    private void onVideoAssetPicked(@NonNull Uri videoUri) {
        try {
            // Take persistable permission so the URI stays valid across sessions
            try {
                getContentResolver().takePersistableUriPermission(
                        videoUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException e) {
                Log.w(TAG, "Could not take persistable URI permission", e);
            }

            long durationMs = getVideoDuration(videoUri);
            if (durationMs <= 0) {
                Log.w(TAG, "Could not determine video duration for added asset");
                Toast.makeText(this, R.string.faditor_asset_error, Toast.LENGTH_SHORT).show();
                return;
            }

            Clip videoClip = new Clip(videoUri, durationMs);

            // Insert after the currently selected segment
            Timeline timeline = project.getTimeline();
            int insertIndex = selectedClipIndex + 1;
            timeline.addClip(insertIndex, videoClip);

            // Select the new clip
            selectSegment(insertIndex);
            refreshTotalTimeDisplay();
            saveProjectNow();

            Toast.makeText(this, R.string.faditor_asset_added, Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Video asset added at index " + insertIndex
                    + " duration=" + durationMs + "ms uri=" + videoUri);
        } catch (Exception e) {
            Log.e(TAG, "Failed to add video asset", e);
            Toast.makeText(this, R.string.faditor_asset_error, Toast.LENGTH_SHORT).show();
        }
    }

    // ── Segment Operations ──────────────────────────────────────────────────

    /**
     * Split the selected segment at the current playhead position.
     */
    private void splitAtPlayhead() {
        try {
            Clip clip = getSelectedClip();
            if (clip == null) return;

            playerManager.pause();

            long currentPositionMs = playerManager.getCurrentPosition();
            long absoluteSplitMs = clip.getInPointMs() + currentPositionMs;

            Timeline timeline = project.getTimeline();
            int newIndex = timeline.splitAt(selectedClipIndex, absoluteSplitMs);
            if (newIndex < 0) {
                Toast.makeText(this, R.string.faditor_split_error, Toast.LENGTH_SHORT).show();
                return;
            }

            selectSegment(selectedClipIndex);
            saveProjectNow();
            Toast.makeText(this, R.string.faditor_split_success, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "splitAtPlayhead failed", e);
            Toast.makeText(this, R.string.faditor_split_error, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Delete the currently selected segment (cannot delete the last remaining segment).
     */
    private void deleteSelectedSegment() {
        try {
            Timeline timeline = project.getTimeline();
            if (timeline.getClipCount() <= 1) {
                Toast.makeText(this, R.string.faditor_delete_last_segment, Toast.LENGTH_SHORT).show();
                return;
            }

            timeline.removeClip(selectedClipIndex);

            int newIndex = Math.min(selectedClipIndex, timeline.getClipCount() - 1);
            selectSegment(newIndex);
            saveProjectNow();
            Toast.makeText(this, R.string.faditor_segment_deleted, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "deleteSelectedSegment failed", e);
        }
    }

    /**
     * Duplicate the currently selected segment (inserts a copy right after it).
     */
    private void duplicateSelectedSegment() {
        try {
            Timeline timeline = project.getTimeline();
            int newIndex = timeline.duplicateClip(selectedClipIndex);
            if (newIndex < 0) return;

            selectSegment(newIndex);
            saveProjectNow();
            Toast.makeText(this, R.string.faditor_segment_duplicated, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "duplicateSelectedSegment failed", e);
        }
    }
}
