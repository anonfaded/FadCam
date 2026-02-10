package com.fadcam.ui.faditor;

import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
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
import androidx.core.content.res.ResourcesCompat;
import androidx.media3.common.Player;
import androidx.media3.transformer.ExportResult;
import androidx.media3.ui.PlayerView;

import com.fadcam.R;
import com.fadcam.Constants;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.playback.FragmentedMp4Remuxer;
import com.fadcam.ui.InputActionBottomSheetFragment;
import com.fadcam.ui.faditor.export.ExportManager;
import com.fadcam.ui.faditor.export.ExportService;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.player.FaditorPlayerManager;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.fadcam.ui.faditor.timeline.EditorTimelineView;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.undo.EditActions;
import com.fadcam.ui.faditor.undo.UndoManager;
import com.fadcam.ui.faditor.util.TimeFormatter;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaPlayer;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    /** Intent extra key for opening a saved project by ID. */
    public static final String EXTRA_PROJECT_ID = "faditor_project_id";

    /** Default duration for still image clips (milliseconds). */
    private static final long IMAGE_CLIP_DURATION_MS = 5000;

    // ── Core components ──────────────────────────────────────────────
    private FaditorProject project;
    private FaditorPlayerManager playerManager;
    private ExportManager exportManager;
    private SharedPreferencesManager prefsManager;
    private FragmentedMp4Remuxer remuxer;

    // ── Export service binding ────────────────────────────────────────
    private ExportService exportService;
    private boolean exportServiceBound = false;
    private final android.content.ServiceConnection exportServiceConnection =
            new android.content.ServiceConnection() {
                @Override
                public void onServiceConnected(android.content.ComponentName name,
                                               android.os.IBinder service) {
                    ExportService.ExportBinder binder = (ExportService.ExportBinder) service;
                    exportService = binder.getService();
                    exportServiceBound = true;
                    exportService.setServiceListener(exportServiceListener);
                    Log.d(TAG, "ExportService bound");
                }

                @Override
                public void onServiceDisconnected(android.content.ComponentName name) {
                    exportService = null;
                    exportServiceBound = false;
                    Log.d(TAG, "ExportService unbound");
                }
            };

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
    private TextView exportProgressPercent;
    private TextView exportEtaText;
    private TextView exportStatusIcon;
    private TextView exportBtnDone;
    private TextView exportTitle;
    private com.google.android.material.progressindicator.LinearProgressIndicator exportProgressBar;
    private TextView exportInfoText;
    private long exportStartTimeMs;
    private View remuxProgressOverlay;
    private TextView remuxProgressText;
    private com.fadcam.ui.faditor.crop.CropOverlayView cropOverlay;
    private android.widget.ImageView imagePreview;

    // ── Crop mode state ──────────────────────────────────────────────
    private boolean inCropMode = false;
    private long lastBackPressTime = 0;
    private static final long BACK_PRESS_INTERVAL_MS = 2000;
    private String preCropPreset;           // saved on entering crop mode
    private float preCropLeft, preCropTop, preCropRight, preCropBottom; // saved custom bounds
    private View cropToolbar;
    private View controlsSection;

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
    private View toolAudio;
    private TextView toolAudioIcon;
    private TextView toolAudioLabel;

    // ── Undo/Redo ────────────────────────────────────────────────────
    private UndoManager undoManager;
    private TextView btnUndo;
    private TextView btnRedo;

    // Pre-edit state capture for undo recording
    private long preTrimInMs = -1;
    private long preTrimOutMs = -1;
    private long preAudioTrimInMs = -1;
    private long preAudioTrimOutMs = -1;

    // ── Multi-segment state ──────────────────────────────────────────
    /** Index of the currently selected clip/segment in the timeline. */
    private int selectedClipIndex = 0;

    // ── Audio extraction ─────────────────────────────────────────────
    private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor();

    /** MediaPlayers for audio clips — one per clip, synced with playhead. */
    private final List<MediaPlayer> audioPlayers = new ArrayList<>();
    /** Whether each audioPlayer is prepared and ready. */
    private final List<Boolean> audioPlayersReady = new ArrayList<>();

    // ── Audio-tail mode: playhead continues past video for audio ─────
    private boolean audioTailActive = false;
    private long audioTailStartWall = 0;   // SystemClock.elapsedRealtime() when tail started
    private long audioTailStartMs = 0;     // playheadPositionMs when tail started

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
                syncAudioPlayerWithPlayhead();
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
     * Returns the total effective video duration (sum of all video clips' trimmed durations).
     */
    private long totalEffectiveMs() {
        long total = 0;
        Timeline tl = project.getTimeline();
        for (int i = 0; i < tl.getClipCount(); i++) {
            total += tl.getClip(i).getTrimmedDurationMs();
        }
        return total;
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

        // Deactivate crop overlay when switching segments (or reselecting)
        if (inCropMode) {
            exitCropMode(false);
        } else if (cropOverlay != null && cropOverlay.isActive()) {
            cropOverlay.deactivate();
        }

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
        projectStorage = new ProjectStorage(this);
        undoManager = new UndoManager();
        undoManager.setSnapshotRestorer(new UndoManager.SnapshotRestorer() {
            @Nullable
            @Override
            public String captureSnapshot() {
                if (project == null || projectStorage == null) return null;
                return projectStorage.toJson(project);
            }

            @Override
            public void restoreFromSnapshot(@NonNull String projectJson) {
                restoreProjectFromSnapshot(projectJson);
            }
        });

        // Check if opening a saved project by ID
        String projectId = getIntent().getStringExtra(EXTRA_PROJECT_ID);
        if (projectId != null) {
            FaditorProject loaded = projectStorage.load(projectId);
            if (loaded != null && !loaded.getTimeline().isEmpty()) {
                initViews();
                project = loaded;
                Uri playUri = project.getTimeline().getClip(0).getSourceUri();
                // Check if the video needs remuxing
                File sourceFile = resolveToFile(playUri);
                if (sourceFile != null && remuxer.needsRemux(sourceFile)) {
                    if (remuxer.hasRemuxedVersion(sourceFile)) {
                        File remuxed = remuxer.getRemuxedFile(sourceFile);
                        playUri = Uri.fromFile(remuxed);
                    }
                }
                continueLoadFromSavedProject(playUri);
                Log.d(TAG, "Editor opened saved project: " + projectId);
                return;
            }
            Log.w(TAG, "Could not load saved project: " + projectId + ", falling back");
        }

        // Parse video URI from intent (new project)
        Uri videoUri = parseVideoUri();
        if (videoUri == null) {
            Log.e(TAG, "No video URI provided");
            Toast.makeText(this, R.string.faditor_error_no_video, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Initialize components
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
        releaseAudioPlayer();
        audioExecutor.shutdownNow();
        saveProjectNow();
        // Unbind from export service but do NOT cancel — let it continue in background
        if (exportServiceBound) {
            if (exportService != null) {
                exportService.setServiceListener(null);
            }
            unbindService(exportServiceConnection);
            exportServiceBound = false;
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
        controlsSection = findViewById(R.id.controls_section);
        cropToolbar = findViewById(R.id.crop_toolbar);
        editorTimeline = findViewById(R.id.editor_timeline_view);
        editorTimeline.setOnSegmentActionListener(new EditorTimelineView.OnSegmentActionListener() {
            @Override
            public void onSegmentSelected(int index) {
                selectSegment(index);
            }

            @Override
            public void onTrimChanged(int segmentIndex, float startFraction, float endFraction, boolean isLeft) {
                if (!userDragging) {
                    // First drag callback — capture pre-trim values for undo
                    Clip clip = getSelectedClip();
                    if (clip != null) {
                        preTrimInMs = clip.getInPointMs();
                        preTrimOutMs = clip.getOutPointMs();
                    }
                }
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
                long newIn = (long)(startFraction * duration);
                long newOut = (long)(endFraction * duration);

                // Record undo action before applying final values
                if (preTrimInMs >= 0 && (preTrimInMs != newIn || preTrimOutMs != newOut)) {
                    undoManager.recordAction(new EditActions.TrimAction(
                            clip, preTrimInMs, preTrimOutMs, newIn, newOut));
                }
                preTrimInMs = -1;
                preTrimOutMs = -1;

                clip.setInPointMs(newIn);
                clip.setOutPointMs(newOut);
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
                audioTailActive = false;  // Cancel audio-tail on seek
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
                // Don't auto-select segment here — let the user control selection
                // via explicit taps. Just sync the toolbar state to the current segment.
                if (selectedClipIndex >= 0 && selectedClipIndex < project.getTimeline().getClipCount()) {
                    Clip clip = getSelectedClip();
                    updateVolumeUI(clip.getVolumeLevel(), clip.isAudioMuted());
                    updateSpeedUI(clip.getSpeedMultiplier());
                }
            }

            @Override
            public void onSegmentReordered(int fromIndex, int toIndex) {
                Timeline tl = project.getTimeline();
                undoManager.recordAction(new EditActions.ReorderClipAction(
                        tl, fromIndex, toIndex));
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

            @Override
            public void onAudioClipSelected(int audioIndex) {
                Log.d(TAG, "Audio clip selected: " + audioIndex);
                // Deselect video segment when audio clip is selected
                if (audioIndex >= 0 && selectedClipIndex >= 0) {
                    // Keep video segment for player but visual deselection
                    // is handled in EditorTimelineView
                }
            }

            @Override
            public void onAudioTrimChanged(int audioIndex, long inPointMs, long outPointMs, boolean isLeft) {
                // Capture pre-trim values on first drag
                if (preAudioTrimInMs < 0 && project != null) {
                    AudioClip ac = project.getTimeline().getAudioClip(audioIndex);
                    if (ac != null) {
                        preAudioTrimInMs = ac.getInPointMs();
                        preAudioTrimOutMs = ac.getOutPointMs();
                    }
                }
                // Real-time visual feedback during audio trim drag
                Log.d(TAG, "Audio trim changed: index=" + audioIndex
                        + " in=" + inPointMs + " out=" + outPointMs);
            }

            @Override
            public void onAudioTrimFinished(int audioIndex, long inPointMs, long outPointMs) {
                Log.d(TAG, "Audio trim finished: index=" + audioIndex
                        + " in=" + inPointMs + " out=" + outPointMs);
                if (project == null) return;
                AudioClip ac = project.getTimeline().getAudioClip(audioIndex);
                if (ac != null) {
                    // Record undo action
                    if (preAudioTrimInMs >= 0
                            && (preAudioTrimInMs != inPointMs || preAudioTrimOutMs != outPointMs)) {
                        undoManager.recordAction(new EditActions.AudioTrimAction(
                                ac, preAudioTrimInMs, preAudioTrimOutMs,
                                inPointMs, outPointMs));
                    }
                    preAudioTrimInMs = -1;
                    preAudioTrimOutMs = -1;

                    ac.setInPointMs(inPointMs);
                    ac.setOutPointMs(outPointMs);
                    editorTimeline.setAudioClips(project.getTimeline().getAudioClips());
                    // Re-prepare audio player with new trim
                    prepareAudioPlayer();
                    scheduleAutoSave();
                }
            }
        });
        btnPlayPause = findViewById(R.id.btn_play_pause);
        timeCurrent = findViewById(R.id.time_current);
        timeTotal = findViewById(R.id.time_total);
        exportProgressOverlay = findViewById(R.id.export_progress_overlay);
        exportProgressText = findViewById(R.id.export_progress_text);
        exportProgressPercent = findViewById(R.id.export_progress_percent);
        exportEtaText = findViewById(R.id.export_eta_text);
        exportStatusIcon = findViewById(R.id.export_status_icon);
        exportBtnDone = findViewById(R.id.export_btn_done);
        exportTitle = findViewById(R.id.export_title);
        exportProgressBar = findViewById(R.id.export_progress_bar);
        exportInfoText = findViewById(R.id.export_info_text);
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
        toolAudio = findViewById(R.id.tool_audio);
        toolAudioIcon = findViewById(R.id.tool_audio_icon);
        toolAudioLabel = findViewById(R.id.tool_audio_label);

        // Undo/Redo buttons with count badges
        btnUndo = findViewById(R.id.btn_undo);
        btnRedo = findViewById(R.id.btn_redo);
        TextView undoCount = findViewById(R.id.undo_count);
        TextView redoCount = findViewById(R.id.redo_count);
        btnUndo.setOnClickListener(v -> performUndo());
        btnRedo.setOnClickListener(v -> performRedo());
        undoManager.setOnStateChangedListener((canUndo, canRedo) -> {
            btnUndo.setAlpha(canUndo ? 1.0f : 0.3f);
            btnRedo.setAlpha(canRedo ? 1.0f : 0.3f);
            btnUndo.setEnabled(canUndo);
            btnRedo.setEnabled(canRedo);

            int uCount = undoManager.getUndoCount();
            int rCount = undoManager.getRedoCount();
            undoCount.setText(String.valueOf(uCount));
            undoCount.setVisibility(View.VISIBLE);
            undoCount.setAlpha(uCount > 0 ? 1.0f : 0.3f);
            redoCount.setText(String.valueOf(rCount));
            redoCount.setVisibility(View.VISIBLE);
            redoCount.setAlpha(rCount > 0 ? 1.0f : 0.3f);
        });

        // Segment tools
        findViewById(R.id.tool_split).setOnClickListener(v -> splitAtPlayhead());
        findViewById(R.id.tool_delete).setOnClickListener(v -> deleteSelectedSegment());
        findViewById(R.id.tool_duplicate).setOnClickListener(v -> duplicateSelectedSegment());
        findViewById(R.id.tool_add_asset).setOnClickListener(v -> showAddAssetPicker());

        // Close button — show confirmation bottom sheet
        findViewById(R.id.btn_close).setOnClickListener(v -> {
            if (inCropMode) {
                exitCropMode(false);
                return;
            }
            showCloseConfirmation();
        });
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
     * Finish editor initialisation when loading a saved project.
     * Skips initProject() since the project is already loaded from storage.
     *
     * @param playUri URI to use for preview playback (may be remuxed)
     */
    private void continueLoadFromSavedProject(@NonNull Uri playUri) {
        // Project is already set from saved data, refresh time display
        refreshTotalTimeDisplay();
        initPlayer();
        initTimeline();
        initToolbar();
        initExport();
        initBackHandler();

        // Restore audio clips into timeline view if any exist
        if (project.getTimeline().hasAudioClips()) {
            editorTimeline.setAudioClips(project.getTimeline().getAudioClips());
            updateAudioToolUI();
        }

        // Restore canvas preset
        if (project.getCanvasPreset() != null
                && !"original".equals(project.getCanvasPreset())) {
            applyCanvasPreview(project.getCanvasPreset());
        }

        // Restore undo history from disk
        List<String> descriptions = new ArrayList<>();
        List<String> snapshots = new ArrayList<>();
        if (projectStorage.loadUndoHistory(project.getId(), descriptions, snapshots)) {
            undoManager.loadHistory(descriptions, snapshots);
            Log.d(TAG, "Restored " + descriptions.size() + " undo history entries");
        }

        Log.d(TAG, "Editor loaded saved project: " + project.getId()
                + ", clips=" + project.getTimeline().getClipCount()
                + ", audioClips=" + project.getTimeline().getAudioClipCount());
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
                // Don't override button state during audio-tail (video paused, audio playing)
                if (!audioTailActive) {
                    updatePlayPauseButton(isPlaying);
                }
                // Sync audio player with ExoPlayer state
                if (isPlaying) {
                    syncAndPlayAudioPlayer();
                } else if (!audioTailActive) {
                    pauseAudioPlayer();
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED && !audioTailActive) {
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
    }

    private void initTimeline() {
        Clip clip = getSelectedClip();
        editorTimeline.setTrimFromClip(clip);
        editorTimeline.setTimeline(project.getTimeline(), selectedClipIndex);

        // If audio clips exist (e.g. from a saved project), load them
        if (project.getTimeline().hasAudioClips()) {
            editorTimeline.setAudioClips(project.getTimeline().getAudioClips());
            prepareAudioPlayer();
            updateAudioToolUI();
        }

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
                if (playerManager.isPlaying() || audioTailActive) {
                    audioTailActive = false;
                    playerManager.pause();
                    pauseAudioPlayer();
                    updatePlayPauseButton(false);
                } else {
                    long playheadMs = editorTimeline.getPlayheadPositionMs();
                    long videoEndMs = totalEffectiveMs();
                    long timelineEndMs = editorTimeline.getTimelineEndMs();

                    if (playheadMs >= videoEndMs && timelineEndMs > videoEndMs) {
                        // Playhead is in audio-only region past video
                        // Enter audio-tail mode directly
                        audioTailActive = true;
                        audioTailStartWall = android.os.SystemClock.elapsedRealtime();
                        audioTailStartMs = playheadMs;
                        btnPlayPause.setText("pause");
                        syncAndPlayAudioPlayer();
                        playheadHandler.post(playheadUpdater);
                        Log.d(TAG, "Play from audio-tail region: playhead=" + playheadMs + " videoEnd=" + videoEndMs);
                    } else {
                        playerManager.play();
                        syncAndPlayAudioPlayer();
                    }
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
        toolCrop.setOnClickListener(v -> enterCropMode());

        // Canvas picker (project-level aspect ratio)
        toolCanvas.setOnClickListener(v -> showCanvasPicker());

        // Audio tool
        toolAudio.setOnClickListener(v -> extractAudioFromCurrentClip());

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

        // Crop is stored in the model but NOT shown as overlay on restore.
        // User must tap the Crop tool to enter crop mode.
        // The crop will be applied on export regardless.
    }

    // ── Volume ────────────────────────────────────────────────────────

    private void showVolumeControl() {
        // Check if an audio clip is selected — control its volume instead
        int audioIdx = editorTimeline.getSelectedAudioIndex();
        if (audioIdx >= 0 && project.getTimeline().hasAudioClips()) {
            AudioClip ac = project.getTimeline().getAudioClip(audioIdx);
            if (ac != null) {
                float oldVol = ac.getVolumeLevel();
                boolean oldMuted = ac.isMuted();
                VolumeControlBottomSheet sheet = VolumeControlBottomSheet.newInstance(
                        ac.getVolumeLevel(), ac.isMuted());
                sheet.setCallback((volume, muted) -> {
                    // Record undo action if changed
                    if (oldVol != volume || oldMuted != muted) {
                        undoManager.recordAction(new EditActions.AudioVolumeAction(
                                ac, oldVol, volume));
                        if (oldMuted != muted) {
                            undoManager.recordAction(new EditActions.AudioMuteAction(
                                    ac, oldMuted, muted));
                        }
                    }
                    ac.setVolumeLevel(volume);
                    ac.setMuted(muted);
                    // Update live audio player volume for this clip
                    if (audioIdx < audioPlayers.size() && audioIdx < audioPlayersReady.size()
                            && audioPlayersReady.get(audioIdx)) {
                        float vol = muted ? 0f : volume;
                        audioPlayers.get(audioIdx).setVolume(vol, vol);
                    }
                    updateVolumeUI(volume, muted);
                    scheduleAutoSave();
                });
                sheet.show(getSupportFragmentManager(), "volumeControl");
                return;
            }
        }

        Clip clip = getSelectedClip();
        float oldVol = clip.getVolumeLevel();
        boolean oldMuted = clip.isAudioMuted();

        VolumeControlBottomSheet sheet = VolumeControlBottomSheet.newInstance(
                clip.getVolumeLevel(), clip.isAudioMuted());
        sheet.setCallback((volume, muted) -> {
            // Record undo action if changed
            if (oldVol != volume) {
                undoManager.recordAction(new EditActions.VolumeAction(
                        clip, oldVol, volume));
            }
            if (oldMuted != muted) {
                undoManager.recordAction(new EditActions.MuteAction(
                        clip, oldMuted, oldVol, muted, volume));
            }
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
        float oldSpeed = clip.getSpeedMultiplier();
        SpeedSliderBottomSheet sheet = SpeedSliderBottomSheet.newInstance(clip.getSpeedMultiplier());
        sheet.setCallback(speed -> {
            if (oldSpeed != speed) {
                undoManager.recordAction(new EditActions.SpeedAction(
                        clip, oldSpeed, speed));
            }
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
        int oldDeg = clip.getRotationDegrees();
        int newDeg = (oldDeg + 90) % 360;
        undoManager.recordAction(new EditActions.RotateAction(clip, oldDeg, newDeg));
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
        boolean oldH = clip.isFlipHorizontal();
        boolean oldV = clip.isFlipVertical();
        FlipPickerBottomSheet sheet = FlipPickerBottomSheet.newInstance(
                clip.isFlipHorizontal(), clip.isFlipVertical());
        sheet.setCallback((flipH, flipV) -> {
            if (oldH != flipH) {
                undoManager.recordAction(new EditActions.FlipHorizontalAction(
                        clip, oldH, flipH));
            }
            if (oldV != flipV) {
                undoManager.recordAction(new EditActions.FlipVerticalAction(
                        clip, oldV, flipV));
            }
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

    /** The aspect ratio presets shown in crop mode. */
    private static final String[][] CROP_ASPECT_PRESETS = {
        {"free",  "Free"},
        {"1_1",   "1:1"},
        {"4_3",   "4:3"},
        {"3_4",   "3:4"},
        {"16_9",  "16:9"},
        {"9_16",  "9:16"},
    };

    /**
     * Enters crop mode: shows the crop overlay on the video preview and
     * replaces the bottom controls with a crop-specific toolbar containing
     * Cancel, aspect-ratio presets, and Done buttons.
     */
    private void enterCropMode() {
        if (inCropMode) return;
        inCropMode = true;

        Clip clip = getSelectedClip();

        // Save current state for cancel restoration
        preCropPreset = clip.getCropPreset();
        preCropLeft   = clip.getCropLeft();
        preCropTop    = clip.getCropTop();
        preCropRight  = clip.getCropRight();
        preCropBottom = clip.getCropBottom();

        // Pause playback while cropping
        if (playerManager != null && playerManager.isPlaying()) {
            playerManager.pause();
            pauseAudioPlayer();
            updatePlayPauseButton(false);
        }

        // Swap UI FIRST so the layout reflows before we compute the video rect.
        // The player_container grows taller when controls_section is hidden.
        if (controlsSection != null) controlsSection.setVisibility(View.GONE);
        if (cropToolbar != null) cropToolbar.setVisibility(View.VISIBLE);

        // Build aspect preset chips and wire buttons
        buildCropPresetChips();
        findViewById(R.id.crop_btn_done).setOnClickListener(v -> exitCropMode(true));
        findViewById(R.id.crop_btn_cancel).setOnClickListener(v -> exitCropMode(false));
        findViewById(R.id.crop_btn_reset).setOnClickListener(v -> {
            if (cropOverlay != null) {
                cropOverlay.setLockedAspectRatio(0f);
                // Re-compute videoRect after reset since container may have changed
                android.graphics.RectF videoRect = computeVideoContentRect();
                cropOverlay.setVideoContentRect(videoRect);
                cropOverlay.activate();
                Clip c = getSelectedClip();
                c.setCustomCropBounds(0f, 0f, 1f, 1f);
                highlightActivePresetChip("free");
            }
            Toast.makeText(this, R.string.faditor_crop_reset, Toast.LENGTH_SHORT).show();
        });

        // Set clip to "custom" and defer crop overlay activation until layout is complete.
        // Using OnGlobalLayoutListener ensures the player container has its final
        // dimensions after the controls section went GONE.
        clip.setCropPreset("custom");
        FrameLayout container = findViewById(R.id.player_container);
        container.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        container.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        activateFreeCrop();
                    }
                });

        Log.d(TAG, "Entered crop mode (saved: preset=" + preCropPreset
                + ", bounds=[" + preCropLeft + "," + preCropTop
                + "," + preCropRight + "," + preCropBottom + "])");
    }

    /**
     * Exit crop mode.
     *
     * @param apply {@code true} to keep the crop, {@code false} to revert
     */
    private void exitCropMode(boolean apply) {
        if (!inCropMode) return;
        inCropMode = false;

        Clip clip = getSelectedClip();

        if (apply) {
            // Keep current overlay bounds — they were already saved to the clip
            // via the OnCropChangeListener. Determine if user made a meaningful crop.
            boolean isFullFrame = Math.abs(clip.getCropLeft()) < 0.01f
                    && Math.abs(clip.getCropTop()) < 0.01f
                    && Math.abs(clip.getCropRight() - 1f) < 0.01f
                    && Math.abs(clip.getCropBottom() - 1f) < 0.01f;
            if (isFullFrame) {
                clip.setCropPreset("none");
                clip.setCustomCropBounds(0f, 0f, 1f, 1f);
            }
            // Record undo action for crop change
            String newPreset = clip.getCropPreset();
            float newL = clip.getCropLeft(), newT = clip.getCropTop();
            float newR = clip.getCropRight(), newB = clip.getCropBottom();
            if (!preCropPreset.equals(newPreset)
                    || preCropLeft != newL || preCropTop != newT
                    || preCropRight != newR || preCropBottom != newB) {
                undoManager.recordAction(new EditActions.CropAction(
                        clip,
                        preCropPreset, preCropLeft, preCropTop, preCropRight, preCropBottom,
                        newPreset, newL, newT, newR, newB));
            }
            // else keep "custom" preset with current bounds
            Toast.makeText(this, R.string.faditor_crop_applied, Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Crop applied: preset=" + clip.getCropPreset()
                    + ", bounds=[" + clip.getCropLeft() + "," + clip.getCropTop()
                    + "," + clip.getCropRight() + "," + clip.getCropBottom() + "]");
        } else {
            // Revert to saved state
            clip.setCropPreset(preCropPreset);
            clip.setCustomCropBounds(preCropLeft, preCropTop, preCropRight, preCropBottom);
            Toast.makeText(this, R.string.faditor_crop_cancelled, Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Crop cancelled, reverted to: " + preCropPreset);
        }

        // Deactivate the overlay
        if (cropOverlay != null && cropOverlay.isActive()) {
            cropOverlay.deactivate();
        }

        // Swap: show controls, hide crop toolbar
        if (cropToolbar != null) cropToolbar.setVisibility(View.GONE);
        if (controlsSection != null) controlsSection.setVisibility(View.VISIBLE);

        updateCropUI(clip.getCropPreset());

        // Defer preview transforms until layout has settled — the player container
        // changes size when the controls section becomes visible again.
        FrameLayout container = findViewById(R.id.player_container);
        container.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        container.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        updatePreviewTransforms();
                    }
                });

        scheduleAutoSave();
    }

    /**
     * Builds the aspect-ratio preset chips inside the crop toolbar.
     */
    private void buildCropPresetChips() {
        LinearLayout row = findViewById(R.id.crop_presets_row);
        if (row == null) return;
        row.removeAllViews();

        float dp = getResources().getDisplayMetrics().density;
        Typeface materialIcons = ResourcesCompat.getFont(this, R.font.materialicons);

        for (String[] preset : CROP_ASPECT_PRESETS) {
            String key   = preset[0];
            String label = preset[1];

            TextView chip = new TextView(this);
            chip.setText(label);
            chip.setTextSize(13);
            chip.setTextColor(0xFFCCCCCC);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding((int)(14 * dp), (int)(8 * dp), (int)(14 * dp), (int)(8 * dp));
            chip.setBackgroundResource(R.drawable.settings_home_row_bg);
            chip.setTag(key);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd((int)(6 * dp));
            chip.setLayoutParams(lp);

            chip.setOnClickListener(v -> applyCropAspectPreset(key));
            row.addView(chip);
        }

        // Highlight the default ("free")
        highlightActivePresetChip("free");
    }

    /**
     * Applies an aspect ratio preset to the crop overlay.
     */
    private void applyCropAspectPreset(String key) {
        if (cropOverlay == null || !cropOverlay.isActive()) return;

        float ratio;
        switch (key) {
            case "1_1":  ratio = 1f;            break;
            case "4_3":  ratio = 4f / 3f;       break;
            case "3_4":  ratio = 3f / 4f;       break;
            case "16_9": ratio = 16f / 9f;      break;
            case "9_16": ratio = 9f / 16f;      break;
            default:     ratio = 0f;             break; // free
        }

        cropOverlay.setLockedAspectRatio(ratio);
        highlightActivePresetChip(key);
    }

    /**
     * Highlights the active aspect ratio chip and dims the others.
     */
    private void highlightActivePresetChip(String activeKey) {
        LinearLayout row = findViewById(R.id.crop_presets_row);
        if (row == null) return;

        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof TextView) {
                boolean isActive = activeKey.equals(child.getTag());
                ((TextView) child).setTextColor(isActive ? 0xFF4CAF50 : 0xFFCCCCCC);
                ((TextView) child).setTypeface(null,
                        isActive ? Typeface.BOLD : Typeface.NORMAL);
            }
        }
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
            Log.d(TAG, "activateFreeCrop: videoRect=" + videoRect
                    + ", cropOverlay size=" + cropOverlay.getWidth() + "x" + cropOverlay.getHeight());
            cropOverlay.setVideoContentRect(videoRect);

            Clip clip = getSelectedClip();
            if ("custom".equals(clip.getCropPreset())
                    && (clip.getCropLeft() > 0 || clip.getCropTop() > 0
                    || clip.getCropRight() < 1 || clip.getCropBottom() < 1)) {
                // Restore previous custom crop bounds
                Log.d(TAG, "activateFreeCrop: restoring bounds ["
                        + clip.getCropLeft() + "," + clip.getCropTop()
                        + "," + clip.getCropRight() + "," + clip.getCropBottom() + "]");
                cropOverlay.activate(
                        clip.getCropLeft(), clip.getCropTop(),
                        clip.getCropRight(), clip.getCropBottom());
            } else {
                Log.d(TAG, "activateFreeCrop: full frame (no prior custom bounds)");
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

        // Offset for PlayerView position within its parent (e.g. when canvas preview
        // constrains PlayerView to a smaller centered area inside the container).
        // The crop overlay is match_parent on the container, so we need absolute offsets.
        float offsetX = playerView.getLeft();
        float offsetY = playerView.getTop();

        Log.d(TAG, "computeVideoContentRect: playerView size=" + viewW + "x" + viewH
                + ", offset=(" + offsetX + "," + offsetY + ")");

        // Try to get actual video dimensions
        if (playerManager != null && playerManager.getPlayer() != null) {
            androidx.media3.common.VideoSize videoSize =
                    playerManager.getPlayer().getVideoSize();
            int videoW = videoSize.width;
            int videoH = videoSize.height;

            Log.d(TAG, "computeVideoContentRect: videoSize=" + videoW + "x" + videoH);

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

                float left = offsetX + (viewW - renderW) / 2f;
                float top = offsetY + (viewH - renderH) / 2f;
                android.graphics.RectF result = new android.graphics.RectF(
                        left, top, left + renderW, top + renderH);
                Log.d(TAG, "computeVideoContentRect: result=" + result);
                return result;
            }
        }

        // Fallback: assume full view
        Log.d(TAG, "computeVideoContentRect: FALLBACK to full view");
        return new android.graphics.RectF(offsetX, offsetY, offsetX + viewW, offsetY + viewH);
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
        String oldPreset = project.getCanvasPreset();
        CanvasPickerBottomSheet sheet =
                CanvasPickerBottomSheet.newInstance(project.getCanvasPreset());
        sheet.setCallback(preset -> {
            if (!oldPreset.equals(preset)) {
                undoManager.recordAction(new EditActions.CanvasPresetAction(
                        project, oldPreset, preset));
            }
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

    // ── Audio ─────────────────────────────────────────────────────────

    /**
     * Extracts the audio track from the currently selected video clip,
     * saves it to an AAC file, generates a waveform, and adds an AudioClip
     * to the timeline.
     */
    private void extractAudioFromCurrentClip() {
        Clip clip = getSelectedClip();
        if (clip == null || clip.isImageClip()) {
            Toast.makeText(this, R.string.faditor_audio_no_track, Toast.LENGTH_SHORT).show();
            return;
        }

        Uri videoUri = clip.getSourceUri();
        Toast.makeText(this, R.string.faditor_audio_extracting, Toast.LENGTH_SHORT).show();

        audioExecutor.execute(() -> {
            try {
                // ── Step 1: Extract raw audio to AAC file ────────────────
                MediaExtractor extractor = new MediaExtractor();
                extractor.setDataSource(this, videoUri, null);

                int audioTrackIndex = -1;
                MediaFormat audioFormat = null;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat fmt = extractor.getTrackFormat(i);
                    String mime = fmt.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) {
                        audioTrackIndex = i;
                        audioFormat = fmt;
                        break;
                    }
                }

                if (audioTrackIndex < 0 || audioFormat == null) {
                    runOnUiThread(() -> Toast.makeText(this,
                            R.string.faditor_audio_no_track, Toast.LENGTH_SHORT).show());
                    extractor.release();
                    return;
                }

                long durationUs = audioFormat.containsKey(MediaFormat.KEY_DURATION)
                        ? audioFormat.getLong(MediaFormat.KEY_DURATION) : 0;
                long durationMs = durationUs / 1000;
                if (durationMs <= 0) {
                    // Fallback: use video duration
                    durationMs = clip.getSourceDurationMs();
                }

                // Mux audio track into a proper M4A container (MediaPlayer needs headers)
                extractor.selectTrack(audioTrackIndex);
                File cacheDir = new File(getCacheDir(), "faditor_audio");
                if (!cacheDir.exists()) cacheDir.mkdirs();
                File audioFile = new File(cacheDir,
                        "audio_" + System.currentTimeMillis() + ".m4a");

                MediaMuxer muxer = new MediaMuxer(audioFile.getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                int muxerTrackIndex = muxer.addTrack(audioFormat);
                muxer.start();

                ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                while (true) {
                    buffer.clear();
                    int sampleSize = extractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) break;
                    bufferInfo.offset = 0;
                    bufferInfo.size = sampleSize;
                    bufferInfo.presentationTimeUs = extractor.getSampleTime();
                    bufferInfo.flags = extractor.getSampleFlags();
                    muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo);
                    extractor.advance();
                }

                muxer.stop();
                muxer.release();
                extractor.release();

                Uri audioUri = Uri.fromFile(audioFile);
                final long finalDurationMs = durationMs;

                // ── Step 2: Generate waveform ────────────────────────────
                int[] waveform = generateWaveform(videoUri, audioTrackIndex, 800);

                // ── Step 3: Create AudioClip and add to timeline ─────────
                AudioClip audioClip = new AudioClip(audioUri, finalDurationMs);
                audioClip.setLabel(getString(R.string.faditor_audio_extract_current));
                audioClip.setWaveform(waveform);

                runOnUiThread(() -> {
                    Clip srcClip = getSelectedClip();
                    // Capture pre-extraction state for undo
                    boolean prevMuted = srcClip != null && srcClip.isAudioMuted();
                    float prevVolume = srcClip != null ? srcClip.getVolumeLevel() : 1.0f;

                    project.getTimeline().addAudioClip(audioClip);
                    editorTimeline.setAudioClips(project.getTimeline().getAudioClips());

                    // Record undo action for adding audio clip
                    undoManager.recordAction(new EditActions.AddAudioClipAction(
                            project.getTimeline(), audioClip,
                            srcClip, prevMuted, prevVolume));

                    // Mute the source video clip since audio is now on a separate track
                    if (srcClip != null) {
                        srcClip.setAudioMuted(true);
                        srcClip.setVolumeLevel(0f);
                        playerManager.setVolume(0f);
                        updateVolumeUI(0f, true);
                        Log.i(TAG, "Auto-muted video clip after audio extraction: "
                                + "audioMuted=" + srcClip.isAudioMuted()
                                + ", volumeLevel=" + srcClip.getVolumeLevel());
                    } else {
                        Log.w(TAG, "Could not auto-mute: getSelectedClip() returned null");
                    }

                    updateAudioToolUI();
                    prepareAudioPlayer();
                    scheduleAutoSave();
                    Toast.makeText(this, R.string.faditor_audio_extracted,
                            Toast.LENGTH_SHORT).show();
                });

            } catch (Exception e) {
                Log.e(TAG, "Audio extraction failed", e);
                runOnUiThread(() -> Toast.makeText(this,
                        R.string.faditor_audio_extract_failed, Toast.LENGTH_SHORT).show());
            }
        });
    }

    /**
     * Decodes audio from the given URI and produces a downsampled amplitude
     * array for waveform visualisation.
     *
     * @param uri              source media URI
     * @param audioTrackIndex  index of the audio track in the container
     * @param targetSamples    desired number of waveform bars
     * @return amplitude array (0–255), or null on failure
     */
    @Nullable
    private int[] generateWaveform(@NonNull Uri uri, int audioTrackIndex, int targetSamples) {
        try {
            MediaExtractor extractor = new MediaExtractor();
            extractor.setDataSource(this, uri, null);
            extractor.selectTrack(audioTrackIndex);

            MediaFormat format = extractor.getTrackFormat(audioTrackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) {
                extractor.release();
                return null;
            }

            MediaCodec codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            ByteBuffer[] inputBuffers = codec.getInputBuffers();
            ByteBuffer[] outputBuffers = codec.getOutputBuffers();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            // Collect all decoded PCM samples (short values)
            List<Short> allSamples = new ArrayList<>();
            boolean inputDone = false;
            boolean outputDone = false;

            while (!outputDone) {
                // Feed input
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(10_000);
                    if (inIdx >= 0) {
                        ByteBuffer buf = inputBuffers[inIdx];
                        int sampleSize = extractor.readSampleData(buf, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                // Drain output
                int outIdx = codec.dequeueOutputBuffer(info, 10_000);
                if (outIdx >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                    ByteBuffer outBuf = outputBuffers[outIdx];
                    outBuf.position(info.offset);
                    outBuf.limit(info.offset + info.size);
                    ShortBuffer shorts = outBuf.asShortBuffer();
                    while (shorts.hasRemaining()) {
                        allSamples.add(shorts.get());
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                } else if (outIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = codec.getOutputBuffers();
                }
            }

            codec.stop();
            codec.release();
            extractor.release();

            if (allSamples.isEmpty()) return null;

            // Downsample to targetSamples bins
            int totalSamples = allSamples.size();
            int samplesPerBin = Math.max(1, totalSamples / targetSamples);
            int[] waveform = new int[Math.min(targetSamples, totalSamples)];
            for (int i = 0; i < waveform.length; i++) {
                long sum = 0;
                int start = i * samplesPerBin;
                int end = Math.min(start + samplesPerBin, totalSamples);
                for (int j = start; j < end; j++) {
                    sum += Math.abs(allSamples.get(j));
                }
                long avg = sum / (end - start);
                // Normalise to 0–255
                waveform[i] = (int) Math.min(255, (avg * 255) / 32768);
            }
            return waveform;

        } catch (Exception e) {
            Log.e(TAG, "Waveform generation failed", e);
            return null;
        }
    }

    /**
     * Updates the audio tool button UI based on whether audio clips exist.
     */
    private void updateAudioToolUI() {
        boolean hasAudio = project != null && project.getTimeline().hasAudioClips();
        int color = hasAudio ? 0xFF4CAF50 : 0xFF888888;
        if (toolAudioIcon != null) toolAudioIcon.setTextColor(color);
        if (toolAudioLabel != null) toolAudioLabel.setTextColor(color);
    }

    // ── Audio player (playback sync) ─────────────────────────────────

    /**
     * Prepares MediaPlayers for ALL audio clips in the timeline.
     * One MediaPlayer per clip, each pre-prepared for instant playback.
     */
    private void prepareAudioPlayer() {
        releaseAudioPlayer();
        if (project == null || !project.getTimeline().hasAudioClips()) return;

        List<AudioClip> clips = project.getTimeline().getAudioClips();
        for (int i = 0; i < clips.size(); i++) {
            AudioClip ac = clips.get(i);
            if (ac == null) continue;
            final int idx = i;

            try {
                MediaPlayer mp = new MediaPlayer();
                mp.setDataSource(this, ac.getSourceUri());
                mp.setLooping(false);
                float vol = ac.isMuted() ? 0f : ac.getVolumeLevel();
                mp.setVolume(vol, vol);

                audioPlayers.add(mp);
                audioPlayersReady.add(false);

                mp.setOnPreparedListener(p -> {
                    if (idx < audioPlayersReady.size()) {
                        audioPlayersReady.set(idx, true);
                    }
                    Log.d(TAG, "AudioPlayer[" + idx + "] prepared, duration=" + p.getDuration() + "ms");
                });
                mp.setOnErrorListener((p, what, extra) -> {
                    Log.e(TAG, "AudioPlayer[" + idx + "] error: what=" + what + " extra=" + extra);
                    if (idx < audioPlayersReady.size()) {
                        audioPlayersReady.set(idx, false);
                    }
                    return true;
                });
                mp.prepareAsync();
            } catch (Exception e) {
                Log.e(TAG, "Failed to prepare audio player[" + i + "]", e);
            }
        }
    }

    /**
     * Syncs and plays the correct audio player(s) for the current playhead position.
     * Called once when user presses play.
     */
    private void syncAndPlayAudioPlayer() {
        if (project == null || !project.getTimeline().hasAudioClips()) return;

        long playheadMs = editorTimeline.getPlayheadPositionMs();
        List<AudioClip> clips = project.getTimeline().getAudioClips();

        for (int i = 0; i < clips.size() && i < audioPlayers.size(); i++) {
            if (i >= audioPlayersReady.size() || !audioPlayersReady.get(i)) continue;
            AudioClip ac = clips.get(i);
            MediaPlayer mp = audioPlayers.get(i);
            if (ac == null || mp == null) continue;

            long audioStartMs = ac.getOffsetMs();
            long audioEndMs = ac.getEndOnTimelineMs();

            try {
                if (playheadMs >= audioStartMs && playheadMs < audioEndMs) {
                    long seekPos = ac.getInPointMs() + (playheadMs - audioStartMs);
                    mp.seekTo((int) seekPos);
                    float vol = ac.isMuted() ? 0f : ac.getVolumeLevel();
                    mp.setVolume(vol, vol);
                    mp.start();
                } else {
                    if (mp.isPlaying()) mp.pause();
                }
            } catch (Exception e) {
                Log.e(TAG, "AudioPlayer[" + i + "] sync error", e);
            }
        }
    }

    /**
     * Periodically called from playheadUpdater to start/stop audio players
     * as the playhead enters/exits each audio clip range during playback.
     */
    private void syncAudioPlayerWithPlayhead() {
        if (project == null || !project.getTimeline().hasAudioClips()) return;

        // Only sync when something is supposed to be playing
        boolean videoPlaying = playerManager != null && playerManager.getPlayWhenReady();
        boolean imageActive = imagePlaybackActive;
        if (!videoPlaying && !imageActive && !audioTailActive) return;

        long playheadMs = editorTimeline.getPlayheadPositionMs();
        List<AudioClip> clips = project.getTimeline().getAudioClips();

        for (int i = 0; i < clips.size() && i < audioPlayers.size(); i++) {
            if (i >= audioPlayersReady.size() || !audioPlayersReady.get(i)) continue;
            AudioClip ac = clips.get(i);
            MediaPlayer mp = audioPlayers.get(i);
            if (ac == null || mp == null) continue;

            long audioStartMs = ac.getOffsetMs();
            long audioEndMs = ac.getEndOnTimelineMs();

            try {
                if (playheadMs >= audioStartMs && playheadMs < audioEndMs) {
                    if (!mp.isPlaying()) {
                        long seekPos = ac.getInPointMs() + (playheadMs - audioStartMs);
                        mp.seekTo((int) seekPos);
                        float vol = ac.isMuted() ? 0f : ac.getVolumeLevel();
                        mp.setVolume(vol, vol);
                        mp.start();
                    }
                } else {
                    if (mp.isPlaying()) {
                        mp.pause();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Audio sync[" + i + "] error", e);
            }
        }
    }

    /**
     * Pauses all audio players.
     */
    private void pauseAudioPlayer() {
        for (int i = 0; i < audioPlayers.size(); i++) {
            if (i >= audioPlayersReady.size() || !audioPlayersReady.get(i)) continue;
            try {
                MediaPlayer mp = audioPlayers.get(i);
                if (mp != null && mp.isPlaying()) mp.pause();
            } catch (Exception ignored) {}
        }
    }

    /**
     * Releases all audio player resources.
     */
    private void releaseAudioPlayer() {
        for (MediaPlayer mp : audioPlayers) {
            if (mp != null) {
                try { mp.stop(); } catch (Exception ignored) {}
                try { mp.release(); } catch (Exception ignored) {}
            }
        }
        audioPlayers.clear();
        audioPlayersReady.clear();
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

        // Base scale: flip + 90/270 shrink
        float baseScaleX = flipH ? -1f : 1f;
        float baseScaleY = flipV ? -1f : 1f;

        if (degrees == 90 || degrees == 270) {
            int w = playerView.getWidth();
            int h = playerView.getHeight();
            if (w > 0 && h > 0) {
                float rotScale = Math.min((float) w / h, (float) h / w);
                baseScaleX *= rotScale;
                baseScaleY *= rotScale;
            }
        }

        // ── Crop preview ─────────────────────────────────────────────
        // When a crop is applied and we're NOT in crop-mode (overlay active),
        // clip the PlayerView to the crop region and scale it to fill the view.
        FrameLayout container = findViewById(R.id.player_container);
        String cropPreset = clip.getCropPreset();
        boolean applyCropZoom = false;

        if ("custom".equals(cropPreset) && !inCropMode) {
            float cropL = clip.getCropLeft();
            float cropT = clip.getCropTop();
            float cropR = clip.getCropRight();
            float cropB = clip.getCropBottom();
            float cropW = cropR - cropL;
            float cropH = cropB - cropT;

            if (cropW > 0.01f && cropH > 0.01f
                    && (cropW < 0.99f || cropH < 0.99f)) {

                int viewW = playerView.getWidth();
                int viewH = playerView.getHeight();
                if (viewW > 0 && viewH > 0) {
                    // Compute video render rect inside PlayerView (no parent offset)
                    float renderW = viewW, renderH = viewH;
                    float videoLeft = 0f, videoTop = 0f;

                    if (playerManager != null && playerManager.getPlayer() != null) {
                        androidx.media3.common.VideoSize vs =
                                playerManager.getPlayer().getVideoSize();
                        if (vs.width > 0 && vs.height > 0) {
                            float vidAspect = (float) vs.width / vs.height;
                            float viewAspect = (float) viewW / viewH;
                            if (vidAspect > viewAspect) {
                                renderW = viewW;
                                renderH = viewW / vidAspect;
                            } else {
                                renderH = viewH;
                                renderW = viewH * vidAspect;
                            }
                            videoLeft = (viewW - renderW) / 2f;
                            videoTop = (viewH - renderH) / 2f;
                        }
                    }

                    // Crop rect in PlayerView's own coordinate space
                    float cL = videoLeft + cropL * renderW;
                    float cT = videoTop + cropT * renderH;
                    float cR = videoLeft + cropR * renderW;
                    float cB = videoTop + cropB * renderH;

                    // Clip the PlayerView so only the crop region is drawn
                    playerView.setClipBounds(new android.graphics.Rect(
                            Math.round(cL), Math.round(cT),
                            Math.round(cR), Math.round(cB)));

                    // Scale up so the clipped crop region fills the container
                    float cropPixW = cR - cL;
                    float cropPixH = cB - cT;
                    float sX = (float) viewW / cropPixW;
                    float sY = (float) viewH / cropPixH;
                    float cropScale = Math.min(sX, sY);

                    baseScaleX *= cropScale;
                    baseScaleY *= cropScale;

                    // Crop center in PlayerView coordinates
                    float cropCX = (cL + cR) / 2f;
                    float cropCY = (cT + cB) / 2f;

                    // Pivot stays at view center. We need translation so that
                    // the crop center maps to the container center after scaling.
                    // With pivot = (vW/2, vH/2):
                    //   mapped_x = vW/2 + totalScaleX*(cx - vW/2) + tx
                    //   Want mapped_x = vW/2 → tx = -totalScaleX*(cx - vW/2)
                    // Use absolute cropScale for translation (flip sign handled by scaleX).
                    float tx = cropScale * (viewW / 2f - cropCX);
                    float ty = cropScale * (viewH / 2f - cropCY);

                    playerView.setPivotX(viewW / 2f);
                    playerView.setPivotY(viewH / 2f);
                    playerView.setTranslationX(tx);
                    playerView.setTranslationY(ty);

                    container.setClipChildren(true);
                    container.setClipToPadding(true);
                    applyCropZoom = true;
                }
            }
        }

        if (!applyCropZoom) {
            // Reset crop-related transforms
            playerView.setClipBounds(null);
            playerView.setPivotX(playerView.getWidth() / 2f);
            playerView.setPivotY(playerView.getHeight() / 2f);
            playerView.setTranslationX(0f);
            playerView.setTranslationY(0f);
            if (container != null) {
                container.setClipChildren(false);
                container.setClipToPadding(false);
            }
        }

        playerView.setScaleX(baseScaleX);
        playerView.setScaleY(baseScaleY);
    }

    private void initExport() {
        exportManager = new ExportManager(this, prefsManager);

        // Export button → show confirmation bottom sheet
        findViewById(R.id.btn_export).setOnClickListener(v -> showExportConfirmation());

        // Cancel export button
        findViewById(R.id.btn_cancel_export).setOnClickListener(v -> {
            if (exportServiceBound && exportService != null) {
                exportService.cancelExport();
            }
            hideExportProgress();
            Toast.makeText(this, R.string.faditor_export_cancelled, Toast.LENGTH_SHORT).show();
        });

        // Back button on export screen → cancel and return to editor
        findViewById(R.id.export_btn_back).setOnClickListener(v -> {
            if (exportServiceBound && exportService != null && exportService.isExporting()) {
                exportService.cancelExport();
            }
            hideExportProgress();
        });

        // Done button → navigate to Faditor Mini tab
        if (exportBtnDone != null) {
            exportBtnDone.setOnClickListener(v -> {
                hideExportProgress();
                saveProjectNow();
                finish();
            });
        }
    }

    /**
     * Service listener for export progress/completion events.
     * Updates the in-app overlay UI. If the Activity is destroyed, events
     * are only reflected via the service's notification.
     */
    private final ExportService.ExportServiceListener exportServiceListener =
            new ExportService.ExportServiceListener() {
                @Override
                public void onExportStarted(@NonNull String outputPath) {
                    exportStartTimeMs = System.currentTimeMillis();
                    runOnUiThread(() -> showExportProgress());
                }

                @Override
                public void onExportProgress(float progress) {
                    runOnUiThread(() -> {
                        int percent = (int) (progress * 100);

                        if (exportProgressPercent != null) {
                            exportProgressPercent.setText(percent + "%");
                        }
                        if (exportProgressBar != null) {
                            exportProgressBar.setIndeterminate(false);
                            exportProgressBar.setProgress(percent);
                        }
                        if (exportProgressText != null) {
                            exportProgressText.setText(
                                    getString(R.string.faditor_exporting_percent, percent));
                        }

                        // Compute ETA
                        if (progress > 0.05f && exportEtaText != null) {
                            long elapsed = System.currentTimeMillis() - exportStartTimeMs;
                            long totalEstimated = (long) (elapsed / progress);
                            long remainingMs = totalEstimated - elapsed;
                            exportEtaText.setText(getString(R.string.faditor_export_eta,
                                    formatEta(remainingMs)));
                            exportEtaText.setVisibility(View.VISIBLE);
                        }
                    });
                }

                @Override
                public void onExportCompleted(@NonNull String outputPath,
                                              @NonNull ExportResult result) {
                    runOnUiThread(() -> {
                        Log.d(TAG, "Export saved to: " + outputPath);

                        if (exportProgressPercent != null) exportProgressPercent.setText("100%");
                        if (exportProgressBar != null) exportProgressBar.setProgress(100);
                        if (exportProgressText != null) {
                            exportProgressText.setText(R.string.faditor_export_complete_summary);
                            exportProgressText.setTextColor(0xFFFFFFFF);
                        }
                        if (exportStatusIcon != null) exportStatusIcon.setText("check_circle");
                        if (exportEtaText != null) exportEtaText.setVisibility(View.GONE);
                        if (exportInfoText != null) exportInfoText.setVisibility(View.GONE);
                        if (exportTitle != null) {
                            exportTitle.setText(R.string.faditor_export_complete_title);
                        }
                        if (exportProgressBar != null) {
                            exportProgressBar.setIndeterminate(false);
                            exportProgressBar.setProgress(100);
                        }

                        View cancelBtn = findViewById(R.id.btn_cancel_export);
                        if (cancelBtn != null) cancelBtn.setVisibility(View.GONE);
                        if (exportBtnDone != null) exportBtnDone.setVisibility(View.VISIBLE);

                        View backBtn = findViewById(R.id.export_btn_back);
                        if (backBtn != null) backBtn.setVisibility(View.INVISIBLE);

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
            };

    /**
     * Format remaining time estimate into human-readable string.
     */
    @NonNull
    private String formatEta(long remainingMs) {
        long seconds = remainingMs / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        seconds = seconds % 60;
        if (minutes < 60) return minutes + "m " + seconds + "s";
        long hours = minutes / 60;
        minutes = minutes % 60;
        return hours + "h " + minutes + "m";
    }

    private void initBackHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // If in crop mode, back = cancel crop
                if (inCropMode) {
                    exitCropMode(false);
                    return;
                }

                // Double-press to exit
                long now = System.currentTimeMillis();
                if (now - lastBackPressTime < BACK_PRESS_INTERVAL_MS) {
                    handleClose();
                } else {
                    lastBackPressTime = now;
                    Toast.makeText(FaditorEditorActivity.this,
                            R.string.faditor_back_press_exit, Toast.LENGTH_SHORT).show();
                }
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

        // ── Audio-tail mode: playhead continues past video end ───────
        if (audioTailActive) {
            long elapsed = android.os.SystemClock.elapsedRealtime() - audioTailStartWall;
            long playheadMs = audioTailStartMs + elapsed;
            long timelineEndMs = editorTimeline.getTimelineEndMs();

            if (playheadMs >= timelineEndMs) {
                // Audio tail finished — fully stop
                audioTailActive = false;
                pauseAudioPlayer();
                playheadMs = timelineEndMs;
                updatePlayPauseButton(false);
                Log.d(TAG, "Audio-tail ended at " + playheadMs + "ms");
            }

            editorTimeline.setPlayheadPositionMs(playheadMs);
            timeCurrent.setText(TimeFormatter.formatAuto(playheadMs));
            return;
        }

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
                    long timelineEndMs = editorTimeline.getTimelineEndMs();
                    if (timelineEndMs > totalEffectiveMs()) {
                        // Audio extends beyond image clip — enter audio-tail
                        stopImagePlayback();
                        audioTailActive = true;
                        audioTailStartMs = totalEffectiveMs();
                        audioTailStartWall = android.os.SystemClock.elapsedRealtime();
                        updatePlayPauseButton(true);
                        Log.d(TAG, "Image: entering audio-tail");
                    } else {
                        // Last segment — set playhead to end
                        float endFraction = (float) clip.getOutPointMs() / sourceDuration;
                        lastUserPlayheadFraction = endFraction;
                        editorTimeline.setPlayheadFraction(endFraction);
                        long totalMs = timeline.getTotalDurationMs();
                        timeCurrent.setText(TimeFormatter.formatAuto(totalMs));
                        Log.d(TAG, "Image playback stopped at last segment end");
                    }
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
                    // Last video segment reached its end
                    long timelineEndMs = editorTimeline.getTimelineEndMs();
                    if (timelineEndMs > totalEffectiveMs()) {
                        // Audio extends beyond video — enter audio-tail mode
                        playerManager.pause();
                        audioTailActive = true;
                        audioTailStartMs = totalEffectiveMs();
                        audioTailStartWall = android.os.SystemClock.elapsedRealtime();
                        updatePlayPauseButton(true);
                        Log.d(TAG, "Entering audio-tail: videoEnd=" + audioTailStartMs
                                + " timelineEnd=" + timelineEndMs);
                    } else {
                        // No audio beyond video — fully stop
                        playerManager.pause();
                        long trimmedDur = clip.getOutPointMs() - clip.getInPointMs();
                        float endFraction = (float) clip.getOutPointMs() / sourceDuration;
                        lastUserPlayheadFraction = endFraction;
                        editorTimeline.setPlayheadFraction(endFraction);
                        long totalMs = timeline.getTotalDurationMs();
                        timeCurrent.setText(TimeFormatter.formatAuto(totalMs));
                        updatePlayPauseButton(false);
                        Log.d(TAG, "Playback stopped at last segment end");
                    }
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

        // Deactivate crop overlay when switching segments
        if (inCropMode) {
            exitCropMode(false);
        } else if (cropOverlay != null && cropOverlay.isActive()) {
            cropOverlay.deactivate();
        }

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

    /**
     * Show an export confirmation bottom sheet with project info.
     * On confirm, starts the export via the foreground service.
     */
    private void showExportConfirmation() {
        if (isExportRunning()) {
            Toast.makeText(this, R.string.faditor_export_in_progress, Toast.LENGTH_SHORT).show();
            return;
        }

        Timeline tl = project.getTimeline();
        int clipCount = tl.getClipCount();
        long totalDurationMs = tl.getTotalDurationMs();
        String durationStr = TimeFormatter.formatAuto(totalDurationMs);
        boolean hasAudio = tl.hasAudioClips();
        String audioInfo = hasAudio
                ? " • " + tl.getAudioClips().size() + " audio"
                : "";

        String helperText = getString(R.string.faditor_export_confirm_helper,
                durationStr, clipCount, audioInfo);

        try {
            InputActionBottomSheetFragment sheet = InputActionBottomSheetFragment.newConfirm(
                    getString(R.string.faditor_export_confirm_title),
                    getString(R.string.faditor_export_confirm_action),
                    getString(R.string.faditor_export_confirm_subtitle),
                    R.drawable.ic_save,
                    helperText);

            sheet.setCallbacks(new InputActionBottomSheetFragment.Callbacks() {
                @Override
                public void onImportConfirmed(org.json.JSONObject json) { }

                @Override
                public void onResetConfirmed() {
                    startExportViaService();
                }
            });
            sheet.show(getSupportFragmentManager(), "export_confirm");
        } catch (Exception e) {
            Log.e(TAG, "Failed to show export confirmation, starting directly", e);
            startExportViaService();
        }
    }

    /**
     * Start the export via the foreground service.
     * The service handles the Transformer lifecycle and survives Activity destruction.
     */
    private void startExportViaService() {
        if (isExportRunning()) {
            Toast.makeText(this, R.string.faditor_export_in_progress, Toast.LENGTH_SHORT).show();
            return;
        }

        // Pause playback during export
        playerManager.pause();

        // Pass project to the service via static bridge
        ExportService.setPendingProject(project);

        // Start and bind to service
        android.content.Intent serviceIntent =
                new android.content.Intent(this, ExportService.class);
        serviceIntent.setAction(ExportService.ACTION_START_EXPORT);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        // Bind for real-time UI progress
        bindService(new android.content.Intent(this, ExportService.class),
                exportServiceConnection, BIND_AUTO_CREATE);

        // Show export info on the overlay
        showExportInfoOnScreen();
    }

    /**
     * Check if an export is currently running (via bound service or manager).
     */
    private boolean isExportRunning() {
        if (exportServiceBound && exportService != null && exportService.isExporting()) {
            return true;
        }
        if (exportManager != null && exportManager.isExporting()) {
            return true;
        }
        return false;
    }

    /**
     * Show export info (duration, clips, codec) on the export progress screen.
     */
    private void showExportInfoOnScreen() {
        if (exportInfoText == null || project == null) return;

        Timeline tl = project.getTimeline();
        int clipCount = tl.getClipCount();
        long totalDurationMs = tl.getTotalDurationMs();
        String durationStr = TimeFormatter.formatAuto(totalDurationMs);
        boolean hasAudio = tl.hasAudioClips();
        String audioInfo = hasAudio
                ? " • " + tl.getAudioClips().size() + " audio"
                : "";

        exportInfoText.setText(getString(R.string.faditor_export_info,
                durationStr, clipCount, audioInfo));
        exportInfoText.setVisibility(View.VISIBLE);
    }

    private void showExportProgress() {
        if (exportProgressOverlay == null) return;

        // Reset to initial exporting state (indeterminate until first progress poll)
        if (exportProgressPercent != null) exportProgressPercent.setText("–");
        if (exportProgressBar != null) {
            exportProgressBar.setIndeterminate(true);
        }
        if (exportProgressText != null) {
            exportProgressText.setText(R.string.faditor_exporting);
            exportProgressText.setTextColor(0xFFAAAAAA);
        }
        if (exportEtaText != null) exportEtaText.setVisibility(View.GONE);
        if (exportStatusIcon != null) exportStatusIcon.setText("movie");
        if (exportTitle != null) exportTitle.setText(R.string.faditor_exporting);
        if (exportBtnDone != null) exportBtnDone.setVisibility(View.INVISIBLE);

        // Reset Back button visibility
        View backBtn = findViewById(R.id.export_btn_back);
        if (backBtn != null) backBtn.setVisibility(View.VISIBLE);

        View cancelBtn = findViewById(R.id.btn_cancel_export);
        if (cancelBtn != null) cancelBtn.setVisibility(View.VISIBLE);

        exportProgressOverlay.setVisibility(View.VISIBLE);
        exportProgressOverlay.setAlpha(0f);
        exportProgressOverlay.animate().alpha(1f).setDuration(200).start();
    }

    private void hideExportProgress() {
        if (exportProgressOverlay != null) {
            exportProgressOverlay.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                exportProgressOverlay.setVisibility(View.GONE);
            }).start();
        }
    }

    // ── Navigation ───────────────────────────────────────────────────

    /**
     * Show a confirmation bottom sheet before closing the editor.
     */
    private void showCloseConfirmation() {
        try {
            InputActionBottomSheetFragment sheet = InputActionBottomSheetFragment.newConfirm(
                    getString(R.string.faditor_close_confirm_title),
                    getString(R.string.faditor_close_confirm_action),
                    getString(R.string.faditor_close_confirm_subtitle),
                    R.drawable.ic_save,
                    getString(R.string.faditor_close_confirm_helper));

            sheet.setCallbacks(new InputActionBottomSheetFragment.Callbacks() {
                @Override
                public void onImportConfirmed(org.json.JSONObject json) { }

                @Override
                public void onResetConfirmed() {
                    handleClose();
                }
            });
            sheet.show(getSupportFragmentManager(), "close_confirm");
        } catch (Exception e) {
            Log.e(TAG, "Failed to show close confirmation sheet, using dialog fallback", e);
            showCloseConfirmationDialog();
        }
    }

    /**
     * Fallback confirmation dialog if bottom sheet fails.
     */
    private void showCloseConfirmationDialog() {
        new android.app.AlertDialog.Builder(this, R.style.CustomBottomSheetDialogTheme)
                .setTitle(R.string.faditor_close_confirm_title)
                .setMessage(R.string.faditor_close_confirm_helper)
                .setPositiveButton(R.string.faditor_close_confirm_action, (d, w) -> handleClose())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void handleClose() {
        saveProjectNow();
        finish();
    }

    // ── Persistence ──────────────────────────────────────────────────

    /**
     * Schedule a debounced auto-save (resets timer on each call).
     */
    // ── Undo / Redo ───────────────────────────────────────────────

    /**
     * Perform undo: reverts the last edit action and refreshes all relevant UI.
     */
    private void performUndo() {
        if (!undoManager.canUndo()) return;
        undoManager.undo();
        refreshEditorAfterUndoRedo();
        scheduleAutoSave();
    }

    /**
     * Perform redo: re-applies a previously undone edit action and refreshes UI.
     */
    private void performRedo() {
        if (!undoManager.canRedo()) return;
        undoManager.redo();
        refreshEditorAfterUndoRedo();
        scheduleAutoSave();
    }

    /**
     * Restore the entire project from a JSON snapshot.
     * Called by UndoManager's SnapshotRestorer during snapshot-based undo/redo.
     * Replaces the project object and all model references.
     *
     * @param projectJson the serialized project JSON to restore from
     */
    private void restoreProjectFromSnapshot(@NonNull String projectJson) {
        FaditorProject restored = projectStorage.fromJson(projectJson);
        if (restored == null) {
            Log.e(TAG, "Failed to restore project from snapshot");
            return;
        }
        this.project = restored;
        Log.d(TAG, "Project restored from snapshot, clips="
                + restored.getTimeline().getClipCount()
                + ", audioClips=" + restored.getTimeline().getAudioClipCount());
    }

    /**
     * Refresh all editor UI after an undo/redo operation.
     * Syncs timeline, toolbar, player state, and preview transforms with the current model.
     * Handles both action-based (same objects) and snapshot-based (new objects) restoration.
     */
    private void refreshEditorAfterUndoRedo() {
        // Clamp selected index in case clip count changed (e.g. after snapshot restore)
        int clipCount = project.getTimeline().getClipCount();
        if (clipCount == 0) return;
        if (selectedClipIndex < 0 || selectedClipIndex >= clipCount) {
            selectedClipIndex = Math.max(0, clipCount - 1);
        }

        Clip clip = getSelectedClip();
        if (clip == null) return;

        // Rebuild timeline view completely (handles both action and snapshot changes)
        editorTimeline.setTimeline(project.getTimeline(), selectedClipIndex);
        editorTimeline.setTrimFromClip(clip);
        editorTimeline.setAudioClips(project.getTimeline().getAudioClips());

        // Update player state
        if (!clip.isImageClip()) {
            playerManager.updateTrimBounds(clip);
            playerManager.setVolume(clip.isAudioMuted() ? 0f : clip.getVolumeLevel());
            playerManager.setPlaybackSpeed(clip.getSpeedMultiplier());
        }

        // Update toolbar UI
        updateVolumeUI(clip.getVolumeLevel(), clip.isAudioMuted());
        updateSpeedUI(clip.getSpeedMultiplier());
        updateRotateUI(clip.getRotationDegrees());
        updateFlipUI(clip.isFlipHorizontal(), clip.isFlipVertical());
        updateCropUI(clip.getCropPreset());
        updateCanvasUI(project.getCanvasPreset());
        updateAudioToolUI();

        // Update preview transforms (rotation, flip, crop, canvas)
        updatePreviewTransforms();

        // Update time displays
        updateCurrentTimeDisplay(0);
        refreshTotalTimeDisplay();

        // Re-prepare audio player for any audio clip changes
        prepareAudioPlayer();
    }

    // ── Auto-save ────────────────────────────────────────────────────

    private void scheduleAutoSave() {
        autoSaveHandler.removeCallbacks(autoSaveRunnable);
        autoSaveHandler.postDelayed(autoSaveRunnable, AUTO_SAVE_DELAY_MS);
    }

    /**
     * Save project immediately (blocking on current thread, fast for small JSON).
     * Also persists the undo history snapshots alongside the project.
     */
    private void saveProjectNow() {
        if (project != null && projectStorage != null) {
            autoSaveHandler.removeCallbacks(autoSaveRunnable);
            projectStorage.save(project);

            // Persist undo history
            List<UndoManager.HistoryEntry> history = undoManager.getUndoHistory();
            if (!history.isEmpty()) {
                List<String> descriptions = new ArrayList<>();
                List<String> snapshots = new ArrayList<>();
                for (UndoManager.HistoryEntry entry : history) {
                    if (entry.getSnapshotBefore() != null) {
                        descriptions.add(entry.getDescription());
                        snapshots.add(entry.getSnapshotBefore());
                    }
                }
                projectStorage.saveUndoHistory(project.getId(), descriptions, snapshots);
            }

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

            // Record undo action
            undoManager.recordAction(new EditActions.AddClipAction(
                    timeline, imageClip, insertIndex));

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

            // Record undo action
            undoManager.recordAction(new EditActions.AddClipAction(
                    timeline, videoClip, insertIndex));

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
            // Check if an audio clip is selected — split that
            int audioIdx = editorTimeline.getSelectedAudioIndex();
            if (audioIdx >= 0) {
                splitAudioAtPlayhead(audioIdx);
                return;
            }

            Clip clip = getSelectedClip();
            if (clip == null) return;

            playerManager.pause();

            long currentPositionMs = playerManager.getCurrentPosition();
            long absoluteSplitMs = clip.getInPointMs() + currentPositionMs;

            // Save reference to original clip before split
            Clip originalClip = clip;
            int originalIndex = selectedClipIndex;

            Timeline timeline = project.getTimeline();
            int newIndex = timeline.splitAt(selectedClipIndex, absoluteSplitMs);
            if (newIndex < 0) {
                Toast.makeText(this, R.string.faditor_split_error, Toast.LENGTH_SHORT).show();
                return;
            }

            // Record undo action with the two new clips
            Clip clipA = timeline.getClip(newIndex);
            Clip clipB = timeline.getClip(newIndex + 1);
            undoManager.recordAction(new EditActions.SplitClipAction(
                    timeline, originalIndex, originalClip, clipA, clipB));

            selectSegment(selectedClipIndex);
            saveProjectNow();
            Toast.makeText(this, R.string.faditor_split_success, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "splitAtPlayhead failed", e);
            Toast.makeText(this, R.string.faditor_split_error, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Split the selected audio clip at the current playhead position.
     * Creates two audio clips from the original at the split point.
     */
    private void splitAudioAtPlayhead(int audioIdx) {
        Timeline timeline = project.getTimeline();
        AudioClip ac = timeline.getAudioClip(audioIdx);
        if (ac == null) return;

        long playheadMs = editorTimeline.getPlayheadPositionMs();
        long audioStartOnTimeline = ac.getOffsetMs();
        long audioEndOnTimeline = ac.getEndOnTimelineMs();

        // Check if playhead is within the audio clip
        if (playheadMs <= audioStartOnTimeline || playheadMs >= audioEndOnTimeline) {
            Toast.makeText(this, R.string.faditor_split_error, Toast.LENGTH_SHORT).show();
            return;
        }

        // Minimum 500ms on each side
        long splitInSource = ac.getInPointMs() + (playheadMs - audioStartOnTimeline);
        if (splitInSource - ac.getInPointMs() < 500 || ac.getOutPointMs() - splitInSource < 500) {
            Toast.makeText(this, R.string.faditor_split_error, Toast.LENGTH_SHORT).show();
            return;
        }

        // Create two clips from the original
        AudioClip left = new AudioClip(ac);
        left.setOutPointMs(splitInSource);

        AudioClip right = new AudioClip(ac);
        right.setInPointMs(splitInSource);
        right.setOffsetMs(playheadMs); // Starts at the split point on the timeline

        // Record undo action before modifying timeline
        undoManager.recordAction(new EditActions.SplitAudioClipAction(
                timeline, audioIdx, ac, left, right));

        // Remove original, add the two new clips
        timeline.removeAudioClip(audioIdx);
        timeline.addAudioClip(left);
        timeline.addAudioClip(right);

        editorTimeline.setAudioClips(timeline.getAudioClips());
        prepareAudioPlayer();
        scheduleAutoSave();
        Toast.makeText(this, R.string.faditor_split_success, Toast.LENGTH_SHORT).show();
    }

    /**
     * Delete the currently selected segment (cannot delete the last remaining segment).
     */
    private void deleteSelectedSegment() {
        try {
            // Check if an audio clip is selected — delete that instead
            int audioIdx = editorTimeline.getSelectedAudioIndex();
            if (audioIdx >= 0) {
                deleteSelectedAudioClip(audioIdx);
                return;
            }

            Timeline timeline = project.getTimeline();
            if (timeline.getClipCount() <= 1) {
                Toast.makeText(this, R.string.faditor_delete_last_segment, Toast.LENGTH_SHORT).show();
                return;
            }

            // Record undo action before deletion
            Clip deletedClip = timeline.getClip(selectedClipIndex);
            int deletedIndex = selectedClipIndex;
            undoManager.recordAction(new EditActions.DeleteClipAction(
                    timeline, deletedClip, deletedIndex));

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
     * Deletes the selected audio clip from the timeline.
     */
    private void deleteSelectedAudioClip(int audioIndex) {
        Timeline timeline = project.getTimeline();
        if (audioIndex < 0 || audioIndex >= timeline.getAudioClipCount()) return;

        // Record undo action before deletion
        AudioClip deletedClip = timeline.getAudioClip(audioIndex);
        undoManager.recordAction(new EditActions.DeleteAudioClipAction(
                timeline, deletedClip, audioIndex));

        timeline.removeAudioClip(audioIndex);
        editorTimeline.setAudioClips(timeline.getAudioClips());
        releaseAudioPlayer();
        if (timeline.hasAudioClips()) {
            prepareAudioPlayer();
        }
        updateAudioToolUI();
        saveProjectNow();
        Toast.makeText(this, R.string.faditor_segment_deleted, Toast.LENGTH_SHORT).show();
    }

    /**
     * Duplicate the currently selected segment (inserts a copy right after it).
     */
    private void duplicateSelectedSegment() {
        try {
            Timeline timeline = project.getTimeline();
            int newIndex = timeline.duplicateClip(selectedClipIndex);
            if (newIndex < 0) return;

            // Record undo action for the duplication
            Clip duplicated = timeline.getClip(newIndex);
            undoManager.recordAction(new EditActions.DuplicateClipAction(
                    timeline, duplicated, newIndex));

            selectSegment(newIndex);
            saveProjectNow();
            Toast.makeText(this, R.string.faditor_segment_duplicated, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "duplicateSelectedSegment failed", e);
        }
    }
}
