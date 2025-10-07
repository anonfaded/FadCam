package com.fadcam.fadrec.ui;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import androidx.core.content.ContextCompat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;

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
    
    public interface OnLayerActionListener {
        void onLayerSelected(int index);
        void onLayerVisibilityChanged(int index, boolean visible);
        void onLayerLockChanged(int index, boolean locked);
        void onLayerOpacityChanged(int index, float opacity);
        void onLayerPinnedChanged(int index, boolean pinned);
        void onLayerAdded();
        void onLayerDeleted(int index);
    }
    
    public LayerPanelOverlay(Context context, AnnotationPage page) {
        this.context = context;
        this.page = page;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
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
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        
        params.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        params.x = 20; // Small margin from right
        
        windowManager.addView(overlayView, params);
    }
    
    public void hide() {
        if (overlayView != null && windowManager != null) {
            windowManager.removeView(overlayView);
            overlayView = null;
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
        if (isActive) {
            layerView.setBackgroundResource(R.drawable.annotation_layer_selected);
        } else {
            layerView.setBackgroundResource(R.drawable.settings_home_row_bg);
        }
        
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
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        // Rename button - launches rename dialog
        if (btnRenameLayer != null) {
            btnRenameLayer.setOnClickListener(v -> {
                android.util.Log.d("LayerPanelOverlay", "=== RENAME LAYER BUTTON CLICKED ===");
                android.util.Log.d("LayerPanelOverlay", "Layer index: " + index);
                android.util.Log.d("LayerPanelOverlay", "Layer name: " + layer.getName());
                
                // Broadcast to show rename dialog
                android.content.Intent intent = new android.content.Intent("com.fadcam.fadrec.RENAME_LAYER");
                intent.putExtra("layerIndex", index);
                intent.putExtra("currentName", layer.getName());
                context.sendBroadcast(intent);
                
                android.util.Log.d("LayerPanelOverlay", "Broadcast sent for layer rename");
            });
        } else {
            android.util.Log.w("LayerPanelOverlay", "btnRenameLayer is NULL!");
        }
        
                // Drag handle - Simple drag implementation
        if (btnDragHandle != null) {
            btnDragHandle.setOnLongClickListener(v -> {
                // Show toast for now - full drag implementation requires RecyclerView with ItemTouchHelper
                Toast.makeText(context, "Drag-to-reorder: Hold and drag to reorder layers (Coming soon)", Toast.LENGTH_SHORT).show();
                return true;
            });
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
    
    public void refresh() {
        if (overlayView != null) {
            updateLayers();
        }
    }
}
