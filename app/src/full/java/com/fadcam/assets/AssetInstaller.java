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
     * Verifies and installs a completed download of one archive or file.
     *
     * <p>Kept for single-file assets. Use {@link #installAll} for assets made of several
     * entries, because this clears the asset directory and would delete the entries
     * already installed.
     *
     * @throws AssetException when the hash does not match or unpacking fails
     */
    public static void install(@NonNull Asset asset, @NonNull AssetStore store,
                               @NonNull AssetManifest.Entry entry, @NonNull File downloaded) {
        installAll(asset, store,
                java.util.Collections.singletonList(entry),
                java.util.Collections.singletonList(downloaded));
    }

    /**
     * Verifies and installs a whole asset: every entry, in one pass.
     *
     * <p>All downloads are verified <b>before</b> the install directory is touched, so a
     * bad second file cannot destroy a working first one. The directory is cleared once,
     * at the start, rather than per entry — clearing per entry deleted the files installed
     * by the previous iteration, which broke multi-file assets such as the two-graph model
     * set.
     *
     * @param downloaded one file per entry, same order as {@code entries}
     * @throws AssetException when any hash does not match or unpacking fails
     */
    public static void installAll(@NonNull Asset asset, @NonNull AssetStore store,
                                  @NonNull List<AssetManifest.Entry> entries,
                                  @NonNull List<File> downloaded) {
        if (entries.size() != downloaded.size()) {
            throw new AssetException(asset, "install called with " + entries.size()
                    + " entries but " + downloaded.size() + " files");
        }
        try {
            // Verify everything first. Never write unverified bytes into the asset dir.
            for (int i = 0; i < entries.size(); i++) {
                AssetManifest.Entry entry = entries.get(i);
                File file = downloaded.get(i);
                String actual = AssetStore.sha256(file);
                if (!actual.equalsIgnoreCase(entry.sha256)) {
                    throw new AssetException(asset, "checksum mismatch for " + entry.file
                            + ": expected " + entry.sha256 + " but got " + actual);
                }
                if (entry.size > 0 && file.length() != entry.size) {
                    throw new AssetException(asset, "size mismatch for " + entry.file
                            + ": expected " + entry.size + " but got " + file.length());
                }
            }

            File dir = store.dirFor(asset);
            // Clear any previous attempt exactly once, so a repair cannot leave stale
            // siblings behind and a multi-entry asset is not cleared mid-install.
            deleteContents(dir);

            for (int i = 0; i < entries.size(); i++) {
                AssetManifest.Entry entry = entries.get(i);
                File source = downloaded.get(i);
                if (entry.isArchive()) {
                    unpackFlat(source, dir, entry, asset);
                } else {
                    File target = store.fileFor(asset, entry.file);
                    if (!source.renameTo(target)) {
                        copyFile(source, target);
                    }
                }
            }

            // Markers last: their presence is what makes hasAllFiles() report success, so a
            // crash before this point leaves the asset looking uninstalled rather than
            // installed-but-broken.
            for (AssetManifest.Entry entry : entries) {
                AssetStore.writeTextAtomically(store.markerFor(asset, entry), entry.sha256);
            }
            FLog.i(TAG, "installed " + asset.id + " (" + entries.size() + " file(s))");
        } catch (AssetException e) {
            throw e;
        } catch (Exception e) {
            throw new AssetException(asset, "could not install: " + e.getMessage(), e);
        } finally {
            for (File file : downloaded) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
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
