package com.servalabs.cam.ui;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.servalabs.cam.Constants;
import com.servalabs.cam.SharedPreferencesManager;
import com.servalabs.cam.fadrec.ui.ServaRecHomeFragment;
import com.servalabs.cam.forensics.ui.ForensicIntelligenceFragment;

/**
 * ViewPagerAdapter manages fragments for the main ViewPager.
 * Creates appropriate HomeFragment based on current recording mode (ServaCam/ServaRec).
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
                return new EditorMiniMiniFragment();
            case 4:
                // Phase 1: Use new SettingsHomeFragment (legacy fragment accessible from inside)
                return new SettingsHomeFragment();
            case 5:
                return new ForensicIntelligenceFragment();
            default:
                return new HomeFragment();
        }
    }

    /**
     * Creates the appropriate HomeFragment based on current recording mode.
     * @return HomeFragment or ServaRecHomeFragment depending on mode
     */
    private Fragment createHomeFragment() {
        SharedPreferencesManager prefsManager = 
            SharedPreferencesManager.getInstance(fragmentActivity);
        
        String currentMode = prefsManager.getCurrentRecordingMode();
        
        if (Constants.MODE_FADREC.equals(currentMode)) {
            // ServaRec mode - create screen recording fragment
            return ServaRecHomeFragment.newInstance();
        } else {
            // ServaCam mode (default) - create camera recording fragment
            return new HomeFragment();
        }
    }

    @Override
    public int getItemCount() {
        return 6; // Home, Records, Remote, EditorMini Mini, Settings, Lab
    }
}
