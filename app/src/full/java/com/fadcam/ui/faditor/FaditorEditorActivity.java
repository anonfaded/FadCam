package com.fadcam.ui.faditor;

import com.fadcam.ui.faditor.util.FaditorPalette;

import com.fadcam.FLog;
import android.content.Intent;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.Player;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.ExportResult;

import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.playback.FragmentedMp4Remuxer;
import com.fadcam.ui.InputActionBottomSheetFragment;
import com.fadcam.ui.faditor.composition.FaditorCompositionPlayer;
import com.fadcam.ui.faditor.composition.TimelineCompositionBuilder;
import com.fadcam.ui.faditor.export.ExportManager;
import com.fadcam.ui.faditor.export.ExportService;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.ExportSettings;
import com.fadcam.ui.faditor.model.FaditorProject;
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
    @Nullable
    private FaditorCompositionPlayer compositionPlayer;
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
                    FLog.d(TAG, "ExportService bound");
                }

                @Override
                public void onServiceDisconnected(android.content.ComponentName name) {
                    exportService = null;
                    exportServiceBound = false;
                    FLog.d(TAG, "ExportService unbound");
                }
            };

    // ── Asset pickers ────────────────────────────────────────────────
    private ActivityResultLauncher<Intent> imagePickerLauncher;
    private ActivityResultLauncher<Intent> videoPickerLauncher;

    // ── Views ────────────────────────────────────────────────────────
    private android.view.SurfaceView playerSurface;
    private EditorTimelineView editorTimeline;
    private TextView btnPlayPause;
    private TextView timeCurrent;
    private TextView timeTotal;
    /** Project name shown in the top bar; export files are named from it. */
    private TextView editorTitle;
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

    // ── Crop mode state ──────────────────────────────────────────────
    private boolean inCropMode = false;
    private long lastBackPressTime = 0;
    private static final long BACK_PRESS_INTERVAL_MS = 2000;
    private String preCropPreset;           // saved on entering crop mode
    private float preCropLeft, preCropTop, preCropRight, preCropBottom; // saved custom bounds
    /** Clip whose crop is skipped in the preview composition while its crop UI is open. */
    @Nullable
    private String croplessPreviewClipId;
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
    private View toolSplit;
    private View toolDelete;
    private View toolDuplicate;

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
    /** Index of the highlighted clip, or -1 when nothing is selected. */
    private int selectedClipIndex = -1;

    /**
     * True once playback reached the end; the playhead updater stays stopped until the
     * user plays or seeks again.
     */
    private boolean playbackFinished;

    // ── Audio extraction ─────────────────────────────────────────────
    private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor();

    // ── Persistence ──────────────────────────────────────────────────
    private ProjectStorage projectStorage;
    private final Handler autoSaveHandler = new Handler(Looper.getMainLooper());
    private static final long AUTO_SAVE_DELAY_MS = 3000;
    /** Edits reach the preview quickly; saving can wait for the longer autosave delay. */
    private static final long COMPOSITION_REBUILD_DELAY_MS = 350;
    private final Runnable autoSaveRunnable = () -> {
        if (project != null && projectStorage != null) {
            projectStorage.save(project);
        }
    };

    /**
     * True while the user is actively touching/dragging the timeline (playhead or trim).
     * Prevents the composition position from overwriting the drag position.
     */
    private boolean userDragging = false;

    // ── Playhead sync ────────────────────────────────────────────────
    /** Frame-synced playhead so the timeline moves with the display, not a timer. */
    private final android.view.Choreographer choreographer =
            android.view.Choreographer.getInstance();

    private final android.view.Choreographer.FrameCallback playheadFrameCallback =
            new android.view.Choreographer.FrameCallback() {
                @Override
                public void doFrame(long frameTimeNanos) {
                    try {
                        updatePlayheadFromComposition();
                    } catch (Exception e) {
                        FLog.e(TAG, "Error updating playhead", e);
                    }
                    if (!playbackFinished && compositionPlayer != null
                            && compositionPlayer.isPlayingOrBuffering()) {
                        choreographer.postFrameCallback(this);
                    }
                }
            };

    /** Cached timeline duration; rebuilt only when the composition changes. */
    private long compositionDurationMs;
    /** Last whole second painted in the time label, to avoid a TextView update per frame. */
    private long lastDisplayedSecond = -1;

    /** True while a scrub gesture owns the preview (composition in scrubbing mode). */
    private boolean scrubbing;
    private long pendingScrubSeekMs = -1;
    /** At most one seek may be in flight; CompositionPlayer tears down decoders per seek. */
    private boolean scrubSeekInFlight;
    private boolean scrubSeekScheduled;
    private long lastScrubSeekAtMs;
    private static final long MIN_SCRUB_SEEK_INTERVAL_MS = 120;
    private static final long SCRUB_SEEK_TIMEOUT_MS = 300;
    /** Last position handed to the player, so play does not repeat the same seek. */
    private long lastSeekTargetMs = -1;

    private final android.view.Choreographer.FrameCallback scrubSeekCallback =
            new android.view.Choreographer.FrameCallback() {
                @Override
                public void doFrame(long frameTimeNanos) {
                    scrubSeekScheduled = false;
                    if (!scrubbing || compositionPlayer == null) return;

                    long now = android.os.SystemClock.elapsedRealtime();
                    if (scrubSeekInFlight
                            && now - lastScrubSeekAtMs < SCRUB_SEEK_TIMEOUT_MS) {
                        scheduleNextScrubFrame();
                        return;
                    }
                    scrubSeekInFlight = false;

                    long target = pendingScrubSeekMs;
                    if (target < 0) return;

                    if (now - lastScrubSeekAtMs < MIN_SCRUB_SEEK_INTERVAL_MS) {
                        scheduleNextScrubFrame();
                        return;
                    }

                    pendingScrubSeekMs = -1;
                    scrubSeekInFlight = true;
                    lastScrubSeekAtMs = now;
                    seekCompositionTo(target);
                    scheduleNextScrubFrame();
                }
            };

    private void scheduleNextScrubFrame() {
        if (!scrubbing || scrubSeekScheduled) return;
        if (pendingScrubSeekMs < 0 && !scrubSeekInFlight) return;
        scrubSeekScheduled = true;
        choreographer.postFrameCallback(scrubSeekCallback);
    }

    /** Seeks the composition and remembers the target for the next play press. */
    private void seekCompositionTo(long positionMs) {
        if (compositionPlayer == null) return;
        compositionPlayer.seekTo(positionMs);
        lastSeekTargetMs = positionMs;
    }

    /** Records a scrub position; the frame callback sends only the latest one. */
    private void scheduleScrubSeek(long positionMs) {
        pendingScrubSeekMs = positionMs;
        scheduleNextScrubFrame();
    }

    private void cancelScrubSeek() {
        pendingScrubSeekMs = -1;
        scrubSeekInFlight = false;
        scrubSeekScheduled = false;
        choreographer.removeFrameCallback(scrubSeekCallback);
    }

    // ── Segment helpers ──────────────────────────────────────────────

    /**
     * Returns the currently selected clip, or null when nothing is selected.
     *
     * <p>Selection is genuine state, not a default: opening an editor with a clip
     * already highlighted is unlike every professional editor, and it made Delete
     * silently destroy whatever happened to be first.</p>
     */
    @Nullable
    private Clip getSelectedClip() {
        if (project == null) return null;
        int count = project.getTimeline().getClipCount();
        if (selectedClipIndex < 0 || selectedClipIndex >= count) return null;
        return project.getTimeline().getClip(selectedClipIndex);
    }

    /**
     * Rebuilds the timeline strip after the clip list itself changed.
     *
     * <p>Selection-only changes must not rebuild (that re-centres the strip), but
     * duplicate/split/delete/reorder change the list, and without this the view kept
     * drawing the old segments — the new clip was in the model and in the total
     * duration, yet invisible on the strip.</p>
     */
    private void refreshTimelineAfterStructuralChange() {
        if (project == null) return;
        if (selectedClipIndex >= project.getTimeline().getClipCount()) {
            selectedClipIndex = -1;
        }
        editorTimeline.setTimeline(project.getTimeline(), selectedClipIndex);
        editorTimeline.setAudioClips(project.getTimeline().getAudioClips());
        if (selectedClipIndex < 0 && editorTimeline.getSelectedAudioIndex() < 0) {
            clearSegmentTools();
        }
        clampPlayheadToTimeline();
        rebuildComposition();
        updateToolAvailability();
        refreshTotalTimeDisplay();
        FLog.d(TAG, "Timeline rebuilt: clips=" + project.getTimeline().getClipCount()
                + " selected=" + selectedClipIndex);
    }

    /**
     * Where a newly added asset belongs: right after the selected clip, otherwise right
     * after the clip under the playhead, otherwise at the end.
     */
    private int insertionIndexForNewClip() {
        Timeline tl = project.getTimeline();
        int anchor = selectedClipIndex;
        if (anchor < 0) anchor = editorTimeline.getSegmentAtPlayhead();
        if (anchor < 0 || anchor >= tl.getClipCount()) return tl.getClipCount();
        return anchor + 1;
    }

    /**
     * Select a segment by index, update the timeline and toolbar.
     *
     * @param index the segment index to select (-1 to deselect)
     */
    private void selectSegment(int index) {
        int count = project.getTimeline().getClipCount();

        // Deselecting keeps whatever clip the player has loaded and leaves the
        // playhead alone — only the highlight and the clip-specific tools change.
        if (index < 0) {
            selectedClipIndex = -1;
            editorTimeline.setSelectedIndex(-1);
            clearSegmentTools();
            updateToolAvailability();
            refreshTotalTimeDisplay();
            FLog.d(TAG, "Deselected all segments");
            return;
        }

        // Validate index
        if (index >= count) return;

        // Track if this is a reselection of the same segment
        boolean isReselection = (index == selectedClipIndex);

        selectedClipIndex = index;
        Clip clip = getSelectedClip();
        if (clip == null) return;

        // Tools read the selected clip; the preview and the playhead do NOT move.
        // In every professional editor, selection answers "what will the edit apply
        // to" and the playhead answers "what moment am I looking at".
        if (clip.isImageClip()) {
            // The audio/speed tools are unavailable for stills; show their idle read-out
            // instead of a red "Muted" that implies an editable audio track.
            updateVolumeUI(1f, false);
            updateSpeedUI(1f);
        } else {
            updateVolumeUI(clip.getVolumeLevel(), clip.isAudioMuted());
            updateSpeedUI(clip.getSpeedMultiplier());
        }
        updateRotateUI(clip.getRotationDegrees());
        updateFlipUI(clip.isFlipHorizontal(), clip.isFlipVertical());
        updateCropUI(clip.getCropPreset());

        // Leaving crop mode is a selection change, not a preview change.
        if (inCropMode) {
            exitCropMode(false);
        } else if (cropOverlay != null && cropOverlay.isActive()) {
            cropOverlay.deactivate();
        }

        FLog.d(TAG, "selectSegment: index=" + index + "/" + count
                + " id=" + clip.getId()
                + " (playhead untouched at "
                + editorTimeline.getPlayheadPositionMs() + "ms)");

        // Update only the highlight. setTimeline() would rebuild every segment and call
        // centerPlayhead(), which silently scrolls the strip under a tap.
        editorTimeline.setSelectedIndex(index);
        updateToolAvailability();
        refreshTotalTimeDisplay();

        FLog.d(TAG, "Selected segment " + index + "/" + count
                + " id=" + clip.getId()
                + " in=" + clip.getInPointMs() + " out=" + clip.getOutPointMs()
                + " isReselection=" + isReselection);
    }
    // ── Lifecycle ────────────────────────────────────────────────────

    /**
     * Gives the top bar the status bar / cutout height without replacing its own padding.
     *
     * <p>{@code fitsSystemWindows} would call {@code setPadding()} with the raw insets, which
     * zeroes the start/end padding around the close and export buttons. Composing the inset
     * with the designed padding keeps those buttons off the screen edges and lets the top bar's
     * background fill the cutout area instead of leaving a black strip.</p>
     */
    private void applyTopBarInset() {
        View root = findViewById(R.id.editor_root);
        View topBar = findViewById(R.id.editor_top_bar);
        if (root == null || topBar == null) return;
        final int baseTop = topBar.getPaddingTop();
        final int baseLeft = topBar.getPaddingLeft();
        final int baseRight = topBar.getPaddingRight();
        final int baseBottom = topBar.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            topBar.setPadding(baseLeft, baseTop + bars.top, baseRight, baseBottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Sheets opened from the editor are separate windows; without this they bring the
        // status and navigation bars back over the fullscreen editor.
        com.fadcam.ui.utils.ImmersiveDialogs.install(this);

        // Register asset picker launchers (must be before onStart)
        registerAssetPickers();

        // Keep screen on while editing
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // ── True fullscreen: hide status bar and nav bar ────────────
        WindowCompat.enableEdgeToEdge(getWindow());
        com.fadcam.util.SystemBarUtil.hideSystemBars(getWindow().getDecorView());

        setContentView(R.layout.activity_faditor_editor);
        applyTopBarInset();

        // The editor is its own window, so it installs the shared side-sheet adapter
        // that MainActivity uses: rename, export and add-asset sheets all dock to the
        // trailing edge in landscape.
        com.fadcam.ui.SideSheet.install(getSupportFragmentManager());

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
                // Resolve to file:// and check remux
                File sourceFile = resolveToFile(playUri);
                if (sourceFile != null) {
                    if (remuxer.needsRemux(sourceFile) && remuxer.hasRemuxedVersion(sourceFile)) {
                        playUri = Uri.fromFile(remuxer.getRemuxedFile(sourceFile));
                    } else {
                        // Always prefer file:// URI for reliable seeking
                        playUri = Uri.fromFile(sourceFile);
                    }
                }
                continueLoadFromSavedProject(playUri);
                FLog.d(TAG, "Editor opened saved project: " + projectId);
                return;
            }
            FLog.w(TAG, "Could not load saved project: " + projectId + ", falling back");
        }

        // Parse video URI from intent (new project)
        Uri videoUri = parseVideoUri();
        if (videoUri == null) {
            FLog.e(TAG, "No video URI provided");
            Toast.makeText(this, R.string.faditor_error_no_video, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Initialize components
        initViews();

        // Check if video needs remuxing for seekable playback
        attemptRemuxAndLoad(videoUri);

        FLog.d(TAG, "Editor opened with: " + videoUri);
    }

    @Override
    protected void onResume() {
        super.onResume();
        
        // Reapply immersive fullscreen (in case it was cleared by edge swipes)
        com.fadcam.util.SystemBarUtil.hideSystemBars(getWindow().getDecorView());

        if (compositionPlayer != null && compositionPlayer.isPlayingOrBuffering()) {
            startPlayheadUpdates();
        } else {
            updatePlayheadFromComposition();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPlayheadUpdates();
        cancelScrubSeek();
        scrubbing = false;
        if (compositionPlayer != null && compositionPlayer.isPlayingOrBuffering()) {
            compositionPlayer.pause();
            updatePlayPauseButton(false);
        }
        // Save project on pause (e.g. user switches away)
        saveProjectNow();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayheadUpdates();
        cancelScrubSeek();
        autoSaveHandler.removeCallbacksAndMessages(null);
        if (compositionPlayer != null) {
            compositionPlayer.release();
            compositionPlayer = null;
        }
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

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // Reapply immersive fullscreen when window regains focus
            com.fadcam.util.SystemBarUtil.hideSystemBars(getWindow().getDecorView());
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
        playerSurface = findViewById(R.id.player_surface);
        cropOverlay = findViewById(R.id.crop_overlay);
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
                // The handle belongs to a specific segment. Reading "the selected clip"
                // here silently trimmed the wrong one whenever selection and the drawn
                // handles had drifted apart (e.g. after scrubbing across a cut).
                Clip clip = clipAt(segmentIndex);
                if (clip == null) return;
                if (!userDragging) {
                    // First drag callback — capture pre-trim values for undo
                    preTrimInMs = clip.getInPointMs();
                    preTrimOutMs = clip.getOutPointMs();
                }
                userDragging = true;
                if (selectedClipIndex != segmentIndex) {
                    selectedClipIndex = segmentIndex;
                    editorTimeline.setSelectedIndex(segmentIndex);
                }
                long duration = clip.getSourceDurationMs();
                clip.setInPointMs((long)(startFraction * duration));
                clip.setOutPointMs((long)(endFraction * duration));
                // View handles its own visual update during drag.
                // setTrimFromClip is called in onTrimFinished for the final commit.
                updateCurrentTimeDisplay();
                refreshTotalTimeDisplay();
            }

            @Override
            public void onTrimFinished(int segmentIndex, float startFraction, float endFraction) {
                userDragging = false;
                Clip clip = clipAt(segmentIndex);
                if (clip == null) return;
                if (selectedClipIndex != segmentIndex) {
                    selectedClipIndex = segmentIndex;
                    editorTimeline.setSelectedIndex(segmentIndex);
                }
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
                clampPlayheadToTimeline();
                rebuildComposition();
                updateCurrentTimeDisplay();
                refreshTotalTimeDisplay();
                saveProjectNow();
            }

            @Override
            public void onPlayheadSeeked(int segmentIndex, float fractionInSegment, boolean isDragging) {
                long playheadMs = editorTimeline.getPlayheadPositionMs();
                if (isDragging) {
                    userDragging = true;
                    playbackFinished = true;
                    stopPlayheadUpdates();
                    if (!scrubbing) {
                        scrubbing = true;
                        if (compositionPlayer != null) {
                            compositionPlayer.pause();
                        }
                        updatePlayPauseButton(false);
                    }
                    // One seek per display frame: the preview keeps up with the finger
                    // without queueing a seek for every touch event.
                    scheduleScrubSeek(playheadMs);
                } else {
                    userDragging = false;
                    playbackFinished = true;
                    cancelScrubSeek();
                    if (compositionPlayer != null) {
                        compositionPlayer.pause();
                        scrubbing = false;
                        if (lastSeekTargetMs != playheadMs
                                || Math.abs(compositionPlayer.getPositionMs() - playheadMs) > 50) {
                            seekCompositionTo(playheadMs);
                        }
                    }
                    updatePlayPauseButton(false);
                }
                updateCurrentTimeDisplay();
            }

            @Override
            public void onPlayheadDragFinished() {
                userDragging = false;
                playbackFinished = true;
                stopPlayheadUpdates();
                cancelScrubSeek();
                clampPlayheadToTimeline();
                if (compositionPlayer != null) {
                    compositionPlayer.pause();
                    scrubbing = false;
                    long target = editorTimeline.getPlayheadPositionMs();
                    if (lastSeekTargetMs != target) seekCompositionTo(target);
                }
                updatePlayPauseButton(false);
                updateCurrentTimeDisplay();

                // The toolbar reflects the selection, which scrubbing does not change.
                Clip selected = getSelectedClip();
                if (selected != null) {
                    if (selected.isImageClip()) {
                        updateVolumeUI(1f, false);
                        updateSpeedUI(1f);
                    } else {
                        updateVolumeUI(selected.getVolumeLevel(), selected.isAudioMuted());
                        updateSpeedUI(selected.getSpeedMultiplier());
                    }
                }
            }

            @Override
            public void onSegmentReordered(int fromIndex, int toIndex) {
                Timeline tl = project.getTimeline();
                undoManager.recordAction(new EditActions.ReorderClipAction(
                        tl, fromIndex, toIndex));
                tl.moveClip(fromIndex, toIndex);
                refreshTimelineAfterStructuralChange();
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
                FLog.d(TAG, "Audio clip selected: " + audioIndex);
                if (audioIndex >= 0 && project != null) {
                    AudioClip ac = project.getTimeline().getAudioClip(audioIndex);
                    if (ac != null) {
                        updateVolumeUI(ac.getVolumeLevel(), ac.isMuted());
                    }
                } else if (getSelectedClip() == null) {
                    clearSegmentTools();
                }
                updateToolAvailability();
            }

            @Override
            public void onAudioClipMoved(int audioIndex, long oldOffsetMs, long newOffsetMs) {
                if (project == null) return;
                AudioClip ac = project.getTimeline().getAudioClip(audioIndex);
                if (ac == null) return;
                undoManager.recordAction(new EditActions.AudioOffsetAction(
                        ac, oldOffsetMs, newOffsetMs));
                clampPlayheadToTimeline();
                refreshTotalTimeDisplay();
                rebuildComposition();
                updateToolAvailability();
                scheduleAutoSave();
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
                FLog.d(TAG, "Audio trim changed: index=" + audioIndex
                        + " in=" + inPointMs + " out=" + outPointMs);
            }

            @Override
            public void onAudioTrimFinished(int audioIndex, long inPointMs, long outPointMs) {
                FLog.d(TAG, "Audio trim finished: index=" + audioIndex
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
                    int selectedAudio = editorTimeline.getSelectedAudioIndex();
                    editorTimeline.setAudioClips(project.getTimeline().getAudioClips());
                    if (selectedAudio >= 0
                            && selectedAudio < project.getTimeline().getAudioClipCount()) {
                        editorTimeline.setSelectedAudioIndex(selectedAudio);
                    }
                    clampPlayheadToTimeline();
                    refreshTotalTimeDisplay();
                    rebuildComposition();
                    updateToolAvailability();
                    scheduleAutoSave();
                }
            }
        });
        btnPlayPause = findViewById(R.id.btn_play_pause);
        timeCurrent = findViewById(R.id.time_current);
        timeTotal = findViewById(R.id.time_total);
        editorTitle = findViewById(R.id.editor_title);
        View renameGroup = findViewById(R.id.editor_title_group);
        if (renameGroup != null) {
            renameGroup.setOnClickListener(v -> showRenameProjectSheet());
        }
        bindTitleWidthConstraint();
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
        toolSplit = findViewById(R.id.tool_split);
        toolDelete = findViewById(R.id.tool_delete);
        toolDuplicate = findViewById(R.id.tool_duplicate);

        // Landscape re-parents the preview and the tool strip; do it once the whole
        // hierarchy exists and before anything measures.
        applyEditorOrientationLayout();

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
        toolSplit.setOnClickListener(v -> splitAtPlayhead());
        toolDelete.setOnClickListener(v -> deleteSelectedSegment());
        toolDuplicate.setOnClickListener(v -> duplicateSelectedSegment());
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

        // Build a file:// URI when the source resolves to a readable file. Local files
        // support random-access seeking, which the editor and the exporter both need.
        Uri fileUri = (sourceFile != null) ? Uri.fromFile(sourceFile) : videoUri;

        if (sourceFile != null && remuxer.needsRemux(sourceFile)) {
            // Already remuxed?
            if (remuxer.hasRemuxedVersion(sourceFile)) {
                File remuxed = remuxer.getRemuxedFile(sourceFile);
                FLog.d(TAG, "Using cached remuxed file: " + remuxed.getName());
                continueLoadWithUri(Uri.fromFile(remuxed), videoUri);
                return;
            }

            // Show progress and remux async
            showRemuxProgress();
            FLog.i(TAG, "Remuxing fragmented MP4 for seekable preview…");

            remuxer.remuxAsync(sourceFile, new FragmentedMp4Remuxer.RemuxCallback() {
                @Override
                public void onRemuxComplete(boolean success, String outputPath) {
                    runOnUiThread(() -> {
                        hideRemuxProgress();
                        if (success && outputPath != null) {
                            FLog.i(TAG, "Remux complete: " + outputPath);
                            continueLoadWithUri(
                                    Uri.fromFile(new File(outputPath)), videoUri);
                        } else {
                            FLog.w(TAG, "Remux failed, loading original (seeking may not work)");
                            continueLoadWithUri(fileUri, videoUri);
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
            // No remux needed — still use file:// URI when available for
            // reliable seeking and duration extraction.
            continueLoadWithUri(fileUri, videoUri);
        }
    }

    /**
     * Finish editor initialisation after (optional) remux is done.
     *
     * @param playUri   URI to use for preview playback (may be remuxed)
     * @param exportUri original URI kept for export (same content, original container)
     */
    private void continueLoadWithUri(@NonNull Uri playUri, @NonNull Uri exportUri) {
        if (!initProject(playUri)) return;
        initPlayer();
        initTimeline();
        prepareCompositionPreview();
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
        updateProjectTitle();
        refreshTotalTimeDisplay();
        initPlayer();
        initTimeline();
        prepareCompositionPreview();
        initToolbar();
        initExport();
        initBackHandler();

        // Restore audio clips into timeline view if any exist
        if (project.getTimeline().hasAudioClips()) {
            editorTimeline.setAudioClips(project.getTimeline().getAudioClips());
            updateAudioToolUI();
        }

        // Canvas is part of the composition; only the surface bounds need updating.
        applyPreviewAspect();

        // Restore undo history from disk
        List<String> descriptions = new ArrayList<>();
        List<String> snapshots = new ArrayList<>();
        if (projectStorage.loadUndoHistory(project.getId(), descriptions, snapshots)) {
            undoManager.loadHistory(descriptions, snapshots);
            FLog.d(TAG, "Restored " + descriptions.size() + " undo history entries");
        }

        FLog.d(TAG, "Editor loaded saved project: " + project.getId()
                + ", clips=" + project.getTimeline().getClipCount()
                + ", audioClips=" + project.getTimeline().getAudioClipCount());
    }

    /**
     * Try to resolve a URI to a local {@link File}.
     * Handles file:// URIs and SAF content:// URIs from both internal storage
     * ("primary") and SD card (e.g. "ABCD-1234") mount points.
     *
     * @return the File if resolvable and readable, or null
     */
    @Nullable
    private File resolveToFile(@NonNull Uri uri) {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            return new File(uri.getPath());
        }
        String resolved = resolveSafPath(uri);
        if (resolved != null) {
            File f = new File(resolved);
            if (f.exists() && f.canRead()) return f;
        }
        return null;
    }

    /**
     * Derive an absolute file-system path from a SAF content:// URI.
     *
     * <p>SAF document/tree URIs embed the storage ID and relative path in
     * the URI path segment, separated by a colon, e.g.:<br>
     * {@code /document/primary:DCIM/Camera/video.mp4}  → internal storage<br>
     * {@code /document/ABCD-1234:DCIM/Camera/video.mp4} → SD card</p>
     *
     * @return absolute path string, or null if the URI cannot be parsed
     */
    @Nullable
    private String resolveSafPath(@NonNull Uri uri) {
        String uriPath = uri.getPath();
        if (uriPath == null || !uriPath.contains(":")) return null;
        try {
            int lastColon = uriPath.lastIndexOf(':');
            String encodedRel = uriPath.substring(lastColon + 1);
            String relativePath = java.net.URLDecoder.decode(encodedRel, "UTF-8");

            // Extract storage ID: the path segment immediately before the colon
            String beforeColon = uriPath.substring(0, lastColon);
            int lastSlash = beforeColon.lastIndexOf('/');
            String encodedId = lastSlash >= 0
                    ? beforeColon.substring(lastSlash + 1) : beforeColon;
            String storageId = java.net.URLDecoder.decode(encodedId, "UTF-8");

            // Map storage ID to mount point
            String mountPoint = "primary".equalsIgnoreCase(storageId)
                    ? "/storage/emulated/0"
                    : "/storage/" + storageId;

            String fullPath = mountPoint + "/" + relativePath;
            FLog.d(TAG, "resolveSafPath: storageId='" + storageId + "' → " + fullPath);
            return fullPath;
        } catch (Exception e) {
            FLog.w(TAG, "resolveSafPath failed for " + uri, e);
            return null;
        }
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

    /**
     * Derives a display name from the source video: "FadCam_20261124_043715.mp4"
     * becomes "FadCam_20261124_043715". Falls back to the untitled label when the
     * provider gives us nothing usable.
     */
    @NonNull
    private String projectNameFromUri(@NonNull Uri uri) {
        String displayName = null;
        try {
            if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                displayName = new File(uri.getPath()).getName();
            } else {
                try (android.database.Cursor c = getContentResolver().query(uri,
                        new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                        null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0) displayName = c.getString(idx);
                    }
                }
            }
        } catch (Exception e) {
            FLog.w(TAG, "Could not read source display name", e);
        }
        if (displayName == null || displayName.trim().isEmpty()) {
            return getString(R.string.faditor_project_untitled);
        }
        int dot = displayName.lastIndexOf('.');
        if (dot > 0) displayName = displayName.substring(0, dot);
        displayName = displayName.trim();
        return displayName.isEmpty()
                ? getString(R.string.faditor_project_untitled) : displayName;
    }

    /**
     * Caps the title at the width the top bar can spare.
     *
     * <p>A long project name used to expand the title TextView until the pencil beside
     * it was clipped. The pencil is a fixed-size sibling, so the title gets an explicit
     * {@code maxWidth} derived from the bar: everything except the close button, the
     * pencil and the export button, minus the gaps. The name ellipsises in the middle,
     * which keeps both ends readable — exactly how file names are shown elsewhere.</p>
     */
    private void bindTitleWidthConstraint() {
        final View bar = findViewById(R.id.editor_top_bar);
        final View title = editorTitle;
        final View pencil = findViewById(R.id.btn_rename_project);
        if (bar == null || title == null || pencil == null) return;

        bar.post(() -> {
            int barWidth = bar.getWidth();
            if (barWidth <= 0) return;
            int fixed = dp(48)          // close button
                    + dp(40)            // export button
                    + dp(28) + dp(6)    // pencil + gap
                    + dp(12) + dp(4);   // bar end padding + close start padding
            int available = Math.max(dp(80), barWidth - fixed);
            if (title instanceof TextView) {
                ((TextView) title).setMaxWidth(available);
            }
        });
    }

    /** dp → px for layout maths done in code. */
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Reflects {@link #project}'s name in the top bar. */
    private void updateProjectTitle() {
        if (editorTitle == null || project == null) return;
        editorTitle.setText(project.getName());
    }

    /** Rename sheet: the typed name becomes the project name and the export filename. */
    private void showRenameProjectSheet() {
        if (project == null) return;
        try {
            InputActionBottomSheetFragment sheet =
                    InputActionBottomSheetFragment.newFileNameInput(
                            getString(R.string.faditor_rename_project),
                            project.getName(),
                            getString(R.string.faditor_rename_hint),
                            getString(R.string.faditor_rename_action),
                            getString(R.string.faditor_rename_subtitle),
                            R.drawable.ic_rename,
                            getString(R.string.faditor_rename_helper),
                            getString(R.string.faditor_export_name_preview));
            sheet.setCallbacks(new InputActionBottomSheetFragment.Callbacks() {
                @Override
                public void onImportConfirmed(org.json.JSONObject json) { }

                @Override
                public void onResetConfirmed() { }

                @Override
                public void onInputConfirmed(String input) {
                    applyProjectName(input);
                }
            });
            sheet.show(getSupportFragmentManager(), "rename_project");
        } catch (Exception e) {
            FLog.e(TAG, "Failed to show rename sheet", e);
        }
    }

    /** Applies a user-typed project name, falling back when it is blank. */
    private void applyProjectName(@Nullable String input) {
        if (project == null) return;
        // The same slug the export applies, so the header and the file always agree.
        String name = com.fadcam.ui.faditor.util.FileNameSlug.slugify(input);
        if (name.isEmpty()) {
            name = getString(R.string.faditor_project_untitled);
        }
        if (name.equals(project.getName())) return;
        project.setName(name);
        updateProjectTitle();
        saveProjectNow();
        FLog.d(TAG, "Project renamed to: " + name);
    }

    /**
     * Mirrors the contextual-toolbar rule every professional editor uses: with nothing
     * selected the clip-scoped tools are visibly unavailable, and they come alive on the
     * first tap.
     *
     * <p>Canvas is project-level, so it stays available either way. Trim is driven by the
     * segment handles rather than a button, so it stays visible but is greyed with the
     * rest to signal that there is no handle to grab.</p>
     */
    private void updateToolAvailability() {
        Clip clip = getSelectedClip();
        int audioIndex = editorTimeline != null
                ? editorTimeline.getSelectedAudioIndex() : -1;
        boolean hasAudioClip = audioIndex >= 0 && project != null
                && project.getTimeline().hasAudioClips();
        boolean hasVideo = clip != null;
        boolean hasVideoAudio = hasVideo && !clip.isImageClip();
        float videoAlpha = hasVideo ? 1f : 0.35f;
        float audioAlpha = (hasVideoAudio || hasAudioClip) ? 1f : 0.35f;

        // Trim, split and delete work on the selected audio clip too.
        applyToolEnabled(toolTrim, hasVideo || hasAudioClip,
                (hasVideo || hasAudioClip) ? 1f : 0.35f);
        applyToolEnabled(toolSplit, hasVideo || hasAudioClip,
                (hasVideo || hasAudioClip) ? 1f : 0.35f);
        applyToolEnabled(toolDelete, hasVideo || hasAudioClip,
                (hasVideo || hasAudioClip) ? 1f : 0.35f);

        applyToolEnabled(toolRotate, hasVideo, videoAlpha);
        applyToolEnabled(toolFlip, hasVideo, videoAlpha);
        applyToolEnabled(toolCrop, hasVideo, videoAlpha);
        // Duplicate is video-only.
        applyToolEnabled(toolDuplicate, hasVideo, videoAlpha);

        // Volume works for both tracks; speed/audio extraction are video-with-audio only.
        applyToolEnabled(toolMute, hasVideoAudio || hasAudioClip, audioAlpha);
        applyToolEnabled(toolSpeed, hasVideoAudio, audioAlpha);
        applyToolEnabled(toolAudio, hasVideoAudio, audioAlpha);

        // Canvas is a project setting: never disable it.
        if (toolCanvas != null) {
            toolCanvas.setAlpha(1f);
            toolCanvas.setEnabled(true);
            toolCanvas.setClickable(true);
        }
    }

    private void applyToolEnabled(@Nullable View tool, boolean enabled, float alpha) {
        if (tool == null) return;
        tool.setAlpha(alpha);
        tool.setEnabled(enabled);
        tool.setClickable(enabled);
    }

    /**
     * Resets the clip-specific tools to their neutral state. Used when the selection is
     * cleared: the toolbar must stop implying an edit target that no longer exists.
     */
    private void clearSegmentTools() {
        updateVolumeUI(1f, false);
        updateSpeedUI(1f);
        updateRotateUI(0);
        updateFlipUI(false, false);
        updateCropUI("original");
        if (inCropMode) {
            exitCropMode(false);
        } else if (cropOverlay != null && cropOverlay.isActive()) {
            cropOverlay.deactivate();
        }
    }

    /** Clip at {@code index}, or null when it is out of range. */
    @Nullable
    private Clip clipAt(int index) {
        if (project == null) return null;
        Timeline tl = project.getTimeline();
        if (index < 0 || index >= tl.getClipCount()) return null;
        return tl.getClip(index);
    }

    /**
     * Builds and displays the preview composition at the current playhead.
     * This is the only playback engine: preview and export share the composition.
     */
    private void prepareCompositionPreview() {
        if (project == null || compositionPlayer == null) return;
        long position = editorTimeline != null
                ? editorTimeline.getPlayheadPositionMs() : 0L;
        applyComposition(position, false);
    }

    /** Rebuilds the composition after an edit, preserving playhead and play state. */
    private void rebuildComposition() {
        if (project == null || compositionPlayer == null) return;
        long position = editorTimeline != null
                ? editorTimeline.getPlayheadPositionMs() : 0L;
        boolean play = compositionPlayer.isPlayingOrBuffering();
        applyComposition(position, play);
        if (play) startPlayheadUpdates();
    }

    /** Schedules a debounced rebuild for property edits (sliders, canvas, volume). */
    private void scheduleCompositionRebuild() {
        if (project == null || compositionPlayer == null || userDragging) return;
        autoSaveHandler.removeCallbacks(compositionRefreshRunnable);
        autoSaveHandler.postDelayed(compositionRefreshRunnable, COMPOSITION_REBUILD_DELAY_MS);
    }

    private void applyComposition(long positionMs, boolean play) {
        if (project == null || compositionPlayer == null) return;
        Composition composition;
        try {
            composition = TimelineCompositionBuilder.build(
                    this, project, null, croplessPreviewClipId);
        } catch (RuntimeException e) {
            FLog.e(TAG, "Composition build failed", e);
            return;
        }
        if (composition == null) {
            FLog.w(TAG, "Composition not built: no playable clips");
            return;
        }
        long end = TimelineCompositionBuilder.compositionDurationMs(project.getTimeline());
        compositionDurationMs = end;
        long at = Math.max(0L, Math.min(positionMs, end));
        compositionPlayer.setComposition(composition, at, play);
        lastSeekTargetMs = at;
        applyPreviewAspect();
    }

    private boolean initProject(@NonNull Uri videoUri) {
        // Get video duration
        long durationMs = getVideoDuration(videoUri);
        if (durationMs <= 0) {
            FLog.e(TAG, "Could not read video duration for: " + videoUri);
            Toast.makeText(this, R.string.faditor_error_read_video, Toast.LENGTH_SHORT).show();
            finish();
            return false;
        }

        // Create project with a single clip. The name starts as the source file's
        // name — that is what the user recognises — and can be edited from the top
        // bar. The export file is named after it.
        project = new FaditorProject(projectNameFromUri(videoUri));
        Clip clip = new Clip(videoUri, durationMs);
        project.getTimeline().addClip(clip);

        updateProjectTitle();

        // Update total time display (project-wide duration)
        refreshTotalTimeDisplay();

        FLog.d(TAG, "Project created: duration=" + durationMs + "ms");
        return true;
    }

    private void initPlayer() {
        if (playerSurface == null) return;
        compositionPlayer = new FaditorCompositionPlayer(this);
        compositionPlayer.bindSurfaceView(playerSurface);
        compositionPlayer.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (!userDragging) {
                    boolean showPlaying = !playbackFinished
                            && (isPlaying || (compositionPlayer != null
                            && compositionPlayer.getPlayWhenReady()));
                    updatePlayPauseButton(showPlaying);
                }
                if (isPlaying) startPlayheadUpdates();
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED) {
                    playbackFinished = true;
                    stopPlayheadUpdates();
                    updatePlayPauseButton(false);
                    long end = compositionDurationMs;
                    editorTimeline.setPlayheadPositionMs(end);
                    updateCurrentTimeDisplay();
                    FLog.d(TAG, "playback finished: modelEnd=" + end
                            + " playerEnd=" + compositionPlayer.getDurationMs()
                            + " pos=" + compositionPlayer.getPositionMs());
                } else if (playbackState == Player.STATE_READY
                        || playbackState == Player.STATE_BUFFERING) {
                    playbackFinished = false;
                    if (playbackState == Player.STATE_READY && scrubbing) {
                        scrubSeekInFlight = false;
                    }
                    if (compositionPlayer != null && compositionPlayer.getPlayWhenReady()) {
                        startPlayheadUpdates();
                    }
                }
            }

            @Override
            public void onPlayerError(@NonNull androidx.media3.common.PlaybackException error) {
                FLog.e(TAG, "Composition playback error: " + error.getMessage(), error);
                playbackFinished = true;
                stopPlayheadUpdates();
                updatePlayPauseButton(false);
            }
        });

        playerSurface.post(this::applyPreviewAspect);
    }

    /**
     * Starts playback exactly where the playhead is.
     *
     * <p>The composition player's position is already project-timeline time, so there is
     * no clip arithmetic and no clip to load: seeking is the whole operation.</p>
     */
    private void startPlaybackFromPlayhead() {
        if (project == null || project.getTimeline().isEmpty()) return;
        if (compositionPlayer == null) {
            initPlayer();
            prepareCompositionPreview();
        }
        if (compositionPlayer == null) return;

        playbackFinished = false;
        editorTimeline.cancelActiveGesture();
        cancelScrubSeek();
        scrubbing = false;
        long end = compositionDurationMs;
        long playheadMs = editorTimeline.getPlayheadPositionMs();

        // Sitting on the end means "play again from the start", like every editor.
        if (end > 0 && playheadMs >= end - 1) {
            playheadMs = 0;
            editorTimeline.setPlayheadPositionMs(0);
        }

        // The drag released into a paused seek at this exact position. Seeking again
        // discards that buffered frame and is what made play stall for a second; only
        // seek when the player is genuinely somewhere else (or idle/ended).
        long playerPosition = compositionPlayer.getPositionMs();
        int state = compositionPlayer.getPlaybackState();
        boolean needsSeek = state != Player.STATE_READY
                || Math.abs(playerPosition - playheadMs) > 50;
        if (needsSeek) seekCompositionTo(playheadMs);
        compositionPlayer.play();
        updatePlayPauseButton(true);
        startPlayheadUpdates();
        FLog.d(TAG, "play: at=" + playheadMs + " modelEnd=" + end
                + " playerEnd=" + compositionPlayer.getDurationMs()
                + " pos=" + playerPosition + " state=" + state
                + (needsSeek ? " seek" : " buffered"));
    }

    private void startPlayheadUpdates() {
        choreographer.removeFrameCallback(playheadFrameCallback);
        if (!playbackFinished) choreographer.postFrameCallback(playheadFrameCallback);
    }

    private void stopPlayheadUpdates() {
        choreographer.removeFrameCallback(playheadFrameCallback);
    }

    /** Reads the composition position and mirrors it onto the timeline playhead. */
    private void updatePlayheadFromComposition() {
        if (project == null || compositionPlayer == null) return;
        if (userDragging || playbackFinished) return;
        if (!compositionPlayer.isPlayingOrBuffering()) return;

        long positionMs = compositionPlayer.getPositionMs();
        if (compositionDurationMs > 0 && positionMs > compositionDurationMs) {
            positionMs = compositionDurationMs;
        }
        if (positionMs != editorTimeline.getPlayheadPositionMs()) {
            editorTimeline.setPlayheadPositionMs(positionMs);
        }
        long second = positionMs / 1000L;
        if (second != lastDisplayedSecond) {
            lastDisplayedSecond = second;
            timeCurrent.setText(TimeFormatter.formatAuto(positionMs));
        }
    }

    /** Keeps the playhead inside the project after a trim or delete. */
    private void clampPlayheadToTimeline() {
        if (editorTimeline == null) return;
        long end = editorTimeline.getTimelineEndMs();
        if (editorTimeline.getPlayheadPositionMs() > end) {
            editorTimeline.setPlayheadPositionMs(end);
        }
    }

    private void updateCurrentTimeDisplay() {
        long absoluteMs = editorTimeline != null
                ? editorTimeline.getPlayheadPositionMs() : 0L;
        lastDisplayedSecond = absoluteMs / 1000L;
        timeCurrent.setText(TimeFormatter.formatAuto(absoluteMs));
    }

    /**
     * Re-arranges the editor for the current orientation.
     *
     * <p>Portrait keeps the vertical stack: preview, then time row, timeline and a
     * horizontal tool strip. Landscape has very little height, so the same views are
     * re-parented instead of duplicating an 800-line layout: the preview takes the
     * whole left side, the tool strip becomes a vertical column on the right, and only
     * the time row + timeline stay along the bottom. That gives the preview the space
     * it needs while every control stays reachable.</p>
     */
    private void applyEditorOrientationLayout() {
        LinearLayout root = findViewById(R.id.editor_root);
        View player = findViewById(R.id.player_container);
        android.view.ViewGroup controls = findViewById(R.id.controls_section);
        android.view.ViewGroup toolbarScroll = findViewById(R.id.tool_toolbar_scroll);
        LinearLayout toolbarRow = findViewById(R.id.tool_toolbar_row);
        if (root == null || player == null || controls == null
                || toolbarScroll == null || toolbarRow == null) return;

        boolean landscape = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;

        // ── Reset to the portrait arrangement first, so rotating back is clean ────
        View existingMain = root.findViewById(R.id.editor_main_area);
        if (existingMain instanceof android.view.ViewGroup) {
            android.view.ViewGroup mainGroup = (android.view.ViewGroup) existingMain;
            // Pull the player and the tool row back out before dropping the container.
            if (toolbarRow.getParent() instanceof android.view.ViewGroup
                    && toolbarRow.getParent() != toolbarScroll) {
                ((android.view.ViewGroup) toolbarRow.getParent()).removeView(toolbarRow);
            }
            if (player.getParent() == mainGroup) mainGroup.removeView(player);
            root.removeView(mainGroup);
        }
        if (toolbarRow.getParent() != toolbarScroll) {
            if (toolbarRow.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) toolbarRow.getParent()).removeView(toolbarRow);
            }
            toolbarRow.setOrientation(LinearLayout.HORIZONTAL);
            toolbarScroll.addView(toolbarRow);
        }
        toolbarRow.setOrientation(LinearLayout.HORIZONTAL);
        toolbarRow.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        if (player.getParent() == null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
            root.addView(player, Math.min(1, root.getChildCount()), lp);
        }
        if (toolbarScroll.getParent() != controls) {
            if (toolbarScroll.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) toolbarScroll.getParent()).removeView(toolbarScroll);
            }
            controls.addView(toolbarScroll);
        }
        setToolLabelsVisible(true);
        applyToolRailCompact(false, toolbarRow);

        if (!landscape) {
            FLog.d(TAG, "Editor layout: portrait");
            return;
        }

        // ── Landscape: preview left, vertical tool column right ──────────────────
        root.removeView(player);

        // The rail floats OVER the preview instead of taking a column away from it, so
        // the preview keeps its full width and stays where it was. A side-by-side split
        // shrank the preview and pushed it left.
        FrameLayout main = new FrameLayout(this);
        main.setId(R.id.editor_main_area);
        LinearLayout.LayoutParams mainLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(main, Math.min(1, root.getChildCount()), mainLp);

        main.addView(player, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        androidx.core.widget.NestedScrollView column = new androidx.core.widget.NestedScrollView(this);
        column.setFillViewport(false);
        // Same surface + rounding as the controls section and the top bar.
        column.setBackgroundResource(R.drawable.bg_editor_tool_rail);
        column.setVerticalScrollBarEnabled(false);
        int railPad = dp(4);
        column.setPadding(0, railPad, 0, railPad);
        column.setClipToPadding(false);
        // Compact floating rail, inset from the preview edges like a tool palette.
        FrameLayout.LayoutParams colLp = new FrameLayout.LayoutParams(
                dp(60), FrameLayout.LayoutParams.MATCH_PARENT);
        colLp.gravity = android.view.Gravity.END;
        colLp.topMargin = dp(8);
        colLp.bottomMargin = dp(8);
        colLp.setMarginEnd(dp(8));
        main.addView(column, colLp);

        // Move the tool row itself, not the horizontal scroller: a vertical list inside
        // a horizontal scroller cannot scroll.
        toolbarScroll.removeView(toolbarRow);
        toolbarRow.setOrientation(LinearLayout.VERTICAL);
        // Centre the fixed-width tool items inside the narrow column.
        toolbarRow.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        column.addView(toolbarRow, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        // The column is the scroller now; keep the old scroller out of the way.
        controls.removeView(toolbarScroll);

        // Labels cost height in a column that has little of it; the icons are enough and
        // the tool names remain discoverable through the tooltips/content descriptions.
        setToolLabelsVisible(false);
        applyToolRailCompact(true, toolbarRow);
        FLog.d(TAG, "Editor layout: landscape (floating compact rail)");
    }

    /**
     * Tool buttons are 72dp wide in the horizontal strip. In the compact landscape rail
     * they must shrink to the rail width, otherwise they are clipped.
     */
    private void applyToolRailCompact(boolean compact, @Nullable ViewGroup row) {
        if (row == null) return;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            ViewGroup.LayoutParams raw = child.getLayoutParams();
            if (!(raw instanceof LinearLayout.LayoutParams)) continue;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) raw;
            lp.width = compact ? LinearLayout.LayoutParams.MATCH_PARENT : dp(72);
            child.setLayoutParams(lp);
            int pad = compact ? dp(4) : dp(8);
            child.setPadding(pad, pad, pad, pad);
        }
    }

    /** Shows/hides the text labels under the tool icons. */
    private void setToolLabelsVisible(boolean visible) {
        int[] ids = {R.id.tool_trim_label, R.id.tool_speed_label, R.id.tool_mute_label,
                R.id.tool_rotate_label, R.id.tool_flip_label, R.id.tool_crop_label,
                R.id.tool_canvas_label, R.id.tool_audio_label, R.id.tool_split_label,
                R.id.tool_delete_label, R.id.tool_duplicate_label,
                R.id.tool_add_asset_label};
        for (int id : ids) {
            View label = findViewById(id);
            if (label != null) label.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onConfigurationChanged(@androidx.annotation.NonNull
            android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // The activity handles orientation itself, so re-apply the arrangement here.
        applyEditorOrientationLayout();
        if (playerSurface != null) playerSurface.post(this::applyPreviewAspect);
    }

    private void initTimeline() {
        // Open with no clip highlighted, like any professional editor: the tools come
        // alive on the first tap. The player still shows clip 0 at the playhead.
        editorTimeline.setTimeline(project.getTimeline(), selectedClipIndex);

        // If audio clips exist (e.g. from a saved project), show them on the track.
        if (project.getTimeline().hasAudioClips()) {
            editorTimeline.setAudioClips(project.getTimeline().getAudioClips());
            updateAudioToolUI();
        }

        FLog.d(TAG, "Timeline initialized with " + project.getTimeline().getClipCount()
                + " clip(s), selectedIndex=" + selectedClipIndex);
    }

    private void initToolbar() {
        // Play/Pause button
        btnPlayPause.setOnClickListener(v -> {
            if (compositionPlayer != null && compositionPlayer.isPlayingOrBuffering()) {
                compositionPlayer.pause();
                playbackFinished = true;
                stopPlayheadUpdates();
                updatePlayPauseButton(false);
                return;
            }
            startPlaybackFromPlayhead();
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

        // Nothing is selected on open, so start from the disabled state and only
        // populate the tool read-outs once there is a clip to read.
        updateToolAvailability();
        updateCanvasUI(project.getCanvasPreset());

        // Sync UI to existing clip state (e.g. reopened project)
        Clip clip = getSelectedClip();
        if (clip == null) {
            playerSurface.post(this::applyPreviewAspect);
            return;
        }
        updateVolumeUI(clip.getVolumeLevel(), clip.isAudioMuted());
        updateSpeedUI(clip.getSpeedMultiplier());
        updateRotateUI(clip.getRotationDegrees());
        updateFlipUI(clip.isFlipHorizontal(), clip.isFlipVertical());
        updateCropUI(clip.getCropPreset());
        updateCanvasUI(project.getCanvasPreset());

        // The composition already contains the clip transforms; the surface only needs
        // the output aspect ratio.
        playerSurface.post(this::applyPreviewAspect);

        // Crop is stored in the model but NOT shown as overlay on restore.
        // User must tap the Crop tool to enter crop mode.
        // The crop is applied by the composition for both preview and export.
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
                    updateVolumeUI(volume, muted);
                    scheduleCompositionRebuild();
                    scheduleAutoSave();
                });
                sheet.show(getSupportFragmentManager(), "volumeControl");
                return;
            }
        }

        Clip clip = getSelectedClip();
        if (clip == null || clip.isImageClip()) return;
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
            scheduleCompositionRebuild();
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
                color = FaditorPalette.ACCENT; // green for modified
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
        if (clip == null || clip.isImageClip()) return;
        float oldSpeed = clip.getSpeedMultiplier();
        SpeedSliderBottomSheet sheet = SpeedSliderBottomSheet.newInstance(clip.getSpeedMultiplier());
        sheet.setCallback(speed -> {
            if (oldSpeed != speed) {
                undoManager.recordAction(new EditActions.SpeedAction(
                        clip, oldSpeed, speed));
            }
            clip.setSpeedMultiplier(speed);
            // Speed changes the clip's presented length: the strip width, total time and
            // playhead bounds must follow immediately, not only after the next autosave.
            editorTimeline.setTrimFromClip(clip);
            refreshTotalTimeDisplay();
            clampPlayheadToTimeline();
            updateCurrentTimeDisplay();
            compositionDurationMs = project.getTimeline().getTotalDurationMs();
            updateSpeedUI(speed);
            scheduleCompositionRebuild();
            scheduleAutoSave();
        });
        sheet.show(getSupportFragmentManager(), "speed_slider");
    }

    private void updateSpeedUI(float speed) {
        if (toolSpeedLabel != null) {
            toolSpeedLabel.setText(formatSpeed(speed));
            int color = Math.abs(speed - 1f) < 0.001f ? 0xFF888888 : FaditorPalette.ACCENT;
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
        if (clip == null) return;
        int oldDeg = clip.getRotationDegrees();
        int newDeg = (oldDeg + 90) % 360;
        undoManager.recordAction(new EditActions.RotateAction(clip, oldDeg, newDeg));
        clip.setRotationDegrees(newDeg);
        updateRotateUI(newDeg);
        rebuildComposition();
        scheduleAutoSave();
    }

    private void updateRotateUI(int degrees) {
        boolean active = degrees != 0;
        int color = active ? FaditorPalette.ACCENT : 0xFF888888;
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
        if (clip == null) return;
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
            rebuildComposition();
            scheduleAutoSave();
        });
        sheet.show(getSupportFragmentManager(), "flipPicker");
    }

    private void updateFlipUI(boolean flipH, boolean flipV) {
        boolean active = flipH || flipV;
        int color = active ? FaditorPalette.ACCENT : 0xFF888888;
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
        Clip clip = getSelectedClip();
        if (inCropMode || clip == null) return;
        inCropMode = true;

        // Save current state for cancel restoration
        preCropPreset = clip.getCropPreset();
        preCropLeft   = clip.getCropLeft();
        preCropTop    = clip.getCropTop();
        preCropRight  = clip.getCropRight();
        preCropBottom = clip.getCropBottom();

        // Pause playback while cropping
        if (compositionPlayer != null && compositionPlayer.isPlayingOrBuffering()) {
            compositionPlayer.pause();
            playbackFinished = true;
            stopPlayheadUpdates();
            updatePlayPauseButton(false);
        }

        // Show the full uncropped frame while the crop UI is open. The rest of the
        // composition stays intact, so entering crop never changes the edit.
        croplessPreviewClipId = clip != null ? clip.getId() : null;
        rebuildComposition();

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
                if (c != null) c.setCustomCropBounds(0f, 0f, 1f, 1f);
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

        FLog.d(TAG, "Entered crop mode (saved: preset=" + preCropPreset
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

        if (apply && clip != null) {
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
            FLog.d(TAG, "Crop applied: preset=" + clip.getCropPreset()
                    + ", bounds=[" + clip.getCropLeft() + "," + clip.getCropTop()
                    + "," + clip.getCropRight() + "," + clip.getCropBottom() + "]");
        } else {
            // Revert to saved state
            if (clip != null) {
                clip.setCropPreset(preCropPreset);
                clip.setCustomCropBounds(preCropLeft, preCropTop, preCropRight, preCropBottom);
            }
            Toast.makeText(this, R.string.faditor_crop_cancelled, Toast.LENGTH_SHORT).show();
            FLog.d(TAG, "Crop cancelled, reverted to: " + preCropPreset);
        }

        // Deactivate the overlay
        if (cropOverlay != null && cropOverlay.isActive()) {
            cropOverlay.deactivate();
        }

        // Swap: show controls, hide crop toolbar
        if (cropToolbar != null) cropToolbar.setVisibility(View.GONE);
        if (controlsSection != null) controlsSection.setVisibility(View.VISIBLE);

        if (clip != null) updateCropUI(clip.getCropPreset());

        // The crop UI is gone; the composition carries the crop again.
        croplessPreviewClipId = null;
        rebuildComposition();

        // Defer the surface aspect until layout has settled — the player container
        // changes size when the controls section becomes visible again.
        FrameLayout container = findViewById(R.id.player_container);
        container.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        container.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        applyPreviewAspect();
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
                ((TextView) child).setTextColor(isActive ? FaditorPalette.ACCENT : 0xFFCCCCCC);
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

        // The composition renders the uncropped frame while this overlay is open, and
        // the surface is already sized to that frame's aspect ratio.
        playerSurface.post(() -> {
            android.graphics.RectF videoRect = computeVideoContentRect();
            FLog.d(TAG, "activateFreeCrop: videoRect=" + videoRect
                    + ", cropOverlay size=" + cropOverlay.getWidth() + "x" + cropOverlay.getHeight());
            cropOverlay.setVideoContentRect(videoRect);

            Clip clip = getSelectedClip();
            if (clip == null) return;
            if ("custom".equals(clip.getCropPreset())
                    && (clip.getCropLeft() > 0 || clip.getCropTop() > 0
                    || clip.getCropRight() < 1 || clip.getCropBottom() < 1)) {
                // Restore previous custom crop bounds
                FLog.d(TAG, "activateFreeCrop: restoring bounds ["
                        + clip.getCropLeft() + "," + clip.getCropTop()
                        + "," + clip.getCropRight() + "," + clip.getCropBottom() + "]");
                cropOverlay.activate(
                        clip.getCropLeft(), clip.getCropTop(),
                        clip.getCropRight(), clip.getCropBottom());
            } else {
                FLog.d(TAG, "activateFreeCrop: full frame (no prior custom bounds)");
                cropOverlay.activate();
            }

            cropOverlay.setOnCropChangeListener((left, top, right, bottom) -> {
                clip.setCustomCropBounds(left, top, right, bottom);
                scheduleAutoSave();
            });
        });
    }

    /**
     * The video content rect in container coordinates. The surface is constrained to
     * the composition's output aspect, so its bounds are the content rect.
     */
    @NonNull
    private android.graphics.RectF computeVideoContentRect() {
        float left = playerSurface.getLeft();
        float top = playerSurface.getTop();
        return new android.graphics.RectF(left, top,
                left + playerSurface.getWidth(), top + playerSurface.getHeight());
    }

    private void updateCropUI(@NonNull String preset) {
        boolean active = !"none".equals(preset);
        int color = active ? FaditorPalette.ACCENT : 0xFF888888;
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
            rebuildComposition();
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
        int color = active ? FaditorPalette.ACCENT : 0xFF888888;
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
     * Sizes the surface to the composition's output aspect ratio inside its container.
     * A raw SurfaceView stretches its frames to its bounds, so this is what letterboxes
     * the preview instead of distorting it.
     */
    private void applyPreviewAspect() {
        if (playerSurface == null || project == null || project.getTimeline().isEmpty()) return;
        FrameLayout container = findViewById(R.id.player_container);
        if (container == null) return;

        int containerW = container.getWidth();
        int containerH = container.getHeight();
        if (containerW <= 0 || containerH <= 0) {
            if (!isFinishing() && !isDestroyed()) container.post(this::applyPreviewAspect);
            return;
        }

        int[] output = TimelineCompositionBuilder.previewOutputSize(this, project);
        if (output == null) {
            FrameLayout.LayoutParams full = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT);
            full.gravity = android.view.Gravity.CENTER;
            playerSurface.setLayoutParams(full);
            return;
        }

        int outW = output[0];
        int outH = output[1];
        float aspect = (float) outW / outH;
        float containerAspect = (float) containerW / containerH;
        int targetW;
        int targetH;
        if (aspect > containerAspect) {
            targetW = containerW;
            targetH = Math.round(containerW / aspect);
        } else {
            targetH = containerH;
            targetW = Math.round(containerH * aspect);
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(targetW, targetH);
        lp.gravity = android.view.Gravity.CENTER;
        playerSurface.setLayoutParams(lp);
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
                    // Use the video duration when the audio track reports none
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
                    refreshTotalTimeDisplay();

                    // Record undo action for adding audio clip
                    undoManager.recordAction(new EditActions.AddAudioClipAction(
                            project.getTimeline(), audioClip,
                            srcClip, prevMuted, prevVolume));

                    // Mute the source video clip since audio is now on a separate track
                    if (srcClip != null) {
                        srcClip.setAudioMuted(true);
                        srcClip.setVolumeLevel(0f);
                        updateVolumeUI(0f, true);
                        FLog.i(TAG, "Auto-muted video clip after audio extraction: "
                                + "audioMuted=" + srcClip.isAudioMuted()
                                + ", volumeLevel=" + srcClip.getVolumeLevel());
                    } else {
                        FLog.w(TAG, "Could not auto-mute: getSelectedClip() returned null");
                    }

                    updateAudioToolUI();
                    rebuildComposition();
                    scheduleAutoSave();
                    Toast.makeText(this, R.string.faditor_audio_extracted,
                            Toast.LENGTH_SHORT).show();
                });

            } catch (Exception e) {
                FLog.e(TAG, "Audio extraction failed", e);
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
                        ByteBuffer buf = codec.getInputBuffer(inIdx);
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
                    ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                    outBuf.position(info.offset);
                    outBuf.limit(info.offset + info.size);
                    ShortBuffer shorts = outBuf.asShortBuffer();
                    while (shorts.hasRemaining()) {
                        allSamples.add(shorts.get());
                    }
                    codec.releaseOutputBuffer(outIdx, false);
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
            FLog.e(TAG, "Waveform generation failed", e);
            return null;
        }
    }

    /**
     * Updates the audio tool button UI based on whether audio clips exist.
     */
    private void updateAudioToolUI() {
        boolean hasAudio = project != null && project.getTimeline().hasAudioClips();
        int color = hasAudio ? FaditorPalette.ACCENT : 0xFF888888;
        if (toolAudioIcon != null) toolAudioIcon.setTextColor(color);
        if (toolAudioLabel != null) toolAudioLabel.setTextColor(color);
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
                    // The export is now owned by the foreground service. Hand the user
                    // back to the Faditor tab, whose header dock carries live progress.
                    runOnUiThread(() -> {
                        if (!isFinishing()) returnToFaditorOnExportStart();
                    });
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
                        FLog.d(TAG, "Export saved to: " + outputPath);

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
                        // Stay in the editor: the user needs to see why the export did
                        // not start rather than being dropped on the Faditor tab with
                        // nothing to show for it.
                        String detail = error.getMessage() != null
                                ? error.getMessage() : error.getClass().getSimpleName();
                        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                                FaditorEditorActivity.this)
                                .setTitle(R.string.faditor_export_failed)
                                .setMessage(getString(R.string.faditor_export_error, detail))
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                        FLog.e(TAG, "Export failed", error);
                    });
                }
            };

    /**
     * Closes the editor as soon as the export service has taken over, returning to the
     * Faditor tab. The project is saved first so the entry in Recent Projects is current,
     * and the caller's result refreshes that list.
     */
    private void returnToFaditorOnExportStart() {
        saveProjectNow();
        setResult(RESULT_OK);
        finish();
    }

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

    // ── Export helpers ────────────────────────────────────────────────

    /**
     * Shows the export settings sheet (resolution + quality). Confirming it starts the
     * export through the foreground service with the shared composition.
     */
    private void showExportConfirmation() {
        if (isExportRunning()) {
            Toast.makeText(this, R.string.faditor_export_in_progress, Toast.LENGTH_SHORT).show();
            return;
        }

        ExportSettings settings = project.getExportSettings();
        ExportSettingsBottomSheet sheet = ExportSettingsBottomSheet.newInstance(
                settings.getResolution(), settings.getQuality());
        sheet.setCallback((resolution, quality) -> {
            settings.setResolution(resolution);
            settings.setQuality(quality);
            saveProjectNow();
            startExportViaService();
        });
        sheet.show(getSupportFragmentManager(), "export_settings");
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
        if (compositionPlayer != null) {
            compositionPlayer.pause();
            updatePlayPauseButton(false);
        }

        // Pass project to the service via static bridge
        ExportService.setPendingProject(project);

        // Start and bind to service
        android.content.Intent serviceIntent =
                new android.content.Intent(this, ExportService.class);
        serviceIntent.setAction(ExportService.ACTION_START_EXPORT);

        // Bind BEFORE starting: the service emits "started" exactly once, and a late
        // bind used to miss it, leaving the editor stuck with no reason to close.
        bindService(new android.content.Intent(this, ExportService.class),
                exportServiceConnection, BIND_AUTO_CREATE);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        // Hold the editor on the progress overlay until the service confirms it has
        // actually started. Leaving earlier would swallow a start failure; leaving
        // later traps the user behind a full-screen overlay.
        showExportInfoOnScreen();
        showExportProgress();
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
                    getString(R.string.faditor_close_confirm_helper),
                    false);   // closing saves the project; nothing is destroyed

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
            FLog.e(TAG, "Failed to show close confirmation sheet, using the dialog form", e);
            showCloseConfirmationDialog();
        }
    }

    /**
     * Dialog form used when the confirmation sheet cannot be shown.
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
            FLog.e(TAG, "Failed to restore project from snapshot");
            return;
        }
        this.project = restored;
        FLog.d(TAG, "Project restored from snapshot, clips="
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

        // Rebuild timeline view completely (handles both action and snapshot changes)
        editorTimeline.setTimeline(project.getTimeline(), selectedClipIndex);
        if (clip != null) {
            editorTimeline.setTrimFromClip(clip);
        }
        editorTimeline.setAudioClips(project.getTimeline().getAudioClips());

        // Update toolbar UI
        if (clip != null) {
            if (clip.isImageClip()) {
                updateVolumeUI(1f, false);
                updateSpeedUI(1f);
            } else {
                updateVolumeUI(clip.getVolumeLevel(), clip.isAudioMuted());
                updateSpeedUI(clip.getSpeedMultiplier());
            }
            updateRotateUI(clip.getRotationDegrees());
            updateFlipUI(clip.isFlipHorizontal(), clip.isFlipVertical());
            updateCropUI(clip.getCropPreset());
        }
        updateCanvasUI(project.getCanvasPreset());
        updateAudioToolUI();
        updateToolAvailability();

        // The composition carries every transform, so the model change is the preview.
        clampPlayheadToTimeline();
        rebuildComposition();

        // Update time displays
        updateCurrentTimeDisplay();
        refreshTotalTimeDisplay();
    }

    // ── Auto-save ────────────────────────────────────────────────────

    private void scheduleAutoSave() {
        autoSaveHandler.removeCallbacks(autoSaveRunnable);
        autoSaveHandler.postDelayed(autoSaveRunnable, AUTO_SAVE_DELAY_MS);
        scheduleCompositionRebuild();
    }

    /**
     * Refreshes the preview composition after an edit.
     *
     * <p>Debounced so a burst of changes (dragging a slider, several trims) rebuilds
     * once. The playhead and play state are restored afterwards.</p>
     */
    private final Runnable compositionRefreshRunnable = () -> {
        if (compositionPlayer == null || project == null) return;
        long positionMs = editorTimeline.getPlayheadPositionMs();
        boolean wasPlaying = compositionPlayer.isPlayingOrBuffering();
        rebuildComposition();
        FLog.d(TAG, "Composition refreshed at " + positionMs + "ms playing=" + wasPlaying);
    };

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

            FLog.d(TAG, "Project saved: " + project.getId());
        }
    }

    // ── Utility ──────────────────────────────────────────────────────

    /**
     * Get video duration using FFprobeKit (reliable for fragmented MP4),
     * with a MediaMetadataRetriever pass.
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
                    FLog.d(TAG, "Duration from FFprobe: " + durationMs + "ms");
                    return durationMs;
                }
            }
        } catch (Exception e) {
            FLog.e(TAG, "FFprobe duration failed", e);
        }

        // ── MediaMetadataRetriever pass ──────────────────────────
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            if ("file".equals(videoUri.getScheme()) && videoUri.getPath() != null) {
                retriever.setDataSource(videoUri.getPath());
            } else {
                // For content:// URIs, try to resolve to a file path first
                // (ContentResolver-based setDataSource can fail on SD card)
                String resolvedPath = resolveSafPath(videoUri);
                if (resolvedPath != null) {
                    java.io.File f = new java.io.File(resolvedPath);
                    if (f.exists() && f.canRead()) {
                        retriever.setDataSource(resolvedPath);
                    } else {
                        retriever.setDataSource(this, videoUri);
                    }
                } else {
                    retriever.setDataSource(this, videoUri);
                }
            }
            String durationStr = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                long ms = Long.parseLong(durationStr);
                FLog.d(TAG, "Duration from MediaMetadataRetriever: " + ms + "ms");
                return ms;
            }
        } catch (Exception e) {
            FLog.e(TAG, "MMR duration failed", e);
        } finally {
            if (retriever != null) {
                try { retriever.release(); } catch (Exception ignored) { }
            }
        }

        return -1;
    }

    /**
     * Build a file path suitable for FFprobeKit.
     * Uses {@link #resolveSafPath(Uri)} for SAF content:// URIs from
     * both internal storage and SD card.
     */
    @NonNull
    private String getFFprobePathForUri(@NonNull Uri uri) {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            return uri.getPath();
        }
        String resolved = resolveSafPath(uri);
        if (resolved != null) {
            java.io.File f = new java.io.File(resolved);
            if (f.exists() && f.canRead()) {
                FLog.d(TAG, "FFprobe using resolved path: " + resolved);
                return resolved;
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
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(isImage ? "image/*" : "video/*");
            // OPEN_DOCUMENT grants a persistable permission, so the URIs survive an
            // editor restart. GET_CONTENT does not, which is what made image/video
            // assets turn into unreadable clips after reopening a project.
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            if (isImage) {
                imagePickerLauncher.launch(intent);
            } else {
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
                FLog.w(TAG, "Could not take persistable URI permission", e);
            }

            Clip imageClip = new Clip(imageUri, IMAGE_CLIP_DURATION_MS);
            imageClip.setImageClip(true);
            imageClip.setAudioMuted(true); // Images have no audio

            // Insert after the selected clip; with nothing selected, after the clip
            // under the playhead; if that is also unavailable, append.
            Timeline timeline = project.getTimeline();
            int insertIndex = insertionIndexForNewClip();
            timeline.addClip(insertIndex, imageClip);

            // Record undo action
            undoManager.recordAction(new EditActions.AddClipAction(
                    timeline, imageClip, insertIndex));

            // Show the inserted clip, then select it.
            refreshTimelineAfterStructuralChange();
            selectSegment(insertIndex);
            refreshTotalTimeDisplay();
            saveProjectNow();

            Toast.makeText(this, R.string.faditor_asset_added, Toast.LENGTH_SHORT).show();
            FLog.d(TAG, "Image asset added at index " + insertIndex
                    + " duration=" + IMAGE_CLIP_DURATION_MS + "ms uri=" + imageUri);
        } catch (Exception e) {
            FLog.e(TAG, "Failed to add image asset", e);
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
                FLog.w(TAG, "Could not take persistable URI permission", e);
            }

            long durationMs = getVideoDuration(videoUri);
            if (durationMs <= 0) {
                FLog.w(TAG, "Could not determine video duration for added asset");
                Toast.makeText(this, R.string.faditor_asset_error, Toast.LENGTH_SHORT).show();
                return;
            }

            File sourceFile = resolveToFile(videoUri);
            if (sourceFile != null && remuxer.needsRemux(sourceFile)) {
                if (remuxer.hasRemuxedVersion(sourceFile)) {
                    insertVideoClip(Uri.fromFile(remuxer.getRemuxedFile(sourceFile)),
                            durationMs);
                    return;
                }
                // An fMP4 added asset must be converted before it can seek in the
                // composition; otherwise it is added but play/seek do nothing.
                showRemuxProgress();
                remuxer.remuxAsync(sourceFile, new FragmentedMp4Remuxer.RemuxCallback() {
                    @Override
                    public void onRemuxComplete(boolean success, @Nullable String outputPath) {
                        runOnUiThread(() -> {
                            hideRemuxProgress();
                            if (success && outputPath != null) {
                                insertVideoClip(Uri.fromFile(new File(outputPath)), durationMs);
                            } else {
                                Toast.makeText(FaditorEditorActivity.this,
                                        R.string.faditor_asset_error, Toast.LENGTH_SHORT).show();
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
                return;
            }

            Uri playableUri = sourceFile != null ? Uri.fromFile(sourceFile) : videoUri;
            insertVideoClip(playableUri, durationMs);
        } catch (Exception e) {
            FLog.e(TAG, "Failed to add video asset", e);
            Toast.makeText(this, R.string.faditor_asset_error, Toast.LENGTH_SHORT).show();
        }
    }

    /** Adds an already-playable video source to the timeline and selects it. */
    private void insertVideoClip(@NonNull Uri playableUri, long durationMs) {
        if (project == null) return;
        Clip videoClip = new Clip(playableUri, durationMs);

        // Insert after the selected clip; with nothing selected, after the clip
        // under the playhead; if that is also unavailable, append.
        Timeline timeline = project.getTimeline();
        int insertIndex = insertionIndexForNewClip();
        timeline.addClip(insertIndex, videoClip);

        // Record undo action
        undoManager.recordAction(new EditActions.AddClipAction(
                timeline, videoClip, insertIndex));

        // Show the inserted clip, then select it.
        refreshTimelineAfterStructuralChange();
        selectSegment(insertIndex);
        refreshTotalTimeDisplay();
        saveProjectNow();

        Toast.makeText(this, R.string.faditor_asset_added, Toast.LENGTH_SHORT).show();
        FLog.d(TAG, "Video asset added at index " + insertIndex
                + " duration=" + durationMs + "ms uri=" + playableUri);
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

            if (compositionPlayer != null && compositionPlayer.isPlayingOrBuffering()) {
                compositionPlayer.pause();
                updatePlayPauseButton(false);
            }
            playbackFinished = true;
            stopPlayheadUpdates();

            // Derive the split point from the VISUAL timeline playhead (authoritative)
            // rather than the player position, which may not have settled yet if a seek
            // was still in-flight when the user tapped the split button.
            long playheadMs = editorTimeline.getPlayheadPositionMs();
            long segStartMs = editorTimeline.getSegmentStartTimeMs(selectedClipIndex);
            long localEffectiveMs = Math.max(0, playheadMs - segStartMs);
            // Convert effective (timeline) time → absolute source position (accounts for speed)
            long absoluteSplitMs = clip.getInPointMs()
                    + (long)(localEffectiveMs * clip.getSpeedMultiplier());
            absoluteSplitMs = Math.max(clip.getInPointMs(),
                    Math.min(absoluteSplitMs, clip.getOutPointMs()));

            FLog.d(TAG, "splitAtPlayhead: playhead=" + playheadMs + " segStart=" + segStartMs
                    + " local=" + localEffectiveMs + " absoluteSplit=" + absoluteSplitMs);

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

            // The clip list changed: the strip must be rebuilt before the highlight is
            // moved, otherwise the two halves never appear.
            refreshTimelineAfterStructuralChange();
            selectSegment(selectedClipIndex);
            saveProjectNow();
            Toast.makeText(this, R.string.faditor_split_success, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            FLog.e(TAG, "splitAtPlayhead failed", e);
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
        rebuildComposition();
        updateToolAvailability();
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

            // With nothing highlighted, act on the segment under the playhead. The
            // toolbar used to do nothing at all in that state, which read as a bug.
            int deleteIndex = selectedClipIndex >= 0
                    ? selectedClipIndex : editorTimeline.getSegmentAtPlayhead();
            if (deleteIndex < 0 || deleteIndex >= timeline.getClipCount()) {
                Toast.makeText(this, R.string.faditor_select_segment_first,
                        Toast.LENGTH_SHORT).show();
                return;
            }

            // Record undo action before deletion
            Clip deletedClip = timeline.getClip(deleteIndex);
            int deletedIndex = deleteIndex;
            undoManager.recordAction(new EditActions.DeleteClipAction(
                    timeline, deletedClip, deletedIndex));

            timeline.removeClip(deleteIndex);

            int newIndex = Math.min(deleteIndex, timeline.getClipCount() - 1);
            selectedClipIndex = newIndex;
            refreshTimelineAfterStructuralChange();
            selectSegment(newIndex);
            saveProjectNow();
            Toast.makeText(this, R.string.faditor_segment_deleted, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            FLog.e(TAG, "deleteSelectedSegment failed", e);
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
        // An audio clip can be the longest track, so removing it changes the total.
        clampPlayheadToTimeline();
        refreshTotalTimeDisplay();
        rebuildComposition();
        updateAudioToolUI();
        updateToolAvailability();
        saveProjectNow();
        Toast.makeText(this, R.string.faditor_segment_deleted, Toast.LENGTH_SHORT).show();
    }

    /**
     * Duplicate the currently selected segment (inserts a copy right after it).
     */
    private void duplicateSelectedSegment() {
        try {
            Timeline timeline = project.getTimeline();
            int sourceIndex = selectedClipIndex >= 0
                    ? selectedClipIndex : editorTimeline.getSegmentAtPlayhead();
            int newIndex = timeline.duplicateClip(sourceIndex);
            if (newIndex < 0) return;

            // Record undo action for the duplication
            Clip duplicated = timeline.getClip(newIndex);
            undoManager.recordAction(new EditActions.DuplicateClipAction(
                    timeline, duplicated, newIndex));

            // Show the copy before selecting it, so the highlight lands on the new clip
            // instead of on the neighbour that still occupied that index on screen.
            refreshTimelineAfterStructuralChange();
            selectSegment(newIndex);
            saveProjectNow();
            Toast.makeText(this, R.string.faditor_segment_duplicated, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            FLog.e(TAG, "duplicateSelectedSegment failed", e);
        }
    }

}
