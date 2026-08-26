package com.fadcam.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Local branding state for the free Pro/Pro+ feature set. */
public final class BrandingManager {
    private static final String PREFS = "fadcam_pro_features";
    private static final String PREF_CUSTOM_NAME = "custom_app_name";
    private static final String ICON_FILE = "custom_branding_icon.png";
    private static final int MAX_NAME_LENGTH = 32;

    private BrandingManager() {}

    public static String getDisplayName(Context context) {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_CUSTOM_NAME, "FadCam");
        return sanitizeName(value);
    }

    public static boolean hasCustomIcon(Context context) {
        File icon = getIconFile(context);
        return icon.isFile() && icon.length() > 0;
    }

    /** Saves the name and copies the selected document into app-private storage. */
    public static boolean save(Context context, @Nullable String name, @Nullable Uri iconUri) {
        String safeName = sanitizeName(name);
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREF_CUSTOM_NAME, safeName);
        if (iconUri != null && !copyIcon(context, iconUri)) return false;
        editor.apply();
        return true;
    }

    public static void clearIcon(Context context) {
        File icon = getIconFile(context);
        if (icon.exists()) icon.delete();
    }

    @Nullable
    public static Bitmap loadIcon(Context context) {
        File file = getIconFile(context);
        if (!file.isFile()) return null;
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }

    /** Applies the custom name to Android's activity title without changing the package label. */
    public static void applyToActivity(Activity activity) {
        if (activity != null) activity.setTitle(getDisplayName(activity));
    }

    /** Creates/refreshes a supported Android home-screen shortcut with custom branding. */
    public static boolean publishHomeShortcut(Context context) {
        Bitmap bitmap = loadIcon(context);
        if (bitmap == null || bitmap.isRecycled()) return false;
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launch == null) return false;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        IconCompat icon = IconCompat.createWithBitmap(bitmap);
        ShortcutInfoCompat shortcut = new ShortcutInfoCompat.Builder(context, "fadcam-custom-branding")
                .setShortLabel(getDisplayName(context))
                .setLongLabel(getDisplayName(context))
                .setIcon(icon)
                .setIntent(launch)
                .build();
        return ShortcutManagerCompat.pushDynamicShortcut(context, shortcut);
    }

    private static boolean copyIcon(Context context, Uri source) {
        File destination = getIconFile(context);
        File temp = new File(context.getFilesDir(), ICON_FILE + ".tmp");
        try (InputStream in = context.getContentResolver().openInputStream(source);
             FileOutputStream out = new FileOutputStream(temp)) {
            if (in == null) return false;
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            if (!temp.renameTo(destination)) {
                if (destination.exists()) destination.delete();
                if (!temp.renameTo(destination)) return false;
            }
            return true;
        } catch (Exception e) {
            if (temp.exists()) temp.delete();
            return false;
        }
    }

    private static File getIconFile(Context context) {
        return new File(context.getFilesDir(), ICON_FILE);
    }

    /** Pure-Java sanitization so the unit test can run without Android framework stubs. */
    static String sanitizeName(String value) {
        if (value == null || value.trim().isEmpty()) return "FadCam";

        // Keep line/tab separators from being silently concatenated into words.
        // All other ISO control characters are replaced with a space as well.
        StringBuilder cleaned = new StringBuilder();
        boolean previousWasSpace = false;
        for (int i = 0; i < value.length() && cleaned.length() < MAX_NAME_LENGTH; i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)) {
                if (!previousWasSpace) {
                    cleaned.append(' ');
                    previousWasSpace = true;
                }
            } else {
                cleaned.append(c);
                previousWasSpace = false;
            }
        }
        String result = cleaned.toString().trim();
        return result.isEmpty() ? "FadCam" : result;
    }
}
