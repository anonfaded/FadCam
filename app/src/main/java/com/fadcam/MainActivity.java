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
import com.fadcam.ui.OverlayNavUtil;

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
import androidx.core.splashscreen.SplashScreen; // SplashScreen API
import android.view.WindowManager;

public class MainActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private BottomNavigationView bottomNavigationView;

    // ----- Fix Start: Add fields for double-back to exit -----
    private boolean doubleBackToExitPressedOnce = false;
    private boolean skipNextBackHandling = false; // New flag to skip toast on next back press
    private Handler backPressHandler = new Handler();
    private static final int BACK_PRESS_DELAY = 2000; // 2 seconds

    // Add SharedPreferencesManager field
    private SharedPreferencesManager sharedPreferencesManager;
    // Cloak overlay view reference
    private View cloakOverlay;
    private android.widget.ImageView cloakIconView;
    private android.widget.TextView cloakTitleView;

    private final Runnable backPressRunnable = new Runnable() {
        @Override
        public void run() {
            doubleBackToExitPressedOnce = false;
        }
    };
    // ----- Fix End: Add fields for double-back to exit -----

    // ----- Fix Start: Add method to disable back toast temporarily -----
    /**
     * Public method to be called from fragments that need to disable the
     * double-back toast temporarily
     * This will prevent the "Press back again to exit" toast from showing on the
     * next back press
     */
    public void skipNextBackExitHandling() {
        skipNextBackHandling = true;
        // Reset automatically after a delay
        backPressHandler.postDelayed(() -> skipNextBackHandling = false, 1000);
    }
    // ----- Fix End: Add method to disable back toast temporarily -----

    // Removed Trash-specific visibility checks; overlay back handling is unified
    // below.

    // -------------- Fix Start for this method(hideOverlayIfNoFragments)-----------
    /**
     * Utility so child fragments can request overlay dismissal after popping back
     * stack.
     */
    public void hideOverlayIfNoFragments() {
        View overlayContainer = findViewById(R.id.overlay_fragment_container);
        if (overlayContainer != null) {
            if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
                Log.d("OverlayDebug", "hideOverlayIfNoFragments: back stack empty -> hide overlay");
                overlayContainer.setVisibility(View.GONE);
            } else {
                Log.d("OverlayDebug", "hideOverlayIfNoFragments: back stack count="
                        + getSupportFragmentManager().getBackStackEntryCount());
            }
        }
    }
    // -------------- Fix Ended for this method(hideOverlayIfNoFragments)-----------

    // -------------- Fix Start for this method(showOverlayFragment)-----------
    /**
     * Present a fragment in the overlay container, avoiding duplicate dark blank
     * state.
     */
    public void showOverlayFragment(Fragment fragment, String tag) {
        View overlayContainer = findViewById(R.id.overlay_fragment_container);
        if (overlayContainer == null)
            return;
        Log.d("OverlayDebug", "showOverlayFragment: tag=" + tag + " currentBackStack="
                + getSupportFragmentManager().getBackStackEntryCount());
        overlayContainer.setVisibility(View.VISIBLE);
        overlayContainer.setAlpha(0f);
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.overlay_fragment_container, fragment, tag)
                .addToBackStack(tag)
                .commitAllowingStateLoss();
        overlayContainer.animate().alpha(1f).setDuration(120).start();
    }
    // -------------- Fix Ended for this method(showOverlayFragment)-----------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Install splash screen (shows the themed windowSplashScreenAnimatedIcon)
        SplashScreen.installSplashScreen(this);
        // Apply user-selected theme AFTER splash so postSplashScreenTheme replaced by
        // dynamic choice
        applyTheme();

        // -------------- Fix Start for this block(apply cloak as early as
        // possible)-----------
        try {
            if (this.sharedPreferencesManager == null) {
                this.sharedPreferencesManager = SharedPreferencesManager.getInstance(this);
            }
            boolean cloakEarly = this.sharedPreferencesManager.isCloakRecentsEnabled();
            if (cloakEarly) {
                if (Build.VERSION.SDK_INT >= 34) {
                    try {
                        this.setRecentsScreenshotEnabled(false);
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("Cloak", "early cloak apply fail", t);
        }
        // -------------- Fix Ended for this block(apply cloak as early as
        // possible)-----------

        // ----- Fix Start: Ensure onboarding shows on first install -----
        // Initialize SharedPreferencesManager instance first
        this.sharedPreferencesManager = SharedPreferencesManager.getInstance(this);

        // Check if this is a first launch by looking for a special flag
        boolean firstInstallChecked = sharedPreferencesManager.sharedPreferences
                .getBoolean(Constants.FIRST_INSTALL_CHECKED_KEY, false);

        if (!firstInstallChecked) {
            // This is definitely a first install or app data was cleared
            // Force onboarding to show by setting the flag to false
            android.util.Log.d("MainActivity", "First install detected! Forcing onboarding to show.");
            sharedPreferencesManager.sharedPreferences.edit()
                    .putBoolean(Constants.COMPLETED_ONBOARDING_KEY, false)
                    .putBoolean(Constants.FIRST_INSTALL_CHECKED_KEY, true)
                    .commit(); // Use commit() for immediate effect
        }

        // Check for onboarding BEFORE applying theme or language
        boolean showOnboarding = sharedPreferencesManager.isShowOnboarding();
        android.util.Log.d("MainActivity", "Should show onboarding: " + showOnboarding);

        if (showOnboarding) {
            // Launch onboarding activity if needed
            Intent intent = new Intent(this, com.fadcam.ui.OnboardingActivity.class);
            startActivity(intent);
            finish(); // Finish this activity so it's not in the back stack
            return;
        }
        // ----- Fix End: Ensure onboarding shows on first install -----

        // Now that we know we're not showing onboarding, continue with normal
        // initialization

        // Load and apply the saved language preference before anything else
        SharedPreferences prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        String savedLanguageCode = prefs.getString(Constants.LANGUAGE_KEY, Locale.getDefault().getLanguage());

        applyLanguage(savedLanguageCode); // Apply the language preference

        // Check if current locale is Pashto
        if (getResources().getConfiguration().locale.getLanguage().equals("ps")) {
            getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        }

        // ----- Fix Start: Remove duplicate onboarding check -----
        // The onboarding check was already done at the beginning of onCreate
        // ----- Fix End: Remove duplicate onboarding check -----

        setContentView(R.layout.activity_main);

        // -------------- Fix Start for this block(apply persistent cloak at
        // startup)-----------
        try {
            if (this.sharedPreferencesManager == null) {
                this.sharedPreferencesManager = SharedPreferencesManager.getInstance(this);
            }
            boolean cloak = this.sharedPreferencesManager.isCloakRecentsEnabled();
            if (cloak) {
                if (Build.VERSION.SDK_INT >= 34) {
                    try {
                        this.setRecentsScreenshotEnabled(false);
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                } catch (Throwable ignored) {
                }
            } else {
                if (Build.VERSION.SDK_INT >= 34) {
                    try {
                        this.setRecentsScreenshotEnabled(true);
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("Cloak", "init cloak state fail", t);
        }
        // -------------- Fix Ended for this block(apply persistent cloak at
        // startup)-----------

        viewPager = findViewById(R.id.view_pager);
        bottomNavigationView = findViewById(R.id.bottom_navigation);

        // -------------- Fix Start for this block(init cloak overlay)-----------
        // A simple overlay that we can show/hide to mask the UI before recents
        // snapshot.
        try {
            android.widget.FrameLayout overlay = new android.widget.FrameLayout(this);
            overlay.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            overlay.setBackgroundColor(0xFF000000); // default solid black
            overlay.setClickable(true); // swallow touches while visible
            overlay.setFocusable(true);

            // Centered decoy content
            android.widget.LinearLayout content = new android.widget.LinearLayout(this);
            content.setOrientation(android.widget.LinearLayout.VERTICAL);
            content.setGravity(android.view.Gravity.CENTER);
            android.widget.FrameLayout.LayoutParams clp = new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            content.setLayoutParams(clp);

            cloakIconView = new android.widget.ImageView(this);
            int iconSize = (int) (72 * getResources().getDisplayMetrics().density);
            android.widget.LinearLayout.LayoutParams ilp = new android.widget.LinearLayout.LayoutParams(iconSize,
                    iconSize);
            cloakIconView.setLayoutParams(ilp);
            cloakIconView.setImageResource(SharedPreferencesManager.getInstance(this).getCurrentAppIconResId());

            cloakTitleView = new android.widget.TextView(this);
            android.widget.LinearLayout.LayoutParams tlp = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = (int) (12 * getResources().getDisplayMetrics().density);
            cloakTitleView.setLayoutParams(tlp);
            cloakTitleView.setTextColor(0xFFFFFFFF);
            cloakTitleView.setTextSize(18f);
            cloakTitleView.setTypeface(cloakTitleView.getTypeface(), android.graphics.Typeface.BOLD);
            cloakTitleView.setText(SharedPreferencesManager.getInstance(this).getAppIconDisplayName());

            content.addView(cloakIconView);
            content.addView(cloakTitleView);
            overlay.addView(content);

            overlay.setVisibility(View.GONE);
            ViewGroup root = (ViewGroup) findViewById(android.R.id.content);
            if (root != null) {
                root.addView(overlay);
            }
            cloakOverlay = overlay;
        } catch (Exception e) {
            android.util.Log.w("Cloak", "Failed to init cloak overlay", e);
        }
        // -------------- Fix Ended for this block(init cloak overlay)-----------

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
            } else if (itemId == R.id.navigation_faditor_mini) {
                viewPager.setCurrentItem(3, true);
            } else if (itemId == R.id.navigation_settings) {
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
                        // Trigger lazy loading when user navigates to Records tab
                        try {
                            Fragment recordsFragment = getSupportFragmentManager().findFragmentByTag("f" + position);
                            if (recordsFragment instanceof RecordsFragment) {
                                ((RecordsFragment) recordsFragment).onFragmentBecameVisible();
                            }
                        } catch (Exception e) {
                            Log.e("MainActivity", "Error triggering Records lazy load", e);
                        }
                        break;
                    case 2:
                        bottomNavigationView.setSelectedItemId(R.id.navigation_remote);
                        break;
                    case 3:
                        bottomNavigationView.setSelectedItemId(R.id.navigation_faditor_mini);
                        break;
                    case 4:
                        bottomNavigationView.setSelectedItemId(R.id.navigation_settings);
                        break;
                }
            }
        });

        // Add custom badge to the Remote tab and Faditor Mini tab in
        // BottomNavigationView
        bottomNavigationView.post(() -> {
            ViewGroup menuView = (ViewGroup) bottomNavigationView.getChildAt(0);
            if (menuView != null && menuView.getChildCount() > 3) {
                // Add badge to Remote tab (index 2)
                View remoteTab = menuView.getChildAt(2); // 0:home, 1:records, 2:remote
                if (remoteTab instanceof ViewGroup) {
                    // Prevent duplicate badge
                    View existingBadge = ((ViewGroup) remoteTab).findViewById(R.id.badge_text);
                    if (existingBadge == null) {
                        View badge = getLayoutInflater().inflate(R.layout.custom_badge, (ViewGroup) remoteTab, false);
                        ((ViewGroup) remoteTab).addView(badge);
                    }
                }

                // Add badge to Faditor Mini tab (index 3)
                View faditorMiniTab = menuView.getChildAt(3); // 0:home, 1:records, 2:remote, 3:faditor_mini
                if (faditorMiniTab instanceof ViewGroup) {
                    // Prevent duplicate badge
                    View existingBadge = ((ViewGroup) faditorMiniTab).findViewById(R.id.badge_text);
                    if (existingBadge == null) {
                        View badge = getLayoutInflater().inflate(R.layout.custom_badge, (ViewGroup) faditorMiniTab,
                                false);
                        ((ViewGroup) faditorMiniTab).addView(badge);
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
        if (toolbar != null)
            toolbar.setBackgroundColor(colorTopBar);
        // Bottom navigation
        com.google.android.material.bottomnavigation.BottomNavigationView bottomNav = findViewById(
                R.id.bottom_navigation);
        if (bottomNav != null)
            bottomNav.setBackgroundColor(colorBottomNav);
        // Status bar
        getWindow().setStatusBarColor(colorStatusBar);
        getWindow().setNavigationBarColor(colorBottomNav);

        // -------------- Fix Start for this logic(reopen appearance/theme sheet after
        // theme change)-----------
        try {
            SharedPreferences reopenPrefs = sharedPreferencesManager.sharedPreferences;
            boolean reopenAppearance = reopenPrefs.getBoolean("reopen_appearance_after_theme", false);
            if (reopenAppearance) {
                // Clear flag to avoid loops
                reopenPrefs.edit().putBoolean("reopen_appearance_after_theme", false).apply();
                // Ensure Settings tab selected (index 4)
                if (viewPager != null) {
                    viewPager.setCurrentItem(4, false);
                }
                // Post to allow SettingsHomeFragment attach
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        com.fadcam.ui.AppearanceSettingsFragment frag = new com.fadcam.ui.AppearanceSettingsFragment();
                        OverlayNavUtil.show(this, frag, "AppearanceSettingsFragment");
                        boolean reopenSheet = reopenPrefs.getBoolean("reopen_theme_sheet_after_theme", false);
                        if (reopenSheet) {
                            reopenPrefs.edit().putBoolean("reopen_theme_sheet_after_theme", false).apply();
                            frag.getLifecycle().addObserver(new androidx.lifecycle.DefaultLifecycleObserver() {
                                @Override
                                public void onResume(androidx.lifecycle.LifecycleOwner owner) {
                                    View v = frag.getView();
                                    if (v != null) {
                                        View row = v.findViewById(R.id.row_theme);
                                        if (row != null) {
                                            row.postDelayed(row::performClick, 100);
                                        }
                                    }
                                }
                            });
                        }
                    } catch (Exception e) {
                        android.util.Log.e("ThemeReopen", "Failed to reopen appearance fragment", e);
                    }
                }, 100);
            }
        } catch (Exception e) {
            android.util.Log.e("ThemeReopen", "Outer fail", e);
        }
        // -------------- Fix Ended for this logic(reopen appearance/theme sheet after
        // theme change)-----------

        // -------------- Fix Start for this logic(handle widget intent to open
        // shortcuts)-----------
        handleWidgetIntent();
        // -------------- Fix Ended for this logic(handle widget intent to open
        // shortcuts)-----------
    }

    private void handleWidgetIntent() {
        Intent intent = getIntent();
        if (intent != null && intent.getBooleanExtra("open_shortcuts_widgets", false)) {
            // Navigate to Settings tab and then open Shortcuts & Widgets screen
            if (viewPager != null) {
                viewPager.setCurrentItem(4, false); // Settings tab
            }
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    com.fadcam.ui.ShortcutsSettingsFragment frag = new com.fadcam.ui.ShortcutsSettingsFragment();
                    OverlayNavUtil.show(this, frag, "ShortcutsSettingsFragment");
                } catch (Exception e) {
                    android.util.Log.e("WidgetIntent", "Failed to open shortcuts fragment", e);
                }
            }, 100);
        }
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
                stopRecordShortcut));
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

    // -------------- Fix Start for this method(applyCloakIfNeeded)-----------
    /** Shows or hides the cloak overlay based on preference. */
    private void applyCloakIfNeeded(boolean show) {
        try {
            if (cloakOverlay == null)
                return;
            // Refresh decoy visuals from current icon & label
            if (cloakIconView != null)
                cloakIconView.setImageResource(SharedPreferencesManager.getInstance(this).getCurrentAppIconResId());
            if (cloakTitleView != null)
                cloakTitleView.setText(SharedPreferencesManager.getInstance(this).getAppIconDisplayName());
            if (show) {
                cloakOverlay.setAlpha(1f);
                cloakOverlay.setVisibility(View.VISIBLE);
            } else {
                cloakOverlay.setVisibility(View.GONE);
                cloakOverlay.setAlpha(1f);
            }
        } catch (Exception e) {
            android.util.Log.w("Cloak", "applyCloakIfNeeded failed", e);
        }
    }
    // -------------- Fix Ended for this method(applyCloakIfNeeded)-----------

    // -------------- Fix Start for this method(applyCloakPreferenceNow)-----------
    /**
     * Immediately applies or removes recents cloaking flags at runtime based on
     * user toggle.
     * This lets the change take effect without restarting the app.
     */
    public void applyCloakPreferenceNow(boolean enable) {
        try {
            if (enable) {
                if (Build.VERSION.SDK_INT >= 34) {
                    try {
                        this.setRecentsScreenshotEnabled(false);
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                } catch (Throwable ignored) {
                }
            } else {
                if (Build.VERSION.SDK_INT >= 34) {
                    try {
                        this.setRecentsScreenshotEnabled(true);
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                } catch (Throwable ignored) {
                }
            }
            // Ensure decoy overlay is hidden during active use
            applyCloakIfNeeded(false);
        } catch (Exception e) {
            android.util.Log.w("Cloak", "applyCloakPreferenceNow failed", e);
        }
    }
    // -------------- Fix Ended for this method(applyCloakPreferenceNow)-----------

    // ----- Fix Start: Proper back button handling with double-press to exit -----
    @Override
    public void onBackPressed() {
        // Unified: handle any visible overlay fragment (trash or settings)
        if (handleOverlayBack()) {
            Log.d("OverlayDebug", "onBackPressed: dismissed overlay fragment");
            return;
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
        // -------------- Fix Start for this method(onResume - enforce preference
        // state)-----------
        applyCloakIfNeeded(false); // hide decoy overlay while active
        try {
            boolean cloak = SharedPreferencesManager.getInstance(this).isCloakRecentsEnabled();
            if (cloak) {
                if (Build.VERSION.SDK_INT >= 34) {
                    try {
                        this.setRecentsScreenshotEnabled(false);
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                } catch (Throwable ignored) {
                }
            } else {
                if (Build.VERSION.SDK_INT >= 34) {
                    try {
                        this.setRecentsScreenshotEnabled(true);
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("Cloak", "onResume preference apply fail", t);
        }
        // -------------- Fix Ended for this method(onResume - enforce preference
        // state)-----------

        // Update UI for current theme
        String currentTheme = SharedPreferencesManager.getInstance(this).sharedPreferences
                .getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);

        // Special handling for Snow Veil theme - set light status bar with dark icons
        if ("Snow Veil".equals(currentTheme) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

            // Also set light navigation bar if API level is high enough
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // For other themes, use dark status bar with light icons (default)
            getWindow().getDecorView().setSystemUiVisibility(0);
        }

        // Restore language settings
        this.sharedPreferencesManager = SharedPreferencesManager.getInstance(this);
        String savedLanguageCode = sharedPreferencesManager.sharedPreferences.getString(Constants.LANGUAGE_KEY,
                Locale.getDefault().getLanguage());
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
                    // Unified: generic overlay back handling (trash & settings)
                    if (handleOverlayBack()) {
                        Log.d("OverlayDebug", "DispatcherBack: dismissed overlay fragment");
                        return; // handled
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

    // -------------- Fix Start for this method(onPause)-----------
    @Override
    protected void onPause() {
        // Show cloak just before going into background to affect recents snapshot
        try {
            if (sharedPreferencesManager == null) {
                sharedPreferencesManager = SharedPreferencesManager.getInstance(this);
            }
            boolean enabled = sharedPreferencesManager.isCloakRecentsEnabled();
            if (enabled) {
                // -------------- Fix Start for this block(onPause - enforce
                // secure/recents)-----------
                applyCloakIfNeeded(true);
                // For Android 14+, explicitly disable recents screenshots
                if (Build.VERSION.SDK_INT >= 34) {
                    try {
                        this.setRecentsScreenshotEnabled(false);
                    } catch (Throwable ignored) {
                    }
                }
                // On older devices, set FLAG_SECURE to force a black snapshot in recents
                try {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                } catch (Throwable ignored) {
                }
                // -------------- Fix Ended for this block(onPause - enforce
                // secure/recents)-----------
            }
        } catch (Exception e) {
            android.util.Log.w("Cloak", "onPause cloak fail", e);
        }
        super.onPause();
    }
    // -------------- Fix Ended for this method(onPause)-----------

    // -------------- Fix Start for this method(onUserLeaveHint)-----------
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Additional safeguard when user leaves to recents directly
        try {
            if (sharedPreferencesManager == null) {
                sharedPreferencesManager = SharedPreferencesManager.getInstance(this);
            }
            boolean enabled = sharedPreferencesManager.isCloakRecentsEnabled();
            if (enabled) {
                // -------------- Fix Start for this block(onUserLeaveHint - enforce
                // secure/recents)-----------
                applyCloakIfNeeded(true);
                if (Build.VERSION.SDK_INT >= 34) {
                    try {
                        this.setRecentsScreenshotEnabled(false);
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                } catch (Throwable ignored) {
                }
                // -------------- Fix Ended for this block(onUserLeaveHint - enforce
                // secure/recents)-----------
            }
        } catch (Exception e) {
            android.util.Log.w("Cloak", "onUserLeaveHint cloak fail", e);
        }
    }
    // -------------- Fix Ended for this method(onUserLeaveHint)-----------

    // -------------- Fix Start for this method(handleOverlayBack)-----------
    /** Handles back press for any visible overlay fragment (settings or trash). */
    private boolean handleOverlayBack() {
        View overlayContainer = findViewById(R.id.overlay_fragment_container);
        if (overlayContainer == null || overlayContainer.getVisibility() != View.VISIBLE)
            return false;
        Fragment top = getSupportFragmentManager().findFragmentById(R.id.overlay_fragment_container);
        if (top == null)
            return false;
        // Animate fade out then pop
        // -------------- Fix Start for this
        // method(handleOverlayBack_raceGuard)-----------
        final Fragment beforeTop = getSupportFragmentManager().findFragmentById(R.id.overlay_fragment_container);
        overlayContainer.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            Fragment currentTop = getSupportFragmentManager().findFragmentById(R.id.overlay_fragment_container);
            boolean sameInstance = currentTop == beforeTop;
            if (sameInstance) {
                overlayContainer.setVisibility(View.GONE);
                overlayContainer.setAlpha(1f);
                if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
                    getSupportFragmentManager().popBackStack();
                }
            } else {
                // A different overlay appeared; don't hide or pop.
                overlayContainer.setAlpha(1f);
            }
        }).start();
        // -------------- Fix Ended for this
        // method(handleOverlayBack_raceGuard)-----------
        return true;
    }
    // -------------- Fix Ended for this method(handleOverlayBack)-----------

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

    private void initTheme() {
        // Apply the saved theme if one exists
        sharedPreferencesManager = SharedPreferencesManager.getInstance(this);
        String savedTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME,
                Constants.DEFAULT_APP_THEME);
        applyTheme(savedTheme);
    }

    private void applyTheme(String themeName) {
        // Use DEFAULT_APP_THEME as fallback if themeName is null
        if (themeName == null) {
            themeName = Constants.DEFAULT_APP_THEME;
        }

        // Apply the appropriate theme based on name
        if ("Faded Night".equals(themeName) ||
                "AMOLED".equals(themeName) ||
                "Amoled".equals(themeName) ||
                "amoled".equals(themeName)) {
            setTheme(R.style.Theme_FadCam_Amoled);

            // Standardize theme name to "Faded Night"
            if (!"Faded Night".equals(themeName)) {
                sharedPreferencesManager.sharedPreferences.edit()
                        .putString(Constants.PREF_APP_THEME, "Faded Night")
                        .apply();
            }

            getWindow().setNavigationBarColor(getResources().getColor(R.color.amoled_background, getTheme()));
        } else if ("Crimson Bloom".equals(themeName)) {
            // Red theme
            setTheme(R.style.Theme_FadCam_Red);
            getWindow().setNavigationBarColor(getResources().getColor(R.color.red_theme_background_dark, getTheme()));
        } else if ("Premium Gold".equals(themeName)) {
            // Gold theme
            setTheme(R.style.Theme_FadCam_Gold);
            getWindow().setNavigationBarColor(getResources().getColor(R.color.gold_theme_background_dark, getTheme()));
        } else if ("Silent Forest".equals(themeName)) {
            // Silent Forest theme
            setTheme(R.style.Theme_FadCam_SilentForest);
            getWindow().setNavigationBarColor(
                    getResources().getColor(R.color.silentforest_theme_background_dark, getTheme()));
        } else if ("Shadow Alloy".equals(themeName)) {
            // Shadow Alloy theme
            setTheme(R.style.Theme_FadCam_ShadowAlloy);
            getWindow().setNavigationBarColor(
                    getResources().getColor(R.color.shadowalloy_theme_background_dark, getTheme()));
        } else if ("Pookie Pink".equals(themeName)) {
            // Pookie Pink theme
            setTheme(R.style.Theme_FadCam_PookiePink);
            getWindow().setNavigationBarColor(
                    getResources().getColor(R.color.pookiepink_theme_background_dark, getTheme()));
        } else if ("Snow Veil".equals(themeName)) {
            // Snow Veil theme
            setTheme(R.style.Theme_FadCam_SnowVeil);
            getWindow().setNavigationBarColor(
                    getResources().getColor(R.color.snowveil_theme_background_light, getTheme()));

            // Set status bar icons to dark for light theme
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        } else if ("Midnight Dusk".equals(themeName)) {
            // Always use the custom always-dark theme for Midnight Dusk
            setTheme(R.style.Theme_FadCam_MidnightDusk);
            getWindow().setNavigationBarColor(getResources().getColor(R.color.gray, getTheme()));
        } else {
            // If we get an unknown theme name, use the system default
            // This should be the Crimson Bloom theme as defined in
            // Constants.DEFAULT_APP_THEME
            if ("Crimson Bloom".equals(Constants.DEFAULT_APP_THEME)) {
                setTheme(R.style.Theme_FadCam_Red);
                getWindow()
                        .setNavigationBarColor(getResources().getColor(R.color.red_theme_background_dark, getTheme()));
            } else {
                // Fallback to base theme
                setTheme(R.style.Base_Theme_FadCam);
                getWindow().setNavigationBarColor(getResources().getColor(R.color.gray, getTheme()));
            }
        }
    }

    /**
     * Apply the selected theme from preferences before any views are created
     * This ensures the theme is consistently applied across the entire app
     */
    private void applyTheme() {
        // Get shared preferences and current theme
        SharedPreferencesManager sharedPreferencesManager = SharedPreferencesManager.getInstance(this);
        String themeName = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME,
                Constants.DEFAULT_APP_THEME);

        // Ensure we have a valid theme name, default to Crimson Bloom if null or empty
        if (themeName == null || themeName.isEmpty()) {
            themeName = Constants.DEFAULT_APP_THEME;
            sharedPreferencesManager.sharedPreferences.edit()
                    .putString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME)
                    .apply();
        }

        // Apply appropriate theme based on name
        if ("Crimson Bloom".equals(themeName)) {
            setTheme(R.style.Theme_FadCam_Red);
        } else if ("Faded Night".equals(themeName)) {
            setTheme(R.style.Theme_FadCam_Amoled);
        } else if ("Midnight Dusk".equals(themeName)) {
            setTheme(R.style.Theme_FadCam_MidnightDusk); // Always use the custom always-dark theme
        } else if ("Premium Gold".equals(themeName)) {
            setTheme(R.style.Theme_FadCam_Gold);
        } else if ("Silent Forest".equals(themeName)) {
            setTheme(R.style.Theme_FadCam_SilentForest);
        } else if ("Shadow Alloy".equals(themeName)) {
            setTheme(R.style.Theme_FadCam_ShadowAlloy);
        } else if ("Pookie Pink".equals(themeName)) {
            setTheme(R.style.Theme_FadCam_PookiePink);
        } else if ("Snow Veil".equals(themeName)) {
            setTheme(R.style.Theme_FadCam_SnowVeil);
        } else {
            // Default to Crimson Bloom for any unknown values
            setTheme(R.style.Theme_FadCam_Red);
            // Save the corrected theme value
            sharedPreferencesManager.sharedPreferences.edit()
                    .putString(Constants.PREF_APP_THEME, "Crimson Bloom")
                    .apply();
        }

        // Update default clock color based on theme
        sharedPreferencesManager.updateDefaultClockColorForTheme();
    }

    // -------------- Fix Start for this method(applyThemeFromSettings)-----------
    /**
     * Public wrapper so settings fragments can request a theme change.
     * Persists preference already written by caller and recreates activity to apply
     * resources.
     */
    public void applyThemeFromSettings(String themeName) {
        applyTheme(themeName);
        recreate();
    }
    // -------------- Fix Ended for this method(applyThemeFromSettings)-----------
}