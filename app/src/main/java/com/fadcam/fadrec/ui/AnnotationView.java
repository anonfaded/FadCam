package com.fadcam.fadrec.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import com.fadcam.fadrec.ui.annotation.AddPathCommand;
import com.fadcam.fadrec.ui.annotation.AnnotationLayer;
import com.fadcam.fadrec.ui.annotation.AnnotationPage;
import com.fadcam.fadrec.ui.annotation.AnnotationState;
import com.fadcam.fadrec.ui.annotation.ClearLayerCommand;
import com.fadcam.fadrec.ui.annotation.DrawingPath;
import com.fadcam.fadrec.ui.annotation.objects.AnnotationObject;
import com.fadcam.fadrec.ui.annotation.objects.PathObject;

/**
 * Custom view for drawing annotations on screen during recording.
 * Supports layers, pages, undo/redo, and state persistence.
 */
public class AnnotationView extends View {
    private static final String TAG = "AnnotationView";
    private static final long LONG_PRESS_TIMEOUT = 500; // ms
    
    // State management
    private AnnotationState state;
    
    // Drawing state
    private Paint drawPaint;
    private Path currentPath;
    private Paint blackboardPaint;
    
    // Selection mode state
    private boolean selectionMode = false;
    private AnnotationObject selectedObject = null;
    private float lastTouchX;
    private float lastTouchY;
    private float lastDrawX; // Last point for path drawing
    private float lastDrawY;
    private Paint selectionPaint;
    private boolean wasInSelectionMode = false; // Track if we auto-entered selection
    
    // Handle dragging
    private enum HandleType { NONE, LEFT, RIGHT, TOP, EDIT }
    private HandleType activeHandle = HandleType.NONE;
    private float initialScale = 1.0f;
    private float initialRotation = 0f;
    
    // Snap-to-angle configuration
    private static final float[] SNAP_ANGLES = {0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f};
    private static final float SNAP_THRESHOLD = 5f; // Degrees within which to snap
    private float snappedAngle = -1f; // -1 means no snap active
    
    // Long-press detection
    private Handler longPressHandler;
    private Runnable longPressRunnable;
    private boolean isLongPressing = false;
    
    // Callback for state changes
    private OnStateChangeListener stateChangeListener;
    private OnSelectionModeChangeListener selectionModeChangeListener;
    private OnTextEditRequestListener textEditRequestListener;
    
    public interface OnStateChangeListener {
        void onStateChanged();
    }
    
    public interface OnSelectionModeChangeListener {
        void onSelectionModeChanged(boolean isActive);
    }
    
    public interface OnTextEditRequestListener {
        void onTextEditRequested(com.fadcam.fadrec.ui.annotation.objects.TextObject textObject);
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
        state = new AnnotationState();
        
        // Setup drawing paint
        drawPaint = new Paint();
        drawPaint.setAntiAlias(true);
        drawPaint.setStyle(Paint.Style.STROKE);
        drawPaint.setStrokeJoin(Paint.Join.ROUND);
        drawPaint.setStrokeCap(Paint.Cap.ROUND);
        drawPaint.setColor(state.getCurrentColor());
        drawPaint.setStrokeWidth(state.getCurrentStrokeWidth());
        
        // Setup blackboard background paint
        blackboardPaint = new Paint();
        blackboardPaint.setColor(0xFF000000);
        blackboardPaint.setStyle(Paint.Style.FILL);
        
        // Setup selection paint (dotted border)
        selectionPaint = new Paint();
        selectionPaint.setColor(0xFF4CAF50); // Green
        selectionPaint.setStyle(Paint.Style.STROKE);
        selectionPaint.setStrokeWidth(4f);
        selectionPaint.setAntiAlias(true);
        selectionPaint.setPathEffect(new DashPathEffect(new float[]{10, 5}, 0)); // Dotted
        
        // Setup long-press handler
        longPressHandler = new Handler(Looper.getMainLooper());
        
        currentPath = new Path();
    }
    
    public void setState(AnnotationState state) {
        this.state = state;
        updatePaintFromState();
        invalidate();
    }
    
    public AnnotationState getState() {
        return state;
    }
    
    public void setOnStateChangeListener(OnStateChangeListener listener) {
        this.stateChangeListener = listener;
    }
    
    public void setOnSelectionModeChangeListener(OnSelectionModeChangeListener listener) {
        this.selectionModeChangeListener = listener;
    }
    
    public void setOnTextEditRequestListener(OnTextEditRequestListener listener) {
        this.textEditRequestListener = listener;
    }
    
    private void notifyStateChanged() {
        if (stateChangeListener != null) {
            stateChangeListener.onStateChanged();
        }
    }
    
    private void notifySelectionModeChanged(boolean isActive) {
        if (selectionModeChangeListener != null) {
            selectionModeChangeListener.onSelectionModeChanged(isActive);
        }
    }
    
    private void showTextEditDialog(com.fadcam.fadrec.ui.annotation.objects.TextObject textObject) {
        if (textEditRequestListener != null) {
            textEditRequestListener.onTextEditRequested(textObject);
        }
    }
    
    private void updatePaintFromState() {
        if (state.isEraserMode()) {
            drawPaint.setColor(0x00000000);
            drawPaint.setStrokeWidth(state.getCurrentStrokeWidth() * 2.5f);
            drawPaint.setXfermode(new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.CLEAR));
        } else {
            drawPaint.setXfermode(null);
            drawPaint.setColor(state.getCurrentColor());
            drawPaint.setStrokeWidth(state.getCurrentStrokeWidth());
        }
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        AnnotationPage currentPage = state.getActivePage();
        if (currentPage == null) return;
        
        // Draw blackboard background if enabled
        if (currentPage.isBlackboardMode()) {
            canvas.drawRect(0, 0, getWidth(), getHeight(), blackboardPaint);
        }
        
        // Draw all layers (bottom to top)
        for (AnnotationLayer layer : currentPage.getLayers()) {
            if (!layer.isVisible()) continue;
            
            // Create identity matrix (no transformation yet)
            Matrix transform = new Matrix();
            
            // Draw all objects in this layer
            for (AnnotationObject obj : layer.getObjects()) {
                if (obj.isVisible()) {
                    // Apply layer opacity
                    float originalOpacity = obj.getOpacity();
                    obj.setOpacity(originalOpacity * layer.getOpacity());
                    obj.draw(canvas, transform);
                    obj.setOpacity(originalOpacity); // Restore original
                    
                    // Draw selection highlight if this object is selected
                    if ((selectionMode || isLongPressing) && selectedObject == obj) {
                        // Draw snap line if snapping is active during rotation
                        if (activeHandle == HandleType.TOP && snappedAngle >= 0) {
                            drawSnapLine(canvas, obj, snappedAngle);
                        }
                        drawSelectionHandles(canvas, obj);
                    }
                }
            }
        }
        
        // Draw current path being drawn (only in draw mode and not long-pressing)
        if (!selectionMode && !isLongPressing) {
            canvas.drawPath(currentPath, drawPaint);
        }
    }
    
    /**
     * Draw snap guide line when rotating near snap angles
     */
    private void drawSnapLine(Canvas canvas, AnnotationObject obj, float angle) {
        RectF bounds = obj.getBounds();
        float centerX = bounds.centerX();
        float centerY = bounds.centerY();
        
        Paint snapLinePaint = new Paint();
        snapLinePaint.setColor(0xFF4CAF50); // Green
        snapLinePaint.setStrokeWidth(2f);
        snapLinePaint.setAlpha(180); // Semi-transparent
        snapLinePaint.setAntiAlias(true);
        
        // Draw a line through the center at the snap angle
        float length = Math.max(getWidth(), getHeight());
        float radians = (float) Math.toRadians(angle);
        
        float dx = (float) Math.cos(radians) * length;
        float dy = (float) Math.sin(radians) * length;
        
        canvas.drawLine(
            centerX - dx, centerY - dy,
            centerX + dx, centerY + dy,
            snapLinePaint
        );
    }
    
    /**
     * Draw Telegram/Instagram-style selection handles with dotted border
     * Border rotates and scales with the object
     */
    private void drawSelectionHandles(Canvas canvas, AnnotationObject obj) {
        RectF bounds = obj.getBounds();
        float centerX = bounds.centerX();
        float centerY = bounds.centerY();
        
        canvas.save();
        
        // Apply same transformations as the object
        canvas.rotate(obj.getRotation(), centerX, centerY);
        canvas.scale(obj.getScale(), obj.getScale(), centerX, centerY);
        
        // Calculate dynamic dash pattern based on perimeter and scale
        float perimeter = 2 * (bounds.width() + bounds.height());
        float scaledPerimeter = perimeter * obj.getScale();
        float dashLength = scaledPerimeter / 40f; // ~40 dashes around perimeter
        float gapLength = dashLength * 0.5f;
        
        // Update selection paint with dynamic dash pattern
        Paint dynamicSelectionPaint = new Paint(selectionPaint);
        dynamicSelectionPaint.setPathEffect(new DashPathEffect(new float[]{dashLength, gapLength}, 0));
        
        // Draw dotted border with rounded corners
        float cornerRadius = 8f;
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, dynamicSelectionPaint);
        
        // Handle paint (white circle with green border)
        Paint handlePaint = new Paint();
        handlePaint.setColor(0xFFFFFFFF);
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setAntiAlias(true);
        
        Paint handleBorderPaint = new Paint();
        handleBorderPaint.setColor(0xFF4CAF50);
        handleBorderPaint.setStyle(Paint.Style.STROKE);
        handleBorderPaint.setStrokeWidth(3f);
        handleBorderPaint.setAntiAlias(true);
        
        float handleRadius = 12f;
        
        // Left handle (for scaling)
        float leftX = bounds.left;
        float leftY = bounds.centerY();
        canvas.drawCircle(leftX, leftY, handleRadius, handlePaint);
        canvas.drawCircle(leftX, leftY, handleRadius, handleBorderPaint);
        
        // Right handle (for scaling)
        float rightX = bounds.right;
        float rightY = bounds.centerY();
        canvas.drawCircle(rightX, rightY, handleRadius, handlePaint);
        canvas.drawCircle(rightX, rightY, handleRadius, handleBorderPaint);
        
        // Top rotation handle (slightly above)
        float topX = bounds.centerX();
        float topY = bounds.top - 30;
        
        // Draw line from top of box to rotation handle
        canvas.drawLine(bounds.centerX(), bounds.top, topX, topY, selectionPaint);
        
        // Draw rotation handle
        canvas.drawCircle(topX, topY, handleRadius, handlePaint);
        canvas.drawCircle(topX, topY, handleRadius, handleBorderPaint);
        
        // If it's a text object, add an "Edit" button at bottom
        if (obj instanceof com.fadcam.fadrec.ui.annotation.objects.TextObject) {
            canvas.restore(); // Restore to remove scale transform
            canvas.save(); // Save again for just rotation
            canvas.rotate(obj.getRotation(), centerX, centerY);
            
            float editX = bounds.centerX();
            float editY = bounds.bottom * obj.getScale() + (bounds.centerY() * (1 - obj.getScale())) + 30;
            
            // Draw edit button with consistent size (same as other handles)
            canvas.drawCircle(editX, editY, handleRadius, handlePaint);
            canvas.drawCircle(editX, editY, handleRadius, handleBorderPaint);
            
            // Draw edit emoji ✏️
            Paint textPaint = new Paint();
            textPaint.setColor(0xFF4CAF50);
            textPaint.setTextSize(handleRadius * 1.4f);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setAntiAlias(true);
            canvas.drawText("✏️", editX, editY + handleRadius * 0.5f, textPaint);
        }
        
        canvas.restore();
    }
    
    /**
     * Transform a point by rotation and scale around a center point
     */
    private float[] transformPoint(float px, float py, float centerX, float centerY, float rotation, float scale) {
        // Translate to origin
        float tx = px - centerX;
        float ty = py - centerY;
        
        // Apply scale
        tx *= scale;
        ty *= scale;
        
        // Apply rotation
        float radians = (float) Math.toRadians(rotation);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        float rx = tx * cos - ty * sin;
        float ry = tx * sin + ty * cos;
        
        // Translate back
        return new float[]{rx + centerX, ry + centerY};
    }
    
    /**
     * Check if a point is inside a rotated/scaled object
     * Use inverse transformation to test in object's local space
     */
    private boolean containsPoint(AnnotationObject obj, float px, float py) {
        RectF bounds = obj.getBounds();
        float centerX = bounds.centerX();
        float centerY = bounds.centerY();
        float rotation = obj.getRotation();
        float scale = obj.getScale();
        
        // Transform touch point to object's local space (inverse transformation)
        // Translate to origin
        float tx = px - centerX;
        float ty = py - centerY;
        
        // Inverse rotation
        float radians = (float) Math.toRadians(-rotation);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        float rx = tx * cos - ty * sin;
        float ry = tx * sin + ty * cos;
        
        // Inverse scale
        if (scale != 0) {
            rx /= scale;
            ry /= scale;
        }
        
        // Translate back
        float localX = rx + centerX;
        float localY = ry + centerY;
        
        // Test in original bounds
        return bounds.contains(localX, localY);
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        AnnotationPage currentPage = state.getActivePage();
        if (currentPage == null) return false;
        
        AnnotationLayer currentLayer = currentPage.getActiveLayer();
        if (currentLayer == null || currentLayer.isLocked()) return false;
        
        float x = event.getX();
        float y = event.getY();
        
        // Handle selection mode (tap to select, drag to move)
        if (selectionMode) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchX = x;
                    lastTouchY = y;
                    activeHandle = HandleType.NONE;
                    
                    // If we have a selected object, check if clicking on it or its handles
                    if (selectedObject != null) {
                        RectF bounds = selectedObject.getBounds();
                        float handleRadius = 40f; // Touch radius
                        float centerX = bounds.centerX();
                        float centerY = bounds.centerY();
                        float rotation = selectedObject.getRotation();
                        float scale = selectedObject.getScale();
                        
                        // Calculate transformed handle positions (after rotation and scale)
                        
                        // Left handle
                        float[] leftPos = transformPoint(bounds.left, bounds.centerY(), centerX, centerY, rotation, scale);
                        float distLeft = (float) Math.sqrt((x - leftPos[0]) * (x - leftPos[0]) + (y - leftPos[1]) * (y - leftPos[1]));
                        
                        // Right handle
                        float[] rightPos = transformPoint(bounds.right, bounds.centerY(), centerX, centerY, rotation, scale);
                        float distRight = (float) Math.sqrt((x - rightPos[0]) * (x - rightPos[0]) + (y - rightPos[1]) * (y - rightPos[1]));
                        
                        // Top rotation handle
                        float[] topPos = transformPoint(bounds.centerX(), bounds.top - 30, centerX, centerY, rotation, scale);
                        float distTop = (float) Math.sqrt((x - topPos[0]) * (x - topPos[0]) + (y - topPos[1]) * (y - topPos[1]));
                        
                        // Edit button for text (bottom)
                        float distEdit = Float.MAX_VALUE;
                        if (selectedObject instanceof com.fadcam.fadrec.ui.annotation.objects.TextObject) {
                            float[] editPos = transformPoint(bounds.centerX(), bounds.bottom + 30, centerX, centerY, rotation, scale);
                            distEdit = (float) Math.sqrt((x - editPos[0]) * (x - editPos[0]) + (y - editPos[1]) * (y - editPos[1]));
                        }
                        
                        // Check handles in priority order: edit first (more specific), then rotation, then scale
                        if (distEdit < handleRadius) {
                            // Edit button clicked
                            activeHandle = HandleType.EDIT;
                            showTextEditDialog((com.fadcam.fadrec.ui.annotation.objects.TextObject) selectedObject);
                            return true;
                        } else if (distTop < handleRadius) {
                            // Rotation handle
                            activeHandle = HandleType.TOP;
                            initialRotation = selectedObject.getRotation();
                            return true;
                        } else if (distLeft < handleRadius) {
                            // Left scale handle
                            activeHandle = HandleType.LEFT;
                            return true;
                        } else if (distRight < handleRadius) {
                            // Right scale handle
                            activeHandle = HandleType.RIGHT;
                            return true;
                        } else if (containsPoint(selectedObject, x, y)) {
                            // Clicked inside object - start drag
                            activeHandle = HandleType.NONE;
                            return true;
                        } else {
                            // Clicked outside - try to select another object or exit selection mode
                            AnnotationObject newSelection = findObjectAtPoint(x, y, currentPage);
                            if (newSelection != null) {
                                selectedObject = newSelection;
                            } else {
                                // Clicked on empty space - exit selection mode
                                selectedObject = null;
                                selectionMode = false;
                                notifySelectionModeChanged(false);
                            }
                            activeHandle = HandleType.NONE;
                            invalidate();
                            return true;
                        }
                    } else {
                        // No selection - try to find and select an object or exit selection mode
                        selectedObject = findObjectAtPoint(x, y, currentPage);
                        if (selectedObject == null) {
                            // No object found - exit selection mode
                            selectionMode = false;
                            notifySelectionModeChanged(false);
                        }
                        invalidate();
                        return true;
                    }
                    
                case MotionEvent.ACTION_MOVE:
                    if (selectedObject != null) {
                        RectF bounds = selectedObject.getBounds();
                        float dx = x - lastTouchX;
                        float dy = y - lastTouchY;
                        
                        switch (activeHandle) {
                            case TOP:
                                // Rotate around center with snap-to-angle
                                float centerX = bounds.centerX();
                                float centerY = bounds.centerY();
                                
                                // Calculate angle from center to current touch point
                                float angle1 = (float) Math.toDegrees(Math.atan2(lastTouchY - centerY, lastTouchX - centerX));
                                float angle2 = (float) Math.toDegrees(Math.atan2(y - centerY, x - centerX));
                                float angleDelta = angle2 - angle1;
                                
                                float newRotation = selectedObject.getRotation() + angleDelta;
                                
                                // Normalize to 0-360
                                while (newRotation < 0) newRotation += 360;
                                while (newRotation >= 360) newRotation -= 360;
                                
                                // Check for snap angles
                                snappedAngle = -1f;
                                for (float snapAngle : SNAP_ANGLES) {
                                    float diff = Math.abs(newRotation - snapAngle);
                                    if (diff <= SNAP_THRESHOLD) {
                                        newRotation = snapAngle;
                                        snappedAngle = snapAngle;
                                        break;
                                    }
                                }
                                
                                selectedObject.setRotation(newRotation);
                                break;
                                
                            case LEFT:
                                // Scale by dragging left handle (inverse direction)
                                float scaleLeftDelta = -dx / 100f; // Negative for left handle
                                float newScaleLeft = selectedObject.getScale() + scaleLeftDelta;
                                if (newScaleLeft > 0.1f && newScaleLeft < 5.0f) {
                                    selectedObject.setScale(newScaleLeft);
                                }
                                break;
                                
                            case RIGHT:
                                // Scale by dragging right handle (normal direction)
                                float scaleRightDelta = dx / 100f;
                                float newScaleRight = selectedObject.getScale() + scaleRightDelta;
                                if (newScaleRight > 0.1f && newScaleRight < 5.0f) {
                                    selectedObject.setScale(newScaleRight);
                                }
                                break;
                                
                            case NONE:
                                // Normal drag - move object
                                selectedObject.translate(dx, dy);
                                break;
                        }
                        
                        lastTouchX = x;
                        lastTouchY = y;
                        invalidate();
                    }
                    return true;
                    
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    activeHandle = HandleType.NONE;
                    snappedAngle = -1f; // Clear snap indication
                    notifyStateChanged();
                    return true;
            }
            return false;
        }
        
        // Handle draw mode with single-tap selection and long-press quick-drag
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // Check if touching an object
                AnnotationObject touchedObject = findObjectAtPoint(x, y, currentPage);
                
                if (touchedObject != null) {
                    // Object touched - start long-press timer for quick drag
                    isLongPressing = false;
                    lastTouchX = x;
                    lastTouchY = y;
                    
                    longPressRunnable = () -> {
                        // Long press detected! Enable quick drag (no selection mode)
                        isLongPressing = true;
                        selectedObject = touchedObject;
                        invalidate();
                    };
                    
                    longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT);
                    // Don't start path yet - wait to see if it's tap, long-press, or draw
                } else {
                    // Normal drawing - no object touched
                    currentPath.reset();
                    currentPath.moveTo(x, y);
                    lastDrawX = x;
                    lastDrawY = y;
                }
                return true;
                
            case MotionEvent.ACTION_MOVE:
                // Cancel long-press if moved too much
                if (longPressRunnable != null) {
                    float dx = x - lastTouchX;
                    float dy = y - lastTouchY;
                    float distance = (float) Math.sqrt(dx * dx + dy * dy);
                    
                    if (distance > 10) {
                        longPressHandler.removeCallbacks(longPressRunnable);
                        longPressRunnable = null;
                        
                        // Start drawing path now (was waiting for long-press)
                        if (!isLongPressing) {
                            currentPath.reset();
                            currentPath.moveTo(lastTouchX, lastTouchY);
                            lastDrawX = lastTouchX;
                            lastDrawY = lastTouchY;
                        }
                    }
                }
                
                if (isLongPressing && selectedObject != null) {
                    // Move object while long-pressing
                    float dx = x - lastTouchX;
                    float dy = y - lastTouchY;
                    selectedObject.translate(dx, dy);
                    lastTouchX = x;
                    lastTouchY = y;
                    invalidate();
                } else if (longPressRunnable == null && !isLongPressing) {
                    // Normal drawing (only if not waiting for long-press)
                    currentPath.quadTo(lastDrawX, lastDrawY, (x + lastDrawX) / 2, (y + lastDrawY) / 2);
                    lastDrawX = x;
                    lastDrawY = y;
                    invalidate();
                }
                return true;
                
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                // Cancel any pending long-press
                if (longPressRunnable != null) {
                    longPressHandler.removeCallbacks(longPressRunnable);
                    AnnotationObject touchedObj = findObjectAtPoint(x, y, currentPage);
                    
                    // If we didn't move and didn't long-press, it's a single tap
                    if (!isLongPressing && touchedObj != null) {
                        float dx = x - lastTouchX;
                        float dy = y - lastTouchY;
                        float distance = (float) Math.sqrt(dx * dx + dy * dy);
                        
                        if (distance < 10) {
                            // Single tap on object - enter selection mode!
                            selectionMode = true;
                            selectedObject = touchedObj;
                            currentPath.reset(); // Clear any accidental path
                            invalidate();
                            notifyStateChanged();
                            notifySelectionModeChanged(true); // Update toolbar
                            return true;
                        }
                    }
                    
                    longPressRunnable = null;
                }
                
                if (isLongPressing) {
                    // Finish quick drag (long-press move)
                    isLongPressing = false;
                    selectedObject = null;
                    currentPath.reset(); // Clear path (wasn't drawing)
                    invalidate();
                    notifyStateChanged();
                } else if (longPressRunnable == null) {
                    // Only save path if we were actually drawing (not waiting for tap)
                    if (currentPath != null && !currentPath.isEmpty()) {
                        AddPathCommand command = new AddPathCommand(
                            currentLayer, 
                            new Path(currentPath), 
                            new Paint(drawPaint)
                        );
                        currentPage.executeCommand(command);
                        
                        currentPath = new Path();
                        invalidate();
                        notifyStateChanged();
                    }
                }
                return true;
        }
        
        return false;
    }
    
    /**
     * Find the topmost object at the given point
     */
    private AnnotationObject findObjectAtPoint(float x, float y, AnnotationPage page) {
        // Search from top layer to bottom
        for (int i = page.getLayers().size() - 1; i >= 0; i--) {
            AnnotationLayer layer = page.getLayers().get(i);
            if (!layer.isVisible()) continue;
            
            // Search from top object to bottom in this layer
            for (int j = layer.getObjects().size() - 1; j >= 0; j--) {
                AnnotationObject obj = layer.getObjects().get(j);
                if (obj.isVisible() && obj.contains(x, y)) {
                    return obj;
                }
            }
        }
        return null;
    }
    
    /**
     * Set pen mode for drawing
     */
    public void setPenMode() {
        state.setEraserMode(false);
        updatePaintFromState();
    }
    
    /**
     * Set eraser mode
     */
    public void setEraserMode() {
        state.setEraserMode(true);
        updatePaintFromState();
    }
    
    /**
     * Set drawing color
     */
    public void setColor(int color) {
        state.setCurrentColor(color);
        updatePaintFromState();
    }
    
    /**
     * Set stroke width
     * @param width 0=thin, 1=medium, 2=thick
     */
    public void setStrokeWidth(int width) {
        float strokeWidth;
        switch (width) {
            case 0: strokeWidth = 4f; break;
            case 1: strokeWidth = 8f; break;
            case 2: strokeWidth = 16f; break;
            default: strokeWidth = 8f;
        }
        state.setCurrentStrokeWidth(strokeWidth);
        updatePaintFromState();
    }
    
    /**
     * Clear all drawings on active layer
     */
    public void clearAll() {
        AnnotationPage currentPage = state.getActivePage();
        if (currentPage == null) return;
        
        AnnotationLayer currentLayer = currentPage.getActiveLayer();
        if (currentLayer == null) return;
        
        ClearLayerCommand command = new ClearLayerCommand(currentLayer);
        currentPage.executeCommand(command);
        
        currentPath.reset();
        invalidate();
        notifyStateChanged();
    }
    
    /**
     * Undo last action
     */
    public void undo() {
        AnnotationPage currentPage = state.getActivePage();
        if (currentPage != null && currentPage.canUndo()) {
            currentPage.undo();
            invalidate();
            notifyStateChanged();
        }
    }
    
    /**
     * Redo last undone action
     */
    public void redo() {
        AnnotationPage currentPage = state.getActivePage();
        if (currentPage != null && currentPage.canRedo()) {
            currentPage.redo();
            invalidate();
            notifyStateChanged();
        }
    }
    
    /**
     * Check if undo is available
     */
    public boolean canUndo() {
        AnnotationPage currentPage = state.getActivePage();
        return currentPage != null && currentPage.canUndo();
    }
    
    /**
     * Check if redo is available
     */
    public boolean canRedo() {
        AnnotationPage currentPage = state.getActivePage();
        return currentPage != null && currentPage.canRedo();
    }
    
    /**
     * Get number of available undo operations
     */
    public int getUndoCount() {
        AnnotationPage currentPage = state.getActivePage();
        return currentPage != null ? currentPage.getUndoStackSize() : 0;
    }
    
    /**
     * Get number of available redo operations
     */
    public int getRedoCount() {
        AnnotationPage currentPage = state.getActivePage();
        return currentPage != null ? currentPage.getRedoStackSize() : 0;
    }
    
    /**
     * Toggle blackboard mode (black background)
     */
    public void setBlackboardMode(boolean enabled) {
        AnnotationPage currentPage = state.getActivePage();
        if (currentPage != null) {
            currentPage.setBlackboardMode(enabled);
            invalidate();
            notifyStateChanged();
        }
    }
    
    /**
     * Check if blackboard mode is enabled
     */
    public boolean isBlackboardMode() {
        AnnotationPage currentPage = state.getActivePage();
        return currentPage != null && currentPage.isBlackboardMode();
    }
    
    /**
     * Switch to different page
     */
    public void switchToPage(int pageIndex) {
        state.setActivePageIndex(pageIndex);
        invalidate();
        notifyStateChanged();
    }
    
    /**
     * Add new page
     */
    public void addPage(String name) {
        state.addPage(name);
        notifyStateChanged();
    }
    
    /**
     * Add new layer to current page
     */
    public void addLayer(String name) {
        AnnotationPage currentPage = state.getActivePage();
        if (currentPage != null) {
            currentPage.addLayer(name);
            notifyStateChanged();
        }
    }
    
    /**
     * Enable selection mode (for moving objects)
     */
    public void setSelectionMode(boolean enabled) {
        selectionMode = enabled;
        wasInSelectionMode = false; // Reset auto-entry flag
        if (!enabled) {
            selectedObject = null; // Clear selection when exiting selection mode
        }
        notifySelectionModeChanged(enabled); // Update toolbar UI
        invalidate();
    }
    
    /**
     * Check if selection mode is active
     */
    public boolean isSelectionMode() {
        return selectionMode;
    }
    
    /**
     * Deselect currently selected object
     */
    public void deselectObject() {
        selectedObject = null;
        invalidate();
    }
}
