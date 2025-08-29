package com.fadcam.ui.faditor.components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.utils.TimelineUtils;

/**
 * Interactive timeline component for video editing.
 * Provides draggable trim handles, position indicator, and visual feedback.
 * Implements requirements 2.4, 2.5, 2.6, 3.1, 3.2 from the spec.
 */
public class TimelineComponent extends View {
    
    private static final String TAG = "TimelineComponent";
    
    // Visual constants
    private static final int TIMELINE_HEIGHT_DP = 40;
    private static final int HANDLE_WIDTH_DP = 12;
    private static final int HANDLE_HEIGHT_DP = 48;
    private static final int POSITION_INDICATOR_WIDTH_DP = 2;
    private static final int TOUCH_SLOP_DP = 16;
    private static final int MIN_TRIM_DURATION_MS = 1000; // Minimum 1 second trim
    
    // Colors (will be themed)
    private static final int TIMELINE_COLOR = Color.parseColor("#E0E0E0");
    private static final int TRIM_RANGE_COLOR = Color.parseColor("#2196F3");
    private static final int HANDLE_COLOR = Color.parseColor("#1976D2");
    private static final int POSITION_COLOR = Color.parseColor("#FF5722");
    private static final int BACKGROUND_COLOR = Color.parseColor("#F5F5F5");
    
    // Paint objects
    private Paint timelinePaint;
    private Paint trimRangePaint;
    private Paint handlePaint;
    private Paint positionPaint;
    private Paint backgroundPaint;
    private Paint textPaint;
    
    // Timeline state
    private long videoDuration = 0;
    private long trimStart = 0;
    private long trimEnd = 0;
    private long currentPosition = 0;
    
    // UI measurements (in pixels)
    private int timelineHeight;
    private int handleWidth;
    private int handleHeight;
    private int positionIndicatorWidth;
    private int touchSlop;
    
    // Touch handling
    private boolean isDraggingStartHandle = false;
    private boolean isDraggingEndHandle = false;
    private boolean isDraggingPosition = false;
    private float lastTouchX = 0;
    private float startHandleX = 0;
    private float endHandleX = 0;
    
    // Listener interface
    public interface TimelineListener {
        void onTrimRangeChanged(long startMs, long endMs);
        void onPositionSeek(long positionMs);
        void onTrimHandleDragStart();
        void onTrimHandleDragEnd();
    }
    
    private TimelineListener listener;
    
    public TimelineComponent(@NonNull Context context) {
        super(context);
        init();
    }
    
    public TimelineComponent(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public TimelineComponent(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    private void init() {
        // Convert DP to pixels
        float density = getContext().getResources().getDisplayMetrics().density;
        timelineHeight = (int) (TIMELINE_HEIGHT_DP * density);
        handleWidth = (int) (HANDLE_WIDTH_DP * density);
        handleHeight = (int) (HANDLE_HEIGHT_DP * density);
        positionIndicatorWidth = (int) (POSITION_INDICATOR_WIDTH_DP * density);
        touchSlop = (int) (TOUCH_SLOP_DP * density);
        
        // Initialize paint objects
        initPaints();
        
        Log.d(TAG, "TimelineComponent initialized with density: " + density);
    }
    
    private void initPaints() {
        // Timeline background
        timelinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        timelinePaint.setColor(TIMELINE_COLOR);
        timelinePaint.setStyle(Paint.Style.FILL);
        
        // Trim range highlight
        trimRangePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trimRangePaint.setColor(TRIM_RANGE_COLOR);
        trimRangePaint.setStyle(Paint.Style.FILL);
        trimRangePaint.setAlpha(128); // Semi-transparent
        
        // Trim handles
        handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        handlePaint.setColor(HANDLE_COLOR);
        handlePaint.setStyle(Paint.Style.FILL);
        
        // Position indicator
        positionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        positionPaint.setColor(POSITION_COLOR);
        positionPaint.setStyle(Paint.Style.FILL);
        
        // Background
        backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        backgroundPaint.setColor(BACKGROUND_COLOR);
        backgroundPaint.setStyle(Paint.Style.FILL);
        
        // Text for time labels
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.BLACK);
        textPaint.setTextSize(12 * getContext().getResources().getDisplayMetrics().scaledDensity);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }
    
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = handleHeight + getPaddingTop() + getPaddingBottom();
        
        setMeasuredDimension(width, height);
        
        // Update handle positions based on current trim range
        updateHandlePositions();
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (videoDuration <= 0) {
            // No video loaded, draw placeholder
            drawPlaceholder(canvas);
            return;
        }
        
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        int height = getHeight() - getPaddingTop() - getPaddingBottom();
        int centerY = getPaddingTop() + height / 2;
        int timelineTop = centerY - timelineHeight / 2;
        int timelineBottom = centerY + timelineHeight / 2;
        
        // Draw background
        canvas.drawRect(getPaddingLeft(), getPaddingTop(), 
                       getWidth() - getPaddingRight(), getHeight() - getPaddingBottom(), 
                       backgroundPaint);
        
        // Draw timeline background
        RectF timelineRect = new RectF(getPaddingLeft(), timelineTop, 
                                      getWidth() - getPaddingRight(), timelineBottom);
        canvas.drawRoundRect(timelineRect, 4, 4, timelinePaint);
        
        // Draw trim range highlight
        if (trimEnd > trimStart) {
            float startX = getPaddingLeft() + (startHandleX - getPaddingLeft());
            float endX = getPaddingLeft() + (endHandleX - getPaddingLeft());
            
            RectF trimRect = new RectF(startX, timelineTop, endX, timelineBottom);
            canvas.drawRoundRect(trimRect, 4, 4, trimRangePaint);
        }
        
        // Draw trim handles
        drawTrimHandle(canvas, startHandleX, centerY, true);
        drawTrimHandle(canvas, endHandleX, centerY, false);
        
        // Draw current position indicator
        if (currentPosition >= 0 && currentPosition <= videoDuration) {
            float positionX = getPaddingLeft() + (float) currentPosition / videoDuration * width;
            drawPositionIndicator(canvas, positionX, centerY);
        }
        
        // Draw time labels
        drawTimeLabels(canvas, width, timelineBottom + 20);
    }
    
    private void drawPlaceholder(Canvas canvas) {
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        int height = getHeight() - getPaddingTop() - getPaddingBottom();
        int centerY = getPaddingTop() + height / 2;
        
        // Draw placeholder timeline
        RectF placeholderRect = new RectF(getPaddingLeft(), centerY - timelineHeight / 2, 
                                         getWidth() - getPaddingRight(), centerY + timelineHeight / 2);
        canvas.drawRoundRect(placeholderRect, 4, 4, timelinePaint);
        
        // Draw placeholder text
        canvas.drawText("Load video to see timeline", getWidth() / 2f, centerY, textPaint);
    }
    
    private void drawTrimHandle(Canvas canvas, float x, float centerY, boolean isStartHandle) {
        // Draw handle background
        RectF handleRect = new RectF(x - handleWidth / 2f, centerY - handleHeight / 2f,
                                    x + handleWidth / 2f, centerY + handleHeight / 2f);
        canvas.drawRoundRect(handleRect, 6, 6, handlePaint);
        
        // Draw handle grip lines
        Paint gripPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gripPaint.setColor(Color.WHITE);
        gripPaint.setStrokeWidth(2);
        
        float gripSpacing = 4;
        for (int i = -1; i <= 1; i++) {
            float lineX = x + i * gripSpacing;
            canvas.drawLine(lineX, centerY - handleHeight / 4f, 
                           lineX, centerY + handleHeight / 4f, gripPaint);
        }
        
        // Draw directional indicator
        Path arrowPath = new Path();
        float arrowSize = 6;
        if (isStartHandle) {
            // Right-pointing arrow for start handle
            arrowPath.moveTo(x - arrowSize, centerY - arrowSize / 2);
            arrowPath.lineTo(x - arrowSize / 2, centerY);
            arrowPath.lineTo(x - arrowSize, centerY + arrowSize / 2);
        } else {
            // Left-pointing arrow for end handle
            arrowPath.moveTo(x + arrowSize, centerY - arrowSize / 2);
            arrowPath.lineTo(x + arrowSize / 2, centerY);
            arrowPath.lineTo(x + arrowSize, centerY + arrowSize / 2);
        }
        
        Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arrowPaint.setColor(Color.WHITE);
        arrowPaint.setStyle(Paint.Style.STROKE);
        arrowPaint.setStrokeWidth(2);
        canvas.drawPath(arrowPath, arrowPaint);
    }
    
    private void drawPositionIndicator(Canvas canvas, float x, float centerY) {
        // Draw position line
        canvas.drawRect(x - positionIndicatorWidth / 2f, centerY - handleHeight / 2f,
                       x + positionIndicatorWidth / 2f, centerY + handleHeight / 2f,
                       positionPaint);
        
        // Draw position circle at top
        canvas.drawCircle(x, centerY - handleHeight / 2f - 8, 6, positionPaint);
    }
    
    private void drawTimeLabels(Canvas canvas, int width, float y) {
        if (videoDuration <= 0) return;
        
        // Draw start time
        String startTime = TimelineUtils.formatDuration(trimStart);
        canvas.drawText(startTime, startHandleX, y, textPaint);
        
        // Draw end time
        String endTime = TimelineUtils.formatDuration(trimEnd);
        canvas.drawText(endTime, endHandleX, y, textPaint);
        
        // Draw current position time
        if (currentPosition >= 0 && currentPosition <= videoDuration) {
            float positionX = getPaddingLeft() + (float) currentPosition / videoDuration * width;
            String positionTime = TimelineUtils.formatDuration(currentPosition);
            
            // Offset text to avoid overlap with handles
            float textX = positionX;
            if (Math.abs(positionX - startHandleX) < 40) {
                textX = startHandleX + 40;
            } else if (Math.abs(positionX - endHandleX) < 40) {
                textX = endHandleX - 40;
            }
            
            Paint positionTextPaint = new Paint(textPaint);
            positionTextPaint.setColor(POSITION_COLOR);
            canvas.drawText(positionTime, textX, y + 20, positionTextPaint);
        }
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (videoDuration <= 0) {
            return false; // No interaction when no video is loaded
        }
        
        float x = event.getX();
        float y = event.getY();
        
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                return handleTouchDown(x, y);
                
            case MotionEvent.ACTION_MOVE:
                return handleTouchMove(x, y);
                
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return handleTouchUp(x, y);
        }
        
        return false;
    }
    
    private boolean handleTouchDown(float x, float y) {
        lastTouchX = x;
        
        // Check if touching start handle
        if (Math.abs(x - startHandleX) <= touchSlop) {
            isDraggingStartHandle = true;
            if (listener != null) {
                listener.onTrimHandleDragStart();
            }
            Log.d(TAG, "Started dragging start handle");
            return true;
        }
        
        // Check if touching end handle
        if (Math.abs(x - endHandleX) <= touchSlop) {
            isDraggingEndHandle = true;
            if (listener != null) {
                listener.onTrimHandleDragStart();
            }
            Log.d(TAG, "Started dragging end handle");
            return true;
        }
        
        // Check if touching timeline for position seeking
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        if (x >= getPaddingLeft() && x <= getWidth() - getPaddingRight()) {
            isDraggingPosition = true;
            seekToPosition(x);
            Log.d(TAG, "Started position seeking");
            return true;
        }
        
        return false;
    }
    
    private boolean handleTouchMove(float x, float y) {
        if (isDraggingStartHandle) {
            updateStartHandle(x);
            return true;
        }
        
        if (isDraggingEndHandle) {
            updateEndHandle(x);
            return true;
        }
        
        if (isDraggingPosition) {
            seekToPosition(x);
            return true;
        }
        
        return false;
    }
    
    private boolean handleTouchUp(float x, float y) {
        boolean wasInteracting = isDraggingStartHandle || isDraggingEndHandle || isDraggingPosition;
        
        if (isDraggingStartHandle || isDraggingEndHandle) {
            if (listener != null) {
                listener.onTrimHandleDragEnd();
            }
        }
        
        isDraggingStartHandle = false;
        isDraggingEndHandle = false;
        isDraggingPosition = false;
        
        return wasInteracting;
    }
    
    private void updateStartHandle(float x) {
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        float clampedX = Math.max(getPaddingLeft(), Math.min(x, endHandleX - handleWidth));
        
        // Convert to time
        long newStartTime = (long) ((clampedX - getPaddingLeft()) / width * videoDuration);
        
        // Ensure minimum trim duration
        if (trimEnd - newStartTime < MIN_TRIM_DURATION_MS) {
            newStartTime = Math.max(0, trimEnd - MIN_TRIM_DURATION_MS);
        }
        
        setTrimStart(newStartTime);
    }
    
    private void updateEndHandle(float x) {
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        float clampedX = Math.max(startHandleX + handleWidth, Math.min(x, getWidth() - getPaddingRight()));
        
        // Convert to time
        long newEndTime = (long) ((clampedX - getPaddingLeft()) / width * videoDuration);
        
        // Ensure minimum trim duration
        if (newEndTime - trimStart < MIN_TRIM_DURATION_MS) {
            newEndTime = Math.min(videoDuration, trimStart + MIN_TRIM_DURATION_MS);
        }
        
        setTrimEnd(newEndTime);
    }
    
    private void seekToPosition(float x) {
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        float clampedX = Math.max(getPaddingLeft(), Math.min(x, getWidth() - getPaddingRight()));
        
        // Convert to time
        long newPosition = (long) ((clampedX - getPaddingLeft()) / width * videoDuration);
        newPosition = Math.max(0, Math.min(newPosition, videoDuration));
        
        setCurrentPosition(newPosition);
        
        if (listener != null) {
            listener.onPositionSeek(newPosition);
        }
    }
    
    private void updateHandlePositions() {
        if (videoDuration <= 0) return;
        
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        
        startHandleX = getPaddingLeft() + (float) trimStart / videoDuration * width;
        endHandleX = getPaddingLeft() + (float) trimEnd / videoDuration * width;
        
        // Ensure handles don't overlap
        if (endHandleX - startHandleX < handleWidth) {
            float center = (startHandleX + endHandleX) / 2;
            startHandleX = center - handleWidth / 2f;
            endHandleX = center + handleWidth / 2f;
        }
    }
    
    // Public API methods
    
    /**
     * Set the video duration for the timeline
     */
    public void setVideoDuration(long duration) {
        this.videoDuration = duration;
        
        // Initialize trim range to full video if not set
        if (trimEnd <= trimStart) {
            trimStart = 0;
            trimEnd = duration;
        }
        
        updateHandlePositions();
        invalidate();
        
        Log.d(TAG, "Video duration set: " + TimelineUtils.formatDuration(duration));
    }
    
    /**
     * Set the trim range (start and end times)
     */
    public void setTrimRange(long start, long end) {
        // Validate and clamp values
        start = Math.max(0, Math.min(start, videoDuration));
        end = Math.max(start + MIN_TRIM_DURATION_MS, Math.min(end, videoDuration));
        
        if (this.trimStart != start || this.trimEnd != end) {
            this.trimStart = start;
            this.trimEnd = end;
            
            updateHandlePositions();
            invalidate();
            
            if (listener != null) {
                listener.onTrimRangeChanged(trimStart, trimEnd);
            }
            
            Log.d(TAG, "Trim range set: " + TimelineUtils.formatDuration(start) + 
                      " - " + TimelineUtils.formatDuration(end));
        }
    }
    
    /**
     * Set the trim start time
     */
    public void setTrimStart(long start) {
        setTrimRange(start, trimEnd);
    }
    
    /**
     * Set the trim end time
     */
    public void setTrimEnd(long end) {
        setTrimRange(trimStart, end);
    }
    
    /**
     * Set the current playback position
     */
    public void setCurrentPosition(long position) {
        position = Math.max(0, Math.min(position, videoDuration));
        
        if (this.currentPosition != position) {
            this.currentPosition = position;
            invalidate();
        }
    }
    
    /**
     * Get the current trim range
     */
    public TrimRange getTrimRange() {
        return new TrimRange(trimStart, trimEnd);
    }
    
    /**
     * Get the trim start time
     */
    public long getTrimStart() {
        return trimStart;
    }
    
    /**
     * Get the trim end time
     */
    public long getTrimEnd() {
        return trimEnd;
    }
    
    /**
     * Get the current position
     */
    public long getCurrentPosition() {
        return currentPosition;
    }
    
    /**
     * Get the video duration
     */
    public long getVideoDuration() {
        return videoDuration;
    }
    
    /**
     * Set the timeline listener for events
     */
    public void setTimelineListener(TimelineListener listener) {
        this.listener = listener;
    }
    
    /**
     * Reset the timeline to initial state
     */
    public void reset() {
        videoDuration = 0;
        trimStart = 0;
        trimEnd = 0;
        currentPosition = 0;
        
        isDraggingStartHandle = false;
        isDraggingEndHandle = false;
        isDraggingPosition = false;
        
        invalidate();
        
        Log.d(TAG, "Timeline reset");
    }
    
    /**
     * Data class representing a trim range
     */
    public static class TrimRange {
        private final long start;
        private final long end;
        
        public TrimRange(long start, long end) {
            this.start = start;
            this.end = end;
        }
        
        public long getStart() {
            return start;
        }
        
        public long getEnd() {
            return end;
        }
        
        public long getDuration() {
            return end - start;
        }
        
        @Override
        public String toString() {
            return "TrimRange{start=" + start + "ms, end=" + end + "ms, duration=" + getDuration() + "ms}";
        }
    }
}