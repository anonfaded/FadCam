package com.fadcam;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.viewpager2.widget.ViewPager2;

import android.os.Bundle;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private BottomNavigationView bottomNavigationView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES); setContentView(R.layout.activity_main); //force dark theme even on light themed devices
        setContentView(R.layout.activity_main);

        viewPager = findViewById(R.id.view_pager);
        bottomNavigationView = findViewById(R.id.bottom_navigation);

        com.fadcam.ViewPagerAdapter adapter = new com.fadcam.ViewPagerAdapter(this);
        viewPager.setAdapter(adapter);

        bottomNavigationView.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.navigation_home) {
                viewPager.setCurrentItem(0);
            } else if (itemId == R.id.navigation_records) {
                viewPager.setCurrentItem(1);
            } else if (itemId == R.id.navigation_settings) {
                viewPager.setCurrentItem(2);
            } else if (itemId == R.id.navigation_about) {
                viewPager.setCurrentItem(3);
            }
            return true;
        });

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                switch (position) {
                    case 0:
                        bottomNavigationView.setSelectedItemId(R.id.navigation_home);
                        break;
                    case 1:
                        bottomNavigationView.setSelectedItemId(R.id.navigation_records);
                        break;
                    case 2:
                        bottomNavigationView.setSelectedItemId(R.id.navigation_settings);
                        break;
                    case 3:
                        bottomNavigationView.setSelectedItemId(R.id.navigation_about);
                        break;
                }
            }
        });
    }
}