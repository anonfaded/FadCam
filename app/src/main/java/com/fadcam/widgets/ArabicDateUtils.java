package com.fadcam.widgets;

import java.time.LocalDate;
import java.time.chrono.HijrahDate;
import java.time.temporal.ChronoField;

/**
 * Utility for converting Gregorian dates to Arabic/Islamic calendar using proper Java Hijri calendar
 */
public class ArabicDateUtils {
    
    // Arabic month names
    private static final String[] ARABIC_MONTHS = {
        "محرم", "صفر", "ربيع الأول", "ربيع الثاني", "جمادى الأولى", "جمادى الثانية",
        "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة"
    };
    
    // English Islamic month names
    private static final String[] ENGLISH_ISLAMIC_MONTHS = {
        "Muharram", "Safar", "Rabi' al-Awwal", "Rabi' al-Thani", "Jumada al-Awwal", "Jumada al-Thani",
        "Rajab", "Sha'ban", "Ramadan", "Shawwal", "Dhu al-Qi'dah", "Dhu al-Hijjah"
    };
    
    // Arabic numerals
    private static final String[] ARABIC_NUMERALS = {
        "٠", "١", "٢", "٣", "٤", "٥", "٦", "٧", "٨", "٩"
    };
    
    /**
     * Convert Gregorian date to Hijri date using Java's built-in HijrahDate
     */
    public static String getArabicDate() {
        return getArabicDate("FULL_ARABIC");
    }
    
    /**
     * Convert Gregorian date to Hijri date with format option using Java's built-in HijrahDate
     * @param format "FULL_ARABIC" = all Arabic, "MONTH_ARABIC_NUMBERS_ENGLISH" = Arabic month + English numbers, "FULL_ENGLISH" = all English
     */
    public static String getArabicDate(String format) {
        try {
            // Get current Gregorian date
            LocalDate gregorianDate = LocalDate.now();
            
            // Convert to Hijri date using Java's built-in calendar
            HijrahDate hijriDate = HijrahDate.from(gregorianDate);
            
            // Extract Hijri date components
            int hijriDay = hijriDate.get(ChronoField.DAY_OF_MONTH);
            int hijriMonthIndex = hijriDate.get(ChronoField.MONTH_OF_YEAR) - 1; // Convert to 0-based index for arrays
            int hijriYear = hijriDate.get(ChronoField.YEAR);
            
            switch (format) {
                case "MONTH_ARABIC_NUMBERS_ENGLISH":
                    // Arabic month name with English numbers
                    return hijriDay + " " + ARABIC_MONTHS[hijriMonthIndex] + " " + hijriYear;
                    
                case "FULL_ENGLISH":
                    // All English (Islamic month names in English)
                    return hijriDay + " " + ENGLISH_ISLAMIC_MONTHS[hijriMonthIndex] + " " + hijriYear;
                    
                case "FULL_ARABIC":
                default:
                    // All Arabic
                    String arabicDay = convertToArabicNumerals(String.valueOf(hijriDay));
                    String arabicYear = convertToArabicNumerals(String.valueOf(hijriYear));
                    String monthName = ARABIC_MONTHS[hijriMonthIndex];
                    return arabicDay + " " + monthName + " " + arabicYear;
            }
        } catch (Exception e) {
            // Fallback in case of any issues with Hijri calendar conversion
            return "١ محرم ١٤٤٦"; // Default Arabic date
        }
    }
    
    /**
     * Convert Western numerals to Arabic numerals
     */
    private static String convertToArabicNumerals(String number) {
        StringBuilder result = new StringBuilder();
        for (char c : number.toCharArray()) {
            if (Character.isDigit(c)) {
                result.append(ARABIC_NUMERALS[c - '0']);
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
}
