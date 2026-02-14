package com.fadcam.forensics.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.fadcam.R;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

public class ForensicIntelligenceFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_forensic_intelligence, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        TabLayout tabLayout = view.findViewById(R.id.forensics_tab_layout);
        ViewPager2 pager = view.findViewById(R.id.forensics_view_pager);
        pager.setAdapter(new FragmentStateAdapter(this) {
            @NonNull
            @Override
            public Fragment createFragment(int position) {
                return position == 0
                    ? ForensicsEventsFragment.newEmbeddedInstance()
                    : ForensicsInsightsFragment.newEmbeddedInstance();
            }

            @Override
            public int getItemCount() {
                return 2;
            }
        });
        new TabLayoutMediator(tabLayout, pager, (tab, position) -> {
            tab.setText(position == 0
                ? R.string.forensics_events_title
                : R.string.forensics_insights_title);
        }).attach();
    }
}
