package com.fadcam.ui.faditor.timeline;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.Timeline;

import java.util.ArrayList;
import java.util.List;

/**
 * Multi-segment overview timeline showing all clips as proportional blocks.
 *
 * <p>Renders a horizontal bar of colored segments proportional to each clip's
 * effective duration. The selected segment gets a green highlight border.
 * Tapping a segment selects it (fires callback). A global playhead indicator
 * shows the current position across all segments.</p>
 */
public class SegmentTimelineView extends View {

    private static final String TAG = "SegmentTimelineView";

    // ── Dimensions (dp) ──────────────────────────────────────────────
    private static final float CORNER_DP = 6f;
    private static final float GAP_DP = 2f;
    private static final float SELECTED_BORDER_DP = 2f;
    private static final float PLAYHEAD_WIDTH_DP = 2.5f;
    private static final float PLAYHEAD_CIRCLE_DP = 4f;
    private static final float PADDING_V_DP = 4f;
    private static final float LABEL_SIZE_DP = 9f;

    // ── Paints ───────────────────────────────────────────────────────
    private final Paint segmentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── Pixel values ─────────────────────────────────────────────────
    private float cornerPx;
    private float gapPx;
    private float selectedBorderPx;
    private float playheadWidthPx;
    private float playheadCirclePx;
    private float paddingVPx;

    // ── State ────────────────────────────────────────────────────────
    private final List<SegmentInfo> segments = new ArrayList<>();
    private int selectedIndex = 0;
    private float globalPlayheadFraction = 0f;  // 0..1 across all segments

    /** Cached rect for each segment block. */
    private final List<RectF> segmentRects = new ArrayList<>();

    @Nullable
    private OnSegmentSelectedListener segmentSelectedListener;

    // ── Colors ───────────────────────────────────────────────────────
    private static final int COLOR_SEGMENT_DEFAULT = 0xFF2A2A2A;
    private static final int COLOR_SEGMENT_SELECTED = 0xFF1E3A22;
    private static final int COLOR_BORDER_SELECTED = 0xFF4CAF50;
    private static final int COLOR_PLAYHEAD = 0xFFFFFFFF;
    private static final int COLOR_LABEL = 0xAAFFFFFF;

    // ── Data model ───────────────────────────────────────────────────

    /** Internal data holder for each segment. */
    private static class SegmentInfo {
        final long effectiveDurationMs;
        final int index;

        SegmentInfo(int index, long effectiveDurationMs) {
            this.index = index;
            this.effectiveDurationMs = effectiveDurationMs;
        }
    }

    // ── Listener ─────────────────────────────────────────────────────

    /**
     * Callback when a segment block is tapped.
     */
    public interface OnSegmentSelectedListener {
        void onSegmentSelected(int index);
    }

    // ── Constructors ─────────────────────────────────────────────────

    public SegmentTimelineView(Context context) {
        super(context);
        init();
    }

    public SegmentTimelineView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SegmentTimelineView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    // ── Setup ────────────────────────────────────────────────────────

    private void init() {
        float d = getResources().getDisplayMetrics().density;
        cornerPx = CORNER_DP * d;
        gapPx = GAP_DP * d;
        selectedBorderPx = SELECTED_BORDER_DP * d;
        playheadWidthPx = PLAYHEAD_WIDTH_DP * d;
        playheadCirclePx = PLAYHEAD_CIRCLE_DP * d;
        paddingVPx = PADDING_V_DP * d;

        segmentPaint.setStyle(Paint.Style.FILL);

        selectedBorderPaint.setColor(COLOR_BORDER_SELECTED);
        selectedBorderPaint.setStyle(Paint.Style.STROKE);
        selectedBorderPaint.setStrokeWidth(selectedBorderPx);

        playheadPaint.setColor(COLOR_PLAYHEAD);
        playheadPaint.setStyle(Paint.Style.FILL);

        playheadCirclePaint.setColor(COLOR_PLAYHEAD);
        playheadCirclePaint.setStyle(Paint.Style.FILL);
        playheadCirclePaint.setShadowLayer(2f * d, 0, 1f * d, 0x40000000);
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        labelPaint.setColor(COLOR_LABEL);
        labelPaint.setTextSize(LABEL_SIZE_DP * d);
        labelPaint.setTextAlign(Paint.Align.CENTER);
    }

    // ── Public API ───────────────────────────────────────────────────

    /**
     * Set the segment selected listener.
     */
    public void setOnSegmentSelectedListener(@Nullable OnSegmentSelectedListener listener) {
        this.segmentSelectedListener = listener;
    }

    /**
     * Update the segment data from the timeline model.
     * Call this whenever clips are added, removed, split, or reordered.
     *
     * @param timeline the Timeline model
     * @param selected the currently selected clip index
     */
    public void setTimeline(@NonNull Timeline timeline, int selected) {
        segments.clear();
        for (int i = 0; i < timeline.getClipCount(); i++) {
            Clip clip = timeline.getClip(i);
            long trimmedDuration = clip.getOutPointMs() - clip.getInPointMs();
            // Effective duration adjusted by speed
            long effectiveMs = Math.max(1, (long) (trimmedDuration / clip.getSpeedMultiplier()));
            segments.add(new SegmentInfo(i, effectiveMs));
        }
        this.selectedIndex = Math.max(0, Math.min(selected, segments.size() - 1));
        computeRects();
        invalidate();
    }

    /**
     * Update just the selected segment index without rebuilding segment data.
     */
    public void setSelectedIndex(int index) {
        if (index < 0 || index >= segments.size()) return;
        this.selectedIndex = index;
        invalidate();
    }

    /**
     * Set the global playhead position as a fraction (0..1) of total timeline duration.
     */
    public void setGlobalPlayheadFraction(float fraction) {
        this.globalPlayheadFraction = Math.max(0f, Math.min(1f, fraction));
        invalidate();
    }

    /**
     * Hide this view if only one segment (show trim-only mode).
     * Show when multiple segments exist.
     */
    public void updateVisibility() {
        setVisibility(segments.size() > 1 ? VISIBLE : GONE);
    }

    // ── Internal layout ──────────────────────────────────────────────

    private void computeRects() {
        segmentRects.clear();
        if (segments.isEmpty()) return;

        float totalWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        if (totalWidth <= 0) return;

        long totalDuration = 0;
        for (SegmentInfo seg : segments) {
            totalDuration += seg.effectiveDurationMs;
        }
        if (totalDuration <= 0) return;

        float totalGaps = gapPx * (segments.size() - 1);
        float availableWidth = totalWidth - totalGaps;
        float top = getPaddingTop() + paddingVPx;
        float bottom = getHeight() - getPaddingBottom() - paddingVPx;

        float x = getPaddingLeft();
        for (int i = 0; i < segments.size(); i++) {
            SegmentInfo seg = segments.get(i);
            float segWidth = availableWidth * ((float) seg.effectiveDurationMs / totalDuration);
            // Ensure a minimum visible width
            segWidth = Math.max(segWidth, cornerPx * 2);
            segmentRects.add(new RectF(x, top, x + segWidth, bottom));
            x += segWidth + gapPx;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeRects();
    }

    // ── Drawing ──────────────────────────────────────────────────────

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (segments.isEmpty() || segmentRects.isEmpty()) return;

        // Draw each segment block
        for (int i = 0; i < segmentRects.size(); i++) {
            RectF rect = segmentRects.get(i);
            boolean isSelected = (i == selectedIndex);

            // Fill
            segmentPaint.setColor(isSelected ? COLOR_SEGMENT_SELECTED : COLOR_SEGMENT_DEFAULT);
            canvas.drawRoundRect(rect, cornerPx, cornerPx, segmentPaint);

            // Selected border
            if (isSelected) {
                float inset = selectedBorderPx / 2f;
                RectF borderRect = new RectF(
                        rect.left + inset, rect.top + inset,
                        rect.right - inset, rect.bottom - inset);
                canvas.drawRoundRect(borderRect, cornerPx, cornerPx, selectedBorderPaint);
            }

            // Segment label (1, 2, 3...)
            if (rect.width() > labelPaint.getTextSize() * 1.5f) {
                String label = String.valueOf(i + 1);
                float textY = rect.centerY() + labelPaint.getTextSize() / 3f;
                canvas.drawText(label, rect.centerX(), textY, labelPaint);
            }
        }

        // Draw global playhead
        if (globalPlayheadFraction > 0f || globalPlayheadFraction == 0f) {
            float totalWidth = 0;
            for (RectF r : segmentRects) {
                totalWidth += r.width();
            }
            totalWidth += gapPx * (segmentRects.size() - 1);

            float targetX = getPaddingLeft() + globalPlayheadFraction * totalWidth;
            float top = getPaddingTop();
            float bottom = getHeight() - getPaddingBottom();

            // Playhead line
            canvas.drawRect(
                    targetX - playheadWidthPx / 2f, top + paddingVPx,
                    targetX + playheadWidthPx / 2f, bottom - paddingVPx,
                    playheadPaint);

            // Playhead circle at top
            canvas.drawCircle(targetX, top + paddingVPx, playheadCirclePx, playheadCirclePaint);
        }
    }

    // ── Touch handling ───────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float x = event.getX();
            float y = event.getY();
            for (int i = 0; i < segmentRects.size(); i++) {
                RectF rect = segmentRects.get(i);
                if (rect.contains(x, y)) {
                    if (i != selectedIndex) {
                        selectedIndex = i;
                        invalidate();
                        if (segmentSelectedListener != null) {
                            segmentSelectedListener.onSegmentSelected(i);
                        }
                    }
                    break;
                }
            }
            return true;
        }
        return event.getAction() == MotionEvent.ACTION_DOWN;
    }

    // ── Measurement ──────────────────────────────────────────────────

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float d = getResources().getDisplayMetrics().density;
        int defaultHeight = (int) (36 * d);  // 36dp
        int height = resolveSize(defaultHeight, heightMeasureSpec);
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}
