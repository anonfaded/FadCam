package com.fadcam;

/**
 * Supported maximum camera-recording duration options and validation helpers.
 */
public final class MaximumRecordingDuration {
    public static final String OPTION_NO_LIMIT = "no_limit";
    public static final String OPTION_5_MINUTES = "5_minutes";
    public static final String OPTION_10_MINUTES = "10_minutes";
    public static final String OPTION_30_MINUTES = "30_minutes";
    public static final String OPTION_1_HOUR = "1_hour";
    public static final String OPTION_CUSTOM = "custom";

    public static final int MIN_CUSTOM_MINUTES = 1;
    public static final int MAX_CUSTOM_MINUTES = 24 * 60;
    public static final int DEFAULT_CUSTOM_MINUTES = 30;

    private MaximumRecordingDuration() {
    }

    public static boolean isSupportedOption(String option) {
        return OPTION_NO_LIMIT.equals(option)
                || OPTION_5_MINUTES.equals(option)
                || OPTION_10_MINUTES.equals(option)
                || OPTION_30_MINUTES.equals(option)
                || OPTION_1_HOUR.equals(option)
                || OPTION_CUSTOM.equals(option);
    }

    public static boolean isValidCustomMinutes(int minutes) {
        return minutes >= MIN_CUSTOM_MINUTES && minutes <= MAX_CUSTOM_MINUTES;
    }

    public static int resolveMinutes(String option, int customMinutes) {
        if (OPTION_5_MINUTES.equals(option)) {
            return 5;
        }
        if (OPTION_10_MINUTES.equals(option)) {
            return 10;
        }
        if (OPTION_30_MINUTES.equals(option)) {
            return 30;
        }
        if (OPTION_1_HOUR.equals(option)) {
            return 60;
        }
        if (OPTION_CUSTOM.equals(option) && isValidCustomMinutes(customMinutes)) {
            return customMinutes;
        }
        return 0;
    }

    public static long resolveDurationMillis(String option, int customMinutes) {
        return resolveMinutes(option, customMinutes) * 60_000L;
    }
}
