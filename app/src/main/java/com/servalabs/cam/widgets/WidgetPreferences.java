package com.servalabs.cam.widgets;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Widget preferences manager for clock customization
 */
public class WidgetPreferences {
    private static final String PREFS_NAME = "widget_clock_prefs";
    private static final String KEY_TIME_FORMAT = "time_format"; // "12h" or "24h"
    private static final String KEY_DATE_FORMAT = "date_format"; // see DATE_FORMAT_* constants
    private static final String KEY_SHOW_DATE = "show_date";
    private static final String KEY_BLACK_BACKGROUND = "black_background";
    private static final String KEY_SHOW_BRANDING = "show_branding";
    
    // Date format options
    public static final String DATE_FORMAT_DAY_MONTH_YEAR = "dd MMM yyyy"; // 13 Aug 2025
    public static final String DATE_FORMAT_MONTH_DAY_YEAR = "MMM dd, yyyy"; // Aug 13, 2025
    public static final String DATE_FORMAT_DMY_NUMERIC = "dd/MM/yyyy"; // 13/08/2025
    public static final String DATE_FORMAT_MDY_NUMERIC = "MM/dd/yyyy"; // 08/13/2025
    public static final String DATE_FORMAT_FULL_DAY_NO_YEAR = "EEEE, dd MMM"; // Wednesday, 13 Aug
    public static final String DATE_FORMAT_SHORT_DAY_NO_YEAR = "EEE, dd MMM"; // Wed, 13 Aug
    
    private final SharedPreferences prefs;
    
    public WidgetPreferences(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
    
    public boolean is24HourFormat() {
        return "24h".equals(prefs.getString(KEY_TIME_FORMAT, "12h"));
    }
    
    public void setTimeFormat(boolean is24Hour) {
        prefs.edit().putString(KEY_TIME_FORMAT, is24Hour ? "24h" : "12h").apply();
    }
    
    public String getDateFormat() {
        return prefs.getString(KEY_DATE_FORMAT, DATE_FORMAT_MONTH_DAY_YEAR); // Default: Aug 13, 2025
    }
    
    public void setDateFormat(String format) {
        prefs.edit().putString(KEY_DATE_FORMAT, format).apply();
    }
    
    public boolean showDate() {
        return prefs.getBoolean(KEY_SHOW_DATE, true);
    }
    
    public void setShowDate(boolean show) {
        prefs.edit().putBoolean(KEY_SHOW_DATE, show).apply();
    }
    
    public boolean hasBlackBackground() {
        return prefs.getBoolean(KEY_BLACK_BACKGROUND, false);
    }
    
    public void setBlackBackground(boolean blackBackground) {
        prefs.edit().putBoolean(KEY_BLACK_BACKGROUND, blackBackground).apply();
    }
    
    public boolean showBranding() {
        return prefs.getBoolean(KEY_SHOW_BRANDING, true);
    }
    
    public void setBranding(boolean show) {
        prefs.edit().putBoolean(KEY_SHOW_BRANDING, show).apply();
    }
}
