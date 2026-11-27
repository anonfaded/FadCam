package com.fadcam.assets;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.List;

/**
 * Loads downloaded shared libraries into the process, in dependency order.
 *
 * <p>Verified on device (Pixel 7 Pro, API 37, and TECNO KG5k, API 30) with the
 * libraries absent from the APK entirely: {@code System.load(absolutePath)} makes a
 * library resident under its soname, and the library's own internal
 * {@code System.loadLibrary("name")} then resolves to that resident copy. So no
 * patched loader, no reflection and no fork of ffmpeg-kit is needed.
 *
 * <p>Order matters and is not optional:
 * <ol>
 *   <li>{@code libc++_shared.so} first — both OpenCV and ffmpeg need it, and nothing
 *       else in the APK provides it once these libraries leave.</li>
 *   <li>Leaves, then roots, exactly as the dependency graph requires. Loading a root
 *       before its dependencies fails with {@code UnsatisfiedLinkError}.</li>
 * </ol>
 *
 * <p>{@code setReadOnly()} is called before every load. It is not required at
 * {@code targetSdk} 36 (verified: a writable library still loads on API 37), but it
 * is the documented requirement for {@code targetSdk} 37+ and costs one syscall.
 */
public final class NativeLibraryLoader {

    /**
     * Load order per asset. Every name here must appear in the asset's manifest
     * entries; {@link #loadAll} ignores names that are not present so a manifest can
     * ship a subset without breaking the order.
     */
    private static final String SHARED_RUNTIME = "libc++_shared.so";

    private static final String[] FFMPEG_ORDER = {
            SHARED_RUNTIME,
            "libavutil.so",
            "libswresample.so",
            "libswscale.so",
            "libavcodec.so",
            "libavformat.so",
            "libavfilter.so",
            "libavdevice.so",
            "libffmpegkit_abidetect.so",
            "libffmpegkit.so",
    };

    private static final String[] OPENCV_ORDER = {
            SHARED_RUNTIME,
            "libopencv_java5.so",
    };

    private NativeLibraryLoader() {
    }

    /** Dependency order for this asset. Empty for assets with no native libraries. */
    @NonNull
    public static String[] orderFor(@NonNull Asset asset) {
        switch (asset) {
            case FFMPEG:
                return FFMPEG_ORDER.clone();
            case OPENCV:
                return OPENCV_ORDER.clone();
            default:
                return new String[0];
        }
    }

    /**
     * Loads every library of this asset that the manifest declares, in order.
     *
     * <p>A name in {@link #orderFor} that the manifest does not list is skipped, so a
     * manifest can legitimately omit an optional library. A manifest entry that is in
     * order but missing on disk is an error: the asset is installed but incomplete, and
     * proceeding would fail later at an unpredictable call site.
     *
     * @throws AssetException when a listed library is missing, unreadable, or fails to
     *                        load
     */
    public static void loadAll(@NonNull Asset asset, @NonNull AssetStore store,
                               @NonNull List<AssetManifest.Entry> entries) {
        String[] order = orderFor(asset);
        if (order.length == 0) {
            return;
        }
        // Names are matched case-sensitively; the manifest is generated from the
        // archive so the two always agree.
        java.util.Set<String> declared = new java.util.HashSet<>();
        for (AssetManifest.Entry e : entries) {
            if (e.isArchive() && e.members != null) {
                declared.addAll(e.members);
            } else {
                declared.add(e.file);
            }
        }
        for (String name : order) {
            if (!declared.contains(name)) {
                continue;
            }
            File f = store.fileFor(asset, name);
            if (!f.isFile()) {
                throw new AssetException(asset,
                        "missing " + name + " after install; repair the download");
            }
            // Android 17 (targetSdk 37+) refuses to load a native file the app can still
            // write to. Doing this unconditionally keeps that future bump from breaking
            // loading, and is harmless on every current API level.
            if (!f.setReadOnly()) {
                throw new AssetException(asset, "could not make " + name + " read-only");
            }
            try {
                System.load(f.getAbsolutePath());
            } catch (UnsatisfiedLinkError e) {
                throw new AssetException(asset, "could not load " + name + ": "
                        + e.getMessage(), e);
            }
        }
    }
}
