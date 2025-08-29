package com.fadcam.ui.faditor.utils;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.fadcam.Log;
import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.ui.faditor.FaditorEditorFragment;

/**
 * Utility class for handling navigation between Faditor fragments
 * Manages smooth fragment transitions and navigation stack
 */
public class NavigationUtils {
    
    private static final String TAG = "NavigationUtils";
    
    /**
     * Opens the full-screen editor fragment with the specified project
     * Uses the overlay fragment container for full-screen editing experience
     */
    public static void openEditor(Fragment currentFragment, String projectId) {
        Log.d(TAG, "Opening editor for project: " + projectId);
        
        if (currentFragment.getActivity() instanceof MainActivity) {
            MainActivity mainActivity = (MainActivity) currentFragment.getActivity();
            
            // Create editor fragment
            FaditorEditorFragment editorFragment = FaditorEditorFragment.newInstance(projectId);
            
            // Use the overlay fragment container for full-screen editing
            mainActivity.showOverlayFragment(editorFragment, "faditor_editor");
        } else {
            Log.e(TAG, "Cannot open editor: Activity is not MainActivity");
        }
    }
    
    /**
     * Returns to the project browser from the editor
     * Pops the editor fragment from the overlay container
     */
    public static void returnToBrowser(Fragment currentFragment) {
        Log.d(TAG, "Returning to project browser");
        
        FragmentManager fragmentManager = currentFragment.getParentFragmentManager();
        if (fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStack();
        }
    }
}