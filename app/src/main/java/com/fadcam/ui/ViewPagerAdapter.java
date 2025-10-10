package com.fadcam.ui;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.fadcam.Constants;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.fadrec.ui.FadRecHomeFragment;

/**
 * ViewPagerAdapter manages fragments for the main ViewPager.
 * Creates appropriate HomeFragment based on current recording mode (FadCam/FadRec).
 */
public class ViewPagerAdapter extends FragmentStateAdapter {

    private final FragmentActivity fragmentActivity;

    public ViewPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
        this.fragmentActivity = fragmentActivity;
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 0:
                // Home tab - check current mode and create appropriate fragment
                return createHomeFragment();
            case 1:
                return new RecordsFragment();
            case 2:
                return new RemoteFragment();
            case 3:
                return new FaditorMiniFragment();
            case 4:
                // Phase 1: Use new SettingsHomeFragment (legacy fragment accessible from inside)
                return new SettingsHomeFragment();
            default:
                return new HomeFragment();
        }
    }

    /**
     * Creates the appropriate HomeFragment based on current recording mode.
     * @return HomeFragment or FadRecHomeFragment depending on mode
     */
    private Fragment createHomeFragment() {
        SharedPreferencesManager prefsManager = 
            SharedPreferencesManager.getInstance(fragmentActivity);
        
        String currentMode = prefsManager.getCurrentRecordingMode();
        
        if (Constants.MODE_FADREC.equals(currentMode)) {
            // FadRec mode - create screen recording fragment
            return FadRecHomeFragment.newInstance();
        } else {
            // FadCam mode (default) - create camera recording fragment
            return new HomeFragment();
        }
    }

    @Override
    public int getItemCount() {
        return 5; // Added Faditor Mini tab between Remote and Settings
    }
}
