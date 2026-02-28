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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewConfiguration;
import android.widget.Toast;
import com.fadcam.ui.OverlayNavUtil;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;

import com.fadcam.ui.RecordsFragment;
import com.fadcam.ui.RemoteFragment;
import com.fadcam.ui.FaditorMiniFragment;
import com.fadcam.ui.SettingsHomeFragment;
import com.fadcam.forensics.ui.ForensicIntelligenceFragment;
import com.fadcam.ui.utils.NewFeatureManager;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import androidx.core.splashscreen.SplashScreen; // SplashScreen API
import android.view.WindowManager;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import android.widget.HorizontalScrollView;

public class MainActivity extends AppCompatActivity {

    private BottomNavigationView bottomNavigationView;
    private int originalBottomNavColor = 0; // Store original bottom nav color

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

    // Tab swipe navigation state (fragment-based navigation, not ViewPager)
    private float swipeDownX = 0f;
    private float swipeDownY = 0f;
    private boolean swipeCandidate = false;
    private boolean swipeHandled = false;
    private int swipeTouchSlop = 0;
    private static final float SWIPE_HORIZONTAL_RATIO = 1.35f;
    private boolean previewGestureInProgress = false;
    private float previewGestureZoomRatio = 1.0f;

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

    // Removed Trash-specific visibility checks; overlay back handling is unified
    // below.

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

    /**
     * Set bottom navigation bar color dynamically.
     * Used by fragments to change bottom nav color (e.g., Remote tab makes it black).
     *
     * @param color Color as integer (e.g., 0xFF000000 for black), or 0 to restore original
     */
    public void setBottomNavColor(int color) {
        if (bottomNavigationView != null) {
            if (color == 0) {
                // Restore original color
                if (originalBottomNavColor != 0) {
                    bottomNavigationView.setBackgroundColor(originalBottomNavColor);
                }
            } else {
                // Set custom color
                bottomNavigationView.setBackgroundColor(color);
            }
        }
    }

    /**
     * Set the status bar (notification bar at top) color dynamically.
     * Used by fragments to change status bar color (e.g., Remote tab makes it black).
     *
     * @param color Color as integer (e.g., 0xFF000000 for black), or 0 to restore original from theme
     */
    public void setStatusBarColor(int color) {
        if (getWindow() != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            if (color == 0) {
                // Restore status bar to match header/nav (colorTopBar)
                try {
                    android.util.TypedValue typedValue = new android.util.TypedValue();
                    int colorTopBarAttr = getResources().getIdentifier("colorTopBar", "attr", getPackageName());
                    if (colorTopBarAttr != 0 && getTheme().resolveAttribute(colorTopBarAttr, typedValue, true)) {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            int statusColor = getColor(typedValue.resourceId);
                            getWindow().setStatusBarColor(statusColor);
                        } else {
                            int statusColor = getResources().getColor(typedValue.resourceId);
                            getWindow().setStatusBarColor(statusColor);
                        }
                        Log.d("MainActivity", "Restored status bar color from colorTopBar: " + Integer.toHexString(typedValue.resourceId));
                    }
                } catch (Exception e) {
                    Log.e("MainActivity", "Error restoring status bar color from colorTopBar", e);
                }
            } else {
                // Set custom color
                getWindow().setStatusBarColor(color);
            }
        }
    }

    /**
     * Set the system navigation bar (gesture/buttons area at bottom) color.
     *
     * @param color Color as integer (e.g., 0xFF000000 for black), or 0 to restore theme color
     */
    public void setNavigationBarColor(int color) {
        if (getWindow() != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            if (color == 0) {
                int navColor = resolveThemeColor(this, R.attr.colorBottomNav);
                getWindow().setNavigationBarColor(navColor);
            } else {
                getWindow().setNavigationBarColor(color);
            }
        }
    }

    /**
     * Update feature badge visibility based on whether features have been seen.
     * Uses Material Design BadgeDrawable on BottomNavigationView items.
     */
    private void updateFeatureBadgeVisibility() {
        try {
            if (bottomNavigationView == null) {
                return;
            }
            
            // Handle Remote badge
            boolean shouldShowRemoteBadge = NewFeatureManager.shouldShowBadge(this, "remote");
            Log.d("MainActivity", "updateFeatureBadgeVisibility: shouldShowRemoteBadge=" + shouldShowRemoteBadge);
            
            if (shouldShowRemoteBadge) {
                // Show badge on Remote nav item
                try {
                    com.google.android.material.badge.BadgeDrawable badge = 
                        bottomNavigationView.getOrCreateBadge(R.id.navigation_remote);
                    badge.setVisible(true);
                    badge.setText("NEW"); // Show "NEW" text instead of number
                    badge.setBackgroundColor(0xFF4CAF50); // Green background
                    badge.setBadgeTextColor(0xFFFFFFFF); // White text color
                    Log.d("MainActivity", "Badge shown for remote");
                } catch (Exception e) {
                    Log.e("MainActivity", "Error creating badge", e);
                }
            } else {
                // Remove badge from Remote nav item
                try {
                    bottomNavigationView.removeBadge(R.id.navigation_remote);
                    Log.d("MainActivity", "Badge removed for remote");
                } catch (Exception e) {
                    Log.e("MainActivity", "Error removing badge", e);
                }
            }
            
            // Handle Settings Nav Badge (separate from watermark option inside settings)
            boolean shouldShowSettingsNavBadge = NewFeatureManager.shouldShowBadge(this, "settings_nav");
            Log.d("MainActivity", "updateFeatureBadgeVisibility: shouldShowSettingsNavBadge=" + shouldShowSettingsNavBadge);
            
            if (shouldShowSettingsNavBadge) {
                // Show badge on Settings nav item as a small dot (no text, no number)
                try {
                    com.google.android.material.badge.BadgeDrawable badge = 
                        bottomNavigationView.getOrCreateBadge(R.id.navigation_settings);
                    badge.setVisible(true);
                    badge.clearNumber();
                    badge.clearText();
                    badge.setHorizontalPadding(0);
                    badge.setVerticalPadding(0);
                    badge.setBackgroundColor(0xFF4CAF50); // Green background
                    Log.d("MainActivity", "Badge shown for settings");
                } catch (Exception e) {
                    Log.e("MainActivity", "Error creating settings badge", e);
                }
            } else {
                // Remove badge from Settings nav item
                try {
                    bottomNavigationView.removeBadge(R.id.navigation_settings);
                    Log.d("MainActivity", "Badge removed for settings");
                } catch (Exception e) {
                    Log.e("MainActivity", "Error removing settings badge", e);
                }
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Error updating badge visibility", e);
        }
    }

    /**
     * Public method to refresh feature badges immediately.
     * Called by fragments after marking features as seen.
     */
    public void refreshFeatureBadges() {
        updateFeatureBadgeVisibility();
    }

    /**
     * Check if Pro feature badge should be shown.
     * Called from HomeFragment to determine badge visibility.
     */
    public boolean shouldShowProBadge() {
        return NewFeatureManager.shouldShowBadge(this, "pro");
    }

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        swipeTouchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        // Install splash screen (shows the themed windowSplashScreenAnimatedIcon)
        SplashScreen.installSplashScreen(this);
        // Apply user-selected theme AFTER splash so postSplashScreenTheme replaced by
        // dynamic choice
        applyTheme();

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
        // possible)-----------

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
        boolean completedOnboarding = sharedPreferencesManager.sharedPreferences.getBoolean(Constants.COMPLETED_ONBOARDING_KEY, false);
        boolean showOnboarding = sharedPreferencesManager.isShowOnboarding();
        android.util.Log.d("MainActivity", "DEBUG - COMPLETED_ONBOARDING_KEY raw value: " + completedOnboarding);
        android.util.Log.d("MainActivity", "DEBUG - isShowOnboarding() result: " + showOnboarding);
        android.util.Log.d("MainActivity", "Should show onboarding: " + showOnboarding);

        if (showOnboarding) {
            // Check if onboarding was actually completed (user went through it)
            if (!completedOnboarding) {
                // User has NOT completed onboarding yet - show full onboarding first
                Intent intent = new Intent(this, com.fadcam.ui.OnboardingActivity.class);
                startActivity(intent);
            } else {
                // User HAS completed onboarding - show What's New screen instead
                Intent intent = new Intent(this, com.fadcam.ui.WhatsNewActivity.class);
                startActivity(intent);
            }
            finish(); // Finish this activity so it's not in the back stack
            return;
        }

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

        // The onboarding check was already done at the beginning of onCreate

        setContentView(R.layout.activity_main);

        // Enable edge-to-edge display for Android 15 compatibility
        enableEdgeToEdge();

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
        // startup)-----------

        // Fragment container for tab navigation
        bottomNavigationView = findViewById(R.id.bottom_navigation);
        
        // Initialize SharedPreferencesManager before using it in fragments
        sharedPreferencesManager = SharedPreferencesManager.getInstance(this);

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

        // Save the original bottom nav background color for restoration later
        // Get colorTopBar from theme (same color as header bar)
        try {
            android.util.TypedValue typedValue = new android.util.TypedValue();
            int colorTopBarAttr = getResources().getIdentifier("colorTopBar", "attr", getPackageName());
            if (colorTopBarAttr != 0 && getTheme().resolveAttribute(colorTopBarAttr, typedValue, true)) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    originalBottomNavColor = getColor(typedValue.resourceId);
                } else {
                    originalBottomNavColor = getResources().getColor(typedValue.resourceId);
                }
                Log.d("MainActivity", "Saved bottom nav color from colorTopBar: " + Integer.toHexString(originalBottomNavColor));
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Error getting bottom nav color from colorTopBar", e);
        }

        // Initialize badge visibility
        updateFeatureBadgeVisibility();

        // Load initial fragment (Home tab) immediately with commitNow()
        // Check if there's already a fragment (e.g., after configuration change)
        Log.d("FragmentNav", "onCreate: About to load initial fragment (position 0), savedInstanceState=" + (savedInstanceState == null ? "null" : "exists"));
        if (savedInstanceState == null) {
            // Fresh launch — load Home tab
            Log.d("FragmentNav", "onCreate: No saved state, loading Home fragment");
            switchFragment(0, false); // Uses commitNow() for instant, synchronous load
            Log.d("FragmentNav", "onCreate: Initial fragment load completed");
        } else {
            // Configuration change / process death — FragmentManager restores all added fragments
            // We need to find which was the current one and ensure others are hidden
            Log.d("FragmentNav", "onCreate: Restoring from savedInstanceState");
            int restoredPosition = savedInstanceState.getInt("current_fragment_position", 0);
            androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
            
            // Hide all fragments except the current one, and restore currentFragmentPosition
            for (int i = 0; i < 6; i++) {
                String tag = FRAGMENT_TAG_PREFIX + i;
                Fragment f = fm.findFragmentByTag(tag);
                if (f != null) {
                    if (i == restoredPosition) {
                        fm.beginTransaction().show(f).commitNow();
                        Log.d("FragmentNav", "onCreate: Showing restored fragment at position " + i + ": " + f.getClass().getSimpleName());
                    } else {
                        fm.beginTransaction().hide(f).commitNow();
                        Log.d("FragmentNav", "onCreate: Hiding restored fragment at position " + i + ": " + f.getClass().getSimpleName());
                    }
                }
            }
            currentFragmentPosition = restoredPosition;
            Log.d("FragmentNav", "onCreate: Restored current position to " + restoredPosition);
        }

        bottomNavigationView.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            int targetPosition = -1;
            
            if (itemId == R.id.navigation_home) {
                targetPosition = 0;
            } else if (itemId == R.id.navigation_records) {
                targetPosition = 1;
            } else if (itemId == R.id.navigation_remote) {
                targetPosition = 2;
            } else if (itemId == R.id.navigation_faditor_mini) {
                targetPosition = 3;
            } else if (itemId == R.id.navigation_settings) {
                targetPosition = 4;
            } else if (itemId == R.id.navigation_lab) {
                targetPosition = 5;
            }
            
            if (targetPosition != -1) {
                // Always use instant switch with fade animation
                switchFragment(targetPosition, true);
            }
            return true;
        });

        // Dock reveal animation – only on fresh cold start, not config changes
        if (savedInstanceState == null) {
            View navContainer = findViewById(R.id.nav_container);
            com.fadcam.ui.DockRevealAnimator.reveal(navContainer, bottomNavigationView);
        }

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

        // theme change)-----------
        try {
            SharedPreferences reopenPrefs = sharedPreferencesManager.sharedPreferences;
            boolean reopenAppearance = reopenPrefs.getBoolean("reopen_appearance_after_theme", false);
            if (reopenAppearance) {
                Log.d("FragmentNav", "Theme reopen: Switching to Settings tab");
                // Clear flag to avoid loops
                reopenPrefs.edit().putBoolean("reopen_appearance_after_theme", false).apply();
                //  Ensure Settings tab selected (index 4)
                switchFragment(4, false);
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
        // theme change)-----------

        // shortcuts)-----------
        handleWidgetIntent();
        // shortcuts)-----------
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // Disable tab swipe when overlay fragment is visible.
        View overlayContainer = findViewById(R.id.overlay_fragment_container);
        boolean overlayVisible = overlayContainer != null && overlayContainer.getVisibility() == View.VISIBLE;
        if (overlayVisible) {
            return super.dispatchTouchEvent(ev);
        }

        final int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                swipeDownX = ev.getRawX();
                swipeDownY = ev.getRawY();
                swipeHandled = false;
                View touched = findDeepestViewAt(getWindow().getDecorView(), ev.getRawX(), ev.getRawY());
                swipeCandidate = !isSwipeExcludedTarget(touched);
                if (previewGestureInProgress && Math.abs(previewGestureZoomRatio - 0.5f) >= 0.01f) {
                    swipeCandidate = false;
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!swipeCandidate || swipeHandled) break;
                if (previewGestureInProgress && Math.abs(previewGestureZoomRatio - 0.5f) >= 0.01f) {
                    swipeCandidate = false;
                    break;
                }
                float dx = ev.getRawX() - swipeDownX;
                float dy = ev.getRawY() - swipeDownY;
                if (Math.abs(dy) > Math.abs(dx)) {
                    swipeCandidate = false;
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                if (!swipeCandidate || swipeHandled) break;
                float dx = ev.getRawX() - swipeDownX;
                float dy = ev.getRawY() - swipeDownY;
                if (Math.abs(dx) > Math.max(swipeTouchSlop * 6f, 180f)
                        && Math.abs(dx) > Math.abs(dy) * SWIPE_HORIZONTAL_RATIO) {
                    int target = currentFragmentPosition + (dx < 0 ? 1 : -1);
                    if (target >= 0 && target <= 5) {
                        switchFragment(target, true);
                        swipeHandled = true;
                        swipeCandidate = false;
                        return true;
                    }
                }
                swipeCandidate = false;
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                swipeCandidate = false;
                swipeHandled = false;
                break;
            }
            default:
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    private boolean isSwipeExcludedTarget(View touchedView) {
        if (touchedView == null) return false;

        View navContainer = findViewById(R.id.nav_container);
        if (navContainer != null && isDescendantOf(touchedView, navContainer)) {
            return true;
        }

        ViewParent parent = touchedView.getParent();
        View current = touchedView;
        while (current != null) {
            if (current instanceof HorizontalScrollView) return true;
            if (current instanceof com.google.android.material.chip.Chip) return true;
            if (current instanceof com.google.android.material.chip.ChipGroup) return true;
            if (current instanceof BottomNavigationView) return true;
            if (current.getId() == R.id.textureView || current.getId() == R.id.fullscreenTextureView) return true;
            if (current instanceof RecyclerView) {
                RecyclerView rv = (RecyclerView) current;
                if (rv.canScrollHorizontally(-1) || rv.canScrollHorizontally(1)) return true;
            }
            if (!(parent instanceof View)) break;
            current = (View) parent;
            parent = current.getParent();
        }
        return false;
    }

    public void setPreviewGestureInProgress(boolean inProgress, float zoomRatio) {
        previewGestureInProgress = inProgress;
        previewGestureZoomRatio = zoomRatio;
    }

    private boolean isDescendantOf(@NonNull View child, @NonNull View ancestor) {
        ViewParent p = child.getParent();
        while (p instanceof View) {
            if (p == ancestor) return true;
            p = p.getParent();
        }
        return false;
    }

    private View findDeepestViewAt(@NonNull View root, float rawX, float rawY) {
        int[] loc = new int[2];
        root.getLocationOnScreen(loc);
        float x = rawX - loc[0];
        float y = rawY - loc[1];
        return findDeepestViewAtInternal(root, x, y);
    }

    private View findDeepestViewAtInternal(@NonNull View view, float x, float y) {
        if (x < 0 || y < 0 || x > view.getWidth() || y > view.getHeight()) return null;
        if (!(view instanceof ViewGroup)) return view;
        ViewGroup group = (ViewGroup) view;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            float childX = x - child.getLeft();
            float childY = y - child.getTop();
            View target = findDeepestViewAtInternal(child, childX, childY);
            if (target != null) return target;
        }
        return view;
    }

    private void handleWidgetIntent() {
        Intent intent = getIntent();
        if (intent != null) {
            // Handle navigation to specific tab (e.g., from notification)
            int navigateToTab = intent.getIntExtra("navigate_to_tab", -1);
            if (navigateToTab >= 0) {
                // Use a delay to ensure fragments are properly initialized
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    switchFragment(navigateToTab, true); // Use smooth fade
                        
                        // Trigger fragment visibility callback for Records tab
                        if (navigateToTab == 1) {
                            // Add extra delay to ensure fragment is fully visible before refreshing
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                try {
                                    Fragment recordsFragment = getSupportFragmentManager()
                                        .findFragmentByTag(FRAGMENT_TAG_PREFIX + navigateToTab);
                                    if (recordsFragment instanceof RecordsFragment) {
                                        RecordsFragment records = (RecordsFragment) recordsFragment;
                                        records.onFragmentBecameVisible();
                                        // Trigger refresh to show the newly recorded video
                                        records.refreshList();
                                        android.util.Log.d("MainActivity", "Records tab refreshed after notification");
                                    }
                                } catch (Exception e) {
                                    android.util.Log.e("MainActivity", "Error triggering Records refresh", e);
                                }
                            }, 300); // Additional delay for refresh
                        }
                }, 200);
                // Clear the extra so it doesn't trigger again on configuration change
                intent.removeExtra("navigate_to_tab");
            }
            
            // Handle widget intent to open shortcuts
            if (intent.getBooleanExtra("open_shortcuts_widgets", false)) {
                // Navigate to Settings tab and then open Shortcuts & Widgets screen
                switchFragment(4, false); // Settings tab
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

    @Override
    protected void onSaveInstanceState(@androidx.annotation.NonNull android.os.Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("current_fragment_position", currentFragmentPosition);
    }

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

    @Override
    public void onBackPressed() {
        // Unified: handle any visible overlay fragment (trash or settings)
        if (handleOverlayBack()) {
            Log.d("OverlayDebug", "onBackPressed: dismissed overlay fragment");
            return;
        }

        // If we're not on the home tab, go to home tab first before exiting
        if (getCurrentFragmentPosition() != 0) {
            switchFragment(0, true); // Enable animation
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
        // state)-----------

        // Restore status/nav bar colors for the current tab
        // With hide/show navigation, fragments stay alive and don't re-trigger color setup
        // So we must restore the correct colors when app returns from background
        restoreBarColorsForCurrentTab();

        // Update badge visibility for new features
        updateFeatureBadgeVisibility();

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
            switchFragment(1, false); // Navigate to Records tab
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
                    if (getCurrentFragmentPosition() != 0) {
                        switchFragment(0, true); // Enable animation
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
    protected void onPause() {
        // Show cloak just before going into background to affect recents snapshot
        try {
            if (sharedPreferencesManager == null) {
                sharedPreferencesManager = SharedPreferencesManager.getInstance(this);
            }
            boolean enabled = sharedPreferencesManager.isCloakRecentsEnabled();
            if (enabled) {
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
                // secure/recents)-----------
            }
        } catch (Exception e) {
            android.util.Log.w("Cloak", "onPause cloak fail", e);
        }
        super.onPause();
    }

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
                // secure/recents)-----------
            }
        } catch (Exception e) {
            android.util.Log.w("Cloak", "onUserLeaveHint cloak fail", e);
        }
    }

    /** Handles back press for any visible overlay fragment (settings or trash). */
    private boolean handleOverlayBack() {
        View overlayContainer = findViewById(R.id.overlay_fragment_container);
        if (overlayContainer == null || overlayContainer.getVisibility() != View.VISIBLE)
            return false;
        Fragment top = getSupportFragmentManager().findFragmentById(R.id.overlay_fragment_container);
        if (top == null)
            return false;
        // Animate fade out then pop
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
        // method(handleOverlayBack_raceGuard)-----------
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clean up handler callbacks to prevent memory leaks
        backPressHandler.removeCallbacks(backPressRunnable);
    }

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

    /**
     * Public wrapper so settings fragments can request a theme change.
     * Persists preference already written by caller and recreates activity to apply
     * resources.
     */
    public void applyThemeFromSettings(String themeName) {
        applyTheme(themeName);
        recreate();
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        Log.d("MainActivity", "Configuration changed - orientation: " +
                (newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ? "landscape"
                        : "portrait"));

        // Notify all fragments about the orientation change
        notifyFragmentsOfOrientationChange(newConfig.orientation);
    }

    /**
     * Handle orientation changes by detaching and re-attaching ALL added fragments in separate transactions.
     * With hide/show navigation, fragments stay alive in memory — their views
     * are never re-inflated on orientation change. We must use TWO separate transactions:
     * 1. Detach all and commit
     * 2. Re-attach all with correct layouts (e.g., layout-land/fragment_home.xml)
     * Using separate transactions prevents FragmentManager from optimizing away the detach+attach.
     */
    private void notifyFragmentsOfOrientationChange(int orientation) {
        try {
            androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
            
            // TRANSACTION 1: Detach all added fragments
            androidx.fragment.app.FragmentTransaction detachTx = fm.beginTransaction();
            for (int i = 0; i < 6; i++) {
                String tag = FRAGMENT_TAG_PREFIX + i;
                Fragment f = fm.findFragmentByTag(tag);
                if (f != null) {
                    detachTx.detach(f);
                    Log.d("MainActivity", "Orientation: detaching fragment at position " + i + ": " + f.getClass().getSimpleName());
                }
            }
            detachTx.commitNow(); // Must commit before re-attaching
            
            // TRANSACTION 2: Re-attach all fragments (they will re-inflate with new orientation's layout)
            androidx.fragment.app.FragmentTransaction attachTx = fm.beginTransaction();
            for (int i = 0; i < 6; i++) {
                String tag = FRAGMENT_TAG_PREFIX + i;
                Fragment f = fm.findFragmentByTag(tag);
                if (f != null) {
                    attachTx.attach(f);
                    // Re-apply hidden state: only the current tab is visible
                    if (i != currentFragmentPosition) {
                        attachTx.hide(f);
                    } else {
                        attachTx.show(f);
                    }
                    Log.d("MainActivity", "Orientation: re-attached fragment at position " + i + ": " + f.getClass().getSimpleName());
                }
            }
            attachTx.commitNow();
            
            Log.d("MainActivity", "Orientation changed to " +
                    (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ? "landscape" : "portrait") +
                    " — all fragments re-inflated");
        } catch (Exception e) {
            Log.e("MainActivity", "Error handling orientation change", e);
        }
    }

    /**
     * Restore status bar, navigation bar, and bottom nav colors based on the current tab.
     * Called from onResume to ensure colors are correct when returning from background.
     */
    private void restoreBarColorsForCurrentTab() {
        if (currentFragmentPosition == 2) {
            // Remote tab uses black bars
            setBottomNavColor(0xFF000000);
            setStatusBarColor(0xFF000000);
            setNavigationBarColor(0xFF000000);
        } else {
            // All other tabs use theme default colors
            setBottomNavColor(0);
            setStatusBarColor(0);
            setNavigationBarColor(0);
        }
    }

    /**
     * Enable edge-to-edge display following Android 15 guidelines
     */
    private void enableEdgeToEdge() {
        // Enable edge-to-edge display
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        // Make system bars transparent
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);

        // Handle window insets properly
        View rootView = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            androidx.core.graphics.Insets systemBars = insets
                    .getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());

            // Apply insets to main content areas
            View bottomNav = findViewById(R.id.bottom_navigation);
            View fragmentContainer = findViewById(R.id.fragment_container);
            View overlayContainer = findViewById(R.id.overlay_fragment_container);
            if (fragmentContainer != null) {
                // Apply top and side insets to main content, but not bottom (handled by bottom
                // nav)
                fragmentContainer.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);
            }

            if (bottomNav != null) {
                // Apply bottom inset to bottom navigation
                bottomNav.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom);
            }

            if (overlayContainer != null) {
                // Apply all insets to overlay container (for trash, etc.)
                overlayContainer.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            }

            return insets;
        });
    }

    // ========== Fragment-based Navigation System ==========
    
    private int currentFragmentPosition = -1; // -1 means no fragment loaded yet
    private static final String FRAGMENT_TAG_PREFIX = "tab_fragment_";
    
    /**
     * Switch to a fragment at the specified position using hide/show for instant switching.
     * Fragments are kept alive in memory — views are preserved, animations continue,
     * and subsequent tab switches are instant (no inflation or setup overhead).
     * Public method accessible to fragments for programmatic navigation.
     * @param position Tab position (0-5)
     * @param animate Whether to animate the transition
     */
    public void switchFragment(int position, boolean animate) {
        Log.d("FragmentNav", "switchFragment: Called with position=" + position + ", animate=" + animate + ", current=" + currentFragmentPosition);
        
        if (position == currentFragmentPosition) {
            Log.d("FragmentNav", "switchFragment: Already showing position " + position + ", returning");
            return; // Already showing this fragment
        }
        
        long startTime = System.currentTimeMillis();
        androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
        androidx.fragment.app.FragmentTransaction transaction = fm.beginTransaction();
        
        // Set custom fade animations
        if (animate) {
            transaction.setCustomAnimations(
                R.anim.fade_in,  // Fast 120ms fade in
                R.anim.fade_out  // Fast 100ms fade out
            );
        }
        
        // Hide the currently visible fragment (if any)
        if (currentFragmentPosition >= 0) {
            String currentTag = FRAGMENT_TAG_PREFIX + currentFragmentPosition;
            Fragment currentFragment = fm.findFragmentByTag(currentTag);
            if (currentFragment != null) {
                transaction.hide(currentFragment);
                Log.d("FragmentNav", "switchFragment: Hiding fragment at position " + currentFragmentPosition);
            }
        }
        
        // Show existing fragment or add new one for the target position
        String targetTag = FRAGMENT_TAG_PREFIX + position;
        Fragment targetFragment = fm.findFragmentByTag(targetTag);
        
        if (targetFragment != null) {
            // Fragment already added — just show it (instant, no view inflation)
            transaction.show(targetFragment);
            Log.d("FragmentNav", "switchFragment: Showing existing fragment at position " + position + ": " + targetFragment.getClass().getSimpleName());
        } else {
            // First time visiting this tab — create and add
            targetFragment = createFragmentForPosition(position);
            transaction.add(R.id.fragment_container, targetFragment, targetTag);
            Log.d("FragmentNav", "switchFragment: Adding new fragment at position " + position + ": " + targetFragment.getClass().getSimpleName());
        }
        
        // Use commitNow() for instant switching (no lag), commit() for animated transitions
        if (animate) {
            Log.d("FragmentNav", "switchFragment: Committing with animation (async)");
            transaction.commit(); // Async for animation
        } else {
            Log.d("FragmentNav", "switchFragment: Committing NOW (synchronous)");
            transaction.commitNow(); // Immediate for instant switching and initial load
        }
        
        long endTime = System.currentTimeMillis();
        Log.d("FragmentNav", "switchFragment: Transaction completed in " + (endTime - startTime) + "ms");
        
        currentFragmentPosition = position;
        Log.d("FragmentNav", "switchFragment: Updated currentFragmentPosition to " + position);
        
        // Handle tab-specific logic (same as old onPageSelected)
        handleTabSelected(position);
    }
    
    /**
     * Create a new fragment instance for the given tab position.
     * Only called the first time a tab is visited — subsequent visits reuse the existing fragment.
     */
    private Fragment createFragmentForPosition(int position) {
        Log.d("FragmentNav", "createFragmentForPosition: Creating new fragment for position " + position);
        
        // Create new fragment if not found
        Fragment newFragment;
        switch (position) {
            case 0:
                // Home tab - check current mode
                String currentMode = sharedPreferencesManager.getCurrentRecordingMode();
                if (com.fadcam.Constants.MODE_FADREC.equals(currentMode)) {
                    newFragment = com.fadcam.fadrec.ui.FadRecHomeFragment.newInstance();
                } else {
                    newFragment = new com.fadcam.ui.HomeFragment();
                }
                break;
            case 1:
                newFragment = new RecordsFragment();
                break;
            case 2:
                newFragment = new RemoteFragment();
                break;
            case 3:
                newFragment = new FaditorMiniFragment();
                break;
            case 4:
                newFragment = new com.fadcam.ui.SettingsHomeFragment();
                break;
            case 5:
                newFragment = new com.fadcam.forensics.ui.ForensicIntelligenceFragment();
                break;
            default:
                newFragment = new com.fadcam.ui.HomeFragment();
        }
        
        Log.d("FragmentNav", "createFragmentForPosition: Created new fragment for position " + position + ": " + newFragment.getClass().getSimpleName());
        return newFragment;
    }
    
    /**
     * Handle tab-specific logic when a tab is selected.
     * Replaces old ViewPager2.OnPageChangeCallback logic.
     */
    private void handleTabSelected(int position) {
        Log.d("FragmentNav", "handleTabSelected: Called for position " + position);
        
        // Update bottom nav selection
        int navItemId = getNavItemIdForPosition(position);
        if (navItemId != -1) {
            bottomNavigationView.setSelectedItemId(navItemId);
        }
        
        // Restore correct bar colors for the selected tab
        restoreBarColorsForCurrentTab();
        
        // Handle tab-specific callbacks
        switch (position) {
            case 1: // Records tab
                // Trigger lazy loading
                try {
                    Fragment recordsFragment = getSupportFragmentManager()
                        .findFragmentByTag(FRAGMENT_TAG_PREFIX + position);
                    if (recordsFragment instanceof RecordsFragment) {
                        ((RecordsFragment) recordsFragment).onFragmentBecameVisible();
                    }
                } catch (Exception e) {
                    Log.e("MainActivity", "Error triggering Records lazy load", e);
                }
                break;
            case 5: // Lab tab
                // Mark feature as seen
                com.fadcam.ui.utils.NewFeatureManager.markFeatureAsSeen(this, "lab");
                refreshFeatureBadges();
                break;
        }
    }
    
    /**
     * Get navigation item ID for a given tab position.
     */
    private int getNavItemIdForPosition(int position) {
        switch (position) {
            case 0: return R.id.navigation_home;
            case 1: return R.id.navigation_records;
            case 2: return R.id.navigation_remote;
            case 3: return R.id.navigation_faditor_mini;
            case 4: return R.id.navigation_settings;
            case 5: return R.id.navigation_lab;
            default: return -1;
        }
    }
    
    /**
     * Get the current fragment position.
     */
    public int getCurrentFragmentPosition() {
        return currentFragmentPosition;
    }

    /**
     * Force recreate the fragment at the specified position.
     * Used for mode switching (FadCam <-> FadRec) where the fragment class changes
     * but the position stays the same.
     * @param position Tab position to recreate
     */
    public void forceRecreateFragment(int position) {
        Log.d("FragmentNav", "forceRecreateFragment: Forcing recreation of fragment at position " + position);
        
        androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
        String tag = FRAGMENT_TAG_PREFIX + position;
        Fragment existingFragment = fm.findFragmentByTag(tag);
        
        if (existingFragment != null) {
            // Remove the existing fragment
            androidx.fragment.app.FragmentTransaction removeTx = fm.beginTransaction();
            removeTx.remove(existingFragment);
            removeTx.commitNow();
            Log.d("FragmentNav", "forceRecreateFragment: Removed existing fragment: " + existingFragment.getClass().getSimpleName());
        }
        
        // Create and add the new fragment
        Fragment newFragment = createFragmentForPosition(position);
        androidx.fragment.app.FragmentTransaction addTx = fm.beginTransaction();
        addTx.add(R.id.fragment_container, newFragment, tag);
        
        // Apply visibility state: only show if it's the current position
        if (position == currentFragmentPosition) {
            addTx.show(newFragment);
        } else {
            addTx.hide(newFragment);
        }
        
        addTx.commitNow();
        Log.d("FragmentNav", "forceRecreateFragment: Added new fragment: " + newFragment.getClass().getSimpleName());
    }
}
