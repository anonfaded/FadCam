package com.fadcam.ui.faditor.timeline;

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
import android.util.Log;
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

    // ── Scale & layout constants (dp) ────────────────────────────────
    private static final float BASE_DP_PER_SECOND = 50f;
    private static final float MIN_ZOOM = 0.5f;
    private static final float MAX_ZOOM = 8f;
    private static final float MIN_SEGMENT_DP = 80f;
    private static final float EDGE_PADDING_DP = 20f;

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
    private static final int COLOR_SEGMENT_SEL    = 0xFF1B3A20;
    private static final int COLOR_BORDER_SEL     = 0xFF4CAF50;
    private static final int COLOR_HANDLE         = 0xFF4CAF50;
    private static final int COLOR_HANDLE_NOTCH   = 0xBB1B5E20;
    private static final int COLOR_PLAYHEAD       = 0xFFFFFFFF;
    private static final int COLOR_LABEL          = 0xBBFFFFFF;
    private static final int COLOR_DRAG_GHOST     = 0x664CAF50;
    private static final int COLOR_AUDIO_BG       = 0xFF1E2A1E;
    private static final int COLOR_AUDIO_BG_SEL   = 0xFF1B3A20;
    private static final int COLOR_AUDIO_WAVE     = 0xFF4CAF50;
    private static final int COLOR_AUDIO_WAVE_MUTED = 0xFF555555;
    private static final int COLOR_AUDIO_WAVE_DIM = 0x404CAF50; // Faded version for mirror half
    private static final int COLOR_AUDIO_CENTERLINE = 0x334CAF50;
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
    private int lastPlaybackIndex = 0;  // Playback tracking (persists when deselected)
    private long playheadPositionMs = 0; // Absolute playhead position in timeline
    private long totalEffectiveMs = 0;
    private float contentWidthPx = 0;
    
    // Timeline zoom and scroll state
    private float zoomLevel = 1f;
    private float scrollOffsetPx = 0f;
    
    // Thumbnails cache (key: content-based cacheKey, value: list of thumbnails)
    private final Map<String, List<Bitmap>> thumbnailsCache = new HashMap<>();
    private final Set<String> thumbnailsLoading = new HashSet<>();
    private static final int MAX_THUMBNAILS_PER_SEGMENT = 30;
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
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
    private long downTime;
    private int downSegIndex = -1;
    private boolean longPressTriggered;
    private long dragStartInMs, dragStartOutMs;
    private float dragStartSegLeft, dragStartSegRight;
    private float trimDragX;                  // Timeline-space x of handle during trim drag
    private float trimDragStartFrac;          // Start fraction computed during trim drag
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
    private float reorderBlockSize;            // computed block side length
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
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
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
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
            }
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
            // Content-based key: same media + same trim = same thumbnails
            this.cacheKey = sourceUri.hashCode() + "_" + inPointMs + "_" + outPointMs;
        }
    }

    // ── Listener interface ───────────────────────────────────────────
    public interface OnSegmentActionListener {
        void onSegmentSelected(int index);
        void onTrimChanged(int segmentIndex, float startFraction, float endFraction, boolean isLeft);
        void onTrimFinished(int segmentIndex, float startFraction, float endFraction);
        void onPlayheadSeeked(int segmentIndex, float fractionInSegment);
        void onPlayheadDragFinished();
        void onSegmentReordered(int fromIndex, int toIndex);
        void onReorderModeChanged(boolean entering);
        void onAudioClipSelected(int audioIndex);
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
        dragGhostPaint.setColor(COLOR_DRAG_GHOST);
        dragGhostPaint.setStyle(Paint.Style.FILL);
        trimOverlayPaint.setColor(0x80000000);
        trimOverlayPaint.setStyle(Paint.Style.FILL);
        trimRecoverPaint.setColor(0x404CAF50);
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
        dpPerSecondPx = BASE_DP_PER_SECOND * zoomLevel * density;
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
        if (selected >= 0) {
            lastPlaybackIndex = selected;  // Track for playback continuation
        }
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

    public void setSelectedIndex(int index) {
        if (index != selectedIndex && index >= 0 && index < segments.size()) {
            selectedIndex = index;
            lastPlaybackIndex = index;  // Update playback tracking
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
        playheadPositionMs = positionMs;
        centerPlayhead();
        invalidate();
    }

    /**
     * Returns the start time (in timeline ms) for the given segment index.
     */
    public long getSegmentStartTimeMs(int index) {
        return getSegmentStartTime(index);
    }

    /**
     * Accept a source-absolute fraction (0..1 of source duration).
     * Converts to absolute ms position in timeline.
     * Works regardless of UI selection state - uses last known playing segment.
     */
    public void setPlayheadFraction(float sourceFraction) {
        // Use last valid index for playback if currently deselected
        int playbackIndex = selectedIndex >= 0 ? selectedIndex : lastPlaybackIndex;
        
        Log.d(TAG, "setPlayheadFraction: fraction=" + sourceFraction + " selectedIndex=" + selectedIndex + " playbackIndex=" + playbackIndex);
        
        if (playbackIndex >= 0 && playbackIndex < segments.size()) {
            SegmentData sd = segments.get(playbackIndex);
            long selectedSegmentStartMs = getSegmentStartTime(playbackIndex);
            long localMs = (long)(sourceFraction * sd.sourceDurationMs);
            // Convert from source position to trimmed position
            localMs = Math.max(sd.inPointMs, Math.min(localMs, sd.outPointMs)) - sd.inPointMs;
            // Adjust for speed
            localMs = (long)(localMs / sd.speed);
            playheadPositionMs = selectedSegmentStartMs + localMs;
            
            // Remember this index for playback continuation
            lastPlaybackIndex = playbackIndex;
            
            Log.d(TAG, "setPlayheadFraction: playheadPositionMs=" + playheadPositionMs);
            
            // Auto-scroll to keep playhead centered (always, not just when not dragging)
            centerPlayhead();
        }
        invalidate();
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
        Log.d(TAG, "centerPlayhead: centerX=" + centerX + " playheadX=" + playheadX + " scrollOffset=" + scrollOffsetPx);
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
            float segW = Math.max(minSegmentPx, (sd.effectiveMs / 1000f) * dpPerSecondPx);
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
                float clipW = Math.max(minSegmentPx,
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

        for (int i = 0; i < segRects.size(); i++) {
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
        
        // Dynamic label interval based on zoom (like professional editors)
        // Calculate minimum pixels between labels to avoid overlap (~60dp)
        float minLabelGapPx = 60f * density;
        long labelInterval = calculateDynamicLabelInterval(minLabelGapPx);
        
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
        
        // Draw minor ticks (finest detail) - skip if too dense
        if (dpPerSecondPx > 25f * density) {  // Only show when zoomed in enough
            rulerTickPaint.setStrokeWidth(1f * density);
            for (long t = 0; t <= totalEffectiveMs; t += minorInterval) {
                if (t % mediumInterval == 0) continue;  // Skip medium/major ticks
                float x = timeToX(t);
                canvas.drawLine(x, rulerHeightPx - rulerTickHeightPx * 0.3f, x, rulerHeightPx, rulerTickPaint);
            }
        }
        
        // Draw medium ticks (1s or sub-intervals)
        if (labelInterval > mediumInterval) {
            rulerTickPaint.setStrokeWidth(1.5f * density);
            for (long t = 0; t <= totalEffectiveMs; t += mediumInterval) {
                if (t % labelInterval == 0) continue;  // Skip labeled ticks
                float x = timeToX(t);
                canvas.drawLine(x, rulerHeightPx - rulerTickHeightPx * 0.6f, x, rulerHeightPx, rulerTickPaint);
            }
        }
        
        // Draw major ticks with labels (dynamic interval)
        rulerTickPaint.setStrokeWidth(2f * density);
        for (long t = 0; t <= totalEffectiveMs; t += labelInterval) {
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
    private long calculateDynamicLabelInterval(float minLabelGapPx) {
        // Available intervals in ascending order
        long[] intervals = {100, 200, 500, 1000, 2000, 5000, 10000, 30000, 60000};
        
        // Find smallest interval that gives enough pixel spacing
        for (long interval : intervals) {
            float pixelGap = (interval / 1000f) * dpPerSecondPx;
            if (pixelGap >= minLabelGapPx) {
                return interval;
            }
        }
        
        return 60000;  // Fallback to 60s for very zoomed out views
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

    private long getMajorTickInterval(long totalMs) {
        // Return major tick intervals (5s, 10s, 30s, 60s) based on total duration
        if (totalMs < 30000) return 5000;   // < 30s: show 5s ticks
        if (totalMs < 60000) return 10000;  // < 1min: show 10s ticks
        if (totalMs < 300000) return 30000; // < 5min: show 30s ticks
        return 60000;  // >= 5min: show 1min ticks
    }

    private String fmtTime(long ms) {
        // Special case: show "0s" instead of "0.0s"
        if (ms == 0) {
            return "0s";
        }
        
        // Show decimal seconds for sub-second precision (0.1s, 0.2s, etc.)
        if (ms < 1000) {
            return String.format(Locale.US, "0.%ds", ms / 100);
        }
        
        long sec = ms / 1000;
        long m = sec / 60;
        long s = sec % 60;
        
        // Show sub-second precision if not on whole second boundary
        if (ms % 1000 > 0) {
            long decimal = (ms % 1000) / 100;
            if (m > 0) {
                return String.format(Locale.US, "%d:%02d.%d", m, s, decimal);
            } else {
                return String.format(Locale.US, "%d.%ds", s, decimal);
            }
        }
        
        return m > 0 ? String.format(Locale.US, "%d:%02d", m, s)
                      : String.format(Locale.US, "%ds", s);
    }

    /**
     * Format a duration in ms as a compact label for timeline segments.
     * e.g. "3s", "1:25", "0.8s"
     */
    private String formatDurationCompact(long ms) {
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
            RectF br = new RectF(r.left + ins, r.top + ins, r.right - ins, r.bottom - ins);
            canvas.drawRoundRect(br, segmentCornerPx, segmentCornerPx, borderPaint);
        }

        if (r.width() > labelPaint.getTextSize() * 3f) {
            // Show segment duration at the bottom-right — sticky to visible portion
            String label = formatDurationCompact(sd.effectiveMs);
            float ty = r.bottom - 4f * density;

            // Compute visible portion of this segment for sticky positioning
            float viewLeft = scrollOffsetPx;
            float viewRight = scrollOffsetPx + getWidth();
            float visRight = Math.min(r.right, viewRight);

            float labelWidth = labelPaint.measureText(label);
            float padding = 6f * density;
            // Position at right edge of visible portion
            float cx = visRight - padding - labelWidth / 2f;
            // Clamp within segment bounds
            cx = Math.max(r.left + labelWidth / 2f + padding, cx);
            cx = Math.min(r.right - labelWidth / 2f - padding, cx);

            // Shadow for better visibility
            labelPaint.setShadowLayer(2f * density, 0, 0, 0xFF000000);
            canvas.drawText(label, cx, ty, labelPaint);
            labelPaint.clearShadowLayer();
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
                RectF dest = new RectF(x, rect.top, drawRight, rect.bottom);
                canvas.drawBitmap(thumb, null, dest, null);
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
            segmentPaint.setColor(0x40004400);
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
                Log.w(TAG, "Failed to extract thumbnails for " + key, e);
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
            Log.w(TAG, "Image thumbnail extraction failed", e);
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
            Log.w(TAG, "Video thumbnail extraction failed", e);
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
        for (int i = 0; i < audioClips.size() && i < audioClipRects.size(); i++) {
            AudioClip ac = audioClips.get(i);
            RectF rect = audioClipRects.get(i);

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
                RectF barRect = new RectF();
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

        // ── Top bar ──
        canvas.drawRect(0, 0, w, reorderBarHeightPx, reorderBarPaint);

        // Cancel "✕" on left
        reorderBtnTextPaint.setTextAlign(Paint.Align.LEFT);
        float btnY = reorderBarHeightPx / 2f + reorderBtnTextPaint.getTextSize() / 3f;
        canvas.drawText("✕  Cancel", reorderBtnPaddingPx, btnY, reorderBtnTextPaint);
        float cancelTextW = reorderBtnTextPaint.measureText("✕  Cancel");
        reorderCancelRect.set(0, 0, reorderBtnPaddingPx + cancelTextW + reorderBtnPaddingPx, reorderBarHeightPx);

        // "Done" on right
        reorderBtnTextPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("Done", w - reorderBtnPaddingPx, btnY, reorderBtnTextPaint);
        float doneTextW = reorderBtnTextPaint.measureText("Done");
        reorderDoneRect.set(w - reorderBtnPaddingPx - doneTextW - reorderBtnPaddingPx, 0, w, reorderBarHeightPx);

        // Title "Reorder" in center
        reorderBtnTextPaint.setTextAlign(Paint.Align.CENTER);
        reorderBtnTextPaint.setColor(0xAAFFFFFF);
        canvas.drawText("Reorder", w / 2f, btnY, reorderBtnTextPaint);
        reorderBtnTextPaint.setColor(COLOR_HANDLE);  // restore

        // ── Compute block layout ──
        float availW = w - reorderBtnPaddingPx * 2;
        float availH = h - reorderBarHeightPx - 16f * density;  // vertical space for blocks
        reorderGapPx = segmentGapPx * 2;
        reorderBlockSize = Math.min(availH * 0.8f,
                (availW - (n - 1) * reorderGapPx) / n);
        reorderBlockSize = Math.max(reorderBlockSize, 36f * density);  // minimum size
        float totalRowW = n * reorderBlockSize + (n - 1) * reorderGapPx;
        reorderRowStartX = (w - totalRowW) / 2f;
        reorderRowCenterY = reorderBarHeightPx + (h - reorderBarHeightPx) / 2f;

        // ── Compute drop position if dragging ──
        int dropTarget = -1;
        if (reorderDragIdx >= 0) {
            dropTarget = computeReorderDropTarget(reorderDragCenterX);
        }

        // ── Draw blocks ──
        float drawIdx = 0;
        for (int i = 0; i < n; i++) {
            if (i == reorderDragIdx) continue; // Skip the dragged block (drawn later)

            // If a block is being dragged, shift blocks to show drop gap
            float visualPos = drawIdx;
            if (reorderDragIdx >= 0 && dropTarget >= 0) {
                int adjustedDrop = dropTarget;
                if (reorderDragIdx < dropTarget) adjustedDrop--;
                if (drawIdx >= adjustedDrop) visualPos = drawIdx + 1;
            }

            float bx = reorderRowStartX + visualPos * (reorderBlockSize + reorderGapPx);
            float by = reorderRowCenterY - reorderBlockSize / 2f;
            RectF blockRect = new RectF(bx, by, bx + reorderBlockSize, by + reorderBlockSize);

            canvas.drawRoundRect(blockRect, reorderBlockCornerPx, reorderBlockCornerPx, reorderBlockPaint2);

            // Segment number
            int segNum = reorderOrder.get(i) + 1;
            canvas.drawText(String.valueOf(segNum), blockRect.centerX(),
                    blockRect.centerY() + reorderNumPaint.getTextSize() / 3f, reorderNumPaint);

            drawIdx++;
        }

        // ── Draw drop indicator line ──
        if (reorderDragIdx >= 0 && dropTarget >= 0) {
            int adjustedDrop = dropTarget;
            if (reorderDragIdx < dropTarget) adjustedDrop--;
            float indicatorX = reorderRowStartX + adjustedDrop * (reorderBlockSize + reorderGapPx) - reorderGapPx / 2f;
            float indicatorTop = reorderRowCenterY - reorderBlockSize / 2f - 4f * density;
            float indicatorBot = reorderRowCenterY + reorderBlockSize / 2f + 4f * density;
            canvas.drawLine(indicatorX, indicatorTop, indicatorX, indicatorBot, reorderDropIndicatorPaint);
        }

        // ── Draw dragged block last (on top) ──
        if (reorderDragIdx >= 0 && reorderDragIdx < n) {
            float bx = reorderDragCenterX - reorderBlockSize / 2f;
            float by = reorderDragCenterY - reorderBlockSize / 2f;
            RectF dragRect = new RectF(bx, by, bx + reorderBlockSize, by + reorderBlockSize);

            canvas.drawRoundRect(dragRect, reorderBlockCornerPx, reorderBlockCornerPx, reorderBlockDragPaint);
            canvas.drawRoundRect(dragRect, reorderBlockCornerPx, reorderBlockCornerPx, reorderBlockSelectedPaint);

            int segNum = reorderOrder.get(reorderDragIdx) + 1;
            canvas.drawText(String.valueOf(segNum), dragRect.centerX(),
                    dragRect.centerY() + reorderNumPaint.getTextSize() / 3f, reorderNumPaint);
        }
    }

    private int computeReorderDropTarget(float fingerX) {
        int n = reorderOrder.size();
        for (int i = 0; i < n; i++) {
            float blockCenterX = reorderRowStartX + i * (reorderBlockSize + reorderGapPx) + reorderBlockSize / 2f;
            if (fingerX < blockCenterX) return i;
        }
        return n; // drop at end
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

        // Prevent parent (HorizontalScrollView) from intercepting
        getParent().requestDisallowInterceptTouchEvent(true);

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Check cancel button
                if (reorderCancelRect.contains(x, y)) {
                    exitReorderMode(false);
                    return true;
                }
                // Check done button
                if (reorderDoneRect.contains(x, y)) {
                    exitReorderMode(true);
                    return true;
                }
                // Check if touching a block
                for (int i = 0; i < n; i++) {
                    float bx = reorderRowStartX + i * (reorderBlockSize + reorderGapPx);
                    float by = reorderRowCenterY - reorderBlockSize / 2f;
                    if (x >= bx && x <= bx + reorderBlockSize
                            && y >= by && y <= by + reorderBlockSize) {
                        reorderDragIdx = i;
                        reorderDragCenterX = bx + reorderBlockSize / 2f;
                        reorderDragCenterY = by + reorderBlockSize / 2f;
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        invalidate();
                        return true;
                    }
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (reorderDragIdx >= 0) {
                    reorderDragCenterX = x;
                    reorderDragCenterY = y;
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (reorderDragIdx >= 0) {
                    int dropTarget = computeReorderDropTarget(x);
                    // Clamp
                    dropTarget = Math.max(0, Math.min(dropTarget, n));
                    // Perform the visual reorder
                    int movedItem = reorderOrder.remove(reorderDragIdx);
                    int insertAt = dropTarget;
                    if (reorderDragIdx < dropTarget) insertAt--;
                    insertAt = Math.max(0, Math.min(insertAt, reorderOrder.size()));
                    reorderOrder.add(insertAt, movedItem);
                    reorderDragIdx = -1;
                    invalidate();
                }
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
            float newScrollOffset = flingScroller.getCurrX();
            
            // Calculate playhead position from scroll offset
            float centerX = getWidth() / 2f;
            float playheadX = centerX + newScrollOffset;
            
            // Update playhead position (will trigger seek and centerPlayhead)
            updatePlayheadFromX(playheadX);
            
            // Continue animation
            postInvalidateOnAnimation();
        } else if (flingJustFinished) {
            // Fling completed — notify listener so userDragging gets reset
            flingJustFinished = false;
            if (listener != null) {
                Log.d(TAG, "computeScroll: fling finished, calling onPlayheadDragFinished");
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

        Log.d(TAG, "onTouchEvent: action=" + e.getActionMasked() + " x=" + e.getX() + " isScaling=" + isScaling + " activeDrag=" + activeDrag);
        
        // Let scale detector process ALL events (it needs to track for pinch detection)
        scaleDetector.onTouchEvent(e);
        
        // If actually pinch-zooming, block other handlers
        if (isScaling) {
            Log.d(TAG, "onTouchEvent: consumed by active pinch zoom");
            return true;
        }
        
        // When actively dragging a handle, audio clip, or reordering, bypass gesture detector
        // to prevent GestureDetector.onTouchEvent() returning true and blocking onMove/onUp.
        if (activeDrag == Drag.NONE && !isDraggingAudio) {
            // Let gesture detector process events only when no active drag
            boolean gestureEvent = gestureDetector.onTouchEvent(e);
            if (gestureEvent) {
                Log.d(TAG, "onTouchEvent: consumed by gesture detector");
                return true;
            }
        }
        
        // Handle custom touch logic for trim/reorder
        float x = e.getX(), y = e.getY();
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN: 
                Log.d(TAG, "onTouchEvent: ACTION_DOWN - calling onDown");
                return onDown(x, y);
            case MotionEvent.ACTION_MOVE: 
                return onMove(x, y);
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: 
                Log.d(TAG, "onTouchEvent: ACTION_UP/CANCEL - calling onUp");
                return onUp(x, y, e.getAction() == MotionEvent.ACTION_UP);
        }
        return super.onTouchEvent(e);
    }

    private boolean onDown(float x, float y) {
        Log.d(TAG, "onDown: x=" + x + " y=" + y);
        downX = x;
        downY = y;
        downTime = System.currentTimeMillis();
        longPressTriggered = false;

        // Adjust x for scroll offset
        float scrolledX = x + scrollOffsetPx;
        Log.d(TAG, "onDown: scrolledX=" + scrolledX + " scrollOffset=" + scrollOffsetPx);
        
        // Check trim handles first
        if (selectedIndex >= 0 && selectedIndex < segRects.size()) {
            Drag h = hitTestHandle(scrolledX, y);
            if (h != Drag.NONE) {
                Log.d(TAG, "onDown: hit handle " + h);
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
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        }

        downSegIndex = hitTestSegment(scrolledX, y);
        Log.d(TAG, "onDown: hit segment " + downSegIndex);

        // Check audio trim handles (before audio body hit test)
        if (selectedAudioIndex >= 0 && selectedAudioIndex < audioClipRects.size()) {
            Drag ah = hitTestAudioHandle(scrolledX, y);
            if (ah != Drag.NONE) {
                Log.d(TAG, "onDown: hit audio handle " + ah);
                activeDrag = ah;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        }

        // Check if touch is on an audio clip (for select/drag)
        if (downSegIndex < 0) {
            int audioHit = hitTestAudioClip(scrolledX, y);
            if (audioHit >= 0) {
                Log.d(TAG, "onDown: hit audio clip " + audioHit);
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
            if (listener != null) {
                // Use the drag-tracked fractions (segment data was not updated during drag)
                listener.onTrimFinished(selectedIndex, trimDragStartFrac, trimDragEndFrac);
            }
        } else if (last == Drag.AUDIO_LEFT_HANDLE || last == Drag.AUDIO_RIGHT_HANDLE) {
            // Audio trim finished — data was already applied during drag
            if (listener != null) {
                listener.onAudioTrimFinished(selectedAudioIndex, audioTrimDragInMs, audioTrimDragOutMs);
            }
        } else if (isUp && downSegIndex >= 0) {
            if (Math.abs(x - downX) < touchSlopPx) {
                // Toggle selection: deselect if clicking same segment, select if different
                if (downSegIndex == selectedIndex) {
                    selectedIndex = -1; // Deselect
                    invalidate();
                    if (listener != null) listener.onSegmentSelected(-1);
                } else {
                    selectedIndex = downSegIndex; // Select new segment
                    // Deselect audio when video is selected
                    if (selectedAudioIndex >= 0) {
                        selectedAudioIndex = -1;
                        if (listener != null) listener.onAudioClipSelected(-1);
                    }
                    invalidate();
                    if (listener != null) listener.onSegmentSelected(downSegIndex);
                }
            }
        }

        // Reset drag state and notify listener that playhead drag finished (if any)
        if (listener != null && last == Drag.NONE) {
            listener.onPlayheadDragFinished();
        }
        
        activeDrag = Drag.NONE;
        downSegIndex = -1;
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

        // Left handle zone
        if (x >= seg.left - touchSlopPx / 2 && x <= seg.left + handleWidthPx + touchSlopPx / 2) {
            return Drag.LEFT_HANDLE;
        }
        // Right handle zone
        if (x >= seg.right - handleWidthPx - touchSlopPx / 2 && x <= seg.right + touchSlopPx / 2) {
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
            float deltaX = x - dragStartSegLeft;
            float deltaSrcMs = deltaX / pxPerSrcMs;
            long newInMs = Math.max(0, Math.min(dragStartOutMs - 500, dragStartInMs + (long) deltaSrcMs));
            trimDragStartFrac = (float) newInMs / sd.sourceDurationMs;
            trimDragStartFrac = Math.max(0f, Math.min(trimDragStartFrac, endFrac - minGapFrac));
            trimDragEndFrac = endFrac;
        } else {
            float startFrac = (float) dragStartInMs / sd.sourceDurationMs;
            float deltaX = x - dragStartSegRight;
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
        trimDragX = Math.max(minTrimX, Math.min(x, maxTrimX));

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
    private void updatePlayheadFromX(float x) {
        float playheadX = x;  // Use the provided x position
        
        Log.d(TAG, "updatePlayheadFromX: x=" + x + " playheadX=" + playheadX);
        
        // Find which segment the playhead is in
        long cumulative = 0;
        for (int i = 0; i < segments.size(); i++) {
            RectF rect = segRects.get(i);
            if (playheadX >= rect.left && playheadX <= rect.right) {
                float frac = (playheadX - rect.left) / rect.width();
                SegmentData sd = segments.get(i);
                long localMs = (long)(frac * sd.effectiveMs);
                playheadPositionMs = cumulative + localMs;
                
                Log.d(TAG, "updatePlayheadFromX: segment=" + i + " frac=" + frac + " localMs=" + localMs + " playheadPositionMs=" + playheadPositionMs);
                
                // Notify listener with fraction within segment's SOURCE duration
                if (listener != null) {
                    long sourceMs = sd.inPointMs + (long)(localMs * sd.speed);
                    float sourceFrac = sd.sourceDurationMs > 0 ? (float)sourceMs / sd.sourceDurationMs : 0f;
                    Log.d(TAG, "updatePlayheadFromX: calling onPlayheadSeeked segment=" + i + " sourceFrac=" + sourceFrac);
                    listener.onPlayheadSeeked(i, sourceFrac);
                }
                
                // Auto-scroll to center the new playhead position (like during playback)
                centerPlayhead();
                invalidate();
                return;
            }
            cumulative += segments.get(i).effectiveMs;
        }
        Log.d(TAG, "updatePlayheadFromX: playhead outside all segments");
        // Check if playhead is past the last video segment and audio extends beyond
        if (!segments.isEmpty() && !segRects.isEmpty()) {
            RectF lastRect = segRects.get(segRects.size() - 1);
            long timelineEndMs = getTimelineEndMs();

            if (playheadX > lastRect.right && timelineEndMs > totalEffectiveMs) {
                // Playhead is in the audio-only region past video
                float distPastVideo = playheadX - lastRect.right;
                long msPastVideo = (long) ((distPastVideo / dpPerSecondPx) * 1000f);
                playheadPositionMs = Math.min(totalEffectiveMs + msPastVideo, timelineEndMs);

                Log.d(TAG, "updatePlayheadFromX: audio-only region, playheadMs=" + playheadPositionMs);

                // Seek video to end of last segment
                if (listener != null) {
                    int lastIdx = segments.size() - 1;
                    SegmentData sd = segments.get(lastIdx);
                    float sourceFrac = sd.sourceDurationMs > 0
                            ? (float) sd.outPointMs / sd.sourceDurationMs : 1f;
                    listener.onPlayheadSeeked(lastIdx, sourceFrac);
                }
                centerPlayhead();
                invalidate();
                return;
            }

            // Before video start or in gaps — snap to nearest segment
            int nearestSeg = 0;
            float nearestDist = Float.MAX_VALUE;
            for (int i = 0; i < segRects.size(); i++) {
                RectF r = segRects.get(i);
                float dist;
                if (playheadX < r.left) {
                    dist = r.left - playheadX;
                } else if (playheadX > r.right) {
                    dist = playheadX - r.right;
                } else {
                    dist = 0;
                }
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearestSeg = i;
                }
            }
            // Clamp to nearest segment boundary
            RectF nearestRect = segRects.get(nearestSeg);
            float clampedX = Math.max(nearestRect.left, Math.min(playheadX, nearestRect.right));
            float frac = nearestRect.width() > 0 ? (clampedX - nearestRect.left) / nearestRect.width() : 0f;
            SegmentData sd = segments.get(nearestSeg);
            long localMs = (long)(frac * sd.effectiveMs);
            long cumulMs = 0;
            for (int j = 0; j < nearestSeg; j++) cumulMs += segments.get(j).effectiveMs;
            playheadPositionMs = cumulMs + localMs;
            if (listener != null) {
                long sourceMs = sd.inPointMs + (long)(localMs * sd.speed);
                float sourceFrac = sd.sourceDurationMs > 0 ? (float) sourceMs / sd.sourceDurationMs : 0f;
                Log.d(TAG, "updatePlayheadFromX: snapped to segment=" + nearestSeg + " frac=" + frac + " localMs=" + localMs);
                listener.onPlayheadSeeked(nearestSeg, sourceFrac);
            }
            centerPlayhead();
            invalidate();
        }
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
            Log.d(TAG, "ScaleListener.onScaleBegin: zoom=" + zoomLevel);
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
            
            Log.d(TAG, "ScaleListener.onScale: scaleFactor=" + detector.getScaleFactor() + " zoomLevel=" + zoomLevel);
            
            updateDpPerSecond();
            computeRects();
            
            // Keep playhead centered during zoom
            centerPlayhead();
            
            invalidate();
            return true;
        }
        
        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            Log.d(TAG, "ScaleListener.onScaleEnd");
            isScaling = false;  // Clear flag to allow other touches
            getParent().requestDisallowInterceptTouchEvent(false);
        }
    }
    
    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            Log.d(TAG, "GestureListener.onDown");
            // Cancel any ongoing fling
            if (!flingScroller.isFinished()) {
                flingScroller.abortAnimation();
            }
            return false; // Let custom onDown handle it
        }
        
        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            Log.d(TAG, "GestureListener.onScroll: distanceX=" + distanceX + " activeDrag=" + activeDrag);
            // Cancel long press — user is scrolling, not holding
            longPressHandler.removeCallbacks(longPressRunnable);
            // Only handle scroll if not dragging handles
            if (activeDrag != Drag.NONE) {
                Log.d(TAG, "GestureListener.onScroll: ignoring, activeDrag=" + activeDrag);
                return false;
            }
            
            // Horizontal drag on timeline = seek playhead
            float centerX = getWidth() / 2f;
            float newPlayheadX = centerX + scrollOffsetPx + distanceX;
            
            Log.d(TAG, "GestureListener.onScroll: newPlayheadX=" + newPlayheadX);
            
            // Find which segment and position this corresponds to
            updatePlayheadFromX(newPlayheadX);
            
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        
        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            Log.d(TAG, "GestureListener.onFling: velocityX=" + velocityX);
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
