package com.fadcam.fadrec.ui.overlay;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.Log;

/**
 * Abstract base class for all inline overlay editors (text, shapes, images, etc.).
 * Provides common functionality for showing/hiding overlays, managing window params,
 * and handling lifecycle events.
 * 
 * Subclasses must implement:
 * - getLayoutResourceId() to provide the layout
 * - onViewCreated() to initialize views and setup listeners
 * - onShow() for show-specific logic
 * - onHide() for cleanup logic
 */
public abstract class BaseEditorOverlay {
    
    protected static final String TAG = "BaseEditorOverlay";
    
    protected final Context context;
    protected final WindowManager windowManager;
    protected View overlayView;
    protected WindowManager.LayoutParams layoutParams;
    protected boolean isShowing = false;
    
    // Callbacks
    protected EditorCallback editorCallback;
    
    /**
     * Constructor
     * @param context Application context
     * @param windowManager System window manager
     */
    public BaseEditorOverlay(Context context, WindowManager windowManager) {
        this.context = context;
        this.windowManager = windowManager;
        initializeLayoutParams();
    }
    
    /**
     * Initialize window layout parameters with common settings.
     * Subclasses can override to customize.
     */
    protected void initializeLayoutParams() {
        layoutParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH |
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );
    }
    
    /**
     * Get the layout resource ID for this editor.
     * Must be implemented by subclasses.
     */
    protected abstract int getLayoutResourceId();
    
    /**
     * Called after view is inflated to initialize UI components.
     * Subclasses should find views and setup listeners here.
     */
    protected abstract void onViewCreated(View view);
    
    /**
     * Called when the overlay is shown.
     * Subclasses can perform show-specific initialization here.
     */
    protected abstract void onShow();
    
    /**
     * Called when the overlay is hidden.
     * Subclasses should cleanup resources here.
     */
    protected abstract void onHide();
    
    /**
     * Show the overlay editor
     */
    public void show() {
        if (isShowing) {
            Log.w(TAG, "Overlay already showing");
            return;
        }
        
        try {
            // Inflate view if not already done
            if (overlayView == null) {
                LayoutInflater inflater = LayoutInflater.from(context);
                overlayView = inflater.inflate(getLayoutResourceId(), null);
                onViewCreated(overlayView);
            }
            
            // Add to window manager
            windowManager.addView(overlayView, layoutParams);
            isShowing = true;
            
            // Call subclass-specific show logic
            onShow();
            
            Log.d(TAG, getClass().getSimpleName() + " shown");
        } catch (Exception e) {
            Log.e(TAG, "Error showing overlay", e);
        }
    }
    
    /**
     * Hide the overlay editor
     */
    public void hide() {
        if (!isShowing) {
            Log.w(TAG, "Overlay not showing");
            return;
        }
        
        try {
            // Call subclass-specific hide logic first
            onHide();
            
            // Remove from window manager
            if (overlayView != null && overlayView.getParent() != null) {
                windowManager.removeView(overlayView);
            }
            
            isShowing = false;
            Log.d(TAG, getClass().getSimpleName() + " hidden");
        } catch (Exception e) {
            Log.e(TAG, "Error hiding overlay", e);
        }
    }
    
    /**
     * Destroy the overlay and release resources
     */
    public void destroy() {
        hide();
        overlayView = null;
        editorCallback = null;
    }
    
    /**
     * Check if overlay is currently showing
     */
    public boolean isShowing() {
        return isShowing;
    }
    
    /**
     * Set callback for editor events
     */
    public void setEditorCallback(EditorCallback callback) {
        this.editorCallback = callback;
    }
    
    /**
     * Callback interface for editor events
     */
    public interface EditorCallback {
        /**
         * Called when editor requests to be closed
         */
        void onEditorClosed();
        
        /**
         * Called when content is confirmed/saved
         * @param data Any data related to the edited content
         */
        void onContentConfirmed(Object data);
        
        /**
         * Called when content is cancelled
         */
        void onContentCancelled();
        
        /**
         * Called when delete is requested
         */
        void onDeleteRequested();
    }
}
