package com.fadcam.ui.faditor.timeline;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.fadcam.ui.faditor.model.Clip;

/**
 * Custom timeline view with draggable trim handles and playhead indicator.
 *
 * <p>For Phase 1 (MVP) this renders a simple waveform-style bar
 * with left/right trim handles and a playhead line.</p>
 *
 * <p>Future phases will add thumbnail strips and multi-clip support.</p>
 */
public class TimelineView extends View {

    private static final String TAG = "TimelineView";

    // Minimum handle gap (px) so they can't overlap
    private static final float MIN_HANDLE_GAP_DP = 24f;

    // Handle width in dp
    private static final float HANDLE_WIDTH_DP = 14f;

    // ── Paint objects ────────────────────────────────────────────────
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── Dimensions ───────────────────────────────────────────────────
    private float handleWidthPx;
    private float minHandleGapPx;
    private float trackTopPx;
    private float trackBottomPx;
    private float trackHeightPx;
    private final float trackCornerRadius = 8f;

    // ── State ────────────────────────────────────────────────────────
    /** Normalized in-point [0.0, 1.0) — left trim handle position. */
    private float trimStartFraction = 0f;

    /** Normalized out-point (0.0, 1.0] — right trim handle position. */
    private float trimEndFraction = 1f;

    /** Normalized playhead position [0.0, 1.0]. */
    private float playheadFraction = 0f;

    /** Which handle is being dragged. */
    private enum DragTarget { NONE, LEFT, RIGHT, PLAYHEAD }
    private DragTarget activeDrag = DragTarget.NONE;

    @Nullable
    private TrimChangeListener trimListener;

    @Nullable
    private PlayheadChangeListener playheadListener;

    // ── Listener interfaces ──────────────────────────────────────────

    public interface TrimChangeListener {
        /** Called continuously while a trim handle is dragged. */
        void onTrimChanged(float startFraction, float endFraction);

        /** Called once when the user lifts their finger from a trim handle. */
        void onTrimFinished(float startFraction, float endFraction);
    }

    public interface PlayheadChangeListener {
        /** Called when the user taps / drags the playhead to a new position. */
        void onPlayheadSeeked(float fraction);
    }

    // ── Constructors ─────────────────────────────────────────────────

    public TimelineView(Context context) {
        super(context);
        init();
    }

    public TimelineView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TimelineView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    // ── Setup ────────────────────────────────────────────────────────

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        handleWidthPx = HANDLE_WIDTH_DP * density;
        minHandleGapPx = MIN_HANDLE_GAP_DP * density;

        // Track background (darker)
        trackPaint.setColor(0xFF2C2C2C);
        trackPaint.setStyle(Paint.Style.FILL);

        // Active (trimmed) region
        activePaint.setColor(0xFF4CAF50); // FadCam green
        activePaint.setStyle(Paint.Style.FILL);

        // Trim handles
        handlePaint.setColor(0xFFFFFFFF);
        handlePaint.setStyle(Paint.Style.FILL);

        // Playhead line
        playheadPaint.setColor(0xFFFF5722); // Orange-red
        playheadPaint.setStyle(Paint.Style.FILL);
        playheadPaint.setStrokeWidth(3f * density);

        // Dimmed (excluded) regions
        dimPaint.setColor(0x80000000);
        dimPaint.setStyle(Paint.Style.FILL);
    }

    // ── Public setters ───────────────────────────────────────────────

    public void setTrimChangeListener(@Nullable TrimChangeListener l) {
        this.trimListener = l;
    }

    public void setPlayheadChangeListener(@Nullable PlayheadChangeListener l) {
        this.playheadListener = l;
    }

    /**
     * Set trim positions from a Clip (converts ms → fraction of source duration).
     */
    public void setTrimFromClip(@NonNull Clip clip) {
        if (clip.getSourceDurationMs() <= 0) return;
        this.trimStartFraction = (float) clip.getInPointMs() / clip.getSourceDurationMs();
        this.trimEndFraction = (float) clip.getOutPointMs() / clip.getSourceDurationMs();
        invalidate();
    }

    /**
     * Update playhead position (called from player position updates).
     *
     * @param fraction 0.0 – 1.0 within the full source duration
     */
    public void setPlayheadFraction(float fraction) {
        this.playheadFraction = Math.max(0f, Math.min(fraction, 1f));
        invalidate();
    }

    public float getTrimStartFraction() { return trimStartFraction; }
    public float getTrimEndFraction() { return trimEndFraction; }

    // ── Drawing ──────────────────────────────────────────────────────

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float padding = handleWidthPx;
        trackTopPx = getPaddingTop() + 8f * getResources().getDisplayMetrics().density;
        trackBottomPx = h - getPaddingBottom() - 8f * getResources().getDisplayMetrics().density;
        trackHeightPx = trackBottomPx - trackTopPx;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float usableWidth = w - 2 * handleWidthPx;
        float baseLeft = handleWidthPx;

        // 1. Full track background
        RectF trackRect = new RectF(baseLeft, trackTopPx,
                baseLeft + usableWidth, trackBottomPx);
        canvas.drawRoundRect(trackRect, trackCornerRadius, trackCornerRadius, trackPaint);

        // 2. Active (selected) region
        float activeLeft = baseLeft + trimStartFraction * usableWidth;
        float activeRight = baseLeft + trimEndFraction * usableWidth;
        RectF activeRect = new RectF(activeLeft, trackTopPx, activeRight, trackBottomPx);
        canvas.drawRoundRect(activeRect, trackCornerRadius, trackCornerRadius, activePaint);

        // 3. Dimmed regions outside trim
        if (trimStartFraction > 0f) {
            RectF leftDim = new RectF(baseLeft, trackTopPx, activeLeft, trackBottomPx);
            canvas.drawRoundRect(leftDim, trackCornerRadius, trackCornerRadius, dimPaint);
        }
        if (trimEndFraction < 1f) {
            RectF rightDim = new RectF(activeRight, trackTopPx,
                    baseLeft + usableWidth, trackBottomPx);
            canvas.drawRoundRect(rightDim, trackCornerRadius, trackCornerRadius, dimPaint);
        }

        // 4. Trim handles
        float handleHeight = trackHeightPx + 16f * getResources().getDisplayMetrics().density;
        float handleTop = trackTopPx - 8f * getResources().getDisplayMetrics().density;
        float handleBottom = handleTop + handleHeight;

        // Left handle
        RectF leftHandle = new RectF(
                activeLeft - handleWidthPx, handleTop,
                activeLeft, handleBottom);
        canvas.drawRoundRect(leftHandle, 6f, 6f, handlePaint);

        // Right handle
        RectF rightHandle = new RectF(
                activeRight, handleTop,
                activeRight + handleWidthPx, handleBottom);
        canvas.drawRoundRect(rightHandle, 6f, 6f, handlePaint);

        // 5. Playhead line
        float playheadX = baseLeft + playheadFraction * usableWidth;
        float phTop = trackTopPx - 12f * getResources().getDisplayMetrics().density;
        float phBottom = trackBottomPx + 12f * getResources().getDisplayMetrics().density;
        canvas.drawLine(playheadX, phTop, playheadX, phBottom, playheadPaint);

        // Playhead circle indicator
        canvas.drawCircle(playheadX, phTop, 6f * getResources().getDisplayMetrics().density,
                playheadPaint);
    }

    // ── Touch handling ───────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float usableWidth = getWidth() - 2 * handleWidthPx;
        float baseLeft = handleWidthPx;

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                activeDrag = detectTarget(x, usableWidth, baseLeft);
                if (activeDrag != DragTarget.NONE) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                // Tap on track → seek playhead
                float fraction = (x - baseLeft) / usableWidth;
                fraction = Math.max(trimStartFraction, Math.min(fraction, trimEndFraction));
                playheadFraction = fraction;
                invalidate();
                if (playheadListener != null) {
                    playheadListener.onPlayheadSeeked(fraction);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (activeDrag == DragTarget.LEFT) {
                    float newStart = (x - baseLeft) / usableWidth;
                    float maxStart = trimEndFraction - minHandleGapPx / usableWidth;
                    trimStartFraction = Math.max(0f, Math.min(newStart, maxStart));
                    invalidate();
                    if (trimListener != null) {
                        trimListener.onTrimChanged(trimStartFraction, trimEndFraction);
                    }
                } else if (activeDrag == DragTarget.RIGHT) {
                    float newEnd = (x - baseLeft) / usableWidth;
                    float minEnd = trimStartFraction + minHandleGapPx / usableWidth;
                    trimEndFraction = Math.max(minEnd, Math.min(newEnd, 1f));
                    invalidate();
                    if (trimListener != null) {
                        trimListener.onTrimChanged(trimStartFraction, trimEndFraction);
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (activeDrag == DragTarget.LEFT || activeDrag == DragTarget.RIGHT) {
                    if (trimListener != null) {
                        trimListener.onTrimFinished(trimStartFraction, trimEndFraction);
                    }
                }
                activeDrag = DragTarget.NONE;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
        }

        return super.onTouchEvent(event);
    }

    private DragTarget detectTarget(float x, float usableWidth, float baseLeft) {
        float leftHandleCenter = baseLeft + trimStartFraction * usableWidth;
        float rightHandleCenter = baseLeft + trimEndFraction * usableWidth;
        float touchSlop = handleWidthPx * 1.5f;

        // Check left handle first
        if (Math.abs(x - leftHandleCenter) < touchSlop) {
            return DragTarget.LEFT;
        }
        // Then right handle
        if (Math.abs(x - rightHandleCenter) < touchSlop) {
            return DragTarget.RIGHT;
        }
        return DragTarget.NONE;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int defaultHeight = (int) (80 * getResources().getDisplayMetrics().density);
        int height = resolveSize(defaultHeight, heightMeasureSpec);
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}
