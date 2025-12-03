package com.fadcam.fadrec.ui.annotation;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.fadcam.fadrec.ui.annotation.objects.AnnotationObject;

/**
 * Telegram/Instagram-style selection handles overlay.
 * Shows dotted border with corner handles for scaling and top handle for rotation.
 */
public class SelectionHandlesView extends View {
    
    private static final float HANDLE_RADIUS = 20f;
    private static final float HANDLE_TOUCH_RADIUS = 40f;
    
    private Paint borderPaint;
    private Paint handlePaint;
    private Paint handleBorderPaint;
    
    private AnnotationObject selectedObject;
    private RectF objectBounds;
    
    // Handle positions
    private float leftHandleX, leftHandleY;
    private float rightHandleX, rightHandleY;
    private float topHandleX, topHandleY;
    
    // Touch state
    private enum HandleType { NONE, LEFT, RIGHT, TOP, BODY }
    private HandleType activeHandle = HandleType.NONE;
    private float lastTouchX, lastTouchY;
    private float initialDistance;
    
    // Callbacks
    public interface SelectionListener {
        void onMove(float dx, float dy);
        void onScale(float scaleFactor);
        void onRotate(float angleDelta);
        void onDeselect();
    }
    
    private SelectionListener listener;
    
    public SelectionHandlesView(Context context) {
        super(context);
        init();
    }
    
    private void init() {
        // Dotted border paint
        borderPaint = new Paint();
        borderPaint.setColor(0xFF4CAF50); // Green
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3f);
        borderPaint.setAntiAlias(true);
        borderPaint.setPathEffect(new DashPathEffect(new float[]{10, 5}, 0));
        
        // Handle fill paint
        handlePaint = new Paint();
        handlePaint.setColor(0xFFFFFFFF); // White
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setAntiAlias(true);
        
        // Handle border paint
        handleBorderPaint = new Paint();
        handleBorderPaint.setColor(0xFF4CAF50); // Green border
        handleBorderPaint.setStyle(Paint.Style.STROKE);
        handleBorderPaint.setStrokeWidth(3f);
        handleBorderPaint.setAntiAlias(true);
        
        objectBounds = new RectF();
    }
    
    public void setSelectedObject(AnnotationObject object) {
        this.selectedObject = object;
        if (object != null) {
            updateBounds();
            setVisibility(VISIBLE);
        } else {
            setVisibility(GONE);
        }
        invalidate();
    }
    
    public void setListener(SelectionListener listener) {
        this.listener = listener;
    }
    
    private void updateBounds() {
        if (selectedObject != null) {
            RectF bounds = selectedObject.getBounds();
            objectBounds.set(bounds);
            
            // Calculate handle positions
            leftHandleX = objectBounds.left;
            leftHandleY = objectBounds.centerY();
            
            rightHandleX = objectBounds.right;
            rightHandleY = objectBounds.centerY();
            
            topHandleX = objectBounds.centerX();
            topHandleY = objectBounds.top - 30; // Slightly above
        }
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (selectedObject == null) return;
        
        updateBounds(); // Update bounds every frame
        
        // Draw dotted border
        canvas.drawRect(objectBounds, borderPaint);
        
        // Draw left handle (square)
        canvas.drawCircle(leftHandleX, leftHandleY, HANDLE_RADIUS, handlePaint);
        canvas.drawCircle(leftHandleX, leftHandleY, HANDLE_RADIUS, handleBorderPaint);
        
        // Draw right handle (square)
        canvas.drawCircle(rightHandleX, rightHandleY, HANDLE_RADIUS, handlePaint);
        canvas.drawCircle(rightHandleX, rightHandleY, HANDLE_RADIUS, handleBorderPaint);
        
        // Draw top rotation handle (circle)
        canvas.drawCircle(topHandleX, topHandleY, HANDLE_RADIUS, handlePaint);
        canvas.drawCircle(topHandleX, topHandleY, HANDLE_RADIUS, handleBorderPaint);
        
        // Draw rotation indicator line
        Path rotateLine = new Path();
        rotateLine.moveTo(topHandleX, topHandleY);
        rotateLine.lineTo(objectBounds.centerX(), objectBounds.top);
        canvas.drawPath(rotateLine, borderPaint);
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // Detect which handle or body was touched
                if (distance(x, y, topHandleX, topHandleY) < HANDLE_TOUCH_RADIUS) {
                    activeHandle = HandleType.TOP;
                    lastTouchX = x;
                    lastTouchY = y;
                    return true;
                } else if (distance(x, y, leftHandleX, leftHandleY) < HANDLE_TOUCH_RADIUS) {
                    activeHandle = HandleType.LEFT;
                    initialDistance = distance(x, y, rightHandleX, rightHandleY);
                    return true;
                } else if (distance(x, y, rightHandleX, rightHandleY) < HANDLE_TOUCH_RADIUS) {
                    activeHandle = HandleType.RIGHT;
                    initialDistance = distance(x, y, leftHandleX, leftHandleY);
                    return true;
                } else if (objectBounds.contains(x, y)) {
                    activeHandle = HandleType.BODY;
                    lastTouchX = x;
                    lastTouchY = y;
                    return true;
                }
                break;
                
            case MotionEvent.ACTION_MOVE:
                if (activeHandle == HandleType.BODY) {
                    // Move object
                    float dx = x - lastTouchX;
                    float dy = y - lastTouchY;
                    if (listener != null) {
                        listener.onMove(dx, dy);
                    }
                    lastTouchX = x;
                    lastTouchY = y;
                    invalidate();
                    return true;
                    
                } else if (activeHandle == HandleType.LEFT || activeHandle == HandleType.RIGHT) {
                    // Scale object
                    float oppositeX = activeHandle == HandleType.LEFT ? rightHandleX : leftHandleX;
                    float oppositeY = activeHandle == HandleType.LEFT ? rightHandleY : leftHandleY;
                    float newDistance = distance(x, y, oppositeX, oppositeY);
                    float scaleFactor = newDistance / initialDistance;
                    
                    if (listener != null && scaleFactor > 0.5f && scaleFactor < 2.0f) {
                        listener.onScale(scaleFactor);
                    }
                    initialDistance = newDistance;
                    invalidate();
                    return true;
                    
                } else if (activeHandle == HandleType.TOP) {
                    // Rotate object (simplified - just update visual)
                    float centerX = objectBounds.centerX();
                    float centerY = objectBounds.centerY();
                    
                    float angle1 = (float) Math.atan2(lastTouchY - centerY, lastTouchX - centerX);
                    float angle2 = (float) Math.atan2(y - centerY, x - centerX);
                    float angleDelta = (float) Math.toDegrees(angle2 - angle1);
                    
                    if (listener != null) {
                        listener.onRotate(angleDelta);
                    }
                    
                    lastTouchX = x;
                    lastTouchY = y;
                    invalidate();
                    return true;
                }
                break;
                
            case MotionEvent.ACTION_UP:
                activeHandle = HandleType.NONE;
                return true;
        }
        
        return false;
    }
    
    private float distance(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
