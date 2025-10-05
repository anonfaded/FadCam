package com.fadcam.fadrec.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
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
    private Paint selectionPaint;
    
    // Callback for state changes
    private OnStateChangeListener stateChangeListener;
    
    public interface OnStateChangeListener {
        void onStateChanged();
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
        
        // Setup selection paint (for highlighting selected objects)
        selectionPaint = new Paint();
        selectionPaint.setColor(0xFF4CAF50); // Green
        selectionPaint.setStyle(Paint.Style.STROKE);
        selectionPaint.setStrokeWidth(4f);
        selectionPaint.setAntiAlias(true);
        
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
    
    private void notifyStateChanged() {
        if (stateChangeListener != null) {
            stateChangeListener.onStateChanged();
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
                    if (selectionMode && selectedObject == obj) {
                        RectF bounds = obj.getBounds();
                        canvas.drawRect(bounds, selectionPaint);
                    }
                }
            }
        }
        
        // Draw current path being drawn (only in draw mode)
        if (!selectionMode) {
            canvas.drawPath(currentPath, drawPaint);
        }
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
                    // Try to find and select an object at touch point
                    selectedObject = findObjectAtPoint(x, y, currentPage);
                    lastTouchX = x;
                    lastTouchY = y;
                    invalidate();
                    return true;
                    
                case MotionEvent.ACTION_MOVE:
                    // Move selected object
                    if (selectedObject != null) {
                        float dx = x - lastTouchX;
                        float dy = y - lastTouchY;
                        selectedObject.translate(dx, dy);
                        lastTouchX = x;
                        lastTouchY = y;
                        invalidate();
                    }
                    return true;
                    
                case MotionEvent.ACTION_UP:
                    // Finish moving (deselect on next tap or keep selected)
                    if (selectedObject != null) {
                        notifyStateChanged();
                    }
                    return true;
            }
            return false;
        }
        
        // Handle draw mode (original behavior)
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                currentPath.moveTo(x, y);
                return true;
                
            case MotionEvent.ACTION_MOVE:
                currentPath.lineTo(x, y);
                invalidate();
                return true;
                
            case MotionEvent.ACTION_UP:
                // Create command and execute it (adds to history)
                AddPathCommand command = new AddPathCommand(
                    currentLayer, 
                    new Path(currentPath), 
                    new Paint(drawPaint)
                );
                currentPage.executeCommand(command);
                
                currentPath = new Path();
                invalidate();
                notifyStateChanged();
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
        if (!enabled) {
            selectedObject = null; // Clear selection when exiting selection mode
        }
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
