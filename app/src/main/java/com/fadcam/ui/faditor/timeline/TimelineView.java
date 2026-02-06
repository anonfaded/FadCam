package com.fadcam.ui.faditor.timeline;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.Clip;

/**
 * Custom timeline view with draggable trim handles and playhead indicator.
 *
 * <p>Renders a waveform-style bar with left/right trim handles (rounded pill shape),
 * an active (selected) region, dimmed excluded regions, and a playhead line.</p>
 */
public class TimelineView extends View {

    private static final String TAG = "TimelineView";

    // Handle/layout dimensions (dp)
    private static final float HANDLE_WIDTH_DP = 12f;
    private static final float HANDLE_CORNER_DP = 6f;
    private static final float MIN_HANDLE_GAP_DP = 20f;
    private static final float TRACK_CORNER_DP = 10f;
    private static final float TRACK_PADDING_V_DP = 10f;
    private static final float PLAYHEAD_WIDTH_DP = 2.5f;
    private static final float PLAYHEAD_CIRCLE_DP = 5f;
    private static final float HANDLE_NOTCH_WIDTH_DP = 2f;
    private static final float HANDLE_NOTCH_HEIGHT_DP = 14f;

    // ── Paint objects ────────────────────────────────────────────────
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activeTopBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activeBottomBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleNotchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── Computed pixel values ────────────────────────────────────────
    private float handleWidthPx;
    private float handleCornerPx;
    private float minHandleGapPx;
    private float trackCornerPx;
    private float trackPaddingVPx;
    private float playheadWidthPx;
    private float playheadCirclePx;
    private float handleNotchWidthPx;
    private float handleNotchHeightPx;

    // Track bounds
    private float trackTop;
    private float trackBottom;

    // ── State ────────────────────────────────────────────────────────
    private float trimStartFraction = 0f;
    private float trimEndFraction = 1f;
    private float playheadFraction = 0f;

    private enum DragTarget { NONE, LEFT, RIGHT, PLAYHEAD }
    private DragTarget activeDrag = DragTarget.NONE;

    @Nullable private TrimChangeListener trimListener;
    @Nullable private PlayheadChangeListener playheadListener;

    // ── Listener interfaces ──────────────────────────────────────────

    public interface TrimChangeListener {
        /**
         * Called continuously while a trim handle is being dragged.
         * @param startFraction current start trim fraction (0-1)
         * @param endFraction   current end trim fraction (0-1)
         * @param isLeftHandle  true if the left (in-point) handle is being dragged
         */
        void onTrimChanged(float startFraction, float endFraction, boolean isLeftHandle);
        void onTrimFinished(float startFraction, float endFraction);
    }

    public interface PlayheadChangeListener {
        void onPlayheadSeeked(float fraction);
        /** Called when the user lifts their finger after dragging the playhead. */
        void onPlayheadDragFinished();
    }

    // ── Constructors ─────────────────────────────────────────────────

    public TimelineView(Context context) { super(context); init(); }
    public TimelineView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }
    public TimelineView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr); init();
    }

    // ── Setup ────────────────────────────────────────────────────────

    private void init() {
        float d = getResources().getDisplayMetrics().density;
        handleWidthPx = HANDLE_WIDTH_DP * d;
        handleCornerPx = HANDLE_CORNER_DP * d;
        minHandleGapPx = MIN_HANDLE_GAP_DP * d;
        trackCornerPx = TRACK_CORNER_DP * d;
        trackPaddingVPx = TRACK_PADDING_V_DP * d;
        playheadWidthPx = PLAYHEAD_WIDTH_DP * d;
        playheadCirclePx = PLAYHEAD_CIRCLE_DP * d;
        handleNotchWidthPx = HANDLE_NOTCH_WIDTH_DP * d;
        handleNotchHeightPx = HANDLE_NOTCH_HEIGHT_DP * d;

        // Track background (subtle dark texture)
        trackPaint.setColor(0xFF1E1E1E);
        trackPaint.setStyle(Paint.Style.FILL);

        // Active region (subtle gradient will be set in onSizeChanged)
        activePaint.setColor(0xFF263228);
        activePaint.setStyle(Paint.Style.FILL);

        // Top/bottom borders of active region
        activeTopBorderPaint.setColor(0xFF4CAF50);
        activeTopBorderPaint.setStyle(Paint.Style.FILL);
        activeBottomBorderPaint.setColor(0xFF4CAF50);
        activeBottomBorderPaint.setStyle(Paint.Style.FILL);

        // Trim handles — green pill
        handlePaint.setColor(0xFF4CAF50);
        handlePaint.setStyle(Paint.Style.FILL);

        // Handle notch (grip lines)
        handleNotchPaint.setColor(0xCC1B5E20);
        handleNotchPaint.setStyle(Paint.Style.FILL);
        handleNotchPaint.setStrokeCap(Paint.Cap.ROUND);

        // Playhead line — white
        playheadPaint.setColor(0xFFFFFFFF);
        playheadPaint.setStyle(Paint.Style.FILL);

        // Playhead circle — white with slight shadow
        playheadCirclePaint.setColor(0xFFFFFFFF);
        playheadCirclePaint.setStyle(Paint.Style.FILL);
        playheadCirclePaint.setShadowLayer(3f * d, 0, 1f * d, 0x40000000);
        setLayerType(LAYER_TYPE_SOFTWARE, null); // needed for shadow

        // Dimmed excluded regions
        dimPaint.setColor(0xAA000000);
        dimPaint.setStyle(Paint.Style.FILL);
    }

    // ── Public setters ───────────────────────────────────────────────

    public void setTrimChangeListener(@Nullable TrimChangeListener l) { this.trimListener = l; }
    public void setPlayheadChangeListener(@Nullable PlayheadChangeListener l) { this.playheadListener = l; }

    public void setTrimFromClip(@NonNull Clip clip) {
        if (clip.getSourceDurationMs() <= 0) return;
        this.trimStartFraction = (float) clip.getInPointMs() / clip.getSourceDurationMs();
        this.trimEndFraction = (float) clip.getOutPointMs() / clip.getSourceDurationMs();
        invalidate();
    }

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
        trackTop = getPaddingTop() + trackPaddingVPx;
        trackBottom = h - getPaddingBottom() - trackPaddingVPx;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float usableWidth = w - 2 * handleWidthPx;
        float baseLeft = handleWidthPx;
        float trackH = trackBottom - trackTop;
        float borderThickness = 2.5f * getResources().getDisplayMetrics().density;

        // Positions for active region
        float activeLeft = baseLeft + trimStartFraction * usableWidth;
        float activeRight = baseLeft + trimEndFraction * usableWidth;

        // 1. Full track background
        RectF trackRect = new RectF(baseLeft, trackTop, baseLeft + usableWidth, trackBottom);
        canvas.drawRoundRect(trackRect, trackCornerPx, trackCornerPx, trackPaint);

        // 2. Active region fill
        RectF activeRect = new RectF(activeLeft, trackTop, activeRight, trackBottom);
        canvas.drawRect(activeRect, activePaint);

        // 3. Top and bottom green borders on active region
        RectF topBorder = new RectF(activeLeft, trackTop, activeRight, trackTop + borderThickness);
        canvas.drawRect(topBorder, activeTopBorderPaint);
        RectF bottomBorder = new RectF(activeLeft, trackBottom - borderThickness, activeRight, trackBottom);
        canvas.drawRect(bottomBorder, activeBottomBorderPaint);

        // 4. Dimmed regions outside trim
        if (trimStartFraction > 0.001f) {
            RectF leftDim = new RectF(baseLeft, trackTop, activeLeft, trackBottom);
            canvas.drawRoundRect(leftDim, trackCornerPx, trackCornerPx, dimPaint);
        }
        if (trimEndFraction < 0.999f) {
            RectF rightDim = new RectF(activeRight, trackTop, baseLeft + usableWidth, trackBottom);
            canvas.drawRoundRect(rightDim, trackCornerPx, trackCornerPx, dimPaint);
        }

        // 5. Trim handles — pill shaped, extends above/below track
        float handleExtend = 4f * getResources().getDisplayMetrics().density;
        float handleTop = trackTop - handleExtend;
        float handleBottom = trackBottom + handleExtend;

        // Left handle
        RectF leftHandle = new RectF(
                activeLeft - handleWidthPx, handleTop,
                activeLeft, handleBottom);
        canvas.drawRoundRect(leftHandle, handleCornerPx, handleCornerPx, handlePaint);
        drawHandleNotch(canvas, leftHandle);

        // Right handle
        RectF rightHandle = new RectF(
                activeRight, handleTop,
                activeRight + handleWidthPx, handleBottom);
        canvas.drawRoundRect(rightHandle, handleCornerPx, handleCornerPx, handlePaint);
        drawHandleNotch(canvas, rightHandle);

        // 6. Playhead line (only draw within active region)
        float playheadX = baseLeft + playheadFraction * usableWidth;
        if (playheadX >= activeLeft && playheadX <= activeRight) {
            float phTop = trackTop - handleExtend - playheadCirclePx;
            float phBottom = trackBottom + handleExtend;

            // Line
            RectF phLine = new RectF(
                    playheadX - playheadWidthPx / 2, phTop + playheadCirclePx,
                    playheadX + playheadWidthPx / 2, phBottom);
            canvas.drawRoundRect(phLine, playheadWidthPx / 2, playheadWidthPx / 2, playheadPaint);

            // Circle at top
            canvas.drawCircle(playheadX, phTop + playheadCirclePx, playheadCirclePx, playheadCirclePaint);
        }
    }

    /** Draw a small vertical notch (grip) in the center of a handle. */
    private void drawHandleNotch(Canvas canvas, RectF handleRect) {
        float cx = handleRect.centerX();
        float cy = handleRect.centerY();
        float halfH = handleNotchHeightPx / 2;
        RectF notch = new RectF(
                cx - handleNotchWidthPx / 2, cy - halfH,
                cx + handleNotchWidthPx / 2, cy + halfH);
        canvas.drawRoundRect(notch, handleNotchWidthPx / 2, handleNotchWidthPx / 2, handleNotchPaint);
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
                    Log.d(TAG, "Drag started: " + activeDrag);
                    return true;
                }
                // Tap on track → seek playhead (and start draggable seek)
                activeDrag = DragTarget.PLAYHEAD;
                getParent().requestDisallowInterceptTouchEvent(true);
                float fraction = (x - baseLeft) / usableWidth;
                fraction = Math.max(trimStartFraction, Math.min(fraction, trimEndFraction));
                playheadFraction = fraction;
                invalidate();
                if (playheadListener != null) {
                    playheadListener.onPlayheadSeeked(fraction);
                }
                Log.d(TAG, "Playhead seek: " + fraction);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (activeDrag == DragTarget.LEFT) {
                    float newStart = (x - baseLeft) / usableWidth;
                    float maxStart = trimEndFraction - minHandleGapPx / usableWidth;
                    trimStartFraction = Math.max(0f, Math.min(newStart, maxStart));
                    invalidate();
                    if (trimListener != null) {
                        trimListener.onTrimChanged(trimStartFraction, trimEndFraction, true);
                    }
                } else if (activeDrag == DragTarget.RIGHT) {
                    float newEnd = (x - baseLeft) / usableWidth;
                    float minEnd = trimStartFraction + minHandleGapPx / usableWidth;
                    trimEndFraction = Math.max(minEnd, Math.min(newEnd, 1f));
                    invalidate();
                    if (trimListener != null) {
                        trimListener.onTrimChanged(trimStartFraction, trimEndFraction, false);
                    }
                } else if (activeDrag == DragTarget.PLAYHEAD) {
                    float newFrac = (x - baseLeft) / usableWidth;
                    newFrac = Math.max(trimStartFraction, Math.min(newFrac, trimEndFraction));
                    playheadFraction = newFrac;
                    invalidate();
                    if (playheadListener != null) {
                        playheadListener.onPlayheadSeeked(newFrac);
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (activeDrag == DragTarget.LEFT || activeDrag == DragTarget.RIGHT) {
                    if (trimListener != null) {
                        trimListener.onTrimFinished(trimStartFraction, trimEndFraction);
                    }
                    Log.d(TAG, "Trim finished: start=" + trimStartFraction
                            + " end=" + trimEndFraction);
                } else if (activeDrag == DragTarget.PLAYHEAD) {
                    if (playheadListener != null) {
                        playheadListener.onPlayheadDragFinished();
                    }
                    Log.d(TAG, "Playhead drag finished at: " + playheadFraction);
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
        float touchSlop = handleWidthPx * 2f; // generous touch target

        if (Math.abs(x - leftHandleCenter) < touchSlop) {
            return DragTarget.LEFT;
        }
        if (Math.abs(x - rightHandleCenter) < touchSlop) {
            return DragTarget.RIGHT;
        }
        return DragTarget.NONE;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int defaultHeight = (int) (72 * getResources().getDisplayMetrics().density);
        int height = resolveSize(defaultHeight, heightMeasureSpec);
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}
