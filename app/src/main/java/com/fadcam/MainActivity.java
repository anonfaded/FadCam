package com.fadcam;

import com.fadcam.FLog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewConfiguration;
import android.widget.Toast;
import android.widget.ImageView;
import com.fadcam.ui.OverlayNavUtil;
import com.fadcam.shortcuts.ShortcutsManager;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;

import com.fadcam.pro.ProReminderManager;
import com.fadcam.security.TabLock;
import com.fadcam.ui.RecordsFragment;
import com.fadcam.ui.HomeFragment;
import com.fadcam.FeatureRegistry;
import com.fadcam.ui.SettingsHomeFragment;
import com.fadcam.ui.utils.NewFeatureManager;
import com.fadcam.utils.RuntimeCompat;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationBarView;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import androidx.core.splashscreen.SplashScreen; // SplashScreen API
import androidx.core.splashscreen.SplashScreenViewProvider;
import android.view.WindowManager;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.ViewCompat;

import androidx.recyclerview.widget.RecyclerView;
import android.content.pm.PackageManager;

import android.widget.HorizontalScrollView;

public class MainActivity extends AppCompatActivity {

    private NavigationBarView bottomNavigationView;
    /** The landscape left rail. Same menu as the bottom pill; only one is visible at a time. */
    private NavigationBarView railNavigationView;
    private NavigationBarView.OnItemSelectedListener navItemSelectedListener;
    /** nav_container's own padding before window insets are added on top. */
    private int navBasePaddingStart, navBasePaddingTop, navBasePaddingEnd, navBasePaddingBottom;
    private int originalBottomNavColor = 0; // Store original bottom nav color

    private boolean doubleBackToExitPressedOnce = false;
    private boolean skipNextBackHandling = false; // New flag to skip toast on next back press
    private Handler backPressHandler = new Handler(android.os.Looper.getMainLooper());
    private static final int BACK_PRESS_DELAY = 2000; // 2 seconds

    // Tab Lock: one gate for every navigation path and one opaque screen for
    // the current tab, so no protected content can be shown or read before the
    // App Lock credential is verified.
    private View tabLockOverlay;
    private boolean tabLockPromptShowing;
    /** True while a tab selection is applied programmatically. */
    private boolean suppressReselectScroll;

    // Add SharedPreferencesManager field
    private SharedPreferencesManager sharedPreferencesManager;
    // Launch splash hand-off. The system SplashScreen view is kept on screen
    // until the Home avatar is ready, then animated into place in one window.
    private SplashScreen splashScreen;
    private boolean splashReady = false;
    private boolean splashAborted = false;
    private HomeFragment splashHomeFragment;
    private com.fadcam.ui.SplashAvatarOverlay splashAvatarOverlay;
    // Cloak overlay view reference
    private View cloakOverlay;
    private android.widget.ImageView cloakIconView;
    private android.widget.TextView cloakTitleView;

    // ── Volume shutter (volume keys as camera shutter) ─────────────
    // Tracks the keycode of the volume key currently held down (0 = none held).
    private int volumeShutterActiveKeyCode = 0;
    // True once a long-press already fired the recording toggle, so the
    // subsequent key-up does not ALSO capture a photo.
    private boolean volumeShutterLongPressTriggered = false;
    // Double-click window: two short presses within this time switch camera.
    private static final long VOLUME_DOUBLE_CLICK_TIMEOUT_MS = 350L;
    // Pending single-click action, delayed to allow a second click to arrive.
    private Runnable pendingVolumeSingleClickRunnable;
    private final Handler volumeShutterHandler = new Handler(Looper.getMainLooper());

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
    /** True while the home quick-actions rearrange (jiggle) mode is active —
     *  tab-swipes and the sidebar-open swipe must not interfere with dragging. */
    private boolean quickActionsRearrangeActive = false;
    /** True while the mode-switcher pill is being dragged. */
    private boolean modePillDragActive = false;
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
     * Re-shows the overlay container when FragmentManager restored an overlay screen.
     *
     * <p>The container's visibility lives in the layout as {@code gone} and is not part of
     * the fragment state that is saved across an activity recreation. Without this, changing
     * the app language (which recreates the activity) dropped the user out of the Settings
     * sub-screen they were in and back onto the Settings tab.</p>
     */
    private void restoreOverlayContainerVisibility() {
        View overlayContainer = findViewById(R.id.overlay_fragment_container);
        if (overlayContainer == null) return;
        Fragment overlayFragment = getSupportFragmentManager()
                .findFragmentById(R.id.overlay_fragment_container);
        if (overlayFragment != null) {
            overlayContainer.setVisibility(View.VISIBLE);
            overlayContainer.setAlpha(1f);
        }
    }

    /**
     * Utility so child fragments can request overlay dismissal after popping back
     * stack.
     */
    public void hideOverlayIfNoFragments() {
        View overlayContainer = findViewById(R.id.overlay_fragment_container);
        if (overlayContainer != null) {
            if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
                overlayContainer.setVisibility(View.GONE);
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
        FLog.d("Chrome", "setBottomNavColor(0x" + Integer.toHexString(color)
                + ") tab=" + currentFragmentPosition
                + " caller=" + new Throwable().getStackTrace()[1].getMethodName());
        int resolved = color;
        if (color == 0) {
            // Restore original color
            resolved = originalBottomNavColor;
        }
        if (bottomNavigationView != null && (color != 0 || resolved != 0)) {
            bottomNavigationView.setBackgroundColor(resolved);
        }
        // The landscape rail is the same dock, so it must follow the same colour.
        View rail = findViewById(R.id.rail_navigation);
        if (rail != null && (color != 0 || resolved != 0)) {
            rail.setBackgroundColor(resolved);
        }
    }

    /**
     * Sets light (dark icons) vs dark (light icons) status bar appearance.
     * Uses WindowInsetsController (API 30+) and falls back to the legacy
     * SYSTEM_UI_FLAG on older devices.
     */
    @SuppressWarnings("deprecation") // legacy SYSTEM_UI_FLAG fallback for < API 30
    private void setStatusBarIconsLight(boolean light) {
        View decor = getWindow() != null ? getWindow().getDecorView() : null;
        if (decor == null) return;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int flag = light ? android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0;
                c.setSystemBarsAppearance(flag, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            }
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            int vis = decor.getSystemUiVisibility();
            decor.setSystemUiVisibility(light
                    ? vis | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    : vis & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    /**
     * Sets light (dark icons) vs dark (light icons) navigation bar appearance.
     * Uses WindowInsetsController (API 30+) and falls back to the legacy
     * SYSTEM_UI_FLAG on older devices.
     */
    @SuppressWarnings("deprecation") // legacy SYSTEM_UI_FLAG fallback for < API 30
    private void setNavigationBarIconsLight(boolean light) {
        View decor = getWindow() != null ? getWindow().getDecorView() : null;
        if (decor == null) return;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int flag = light ? android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0;
                c.setSystemBarsAppearance(flag, android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            int vis = decor.getSystemUiVisibility();
            decor.setSystemUiVisibility(light
                    ? vis | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    : vis & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    /**
     * Set the status bar (notification bar at top) color dynamically.
     * Used by fragments to change status bar color (e.g., Remote tab makes it black).
     *
     * @param color Color as integer (e.g., 0xFF000000 for black), or 0 to restore original from theme
     */
    @SuppressWarnings("deprecation") // setStatusBarColor(int) is deprecated on API 35+; still needed for older edge-to-edge handling
    public void setStatusBarColor(int color) {
        if (getWindow() != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            // Keep status bar transparent so it always blends with dynamic headers/themes.
            getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        }
        
        // Update the status bar scrim color
        View statusBarScrim = findViewById(R.id.status_bar_scrim);
        if (statusBarScrim != null) {
            if (color == 0) {
                // Restore theme color (resolve TopBar color attribute)
                int colorTopBar = resolveThemeColor(this, R.attr.colorTopBar);
                statusBarScrim.setBackgroundColor(colorTopBar);
                
                // Restore theme-based icon tint (Snow Veil is light theme, others are dark)
                String currentTheme = SharedPreferencesManager.getInstance(this).sharedPreferences
                        .getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
                setStatusBarIconsLight("Snow Veil".equals(currentTheme));
            } else {
                // Set custom color (e.g., black for Remote tab)
                statusBarScrim.setBackgroundColor(color);
                
                // For dark backgrounds (like black), ensure icons are light (white)
                setStatusBarIconsLight(false);
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
            // Keep navigation bar transparent in edge-to-edge mode.
                    }
        
        // Update root layout background color to affect the navigation/gesture area background.
        // This area is visible because of the fragment_container and nav_container paddings.
        View root = findViewById(R.id.main_root_layout);
        if (root != null) {
            if (color == 0) {
                // Restore original theme background color
                int bgColor = resolveThemeColor(this, android.R.attr.colorBackground);
                root.setBackgroundColor(bgColor);
                
                // Restore theme-based icon tint
                String currentTheme = SharedPreferencesManager.getInstance(this).sharedPreferences
                        .getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
                setNavigationBarIconsLight("Snow Veil".equals(currentTheme));
            } else {
                // Set custom color
                root.setBackgroundColor(color);
                
                // For dark backgrounds (like black), ensure icons are light (white)
                setNavigationBarIconsLight(false);
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
            
            // Handle Settings Nav Badge (separate from watermark option inside settings)
            boolean shouldShowSettingsNavBadge = NewFeatureManager.shouldShowBadge(this, "settings_nav");
            FLog.d("MainActivity", "updateFeatureBadgeVisibility: shouldShowSettingsNavBadge=" + shouldShowSettingsNavBadge);
            
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
                    badge.setBackgroundColor(androidx.core.content.ContextCompat.getColor(
                            this, R.color.pro_gold)); // Champagne gold new-feature dot
                    FLog.d("MainActivity", "Badge shown for settings");
                } catch (Exception e) {
                    FLog.e("MainActivity", "Error creating settings badge", e);
                }
            } else {
                // Remove badge from Settings nav item
                try {
                    bottomNavigationView.removeBadge(R.id.navigation_settings);
                    FLog.d("MainActivity", "Badge removed for settings");
                } catch (Exception e) {
                    FLog.e("MainActivity", "Error removing settings badge", e);
                }
            }
        } catch (Exception e) {
            FLog.e("MainActivity", "Error updating badge visibility", e);
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
        // Build/package identity for update-channel verification (beta vs stable).
        try {
            FLog.d("UpdateCheck", "App identity: applicationId=" + BuildConfig.APPLICATION_ID
                    + " package=" + getPackageName()
                    + " isBeta=" + BuildConfig.APPLICATION_ID.endsWith(".beta")
                    + " versionName=" + BuildConfig.VERSION_NAME);
        } catch (Exception e) {
            FLog.w("UpdateCheck", "App identity log failed: " + e.getMessage());
        }
        // Install the splash before super.onCreate so the platform/compat splash
        // view is ours to animate; keep it on screen until Home is ready.
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        if (RuntimeCompat.shouldUseWatchUi(this)) {
            startActivity(new Intent(this, com.fadcam.ui.WatchMainActivity.class));
            finish();
            return;
        }
        this.splashScreen = splashScreen;
        splashScreen.setKeepOnScreenCondition(() -> !splashReady);
        splashScreen.setOnExitAnimationListener(this::onSplashExit);
        swipeTouchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

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
            FLog.w("Cloak", "early cloak apply fail", t);
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
            FLog.d("MainActivity", "First install detected! Forcing onboarding to show.");
            sharedPreferencesManager.sharedPreferences.edit()
                    .putBoolean(Constants.COMPLETED_ONBOARDING_KEY, false)
                    .putBoolean(Constants.FIRST_INSTALL_CHECKED_KEY, true)
                    .commit(); // Use commit() for immediate effect
        }

        // Check for onboarding BEFORE applying theme or language
        boolean completedOnboarding = sharedPreferencesManager.sharedPreferences.getBoolean(Constants.COMPLETED_ONBOARDING_KEY, false);
        boolean showOnboarding = sharedPreferencesManager.isShowOnboarding();
        boolean skipWhatsNew = BuildConfig.ENABLE_PRO_REMINDER
                && getIntent().getBooleanExtra(
                        ProReminderManager.EXTRA_SKIP_WHATS_NEW, false);
        FLog.d("MainActivity", "Should show onboarding: " + showOnboarding);

        if (showOnboarding) {
            // Check if onboarding was actually completed (user went through it)
            if (!completedOnboarding) {
                // User has NOT completed onboarding yet - show full onboarding first
                Intent intent = new Intent(this, com.fadcam.ui.OnboardingActivity.class);
                startActivity(intent);
                finish();
                return;
            }
            // A weekly-notification tap is a direct intent: skip What's New for this launch
            // (it will show again on the next normal launch) and let the handler open Pro.
            if (!skipWhatsNew) {
                // User HAS completed onboarding - show What's New screen instead
                // Skip WhatsNewActivity on Wear OS: WebView is not supported
                if (!RuntimeCompat.isWatchDevice(this)) {
                    Intent intent = new Intent(this, com.fadcam.ui.WhatsNewActivity.class);
                    startActivity(intent);
                }
                finish(); // Finish this activity so it's not in the back stack
                return;
            }
        }

        // Now that we know we're not showing onboarding, continue with normal
        // initialization

        // Apply only a language the user explicitly picked. With no saved choice the
        // app keeps following the device language, including later device changes.
        SharedPreferences prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        String savedLanguageCode = prefs.getString(Constants.LANGUAGE_KEY, null);
        if (savedLanguageCode != null && !savedLanguageCode.isEmpty()) {
            applyLanguage(savedLanguageCode);
        } else {
            followSystemLanguage();
        }

        // Check if current locale is Pashto
        if (getResources().getConfiguration().getLocales().get(0).getLanguage().equals("ps")) {
            getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        }

        // The onboarding check was already done at the beginning of onCreate

        setContentView(R.layout.activity_main);

        initTabLockUi();

        // Enable edge-to-edge display for Android 15 compatibility
        enableEdgeToEdge();

        setupBackPressedHandler();

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
            FLog.w("Cloak", "init cloak state fail", t);
        }
        // startup)-----------

        // Fragment container for tab navigation
        bottomNavigationView = findViewById(R.id.bottom_navigation);
        railNavigationView = findViewById(R.id.rail_navigation);
        if (bottomNavigationView != null && com.fadcam.BuildConfig.LITE_EDITION) {
            // Lite edition: three tabs only — Records (left), Home (center), Settings (right).
            // The internal position engine (0-5) is untouched; the menu is just a selector.
            applyLiteMenu(bottomNavigationView);
            applyLiteMenu(railNavigationView);
            applyLiteDockMode();

        }
        // Chooses the bottom pill or the landscape rail, styles it, and lays the
        // fragment container out around it.
        applyResponsiveNavLayout();
        
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
            FLog.w("Cloak", "Failed to init cloak overlay", e);
        }

        // Save the original bottom nav background color for restoration later
        try {
            android.util.TypedValue typedValue = new android.util.TypedValue();
            int colorBottomNavAttr = getResources().getIdentifier("colorBottomNav", "attr", getPackageName());
            if (colorBottomNavAttr != 0 && getTheme().resolveAttribute(colorBottomNavAttr, typedValue, true)) {
                originalBottomNavColor = getResources().getColor(typedValue.resourceId, getTheme());
                FLog.d("MainActivity", "Saved bottom nav color from colorBottomNav: " + Integer.toHexString(originalBottomNavColor));
            }
        } catch (Exception e) {
            FLog.e("MainActivity", "Error getting bottom nav color from colorBottomNav", e);
        }

        // Initialize badge visibility
        updateFeatureBadgeVisibility();

        // Load initial fragment (Home tab) immediately with commitNow()
        // Check if there's already a fragment (e.g., after configuration change)
        if (savedInstanceState == null) {
            // Fresh launch — load Home tab
            switchFragment(0, false); // Uses commitNow() for instant, synchronous load
            prepareSplashAnimation();
            scheduleTabPrewarm();
        } else {
            // Configuration change / process death — FragmentManager restores all added fragments
            // We need to find which was the current one and ensure others are hidden
            // The system splash belongs to a cold start only. prepareSplashAnimation() is the
            // only path that sets splashReady=true, and it runs in the fresh-launch branch
            // above. On a recreation (theme/language change, process restore) it was never
            // called, so keepOnScreenCondition stayed true and the splash window covered the
            // app forever — a full black screen with only dialogs visible on top. Release it
            // immediately instead of replaying the intro.
            splashAborted = true;
            splashReady = true;
            // Overlay screens (Settings sub-tabs such as Appearance) live in their own
            // container that ships hidden in the XML. FragmentManager restores the fragment,
            // but a view's visibility is a View property and is not part of that state, so
            // the container stayed GONE and the recreation dropped the user back onto the
            // Settings tab instead of the sub-screen they were editing.
            restoreOverlayContainerVisibility();
            int restoredPosition = savedInstanceState.getInt("current_fragment_position", 0);
            androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
            
            // Hide all fragments except the current one, and restore currentFragmentPosition
            for (int i = 0; i < 6; i++) {
                String visibleTag = i == 0 ? getHomeFragmentTagForCurrentMode() : FRAGMENT_TAG_PREFIX + i;
                for (String tag : getFragmentTagsForPosition(i)) {
                    Fragment f = fm.findFragmentByTag(tag);
                    if (f != null) {
                        if (i == restoredPosition && tag.equals(visibleTag)) {
                            fm.beginTransaction().show(f).commitNow();
                        } else {
                            fm.beginTransaction().hide(f).commitNow();
                        }
                    }
                }
            }
            currentFragmentPosition = restoredPosition;
            scheduleTabPrewarm();
        }

        // Runs before the splash is released. Moving it after the first frame was
        // measured to gain nothing (the first layout dominates), and running it
        // during the launch animation would stall frames, so it stays here.
        configureStartupUi();

        // shortcuts)-----------
        handleWidgetIntent();
        // shortcuts)-----------
    }

    /** UI wiring that must not delay the first frame of the launch animation. */
    private void configureStartupUi() {
        if (isFinishing() || isDestroyed()) return;

        // Landscape presents every bottom sheet as a full-height trailing panel.
        // Registered through the shared adapter so the editor window can install the
        // same behaviour without duplicating it.
        com.fadcam.ui.SideSheet.install(getSupportFragmentManager());

        // A weekly-notification tap routes through Home first, so backing out of Pro
        // returns to the app (and the after-Pro confirmation) instead of the launcher.
        navItemSelectedListener = item -> {
            int itemId = item.getItemId();
            int targetPosition = -1;
            
            if (itemId == R.id.navigation_home) {
                targetPosition = 0;
                // Keep the user's chosen icon applied (selection re-renders the item).
        applyHomeNavIcon();
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
            updateLiteDockSelection();
            return true;
        };
        // Both docks share the same menu ids; bind both so a rotation does not
        // need to re-attach the listener.
        if (bottomNavigationView != null) {
            bottomNavigationView.setOnItemSelectedListener(navItemSelectedListener);
        }
        if (railNavigationView != null) {
            railNavigationView.setOnItemSelectedListener(navItemSelectedListener);
        }

        // Material only calls the selected listener when the item changes;
        // reselecting the active tab is delivered to this listener.
        NavigationBarView.OnItemReselectedListener navItemReselectedListener = item -> {
            if (suppressReselectScroll) return;
            int position = internalPositionForItemId(item.getItemId());
            if (position != -1) {
                scrollTabToTop(position);
            }
        };
        if (bottomNavigationView != null) {
            bottomNavigationView.setOnItemReselectedListener(navItemReselectedListener);
        }
        if (railNavigationView != null) {
            railNavigationView.setOnItemReselectedListener(navItemReselectedListener);
        }

        // Dock reveal animation – REMOVED: the launch reveal (scale/fade/slide of
        // the bottom nav dock) consumed resources and delayed the dock's presence
        // on every cold start. The dock now simply renders in place.
        // (DockRevealAnimator kept for reference but no longer invoked.)
        View navContainer = findViewById(R.id.nav_container);
        if (navContainer != null) {
            navContainer.setScaleX(1f);
            navContainer.setAlpha(1f);
            navContainer.setTranslationY(0f);
        }
        if (bottomNavigationView != null) {
            bottomNavigationView.setAlpha(1f);
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

        // The initial bar colors and transparency are now handled by switchFragment(0, false) 
        applyHomeNavIcon();
        setupHomeIconCustomization();
        // or restored from saved state via restoreBarColorsForCurrentTab() inside handleTabSelected.

        // theme change)-----------
        try {
            SharedPreferences reopenPrefs = sharedPreferencesManager.sharedPreferences;
            boolean reopenAppearance = reopenPrefs.getBoolean("reopen_appearance_after_theme", false);
            if (reopenAppearance) {
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
                        FLog.e("ThemeReopen", "Failed to reopen appearance fragment", e);
                    }
                }, 100);
            }
        } catch (Exception e) {
            FLog.e("ThemeReopen", "Outer fail", e);
        }
        // theme change)-----------

        // dpad_settings)-----------
        // Register once so every settings sub-fragment gets proper D-pad focus when opened.
        // The helper silently no-ops on non-settings fragments (those without content_scroll).
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentViewCreated(
                            @androidx.annotation.NonNull androidx.fragment.app.FragmentManager fm,
                            @androidx.annotation.NonNull androidx.fragment.app.Fragment f,
                            @androidx.annotation.NonNull View v,
                            @androidx.annotation.Nullable android.os.Bundle savedInstanceState) {
                        com.fadcam.ui.utils.DpadSettingsFocusHelper.setup(v);
                    }
                }, false /* non-recursive: only direct overlay_fragment_container children */);
        // dpad_settings)-----------

    }


    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // The opaque lock screen owns all touches. In particular, skip the
        // activity-level tab swipe handler so it cannot open the Home sidebar
        // or navigate behind the lock.
        if (tabLockOverlay != null && tabLockOverlay.getVisibility() == View.VISIBLE) {
            return super.dispatchTouchEvent(ev);
        }

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
                // Rearrange mode: dragging quick-action icons must never trigger
                // tab navigation or the sidebar-open swipe.
                if (quickActionsRearrangeActive || modePillDragActive) {
                    swipeCandidate = false;
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!swipeCandidate || swipeHandled) break;
                // Rearrange mode may start mid-gesture (long-press) — kill the swipe.
                if (quickActionsRearrangeActive || modePillDragActive) {
                    swipeCandidate = false;
                    break;
                }
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
                // Rearrange mode: never navigate tabs or open the sidebar.
                if (quickActionsRearrangeActive || modePillDragActive) {
                    swipeCandidate = false;
                    break;
                }
                float dx = ev.getRawX() - swipeDownX;
                float dy = ev.getRawY() - swipeDownY;
                if (Math.abs(dx) > Math.max(swipeTouchSlop * 6f, 180f)
                        && Math.abs(dx) > Math.abs(dy) * SWIPE_HORIZONTAL_RATIO) {
                    // Check layout direction for RTL-aware swipe handling
                    boolean isRtl = getWindow().getDecorView().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                    boolean isSwipeBack = isRtl ? (dx < 0) : (dx > 0);
                    
                    // Navigate by the VISIBLE menu order (Lite has fewer tabs and a
                    // different order than Full — internal positions must not drive swipes).
                    java.util.List<Integer> visibleIds = visibleTabIds();
                    int curIdx = -1;
                    int curId = getNavItemIdForPosition(currentFragmentPosition);
                    for (int i = 0; i < visibleIds.size(); i++) {
                        if (visibleIds.get(i) == curId) {
                            curIdx = i;
                            break;
                        }
                    }
                    if (curIdx >= 0) {
                        // Swipe "back" from the FIRST (leftmost) home tab opens the sidebar
                        if (isSwipeBack && curIdx == 0 && curId == R.id.navigation_home) {
                            openHomeSidebarFromSwipe();
                            swipeHandled = true;
                            swipeCandidate = false;
                            return true;
                        }
                        // RTL-aware tab navigation: reverse direction in RTL layout
                        int direction = isRtl ? (dx > 0 ? 1 : -1) : (dx < 0 ? 1 : -1);
                        int targetIdx = curIdx + direction;
                        if (targetIdx >= 0 && targetIdx < visibleIds.size()) {
                            int targetPos = internalPositionForItemId(visibleIds.get(targetIdx));
                            if (targetPos >= 0) {
                                switchFragment(targetPos, true);
                                swipeHandled = true;
                                swipeCandidate = false;
                                return true;
                            }
                        }
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

    /** Wires a compact-dock button to the hidden nav engine. */
    private static int mix(int base, int overlay, float t) {
        int a = (base >>> 24) & 0xFF;
        int r = (base >>> 16) & 0xFF;
        int g = (base >>> 8) & 0xFF;
        int b = base & 0xFF;
        int ar = (overlay >>> 16) & 0xFF;
        int ag = (overlay >>> 8) & 0xFF;
        int ab = overlay & 0xFF;
        return (a << 24) | ((int) (r + (ar - r) * t) << 16)
                | ((int) (g + (ag - g) * t) << 8) | (int) (b + (ab - b) * t);
    }

    private void applyLiteDockSideAlignment(android.view.View records, android.view.View settings, android.view.View home) {
        if (records == null || settings == null || home == null) return;
        android.view.ViewGroup.LayoutParams rp = records.getLayoutParams();
        android.view.ViewGroup.LayoutParams sp = settings.getLayoutParams();
        android.view.ViewGroup.LayoutParams hp = home.getLayoutParams();
        int circleBottom = hp.height; // home is square; container height == home height
        int sideH = (int) getResources().getDimension(com.fadcam.R.dimen.home_dock_side_height);
        rp.height = sideH;
        sp.height = sideH;
        ((android.view.ViewGroup.MarginLayoutParams) rp).topMargin = (circleBottom - sideH) / 2;
        ((android.view.ViewGroup.MarginLayoutParams) sp).topMargin = (circleBottom - sideH) / 2;
        records.setLayoutParams(rp);
        settings.setLayoutParams(sp);
    }

    private void bindLiteDockItem(android.view.View dock, int dockItemId, final int navItemId) {
        android.view.View item = dock.findViewById(dockItemId);
        if (item == null) return;
        item.setOnClickListener(v -> {
            if (bottomNavigationView != null) {
                bottomNavigationView.setSelectedItemId(navItemId);
            }
        });
    }

    /** Highlights the selected Lite dock button; animates the label like Material. */
    private void updateLiteDockSelection() {
        android.view.View dock = findViewById(R.id.lite_dock);
        if (dock == null || dock.getVisibility() != android.view.View.VISIBLE) return;
        int selectedId = getNavItemIdForPosition(currentFragmentPosition);
        applyLiteDockItemState(dock, R.id.lite_dock_item_records, R.id.lite_dock_icon_records,
                0, selectedId == R.id.navigation_records);
        applyLiteDockItemState(dock, R.id.lite_dock_item_home, R.id.lite_dock_icon_home,
                R.id.lite_dock_label_home, selectedId == R.id.navigation_home);
        applyLiteDockItemState(dock, R.id.lite_dock_item_settings, R.id.lite_dock_icon_settings,
                0, selectedId == R.id.navigation_settings);
    }

    private void applyLiteDockItemState(android.view.View dock, int itemId, int iconId, int labelId,
                                        boolean selected) {
        android.widget.ImageView icon = dock.findViewById(iconId);
        android.widget.TextView label = dock.findViewById(labelId);
        if (icon != null) {
            icon.setColorFilter(selected ? 0xFFFFFFFF : 0x66FFFFFF,
                    android.graphics.PorterDuff.Mode.SRC_IN);
        }
        if (label != null) {
            // GONE when inactive = icon fully centered (no reserved gap);
            // VISIBLE when active = icon rises and label appears (animateLayoutChanges
            // on the item animates the icon movement like the stable dock).
            label.setVisibility(selected ? android.view.View.VISIBLE : android.view.View.GONE);
        }
    }

    /** Visible tab ids in display order (menu order = what the user sees). */
    private java.util.List<Integer> visibleTabIds() {
        java.util.ArrayList<Integer> ids = new java.util.ArrayList<>();
        if (bottomNavigationView == null) return ids;
        for (int i = 0; i < bottomNavigationView.getMenu().size(); i++) {
            ids.add(bottomNavigationView.getMenu().getItem(i).getItemId());
        }
        return ids;
    }

    /** Internal fragment position for a menu item id (matches the nav listener). */
    private int internalPositionForItemId(int itemId) {
        if (itemId == R.id.navigation_home) return 0;
        if (itemId == R.id.navigation_records) return 1;
        if (itemId == R.id.navigation_remote) return 2;
        if (itemId == R.id.navigation_faditor_mini) return 3;
        if (itemId == R.id.navigation_settings) return 4;
        if (itemId == R.id.navigation_lab) return 5;
        return -1;
    }

    /** Opens the home sidebar when the user swipes left from the home tab. */
    private void openHomeSidebarFromSwipe() {
        if (currentFragmentPosition == 0) {
            androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
            String tag = getFragmentTagForPosition(0);
            androidx.fragment.app.Fragment frag = fm.findFragmentByTag(tag);
            if (frag instanceof com.fadcam.ui.HomeFragment) {
                com.fadcam.ui.HomeSidebarFragment sidebar = com.fadcam.ui.HomeSidebarFragment.newInstance();
                sidebar.show(fm, "HomeSidebar");
            }
        }
    }

    private boolean isSwipeExcludedTarget(View touchedView) {
        if (touchedView == null) return false;

        View navContainer = findViewById(R.id.nav_container);
        if (navContainer != null && isDescendantOf(touchedView, navContainer)) {
            FLog.d("TabSwipe","excl "+touchedView.getClass().getSimpleName()+" #"+touchedView.getId()); return true;
        }

        ViewParent parent = touchedView.getParent();
        View current = touchedView;
        while (current != null) {
            if (current.getId() == R.id.nav_container) return true;
            // Interactive surfaces only exist on the HOME tab (quick-actions reorder,
            // mode pill, tutorial, preview, fast-scroll). On other tabs (Records body)
            // horizontal swipes must switch tabs, not be swallowed by the list.
            if (current instanceof androidx.viewpager2.widget.ViewPager2) return true;
            if (current instanceof androidx.viewpager.widget.ViewPager) return true;
            if (current instanceof HorizontalScrollView) return true;
            if (current.getId() == R.id.tutorial_scroll) return true;
            if (current.getId() == R.id.mode_switcher || current.getId() == R.id.mode_switcher_root) return true;
            if (current instanceof com.fadcam.ui.GalleryFastScroller) return true;
            if (current instanceof com.google.android.material.chip.Chip) return true;
            if (current instanceof com.google.android.material.chip.ChipGroup) return true;
            if (current instanceof com.google.android.material.bottomnavigation.BottomNavigationView) return true;
            if (current.getId() == R.id.textureView || current.getId() == R.id.fullscreenTextureView) return true;
            if (current.getId() == R.id.cardPreview) {
                View previewTexture = findViewById(R.id.textureView);
                if (previewTexture != null && previewTexture.getVisibility() == View.VISIBLE) {
                    return true;
                }
            }
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

    /** Called by HomeFragment when the quick-actions rearrange mode starts/ends. */
    public void setQuickActionsRearrangeActive(boolean active) {
        quickActionsRearrangeActive = active;
    }

    /** Called by the mode-switcher component while the pill is being dragged. */
    public void setModePillDragActive(boolean active) {
        modePillDragActive = active;
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
            // Account for the parent's scroll offset — child.getLeft() / getTop()
            // return layout positions, which are NOT adjusted for scrolling.
            float childX = x - child.getLeft() + group.getScrollX();
            float childY = y - child.getTop() + group.getScrollY();
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
                                        FLog.d("MainActivity", "Records tab refreshed after notification");
                                    }
                                } catch (Exception e) {
                                    FLog.e("MainActivity", "Error triggering Records refresh", e);
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
                        FLog.e("WidgetIntent", "Failed to open shortcuts fragment", e);
                    }
                }, 100);
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N_MR1)
    private void createDynamicShortcuts() {
        new ShortcutsManager(this).publishAllDynamic();
    }

    public void applyLanguage(String languageCode) {
        if (languageCode == null || languageCode.isEmpty()) return;
        androidx.core.os.LocaleListCompat desired =
                androidx.core.os.LocaleListCompat.forLanguageTags(languageCode);
        if (desired.isEmpty()) return;

        // The previous implementation built a ConfigurationContext, discarded it, then
        // recreated the activity. The activity's locale never changed, so every recreation
        // ran this method again and recreated forever (visible as UI flicker, e.g. switching
        // to Chinese). AppCompat applies the locale for real and recreates once; on the new
        // instance the check below matches and the loop stops.
        androidx.core.os.LocaleListCompat current =
                androidx.appcompat.app.AppCompatDelegate.getApplicationLocales();
        String currentTag = current.isEmpty()
                ? getResources().getConfiguration().getLocales().get(0).toLanguageTag()
                : current.get(0).toLanguageTag();
        String desiredTag = desired.get(0).toLanguageTag();

        if (desiredTag.equals(currentTag)) {
            FLog.d("MainActivity", "Language is already set to " + languageCode + "; no need to change.");
            return;
        }
        // A dialog open at this moment is restored by FragmentManager from its saved
        // arguments, which hold the title and the checked id resolved in the *previous*
        // locale. That is why the language sheet came back still showing the old language
        // selected and titled in the old language. Close dialogs first so the recreation
        // starts clean and the caller reopens the sheet in the new locale on demand.
        dismissOpenDialogs();
        FLog.d("MainActivity", "Applying language: " + languageCode);
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(desired);
    }

    /**
     * Dismisses every dialog currently attached to this activity (including dialogs owned
     * by overlay sub-fragments). Used before a locale change, because restored dialogs keep
     * the text and selection they were created with.
     */
    private void dismissOpenDialogs() {
        try {
            androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
            for (androidx.fragment.app.Fragment fragment :
                    new java.util.ArrayList<>(fm.getFragments())) {
                dismissDialogsRecursively(fragment);
            }
        } catch (Exception e) {
            FLog.w("MainActivity", "dismissOpenDialogs failed", e);
        }
    }

    private void dismissDialogsRecursively(androidx.fragment.app.Fragment fragment) {
        if (fragment == null) return;
        if (fragment instanceof androidx.fragment.app.DialogFragment) {
            androidx.fragment.app.DialogFragment dialog =
                    (androidx.fragment.app.DialogFragment) fragment;
            if (dialog.isAdded() && !dialog.isRemoving()) {
                dialog.dismissAllowingStateLoss();
            }
            return;
        }
        if (fragment.isAdded()) {
            for (androidx.fragment.app.Fragment child :
                    new java.util.ArrayList<>(fragment.getChildFragmentManager().getFragments())) {
                dismissDialogsRecursively(child);
            }
        }
    }

    /**
     * Clears any AppCompat per-app locale override so the app follows the device
     * language. Used only while the user has never picked a language manually.
     */
    private void followSystemLanguage() {
        if (!androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().isEmpty()) {
            FLog.d("MainActivity", "No saved language choice; following the device language.");
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                    androidx.core.os.LocaleListCompat.getEmptyLocaleList());
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onStop() {
        // If the user leaves mid-splash, restore the real Home avatar before the
        // activity is hidden so returning to the app never shows a frozen frame.
        splashAborted = true;
        splashReady = true;
        if (splashAvatarOverlay != null) {
            splashAvatarOverlay.cancel();
            splashAvatarOverlay = null;
        } else if (splashHomeFragment != null
                && splashHomeFragment.isSplashTransitionActive()) {
            splashHomeFragment.cancelSplashTransition();
        }
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
            FLog.w("Cloak", "applyCloakIfNeeded failed", e);
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
            FLog.w("Cloak", "applyCloakPreferenceNow failed", e);
        }
    }

    private void setupBackPressedHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // A locked current tab owns the whole screen. Back returns Home,
                // or closes the app when Home itself is the locked tab.
                if (tabLockOverlay != null && tabLockOverlay.getVisibility() == View.VISIBLE) {
                    handleTabLockScreenBack();
                    return;
                }

                // Back during the launch avatar animation finishes the animation
                // instead of leaving the app.
                if (splashAvatarOverlay != null && splashAvatarOverlay.isShowing()) {
                    splashAvatarOverlay.skip();
                    return;
                }

                // Unified: handle any visible overlay fragment (trash or settings)
                if (handleOverlayBack()) {
                    return;
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
                    Toast.makeText(MainActivity.this, getString(R.string.press_back_again_exit), Toast.LENGTH_SHORT).show();

                    // Reset the flag after a delay
                    backPressHandler.postDelayed(backPressRunnable, BACK_PRESS_DELAY);
                }
            }
        });
    }

    /**
     * Keeps the system splash on screen until the Home avatar is laid out, then
     * releases it. The exit listener owns the single-window avatar animation.
     */
    private void prepareSplashAnimation() {
        if (isFinishing() || isDestroyed()) {
            splashReady = true;
            return;
        }
        Intent intent = getIntent();
        if (intent != null && intent.getIntExtra("navigate_to_tab", -1) >= 0) {
            splashReady = true;
            return;
        }
        if (sharedPreferencesManager == null) {
            sharedPreferencesManager = SharedPreferencesManager.getInstance(this);
        }
        // If the landing Home tab is locked, the opaque lock screen is the first
        // thing that must be seen. Playing the avatar intro behind it would only
        // delay the credential prompt and waste frames.
        if (TabLock.isLocked(this, TabLock.TAB_HOME)) {
            splashReady = true;
            return;
        }
        String style = sharedPreferencesManager.sharedPreferences.getString(
                Constants.PREF_SPLASH_STYLE, Constants.SPLASH_STYLE_DEFAULT);
        if (Constants.SPLASH_STYLE_FLAG.equals(style)) {
            // The exit listener crossfades into the flag; no avatar to hide.
            splashReady = true;
            return;
        }
        // FadRec's Home has a different layout and no sleeping avatar.
        if (Constants.MODE_FADREC.equals(sharedPreferencesManager.getCurrentRecordingMode())) {
            splashReady = true;
            return;
        }
        // Honour the system "remove animations" accessibility setting.
        float animatorScale = android.provider.Settings.Global.getFloat(
                getContentResolver(),
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f);
        if (animatorScale == 0f) {
            splashReady = true;
            return;
        }
        Fragment fragment = getSupportFragmentManager()
                .findFragmentById(R.id.fragment_container);
        if (!(fragment instanceof HomeFragment)) {
            splashReady = true;
            return;
        }
        splashHomeFragment = (HomeFragment) fragment;
        // Attach the overlay NOW, inside onCreate, so the app's very first frame
        // already contains the avatar. The system splash has been made blank, so
        // dismissing it later simply reveals the same avatar with no gap.
        splashAvatarOverlay = new com.fadcam.ui.SplashAvatarOverlay(
                this, splashHomeFragment);
        splashAvatarOverlay.start();
        splashReady = true;
    }

    /**
     * Called once the system splash is ready to leave the screen. Everything from
     * here happens inside the splash window itself, so there is no activity cut.
     */
    private void onSplashExit(SplashScreenViewProvider provider) {
        if (isFinishing() || isDestroyed() || splashAborted) {
            if (splashHomeFragment != null && splashAvatarOverlay == null
                    && splashHomeFragment.isSplashTransitionActive()) {
                splashHomeFragment.cancelSplashTransition();
            }
            provider.remove();
            return;
        }
        // The overlay is already attached underneath the system splash. Give it the
        // exact size the system splash is showing before dropping the splash, so its
        // first frame is the same image instead of a resized one.
        if (splashAvatarOverlay != null) {
            try {
                android.view.View icon = provider.getIconView();
                if (icon instanceof android.widget.ImageView) {
                    android.graphics.drawable.Drawable d =
                            ((android.widget.ImageView) icon).getDrawable();
                    android.graphics.Rect bounds = new android.graphics.Rect();
                    if (d != null) d.copyBounds(bounds);
                    int canvas = Math.max(bounds.width(), icon.getWidth());
                    if (d != null && canvas > 0) {
                        splashAvatarOverlay.adoptSplashIcon(d, canvas);
                    }
                }
            } catch (Exception e) {
                com.fadcam.FLog.d("SplashAvatar", "splash icon hand-off failed: " + e);
            }
            provider.remove();
            return;
        }
        String style = sharedPreferencesManager != null
                ? sharedPreferencesManager.sharedPreferences.getString(
                        Constants.PREF_SPLASH_STYLE, Constants.SPLASH_STYLE_DEFAULT)
                : Constants.SPLASH_STYLE_DEFAULT;
        if (Constants.SPLASH_STYLE_FLAG.equals(style) || splashHomeFragment == null) {
            runFallbackSplashExit(provider);
            return;
        }
        // Overlay was already attached in onCreate; just drop the system splash.
        if (splashAvatarOverlay != null) {
            provider.remove();
            return;
        }
        runFallbackSplashExit(provider);
    }
    /**
     * Flag style and non-Home launches: no avatar motion. Crossfade the flag (or
     * simply fade the system splash) and reveal the already-laid-out Home.
     */
    private void runFallbackSplashExit(SplashScreenViewProvider provider) {
        if (splashHomeFragment != null && splashAvatarOverlay == null
                && splashHomeFragment.isSplashTransitionActive()) {
            splashHomeFragment.cancelSplashTransition();
        }
        String style = sharedPreferencesManager != null
                ? sharedPreferencesManager.sharedPreferences.getString(
                        Constants.PREF_SPLASH_STYLE, Constants.SPLASH_STYLE_DEFAULT)
                : Constants.SPLASH_STYLE_DEFAULT;
        View splash = provider.getView();
        if (splash == null) {
            provider.remove();
            return;
        }
        if (Constants.SPLASH_STYLE_FLAG.equals(style) && splash instanceof ViewGroup) {
            View icon = provider.getIconView();
            if (icon != null) {
                icon.animate().alpha(0f).setDuration(180L).start();
            }
            android.widget.ImageView flag = new android.widget.ImageView(this);
            flag.setImageResource(R.drawable.fadseclab_flag);
            flag.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            flag.setAlpha(0f);
            android.widget.FrameLayout.LayoutParams lp =
                    new android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            android.view.Gravity.CENTER);
            ((ViewGroup) splash).addView(flag, lp);
            flag.animate().alpha(1f).setDuration(200L).start();
            splash.postDelayed(() -> splash.animate()
                    .alpha(0f)
                    .setDuration(220L)
                    .withEndAction(provider::remove)
                    .start(), 900L);
            return;
        }
        splash.animate()
                .alpha(0f)
                .setDuration(160L)
                .withEndAction(provider::remove)
                .start();
    }

    /**
     * Handle D-pad navigation for Android TV. Routes DPAD_LEFT/RIGHT to tab switching
     * and passes other keys to current fragment.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // ── Volume shutter ──────────────────────────────────────────
        // Long press = start/stop recording, single click = FadShot photo,
        // double click = switch camera. Active only on the home tab while the
        // preference is enabled.
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (isVolumeShutterActive()) {
                if (event.getRepeatCount() == 0) {
                    // First key-down: arm the shutter, wait for long-press or release
                    volumeShutterActiveKeyCode = keyCode;
                    volumeShutterLongPressTriggered = false;
                } else if (volumeShutterActiveKeyCode == keyCode && !volumeShutterLongPressTriggered) {
                    // Key repeat = long press → toggle recording
                    volumeShutterLongPressTriggered = true;
                    // A click was pending from a previous tap — cancel it, this is now a long press
                    cancelPendingVolumeClick();
                    triggerVolumeShutterLongPress();
                }
                return true; // Consume so the system volume doesn't change
            }
            volumeShutterActiveKeyCode = 0;
        }

        Fragment currentFragment = getCurrentFragment();
        int currentPos = getCurrentFragmentPosition();

        // D-pad up/down: move focus explicitly so it always works even after state
        // transitions that disable views (e.g. Start→recording causes buttonPauseResume to
        // be disabled, Android loses the focus chain and DPAD stops working).
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            int direction = (keyCode == KeyEvent.KEYCODE_DPAD_UP) ? View.FOCUS_UP : View.FOCUS_DOWN;
            View focused = getCurrentFocus();
            // If a real descendant has focus, try to traverse from it
            if (focused != null && focused.getId() != R.id.main_root_layout) {
                View next = focused.focusSearch(direction);
                if (next != null && next != focused && next.getId() != R.id.main_root_layout) {
                    next.requestFocus();
                    return true;
                }
            }
            // Fallback: focus is lost (root grabbed it or no focused view).
            // Try known anchors first, then fall back to ANY first focusable descendant.
            // Only fall back if focus is truly missing (null or root). If a real view has
            // focus but focusSearch failed, leave it alone so we don't reset the user's position.
            View currentlyFocused = getCurrentFocus();
            boolean focusLost = currentlyFocused == null || currentlyFocused.getId() == R.id.main_root_layout;
            if (focusLost && currentFragment != null && currentFragment.getView() != null) {
                View anchor = currentFragment.getView().findViewById(R.id.btnHamburgerMenu);
                if (anchor == null) anchor = currentFragment.getView().findViewById(R.id.buttonStartStop);
                if (anchor != null && anchor.isEnabled()) {
                    anchor.requestFocus();
                    return true;
                }
                // Generic fallback: find first focusable, visible, enabled descendant
                View first = com.fadcam.ui.utils.DpadSettingsFocusHelper.findFirstFocusable(currentFragment.getView());
                if (first != null) {
                    first.requestFocus();
                    return true;
                }
            }
            return true;
        }
        
        // D-pad right: navigate to next tab
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (currentFragment instanceof HomeFragment) {
                HomeFragment homeFragment = (HomeFragment) currentFragment;
                if (homeFragment.onKeyDown(keyCode, event)) {
                    return true; // Fragment consumed it (e.g., dialog navigation)
                }
            }
            // Switch to next tab if not at end
            if (currentPos < 4) {
                switchFragment(currentPos + 1, true);
                return true;
            }
            return true;
        }
        
        // D-pad left: navigate to previous tab or stay on home
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (currentFragment instanceof HomeFragment) {
                HomeFragment homeFragment = (HomeFragment) currentFragment;
                if (homeFragment.onKeyDown(keyCode, event)) {
                    return true; // Fragment consumed it
                }
            }
            // Switch to previous tab if not at start
            if (currentPos > 0) {
                switchFragment(currentPos - 1, true);
                return true;
            }
            return true;
        }
        
        // D-pad center/ENTER: let current fragment handle it
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (currentFragment instanceof HomeFragment) {
                if (((HomeFragment) currentFragment).onKeyDown(keyCode, event)) {
                    return true;
                }
            }
        }
        
        return super.onKeyDown(keyCode, event);
    }

    /**
     * Volume shutter key-up: a short press captures a FadShot photo, unless a
     * second press arrives within the double-click window (then it switches
     * camera instead). A long press already toggled recording, so key-up only
     * resets the shutter state.
     */
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (volumeShutterActiveKeyCode == keyCode && isVolumeShutterActive()) {
                if (!volumeShutterLongPressTriggered) {
                    // Short press: wait briefly to distinguish single from double click
                    if (pendingVolumeSingleClickRunnable != null) {
                        // Second click within the window → double click → switch camera
                        volumeShutterHandler.removeCallbacks(pendingVolumeSingleClickRunnable);
                        pendingVolumeSingleClickRunnable = null;
                        triggerVolumeShutterCameraSwitch();
                    } else {
                        pendingVolumeSingleClickRunnable = () -> {
                            pendingVolumeSingleClickRunnable = null;
                            // Re-check so we never fire after the user left the home tab
                            if (isVolumeShutterActive()) {
                                triggerVolumeShutterClick();
                            }
                        };
                        volumeShutterHandler.postDelayed(
                                pendingVolumeSingleClickRunnable, VOLUME_DOUBLE_CLICK_TIMEOUT_MS);
                    }
                } else {
                    // Long press already toggled recording — drop any pending click
                    cancelPendingVolumeClick();
                }
                volumeShutterActiveKeyCode = 0;
                volumeShutterLongPressTriggered = false;
                return true; // Consume so the system volume doesn't change
            }
            cancelPendingVolumeClick();
            volumeShutterActiveKeyCode = 0;
            volumeShutterLongPressTriggered = false;
        }
        return super.onKeyUp(keyCode, event);
    }

    /**
     * Whether the volume shutter is currently active: home tab visible AND the
     * preference is enabled. Falls back to disabled on any error.
     */
    private boolean isVolumeShutterActive() {
        if (tabLockOverlay != null && tabLockOverlay.getVisibility() == View.VISIBLE) return false;
        if (getCurrentFragmentPosition() != 0) return false;
        try {
            if (sharedPreferencesManager == null) {
                sharedPreferencesManager = SharedPreferencesManager.getInstance(this);
            }
            return sharedPreferencesManager.isVolumeShutterEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    /** Route a volume-shutter long press to the home fragment (start/stop recording). */
    private void triggerVolumeShutterLongPress() {
        Fragment current = getCurrentFragment();
        if (current instanceof HomeFragment) {
            ((HomeFragment) current).handleVolumeShutterLongPress();
        }
    }

    /** Route a volume-shutter click to the home fragment (capture FadShot photo). */
    private void triggerVolumeShutterClick() {
        Fragment current = getCurrentFragment();
        if (current instanceof HomeFragment) {
            ((HomeFragment) current).handleVolumeShutterClick();
        }
    }

    /** Route a volume-shutter double click to the home fragment (switch camera). */
    private void triggerVolumeShutterCameraSwitch() {
        Fragment current = getCurrentFragment();
        if (current instanceof HomeFragment) {
            ((HomeFragment) current).handleVolumeShutterCameraSwitch();
        }
    }

    /** Drop any scheduled single-click action (e.g. after a long press or leaving home). */
    private void cancelPendingVolumeClick() {
        if (pendingVolumeSingleClickRunnable != null) {
            volumeShutterHandler.removeCallbacks(pendingVolumeSingleClickRunnable);
            pendingVolumeSingleClickRunnable = null;
        }
    }

    /**
     * Get current fragment that's visible (not hidden).
     */
    private Fragment getCurrentFragment() {
        androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
        for (String tag : getFragmentTagsForPosition(currentFragmentPosition)) {
            Fragment fragment = fm.findFragmentByTag(tag);
            if (fragment != null && fragment.isAdded() && !fragment.isHidden()) {
                return fragment;
            }
        }
        return null;
    }

    private int platformStatusBarHeightPx = -1;

    /** Status bar height from the platform, stable whether or not the bar is currently shown. */
    private int getPlatformStatusBarHeight() {
        if (platformStatusBarHeightPx < 0) {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            platformStatusBarHeightPx = id > 0 ? getResources().getDimensionPixelSize(id) : 0;
        }
        return platformStatusBarHeightPx;
    }


    private boolean immersiveDialogsRegistered = false;
    // Status bar / cutout height in px, computed from the insets this activity receives.
    private int topBandPx = 0;

    /**
     * Gives the band to the current screen: its header, its scroll container, or its root.
     * Called from the inset dispatch and whenever a screen's view is created, so tab switches
     * and sub-screens behave the same.
     */
    private void applyBandToCurrentFragment() {
        // Both containers span the whole window, so neither may keep its own top padding:
        // the screen inside applies the band, and its background fills it. Settings
        // sub-screens live in the overlay container, which is why the band has to be pushed
        // to both fragments rather than only the one in fragment_container.
        for (int id : new int[]{R.id.fragment_container, R.id.overlay_fragment_container}) {
            View container = findViewById(id);
            if (container != null) {
                container.setPadding(container.getPaddingLeft(), 0,
                        container.getPaddingRight(), container.getPaddingBottom());
            }
        }
        androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
        // Tabs use add/hide/show, so fragment_container can hold several fragments at once.
        // findFragmentById() returns only one of them (often a hidden tab), which left the
        // visible tab without its status-bar band after an activity recreation (e.g. an
        // app-language change). Band every restored tab; HeaderInset remembers each view's
        // base padding, so this is idempotent.
        for (int i = 0; i < 6; i++) {
            for (String tag : getFragmentTagsForPosition(i)) {
                Fragment f = fm.findFragmentByTag(tag);
                if (f != null) {
                    applyBandToFragment(f);
                }
            }
        }
        View overlay = findViewById(R.id.overlay_fragment_container);
        if (overlay != null && overlay.getVisibility() == View.VISIBLE) {
            applyBandToFragment(fm.findFragmentById(R.id.overlay_fragment_container));
        }
    }

    /**
     * Records, Faditor, Settings and Lab keep a fixed content height below the status
     * band so the four headers are visually identical. {@code minHeight} alone is not
     * honoured once the band is pushed in as padding, so the total height is set
     * explicitly: band + {@code app_header_content_height}.
     */
    /** Puts the band on a screen's header, scroll container or root, in that order. */
    private void applyBandToFragment(androidx.fragment.app.Fragment fragment) {
        if (fragment == null || fragment.getView() == null) return;
        View root = fragment.getView();
        View header = root.findViewById(R.id.header_bar);
        if (header == null) header = root.findViewById(R.id.fixed_header);
        if (header != null) {
            // A header paints the band with its own colour; its content sits below the inset.
            com.fadcam.ui.utils.HeaderInset.applyBand(header, topBandPx);
            return;
        }
        View scroll = root.findViewById(R.id.screen_scroll);
        if (scroll != null) {
            // No header: put the band on the scroll container so the content can travel
            // through it while scrolling instead of being clipped by a solid root padding.
            com.fadcam.ui.utils.HeaderInset.applyScrollBand(scroll, topBandPx);
            return;
        }
        com.fadcam.ui.utils.HeaderInset.applyBand(root, topBandPx);
    }

    /**
     * Hides the bars on dialog windows and gives every new screen its top band.
     *
     * <p>Dialogs are handled by {@link com.fadcam.ui.utils.ImmersiveDialogs}, which is
     * recursive so sheets shown through a fragment's child manager are covered too.</p>
     */
    private void registerImmersiveDialogs() {
        if (immersiveDialogsRegistered) return;
        immersiveDialogsRegistered = true;
        com.fadcam.ui.utils.ImmersiveDialogs.install(this);
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentViewCreated(
                            @androidx.annotation.NonNull androidx.fragment.app.FragmentManager fm,
                            @androidx.annotation.NonNull androidx.fragment.app.Fragment f,
                            @androidx.annotation.NonNull View v,
                            @androidx.annotation.Nullable android.os.Bundle savedInstanceState) {
                        if (f instanceof androidx.fragment.app.DialogFragment) return;
                        // Push the band on the next frame, once the fragment has been laid out.
                        v.post(MainActivity.this::applyBandToCurrentFragment);
                    }
                }, true);
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        registerImmersiveDialogs();
        // A language change recreates the activity and restores hidden tabs; make sure the
        // restored visible screen gets the status-bar band even if the inset dispatch ran
        // before FragmentManager finished restoring it.
        applyBandToCurrentFragment();
        // Immersive shell, applied once the window is attached and focused. Running this from
        // onWindowFocusChanged re-triggered focus and looped before the first frame, which left
        // the window showing the theme background instead of the UI.
        com.fadcam.ui.utils.ImmersiveMode.apply(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
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
            FLog.w("Cloak", "onResume preference apply fail", t);
        }
        // state)-----------

        // Restore status/nav bar colors for the current tab
        // With hide/show navigation, fragments stay alive and don't re-trigger color setup
        // So we must restore the correct colors when app returns from background
        restoreBarColorsForCurrentTab();

        // Tab Lock: the session is reset by FadCamApplication.onStop. Returning to a
        // protected tab must not reveal it for even one frame.
        enforceCurrentTabLockIfNeeded();

        // Update badge visibility for new features
        updateFeatureBadgeVisibility();

        // Restore language settings. A saved language is a manual choice and stays
        // pinned; otherwise the app must keep following the device language.
        this.sharedPreferencesManager = SharedPreferencesManager.getInstance(this);
        String savedLanguageCode = sharedPreferencesManager.sharedPreferences.getString(Constants.LANGUAGE_KEY, null);
        if (savedLanguageCode != null && !savedLanguageCode.isEmpty()) {
            applyLanguage(savedLanguageCode);
        } else {
            followSystemLanguage();
        }

        // Create shortcuts if needed (Android 7.1+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            createDynamicShortcuts();
        }

        // Handle any pending intents
        Intent intent = getIntent();
        if (intent != null && Constants.ACTION_SHOW_RECORDS.equals(intent.getAction())) {
            switchFragment(1, false); // Navigate to Records tab
        }

    }

    @Override
     protected void onPause() {
        // Cancel any pending volume-shutter click (e.g. app backgrounded mid double-click)
        cancelPendingVolumeClick();
        volumeShutterActiveKeyCode = 0;
        volumeShutterLongPressTriggered = false;
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
            FLog.w("Cloak", "onPause cloak fail", e);
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
            FLog.w("Cloak", "onUserLeaveHint cloak fail", e);
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
        // Use popLevel: pops one sub-fragment if stacked, dismisses overlay if only one
        OverlayNavUtil.popLevel(this);
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clean up handler callbacks to prevent memory leaks
        backPressHandler.removeCallbacks(backPressRunnable);
        splashAborted = true;
        splashReady = true;
        if (splashAvatarOverlay != null) {
            splashAvatarOverlay.cancel();
            splashAvatarOverlay = null;
        } else if (splashHomeFragment != null
                && splashHomeFragment.isSplashTransitionActive()) {
            splashHomeFragment.cancelSplashTransition();
        }
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

                        setNavigationBarColor(getResources().getColor(R.color.amoled_background, getTheme()));
        } else if ("Crimson Bloom".equals(themeName)) {
            // Red theme
            setTheme(R.style.Theme_FadCam_Red);
                        setNavigationBarColor(getResources().getColor(R.color.red_theme_background_dark, getTheme()));
        } else if ("Premium Gold".equals(themeName)) {
            // Gold theme
            setTheme(R.style.Theme_FadCam_Gold);
                        setNavigationBarColor(getResources().getColor(R.color.gold_theme_background_dark, getTheme()));
        } else if ("Silent Forest".equals(themeName)) {
            // Silent Forest theme
            setTheme(R.style.Theme_FadCam_SilentForest);
                        setNavigationBarColor(
                    getResources().getColor(R.color.silentforest_theme_background_dark, getTheme()));
        } else if ("Shadow Alloy".equals(themeName)) {
            // Shadow Alloy theme
            setTheme(R.style.Theme_FadCam_ShadowAlloy);
                        setNavigationBarColor(
                    getResources().getColor(R.color.shadowalloy_theme_background_dark, getTheme()));
        } else if ("Pookie Pink".equals(themeName)) {
            // Pookie Pink theme
            setTheme(R.style.Theme_FadCam_PookiePink);
            setNavigationBarColor(
                    getResources().getColor(R.color.pookiepink_theme_background_dark, getTheme()));
        } else if ("Snow Veil".equals(themeName)) {
            // Snow Veil theme
            setTheme(R.style.Theme_FadCam_SnowVeil);
            setNavigationBarColor(
                    getResources().getColor(R.color.snowveil_theme_background_light, getTheme()));

            // Set status bar icons to dark for light theme
            setStatusBarIconsLight(true);
            setNavigationBarIconsLight(true);
        } else if ("Midnight Dusk".equals(themeName)) {
            // Always use the custom always-dark theme for Midnight Dusk
            setTheme(R.style.Theme_FadCam_MidnightDusk);
            setNavigationBarColor(getResources().getColor(R.color.gray, getTheme()));
        } else {
            // If we get an unknown theme name, use the system default
            // This should be the Crimson Bloom theme as defined in
            // Constants.DEFAULT_APP_THEME
            if ("Crimson Bloom".equals(Constants.DEFAULT_APP_THEME)) {
                setTheme(R.style.Theme_FadCam_Red);
                                setNavigationBarColor(getResources().getColor(R.color.red_theme_background_dark, getTheme()));
            } else {
                // Fallback to base theme
                setTheme(R.style.Base_Theme_FadCam);
                                setNavigationBarColor(getResources().getColor(R.color.gray, getTheme()));
            }
        }
    }

    /**
     * Apply the selected theme from preferences before any views are created
     * This ensures the theme is consistently applied across the entire app
     */
    private void applyTheme() {
        // Guard: Skip theme application for watch devices
        // Watch uses dedicated WatchMainActivity with Wear OS-optimized themes
        if (isWatchDevice()) {
            FLog.w("MainActivity", "applyTheme() called on watch device - skipping phone theme application");
            return;
        }

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

        FLog.d("MainActivity", "Configuration changed - orientation: " +
                (newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ? "landscape"
                        : "portrait"));

        // Notify all fragments about the orientation change
        notifyFragmentsOfOrientationChange(newConfig.orientation);
        // The docks live in the activity layout and are separate views, so the right one
        // has to be selected explicitly on every rotation. Without this the vertical
        // dock stayed on screen after returning to portrait.
        if (com.fadcam.BuildConfig.LITE_EDITION) {
            applyLiteDockMode();
        }
        // The dock lives in the activity layout: portrait keeps the bottom pill,
        // landscape turns it into the left rail.
        applyResponsiveNavLayout();
        syncNavSelection();
        setupHomeIconCustomization();
        restoreBarColorsForCurrentTab();
        // Some locale changes arrive as a live configuration change rather than a
        // recreation; the detach/attach above re-inflated the tab views, so reapply the
        // status-bar band to every restored tab.
        applyBandToCurrentFragment();
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
                for (String tag : getFragmentTagsForPosition(i)) {
                    Fragment f = fm.findFragmentByTag(tag);
                    if (f != null) {
                        detachTx.detach(f);
                        FLog.d("MainActivity", "Orientation: detaching fragment at position " + i + ": " + f.getClass().getSimpleName());
                    }
                }
            }
            detachTx.commitNow(); // Must commit before re-attaching
            
            // TRANSACTION 2: Re-attach all fragments (they will re-inflate with new orientation's layout)
            androidx.fragment.app.FragmentTransaction attachTx = fm.beginTransaction();
            for (int i = 0; i < 6; i++) {
                String visibleTag = i == 0 ? getHomeFragmentTagForCurrentMode() : FRAGMENT_TAG_PREFIX + i;
                for (String tag : getFragmentTagsForPosition(i)) {
                    Fragment f = fm.findFragmentByTag(tag);
                    if (f != null) {
                        attachTx.attach(f);
                        if (i != currentFragmentPosition || !tag.equals(visibleTag)) {
                            attachTx.hide(f);
                        } else {
                            attachTx.show(f);
                        }
                        FLog.d("MainActivity", "Orientation: re-attached fragment at position " + i + ": " + f.getClass().getSimpleName());
                    }
                }
            }
            attachTx.commitNow();
            
            FLog.d("MainActivity", "Orientation changed to " +
                    (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ? "landscape" : "portrait") +
                    " — all fragments re-inflated");
        } catch (Exception e) {
            FLog.e("MainActivity", "Error handling orientation change", e);
        }
    }

    /**
     * Restore status bar, navigation bar, and bottom nav colors based on the current tab.
     * Called from onResume to ensure colors are correct when returning from background.
     */
    /**
     * Re-applies the chrome that belongs to the current tab.
     *
     * <p>Fragments that recolour the bars while they are visible (Remote is the only
     * one) must call this instead of restoring "the theme colour": a fragment's
     * onPause can run *after* the next tab has already set its own chrome, and a blind
     * restore then repainted the new tab — which is how the Faditor dock turned red
     * after visiting Remote.</p>
     */
    public void refreshChromeForCurrentTab() {
        restoreBarColorsForCurrentTab();
    }

    private void restoreBarColorsForCurrentTab() {
        FLog.d("Chrome", "restoreBarColorsForCurrentTab: tab=" + currentFragmentPosition);
        if (currentFragmentPosition == 2) {
            // Remote tab uses black bars
            setBottomNavColor(0xFF000000);
            setStatusBarColor(0xFF000000);
            setNavigationBarColor(0xFF000000);
        } else if (currentFragmentPosition == 3) {
            // Faditor uses the same surface as its header so the dock and the top bar
            // read as one chrome layer; champagne stays an accent on type and controls.
            setBottomNavColor(getResources().getColor(R.color.amoled_card, getTheme()));
            // Follow the theme's chrome rather than hardcoding black: in Faded Night this
            // resolves to the same surface as the header below it, so the band and the
            // header read as one piece instead of showing a black seam.
            setStatusBarColor(0);
            setNavigationBarColor(0);
        } else {
            // All other tabs use theme default colors
            setBottomNavColor(0);
            setStatusBarColor(0);
            setNavigationBarColor(0);
        }
    }

    /** True when the activity is laid out in landscape. */
    private boolean isLandscapeLayout() {
        return getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    /** Lite line menu: three tabs, same ids as the full menu. */
    private void applyLiteMenu(NavigationBarView nav) {
        if (nav == null) return;
        android.view.Menu menu = nav.getMenu();
        menu.clear();
        menu.add(android.view.Menu.NONE, R.id.navigation_records, 0,
                R.string.nav_records).setIcon(R.drawable.ic_records_selector);
        menu.add(android.view.Menu.NONE, R.id.navigation_home, 1,
                R.string.nav_home).setIcon(R.drawable.pilot_steering_white);
        menu.add(android.view.Menu.NONE, R.id.navigation_settings, 2,
                R.string.nav_settings).setIcon(R.drawable.ic_nav_settings);
    }

    /**
     * Selects and styles the Lite dock for the current orientation. Called on create
     * and again on every configuration change, because the two docks live in the same
     * activity layout and must swap explicitly: leaving the previous one visible is
     * what made the vertical dock linger after returning to portrait.
     */
    private void applyLiteDockMode() {
        if (bottomNavigationView == null) {
            return;
        }
        final boolean landscape = isLandscapeLayout();

        android.view.View dockPortrait = findViewById(R.id.lite_dock);
        android.view.View dockLand = findViewById(R.id.lite_dock_land);

        // The real nav stays in the tree as the invisible engine that owns selection.
        android.view.ViewGroup.LayoutParams navLp =
                bottomNavigationView.getLayoutParams();
        if (navLp != null && navLp.height != 0) {
            navLp.height = 0;
            bottomNavigationView.setLayoutParams(navLp);
        }
        bottomNavigationView.setVisibility(android.view.View.INVISIBLE);

        if (dockLand != null) {
            dockLand.setVisibility(landscape
                    ? android.view.View.VISIBLE : android.view.View.GONE);
        }
        if (dockPortrait != null) {
            dockPortrait.setVisibility(landscape
                    ? android.view.View.GONE : android.view.View.VISIBLE);
        }

        if (landscape) {
            if (railNavigationView != null) {
                railNavigationView.setVisibility(android.view.View.GONE);
            }
            if (dockLand != null) {
                styleLiteLandscapeDock(dockLand);
                bindLiteDockItem(dockLand, R.id.lite_dock_land_item_records, R.id.navigation_records);
                bindLiteDockItem(dockLand, R.id.lite_dock_land_item_home, R.id.navigation_home);
                bindLiteDockItem(dockLand, R.id.lite_dock_land_item_settings, R.id.navigation_settings);
                android.view.View home = dockLand.findViewById(R.id.lite_dock_land_item_home);
                if (home != null) {
                    home.setOnLongClickListener(v -> {
                        showHomeIconPicker();
                        return true;
                    });
                }
                dockLand.post(this::updateLiteDockSelection);
            }
        } else if (dockPortrait != null) {
            styleLitePortraitDock(dockPortrait);
            bindLiteDockItem(dockPortrait, R.id.lite_dock_item_records, R.id.navigation_records);
            bindLiteDockItem(dockPortrait, R.id.lite_dock_item_home, R.id.navigation_home);
            bindLiteDockItem(dockPortrait, R.id.lite_dock_item_settings, R.id.navigation_settings);
            android.view.View home = dockPortrait.findViewById(R.id.lite_dock_item_home);
            if (home != null) {
                home.setOnLongClickListener(v -> {
                    showHomeIconPicker();
                    return true;
                });
            }
            dockPortrait.post(this::updateLiteDockSelection);
        }
    }

    /**
     * Portrait blob dock: side buttons in the darkened bar tone with round outer ends,
     * Home a raised circle in the bar colour.
     */
    private void styleLitePortraitDock(@NonNull android.view.View dock) {
        android.util.TypedValue tv = new android.util.TypedValue();
        int color = 0xFF1E1E1E;
        if (getTheme().resolveAttribute(com.fadcam.R.attr.colorBottomNav, tv, true)) {
            color = tv.data;
        }
        float d = getResources().getDisplayMetrics().density;
        float homeH = getResources().getDimension(com.fadcam.R.dimen.home_dock_height);
        int sideColor = mix(color, 0xFF000000, 0.30f);
        int light = mix(color, 0xFFFFFFFF, 0.08f);
        float sideRadius = homeH * 0.45f;

        android.graphics.drawable.GradientDrawable left = new android.graphics.drawable.GradientDrawable();
        left.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        left.setColor(sideColor);
        left.setCornerRadii(new float[]{sideRadius, sideRadius, 0f, 0f,
                0f, 0f, sideRadius, sideRadius});

        android.graphics.drawable.GradientDrawable right = new android.graphics.drawable.GradientDrawable();
        right.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        right.setColor(sideColor);
        right.setCornerRadii(new float[]{0f, 0f, sideRadius, sideRadius,
                sideRadius, sideRadius, 0f, 0f});

        android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setGradientType(android.graphics.drawable.GradientDrawable.LINEAR_GRADIENT);
        circle.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
        circle.setColors(new int[]{light, mix(color, 0xFF000000, 0.10f)});
        circle.setStroke((int) Math.max(1f, 1.2f * d), 0x22FFFFFF);

        android.view.View records = dock.findViewById(R.id.lite_dock_item_records);
        android.view.View settings = dock.findViewById(R.id.lite_dock_item_settings);
        android.view.View home = dock.findViewById(R.id.lite_dock_item_home);
        if (records != null) records.setBackground(left);
        if (settings != null) settings.setBackground(right);
        if (home != null) {
            home.setBackground(circle);
            home.setElevation(3f * d);
        }
        dock.setBackground(null);
    }

    /**
     * Landscape counterpart of the portrait blob dock: the same pill stood upright.
     * Home keeps the large circle in the middle; Records and Settings are the long
     * bars that tuck underneath it, so their round ends only face outward and the
     * trio reads as one shape, exactly like portrait.
     */
    private void styleLiteLandscapeDock(@NonNull android.view.View dockLand) {
        android.util.TypedValue tv = new android.util.TypedValue();
        int color = 0xFF1E1E1E;
        if (getTheme().resolveAttribute(com.fadcam.R.attr.colorBottomNav, tv, true)) {
            color = tv.data;
        }
        float d = getResources().getDisplayMetrics().density;
        float sideW = getResources().getDimension(com.fadcam.R.dimen.home_land_dock_side_width);
        int sideColor = mix(color, 0xFF000000, 0.30f);
        int light = mix(color, 0xFFFFFFFF, 0.08f);
        // Half the short side, so each end is a true semicircle.
        float sideRadius = sideW * 0.45f;

        android.graphics.drawable.GradientDrawable top = new android.graphics.drawable.GradientDrawable();
        top.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        top.setColor(sideColor);
        top.setCornerRadii(new float[]{sideRadius, sideRadius, sideRadius, sideRadius,
                0f, 0f, 0f, 0f});

        android.graphics.drawable.GradientDrawable bottom = new android.graphics.drawable.GradientDrawable();
        bottom.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bottom.setColor(sideColor);
        bottom.setCornerRadii(new float[]{0f, 0f, 0f, 0f,
                sideRadius, sideRadius, sideRadius, sideRadius});

        android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setGradientType(android.graphics.drawable.GradientDrawable.LINEAR_GRADIENT);
        circle.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
        circle.setColors(new int[]{light, mix(color, 0xFF000000, 0.10f)});
        circle.setStroke((int) Math.max(1f, 1.2f * d), 0x22FFFFFF);

        android.view.View records = dockLand.findViewById(R.id.lite_dock_land_item_records);
        android.view.View settings = dockLand.findViewById(R.id.lite_dock_land_item_settings);
        android.view.View home = dockLand.findViewById(R.id.lite_dock_land_item_home);
        if (records != null) records.setBackground(top);
        if (settings != null) settings.setBackground(bottom);
        if (home != null) {
            home.setBackground(circle);
            home.setElevation(3f * d);
        }
        dockLand.setBackground(null);
        dockLand.setVisibility(android.view.View.VISIBLE);
    }

    /**
     * Portrait: floating bottom pill, full-width content above it.
     * Landscape: the same items as a vertical rail on the leading edge, so the
     * short landscape height goes to the camera UI instead of the dock.
     */
    private void applyResponsiveNavLayout() {
        View rootView = findViewById(R.id.main_root_layout);
        View navContainer = findViewById(R.id.nav_container);
        View fragmentContainer = findViewById(R.id.fragment_container);
        View rail = findViewById(R.id.rail_navigation);
        View bottom = findViewById(R.id.bottom_navigation);
        if (!(rootView instanceof androidx.constraintlayout.widget.ConstraintLayout)
                || navContainer == null || fragmentContainer == null) {
            styleNavView(bottomNavigationView, false);
            return;
        }

        boolean landscape = isLandscapeLayout();
        androidx.constraintlayout.widget.ConstraintLayout root =
                (androidx.constraintlayout.widget.ConstraintLayout) rootView;
        androidx.constraintlayout.widget.ConstraintSet set =
                new androidx.constraintlayout.widget.ConstraintSet();
        set.clone(root);

        final int parent = androidx.constraintlayout.widget.ConstraintSet.PARENT_ID;
        final int start = androidx.constraintlayout.widget.ConstraintSet.START;
        final int end = androidx.constraintlayout.widget.ConstraintSet.END;
        final int top = androidx.constraintlayout.widget.ConstraintSet.TOP;
        final int bottomEdge = androidx.constraintlayout.widget.ConstraintSet.BOTTOM;

        if (landscape && rail instanceof NavigationBarView) {
            int margin = getResources().getDimensionPixelSize(R.dimen.home_rail_dock_margin);
            int railWidth = getResources().getDimensionPixelSize(R.dimen.home_rail_dock_width);
            boolean liteDockLand = com.fadcam.BuildConfig.LITE_EDITION
                    && findViewById(R.id.lite_dock_land) != null;
            if (bottom != null) bottom.setVisibility(View.GONE);
            if (liteDockLand) {
                // Lite supplies its own styled vertical dock; the Material rail would
                // only duplicate it (and did: both were visible at once).
                rail.setVisibility(View.GONE);
                railWidth = getResources().getDimensionPixelSize(R.dimen.home_land_dock_width);
            } else {
                rail.setVisibility(View.VISIBLE);
            }
            bottomNavigationView = (NavigationBarView) rail;

            set.constrainWidth(R.id.nav_container, railWidth + margin * 2);
            if (liteDockLand) {
                // Centre the short dock instead of stretching its container to the top
                // and bottom edges: that stretch is what left the dead band below.
                set.constrainHeight(R.id.nav_container,
                        androidx.constraintlayout.widget.ConstraintSet.WRAP_CONTENT);
                set.clear(R.id.nav_container, top);
                set.clear(R.id.nav_container, bottomEdge);
                set.connect(R.id.nav_container, top, parent, top, 0);
                set.connect(R.id.nav_container, bottomEdge, parent, bottomEdge, 0);
                set.setVerticalBias(R.id.nav_container, 0.5f);
            } else {
                // Wrap the rail's items: a full-height rail looked like a second screen edge.
                set.constrainHeight(R.id.nav_container,
                        androidx.constraintlayout.widget.ConstraintSet.WRAP_CONTENT);
                set.connect(R.id.nav_container, top, parent, top, 0);
                set.connect(R.id.nav_container, bottomEdge, parent, bottomEdge, 0);
            }
            set.connect(R.id.nav_container, start, parent, start, 0);
            set.clear(R.id.nav_container, end);
            // match_parent on a ConstraintLayout child ignores constraints; make the
            // container a 0dp span so it really starts after the rail.
            set.constrainWidth(R.id.fragment_container,
                    androidx.constraintlayout.widget.ConstraintSet.MATCH_CONSTRAINT);
            set.connect(R.id.fragment_container, end, parent, end, 0);
            set.connect(R.id.fragment_container, start, R.id.nav_container, end, 0);

            navBasePaddingStart = margin;
            navBasePaddingTop = margin;
            navBasePaddingEnd = margin;
            navBasePaddingBottom = margin;
        } else {
            if (rail != null) rail.setVisibility(View.GONE);
            if (bottom != null) bottom.setVisibility(View.VISIBLE);
            bottomNavigationView = bottom instanceof NavigationBarView
                    ? (NavigationBarView) bottom : bottomNavigationView;

            int dockPad = getResources().getDimensionPixelSize(R.dimen.home_dock_padding);
            set.constrainWidth(R.id.nav_container,
                    androidx.constraintlayout.widget.ConstraintSet.MATCH_CONSTRAINT);
            set.constrainHeight(R.id.nav_container,
                    androidx.constraintlayout.widget.ConstraintSet.WRAP_CONTENT);
            set.connect(R.id.nav_container, bottomEdge, parent, bottomEdge, 0);
            set.connect(R.id.nav_container, start, parent, start, 0);
            set.connect(R.id.nav_container, end, parent, end, 0);
            set.clear(R.id.nav_container, top);
            set.constrainWidth(R.id.fragment_container,
                    androidx.constraintlayout.widget.ConstraintSet.MATCH_CONSTRAINT);
            set.connect(R.id.fragment_container, start, parent, start, 0);
            set.connect(R.id.fragment_container, end, parent, end, 0);

            navBasePaddingStart = dockPad;
            navBasePaddingTop = 0;
            navBasePaddingEnd = dockPad;
            navBasePaddingBottom = dockPad;
        }
        set.applyTo(root);
        navContainer.setPadding(navBasePaddingStart, navBasePaddingTop,
                navBasePaddingEnd, navBasePaddingBottom);

        View focusScrim = findViewById(R.id.dock_focus_gradient_scrim);
        if (focusScrim != null) {
            focusScrim.setVisibility(landscape ? View.GONE : View.VISIBLE);
        }

        styleNavView(bottomNavigationView, landscape);
        applyHomeNavIcon();
        if (landscape && rail instanceof NavigationBarView) {
            sizeRailToMenu((NavigationBarView) rail);
        }
        androidx.core.view.ViewCompat.requestApplyInsets(rootView);
    }

    /**
     * A NavigationRailView measures itself to the available height even with
     * wrap_content. Give it an explicit height equal to its items so the pill
     * wraps the buttons instead of running the full screen edge.
     */
    private void sizeRailToMenu(NavigationBarView rail) {
        if (rail == null) return;
        int count = rail.getMenu().size();
        if (count <= 0) return;
        int itemHeight = getResources().getDimensionPixelSize(
                R.dimen.home_rail_dock_item_height);
        // The rail menu starts one content-inset below the pill's top. Reserving the
        // same inset at the bottom keeps the first and last buttons equally padded
        // (without it the last item overflows the pill by one inset).
        int inset = getResources().getDimensionPixelSize(
                R.dimen.home_rail_dock_content_inset);
        int height = itemHeight * count + inset * 2
                + rail.getPaddingTop() + rail.getPaddingBottom();
        ViewGroup.LayoutParams lp = rail.getLayoutParams();
        if (lp != null && lp.height != height) {
            lp.height = height;
            rail.setLayoutParams(lp);
        }
    }

    /** Shared look for the bottom pill and the rail; only the corner radius differs. */
    private void styleNavView(NavigationBarView nav, boolean rail) {
        if (nav == null) return;
        // Material must not add its own insets: the parent nav_container owns them.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(nav, (v, insets) -> insets);
        nav.setPadding(0, 0, 0, 0);
        nav.setLabelVisibilityMode(rail
                ? NavigationBarView.LABEL_VISIBILITY_UNLABELED
                : NavigationBarView.LABEL_VISIBILITY_SELECTED);
        nav.setItemActiveIndicatorEnabled(false);
        nav.setItemGravity(NavigationBarView.ITEM_GRAVITY_CENTER);
        try {
            nav.setItemActiveIndicatorColor(
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
        } catch (Exception ignored) { }

        // Rounded pill: half the rail width leaves the ends fully round.
        final float cornerPx = rail
                ? getResources().getDimension(R.dimen.home_rail_dock_width) / 2f
                : android.util.TypedValue.applyDimension(
                        android.util.TypedValue.COMPLEX_UNIT_DIP, 24,
                        getResources().getDisplayMetrics());
        nav.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(android.view.View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerPx);
            }
        });
        nav.setClipToOutline(true);
    }

    /** Mirrors the current tab into the newly visible nav without firing navigation. */
    private void syncNavSelection() {
        if (currentFragmentPosition < 0) return;
        int id = getNavItemIdForPosition(currentFragmentPosition);
        if (id == -1) return;
        syncNavSelection(bottomNavigationView, id);
        // Landscape uses the rail, not the bottom pill. Both are kept in sync so a
        // cancelled unlock can never leave the guarded tab visibly selected.
        syncNavSelection(railNavigationView, id);
    }

    private void syncNavSelection(NavigationBarView nav, int itemId) {
        if (nav == null || nav.getMenu().findItem(itemId) == null) return;
        NavigationBarView.OnItemSelectedListener listener = navItemSelectedListener;
        boolean wasSuppressed = suppressReselectScroll;
        suppressReselectScroll = true;
        try {
            nav.setOnItemSelectedListener(null);
            nav.setSelectedItemId(itemId);
            nav.setOnItemSelectedListener(listener);
        } finally {
            suppressReselectScroll = wasSuppressed;
        }
    }

    /**
     * Reselecting the active tab scrolls it to the top. The three scrollable
     * tabs use different ids because their layouts are independent.
     */
    private void scrollTabToTop(int position) {
        FLog.d("TabNav", "Reselect scroll to top: position=" + position);
        Fragment fragment = findVisibleFragmentForPosition(
                getSupportFragmentManager(), position);
        if (fragment == null || fragment.getView() == null) return;

        int scrollId;
        switch (position) {
            case 2:
                scrollId = R.id.screen_scroll;   // Remote
                break;
            case 3:
                scrollId = R.id.faditor_scroll;  // Faditor Mini
                break;
            case 4:
                scrollId = R.id.content_scroll;  // Settings
                break;
            default:
                return;
        }

        View scroll = fragment.getView().findViewById(scrollId);
        if (scroll instanceof androidx.core.widget.NestedScrollView) {
            ((androidx.core.widget.NestedScrollView) scroll).smoothScrollTo(0, 0);
        } else if (scroll instanceof android.widget.ScrollView) {
            ((android.widget.ScrollView) scroll).smoothScrollTo(0, 0);
        }
    }

    /**
     * Enable edge-to-edge display following Android 15 guidelines
     */
    @SuppressWarnings("deprecation") // legacy system-bar color/contrast calls are needed below API 35
    private void enableEdgeToEdge() {
        // Enable edge-to-edge display following Android 15 guidelines.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        // On API 35+, setStatusBarColor/setNavigationBarColor and the contrast
        // flags are deprecated (no-ops under edge-to-edge). Keep the legacy
        // behavior for older devices where they still apply.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                getWindow().setStatusBarContrastEnforced(false);
                getWindow().setNavigationBarContrastEnforced(false);
            }
        }

        // Handle window insets properly
        View rootView = findViewById(android.R.id.content);
        View navContainer = findViewById(R.id.nav_container);
        View statusBarScrim = findViewById(R.id.status_bar_scrim);
        View dockGradientScrim = findViewById(R.id.dock_focus_gradient_scrim);
        navBasePaddingStart = navContainer != null ? navContainer.getPaddingStart() : 0;
        navBasePaddingTop = navContainer != null ? navContainer.getPaddingTop() : 0;
        navBasePaddingEnd = navContainer != null ? navContainer.getPaddingEnd() : 0;
        navBasePaddingBottom = navContainer != null ? navContainer.getPaddingBottom() : 0;
        // Base gradient height in pixels (180dp), extended by systemBars.bottom so it always
        // peeks above the nav pill on API 35+ edge-to-edge devices.
        final int dockGradientBaseHeightPx = Math.round(180 * getResources().getDisplayMetrics().density);
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            androidx.core.graphics.Insets systemBars = insets
                    .getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            // A hidden navigation bar still reports its height on some three-button devices.
            // Only reserve its inset while it is actually visible, otherwise the dock and page
            // content leave an empty strip at the bottom.
            boolean navVisible = insets.isVisible(
                    androidx.core.view.WindowInsetsCompat.Type.navigationBars());
            int navLeft = navVisible ? systemBars.left : 0;
            int navRight = navVisible ? systemBars.right : 0;
            int navBottom = navVisible ? systemBars.bottom : 0;

            // A punch-hole / notch is reported as a display cutout, not as a system bar.
            // In landscape the camera sits on the left or right edge, so the cutout has
            // to be treated as a horizontal safe area too — not only as the top band.
            androidx.core.graphics.Insets cutout = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            int safeLeft = Math.max(navLeft, cutout.left);
            int safeRight = Math.max(navRight, cutout.right);
            int safeBottom = Math.max(navBottom, cutout.bottom);

            // Check if running on TV form factor (TV or Wear OS smartwatch)
            boolean isTV = getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                    || getPackageManager().hasSystemFeature("android.hardware.type.television");
            boolean isWear = getPackageManager().hasSystemFeature("android.hardware.type.watch");
            boolean isFormFactorTV = isTV || isWear;

            // The header keeps a painted band the height of the status bar area even while
            // the bar itself is hidden, so the app never renders flush against the physical
            // top edge or the display cutout. getInsetsIgnoringVisibility reports that height
            // regardless of whether the bar is currently shown.
            // A hidden status bar is reported inconsistently: 0 on some devices, the real
            // height on others. The platform dimension plus the display cutout is stable either
            // way, which is what makes the band identical on every phone.
            int contentTopInset = Math.max(getPlatformStatusBarHeight(),
                    insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout()).top);
            // Landscape is full screen on phones: the status bar is hidden, so a band
            // would just push the header down and eat the scarce height.
            boolean statusBarHidden = !insets.isVisible(
                    androidx.core.view.WindowInsetsCompat.Type.statusBars());
            boolean landscapeNow = isLandscapeLayout();
            // Only suppress the band in landscape when there is no cutout on the top edge
            // either; a notch there still needs its height reserved.
            topBandPx = (isFormFactorTV
                    || (landscapeNow && statusBarHidden && cutout.top == 0))
                    ? 0 : contentTopInset;
            applyBandToCurrentFragment();

            // Apply insets to main content areas
            View fragmentContainer = findViewById(R.id.fragment_container);
            View overlayContainer = findViewById(R.id.overlay_fragment_container);
            if (fragmentContainer != null) {
                // fragment_container spans the full screen (behind the dock).
                // The top band is owned by the screen itself (header or scroll container),
                // so the container only carries horizontal and bottom padding.
                // clipToPadding=false allows visual overflow behind/below the dock.
                // On TV/Wear: no status bar, no dock → all padding = 0.
                ((ViewGroup) fragmentContainer).setClipToPadding(false);
                ((ViewGroup) fragmentContainer).setClipChildren(false);
                // Fragment spans full screen (floating dock UX):
                // paddingBottom = only the system gesture bar so the last interactive
                // element is never hidden behind the gesture area.
                // Dock clearance is handled per-fragment via layout_marginBottom=72dp.
                int contentBottomPadding = isFormFactorTV ? 0 : safeBottom;
                // The top band belongs to the screen itself (header or scroll container), so
                // the container only carries the horizontal and bottom insets. In landscape
                // the rail already owns the start inset and the container starts after it.
                boolean railLayout = landscapeNow;
                fragmentContainer.setPadding(
                        railLayout ? 0 : safeLeft, 0,
                        safeRight, contentBottomPadding);
            }
            if (overlayContainer != null) {
                // Overlay screens (Settings sub-screens, Trash) apply the top band through
                // their own header, so the container only carries the horizontal and bottom
                // insets. Padding it here is what left a solid strip above the header.
                int overlayBottom = isFormFactorTV ? 0 : safeBottom;
                overlayContainer.setPadding(
                        safeLeft, 0,
                        safeRight, overlayBottom);
            }

            // Update status bar scrim height directly via LayoutParams.
            // The scrim floats over fragment_container via elevation="11dp".
            // On TV/Wear: hide scrim (GONE). On phones: set to actual status bar height.
            if (statusBarScrim != null) {
                if (isFormFactorTV) {
                    // GONE is definitive — a GONE view never renders regardless of constraints.
                    // Setting height=0 on a MATCH_CONSTRAINT view doesn't reliably suppress it.
                    statusBarScrim.setVisibility(View.GONE);
                    FLog.i("MainActivity", "TV/Wear: status_bar_scrim set to GONE");
                } else {
                    // Each screen paints the band itself: a header with its own colour, or a
                    // scroll container that lets content travel through it. A separate scrim
                    // would paint first and flash before the fragment attaches.
                    statusBarScrim.setVisibility(View.GONE);
                }
            }

            if (navContainer != null) {
                // In landscape the rail lives on the leading edge, so the cutout is carried
                // as a margin (padding would squeeze the 40dp rail). Portrait takes it as
                // normal padding.
                ViewGroup.LayoutParams navLp = navContainer.getLayoutParams();
                if (navLp instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) navLp;
                    int wantedLeft = landscapeNow ? safeLeft : 0;
                    int wantedRight = landscapeNow ? 0 : safeRight;
                    if (mlp.leftMargin != wantedLeft || mlp.rightMargin != wantedRight) {
                        mlp.leftMargin = wantedLeft;
                        mlp.rightMargin = wantedRight;
                        navContainer.setLayoutParams(mlp);
                    }
                }
                navContainer.setPadding(
                        navBasePaddingStart + (landscapeNow ? 0 : safeLeft),
                        navBasePaddingTop,
                        navBasePaddingEnd + (landscapeNow ? 0 : safeRight),
                        navBasePaddingBottom + safeBottom);
            }

            // Extend gradient scrim height by systemBars.bottom so it always peeks above
            // the nav pill even on API 35+ where the window extends behind the nav bar.
            if (dockGradientScrim != null
                    && dockGradientScrim.getVisibility() != View.GONE) {
                ViewGroup.LayoutParams lp = dockGradientScrim.getLayoutParams();
                int requiredHeight = dockGradientBaseHeightPx + safeBottom;
                if (lp != null && lp.height != requiredHeight) {
                    lp.height = requiredHeight;
                    dockGradientScrim.setLayoutParams(lp);
                }
            }

            return insets;
        });
    }

    // ========== Fragment-based Navigation System ==========
    
    private int currentFragmentPosition = -1; // -1 means no fragment loaded yet
    private static final String FRAGMENT_TAG_PREFIX = "tab_fragment_";
    private static final String HOME_FRAGMENT_TAG_FADCAM = FRAGMENT_TAG_PREFIX + "0_fadcam";
    private static final String HOME_FRAGMENT_TAG_FADREC = FRAGMENT_TAG_PREFIX + "0_fadrec";

    private String getHomeFragmentTagForCurrentMode() {
        String currentMode = sharedPreferencesManager.getCurrentRecordingMode();
        return Constants.MODE_FADREC.equals(currentMode)
                ? HOME_FRAGMENT_TAG_FADREC
                : HOME_FRAGMENT_TAG_FADCAM;
    }

    private String getFragmentTagForPosition(int position) {
        if (position == 0) {
            return getHomeFragmentTagForCurrentMode();
        }
        return FRAGMENT_TAG_PREFIX + position;
    }

    private java.util.List<String> getFragmentTagsForPosition(int position) {
        if (position == 0) {
            return Arrays.asList(HOME_FRAGMENT_TAG_FADCAM, HOME_FRAGMENT_TAG_FADREC);
        }
        return Collections.singletonList(FRAGMENT_TAG_PREFIX + position);
    }

    private Fragment findVisibleFragmentForPosition(androidx.fragment.app.FragmentManager fm, int position) {
        for (String tag : getFragmentTagsForPosition(position)) {
            Fragment fragment = fm.findFragmentByTag(tag);
            if (fragment != null && fragment.isAdded() && !fragment.isHidden()) {
                return fragment;
            }
        }
        return null;
    }

    /**
     * Applies the user's chosen home nav icon (default house / fighter jet / pilot).
     */
    private void applyHomeNavIcon() {
        if (bottomNavigationView == null) return;
        try {
            if (sharedPreferencesManager == null) {
                sharedPreferencesManager = SharedPreferencesManager.getInstance(this);
            }
            String choice = sharedPreferencesManager.getHomeIcon();
            int res;
            if (Constants.HOME_ICON_JET.equals(choice)) {
                res = R.drawable.fighter_jet_top_view;
            } else if (Constants.HOME_ICON_PILOT.equals(choice)) {
                res = R.drawable.pilot_steering_white;
            } else {
                res = R.drawable.ic_house;
            }
            android.view.MenuItem home = bottomNavigationView.getMenu().findItem(R.id.navigation_home);
            if (home != null) home.setIcon(res);
            android.widget.ImageView dockHomeIcon = findViewById(R.id.lite_dock_icon_home);
            if (dockHomeIcon != null) dockHomeIcon.setImageResource(res);
        } catch (Exception e) {
            FLog.w("MainActivity", "applyHomeNavIcon failed: " + e.getMessage());
        }
    }

    /**
     * Long-press the home nav item to pick which icon it shows.
     * Locates the Home item by its menu id — NOT by position — because the
     * Lite edition reorders the menu (Records first).
     */
    private void setupHomeIconCustomization() {
        if (bottomNavigationView == null) return;
        bottomNavigationView.post(() -> {
            try {
                // Both the bottom bar and the rail give each item view the menu id.
                View directHome = bottomNavigationView.findViewById(R.id.navigation_home);
                if (directHome != null) {
                    directHome.setOnLongClickListener(v -> {
                        showHomeIconPicker();
                        return true;
                    });
                    return;
                }
                if (bottomNavigationView.getChildCount() == 0) return;
                View menuView = bottomNavigationView.getChildAt(0);
                if (!(menuView instanceof ViewGroup)) return;
                ViewGroup group = (ViewGroup) menuView;
                if (group.getChildCount() == 0) return;
                int homeIndex = -1;
                for (int i = 0; i < bottomNavigationView.getMenu().size(); i++) {
                    if (bottomNavigationView.getMenu().getItem(i).getItemId() == R.id.navigation_home) {
                        homeIndex = i;
                        break;
                    }
                }
                if (homeIndex < 0 || homeIndex >= group.getChildCount()) return;
                View homeItem = group.getChildAt(homeIndex);
                if (homeItem != null) {
                    homeItem.setOnLongClickListener(v -> {
                        showHomeIconPicker();
                        return true;
                    });
                }
            } catch (Exception e) {
                FLog.w("MainActivity", "setupHomeIconCustomization failed: " + e.getMessage());
            }
        });
    }

    private void showHomeIconPicker() {
        final String resultKey = "picker_result_home_icon";
        getSupportFragmentManager().setFragmentResultListener(resultKey, this, (k, b) -> {
            String sel = b.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (sel != null) {
                try {
                    if (sharedPreferencesManager == null) {
                        sharedPreferencesManager = SharedPreferencesManager.getInstance(this);
                    }
                    sharedPreferencesManager.setHomeIcon(sel);
                    applyHomeNavIcon();
                } catch (Exception e) {
                    FLog.w("MainActivity", "home icon selection failed: " + e.getMessage());
                }
            }
        });
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        // Pilot is the default — list it first.
        items.add(new com.fadcam.ui.picker.OptionItem(Constants.HOME_ICON_PILOT,
                getString(R.string.home_icon_pilot),
                R.drawable.pilot_steering_white));
        items.add(new com.fadcam.ui.picker.OptionItem(Constants.HOME_ICON_JET,
                getString(R.string.home_icon_jet),
                R.drawable.fighter_jet_top_view));
        items.add(new com.fadcam.ui.picker.OptionItem(Constants.HOME_ICON_DEFAULT,
                getString(R.string.home_icon_home),
                R.drawable.ic_house));
        String current = Constants.HOME_ICON_DEFAULT;
        try {
            if (sharedPreferencesManager == null) {
                sharedPreferencesManager = SharedPreferencesManager.getInstance(this);
            }
            current = sharedPreferencesManager.getHomeIcon();
        } catch (Exception ignored) {
        }
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
                com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
                        getString(R.string.home_icon_picker_title), items, current, resultKey, null);
        sheet.show(getSupportFragmentManager(), "home_icon_picker");
    }

    private void scheduleTabPrewarm() {
        if (splashAvatarOverlay != null) {
            // Inflating another tab costs ~360ms on low-end phones. Doing it on a
            // fixed 3s timer landed exactly on the launch animation's landing and
            // stalled its final frames, so wait until the animation is over.
            splashAvatarOverlay.setOnLanded(this::startTabPrewarmTimer);
            // Fail-safe in case the overlay is cancelled (activity left mid-splash).
            backPressHandler.postDelayed(this::startTabPrewarmTimer, 9000L);
            return;
        }
        startTabPrewarmTimer();
    }

    private void startTabPrewarmTimer() {
        View container = findViewById(R.id.fragment_container);
        if (container == null) {
            return;
        }
        container.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            prewarmFragmentIfMissing(1);
        }, 1200L);
    }

    private void prewarmFragmentIfMissing(int position) {
        if (position == currentFragmentPosition) {
            return;
        }
        androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
        String targetTag = getFragmentTagForPosition(position);
        Fragment existing = fm.findFragmentByTag(targetTag);
        if (existing != null) {
            return;
        }
        try {
            Fragment prewarmedFragment = createFragmentForPosition(position);
            fm.beginTransaction()
                    .add(R.id.fragment_container, prewarmedFragment, targetTag)
                    .hide(prewarmedFragment)
                    .commitNowAllowingStateLoss();
        } catch (Exception e) {
            FLog.w("FragmentNav", "prewarmFragmentIfMissing: Failed for position " + position, e);
        }
    }
    
    /**
     * Switch to a fragment at the specified position using hide/show for instant switching.
     * Fragments are kept alive in memory — views are preserved, animations continue,
     * and subsequent tab switches are instant (no inflation or setup overhead).
     * Public method accessible to fragments for programmatic navigation.
     * @param position Tab position (0-5)
     * @param animate Whether to animate the transition
     */
    public void switchFragment(int position, boolean animate) {
        if (position < 0 || position > 5) {
            return;
        }

        // While the opaque lock screen is up, nothing may change tabs — this also
        // covers programmatic navigation from fragments and notification intents.
        if (tabLockOverlay != null && tabLockOverlay.getVisibility() == View.VISIBLE) {
            FLog.d("TabLock", "switchFragment blocked while app is locked");
            return;
        }

        if (position == currentFragmentPosition) {
            return; // Already showing this fragment
        }

        // The very first fragment (Home on a fresh launch) is created immediately
        // so the splash has something to land on; onResume enforces its lock.
        if (currentFragmentPosition >= 0 && TabLock.isLocked(this, position)) {
            FLog.i("TabLock", "Unlock required before showing tab " + position);
            requestTabUnlock(
                    position,
                    () -> switchFragmentUnlocked(position, animate),
                    () -> {
                        // Cancelled: the old tab must stay selected in every dock.
                        syncNavSelection();
                        updateLiteDockSelection();
                    });
            return;
        }

        switchFragmentUnlocked(position, animate);
    }

    private void switchFragmentUnlocked(int position, boolean animate) {
        if (position == currentFragmentPosition) {
            return; // Already showing this fragment
        }
        
        androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
        androidx.fragment.app.FragmentTransaction transaction = fm.beginTransaction();
        
        // Set custom fade animations
        if (animate) {
            transaction.setCustomAnimations(
                R.anim.fade_in,  // Duration is overridden per-fragment from the user preference
                R.anim.fade_out
            );
        }
        
        // Hide the currently visible fragment (if any)
        if (currentFragmentPosition >= 0) {
            Fragment currentFragment = findVisibleFragmentForPosition(fm, currentFragmentPosition);
            if (currentFragment != null) {
                transaction.hide(currentFragment);
            }
        }
        
        // Show existing fragment or add new one for the target position
        String targetTag = getFragmentTagForPosition(position);
        Fragment targetFragment = fm.findFragmentByTag(targetTag);
        
        if (targetFragment != null) {
            // Fragment already added — just show it (instant, no view inflation)
            transaction.show(targetFragment);
        } else {
            // First time visiting this tab — create and add
            targetFragment = createFragmentForPosition(position);
            transaction.add(R.id.fragment_container, targetFragment, targetTag);
        }
        
        // Use commitNow() for instant switching (no lag), commit() for animated transitions
        if (animate) {
            transaction.commit(); // Async for animation
        } else {
            transaction.commitNow(); // Immediate for instant switching and initial load
        }
        
        currentFragmentPosition = position;

        // Re-apply the status-bar band to the tab that just became visible. Fragments are
        // kept alive, so a tab shown after an activity recreation may not receive a new
        // onFragmentViewCreated callback.
        applyBandToCurrentFragment();

        // Handle tab-specific logic (same as old onPageSelected)
        handleTabSelected(position);
    }

    // ========== Tab Lock enforcement ==========

    private void initTabLockUi() {
        tabLockOverlay = findViewById(R.id.tab_lock_overlay);
        View lockScreen = findViewById(R.id.tab_lock_screen_root);
        if (lockScreen != null) {
            lockScreen.setOnClickListener(v -> requestCurrentTabUnlock());
        }
        View unlockButton = findViewById(R.id.tab_lock_unlock_button);
        if (unlockButton != null) {
            unlockButton.setOnClickListener(v -> requestCurrentTabUnlock());
        }
        View closeButton = findViewById(R.id.tab_lock_close_button);
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> handleTabLockScreenBack());
        }
    }

    /**
     * Called from onResume. If the tab that is currently restored/visible is
     * protected, an opaque screen is placed over the whole app and the credential
     * prompt is shown. Cancelling leaves the lock screen up, so the content stays
     * hidden and the user can tap Unlock to try again.
     */
    private void enforceCurrentTabLockIfNeeded() {
        if (isFinishing() || isDestroyed() || currentFragmentPosition < 0) {
            return;
        }
        if (!TabLock.isLocked(this, currentFragmentPosition)) {
            hideTabLockScreen();
            return;
        }
        showTabLockScreen();
        requestTabUnlock(
                currentFragmentPosition,
                () -> hideTabLockScreen(),
                null);
    }

    private void requestCurrentTabUnlock() {
        if (currentFragmentPosition < 0) return;
        if (!TabLock.isLocked(this, currentFragmentPosition)) {
            hideTabLockScreen();
            return;
        }
        showTabLockScreen();
        requestTabUnlock(
                currentFragmentPosition,
                () -> hideTabLockScreen(),
                null);
    }

    /** True while the opaque app-wide Tab Lock screen is covering the content. */
    public boolean isTabLockScreenVisible() {
        return tabLockOverlay != null && tabLockOverlay.getVisibility() == View.VISIBLE;
    }

    /**
     * Back / X on the lock screen. Home is the root screen, so leaving it closes
     * the app. From Records or Lab, the app returns Home without unlocking the
     * tab that was left; if Home is protected too, its own gate takes over.
     */
    private void handleTabLockScreenBack() {
        if (currentFragmentPosition < 0
                || currentFragmentPosition == TabLock.TAB_HOME) {
            FLog.d("TabLock", "Lock screen close on Home: finishing app");
            finishAndRemoveTask();
            return;
        }

        FLog.d("TabLock", "Lock screen close: leaving tab " + currentFragmentPosition);
        // Switch behind the lock screen first so the protected tab never flashes.
        switchFragmentUnlocked(TabLock.TAB_HOME, true);
        if (TabLock.isLocked(this, TabLock.TAB_HOME)) {
            showTabLockScreen();
            requestTabUnlock(
                    TabLock.TAB_HOME,
                    () -> hideTabLockScreen(),
                    null);
        } else {
            hideTabLockScreen();
        }
    }

    /**
     * Shows the standard App Lock prompt. Exactly one prompt can exist at a time;
     * a successful unlock marks only the requested tab, so unlocking Lab never
     * unlocks Records or Home.
     */
    private void requestTabUnlock(int position, Runnable onUnlocked, Runnable onCanceled) {
        if (tabLockPromptShowing) {
            return;
        }
        if (!com.guardanis.applock.AppLock.isEnrolled(this)) {
            // The master switch cannot be turned on without enrolling, but a
            // restored preference bundle could be inconsistent. Never strand the
            // user on an unlockable screen.
            if (onUnlocked != null) onUnlocked.run();
            return;
        }

        tabLockPromptShowing = true;
        FLog.i("TabLock", "Showing unlock prompt; tab=" + position);
        try {
            android.app.Dialog dialog = TabLock.unlockDialog(
                    this,
                    () -> {
                        tabLockPromptShowing = false;
                        TabLock.markUnlocked(this, position);
                        FLog.i("TabLock", "Unlocked tab " + position + " only");
                        if (onUnlocked != null) onUnlocked.run();
                    },
                    () -> {
                        tabLockPromptShowing = false;
                        FLog.i("TabLock", "Unlock cancelled; tab=" + position);
                        if (onCanceled != null) onCanceled.run();
                    })
                    .show();
            if (dialog == null) {
                tabLockPromptShowing = false;
                FLog.w("TabLock", "Unlock prompt returned no dialog");
            }
        } catch (Throwable t) {
            tabLockPromptShowing = false;
            FLog.e("TabLock", "Unable to show unlock prompt", t);
        }
    }

    private void showTabLockScreen() {
        if (tabLockOverlay == null) return;
        if (tabLockOverlay.getVisibility() == View.VISIBLE) return;
        tabLockOverlay.setVisibility(View.VISIBLE);
        tabLockOverlay.setAlpha(1f);
        tabLockOverlay.bringToFront();
        // The shell is already immersive. Re-apply it so the lock screen is always
        // edge to edge, with both bars hidden and revealed only by a swipe, instead
        // of reserving permanent black system-bar strips.
        setStatusBarIconsLight(false);
        setNavigationBarIconsLight(false);
        com.fadcam.ui.utils.ImmersiveMode.apply(this);
        // Nothing behind the black screen may be exposed to TalkBack.
        setLockedContentAccessibility(true);
    }

    private void hideTabLockScreen() {
        if (tabLockOverlay == null) return;
        if (tabLockOverlay.getVisibility() == View.GONE) return;
        tabLockOverlay.setVisibility(View.GONE);
        setLockedContentAccessibility(false);
        // Restore the current tab's normal status/navigation bar treatment and the
        // immersive shell (swipe reveals the transient blue/black bars on demand).
        restoreBarColorsForCurrentTab();
        com.fadcam.ui.utils.ImmersiveMode.apply(this);
    }

    private void setLockedContentAccessibility(boolean locked) {
        int mode = locked
                ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO;
        setAccessibilityMode(R.id.fragment_container, mode);
        setAccessibilityMode(R.id.nav_container, mode);
        setAccessibilityMode(R.id.overlay_fragment_container, mode);
        setAccessibilityMode(R.id.status_bar_scrim, mode);
    }

    private void setAccessibilityMode(int viewId, int mode) {
        View view = findViewById(viewId);
        if (view != null) {
            view.setImportantForAccessibility(mode);
        }
    }
    
    /**
     * Create a new fragment instance for the given tab position.
     * Only called the first time a tab is visited — subsequent visits reuse the existing fragment.
     */
    private Fragment createFragmentForPosition(int position) {
        // Create new fragment if not found
        Fragment newFragment;
        switch (position) {
            case 0:
                // Home tab - check current mode (FadRec is Full-only; Lite always uses FadCam)
                String currentMode = sharedPreferencesManager.getCurrentRecordingMode();
                Fragment fadRecHome = com.fadcam.FeatureRegistry.features().createFadRecHomeFragment();
                if (com.fadcam.Constants.MODE_FADREC.equals(currentMode) && fadRecHome != null) {
                    newFragment = fadRecHome;
                } else {
                    newFragment = new com.fadcam.ui.HomeFragment();
                }
                break;
            case 1:
                newFragment = new RecordsFragment();
                break;
            case 2:
                // Remote tab is Full-only (src/full); fall back to Home in Lite
                Fragment remoteFragment = com.fadcam.FeatureRegistry.features().createRemoteFragment();
                newFragment = remoteFragment != null ? remoteFragment : new com.fadcam.ui.HomeFragment();
                break;
            case 3:
                // Faditor tab is a Full-only feature (src/full); fall back to Home in Lite
                Fragment faditor = FeatureRegistry.features().createFaditorFragment();
                newFragment = faditor != null ? faditor : new com.fadcam.ui.HomeFragment();
                break;
            case 4:
                newFragment = new com.fadcam.ui.SettingsHomeFragment();
                break;
            case 5:
                // Forensics Lab is Full-only (src/full); fall back to Home in Lite
                Fragment labFragment = FeatureRegistry.features().createLabFragment();
                newFragment = labFragment != null ? labFragment : new com.fadcam.ui.HomeFragment();
                break;
            default:
                newFragment = new com.fadcam.ui.HomeFragment();
        }
        
        return newFragment;
    }
    
    /**
     * Handle tab-specific logic when a tab is selected.
     * Replaces old ViewPager2.OnPageChangeCallback logic.
     */
    private void handleTabSelected(int position) {
        // Update bottom nav selection
        int navItemId = getNavItemIdForPosition(position);
        if (navItemId != -1) {
            boolean wasSuppressed = suppressReselectScroll;
            suppressReselectScroll = true;
            try {
                bottomNavigationView.setSelectedItemId(navItemId);
            } finally {
                suppressReselectScroll = wasSuppressed;
            }
        }
        updateLiteDockSelection();
        
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
                    FLog.e("MainActivity", "Error triggering Records lazy load", e);
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
    public void forceRecreateFragment(int position, View continuityView) {
        if (position == 0) {
            switchHomeModeFragment();
            return;
        }
        
        androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
        String tag = FRAGMENT_TAG_PREFIX + position;
        Fragment newFragment = createFragmentForPosition(position);
        View continuityOverlay = createContinuityOverlay(continuityView);
        androidx.fragment.app.FragmentTransaction tx = fm.beginTransaction();
        tx.setReorderingAllowed(true);
        tx.replace(R.id.fragment_container, newFragment, tag);
        
        // Apply visibility state: only show if it's the current position
        if (position == currentFragmentPosition) {
            tx.show(newFragment);
        } else {
            tx.hide(newFragment);
        }

        tx.commitNow();
        clearContinuityOverlayAfterNextDraw(continuityOverlay, newFragment);
    }

    private void switchHomeModeFragment() {
        androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
        String targetTag = getHomeFragmentTagForCurrentMode();
        Fragment currentFragment = findVisibleFragmentForPosition(fm, 0);
        Fragment targetFragment = fm.findFragmentByTag(targetTag);

        if (targetFragment == currentFragment && targetFragment != null) {
            return;
        }

        androidx.fragment.app.FragmentTransaction tx = fm.beginTransaction();
        tx.setReorderingAllowed(true);

        if (currentFragment != null) {
            tx.hide(currentFragment);
        }

        if (targetFragment != null) {
            tx.show(targetFragment);
        } else {
            targetFragment = createFragmentForPosition(0);
            tx.add(R.id.fragment_container, targetFragment, targetTag);
        }

        tx.commitNow();
    }

    private View createContinuityOverlay(View sourceView) {
        if (sourceView == null || sourceView.getWidth() <= 0 || sourceView.getHeight() <= 0) {
            return null;
        }

        View root = findViewById(R.id.main_root_layout);
        if (!(root instanceof ViewGroup)) {
            return null;
        }

        Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(sourceView.getWidth(), sourceView.getHeight(), Bitmap.Config.ARGB_8888);
        } catch (IllegalArgumentException e) {
            FLog.w("FragmentNav", "createContinuityOverlay: Unable to allocate bitmap", e);
            return null;
        }

        Canvas canvas = new Canvas(bitmap);
        sourceView.draw(canvas);

        ImageView overlayView = new ImageView(this);
        overlayView.setImageBitmap(bitmap);
        overlayView.setClickable(false);
        overlayView.setFocusable(false);
        overlayView.setElevation(1000f);

        int[] sourceLocation = new int[2];
        int[] rootLocation = new int[2];
        sourceView.getLocationOnScreen(sourceLocation);
        root.getLocationOnScreen(rootLocation);

        overlayView.layout(0, 0, sourceView.getWidth(), sourceView.getHeight());
        overlayView.setX(sourceLocation[0] - rootLocation[0]);
        overlayView.setY(sourceLocation[1] - rootLocation[1]);
        ((ViewGroup) root).getOverlay().add(overlayView);
        return overlayView;
    }

    private void clearContinuityOverlayAfterNextDraw(View overlayView, Fragment newFragment) {
        if (overlayView == null) {
            return;
        }

        View root = findViewById(R.id.main_root_layout);
        if (!(root instanceof ViewGroup)) {
            return;
        }

        View fragmentView = newFragment.getView();
        View anchorView = fragmentView != null ? fragmentView : findViewById(R.id.fragment_container);
        if (anchorView == null) {
            ((ViewGroup) root).getOverlay().remove(overlayView);
            return;
        }

        View finalAnchorView = anchorView;
        finalAnchorView.post(() -> finalAnchorView.postDelayed(() -> overlayView.animate()
                .alpha(0f)
                .setDuration(120L)
                .withEndAction(() -> ((ViewGroup) root).getOverlay().remove(overlayView)), 120L));
    }

    /**
     * Check if the device is a Wear OS watch.
     * Prevents phone-specific theme code from running on watch devices.
     * @return true if device has watch hardware feature, false otherwise
     */
    private boolean isWatchDevice() {
        return getPackageManager().hasSystemFeature("android.hardware.type.watch");
    }
}
