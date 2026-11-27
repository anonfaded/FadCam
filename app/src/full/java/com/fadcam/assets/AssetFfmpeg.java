package com.fadcam.assets;

import android.content.Context;

import androidx.annotation.NonNull;

import com.fadcam.FLog;

import java.io.File;
import java.util.List;

/**
 * Gives ffmpeg-kit a runtime loaded from downloadable assets.
 *
 * <p><b>Order is not optional here.</b> {@code FFmpegKitConfig}'s static initialiser calls
 * {@code NativeLoader.loadFFmpeg()} the moment any {@code com.arthenica.ffmpegkit} class is
 * touched, and that loader uses bare {@code System.loadLibrary} names. If the libraries are
 * not already resident under those sonames, the load fails and the bundle never starts.
 *
 * <p>So the absolute-path loads must complete <b>before</b> the first ffmpeg class reference.
 * Every ffmpeg entry point calls {@link #ensureLoaded(Context)} first for exactly that
 * reason; it must not be moved or made lazy.
 *
 * <p>The bridge itself is the dynamic linker's loaded-library list: loading by absolute path
 * makes each library resident under its soname, and ffmpeg-kit's own {@code loadLibrary}
 * then resolves to it. Verified on device with the libraries absent from the APK.
 */
public final class AssetFfmpeg {

    private static final String TAG = "AssetFfmpeg";

    /** Set once loading has succeeded, so repeat calls are free. */
    private static volatile boolean loaded;

    private AssetFfmpeg() {
    }

    /**
     * Makes ffmpeg usable. Idempotent, and safe to call from every entry point.
     *
     * @param context any context; the application context is used
     * @throws AssetException when ffmpeg is neither bundled nor downloaded, or a library
     *                        fails to load
     */
    public static void ensureLoaded(@NonNull Context context) {
        if (loaded) {
            return;
        }
        synchronized (AssetFfmpeg.class) {
            if (loaded) {
                return;
            }
            AssetRegistry registry = AssetRegistry.get(context.getApplicationContext());
            String source;
            if (registry.isBundled(Asset.FFMPEG)) {
                // The APK provides the libraries, so ffmpeg-kit's own loader finds them
                // exactly as it did before this feature existed.
                source = "app package";
            } else {
                List<AssetManifest.Entry> entries = registry.entriesFor(Asset.FFMPEG);
                if (entries == null) {
                    throw new AssetException(Asset.FFMPEG, "no manifest entry for this device");
                }
                if (!registry.store().hasAllFiles(Asset.FFMPEG, entries)) {
                    throw new AssetException(Asset.FFMPEG,
                            "video processing needs a one-time download");
                }
                // Load every .so in dependency order BEFORE any ffmpeg-kit class is touched,
                // because that class's static initialiser asks for the same libraries by bare
                // name and would fail if they were not already resident.
                NativeLibraryLoader.loadAll(Asset.FFMPEG, registry.store(), entries);
                source = "app storage";
            }

            // Only now is it safe to touch the library's own classes.
            String version = com.arthenica.ffmpegkit.FFmpegKitConfig.getVersion();

            loaded = true;
            FLog.i(TAG, "ffmpeg initialised from " + source + ", version=" + version);
        }
    }

    /** True when video processing can start right now without a download. */
    public static boolean isReady(@NonNull Context context) {
        if (loaded) {
            return true;
        }
        AssetRegistry registry = AssetRegistry.get(context.getApplicationContext());
        if (registry.isBundled(Asset.FFMPEG)) {
            return true;
        }
        List<AssetManifest.Entry> entries = registry.entriesFor(Asset.FFMPEG);
        return entries != null && registry.store().hasAllFiles(Asset.FFMPEG, entries);
    }

    /** Bytes ffmpeg occupies on device, for the download manager. */
    public static long installedSize(@NonNull Context context) {
        AssetRegistry registry = AssetRegistry.get(context.getApplicationContext());
        List<AssetManifest.Entry> entries = registry.entriesFor(Asset.FFMPEG);
        return entries == null ? 0L : registry.store().installedSize(Asset.FFMPEG, entries);
    }

    /**
     * Test-only: lets a test re-run the load path in one process. The native libraries
     * cannot actually be unloaded.
     */
    static void resetLoadedForTesting() {
        loaded = false;
    }
}
