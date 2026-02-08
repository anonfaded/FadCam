package com.fadcam.ui.faditor.timeline;

import android.content.Context;
import android.graphics.Bitmap;
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

import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.Timeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    // ── Pixel dimensions ─────────────────────────────────────────────
    private float density;
    private float dpPerSecondPx, minSegmentPx, edgePaddingPx;
    private float rulerHeightPx, trackHeightPx, segmentGapPx, segmentCornerPx;
    private float handleWidthPx, handleOverhangPx, handleNotchWidthPx, handleNotchHeightPx;
    private float playheadWidthPx, playheadCirclePx, borderWidthPx;
    private float touchSlopPx, rulerTickHeightPx;

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
    
    // Thumbnails cache (key: clipId, value: list of thumbnails)
    private final Map<String, List<Bitmap>> thumbnailsCache = new HashMap<>();
    private static final long THUMBNAIL_INTERVAL_MS = 1000;

    // ── Touch ────────────────────────────────────────────────────────
    private boolean isScaling = false;
    
    private enum Drag { NONE, LEFT_HANDLE, RIGHT_HANDLE }
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
        long inPointMs;
        long outPointMs;
        long trimmedMs;
        float speed;
        long effectiveMs;

        SegmentData(int i, @NonNull Clip clip) {
            this.index = i;
            this.sourceDurationMs = clip.getSourceDurationMs();
            this.inPointMs = clip.getInPointMs();
            this.outPointMs = clip.getOutPointMs();
            this.trimmedMs = outPointMs - inPointMs;
            this.speed = clip.getSpeedMultiplier();
            this.effectiveMs = Math.max(1, (long)(trimmedMs / speed));
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
        for (int i = 0; i < timeline.getClipCount(); i++) {
            SegmentData sd = new SegmentData(i, timeline.getClip(i));
            segments.add(sd);
            totalEffectiveMs += sd.effectiveMs;
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
    
    private void clampScroll() {
        if (contentWidthPx <= 0 || getWidth() <= 0) return;
        
        float centerX = getWidth() / 2f;
        
        // Strict bounds: prevent scrolling past video start (0ms) or end (totalEffectiveMs)
        // minScroll: scroll offset when 0ms is at center
        // maxScroll: scroll offset when totalEffectiveMs is at center
        float minScroll = edgePaddingPx - centerX;  // Allows 0ms to be centered
        float maxScroll = timeToX(totalEffectiveMs) - centerX;  // Allows end to be centered
        
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
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int defH = (int) ((RULER_HEIGHT_DP + TRACK_HEIGHT_DP + 12) * density);
        int h = resolveSize(defH, hSpec);
        int w = MeasureSpec.getSize(wSpec);  // Use parent width, not content width
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

        // Backgrounds
        canvas.drawRect(0, 0, w, rulerHeightPx, rulerBgPaint);
        canvas.drawRect(0, tTop, w, tBot, trackBgPaint);

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
        
        canvas.restore();
        
        // Draw fixed center playhead (NOT affected by scroll)
        drawCenterPlayhead(canvas, tTop, tBot);
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
        if (!segRects.isEmpty()) return segRects.get(segRects.size() - 1).right;
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

    private void drawSegment(Canvas canvas, int i) {
        RectF r = segRects.get(i);
        boolean sel = (i == selectedIndex);

        // Draw thumbnails if available, otherwise draw solid color
        String clipId = String.valueOf(i);
        if (thumbnailsCache.containsKey(clipId) && !thumbnailsCache.get(clipId).isEmpty()) {
            drawThumbnailsForSegment(canvas, r, clipId);
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

        if (r.width() > labelPaint.getTextSize() * 2f) {
            String label = String.valueOf(i + 1);
            float ty = r.centerY() + labelPaint.getTextSize() / 3f;
            
            // Shadow for better visibility
            labelPaint.setShadowLayer(2f * density, 0, 0, 0xFF000000);
            canvas.drawText(label, r.centerX(), ty, labelPaint);
            labelPaint.clearShadowLayer();
        }
    }
    
    private void drawThumbnailsForSegment(Canvas canvas, RectF rect, String clipId) {
        List<Bitmap> thumbs = thumbnailsCache.get(clipId);
        if (thumbs == null || thumbs.isEmpty()) return;
        
        float thumbWidth = thumbs.get(0).getWidth();
        float x = rect.left;
        
        for (Bitmap thumb : thumbs) {
            if (x >= rect.right) break;
            
            float drawWidth = Math.min(thumbWidth, rect.right - x);
            RectF dest = new RectF(x, rect.top, x + drawWidth, rect.bottom);
            canvas.drawBitmap(thumb, null, dest, null);
            
            x += thumbWidth;
        }
    }
    
    private void loadThumbnailsForSegment(int index) {
        if (index < 0 || index >= segments.size()) return;
        
        String clipId = String.valueOf(index);
        if (thumbnailsCache.containsKey(clipId)) return; // Already loaded/loading
        
        thumbnailsCache.put(clipId, new ArrayList<>()); // Mark as loading
        
        // Load in background
        // For now, just mark as loaded without actual thumbnails
        // You can implement actual MediaMetadataRetriever logic here
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
        
        // When actively dragging a handle or reordering, bypass gesture detector
        // to prevent GestureDetector.onTouchEvent() returning true and blocking onMove/onUp.
        if (activeDrag == Drag.NONE) {
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
        }

        if (activeDrag == Drag.LEFT_HANDLE || activeDrag == Drag.RIGHT_HANDLE) {
            lastTrimFingerScreenX = x;  // store screen-space X for edge scroll
            doTrimDrag(scrolledX);
            // Start/stop edge auto-scroll based on finger proximity to screen edges
            startOrStopEdgeScroll(x);
            return true;
        }

        return true;
    }

    private boolean onUp(float x, float y, boolean isUp) {
        Drag last = activeDrag;
        float scrolledX = x + scrollOffsetPx;

        // Stop edge auto-scroll and cancel long press
        stopEdgeScroll();
        longPressHandler.removeCallbacks(longPressRunnable);

        if (last == Drag.LEFT_HANDLE || last == Drag.RIGHT_HANDLE) {
            if (listener != null) {
                // Use the drag-tracked fractions (segment data was not updated during drag)
                listener.onTrimFinished(selectedIndex, trimDragStartFrac, trimDragEndFrac);
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
        Log.d(TAG, "updatePlayheadFromX: playhead outside all segments, snapping to nearest");
        // Find the nearest segment (handles gaps between segments and before/after edges)
        if (!segments.isEmpty() && !segRects.isEmpty()) {
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
            
            // Calculate proper scroll bounds to keep playhead within video range
            float centerX = getWidth() / 2f;
            float minScroll = edgePaddingPx - centerX;  // When 0ms at center
            float maxScroll = timeToX(totalEffectiveMs) - centerX;  // When end at center
            
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
