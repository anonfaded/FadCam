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
    private int selectedIndex = -1;
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
    
    private enum Drag { NONE, LEFT_HANDLE, RIGHT_HANDLE, REORDER }
    private Drag activeDrag = Drag.NONE;
    private float downX, downY;
    private long downTime;
    private int downSegIndex = -1;
    private boolean longPressTriggered;
    private int reorderIdx = -1;
    private float reorderX;
    private long dragStartInMs, dragStartOutMs;
    private float dragStartSegLeft, dragStartSegRight;
    private static final long LONG_PRESS_MS = 400;
    
    // Gesture detectors for zoom and scroll
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private OverScroller flingScroller;

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
        void onPlayheadSeeked(float fractionInSegment);
        void onPlayheadDragFinished();
        void onSegmentReordered(int fromIndex, int toIndex);
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
            invalidate();
        }
    }

    /**
     * Accept a source-absolute fraction (0..1 of source duration).
     * Converts to absolute ms position in timeline.
     */
    public void setPlayheadFraction(float sourceFraction) {
        Log.d(TAG, "setPlayheadFraction: fraction=" + sourceFraction + " selectedIndex=" + selectedIndex);
        if (selectedIndex >= 0 && selectedIndex < segments.size()) {
            SegmentData sd = segments.get(selectedIndex);
            long selectedSegmentStartMs = getSegmentStartTime(selectedIndex);
            long localMs = (long)(sourceFraction * sd.sourceDurationMs);
            // Convert from source position to trimmed position
            localMs = Math.max(sd.inPointMs, Math.min(localMs, sd.outPointMs)) - sd.inPointMs;
            // Adjust for speed
            localMs = (long)(localMs / sd.speed);
            playheadPositionMs = selectedSegmentStartMs + localMs;
            
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

        if (activeDrag == Drag.REORDER && reorderIdx >= 0 && reorderIdx < segRects.size()) {
            drawGhost(canvas);
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

        if (activeDrag == Drag.REORDER && i == reorderIdx) {
            segmentPaint.setColor(0xFF1A1A1A);
            canvas.drawRoundRect(r, segmentCornerPx, segmentCornerPx, segmentPaint);
            return;
        }

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
        
        // White line from ruler to bottom
        float lineTop = 0;
        float lineBot = tBot;
        canvas.drawRect(centerX - playheadWidthPx / 2f, lineTop,
                centerX + playheadWidthPx / 2f, lineBot, playheadPaint);
        
        // Circle at top
        canvas.drawCircle(centerX, playheadCirclePx + 2f * density, playheadCirclePx, playheadCirclePaint);
        
        // Triangle at bottom
        Path triangle = new Path();
        float triangleSize = playheadCirclePx;
        triangle.moveTo(centerX, lineBot);
        triangle.lineTo(centerX - triangleSize, lineBot - triangleSize * 1.5f);
        triangle.lineTo(centerX + triangleSize, lineBot - triangleSize * 1.5f);
        triangle.close();
        canvas.drawPath(triangle, playheadCirclePaint);
    }

    /**
     * Trim handles at left and right edges of the selected segment.
     * Left handle: only left corners rounded. Right handle: only right corners rounded.
     */
    private void drawTrimHandles(Canvas canvas, RectF seg) {
        float hTop = seg.top - handleOverhangPx;
        float hBot = seg.bottom + handleOverhangPx;

        // Left handle — overlaps the left edge
        RectF leftH = new RectF(seg.left, hTop, seg.left + handleWidthPx, hBot);
        Path leftPath = roundedRectPath(leftH, handleWidthPx / 2f, handleWidthPx / 2f, 0, 0);
        canvas.drawPath(leftPath, handlePaint);
        drawNotch(canvas, leftH);

        // Right handle — overlaps the right edge
        RectF rightH = new RectF(seg.right - handleWidthPx, hTop, seg.right, hBot);
        Path rightPath = roundedRectPath(rightH, 0, 0, handleWidthPx / 2f, handleWidthPx / 2f);
        canvas.drawPath(rightPath, handlePaint);
        drawNotch(canvas, rightH);
    }

    /**
     * Creates a path for a rounded rect with individual corner radii.
     * Order: topLeft, bottomLeft, topRight, bottomRight.
     */
    private Path roundedRectPath(RectF r, float tl, float bl, float tr, float br) {
        Path p = new Path();
        float[] radii = {tl, tl, tr, tr, br, br, bl, bl};
        p.addRoundRect(r, radii, Path.Direction.CW);
        return p;
    }

    private void drawNotch(Canvas canvas, RectF hr) {
        float cx = hr.centerX(), cy = hr.centerY();
        float half = handleNotchHeightPx / 2f;
        RectF n = new RectF(cx - handleNotchWidthPx / 2f, cy - half,
                            cx + handleNotchWidthPx / 2f, cy + half);
        canvas.drawRoundRect(n, handleNotchWidthPx / 2f, handleNotchWidthPx / 2f, handleNotchPaint);
    }

private void drawGhost(Canvas canvas) {
        if (reorderIdx < 0 || reorderIdx >= segRects.size()) return;
        RectF orig = segRects.get(reorderIdx);
        float hw = orig.width() / 2f;
        RectF g = new RectF(reorderX - hw, orig.top, reorderX + hw, orig.bottom);
        canvas.drawRoundRect(g, segmentCornerPx, segmentCornerPx, dragGhostPaint);
        String lbl = String.valueOf(reorderIdx + 1);
        canvas.drawText(lbl, g.centerX(), g.centerY() + labelPaint.getTextSize() / 3f, labelPaint);
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
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        Log.d(TAG, "onTouchEvent: action=" + e.getActionMasked() + " x=" + e.getX() + " isScaling=" + isScaling);
        
        // Let scale detector process ALL events (it needs to track for pinch detection)
        boolean scaledEvent = scaleDetector.onTouchEvent(e);
        
        // If actually pinch-zooming, block other handlers
        if (isScaling) {
            Log.d(TAG, "onTouchEvent: consumed by active pinch zoom");
            return true;
        }
        
        // Let gesture detector process events
        boolean gestureEvent = gestureDetector.onTouchEvent(e);
        if (gestureEvent) {
            Log.d(TAG, "onTouchEvent: consumed by gesture detector");
            return true;
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
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        }

        downSegIndex = hitTestSegment(scrolledX, y);
        Log.d(TAG, "onDown: hit segment " + downSegIndex);
        if (downSegIndex >= 0) {
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }

        return false;
    }

    private boolean onMove(float x, float y) {
        float dx = Math.abs(x - downX);
        float scrolledX = x + scrollOffsetPx;

        // Long press for reorder
        if (!longPressTriggered && activeDrag == Drag.NONE
                && downSegIndex >= 0 && segments.size() > 1) {
            if (System.currentTimeMillis() - downTime >= LONG_PRESS_MS && dx < touchSlopPx) {
                longPressTriggered = true;
                activeDrag = Drag.REORDER;
                reorderIdx = downSegIndex;
                reorderX = scrolledX;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                invalidate();
                return true;
            }
        }

        if (activeDrag == Drag.LEFT_HANDLE || activeDrag == Drag.RIGHT_HANDLE) {
            doTrimDrag(scrolledX);
            return true;
        }

        if (activeDrag == Drag.REORDER) {
            reorderX = scrolledX;
            invalidate();
            return true;
        }

        return true;
    }

    private boolean onUp(float x, float y, boolean isUp) {
        Drag last = activeDrag;
        float scrolledX = x + scrollOffsetPx;

        if (last == Drag.LEFT_HANDLE || last == Drag.RIGHT_HANDLE) {
            if (listener != null) {
                listener.onTrimFinished(selectedIndex, getTrimStartFraction(), getTrimEndFraction());
            }
        } else if (last == Drag.REORDER) {
            int drop = findDrop(reorderX);
            if (drop >= 0 && drop != reorderIdx && listener != null) {
                listener.onSegmentReordered(reorderIdx, drop);
            }
            reorderIdx = -1;
            invalidate();
        } else if (isUp && downSegIndex >= 0) {
            if (Math.abs(x - downX) < touchSlopPx) {
                if (downSegIndex != selectedIndex) {
                    selectedIndex = downSegIndex;
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
     * Convert pixel drag to trim change using the scale-based px-per-ms.
     * Uses captured drag-start values for stable, non-drifting conversion.
     */
    private void doTrimDrag(float x) {
        if (selectedIndex < 0 || selectedIndex >= segments.size()) return;
        SegmentData sd = segments.get(selectedIndex);
        if (sd.sourceDurationMs <= 0) return;

        boolean isLeft = (activeDrag == Drag.LEFT_HANDLE);
        float currentStart = (float) sd.inPointMs / sd.sourceDurationMs;
        float currentEnd = (float) sd.outPointMs / sd.sourceDurationMs;

        // px per effective ms at current scale
        float pxPerEffMs = dpPerSecondPx / 1000f;
        // Convert to source ms: source ms = effective ms * speed
        float pxPerSrcMs = pxPerEffMs / sd.speed;

        float minGapFrac = 500f / sd.sourceDurationMs;

        if (isLeft) {
            float deltaX = x - dragStartSegLeft;
            float deltaSrcMs = deltaX / pxPerSrcMs;
            long newInMs = Math.max(0, Math.min(sd.outPointMs - 500, dragStartInMs + (long) deltaSrcMs));
            float newStart = (float) newInMs / sd.sourceDurationMs;
            newStart = Math.max(0f, Math.min(newStart, currentEnd - minGapFrac));
            if (listener != null) listener.onTrimChanged(selectedIndex, newStart, currentEnd, true);
        } else {
            float deltaX = x - dragStartSegRight;
            float deltaSrcMs = deltaX / pxPerSrcMs;
            long newOutMs = Math.max(sd.inPointMs + 500,
                    Math.min(sd.sourceDurationMs, dragStartOutMs + (long) deltaSrcMs));
            float newEnd = (float) newOutMs / sd.sourceDurationMs;
            newEnd = Math.max(currentStart + minGapFrac, Math.min(1f, newEnd));
            if (listener != null) listener.onTrimChanged(selectedIndex, currentStart, newEnd, false);
        }
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
                    Log.d(TAG, "updatePlayheadFromX: calling onPlayheadSeeked with sourceFrac=" + sourceFrac);
                    listener.onPlayheadSeeked(sourceFrac);
                }
                
                // Auto-scroll to center the new playhead position (like during playback)
                centerPlayhead();
                invalidate();
                return;
            }
            cumulative += segments.get(i).effectiveMs;
        }
        Log.d(TAG, "updatePlayheadFromX: playhead outside all segments");
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
            // Only handle scroll if not dragging handles or reordering
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
            
            // Start fling animation (negative velocity because scrolling moves playhead position)
            int startX = (int) scrollOffsetPx;
            flingScroller.fling(
                startX, 0,               // startX, startY
                (int) -velocityX, 0,     // velocityX (invert), velocityY
                Integer.MIN_VALUE,       // minX (unlimited)
                Integer.MAX_VALUE,       // maxX (unlimited)
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
