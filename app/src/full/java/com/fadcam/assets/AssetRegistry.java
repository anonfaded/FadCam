package com.fadcam.assets;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;

import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for which heavy assets are available.
 *
 * <p>Every call site asks this class; nothing inspects files directly and nothing
 * caches a boolean. State is recomputed on each read, so a file deleted by the user
 * or an OS cleaner becomes {@link AssetState#MISSING} on the next read instead of
 * leaving a stale "Ready" row on screen.
 *
 * <p>Resolution order, matching the migration plan:
 * <ol>
 *   <li>already loaded in this process → usable</li>
 *   <li>installed in app storage and intact → {@link AssetState#READY}</li>
 *   <li>present inside the APK → {@link AssetState#BUNDLED}, so old builds and the
 *       transition release keep working with zero downloads</li>
 *   <li>otherwise {@link AssetState#MISSING}</li>
 * </ol>
 *
 * <p>Full builds only.
 */
public final class AssetRegistry {

    private static final String TAG = "AssetRegistry";
    private static final String MANIFEST_ASSET = "assets-manifest.json";
    /** Guard against reading a corrupt or hostile manifest file. */
    private static final int MAX_MANIFEST_BYTES = 128 * 1024;

    private static volatile AssetRegistry instance;

    private final Context appContext;
    private final AssetStore store;
    private final AssetManifest manifest;
    private final Map<Asset, Long> downloadingSince = new HashMap<>();

    /** Assets whose libraries this process has already loaded. */
    private final java.util.Set<Asset> loaded =
            java.util.Collections.synchronizedSet(new java.util.HashSet<Asset>());

    /** Per-asset cache of "is it in the APK". The APK cannot change while we run. */
    private final Map<Asset, Boolean> bundledCache = new HashMap<>();
    /** Library names inside the installed APK, read once. */
    @Nullable
    private volatile java.util.Set<String> bundledLibraries;

    private AssetRegistry(@NonNull Context context, @NonNull AssetStore store,
                          @Nullable AssetManifest manifest) {
        this.appContext = context;
        this.store = store;
        this.manifest = manifest;
        this.store.clearTmp();
    }

    /** Returns the singleton, creating it on first use. */
    @NonNull
    public static AssetRegistry get(@NonNull Context context) {
        AssetRegistry local = instance;
        if (local == null) {
            synchronized (AssetRegistry.class) {
                local = instance;
                if (local == null) {
                    Context app = context.getApplicationContext();
                    local = new AssetRegistry(app, new AssetStore(app.getFilesDir()),
                            loadManifest(app));
                    instance = local;
                }
            }
        }
        return local;
    }

    private static AssetManifest loadManifest(@NonNull Context context) {
        try (InputStream in = context.getAssets().open(MANIFEST_ASSET)) {
            byte[] buf = new byte[MAX_MANIFEST_BYTES];
            int read = 0;
            while (read < buf.length) {
                int n = in.read(buf, read, buf.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return AssetManifest.parse(new String(buf, 0, read,
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            // A missing or malformed manifest is a build error, not a runtime one. It is
            // logged loudly and produces MISSING state so every feature shows a banner
            // rather than crashing on an unhelpful null.
            FLog.e(TAG, "could not read " + MANIFEST_ASSET, e);
            return null;
        }
    }

    /** The bundled manifest, or null when it could not be read. */
    @Nullable
    public AssetManifest manifest() {
        return manifest;
    }

    @NonNull
    public AssetStore store() {
        return store;
    }

    // ----------------------------------------------------------------- state

    /** Live state of this asset right now. */
    @NonNull
    public AssetState state(@NonNull Asset asset) {
        if (loaded.contains(asset)) {
            return AssetState.READY;
        }
        synchronized (downloadingSince) {
            if (downloadingSince.containsKey(asset)) {
                return AssetState.DOWNLOADING;
            }
        }
        if (isBundled(asset)) {
            return AssetState.BUNDLED;
        }
        List<AssetManifest.Entry> entries = entriesFor(asset);
        if (entries == null) {
            return AssetState.MISSING;
        }
        if (store.hasAllFiles(asset, entries)) {
            return AssetState.READY;
        }
        // Distinguish "never installed" from "was installed and is now damaged": a
        // leftover directory means a partial install, which is worth repairing rather
        // than downloading blind.
        File dir = store.dirFor(asset);
        File[] kids = dir.listFiles();
        if (kids != null && kids.length > 0) {
            return AssetState.CORRUPT;
        }
        return AssetState.MISSING;
    }

    /** True when this asset's libraries already ship inside the installed APK. */
    public boolean isBundled(@NonNull Asset asset) {
        String[] order = NativeLibraryLoader.orderFor(asset);
        if (order.length == 0) {
            // Models are data, not native libraries: BUNDLED means the graphs are in
            // the APK's assets, which is how the current builds ship them.
            return modelGraphsBundled();
        }
        // The APK is packaged with useLegacyPackaging = false, so shared libraries are
        // NOT extracted to a lib/ directory on disk — they stay inside the APK and load
        // straight from it. Checked by looking inside the APK, not at nativeLibraryDir,
        // which is empty on those builds.
        Boolean cached = bundledCache.get(asset);
        if (cached != null) {
            return cached;
        }
        boolean present = apkBundledLibraries().contains(order[order.length - 1]);
        bundledCache.put(asset, present);
        return present;
    }

    /**
     * Library names the installed APK provides, read from the APK's own {@code lib/}
     * entries. Cached for the process lifetime: the APK cannot change while this
     * process is alive.
     */
    private java.util.Set<String> apkBundledLibraries() {
        java.util.Set<String> names = bundledLibraries;
        if (names != null) {
            return names;
        }
        names = new java.util.HashSet<>();
        String abi = Build.SUPPORTED_ABIS[0];
        String prefix = "lib/" + abi + "/";
        try (java.util.zip.ZipFile apk =
                     new java.util.zip.ZipFile(appContext.getApplicationInfo().sourceDir)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> e = apk.entries();
            while (e.hasMoreElements()) {
                String name = e.nextElement().getName();
                if (name.startsWith(prefix) && name.endsWith(".so")) {
                    names.add(name.substring(prefix.length()));
                }
            }
        } catch (java.io.IOException e) {
            FLog.w(TAG, "could not read APK for bundled libraries: " + e.getMessage());
        }
        bundledLibraries = names;
        return names;
    }

    /** True when the detection graphs are still shipped in the APK's assets. */
    private boolean modelGraphsBundled() {
        Boolean cached = bundledCache.get(Asset.MODELS);
        if (cached != null) {
            return cached;
        }
        boolean present;
        try (InputStream in = appContext.getAssets().open("models/rfdetr_nano_graph_a.tflite")) {
            present = in.read() >= 0;
        } catch (java.io.IOException e) {
            present = false;
        }
        bundledCache.put(Asset.MODELS, present);
        return present;
    }

    /** Resolved manifest entries for this device's best ABI, or null when unavailable. */
    @Nullable
    public List<AssetManifest.Entry> entriesFor(@NonNull Asset asset) {
        if (manifest == null) {
            return null;
        }
        return manifest.resolve(asset, ABIS);
    }

    /** Installed size in bytes for this device, or 0 when the manifest does not say. */
    public long installedSize(@NonNull Asset asset) {
        return manifest == null ? 0L : manifest.totalInstalledSize(entriesFor(asset));
    }

    /** Download size in bytes for this device, or 0 when unknown. */
    public long downloadSize(@NonNull Asset asset) {
        return manifest == null ? 0L : manifest.totalSize(entriesFor(asset));
    }

    /** ABI preference order, highest-ranked first, from the platform. */
    static final AssetManifest.AbiRank ABIS = new AssetManifest.AbiRank() {
        @Override
        public int rankOf(String abi) {
            if ("universal".equals(abi)) {
                return Integer.MAX_VALUE - 1;
            }
            String[] supported = Build.SUPPORTED_ABIS;
            for (int i = 0; i < supported.length; i++) {
                if (supported[i].equals(abi)) {
                    return i;
                }
            }
            return -1;
        }
    };

    // ---------------------------------------------------------------- loading

    /**
     * Makes this asset usable, loading native libraries if needed.
     *
     * <p>Idempotent. Safe to call on every feature start.
     *
     * @throws AssetException when the asset is not installed, or a library fails to load
     */
    public synchronized void require(@NonNull Asset asset) {
        if (loaded.contains(asset)) {
            return;
        }
        if (isBundled(asset)) {
            // The APK provides it. Native libraries are loaded by their own class
            // static initialisers in that case, exactly as before this feature existed.
            loaded.add(asset);
            return;
        }
        AssetState state = state(asset);
        if (state != AssetState.READY) {
            throw new AssetException(asset, "not installed: " + state);
        }
        List<AssetManifest.Entry> entries = entriesFor(asset);
        if (entries == null) {
            throw new AssetException(asset, "no manifest entry for this device");
        }
        NativeLibraryLoader.loadAll(asset, store, entries);
        loaded.add(asset);
        FLog.i(TAG, "loaded " + asset.id + " from app storage");
    }

    /** True when {@link #require} can succeed right now without a download. */
    public boolean isUsable(@NonNull Asset asset) {
        return loaded.contains(asset) || state(asset).isUsable();
    }

    /** Records that a download started, so state reports DOWNLOADING. */
    public void onDownloadStarted(@NonNull Asset asset) {
        synchronized (downloadingSince) {
            downloadingSince.put(asset, System.currentTimeMillis());
        }
    }

    /** Records that a download finished, successfully or not. */
    public void onDownloadFinished(@NonNull Asset asset) {
        synchronized (downloadingSince) {
            downloadingSince.remove(asset);
        }
    }

    /**
     * Announces that this asset's files changed outside the download service, so any listener
     * can re-read state. Used by the manual importer.
     *
     * <p>State is never cached, so this is only a nudge for the UI; correctness does not
     * depend on it.
     */
    public void notifyAssetChanged(@NonNull Asset asset) {
        FLog.i(TAG, "asset changed: " + asset.id);
    }

    /** Deletes this asset's files. The feature falls back to MISSING on next read. */
    public void delete(@NonNull Asset asset) {
        loaded.remove(asset);
        store.deleteAsset(asset);
        FLog.i(TAG, "deleted " + asset.id);
    }

    /**
     * Forgets in-memory loaded state for every asset.
     *
     * <p>Test-only. Libraries cannot actually be unloaded from a process, so this exists
     * so instrumented tests can exercise the state machine across cases in one process.
     */
    void resetLoadedForTesting() {
        loaded.clear();
    }

    /** Replaces the singleton for tests. Test-only. */
    static void setInstanceForTesting(@Nullable AssetRegistry registry) {
        instance = registry;
    }
}
