package com.fadcam.ui.faditor.timeline;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.Clip;

import java.util.ArrayList;
import java.util.List;

/**
 * Advanced timeline view with fixed center playhead and scrollable content.
 *
 * <p>Features: fixed white center line, pinch-to-zoom, video frame thumbnails,
 * scrollable timeline, click-to-select clips.</p>
 */
public class TimelineView extends View {

    private static final String TAG = "TimelineView";

    // Handle/layout dimensions (dp)
    private static final float HANDLE_WIDTH_DP = 12f;
    private static final float HANDLE_CORNER_DP = 6f;
    private static final float MIN_HANDLE_GAP_DP = 20f;
    private static final float TRACK_CORNER_DP = 10f;
    private static final float TRACK_PADDING_V_DP = 10f;
    private static final float PLAYHEAD_WIDTH_DP = 3f;
    private static final float PLAYHEAD_CIRCLE_DP = 6f;
    private static final float HANDLE_NOTCH_WIDTH_DP = 2f;
    private static final float HANDLE_NOTCH_HEIGHT_DP = 14f;
    private static final float RULER_HEIGHT_DP = 24f;
    
    // Zoom/scroll
    private static final float MIN_ZOOM = 1f;
    private static final float MAX_ZOOM = 10f;
    private static final float DEFAULT_PIXELS_PER_MS = 0.1f;

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
    private final Paint rulerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rulerTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbnailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

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
    private float rulerHeightPx;

    // Track bounds
    private float trackTop;
    private float trackBottom;
    private float rulerTop;

    // ── State ────────────────────────────────────────────────────────
    private float trimStartFraction = 0f;
    private float trimEndFraction = 1f;
    private long playheadPositionMs = 0; // Playhead position in milliseconds
    private long videoDurationMs = 0; // Total video duration
    
    // Scroll and zoom
    private float scrollOffsetPx = 0f; // Horizontal scroll offset
    private float zoomLevel = 1f; // Zoom level (1x to 10x)
    private float pixelsPerMs = DEFAULT_PIXELS_PER_MS; // Pixels per millisecond at current zoom
    
    // Thumbnails
    private List<Bitmap> thumbnails = new ArrayList<>();
    private long thumbnailIntervalMs = 1000; // 1 thumbnail per second
    private boolean thumbnailsLoaded = false;
    
    // Clip reference
    private Clip currentClip;

    private enum DragTarget { NONE, LEFT, RIGHT, TIMELINE }
    private DragTarget activeDrag = DragTarget.NONE;
    private float lastTouchX = 0f;
    
    // Gesture detectors
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private boolean isSelected = false;

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
        void onPlayheadSeeked(long positionMs);
        /** Called when the user lifts their finger after dragging the timeline. */
        void onPlayheadDragFinished();
    }
    
    public interface ClipSelectionListener {
        void onClipSelected();
        void onClipDeselected();
    }
    
    @Nullable private ClipSelectionListener selectionListener;

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
        rulerHeightPx = RULER_HEIGHT_DP * d;

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
        playheadPaint.setStrokeWidth(playheadWidthPx);

        // Playhead circle — white with slight shadow
        playheadCirclePaint.setColor(0xFFFFFFFF);
        playheadCirclePaint.setStyle(Paint.Style.FILL);
        playheadCirclePaint.setShadowLayer(3f * d, 0, 1f * d, 0x40000000);

        // Dimmed excluded regions
        dimPaint.setColor(0xAA000000);
        dimPaint.setStyle(Paint.Style.FILL);
        
        // Ruler
        rulerPaint.setColor(0xFF888888);
        rulerPaint.setStyle(Paint.Style.STROKE);
        rulerPaint.setStrokeWidth(1f * d);
        
        rulerTextPaint.setColor(0xFFAAAAAA);
        rulerTextPaint.setTextSize(10f * d);
        rulerTextPaint.setTextAlign(Paint.Align.CENTER);
        
        setLayerType(LAYER_TYPE_SOFTWARE, null); // needed for shadow
        
        // Initialize gesture detectors
        scaleDetector = new ScaleGestureDetector(getContext(), new ScaleListener());
        gestureDetector = new GestureDetector(getContext(), new GestureListener());
    }

    // ── Public setters ───────────────────────────────────────────────

    public void setTrimChangeListener(@Nullable TrimChangeListener l) { this.trimListener = l; }
    public void setPlayheadChangeListener(@Nullable PlayheadChangeListener l) { this.playheadListener = l; }
    public void setSelectionListener(@Nullable ClipSelectionListener l) { this.selectionListener = l; }

    public void setTrimFromClip(@NonNull Clip clip) {
        this.currentClip = clip;
        this.videoDurationMs = clip.getSourceDurationMs();
        if (this.videoDurationMs <= 0) return;
        
        this.trimStartFraction = (float) clip.getInPointMs() / clip.getSourceDurationMs();
        this.trimEndFraction = (float) clip.getOutPointMs() / clip.getSourceDurationMs();
        this.playheadPositionMs = clip.getInPointMs();
        
        // Calculate initial scroll so video starts at center line
        updatePixelsPerMs();
        centerPlayhead();
        
        // Load thumbnails in background
        if (!thumbnailsLoaded) {
            loadThumbnails(clip);
        }
        
        invalidate();
    }

    public void setPlayheadPosition(long positionMs) {
        this.playheadPositionMs = Math.max(0, Math.min(positionMs, videoDurationMs));
        
        // Auto-scroll to keep playhead centered
        if (!isUserInteracting()) {
            centerPlayhead();
        }
        
        invalidate();
    }

    public void setSelected(boolean selected) {
        if (this.isSelected != selected) {
            this.isSelected = selected;
            if (selected && selectionListener != null) {
                selectionListener.onClipSelected();
            } else if (!selected && selectionListener != null) {
                selectionListener.onClipDeselected();
            }
            invalidate();
        }
    }
    
    public boolean isSelected() {
        return isSelected;
    }
    
    private boolean isUserInteracting() {
        return activeDrag != DragTarget.NONE;
    }
    
    private void centerPlayhead() {
        float centerX = getWidth() / 2f;
        float playheadX = playheadPositionMs * pixelsPerMs;
        scrollOffsetPx = playheadX - centerX;
        clampScroll();
    }
    
    private void clampScroll() {
        float totalWidth = videoDurationMs * pixelsPerMs;
        float viewWidth = getWidth();
        
        // Allow scrolling from 0 to (totalWidth - viewWidth)
        float maxScroll = Math.max(0, totalWidth - viewWidth);
        scrollOffsetPx = Math.max(0, Math.min(scrollOffsetPx, maxScroll));
    }
    
    private void updatePixelsPerMs() {
        pixelsPerMs = DEFAULT_PIXELS_PER_MS * zoomLevel;
    }

    public float getTrimStartFraction() { return trimStartFraction; }
    public float getTrimEndFraction() { return trimEndFraction; }
    
    private void loadThumbnails(@NonNull Clip clip) {
        // Load thumbnails in background thread
        new Thread(() -> {
            try {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                retriever.setDataSource(getContext(), clip.getSourceUri());
                
                List<Bitmap> newThumbnails = new ArrayList<>();
                long duration = clip.getSourceDurationMs();
                
                for (long time = 0; time < duration; time += thumbnailIntervalMs) {
                    Bitmap frame = retriever.getFrameAtTime(
                        time * 1000, // microseconds
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    );
                    if (frame != null) {
                        // Scale down for memory efficiency
                        int targetHeight = (int)(48 * getResources().getDisplayMetrics().density);
                        float scale = (float)targetHeight / frame.getHeight();
                        int targetWidth = (int)(frame.getWidth() * scale);
                        Bitmap scaled = Bitmap.createScaledBitmap(frame, targetWidth, targetHeight, true);
                        frame.recycle();
                        newThumbnails.add(scaled);
                    }
                }
                
                retriever.release();
                
                post(() -> {
                    thumbnails = newThumbnails;
                    thumbnailsLoaded = true;
                    invalidate();
                });
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to load thumbnails", e);
            }
        }).start();
    }

    public void setPlayheadFraction(float fraction) {
        this.playheadPositionMs = (long)(fraction * videoDurationMs);
        invalidate();
    }

    // ── Drawing ──────────────────────────────────────────────────────

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rulerTop = getPaddingTop();
        trackTop = rulerTop + rulerHeightPx + trackPaddingVPx;
        trackBottom = h - getPaddingBottom() - trackPaddingVPx;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        if (videoDurationMs <= 0) return;
        
        float viewWidth = getWidth();
        float centerX = viewWidth / 2f;
        float trackH = trackBottom - trackTop;
        
        // Calculate timeline positions
        float totalWidth = videoDurationMs * pixelsPerMs;
        float startX = -scrollOffsetPx;
        
        // Calculate trim positions
        long trimStartMs = (long)(trimStartFraction * videoDurationMs);
        long trimEndMs = (long)(trimEndFraction * videoDurationMs);
        float trimStartX = startX + (trimStartMs * pixelsPerMs);
        float trimEndX = startX + (trimEndMs * pixelsPerMs);
        
        // Only draw what's visible
        canvas.save();
        canvas.clipRect(0, 0, viewWidth, getHeight());
        
        // 1. Draw ruler at top
        drawRuler(canvas, startX, viewWidth);
        
        // 2. Draw video frames/thumbnails
        drawThumbnails(canvas, startX, trackTop, trackH);
        
        // 3. Draw dimmed regions outside trim
        if (trimStartX > 0) {
            RectF leftDim = new RectF(Math.max(0, startX), trackTop, 
                Math.min(viewWidth, trimStartX), trackBottom);
            canvas.drawRect(leftDim, dimPaint);
        }
        if (trimEndX < startX + totalWidth) {
            RectF rightDim = new RectF(Math.max(0, trimEndX), trackTop, 
                Math.min(viewWidth, startX + totalWidth), trackBottom);
            canvas.drawRect(rightDim, dimPaint);
        }
        
        // 4. Draw selection border if selected
        if (isSelected) {
            float borderThickness = 3f * getResources().getDisplayMetrics().density;
            RectF topBorder = new RectF(Math.max(0, trimStartX), trackTop, 
                Math.min(viewWidth, trimEndX), trackTop + borderThickness);
            canvas.drawRect(topBorder, activeTopBorderPaint);
            RectF bottomBorder = new RectF(Math.max(0, trimStartX), trackBottom - borderThickness, 
                Math.min(viewWidth, trimEndX), trackBottom);
            canvas.drawRect(bottomBorder, activeBottomBorderPaint);
        }
        
        // 5. Draw trim handles if selected
        if (isSelected) {
            float handleExtend = 6f * getResources().getDisplayMetrics().density;
            float handleTop = trackTop - handleExtend;
            float handleBottom = trackBottom + handleExtend;

            // Left handle (only if visible)
            if (trimStartX >= -handleWidthPx && trimStartX <= viewWidth) {
                RectF leftHandle = new RectF(
                    trimStartX - handleWidthPx, handleTop,
                    trimStartX, handleBottom);
                canvas.drawRoundRect(leftHandle, handleCornerPx, handleCornerPx, handlePaint);
                drawHandleNotch(canvas, leftHandle);
            }

            // Right handle (only if visible)
            if (trimEndX >= 0 && trimEndX <= viewWidth + handleWidthPx) {
                RectF rightHandle = new RectF(
                    trimEndX, handleTop,
                    trimEndX + handleWidthPx, handleBottom);
                canvas.drawRoundRect(rightHandle, handleCornerPx, handleCornerPx, handlePaint);
                drawHandleNotch(canvas, rightHandle);
            }
        }
        
        // 6. Draw fixed center playhead line (ALWAYS visible)
        float playheadTop = rulerTop + rulerHeightPx;
        float playheadBottom = trackBottom;
        
        // White line
        canvas.drawRect(centerX - playheadWidthPx / 2, playheadTop, 
            centerX + playheadWidthPx / 2, playheadBottom, playheadPaint);
        
        // Circle at top
        canvas.drawCircle(centerX, playheadTop + playheadCirclePx, 
            playheadCirclePx, playheadCirclePaint);
        
        // Triangle at bottom
        Path triangle = new Path();
        float triangleSize = playheadCirclePx;
        triangle.moveTo(centerX, playheadBottom);
        triangle.lineTo(centerX - triangleSize, playheadBottom - triangleSize * 1.5f);
        triangle.lineTo(centerX + triangleSize, playheadBottom - triangleSize * 1.5f);
        triangle.close();
        canvas.drawPath(triangle, playheadCirclePaint);
        
        canvas.restore();
    }
    
    private void drawRuler(Canvas canvas, float startX, float viewWidth) {
        // Draw time ruler at top
        long startMs = (long)Math.max(0, scrollOffsetPx / pixelsPerMs);
        long endMs = (long)Math.min(videoDurationMs, (scrollOffsetPx + viewWidth) / pixelsPerMs);
        
        // Determine tick interval based on zoom level
        long tickIntervalMs = 1000; // 1 second default
        if (zoomLevel < 2) tickIntervalMs = 5000; // 5 seconds
        else if (zoomLevel > 5) tickIntervalMs = 100; // 100ms
        
        long firstTick = (startMs / tickIntervalMs) * tickIntervalMs;
        
        for (long time = firstTick; time <= endMs; time += tickIntervalMs) {
            float x = startX + (time * pixelsPerMs);
            if (x < 0 || x > viewWidth) continue;
            
            // Draw tick mark
            float tickHeight = (time % (tickIntervalMs * 5) == 0) ? 12f : 6f;
            tickHeight *= getResources().getDisplayMetrics().density;
            canvas.drawLine(x, rulerTop + rulerHeightPx - tickHeight, 
                x, rulerTop + rulerHeightPx, rulerPaint);
            
            // Draw time label for major ticks
            if (time % (tickIntervalMs * 5) == 0) {
                String label = formatTime(time);
                canvas.drawText(label, x, rulerTop + rulerHeightPx - tickHeight - 4, rulerTextPaint);
            }
        }
        
        // Draw ruler baseline
        canvas.drawLine(0, rulerTop + rulerHeightPx, viewWidth, rulerTop + rulerHeightPx, rulerPaint);
    }
    
    private void drawThumbnails(Canvas canvas, float startX, float top, float height) {
        if (!thumbnailsLoaded || thumbnails.isEmpty()) {
            // Draw solid color background
            RectF track = new RectF(Math.max(0, startX), top, 
                Math.min(getWidth(), startX + videoDurationMs * pixelsPerMs), top + height);
            canvas.drawRoundRect(track, trackCornerPx, trackCornerPx, trackPaint);
            return;
        }
        
        // Draw thumbnails
        for (int i = 0; i < thumbnails.size(); i++) {
            long time = i * thumbnailIntervalMs;
            float x = startX + (time * pixelsPerMs);
            
            Bitmap thumb = thumbnails.get(i);
            float thumbWidth = thumb.getWidth();
            
            // Only draw visible thumbnails
            if (x + thumbWidth < 0 || x > getWidth()) continue;
            
            RectF dest = new RectF(x, top, x + thumbWidth, top + height);
            canvas.drawBitmap(thumb, null, dest, thumbnailPaint);
        }
    }
    
    private String formatTime(long ms) {
        long sec = ms / 1000;
        long min = sec / 60;
        sec = sec % 60;
        
        if (min > 0) {
            return String.format("%d:%02d", min, sec);
        } else {
            return String.format("%ds", sec);
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
        // Handle pinch-to-zoom
        boolean scaledEvent = scaleDetector.onTouchEvent(event);
        
        // Handle gestures (tap, scroll)
        boolean gestureEvent = gestureDetector.onTouchEvent(event);
        
        float x = event.getX();
        
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = x;
                
                // Check if touching handles (only if selected)
                if (isSelected) {
                    activeDrag = detectHandleTouch(x);
                    if (activeDrag != DragTarget.NONE) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                }
                
                // Check if touching the clip to select/deselect
                float centerX = getWidth() / 2f;
                float playheadX = -scrollOffsetPx + (playheadPositionMs * pixelsPerMs);
                if (Math.abs(x - centerX) < 50 * getResources().getDisplayMetrics().density) {
                    // Tapped near center - toggle selection
                    setSelected(!isSelected);
                    return true;
                }
                
                return false;

            case MotionEvent.ACTION_MOVE:
                if (activeDrag == DragTarget.LEFT) {
                    // Dragging left trim handle
                    float deltaX = x - lastTouchX;
                    long trimStartMs = (long)(trimStartFraction * videoDurationMs);
                    trimStartMs += (long)(deltaX / pixelsPerMs);
                    
                    long minStart = 0;
                    long maxStart = (long)(trimEndFraction * videoDurationMs) - (long)(minHandleGapPx / pixelsPerMs);
                    trimStartMs = Math.max(minStart, Math.min(trimStartMs, maxStart));
                    
                    trimStartFraction = (float)trimStartMs / videoDurationMs;
                    lastTouchX = x;
                    invalidate();
                    
                    if (trimListener != null) {
                        trimListener.onTrimChanged(trimStartFraction, trimEndFraction, true);
                    }
                    return true;
                    
                } else if (activeDrag == DragTarget.RIGHT) {
                    // Dragging right trim handle
                    float deltaX = x - lastTouchX;
                    long trimEndMs = (long)(trimEndFraction * videoDurationMs);
                    trimEndMs += (long)(deltaX / pixelsPerMs);
                    
                    long minEnd = (long)(trimStartFraction * videoDurationMs) + (long)(minHandleGapPx / pixelsPerMs);
                    long maxEnd = videoDurationMs;
                    trimEndMs = Math.max(minEnd, Math.min(trimEndMs, maxEnd));
                    
                    trimEndFraction = (float)trimEndMs / videoDurationMs;
                    lastTouchX = x;
                    invalidate();
                    
                    if (trimListener != null) {
                        trimListener.onTrimChanged(trimStartFraction, trimEndFraction, false);
                    }
                    return true;
                }
                return false;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (activeDrag == DragTarget.LEFT || activeDrag == DragTarget.RIGHT) {
                    if (trimListener != null) {
                        trimListener.onTrimFinished(trimStartFraction, trimEndFraction);
                    }
                    Log.d(TAG, "Trim finished: start=" + trimStartFraction
                            + " end=" + trimEndFraction);
                } else if (activeDrag == DragTarget.TIMELINE) {
                    if (playheadListener != null) {
                        playheadListener.onPlayheadDragFinished();
                    }
                }
                activeDrag = DragTarget.NONE;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
        }

        return scaledEvent || gestureEvent || super.onTouchEvent(event);
    }

    private DragTarget detectHandleTouch(float x) {
        if (!isSelected || videoDurationMs <= 0) return DragTarget.NONE;
        
        float startX = -scrollOffsetPx;
        long trimStartMs = (long)(trimStartFraction * videoDurationMs);
        long trimEndMs = (long)(trimEndFraction * videoDurationMs);
        float trimStartX = startX + (trimStartMs * pixelsPerMs);
        float trimEndX = startX + (trimEndMs * pixelsPerMs);
        
        float touchSlop = handleWidthPx * 3f; // generous touch target

        if (Math.abs(x - trimStartX) < touchSlop) {
            return DragTarget.LEFT;
        }
        if (Math.abs(x - trimEndX) < touchSlop) {
            return DragTarget.RIGHT;
        }
        return DragTarget.NONE;
    }
    
    // ── Gesture listeners ────────────────────────────────────────────
    
    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        private float initialZoom;
        
        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            initialZoom = zoomLevel;
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float scaleFactor = detector.getScaleFactor();
            zoomLevel = initialZoom * scaleFactor;
            zoomLevel = Math.max(MIN_ZOOM, Math.min(zoomLevel, MAX_ZOOM));
            
            updatePixelsPerMs();
            
            // Adjust scroll to keep content under focus point
            float focusX = detector.getFocusX();
            float oldContentX = scrollOffsetPx + focusX;
            float timeAtFocus = oldContentX / (DEFAULT_PIXELS_PER_MS * initialZoom);
            float newContentX = timeAtFocus * pixelsPerMs;
            scrollOffsetPx = newContentX - focusX;
            
            clampScroll();
            invalidate();
            return true;
        }
        
        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            getParent().requestDisallowInterceptTouchEvent(false);
        }
    }
    
    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }
        
        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            // Only scroll if not dragging handles
            if (activeDrag == DragTarget.NONE) {
                activeDrag = DragTarget.TIMELINE;
                scrollOffsetPx += distanceX;
                clampScroll();
                
                // Update playhead position based on center
                float centerX = getWidth() / 2f;
                playheadPositionMs = (long)((scrollOffsetPx + centerX) / pixelsPerMs);
                playheadPositionMs = Math.max(0, Math.min(playheadPositionMs, videoDurationMs));
                
                if (playheadListener != null) {
                    playheadListener.onPlayheadSeeked(playheadPositionMs);
                }
                
                invalidate();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            return false;
        }
        
        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            // Toggle selection on tap
            setSelected(!isSelected);
            return true;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int defaultHeight = (int) ((RULER_HEIGHT_DP + 48 + TRACK_PADDING_V_DP * 2) 
            * getResources().getDisplayMetrics().density);
        int height = resolveSize(defaultHeight, heightMeasureSpec);
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // Clean up thumbnails
        for (Bitmap bitmap : thumbnails) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
        thumbnails.clear();
    }
}
