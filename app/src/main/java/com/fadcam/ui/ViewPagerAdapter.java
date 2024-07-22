package com.fadcam;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.fadcam.ui.AboutFragment;
import com.fadcam.ui.HomeFragment;
import com.fadcam.ui.RecordsFragment;
import com.fadcam.ui.SettingsFragment;

public class ViewPagerAdapter extends FragmentStateAdapter {

    public ViewPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 0:
                return new HomeFragment();
            case 1:
                return new RecordsFragment();
            case 2:
                return new SettingsFragment();
            case 3:
                return new AboutFragment();
            default:
                return new HomeFragment();
        }
    }

    @Override
    public int getItemCount() {
        return 4; // Number of fragments
    }
}