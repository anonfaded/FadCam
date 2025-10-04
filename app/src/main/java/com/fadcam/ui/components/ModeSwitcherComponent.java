package com.fadcam.ui.components;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

/**
 * Component for handling the mode switcher (FadCam, FadRec, FadMic) functionality
 * Follows modular design principles with clean separation of concerns
 */
public class ModeSwitcherComponent {
    private static final String TAG = "ModeSwitcherComponent";
    
    private final Context context;
    private final SharedPreferencesManager sharedPreferencesManager;
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
        this.sharedPreferencesManager = SharedPreferencesManager.getInstance(context);
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
            
            // Hide "Soon" badge for FadRec since it's now available
            View badgeFadRec = rootView.findViewById(R.id.badge_fadrec);
            if (badgeFadRec != null) {
                badgeFadRec.setVisibility(View.GONE);
            }
            
            // Setup click listeners
            setupClickListeners();
            
            // Set initial state from SharedPreferences
            String currentMode = sharedPreferencesManager.getCurrentRecordingMode();
            updateActiveState(currentMode);
            
            Log.d(TAG, "ModeSwitcher initialized successfully with mode: " + currentMode);
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
        
        // Update visual state for all modes
        updateActiveState(mode);
        
        switch (mode) {
            case Constants.MODE_FADCAM:
                // FadCam mode selected
                if (listener != null) {
                    listener.onModeSelected(mode);
                }
                break;
                
            case Constants.MODE_FADREC:
                // FadRec (Screen Recording) mode selected
                if (listener != null) {
                    listener.onModeSelected(mode);
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
            setSegmentActive(activeSegment);
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
            segment.setBackgroundResource(R.drawable.segment_inactive_background);
            
            // Update text color to inactive
            android.widget.TextView textView = (android.widget.TextView) segment.getChildAt(0);
            if (textView != null) {
                textView.setTextColor(context.getResources().getColor(R.color.amoled_text_secondary, null));
            }
        }
    }
    
    /**
     * Set segment to active state
     * @param segment The segment to activate
     */
    private void setSegmentActive(FrameLayout segment) {
        if (segment != null) {
            segment.setSelected(true);
            segment.setBackgroundResource(R.drawable.segment_active_background);
            
            // Update text color to active (white)
            android.widget.TextView textView = (android.widget.TextView) segment.getChildAt(0);
            if (textView != null) {
                textView.setTextColor(context.getResources().getColor(android.R.color.white, null));
                textView.setTypeface(null, android.graphics.Typeface.BOLD);
            }
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