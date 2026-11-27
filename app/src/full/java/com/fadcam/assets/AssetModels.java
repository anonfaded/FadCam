package com.fadcam.assets;

import android.content.Context;
import android.content.res.AssetManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/**
 * Resolves the RF-DETR graph files, however they happen to be available.
 *
 * <p>{@code CompiledModel.create} accepts either an {@link AssetManager} path or an
 * absolute file path, so a downloaded graph is used directly and a bundled one is copied
 * out of the APK once. That keeps a single code path for the detector regardless of where
 * the model came from, and means nothing changes when the graphs leave the APK.
 *
 * <p>File names come from {@link AssetManifest}, never from a constant here. The published
 * names carry a version ({@code rfdetr-nano-v1.0-graph-a.tflite}); hardcoding one in the
 * detector meant a re-versioned model silently failed to resolve.
 */
public final class AssetModels {

    private static final String TAG = "AssetModels";
    /** Where a bundled graph sits inside the APK, for builds that still ship them. */
    private static final String APK_ASSET_DIR = "models/";

    /** Graph A: the backbone pass. */
    public static final int GRAPH_A = 0;
    /** Graph B: the detection head. */
    public static final int GRAPH_B = 1;

    /**
     * Marks which graph each bundled file is. The manifest's own names are used when a
     * model is installed; this only matters for the copy-out path on a build that still
     * packages the graphs.
     */
    private static final String[] APK_GRAPH_FILE = {
            "rfdetr_nano_graph_a.tflite",
            "rfdetr_nano_graph_b.tflite",
    };

    private static final String[] ROLE_SUFFIX = {
            "graph-a.tflite",
            "graph-b.tflite",
    };

    private AssetModels() {
    }

    /**
     * Absolute path to a detection graph, or null when it is neither installed nor
     * bundled.
     *
     * @param role {@link #GRAPH_A} or {@link #GRAPH_B}
     */
    @Nullable
    public static File graphFile(@NonNull Context context, int role) {
        AssetRegistry registry = AssetRegistry.get(context);
        AssetStore store = registry.store();

        // 1. Installed: the manifest is the authority on the file name.
        AssetManifest.Entry entry = entryFor(registry, role);
        if (entry != null) {
            File installed = store.fileFor(Asset.MODELS, entry.file);
            if (store.isPresent(installed)) {
                return installed;
            }
        }

        // 2. Not installed. If this build still packages the graphs, copy one out once.
        String assetPath = APK_ASSET_DIR + APK_GRAPH_FILE[role];
        if (!assetExists(context, assetPath)) {
            return null;
        }
        // Store it under the manifest's name when there is one, so the installed and
        // bundled paths agree and a later download is found by the same lookup.
        String storedName = entry != null ? entry.file : APK_GRAPH_FILE[role];
        File target = store.fileFor(Asset.MODELS, storedName);
        try {
            //noinspection ResultOfMethodCallIgnored
            store.dirFor(Asset.MODELS).mkdirs();
            copyAsset(context.getAssets(), assetPath, target);
            FLog.i(TAG, "copied bundled " + assetPath + " to app storage ("
                    + target.length() + " bytes)");
            return target;
        } catch (Exception e) {
            FLog.e(TAG, "could not copy bundled " + assetPath, e);
            return null;
        }
    }

    /** The manifest entry for one graph role, or null when the manifest has no models. */
    @Nullable
    private static AssetManifest.Entry entryFor(@NonNull AssetRegistry registry, int role) {
        List<AssetManifest.Entry> entries = registry.entriesFor(Asset.MODELS);
        if (entries == null) {
            return null;
        }
        String suffix = ROLE_SUFFIX[role];
        for (AssetManifest.Entry e : entries) {
            if (e.file.toLowerCase(Locale.US).endsWith(suffix)) {
                return e;
            }
        }
        return null;
    }

    /** True when both detection graphs are available, bundled or downloaded. */
    public static boolean modelsAvailable(@NonNull Context context) {
        return graphFile(context, GRAPH_A) != null && graphFile(context, GRAPH_B) != null;
    }

    /** Total bytes the models occupy on device, for the download manager UI. */
    public static long installedSize(@NonNull Context context) {
        AssetRegistry registry = AssetRegistry.get(context);
        List<AssetManifest.Entry> entries = registry.entriesFor(Asset.MODELS);
        return entries == null ? 0L : registry.store().installedSize(Asset.MODELS, entries);
    }

    private static boolean assetExists(@NonNull Context context, @NonNull String path) {
        try (InputStream in = context.getAssets().open(path)) {
            return in.read() >= 0;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static void copyAsset(@NonNull AssetManager assets, @NonNull String path,
                                  @NonNull File target) throws java.io.IOException {
        try (InputStream in = assets.open(path);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[1 << 16];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            out.getFD().sync();
        }
    }
}
