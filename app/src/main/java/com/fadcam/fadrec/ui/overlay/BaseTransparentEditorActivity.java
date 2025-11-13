package com.fadcam.fadrec.ui.overlay;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

/**
 * Base transparent fullscreen activity for all editing overlays.
 * Provides proper keyboard handling, lifecycle management, and result communication.
 * 
 * Subclasses should implement:
 * - getLayoutResourceId() - provide the layout XML
 * - onEditorViewCreated() - initialize UI components
 * - onEditorResult() - handle save/cancel results
 * 
 * Benefits over WindowManager overlay:
 * - Proper keyboard handling with windowSoftInputMode
 * - Correct layout measurements and callbacks
 * - Activity lifecycle management
 * - Easy result passing with Intent extras
 */
public abstract class BaseTransparentEditorActivity extends Activity {
    
    // Broadcast actions
    public static final String ACTION_EDITOR_RESULT = "com.fadcam.fadrec.ACTION_EDITOR_RESULT";
    public static final String ACTION_EDITOR_STARTED = "com.fadcam.fadrec.ACTION_EDITOR_STARTED";
    public static final String ACTION_EDITOR_FINISHED = "com.fadcam.fadrec.ACTION_EDITOR_FINISHED";
    
    // Result extras
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_ACTION = "result_action";
    public static final String EXTRA_RESULT_DATA = "result_data";
    
    // Result codes
    public static final int RESULT_SAVE = 1;
    public static final int RESULT_DELETE = 2;
    public static final int RESULT_CANCELLED = 0;
    
    // Action types (for legacy compatibility)
    public static final String ACTION_SAVE = "save";
    public static final String ACTION_CANCEL = "cancel";
    public static final String ACTION_DELETE = "delete";
    
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d("BaseTransparentEditor", "onCreate() started");
        
        // Setup edge-to-edge layout with proper system insets
        setupEdgeToEdge();
        
        // Setup transparent window
        setupTransparentWindow();
        
        // Notify that editor started (disable annotation canvas)
        Intent startBroadcast = new Intent(ACTION_EDITOR_STARTED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(startBroadcast);
        Log.d("BaseTransparentEditor", "Sent ACTION_EDITOR_STARTED broadcast");
        
        // Set content view from subclass
        setContentView(getLayoutResourceId());
        Log.d("BaseTransparentEditor", "Content view set");
        
        // Let subclass initialize views
        View rootView = findViewById(android.R.id.content);
        Log.d("BaseTransparentEditor", "Root view: " + rootView);
        
        // Apply safe area insets properly
        applySafeAreaInsets(rootView);
        
        onEditorViewCreated(rootView);
        Log.d("BaseTransparentEditor", "onCreate() completed");
    }
    
    /**
     * Setup edge-to-edge layout - make content draw behind system bars
     * but respect their space (system bars have alpha, content beneath is visible)
     */
    private void setupEdgeToEdge() {
        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        Log.d("BaseTransparentEditor", "Edge-to-edge layout enabled");
    }
    
    /**
     * Apply safe area insets to prevent content from overlaying system bars
     * Uses WindowInsetsCompat for compatibility across all API levels
     */
    private void applySafeAreaInsets(View rootView) {
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            // Get system bar insets (status bar, navigation bar, display cutout)
            int insetType = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top > 0 ? 
                           WindowInsetsCompat.Type.systemBars() : 
                           WindowInsetsCompat.Type.displayCutout();
            
            int top = insets.getInsets(insetType).top;
            int left = insets.getInsets(insetType).left;
            int right = insets.getInsets(insetType).right;
            
            Log.d("BaseTransparentEditor", "System insets - top:" + top + " left:" + left + " right:" + right);
            
            // Apply padding to root view to prevent content from going under bars
            v.setPadding(left, top, right, 0);
            
            // Consume insets to prevent further propagation
            return WindowInsetsCompat.CONSUMED;
        });
    }
    
    /**
     * Setup window for transparent overlay appearance
     */
    private void setupTransparentWindow() {
        // CRITICAL: Show on top of other app overlays (like AnnotationService overlay)
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        );
        
        // Make sure this activity can receive touches
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        
        // Handle keyboard properly - adjust resize so keyboard doesn't cover content
        getWindow().setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        );
        
        Log.d("BaseTransparentEditor", "Window configured for proper inset handling");
    }
    
    /**
     * Get the layout resource ID for this editor.
     * Must be implemented by subclasses.
     */
    protected abstract int getLayoutResourceId();
    
    /**
     * Called after view is inflated for subclass to initialize components.
     * 
     * @param rootView The root view of the activity
     */
    protected abstract void onEditorViewCreated(View rootView);
    
    /**
     * Finish with save result
     */
    protected void finishWithSave(Bundle resultData) {
        // Send broadcast for AnnotationService
        Intent broadcast = new Intent(ACTION_EDITOR_RESULT);
        broadcast.putExtra(EXTRA_RESULT_CODE, RESULT_SAVE);
        broadcast.putExtra(EXTRA_RESULT_DATA, resultData);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
        
        // Notify editor finished (re-enable annotation canvas)
        Intent finishBroadcast = new Intent(ACTION_EDITOR_FINISHED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(finishBroadcast);
        
        // Also set Activity result
        resultData.putString(EXTRA_RESULT_ACTION, ACTION_SAVE);
        setResult(RESULT_OK, getIntent().putExtras(resultData));
        finish();
        overridePendingTransition(0, 0); // No animation
    }
    
    /**
     * Finish with cancel result
     */
    protected void finishWithCancel() {
        // Send broadcast for AnnotationService
        Intent broadcast = new Intent(ACTION_EDITOR_RESULT);
        broadcast.putExtra(EXTRA_RESULT_CODE, RESULT_CANCELLED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
        
        // Notify editor finished (re-enable annotation canvas)
        Intent finishBroadcast = new Intent(ACTION_EDITOR_FINISHED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(finishBroadcast);
        
        Bundle resultData = new Bundle();
        resultData.putString(EXTRA_RESULT_ACTION, ACTION_CANCEL);
        setResult(RESULT_CANCELED, getIntent().putExtras(resultData));
        finish();
        overridePendingTransition(0, 0); // No animation
    }
    
    /**
     * Finish with delete result
     */
    protected void finishWithDelete(Bundle resultData) {
        // Send broadcast for AnnotationService
        Intent broadcast = new Intent(ACTION_EDITOR_RESULT);
        broadcast.putExtra(EXTRA_RESULT_CODE, RESULT_DELETE);
        broadcast.putExtra(EXTRA_RESULT_DATA, resultData);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
        
        // Notify editor finished (re-enable annotation canvas)
        Intent finishBroadcast = new Intent(ACTION_EDITOR_FINISHED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(finishBroadcast);
        
        resultData.putString(EXTRA_RESULT_ACTION, ACTION_DELETE);
        setResult(RESULT_OK, getIntent().putExtras(resultData));
        finish();
        overridePendingTransition(0, 0); // No animation
    }
    
    @Override
    public void onBackPressed() {
        // Back button cancels editing
        finishWithCancel();
    }
}
