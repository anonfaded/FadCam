package com.fadcam.ui.helpers;

import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.ui.components.ModeSwitcherComponent;

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

        // Save the selected mode
        sharedPreferencesManager.setCurrentRecordingMode(mode);

        switch (mode) {
            case Constants.MODE_FADCAM:
                // FadCam mode - regular camera recording
                Log.d(TAG, "FadCam mode selected");
                // Recreate fragment to show HomeFragment
                recreateHomeFragment();
                break;

            case Constants.MODE_FADREC:
                // FadRec mode - screen recording
                Log.d(TAG, "FadRec mode selected");
                // Recreate fragment to show FadRecHomeFragment
                recreateHomeFragment();
                break;

            case Constants.MODE_FADMIC:
                // Future: Handle mic recording mode
                Log.d(TAG, "FadMic mode will be implemented in future");
                break;
        }
    }

    /**
     * Recreate the home fragment to switch between FadCam and FadRec modes.
     * This triggers ViewPagerAdapter to create the appropriate fragment.
     */
    private void recreateHomeFragment() {
        try {
            FragmentActivity activity = fragment.getActivity();
            if (activity == null) {
                Log.w(TAG, "Activity is null, cannot recreate fragment");
                return;
            }

            // Find the ViewPager
            ViewPager2 viewPager = activity.findViewById(R.id.view_pager);
            if (viewPager == null) {
                Log.w(TAG, "ViewPager not found");
                return;
            }

            // Get current position
            int currentPosition = viewPager.getCurrentItem();
            
            // Force adapter to recreate fragments by setting adapter again
            FragmentStateAdapter adapter = (FragmentStateAdapter) viewPager.getAdapter();
            if (adapter != null) {
                viewPager.setAdapter(adapter);
                // Navigate back to home tab to show the new fragment
                viewPager.setCurrentItem(currentPosition, false);
                Log.d(TAG, "Home fragment recreated for mode switch");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error recreating home fragment", e);
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