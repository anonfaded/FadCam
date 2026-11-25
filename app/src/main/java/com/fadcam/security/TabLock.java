package com.fadcam.security;

import android.app.Activity;
import android.content.Context;

import com.fadcam.SharedPreferencesManager;
import com.guardanis.applock.AppLock;
import com.guardanis.applock.dialogs.UnlockDialogBuilder;

/**
 * Single source of truth for Tab Lock.
 *
 * <p>Tab Lock is the existing App Lock credential (PIN / password / fingerprint).
 * Which tabs it protects is configurable: Home, Records (its Trash screen is part
 * of the same protected area and is never exposed as a separate option) and Lab.
 * Records is protected by default, matching the old behaviour.</p>
 *
 * <p>All navigation enforcement goes through this class so Records, Home, Lab and
 * the Trash overlay can never disagree about the current policy.</p>
 */
public final class TabLock {

    /** Fragment positions used by MainActivity. */
    public static final int TAB_HOME = 0;
    public static final int TAB_RECORDS = 1;
    public static final int TAB_REMOTE = 2;
    public static final int TAB_FADITOR = 3;
    public static final int TAB_SETTINGS = 4;
    public static final int TAB_LAB = 5;

    private TabLock() {
    }

    /** True when the user asked Tab Lock to protect this tab. */
    public static boolean isProtected(Context context, int position) {
        if (context == null) return false;
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(context);
        switch (position) {
            case TAB_HOME:
                return prefs.isTabLockHomeEnabled();
            case TAB_RECORDS:
                return prefs.isTabLockRecordsEnabled();
            case TAB_REMOTE:
                return prefs.isTabLockRemoteEnabled();
            case TAB_FADITOR:
                return prefs.isTabLockFaditorEnabled();
            case TAB_SETTINGS:
                return prefs.isTabLockSettingsEnabled();
            case TAB_LAB:
                return prefs.isTabLockLabEnabled();
            default:
                return false;
        }
    }

    /**
     * True when this tab has already been unlocked for the current foreground
     * session. Every tab owns its own flag; unlocking one never unlocks another.
     */
    public static boolean isUnlocked(Context context, int position) {
        if (context == null) return false;
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(context);
        switch (position) {
            case TAB_HOME:
                return prefs.isTabLockUnlockedHome();
            case TAB_RECORDS:
                return prefs.isTabLockUnlockedRecords();
            case TAB_REMOTE:
                return prefs.isTabLockUnlockedRemote();
            case TAB_FADITOR:
                return prefs.isTabLockUnlockedFaditor();
            case TAB_SETTINGS:
                return prefs.isTabLockUnlockedSettings();
            case TAB_LAB:
                return prefs.isTabLockUnlockedLab();
            default:
                return false;
        }
    }

    /** Marks exactly one tab unlocked for this foreground session. */
    public static void markUnlocked(Context context, int position) {
        if (context == null) return;
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(context);
        switch (position) {
            case TAB_HOME:
                prefs.setTabLockUnlockedHome(true);
                break;
            case TAB_RECORDS:
                prefs.setTabLockUnlockedRecords(true);
                break;
            case TAB_REMOTE:
                prefs.setTabLockUnlockedRemote(true);
                break;
            case TAB_FADITOR:
                prefs.setTabLockUnlockedFaditor(true);
                break;
            case TAB_SETTINGS:
                prefs.setTabLockUnlockedSettings(true);
                break;
            case TAB_LAB:
                prefs.setTabLockUnlockedLab(true);
                break;
            default:
                break;
        }
    }

    /**
     * True when this tab must be unlocked right now. Requires the master Tab Lock
     * switch, an enrolled credential, a protected tab and that tab being locked.
     */
    public static boolean isLocked(Context context, int position) {
        if (!isProtected(context, position)) return false;
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(context);
        if (!prefs.isAppLockEnabled()) return false;
        if (isUnlocked(context, position)) return false;
        return AppLock.isEnrolled(context);
    }

    /**
     * Builds the standard unlock prompt. The caller owns prompt de-duplication.
     */
    public static UnlockDialogBuilder unlockDialog(Activity activity,
                                                   Runnable onUnlocked,
                                                   Runnable onCanceled) {
        UnlockDialogBuilder builder = new UnlockDialogBuilder(activity);
        if (onUnlocked != null) builder.onUnlocked(onUnlocked);
        if (onCanceled != null) builder.onCanceled(onCanceled);
        return builder;
    }
}
