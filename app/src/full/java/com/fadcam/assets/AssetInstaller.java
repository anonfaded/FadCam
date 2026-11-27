package com.fadcam.assets;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Turns a verified download into installed files.
 *
 * <p>Two shapes, because they compress very differently:
 * <ul>
 *   <li><b>Archive</b> (OpenCV, ffmpeg): 50-63% smaller when zipped, so the download
 *       is a zip. It is unpacked flat into the asset directory.</li>
 *   <li><b>Raw</b> (models): only ~9.5% smaller zipped, so the downloaded file is the
 *       artifact and is simply moved into place.</li>
 * </ul>
 *
 * <p>Order is deliberate: hash the download, unpack, then write the marker
 * <b>last</b>. A crash at any point leaves the asset looking uninstalled rather than
 * installed-but-broken, which is what lets {@link AssetRegistry} report CORRUPT and
 * offer a repair instead of handing a half-written library to the linker.
 */
public final class AssetInstaller {

    private static final String TAG = "AssetInstaller";

    private AssetInstaller() {
    }

    /**
     * Verifies and installs a completed download.
     *
     * @param asset      the asset being installed
     * @param store      where files live
     * @param entry      the manifest entry that was downloaded
     * @param downloaded the completed file, in the scratch directory
     * @throws AssetException when the hash does not match or unpacking fails
     */
    public static void install(@NonNull Asset asset, @NonNull AssetStore store,
                               @NonNull AssetManifest.Entry entry, @NonNull File downloaded) {
        try {
            // Verify before touching the install directory: never write unverified bytes.
            String actual = AssetStore.sha256(downloaded);
            if (!actual.equalsIgnoreCase(entry.sha256)) {
                //noinspection ResultOfMethodCallIgnored
                downloaded.delete();
                throw new AssetException(asset, "checksum mismatch: expected "
                        + entry.sha256 + " but got " + actual);
            }
            if (entry.size > 0 && downloaded.length() != entry.size) {
                //noinspection ResultOfMethodCallIgnored
                downloaded.delete();
                throw new AssetException(asset, "size mismatch: expected " + entry.size
                        + " but got " + downloaded.length());
            }

            File dir = store.dirFor(asset);
            // Clear any previous attempt so a repair cannot leave stale siblings behind.
            deleteContents(dir);

            if (entry.isArchive()) {
                unpackFlat(downloaded, dir, entry, asset);
            } else {
                File target = store.fileFor(asset, entry.file);
                if (!downloaded.renameTo(target)) {
                    copyFile(downloaded, target);
                }
            }

            // Marker last: its presence is what makes hasAllFiles() report success.
            AssetStore.writeTextAtomically(store.markerFor(asset, entry), entry.sha256);
            FLog.i(TAG, "installed " + asset.id + " (" + entry.file + ")");
        } catch (AssetException e) {
            throw e;
        } catch (Exception e) {
            throw new AssetException(asset, "could not install: " + e.getMessage(), e);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            downloaded.delete();
        }
    }

    /**
     * Unpacks a zip into {@code dir}, flat.
     *
     * <p>Entry names are reduced to their base name so a crafted archive cannot escape
     * the directory, and directories inside the archive are skipped — the published
     * bundles are flat by design.
     */
    private static void unpackFlat(@NonNull File zip, @NonNull File dir,
                                   @NonNull AssetManifest.Entry entry,
                                   @NonNull Asset asset) throws IOException {
        List<String> expected = entry.members;
        int written = 0;
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                String name = new File(e.getName()).getName();
                if (name.isEmpty() || name.startsWith(".")) {
                    continue;
                }
                if (expected != null && !expected.contains(name)) {
                    // Not part of the declared set: skip rather than write something the
                    // manifest does not account for.
                    continue;
                }
                File out = new File(dir, name);
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[1 << 16];
                    int read;
                    while ((read = in.read(buf)) != -1) {
                        fos.write(buf, 0, read);
                    }
                }
                written++;
            }
        }
        if (expected != null && written < expected.size()) {
            throw new AssetException(asset, "archive was missing files: wrote " + written
                    + " of " + expected.size());
        }
    }

    private static void copyFile(@NonNull File from, @NonNull File to) throws IOException {
        File parent = to.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (InputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[1 << 16];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
        }
    }

    private static void deleteContents(@Nullable File dir) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File kid : kids) {
            if (kid.isDirectory()) {
                deleteContents(kid);
            }
            //noinspection ResultOfMethodCallIgnored
            kid.delete();
        }
    }
}
