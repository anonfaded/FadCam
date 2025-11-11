package com.fadcam.fadrec.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import java.util.List;

import com.fadcam.fadrec.ui.annotation.AddPathCommand;
import com.fadcam.fadrec.ui.annotation.AnnotationLayer;
import com.fadcam.fadrec.ui.annotation.AnnotationPage;
import com.fadcam.fadrec.ui.annotation.AnnotationState;
import com.fadcam.fadrec.ui.annotation.ClearLayerCommand;
import com.fadcam.fadrec.ui.annotation.ClearAllLayersCommand;
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
    private Paint whiteboardPaint;
    
    // Track the last point drawn for incremental eraser drawing
    private float lastIncrementalX;
    private float lastIncrementalY;
    private boolean isFirstSegment;
    
    // Layer separation for proper eraser support
    private Bitmap drawingLayerBitmap;
    private Canvas drawingLayerCanvas;
    private Bitmap tempStrokeBitmap; // Temporary bitmap for current stroke being drawn
    private Canvas tempStrokeCanvas;
    
    // Selection mode state
    private boolean selectionMode = false;
    private AnnotationObject selectedObject = null;
    private float lastTouchX;
    private float lastTouchY;
    private float lastDrawX; // Last point for path drawing
    private float lastDrawY;
    private Paint selectionPaint;
    private boolean wasInSelectionMode = false; // Track if we auto-entered selection
    private boolean isDraggingObject = false; // Track if currently dragging object
    
    // Handle dragging
    private enum HandleType { NONE, LEFT, RIGHT, TOP, EDIT }
    private HandleType activeHandle = HandleType.NONE;
    private float initialScale = 1.0f;
    private float initialRotation = 0f;
    
    // Snap guides toggle
    private boolean snapGuidesEnabled = true; // Can be toggled by user
    
    // Canvas visibility (hide/show with pinned layer exemption)
    private boolean canvasHidden = false;
    
    // Rotation snap configuration
    private static final float[] SNAP_ANGLES = {0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f};
    private static final float SNAP_THRESHOLD = 10f; // Degrees within which to show snap guide (increased)
    private static final float SNAP_STRENGTH = 0.15f; // How much to pull toward snap (reduced for smoother feel)
    private float snappedAngle = -1f; // -1 means no snap active
    private float lastSnappedAngle = -1f; // Track last snap to prevent oscillation
    
    // Position snap configuration (safe area guides)
    private static final float SAFE_AREA_MARGIN = 60f; // Distance from screen edge for safe area
    private static final float POSITION_SNAP_THRESHOLD = 15f; // Pixels within which to show position guide
    private static final float POSITION_SNAP_STRENGTH = 0.2f; // Pull strength for position snapping
    private float snappedHorizontalLine = -1f; // -1 means no horizontal snap active
    private float snappedVerticalLine = -1f; // -1 means no vertical snap active
    
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
        
        // Setup whiteboard background paint
        whiteboardPaint = new Paint();
        whiteboardPaint.setColor(0xFFFFFFFF);
        whiteboardPaint.setStyle(Paint.Style.FILL);
        
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
        Log.d(TAG, "========== setState() called ==========");
        Log.d(TAG, "  New state pages: " + (state != null ? state.getPages().size() : "null"));
        
        if (state != null) {
            for (int i = 0; i < state.getPages().size(); i++) {
                AnnotationPage page = state.getPages().get(i);
                int totalObjects = 0;
                for (AnnotationLayer layer : page.getLayers()) {
                    totalObjects += layer.getObjects().size();
                }
                Log.d(TAG, "    Page " + (i+1) + ": " + page.getLayers().size() + " layers, " + totalObjects + " objects");
            }
            Log.d(TAG, "  Active page index: " + state.getActivePageIndex());
        }
        
        this.state = state;
        updatePaintFromState();
        
        // Redraw all content onto the drawing layer
        redrawAllToLayer();
        
        Log.d(TAG, "  Calling invalidate() to trigger redraw...");
        invalidate();
        
        Log.d(TAG, "  View dimensions: " + getWidth() + "x" + getHeight());
        Log.d(TAG, "========== setState() complete, should redraw now ==========");
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
    
    /**
     * Toggle snap guides on/off
     */
    public void setSnapGuidesEnabled(boolean enabled) {
        this.snapGuidesEnabled = enabled;
        if (!enabled) {
            // Clear any active snaps
            snappedAngle = -1f;
            snappedHorizontalLine = -1f;
            snappedVerticalLine = -1f;
            lastSnappedAngle = -1f;
        }
        invalidate();
    }
    
    public boolean isSnapGuidesEnabled() {
        return snapGuidesEnabled;
    }
    
    /**
     * Set canvas visibility (hide unpinned layers)
     */
    public void setCanvasHidden(boolean hidden) {
        this.canvasHidden = hidden;
        // CRITICAL: Regenerate bitmap to reflect new canvas visibility state
        // Just calling invalidate() would redraw the cached bitmap without updating layer visibility
        redrawAllToLayer();
        invalidate(); // Trigger canvas redraw to show updated bitmap
    }
    
    public boolean isCanvasHidden() {
        return canvasHidden;
    }
    
    /**
     * Get the currently selected object
     */
    public AnnotationObject getSelectedObject() {
        return selectedObject;
    }
    
    /**
     * Set the selected object (for programmatic selection)
     */
    public void setSelectedObject(AnnotationObject object) {
        this.selectedObject = object;
        invalidate();
    }
    
    public void notifyStateChanged() {
        if (stateChangeListener != null) {
            stateChangeListener.onStateChanged();
        }
        // Note: redrawAllToLayer() should be called explicitly when needed
        // It's skipped here for eraser strokes to avoid overwriting incremental drawing
    }
    
    /**
     * Notify state changed and force a full redraw from state.
     * Public method for external callers (e.g., AnnotationService) to trigger bitmap regeneration.
     */
    public void notifyStateChangedWithRedraw() {
        if (stateChangeListener != null) {
            stateChangeListener.onStateChanged();
        }
        redrawAllToLayer();
        invalidate(); // CRITICAL: Trigger canvas redraw to show updated bitmap
    }
    
    /**
     * Clean up resources when view is detached
     */
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (drawingLayerBitmap != null) {
            drawingLayerBitmap.recycle();
            drawingLayerBitmap = null;
            drawingLayerCanvas = null;
        }
        if (tempStrokeBitmap != null) {
            tempStrokeBitmap.recycle();
            tempStrokeBitmap = null;
            tempStrokeCanvas = null;
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
            // Use CLEAR mode for proper erasing (only affects drawing layer, not background)
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
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        
        // Create drawing layer bitmap when view size is determined
        if (w > 0 && h > 0) {
            createDrawingLayer(w, h);
        }
    }
    
    /**
     * Create or recreate the drawing layer bitmap
     */
    private void createDrawingLayer(int width, int height) {
        // Clean up old bitmaps if exist
        if (drawingLayerBitmap != null) {
            drawingLayerBitmap.recycle();
        }
        if (tempStrokeBitmap != null) {
            tempStrokeBitmap.recycle();
        }
        
        // Create new bitmap with alpha channel for transparency
        drawingLayerBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        drawingLayerCanvas = new Canvas(drawingLayerBitmap);
        
        // Create temporary stroke bitmap for current path
        tempStrokeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        tempStrokeCanvas = new Canvas(tempStrokeBitmap);
        
        // Redraw all existing content onto the new layer
        redrawAllToLayer();
    }
    
    /**
     * Redraw all annotation objects onto the drawing layer.
     * PROFESSIONAL PHOTOSHOP-STYLE ARCHITECTURE:
     * - Background layer is separate (filled with black/white/transparent)
     * - Each content layer has transparency where there's no content
     * - Layers stack on top of background
     * - Erasers use CLEAR mode to create transparency in their layer
     */
    private void redrawAllToLayer() {
        if (drawingLayerCanvas == null) return;
        
        AnnotationPage currentPage = state.getActivePage();
        if (currentPage == null) return;
        
        // Clear to transparent first
        drawingLayerCanvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
        
        // STEP 1: Draw background layer (separate from content layers)
        int backgroundColor = 0x00000000; // Transparent by default
        if (currentPage.isBlackboardMode()) {
            backgroundColor = 0xFF000000; // Black
            drawingLayerCanvas.drawColor(backgroundColor);
        } else if (currentPage.isWhiteboardMode()) {
            backgroundColor = 0xFFFFFFFF; // White
            drawingLayerCanvas.drawColor(backgroundColor);
        }
        // For transparent mode: no background fill, stays transparent
        
        // STEP 2: Render each content layer independently (with transparency)
        // Each layer is drawn on a transparent bitmap, then composited
        Matrix transform = new Matrix();
        
        for (AnnotationLayer layer : currentPage.getLayers()) {
            if (layer.isDeleted()) continue;
            if (!layer.isVisible()) continue;
            if (canvasHidden && !layer.isPinned()) continue;
            
            // Create a transparent bitmap for this layer
            Bitmap layerBitmap = Bitmap.createBitmap(
                drawingLayerBitmap.getWidth(), 
                drawingLayerBitmap.getHeight(), 
                Bitmap.Config.ARGB_8888
            );
            Canvas layerCanvas = new Canvas(layerBitmap);
            // NOTE: No background fill! Each layer starts transparent.
            // This is critical for proper layer stacking.
            
            // Draw all objects in creation order (pen and eraser mixed)
            // This ensures erasers only affect objects drawn BEFORE them
            for (AnnotationObject obj : layer.getObjects()) {
                if (obj.isDeleted()) continue; // Skip soft-deleted (for undo/redo)
                if (obj.isVisible()) {
                    float originalOpacity = obj.getOpacity();
                    obj.setOpacity(originalOpacity * layer.getOpacity());
                    obj.draw(layerCanvas, transform);
                    obj.setOpacity(originalOpacity);
                }
            }
            
            // STEP 3: Composite this layer onto the main canvas (on top of background and previous layers)
            Paint layerPaint = new Paint();
            layerPaint.setAlpha((int)(255 * layer.getOpacity()));
            drawingLayerCanvas.drawBitmap(layerBitmap, 0, 0, layerPaint);
            
            // Clean up layer bitmap
            layerBitmap.recycle();
        }
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        AnnotationPage currentPage = state.getActivePage();
        if (currentPage == null) return;
        
        // Determine if we're currently drawing/erasing (need to show current stroke at correct z-index)
        boolean isDrawingStroke = !currentPath.isEmpty();
        boolean isErasing = isDrawingStroke && state.isEraserMode();
        int activeLayerIndex = currentPage.getActiveLayerIndex();
        
        // LAYER 1: Draw background
        if (!currentPage.isBlackboardMode() && !currentPage.isWhiteboardMode()) {
            // Transparent mode - no background to draw
        } else {
            // Blackboard/Whiteboard - draw background
            if (currentPage.isBlackboardMode()) {
                canvas.drawRect(0, 0, getWidth(), getHeight(), blackboardPaint);
            } else if (currentPage.isWhiteboardMode()) {
                canvas.drawRect(0, 0, getWidth(), getHeight(), whiteboardPaint);
            }
        }
        
        // LAYER 2: Draw layers with proper Photoshop-style architecture
        // Background is separate, content layers have transparency
        
        if (isDrawingStroke && tempStrokeBitmap != null && tempStrokeCanvas != null) {
            // REAL-TIME DRAWING/ERASING WITH PHOTOSHOP-STYLE LAYER ARCHITECTURE
            
            List<AnnotationLayer> layers = currentPage.getLayers();
            AnnotationLayer activeLayer = layers.get(activeLayerIndex);
            Matrix transform = new Matrix();
            
            // STEP 1: Draw background layer (separate from content)
            int backgroundColor = 0x00000000;
            if (currentPage.isBlackboardMode()) {
                backgroundColor = 0xFF000000;
                canvas.drawColor(backgroundColor);
            } else if (currentPage.isWhiteboardMode()) {
                backgroundColor = 0xFFFFFFFF;
                canvas.drawColor(backgroundColor);
            }
            // Transparent mode: no background fill
            
            // STEP 2: Render all layers BELOW the active layer
            for (int i = 0; i < activeLayerIndex; i++) {
                AnnotationLayer layer = layers.get(i);
                if (layer.isDeleted()) continue;
                if (!layer.isVisible()) continue;
                if (canvasHidden && !layer.isPinned()) continue;
                
                // Create transparent bitmap for this layer
                Bitmap layerBitmap = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
                Canvas layerCanvas = new Canvas(layerBitmap);
                // NO background fill - layers are transparent where there's no content
                
                // Draw all objects in creation order
                for (AnnotationObject obj : layer.getObjects()) {
                    if (obj.isDeleted()) continue;
                    if (obj.isVisible()) {
                        float originalOpacity = obj.getOpacity();
                        obj.setOpacity(originalOpacity * layer.getOpacity());
                        obj.draw(layerCanvas, transform);
                        obj.setOpacity(originalOpacity);
                    }
                }
                
                // Composite this layer
                Paint layerPaint = new Paint();
                layerPaint.setAlpha((int)(255 * layer.getOpacity()));
                canvas.drawBitmap(layerBitmap, 0, 0, layerPaint);
                layerBitmap.recycle();
            }
            
            // STEP 3: Render the ACTIVE layer with current stroke/eraser
            Bitmap activeLayerBitmap = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
            Canvas activeLayerCanvas = new Canvas(activeLayerBitmap);
            // NO background fill - layer is transparent
            
            // Draw all committed objects in creation order
            for (AnnotationObject obj : activeLayer.getObjects()) {
                if (obj.isDeleted()) continue;
                if (obj.isVisible()) {
                    float originalOpacity = obj.getOpacity();
                    obj.setOpacity(originalOpacity * activeLayer.getOpacity());
                    obj.draw(activeLayerCanvas, transform);
                    obj.setOpacity(originalOpacity);
                }
            }
            
            // Apply current stroke/eraser
            if (isErasing) {
                // Eraser creates transparency in this layer
                Paint eraserPaint = new Paint(drawPaint);
                eraserPaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
                activeLayerCanvas.drawPath(currentPath, eraserPaint);
            } else {
                // Pen draws normally
                Paint penPaint = new Paint(drawPaint);
                penPaint.setAlpha((int)(drawPaint.getAlpha() * activeLayer.getOpacity()));
                activeLayerCanvas.drawPath(currentPath, penPaint);
            }
            
            // Composite active layer
            if (activeLayer.isVisible() && (!canvasHidden || activeLayer.isPinned())) {
                Paint layerPaint = new Paint();
                layerPaint.setAlpha((int)(255 * activeLayer.getOpacity()));
                canvas.drawBitmap(activeLayerBitmap, 0, 0, layerPaint);
            }
            activeLayerBitmap.recycle();
            
            // STEP 4: Render all layers ABOVE the active layer
            for (int i = activeLayerIndex + 1; i < layers.size(); i++) {
                AnnotationLayer layer = layers.get(i);
                if (layer.isDeleted()) continue;
                if (!layer.isVisible()) continue;
                if (canvasHidden && !layer.isPinned()) continue;
                
                // Create transparent bitmap for this layer
                Bitmap layerBitmap = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
                Canvas layerCanvas = new Canvas(layerBitmap);
                // NO background fill
                
                // Draw all objects in creation order
                for (AnnotationObject obj : layer.getObjects()) {
                    if (obj.isDeleted()) continue;
                    if (obj.isVisible()) {
                        float originalOpacity = obj.getOpacity();
                        obj.setOpacity(originalOpacity * layer.getOpacity());
                        obj.draw(layerCanvas, transform);
                        obj.setOpacity(originalOpacity);
                    }
                }
                
                // Composite this layer
                Paint layerPaint = new Paint();
                layerPaint.setAlpha((int)(255 * layer.getOpacity()));
                canvas.drawBitmap(layerBitmap, 0, 0, layerPaint);
                layerBitmap.recycle();
            }
            
        } else {
            // NO ACTIVE DRAWING/ERASING: Draw the cached bitmap
            if (drawingLayerBitmap != null) {
                // CRITICAL: During drag, regenerate bitmap for real-time movement
                if (isDraggingObject) {
                    redrawAllToLayer();
                }
                
                canvas.drawBitmap(drawingLayerBitmap, 0, 0, null);
            }
        }
        
        // LAYER 3: Draw selection highlights and handles on top
        if (selectionMode || isLongPressing) {
            for (AnnotationLayer layer : currentPage.getLayers()) {
                if (!layer.isVisible()) continue;
                if (canvasHidden && !layer.isPinned()) continue;
                
                for (AnnotationObject obj : layer.getObjects()) {
                    if (obj.isVisible() && selectedObject == obj) {
                        // Draw snap guides if enabled and active
                        if (snapGuidesEnabled) {
                            if (activeHandle == HandleType.TOP && snappedAngle >= 0) {
                                drawRotationSnapLine(canvas, obj, snappedAngle);
                            }
                            if (activeHandle == HandleType.NONE && (snappedHorizontalLine >= 0 || snappedVerticalLine >= 0)) {
                                drawPositionSnapLines(canvas);
                            }
                        }
                        drawSelectionHandles(canvas, obj);
                    }
                }
            }
        }
        
        // LAYER 4: Draw safe area guides if in selection mode and snap guides enabled
        if ((selectionMode || isLongPressing) && snapGuidesEnabled) {
            drawSafeAreaGuides(canvas);
        }
    }
    
    /**
     * Draw safe area guide lines (Instagram-style)
     */
    private void drawSafeAreaGuides(Canvas canvas) {
        Paint guidePaint = new Paint();
        guidePaint.setColor(0x30FFFFFF); // Very subtle white
        guidePaint.setStrokeWidth(1f);
        guidePaint.setAntiAlias(true);
        
        // Top safe area
        canvas.drawLine(0, SAFE_AREA_MARGIN, getWidth(), SAFE_AREA_MARGIN, guidePaint);
        
        // Bottom safe area
        float bottomLine = getHeight() - SAFE_AREA_MARGIN;
        canvas.drawLine(0, bottomLine, getWidth(), bottomLine, guidePaint);
        
        // Left safe area
        canvas.drawLine(SAFE_AREA_MARGIN, 0, SAFE_AREA_MARGIN, getHeight(), guidePaint);
        
        // Right safe area
        float rightLine = getWidth() - SAFE_AREA_MARGIN;
        canvas.drawLine(rightLine, 0, rightLine, getHeight(), guidePaint);
        
        // Center lines (horizontal and vertical)
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        canvas.drawLine(0, centerY, getWidth(), centerY, guidePaint);
        canvas.drawLine(centerX, 0, centerX, getHeight(), guidePaint);
    }
    
    /**
     * Draw position snap guide lines when near snap points
     */
    private void drawPositionSnapLines(Canvas canvas) {
        Paint snapPaint = new Paint();
        snapPaint.setColor(0xFF4CAF50); // Green
        snapPaint.setStrokeWidth(2f);
        snapPaint.setAlpha(200); // Semi-transparent
        snapPaint.setAntiAlias(true);
        
        // Draw horizontal snap line
        if (snappedHorizontalLine >= 0) {
            canvas.drawLine(0, snappedHorizontalLine, getWidth(), snappedHorizontalLine, snapPaint);
        }
        
        // Draw vertical snap line
        if (snappedVerticalLine >= 0) {
            canvas.drawLine(snappedVerticalLine, 0, snappedVerticalLine, getHeight(), snapPaint);
        }
    }
    
    /**
     * Draw rotation snap guide line when rotating near snap angles
     */
    private void drawRotationSnapLine(Canvas canvas, AnnotationObject obj, float angle) {
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
     * Border rotates and scales with the object, but handles stay consistent size
     */
    private void drawSelectionHandles(Canvas canvas, AnnotationObject obj) {
        RectF bounds = obj.getBounds();
        float centerX = bounds.centerX();
        float centerY = bounds.centerY();
        float rotation = obj.getRotation();
        float scale = obj.getScale();
        
        // First pass: Draw border with rotation and scale
        canvas.save();
        canvas.rotate(rotation, centerX, centerY);
        canvas.scale(scale, scale, centerX, centerY);
        
        // Calculate dynamic dash pattern - keep dash size consistent, increase count
        float perimeter = 2 * (bounds.width() + bounds.height());
        float scaledPerimeter = perimeter * scale;
        
        // Fixed dash size (doesn't scale with object)
        float baseDashLength = 10f; // Consistent dash size
        float baseGapLength = 5f;   // Consistent gap size
        
        // Scale the pattern inversely so it appears consistent
        float dashLength = baseDashLength / scale;
        float gapLength = baseGapLength / scale;
        
        // Update selection paint with dynamic dash pattern
        Paint dynamicSelectionPaint = new Paint(selectionPaint);
        dynamicSelectionPaint.setPathEffect(new DashPathEffect(new float[]{dashLength, gapLength}, 0));
        dynamicSelectionPaint.setStrokeWidth(4f / scale); // Keep stroke width consistent
        
        // Draw dotted border with rounded corners
        float cornerRadius = 8f / scale; // Keep corner radius consistent
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, dynamicSelectionPaint);
        
        canvas.restore();
        
        // Second pass: Draw handles with rotation only (no scale)
        // This keeps handles at consistent size
        canvas.save();
        canvas.rotate(rotation, centerX, centerY);
        
        // Handle paint (white circle with green border)
        Paint handlePaint = new Paint();
        handlePaint.setColor(0xFFFFFFFF);
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setAntiAlias(true);
        
        Paint handleBorderPaint = new Paint();
        handleBorderPaint.setColor(0xFF4CAF50);
        handleBorderPaint.setStyle(Paint.Style.STROKE);
        handleBorderPaint.setStrokeWidth(4f);
        handleBorderPaint.setAntiAlias(true);
        
        float handleRadius = 16f; // Larger for better visibility and touch
        
        // Calculate scaled bound positions
        float scaledLeft = centerX + (bounds.left - centerX) * scale;
        float scaledRight = centerX + (bounds.right - centerX) * scale;
        float scaledTop = centerY + (bounds.top - centerY) * scale;
        float scaledBottom = centerY + (bounds.bottom - centerY) * scale;
        float scaledCenterY = centerY + (bounds.centerY() - centerY) * scale;
        
        // Left handle (for scaling)
        canvas.drawCircle(scaledLeft, scaledCenterY, handleRadius, handlePaint);
        canvas.drawCircle(scaledLeft, scaledCenterY, handleRadius, handleBorderPaint);
        
        // Right handle (for scaling)
        canvas.drawCircle(scaledRight, scaledCenterY, handleRadius, handlePaint);
        canvas.drawCircle(scaledRight, scaledCenterY, handleRadius, handleBorderPaint);
        
        // Top rotation handle (slightly above)
        float topHandleY = scaledTop - 30;
        
        // Draw line from top of box to rotation handle
        Paint linePaint = new Paint(selectionPaint);
        linePaint.setPathEffect(null); // Solid line
        canvas.drawLine(centerX, scaledTop, centerX, topHandleY, linePaint);
        
        // Draw rotation handle
        canvas.drawCircle(centerX, topHandleY, handleRadius, handlePaint);
        canvas.drawCircle(centerX, topHandleY, handleRadius, handleBorderPaint);
        
        // If it's a text object, add an "Edit" button at bottom
        if (obj instanceof com.fadcam.fadrec.ui.annotation.objects.TextObject) {
            float editHandleY = scaledBottom + 30;
            
            // Draw edit button (same size as other handles)
            canvas.drawCircle(centerX, editHandleY, handleRadius, handlePaint);
            canvas.drawCircle(centerX, editHandleY, handleRadius, handleBorderPaint);
            
            // Draw edit emoji ✏️ centered properly
            Paint emojiPaint = new Paint();
            emojiPaint.setColor(0xFF4CAF50);
            emojiPaint.setTextSize(handleRadius * 1.6f);
            emojiPaint.setTextAlign(Paint.Align.CENTER);
            emojiPaint.setAntiAlias(true);
            
            // Get text bounds for precise centering
            Paint.FontMetrics fm = emojiPaint.getFontMetrics();
            float textHeight = fm.descent - fm.ascent;
            float textOffset = textHeight / 2f - fm.descent;
            
            canvas.drawText("✏️", centerX, editHandleY + textOffset, emojiPaint);
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
        boolean contains = bounds.contains(localX, localY);
        android.util.Log.d("AnnotationView", String.format("containsPoint for %s: touch=(%.1f,%.1f) local=(%.1f,%.1f) bounds=[%.1f,%.1f,%.1f,%.1f] result=%b",
            obj.getClass().getSimpleName(), px, py, localX, localY, bounds.left, bounds.top, bounds.right, bounds.bottom, contains));
        return contains;
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // CRITICAL: Respect enabled state - if disabled, don't process any touches
        if (!isEnabled()) {
            return false;
        }
        
        AnnotationPage currentPage = state.getActivePage();
        if (currentPage == null) return false;
        
        AnnotationLayer currentLayer = currentPage.getActiveLayer();
        if (currentLayer == null || currentLayer.isLocked()) return false;
        
        // CRITICAL: Block ALL touch events when canvas is hidden UNLESS drawing on a pinned layer
        if (canvasHidden && !currentLayer.isPinned()) {
            // Show helpful toast only on ACTION_DOWN (not on every move)
            if (event.getAction() == MotionEvent.ACTION_DOWN && getContext() != null) {
                android.widget.Toast.makeText(
                    getContext(), 
                    "⚠️ Canvas hidden! Show canvas or switch to a pinned layer to draw", 
                    android.widget.Toast.LENGTH_SHORT
                ).show();
            }
            return false; // Block ALL touch events (DOWN, MOVE, UP)
        }
        
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
                        float handleRadius = 16f; // Visual size (same as drawing)
                        float touchRadius = handleRadius + 8f; // Slightly larger for easier touch
                        float centerX = bounds.centerX();
                        float centerY = bounds.centerY();
                        float rotation = selectedObject.getRotation();
                        float scale = selectedObject.getScale();
                        
                        android.util.Log.d("AnnotationView", String.format(
                            "Touch at (%.1f, %.1f), object at (%.1f, %.1f), scale=%.2f, rotation=%.1f°",
                            x, y, centerX, centerY, scale, rotation
                        ));
                        
                        // Calculate scaled bound positions (same as drawing)
                        float scaledLeft = centerX + (bounds.left - centerX) * scale;
                        float scaledRight = centerX + (bounds.right - centerX) * scale;
                        float scaledTop = centerY + (bounds.top - centerY) * scale;
                        float scaledBottom = centerY + (bounds.bottom - centerY) * scale;
                        float scaledCenterY = centerY + (bounds.centerY() - centerY) * scale;
                        
                        // Transform handle positions with rotation only (no scale on handles themselves)
                        float radians = (float) Math.toRadians(rotation);
                        float cos = (float) Math.cos(radians);
                        float sin = (float) Math.sin(radians);
                        
                        // Left handle
                        float leftDx = scaledLeft - centerX;
                        float leftDy = scaledCenterY - centerY;
                        float leftX = centerX + leftDx * cos - leftDy * sin;
                        float leftY = centerY + leftDx * sin + leftDy * cos;
                        float distLeft = (float) Math.sqrt((x - leftX) * (x - leftX) + (y - leftY) * (y - leftY));
                        
                        // Right handle
                        float rightDx = scaledRight - centerX;
                        float rightDy = scaledCenterY - centerY;
                        float rightX = centerX + rightDx * cos - rightDy * sin;
                        float rightY = centerY + rightDx * sin + rightDy * cos;
                        float distRight = (float) Math.sqrt((x - rightX) * (x - rightX) + (y - rightY) * (y - rightY));
                        
                        // Top rotation handle
                        float topDx = 0; // centerX - centerX
                        float topDy = (scaledTop - 30) - centerY;
                        float topX = centerX + topDx * cos - topDy * sin;
                        float topY = centerY + topDx * sin + topDy * cos;
                        float distTop = (float) Math.sqrt((x - topX) * (x - topX) + (y - topY) * (y - topY));
                        
                        android.util.Log.d("AnnotationView", String.format(
                            "Handle distances - Left:%.1f Right:%.1f Top:%.1f (radius:%.1f)",
                            distLeft, distRight, distTop, touchRadius
                        ));
                        
                        // Edit button for text (bottom)
                        float distEdit = Float.MAX_VALUE;
                        if (selectedObject instanceof com.fadcam.fadrec.ui.annotation.objects.TextObject) {
                            float editDx = 0; // centerX - centerX
                            float editDy = (scaledBottom + 30) - centerY;
                            float editX = centerX + editDx * cos - editDy * sin;
                            float editY = centerY + editDx * sin + editDy * cos;
                            distEdit = (float) Math.sqrt((x - editX) * (x - editX) + (y - editY) * (y - editY));
                        }
                        
                        // Check handles in priority order: edit first (more specific), then rotation, then scale
                        if (distEdit < touchRadius) {
                            // Edit button clicked
                            activeHandle = HandleType.EDIT;
                            android.util.Log.d("AnnotationView", "EDIT handle activated");
                            showTextEditDialog((com.fadcam.fadrec.ui.annotation.objects.TextObject) selectedObject);
                            return true;
                        } else if (distTop < touchRadius) {
                            // Rotation handle
                            activeHandle = HandleType.TOP;
                            initialRotation = selectedObject.getRotation();
                            android.util.Log.d("AnnotationView", "ROTATION handle activated");
                            return true;
                        } else if (distLeft < touchRadius) {
                            // Left scale handle
                            activeHandle = HandleType.LEFT;
                            android.util.Log.d("AnnotationView", "LEFT SCALE handle activated");
                            return true;
                        } else if (distRight < touchRadius) {
                            // Right scale handle
                            activeHandle = HandleType.RIGHT;
                            android.util.Log.d("AnnotationView", "RIGHT SCALE handle activated");
                            return true;
                        } else if (containsPoint(selectedObject, x, y)) {
                            // Clicked inside object - start drag
                            activeHandle = HandleType.NONE;
                            android.util.Log.d("AnnotationView", "DRAG mode activated (inside object)");
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
                                isDraggingObject = true; // Enable real-time redraw
                                
                                float centerX = bounds.centerX();
                                float centerY = bounds.centerY();
                                
                                // Calculate angle from center to current touch point
                                float angle1 = (float) Math.toDegrees(Math.atan2(lastTouchY - centerY, lastTouchX - centerX));
                                float angle2 = (float) Math.toDegrees(Math.atan2(y - centerY, x - centerX));
                                float angleDelta = angle2 - angle1;
                                
                                android.util.Log.d("AnnotationView", String.format(
                                    "Rotation calc: angle1=%.1f° angle2=%.1f° delta=%.1f° currentRot=%.1f°",
                                    angle1, angle2, angleDelta, selectedObject.getRotation()
                                ));
                                
                                float newRotation = selectedObject.getRotation() + angleDelta;
                                
                                // Normalize to 0-360
                                while (newRotation < 0) newRotation += 360;
                                while (newRotation >= 360) newRotation -= 360;
                                
                                // Only apply snapping if enabled
                                if (snapGuidesEnabled) {
                                    // Check for snap angles - VISUAL GUIDES ONLY (no magnetic pull)
                                    snappedAngle = -1f;
                                    float closestSnapAngle = -1f;
                                    float minDiff = Float.MAX_VALUE;
                                    
                                    for (float snapAngle : SNAP_ANGLES) {
                                        float diff = Math.abs(newRotation - snapAngle);
                                        // Handle wraparound at 0/360
                                        if (diff > 180) diff = 360 - diff;
                                        
                                        if (diff < minDiff) {
                                            minDiff = diff;
                                            closestSnapAngle = snapAngle;
                                        }
                                    }
                                    
                                    // Show snap line if within threshold (visual guide ONLY)
                                    if (minDiff <= SNAP_THRESHOLD) {
                                        snappedAngle = closestSnapAngle;
                                        // NO magnetic pull - just show the guide line
                                    }
                                }
                                
                                selectedObject.setRotation(newRotation);
                                break;
                                
                            case LEFT:
                                // Scale by dragging left handle (inverse direction)
                                isDraggingObject = true; // Enable real-time redraw
                                
                                float scaleLeftDelta = -dx / 100f; // Negative for left handle
                                float newScaleLeft = selectedObject.getScale() + scaleLeftDelta;
                                if (newScaleLeft > 0.1f && newScaleLeft < 5.0f) {
                                    selectedObject.setScale(newScaleLeft);
                                }
                                break;
                                
                            case RIGHT:
                                // Scale by dragging right handle (normal direction)
                                isDraggingObject = true; // Enable real-time redraw
                                
                                float scaleRightDelta = dx / 100f;
                                float newScaleRight = selectedObject.getScale() + scaleRightDelta;
                                if (newScaleRight > 0.1f && newScaleRight < 5.0f) {
                                    selectedObject.setScale(newScaleRight);
                                }
                                break;
                                
                            case NONE:
                                // Normal drag - move object with position snapping
                                isDraggingObject = true; // Set flag for real-time redraw
                                
                                android.util.Log.d("AnnotationView", String.format("Dragging object: type=%s, before=(%.1f, %.1f), dx=%.1f, dy=%.1f", 
                                    selectedObject.getClass().getSimpleName(), selectedObject.getX(), selectedObject.getY(), dx, dy));
                                
                                float newX = selectedObject.getX() + dx;
                                float newY = selectedObject.getY() + dy;
                                
                                // Apply position snapping if enabled
                                if (snapGuidesEnabled) {
                                    snappedHorizontalLine = -1f;
                                    snappedVerticalLine = -1f;
                                    
                                    // Define snap points
                                    float[] horizontalSnapPoints = {
                                        SAFE_AREA_MARGIN,           // Top safe area
                                        getHeight() / 2f,           // Center
                                        getHeight() - SAFE_AREA_MARGIN  // Bottom safe area
                                    };
                                    
                                    float[] verticalSnapPoints = {
                                        SAFE_AREA_MARGIN,           // Left safe area
                                        getWidth() / 2f,            // Center
                                        getWidth() - SAFE_AREA_MARGIN   // Right safe area
                                    };
                                    
                                    // Check horizontal snapping (Y position)
                                    float minYDist = Float.MAX_VALUE;
                                    float closestYSnap = -1f;
                                    for (float snapPoint : horizontalSnapPoints) {
                                        float dist = Math.abs(newY - snapPoint);
                                        if (dist < minYDist && dist < POSITION_SNAP_THRESHOLD) {
                                            minYDist = dist;
                                            closestYSnap = snapPoint;
                                        }
                                    }
                                    
                                    if (closestYSnap >= 0) {
                                        snappedHorizontalLine = closestYSnap;
                                        // Apply gentle magnetic pull
                                        float pullAmount = (POSITION_SNAP_THRESHOLD - minYDist) / POSITION_SNAP_THRESHOLD;
                                        newY = newY + (closestYSnap - newY) * pullAmount * POSITION_SNAP_STRENGTH;
                                    }
                                    
                                    // Check vertical snapping (X position)
                                    float minXDist = Float.MAX_VALUE;
                                    float closestXSnap = -1f;
                                    for (float snapPoint : verticalSnapPoints) {
                                        float dist = Math.abs(newX - snapPoint);
                                        if (dist < minXDist && dist < POSITION_SNAP_THRESHOLD) {
                                            minXDist = dist;
                                            closestXSnap = snapPoint;
                                        }
                                    }
                                    
                                    if (closestXSnap >= 0) {
                                        snappedVerticalLine = closestXSnap;
                                        // Apply gentle magnetic pull
                                        float pullAmount = (POSITION_SNAP_THRESHOLD - minXDist) / POSITION_SNAP_THRESHOLD;
                                        newX = newX + (closestXSnap - newX) * pullAmount * POSITION_SNAP_STRENGTH;
                                    }
                                }
                                
                                // Apply the new position
                                selectedObject.setX(newX);
                                selectedObject.setY(newY);
                                android.util.Log.d("AnnotationView", String.format("After drag: (%.1f, %.1f)", newX, newY));
                                break;
                        }
                        
                        lastTouchX = x;
                        lastTouchY = y;
                        invalidate();
                    }
                    return true;
                    
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    isDraggingObject = false; // Clear drag flag
                    activeHandle = HandleType.NONE;
                    snappedAngle = -1f; // Clear rotation snap
                    lastSnappedAngle = -1f; // Clear rotation snap memory
                    snappedHorizontalLine = -1f; // Clear position snaps
                    snappedVerticalLine = -1f;
                    invalidate(); // Redraw to remove snap lines
                    notifyStateChangedWithRedraw(); // Object transformed, redraw
                    return true;
            }
            return false;
        }
        
        // CRITICAL: If selection mode is active, don't allow drawing
        // Selection mode should ONLY manipulate selected objects, not create new paths
        if (selectionMode && selectedObject != null) {
            // Already handled in selection mode block above
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
                    
                    // Initialize incremental drawing tracking
                    lastIncrementalX = x;
                    lastIncrementalY = y;
                    isFirstSegment = true;
                    
                    // Clear temp stroke bitmap when starting a new stroke
                    if (tempStrokeCanvas != null) {
                        tempStrokeCanvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
                    }
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
                    // Move object while long-pressing with real-time rendering
                    isDraggingObject = true; // Enable real-time redraw for smooth movement
                    
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
                    
                    // REMOVED: Old incremental eraser drawing that erased through all layers
                    // New approach: Treat eraser like pen - draw to temp preview, apply on release
                    // This ensures layer-specific erasing with proper z-index rendering
                    
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
                    isDraggingObject = false; // Reset real-time redraw flag
                    selectedObject = null;
                    currentPath.reset(); // Clear path (wasn't drawing)
                    invalidate();
                    notifyStateChangedWithRedraw(); // Object moved, redraw
                } else if (longPressRunnable == null) {
                    // Only save path if we were actually drawing (not waiting for tap)
                    if (currentPath != null && !currentPath.isEmpty()) {
                        // Save path for both pen and eraser
                        // Create paint copy with correct xfermode based on current mode
                        Paint pathPaint = new Paint(drawPaint);
                        
                        // Ensure xfermode matches current mode (in case drawPaint wasn't updated)
                        if (state.isEraserMode()) {
                            // Eraser must have CLEAR xfermode
                            if (pathPaint.getXfermode() == null) {
                                pathPaint.setXfermode(new android.graphics.PorterDuffXfermode(
                                    android.graphics.PorterDuff.Mode.CLEAR));
                            }
                        } else {
                            // Pen must NOT have any xfermode
                            pathPaint.setXfermode(null);
                        }
                        
                        AddPathCommand command = new AddPathCommand(
                            currentLayer, 
                            new Path(currentPath), 
                            pathPaint
                        );
                        currentPage.executeCommand(command);
                        
                        // CRITICAL FIX: Instead of directly drawing the path on top of the bitmap,
                        // we need to regenerate the entire bitmap to respect layer z-ordering.
                        // The old approach (drawingLayerCanvas.drawPath) always drew on top,
                        // which broke z-index when the active layer wasn't the topmost layer.
                        
                        // Regenerate bitmap with proper layer ordering
                        redrawAllToLayer();
                        
                        notifyStateChanged();
                        
                        currentPath = new Path();
                        invalidate();
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
            case 0: strokeWidth = 4f; break;   // Thin (8dp visual)
            case 1: strokeWidth = 8f; break;   // Medium (16dp visual)
            case 2: strokeWidth = 16f; break;  // Thick (24dp visual)
            case 3: strokeWidth = 32f; break;  // Extra Thick (32dp visual)
            default: strokeWidth = 8f;
        }
        state.setCurrentStrokeWidth(strokeWidth);
        updatePaintFromState();
    }
    
    /**
     * Clear all drawings on all layers
     */
    public void clearAll() {
        AnnotationPage currentPage = state.getActivePage();
        if (currentPage == null) return;
        
        // Use atomic command to clear all layers at once
        // This allows proper undo/redo of the entire "Delete All" operation
        ClearAllLayersCommand command = new ClearAllLayersCommand(currentPage);
        currentPage.executeCommand(command);
        
        currentPath.reset();
        invalidate();
        notifyStateChangedWithRedraw(); // Force redraw after clear all
    }
    
    /**
     * Undo last action
     */
    public void undo() {
        AnnotationPage currentPage = state.getActivePage();
        if (currentPage != null && currentPage.canUndo()) {
            currentPage.undo();
            invalidate();
            notifyStateChangedWithRedraw(); // Force redraw for undo
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
            notifyStateChangedWithRedraw(); // Force redraw for redo
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
            notifyStateChangedWithRedraw(); // Board mode changed, redraw
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
     * Toggle whiteboard mode (white background)
     */
    public void setWhiteboardMode(boolean enabled) {
        AnnotationPage currentPage = state.getActivePage();
        if (currentPage != null) {
            currentPage.setWhiteboardMode(enabled);
            invalidate();
            notifyStateChangedWithRedraw(); // Board mode changed, redraw
        }
    }
    
    /**
     * Check if whiteboard mode is enabled
     */
    public boolean isWhiteboardMode() {
        AnnotationPage currentPage = state.getActivePage();
        return currentPage != null && currentPage.isWhiteboardMode();
    }
    
    /**
     * Switch to different page
     */
    public void switchToPage(int pageIndex) {
        state.setActivePageIndex(pageIndex);
        invalidate();
        notifyStateChangedWithRedraw(); // Page switched, redraw
    }
    
    /**
     * Add new page
     */
    public void addPage(String name) {
        state.addPage(name);
        notifyStateChangedWithRedraw(); // Page added, redraw
    }
    
    /**
     * Add new layer to current page
     */
    public void addLayer(String name) {
        AnnotationPage currentPage = state.getActivePage();
        if (currentPage != null) {
            currentPage.addLayer(name);
            notifyStateChangedWithRedraw(); // Layer added, redraw
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
