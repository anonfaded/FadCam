package com.fadcam.utils;

import android.content.Context;
import android.content.pm.PackageInfo;

import com.fadcam.R;

/**
 * The app's own age, phrased as a product line: "Using FadCam for 1 month, 9 days".
 *
 * <p>Shared by the Pro mockup's lock screen and the home sidebar footer, so both always show the
 * same thing. The age comes from the real first-install time, never from an update.
 */
public final class AppUsageAge {

    private AppUsageAge() {
    }

    /** The finished one-line phrasing, or an empty string when the install time is unreadable. */
    public static String text(Context context) {
        try {
            return context.getString(R.string.app_usage_since_install, value(context));
        } catch (Exception e) {
            return "";
        }
    }

    /** The leading phrase on its own, for hosts that show the age as a titled stat. */
    public static String title(Context context) {
        return context.getString(R.string.app_usage_since_install_title);
    }

    /**
     * The exact first-install moment: full weekday, abbreviated month, exact time in the user's
     * locale — "Monday, 9 Sep 2026 · 3:52 AM".
     */
    public static String installedAt(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            java.util.Date installed = new java.util.Date(info.firstInstallTime);
            java.util.Locale locale = java.util.Locale.getDefault();
            String day = new java.text.SimpleDateFormat("EEEE, d MMM yyyy", locale).format(installed);
            String time = java.text.DateFormat.getTimeInstance(
                    java.text.DateFormat.SHORT, locale).format(installed);
            return day + " · " + time;
        } catch (Exception e) {
            return "";
        }
    }

    /** The age itself, open-ended ("1 year, 1 month, 5 days, 3 hours…"). */
    public static String value(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            long millis = Math.max(0L, System.currentTimeMillis() - info.firstInstallTime);
            return duration(millis) + "…";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Exactly two meaningful units, for short promotional copy:
     * "1 year 2 months", "3 months 8 days", "5 days 4 hours".
     * Zero units are skipped; minutes are only used when the whole age is under an hour,
     * where "under an hour" reads better than a fake second unit.
     */
    public static String twoUnits(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            long millis = Math.max(0L, System.currentTimeMillis() - info.firstInstallTime);
            return twoUnits(millis);
        } catch (Exception e) {
            return "";
        }
    }

    static String twoUnits(long millis) {
        long minutes = millis / 60000L;
        long hours = minutes / 60L;
        long days = hours / 24L;
        long years = days / 365L;
        long months = (days % 365L) / 30L;
        long remDays = (days % 365L) % 30L;
        long remHours = hours % 24L;

        String[] parts = new String[4];
        int count = 0;
        if (years > 0) parts[count++] = years + (years == 1 ? " year" : " years");
        if (months > 0) parts[count++] = months + (months == 1 ? " month" : " months");
        if (remDays > 0) parts[count++] = remDays + (remDays == 1 ? " day" : " days");
        if (remHours > 0) parts[count++] = remHours + (remHours == 1 ? " hour" : " hours");

        if (count == 0) return "under an hour";
        if (count == 1) return parts[0];
        return parts[0] + " " + parts[1];
    }

    /**
     * Every non-zero unit, most significant first ("1 month, 9 days, 16 hours, 25 minutes"),
     * joined cleanly — never a dangling separator. Days are the smallest calendar unit: a
     * rounded week reads as a vague guess beside a date.
     */
    static String duration(long millis) {
        long minutes = millis / 60000L;
        long hours = minutes / 60L;
        long days = hours / 24L;
        long years = days / 365L;
        long months = (days % 365L) / 30L;
        long remDays = (days % 365L) % 30L;
        long remHours = hours % 24L;
        long remMinutes = minutes % 60L;
        String[] parts = new String[6];
        int count = 0;
        if (years > 0) parts[count++] = years + (years == 1 ? " year" : " years");
        if (months > 0) parts[count++] = months + (months == 1 ? " month" : " months");
        if (remDays > 0) parts[count++] = remDays + (remDays == 1 ? " day" : " days");
        if (remHours > 0) parts[count++] = remHours + (remHours == 1 ? " hour" : " hours");
        if (remMinutes > 0) parts[count++] = remMinutes + (remMinutes == 1 ? " minute" : " minutes");
        if (count == 0) return "under a minute";
        StringBuilder joined = new StringBuilder(parts[0]);
        for (int i = 1; i < count; i++) {
            joined.append(", ").append(parts[i]);
        }
        return joined.toString();
    }
}
