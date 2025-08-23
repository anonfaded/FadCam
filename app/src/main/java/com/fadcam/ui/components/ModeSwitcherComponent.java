package com.fadcam.ui.components;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.fadcam.Constants;
import com.fadcam.R;

/**
 * Component for handling the mode switcher (FadCam, FadRec, FadMic) functionality
 * Follows modular design principles with clean separation of concerns
 */
public class ModeSwitcherComponent {
    private static final String TAG = "ModeSwitcherComponent";
    
    private final Context context;
    private String currentMode = Constants.MODE_FADCAM;
    private ModeSwitcherListener listener;
    
    // UI References
    private FrameLayout segmentFadCam;
    private FrameLayout segmentFadRec;
    private FrameLayout segmentFadMic;
    
    /**
     * Interface for mode switcher callbacks
     */
    public interface ModeSwitcherListener {
        void onModeSelected(String mode);
        void onComingSoonRequested(String modeName);
    }
    
    /**
     * Constructor
     * @param context Application context
     */
    public ModeSwitcherComponent(@NonNull Context context) {
        this.context = context;
    }
    
    /**
     * Initialize the mode switcher with the provided view
     * @param rootView The root view containing the mode switcher
     */
    public void initialize(@NonNull View rootView) {
        try {
            // Find mode switcher segments
            segmentFadCam = rootView.findViewById(R.id.segment_fadcam);
            segmentFadRec = rootView.findViewById(R.id.segment_fadrec);
            segmentFadMic = rootView.findViewById(R.id.segment_fadmic);
            
            // Setup click listeners
            setupClickListeners();
            
            // Set initial state
            updateActiveState(Constants.MODE_FADCAM);
            
            Log.d(TAG, "ModeSwitcher initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing ModeSwitcher", e);
        }
    }
    
    /**
     * Setup click listeners for all segments
     */
    private void setupClickListeners() {
        if (segmentFadCam != null) {
            segmentFadCam.setOnClickListener(v -> handleModeClick(Constants.MODE_FADCAM));
        }
        
        if (segmentFadRec != null) {
            segmentFadRec.setOnClickListener(v -> handleModeClick(Constants.MODE_FADREC));
        }
        
        if (segmentFadMic != null) {
            segmentFadMic.setOnClickListener(v -> handleModeClick(Constants.MODE_FADMIC));
        }
    }
    
    /**
     * Handle mode selection clicks
     * @param mode The selected mode
     */
    private void handleModeClick(String mode) {
        Log.d(TAG, "Mode clicked: " + mode);
        
        switch (mode) {
            case Constants.MODE_FADCAM:
                // FadCam is already active, no action needed
                if (listener != null) {
                    listener.onModeSelected(mode);
                }
                break;
                
            case Constants.MODE_FADREC:
                // Show coming soon for FadRec (Screen Recording)
                showComingSoonToast("FadRec (Screen Recording)");
                if (listener != null) {
                    listener.onComingSoonRequested("FadRec");
                }
                break;
                
            case Constants.MODE_FADMIC:
                // Show coming soon for FadMic (Mic Recording)
                showComingSoonToast("FadMic (Mic Recording)");
                if (listener != null) {
                    listener.onComingSoonRequested("FadMic");
                }
                break;
        }
    }
    
    /**
     * Update the active state visual indicator
     * @param activeMode The currently active mode
     */
    private void updateActiveState(String activeMode) {
        currentMode = activeMode;
        
        // Reset all segments to inactive state
        resetSegmentState(segmentFadCam);
        resetSegmentState(segmentFadRec);
        resetSegmentState(segmentFadMic);
        
        // Set active segment
        FrameLayout activeSegment = getSegmentForMode(activeMode);
        if (activeSegment != null) {
            activeSegment.setSelected(true);
        }
        
        Log.d(TAG, "Active mode updated to: " + activeMode);
    }
    
    /**
     * Reset segment to inactive state
     * @param segment The segment to reset
     */
    private void resetSegmentState(FrameLayout segment) {
        if (segment != null) {
            segment.setSelected(false);
        }
    }
    
    /**
     * Get the segment view for a specific mode
     * @param mode The mode to get segment for
     * @return The corresponding FrameLayout segment
     */
    private FrameLayout getSegmentForMode(String mode) {
        switch (mode) {
            case Constants.MODE_FADCAM: return segmentFadCam;
            case Constants.MODE_FADREC: return segmentFadRec;
            case Constants.MODE_FADMIC: return segmentFadMic;
            default: return null;
        }
    }
    
    /**
     * Show coming soon toast message
     * @param modeName The name of the mode
     */
    private void showComingSoonToast(String modeName) {
        String message = modeName + " coming soon! 🚀";
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        Log.d(TAG, "Showed coming soon toast for: " + modeName);
    }
    
    /**
     * Set the mode switcher listener
     * @param listener The listener to set
     */
    public void setListener(ModeSwitcherListener listener) {
        this.listener = listener;
    }
    
    /**
     * Get the current active mode
     * @return The current mode
     */
    public String getCurrentMode() {
        return currentMode;
    }
    
    /**
     * Programmatically set the active mode
     * @param mode The mode to set as active
     */
    public void setActiveMode(String mode) {
        updateActiveState(mode);
    }
    
    /**
     * Check if the component is properly initialized
     * @return true if initialized, false otherwise
     */
    public boolean isInitialized() {
        return segmentFadCam != null && segmentFadRec != null && segmentFadMic != null;
    }
}