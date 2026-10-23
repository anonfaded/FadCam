package com.fadcam.utils;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.icu.text.MeasureFormat;
import android.icu.util.Measure;
import android.icu.util.MeasureUnit;

import com.fadcam.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
     * Zero units are skipped; minutes are only used when the whole age is under an hour.
     *
     * <p>This is the localized path used by the weekly reminder. ICU supplies the unit names,
     * plural forms and list separators for every shipped locale. Pashto is special-cased
     * because the app ships Roman Pashto while ICU's {@code ps} data is Arabic script.
     */
    public static String twoUnits(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            long millis = Math.max(0L, System.currentTimeMillis() - info.firstInstallTime);
            return twoUnits(context, millis);
        } catch (Exception e) {
            return "";
        }
    }

    static String twoUnits(Context context, long millis) {
        long minutes = millis / 60000L;
        long hours = minutes / 60L;
        long days = hours / 24L;
        long years = days / 365L;
        long months = (days % 365L) / 30L;
        long remDays = (days % 365L) % 30L;
        long remHours = hours % 24L;

        List<Measure> measures = new ArrayList<>(4);
        if (years > 0) measures.add(new Measure(years, MeasureUnit.YEAR));
        if (months > 0) measures.add(new Measure(months, MeasureUnit.MONTH));
        if (remDays > 0) measures.add(new Measure(remDays, MeasureUnit.DAY));
        if (remHours > 0) measures.add(new Measure(remHours, MeasureUnit.HOUR));
        if (measures.isEmpty()) {
            // The reminder only appears after three days, but keep a sane value for any
            // future caller: "1 minute" rather than an English "under an hour".
            measures.add(new Measure(Math.max(1L, minutes), MeasureUnit.MINUTE));
        }

        Locale locale = appLocale(context);
        if ("ps".equals(locale.getLanguage())) {
            return pashtoTwoUnits(context, measures);
        }
        try {
            MeasureFormat format = MeasureFormat.getInstance(
                    locale, MeasureFormat.FormatWidth.WIDE);
            Measure[] firstTwo = measures
                    .subList(0, Math.min(2, measures.size()))
                    .toArray(new Measure[0]);
            String formatted = format.formatMeasures(firstTwo);
            if (formatted != null && !formatted.trim().isEmpty()) return formatted;
        } catch (Exception ignored) {
            // Fall through to the English helper below.
        }
        return twoUnits(millis);
    }

    private static Locale appLocale(Context context) {
        try {
            android.content.res.Configuration configuration =
                    context.getResources().getConfiguration();
            if (configuration != null && !configuration.getLocales().isEmpty()) {
                return configuration.getLocales().get(0);
            }
        } catch (Exception ignored) {
        }
        return Locale.getDefault();
    }

    private static String pashtoTwoUnits(Context context, List<Measure> measures) {
        StringBuilder out = new StringBuilder();
        int count = Math.min(2, measures.size());
        for (int i = 0; i < count; i++) {
            Measure measure = measures.get(i);
            int value = measure.getNumber().intValue();
            MeasureUnit unit = measure.getUnit();
            int res;
            if (MeasureUnit.YEAR.equals(unit)) res = R.plurals.app_age_year;
            else if (MeasureUnit.MONTH.equals(unit)) res = R.plurals.app_age_month;
            else if (MeasureUnit.DAY.equals(unit)) res = R.plurals.app_age_day;
            else if (MeasureUnit.HOUR.equals(unit)) res = R.plurals.app_age_hour;
            else res = R.plurals.app_age_minute;
            if (i > 0) out.append(' ');
            // %1$s in the Pashto resources: keeps the digits Western instead of the
            // Arabic-Indic digits Android would use for %1$d under the ps locale.
            out.append(context.getResources().getQuantityString(
                    res, value, String.valueOf(value)));
        }
        return out.toString();
    }

    /**
     * English helper retained for the unit test and as a last-resort fallback when ICU is
     * unavailable on a broken OEM build.
     */
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
