package com.fadcam.assets;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;

import java.io.File;
import java.util.List;

/**
 * Gives the OpenCV detector a runtime loaded from downloadable assets.
 *
 * <p>OpenCV's own entry point is {@code OpenCVLoader.initLocal()}, which calls
 * {@code System.loadLibrary("opencv_java5")} with a bare name. That cannot find a file in
 * app storage on its own.
 *
 * <p>The bridge is the dynamic linker's loaded-library list: calling
 * {@code System.load(absolutePath)} makes the library resident under its soname, and
 * {@code initLocal()} then resolves to that resident copy. Verified on device with OpenCV
 * absent from the APK entirely, so nothing could be satisfied from a bundled copy.
 */
public final class AssetOpenCv {

    private static final String TAG = "AssetOpenCv";

    private AssetOpenCv() {
    }

    /**
     * Ensures the OpenCV runtime is available and initialised.
     *
     * <p>Idempotent: the registry remembers what it has loaded, and
     * {@code initLocal()} is cheap to repeat.
     *
     * @throws AssetException when OpenCV is neither bundled nor downloaded, or a library
     *                        fails to load
     */
    public static void ensureLoaded(@NonNull Context context) {
        AssetRegistry registry = AssetRegistry.get(context);
        if (registry.isBundled(Asset.OPENCV)) {
            // The APK provides the libraries, so OpenCV's own loader finds them exactly as
            // it did before this feature existed.
            if (!org.opencv.android.OpenCVLoader.initLocal()) {
                throw new AssetException(Asset.OPENCV,
                        "OpenCV runtime shipped in the app could not initialise");
            }
            return;
        }

        List<AssetManifest.Entry> entries = registry.entriesFor(Asset.OPENCV);
        if (entries == null) {
            throw new AssetException(Asset.OPENCV, "no manifest entry for this device");
        }
        if (!registry.store().hasAllFiles(Asset.OPENCV, entries)) {
            throw new AssetException(Asset.OPENCV, "motion detection needs a one-time download");
        }

        // Load by absolute path first. This makes libc++_shared and libopencv_java5 resident
        // under their sonames.
        NativeLibraryLoader.loadAll(Asset.OPENCV, registry.store(), entries);

        // Then let OpenCV run its own init, which now resolves to the resident copies and
        // does the version bookkeeping initLocal() normally does.
        if (!org.opencv.android.OpenCVLoader.initLocal()) {
            throw new AssetException(Asset.OPENCV,
                    "OpenCV loaded from storage but failed to initialise");
        }
        FLog.i(TAG, "OpenCV initialised from app storage ("
                + org.opencv.core.Core.VERSION + ")");
    }

    /** True when motion detection can start right now without a download. */
    public static boolean isReady(@NonNull Context context) {
        AssetRegistry registry = AssetRegistry.get(context);
        if (registry.isBundled(Asset.OPENCV)) {
            return true;
        }
        List<AssetManifest.Entry> entries = registry.entriesFor(Asset.OPENCV);
        return entries != null && registry.store().hasAllFiles(Asset.OPENCV, entries);
    }

    /** Bytes OpenCV occupies on device, for the download manager. */
    public static long installedSize(@NonNull Context context) {
        AssetRegistry registry = AssetRegistry.get(context);
        List<AssetManifest.Entry> entries = registry.entriesFor(Asset.OPENCV);
        return entries == null ? 0L : registry.store().installedSize(Asset.OPENCV, entries);
    }
}
