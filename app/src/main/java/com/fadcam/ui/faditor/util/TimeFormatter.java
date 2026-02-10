package com.fadcam.ui.faditor.util;

import androidx.annotation.NonNull;

import java.util.Locale;

/**
 * Utility for formatting time values in the editor UI.
 */
public final class TimeFormatter {

    private TimeFormatter() {
        // No instances
    }

    /**
     * Format milliseconds as MM:SS.
     *
     * @param ms milliseconds
     * @return formatted string like "01:23"
     */
    @NonNull
    public static String formatMmSs(long ms) {
        if (ms < 0) ms = 0;
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    /**
     * Format milliseconds as MM:SS.ms (with tenths).
     *
     * @param ms milliseconds
     * @return formatted string like "01:23.4"
     */
    @NonNull
    public static String formatMmSsTenths(long ms) {
        if (ms < 0) ms = 0;
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        long tenths = (ms % 1000) / 100;
        return String.format(Locale.US, "%02d:%02d.%d", minutes, seconds, tenths);
    }

    /**
     * Format milliseconds as HH:MM:SS for long videos.
     *
     * @param ms milliseconds
     * @return formatted string like "01:23:45"
     */
    @NonNull
    public static String formatHhMmSs(long ms) {
        if (ms < 0) ms = 0;
        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    /**
     * Auto-format: uses HH:MM:SS if >= 1 hour, else MM:SS.
     */
    @NonNull
    public static String formatAuto(long ms) {
        if (ms >= 3600_000) {
            return formatHhMmSs(ms);
        }
        return formatMmSs(ms);
    }
}
