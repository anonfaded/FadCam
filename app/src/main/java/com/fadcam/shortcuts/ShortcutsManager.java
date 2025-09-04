package com.fadcam.shortcuts;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies customization to ShortcutInfoCompat before pinning/requesting dynamic shortcuts.
 */
public class ShortcutsManager {

    public static final String ID_TORCH = "torch_toggle";
    public static final String ID_START = "record_start";
    public static final String ID_STOP = "record_stop";

    private final Context ctx;
    private final ShortcutsPreferences prefs;

    public ShortcutsManager(@NonNull Context context) {
        this.ctx = context.getApplicationContext();
        this.prefs = new ShortcutsPreferences(ctx);
    }

    public ShortcutInfoCompat buildShortcut(
        @NonNull String id,
        @NonNull Intent intent,
        @DrawableRes int defaultIcon,
        @NonNull CharSequence defaultLabel
    ) {
        ShortcutInfoCompat.Builder b = new ShortcutInfoCompat.Builder(ctx, id);
        String customLabel = prefs.getCustomLabel(id);
        b.setShortLabel(customLabel != null ? customLabel : defaultLabel);
        b.setLongLabel(customLabel != null ? customLabel : defaultLabel);
        IconCompat icon = resolveIcon(id, defaultIcon);
        if (icon != null) b.setIcon(icon);
        b.setIntent(intent);
        return b.build();
    }

    /**
     * Build a ShortcutInfoCompat specifically for pinning. If there's customization for the given
     * id, we use a distinct id (id + "_custom") to avoid launchers falling back to static XML.
     */
    public ShortcutInfoCompat buildShortcutForPin(
        @NonNull String id,
        @NonNull Intent intent,
        @DrawableRes int defaultIcon,
        @NonNull CharSequence defaultLabel
    ) {
        boolean hasCustom =
            prefs.getCustomLabel(id) != null ||
            prefs.getCustomIconPath(id) != null;
        String pinId = hasCustom ? (id + "_custom") : id;
        ShortcutInfoCompat.Builder b = new ShortcutInfoCompat.Builder(
            ctx,
            pinId
        );
        String customLabel = prefs.getCustomLabel(id);
        CharSequence label = customLabel != null ? customLabel : defaultLabel;
        b.setShortLabel(label);
        b.setLongLabel(label);
        IconCompat icon = resolveIcon(id, defaultIcon);
        if (icon != null) b.setIcon(icon);
        b.setIntent(intent);
        return b.build();
    }

    @Nullable
    private IconCompat resolveIcon(
        @NonNull String id,
        @DrawableRes int fallback
    ) {
        // Prefer resource id when present to preserve adaptive icon masks and scaling
        int resId = prefs.getCustomIconRes(id);
        if (resId != 0) {
            return IconCompat.createWithResource(ctx, resId);
        }
        String path = prefs.getCustomIconPath(id);
        if (path != null) {
            File f = new File(path);
            if (f.exists()) {
                android.graphics.Bitmap bmp = BitmapFactory.decodeFile(path);
                if (bmp != null) {
                    if (
                        android.os.Build.VERSION.SDK_INT >=
                        android.os.Build.VERSION_CODES.O
                    ) {
                        try {
                            return IconCompat.createWithAdaptiveBitmap(bmp);
                        } catch (Throwable ignored) {}
                    }
                    return IconCompat.createWithBitmap(bmp);
                }
            }
        }
        return IconCompat.createWithResource(ctx, fallback);
    }

    public void reset(@NonNull String id) {
        prefs.reset(id);
    }

    public void setCustomLabel(@NonNull String id, @Nullable String label) {
        prefs.setCustomLabel(id, label);
    }

    public boolean setCustomIconFromUri(
        @NonNull String id,
        @NonNull android.net.Uri uri
    ) {
        return prefs.setCustomIconFromUri(id, uri) != null;
    }

    // -------------- Fix Start for this method(setCustomIconFromBitmap)-----------
    /**
     * Persists a custom icon for the given shortcut from a Bitmap.
     * Returns true if saved successfully.
     */
    public boolean setCustomIconFromBitmap(
        @NonNull String id,
        @NonNull android.graphics.Bitmap bitmap
    ) {
        return prefs.setCustomIconFromBitmap(id, bitmap) != null;
    }

    // -------------- Fix Ended for this method(setCustomIconFromBitmap)-----------

    // -------------- Fix Start for this method(setCustomIconFromResId)-----------
    /**
     * Persists a custom icon for the given shortcut from a drawable resource (including adaptive icons).
     * The drawable is rasterized to a 192x192 bitmap before saving.
     */
    public boolean setCustomIconFromResId(
        @NonNull String id,
        @DrawableRes int resId
    ) {
        try {
            // Store the resource id directly so launcher can apply the correct mask/scale
            prefs.setCustomIconRes(id, resId);
            // Ensure any stale bitmap path is ignored (handled inside prefs as well)
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // -------------- Fix Ended for this method(setCustomIconFromResId)-----------

    public boolean isPinSupported() {
        return ShortcutManagerCompat.isRequestPinShortcutSupported(ctx);
    }

    public void requestPin(@NonNull ShortcutInfoCompat info) {
        // Ensure a dynamic shortcut with this ID exists so the system uses our custom label/icon
        try {
            ShortcutManagerCompat.pushDynamicShortcut(ctx, info);
        } catch (Throwable ignored) {}
        ShortcutManagerCompat.requestPinShortcut(ctx, info, null);
    }

    /**
     * Publishes/replaces dynamic shortcuts so that launcher long-press shows customized entries.
     */
    public void publishAllDynamic() {
        List<ShortcutInfoCompat> list = new ArrayList<>();
        // Torch
        list.add(
            buildShortcut(
                ID_TORCH,
                new Intent(Intent.ACTION_VIEW).setClassName(
                    ctx,
                    "com.fadcam.TorchToggleActivity"
                ),
                com.fadcam.R.drawable.flashlight_shortcut,
                ctx.getString(com.fadcam.R.string.torch_shortcut_short_label)
            )
        );
        // Start
        list.add(
            buildShortcut(
                ID_START,
                new Intent(Intent.ACTION_VIEW).setClassName(
                    ctx,
                    "com.fadcam.RecordingStartActivity"
                ),
                com.fadcam.R.drawable.start_shortcut,
                ctx.getString(com.fadcam.R.string.start_recording)
            )
        );
        // Stop
        list.add(
            buildShortcut(
                ID_STOP,
                new Intent(Intent.ACTION_VIEW).setClassName(
                    ctx,
                    "com.fadcam.RecordingStopActivity"
                ),
                com.fadcam.R.drawable.stop_shortcut,
                ctx.getString(com.fadcam.R.string.stop_recording)
            )
        );
        ShortcutManagerCompat.setDynamicShortcuts(ctx, list);
    }

    /**
     * Refresh dynamic and pinned shortcuts to ensure launcher reflects current
     * labels and icons (useful after app icon or shortcut icon changes).
     *
     * This method performs a best-effort refresh:
     * - republishes dynamic shortcuts (which most launchers update immediately)
     * - attempts to re-request pinned shortcuts so launchers that allow update
     *   via requesting will refresh their icons/labels
     */
    public void refreshShortcuts() {
        try {
            // Republish dynamic shortcuts (ensures updated icons/labels for long-press menus)
            publishAllDynamic();

            // Attempt to refresh pinned shortcuts as a best-effort.
            // Some launchers keep pinned shortcuts tied to the original icon; re-requesting
            // may prompt some launchers to refresh the icon. This will not remove existing
            // shortcuts but tries to update their metadata where supported.
            if (isPinSupported()) {
                List<ShortcutInfoCompat> pinned = new ArrayList<>();

                pinned.add(
                    buildShortcutForPin(
                        ID_TORCH,
                        new Intent(Intent.ACTION_VIEW).setClassName(
                            ctx,
                            "com.fadcam.TorchToggleActivity"
                        ),
                        com.fadcam.R.drawable.flashlight_shortcut,
                        ctx.getString(
                            com.fadcam.R.string.torch_shortcut_short_label
                        )
                    )
                );

                pinned.add(
                    buildShortcutForPin(
                        ID_START,
                        new Intent(Intent.ACTION_VIEW).setClassName(
                            ctx,
                            "com.fadcam.RecordingStartActivity"
                        ),
                        com.fadcam.R.drawable.start_shortcut,
                        ctx.getString(com.fadcam.R.string.start_recording)
                    )
                );

                pinned.add(
                    buildShortcutForPin(
                        ID_STOP,
                        new Intent(Intent.ACTION_VIEW).setClassName(
                            ctx,
                            "com.fadcam.RecordingStopActivity"
                        ),
                        com.fadcam.R.drawable.stop_shortcut,
                        ctx.getString(com.fadcam.R.string.stop_recording)
                    )
                );

                // Do not call requestPinShortcut here — that triggers the launcher "Add to Home" prompt.
                // Instead, attempt to update already-pinned shortcuts via the native ShortcutManager
                // (no user prompt) so launchers that support updates will refresh the icon/label.
                if (
                    android.os.Build.VERSION.SDK_INT >=
                    android.os.Build.VERSION_CODES.N_MR1
                ) {
                    try {
                        android.content.pm.ShortcutManager sm =
                            (android.content.pm.ShortcutManager) ctx.getSystemService(
                                android.content.Context.SHORTCUT_SERVICE
                            );
                        if (sm != null) {
                            List<android.content.pm.ShortcutInfo> nativeList =
                                new ArrayList<>();
                            for (ShortcutInfoCompat sc : pinned) {
                                try {
                                    android.content.pm.ShortcutInfo nativeInfo =
                                        sc.toShortcutInfo();
                                    if (nativeInfo != null) nativeList.add(
                                        nativeInfo
                                    );
                                } catch (Throwable ignored) {
                                    // Ignore conversion issues for individual items
                                }
                            }
                            if (!nativeList.isEmpty()) {
                                try {
                                    sm.updateShortcuts(nativeList);
                                } catch (Throwable ignored) {
                                    // Best-effort only
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                        // Best-effort only
                    }
                }
            }
        } catch (Throwable t) {
            // Non-fatal: shortcut refresh is best-effort only.
        }
    }
}
