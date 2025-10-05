package com.fadcam.fadrec.ui;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

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
        SeekBar seekOpacity = layerView.findViewById(R.id.seekOpacity);
        TextView txtOpacity = layerView.findViewById(R.id.txtOpacity);
        TextView btnDeleteLayer = layerView.findViewById(R.id.btnDeleteLayer);
        
        txtLayerName.setText(layer.getName());
        txtLayerInfo.setText(layer.getPaths().size() + " paths");
        
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
        btnVisibility.setText(layer.isVisible() ? "visibility" : "visibility_off");
        btnVisibility.setTextColor(layer.isVisible() ? 0xFF4CAF50 : 0xFF9E9E9E);
        btnVisibility.setOnClickListener(v -> {
            if (listener != null) {
                boolean newState = !layer.isVisible();
                listener.onLayerVisibilityChanged(index, newState);
                btnVisibility.setText(newState ? "visibility" : "visibility_off");
                btnVisibility.setTextColor(newState ? 0xFF4CAF50 : 0xFF9E9E9E);
            }
        });
        
        // Lock button
        btnLock.setText(layer.isLocked() ? "lock" : "lock_open");
        btnLock.setTextColor(layer.isLocked() ? 0xFFFF5252 : 0xFF9E9E9E);
        btnLock.setOnClickListener(v -> {
            if (listener != null) {
                boolean newState = !layer.isLocked();
                listener.onLayerLockChanged(index, newState);
                btnLock.setText(newState ? "lock" : "lock_open");
                btnLock.setTextColor(newState ? 0xFFFF5252 : 0xFF9E9E9E);
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
