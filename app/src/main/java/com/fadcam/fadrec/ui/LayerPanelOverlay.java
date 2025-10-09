package com.fadcam.fadrec.ui;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.DragEvent;
import android.view.HapticFeedbackConstants;
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
    private int draggingLayerIndex = -1;
    private boolean dragPerformed = false;
    private WindowManager.LayoutParams layoutParams;
    private int dragTouchSlop;
    private float overlayInitialTouchX;
    private float overlayInitialTouchY;
    private int overlayInitialX;
    private int overlayInitialY;
    private boolean isDraggingOverlay = false;
    private boolean opacityGestureActive = false;
    private int activeOpacityChangeIndex = -1;

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
                return false;
            case MotionEvent.ACTION_MOVE:
                int deltaX = Math.round(event.getRawX() - overlayInitialTouchX);
                int deltaY = Math.round(event.getRawY() - overlayInitialTouchY);
                if (!isDraggingOverlay) {
                    if (Math.abs(deltaX) < dragTouchSlop && Math.abs(deltaY) < dragTouchSlop) {
                        return false;
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
                return false;
            default:
                return false;
        }
    };
    
    public interface OnLayerActionListener {
        void onLayerSelected(int index);
        void onLayerVisibilityChanged(int index, boolean visible);
        void onLayerLockChanged(int index, boolean locked);
        void onLayerOpacityChanged(int index, float opacity);
        void onLayerPinnedChanged(int index, boolean pinned);
        void onLayerAdded();
        void onLayerDeleted(int index);
        void onLayersReordered(int fromIndex, int toIndex);
        default void onLayerOpacityGestureStarted(int index) {}
        default void onLayerOpacityGestureEnded(int index) {}
    }
    
    public LayerPanelOverlay(Context context, AnnotationPage page) {
        this.context = context;
        this.page = page;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.dragTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
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
        View headerView = overlayView.findViewById(R.id.layerPanelHeader);
        if (headerView != null) {
            headerView.setOnTouchListener(overlayDragTouchListener);
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
    layoutParams.y = 0;
        
    windowManager.addView(overlayView, layoutParams);
    overlayView.post(this::adjustInitialPosition);
    }
    
    public void hide() {
        if (overlayView != null && windowManager != null) {
            overlayView.animate().cancel();
            overlayView.setAlpha(1f);
            if (opacityGestureActive) {
                setOpacityGestureActive(false);
                if (listener != null && activeOpacityChangeIndex != -1) {
                    listener.onLayerOpacityGestureEnded(activeOpacityChangeIndex);
                }
            }
            windowManager.removeView(overlayView);
            overlayView = null;
            layoutParams = null;
            opacityGestureActive = false;
            isDraggingOverlay = false;
            activeOpacityChangeIndex = -1;
        }
    }
    
    public boolean isShowing() {
        return overlayView != null;
    }
    
    private void updateLayers() {
        layerContainer.removeAllViews();
        
        int activeIndex = page.getActiveLayerIndex();
        
        // Add layers in reverse order (top layer first)
        for (int i = page.getLayers().size() - 1; i >= 0; i--) {
            AnnotationLayer layer = page.getLayers().get(i);
            boolean isActive = (i == activeIndex);
            
            View layerView = createLayerView(layer, i, isActive);
            layerContainer.addView(layerView);
        }
    }
    
    private View createLayerView(AnnotationLayer layer, int index, boolean isActive) {
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
        
        android.util.Log.d("LayerPanelOverlay", "Creating layer view for: " + layer.getName() + " (index " + index + ")");
        android.util.Log.d("LayerPanelOverlay", "btnRenameLayer found: " + (btnRenameLayer != null));
        
        txtLayerName.setText(layer.getName());
        txtLayerInfo.setText(layer.getObjects().size() + " objects");
        
        // Highlight active layer
        layerView.setTag(R.id.tag_layer_index, index);
        layerView.setTag(R.id.tag_layer_active, isActive);
        applyLayerRowBackground(layerView, isActive);
        layerView.setOnDragListener(this::handleLayerDragEvent);
        
        // Click to select layer
        layerView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onLayerSelected(index);
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
                listener.onLayerVisibilityChanged(index, newState);
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
                listener.onLayerLockChanged(index, newState);
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
                listener.onLayerPinnedChanged(index, newState);
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
                    listener.onLayerOpacityChanged(index, progress / 100f);
                }
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                setOpacityGestureActive(true);
                activeOpacityChangeIndex = index;
                if (listener != null) {
                    listener.onLayerOpacityGestureStarted(index);
                }
            }
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                setOpacityGestureActive(false);
                if (listener != null) {
                    listener.onLayerOpacityGestureEnded(index);
                }
                activeOpacityChangeIndex = -1;
            }
        });
        
        // Rename button - launches rename dialog
        if (btnRenameLayer != null) {
            btnRenameLayer.setOnClickListener(v -> {
                android.util.Log.d("LayerPanelOverlay", "=== RENAME LAYER BUTTON CLICKED ===");
                android.util.Log.d("LayerPanelOverlay", "Layer index: " + index);
                android.util.Log.d("LayerPanelOverlay", "Layer name: " + layer.getName());
                
                // Broadcast to show rename dialog
                android.content.Intent intent = new android.content.Intent("com.fadcam.fadrec.RENAME_LAYER");
                intent.putExtra("layer_index", index);
                intent.putExtra("layer_name", layer.getName());
                context.sendBroadcast(intent);
                
                android.util.Log.d("LayerPanelOverlay", "Broadcast sent for layer rename");
            });
        } else {
            android.util.Log.w("LayerPanelOverlay", "btnRenameLayer is NULL!");
        }
        
                // Drag handle - Simple drag implementation
        if (btnDragHandle != null) {
            btnDragHandle.setOnLongClickListener(v -> {
                startLayerDrag(layerView, index);
                return true;
            });
            btnDragHandle.setOnDragListener(this::handleLayerDragEvent);
        }
        
        // Delete button (only if more than one layer)
        if (page.getLayers().size() > 1) {
            btnDeleteLayer.setVisibility(View.VISIBLE);
            btnDeleteLayer.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onLayerDeleted(index);
                    updateLayers();
                }
            });
        } else {
            btnDeleteLayer.setVisibility(View.GONE);
        }
        
        return layerView;
    }
    
    private void startLayerDrag(View layerView, int index) {
        ClipData dragData = ClipData.newPlainText("layer_index", String.valueOf(index));
        DragShadowBuilder shadowBuilder = new DragShadowBuilder(layerView);
        draggingLayerView = layerView;
        draggingLayerIndex = index;
        dragPerformed = false;
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
                int fromIndex = parseDragIndex(event.getClipData());
                Object targetTag = rowView.getTag(R.id.tag_layer_index);
                if (fromIndex == -1 || !(targetTag instanceof Integer)) {
                    return false;
                }
                int toIndex = (Integer) targetTag;
                if (fromIndex != toIndex) {
                    dragPerformed = true;
                    handleLayerReorder(fromIndex, toIndex);
                }
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
                    draggingLayerIndex = -1;
                    dragPerformed = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void handleLayerReorder(int fromIndex, int toIndex) {
        if (listener != null) {
            listener.onLayersReordered(fromIndex, toIndex);
        } else {
            page.moveLayer(fromIndex, toIndex);
            refresh();
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

    private int parseDragIndex(ClipData clipData) {
        if (clipData == null || clipData.getItemCount() == 0) {
            return -1;
        }
        CharSequence text = clipData.getItemAt(0).getText();
        if (text == null) {
            return -1;
        }
        try {
            return Integer.parseInt(text.toString());
        } catch (NumberFormatException ex) {
            return draggingLayerIndex;
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

    private void setOpacityGestureActive(boolean active) {
        if (overlayView == null) {
            opacityGestureActive = active;
            return;
        }
        if (opacityGestureActive == active) {
            return;
        }
        opacityGestureActive = active;
        overlayView.animate().cancel();
        float targetAlpha = active ? 0.55f : 1f;
        long duration = active ? 120L : 160L;
        overlayView.animate()
            .alpha(targetAlpha)
            .setDuration(duration)
            .start();
    }
    
    public void refresh() {
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
