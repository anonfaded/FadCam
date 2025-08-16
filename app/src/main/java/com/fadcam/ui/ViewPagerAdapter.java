package com.fadcam.ui;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class ViewPagerAdapter extends FragmentStateAdapter {

    public ViewPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 1:
                return new RecordsFragment();
            case 2:
                return new RemoteFragment();
            case 3:
                // Phase 1: Use new SettingsHomeFragment (legacy fragment accessible from inside)
                return new SettingsHomeFragment();
            default:
                return new HomeFragment();
        }
    }

    @Override
    public int getItemCount() {
    return 4; // About tab removed; accessible via Settings overlay
    }
}