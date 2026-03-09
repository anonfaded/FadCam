package com.fadcam.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

/**
 * Watch-optimized main activity for Wear OS smartwatch devices.
 *
 * <p>4-page swipe navigation:</p>
 * <ol>
 *   <li>Camera — {@link WatchCameraFragment}</li>
 *   <li>Records — {@link WatchRecordsFragment}</li>
 *   <li>Remote  — {@link WatchRemoteFragment}</li>
 *   <li>Settings — {@link WatchSettingsFragment}</li>
 * </ol>
 *
 * <p>An overlay FrameLayout ({@code R.id.overlay_fragment_container}) allows
 * full-screen overlay fragments (e.g. {@link TrashFragment}) to be shown via
 * {@link OverlayNavUtil}.</p>
 */
public class WatchMainActivity extends AppCompatActivity {

    private static final int PAGE_COUNT = 4;

    private ViewPager2 viewPager;
    private final View[] dots = new View[PAGE_COUNT];
    private boolean uiInitialized = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Check for onboarding BEFORE applying theme or setting content view
        SharedPreferencesManager sharedPreferencesManager = SharedPreferencesManager.getInstance(this);

        // Check if this is a first install
        boolean firstInstallChecked = sharedPreferencesManager.sharedPreferences
                .getBoolean(Constants.FIRST_INSTALL_CHECKED_KEY, false);

        if (!firstInstallChecked) {
            // This is definitely a first install or app data was cleared
            // Force onboarding to show by setting the flag to false
            android.util.Log.d("WatchMainActivity", "First install detected! Forcing onboarding to show.");
            sharedPreferencesManager.sharedPreferences.edit()
                    .putBoolean(Constants.COMPLETED_ONBOARDING_KEY, false)
                    .putBoolean(Constants.FIRST_INSTALL_CHECKED_KEY, true)
                    .commit(); // Use commit() for immediate effect
        }

        // Check for onboarding
        boolean completedOnboarding = sharedPreferencesManager.sharedPreferences.getBoolean(Constants.COMPLETED_ONBOARDING_KEY, false);
        boolean showOnboarding = sharedPreferencesManager.isShowOnboarding();
        android.util.Log.d("WatchMainActivity", "DEBUG - COMPLETED_ONBOARDING_KEY raw value: " + completedOnboarding);
        android.util.Log.d("WatchMainActivity", "DEBUG - isShowOnboarding() result: " + showOnboarding);
        android.util.Log.d("WatchMainActivity", "Should show onboarding: " + showOnboarding);

        if (showOnboarding) {
            // User has NOT completed onboarding yet - show full onboarding first
            Intent intent = new Intent(this, OnboardingActivity.class);
            startActivity(intent);
            // Don't finish - let OnboardingActivity return here when completed
            return;
        }

        // Onboarding completed, initialize UI
        initializeUI();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Check if onboarding was completed while we were paused
        SharedPreferencesManager sharedPreferencesManager = SharedPreferencesManager.getInstance(this);
        boolean completedOnboarding = sharedPreferencesManager.sharedPreferences.getBoolean(Constants.COMPLETED_ONBOARDING_KEY, false);

        if (completedOnboarding && !uiInitialized) {
            // Onboarding just completed, initialize UI now
            initializeUI();
        }
    }

    private void initializeUI() {
        if (uiInitialized) return; // Already initialized

        applyTheme(); // Must be called before setContentView()
        setContentView(R.layout.activity_watch_main);

        viewPager = findViewById(R.id.watch_viewpager);
        dots[0]   = findViewById(R.id.dot_0);
        dots[1]   = findViewById(R.id.dot_1);
        dots[2]   = findViewById(R.id.dot_2);
        dots[3]   = findViewById(R.id.dot_3);

        viewPager.setAdapter(new WatchPagerAdapter(this));
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateDots(position);
            }
        });

        updateDots(0);
        uiInitialized = true;
    }

    /** Disable viewpager swiping while an overlay is showing. */
    public void setSwipeEnabled(boolean enabled) {
        viewPager.setUserInputEnabled(enabled);
    }

    private void updateDots(int selectedPage) {
        for (int i = 0; i < PAGE_COUNT; i++) {
            if (dots[i] != null) {
                dots[i].setBackgroundResource(
                        i == selectedPage
                                ? R.drawable.watch_dot_selected
                                : R.drawable.watch_dot_unselected);
            }
        }
    }

    private static final class WatchPagerAdapter extends FragmentStateAdapter {
        WatchPagerAdapter(@NonNull FragmentActivity fa) {
            super(fa);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case 1:  return new WatchRecordsFragment();
                case 2:  return new WatchRemoteFragment();
                case 3:  return new WatchSettingsFragment();
                default: return new WatchCameraFragment();
            }
        }

        @Override
        public int getItemCount() {
            return PAGE_COUNT;
        }
    }

    /**
     * Applies the user-selected theme before any views are created.
     * Mirrors the logic in {@link com.fadcam.MainActivity#applyTheme()}.
     * Must be called BEFORE {@link #setContentView}.
     */
    private void applyTheme() {
        SharedPreferencesManager sp = SharedPreferencesManager.getInstance(this);
        String themeName = sp.sharedPreferences.getString(
                Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        if (themeName == null || themeName.isEmpty()) {
            themeName = Constants.DEFAULT_APP_THEME;
        }
        
        // Apply theme and set window colors to prevent red overlay on small watch screens
        if ("Faded Night".equals(themeName) || "AMOLED".equals(themeName) || "amoled".equals(themeName)) {
            setTheme(R.style.Theme_FadCam_Amoled);
            getWindow().setNavigationBarColor(getResources().getColor(R.color.amoled_background, getTheme()));
        } else if ("Crimson Bloom".equals(themeName)) {
            setTheme(R.style.Theme_FadCam_Red);
            getWindow().setNavigationBarColor(getResources().getColor(R.color.red_theme_background_dark, getTheme()));
        } else if ("Premium Gold".equals(themeName)) {
            setTheme(R.style.Theme_FadCam_Gold);
            getWindow().setNavigationBarColor(getResources().getColor(R.color.gold_theme_background_dark, getTheme()));
        } else if ("Silent Forest".equals(themeName)) {
            setTheme(R.style.Theme_FadCam_SilentForest);
            getWindow().setNavigationBarColor(getResources().getColor(R.color.silentforest_theme_background_dark, getTheme()));
        } else if ("Shadow Alloy".equals(themeName)) {
            setTheme(R.style.Theme_FadCam_ShadowAlloy);
            getWindow().setNavigationBarColor(getResources().getColor(R.color.shadowalloy_theme_background_dark, getTheme()));
        } else if ("Pookie Pink".equals(themeName)) {
            setTheme(R.style.Theme_FadCam_PookiePink);
            getWindow().setNavigationBarColor(getResources().getColor(R.color.pookiepink_theme_background_dark, getTheme()));
        } else if ("Snow Veil".equals(themeName)) {
            setTheme(R.style.Theme_FadCam_SnowVeil);
            getWindow().setNavigationBarColor(getResources().getColor(R.color.snowveil_theme_background_light, getTheme()));
        } else if ("Midnight Dusk".equals(themeName)) {
            setTheme(R.style.Theme_FadCam_MidnightDusk);
            getWindow().setNavigationBarColor(getResources().getColor(R.color.gray, getTheme()));
        } else {
            // Default fallback — matches Constants.DEFAULT_APP_THEME
            setTheme(R.style.Theme_FadCam_Red);
            getWindow().setNavigationBarColor(getResources().getColor(R.color.red_theme_background_dark, getTheme()));
        }
    }
}
