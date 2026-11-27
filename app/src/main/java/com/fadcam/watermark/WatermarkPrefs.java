package com.fadcam.watermark;

import com.fadcam.Constants;
import com.fadcam.SharedPreferencesManager;

/**
 * The watermark settings for one recording mode.
 *
 * <p>Camera recording and screen recording are different things: a timestamp burned into a
 * camera clip is rarely what you want stamped across a screen capture, and the sensor lines
 * (speed, altitude, compass) read differently too. This class gives each mode its own set of
 * stored values instead of sharing one global set.</p>
 *
 * <p>Camera recording keeps the historical preference keys unchanged, so existing installs
 * keep every setting they already had. Screen recording gets its own namespace, seeded with
 * the same defaults, so the two diverge from the first edit onward without any migration.</p>
 */
public final class WatermarkPrefs {

    /** Historical keys, used by camera (and dual-camera) recording. */
    private static final String CAMERA_PREFIX = "";

    /** Screen recording stores its own copy of every watermark setting. */
    private static final String SCREEN_PREFIX = "screen_";

    /** Marks the one-time copy that gives screen recording its starting point. */
    private static final String SEEDED_KEY = SCREEN_PREFIX + "watermark_seeded";

    private final SharedPreferencesManager prefs;
    private final String prefix;

    private WatermarkPrefs(SharedPreferencesManager prefs, String prefix) {
        this.prefs = prefs;
        this.prefix = prefix;
    }

    /** Settings used by camera and dual-camera recording. */
    public static WatermarkPrefs forCamera(SharedPreferencesManager prefs) {
        return new WatermarkPrefs(prefs, CAMERA_PREFIX);
    }

    /** Settings used by screen recording. */
    public static WatermarkPrefs forScreenRecording(SharedPreferencesManager prefs) {
        seedScreenRecordingFromCameraOnce(prefs);
        return new WatermarkPrefs(prefs, SCREEN_PREFIX);
    }

    /**
     * Gives screen recording its starting point exactly once.
     *
     * <p>Before the two modes were split there was a single set of values, so a screen
     * recording inherited whatever camera recording had — including "no watermark". Copying
     * those values once keeps every existing install behaving exactly as it did, and from then
     * on the two sets are edited independently.</p>
     */
    private static void seedScreenRecordingFromCameraOnce(SharedPreferencesManager prefs) {
        android.content.SharedPreferences sp = prefs.sharedPreferences;
        if (sp.getBoolean(SEEDED_KEY, false)) return;

        String[] keys = {
                Constants.PREF_WATERMARK_OPTION,
                Constants.PREF_WATERMARK_CUSTOM_TEXT,
                Constants.PREF_WATERMARK_UPDATE_INTERVAL,
                Constants.PREF_WATERMARK_LOCATION_FORMAT,
                Constants.PREF_LOCATION_DATA,
                Constants.PREF_WATERMARK_UTM,
                Constants.PREF_WATERMARK_SPEED,
                Constants.PREF_WATERMARK_ALTITUDE,
                Constants.PREF_WATERMARK_ACCURACY,
                Constants.PREF_WATERMARK_COMPASS,
                Constants.PREF_WATERMARK_NOISE,
                Constants.PREF_WATERMARK_WEATHER,
                Constants.PREF_WATERMARK_DAY,
                Constants.PREF_WATERMARK_TIMEZONE,
                Constants.PREF_WATERMARK_TIMEZONE_FORMAT,
        };

        android.content.SharedPreferences.Editor editor = sp.edit();
        for (String key : keys) {
            // Only copy values that exist: absent keys must keep their own defaults.
            if (!sp.contains(key)) continue;
            Object value = sp.getAll().get(key);
            if (value instanceof Boolean) {
                editor.putBoolean(SCREEN_PREFIX + key, (Boolean) value);
            } else if (value instanceof String) {
                editor.putString(SCREEN_PREFIX + key, (String) value);
            } else if (value instanceof Long) {
                editor.putLong(SCREEN_PREFIX + key, (Long) value);
            } else if (value instanceof Integer) {
                editor.putInt(SCREEN_PREFIX + key, (Integer) value);
            } else if (value instanceof Float) {
                editor.putFloat(SCREEN_PREFIX + key, (Float) value);
            }
        }
        editor.putBoolean(SEEDED_KEY, true).apply();
    }

    /** True when this is the screen-recording set. */
    public boolean isScreenRecording() {
        return SCREEN_PREFIX.equals(prefix);
    }

    // ── Keys ──────────────────────────────────────────────────────────

    private String key(String base) {
        return prefix + base;
    }

    private boolean getBoolean(String base, boolean fallback) {
        return prefs.sharedPreferences.getBoolean(key(base), fallback);
    }

    private void putBoolean(String base, boolean value) {
        prefs.sharedPreferences.edit().putBoolean(key(base), value).apply();
    }

    private String getString(String base, String fallback) {
        return prefs.sharedPreferences.getString(key(base), fallback);
    }

    private void putString(String base, String value) {
        prefs.sharedPreferences.edit().putString(key(base), value).apply();
    }

    private long getLong(String base, long fallback) {
        return prefs.sharedPreferences.getLong(key(base), fallback);
    }

    // ── Watermark style and text ──────────────────────────────────────

    public String getWatermarkOption() {
        return getString(Constants.PREF_WATERMARK_OPTION, Constants.DEFAULT_WATERMARK_OPTION);
    }

    public void setWatermarkOption(String option) {
        putString(Constants.PREF_WATERMARK_OPTION,
                option != null ? option : Constants.DEFAULT_WATERMARK_OPTION);
    }

    public String getCustomText() {
        return getString(Constants.PREF_WATERMARK_CUSTOM_TEXT, "");
    }

    public void setCustomText(String text) {
        putString(Constants.PREF_WATERMARK_CUSTOM_TEXT, text != null ? text : "");
    }

    public long getUpdateInterval() {
        return getLong(Constants.PREF_WATERMARK_UPDATE_INTERVAL, 5000L);
    }

    public void setUpdateInterval(long intervalMs) {
        prefs.sharedPreferences.edit()
                .putLong(key(Constants.PREF_WATERMARK_UPDATE_INTERVAL), Math.max(500, intervalMs))
                .apply();
    }

    // ── Location ──────────────────────────────────────────────────────

    public boolean isLocationEnabled() {
        return getBoolean(Constants.PREF_LOCATION_DATA, false);
    }

    public void setLocationEnabled(boolean enabled) {
        putBoolean(Constants.PREF_LOCATION_DATA, enabled);
    }

    public String getLocationFormat() {
        String format = getString(Constants.PREF_WATERMARK_LOCATION_FORMAT, "coordinates");
        // Legacy value from before the address lookup replaced city/country.
        return "city_country".equals(format) ? "address" : format;
    }

    public void setLocationFormat(String format) {
        putString(Constants.PREF_WATERMARK_LOCATION_FORMAT,
                format != null ? format : "coordinates");
    }

    public boolean isUtmEnabled() {
        return getBoolean(Constants.PREF_WATERMARK_UTM, false);
    }

    public void setUtmEnabled(boolean enabled) {
        putBoolean(Constants.PREF_WATERMARK_UTM, enabled);
    }

    // ── Extended sensor lines ─────────────────────────────────────────

    public boolean isSpeedEnabled() {
        return getBoolean(Constants.PREF_WATERMARK_SPEED, false);
    }

    public void setSpeedEnabled(boolean enabled) {
        putBoolean(Constants.PREF_WATERMARK_SPEED, enabled);
    }

    public boolean isAltitudeEnabled() {
        return getBoolean(Constants.PREF_WATERMARK_ALTITUDE, false);
    }

    public void setAltitudeEnabled(boolean enabled) {
        putBoolean(Constants.PREF_WATERMARK_ALTITUDE, enabled);
    }

    public boolean isAccuracyEnabled() {
        return getBoolean(Constants.PREF_WATERMARK_ACCURACY, false);
    }

    public void setAccuracyEnabled(boolean enabled) {
        putBoolean(Constants.PREF_WATERMARK_ACCURACY, enabled);
    }

    public boolean isCompassEnabled() {
        return getBoolean(Constants.PREF_WATERMARK_COMPASS, false);
    }

    public void setCompassEnabled(boolean enabled) {
        putBoolean(Constants.PREF_WATERMARK_COMPASS, enabled);
    }

    public boolean isNoiseEnabled() {
        return getBoolean(Constants.PREF_WATERMARK_NOISE, false);
    }

    public void setNoiseEnabled(boolean enabled) {
        putBoolean(Constants.PREF_WATERMARK_NOISE, enabled);
    }

    public boolean isWeatherEnabled() {
        return getBoolean(Constants.PREF_WATERMARK_WEATHER, false);
    }

    public void setWeatherEnabled(boolean enabled) {
        putBoolean(Constants.PREF_WATERMARK_WEATHER, enabled);
    }

    // ── Timestamp formatting ──────────────────────────────────────────

    public boolean isDayEnabled() {
        return getBoolean(Constants.PREF_WATERMARK_DAY, true); // default ON
    }

    public void setDayEnabled(boolean enabled) {
        putBoolean(Constants.PREF_WATERMARK_DAY, enabled);
    }

    public boolean isTimezoneEnabled() {
        return getBoolean(Constants.PREF_WATERMARK_TIMEZONE, false);
    }

    public void setTimezoneEnabled(boolean enabled) {
        putBoolean(Constants.PREF_WATERMARK_TIMEZONE, enabled);
    }

    public String getTimezoneFormat() {
        return getString(Constants.PREF_WATERMARK_TIMEZONE_FORMAT, "gmt_only");
    }

    public void setTimezoneFormat(String format) {
        putString(Constants.PREF_WATERMARK_TIMEZONE_FORMAT,
                format != null ? format : "gmt_only");
    }

    /**
     * True when any enabled feature needs a live GPS feed. Mirrors
     * {@link com.fadcam.services.LocationFeedGate} but scoped to this mode, so turning on the
     * screen-recording speed line cannot start a GPS listener for camera recording.
     */
    public boolean needsLocationData() {
        return isLocationEnabled()
                || prefs.isLocationEmbeddingEnabled()
                || isUtmEnabled()
                || isSpeedEnabled()
                || isAltitudeEnabled()
                || isAccuracyEnabled()
                || isCompassEnabled()
                || isWeatherEnabled();
    }
}
