package com.fadcam.ui.utils;

import android.content.Context;
import android.util.Log;

import com.fadcam.SharedPreferencesManager;

/**
 * Utility class to manage new feature badge visibility across the app.
 *
 * Provides a centralized way to track which new features have been seen by the user.
 * When a user opens a new feature for the first time, the badge is marked as seen and won't appear again.
 *
 * Usage:
 * - Check if badge should show: NewFeatureManager.shouldShowBadge(context, "remote")
 * - Mark feature as seen: NewFeatureManager.markFeatureAsSeen(context, "remote")
 *
 * Features are identified by simple string keys like: "remote", "custom_icon", "livestream", etc.
 */
public class NewFeatureManager {

    private static final String TAG = "NewFeatureManager";
    private static final String BADGE_SEEN_PREFIX = "badge_";
    private static final String BADGE_SEEN_SUFFIX = "_seen";

    /**
     * Check if a new feature badge should be displayed for the given feature.
     *
     * @param context Application context
     * @param featureKey Unique key for the feature (e.g., "remote", "custom_icon")
     * @return true if badge should be shown, false if feature has been marked as seen
     */
    public static boolean shouldShowBadge(Context context, String featureKey) {
        SharedPreferencesManager prefsManager = SharedPreferencesManager.getInstance(context);
        String key = BADGE_SEEN_PREFIX + featureKey + BADGE_SEEN_SUFFIX;
        boolean shouldShow = !prefsManager.getBoolean(key, false);
        Log.d(TAG, "shouldShowBadge(" + featureKey + "): " + shouldShow + " (key=" + key + ")");
        return shouldShow;
    }

    /**
     * Mark a feature as seen, which will hide the badge on next app load.
     * Typically called when user opens the tab/screen with the new feature for the first time.
     *
     * @param context Application context
     * @param featureKey Unique key for the feature (e.g., "remote", "custom_icon")
     */
    public static void markFeatureAsSeen(Context context, String featureKey) {
        SharedPreferencesManager prefsManager = SharedPreferencesManager.getInstance(context);
        String key = BADGE_SEEN_PREFIX + featureKey + BADGE_SEEN_SUFFIX;
        prefsManager.putBoolean(key, true);
        Log.d(TAG, "markFeatureAsSeen(" + featureKey + "): Set " + key + " = true");
    }

    /**
     * Reset a feature's badge (useful for testing or admin reset).
     *
     * @param context Application context
     * @param featureKey Unique key for the feature
     */
    public static void resetFeatureBadge(Context context, String featureKey) {
        SharedPreferencesManager prefsManager = SharedPreferencesManager.getInstance(context);
        String key = BADGE_SEEN_PREFIX + featureKey + BADGE_SEEN_SUFFIX;
        prefsManager.putBoolean(key, false);
    }

    /**
     * Reset all feature badges (useful for testing).
     *
     * @param context Application context
     */
    public static void resetAllBadges(Context context) {
        // Reset known feature keys
        markFeatureAsSeen(context, "remote");
        resetFeatureBadge(context, "remote");

        markFeatureAsSeen(context, "custom_icon");
        resetFeatureBadge(context, "custom_icon");

        // Add more features as they're added
    }
}
