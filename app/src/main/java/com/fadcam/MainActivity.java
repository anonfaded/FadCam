package com.fadcam;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.fadcam.ui.RecordsFragment;
import com.fadcam.ui.TrashFragment;
import com.fadcam.ui.ViewPagerAdapter;
import com.fadcam.ui.FadePageTransformer;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;

public class MainActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private BottomNavigationView bottomNavigationView;

    // ----- Fix Start: Add fields for double-back to exit -----
    private boolean doubleBackToExitPressedOnce = false;
    private boolean skipNextBackHandling = false; // New flag to skip toast on next back press
    private Handler backPressHandler = new Handler();
    private static final int BACK_PRESS_DELAY = 2000; // 2 seconds

    private final Runnable backPressRunnable = new Runnable() {
        @Override
        public void run() {
            doubleBackToExitPressedOnce = false;
        }
    };
    // ----- Fix End: Add fields for double-back to exit -----

    // ----- Fix Start: Add method to disable back toast temporarily -----
    /**
     * Public method to be called from fragments that need to disable the double-back toast temporarily
     * This will prevent the "Press back again to exit" toast from showing on the next back press
     */
    public void skipNextBackExitHandling() {
        skipNextBackHandling = true;
        // Reset automatically after a delay
        backPressHandler.postDelayed(() -> skipNextBackHandling = false, 1000);
    }
    // ----- Fix End: Add method to disable back toast temporarily -----

    // ----- Fix Start: Add method to check if trash fragment is visible -----
    /**
     * Checks if the TrashFragment is currently visible in the overlay container
     * @return true if TrashFragment is visible, false otherwise
     */
    private boolean isTrashFragmentVisible() {
        View overlayContainer = findViewById(R.id.overlay_fragment_container);
        if (overlayContainer != null && overlayContainer.getVisibility() == View.VISIBLE) {
            Fragment fragment = getSupportFragmentManager()
                    .findFragmentById(R.id.overlay_fragment_container);
            return fragment instanceof TrashFragment;
        }
        return false;
    }
    // ----- Fix End: Add method to check if trash fragment is visible -----

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ----- Fix Start: Apply selected theme globally before setContentView -----
        SharedPreferencesManager sharedPreferencesManager = SharedPreferencesManager.getInstance(this);
        
        // Get the saved theme
        String savedTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME, "Midnight Dusk");
        Log.d("MainActivity", "Saved theme: " + savedTheme);
        
        // Check if theme is one of the AMOLED variants
        boolean isAmoledTheme = "Faded Night".equals(savedTheme) ||
                "AMOLED".equals(savedTheme) ||
                "Amoled".equals(savedTheme) || 
                "amoled".equals(savedTheme);

        // This applies the theme but theme changes to some components only take effect on restart
        if (isAmoledTheme) {
            // Use true AMOLED theme (pure black background)
            setTheme(R.style.Theme_FadCam_Amoled);
            
            // Standardize theme name to "Faded Night"
            if (!"Faded Night".equals(savedTheme)) {
                sharedPreferencesManager.sharedPreferences.edit()
                .putString(Constants.PREF_APP_THEME, "Faded Night")
                .apply();
                savedTheme = "Faded Night";
            }
            
            getWindow().setNavigationBarColor(getResources().getColor(R.color.amoled_background, getTheme()));
            
        } else if ("Crimson Bloom".equals(savedTheme)) {
            // Red theme
            setTheme(R.style.Theme_FadCam_Red);
            getWindow().setNavigationBarColor(getResources().getColor(R.color.red_theme_background_dark, getTheme()));
        } else if ("Premium Gold".equals(savedTheme)) {
            // Gold theme
            setTheme(R.style.Theme_FadCam_Gold);
            getWindow().setNavigationBarColor(getResources().getColor(R.color.gold_theme_background_dark, getTheme()));
        } else if ("Silent Forest".equals(savedTheme)) {
            // Silent Forest theme
            setTheme(R.style.Theme_FadCam_SilentForest);
            getWindow().setNavigationBarColor(getResources().getColor(R.color.silentforest_theme_background_dark, getTheme()));
        } else if ("Shadow Alloy".equals(savedTheme)) {
            // Shadow Alloy theme
            setTheme(R.style.Theme_FadCam_ShadowAlloy);
            getWindow().setNavigationBarColor(getResources().getColor(R.color.shadowalloy_theme_background_dark, getTheme()));
        } else if ("Pookie Pink".equals(savedTheme)) {
            // Pookie Pink theme
            setTheme(R.style.Theme_FadCam_PookiePink);
            getWindow().setNavigationBarColor(getResources().getColor(R.color.pookiepink_theme_background_dark, getTheme()));
        } else {
            // Default dark theme
            setTheme(R.style.Base_Theme_FadCam);
            getWindow().setNavigationBarColor(getResources().getColor(R.color.gray, getTheme()));
        }
        // ----- Fix End: Apply selected theme globally before setContentView -----
        
        // Load and apply the saved language preference before anything else
        SharedPreferences prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        String savedLanguageCode = prefs.getString(Constants.LANGUAGE_KEY, Locale.getDefault().getLanguage());

        applyLanguage(savedLanguageCode);  // Apply the language preference

        // Check if current locale is Pashto
        if (getResources().getConfiguration().locale.getLanguage().equals("ps")) {
            getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        }

        // ----- Fix Start: Launch onboarding if enabled in onCreate -----
        boolean showOnboarding = sharedPreferencesManager.isShowOnboarding();
        if (showOnboarding) {
            Intent intent = new Intent(this, com.fadcam.ui.OnboardingActivity.class);
            startActivity(intent);
            finish();
            return;
        }
        // ----- Fix End: Launch onboarding if enabled in onCreate -----

        setContentView(R.layout.activity_main);

        viewPager = findViewById(R.id.view_pager);
        bottomNavigationView = findViewById(R.id.bottom_navigation);

        ViewPagerAdapter adapter = new ViewPagerAdapter(this);
        viewPager.setAdapter(adapter);

        // Apply the fade animation transformer with more conservative settings
        viewPager.setPageTransformer(new FadePageTransformer());

        // Keep all pages in memory to prevent content disappearing
        viewPager.setOffscreenPageLimit(adapter.getItemCount());

        bottomNavigationView.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.navigation_home) {
                viewPager.setCurrentItem(0, true);
            } else if (itemId == R.id.navigation_records) {
                viewPager.setCurrentItem(1, true);
            } else if (itemId == R.id.navigation_remote) {
                viewPager.setCurrentItem(2, true);
            } else if (itemId == R.id.navigation_settings) {
                viewPager.setCurrentItem(3, true);
            } else if (itemId == R.id.navigation_about) {
                viewPager.setCurrentItem(4, true);
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
                        bottomNavigationView.setSelectedItemId(R.id.navigation_remote);
                        break;
                    case 3:
                        bottomNavigationView.setSelectedItemId(R.id.navigation_settings);
                        break;
                    case 4:
                        bottomNavigationView.setSelectedItemId(R.id.navigation_about);
                        break;
                }
            }
        });

        // Add custom badge to the Remote tab in BottomNavigationView
        bottomNavigationView.post(() -> {
            ViewGroup menuView = (ViewGroup) bottomNavigationView.getChildAt(0);
            if (menuView != null && menuView.getChildCount() > 2) {
                View remoteTab = menuView.getChildAt(2); // 0:home, 1:records, 2:remote
                if (remoteTab instanceof ViewGroup) {
                    // Prevent duplicate badge
                    View existingBadge = ((ViewGroup) remoteTab).findViewById(R.id.badge_text);
                    if (existingBadge == null) {
                        View badge = getLayoutInflater().inflate(R.layout.custom_badge, (ViewGroup) remoteTab, false);
                        ((ViewGroup) remoteTab).addView(badge);
                    }
                }
            }
        });

        // This is the path for the osmdroid tile cache
        File osmdroidBasePath = new File(getCacheDir().getAbsolutePath(), "osmdroid");
        File osmdroidTileCache = new File(osmdroidBasePath, "tiles");
        org.osmdroid.config.Configuration.getInstance().setOsmdroidBasePath(osmdroidBasePath);
        org.osmdroid.config.Configuration.getInstance().setOsmdroidTileCache(osmdroidTileCache);

        // Add dynamic shortcut for torch
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            createDynamicShortcuts();
        }

        // After setContentView, apply theme colors
        int colorTopBar = resolveThemeColor(this, R.attr.colorTopBar);
        int colorBottomNav = resolveThemeColor(this, R.attr.colorBottomNav);
        int colorStatusBar = resolveThemeColor(this, R.attr.colorStatusBar);
        // Top bar (if using Toolbar)
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.topAppBar);
        if (toolbar != null) toolbar.setBackgroundColor(colorTopBar);
        // Bottom navigation
        com.google.android.material.bottomnavigation.BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
        if (bottomNav != null) bottomNav.setBackgroundColor(colorBottomNav);
        // Status bar
        getWindow().setStatusBarColor(colorStatusBar);
        getWindow().setNavigationBarColor(colorBottomNav);
    }

    @RequiresApi(api = Build.VERSION_CODES.N_MR1)
    private void createDynamicShortcuts() {
        ShortcutManager shortcutManager = getSystemService(ShortcutManager.class);

        // Torch Toggle Shortcut
        Intent torchIntent = new Intent(this, TorchToggleActivity.class);
        torchIntent.setAction(Intent.ACTION_VIEW);

        ShortcutInfo torchShortcut = new ShortcutInfo.Builder(this, "torch_toggle")
            .setShortLabel(getString(R.string.torch_shortcut_short_label))
            .setLongLabel(getString(R.string.torch_shortcut_long_label))
            .setIcon(Icon.createWithResource(this, R.drawable.flashlight_shortcut))
            .setIntent(torchIntent)
            .build();

        // Recording Start Shortcut
        Intent startRecordIntent = new Intent(this, RecordingStartActivity.class);
        startRecordIntent.setAction(Intent.ACTION_VIEW);

        ShortcutInfo startRecordShortcut = new ShortcutInfo.Builder(this, "record_start")
            .setShortLabel(getString(R.string.start_recording))
            .setLongLabel(getString(R.string.start_recording))
            .setIcon(Icon.createWithResource(this, R.drawable.start_shortcut))
            .setIntent(startRecordIntent)
            .build();

        // Recording Stop Shortcut
        Intent stopRecordIntent = new Intent(this, RecordingStopActivity.class);
        stopRecordIntent.setAction(Intent.ACTION_VIEW);

        ShortcutInfo stopRecordShortcut = new ShortcutInfo.Builder(this, "record_stop")
            .setShortLabel(getString(R.string.stop_recording))
            .setLongLabel(getString(R.string.stop_recording))
            .setIcon(Icon.createWithResource(this, R.drawable.stop_shortcut))
            .setIntent(stopRecordIntent)
            .build();

        // Set all shortcuts
        shortcutManager.setDynamicShortcuts(Arrays.asList(
            torchShortcut,
            startRecordShortcut,
            stopRecordShortcut
        ));
    }

    public void applyLanguage(String languageCode) {
        // Get current app language
        String currentLanguage = getResources().getConfiguration().locale.getLanguage();

        // Only apply language change if it's different from the current language
        if (!languageCode.equals(currentLanguage)) {
            Log.d("MainActivity", "Applying language: " + languageCode);
            Locale locale = new Locale(languageCode);
            Locale.setDefault(locale);

            android.content.res.Configuration config = new android.content.res.Configuration();
            config.setLocale(locale);
            getApplicationContext().createConfigurationContext(config);

            getResources().updateConfiguration(config, getResources().getDisplayMetrics());

            // Recreate the activity to apply the changes
            recreate();
        } else {
            Log.d("MainActivity", "Language is already set to " + languageCode + "; no need to change.");
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }
    
    // ----- Fix Start: Proper back button handling with double-press to exit -----
    @Override
    public void onBackPressed() {
        // Check if trash fragment is visible - handle separately
        if (isTrashFragmentVisible()) {
            View overlayContainer = findViewById(R.id.overlay_fragment_container);
            if (overlayContainer != null) {
                // Animate fading out
                overlayContainer.animate()
                    .alpha(0f)
                    .setDuration(250)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            // Set visibility to GONE after animation completes
                            overlayContainer.setVisibility(View.GONE);
                            overlayContainer.setAlpha(1f); // Reset alpha for next time
                            
                            // Pop any fragments in the back stack
                            if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
                                getSupportFragmentManager().popBackStack();
                            }
                            
                            // Force a complete reset of the ViewPager and its fragments
                            // Save current position
                            final int currentPosition = viewPager.getCurrentItem();
                            
                            // Completely recreate the adapter (aggressive approach)
                            ViewPagerAdapter newAdapter = new ViewPagerAdapter(MainActivity.this);
                            viewPager.setAdapter(newAdapter);
                            
                            // Reset page transformer to ensure animations work
                            viewPager.setPageTransformer(new FadePageTransformer());
                            
                            // Restore position without animation
                            viewPager.setCurrentItem(currentPosition, false);
                            
                            // Also make sure the correct tab is selected
                            switch (currentPosition) {
                                case 0:
                                    bottomNavigationView.setSelectedItemId(R.id.navigation_home);
                                    break;
                                case 1:
                                    bottomNavigationView.setSelectedItemId(R.id.navigation_records);
                                    break;
                                case 2:
                                    bottomNavigationView.setSelectedItemId(R.id.navigation_remote);
                                    break;
                                case 3:
                                    bottomNavigationView.setSelectedItemId(R.id.navigation_settings);
                                    break;
                                case 4:
                                    bottomNavigationView.setSelectedItemId(R.id.navigation_about);
                                    break;
                            }
                        }
                    });
                return; // Exit early without showing toast
            }
            
            // If for some reason we couldn't animate, fallback to immediate hide
            if (overlayContainer != null) {
                overlayContainer.setVisibility(View.GONE);
            }
            
            // Pop any fragments in the back stack
            if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
                getSupportFragmentManager().popBackStack();
            }
            
            // Refresh the records fragment if needed
            Fragment recordsFragment = getSupportFragmentManager()
                    .findFragmentByTag("RecordsFragment");
            if (recordsFragment instanceof RecordsFragment) {
                ((RecordsFragment) recordsFragment).refreshList();
            }
            
            return; // Exit early without showing toast
        }

        // If we're not on the home tab, go to home tab first before exiting
        if (viewPager.getCurrentItem() != 0) {
            viewPager.setCurrentItem(0, true); // Enable animation
        } else {
            // Check if we should skip this back handling
            if (skipNextBackHandling) {
                skipNextBackHandling = false;
                super.onBackPressed();
                return;
            }
            
            // We're on the home tab, implement double back press to exit
            if (doubleBackToExitPressedOnce) {
                // Remove the callback to prevent it from executing after app close
                backPressHandler.removeCallbacks(backPressRunnable);
                super.onBackPressed();
                return;
            }

            // First back press - show toast and set flag
            this.doubleBackToExitPressedOnce = true;
            Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show();

            // Reset the flag after a delay
            backPressHandler.postDelayed(backPressRunnable, BACK_PRESS_DELAY);
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        
        // Restore language settings
        SharedPreferencesManager sharedPreferencesManager = SharedPreferencesManager.getInstance(this);
        String savedLanguageCode = sharedPreferencesManager.sharedPreferences.getString(Constants.LANGUAGE_KEY, Locale.getDefault().getLanguage());
        applyLanguage(savedLanguageCode);
        
        // Create shortcuts if needed (Android 7.1+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            createDynamicShortcuts();
        }
        
        // Handle any pending intents
        Intent intent = getIntent();
        if (intent != null && Constants.ACTION_SHOW_RECORDS.equals(intent.getAction())) {
            viewPager.setCurrentItem(1, false); // Navigate to Records tab
        }
        
        // Set up the back press behavior with the newer API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    // Check if trash fragment is visible - handle separately
                    if (isTrashFragmentVisible()) {
                        View overlayContainer = findViewById(R.id.overlay_fragment_container);
                        if (overlayContainer != null) {
                            // Animate fading out
                            overlayContainer.animate()
                                .alpha(0f)
                                .setDuration(250)
                                .setListener(new AnimatorListenerAdapter() {
                                    @Override
                                    public void onAnimationEnd(Animator animation) {
                                        // Set visibility to GONE after animation completes
                                        overlayContainer.setVisibility(View.GONE);
                                        overlayContainer.setAlpha(1f); // Reset alpha for next time
                                        
                                        // Pop any fragments in the back stack
                                        if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
                                            getSupportFragmentManager().popBackStack();
                                        }
                                        
                                        // Force a complete reset of the ViewPager and its fragments
                                        // Save current position
                                        final int currentPosition = viewPager.getCurrentItem();
                                        
                                        // Completely recreate the adapter (aggressive approach)
                                        ViewPagerAdapter newAdapter = new ViewPagerAdapter(MainActivity.this);
                                        viewPager.setAdapter(newAdapter);
                                        
                                        // Reset page transformer to ensure animations work
                                        viewPager.setPageTransformer(new FadePageTransformer());
                                        
                                        // Restore position without animation
                                        viewPager.setCurrentItem(currentPosition, false);
                                        
                                        // Also make sure the correct tab is selected
                                        switch (currentPosition) {
                                            case 0:
                                                bottomNavigationView.setSelectedItemId(R.id.navigation_home);
                                                break;
                                            case 1:
                                                bottomNavigationView.setSelectedItemId(R.id.navigation_records);
                                                break;
                                            case 2:
                                                bottomNavigationView.setSelectedItemId(R.id.navigation_remote);
                                                break;
                                            case 3:
                                                bottomNavigationView.setSelectedItemId(R.id.navigation_settings);
                                                break;
                                            case 4:
                                                bottomNavigationView.setSelectedItemId(R.id.navigation_about);
                                                break;
                                        }
                                    }
                                });
                            return; // Exit early without showing toast
                        }
                    }
                    
                    // If we're not on the home tab, go to home tab first before exiting
                    if (viewPager.getCurrentItem() != 0) {
                        viewPager.setCurrentItem(0, true); // Enable animation
                    } else {
                        // Check if we should skip this back handling
                        if (skipNextBackHandling) {
                            skipNextBackHandling = false;
                            setEnabled(false);
                            getOnBackPressedDispatcher().onBackPressed();
                            return;
                        }
                        
                        // We're on the home tab, implement double back press to exit
                        if (doubleBackToExitPressedOnce) {
                            // Remove the callback to prevent it from executing after app close
                            backPressHandler.removeCallbacks(backPressRunnable);
                            setEnabled(false);
                            getOnBackPressedDispatcher().onBackPressed();
                            return;
                        }

                        // First back press - show toast and set flag
                        doubleBackToExitPressedOnce = true;
                        Toast.makeText(MainActivity.this, "Press back again to exit", Toast.LENGTH_SHORT).show();

                        // Reset the flag after a delay
                        backPressHandler.postDelayed(backPressRunnable, BACK_PRESS_DELAY);
                    }
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clean up handler callbacks to prevent memory leaks
        backPressHandler.removeCallbacks(backPressRunnable);
    }
    // ----- Fix End: Proper back button handling with double-press to exit -----

    // Helper to resolve theme color attribute
    private int resolveThemeColor(Context context, int attr) {
        android.util.TypedValue typedValue = new android.util.TypedValue();
        android.content.res.Resources.Theme theme = context.getTheme();
        theme.resolveAttribute(attr, typedValue, true);
        return typedValue.data;
    }
}