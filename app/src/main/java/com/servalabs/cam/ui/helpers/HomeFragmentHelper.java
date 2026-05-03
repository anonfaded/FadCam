package com.servalabs.cam.ui.helpers;

import com.servalabs.cam.Log;
import com.servalabs.cam.FLog;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.servalabs.cam.Constants;
import com.servalabs.cam.R;
import com.servalabs.cam.SharedPreferencesManager;
import com.servalabs.cam.ui.components.ModeSwitcherComponent;

/**
 * Helper class for HomeFragment to keep the main fragment clean
 * Handles initialization of UI components and their interactions
 */
public class HomeFragmentHelper {
    private static final String TAG = "HomeFragmentHelper";

    private final Fragment fragment;
    private ModeSwitcherComponent modeSwitcherComponent;
    private SharedPreferencesManager sharedPreferencesManager;

    /**
     * Constructor
     * 
     * @param fragment The HomeFragment instance
     */
    public HomeFragmentHelper(@NonNull Fragment fragment) {
        this.fragment = fragment;
        this.sharedPreferencesManager = SharedPreferencesManager.getInstance(fragment.requireContext());
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

            FLog.d(TAG, "All components initialized successfully");
        } catch (Exception e) {
            FLog.e(TAG, "Error initializing components", e);
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

        FLog.d(TAG, "ModeSwitcher component initialized");
    }

    /**
     * Handle mode selection events
     * 
     * @param mode The selected mode
     */
    private void handleModeSelection(String mode) {
        FLog.d(TAG, "Mode selected: " + mode);

        // Save the selected mode
        sharedPreferencesManager.setCurrentRecordingMode(mode);

        switch (mode) {
            case Constants.MODE_FADCAM:
                // ServaCam mode - regular camera recording
                FLog.d(TAG, "ServaCam mode selected");
                // Recreate fragment to show HomeFragment
                recreateHomeFragment();
                break;

            case Constants.MODE_FADREC:
                // ServaRec mode - screen recording
                FLog.d(TAG, "ServaRec mode selected");
                // Recreate fragment to show FadRecHomeFragment
                recreateHomeFragment();
                break;

            case Constants.MODE_FADMIC:
                // Future: Handle mic recording mode
                FLog.d(TAG, "ServaMic mode will be implemented in future");
                break;
        }
    }

    /**
     * Recreate the home fragment to switch between ServaCam and ServaRec modes.
     * With hide/show navigation, we must force remove and re-add the fragment
     * since switchFragment() would return early (same position).
     */
    private void recreateHomeFragment() {
        try {
            FragmentActivity activity = fragment.getActivity();
            if (activity == null) {
                FLog.w(TAG, "Activity is null, cannot recreate fragment");
                return;
            }

            // Use MainActivity to force recreate the Home fragment for mode switch
            if (activity instanceof com.servalabs.cam.MainActivity) {
                com.servalabs.cam.MainActivity mainActivity = (com.servalabs.cam.MainActivity) activity;
                View continuityView = fragment.getView();
                
                // Force recreate position 0 (Home tab)
                mainActivity.forceRecreateFragment(0, continuityView);
                FLog.d(TAG, "Home fragment recreated for mode switch");
            } else {
                FLog.w(TAG, "Activity is not MainActivity");
            }
        } catch (Exception e) {
            FLog.e(TAG, "Error recreating home fragment", e);
        }
    }

    /**
     * Handle coming soon requests
     * 
     * @param modeName The name of the mode that's coming soon
     */
    private void handleComingSoonRequest(String modeName) {
        FLog.d(TAG, "Coming soon requested for: " + modeName);
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

    public void syncModeSwitcherToCurrentPreference() {
        if (modeSwitcherComponent == null) {
            return;
        }
        String mode = sharedPreferencesManager.getCurrentRecordingMode();
        if (Constants.MODE_FADMIC.equals(mode)) {
            mode = Constants.MODE_FADCAM;
        }
        modeSwitcherComponent.setActiveMode(mode);
    }

    /**
     * Clean up resources when fragment is destroyed
     */
    public void onDestroy() {
        // Clean up any resources if needed
        modeSwitcherComponent = null;
        FLog.d(TAG, "HomeFragmentHelper cleaned up");
    }
}
