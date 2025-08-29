package com.fadcam.ui.faditor.utils;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.fadcam.Log;
import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.ui.faditor.FaditorEditorFragment;
import com.fadcam.ui.FaditorMiniFragment;
import com.fadcam.ui.faditor.persistence.AutoSaveManager;

/**
 * Enhanced utility class for handling navigation between Faditor fragments
 * Implements Material 3 motion patterns and seamless auto-save integration
 * Requirements: 10.4, 10.5, 12.2, 12.7
 */
public class NavigationUtils {
    
    private static final String TAG = "NavigationUtils";
    
    // Material 3 motion durations (following Material Design guidelines)
    private static final int ENTER_DURATION_MS = 300;
    private static final int EXIT_DURATION_MS = 200;
    private static final int FADE_DURATION_MS = 150;
    
    // Fragment tags for navigation stack management
    private static final String EDITOR_FRAGMENT_TAG = "faditor_editor";
    private static final String BROWSER_FRAGMENT_TAG = "faditor_browser";
    
    // Navigation state tracking
    private static boolean isNavigating = false;
    
    /**
     * Opens the full-screen editor fragment with the specified project
     * Implements Material 3 motion patterns and auto-save integration
     * Requirements: 10.1, 10.2, 12.2
     */
    public static void openEditor(Fragment currentFragment, String projectId) {
        if (isNavigating) {
            Log.d(TAG, "Navigation already in progress, ignoring request");
            return;
        }
        
        Log.d(TAG, "Opening editor for project: " + projectId);
        
        if (!(currentFragment.getActivity() instanceof MainActivity)) {
            Log.e(TAG, "Cannot open editor: Activity is not MainActivity");
            return;
        }
        
        MainActivity mainActivity = (MainActivity) currentFragment.getActivity();
        isNavigating = true;
        
        // Trigger auto-save on current fragment if it's FaditorMiniFragment
        if (currentFragment instanceof FaditorMiniFragment) {
            FaditorMiniFragment browserFragment = (FaditorMiniFragment) currentFragment;
            // Auto-save is handled by the browser fragment's lifecycle
        }
        
        // Create editor fragment
        FaditorEditorFragment editorFragment = FaditorEditorFragment.newInstance(projectId);
        
        // Apply Material 3 enter transition
        applyMaterial3EnterTransition(mainActivity, editorFragment, () -> {
            isNavigating = false;
        });
    }
    
    /**
     * Returns to the project browser from the editor
     * Implements Material 3 exit transitions and auto-save integration
     * Requirements: 10.4, 10.5, 12.2, 12.7
     */
    public static void returnToBrowser(Fragment currentFragment) {
        if (isNavigating) {
            Log.d(TAG, "Navigation already in progress, ignoring request");
            return;
        }
        
        Log.d(TAG, "Returning to project browser");
        isNavigating = true;
        
        // Trigger immediate auto-save if current fragment is editor
        if (currentFragment instanceof FaditorEditorFragment) {
            FaditorEditorFragment editorFragment = (FaditorEditorFragment) currentFragment;
            AutoSaveManager autoSaveManager = editorFragment.getAutoSaveManager();
            if (autoSaveManager != null) {
                autoSaveManager.saveImmediately(); // Requirement 12.2
            }
        }
        
        // Apply Material 3 exit transition
        applyMaterial3ExitTransition(currentFragment, () -> {
            // Pop from back stack after transition completes
            FragmentManager fragmentManager = currentFragment.getParentFragmentManager();
            if (fragmentManager.getBackStackEntryCount() > 0) {
                fragmentManager.popBackStack();
            }
            isNavigating = false;
        });
    }
    
    /**
     * Handles back button press with proper navigation stack management
     * Requirements: 10.4, 10.5
     */
    public static boolean handleBackPress(Fragment currentFragment) {
        if (isNavigating) {
            return true; // Consume the back press during navigation
        }
        
        if (currentFragment instanceof FaditorEditorFragment) {
            FaditorEditorFragment editorFragment = (FaditorEditorFragment) currentFragment;
            
            // Check for unsaved changes and handle accordingly
            if (editorFragment.hasUnsavedChanges()) {
                editorFragment.showUnsavedChangesDialog();
                return true; // Handled by dialog
            } else {
                // No unsaved changes, proceed with navigation
                returnToBrowser(currentFragment);
                return true; // Handled
            }
        }
        
        return false; // Not handled, let system handle
    }
    
    /**
     * Checks if navigation is currently in progress
     */
    public static boolean isNavigating() {
        return isNavigating;
    }
    
    /**
     * Clears navigation state (for error recovery)
     */
    public static void clearNavigationState() {
        isNavigating = false;
    }
    
    // Private helper methods for Material 3 transitions
    
    /**
     * Applies Material 3 enter transition for opening editor
     * Uses shared axis transition pattern
     */
    private static void applyMaterial3EnterTransition(MainActivity activity, 
                                                     FaditorEditorFragment editorFragment, 
                                                     Runnable onComplete) {
        View overlayContainer = activity.findViewById(R.id.overlay_fragment_container);
        if (overlayContainer == null) {
            Log.e(TAG, "Overlay container not found");
            if (onComplete != null) onComplete.run();
            return;
        }
        
        // Set initial state for shared axis transition
        overlayContainer.setVisibility(View.VISIBLE);
        overlayContainer.setAlpha(0f);
        overlayContainer.setTranslationX(100f); // Slide in from right
        
        // Add fragment to container
        activity.getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.overlay_fragment_container, editorFragment, EDITOR_FRAGMENT_TAG)
                .addToBackStack(EDITOR_FRAGMENT_TAG)
                .commitAllowingStateLoss();
        
        // Animate entrance with Material 3 motion
        ObjectAnimator fadeIn = ObjectAnimator.ofFloat(overlayContainer, "alpha", 0f, 1f);
        fadeIn.setDuration(FADE_DURATION_MS);
        fadeIn.setInterpolator(new DecelerateInterpolator());
        
        ObjectAnimator slideIn = ObjectAnimator.ofFloat(overlayContainer, "translationX", 100f, 0f);
        slideIn.setDuration(ENTER_DURATION_MS);
        slideIn.setInterpolator(new DecelerateInterpolator());
        
        fadeIn.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (onComplete != null) onComplete.run();
            }
        });
        
        fadeIn.start();
        slideIn.start();
    }
    
    /**
     * Applies Material 3 exit transition for returning to browser
     * Uses shared axis transition pattern
     */
    private static void applyMaterial3ExitTransition(Fragment currentFragment, Runnable onComplete) {
        View fragmentView = currentFragment.getView();
        if (fragmentView == null) {
            Log.e(TAG, "Fragment view not found for exit transition");
            if (onComplete != null) onComplete.run();
            return;
        }
        
        // Animate exit with Material 3 motion
        ObjectAnimator fadeOut = ObjectAnimator.ofFloat(fragmentView, "alpha", 1f, 0f);
        fadeOut.setDuration(FADE_DURATION_MS);
        fadeOut.setInterpolator(new AccelerateInterpolator());
        
        ObjectAnimator slideOut = ObjectAnimator.ofFloat(fragmentView, "translationX", 0f, -100f);
        slideOut.setDuration(EXIT_DURATION_MS);
        slideOut.setInterpolator(new AccelerateInterpolator());
        
        fadeOut.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (onComplete != null) onComplete.run();
            }
        });
        
        fadeOut.start();
        slideOut.start();
    }
    
    /**
     * Applies fade transition for overlay container
     * Used for subtle state changes
     */
    private static void applyFadeTransition(View view, float fromAlpha, float toAlpha, 
                                          Runnable onComplete) {
        if (view == null) {
            if (onComplete != null) onComplete.run();
            return;
        }
        
        ObjectAnimator fadeAnimator = ObjectAnimator.ofFloat(view, "alpha", fromAlpha, toAlpha);
        fadeAnimator.setDuration(FADE_DURATION_MS);
        fadeAnimator.setInterpolator(new DecelerateInterpolator());
        
        if (onComplete != null) {
            fadeAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    onComplete.run();
                }
            });
        }
        
        fadeAnimator.start();
    }
    
    /**
     * Manages navigation stack state for proper back button handling
     * Requirements: 10.4, 10.5
     */
    public static void manageNavigationStack(FragmentManager fragmentManager) {
        int backStackCount = fragmentManager.getBackStackEntryCount();
        Log.d(TAG, "Navigation stack count: " + backStackCount);
        
        // Ensure proper stack management
        if (backStackCount > 2) {
            // Prevent stack overflow by limiting depth
            Log.w(TAG, "Navigation stack too deep, clearing excess entries");
            while (fragmentManager.getBackStackEntryCount() > 1) {
                fragmentManager.popBackStackImmediate();
            }
        }
    }
    
    /**
     * Integrates with auto-save manager for seamless navigation
     * Requirements: 12.2, 12.7
     */
    public static void integrateAutoSave(Fragment fragment, AutoSaveManager autoSaveManager) {
        if (autoSaveManager == null) {
            Log.w(TAG, "AutoSaveManager is null, cannot integrate");
            return;
        }
        
        // Set up lifecycle-aware auto-save
        if (fragment.getLifecycle() != null) {
            fragment.getLifecycle().addObserver(autoSaveManager);
        }
        
        Log.d(TAG, "Auto-save integration completed for fragment: " + 
              fragment.getClass().getSimpleName());
    }
}