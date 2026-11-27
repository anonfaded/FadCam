package com.fadcam.assets;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Resumable single-file download.
 *
 * <p>Uses a {@code Range} request so an interrupted transfer continues from where it
 * stopped instead of starting over. On a phone that is the difference between a
 * 12 MB download and re-fetching 12 MB because the user switched apps for a moment.
 *
 * <p>If the server ignores the Range header and answers {@code 200} instead of
 * {@code 206}, the partial file is discarded and the transfer restarts, which is the
 * only correct behaviour: appending a full response to a partial file would corrupt it.
 */
public final class AssetDownloader {

    private static final String TAG = "AssetDownloader";

    /** Progress is reported at most this often, to avoid thrashing the UI and log. */
    private static final long PROGRESS_INTERVAL_MS = 250L;

    /** Receives transfer progress. Called on the download thread, not the main thread. */
    public interface Listener {
        /**
         * @param bytesDownloaded bytes present on disk for this file, including any
         *                        previously resumed portion
         * @param totalBytes      expected total, or -1 when the server did not say
         */
        void onProgress(long bytesDownloaded, long totalBytes);
    }

    private final OkHttpClient client;

    public AssetDownloader(@NonNull OkHttpClient client) {
        this.client = client;
    }

    /**
     * Downloads {@code url} into {@code target}, resuming when possible.
     *
     * @param target   destination file; an existing file is treated as a partial
     *                 download and resumed
     * @param listener optional progress listener
     * @throws IOException on network or disk failure
     */
    public void download(@NonNull String url, @NonNull File target,
                         @Nullable Listener listener) throws IOException {
        File parent = target.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        long existing = target.isFile() ? target.length() : 0L;

        Request.Builder rb = new Request.Builder().url(url).get();
        if (existing > 0L) {
            rb.header("Range", "bytes=" + existing + "-");
        }
        Request request = rb.build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " for " + url);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("empty response body for " + url);
            }

            boolean resumed = response.code() == 206;
            long startingAt = resumed ? existing : 0L;
            if (!resumed && existing > 0L) {
                // Server ignored Range. Anything already on disk is now unusable.
                FLog.w(TAG, "server ignored Range, restarting " + target.getName());
            }

            long total = body.contentLength() >= 0
                    ? startingAt + body.contentLength()
                    : -1L;

            long written = startingAt;
            long lastReport = 0L;
            try (InputStream in = new BufferedInputStream(body.byteStream(), 1 << 16);
                 FileOutputStream out = new FileOutputStream(target, resumed)) {
                byte[] buf = new byte[1 << 16];
                int read;
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                    written += read;
                    long now = System.currentTimeMillis();
                    if (listener != null && now - lastReport >= PROGRESS_INTERVAL_MS) {
                        lastReport = now;
                        listener.onProgress(written, total);
                    }
                }
                out.getFD().sync();
            }
            if (listener != null) {
                listener.onProgress(written, total >= 0 ? total : written);
            }
            FLog.i(TAG, "downloaded " + target.getName() + " " + written + " bytes"
                    + (resumed ? " (resumed from " + existing + ")" : ""));
        }
    }

    /**
     * Bytes already present in a partial download, for deciding whether to resume.
     * Returns 0 when the file does not exist.
     */
    public static long partialBytes(@NonNull File target) {
        return target.isFile() ? target.length() : 0L;
    }

    /**
     * Truncates a partial file to a known-good length.
     *
     * <p>Used when the server reports a total smaller than what is on disk, which means
     * the partial file is from a different (probably older) build of the asset and
     * cannot be resumed.
     */
    public static void truncateTo(@NonNull File target, long length) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(target, "rw")) {
            raf.setLength(Math.max(0L, length));
        }
    }

    /** Deletes a partial download. */
    public static void discard(@NonNull File target) {
        //noinspection ResultOfMethodCallIgnored
        target.delete();
    }
}
