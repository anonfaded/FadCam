package com.fadcam;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * Seam between shared (main) code and Full-only features that live in src/full/
 * (Faditor, ffmpeg, motion detection). main code never references Full-only classes
 * at compile time — presence is discovered reflectively at runtime, so Lite variants
 * (which do not compile src/full/) simply report the feature as unavailable.
 * Full variants behave exactly as before; Lite callers decide their own UX.
 */
public final class FeatureRegistry {

    private FeatureRegistry() {
    }

    public static boolean hasFaditor() {
        return classExists("com.fadcam.ui.FaditorMiniFragment");
    }

    @Nullable
    public static Fragment createFaditorFragment() {
        try {
            return (Fragment) Class.forName("com.fadcam.ui.FaditorMiniFragment")
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Starts Faditor's editor on the given video. Returns false when unavailable (Lite). */
    public static boolean launchFaditorEditor(Context ctx, Uri videoUri) {
        try {
            Class<?> editorClass = Class.forName("com.fadcam.ui.faditor.FaditorEditorActivity");
            Intent intent = new Intent(ctx, editorClass);
            intent.setData(videoUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(intent);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
