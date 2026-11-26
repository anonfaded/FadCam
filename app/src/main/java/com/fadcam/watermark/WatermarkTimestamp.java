package com.fadcam.watermark;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Single source of truth for the watermark clock, so the live overlay, the photo watermark and
 * the settings preview can never drift apart.
 *
 * <p>Format is {@code Wed, 10 Jul 2024 · 4:47:03 PM}: natural word order, no slashes competing
 * with the clock's colons. The day-of-month stays zero padded so the line width does not jump
 * on single-digit days.</p>
 */
public final class WatermarkTimestamp {

    private static final String PATTERN_WITH_DAY = "EEE, dd MMM yyyy · h:mm:ss a";
    private static final String PATTERN_NO_DAY = "dd MMM yyyy · h:mm:ss a";

    private WatermarkTimestamp() {
    }

    /** Formats the current time; {@code includeDay} adds the abbreviated weekday prefix. */
    public static String now(boolean includeDay) {
        return format(new Date(), includeDay);
    }

    /** Formats a specific instant, used by the settings preview to show a fixed sample. */
    public static String format(Date date, boolean includeDay) {
        SimpleDateFormat sdf = new SimpleDateFormat(
                includeDay ? PATTERN_WITH_DAY : PATTERN_NO_DAY, Locale.ENGLISH);
        return convertArabicNumeralsToEnglish(sdf.format(date));
    }

    private static String convertArabicNumeralsToEnglish(String text) {
        if (text == null) return null;
        return text.replaceAll("٠", "0").replaceAll("١", "1")
                .replaceAll("٢", "2").replaceAll("٣", "3")
                .replaceAll("٤", "4").replaceAll("٥", "5")
                .replaceAll("٦", "6").replaceAll("٧", "7")
                .replaceAll("٨", "8").replaceAll("٩", "9");
    }
}
