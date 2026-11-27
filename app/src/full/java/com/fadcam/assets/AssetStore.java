package com.fadcam.assets;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

/**
 * Owns the on-disk layout for downloaded assets. Everything else asks this class for
 * paths; nothing hardcodes {@code /data/data} or {@code filesDir} outside here.
 *
 * <pre>
 * filesDir/assets/
 *     manifest.json          which asset set is installed
 *     opencv/1.0/libopencv_java5.so
 *     ffmpeg/1.0/*.so
 *     models/1.0/rfdetr_nano_graph_a.tflite
 *     .tmp/                  partial downloads, cleared on start
 * </pre>
 *
 * <p>The version is part of the path so an upgrade never half-overwrites a working
 * library: the new version downloads alongside, then the manifest pointer swaps.
 */
public final class AssetStore {

    private static final String ROOT = "assets";
    private static final String TMP = ".tmp";

    private final File root;

    public AssetStore(@NonNull File filesDir) {
        this.root = new File(filesDir, ROOT);
    }

    /** Root directory holding every asset set. */
    @NonNull
    public File root() {
        return root;
    }

    /** Scratch directory for partial downloads. */
    @NonNull
    public File tmpDir() {
        File f = new File(root, TMP);
        //noinspection ResultOfMethodCallIgnored
        f.mkdirs();
        return f;
    }

    /** Directory that holds one asset's verified files. */
    @NonNull
    public File dirFor(@NonNull Asset asset) {
        File f = new File(root, asset.id);
        //noinspection ResultOfMethodCallIgnored
        f.mkdirs();
        return f;
    }

    /**
     * Expands a stored file name into the directory it lives in.
     *
     * <p>Downloaded zips are unpacked flat, so every file of an asset sits in
     * {@link #dirFor}. The name is validated to prevent a crafted manifest entry
     * from escaping the directory.
     */
    @NonNull
    public File fileFor(@NonNull Asset asset, @NonNull String fileName) {
        if (fileName.contains("/") || fileName.contains("\\")
                || fileName.equals("..") || fileName.startsWith(".")) {
            throw new IllegalArgumentException("unsafe asset file name: " + fileName);
        }
        return new File(dirFor(asset), fileName);
    }

    /** True when a non-empty file exists at this path. */
    public boolean isPresent(@Nullable File f) {
        return f != null && f.isFile() && f.length() > 0;
    }

    /**
     * True when every file of this asset is installed and still matches what was
     * verified at install time.
     *
     * <p>For a raw asset the downloaded file is the artifact, so its exact size is
     * checked. For an archive the members are unpacked flat, so presence is checked
     * and a marker recording the verified archive hash is compared — the hash is the
     * anchor, not merely "some file exists".
     */
    public boolean hasAllFiles(@NonNull Asset asset, @NonNull List<AssetManifest.Entry> entries) {
        for (AssetManifest.Entry e : entries) {
            if (e.isArchive()) {
                for (String member : e.members) {
                    if (!isPresent(fileFor(asset, member))) {
                        return false;
                    }
                }
                if (!e.sha256.equals(readText(markerFor(asset, e), 128))) {
                    return false;
                }
            } else {
                File f = fileFor(asset, e.file);
                if (!f.isFile() || f.length() != e.size) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Marker recording that the archive with this hash was unpacked successfully.
     * Written last, so a half-unpacked archive is never treated as installed.
     */
    @NonNull
    public File markerFor(@NonNull Asset asset, @NonNull AssetManifest.Entry entry) {
        return new File(dirFor(asset), "." + entry.file + ".sha256");
    }

    /** Absolute path used for the download while it is in flight. */
    @NonNull
    public File partialFileFor(@NonNull AssetManifest.Entry entry) {
        return new File(tmpDir(), entry.file + ".part");
    }

    /**
     * Bytes this asset actually occupies on device.
     *
     * <p>Single source of truth: both the OpenCV and the model views asked for this and
     * each got it subtly wrong, because a zipped asset is stored as its unpacked members
     * rather than under the archive's published name.
     */
    public long installedSize(@NonNull Asset asset,
                              @NonNull List<AssetManifest.Entry> entries) {
        long sum = 0L;
        for (AssetManifest.Entry e : entries) {
            if (e.isArchive() && e.members != null) {
                for (String member : e.members) {
                    File f = fileFor(asset, member);
                    if (isPresent(f)) {
                        sum += f.length();
                    }
                }
            } else {
                File f = fileFor(asset, e.file);
                if (isPresent(f)) {
                    sum += f.length();
                }
            }
        }
        return sum;
    }

    /** Reads up to {@code max} bytes of a UTF-8 text file, or null when absent. */
    @Nullable
    public static String readText(@Nullable File f, int max) {
        if (f == null || !f.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[Math.min(max, (int) Math.max(1, f.length()))];
            int read = 0;
            while (read < buf.length) {
                int n = in.read(buf, read, buf.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return new String(buf, 0, read, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Writes text atomically: temp file then rename, so a crash cannot truncate. */
    public static void writeTextAtomically(@NonNull File target, @NonNull String text)
            throws IOException {
        File parent = target.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        File tmp = new File(target.getPath() + ".new");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("could not replace " + target.getName());
        }
    }

    /** Lowercase hex SHA-256 of a file. */
    @NonNull
    public static String sha256(@NonNull File f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[1 << 16];
                int read;
                while ((read = in.read(buf)) != -1) {
                    md.update(buf, 0, read);
                }
            }
            return toHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every Android platform; treat absence as fatal.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @NonNull
    private static String toHex(@NonNull byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }

    /** Deletes every downloaded file for one asset. Returns true when it is gone. */
    public boolean deleteAsset(@NonNull Asset asset) {
        return deleteRecursively(dirFor(asset));
    }

    /** Deletes scratch files. Called on start so a killed download cannot resume garbage. */
    public void clearTmp() {
        deleteRecursively(tmpDir());
    }

    private static boolean deleteRecursively(@Nullable File f) {
        if (f == null || !f.exists()) {
            return true;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File kid : kids) {
                    deleteRecursively(kid);
                }
            }
        }
        return f.delete();
    }
}
