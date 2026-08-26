package com.fadcam.production;

import android.content.Context;
import android.content.SharedPreferences;

/** Persists producer scene choice and lower-third branding text. */
public final class ProductionSceneManager {
    private static final String PREFS = "fadcam_tv_production";
    private static final String KEY_SCENE = "scene";
    private static final String KEY_LOWER_THIRD = "lower_third";

    private ProductionSceneManager() {}

    public static ProductionScene getScene(Context context) {
        String value = prefs(context).getString(KEY_SCENE, ProductionScene.DUET_PIP.name());
        try {
            return ProductionScene.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return ProductionScene.DUET_PIP;
        }
    }

    public static void setScene(Context context, ProductionScene scene) {
        prefs(context).edit().putString(KEY_SCENE, scene.name()).apply();
    }

    public static String getLowerThird(Context context) {
        return prefs(context).getString(KEY_LOWER_THIRD, "");
    }

    public static void setLowerThird(Context context, String text) {
        String safe = text == null ? "" : text.trim();
        if (safe.length() > 80) safe = safe.substring(0, 80);
        prefs(context).edit().putString(KEY_LOWER_THIRD, safe).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
