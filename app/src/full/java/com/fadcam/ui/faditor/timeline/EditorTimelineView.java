package com.fadcam.ui.faditor.timeline;

import com.fadcam.ui.faditor.util.FaditorPalette;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.LongSparseArray;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.OverScroller;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.Timeline;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Advanced NLE timeline: fixed center playhead, pinch-zoom, frame thumbnails.
 * Timeline scrolls horizontally while playhead remains centered.
 */
public class EditorTimelineView extends View {

    private static final String TAG = "EditorTimelineView";
    /**
     * Per-frame and per-touch tracing. Off: it produced thousands of lines per second,
     * which buries real problems and costs frames. One-shot lifecycle events still log.
     */

    // ── Scale & layout constants (dp) ────────────────────────────────
    private static final float BASE_DP_PER_SECOND = 50f;
    private static final float MIN_ZOOM = 0.5f;
    private static final float MAX_ZOOM = 8f;
    private static final float MIN_SEGMENT_DP = 80f;
    private static final float EDGE_PADDING_DP = 0f;

    private static final float RULER_HEIGHT_DP = 22f;
    private static final float TRACK_HEIGHT_DP = 56f;
    private static final float SEGMENT_GAP_DP = 4f;
    private static final float SEGMENT_CORNER_DP = 6f;

    private static final float HANDLE_WIDTH_DP = 14f;
    private static final float HANDLE_OVERHANG_DP = 4f;
    private static final float HANDLE_NOTCH_WIDTH_DP = 3f;
    private static final float HANDLE_NOTCH_HEIGHT_DP = 18f;

    private static final float PLAYHEAD_WIDTH_DP = 2.5f;
    private static final float PLAYHEAD_CIRCLE_DP = 6f;
    private static final float BORDER_WIDTH_DP = 2f;

    private static final float LABEL_SIZE_DP = 10f;
    private static final float RULER_TEXT_SIZE_DP = 9f;
    private static final float RULER_TICK_HEIGHT_DP = 5f;
    private static final float TOUCH_SLOP_DP = 22f;

    // ── Audio track layout constants (dp) ─────────────────────────────
    private static final float AUDIO_TRACK_HEIGHT_DP = 40f;
    private static final float AUDIO_TRACK_GAP_DP    = 6f;
    private static final float AUDIO_CORNER_DP       = 6f;
    private static final float AUDIO_WAVEFORM_BAR_GAP_DP = 0.5f;

    // ── Colors ───────────────────────────────────────────────────────
    private static final int COLOR_RULER_BG      = 0xFF141414;
    private static final int COLOR_RULER_TEXT     = 0xFF777777;
    private static final int COLOR_RULER_TICK     = 0xFF444444;
    private static final int COLOR_TRACK_BG       = 0xFF1A1A1A;
    private static final int COLOR_SEGMENT        = 0xFF2D2D2D;
    private static final int COLOR_SEGMENT_SEL    = FaditorPalette.SELECTED_FILL;
    private static final int COLOR_BORDER_SEL     = FaditorPalette.ACCENT;
    private static final int COLOR_HANDLE         = FaditorPalette.ACCENT;
    private static final int COLOR_HANDLE_NOTCH   = FaditorPalette.HANDLE_NOTCH;
    private static final int COLOR_PLAYHEAD       = 0xFFFFFFFF;
    private static final int COLOR_LABEL          = 0xBBFFFFFF;
    private static final int COLOR_DRAG_GHOST     = FaditorPalette.ACCENT_GHOST;
    private static final int COLOR_AUDIO_BG       = 0xFF1E1A12; // warm charcoal (gold family)
    private static final int COLOR_AUDIO_BG_SEL   = FaditorPalette.SELECTED_FILL;
    private static final int COLOR_AUDIO_WAVE     = FaditorPalette.ACCENT;
    private static final int COLOR_AUDIO_WAVE_MUTED = 0xFF555555;
    private static final int COLOR_AUDIO_WAVE_DIM = FaditorPalette.ACCENT_FAINT; // Faded version for mirror half
    private static final int COLOR_AUDIO_CENTERLINE = FaditorPalette.ACCENT_SOFT;
    private static final int COLOR_AUDIO_LABEL    = 0xBBFFFFFF;
    private static final int COLOR_AUDIO_TRACK_BG = 0xFF151515;

    // ── Paints ───────────────────────────────────────────────────────
    private final Paint rulerBgPaint       = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rulerTextPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rulerTickPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackBgPaint       = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint segmentPaint       = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint        = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint        = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleNotchPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint         = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint speedLabelPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reorderLabelPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reorderThumbScrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dragGhostPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trimOverlayPaint    = new Paint();
    private final Paint trimRecoverPaint    = new Paint();

    // Audio track paints
    private final Paint audioTrackBgPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint audioClipPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint audioWavePaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint audioWaveMirrorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint audioCenterlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint audioLabelPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint audioBorderPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── Pixel dimensions ─────────────────────────────────────────────
    private float density;
    private float dpPerSecondPx, minSegmentPx, edgePaddingPx;
    private float rulerHeightPx, trackHeightPx, segmentGapPx, segmentCornerPx;
    private float handleWidthPx, handleOverhangPx, handleNotchWidthPx, handleNotchHeightPx;
    private float playheadWidthPx, playheadCirclePx, borderWidthPx;
    private float touchSlopPx, rulerTickHeightPx;
    private float audioTrackHeightPx, audioTrackGapPx, audioCornerPx, audioWaveBarGapPx;

    // ── State ────────────────────────────────────────────────────────
    private final List<SegmentData> segments = new ArrayList<>();
    private final List<RectF> segRects = new ArrayList<>();
    private int selectedIndex = -1;  // UI selection (-1 = none selected)
    private long playheadPositionMs = 0; // Absolute playhead position in timeline
    private long totalEffectiveMs = 0;
    private float contentWidthPx = 0;
    
    // Timeline zoom and scroll state
    private float zoomLevel = 1f;
    /**
     * Extra scale applied when the whole project is narrower than the view, so a few
     * seconds of video still use the full width instead of huddling around the centre
     * line. Disabled as soon as the user pinches, because then the zoom is theirs.
     */
    private float fitScale = 1f;
    private boolean userZoomed;
    private float scrollOffsetPx = 0f;
    
    // Thumbnails cache (key: content-based cacheKey, value: list of thumbnails)
    private final Map<String, List<Bitmap>> thumbnailsCache = new HashMap<>();
    private final Set<String> thumbnailsLoading = new HashSet<>();
    private static final int MAX_THUMBNAILS_PER_SEGMENT = 30;
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Ruler strings are reused every frame; formatting them per draw would allocate. */
    private final LongSparseArray<String> timeLabelCache = new LongSparseArray<>();
    private final RectF drawTmpRect = new RectF();
    private final RectF drawTmpDest = new RectF();
    private final Path clipPath = new Path();

    // ── Audio track state ─────────────────────────────────────────────
    private final List<AudioClip> audioClips = new ArrayList<>();
    private final List<RectF> audioClipRects = new ArrayList<>();
    private int selectedAudioIndex = -1;
    private boolean isDraggingAudio = false;
    private int dragAudioIndex = -1;
    private float dragAudioStartX;
    private long dragAudioStartOffsetMs;
    private boolean audioLongPressTriggered = false;
    private int pendingAudioIndex = -1;       // Index of audio clip awaiting long-press
    private static final long AUDIO_LONG_PRESS_MS = 1000;  // Long hold to avoid accidental drags
    // Audio trim drag state
    private float audioTrimDragX;
    private long audioTrimDragInMs;
    private long audioTrimDragOutMs;

    // ── Touch ────────────────────────────────────────────────────────
    private boolean isScaling = false;
    
    private enum Drag { NONE, LEFT_HANDLE, RIGHT_HANDLE, AUDIO_LEFT_HANDLE, AUDIO_RIGHT_HANDLE }
    private Drag activeDrag = Drag.NONE;
    private float downX, downY;
    /** Set when playback starts mid-gesture; the rest of that gesture must not seek. */
    private boolean gestureCancelled;
    private long downTime;
    private int downSegIndex = -1;
    private boolean longPressTriggered;
    private long dragStartInMs, dragStartOutMs;
    private float dragStartSegLeft, dragStartSegRight;
    private float trimDragX;                  // Timeline-space x of handle during trim drag
    private float trimDragStartFrac;          // Start fraction computed during trim drag
    /**
     * Finger position when the handle was grabbed. Trim deltas are measured from here,
     * not from the clip edge: pressing anywhere inside the handle's hit area used to
     * snap the edge straight to the finger, so a tap shrank the clip by the width of
     * the handle. Pro editors only move once you actually drag.
     */
    private float trimDragOriginX;
    /** True once the finger has moved far enough to count as a drag, not a tap. */
    private boolean trimDragArmed;
    private float trimDragEndFrac;            // End fraction computed during trim drag
    private float lastTrimFingerScreenX;      // Last finger screen X during trim drag (for edge scroll)
    private static final long LONG_PRESS_MS = 400;

    // ── Edge auto-scroll during trim drag ─────────────────────────────
    private static final float EDGE_SCROLL_ZONE_DP = 60f;
    private static final long EDGE_SCROLL_INTERVAL_MS = 16;  // ~60fps
    private static final float EDGE_SCROLL_MAX_SPEED_DP = 12f; // max dp per tick
    private float edgeScrollZonePx;
    private float edgeScrollMaxSpeedPx;
    private final Handler edgeScrollHandler = new Handler(Looper.getMainLooper());
    private boolean isEdgeScrolling = false;

    // ── Reorder mode ────────────────────────────────────────────────
    private boolean isReorderMode = false;
    private final List<Integer> reorderOrder = new ArrayList<>();
    private int reorderDragIdx = -1;           // index within reorderOrder being dragged
    private float reorderDragCenterX;          // drag center X in view coords
    private float reorderDragCenterY;          // drag center Y in view coords
    private float reorderBlockWidth;           // computed tile width
    private float reorderBlockHeight;          // computed tile height
    private int reorderPendingIdx = -1;        // tile awaiting long-press
    private float reorderDownX, reorderDownY;  // finger down position in reorder mode
    private static final long REORDER_LONG_PRESS_MS = 350L;
    private float reorderRowStartX;            // X of first block
    private float reorderRowCenterY;           // Y center of block row
    private float reorderGapPx;               // gap between blocks
    private static final float REORDER_BAR_HEIGHT_DP = 36f;
    private static final float REORDER_BTN_PADDING_DP = 14f;
    private static final float REORDER_BLOCK_CORNER_DP = 8f;
    private float reorderBarHeightPx;
    private float reorderBtnPaddingPx;
    private float reorderBlockCornerPx;
    private final RectF reorderCancelRect = new RectF();
    private final RectF reorderDoneRect = new RectF();
    private final Paint reorderBgPaint2 = new Paint();
    private final Paint reorderBlockPaint2 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reorderBlockDragPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reorderBlockSelectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reorderNumPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reorderBtnTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reorderBarPaint = new Paint();
    private final Paint reorderDropIndicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── Long press detection via Handler ─────────────────────────────
    private final Handler longPressHandler = new Handler(Looper.getMainLooper());
    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (activeDrag == Drag.NONE && downSegIndex >= 0 && segments.size() > 1) {
                longPressTriggered = true;
                if (com.fadcam.Utils.hapticsAllowedForUi(EditorTimelineView.this.getContext())) {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                }
                enterReorderMode();
            }
        }
    };
    private final Runnable audioLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (pendingAudioIndex >= 0 && pendingAudioIndex < audioClips.size()
                    && !isDraggingAudio && activeDrag == Drag.NONE) {
                audioLongPressTriggered = true;
                isDraggingAudio = true;
                dragAudioIndex = pendingAudioIndex;
                float scrolledX = downX + scrollOffsetPx;
                dragAudioStartX = scrolledX;
                dragAudioStartOffsetMs = audioClips.get(dragAudioIndex).getOffsetMs();
                if (com.fadcam.Utils.hapticsAllowedForUi(EditorTimelineView.this.getContext())) {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                }
                getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
            }
        }
    };
    private final Runnable reorderLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (reorderPendingIdx < 0 || reorderPendingIdx >= reorderOrder.size()) return;
            reorderDragIdx = reorderPendingIdx;
            reorderDragCenterX = reorderDownX;
            reorderDragCenterY = reorderDownY;
            reorderPendingIdx = -1;
            if (com.fadcam.Utils.hapticsAllowedForUi(EditorTimelineView.this.getContext())) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
            invalidate();
        }
    };
    private final Runnable edgeScrollRunnable = new Runnable() {
        @Override
        public void run() {
            if (activeDrag != Drag.LEFT_HANDLE && activeDrag != Drag.RIGHT_HANDLE) {
                isEdgeScrolling = false;
                return;
            }
            float screenX = lastTrimFingerScreenX;
            int viewWidth = getWidth();
            float scrollDelta = 0;
            if (screenX < edgeScrollZonePx) {
                // Near left edge → scroll left (decrease scrollOffsetPx)
                float depth = 1f - (screenX / edgeScrollZonePx);
                scrollDelta = -edgeScrollMaxSpeedPx * depth;
            } else if (screenX > viewWidth - edgeScrollZonePx) {
                // Near right edge → scroll right (increase scrollOffsetPx)
                float depth = 1f - ((viewWidth - screenX) / edgeScrollZonePx);
                scrollDelta = edgeScrollMaxSpeedPx * depth;
            } else {
                isEdgeScrolling = false;
                return;
            }
            scrollOffsetPx += scrollDelta;
            clampScroll();
            // Recompute trim with updated scroll (same screen X, new timeline position)
            float scrolledX = screenX + scrollOffsetPx;
            doTrimDrag(scrolledX);
            edgeScrollHandler.postDelayed(this, EDGE_SCROLL_INTERVAL_MS);
        }
    };
    
    // Gesture detectors for zoom and scroll
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private OverScroller flingScroller;
    private boolean flingJustFinished = false;  // Tracks when fling ends so we can reset userDragging

    @Nullable private OnSegmentActionListener listener;

    // ── Segment data holder ──────────────────────────────────────────
    private static class SegmentData {
        final int index;
        final long sourceDurationMs;
        final Uri sourceUri;
        final boolean isImageClip;
        final String cacheKey;
        long inPointMs;
        long outPointMs;
        long trimmedMs;
        float speed;
        long effectiveMs;
        final String durationLabel;
        /** Non-null only when the clip is not at normal speed. */
        @Nullable
        final String speedLabel;

        SegmentData(int i, @NonNull Clip clip) {
            this.index = i;
            this.sourceDurationMs = clip.getSourceDurationMs();
            this.sourceUri = clip.getSourceUri();
            this.isImageClip = clip.isImageClip();
            this.inPointMs = clip.getInPointMs();
            this.outPointMs = clip.getOutPointMs();
            this.trimmedMs = outPointMs - inPointMs;
            this.speed = clip.getSpeedMultiplier();
            this.effectiveMs = Math.max(1, (long)(trimmedMs / speed));
            this.durationLabel = formatDurationCompact(effectiveMs);
            this.speedLabel = Math.abs(speed - 1f) < 0.01f
                    ? null : formatSpeedLabel(speed);
            // Content-based key: same media + same trim = same thumbnails
            this.cacheKey = sourceUri.hashCode() + "_" + inPointMs + "_" + outPointMs;
        }
    }

    private static String formatSpeedLabel(float speed) {
        if (speed == (int) speed) return (int) speed + "x";
        return String.format(Locale.US, "%.2gx", speed);
    }

    // ── Listener interface ───────────────────────────────────────────
    public interface OnSegmentActionListener {
        void onSegmentSelected(int index);
        void onTrimChanged(int segmentIndex, float startFraction, float endFraction, boolean isLeft);
        void onTrimFinished(int segmentIndex, float startFraction, float endFraction);
        /** Called when playhead is seeked. isDragging=true means user is actively dragging,
         *  so don't load new clips yet; isDragging=false means this is a discrete seek or drag end. */
        void onPlayheadSeeked(int segmentIndex, float fractionInSegment, boolean isDragging);
        void onPlayheadDragFinished();
        void onSegmentReordered(int fromIndex, int toIndex);
        void onReorderModeChanged(boolean entering);
        void onAudioClipSelected(int audioIndex);
        void onAudioClipMoved(int audioIndex, long oldOffsetMs, long newOffsetMs);
        void onAudioTrimChanged(int audioIndex, long inPointMs, long outPointMs, boolean isLeft);
        void onAudioTrimFinished(int audioIndex, long inPointMs, long outPointMs);
    }

    // ── Constructors ─────────────────────────────────────────────────
    public EditorTimelineView(Context c) { super(c); init(); }
    public EditorTimelineView(Context c, @Nullable AttributeSet a) { super(c, a); init(); }
    public EditorTimelineView(Context c, @Nullable AttributeSet a, int d) { super(c, a, d); init(); }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        updateDpPerSecond();
        minSegmentPx = MIN_SEGMENT_DP * density;
        edgePaddingPx = EDGE_PADDING_DP * density;
        rulerHeightPx = RULER_HEIGHT_DP * density;
        trackHeightPx = TRACK_HEIGHT_DP * density;
        segmentGapPx = SEGMENT_GAP_DP * density;
        segmentCornerPx = SEGMENT_CORNER_DP * density;
        handleWidthPx = HANDLE_WIDTH_DP * density;
        handleOverhangPx = HANDLE_OVERHANG_DP * density;
        handleNotchWidthPx = HANDLE_NOTCH_WIDTH_DP * density;
        handleNotchHeightPx = HANDLE_NOTCH_HEIGHT_DP * density;
        playheadWidthPx = PLAYHEAD_WIDTH_DP * density;
        playheadCirclePx = PLAYHEAD_CIRCLE_DP * density;
        borderWidthPx = BORDER_WIDTH_DP * density;
        touchSlopPx = TOUCH_SLOP_DP * density;
        rulerTickHeightPx = RULER_TICK_HEIGHT_DP * density;
        audioTrackHeightPx = AUDIO_TRACK_HEIGHT_DP * density;
        audioTrackGapPx = AUDIO_TRACK_GAP_DP * density;
        audioCornerPx = AUDIO_CORNER_DP * density;
        audioWaveBarGapPx = AUDIO_WAVEFORM_BAR_GAP_DP * density;

        rulerBgPaint.setColor(COLOR_RULER_BG);
        rulerBgPaint.setStyle(Paint.Style.FILL);
        rulerTextPaint.setColor(COLOR_RULER_TEXT);
        rulerTextPaint.setTextSize(RULER_TEXT_SIZE_DP * density);
        rulerTextPaint.setTextAlign(Paint.Align.CENTER);
        rulerTextPaint.setTypeface(Typeface.MONOSPACE);
        rulerTickPaint.setColor(COLOR_RULER_TICK);
        rulerTickPaint.setStrokeWidth(1f * density);
        trackBgPaint.setColor(COLOR_TRACK_BG);
        trackBgPaint.setStyle(Paint.Style.FILL);
        segmentPaint.setStyle(Paint.Style.FILL);
        borderPaint.setColor(COLOR_BORDER_SEL);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(borderWidthPx);
        handlePaint.setColor(COLOR_HANDLE);
        handlePaint.setStyle(Paint.Style.FILL);
        handleNotchPaint.setColor(COLOR_HANDLE_NOTCH);
        handleNotchPaint.setStyle(Paint.Style.FILL);
        playheadPaint.setColor(COLOR_PLAYHEAD);
        playheadPaint.setStyle(Paint.Style.FILL);
        playheadCirclePaint.setColor(COLOR_PLAYHEAD);
        playheadCirclePaint.setStyle(Paint.Style.FILL);
        labelPaint.setColor(COLOR_LABEL);
        labelPaint.setTextSize(LABEL_SIZE_DP * density);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        speedLabelPaint.setColor(FaditorPalette.ACCENT);
        speedLabelPaint.setTextSize(LABEL_SIZE_DP * density);
        speedLabelPaint.setTextAlign(Paint.Align.CENTER);
        speedLabelPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        reorderLabelPaint.setColor(0xEEFFFFFF);
        reorderLabelPaint.setTextSize(10f * density);
        reorderLabelPaint.setTextAlign(Paint.Align.CENTER);
        reorderLabelPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        reorderThumbScrimPaint.setColor(0x66000000);
        reorderThumbScrimPaint.setStyle(Paint.Style.FILL);
        dragGhostPaint.setColor(COLOR_DRAG_GHOST);
        dragGhostPaint.setStyle(Paint.Style.FILL);
        trimOverlayPaint.setColor(0x80000000);
        trimOverlayPaint.setStyle(Paint.Style.FILL);
        trimRecoverPaint.setColor(FaditorPalette.ACCENT_FAINT);
        trimRecoverPaint.setStyle(Paint.Style.FILL);
        edgeScrollZonePx = EDGE_SCROLL_ZONE_DP * density;
        edgeScrollMaxSpeedPx = EDGE_SCROLL_MAX_SPEED_DP * density;

        // Reorder mode paints
        reorderBarHeightPx = REORDER_BAR_HEIGHT_DP * density;
        reorderBtnPaddingPx = REORDER_BTN_PADDING_DP * density;
        reorderBlockCornerPx = REORDER_BLOCK_CORNER_DP * density;
        reorderBgPaint2.setColor(0xE0111111);
        reorderBgPaint2.setStyle(Paint.Style.FILL);
        reorderBarPaint.setColor(0xFF1A1A1A);
        reorderBarPaint.setStyle(Paint.Style.FILL);
        reorderBlockPaint2.setColor(0xFF2D2D2D);
        reorderBlockPaint2.setStyle(Paint.Style.FILL);
        reorderBlockDragPaint.setColor(0xFF3A3A3A);
        reorderBlockDragPaint.setStyle(Paint.Style.FILL);
        reorderBlockSelectedPaint.setColor(COLOR_BORDER_SEL);
        reorderBlockSelectedPaint.setStyle(Paint.Style.STROKE);
        reorderBlockSelectedPaint.setStrokeWidth(2.5f * density);
        reorderNumPaint.setColor(0xDDFFFFFF);
        reorderNumPaint.setTextSize(14f * density);
        reorderNumPaint.setTextAlign(Paint.Align.CENTER);
        reorderNumPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        reorderBtnTextPaint.setColor(COLOR_HANDLE);
        reorderBtnTextPaint.setTextSize(14f * density);
        reorderBtnTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        reorderDropIndicatorPaint.setColor(COLOR_HANDLE);
        reorderDropIndicatorPaint.setStrokeWidth(3f * density);
        reorderDropIndicatorPaint.setStrokeCap(Paint.Cap.ROUND);

        // Audio track paints
        audioTrackBgPaint.setColor(COLOR_AUDIO_TRACK_BG);
        audioTrackBgPaint.setStyle(Paint.Style.FILL);
        audioClipPaint.setColor(COLOR_AUDIO_BG);
        audioClipPaint.setStyle(Paint.Style.FILL);
        audioWavePaint.setColor(COLOR_AUDIO_WAVE);
        audioWavePaint.setStyle(Paint.Style.FILL);
        audioWavePaint.setStrokeCap(Paint.Cap.ROUND);
        audioWaveMirrorPaint.setColor(COLOR_AUDIO_WAVE_DIM);
        audioWaveMirrorPaint.setStyle(Paint.Style.FILL);
        audioWaveMirrorPaint.setStrokeCap(Paint.Cap.ROUND);
        audioCenterlinePaint.setColor(COLOR_AUDIO_CENTERLINE);
        audioCenterlinePaint.setStyle(Paint.Style.STROKE);
        audioCenterlinePaint.setStrokeWidth(1f * density);
        audioLabelPaint.setColor(COLOR_AUDIO_LABEL);
        audioLabelPaint.setTextSize(9f * density);
        audioLabelPaint.setTextAlign(Paint.Align.LEFT);
        audioBorderPaint.setColor(COLOR_BORDER_SEL);
        audioBorderPaint.setStyle(Paint.Style.STROKE);
        audioBorderPaint.setStrokeWidth(borderWidthPx);
        
        // Initialize gesture detectors
        scaleDetector = new ScaleGestureDetector(getContext(), new ScaleListener());
        gestureDetector = new GestureDetector(getContext(), new GestureListener());
        flingScroller = new OverScroller(getContext());
    }
    
    private void updateDpPerSecond() {
        dpPerSecondPx = BASE_DP_PER_SECOND * zoomLevel * fitScale * density;
    }

    /**
     * Chooses {@link #fitScale} so a project shorter than the view still spans it.
     * Capped so a single very short clip does not turn into a giant thumbnail.
     */
    private void updateFitScale() {
        if (userZoomed || totalEffectiveMs <= 0) {
            fitScale = 1f;
            return;
        }
        int viewport = getWidth() - getPaddingLeft() - getPaddingRight();
        if (viewport <= 0) {
            fitScale = 1f;
            return;
        }
        float natural = (totalEffectiveMs / 1000f) * BASE_DP_PER_SECOND * zoomLevel * density
                + Math.max(0, segments.size() - 1) * segmentGapPx
                + edgePaddingPx * 2;
        // The strip must span the viewport. The old 3x cap was smaller than a wide
        // landscape screen needs (a 3s project at 50dp/s is 150dp against a 655dp
        // viewport), so the content stayed a small block in the middle.
        float target = viewport * 0.98f;
        if (natural <= 0f || natural >= target) {
            fitScale = 1f;
            return;
        }
        fitScale = Math.min(target / natural, 12f);
    }

    // ══════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ══════════════════════════════════════════════════════════════════

    public void setOnSegmentActionListener(@Nullable OnSegmentActionListener l) {
        this.listener = l;
    }

    public void setTimeline(@NonNull Timeline timeline, int selected) {
        segments.clear();
        totalEffectiveMs = 0;
        timeLabelCache.clear();
        Set<String> activeKeys = new HashSet<>();
        for (int i = 0; i < timeline.getClipCount(); i++) {
            SegmentData sd = new SegmentData(i, timeline.getClip(i));
            segments.add(sd);
            totalEffectiveMs += sd.effectiveMs;
            activeKeys.add(sd.cacheKey);
        }
        // Evict cache entries no longer referenced
        Set<String> toEvict = new HashSet<>(thumbnailsCache.keySet());
        toEvict.removeAll(activeKeys);
        for (String key : toEvict) {
            List<Bitmap> old = thumbnailsCache.remove(key);
            if (old != null) {
                for (Bitmap bmp : old) {
                    if (bmp != null && !bmp.isRecycled()) bmp.recycle();
                }
            }
            thumbnailsLoading.remove(key);
        }
        selectedIndex = selected;  // Allow -1 for no selection
        // The project's length changed, so re-evaluate the fit scale.
        updateFitScale();
        updateDpPerSecond();
        computeRects();
        
        // Center timeline on current playhead position
        if (getWidth() > 0) {
            centerPlayhead();
        }
        
        requestLayout();
        invalidate();
    }

    /**
     * Set the audio clips to display on the audio track below the video segments.
     * Call this whenever the timeline's audio clips change.
     *
     * @param clips unmodifiable list from Timeline.getAudioClips()
     */
    public void setAudioClips(@NonNull List<AudioClip> clips) {
        audioClips.clear();
        audioClips.addAll(clips);
        selectedAudioIndex = -1;
        computeRects();
        requestLayout();
        invalidate();
    }

    /**
     * Returns the currently selected audio clip index, or -1 if none.
     */
    public int getSelectedAudioIndex() {
        return selectedAudioIndex;
    }

    /** Restores the highlighted audio clip after a non-structural refresh. */
    public void setSelectedAudioIndex(int index) {
        if (index >= -1 && index < audioClips.size()) {
            selectedAudioIndex = index;
            invalidate();
        }
    }

    public void setSelectedIndex(int index) {
        if (index != selectedIndex && index >= 0 && index < segments.size()) {
            selectedIndex = index;
            invalidate();
        }
    }

    /**
     * Returns the current playhead position in absolute timeline milliseconds.
     */
    public long getPlayheadPositionMs() {
        return playheadPositionMs;
    }

    /**
     * Sets playhead position in absolute timeline ms and redraws.
     * Used by audio-tail mode when playhead advances past video segments.
     */
    public void setPlayheadPositionMs(long positionMs) {
        if (positionMs == playheadPositionMs) return;
        playheadPositionMs = positionMs;
        centerPlayhead();
        postInvalidateOnAnimation();
    }

    /**
     * Returns the segment index that contains the current playhead position.
     * Scans through segments and returns the index of the segment containing playheadPositionMs.
     * Returns -1 if no segment contains the playhead (e.g., playhead is past all segments).
     */
    /**
     * Segment that contains {@code timeMs}. At an exact cut the later clip wins, so
     * pressing play on a boundary advances rather than replaying the previous clip.
     */
    public int getSegmentIndexAtTime(long timeMs) {
        if (segments.isEmpty()) return -1;
        long accum = 0;
        for (int i = 0; i < segments.size(); i++) {
            long end = accum + segments.get(i).effectiveMs;
            if (timeMs < end) return i;
            if (timeMs == end) return Math.min(i + 1, segments.size() - 1);
            accum = end;
        }
        return segments.size() - 1;
    }

    public int getSegmentAtPlayhead() {
        long cumulMs = 0;
        for (int i = 0; i < segments.size(); i++) {
            SegmentData sd = segments.get(i);
            if (playheadPositionMs >= cumulMs && playheadPositionMs <= cumulMs + sd.effectiveMs) {
                return i;
            }
            cumulMs += sd.effectiveMs;
        }
        return segments.isEmpty() ? -1 : segments.size() - 1;
    }

    /**
     * Returns the start time (in timeline ms) for the given segment index.
     */
    public long getSegmentStartTimeMs(int index) {
        return getSegmentStartTime(index);
    }

    private long getSegmentStartTime(int index) {
        long time = 0;
        for (int i = 0; i < Math.min(index, segments.size()); i++) {
            time += segments.get(i).effectiveMs;
        }
        return time;
    }
    
    private void centerPlayhead() {
        float centerX = getWidth() / 2f;
        float playheadX = timeToX(playheadPositionMs);
        scrollOffsetPx = playheadX - centerX;
        clampScroll();
    }

    /**
     * Returns the effective end of the timeline in ms, accounting for both
     * video segments and audio clips that may extend beyond video.
     */
    public long getTimelineEndMs() {
        long end = totalEffectiveMs;
        for (AudioClip ac : audioClips) {
            end = Math.max(end, ac.getEndOnTimelineMs());
        }
        return end;
    }

    /**
     * Aborts an in-flight fling/scrub so the rest of that gesture cannot send seeks.
     * A new touch on the timeline resumes normal scrubbing.
     */
    public void cancelActiveGesture() {
        flingScroller.abortAnimation();
        stopEdgeScroll();
        activeDrag = Drag.NONE;
        isScaling = false;
        downSegIndex = -1;
        trimDragArmed = false;
        gestureCancelled = true;
    }
    
    private void clampScroll() {
        if (contentWidthPx <= 0 || getWidth() <= 0) return;
        
        float centerX = getWidth() / 2f;
        long timelineEnd = getTimelineEndMs();
        
        // Strict bounds: prevent scrolling past timeline start (0ms) or end
        // minScroll: scroll offset when 0ms is at center
        // maxScroll: scroll offset when timelineEnd is at center
        float minScroll = edgePaddingPx - centerX;  // Allows 0ms to be centered
        float maxScroll = timeToX(timelineEnd) - centerX;  // Allows end to be centered
        
        scrollOffsetPx = Math.max(minScroll, Math.min(scrollOffsetPx, maxScroll));
    }

    public void setTrimFromClip(@NonNull Clip clip) {
        if (selectedIndex >= 0 && selectedIndex < segments.size()) {
            segments.set(selectedIndex, new SegmentData(selectedIndex, clip));
            totalEffectiveMs = 0;
            for (SegmentData sd : segments) totalEffectiveMs += sd.effectiveMs;
            computeRects();
            clampScroll();
            requestLayout();
            invalidate();
        }
    }

    public float getTrimStartFraction() {
        if (selectedIndex < 0 || selectedIndex >= segments.size()) return 0f;
        SegmentData sd = segments.get(selectedIndex);
        return sd.sourceDurationMs > 0 ? (float) sd.inPointMs / sd.sourceDurationMs : 0f;
    }

    public float getTrimEndFraction() {
        if (selectedIndex < 0 || selectedIndex >= segments.size()) return 1f;
        SegmentData sd = segments.get(selectedIndex);
        return sd.sourceDurationMs > 0 ? (float) sd.outPointMs / sd.sourceDurationMs : 1f;
    }

    // ══════════════════════════════════════════════════════════════════
    //  LAYOUT
    // ══════════════════════════════════════════════════════════════════

    private void computeRects() {
        segRects.clear();
        if (segments.isEmpty()) {
            contentWidthPx = 0;
            return;
        }
        float tTop = rulerHeightPx;
        float x = edgePaddingPx;

        for (int i = 0; i < segments.size(); i++) {
            SegmentData sd = segments.get(i);
            // Strictly proportional. A minimum-width floor silently stretched short
            // clips, which desynchronised the ruler from the time axis; short clips are
            // meant to be thin, and pinch-zoom is how you make them grabbable.
            float segW = Math.max(2f, (sd.effectiveMs / 1000f) * dpPerSecondPx);
            segRects.add(new RectF(x, tTop, x + segW, tTop + trackHeightPx));
            x += segW + segmentGapPx;
        }
        contentWidthPx = x - segmentGapPx + edgePaddingPx;
        
        // Load thumbnails for visible segments
        for (int i = 0; i < segments.size(); i++) {
            SegmentData sd = segments.get(i);
            loadThumbnailsForSegment(i);
        }

        // Compute audio clip rectangles
        audioClipRects.clear();
        if (!audioClips.isEmpty() && totalEffectiveMs > 0) {
            float audioTop = rulerHeightPx + trackHeightPx + audioTrackGapPx;
            for (AudioClip ac : audioClips) {
                float clipStartX = edgePaddingPx
                        + (ac.getOffsetMs() / 1000f) * dpPerSecondPx;
                float clipW = Math.max(2f,
                        (ac.getTrimmedDurationMs() / 1000f) * dpPerSecondPx);
                RectF audioRect = new RectF(
                        clipStartX, audioTop,
                        clipStartX + clipW, audioTop + audioTrackHeightPx);
                audioClipRects.add(audioRect);
                // Extend contentWidthPx if audio extends beyond video
                contentWidthPx = Math.max(contentWidthPx, audioRect.right + edgePaddingPx);
            }
        }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        float totalDp = RULER_HEIGHT_DP + TRACK_HEIGHT_DP + 12;
        if (!audioClips.isEmpty()) {
            totalDp += AUDIO_TRACK_GAP_DP + AUDIO_TRACK_HEIGHT_DP;
        }
        int defH = (int) (totalDp * density);
        int h = resolveSize(defH, hSpec);
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // A wider screen needs a larger fit: recompute from scratch.
        fitScale = 1f;
        updateFitScale();
        updateDpPerSecond();
        computeRects();
        
        // Center timeline on current playhead position when view size changes
        if (!segments.isEmpty()) {
            centerPlayhead();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  DRAWING
    // ══════════════════════════════════════════════════════════════════

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        // Reorder mode draws its own UI over everything
        if (isReorderMode) {
            drawReorderMode(canvas);
            return;
        }

        int w = getWidth();
        float tTop = rulerHeightPx;
        float tBot = tTop + trackHeightPx;
        float audioTop = tBot + audioTrackGapPx;
        float audioBot = audioTop + audioTrackHeightPx;

        // Backgrounds
        canvas.drawRect(0, 0, w, rulerHeightPx, rulerBgPaint);
        canvas.drawRect(0, tTop, w, tBot, trackBgPaint);
        if (!audioClips.isEmpty()) {
            canvas.drawRect(0, audioTop, w, audioBot, audioTrackBgPaint);
        }

        if (segments.isEmpty() || segRects.isEmpty()) return;

        // Apply scroll offset
        canvas.save();
        canvas.translate(-scrollOffsetPx, 0);
        
        drawRuler(canvas, w);

        float viewLeft = scrollOffsetPx;
        float viewRight = scrollOffsetPx + w;
        for (int i = 0; i < segRects.size(); i++) {
            RectF rect = segRects.get(i);
            if (rect.right < viewLeft || rect.left > viewRight) continue;
            drawSegment(canvas, i);
        }

        if (selectedIndex >= 0 && selectedIndex < segRects.size()) {
            drawTrimHandles(canvas, segRects.get(selectedIndex));
        }

        // Draw audio clips
        if (!audioClips.isEmpty()) {
            drawAudioTrack(canvas);
        }
        
        canvas.restore();
        
        // Draw fixed center playhead (NOT affected by scroll)
        float playheadBot = !audioClips.isEmpty() ? audioBot : tBot;
        drawCenterPlayhead(canvas, tTop, playheadBot);
    }

    private void drawRuler(Canvas canvas, int viewW) {
        if (totalEffectiveMs <= 0) return;
        
        // Dynamic label interval based on zoom (like professional editors).
        // The gap only has to clear a label's glyphs, not 60dp of air: at the default
        // 50dp/s that threshold skipped the 1s tier entirely and jumped to 2s labels,
        // so a 3s timeline showed a single "2s" and a 1s timeline showed no label.
        float minLabelGapPx = 44f * density;
        // Measure the pixels-per-second the layout actually produced. Using the nominal
        // dpPerSecondPx made the ruler disagree with the clip widths (ticks spread over
        // the full width while the clips stayed narrow).
        float pxPerSecond = totalEffectiveMs > 0
                ? (timeToX(totalEffectiveMs) - timeToX(0)) / (totalEffectiveMs / 1000f)
                : dpPerSecondPx;
        long labelInterval = calculateDynamicLabelInterval(minLabelGapPx, pxPerSecond);
        
        // Calculate tick hierarchy intervals to avoid conflicts
        long minorInterval;
        long mediumInterval;
        
        if (labelInterval >= 5000) {
            // Three-tier system for large intervals (5s+)
            minorInterval = 200;       // 0.2s detail ticks
            mediumInterval = 1000;     // 1s medium ticks
        } else if (labelInterval >= 1000) {
            // Two-tier system for medium intervals (1-5s)
            minorInterval = 200;       // 0.2s detail ticks
            mediumInterval = labelInterval; // No medium tier, same as label
        } else {
            // Single-tier for sub-second intervals (<1s)
            minorInterval = labelInterval; // No minor ticks, same as label
            mediumInterval = labelInterval; // No medium ticks, same as label
        }
        
        // Only iterate ticks that can appear in the viewport. Drawing a full hour of
        // ticks every frame is what made long timelines stutter during playback.
        long visibleStartMs = Math.max(0L, xToTime(scrollOffsetPx));
        long visibleEndMs = Math.min(totalEffectiveMs,
                xToTime(scrollOffsetPx + viewW) + labelInterval);

        // Draw minor ticks (finest detail) - skip if too dense
        if (dpPerSecondPx > 25f * density) {  // Only show when zoomed in enough
            rulerTickPaint.setStrokeWidth(1f * density);
            long first = (visibleStartMs / minorInterval) * minorInterval;
            for (long t = first; t <= visibleEndMs; t += minorInterval) {
                if (t % mediumInterval == 0) continue;  // Skip medium/major ticks
                float x = timeToX(t);
                canvas.drawLine(x, rulerHeightPx - rulerTickHeightPx * 0.3f, x, rulerHeightPx, rulerTickPaint);
            }
        }
        
        // Draw medium ticks (1s or sub-intervals)
        if (labelInterval > mediumInterval) {
            rulerTickPaint.setStrokeWidth(1.5f * density);
            long first = (visibleStartMs / mediumInterval) * mediumInterval;
            for (long t = first; t <= visibleEndMs; t += mediumInterval) {
                if (t % labelInterval == 0) continue;  // Skip labeled ticks
                float x = timeToX(t);
                canvas.drawLine(x, rulerHeightPx - rulerTickHeightPx * 0.6f, x, rulerHeightPx, rulerTickPaint);
            }
        }
        
        // Draw major ticks with labels (dynamic interval)
        rulerTickPaint.setStrokeWidth(2f * density);
        long firstLabel = (visibleStartMs / labelInterval) * labelInterval;
        for (long t = firstLabel; t <= visibleEndMs; t += labelInterval) {
            float x = timeToX(t);
            String text = fmtTime(t);
            float halfText = rulerTextPaint.measureText(text) / 2f;
            
            // Tall tick for labeled intervals
            canvas.drawLine(x, rulerHeightPx - rulerTickHeightPx, x, rulerHeightPx, rulerTickPaint);
            
            // Label
            canvas.drawText(text, x - halfText, rulerHeightPx - rulerTickHeightPx - 2f * density, rulerTextPaint);
        }
    }
    
    /** Calculate label interval dynamically based on zoom level to prevent overlap */
    private long calculateDynamicLabelInterval(float minLabelGapPx, float pxPerSecond) {
        // Available intervals in ascending order
        long[] intervals = {100, 200, 250, 500, 1000, 2000, 5000, 10000, 30000, 60000};
        
        // Find smallest interval that gives enough pixel spacing
        for (long interval : intervals) {
            float pixelGap = (interval / 1000f) * pxPerSecond;
            if (pixelGap >= minLabelGapPx) {
                return interval;
            }
        }
        
        return 60000;  // Widest label interval for very zoomed-out views
    }

    /** Maps absolute timeline time (ms) to x coordinate in timeline space (NOT screen space). */
    private float timeToX(long timeMs) {
        long cumulative = 0;
        for (int i = 0; i < segments.size(); i++) {
            SegmentData sd = segments.get(i);
            if (timeMs <= cumulative + sd.effectiveMs) {
                RectF rect = segRects.get(i);
                long localMs = timeMs - cumulative;
                float frac = sd.effectiveMs > 0 ? (float) localMs / sd.effectiveMs : 0f;
                return rect.left + frac * rect.width();
            }
            cumulative += sd.effectiveMs;
        }
        // Time is past all video segments — extend linearly from last segment edge
        if (!segRects.isEmpty()) {
            float lastRight = segRects.get(segRects.size() - 1).right;
            long msPastVideo = timeMs - totalEffectiveMs;
            return lastRight + (msPastVideo / 1000f) * dpPerSecondPx;
        }
        return 0;
    }

    /**
     * Inverse of {@link #timeToX(long)}: convert an X coordinate to timeline milliseconds.
     * Handles times within video segments, gaps between clips, and audio-only regions.
     * 
     * @param x the X coordinate on the timeline
     * @return the corresponding timeline position in milliseconds
     */
    private long xToTime(float x) {
        if (segments.isEmpty() || segRects.isEmpty()) return 0;
        
        // Try to find which segment rect contains this X
        long cumulative = 0;
        for (int i = 0; i < segments.size(); i++) {
            RectF rect = segRects.get(i);
            SegmentData sd = segments.get(i);
            
            // If x is within this segment's rect
            if (x >= rect.left && x <= rect.right) {
                float frac = rect.width() > 0 ? (x - rect.left) / rect.width() : 0f;
                long localMs = (long)(frac * sd.effectiveMs);
                return cumulative + localMs;
            }
            
            // If x is between this segment and the next, interpolate
            if (i < segments.size() - 1) {
                RectF nextRect = segRects.get(i + 1);
                if (x > rect.right && x < nextRect.left) {
                    // x is in a gap between segments
                    // Assign it to the next segment at position 0 (start of next segment)
                    return cumulative + sd.effectiveMs;
                }
            }
            
            cumulative += sd.effectiveMs;
        }
        
        // x is past the last segment's rect
        RectF lastRect = segRects.get(segRects.size() - 1);
        if (x > lastRect.right) {
            float distPastVideo = x - lastRect.right;
            long msPastVideo = (long)((distPastVideo / dpPerSecondPx) * 1000f);
            return totalEffectiveMs + msPastVideo;
        }
        
        // x is before first segment — return 0
        return 0;
    }

    private long getMajorTickInterval(long totalMs) {
        // Return major tick intervals (5s, 10s, 30s, 60s) based on total duration
        if (totalMs < 30000) return 5000;   // < 30s: show 5s ticks
        if (totalMs < 60000) return 10000;  // < 1min: show 10s ticks
        if (totalMs < 300000) return 30000; // < 5min: show 30s ticks
        return 60000;  // >= 5min: show 1min ticks
    }

    private String fmtTime(long ms) {
        String cached = timeLabelCache.get(ms);
        if (cached != null) return cached;

        // Special case: show "0s" instead of "0.0s"
        if (ms == 0) {
            timeLabelCache.put(0, "0s");
            return "0s";
        }

        String label;
        // Show decimal seconds for sub-second precision (0.1s, 0.2s, etc.)
        if (ms < 1000) {
            label = String.format(Locale.US, "0.%ds", ms / 100);
        } else {
            long sec = ms / 1000;
            long m = sec / 60;
            long s = sec % 60;

            // Show sub-second precision if not on whole second boundary
            if (ms % 1000 > 0) {
                long decimal = (ms % 1000) / 100;
                if (m > 0) {
                    label = String.format(Locale.US, "%d:%02d.%d", m, s, decimal);
                } else {
                    label = String.format(Locale.US, "%d.%ds", s, decimal);
                }
            } else {
                label = m > 0 ? String.format(Locale.US, "%d:%02d", m, s)
                        : String.format(Locale.US, "%ds", s);
            }
        }
        timeLabelCache.put(ms, label);
        return label;
    }

    /**
     * Format a duration in ms as a compact label for timeline segments.
     * e.g. "3s", "1:25", "0.8s"
     */
    private static String formatDurationCompact(long ms) {
        if (ms <= 0) return "0s";
        if (ms < 1000) {
            return String.format(Locale.US, "0.%ds", ms / 100);
        }
        long sec = ms / 1000;
        long m = sec / 60;
        long s = sec % 60;
        if (m > 0) {
            return String.format(Locale.US, "%d:%02d", m, s);
        }
        if (ms % 1000 >= 100) {
            return String.format(Locale.US, "%d.%ds", s, (ms % 1000) / 100);
        }
        return String.format(Locale.US, "%ds", s);
    }

    private void drawSegment(Canvas canvas, int i) {
        RectF r = segRects.get(i);
        boolean sel = (i == selectedIndex);
        SegmentData sd = segments.get(i);

        // Draw thumbnails if available, otherwise draw solid color
        List<Bitmap> thumbs = thumbnailsCache.get(sd.cacheKey);
        if (thumbs != null && !thumbs.isEmpty()) {
            drawThumbnailsForSegment(canvas, r, thumbs, sel);
        } else {
            segmentPaint.setColor(sel ? COLOR_SEGMENT_SEL : COLOR_SEGMENT);
            canvas.drawRoundRect(r, segmentCornerPx, segmentCornerPx, segmentPaint);
        }

        // Selection border
        if (sel) {
            float ins = borderWidthPx / 2f;
            drawTmpRect.set(r.left + ins, r.top + ins, r.right - ins, r.bottom - ins);
            canvas.drawRoundRect(drawTmpRect, segmentCornerPx, segmentCornerPx, borderPaint);
        }

        if (r.width() > labelPaint.getTextSize() * 3f) {
            // Sticky duration label: stays against the visible edge of the clip while the
            // strip scrolls under the fixed playhead, so short/long clips remain readable.
            String label = sd.durationLabel;
            float ty = r.bottom - 4f * density;
            float padding = 6f * density;

            float visLeft = Math.max(r.left, scrollOffsetPx);
            float visRight = Math.min(r.right, scrollOffsetPx + getWidth());
            if (visRight - visLeft > labelPaint.getTextSize() * 2f) {
                float labelWidth = labelPaint.measureText(label);
                float cx = visRight - padding - labelWidth / 2f;
                cx = Math.max(visLeft + labelWidth / 2f + padding, cx);

                labelPaint.setShadowLayer(2f * density, 0, 0, 0xFF000000);
                canvas.drawText(label, cx, ty, labelPaint);
                labelPaint.clearShadowLayer();

                // A non-normal speed is shown directly above the duration so a modified
                // clip is recognisable without opening the speed tool.
                if (sd.speedLabel != null) {
                    String speedText = sd.speedLabel;
                    float speedWidth = speedLabelPaint.measureText(speedText);
                    float speedCx = Math.max(visLeft + speedWidth / 2f + padding, cx);
                    speedCx = Math.min(visRight - speedWidth / 2f - padding, speedCx);
                    float speedY = ty - labelPaint.getTextSize() - 2f * density;
                    if (speedY > r.top + speedLabelPaint.getTextSize()) {
                        speedLabelPaint.setShadowLayer(2f * density, 0, 0, 0xFF000000);
                        canvas.drawText(speedText, speedCx, speedY, speedLabelPaint);
                        speedLabelPaint.clearShadowLayer();
                    }
                }
            }
        }
    }
    
    private void drawThumbnailsForSegment(Canvas canvas, RectF rect,
                                          List<Bitmap> thumbs, boolean selected) {
        if (thumbs.isEmpty()) return;

        // Clip canvas to rounded rect so thumbnails don't bleed outside corners
        canvas.save();
        clipPath.reset();
        clipPath.addRoundRect(rect, segmentCornerPx, segmentCornerPx, Path.Direction.CW);
        canvas.clipPath(clipPath);

        // Tile thumbnails across the segment
        float tileWidth = rect.height();  // Square tiles matching track height
        float x = rect.left;
        int thumbIdx = 0;

        while (x < rect.right && thumbIdx < thumbs.size()) {
            Bitmap thumb = thumbs.get(thumbIdx);
            if (thumb != null && !thumb.isRecycled()) {
                float drawRight = Math.min(x + tileWidth, rect.right);
                drawTmpDest.set(x, rect.top, drawRight, rect.bottom);
                canvas.drawBitmap(thumb, null, drawTmpDest, null);
            }
            x += tileWidth;
            thumbIdx++;
            // If we've used all thumbs but still have space, loop back
            if (thumbIdx >= thumbs.size() && x < rect.right) {
                thumbIdx = 0;
            }
        }

        // Darken overlay for selected segment (green tint)
        if (selected) {
            segmentPaint.setColor(FaditorPalette.SELECTED_TINT);
            canvas.drawRect(rect, segmentPaint);
        } else {
            // Slight darken for unselected to make labels readable
            segmentPaint.setColor(0x30000000);
            canvas.drawRect(rect, segmentPaint);
        }

        canvas.restore();
    }
    
    /**
     * Loads thumbnails for a segment on a background thread.
     * Uses MediaMetadataRetriever for video clips and BitmapFactory for image clips.
     */
    private void loadThumbnailsForSegment(int index) {
        if (index < 0 || index >= segments.size()) return;
        if (index >= segRects.size()) return;

        SegmentData sd = segments.get(index);
        String key = sd.cacheKey;

        // Already loaded or currently loading
        if (thumbnailsCache.containsKey(key) && !thumbnailsCache.get(key).isEmpty()) return;
        if (thumbnailsLoading.contains(key)) return;

        thumbnailsLoading.add(key);

        // Calculate how many thumbnails we need based on segment width
        RectF rect = segRects.get(index);
        float tileWidth = trackHeightPx;  // Square tiles
        int count = Math.max(1, (int) Math.ceil(rect.width() / tileWidth));
        count = Math.min(count, MAX_THUMBNAILS_PER_SEGMENT);

        int thumbSize = Math.max(1, (int) trackHeightPx);
        Uri uri = sd.sourceUri;
        boolean isImage = sd.isImageClip;
        long inMs = sd.inPointMs;
        long outMs = sd.outPointMs;
        int finalCount = count;

        thumbnailExecutor.execute(() -> {
            List<Bitmap> thumbs = new ArrayList<>();
            try {
                if (isImage) {
                    extractImageThumbnails(uri, thumbs, thumbSize);
                } else {
                    extractVideoThumbnails(uri, inMs, outMs, thumbs, thumbSize, finalCount);
                }
            } catch (Exception e) {
                FLog.w(TAG, "Failed to extract thumbnails for " + key, e);
            }
            mainHandler.post(() -> {
                thumbnailsLoading.remove(key);
                if (!thumbs.isEmpty()) {
                    thumbnailsCache.put(key, thumbs);
                    invalidate();
                }
            });
        });
    }

    /**
     * Extracts a single thumbnail from an image URI, scaled to thumbSize.
     */
    private void extractImageThumbnails(@NonNull Uri uri, @NonNull List<Bitmap> out, int thumbSize) {
        try {
            // First pass: get dimensions only
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (InputStream is = getContext().getContentResolver().openInputStream(uri)) {
                if (is == null) return;
                BitmapFactory.decodeStream(is, null, opts);
            }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return;

            // Calculate sample size for efficient decoding
            int maxDim = Math.max(opts.outWidth, opts.outHeight);
            opts.inSampleSize = Math.max(1, maxDim / (thumbSize * 2));
            opts.inJustDecodeBounds = false;

            // Second pass: decode scaled bitmap
            Bitmap raw;
            try (InputStream is = getContext().getContentResolver().openInputStream(uri)) {
                if (is == null) return;
                raw = BitmapFactory.decodeStream(is, null, opts);
            }
            if (raw == null) return;

            // Center-crop to square
            Bitmap cropped = centerCropSquare(raw, thumbSize);
            if (cropped != raw) raw.recycle();
            out.add(cropped);
        } catch (Exception e) {
            FLog.w(TAG, "Image thumbnail extraction failed", e);
        }
    }

    /**
     * Extracts evenly-spaced video frames using MediaMetadataRetriever.
     */
    private void extractVideoThumbnails(@NonNull Uri uri, long inMs, long outMs,
                                        @NonNull List<Bitmap> out, int thumbSize, int count) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(getContext(), uri);
            long rangeMs = Math.max(1, outMs - inMs);

            for (int i = 0; i < count; i++) {
                long timeMs = inMs + (rangeMs * i) / count;
                long timeUs = timeMs * 1000L;
                Bitmap frame = retriever.getFrameAtTime(timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame != null) {
                    Bitmap cropped = centerCropSquare(frame, thumbSize);
                    if (cropped != frame) frame.recycle();
                    out.add(cropped);
                }
            }
        } catch (Exception e) {
            FLog.w(TAG, "Video thumbnail extraction failed", e);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {}
        }
    }

    /**
     * Center-crops a bitmap to a square and scales to targetSize.
     */
    @NonNull
    private static Bitmap centerCropSquare(@NonNull Bitmap src, int targetSize) {
        int w = src.getWidth();
        int h = src.getHeight();
        int side = Math.min(w, h);
        int x = (w - side) / 2;
        int y = (h - side) / 2;
        Bitmap cropped = Bitmap.createBitmap(src, x, y, side, side);
        if (cropped.getWidth() != targetSize) {
            Bitmap scaled = Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true);
            if (scaled != cropped) cropped.recycle();
            return scaled;
        }
        return cropped;
    }
    
    private void drawCenterPlayhead(Canvas canvas, float tTop, float tBot) {
        float centerX = getWidth() / 2f;
        
        // Simple white line from top to bottom (like professional editors)
        float lineTop = 0;
        float lineBot = tBot;
        canvas.drawRect(centerX - playheadWidthPx / 2f, lineTop,
                centerX + playheadWidthPx / 2f, lineBot, playheadPaint);
    }

    /**
     * Draws each audio clip as a rounded rectangle with waveform bars inside.
     * Called within the scrolled canvas context.
     */
    private void drawAudioTrack(Canvas canvas) {
        float visibleLeft = scrollOffsetPx;
        float visibleRight = scrollOffsetPx + getWidth();
        for (int i = 0; i < audioClips.size() && i < audioClipRects.size(); i++) {
            AudioClip ac = audioClips.get(i);
            RectF rect = audioClipRects.get(i);
            if (rect.right < visibleLeft || rect.left > visibleRight) continue;

            // Clip background (always rounded, handles drawn on top)
            boolean selected = (i == selectedAudioIndex);
            audioClipPaint.setColor(selected ? COLOR_AUDIO_BG_SEL : COLOR_AUDIO_BG);
            canvas.drawRoundRect(rect, audioCornerPx, audioCornerPx, audioClipPaint);

            // Selection border (rounded to match)
            if (selected) {
                audioBorderPaint.setStyle(Paint.Style.STROKE);
                canvas.drawRoundRect(rect, audioCornerPx, audioCornerPx, audioBorderPaint);
            }

            // Waveform fills the full width (handles overlap edge bars, like video thumbnails)
            int[] waveform = ac.getWaveform();
            if (waveform != null && waveform.length > 0) {
                boolean muted = ac.isMuted();
                int waveColor = muted ? COLOR_AUDIO_WAVE_MUTED : COLOR_AUDIO_WAVE;
                int mirrorColor = muted ? 0x40555555 : COLOR_AUDIO_WAVE_DIM;
                audioWavePaint.setColor(waveColor);
                audioWaveMirrorPaint.setColor(mirrorColor);

                float clipW = rect.width();
                float centerY = rect.centerY();
                float topHalf = (centerY - rect.top) - 1f * density;
                float botHalf = (rect.bottom - centerY) - 1f * density;

                // Draw centerline
                canvas.drawLine(rect.left, centerY,
                        rect.right, centerY, audioCenterlinePaint);

                // 3dp bars with 0.5dp gaps
                float barW = 3f * density;
                float gap = 0.5f * density;
                int barCount = Math.max(1, (int) (clipW / (barW + gap)));
                float step = clipW / barCount;

                canvas.save();
                canvas.clipRect(rect);
                RectF barRect = drawTmpDest;
                for (int j = 0; j < barCount; j++) {
                    float samplePos = (j / (float) barCount) * waveform.length;
                    int si = Math.min((int) samplePos, waveform.length - 1);
                    int si2 = Math.min(si + 1, waveform.length - 1);
                    float frac = samplePos - si;
                    float amplitude = ((waveform[si] * (1f - frac)) + (waveform[si2] * frac)) / 255f;

                    amplitude = (float) Math.pow(amplitude, 0.7);

                    float minBar = 1f * density;
                    float topH = Math.max(minBar, amplitude * topHalf);
                    float botH = Math.max(minBar, amplitude * botHalf * 0.85f);

                    float barX = rect.left + j * step;
                    float barRadius = barW * 0.4f;

                    barRect.set(barX, centerY - topH, barX + barW, centerY);
                    canvas.drawRoundRect(barRect, barRadius, barRadius, audioWavePaint);

                    barRect.set(barX, centerY, barX + barW, centerY + botH);
                    canvas.drawRoundRect(barRect, barRadius, barRadius, audioWaveMirrorPaint);
                }
                canvas.restore();
            }

            // Label (always offset to clear handle area)
            String label = ac.getLabel();
            if (label != null && !label.isEmpty()) {
                float textX = rect.left + handleWidthPx + 4f * density;
                float textY = rect.top + audioLabelPaint.getTextSize() + 2f * density;
                canvas.save();
                canvas.clipRect(rect);
                canvas.drawText(label, textX, textY, audioLabelPaint);
                canvas.restore();
            }

            // Duration label at bottom-right, sticky to visible portion
            long audioDurMs = ac.getOutPointMs() - ac.getInPointMs();
            if (rect.width() > labelPaint.getTextSize() * 3f) {
                String durLabel = formatDurationCompact(audioDurMs);
                float durY = rect.bottom - 3f * density;

                float viewLeft = scrollOffsetPx;
                float viewRight = scrollOffsetPx + getWidth();
                float visRight = Math.min(rect.right, viewRight);

                float durWidth = labelPaint.measureText(durLabel);
                float padding = 6f * density;
                float cx = visRight - padding - durWidth / 2f;
                cx = Math.max(rect.left + durWidth / 2f + padding, cx);
                cx = Math.min(rect.right - durWidth / 2f - padding, cx);

                labelPaint.setShadowLayer(2f * density, 0, 0, 0xFF000000);
                canvas.drawText(durLabel, cx, durY, labelPaint);
                labelPaint.clearShadowLayer();
            }

            // Trim handles on selected audio clip
            if (selected) {
                drawAudioTrimHandles(canvas, rect);
            }
        }
    }

    /**
     * Trim handles at left and right edges of the selected segment.
     * Fully rounded handles for seamless blend with segment curves.
     */
    private void drawTrimHandles(Canvas canvas, RectF seg) {
        float hTop = seg.top - handleOverhangPx;
        float hBot = seg.bottom + handleOverhangPx;
        float cornerRadius = segmentCornerPx;

        if (activeDrag == Drag.LEFT_HANDLE) {
            if (trimDragX > seg.left) {
                // Trimming: dim overlay from seg.left to handle
                canvas.drawRect(seg.left, seg.top, trimDragX, seg.bottom, trimOverlayPaint);
            } else if (trimDragX < seg.left) {
                // Recovering: green overlay from handle to seg.left
                canvas.drawRect(trimDragX, seg.top, seg.left, seg.bottom, trimRecoverPaint);
            }
            // Left handle at drag position
            RectF leftH = new RectF(trimDragX - handleWidthPx / 2f, hTop,
                    trimDragX + handleWidthPx / 2f, hBot);
            canvas.drawRoundRect(leftH, cornerRadius, cornerRadius, handlePaint);
            drawNotch(canvas, leftH);
            // Right handle at segment edge (unchanged)
            RectF rightH = new RectF(seg.right - handleWidthPx, hTop, seg.right, hBot);
            canvas.drawRoundRect(rightH, cornerRadius, cornerRadius, handlePaint);
            drawNotch(canvas, rightH);
        } else if (activeDrag == Drag.RIGHT_HANDLE) {
            // Left handle at segment edge (unchanged)
            RectF leftH = new RectF(seg.left, hTop, seg.left + handleWidthPx, hBot);
            canvas.drawRoundRect(leftH, cornerRadius, cornerRadius, handlePaint);
            drawNotch(canvas, leftH);
            if (trimDragX < seg.right) {
                // Trimming: dim overlay from handle to seg.right
                canvas.drawRect(trimDragX, seg.top, seg.right, seg.bottom, trimOverlayPaint);
            } else if (trimDragX > seg.right) {
                // Recovering: green overlay from seg.right to handle
                canvas.drawRect(seg.right, seg.top, trimDragX, seg.bottom, trimRecoverPaint);
            }
            // Right handle at drag position
            RectF rightH = new RectF(trimDragX - handleWidthPx / 2f, hTop,
                    trimDragX + handleWidthPx / 2f, hBot);
            canvas.drawRoundRect(rightH, cornerRadius, cornerRadius, handlePaint);
            drawNotch(canvas, rightH);
        } else {
            // Normal: handles at segment edges
            RectF leftH = new RectF(seg.left, hTop, seg.left + handleWidthPx, hBot);
            canvas.drawRoundRect(leftH, cornerRadius, cornerRadius, handlePaint);
            drawNotch(canvas, leftH);
            RectF rightH = new RectF(seg.right - handleWidthPx, hTop, seg.right, hBot);
            canvas.drawRoundRect(rightH, cornerRadius, cornerRadius, handlePaint);
            drawNotch(canvas, rightH);
        }
    }

    private void drawNotch(Canvas canvas, RectF hr) {
        float cx = hr.centerX(), cy = hr.centerY();
        float half = handleNotchHeightPx / 2f;
        RectF n = new RectF(cx - handleNotchWidthPx / 2f, cy - half,
                            cx + handleNotchWidthPx / 2f, cy + half);
        canvas.drawRoundRect(n, handleNotchWidthPx / 2f, handleNotchWidthPx / 2f, handleNotchPaint);
    }

    /**
     * Draws trim handles on the selected audio clip, matching video handle style.
     * Includes trim overlay feedback and notch indicators.
     */
    private void drawAudioTrimHandles(Canvas canvas, RectF rect) {
        float hTop = rect.top - handleOverhangPx;
        float hBot = rect.bottom + handleOverhangPx;
        float cornerRadius = audioCornerPx;

        if (activeDrag == Drag.AUDIO_LEFT_HANDLE) {
            if (audioTrimDragX > rect.left) {
                canvas.drawRect(rect.left, rect.top, audioTrimDragX, rect.bottom, trimOverlayPaint);
            } else if (audioTrimDragX < rect.left) {
                canvas.drawRect(audioTrimDragX, rect.top, rect.left, rect.bottom, trimRecoverPaint);
            }
            RectF leftH = new RectF(audioTrimDragX - handleWidthPx / 2f, hTop,
                    audioTrimDragX + handleWidthPx / 2f, hBot);
            canvas.drawRoundRect(leftH, cornerRadius, cornerRadius, handlePaint);
            drawNotch(canvas, leftH);
            RectF rightH = new RectF(rect.right - handleWidthPx, hTop, rect.right, hBot);
            canvas.drawRoundRect(rightH, cornerRadius, cornerRadius, handlePaint);
            drawNotch(canvas, rightH);
        } else if (activeDrag == Drag.AUDIO_RIGHT_HANDLE) {
            RectF leftH = new RectF(rect.left, hTop, rect.left + handleWidthPx, hBot);
            canvas.drawRoundRect(leftH, cornerRadius, cornerRadius, handlePaint);
            drawNotch(canvas, leftH);
            if (audioTrimDragX < rect.right) {
                canvas.drawRect(audioTrimDragX, rect.top, rect.right, rect.bottom, trimOverlayPaint);
            } else if (audioTrimDragX > rect.right) {
                canvas.drawRect(rect.right, rect.top, audioTrimDragX, rect.bottom, trimRecoverPaint);
            }
            RectF rightH = new RectF(audioTrimDragX - handleWidthPx / 2f, hTop,
                    audioTrimDragX + handleWidthPx / 2f, hBot);
            canvas.drawRoundRect(rightH, cornerRadius, cornerRadius, handlePaint);
            drawNotch(canvas, rightH);
        } else {
            // Normal: handles at edges
            RectF leftH = new RectF(rect.left, hTop, rect.left + handleWidthPx, hBot);
            canvas.drawRoundRect(leftH, cornerRadius, cornerRadius, handlePaint);
            drawNotch(canvas, leftH);
            RectF rightH = new RectF(rect.right - handleWidthPx, hTop, rect.right, hBot);
            canvas.drawRoundRect(rightH, cornerRadius, cornerRadius, handlePaint);
            drawNotch(canvas, rightH);
        }
    }

// ══════════════════════════════════════════════════════════════════
    //  REORDER MODE DRAWING
    // ══════════════════════════════════════════════════════════════════

    private void drawReorderMode(@NonNull Canvas canvas) {
        int w = getWidth();
        if (w <= 0) w = getParent() instanceof View ? ((View) getParent()).getWidth() : 500;
        int h = getHeight();
        int n = reorderOrder.size();
        if (n == 0) return;

        // Dark overlay background
        canvas.drawRect(0, 0, w, h, reorderBgPaint2);

        // Top bar
        canvas.drawRect(0, 0, w, reorderBarHeightPx, reorderBarPaint);

        reorderBtnTextPaint.setTextAlign(Paint.Align.LEFT);
        float btnY = reorderBarHeightPx / 2f + reorderBtnTextPaint.getTextSize() / 3f;
        canvas.drawText("✕  Cancel", reorderBtnPaddingPx, btnY, reorderBtnTextPaint);
        float cancelTextW = reorderBtnTextPaint.measureText("✕  Cancel");
        reorderCancelRect.set(0, 0,
                reorderBtnPaddingPx + cancelTextW + reorderBtnPaddingPx, reorderBarHeightPx);

        reorderBtnTextPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("Done", w - reorderBtnPaddingPx, btnY, reorderBtnTextPaint);
        float doneTextW = reorderBtnTextPaint.measureText("Done");
        reorderDoneRect.set(w - reorderBtnPaddingPx - doneTextW - reorderBtnPaddingPx,
                0, w, reorderBarHeightPx);

        reorderBtnTextPaint.setTextAlign(Paint.Align.CENTER);
        reorderBtnTextPaint.setColor(0xAAFFFFFF);
        canvas.drawText("Reorder", w / 2f, btnY, reorderBtnTextPaint);
        reorderBtnTextPaint.setColor(COLOR_HANDLE);

        // Tiles fill the timeline height, matching the strip below.
        float pad = 8f * density;
        float availW = w - reorderBtnPaddingPx * 2f;
        float availH = h - reorderBarHeightPx - pad * 2f;
        reorderGapPx = Math.min(segmentGapPx * 2f,
                Math.max(2f * density, availW * 0.12f / Math.max(1, n)));
        float tileWidth = (availW - (n - 1) * reorderGapPx) / n;
        reorderBlockWidth = Math.max(12f * density,
                Math.min(tileWidth, availH * 0.78f));
        reorderBlockHeight = Math.max(36f * density, availH);
        float totalRowW = n * reorderBlockWidth + (n - 1) * reorderGapPx;
        reorderRowStartX = (w - totalRowW) / 2f;
        reorderRowCenterY = reorderBarHeightPx + pad + reorderBlockHeight / 2f;

        int dropTarget = -1;
        if (reorderDragIdx >= 0) {
            dropTarget = computeReorderDropTarget(reorderDragCenterX);
        }

        float drawIdx = 0;
        for (int i = 0; i < n; i++) {
            if (i == reorderDragIdx) continue;

            float visualPos = drawIdx;
            if (reorderDragIdx >= 0 && dropTarget >= 0) {
                int adjustedDrop = dropTarget;
                if (reorderDragIdx < dropTarget) adjustedDrop--;
                if (drawIdx >= adjustedDrop) visualPos = drawIdx + 1;
            }

            float bx = reorderRowStartX + visualPos * (reorderBlockWidth + reorderGapPx);
            float by = reorderRowCenterY - reorderBlockHeight / 2f;
            RectF blockRect = new RectF(bx, by,
                    bx + reorderBlockWidth, by + reorderBlockHeight);
            drawReorderTile(canvas, blockRect, reorderOrder.get(i));
            drawIdx++;
        }

        if (reorderDragIdx >= 0 && dropTarget >= 0) {
            int adjustedDrop = dropTarget;
            if (reorderDragIdx < dropTarget) adjustedDrop--;
            float indicatorX = reorderRowStartX
                    + adjustedDrop * (reorderBlockWidth + reorderGapPx)
                    - reorderGapPx / 2f;
            float indicatorTop = reorderRowCenterY - reorderBlockHeight / 2f - 4f * density;
            float indicatorBot = reorderRowCenterY + reorderBlockHeight / 2f + 4f * density;
            canvas.drawLine(indicatorX, indicatorTop, indicatorX, indicatorBot,
                    reorderDropIndicatorPaint);
        }

        if (reorderDragIdx >= 0 && reorderDragIdx < n) {
            float bx = reorderDragCenterX - reorderBlockWidth / 2f;
            float by = reorderDragCenterY - reorderBlockHeight / 2f;
            RectF dragRect = new RectF(bx, by,
                    bx + reorderBlockWidth, by + reorderBlockHeight);
            drawReorderTile(canvas, dragRect, reorderOrder.get(reorderDragIdx));
            canvas.drawRoundRect(dragRect, reorderBlockCornerPx, reorderBlockCornerPx,
                    reorderBlockSelectedPaint);
        }
    }

    /** Draws one reorder tile using the clip's cached thumbnail plus duration/speed. */
    private void drawReorderTile(@NonNull Canvas canvas, @NonNull RectF rect,
                                 int segmentIndex) {
        canvas.save();
        clipPath.reset();
        clipPath.addRoundRect(rect, reorderBlockCornerPx, reorderBlockCornerPx,
                Path.Direction.CW);
        canvas.clipPath(clipPath);

        SegmentData sd = segments.get(segmentIndex);
        List<Bitmap> thumbs = thumbnailsCache.get(sd.cacheKey);
        if (thumbs != null && !thumbs.isEmpty()) {
            Bitmap thumb = thumbs.get(0);
            if (thumb != null && !thumb.isRecycled()) {
                float scale = Math.max(rect.width() / thumb.getWidth(),
                        rect.height() / thumb.getHeight());
                float dw = thumb.getWidth() * scale;
                float dh = thumb.getHeight() * scale;
                drawTmpDest.set(rect.centerX() - dw / 2f, rect.centerY() - dh / 2f,
                        rect.centerX() + dw / 2f, rect.centerY() + dh / 2f);
                canvas.drawBitmap(thumb, null, drawTmpDest, null);
            }
        } else {
            canvas.drawRect(rect, reorderBlockPaint2);
        }

        // Darken the bottom so duration text stays readable over any thumbnail.
        float labelBand = Math.min(rect.height() * 0.34f, 22f * density);
        canvas.drawRect(rect.left, rect.bottom - labelBand, rect.right, rect.bottom,
                reorderThumbScrimPaint);
        canvas.restore();

        if (rect.width() < 24f * density) return;

        float ty = rect.bottom - 4f * density;
        canvas.drawText(sd.durationLabel, rect.centerX(), ty, reorderLabelPaint);
        if (sd.speedLabel != null) {
            float speedY = ty - reorderLabelPaint.getTextSize() - 1f * density;
            if (speedY > rect.top + speedLabelPaint.getTextSize()) {
                speedLabelPaint.setTextSize(9f * density);
                canvas.drawText(sd.speedLabel, rect.centerX(), speedY, speedLabelPaint);
                speedLabelPaint.setTextSize(LABEL_SIZE_DP * density);
            }
        }
    }

    private int computeReorderDropTarget(float fingerX) {
        int n = reorderOrder.size();
        for (int i = 0; i < n; i++) {
            float blockCenterX = reorderRowStartX
                    + i * (reorderBlockWidth + reorderGapPx) + reorderBlockWidth / 2f;
            if (fingerX < blockCenterX) return i;
        }
        return n;
    }

    private void enterReorderMode() {
        isReorderMode = true;
        reorderOrder.clear();
        for (int i = 0; i < segments.size(); i++) reorderOrder.add(i);
        reorderDragIdx = -1;
        if (listener != null) listener.onReorderModeChanged(true);
        invalidate();
    }

    private void exitReorderMode(boolean commit) {
        longPressHandler.removeCallbacks(reorderLongPressRunnable);
        reorderPendingIdx = -1;
        isReorderMode = false;
        if (commit && listener != null) {
            // Apply the permutation using a sequence of moveClip operations
            // Build the desired final order
            List<Integer> desired = new ArrayList<>(reorderOrder);
            List<Integer> current = new ArrayList<>();
            for (int i = 0; i < desired.size(); i++) current.add(i);

            for (int targetPos = 0; targetPos < desired.size(); targetPos++) {
                int wantIdx = desired.get(targetPos);
                int currentPos = current.indexOf(wantIdx);
                if (currentPos != targetPos) {
                    listener.onSegmentReordered(currentPos, targetPos);
                    // Reflect the move in our tracking list
                    int moved = current.remove(currentPos);
                    current.add(targetPos, moved);
                }
            }
        }
        reorderOrder.clear();
        reorderDragIdx = -1;
        if (listener != null) listener.onReorderModeChanged(false);
        invalidate();
    }

    private boolean handleReorderTouch(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        int n = reorderOrder.size();

        // Prevent the parent HorizontalScrollView from stealing the gesture.
        getParent().requestDisallowInterceptTouchEvent(true);

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (reorderCancelRect.contains(x, y)) {
                    exitReorderMode(false);
                    return true;
                }
                if (reorderDoneRect.contains(x, y)) {
                    exitReorderMode(true);
                    return true;
                }
                reorderDownX = x;
                reorderDownY = y;
                reorderPendingIdx = -1;
                reorderDragIdx = -1;
                for (int i = 0; i < n; i++) {
                    float bx = reorderRowStartX + i * (reorderBlockWidth + reorderGapPx);
                    float by = reorderRowCenterY - reorderBlockHeight / 2f;
                    if (x >= bx && x <= bx + reorderBlockWidth
                            && y >= by && y <= by + reorderBlockHeight) {
                        reorderPendingIdx = i;
                        break;
                    }
                }
                // A tile only becomes draggable after a long press, so a plain tap or a
                // horizontal swipe cannot reorder clips by accident.
                if (reorderPendingIdx >= 0) {
                    longPressHandler.removeCallbacks(reorderLongPressRunnable);
                    longPressHandler.postDelayed(reorderLongPressRunnable,
                            REORDER_LONG_PRESS_MS);
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (reorderDragIdx >= 0) {
                    reorderDragCenterX = x;
                    reorderDragCenterY = reorderRowCenterY;
                    invalidate();
                } else if (reorderPendingIdx >= 0) {
                    float travel = Math.abs(x - reorderDownX);
                    if (travel > touchSlopPx) {
                        // Scrolling intent, not a reorder.
                        longPressHandler.removeCallbacks(reorderLongPressRunnable);
                        reorderPendingIdx = -1;
                        invalidate();
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                longPressHandler.removeCallbacks(reorderLongPressRunnable);
                if (reorderDragIdx >= 0) {
                    int dropTarget = computeReorderDropTarget(x);
                    dropTarget = Math.max(0, Math.min(dropTarget, n));
                    int movedItem = reorderOrder.remove(reorderDragIdx);
                    int insertAt = dropTarget;
                    if (reorderDragIdx < dropTarget) insertAt--;
                    insertAt = Math.max(0, Math.min(insertAt, reorderOrder.size()));
                    reorderOrder.add(insertAt, movedItem);
                }
                reorderDragIdx = -1;
                reorderPendingIdx = -1;
                invalidate();
                return true;
        }
        return true;
    }

    // ══════════════════════════════════════════════════════════════════
    //  TOUCH HANDLING
    // ══════════════════════════════════════════════════════════════════

    @Override
    public void computeScroll() {
        super.computeScroll();
        
        // Handle fling animation
        if (flingScroller.computeScrollOffset()) {
            // The scroller already stores the scroll offset itself, so assign it and
            // read the playhead. Do not re-derive it from a snapped time.
            scrollOffsetPx = flingScroller.getCurrX();
            clampScroll();
            updatePlayheadFromCenter();
            
            // Continue animation
            postInvalidateOnAnimation();
        } else if (flingJustFinished) {
            // Fling completed — notify listener so userDragging gets reset
            flingJustFinished = false;
            if (!gestureCancelled && listener != null) {
                listener.onPlayheadDragFinished();
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        // Reorder mode intercepts all touch events
        if (isReorderMode) {
            return handleReorderTouch(e);
        }

        
        // Let scale detector process ALL events (it needs to track for pinch detection)
        scaleDetector.onTouchEvent(e);
        
        // If actually pinch-zooming, block other handlers
        if (isScaling) {
            return true;
        }
        
        // When actively dragging a handle, audio clip, or reordering, bypass gesture detector
        // to prevent GestureDetector.onTouchEvent() returning true and blocking onMove/onUp.
        if (activeDrag == Drag.NONE && !isDraggingAudio) {
            // Let gesture detector process events only when no active drag
            boolean gestureEvent = gestureDetector.onTouchEvent(e);
            if (gestureEvent) {
                return true;
            }
        }
        
        // Handle custom touch logic for trim/reorder
        float x = e.getX(), y = e.getY();
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN: 
                return onDown(x, y);
            case MotionEvent.ACTION_MOVE: 
                return onMove(x, y);
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: 
                return onUp(x, y, e.getAction() == MotionEvent.ACTION_UP);
        }
        return super.onTouchEvent(e);
    }

    private boolean onDown(float x, float y) {
        gestureCancelled = false;
        downX = x;
        downY = y;
        downTime = System.currentTimeMillis();
        longPressTriggered = false;

        // Adjust x for scroll offset
        float scrolledX = x + scrollOffsetPx;
        
        // Check trim handles first
        if (selectedIndex >= 0 && selectedIndex < segRects.size()) {
            Drag h = hitTestHandle(scrolledX, y);
            if (h != Drag.NONE) {
                activeDrag = h;
                SegmentData sd = segments.get(selectedIndex);
                dragStartInMs = sd.inPointMs;
                dragStartOutMs = sd.outPointMs;
                RectF seg = segRects.get(selectedIndex);
                dragStartSegLeft = seg.left;
                dragStartSegRight = seg.right;
                // Init trim drag visual state
                trimDragStartFrac = sd.sourceDurationMs > 0 ? (float) sd.inPointMs / sd.sourceDurationMs : 0f;
                trimDragEndFrac = sd.sourceDurationMs > 0 ? (float) sd.outPointMs / sd.sourceDurationMs : 1f;
                trimDragX = scrolledX;
                trimDragOriginX = scrolledX;
                trimDragArmed = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        }

        downSegIndex = hitTestSegment(scrolledX, y);

        // Check audio trim handles (before audio body hit test)
        if (selectedAudioIndex >= 0 && selectedAudioIndex < audioClipRects.size()) {
            Drag ah = hitTestAudioHandle(scrolledX, y);
            if (ah != Drag.NONE) {
                activeDrag = ah;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        }

        // Check if touch is on an audio clip (for select/drag)
        if (downSegIndex < 0) {
            int audioHit = hitTestAudioClip(scrolledX, y);
            if (audioHit >= 0) {
                pendingAudioIndex = audioHit;
                audioLongPressTriggered = false;
                // Schedule audio long-press for drag
                longPressHandler.removeCallbacks(audioLongPressRunnable);
                longPressHandler.postDelayed(audioLongPressRunnable, AUDIO_LONG_PRESS_MS);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        }

        if (downSegIndex >= 0) {
            // Schedule long press detection via Handler
            longPressHandler.removeCallbacks(longPressRunnable);
            if (segments.size() > 1) {
                longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_MS);
            }
        }
        // Always accept touch for scrolling (anywhere on timeline, not just on segments)
        getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }

    private boolean onMove(float x, float y) {
        float dx = Math.abs(x - downX);
        float scrolledX = x + scrollOffsetPx;

        // Cancel long press if finger moved beyond slop
        if (dx > touchSlopPx) {
            longPressHandler.removeCallbacks(longPressRunnable);
            // Cancel audio long press too (if not already triggered)
            if (!audioLongPressTriggered) {
                longPressHandler.removeCallbacks(audioLongPressRunnable);
                pendingAudioIndex = -1;
            }
        }

        if (activeDrag == Drag.LEFT_HANDLE || activeDrag == Drag.RIGHT_HANDLE) {
            lastTrimFingerScreenX = x;  // store screen-space X for edge scroll
            float travel = Math.abs(scrolledX - trimDragOriginX);
            if (!trimDragArmed && travel <= touchSlopPx) {
                // Still inside the tap slop: don't touch the clip yet. This is what
                // makes the handles feel solid instead of jumping on contact.
                return true;
            }
            trimDragArmed = true;
            doTrimDrag(scrolledX);
            // Start/stop edge auto-scroll based on finger proximity to screen edges
            startOrStopEdgeScroll(x);
            return true;
        }

        // Audio trim handle drag
        if (activeDrag == Drag.AUDIO_LEFT_HANDLE || activeDrag == Drag.AUDIO_RIGHT_HANDLE) {
            doAudioTrimDrag(scrolledX);
            return true;
        }

        // Audio clip drag: update offset based on finger movement
        if (isDraggingAudio && dragAudioIndex >= 0 && dragAudioIndex < audioClips.size()) {
            float deltaX = scrolledX - dragAudioStartX;
            float deltaSec = deltaX / dpPerSecondPx;
            long newOffset = dragAudioStartOffsetMs + (long) (deltaSec * 1000f);
            newOffset = Math.max(0, newOffset);
            AudioClip ac = audioClips.get(dragAudioIndex);
            ac.setOffsetMs(newOffset);
            computeRects();
            invalidate();
            return true;
        }

        return true;
    }

    private boolean onUp(float x, float y, boolean isUp) {
        Drag last = activeDrag;
        float scrolledX = x + scrollOffsetPx;

        // Stop edge auto-scroll and cancel long presses
        stopEdgeScroll();
        longPressHandler.removeCallbacks(longPressRunnable);
        longPressHandler.removeCallbacks(audioLongPressRunnable);

        // Finish audio drag
        if (isDraggingAudio) {
            if (listener != null && dragAudioIndex >= 0
                    && dragAudioIndex < audioClips.size()) {
                long newOffset = audioClips.get(dragAudioIndex).getOffsetMs();
                if (newOffset != dragAudioStartOffsetMs) {
                    listener.onAudioClipMoved(dragAudioIndex,
                            dragAudioStartOffsetMs, newOffset);
                }
            }
            isDraggingAudio = false;
            dragAudioIndex = -1;
            pendingAudioIndex = -1;
            computeRects();
            invalidate();
            getParent().requestDisallowInterceptTouchEvent(false);
            return true;
        }

        // Audio tap: select/deselect (only if long press didn't trigger drag)
        if (isUp && pendingAudioIndex >= 0 && !audioLongPressTriggered) {
            float tapDist = Math.abs(x - downX);
            if (tapDist < touchSlopPx) {
                if (pendingAudioIndex == selectedAudioIndex) {
                    selectedAudioIndex = -1; // Deselect
                } else {
                    selectedAudioIndex = pendingAudioIndex;
                    // Deselect video segment when audio is selected
                    if (selectedIndex >= 0) {
                        selectedIndex = -1;
                        if (listener != null) listener.onSegmentSelected(-1);
                    }
                }
                invalidate();
                if (listener != null) listener.onAudioClipSelected(selectedAudioIndex);
            }
            pendingAudioIndex = -1;
            getParent().requestDisallowInterceptTouchEvent(false);
            return true;
        }
        pendingAudioIndex = -1;

        if (last == Drag.LEFT_HANDLE || last == Drag.RIGHT_HANDLE) {
            boolean moved = trimDragArmed
                    || Math.abs(x - downX) > touchSlopPx
                    || Math.abs(trimDragStartFrac - trimDragEndFrac) > 0.0001f;
            if (!moved && isUp) {
                // The finger landed on a handle but never dragged. Treat it as a tap so
                // the clip can be deselected instead of committing a no-op trim, which
                // reloaded the player and flashed the preview.
                int tapIndex = downSegIndex >= 0 ? downSegIndex : selectedIndex;
                boolean wasSelected = tapIndex == selectedIndex;
                selectedIndex = wasSelected ? -1 : tapIndex;
                invalidate();
                if (listener != null) listener.onSegmentSelected(selectedIndex);
            } else if (listener != null) {
                // Use the drag-tracked fractions (segment data was not updated during drag)
                listener.onTrimFinished(selectedIndex, trimDragStartFrac, trimDragEndFrac);
            }
        } else if (last == Drag.AUDIO_LEFT_HANDLE || last == Drag.AUDIO_RIGHT_HANDLE) {
            // Audio trim finished — data was already applied during drag
            if (listener != null) {
                listener.onAudioTrimFinished(selectedAudioIndex, audioTrimDragInMs, audioTrimDragOutMs);
            }
        } else if (isUp && downSegIndex < 0) {
            // A tap on empty space (ruler, gaps, blank track) clears the selection.
            // Without this a very short clip is impossible to deselect, because you can
            // never reliably hit the same 3px sliver twice.
            if (Math.abs(x - downX) < touchSlopPx) {
                boolean hadSelection = selectedIndex >= 0 || selectedAudioIndex >= 0;
                selectedIndex = -1;
                selectedAudioIndex = -1;
                if (hadSelection) {
                    invalidate();
                    if (listener != null) {
                        listener.onSegmentSelected(-1);
                        listener.onAudioClipSelected(-1);
                    }
                }
            }
        } else if (isUp && downSegIndex >= 0) {
            if (Math.abs(x - downX) < touchSlopPx) {
                // A tap toggles selection only. It must not touch scrollOffsetPx or the
                // playhead: in every professional editor, selection says *what* an edit
                // applies to, while the playhead says *where in time* — moving one as a
                // side effect of the other is what made a tap jump the timeline.
                boolean wasSelected = downSegIndex == selectedIndex;
                selectedIndex = wasSelected ? -1 : downSegIndex;
                if (!wasSelected && selectedAudioIndex >= 0) {
                    selectedAudioIndex = -1;
                    if (listener != null) listener.onAudioClipSelected(-1);
                }
                invalidate();
                if (listener != null) listener.onSegmentSelected(selectedIndex);
            }
        }

        // Reset drag state and notify listener that playhead drag finished (if any)
        if (!gestureCancelled && listener != null && last == Drag.NONE) {
            listener.onPlayheadDragFinished();
        }
        
        activeDrag = Drag.NONE;
        downSegIndex = -1;
        trimDragArmed = false;
        getParent().requestDisallowInterceptTouchEvent(false);
        return true;
    }

    // ── Touch helpers ────────────────────────────────────────────────

    private Drag hitTestHandle(float x, float y) {
        if (selectedIndex < 0 || selectedIndex >= segRects.size()) return Drag.NONE;
        RectF seg = segRects.get(selectedIndex);
        float hTop = seg.top - handleOverhangPx;
        float hBot = seg.bottom + handleOverhangPx;

        if (y < hTop - touchSlopPx / 2 || y > hBot + touchSlopPx / 2) return Drag.NONE;

        // Never let the two handle zones swallow the whole clip: on a short clip the
        // handles would otherwise overlap and every tap would begin a zero-length trim
        // instead of toggling the selection. A third of the width each leaves a middle
        // band that always means "tap".
        float hitWidth = Math.min(handleWidthPx, seg.width() / 3f);

        // Left handle zone
        if (x >= seg.left - touchSlopPx / 2 && x <= seg.left + hitWidth + touchSlopPx / 2) {
            return Drag.LEFT_HANDLE;
        }
        // Right handle zone
        if (x >= seg.right - hitWidth - touchSlopPx / 2 && x <= seg.right + touchSlopPx / 2) {
            return Drag.RIGHT_HANDLE;
        }
        return Drag.NONE;
    }

    /**
     * Hit-test trim handles on the selected audio clip.
     */
    private Drag hitTestAudioHandle(float x, float y) {
        if (selectedAudioIndex < 0 || selectedAudioIndex >= audioClipRects.size()) return Drag.NONE;
        RectF r = audioClipRects.get(selectedAudioIndex);
        float hTop = r.top - handleOverhangPx;
        float hBot = r.bottom + handleOverhangPx;

        if (y < hTop - touchSlopPx / 2 || y > hBot + touchSlopPx / 2) return Drag.NONE;

        if (x >= r.left - touchSlopPx / 2 && x <= r.left + handleWidthPx + touchSlopPx / 2) {
            return Drag.AUDIO_LEFT_HANDLE;
        }
        if (x >= r.right - handleWidthPx - touchSlopPx / 2 && x <= r.right + touchSlopPx / 2) {
            return Drag.AUDIO_RIGHT_HANDLE;
        }
        return Drag.NONE;
    }

    /**
     * Compute trim fractions from the pixel drag without updating segment data.
     * Segment rect stays unchanged during drag — visual feedback is via a dim overlay
     * and the trim handle drawn at the finger position (see drawTrimHandles).
     * Segment data is applied only on ACTION_UP via onTrimFinished.
     */
    private void doTrimDrag(float x) {
        if (selectedIndex < 0 || selectedIndex >= segments.size()) return;
        SegmentData sd = segments.get(selectedIndex);
        if (sd.sourceDurationMs <= 0) return;

        boolean isLeft = (activeDrag == Drag.LEFT_HANDLE);

        // px per effective ms at current scale
        float pxPerEffMs = dpPerSecondPx / 1000f;
        // Convert to source ms: source ms = effective ms * speed
        float pxPerSrcMs = pxPerEffMs / sd.speed;

        float minGapFrac = 500f / sd.sourceDurationMs;

        if (isLeft) {
            float endFrac = (float) dragStartOutMs / sd.sourceDurationMs;
            float deltaX = x - trimDragOriginX;
            float deltaSrcMs = deltaX / pxPerSrcMs;
            long newInMs = Math.max(0, Math.min(dragStartOutMs - 500, dragStartInMs + (long) deltaSrcMs));
            trimDragStartFrac = (float) newInMs / sd.sourceDurationMs;
            trimDragStartFrac = Math.max(0f, Math.min(trimDragStartFrac, endFrac - minGapFrac));
            trimDragEndFrac = endFrac;
        } else {
            float startFrac = (float) dragStartInMs / sd.sourceDurationMs;
            float deltaX = x - trimDragOriginX;
            float deltaSrcMs = deltaX / pxPerSrcMs;
            long newOutMs = Math.max(dragStartInMs + 500,
                    Math.min(sd.sourceDurationMs, dragStartOutMs + (long) deltaSrcMs));
            trimDragEndFrac = (float) newOutMs / sd.sourceDurationMs;
            trimDragEndFrac = Math.max(startFrac + minGapFrac, Math.min(1f, trimDragEndFrac));
            trimDragStartFrac = startFrac;
        }

        // Allow handle to extend beyond segment edges for trim recovery.
        // Left extent: position where inPointMs would be 0
        // Right extent: position where outPointMs would be sourceDurationMs
        float pxPerSrcMs2 = (dpPerSecondPx / 1000f) / sd.speed;
        float minTrimX = dragStartSegLeft - (dragStartInMs * pxPerSrcMs2);
        float maxTrimX = dragStartSegRight + ((sd.sourceDurationMs - dragStartOutMs) * pxPerSrcMs2);
        // Move the drawn handle by the same delta the trim uses, so it stays under the
        // finger instead of snapping to it on the first pixel of movement.
        float handleBaseX = isLeft ? dragStartSegLeft : dragStartSegRight;
        trimDragX = Math.max(minTrimX,
                Math.min(handleBaseX + (x - trimDragOriginX), maxTrimX));

        // Segment data and rects are NOT updated — visual feedback comes from
        // the trim overlay drawn in drawTrimHandles. Data is committed in onUp.
        invalidate();

        // Notify listener for time label updates
        if (listener != null) {
            listener.onTrimChanged(selectedIndex, trimDragStartFrac, trimDragEndFrac, isLeft);
        }
    }

    /**
     * Handles audio clip trim handle drag, updating in/out points visually.
     * Data is committed on ACTION_UP via onAudioTrimChanged callback.
     */
    private void doAudioTrimDrag(float x) {
        if (selectedAudioIndex < 0 || selectedAudioIndex >= audioClips.size()) return;
        AudioClip ac = audioClips.get(selectedAudioIndex);
        long srcDur = ac.getSourceDurationMs();
        if (srcDur <= 0) return;

        RectF rect = audioClipRects.get(selectedAudioIndex);
        float pxPerMs = dpPerSecondPx / 1000f;
        long minGap = 500; // minimum 500ms

        boolean isLeft = (activeDrag == Drag.AUDIO_LEFT_HANDLE);
        if (isLeft) {
            float deltaX = x - rect.left;
            long deltaMs = (long) (deltaX / pxPerMs);
            long newIn = Math.max(0, Math.min(ac.getOutPointMs() - minGap, ac.getInPointMs() + deltaMs));
            audioTrimDragInMs = newIn;
            audioTrimDragOutMs = ac.getOutPointMs();
        } else {
            float deltaX = x - rect.right;
            long deltaMs = (long) (deltaX / pxPerMs);
            long newOut = Math.max(ac.getInPointMs() + minGap,
                    Math.min(srcDur, ac.getOutPointMs() + deltaMs));
            audioTrimDragInMs = ac.getInPointMs();
            audioTrimDragOutMs = newOut;
        }
        audioTrimDragX = x;

        // Apply trim changes immediately for visual feedback
        ac.setInPointMs(audioTrimDragInMs);
        ac.setOutPointMs(audioTrimDragOutMs);
        computeRects();
        invalidate();

        if (listener != null) {
            listener.onAudioTrimChanged(selectedAudioIndex, audioTrimDragInMs, audioTrimDragOutMs, isLeft);
        }
    }
    
    /**
     * Start or stop edge auto-scrolling based on finger position.
     * Called on every MOVE event during a trim drag.
     */
    private void startOrStopEdgeScroll(float screenX) {
        int viewWidth = getWidth();
        boolean nearEdge = screenX < edgeScrollZonePx || screenX > viewWidth - edgeScrollZonePx;
        if (nearEdge && !isEdgeScrolling) {
            isEdgeScrolling = true;
            edgeScrollHandler.post(edgeScrollRunnable);
        } else if (!nearEdge && isEdgeScrolling) {
            stopEdgeScroll();
        }
    }

    private void stopEdgeScroll() {
        isEdgeScrolling = false;
        edgeScrollHandler.removeCallbacks(edgeScrollRunnable);
    }

    private int hitTestSegment(float x, float y) {
        if (y < rulerHeightPx - touchSlopPx / 2 || y > rulerHeightPx + trackHeightPx + touchSlopPx / 2) {
            return -1;
        }
        for (int i = 0; i < segRects.size(); i++) {
            RectF r = segRects.get(i);
            if (x >= r.left - segmentGapPx && x <= r.right + segmentGapPx) return i;
        }
        return -1;
    }

    /**
     * Hit-tests audio clip rects. Returns the index of the audio clip under
     * the given coordinates, or -1 if none.
     */
    private int hitTestAudioClip(float x, float y) {
        if (audioClipRects.isEmpty()) return -1;
        float audioTop = rulerHeightPx + trackHeightPx + audioTrackGapPx;
        float audioBot = audioTop + audioTrackHeightPx;
        if (y < audioTop - touchSlopPx / 2 || y > audioBot + touchSlopPx / 2) return -1;
        for (int i = 0; i < audioClipRects.size(); i++) {
            RectF r = audioClipRects.get(i);
            if (x >= r.left && x <= r.right) return i;
        }
        return -1;
    }

    /** Maps tap/drag x to playhead position. x should be in timeline coordinates. */
    /**
     * Update playhead position from an X coordinate.
     * Allows free movement across the entire timeline, including gaps between clips.
     * 
     * CRITICAL: Only changes segments when X visually crosses into a different clip's rectangle.
     * This prevents the "snapping" behavior users experience when dragging near split points.
     */
    /**
     * Reads the timeline position under the fixed centre line and notifies the host.
     *
     * <p>This is the single scrubbing path. It never writes {@link #scrollOffsetPx}:
     * the strip is the source of truth for scroll, the centre line for time.</p>
     */
    private void updatePlayheadFromCenter() {
        updatePlayheadFromX(getWidth() / 2f + scrollOffsetPx, false);
    }

    private void updatePlayheadFromX(float x) {
        updatePlayheadFromX(x, true);
    }

    /**
     * @param recenter whether to re-centre the strip on the resulting time. Dragging
     *                 and flinging must pass {@code false}: re-centring snaps the strip
     *                 back onto the time that was just derived from it, which is how the
     *                 scroll used to stall at a clip edge.
     */
    private void updatePlayheadFromX(float x, boolean recenter) {
        if (gestureCancelled) return;
        float playheadX = x;
        
        long timelineEndMs = getTimelineEndMs();
        long newPlayheadMs = xToTime(playheadX);
        newPlayheadMs = Math.max(0, Math.min(newPlayheadMs, timelineEndMs));
        playheadPositionMs = newPlayheadMs;
        
        
        if (listener != null && !segments.isEmpty()) {
            // Find which segment's RECTANGLE this X falls into (visually, not time-based)
            // This is the key: we use rect-based detection, not time-based
            int targetSegment = -1;
            
            for (int i = 0; i < segRects.size(); i++) {
                RectF rect = segRects.get(i);
                if (playheadX >= rect.left && playheadX <= rect.right) {
                    targetSegment = i;
                    break;
                }
            }
            
            // If X is in a gap between rectangles, snap to the nearest rectangle edge
            // but DON'T trigger a segment change yet
            if (targetSegment < 0 && !segRects.isEmpty()) {
                float minDist = Float.MAX_VALUE;
                for (int i = 0; i < segRects.size(); i++) {
                    RectF rect = segRects.get(i);
                    float dist;
                    if (playheadX < rect.left) {
                        dist = rect.left - playheadX;
                    } else {
                        dist = playheadX - rect.right;
                    }
                    if (dist < minDist) {
                        minDist = dist;
                        targetSegment = i;
                    }
                }
            }
            
            // Bounds check
            if (targetSegment < 0) targetSegment = 0;
            if (targetSegment >= segments.size()) targetSegment = segments.size() - 1;
            
            // NOW compute position within the target segment using ABSOLUTE time
            SegmentData sd = segments.get(targetSegment);
            long cumulMs = 0;
            for (int i = 0; i < targetSegment; i++) {
                cumulMs += segments.get(i).effectiveMs;
            }
            
            long posInSegmentMs = newPlayheadMs - cumulMs;
            posInSegmentMs = Math.max(0, Math.min(posInSegmentMs, sd.effectiveMs));
            
            long sourceMs = sd.inPointMs + (long)(posInSegmentMs * sd.speed);
            float sourceFrac = sd.sourceDurationMs > 0 ? (float)sourceMs / sd.sourceDurationMs : 0f;
            sourceFrac = Math.max(0f, Math.min(sourceFrac, 1f));
            
            
            listener.onPlayheadSeeked(targetSegment, sourceFrac, true);
        }

        if (recenter) {
            centerPlayhead();
        }
        invalidate();
    }

    private int findDrop(float x) {
        for (int i = 0; i < segRects.size(); i++) {
            if (x <= segRects.get(i).centerX()) return i;
        }
        return segRects.size() - 1;
    }
    
    // ── Gesture listeners ────────────────────────────────────────────
    
    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        private float initialZoom;
        private float scaleAccumulator = 1f;
        
        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            isScaling = true;  // Set flag to block other touches
            initialZoom = zoomLevel;
            scaleAccumulator = 1f;
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            scaleAccumulator *= detector.getScaleFactor();
            zoomLevel = initialZoom * scaleAccumulator;
            zoomLevel = Math.max(MIN_ZOOM, Math.min(zoomLevel, MAX_ZOOM));
            // The user is choosing the scale; stop auto-fitting under their fingers.
            userZoomed = true;
            fitScale = 1f;
            
            updateDpPerSecond();
            computeRects();
            timeLabelCache.clear();
            
            // Keep playhead centered during zoom
            centerPlayhead();
            
            invalidate();
            return true;
        }
        
        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            isScaling = false;  // Clear flag to allow other touches
            getParent().requestDisallowInterceptTouchEvent(false);
        }
    }
    
    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            // Cancel any ongoing fling
            if (!flingScroller.isFinished()) {
                flingScroller.abortAnimation();
            }
            return false; // Let custom onDown handle it
        }
        
        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            // Cancel long press — user is scrolling, not holding
            longPressHandler.removeCallbacks(longPressRunnable);
            // Only handle scroll if not dragging handles
            if (activeDrag != Drag.NONE) {
                return false;
            }
            
            // Horizontal drag = scrub. Move the filmstrip by the finger delta and let
            // the fixed centre line read whatever is now under it.
            //
            // The old code round-tripped the position through xToTime()/timeToX():
            // xToTime() snaps a coordinate inside the inter-clip GAP to the exact end
            // of the previous clip, and timeToX() then maps that back to a pixel LEFT
            // of the gap. Every move therefore pulled the scroll back to the same clip
            // boundary — the "stuck at the end of the first clip" bug — and a fast
            // fling escaped it only because the delta outran the snap.
            scrollOffsetPx += distanceX;
            clampScroll();
            updatePlayheadFromCenter();

            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        
        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            // Only fling if not dragging handles or reordering
            if (activeDrag != Drag.NONE) {
                return false;
            }
            
            // Mark that a fling is starting so we can signal drag finished when it ends
            flingJustFinished = true;
            
            // Calculate proper scroll bounds to keep playhead within timeline range
            float centerX = getWidth() / 2f;
            float minScroll = edgePaddingPx - centerX;  // When 0ms at center
            long timelineEnd = getTimelineEndMs();
            float maxScroll = timeToX(timelineEnd) - centerX;  // When timeline end at center
            
            // Start fling animation (negative velocity because scrolling moves playhead position)
            int startX = (int) scrollOffsetPx;
            flingScroller.fling(
                startX, 0,               // startX, startY
                (int) -velocityX, 0,     // velocityX (invert), velocityY
                (int) minScroll,         // minX (video start bound)
                (int) maxScroll,         // maxX (video end bound)
                0, 0                     // minY, maxY (no vertical scroll)
            );
            
            postInvalidateOnAnimation();
            return true;
        }
    }
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        longPressHandler.removeCallbacks(reorderLongPressRunnable);
        // Shut down thumbnail loader
        thumbnailExecutor.shutdownNow();
        thumbnailsLoading.clear();
        // Clean up thumbnails
        for (List<Bitmap> thumbs : thumbnailsCache.values()) {
            if (thumbs != null) {
                for (Bitmap bmp : thumbs) {
                    if (bmp != null && !bmp.isRecycled()) {
                        bmp.recycle();
                    }
                }
            }
        }
        thumbnailsCache.clear();
    }
}
