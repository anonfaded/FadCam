package com.fadcam.widgets;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Utility for converting Gregorian dates to Arabic/Islamic calendar
 */
public class ArabicDateUtils {
    
    // Arabic month names
    private static final String[] ARABIC_MONTHS = {
        "محرم", "صفر", "ربيع الأول", "ربيع الثاني", "جمادى الأولى", "جمادى الثانية",
        "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة"
    };
    
    // Arabic numerals
    private static final String[] ARABIC_NUMERALS = {
        "٠", "١", "٢", "٣", "٤", "٥", "٦", "٧", "٨", "٩"
    };
    
    /**
     * Convert Gregorian date to approximate Hijri date
     * This is a simplified approximation for display purposes
     */
    public static String getArabicDate() {
        Calendar cal = Calendar.getInstance();
        
        // Simple approximation: Hijri year is roughly Gregorian year - 578-582
        // This is not astronomically accurate but good enough for widget display
        int gregorianYear = cal.get(Calendar.YEAR);
        int hijriYear = gregorianYear - 579; // Approximate conversion
        
        // Estimate month and day (simplified)
        int dayOfYear = cal.get(Calendar.DAY_OF_YEAR);
        int estimatedMonth = ((dayOfYear + 120) / 30) % 12; // Rough estimation
        int estimatedDay = ((dayOfYear + 120) % 30) + 1;
        
        // Convert to Arabic numerals
        String arabicDay = convertToArabicNumerals(String.valueOf(estimatedDay));
        String arabicYear = convertToArabicNumerals(String.valueOf(hijriYear));
        String monthName = ARABIC_MONTHS[estimatedMonth];
        
        return arabicDay + " " + monthName + " " + arabicYear;
    }
    
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
