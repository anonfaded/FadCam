package com.fadcam.fadrec.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.fadcam.fadrec.ui.annotation.AddPathCommand;
import com.fadcam.fadrec.ui.annotation.AnnotationLayer;
import com.fadcam.fadrec.ui.annotation.AnnotationPage;
import com.fadcam.fadrec.ui.annotation.AnnotationState;
import com.fadcam.fadrec.ui.annotation.ClearLayerCommand;
import com.fadcam.fadrec.ui.annotation.DrawingPath;

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
            
            // Apply layer opacity
            int alpha = (int) (layer.getOpacity() * 255);
            
            // Draw all paths in this layer
            for (DrawingPath drawingPath : layer.getPaths()) {
                if (drawingPath.path != null && drawingPath.paint != null) {
                    // Apply layer opacity to path
                    Paint layerPaint = new Paint(drawingPath.paint);
                    layerPaint.setAlpha(alpha);
                    canvas.drawPath(drawingPath.path, layerPaint);
                }
            }
        }
        
        // Draw current path being drawn
        canvas.drawPath(currentPath, drawPaint);
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        AnnotationPage currentPage = state.getActivePage();
        if (currentPage == null) return false;
        
        AnnotationLayer currentLayer = currentPage.getActiveLayer();
        if (currentLayer == null || currentLayer.isLocked()) return false;
        
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
}
