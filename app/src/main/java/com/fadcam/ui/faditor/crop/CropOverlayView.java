package com.fadcam.ui.faditor.crop;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Overlay View that renders a draggable crop rectangle on top of the video preview.
 *
 * <p>The view draws:</p>
 * <ul>
 *   <li>Semi-transparent dark scrim outside the crop region</li>
 *   <li>White border around the crop region</li>
 *   <li>Corner handles for resizing</li>
 *   <li>Rule-of-thirds grid lines inside the crop region</li>
 * </ul>
 *
 * <p>Crop bounds are reported as normalised fractions (0.0 – 1.0) relative to the video
 * content area (not the entire view). Callers must set the video content rect via
 * {@link #setVideoContentRect(RectF)} so that crop coordinates map correctly.</p>
 */
public class CropOverlayView extends View {

    private static final String TAG = "CropOverlay";

    // ── Drawing paints ───────────────────────────────────────────────
    private final Paint scrimPaint = new Paint();
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── Dimensions ───────────────────────────────────────────────────
    private static final float BORDER_WIDTH_PX = 1.5f;
    private static final float HANDLE_STROKE_PX = 4f;
    private static final float HANDLE_LENGTH_PX = 28f;
    private static final float HANDLE_TOUCH_RADIUS_PX = 40f;
    private static final float MIN_CROP_SIZE_PX = 60f;

    /** The actual video content area inside this view (accounting for letterbox). */
    private final RectF videoRect = new RectF();

    /** Current crop rectangle in pixel coordinates (within {@link #videoRect}). */
    private final RectF cropRect = new RectF();

    /** Whether the crop overlay is active / visible. */
    private boolean active = false;

    /** Whether to show grid lines during drag. */
    private boolean showGrid = false;

    /** Locked aspect ratio (width/height). 0 = free crop. */
    private float lockedAspectRatio = 0f;

    // ── Touch handling ───────────────────────────────────────────────
    private enum DragHandle {
        NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
        TOP_EDGE, BOTTOM_EDGE, LEFT_EDGE, RIGHT_EDGE, MOVE
    }

    private DragHandle activeHandle = DragHandle.NONE;
    private float touchStartX, touchStartY;
    private final RectF cropAtTouchStart = new RectF();

    // ── Callback ─────────────────────────────────────────────────────
    /** Listener for crop region changes. */
    public interface OnCropChangeListener {
        /**
         * Called when the crop region changes.
         *
         * @param left   normalised left   (0.0 – 1.0)
         * @param top    normalised top    (0.0 – 1.0)
         * @param right  normalised right  (0.0 – 1.0)
         * @param bottom normalised bottom (0.0 – 1.0)
         */
        void onCropChanged(float left, float top, float right, float bottom);
    }

    @Nullable
    private OnCropChangeListener cropChangeListener;

    // ── Constructors ─────────────────────────────────────────────────

    public CropOverlayView(Context context) {
        super(context);
        init();
    }

    public CropOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CropOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        scrimPaint.setColor(Color.argb(160, 0, 0, 0));
        scrimPaint.setStyle(Paint.Style.FILL);

        borderPaint.setColor(Color.WHITE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(BORDER_WIDTH_PX);

        handlePaint.setColor(Color.WHITE);
        handlePaint.setStyle(Paint.Style.STROKE);
        handlePaint.setStrokeWidth(HANDLE_STROKE_PX);
        handlePaint.setStrokeCap(Paint.Cap.ROUND);

        gridPaint.setColor(Color.argb(100, 255, 255, 255));
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f);
    }

    // ── Public API ───────────────────────────────────────────────────

    /**
     * Set the rectangle describing where the video content is rendered within
     * this overlay. Must be called whenever the player layout changes.
     */
    public void setVideoContentRect(@NonNull RectF rect) {
        // Use the exact video content rect — no inset.
        // Corner handles are drawn as L-brackets inside the crop area
        // so they are never clipped by the view edge.
        videoRect.set(rect);
        // Reset crop to full video area
        cropRect.set(videoRect);
        invalidate();
    }

    /**
     * Show the crop overlay and reset to full selection.
     */
    public void activate() {
        active = true;
        cropRect.set(videoRect);
        setVisibility(VISIBLE);
        invalidate();
    }

    /**
     * Show the crop overlay with specific normalised bounds.
     *
     * @param left   0.0 – 1.0
     * @param top    0.0 – 1.0
     * @param right  0.0 – 1.0
     * @param bottom 0.0 – 1.0
     */
    public void activate(float left, float top, float right, float bottom) {
        active = true;
        cropRect.set(
                videoRect.left + left * videoRect.width(),
                videoRect.top + top * videoRect.height(),
                videoRect.left + right * videoRect.width(),
                videoRect.top + bottom * videoRect.height()
        );
        setVisibility(VISIBLE);
        invalidate();
    }

    /** Hide the crop overlay. */
    public void deactivate() {
        active = false;
        showGrid = false;
        setVisibility(GONE);
        invalidate();
    }

    public boolean isActive() {
        return active;
    }

    /**
     * Lock to a specific aspect ratio (width / height). Pass 0 for free crop.
     */
    public void setLockedAspectRatio(float ratio) {
        this.lockedAspectRatio = ratio;
        if (ratio > 0 && active) {
            enforceAspectRatio();
            invalidate();
            notifyCropChanged();
        }
    }

    /** Set listener for crop region changes. */
    public void setOnCropChangeListener(@Nullable OnCropChangeListener listener) {
        this.cropChangeListener = listener;
    }

    /**
     * Get current crop bounds as normalised values (0.0 – 1.0).
     *
     * @return float[4] = {left, top, right, bottom}
     */
    @NonNull
    public float[] getNormalisedCropBounds() {
        if (videoRect.width() <= 0 || videoRect.height() <= 0) {
            return new float[]{0f, 0f, 1f, 1f};
        }
        return new float[]{
                (cropRect.left - videoRect.left) / videoRect.width(),
                (cropRect.top - videoRect.top) / videoRect.height(),
                (cropRect.right - videoRect.left) / videoRect.width(),
                (cropRect.bottom - videoRect.top) / videoRect.height()
        };
    }

    // ── Drawing ──────────────────────────────────────────────────────

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (!active || videoRect.isEmpty()) return;

        int w = getWidth();
        int h = getHeight();

        // Draw scrim (4 rectangles around the crop area)
        // Top scrim
        canvas.drawRect(0, 0, w, cropRect.top, scrimPaint);
        // Bottom scrim
        canvas.drawRect(0, cropRect.bottom, w, h, scrimPaint);
        // Left scrim
        canvas.drawRect(0, cropRect.top, cropRect.left, cropRect.bottom, scrimPaint);
        // Right scrim
        canvas.drawRect(cropRect.right, cropRect.top, w, cropRect.bottom, scrimPaint);

        // Draw crop border
        canvas.drawRect(cropRect, borderPaint);

        // Draw corner handles (L-shaped brackets — always inside the crop area)
        drawCornerBracket(canvas, cropRect.left, cropRect.top, 1, 1);       // top-left
        drawCornerBracket(canvas, cropRect.right, cropRect.top, -1, 1);     // top-right
        drawCornerBracket(canvas, cropRect.left, cropRect.bottom, 1, -1);   // bottom-left
        drawCornerBracket(canvas, cropRect.right, cropRect.bottom, -1, -1); // bottom-right

        // Draw edge midpoint handles (short dashes)
        drawEdgeDash(canvas, cropRect.centerX(), cropRect.top, true);
        drawEdgeDash(canvas, cropRect.centerX(), cropRect.bottom, true);
        drawEdgeDash(canvas, cropRect.left, cropRect.centerY(), false);
        drawEdgeDash(canvas, cropRect.right, cropRect.centerY(), false);

        // Draw grid (rule of thirds) while dragging
        if (showGrid && cropRect.width() > 10 && cropRect.height() > 10) {
            float thirdW = cropRect.width() / 3f;
            float thirdH = cropRect.height() / 3f;
            // Vertical lines
            canvas.drawLine(cropRect.left + thirdW, cropRect.top,
                    cropRect.left + thirdW, cropRect.bottom, gridPaint);
            canvas.drawLine(cropRect.left + 2 * thirdW, cropRect.top,
                    cropRect.left + 2 * thirdW, cropRect.bottom, gridPaint);
            // Horizontal lines
            canvas.drawLine(cropRect.left, cropRect.top + thirdH,
                    cropRect.right, cropRect.top + thirdH, gridPaint);
            canvas.drawLine(cropRect.left, cropRect.top + 2 * thirdH,
                    cropRect.right, cropRect.top + 2 * thirdH, gridPaint);
        }
    }

    /**
     * Draw an L-shaped corner bracket.
     *
     * @param cx    corner x coordinate (on the crop rect edge)
     * @param cy    corner y coordinate (on the crop rect edge)
     * @param dirX  +1 for left corners (bracket extends right), -1 for right corners
     * @param dirY  +1 for top corners (bracket extends down), -1 for bottom corners
     */
    private void drawCornerBracket(Canvas canvas, float cx, float cy, int dirX, int dirY) {
        // Horizontal arm of the L
        canvas.drawLine(cx, cy, cx + dirX * HANDLE_LENGTH_PX, cy, handlePaint);
        // Vertical arm of the L
        canvas.drawLine(cx, cy, cx, cy + dirY * HANDLE_LENGTH_PX, handlePaint);
    }

    /**
     * Draw a short dash at an edge midpoint.
     *
     * @param cx         center x
     * @param cy         center y
     * @param horizontal true for top/bottom edges (draws horizontal dash),
     *                   false for left/right edges (draws vertical dash)
     */
    private void drawEdgeDash(Canvas canvas, float cx, float cy, boolean horizontal) {
        float halfLen = HANDLE_LENGTH_PX * 0.4f;
        if (horizontal) {
            canvas.drawLine(cx - halfLen, cy, cx + halfLen, cy, handlePaint);
        } else {
            canvas.drawLine(cx, cy - halfLen, cx, cy + halfLen, handlePaint);
        }
    }

    // ── Touch handling ───────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!active) return false;

        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activeHandle = detectHandle(x, y);
                if (activeHandle == DragHandle.NONE) return false;
                touchStartX = x;
                touchStartY = y;
                cropAtTouchStart.set(cropRect);
                showGrid = true;
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (activeHandle == DragHandle.NONE) return false;
                float dx = x - touchStartX;
                float dy = y - touchStartY;
                applyDrag(dx, dy);
                invalidate();
                notifyCropChanged();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activeHandle = DragHandle.NONE;
                showGrid = false;
                invalidate();
                notifyCropChanged();
                return true;
        }
        return super.onTouchEvent(event);
    }

    /**
     * Detect which handle (corner, edge, or body) the touch landed on.
     */
    private DragHandle detectHandle(float x, float y) {
        float r = HANDLE_TOUCH_RADIUS_PX;

        // Corners first (highest priority)
        if (dist(x, y, cropRect.left, cropRect.top) < r) return DragHandle.TOP_LEFT;
        if (dist(x, y, cropRect.right, cropRect.top) < r) return DragHandle.TOP_RIGHT;
        if (dist(x, y, cropRect.left, cropRect.bottom) < r) return DragHandle.BOTTOM_LEFT;
        if (dist(x, y, cropRect.right, cropRect.bottom) < r) return DragHandle.BOTTOM_RIGHT;

        // Edge midpoints
        if (dist(x, y, cropRect.centerX(), cropRect.top) < r) return DragHandle.TOP_EDGE;
        if (dist(x, y, cropRect.centerX(), cropRect.bottom) < r) return DragHandle.BOTTOM_EDGE;
        if (dist(x, y, cropRect.left, cropRect.centerY()) < r) return DragHandle.LEFT_EDGE;
        if (dist(x, y, cropRect.right, cropRect.centerY()) < r) return DragHandle.RIGHT_EDGE;

        // Inside crop rect → move
        if (cropRect.contains(x, y)) return DragHandle.MOVE;

        return DragHandle.NONE;
    }

    private float dist(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Apply a drag delta to the active handle, enforcing bounds and minimum size.
     */
    private void applyDrag(float dx, float dy) {
        switch (activeHandle) {
            case TOP_LEFT:
                cropRect.left = clamp(cropAtTouchStart.left + dx,
                        videoRect.left, cropRect.right - MIN_CROP_SIZE_PX);
                cropRect.top = clamp(cropAtTouchStart.top + dy,
                        videoRect.top, cropRect.bottom - MIN_CROP_SIZE_PX);
                break;
            case TOP_RIGHT:
                cropRect.right = clamp(cropAtTouchStart.right + dx,
                        cropRect.left + MIN_CROP_SIZE_PX, videoRect.right);
                cropRect.top = clamp(cropAtTouchStart.top + dy,
                        videoRect.top, cropRect.bottom - MIN_CROP_SIZE_PX);
                break;
            case BOTTOM_LEFT:
                cropRect.left = clamp(cropAtTouchStart.left + dx,
                        videoRect.left, cropRect.right - MIN_CROP_SIZE_PX);
                cropRect.bottom = clamp(cropAtTouchStart.bottom + dy,
                        cropRect.top + MIN_CROP_SIZE_PX, videoRect.bottom);
                break;
            case BOTTOM_RIGHT:
                cropRect.right = clamp(cropAtTouchStart.right + dx,
                        cropRect.left + MIN_CROP_SIZE_PX, videoRect.right);
                cropRect.bottom = clamp(cropAtTouchStart.bottom + dy,
                        cropRect.top + MIN_CROP_SIZE_PX, videoRect.bottom);
                break;
            case TOP_EDGE:
                cropRect.top = clamp(cropAtTouchStart.top + dy,
                        videoRect.top, cropRect.bottom - MIN_CROP_SIZE_PX);
                break;
            case BOTTOM_EDGE:
                cropRect.bottom = clamp(cropAtTouchStart.bottom + dy,
                        cropRect.top + MIN_CROP_SIZE_PX, videoRect.bottom);
                break;
            case LEFT_EDGE:
                cropRect.left = clamp(cropAtTouchStart.left + dx,
                        videoRect.left, cropRect.right - MIN_CROP_SIZE_PX);
                break;
            case RIGHT_EDGE:
                cropRect.right = clamp(cropAtTouchStart.right + dx,
                        cropRect.left + MIN_CROP_SIZE_PX, videoRect.right);
                break;
            case MOVE:
                float newLeft = cropAtTouchStart.left + dx;
                float newTop = cropAtTouchStart.top + dy;
                float w = cropAtTouchStart.width();
                float h = cropAtTouchStart.height();

                // Clamp within video bounds
                newLeft = clamp(newLeft, videoRect.left, videoRect.right - w);
                newTop = clamp(newTop, videoRect.top, videoRect.bottom - h);

                cropRect.set(newLeft, newTop, newLeft + w, newTop + h);
                break;
            default:
                break;
        }

        // Enforce aspect ratio if locked
        if (lockedAspectRatio > 0 && activeHandle != DragHandle.MOVE) {
            enforceAspectRatio();
        }
    }

    /** Enforce the locked aspect ratio by adjusting height to match width. */
    private void enforceAspectRatio() {
        if (lockedAspectRatio <= 0) return;

        float currentW = cropRect.width();
        float desiredH = currentW / lockedAspectRatio;

        // Ensure it fits within video bounds
        if (cropRect.top + desiredH > videoRect.bottom) {
            desiredH = videoRect.bottom - cropRect.top;
            float desiredW = desiredH * lockedAspectRatio;
            cropRect.right = cropRect.left + desiredW;
        }
        cropRect.bottom = cropRect.top + desiredH;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(value, max));
    }

    private void notifyCropChanged() {
        if (cropChangeListener != null && videoRect.width() > 0 && videoRect.height() > 0) {
            float[] bounds = getNormalisedCropBounds();
            cropChangeListener.onCropChanged(bounds[0], bounds[1], bounds[2], bounds[3]);
        }
    }
}
