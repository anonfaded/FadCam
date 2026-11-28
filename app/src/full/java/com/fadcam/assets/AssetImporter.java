package com.fadcam.assets;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/**
 * Installs an asset the user supplied themselves, instead of downloading it.
 *
 * <p>Motivation: someone on a metered connection, offline, or with a mirror can put the same
 * file on the device and have it accepted. Nothing is trusted: the file is hashed and must
 * match the manifest entry bundled in the APK byte for byte. A file that merely has the right
 * name is rejected.
 *
 * <p>A single entry point takes any supported file and works out which asset it belongs to by
 * matching the hash, so the user never has to know that OpenCV ships as a zip and the models
 * ship raw.
 */
public final class AssetImporter {

    private static final String TAG = "AssetImporter";
    /** Guard against copying an enormous file that cannot be a real asset. */
    private static final long MAX_IMPORT_BYTES = 512L * 1024 * 1024;

    /** What happened, for the UI to render a precise message. */
    public enum Outcome {
        /** Installed successfully. */
        INSTALLED,
        /** The bytes match a manifest entry, but not one for this device's ABI. */
        WRONG_ABI,
        /** Nothing in the manifest has these bytes. */
        NO_MATCH,
        /** The hash matches but installing failed. */
        INSTALL_FAILED,
        /** Could not read the selected file. */
        UNREADABLE,
        /** File is implausibly large. */
        TOO_LARGE
    }

    /** Result of an import attempt. */
    public static final class Result {
        @NonNull
        public final Outcome outcome;
        /** The asset that was installed, or the one whose ABI did not match. */
        @Nullable
        public final Asset asset;
        /** The ABI of the file the user supplied, when it was recognised. */
        @Nullable
        public final String detectedAbi;
        /** Extra detail for logs and the failure message. */
        @Nullable
        public final String detail;

        Result(@NonNull Outcome outcome, @Nullable Asset asset, @Nullable String detectedAbi,
               @Nullable String detail) {
            this.outcome = outcome;
            this.asset = asset;
            this.detectedAbi = detectedAbi;
            this.detail = detail;
        }
    }

    private AssetImporter() {
    }

    /**
     * Hashes the selected file and installs whichever manifest entry it matches.
     *
     * <p>Runs on the calling thread and does file IO; call it from a background thread.
     */
    @NonNull
    public static Result importFrom(@NonNull Context context, @NonNull Uri source) {
        AssetRegistry registry = AssetRegistry.get(context.getApplicationContext());
        AssetManifest manifest = registry.manifest();
        if (manifest == null) {
            return new Result(Outcome.NO_MATCH, null, null, "manifest missing from the app");
        }
        AssetStore store = registry.store();

        // Copy into the scratch directory first: a content:// Uri may be a stream we can only
        // read once, and the installer needs to hash then move it.
        File staged = new File(store.tmpDir(), "import.staged");
        try {
            long copied = copy(context, source, staged);
            if (copied <= 0L) {
                return new Result(Outcome.UNREADABLE, null, null, "empty or unreadable file");
            }
            if (copied > MAX_IMPORT_BYTES) {
                return new Result(Outcome.TOO_LARGE, null, null, copied + " bytes");
            }

            String hash = AssetStore.sha256(staged);
            FLog.i(TAG, "imported file " + copied + " bytes sha256=" + hash);

            // Identify by hash, across every asset and every ABI. This is what lets one entry
            // point accept a zip or a bare model without the user choosing.
            AssetManifest.Entry byHash = null;
            Asset owner = null;
            for (Asset asset : Asset.values()) {
                for (AssetManifest.Entry e : manifest.entries(asset)) {
                    if (e.sha256.equalsIgnoreCase(hash) && e.size == copied) {
                        byHash = e;
                        owner = asset;
                        break;
                    }
                }
                if (byHash != null) {
                    break;
                }
            }
            if (byHash == null) {
                // Not a match for this device's manifest. Say whether the bytes are a real
                // release built for another ABI, which is a much more useful message than
                // "unknown file".
                return new Result(Outcome.NO_MATCH, null, null,
                        "no manifest entry matches sha256 " + hash
                                + " (" + copied + " bytes)");
            }

            // Hash matched, so the file is genuine. Now check it is the build this device can
            // actually run: an arm64 phone must not install an armeabi-v7a bundle.
            String deviceAbi = android.os.Build.SUPPORTED_ABIS[0];
            boolean abiOk = "universal".equals(byHash.abi) || byHash.abi.equals(deviceAbi);
            if (!abiOk) {
                return new Result(Outcome.WRONG_ABI, owner, byHash.abi,
                        "file is " + byHash.abi + ", device is " + deviceAbi);
            }

            // Also refuse a genuine file that is not the ABI the manifest picked for us, so the
            // installed state can never disagree with what entriesFor() resolves.
            List<AssetManifest.Entry> resolved = registry.entriesFor(owner);
            boolean isTheChosenOne = false;
            if (resolved != null) {
                for (AssetManifest.Entry e : resolved) {
                    if (e.file.equals(byHash.file)) {
                        isTheChosenOne = true;
                        break;
                    }
                }
            }
            if (!isTheChosenOne) {
                return new Result(Outcome.WRONG_ABI, owner, byHash.abi,
                        "file is " + byHash.abi + ", device is " + deviceAbi);
            }

            // Install the whole set of that asset. A single-file asset is a set of one; the
            // models are two, so importing only one graph leaves the asset incomplete and the
            // registry will honestly report that.
            AssetInstaller.install(owner, store, byHash, staged);
            registry.notifyAssetChanged(owner);
            return new Result(Outcome.INSTALLED, owner, byHash.abi, byHash.file);
        } catch (AssetException e) {
            return new Result(Outcome.INSTALL_FAILED, e.asset(), null, e.getMessage());
        } catch (Exception e) {
            FLog.e(TAG, "import failed", e);
            return new Result(Outcome.UNREADABLE, null, null, e.getMessage());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            staged.delete();
        }
    }

    /**
     * Copies the selected document into the scratch directory.
     *
     * @return bytes copied, or -1 when it is too large to be an asset
     */
    private static long copy(@NonNull Context context, @NonNull Uri source, @NonNull File target)
            throws java.io.IOException {
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            if (in == null) {
                return 0L;
            }
            long total = 0L;
            try (FileOutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[1 << 16];
                int read;
                while ((read = in.read(buf)) != -1) {
                    total += read;
                    if (total > MAX_IMPORT_BYTES) {
                        return -1L;
                    }
                    out.write(buf, 0, read);
                }
                out.getFD().sync();
            }
            return total;
        }
    }

    /**
     * Human-readable name of what the user should obtain, for the guidance text.
     * Example: {@code opencv-5.1.0-arm64-v8a.zip}.
     */
    @NonNull
    public static String expectedFileName(@NonNull Context context, @NonNull Asset asset) {
        AssetRegistry registry = AssetRegistry.get(context.getApplicationContext());
        List<AssetManifest.Entry> entries = registry.entriesFor(asset);
        if (entries == null || entries.isEmpty()) {
            return asset.title;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(entries.get(i).file);
        }
        return sb.toString();
    }

    /** Where the published files come from, shown to the user. */
    @NonNull
    public static String publishedUrl(@NonNull Context context) {
        AssetRegistry registry = AssetRegistry.get(context.getApplicationContext());
        AssetManifest m = registry.manifest();
        if (m == null) {
            return "https://github.com/fadsec-lab/fadcam-assets";
        }
        // Strip the tag path so the link opens the human-readable repo, not a raw download.
        String base = m.baseUrl();
        int idx = base.indexOf("/releases/");
        return idx > 0 ? base.substring(0, idx) : base;
    }

    /** Size in bytes of the file the user needs for this asset. */
    public static long expectedSize(@NonNull Context context, @NonNull Asset asset) {
        return AssetRegistry.get(context.getApplicationContext()).downloadSize(asset);
    }

    /** Formats a byte count for display. */
    @NonNull
    public static String mb(long bytes) {
        return String.format(Locale.US, "%.1f", bytes / 1048576.0);
    }
}
