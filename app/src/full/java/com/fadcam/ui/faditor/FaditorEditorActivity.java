package com.fadcam.ui.faditor;

import com.fadcam.ui.faditor.util.FaditorPalette;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.BitmapFactory;
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
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
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
    private PlayerView playerView;
    private View playerContainer;
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
    /** Index of the currently selected clip/segment in the timeline. */
    /** Index of the highlighted clip, or -1 when nothing is selected. */
    private int selectedClipIndex = -1;

    /**
     * Index of the clip currently loaded in the player.
     *
     * <p>Deliberately separate from {@link #selectedClipIndex}: in a professional
     * editor the preview follows the <em>playhead</em>, while the selection only says
     * which clip an edit applies to. Coupling them is what made tapping a clip jump the
     * timeline and made scrubbing silently re-target the toolbar.</p>
     */
    private int playbackClipIndex = 0;

    /** Player position we expect after a seek; -1 when no guard is active. */
    private long playbackSeekTargetMs = -1;
    private long playbackSeekDeadlineMs = 0;
    /** True once the queue/end of a clip has been reached; polling must stay stopped. */
    private boolean playbackFinished;

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
            FLog.d(TAG, "Project auto-saved");
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
                FLog.e(TAG, "Error in updatePlayheadPosition", e);
            }
            playheadHandler.postDelayed(this, PLAYHEAD_UPDATE_INTERVAL_MS);
        }
    };

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
            playbackClipIndex = 0;
        }
        // The clip list changed, so any queued playlist is stale.
        playerManager.exitPlaylistMode();
        editorTimeline.setTimeline(project.getTimeline(), selectedClipIndex);
        editorTimeline.setAudioClips(project.getTimeline().getAudioClips());
        updateToolAvailability();
        refreshTotalTimeDisplay();
        FLog.d(TAG, "Timeline rebuilt: clips=" + project.getTimeline().getClipCount()
                + " selected=" + selectedClipIndex + " playback=" + playbackClipIndex);
    }

    /**
     * Where a newly added asset belongs: right after the selected clip, otherwise right
     * after the clip under the playhead, otherwise at the end.
     */
    private int insertionIndexForNewClip() {
        Timeline tl = project.getTimeline();
        int anchor = selectedClipIndex;
        if (anchor < 0) anchor = editorTimeline.getSegmentAtPlayhead();
        if (anchor < 0) anchor = playbackClipIndex;
        if (anchor < 0 || anchor >= tl.getClipCount()) return tl.getClipCount();
        return anchor + 1;
    }

    /** Clip loaded in the player, mirroring the playhead. Defaults to the first clip. */
    @Nullable
    private Clip getPlaybackClip() {
        if (project == null) return null;
        Timeline tl = project.getTimeline();
        if (tl.getClipCount() == 0) return null;
        if (playbackClipIndex < 0 || playbackClipIndex >= tl.getClipCount()) {
            playbackClipIndex = 0;
        }
        return tl.getClip(playbackClipIndex);
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
        // to" and the playhead answers "what moment am I looking at". Tapping a clip
        // used to call setPlayheadFraction(), which is why the white line jumped to a
        // clip edge on a tap.
        updateVolumeUI(clip.getVolumeLevel(), clip.isAudioMuted());
        updateSpeedUI(clip.getSpeedMultiplier());
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
                + editorTimeline.getPlayheadPositionMs() + "ms, playbackClip="
                + playbackClipIndex + ")");

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
                updateCurrentTimeDisplay(0);
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
                if (!clip.isImageClip()) {
                    playerManager.updateTrimBounds(clip);
                }
                updateCurrentTimeDisplay(0);
                refreshTotalTimeDisplay();
                saveProjectNow();
            }

            @Override
            public void onPlayheadSeeked(int segmentIndex, float fractionInSegment, boolean isDragging) {
                userDragging = true;
                if (isDragging && imagePlaybackActive) {
                    stopImagePlayback();
                }
                audioTailActive = false;  // Cancel audio-tail on seek
                // Get clip directly — DON'T call selectSegment() during scrubbing.
                // selectSegment triggers setPlayheadFraction → centerPlayhead which
                // modifies scrollOffsetPx, causing a feedback loop that makes the
                // timeline bounce between segments.
                Timeline tl = project.getTimeline();
                if (segmentIndex < 0 || segmentIndex >= tl.getClipCount()) return;
                Clip clip = tl.getClip(segmentIndex);
                if (clip == null) return;

                // If crossing to a different segment and NOT actively dragging,
                // load the new clip in the player (needed for correct seek bounds).
                // During drag, we skip loading to prevent snapping at split points.
                // When the drag ends, onPlayheadDragFinished() will load the new clip.
                boolean playbackClipChanged = segmentIndex != playbackClipIndex;
                if (playbackClipChanged) {
                    playbackClipIndex = segmentIndex;
                    editorTimeline.setPlaybackIndex(segmentIndex);
                    // Scrubbing follows the playhead: it loads the clip under it so
                    // seeking has the right bounds. It must never touch the selection —
                    // dragging across clips used to re-target the toolbar behind the
                    // user's back.

                }
                // The player is only reloaded once the drag has settled; doing it on
                // every move made the preview snap between clips mid-gesture.
                if (!isDragging && playbackClipChanged) {
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
                    // Only seek ExoPlayer for video clips.
                    if (isDragging) {
                        // Stop the running queue the moment a scrub begins: otherwise
                        // it keeps playing (and advancing) underneath the finger and the
                        // playhead poll drags the line back to the player's position.
                        if (playerManager.isPlayingNow()) {
                            playerManager.pause();
                            updatePlayPauseButton(false);
                            playbackFinished = true;
                            FLog.d(TAG, "scrub: paused playback so the drag owns the playhead");
                        }
                        // Scrubbing used to seek on every move. That re-buffered the
                        // player continuously (log showed a seek every ~7 ms), which is
                        // exactly the stutter and the playhead fighting the finger.
                        // The preview catches up once on drag end.

                    } else {
                        // Pause FIRST so ExoPlayer renders the decoded frame to
                        // TextureView (the frame only becomes visible with
                        // playWhenReady=false and a completed seek).
                        playerManager.pause();
                        playerManager.seekTo(seekPosition);
                    }
                } else {
                    // For image clips: store the seek position for playback resumption
                    imagePlaybackStartOffsetMs = seekPosition;
                }
                // Update both timeline playhead position AND time display
                // Only update playhead fraction when NOT dragging to avoid calculated-position jumping
                // During drag, updatePlayheadFromX already set correct playheadPositionMs
                if (!isDragging) {
                    editorTimeline.setPlayheadFraction(segmentIndex, fractionInSegment);
                }
                updateCurrentTimeDisplay(seekPosition);
            }

            @Override
            public void onPlayheadDragFinished() {
                userDragging = false;
                // Scrubbing always ends playback. Any engine left running here produced
                // a stale pause icon and a preview that kept moving on its own.
                if (imagePlaybackActive) stopImagePlayback();
                if (playerManager.isPlayingNow()) playerManager.pause();
                playbackFinished = true;
                updatePlayPauseButton(false);
                
                // Now that drag is finished, check if we crossed into a different segment
                // If so, load that clip now (we skipped it during the drag to avoid snapping)
                Timeline tl = project.getTimeline();
                int segmentAtPlayhead = editorTimeline.getSegmentAtPlayhead();
                if (segmentAtPlayhead >= 0 && segmentAtPlayhead != playbackClipIndex) {
                    playbackClipIndex = segmentAtPlayhead;
                    editorTimeline.setPlaybackIndex(segmentAtPlayhead);
                    FLog.d(TAG, "drag finished: playbackClipIndex=" + segmentAtPlayhead
                            + " (selection stays " + selectedClipIndex + ")");
                    Clip clip = tl.getClip(segmentAtPlayhead);
                    if (clip != null) {
                        if (clip.isImageClip()) {
                            showImagePreview(clip.getSourceUri());
                            stopImagePlayback();
                        } else {
                            hideImagePreview();
                            playerManager.loadClip(clip);
                            playerManager.setVolume(clip.isAudioMuted() ? 0f : clip.getVolumeLevel());
                            playerManager.setPlaybackSpeed(clip.getSpeedMultiplier());
                            updatePreviewTransforms();
                        }
                    }
                }
                
                // Commit exactly one seek now that the finger is up. Everything the
                // scrub did was visual; this is where the player catches up.
                Clip playing = clipAt(segmentAtPlayhead >= 0 ? segmentAtPlayhead : playbackClipIndex);
                if (playing != null && !playing.isImageClip()) {
                    long playheadMs = editorTimeline.getPlayheadPositionMs();
                    long segStartMs = editorTimeline.getSegmentStartTimeMs(playbackClipIndex);
                    long localEff = Math.max(0, playheadMs - segStartMs);
                    long localSrc = playing.getInPointMs()
                            + (long) (localEff * playing.getSpeedMultiplier());
                    localSrc = Math.max(playing.getInPointMs(),
                            Math.min(localSrc, playing.getOutPointMs()));
                    playerManager.pause();
                    playerManager.seekTo(localSrc - playing.getInPointMs());
                    armPlaybackSeekGuard(localSrc - playing.getInPointMs());
                    FLog.d(TAG, "drag end: single seek to " + (localSrc - playing.getInPointMs())
                            + "ms in clip " + playbackClipIndex);
                }

                // The toolbar reflects the selection, which scrubbing does not change.
                // It only needs refreshing if a clip is actually selected.
                Clip selected = getSelectedClip();
                if (selected != null) {
                    updateVolumeUI(selected.getVolumeLevel(), selected.isAudioMuted());
                    updateSpeedUI(selected.getSpeedMultiplier());
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

        // Build a file:// URI when the source resolves to a readable file.
        // This ensures ExoPlayer uses FileDataSource (supports random-access
        // seeking) instead of ContentDataSource (limited seeking).
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
        updateProjectTitle();
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
        boolean hasClip = getSelectedClip() != null;
        float alpha = hasClip ? 1f : 0.35f;
        View[] clipTools = {toolTrim, toolSpeed, toolMute, toolRotate, toolFlip,
                toolCrop, toolAudio, toolSplit, toolDelete, toolDuplicate};
        for (View tool : clipTools) {
            if (tool == null) continue;
            tool.setAlpha(alpha);
            tool.setEnabled(hasClip);
            tool.setClickable(hasClip);
        }
        // Canvas is a project setting: never disable it.
        if (toolCanvas != null) {
            toolCanvas.setAlpha(1f);
            toolCanvas.setEnabled(true);
            toolCanvas.setClickable(true);
        }
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

    private void initProject(@NonNull Uri videoUri) {
        // Get video duration
        long durationMs = getVideoDuration(videoUri);
        if (durationMs <= 0) {
            FLog.w(TAG, "Could not determine video duration, using fallback");
            durationMs = 60_000; // Fallback 1 minute
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
    }

    private void initPlayer() {
        playerManager = new FaditorPlayerManager(this);
        getLifecycle().addObserver(playerManager);
        playerManager.setPlayerView(playerView);

        // Load the clip at the playhead (nothing is selected on open)
        Clip clip = getSelectedClip();
        if (clip == null) clip = clipAt(0);
        if (clip == null) return;
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
            public void onMediaItemTransition(@Nullable androidx.media3.common.MediaItem mediaItem,
                                              int reason) {
                if (!playerManager.isPlaylistMode()) return;
                int index = playerManager.getCurrentPlaylistIndex();
                Clip playing = playerManager.getCurrentPlaylistClip();
                if (index < 0 || playing == null) return;
                playbackClipIndex = index;
                editorTimeline.setPlaybackIndex(index);
                applyPlayingClipState(playing);
                FLog.d(TAG, "media item transition: clip=" + index + " reason=" + reason);
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED && !audioTailActive) {
                    updatePlayPauseButton(false);
                    playbackSeekTargetMs = -1;
                    // Stop polling. playWhenReady stays true at the end of a queue, so
                    // the updater kept running and re-wrote the playhead to the last
                    // clip after the user had dragged somewhere else.
                    playbackFinished = true;
                    playheadHandler.removeCallbacks(playheadUpdater);
                    FLog.d(TAG, "playback finished: updater stopped");
                } else if (playbackState == Player.STATE_READY
                        || playbackState == Player.STATE_BUFFERING) {
                    playbackFinished = false;
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

    /**
     * Starts playback exactly where the playhead is.
     *
     * <p>The playhead is the single source of truth. The clip under it is resolved
     * from the timeline at press time, loaded into the player if it is not already
     * there, and the player is seeked to the matching source position. Relying on a
     * cached index here is what used to make play jump to the last clip: the player
     * still held clip 0 while the playhead sat at 9s, the seek clamped to the end of
     * clip 0, and the "at trim end" handler then auto-advanced clip after clip.</p>
     */
    private void startPlaybackFromPlayhead() {
        if (project == null || project.getTimeline().isEmpty()) return;
        playbackFinished = false;

        long playheadMs = editorTimeline.getPlayheadPositionMs();
        long videoEndMs = totalEffectiveMs();
        long timelineEndMs = editorTimeline.getTimelineEndMs();

        // Sitting on the end means "play again from the start", like every editor.
        // Otherwise the seek lands on the last frame and only the final moments replay.
        if (videoEndMs > 0 && playheadMs >= videoEndMs - 1 && timelineEndMs <= videoEndMs + 1) {
            playheadMs = 0;
            editorTimeline.setPlayheadPositionMs(0);
            FLog.d(TAG, "play: playhead was at the end, restarting from 0");
        }

        // Playhead past the video but within an audio tail: play only audio.
        if (playheadMs >= videoEndMs && timelineEndMs > videoEndMs) {
            audioTailActive = true;
            audioTailStartWall = android.os.SystemClock.elapsedRealtime();
            audioTailStartMs = playheadMs;
            btnPlayPause.setText("pause");
            syncAndPlayAudioPlayer();
            playheadHandler.post(playheadUpdater);
            FLog.d(TAG, "Play from audio tail: playhead=" + playheadMs + "ms");
            return;
        }

        int index = editorTimeline.getSegmentIndexAtTime(playheadMs);
        if (index < 0) index = 0;
        Clip clip = clipAt(index);
        if (clip == null) return;
        playbackClipIndex = index;
        editorTimeline.setPlaybackIndex(index);

        long segmentStartMs = editorTimeline.getSegmentStartTimeMs(index);
        long localEffectiveMs = Math.max(0, playheadMs - segmentStartMs);
        // The playhead counts timeline (effective) time; the player counts source time.
        long localSourceMs = clip.getInPointMs()
                + (long) (localEffectiveMs * clip.getSpeedMultiplier());
        localSourceMs = Math.max(clip.getInPointMs(),
                Math.min(localSourceMs, clip.getOutPointMs()));

        FLog.d(TAG, "play: playhead=" + playheadMs + "ms index=" + index
                + " segStart=" + segmentStartMs + " localEff=" + localEffectiveMs
                + " localSrc=" + localSourceMs
                + " loadedClip=" + (playerManager.getLoadedClip() != null
                    ? playerManager.getLoadedClip().getId() : "none"));

        if (clip.isImageClip()) {
            // A queued playlist from a previous press would keep advancing underneath
            // the still image — that is what made the playhead jump to the end.
            if (playerManager.isPlayingNow() || playerManager.isPlaylistMode()) {
                playerManager.pause();
                playerManager.exitPlaylistMode();
            }
            showImagePreview(clip.getSourceUri());
            startImagePlayback(localEffectiveMs);
            updatePlayPauseButton(true);
            playheadHandler.post(playheadUpdater);
            return;
        }

        hideImagePreview();
        updatePreviewTransforms(clip);

        // Queue the entire video track. ExoPlayer then plays clip → clip without a
        // setMediaItem()+prepare() stall, which was the visible pause at every cut.
        // Timelines containing still images cannot be queued (an image has no media
        // item), so those fall back to the single-clip path below.
        java.util.List<Clip> clips = project.getTimeline().getClips();
        boolean queued = playerManager.loadTimelineForPlayback(
                clips, index, localSourceMs - clip.getInPointMs());
        if (queued) {
            playerManager.setVolume(clip.isAudioMuted() ? 0f : clip.getVolumeLevel());
            playerManager.setPlaybackSpeed(clip.getSpeedMultiplier());
            // Guard the first frames: until the seek has actually landed the player
            // still reports its old position, and using it yanked the playhead to the
            // clip's start on short clips.
            armPlaybackSeekGuard(localSourceMs - clip.getInPointMs());
            updatePlayPauseButton(true);
            syncAndPlayAudioPlayer();
            playheadHandler.post(playheadUpdater);
            FLog.d(TAG, "play: playlist queued at index=" + index
                    + " offset=" + (localSourceMs - clip.getInPointMs()) + "ms");
            return;
        }

        FLog.d(TAG, "play: playlist unavailable (image clip or empty) — single-clip mode");
        if (playerManager.getLoadedClip() != clip) {
            // Different source (or a different trim state): load it before seeking.
            playerManager.loadClip(clip);
        }
        playerManager.setVolume(clip.isAudioMuted() ? 0f : clip.getVolumeLevel());
        playerManager.setPlaybackSpeed(clip.getSpeedMultiplier());

        playerManager.seekTo(localSourceMs - clip.getInPointMs());
        armPlaybackSeekGuard(localSourceMs - clip.getInPointMs());
        playerManager.play();
        updatePlayPauseButton(true);
        syncAndPlayAudioPlayer();
        playheadHandler.post(playheadUpdater);
    }

    /**
     * After a seek the player keeps reporting its previous position for a frame or two.
     * Ignoring the playhead for that window stops it snapping to the clip start.
     */
    private void armPlaybackSeekGuard(long expectedRelativeMs) {
        playbackSeekTargetMs = Math.max(0, expectedRelativeMs);
        playbackSeekDeadlineMs = android.os.SystemClock.elapsedRealtime() + 600L;
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
    }

    private void initTimeline() {
        // Open with no clip highlighted, like any professional editor: the tools come
        // alive on the first tap. The player still shows clip 0 at the playhead.
        editorTimeline.setTimeline(project.getTimeline(), selectedClipIndex);

        // If audio clips exist (e.g. from a saved project), load them
        if (project.getTimeline().hasAudioClips()) {
            editorTimeline.setAudioClips(project.getTimeline().getAudioClips());
            prepareAudioPlayer();
            updateAudioToolUI();
        }

        FLog.d(TAG, "Timeline initialized with " + project.getTimeline().getClipCount()
                + " clip(s), selectedIndex=" + selectedClipIndex);
    }

    private void initToolbar() {
        // Play/Pause button
        btnPlayPause.setOnClickListener(v -> {
            // Image clips play on their own timer, not on ExoPlayer, so they must be
            // part of the "is something playing" test — otherwise pause did nothing
            // while a still image was previewing.
            if (audioTailActive || imagePlaybackActive || playerManager.isPlayingNow()) {
                audioTailActive = false;
                playerManager.pause();
                if (imagePlaybackActive) stopImagePlayback();
                pauseAudioPlayer();
                playbackFinished = true;
                updatePlayPauseButton(false);
                FLog.d(TAG, "pause requested (image=" + imagePlaybackActive + ")");
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
            playerView.post(() -> {
                updatePreviewTransforms();
                applyCanvasPreview(project.getCanvasPreset());
            });
            return;
        }
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
        if (clip == null) return;
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
        if (clip == null) return;
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
        updatePreviewTransforms();
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
            updatePreviewTransforms();
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
            clip.setCropPreset(preCropPreset);
            clip.setCustomCropBounds(preCropLeft, preCropTop, preCropRight, preCropBottom);
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

        // Compute the video content rect inside the player view
        // PlayerView with resize_mode="fit" centres the video
        playerView.post(() -> {
            android.graphics.RectF videoRect = computeVideoContentRect();
            FLog.d(TAG, "activateFreeCrop: videoRect=" + videoRect
                    + ", cropOverlay size=" + cropOverlay.getWidth() + "x" + cropOverlay.getHeight());
            cropOverlay.setVideoContentRect(videoRect);

            Clip clip = getSelectedClip();
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

        FLog.d(TAG, "computeVideoContentRect: playerView size=" + viewW + "x" + viewH
                + ", offset=(" + offsetX + "," + offsetY + ")");

        // Try to get actual video dimensions
        if (playerManager != null && playerManager.getPlayer() != null) {
            androidx.media3.common.VideoSize videoSize =
                    playerManager.getPlayer().getVideoSize();
            int videoW = videoSize.width;
            int videoH = videoSize.height;

            FLog.d(TAG, "computeVideoContentRect: videoSize=" + videoW + "x" + videoH);

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
                FLog.d(TAG, "computeVideoContentRect: result=" + result);
                return result;
            }
        }

        // Fallback: assume full view
        FLog.d(TAG, "computeVideoContentRect: FALLBACK to full view");
        return new android.graphics.RectF(offsetX, offsetY, offsetX + viewW, offsetY + viewH);
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
                    refreshTotalTimeDisplay();

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
                        FLog.i(TAG, "Auto-muted video clip after audio extraction: "
                                + "audioMuted=" + srcClip.isAudioMuted()
                                + ", volumeLevel=" + srcClip.getVolumeLevel());
                    } else {
                        FLog.w(TAG, "Could not auto-mute: getSelectedClip() returned null");
                    }

                    updateAudioToolUI();
                    prepareAudioPlayer();
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
                    FLog.d(TAG, "AudioPlayer[" + idx + "] prepared, duration=" + p.getDuration() + "ms");
                });
                mp.setOnErrorListener((p, what, extra) -> {
                    FLog.e(TAG, "AudioPlayer[" + idx + "] error: what=" + what + " extra=" + extra);
                    if (idx < audioPlayersReady.size()) {
                        audioPlayersReady.set(idx, false);
                    }
                    return true;
                });
                mp.prepareAsync();
            } catch (Exception e) {
                FLog.e(TAG, "Failed to prepare audio player[" + i + "]", e);
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
                    // Guard against seeking past the audio file's actual duration
                    int mediaDuration = mp.getDuration();
                    if (mediaDuration > 0 && seekPos >= mediaDuration) {
                        FLog.w(TAG, "AudioPlayer[" + i + "] seekPos=" + seekPos
                                + " exceeds mediaDuration=" + mediaDuration + ", clamping");
                        seekPos = Math.max(0, mediaDuration - 100); // seek near end
                    }
                    mp.seekTo((int) seekPos);
                    float vol = ac.isMuted() ? 0f : ac.getVolumeLevel();
                    mp.setVolume(vol, vol);
                    mp.start();
                } else {
                    if (mp.isPlaying()) mp.pause();
                }
            } catch (Exception e) {
                FLog.e(TAG, "AudioPlayer[" + i + "] sync error", e);
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
                        int mediaDuration = mp.getDuration();
                        if (mediaDuration > 0 && seekPos >= mediaDuration) {
                            FLog.w(TAG, "AudioSync[" + i + "] seekPos=" + seekPos
                                    + " exceeds mediaDuration=" + mediaDuration + ", clamping");
                            seekPos = Math.max(0, mediaDuration - 100);
                        }
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
                FLog.e(TAG, "Audio sync[" + i + "] error", e);
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
        updatePreviewTransforms(getSelectedClip());
    }

    /**
     * Applies rotation/flip/crop for a specific clip. Playback passes the clip the
     * player is showing, which is not necessarily the selected one.
     */
    private void updatePreviewTransforms(@Nullable Clip clip) {
        if (project == null || project.getTimeline().isEmpty()) return;
        if (clip == null) return;

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

    /**
     * Correct the project's source duration using ExoPlayer's actual reported duration.
     * Both MMR and FFprobeKit can disagree with ExoPlayer for edge-case files;
     * if ExoPlayer reports significantly different, prefer ExoPlayer's value.
     */
    private void correctDurationFromPlayer() {
        long playerDurationMs = playerManager.getSourceDuration();
        if (playerDurationMs <= 0) return;

        Clip clip = getSelectedClip();
        if (clip == null) return;
        // Image clips have a fixed duration; no correction needed
        if (clip.isImageClip()) return;

        long storedDuration = clip.getSourceDurationMs();

        // Only correct if ExoPlayer reports SHORTER duration.
        // For fragmented MP4, ExoPlayer may report the container duration
        // (e.g. 60 000 ms) while FFprobe reports the actual content duration
        // (e.g. 4 096 ms). We trust FFprobe for "longer" values.
        if (playerDurationMs < storedDuration && (storedDuration - playerDurationMs) > 500) {
            FLog.w(TAG, "Duration correction (shorter): stored=" + storedDuration
                    + "ms → ExoPlayer=" + playerDurationMs + "ms");

            clip.setSourceDurationMs(playerDurationMs);
            clip.setInPointMs(0);
            clip.setOutPointMs(playerDurationMs);

            // Refresh UI with corrected duration — but DO NOT reset playhead or player position.
            // Resetting here caused 'plays from start' because this fires right when the user
            // presses play (the first STATE_READY after load), overriding the user's seeked position.
            refreshTotalTimeDisplay();
            editorTimeline.setTrimFromClip(clip);
            // Only update player's internal trim bounds (no seek, no playWhenReady change)
            playerManager.updateTrimEndOnly(playerDurationMs);
        } else {
            FLog.d(TAG, "Duration OK: stored=" + storedDuration
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
        // Sum the clips before the one the *player* is showing. Using the selection
        // here reported the time of whichever clip was highlighted, so the read-out
        // restarted at each clip instead of tracking the whole timeline.
        long absoluteMs = 0;
        for (int i = 0; i < playbackClipIndex && i < tl.getClipCount(); i++) {
            absoluteMs += tl.getClip(i).getTrimmedDurationMs();
        }
        absoluteMs += positionInCurrentSegmentMs;
        return absoluteMs;
    }

    /**
     * Update the current time display with the absolute position in the project.
     *
     * @param positionInCurrentSegmentMs 0-based position within the clip the player is
     *                                   showing; ignored when the timeline already
     *                                   knows the absolute playhead
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

    /** Applies a clip's audio and visual state while it is the one being previewed. */
    private void applyPlayingClipState(@NonNull Clip clip) {
        // Only touch the renderers when the value actually changes. Re-applying the
        // same speed at every media-item transition makes ExoPlayer reconfigure the
        // audio/video pipeline right at the cut, which is audible as a hitch.
        if (Math.abs(clip.getSpeedMultiplier() - appliedPlaybackSpeed) > 0.001f) {
            appliedPlaybackSpeed = clip.getSpeedMultiplier();
            playerManager.setPlaybackSpeed(clip.getSpeedMultiplier());
        }
        float wantedVolume = clip.isAudioMuted() ? 0f : clip.getVolumeLevel();
        if (Math.abs(wantedVolume - appliedPlaybackVolume) > 0.001f) {
            appliedPlaybackVolume = wantedVolume;
            playerManager.setVolume(wantedVolume);
        }
        updatePreviewTransforms(clip);
    }

    /** Last values pushed to the player, so transitions do not reconfigure needlessly. */
    private float appliedPlaybackSpeed = 1f;
    private float appliedPlaybackVolume = -1f;

    private void updatePlayheadPosition() {
        if (playerManager == null || project == null || project.getTimeline().isEmpty()) return;
        // Never let a stopped player drive the playhead. Without this the poll kept
        // reading the player's position after the user had dragged elsewhere, so the
        // playhead snapped back to wherever the queue had advanced to.
        if (playbackFinished) return;
        if (!audioTailActive && !imagePlaybackActive && !playerManager.isPlayingNow()) return;

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
                FLog.d(TAG, "Audio-tail ended at " + playheadMs + "ms");
            }

            editorTimeline.setPlayheadPositionMs(playheadMs);
            timeCurrent.setText(TimeFormatter.formatAuto(playheadMs));
            return;
        }

        // ── Gapless playlist playback ───────────────────────────────
        // The queue advances by itself, so nothing here may "advance a segment".
        // The playhead is derived from the item the player is on plus its position.
        if (playerManager.isPlaylistMode()) {
            if (userDragging) return;
            int index = playerManager.getCurrentPlaylistIndex();
            Clip playing = playerManager.getCurrentPlaylistClip();
            if (index < 0 || playing == null) return;

            if (index != playbackClipIndex) {
                playbackClipIndex = index;
                editorTimeline.setPlaybackIndex(index);
                applyPlayingClipState(playing);
                FLog.d(TAG, "playlist: now on clip " + index);
            }

            long positionSourceMs = playerManager.getCurrentPosition();
            if (playbackSeekTargetMs >= 0) {
                boolean landed = Math.abs(positionSourceMs - playbackSeekTargetMs) <= 250;
                boolean expired = android.os.SystemClock.elapsedRealtime()
                        > playbackSeekDeadlineMs;
                if (!landed && !expired) {
                    // The seek has not landed yet; keeping the UI still beats showing
                    // the clip's start and then jumping.
                    return;
                }
                playbackSeekTargetMs = -1;
            }
            long localEffectiveMs =
                    (long) (positionSourceMs / Math.max(0.1f, playing.getSpeedMultiplier()));
            long segmentStartMs = editorTimeline.getSegmentStartTimeMs(index);
            long playheadMs = segmentStartMs + Math.max(0, localEffectiveMs);

            editorTimeline.setPlayheadPositionMs(playheadMs);
            timeCurrent.setText(TimeFormatter.formatAuto(playheadMs));
            lastUserPlayheadFraction = (float) (playing.getInPointMs() + positionSourceMs)
                    / Math.max(1, playing.getSourceDurationMs());
            return;
        }

        // The playback loop is about the clip under the playhead, not the selection.
        Clip clip = getPlaybackClip();
        if (clip == null) return;
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
                int nextIndex = playbackClipIndex + 1;
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
                        FLog.d(TAG, "Image: entering audio-tail");
                    } else {
                        // Last segment — set playhead to end
                        float endFraction = (float) clip.getOutPointMs() / sourceDuration;
                        lastUserPlayheadFraction = endFraction;
                        editorTimeline.setPlayheadFraction(endFraction);
                        long totalMs = timeline.getTotalDurationMs();
                        timeCurrent.setText(TimeFormatter.formatAuto(totalMs));
                        FLog.d(TAG, "Image playback stopped at last segment end");
                    }
                }
                return;
            }

            // Convert position to source fraction for timeline view
            float fraction = (float) (clip.getInPointMs() + positionMs) / sourceDuration;
            fraction = Math.max(0f, Math.min(fraction, 1f));
            lastUserPlayheadFraction = fraction;
            editorTimeline.setPlayheadFraction(playbackClipIndex, fraction);
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
                int nextIndex = playbackClipIndex + 1;
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
                        FLog.d(TAG, "Entering audio-tail: videoEnd=" + audioTailStartMs
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
                        FLog.d(TAG, "Playback stopped at last segment end");
                    }
                }
                return;
            }

            long position = playerManager.getCurrentPosition(); // 0-based within trim
            if (playbackSeekTargetMs >= 0) {
                boolean landed = Math.abs(position - playbackSeekTargetMs) <= 250;
                boolean expired = android.os.SystemClock.elapsedRealtime()
                        > playbackSeekDeadlineMs;
                if (!landed && !expired) {
                    return;   // seek still in flight; do not move the playhead yet
                }
                playbackSeekTargetMs = -1;
            }
            long absoluteMs = clip.getInPointMs() + position;
            float fraction = (float) absoluteMs / sourceDuration;
            fraction = Math.max(0f, Math.min(fraction, 1f));

            lastUserPlayheadFraction = fraction;
            editorTimeline.setPlayheadFraction(playbackClipIndex, fraction);
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
        FLog.d(TAG, "Auto-advancing to segment " + nextIndex);

        // Deactivate crop overlay when switching segments
        if (inCropMode) {
            exitCropMode(false);
        } else if (cropOverlay != null && cropOverlay.isActive()) {
            cropOverlay.deactivate();
        }

        Timeline timeline = project.getTimeline();
        playbackClipIndex = nextIndex;
        editorTimeline.setPlaybackIndex(nextIndex);
        Clip nextClip = timeline.getClip(nextIndex);
        if (nextClip == null) return;

        // Restore the trim handles for whichever clip the user has selected; playback
        // moving on must not clear or move that selection.
        editorTimeline.setSelectedIndex(selectedClipIndex);
        if (selectedClipIndex >= 0 && selectedClipIndex < timeline.getClipCount()) {
            editorTimeline.setTrimFromClip(timeline.getClip(selectedClipIndex));
        }

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
        editorTimeline.setPlayheadFraction(nextIndex, startFraction);
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
                    helperText,
                    false);   // exporting is not destructive: keep the row plain

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
            FLog.e(TAG, "Failed to show export confirmation, starting directly", e);
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
            FLog.e(TAG, "Failed to show close confirmation sheet, using dialog fallback", e);
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

            FLog.d(TAG, "Project saved: " + project.getId());
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
                    FLog.d(TAG, "Duration from FFprobe: " + durationMs + "ms");
                    return durationMs;
                }
            }
        } catch (Exception e) {
            FLog.e(TAG, "FFprobe duration failed", e);
        }

        // ── MediaMetadataRetriever fallback ──────────────────────
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
                FLog.d(TAG, "Duration from MMR fallback: " + ms + "ms");
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

            Clip videoClip = new Clip(videoUri, durationMs);

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
                    + " duration=" + durationMs + "ms uri=" + videoUri);
        } catch (Exception e) {
            FLog.e(TAG, "Failed to add video asset", e);
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

            // Derive the split point from the VISUAL timeline playhead (authoritative)
            // rather than playerManager.getCurrentPosition(), which may not have settled
            // yet if a seek was still in-flight when the user tapped the split button.
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

            // Update the player's trim end to match clip A's new out-point.
            // Use updateTrimEndOnly so we DON'T trigger an unwanted seekTo(0).
            // Then park the player 1 ms before the trim end (avoids the edge case where
            // pos == trimEndMs would let play() think we're out of bounds).
            playerManager.updateTrimEndOnly(clipA.getOutPointMs());
            long trimmedMs = clipA.getOutPointMs() - clipA.getInPointMs();
            if (trimmedMs > 1) {
                playerManager.seekTo(trimmedMs - 1);
            }

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
            playbackClipIndex = Math.min(playbackClipIndex, timeline.getClipCount() - 1);
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
        refreshTotalTimeDisplay();
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
