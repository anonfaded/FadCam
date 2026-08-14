package com.fadcam;

/**
 * Supported maximum camera-recording duration options and validation helpers.
 *
 * <p>The custom option supports second granularity (0 seconds = no timer, up to
 * 24 hours) so short unattended clips and long captures are both possible.
 * Durations are resolved to milliseconds for the recording-limit controller.
 */
public final class MaximumRecordingDuration {
    public static final String OPTION_NO_LIMIT = "no_limit";
    public static final String OPTION_1_MINUTE = "1_minute";
    public static final String OPTION_2_MINUTES = "2_minutes";
    public static final String OPTION_3_MINUTES = "3_minutes";
    public static final String OPTION_4_MINUTES = "4_minutes";
    public static final String OPTION_5_MINUTES = "5_minutes";
    public static final String OPTION_10_MINUTES = "10_minutes";
    public static final String OPTION_30_MINUTES = "30_minutes";
    public static final String OPTION_1_HOUR = "1_hour";
    public static final String OPTION_2_HOURS = "2_hours";
    public static final String OPTION_3_HOURS = "3_hours";
    public static final String OPTION_4_HOURS = "4_hours";
    public static final String OPTION_5_HOURS = "5_hours";
    public static final String OPTION_CUSTOM = "custom";

    public static final int MIN_CUSTOM_SECONDS = 0;
    public static final int MAX_CUSTOM_SECONDS = 24 * 60 * 60; // 24 hours
    public static final int DEFAULT_CUSTOM_SECONDS = 30 * 60;  // 30 minutes

    private MaximumRecordingDuration() {
    }

    public static boolean isSupportedOption(String option) {
        return OPTION_NO_LIMIT.equals(option)
                || OPTION_1_MINUTE.equals(option)
                || OPTION_2_MINUTES.equals(option)
                || OPTION_3_MINUTES.equals(option)
                || OPTION_4_MINUTES.equals(option)
                || OPTION_5_MINUTES.equals(option)
                || OPTION_10_MINUTES.equals(option)
                || OPTION_30_MINUTES.equals(option)
                || OPTION_1_HOUR.equals(option)
                || OPTION_2_HOURS.equals(option)
                || OPTION_3_HOURS.equals(option)
                || OPTION_4_HOURS.equals(option)
                || OPTION_5_HOURS.equals(option)
                || OPTION_CUSTOM.equals(option);
    }

    public static boolean isValidCustomSeconds(int seconds) {
        return seconds >= MIN_CUSTOM_SECONDS && seconds <= MAX_CUSTOM_SECONDS;
    }

    /** Splits a total second count into {hours, minutes, seconds} components. */
    public static int[] splitToHms(int totalSeconds) {
        int h = totalSeconds / 3600;
        int m = (totalSeconds % 3600) / 60;
        int s = totalSeconds % 60;
        return new int[] {h, m, s};
    }

    /** Recombines {hours, minutes, seconds} components into total seconds. */
    public static int combineFromHms(int hours, int minutes, int seconds) {
        return Math.max(0, hours * 3600 + minutes * 60 + seconds);
    }

    public static long resolveDurationMillis(String option, int customSeconds) {
        if (OPTION_1_MINUTE.equals(option)) {
            return 1L * 60_000L;
        }
        if (OPTION_2_MINUTES.equals(option)) {
            return 2L * 60_000L;
        }
        if (OPTION_3_MINUTES.equals(option)) {
            return 3L * 60_000L;
        }
        if (OPTION_4_MINUTES.equals(option)) {
            return 4L * 60_000L;
        }
        if (OPTION_5_MINUTES.equals(option)) {
            return 5L * 60_000L;
        }
        if (OPTION_10_MINUTES.equals(option)) {
            return 10L * 60_000L;
        }
        if (OPTION_30_MINUTES.equals(option)) {
            return 30L * 60_000L;
        }
        if (OPTION_1_HOUR.equals(option)) {
            return 60L * 60_000L;
        }
        if (OPTION_2_HOURS.equals(option)) {
            return 2L * 60L * 60_000L;
        }
        if (OPTION_3_HOURS.equals(option)) {
            return 3L * 60L * 60_000L;
        }
        if (OPTION_4_HOURS.equals(option)) {
            return 4L * 60L * 60_000L;
        }
        if (OPTION_5_HOURS.equals(option)) {
            return 5L * 60L * 60_000L;
        }
        if (OPTION_CUSTOM.equals(option) && isValidCustomSeconds(customSeconds)) {
            return customSeconds * 1_000L;
        }
        return 0L;
    }

    /**
     * Builds the option rows (with leading Material icon ligatures) shared by
     * the Settings and home quick-action duration sheets. Returns a fresh list
     * per call so callers may mutate it freely.
     *
     * <p>Icons are grouped by unit for a consistent visual: "timer" for every
     * minute preset, "alarm" for every hour preset, "block" for no limit and
     * "edit" for custom.</p>
     */
    public static java.util.ArrayList<com.fadcam.ui.picker.OptionItem> buildOptionItems(
            android.content.Context context) {
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items =
                new java.util.ArrayList<>();
        items.add(com.fadcam.ui.picker.OptionItem.withLigature(
                OPTION_NO_LIMIT, context.getString(R.string.maximum_recording_duration_no_limit),
                "block"));
        items.add(com.fadcam.ui.picker.OptionItem.withLigature(
                OPTION_1_MINUTE, context.getString(R.string.maximum_recording_duration_1_minute),
                "timer"));
        items.add(com.fadcam.ui.picker.OptionItem.withLigature(
                OPTION_2_MINUTES, context.getString(R.string.maximum_recording_duration_2_minutes),
                "timer"));
        items.add(com.fadcam.ui.picker.OptionItem.withLigature(
                OPTION_3_MINUTES, context.getString(R.string.maximum_recording_duration_3_minutes),
                "timer"));
        items.add(com.fadcam.ui.picker.OptionItem.withLigature(
                OPTION_4_MINUTES, context.getString(R.string.maximum_recording_duration_4_minutes),
                "timer"));
        items.add(com.fadcam.ui.picker.OptionItem.withLigature(
                OPTION_5_MINUTES, context.getString(R.string.maximum_recording_duration_5_minutes),
                "timer"));
        items.add(com.fadcam.ui.picker.OptionItem.withLigature(
                OPTION_10_MINUTES, context.getString(R.string.maximum_recording_duration_10_minutes),
                "timer"));
        items.add(com.fadcam.ui.picker.OptionItem.withLigature(
                OPTION_30_MINUTES, context.getString(R.string.maximum_recording_duration_30_minutes),
                "timer"));
        items.add(com.fadcam.ui.picker.OptionItem.withLigature(
                OPTION_1_HOUR, context.getString(R.string.maximum_recording_duration_1_hour),
                "alarm"));
        items.add(com.fadcam.ui.picker.OptionItem.withLigature(
                OPTION_2_HOURS, context.getString(R.string.maximum_recording_duration_2_hours),
                "alarm"));
        items.add(com.fadcam.ui.picker.OptionItem.withLigature(
                OPTION_3_HOURS, context.getString(R.string.maximum_recording_duration_3_hours),
                "alarm"));
        items.add(com.fadcam.ui.picker.OptionItem.withLigature(
                OPTION_4_HOURS, context.getString(R.string.maximum_recording_duration_4_hours),
                "alarm"));
        items.add(com.fadcam.ui.picker.OptionItem.withLigature(
                OPTION_5_HOURS, context.getString(R.string.maximum_recording_duration_5_hours),
                "alarm"));
        items.add(com.fadcam.ui.picker.OptionItem.withLigature(
                OPTION_CUSTOM, context.getString(R.string.maximum_recording_duration_custom),
                "edit"));
        return items;
    }
}
