package com.fadcam.ui.components;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

/**
 * Component for handling the mode switcher (FadCam, FadRec, FadMic) functionality
 * Follows modular design principles with clean separation of concerns
 * Features smooth animated transitions between modes
 */
public class ModeSwitcherComponent {
    private static final String TAG = "ModeSwitcherComponent";
    private static final int ANIMATION_DURATION = 160; // Text color fade only
    
    private final Context context;
    private final SharedPreferencesManager sharedPreferencesManager;
    private String currentMode = Constants.MODE_FADCAM;
    private ModeSwitcherListener listener;
    
    // UI References
    private FrameLayout segmentFadCam;
    private FrameLayout segmentFadRec;
    private FrameLayout segmentFadMic;

    // Guard during initialization
    private boolean isInitializing = false;
    
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
            isInitializing = true;

            segmentFadCam = rootView.findViewById(R.id.segment_fadcam);
            segmentFadRec = rootView.findViewById(R.id.segment_fadrec);
            segmentFadMic = rootView.findViewById(R.id.segment_fadmic);

            if (segmentFadCam == null || segmentFadRec == null || segmentFadMic == null) {
                Log.e(TAG, "Segment views missing");
                return;
            }

            // Remove FadRec badge (now available)
            View badgeFadRec = rootView.findViewById(R.id.badge_fadrec);
            if (badgeFadRec != null) badgeFadRec.setVisibility(View.GONE);

            // Resolve persisted mode (fallback if invalid / coming soon)
            String persisted = sharedPreferencesManager.getCurrentRecordingMode();
            if (Constants.MODE_FADMIC.equals(persisted)) persisted = Constants.MODE_FADCAM;
            currentMode = persisted;

            // Force clear all backgrounds first to prevent any hardcoded/cached backgrounds
            segmentFadCam.setBackground(null);
            segmentFadRec.setBackground(null);
            segmentFadMic.setBackground(null);

            // Set selection state (exclusive) - simple programmatic backgrounds
            // Use post() to ensure view is fully laid out
            rootView.post(() -> setExclusiveSelected(currentMode));

            setupClickListeners();
            Log.d(TAG, "ModeSwitcher initialized (active=" + currentMode + ")");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing ModeSwitcher", e);
        } finally {
            isInitializing = false;
        }
    }

    /**
     * Set one mode as active, others as inactive
     * Uses simple programmatic backgrounds - no drawable files
     */
    private void setExclusiveSelected(String mode) {
        applyBackground(segmentFadCam, Constants.MODE_FADCAM.equals(mode));
        applyBackground(segmentFadRec, Constants.MODE_FADREC.equals(mode));
        applyBackground(segmentFadMic, Constants.MODE_FADMIC.equals(mode));
        
        styleText(segmentFadCam, Constants.MODE_FADCAM.equals(mode));
        styleText(segmentFadRec, Constants.MODE_FADREC.equals(mode));
        styleText(segmentFadMic, Constants.MODE_FADMIC.equals(mode));
        
        Log.d(TAG, String.format("setExclusiveSelected(%s): FadCam=%b FadRec=%b FadMic=%b", 
            mode, Constants.MODE_FADCAM.equals(mode), 
            Constants.MODE_FADREC.equals(mode), 
            Constants.MODE_FADMIC.equals(mode)));
    }
    
    /**
     * Apply background programmatically - no drawable files needed
     * Creates a simple rounded rectangle with solid color
     */
    private void applyBackground(FrameLayout segment, boolean active) {
        if (segment == null) return;
        
        // Force clear existing background first
        segment.setBackground(null);
        
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(32f); // 32dp radius for rounded corners
        
        if (active) {
            // Active: Red fill (#E43C3C)
            background.setColor(ContextCompat.getColor(context, R.color.redPastel));
        } else {
            // Inactive: Transparent with subtle stroke
            background.setColor(ContextCompat.getColor(context, android.R.color.transparent));
            background.setStroke(2, ContextCompat.getColor(context, R.color.amoled_border));
        }
        
        segment.setBackground(background);
        segment.setSelected(active);
        
        Log.d(TAG, "applyBackground: " + segment.getId() + " active=" + active);
    }

    /**
     * Style text (color and weight) with optional animation
     */
    private void styleText(FrameLayout segment, boolean active) {
        if (segment == null) return;
        android.widget.TextView tv = (android.widget.TextView) segment.getChildAt(0);
        if (tv == null) return;
        
        int from = tv.getCurrentTextColor();
        int to = ContextCompat.getColor(context, active ? android.R.color.white : R.color.amoled_text_secondary);
        tv.setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        
        if (from != to) {
            ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
            anim.setDuration(ANIMATION_DURATION);
            anim.addUpdateListener(a -> tv.setTextColor((Integer) a.getAnimatedValue()));
            anim.start();
        } else {
            tv.setTextColor(to);
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
        
        if (isInitializing) return;

        if (mode.equals(currentMode)) return; // no-op

        if (Constants.MODE_FADMIC.equals(mode)) { // blocked mode
            showComingSoonToast("FadMic (Mic Recording)");
            if (listener != null) listener.onComingSoonRequested("FadMic");
            return;
        }

        currentMode = mode;
        setExclusiveSelected(currentMode);
        sharedPreferencesManager.setCurrentRecordingMode(currentMode);
        Log.d(TAG, "Mode switched -> " + currentMode);
        
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
    private void updateActiveState(String activeMode) { setExclusiveSelected(activeMode); }
    
    /**
     * Reset segment to inactive state with smooth animation
     * @param segment The segment to reset
     */
    private void resetSegmentState(FrameLayout segment) { }
    
    /**
     * Set segment to active state with smooth animation
     * @param segment The segment to activate
     */
    private void setSegmentActive(FrameLayout segment) { }
    
    /**
     * Animate background drawable change with smooth transition
     * @param segment The segment to animate
     * @param toActive Whether animating to active or inactive state
     */
    private void animateBackgroundChange(FrameLayout segment, boolean toActive) { }
    
    /**
     * Animate text color change
     * @param textView The TextView to animate
     * @param fromColor Starting color
     * @param toColor Ending color
     */
    private void animateTextColor(android.widget.TextView textView, int fromColor, int toColor) {
        ValueAnimator colorAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), fromColor, toColor);
        colorAnimator.setDuration(ANIMATION_DURATION);
        colorAnimator.addUpdateListener(animator -> 
            textView.setTextColor((Integer) animator.getAnimatedValue()));
        colorAnimator.start();
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