package com.fadcam.ui.helpers;

import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.fadcam.Constants;
import com.fadcam.ui.components.ModeSwitcherComponent;

/**
 * Helper class for HomeFragment to keep the main fragment clean
 * Handles initialization of UI components and their interactions
 */
public class HomeFragmentHelper {
    private static final String TAG = "HomeFragmentHelper";

    private final Fragment fragment;
    private ModeSwitcherComponent modeSwitcherComponent;

    /**
     * Constructor
     * 
     * @param fragment The HomeFragment instance
     */
    public HomeFragmentHelper(@NonNull Fragment fragment) {
        this.fragment = fragment;
    }

    /**
     * Initialize all UI components for HomeFragment
     * 
     * @param view The root view of the fragment
     */
    public void initializeComponents(@NonNull View view) {
        try {
            // Initialize mode switcher component
            initializeModeSwitcher(view);

            Log.d(TAG, "All components initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing components", e);
        }
    }

    /**
     * Initialize the mode switcher component
     * 
     * @param view The root view containing the mode switcher
     */
    private void initializeModeSwitcher(@NonNull View view) {
        modeSwitcherComponent = new ModeSwitcherComponent(fragment.requireContext());

        // Set up listener for mode switcher events
        modeSwitcherComponent.setListener(new ModeSwitcherComponent.ModeSwitcherListener() {
            @Override
            public void onModeSelected(String mode) {
                handleModeSelection(mode);
            }

            @Override
            public void onComingSoonRequested(String modeName) {
                handleComingSoonRequest(modeName);
            }
        });

        // Initialize the component
        modeSwitcherComponent.initialize(view);

        Log.d(TAG, "ModeSwitcher component initialized");
    }

    /**
     * Handle mode selection events
     * 
     * @param mode The selected mode
     */
    private void handleModeSelection(String mode) {
        Log.d(TAG, "Mode selected: " + mode);

        switch (mode) {
            case Constants.MODE_FADCAM:
                // FadCam is already active - no action needed for now
                Log.d(TAG, "FadCam mode is already active");
                break;

            case Constants.MODE_FADREC:
                // Future: Handle screen recording mode
                Log.d(TAG, "FadRec mode will be implemented in future");
                break;

            case Constants.MODE_FADMIC:
                // Future: Handle mic recording mode
                Log.d(TAG, "FadMic mode will be implemented in future");
                break;
        }
    }

    /**
     * Handle coming soon requests
     * 
     * @param modeName The name of the mode that's coming soon
     */
    private void handleComingSoonRequest(String modeName) {
        Log.d(TAG, "Coming soon requested for: " + modeName);
        // Toast is already shown by the component
        // Future: Could track analytics or show more detailed info
    }

    /**
     * Get the mode switcher component
     * 
     * @return The ModeSwitcherComponent instance
     */
    public ModeSwitcherComponent getModeSwitcherComponent() {
        return modeSwitcherComponent;
    }

    /**
     * Clean up resources when fragment is destroyed
     */
    public void onDestroy() {
        // Clean up any resources if needed
        modeSwitcherComponent = null;
        Log.d(TAG, "HomeFragmentHelper cleaned up");
    }
}