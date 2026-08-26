package com.fadcam.studio;

import android.content.Context;
import android.content.SharedPreferences;

import com.fadcam.Constants;

/**
 * Connects the Studio graphic editor to FadCam's existing GL encoder watermark
 * path. This is deliberately preference based so RecordingService remains the
 * single owner of the camera/encoder lifecycle.
 */
public final class StudioGraphicsRecorderBridge {
    private static final String PREFS = "FadCamStudioGraphics";
    private static final String SAVED_OPTION = "saved_watermark_option";
    private static final String SAVED_TEXT = "saved_watermark_text";
    private static final String ACTIVE = "active";
    private static final String GRAPHIC = "graphic";

    private StudioGraphicsRecorderBridge() {}

    public static void enable(Context context, String graphic) {
        SharedPreferences studio = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences app = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        if (!studio.getBoolean(ACTIVE, false)) {
            studio.edit()
                    .putString(SAVED_OPTION, app.getString(Constants.PREF_WATERMARK_OPTION, Constants.DEFAULT_WATERMARK_OPTION))
                    .putString(SAVED_TEXT, app.getString(Constants.PREF_WATERMARK_CUSTOM_TEXT, ""))
                    .putBoolean(ACTIVE, true)
                    .apply();
        }
        app.edit()
                // timestamp_fadcam is a known encoder-supported watermark mode;
                // the Studio graphic becomes part of the GL-rendered encoded frame.
                .putString(Constants.PREF_WATERMARK_OPTION, Constants.DEFAULT_WATERMARK_OPTION)
                .putString(Constants.PREF_WATERMARK_CUSTOM_TEXT, graphic == null ? "" : graphic)
                .apply();
        studio.edit().putString(GRAPHIC, graphic == null ? "" : graphic).apply();
    }

    public static void update(Context context, String graphic) {
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ACTIVE, false)) {
            enable(context, graphic);
            return;
        }
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(Constants.PREF_WATERMARK_CUSTOM_TEXT, graphic == null ? "" : graphic)
                .apply();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(GRAPHIC, graphic == null ? "" : graphic).apply();
    }

    public static void disable(Context context) {
        SharedPreferences studio = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!studio.getBoolean(ACTIVE, false)) return;
        String oldOption = studio.getString(SAVED_OPTION, Constants.DEFAULT_WATERMARK_OPTION);
        String oldText = studio.getString(SAVED_TEXT, "");
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(Constants.PREF_WATERMARK_OPTION, oldOption)
                .putString(Constants.PREF_WATERMARK_CUSTOM_TEXT, oldText)
                .apply();
        studio.edit().clear().apply();
    }

    public static boolean isActive(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ACTIVE, false);
    }
}
