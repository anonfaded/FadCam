package com.fadcam.fadrec.ui;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.DragEvent;
import android.view.HapticFeedbackConstants;

import com.fadcam.fadrec.ui.annotation.objects.AnnotationObject;
import android.view.View;
import android.view.View.DragShadowBuilder;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import java.util.List;

import com.fadcam.R;
import com.fadcam.fadrec.ui.annotation.AnnotationPage;
import com.fadcam.fadrec.ui.annotation.AnnotationLayer;

/**
 * Overlay showing layer hierarchy panel at the side of the screen.
 * Professional UI with eye icons, lock icons, and opacity controls.
 */
public class LayerPanelOverlay {
    private static final String TAG = "LayerPanelOverlay";
    
    private Context context;
    private WindowManager windowManager;
    private View overlayView;
    private LinearLayout layerContainer;
    private AnnotationPage page;
    private OnLayerActionListener listener;
    private View draggingLayerView;
    private String draggingLayerId = null;
    private boolean dragPerformed = false;
    private WindowManager.LayoutParams layoutParams;
    private int dragTouchSlop;
    private float overlayInitialTouchX;
    private float overlayInitialTouchY;
    private int overlayInitialX;
    private int overlayInitialY;
    private boolean isDraggingOverlay = false;
    private boolean opacityGestureActive = false;
    private int dimRequestCount = 0;
    private String activeOpacityChangeLayerId = null;
    private String activeReorderLayerId = null;
    private float layerDragDownRawX;
    private float layerDragDownRawY;
    private MinimizableOverlayButton minimizeButton;

    private final View.OnTouchListener overlayDragTouchListener = (view, event) -> {
        if (layoutParams == null || overlayView == null || windowManager == null) {
            return false;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                overlayInitialX = layoutParams.x;
                overlayInitialY = layoutParams.y;
                overlayInitialTouchX = event.getRawX();
                overlayInitialTouchY = event.getRawY();
                isDraggingOverlay = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                int deltaX = Math.round(event.getRawX() - overlayInitialTouchX);
                int deltaY = Math.round(event.getRawY() - overlayInitialTouchY);
                if (!isDraggingOverlay) {
                    if (Math.abs(deltaX) < dragTouchSlop && Math.abs(deltaY) < dragTouchSlop) {
                        return true;
                    }
                    isDraggingOverlay = true;
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }

                layoutParams.x = overlayInitialX + deltaX;
                layoutParams.y = overlayInitialY + deltaY;
                clampPosition(layoutParams, getOverlayWidth(), getOverlayHeight());
                windowManager.updateViewLayout(overlayView, layoutParams);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (isDraggingOverlay) {
                    isDraggingOverlay = false;
                    return true;
                }
                return true;
            default:
                return false;
        }
    };
    
    public interface OnLayerActionListener {
        void onLayerSelected(String layerId);
        void onLayerVisibilityChanged(String layerId, boolean visible);
        void onLayerLockChanged(String layerId, boolean locked);
        void onLayerOpacityChanged(String layerId, float opacity);
        void onLayerPinnedChanged(String layerId, boolean pinned);
        void onLayerAdded();
        void onLayerDeleted(String layerId);
        void onLayersReordered(String fromLayerId, String toLayerId);
        default void onLayerOpacityGestureStarted(String layerId) {}
        default void onLayerOpacityGestureEnded(String layerId) {}
        default void onLayerReorderGestureStarted(String layerId) {}
        default void onLayerReorderGestureEnded(String layerId) {}
    }
    
    public LayerPanelOverlay(Context context, AnnotationPage page) {
        this.context = context;
        this.page = page;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.dragTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        this.minimizeButton = new MinimizableOverlayButton(context, "Layers");
        this.minimizeButton.setOnButtonActionListener(new MinimizableOverlayButton.OnButtonActionListener() {
            @Override
            public void onExpandRequested() {
                expand();
            }

            @Override
            public void onMinimizeRequested() {
                // Not used - we minimize via button click
            }
        });
    }
    
    public void setOnLayerActionListener(OnLayerActionListener listener) {
        this.listener = listener;
    }
    
    public void show() {
        if (overlayView != null) {
            hide(); // Remove existing if any
        }
        
        // Inflate layout
        LayoutInflater inflater = LayoutInflater.from(context);
        overlayView = inflater.inflate(R.layout.overlay_layer_panel, null);
        
        layerContainer = overlayView.findViewById(R.id.layerContainer);
        TextView btnAddLayer = overlayView.findViewById(R.id.btnAddLayer);
        TextView btnClose = overlayView.findViewById(R.id.btnCloseLayerPanel);
        TextView btnMinimize = overlayView.findViewById(R.id.btnMinimizeLayerPanel);
        View headerDragHandle = overlayView.findViewById(R.id.layerPanelDragHandle);
        if (headerDragHandle != null) {
            headerDragHandle.setOnTouchListener(overlayDragTouchListener);
        } else {
            View headerView = overlayView.findViewById(R.id.layerPanelHeader);
            if (headerView != null) {
                headerView.setOnTouchListener(overlayDragTouchListener);
            }
        }
        
        // Populate layers
        updateLayers();
        
        // Add layer button
        btnAddLayer.setOnClickListener(v -> {
            if (listener != null) {
                listener.onLayerAdded();
                updateLayers();
            }
        });
        
        // Minimize button
        if (btnMinimize != null) {
            btnMinimize.setOnClickListener(v -> minimize());
        }
        
        // Close button
        btnClose.setOnClickListener(v -> hide());
        
        // Add to window
        int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        
        // Use WRAP_CONTENT for dynamic sizing based on content
    layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        
    layoutParams.gravity = Gravity.TOP | Gravity.START;
    layoutParams.x = 0;
    // Add status bar height offset so overlay appears below status bar
    layoutParams.y = getStatusBarHeight();
        
    windowManager.addView(overlayView, layoutParams);
    overlayView.post(this::adjustInitialPosition);
    }
    
    /**
     * Get status bar height to account for safe area
     */
    private int getStatusBarHeight() {
        int statusBarHeight = 0;
        int resourceId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            statusBarHeight = context.getResources().getDimensionPixelSize(resourceId);
        }
        return statusBarHeight;
    }
    
    public void hide() {
        if (overlayView != null && windowManager != null) {
            overlayView.animate().cancel();
            overlayView.setAlpha(1f);
            if (listener != null && activeOpacityChangeLayerId != null) {
                listener.onLayerOpacityGestureEnded(activeOpacityChangeLayerId);
            }
            if (listener != null && activeReorderLayerId != null) {
                listener.onLayerReorderGestureEnded(activeReorderLayerId);
            }
            windowManager.removeView(overlayView);
            overlayView = null;
            layoutParams = null;
            opacityGestureActive = false;
            dimRequestCount = 0;
            isDraggingOverlay = false;
            activeOpacityChangeLayerId = null;
            activeReorderLayerId = null;
            layerDragDownRawX = 0f;
            layerDragDownRawY = 0f;
        }
        
        // Also hide minimize button
        if (minimizeButton != null) {
            minimizeButton.hide();
        }
    }
    
    public boolean isShowing() {
        return overlayView != null;
    }
    
    public void minimize() {
        if (overlayView == null || layoutParams == null || windowManager == null) {
            return;
        }
        
        if (minimizeButton != null && minimizeButton.isShowing()) {
            return; // Already minimized
        }
        
        // Hide the full overlay
        overlayView.setVisibility(View.GONE);
        
        // Show the minimize button at its last remembered position
        if (minimizeButton != null) {
            minimizeButton.show(); // No parameters - uses last position
            minimizeButton.setExpanded(false);
        }
    }
    
    public void expand() {
        if (overlayView == null || layoutParams == null || windowManager == null) {
            return;
        }
        
        if (minimizeButton != null && !minimizeButton.isShowing()) {
            return; // Already expanded
        }
        
        // Get button position and edge before hiding
        int buttonX = 0, buttonY = 0;
        int buttonWidth = 0, buttonHeight = 0;
        MinimizableOverlayButton.EdgePosition edge = MinimizableOverlayButton.EdgePosition.RIGHT;
        
        if (minimizeButton != null && minimizeButton.isShowing()) {
            buttonX = minimizeButton.getCurrentX();
            buttonY = minimizeButton.getCurrentY();
            buttonWidth = minimizeButton.getButtonWidth();
            buttonHeight = minimizeButton.getButtonHeight();
            edge = minimizeButton.getCurrentEdge();
            minimizeButton.hide();
        }
        
        // Show the full overlay
        overlayView.setVisibility(View.VISIBLE);
        
        // Position overlay next to where button was, with gap
        int margin = (int) dpToPx(8f);
        switch (edge) {
            case LEFT:
                layoutParams.x = buttonX + buttonWidth + margin;
                layoutParams.y = buttonY;
                break;
            case RIGHT:
                layoutParams.x = buttonX - getOverlayWidth() - margin;
                layoutParams.y = buttonY;
                break;
            case TOP:
                layoutParams.x = buttonX;
                layoutParams.y = buttonY + buttonHeight + margin;
                break;
            case BOTTOM:
                layoutParams.x = buttonX;
                layoutParams.y = buttonY - getOverlayHeight() - margin;
                break;
            default:
                layoutParams.x = buttonX;
                layoutParams.y = buttonY;
                break;
        }
        
        clampPosition(layoutParams, getOverlayWidth(), getOverlayHeight());
        windowManager.updateViewLayout(overlayView, layoutParams);
    }
    
    public boolean isMinimized() {
        return minimizeButton != null && minimizeButton.isShowing();
    }
    
    public void hideMinimizeButton() {
        if (minimizeButton != null && minimizeButton.isShowing()) {
            minimizeButton.hide();
        }
    }
    
    public void showMinimizeButtonIfMinimized() {
        if (overlayView != null && overlayView.getVisibility() == View.GONE) {
            // Overlay is hidden, so button should be showing
            if (minimizeButton != null && !minimizeButton.isShowing() && layoutParams != null) {
                minimizeButton.show(layoutParams.x, layoutParams.y);
                minimizeButton.setExpanded(false);
            }
        }
    }
    
    private void updateLayers() {
        layerContainer.removeAllViews();
        
        int activeIndex = page.getActiveLayerIndex();
        
        // CRITICAL: Use getVisibleLayers() to exclude soft-deleted layers
        List<AnnotationLayer> visibleLayers = page.getVisibleLayers();
        List<AnnotationLayer> allLayers = page.getLayers();
        
        // Get active layer ID for comparison
        String activeLayerId = null;
        if (activeIndex >= 0 && activeIndex < allLayers.size()) {
            activeLayerId = allLayers.get(activeIndex).getId();
        }
        
        // Add layers in reverse order (top layer first)
        for (int i = visibleLayers.size() - 1; i >= 0; i--) {
            AnnotationLayer layer = visibleLayers.get(i);
            boolean isActive = layer.getId().equals(activeLayerId);
            
            View layerView = createLayerView(layer, isActive);
            layerContainer.addView(layerView);
        }
    }
    
    private View createLayerView(AnnotationLayer layer, boolean isActive) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View layerView = inflater.inflate(R.layout.item_layer_row, layerContainer, false);
        
        TextView txtLayerName = layerView.findViewById(R.id.txtLayerName);
        TextView txtLayerInfo = layerView.findViewById(R.id.txtLayerInfo);
        TextView btnVisibility = layerView.findViewById(R.id.btnVisibility);
        TextView btnLock = layerView.findViewById(R.id.btnLock);
        TextView btnPin = layerView.findViewById(R.id.btnPin);
        SeekBar seekOpacity = layerView.findViewById(R.id.seekOpacity);
        TextView txtOpacity = layerView.findViewById(R.id.txtOpacity);
        TextView btnDeleteLayer = layerView.findViewById(R.id.btnDeleteLayer);
        TextView btnDragHandle = layerView.findViewById(R.id.btnDragHandle);
        TextView btnRenameLayer = layerView.findViewById(R.id.btnRenameLayer);
        
        String layerId = layer.getId();
        android.util.Log.d("LayerPanelOverlay", "Creating layer view for: " + layer.getName() + " (ID: " + layerId + ")");
        android.util.Log.d("LayerPanelOverlay", "btnRenameLayer found: " + (btnRenameLayer != null));
        
        txtLayerName.setText(layer.getName());
        
        // Count non-deleted objects only
        int visibleObjectCount = 0;
        for (AnnotationObject obj : layer.getObjects()) {
            if (!obj.isDeleted()) {
                visibleObjectCount++;
            }
        }
        txtLayerInfo.setText(visibleObjectCount + " objects");
        
        // Highlight active layer - store layerId instead of index
        layerView.setTag(R.id.tag_layer_index, layerId);
        layerView.setTag(R.id.tag_layer_active, isActive);
        applyLayerRowBackground(layerView, isActive);
        layerView.setOnDragListener(this::handleLayerDragEvent);
        
        // Click to select layer
        layerView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onLayerSelected(layerId);
                updateLayers();
            }
        });
        
        // Visibility button
        int greenAccent = ContextCompat.getColor(context, R.color.annotation_heading_accent);
        int grayInactive = 0xFF9E9E9E;
        
        btnVisibility.setText(layer.isVisible() ? "visibility" : "visibility_off");
        btnVisibility.setTextColor(layer.isVisible() ? greenAccent : grayInactive);
        btnVisibility.setSelected(layer.isVisible()); // Set selected state for green border
        btnVisibility.setOnClickListener(v -> {
            if (listener != null) {
                boolean newState = !layer.isVisible();
                listener.onLayerVisibilityChanged(layerId, newState);
                btnVisibility.setText(newState ? "visibility" : "visibility_off");
                btnVisibility.setTextColor(newState ? greenAccent : grayInactive);
                btnVisibility.setSelected(newState); // Update selected state
            }
        });
        
        // Lock button
        btnLock.setText(layer.isLocked() ? "lock" : "lock_open");
        btnLock.setTextColor(layer.isLocked() ? 0xFFFF5252 : 0xFF9E9E9E);
        btnLock.setSelected(layer.isLocked()); // Set selected state for green border
        btnLock.setOnClickListener(v -> {
            if (listener != null) {
                boolean newState = !layer.isLocked();
                listener.onLayerLockChanged(layerId, newState);
                btnLock.setText(newState ? "lock" : "lock_open");
                btnLock.setTextColor(newState ? 0xFFFF5252 : 0xFF9E9E9E);
                btnLock.setSelected(newState); // Update selected state
            }
        });
        
        // Pin button
        btnPin.setText("push_pin");
        btnPin.setTextColor(layer.isPinned() ? 0xFFFF9800 : 0xFF9E9E9E);
        btnPin.setSelected(layer.isPinned()); // Set selected state for green border
        btnPin.setOnClickListener(v -> {
            if (listener != null) {
                boolean newState = !layer.isPinned();
                listener.onLayerPinnedChanged(layerId, newState);
                btnPin.setTextColor(newState ? 0xFFFF9800 : 0xFF9E9E9E);
                btnPin.setSelected(newState); // Update selected state
            }
        });
        
        // Opacity slider
        int progress = (int)(layer.getOpacity() * 100);
        seekOpacity.setProgress(progress);
        txtOpacity.setText(progress + "%");
        seekOpacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                txtOpacity.setText(progress + "%");
                if (fromUser && listener != null) {
                    listener.onLayerOpacityChanged(layerId, progress / 100f);
                }
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                setOpacityGestureActive(true);
                activeOpacityChangeLayerId = layerId;
                if (listener != null) {
                    listener.onLayerOpacityGestureStarted(layerId);
                }
            }
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                setOpacityGestureActive(false);
                if (listener != null) {
                    listener.onLayerOpacityGestureEnded(layerId);
                }
                activeOpacityChangeLayerId = null;
            }
        });
        
        // Rename button - launches rename dialog
        if (btnRenameLayer != null) {
            btnRenameLayer.setOnClickListener(v -> {
                android.util.Log.d("LayerPanelOverlay", "=== RENAME LAYER BUTTON CLICKED ===");
                android.util.Log.d("LayerPanelOverlay", "Layer ID: " + layerId);
                android.util.Log.d("LayerPanelOverlay", "Layer name: " + layer.getName());
                
                // Broadcast to show rename dialog
                android.content.Intent intent = new android.content.Intent("com.fadcam.fadrec.RENAME_LAYER");
                intent.putExtra("layer_id", layerId);
                intent.putExtra("layer_name", layer.getName());
                context.sendBroadcast(intent);
                
                android.util.Log.d("LayerPanelOverlay", "Broadcast sent for layer rename");
            });
        } else {
            android.util.Log.w("LayerPanelOverlay", "btnRenameLayer is NULL!");
        }
        
                // Drag handle - Simple drag implementation
        if (btnDragHandle != null) {
            btnDragHandle.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN || event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    layerDragDownRawX = event.getRawX();
                    layerDragDownRawY = event.getRawY();
                }
                return false;
            });
            btnDragHandle.setOnLongClickListener(v -> {
                startLayerDrag(layerView, layerId);
                return true;
            });
            btnDragHandle.setOnDragListener(this::handleLayerDragEvent);
        }
        
        // Delete button (only if more than one visible layer)
        if (page.getVisibleLayers().size() > 1) {
            btnDeleteLayer.setVisibility(View.VISIBLE);
            btnDeleteLayer.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onLayerDeleted(layerId);
                    updateLayers();
                }
            });
        } else {
            btnDeleteLayer.setVisibility(View.GONE);
        }
        
        return layerView;
    }
    
    private void startLayerDrag(View layerView, String layerId) {
        ClipData dragData = ClipData.newPlainText("layer_id", layerId);
        PointF touchPoint = computeLocalTouchPoint(layerView, layerDragDownRawX, layerDragDownRawY);
        DragShadowBuilder shadowBuilder = new OffsetDragShadowBuilder(layerView, touchPoint);
        draggingLayerView = layerView;
        draggingLayerId = layerId; // Store layer ID instead of index
        dragPerformed = false;
        activeReorderLayerId = layerId;
        setOpacityGestureActive(true);
        if (listener != null) {
            listener.onLayerReorderGestureStarted(layerId);
        }
        layerView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        layerView.animate().scaleX(1.03f).scaleY(1.03f).alpha(0.75f).setDuration(150).start();
        ViewCompat.setElevation(layerView, dpToPx(8f));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            layerView.startDragAndDrop(dragData, shadowBuilder, layerView, 0);
        } else {
            layerView.startDrag(dragData, shadowBuilder, layerView, 0);
        }
    }

    private boolean handleLayerDragEvent(View targetView, DragEvent event) {
        View rowView = findLayerRow(targetView);
        if (rowView == null) {
            return false;
        }

        ClipDescription description = event.getClipDescription();
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return description != null && description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN);
            case DragEvent.ACTION_DRAG_ENTERED:
                if (rowView != draggingLayerView) {
                    applyLayerDragTargetState(rowView, true);
                }
                return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                if (rowView != draggingLayerView) {
                    applyLayerDragTargetState(rowView, false);
                }
                return true;
            case DragEvent.ACTION_DROP:
                applyLayerDragTargetState(rowView, false);
                Object targetTag = rowView.getTag(R.id.tag_layer_index);
                String fromLayerId = getDragLayerIdFromClipData(event.getClipData());
                if (fromLayerId == null || !(targetTag instanceof String)) {
                    return false;
                }
                String toLayerId = (String) targetTag;
                if (!fromLayerId.equals(toLayerId)) {
                    dragPerformed = true;
                    handleLayerReorder(fromLayerId, toLayerId);
                }
                endLayerReorderGesture();
                return true;
            case DragEvent.ACTION_DRAG_ENDED:
                applyLayerDragTargetState(rowView, false);
                if (event.getLocalState() instanceof View && event.getLocalState() == rowView) {
                    rowView.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(150).start();
                    ViewCompat.setElevation(rowView, 0f);
                    if (!dragPerformed) {
                        refresh();
                    }
                    draggingLayerView = null;
                    draggingLayerId = null;
                    dragPerformed = false;
                }
                endLayerReorderGesture();
                return true;
            default:
                return false;
        }
    }

    private void handleLayerReorder(String fromLayerId, String toLayerId) {
        if (listener != null) {
            listener.onLayersReordered(fromLayerId, toLayerId);
        } else {
            // Fallback - convert IDs to indices
            AnnotationLayer fromLayer = page.getLayerById(fromLayerId);
            AnnotationLayer toLayer = page.getLayerById(toLayerId);
            if (fromLayer != null && toLayer != null) {
                int fromIndex = page.getLayers().indexOf(fromLayer);
                int toIndex = page.getLayers().indexOf(toLayer);
                page.moveLayer(fromIndex, toIndex);
                refresh();
            }
        }
    }

    private void applyLayerRowBackground(View layerView, boolean isActive) {
        layerView.setBackgroundResource(isActive
            ? R.drawable.annotation_layer_selected
            : R.drawable.settings_home_row_bg);
    }

    private void applyLayerDragTargetState(View layerView, boolean isTarget) {
        if (layerView == null) {
            return;
        }
        if (isTarget) {
            layerView.setBackgroundResource(R.drawable.annotation_drag_target_bg);
            ViewCompat.setElevation(layerView, dpToPx(10f));
        } else {
            Object activeTag = layerView.getTag(R.id.tag_layer_active);
            boolean isActive = activeTag instanceof Boolean && (Boolean) activeTag;
            applyLayerRowBackground(layerView, isActive);
            if (layerView != draggingLayerView) {
                ViewCompat.setElevation(layerView, 0f);
            }
        }
    }

    private View findLayerRow(View view) {
        View current = view;
        while (current != null && current.getTag(R.id.tag_layer_index) == null) {
            if (!(current.getParent() instanceof View)) {
                return null;
            }
            current = (View) current.getParent();
        }
        return current;
    }

    private String getDragLayerIdFromClipData(ClipData clipData) {
        if (clipData == null || clipData.getItemCount() == 0) {
            return null;
        }
        CharSequence text = clipData.getItemAt(0).getText();
        if (text == null) {
            return null;
        }
        return text.toString();
    }

    private void endLayerReorderGesture() {
        if (activeReorderLayerId == null) {
            return;
        }
        setOpacityGestureActive(false);
        if (listener != null) {
            listener.onLayerReorderGestureEnded(activeReorderLayerId);
        }
        activeReorderLayerId = null;
        layerDragDownRawX = 0f;
        layerDragDownRawY = 0f;
    }

    private PointF computeLocalTouchPoint(View view, float rawX, float rawY) {
        PointF point = new PointF();
        if (view == null) {
            point.set(0f, 0f);
            return point;
        }

        int width = view.getWidth() > 0 ? view.getWidth() : view.getMeasuredWidth();
        int height = view.getHeight() > 0 ? view.getHeight() : view.getMeasuredHeight();
        if (width <= 0) {
            width = (int) dpToPx(300f);
        }
        if (height <= 0) {
            height = (int) dpToPx(72f);
        }

        if (rawX == 0f && rawY == 0f) {
            point.set(width / 2f, height / 2f);
            return point;
        }

        int[] location = new int[2];
        view.getLocationOnScreen(location);
        float localX = rawX - location[0];
        float localY = rawY - location[1];

        localX = Math.max(0f, Math.min(localX, width));
        localY = Math.max(0f, Math.min(localY, height));
        point.set(localX, localY);
        return point;
    }

    private static class OffsetDragShadowBuilder extends DragShadowBuilder {
        private final Point touchPoint = new Point();

        OffsetDragShadowBuilder(View view, PointF localTouchPoint) {
            super(view);
            if (localTouchPoint != null) {
                touchPoint.set(Math.round(localTouchPoint.x), Math.round(localTouchPoint.y));
            }
        }

        @Override
        public void onProvideShadowMetrics(Point outShadowSize, Point outShadowTouchPoint) {
            View view = getView();
            if (view == null) {
                outShadowSize.set(0, 0);
                outShadowTouchPoint.set(0, 0);
                return;
            }

            int width = Math.max(1, view.getWidth());
            int height = Math.max(1, view.getHeight());
            outShadowSize.set(width, height);

            int clampedX = Math.max(0, Math.min(touchPoint.x, width));
            int clampedY = Math.max(0, Math.min(touchPoint.y, height));
            outShadowTouchPoint.set(clampedX, clampedY);
        }
    }

    private float dpToPx(float dp) {
        return dp * context.getResources().getDisplayMetrics().density;
    }

    private void adjustInitialPosition() {
        if (overlayView == null || layoutParams == null || windowManager == null) {
            return;
        }

        int overlayWidth = getOverlayWidth();
        int overlayHeight = getOverlayHeight();
        if (overlayWidth == 0 || overlayHeight == 0) {
            overlayView.post(this::adjustInitialPosition);
            return;
        }

        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int screenWidth = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;
        int margin = (int) dpToPx(16f);

        layoutParams.x = Math.max(margin, screenWidth - overlayWidth - margin);
        layoutParams.y = Math.max(margin, (screenHeight - overlayHeight) / 2);
        clampPosition(layoutParams, overlayWidth, overlayHeight);
        windowManager.updateViewLayout(overlayView, layoutParams);
    }

    private void clampPosition(WindowManager.LayoutParams params, int overlayWidth, int overlayHeight) {
        if (params == null) {
            return;
        }

        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int screenWidth = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;
        int margin = (int) dpToPx(12f);

        int minX = margin;
        int maxX = screenWidth - overlayWidth - margin;
        if (maxX < minX) {
            maxX = minX;
        }

        int minY = margin;
        int maxY = screenHeight - overlayHeight - margin;
        if (maxY < minY) {
            maxY = minY;
        }

        params.x = Math.max(minX, Math.min(params.x, maxX));
        params.y = Math.max(minY, Math.min(params.y, maxY));
    }

    private int getOverlayWidth() {
        if (overlayView == null) {
            return 0;
        }
        int width = overlayView.getWidth();
        if (width == 0) {
            width = overlayView.getMeasuredWidth();
        }
        if (width == 0) {
            width = (int) dpToPx(300f);
        }
        return width;
    }

    private int getOverlayHeight() {
        if (overlayView == null) {
            return 0;
        }
        int height = overlayView.getHeight();
        if (height == 0) {
            height = overlayView.getMeasuredHeight();
        }
        if (height == 0) {
            height = (int) dpToPx(420f);
        }
        return height;
    }

    private void setOpacityGestureActive(boolean requestActive) {
        if (requestActive) {
            dimRequestCount++;
        } else if (dimRequestCount > 0) {
            dimRequestCount--;
        }

        boolean shouldDim = dimRequestCount > 0;
        if (overlayView == null) {
            opacityGestureActive = shouldDim;
            return;
        }

        if (opacityGestureActive == shouldDim) {
            return;
        }

        opacityGestureActive = shouldDim;
        overlayView.animate().cancel();
        float targetAlpha = shouldDim ? 0.55f : 1f;
        long duration = shouldDim ? 120L : 160L;
        overlayView.animate()
            .alpha(targetAlpha)
            .setDuration(duration)
            .start();
    }
    
    public void refresh() {
        // CRITICAL: Don't refresh while user is dragging opacity slider - it destroys the SeekBar
        if (opacityGestureActive) {
            return;
        }
        
        if (overlayView != null && layerContainer != null) {
            layerContainer.post(() -> {
                if (overlayView != null) {
                    updateLayers();
                    if (layoutParams != null && windowManager != null) {
                        clampPosition(layoutParams, getOverlayWidth(), getOverlayHeight());
                        windowManager.updateViewLayout(overlayView, layoutParams);
                    }
                }
            });
        }
    }
}
