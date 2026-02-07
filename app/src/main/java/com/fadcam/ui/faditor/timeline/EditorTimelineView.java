package com.fadcam.ui.faditor.timeline;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.Timeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Unified NLE-style timeline view with horizontal scrolling support.
 * <p>
 * Each segment block width is proportional to its effective (trimmed + speed-adjusted)
 * duration using a fixed DP_PER_SECOND scale. This makes the view wider than the screen
 * for longer videos, enabling horizontal scrolling via a parent HorizontalScrollView.
 * <p>
 * Selected segments show trim handles at the block edges. Dragging handles changes
 * the in/out points and the block width updates accordingly. Long-press + drag reorders.
 */
public class EditorTimelineView extends View {

    private static final String TAG = "EditorTimelineView";

    // ── Scale & layout constants (dp) ────────────────────────────────
    private static final float DP_PER_SECOND = 50f;
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
    private int selectedIndex = 0;
    private float playheadFraction = 0f;
    private long totalEffectiveMs = 0;
    private float contentWidthPx = 0;

    // ── Touch ────────────────────────────────────────────────────────
    private enum Drag { NONE, LEFT_HANDLE, RIGHT_HANDLE, PLAYHEAD, REORDER }
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
        dpPerSecondPx = DP_PER_SECOND * density;
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
        selectedIndex = Math.max(0, Math.min(selected, segments.size() - 1));
        computeRects();
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
     * Converts to 0..1 within the trimmed region for display.
     */
    public void setPlayheadFraction(float sourceFraction) {
        if (selectedIndex >= 0 && selectedIndex < segments.size()) {
            SegmentData sd = segments.get(selectedIndex);
            float inFrac = sd.sourceDurationMs > 0 ? (float) sd.inPointMs / sd.sourceDurationMs : 0f;
            float outFrac = sd.sourceDurationMs > 0 ? (float) sd.outPointMs / sd.sourceDurationMs : 1f;
            float range = outFrac - inFrac;
            if (range > 0) {
                playheadFraction = Math.max(0f, Math.min(1f, (sourceFraction - inFrac) / range));
            } else {
                playheadFraction = 0f;
            }
        } else {
            playheadFraction = Math.max(0f, Math.min(1f, sourceFraction));
        }
        invalidate();
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
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int defH = (int) ((RULER_HEIGHT_DP + TRACK_HEIGHT_DP + 12) * density);
        int h = resolveSize(defH, hSpec);
        int parentW = MeasureSpec.getSize(wSpec);
        int w = (int) Math.max(contentWidthPx, parentW);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeRects();
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

        drawRuler(canvas, w);

        for (int i = 0; i < segRects.size(); i++) {
            drawSegment(canvas, i);
        }

        if (selectedIndex >= 0 && selectedIndex < segRects.size()) {
            drawTrimHandles(canvas, segRects.get(selectedIndex));
        }

        drawPlayhead(canvas, tTop, tBot);

        if (activeDrag == Drag.REORDER && reorderIdx >= 0 && reorderIdx < segRects.size()) {
            drawGhost(canvas);
        }
    }

    private void drawRuler(Canvas canvas, int viewW) {
        if (totalEffectiveMs <= 0) return;
        long interval = tickInterval(totalEffectiveMs);

        for (long t = 0; t <= totalEffectiveMs; t += interval) {
            float x = timeToX(t);
            if (x < 0 || x > viewW) continue;

            String text = fmtTime(t);
            float halfText = rulerTextPaint.measureText(text) / 2f;
            float labelX = Math.max(halfText + 2 * density,
                    Math.min(x, viewW - halfText - 2 * density));

            canvas.drawLine(x, rulerHeightPx - rulerTickHeightPx, x, rulerHeightPx, rulerTickPaint);
            canvas.drawText(text, labelX, rulerHeightPx - rulerTickHeightPx - 2f * density, rulerTextPaint);
        }
    }

    /** Maps absolute timeline time (ms) to x coordinate across all segments. */
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

    private long tickInterval(long totalMs) {
        if (totalMs < 5000) return 1000;
        if (totalMs < 15000) return 2000;
        if (totalMs < 30000) return 5000;
        if (totalMs < 60000) return 10000;
        if (totalMs < 120000) return 15000;
        if (totalMs < 300000) return 30000;
        return 60000;
    }

    private String fmtTime(long ms) {
        long sec = ms / 1000;
        long m = sec / 60;
        long s = sec % 60;
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

        segmentPaint.setColor(sel ? COLOR_SEGMENT_SEL : COLOR_SEGMENT);
        canvas.drawRoundRect(r, segmentCornerPx, segmentCornerPx, segmentPaint);

        if (sel) {
            float ins = borderWidthPx / 2f;
            RectF br = new RectF(r.left + ins, r.top + ins, r.right - ins, r.bottom - ins);
            canvas.drawRoundRect(br, segmentCornerPx, segmentCornerPx, borderPaint);
        }

        if (r.width() > labelPaint.getTextSize() * 2f) {
            String label = String.valueOf(i + 1);
            float ty = r.centerY() + labelPaint.getTextSize() / 3f;
            canvas.drawText(label, r.centerX(), ty, labelPaint);
        }
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

    private void drawPlayhead(Canvas canvas, float tTop, float tBot) {
        if (selectedIndex < 0 || selectedIndex >= segRects.size()) return;
        RectF seg = segRects.get(selectedIndex);
        float phX = seg.left + playheadFraction * seg.width();

        float lineTop = playheadCirclePx * 2;
        float lineBot = tBot + handleOverhangPx;
        canvas.drawRect(phX - playheadWidthPx / 2f, lineTop,
                         phX + playheadWidthPx / 2f, lineBot, playheadPaint);
        canvas.drawCircle(phX, playheadCirclePx, playheadCirclePx, playheadCirclePaint);
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
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN: return onDown(x, y);
            case MotionEvent.ACTION_MOVE: return onMove(x, y);
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: return onUp(x, y, e.getAction() == MotionEvent.ACTION_UP);
        }
        return super.onTouchEvent(e);
    }

    private boolean onDown(float x, float y) {
        downX = x;
        downY = y;
        downTime = System.currentTimeMillis();
        longPressTriggered = false;

        // Check trim handles first
        if (selectedIndex >= 0 && selectedIndex < segRects.size()) {
            Drag h = hitTestHandle(x, y);
            if (h != Drag.NONE) {
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

        downSegIndex = hitTestSegment(x, y);
        if (downSegIndex >= 0) {
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }

        return false;
    }

    private boolean onMove(float x, float y) {
        float dx = Math.abs(x - downX);

        // Long press for reorder
        if (!longPressTriggered && activeDrag == Drag.NONE
                && downSegIndex >= 0 && segments.size() > 1) {
            if (System.currentTimeMillis() - downTime >= LONG_PRESS_MS && dx < touchSlopPx) {
                longPressTriggered = true;
                activeDrag = Drag.REORDER;
                reorderIdx = downSegIndex;
                reorderX = x;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                invalidate();
                return true;
            }
        }

        if (activeDrag == Drag.LEFT_HANDLE || activeDrag == Drag.RIGHT_HANDLE) {
            doTrimDrag(x);
            return true;
        }

        if (activeDrag == Drag.REORDER) {
            reorderX = x;
            invalidate();
            return true;
        }

        if (activeDrag == Drag.NONE && downSegIndex >= 0 && dx > touchSlopPx / 2) {
            if (downSegIndex == selectedIndex) {
                activeDrag = Drag.PLAYHEAD;
                doPlayheadSeek(x);
            }
            return true;
        }

        if (activeDrag == Drag.PLAYHEAD) {
            doPlayheadSeek(x);
            return true;
        }

        return true;
    }

    private boolean onUp(float x, float y, boolean isUp) {
        Drag last = activeDrag;

        if (last == Drag.LEFT_HANDLE || last == Drag.RIGHT_HANDLE) {
            if (listener != null) {
                listener.onTrimFinished(selectedIndex, getTrimStartFraction(), getTrimEndFraction());
            }
        } else if (last == Drag.PLAYHEAD) {
            if (listener != null) listener.onPlayheadDragFinished();
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
                } else {
                    doPlayheadSeek(x);
                    if (listener != null) listener.onPlayheadDragFinished();
                }
            }
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

    /** Maps tap/drag x to 0..1 within the selected segment's block. */
    private void doPlayheadSeek(float x) {
        if (selectedIndex < 0 || selectedIndex >= segRects.size()) return;
        RectF seg = segRects.get(selectedIndex);
        float frac = seg.width() > 0 ? (x - seg.left) / seg.width() : 0f;
        frac = Math.max(0f, Math.min(1f, frac));
        playheadFraction = frac;
        invalidate();
        if (listener != null) listener.onPlayheadSeeked(frac);
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

    private int findDrop(float x) {
        for (int i = 0; i < segRects.size(); i++) {
            if (x <= segRects.get(i).centerX()) return i;
        }
        return segRects.size() - 1;
    }
}
