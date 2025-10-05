package com.fadcam.fadrec.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom view for drawing annotations on screen during recording.
 * Supports pen drawing, erasing, multiple colors, and stroke widths.
 */
public class AnnotationView extends View {
    private static final String TAG = "AnnotationView";
    
    // Drawing state
    private Paint drawPaint;
    private Path currentPath;
    private List<DrawingPath> paths;
    private boolean isEraser = false;
    private boolean isBlackboardMode = false;
    private Paint blackboardPaint;
    
    // Current tool settings
    private int currentColor = 0xFFFF0000; // Red default
    private float currentStrokeWidth = 8f; // Medium default
    
    // Drawing path class to store each stroke
    private static class DrawingPath {
        Path path;
        Paint paint;
        
        DrawingPath(Path path, Paint paint) {
            this.path = new Path(path);
            this.paint = new Paint(paint);
        }
    }
    
    public AnnotationView(Context context) {
        super(context);
        init();
    }
    
    public AnnotationView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    private void init() {
        paths = new ArrayList<>();
        
        // Setup drawing paint
        drawPaint = new Paint();
        drawPaint.setAntiAlias(true);
        drawPaint.setStyle(Paint.Style.STROKE);
        drawPaint.setStrokeJoin(Paint.Join.ROUND);
        drawPaint.setStrokeCap(Paint.Cap.ROUND);
        drawPaint.setColor(currentColor);
        drawPaint.setStrokeWidth(currentStrokeWidth);
        
        // Setup blackboard background paint
        blackboardPaint = new Paint();
        blackboardPaint.setColor(0xFF000000);
        blackboardPaint.setStyle(Paint.Style.FILL);
        
        currentPath = new Path();
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        // Draw blackboard background if enabled
        if (isBlackboardMode) {
            canvas.drawRect(0, 0, getWidth(), getHeight(), blackboardPaint);
        }
        
        // Draw all saved paths
        for (DrawingPath drawingPath : paths) {
            canvas.drawPath(drawingPath.path, drawingPath.paint);
        }
        
        // Draw current path being drawn
        canvas.drawPath(currentPath, drawPaint);
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                currentPath.moveTo(x, y);
                return true;
                
            case MotionEvent.ACTION_MOVE:
                currentPath.lineTo(x, y);
                invalidate();
                return true;
                
            case MotionEvent.ACTION_UP:
                // Save the current path
                paths.add(new DrawingPath(currentPath, drawPaint));
                currentPath = new Path();
                invalidate();
                return true;
        }
        
        return false;
    }
    
    /**
     * Set pen mode for drawing
     */
    public void setPenMode() {
        isEraser = false;
        drawPaint.setXfermode(null); // Remove eraser mode
        drawPaint.setColor(currentColor);
        drawPaint.setStrokeWidth(currentStrokeWidth);
    }
    
    /**
     * Set eraser mode
     */
    public void setEraserMode() {
        isEraser = true;
        // Use transparent color for eraser to clear the canvas
        drawPaint.setColor(0x00000000);
        drawPaint.setStrokeWidth(currentStrokeWidth * 2.5f); // Eraser is wider
        // Use CLEAR mode to erase pixels
        drawPaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
    }
    
    /**
     * Set drawing color
     */
    public void setColor(int color) {
        currentColor = color;
        if (!isEraser) {
            drawPaint.setColor(color);
        }
    }
    
    /**
     * Set stroke width
     * @param width 0=thin, 1=medium, 2=thick
     */
    public void setStrokeWidth(int width) {
        switch (width) {
            case 0: // Thin
                currentStrokeWidth = 4f;
                break;
            case 1: // Medium
                currentStrokeWidth = 8f;
                break;
            case 2: // Thick
                currentStrokeWidth = 16f;
                break;
        }
        
        if (isEraser) {
            drawPaint.setStrokeWidth(currentStrokeWidth * 2);
        } else {
            drawPaint.setStrokeWidth(currentStrokeWidth);
        }
    }
    
    /**
     * Clear all drawings
     */
    public void clearAll() {
        paths.clear();
        currentPath.reset();
        invalidate();
    }
    
    /**
     * Toggle blackboard mode (black background)
     */
    public void setBlackboardMode(boolean enabled) {
        isBlackboardMode = enabled;
        invalidate();
    }
    
    /**
     * Check if blackboard mode is enabled
     */
    public boolean isBlackboardMode() {
        return isBlackboardMode;
    }
}
