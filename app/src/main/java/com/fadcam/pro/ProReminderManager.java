package com.fadcam.pro;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.fadcam.BuildConfig;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.utils.AppUsageAge;
import com.fadcam.utils.RuntimeCompat;
import com.fadcam.utils.VideoStatsCache;

import java.util.concurrent.TimeUnit;

/**
 * Owns the weekly Pro reminder's state machine.
 *
 * <p>Free builds only. A presentation is claimed atomically before the dialog is shown, and
 * completed only when the user presses Continue (dialog) or taps the weekly notification.
 * A claim that was never completed is dropped when a new process starts, so killing the app
 * before Continue makes the prompt eligible again on the next safe Home visit.
 *
 * <p>{@link #DESIGN_ALWAYS_SHOW} keeps the current preview build showing the dialog on every
 * Home visit. Set it to {@code false} (or remove the debug clause) to switch to the real
 * three-day/first-recording + seven-day cadence.
 */
public final class ProReminderManager {

    /** Preview switch: every Home visit. Must be false for production. */
    private static final boolean DESIGN_ALWAYS_SHOW = BuildConfig.DEBUG;

    private static final long DAY_MS = TimeUnit.DAYS.toMillis(1);
    private static final long WEEK_MS = TimeUnit.DAYS.toMillis(7);
    private static final long FIRST_DELAY_MS = TimeUnit.DAYS.toMillis(3);
    /** A presented-but-unfinished dialog may be reclaimed after this long. */
    private static final long CLAIM_TTL_MS = TimeUnit.MINUTES.toMillis(10);

    public static final int COPY_COUNT = 6;
    public static final int NOTIFICATION_COPY_COUNT = 4;
    public static final String EXTRA_FROM_NOTIFICATION =
            "com.fadcam.pro.extra.PRO_REMINDER_NOTIFICATION";
    /** Set on the Home intent created when Back leaves a notification-launched Pro screen. */
    public static final String EXTRA_SKIP_WHATS_NEW =
            "com.fadcam.pro.extra.SKIP_WHATS_NEW";

    private static final String K_COMPLETED_AT = "pro_reminder_completed_at";
    private static final String K_NEXT_ELIGIBLE_AT = "pro_reminder_next_eligible_at";
    private static final String K_COPY_INDEX = "pro_reminder_copy_index";
    private static final String K_NOTIFICATION_SENT = "pro_reminder_notification_sent";
    private static final String K_NOTIFICATION_POSTED_AT = "pro_reminder_notification_posted_at";
    private static final String K_LAST_NOTIFICATION_AT = "pro_reminder_last_notification_at";
    private static final String K_CLAIMED_AT = "pro_reminder_claimed_at";
    private static final String K_CLAIMED_COPY = "pro_reminder_claimed_copy";
    private static final String K_AFTER_PRO_PENDING = "pro_reminder_after_pro_pending";
    private static final String K_PRO_OPENED_AT = "pro_reminder_pro_opened_at";
    private static final String K_DESIGN_COPY = "pro_reminder_design_copy";
    private static final String K_DESIGN_MODE = "pro_reminder_design_mode";
    private static final String K_HAS_RECORDING = "pro_reminder_has_recording";
    private static final String K_BACK_TOAST_INDEX = "pro_reminder_back_toast_index";

    private static final Object LOCK = new Object();
    private static boolean processInitialized = false;

    private static final int[] DIALOG_TITLES = {
            R.string.pro_reminder_title_1,
            R.string.pro_reminder_title_2,
            R.string.pro_reminder_title_3,
            R.string.pro_reminder_title_4,
            R.string.pro_reminder_title_5,
            R.string.pro_reminder_title_6
    };
    private static final int[] DIALOG_SUBTITLES = {
            R.string.pro_reminder_subtitle_1,
            R.string.pro_reminder_subtitle_2,
            R.string.pro_reminder_subtitle_3,
            R.string.pro_reminder_subtitle_4,
            R.string.pro_reminder_subtitle_5,
            R.string.pro_reminder_subtitle_6
    };
    private static final int[] NOTIFICATION_TITLES = {
            R.string.pro_reminder_notification_title_1,
            R.string.pro_reminder_notification_title_2,
            R.string.pro_reminder_notification_title_3,
            R.string.pro_reminder_notification_title_4
    };
    private static final int[] NOTIFICATION_BODIES = {
            R.string.pro_reminder_notification_body_1,
            R.string.pro_reminder_notification_body_2,
            R.string.pro_reminder_notification_body_3,
            R.string.pro_reminder_notification_body_4
    };

    private ProReminderManager() {
    }

    /** Free builds only. Ownership builds compile the feature flag false and disable receivers. */
    public static boolean isSupported() {
        return BuildConfig.ENABLE_PRO_REMINDER && "FREE".equals(BuildConfig.TIER);
    }

    /**
     * Current preview behaviour: every Home visit instead of the weekly gate. Debug builds
     * default to on; QA can toggle {@code pro_reminder_design_mode} to compare with the real
     * cadence. Release builds ignore it because {@link #DESIGN_ALWAYS_SHOW} is false.
     */
    public static boolean isDesignMode(Context context) {
        return isSupported() && DESIGN_ALWAYS_SHOW
                && prefs(context).getBoolean(K_DESIGN_MODE, true);
    }

    /**
     * Called once per process from {@link com.fadcam.FadCamApplication}. A claim from a
     * previous process was never completed (otherwise it would have been cleared), so drop it.
     */
    public static void onProcessStart(Context context) {
        if (!isSupported()) return;
        synchronized (LOCK) {
            if (processInitialized) return;
            SharedPreferences p = prefs(context);
            long claimed = p.getLong(K_CLAIMED_AT, 0L);
            long completed = p.getLong(K_COMPLETED_AT, 0L);
            if (claimed > completed) {
                p.edit().putLong(K_CLAIMED_AT, 0L).putInt(K_CLAIMED_COPY, 0).apply();
            }
            processInitialized = true;
        }
    }

    /** Watch, TV and automotive surfaces do not get a phone-shaped upsell. */
    public static boolean isUnsupportedSurface(Context context) {
        if (RuntimeCompat.shouldUseWatchUi(context)) return true;
        try {
            PackageManager pm = context.getPackageManager();
            return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                    || pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
                    || pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean shouldShowDialog(Context context) {
        if (!isSupported() || isUnsupportedSurface(context)) return false;
        synchronized (LOCK) {
            SharedPreferences p = prefs(context);
            long now = System.currentTimeMillis();
            if (isClaimValid(p, now)) return false;
            if (p.getBoolean(K_AFTER_PRO_PENDING, false)) return false;
            if (isDesignMode(context)) return true;

            long completed = p.getLong(K_COMPLETED_AT, 0L);
            if (completed <= 0L) return firstEligible(context, p);

            long next = p.getLong(K_NEXT_ELIGIBLE_AT, 0L);
            if (next <= 0L) next = completed + WEEK_MS;
            if (now < completed) {
                // Wall clock moved backwards. Never let that make the reminder due early.
                p.edit().putLong(K_NEXT_ELIGIBLE_AT, now + WEEK_MS).apply();
                return false;
            }
            return now >= next;
        }
    }

    /**
     * Atomically reserve the next dialog. Returns the copy index to render, or -1 when the
     * dialog should not be shown.
     */
    public static int claimPresentation(Context context) {
        if (!isSupported()) return -1;
        synchronized (LOCK) {
            if (!shouldShowDialog(context)) return -1;
            SharedPreferences p = prefs(context);
            boolean design = isDesignMode(context);
            int copy = design
                    ? Math.floorMod(p.getInt(K_DESIGN_COPY, 0), COPY_COUNT)
                    : Math.floorMod(p.getInt(K_COPY_INDEX, 0), COPY_COUNT);
            long now = System.currentTimeMillis();
            boolean saved = p.edit()
                    .putLong(K_CLAIMED_AT, now)
                    .putInt(K_CLAIMED_COPY, copy)
                    .commit();
            if (!saved) return -1;
            if (design) {
                p.edit().putInt(K_DESIGN_COPY, (copy + 1) % COPY_COUNT).apply();
            }
            return copy;
        }
    }

    /** The host dismissed the prompt without completing it; allow the next safe Home visit. */
    public static void releaseClaim(Context context) {
        if (!isSupported()) return;
        synchronized (LOCK) {
            prefs(context).edit().putLong(K_CLAIMED_AT, 0L).putInt(K_CLAIMED_COPY, 0).apply();
        }
    }

    /** Continue was pressed and the Pro screen opened. Start the next seven-day cycle. */
    public static boolean completePresentation(Context context) {
        if (!isSupported()) return false;
        synchronized (LOCK) {
            SharedPreferences p = prefs(context);
            long now = System.currentTimeMillis();
            int currentCopy = Math.floorMod(p.getInt(K_COPY_INDEX, 0), COPY_COUNT);
            int claimedCopy = p.getInt(K_CLAIMED_COPY, -1);
            int nextCopy = isDesignMode(context)
                    ? currentCopy
                    : (claimedCopy >= 0
                            ? (claimedCopy + 1) % COPY_COUNT
                            : (currentCopy + 1) % COPY_COUNT);
            boolean saved = p.edit()
                    .putLong(K_COMPLETED_AT, now)
                    .putLong(K_NEXT_ELIGIBLE_AT, now + WEEK_MS)
                    .putInt(K_COPY_INDEX, nextCopy)
                    .putBoolean(K_NOTIFICATION_SENT, false)
                    .putLong(K_NOTIFICATION_POSTED_AT, 0L)
                    .putLong(K_CLAIMED_AT, 0L)
                    .putInt(K_CLAIMED_COPY, 0)
                    .putBoolean(K_AFTER_PRO_PENDING, true)
                    .putLong(K_PRO_OPENED_AT, now)
                    .commit();
            if (!saved) return false;
        }
        // Leave the tray notification alone: the user may tap it or swipe it away. The next
        // cycle's notification will reuse the same ID and replace any stale copy.
        ProReminderScheduler.ensureScheduled(context);
        return true;
    }

    /**
     * The weekly notification was tapped. It opens Pro, but it does <b>not</b> complete the
     * cycle: the in-app dossier is mandatory too and still appears on the next safe Home
     * visit. Only Continue in that dossier completes the impression and starts the next week.
     */
    public static boolean onNotificationOpened(Context context) {
        if (!isSupported()) return false;
        synchronized (LOCK) {
            SharedPreferences p = prefs(context);
            boolean sent = p.getBoolean(K_NOTIFICATION_SENT, false);
            long postedAt = p.getLong(K_NOTIFICATION_POSTED_AT, 0L);
            if (!sent && postedAt <= 0L) return false;
            // Keep notificationSent=true for this cycle so the alarm cannot repost it; the
            // dialog does not depend on this flag and is still due.
            ProReminderNotification.cancel(context);
            return true;
        }
    }

    public static boolean isAfterProPending(Context context) {
        return prefs(context).getBoolean(K_AFTER_PRO_PENDING, false);
    }

    /** Clear the after-Pro confirmation once its dialog is dismissed. */
    public static void clearAfterProPending(Context context) {
        synchronized (LOCK) {
            prefs(context).edit().putBoolean(K_AFTER_PRO_PENDING, false).apply();
        }
    }

    public static boolean isNotificationSent(Context context) {
        return prefs(context).getBoolean(K_NOTIFICATION_SENT, false);
    }

    public static boolean shouldPostNotification(Context context) {
        if (!isSupported() || isDesignMode(context)
                || isUnsupportedSurface(context)) return false;
        if (!notificationsEnabled(context)) return false;
        synchronized (LOCK) {
            SharedPreferences p = prefs(context);
            if (p.getBoolean(K_NOTIFICATION_SENT, false)) return false;
            long completed = p.getLong(K_COMPLETED_AT, 0L);
            if (completed <= 0L) return false;
            long next = p.getLong(K_NEXT_ELIGIBLE_AT, 0L);
            if (next <= 0L) return false;
            return System.currentTimeMillis() >= next;
        }
    }

    public static void markNotificationPosted(Context context) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(context);
            long now = System.currentTimeMillis();
            p.edit()
                    .putBoolean(K_NOTIFICATION_SENT, true)
                    .putLong(K_NOTIFICATION_POSTED_AT, now)
                    .putLong(K_LAST_NOTIFICATION_AT, now)
                    .apply();
        }
    }

    public static long getNextEligibleAt(Context context) {
        return prefs(context).getLong(K_NEXT_ELIGIBLE_AT, 0L);
    }

    /** Whole days remaining in the current cycle, for the after-Pro copy. */
    public static int getDaysUntilNext(Context context) {
        long next = getNextEligibleAt(context);
        long now = System.currentTimeMillis();
        if (next <= 0L || next <= now) return 7;
        long days = (next - now + DAY_MS - 1L) / DAY_MS;
        return (int) Math.max(1L, days);
    }

    public static String getAgeText(Context context) {
        String age = AppUsageAge.twoUnits(context);
        return age == null || age.trim().isEmpty() ? "a while" : age;
    }

    public static DialogCopy getDialogCopy(Context context, int copyIndex) {
        int index = Math.floorMod(copyIndex, COPY_COUNT);
        String age = getAgeText(context);
        return new DialogCopy(
                context.getString(DIALOG_TITLES[index]),
                context.getString(DIALOG_SUBTITLES[index], age));
    }

    public static int getNotificationCopyIndex(Context context) {
        return Math.floorMod(prefs(context).getInt(K_COPY_INDEX, 0), NOTIFICATION_COPY_COUNT);
    }

    public static NotificationCopy getNotificationCopy(Context context, int copyIndex) {
        int index = Math.floorMod(copyIndex, NOTIFICATION_COPY_COUNT);
        String age = getAgeText(context);
        return new NotificationCopy(
                context.getString(NOTIFICATION_TITLES[index], age),
                context.getString(NOTIFICATION_BODIES[index], age));
    }

    public static String getAfterProTitle(Context context) {
        return context.getString(R.string.pro_reminder_after_title);
    }

    public static String getAfterProBody(Context context) {
        int days = getDaysUntilNext(context);
        return context.getResources().getQuantityString(
                R.plurals.pro_reminder_after_body, days, days);
    }

    /** Rotate the three back-press lines so the same joke never repeats twice in a row. */
    public static String getNextBackToast(Context context) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(context);
            int index = Math.floorMod(p.getInt(K_BACK_TOAST_INDEX, 0), 3);
            p.edit().putInt(K_BACK_TOAST_INDEX, (index + 1) % 3).apply();
            switch (index) {
                case 1:
                    return context.getString(R.string.pro_reminder_back_toast_2);
                case 2:
                    return context.getString(R.string.pro_reminder_back_toast_3);
                default:
                    return context.getString(R.string.pro_reminder_back_toast_1);
            }
        }
    }

    /** Called from HomeFragment when the stats cache proves at least one recording exists. */
    public static void markSuccessfulRecording(Context context) {
        if (!isSupported()) return;
        SharedPreferences p = prefs(context);
        if (!p.getBoolean(K_HAS_RECORDING, false)) {
            p.edit().putBoolean(K_HAS_RECORDING, true).apply();
        }
    }

    private static boolean firstEligible(Context context, SharedPreferences p) {
        long now = System.currentTimeMillis();
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            if (now - info.firstInstallTime < FIRST_DELAY_MS) return false;
        } catch (Exception e) {
            return false;
        }
        return hasSuccessfulRecording(context, p);
    }

    private static boolean hasSuccessfulRecording(Context context, SharedPreferences p) {
        if (p.getBoolean(K_HAS_RECORDING, false)) return true;
        try {
            VideoStatsCache.VideoStats stats = VideoStatsCache.getCachedStats(
                    SharedPreferencesManager.getInstance(context));
            if (stats != null && stats.isValid() && stats.videoCount > 0) {
                p.edit().putBoolean(K_HAS_RECORDING, true).apply();
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static boolean isClaimValid(SharedPreferences p, long now) {
        long claimed = p.getLong(K_CLAIMED_AT, 0L);
        long completed = p.getLong(K_COMPLETED_AT, 0L);
        return claimed > completed && now >= claimed && now - claimed < CLAIM_TTL_MS;
    }

    private static boolean notificationsEnabled(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        try {
            return NotificationManagerCompat.from(context).areNotificationsEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return SharedPreferencesManager.getInstance(context.getApplicationContext())
                .sharedPreferences;
    }

    public static final class DialogCopy {
        public final String title;
        public final String subtitle;

        DialogCopy(String title, String subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    public static final class NotificationCopy {
        public final String title;
        public final String body;

        NotificationCopy(String title, String body) {
            this.title = title;
            this.body = body;
        }
    }
}
