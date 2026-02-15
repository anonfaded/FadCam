package com.fadcam.forensics.domain.fingerprint;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

public final class VisualFingerprintGenerator {

    private static final String TAG = "VisualFingerprintGen";
    private static final int HASH_SIZE = 8;

    private VisualFingerprintGenerator() {
    }

    public static String compute(Context context, Uri uri) {
        if (context == null || uri == null) {
            return null;
        }

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap frame = null;
        Bitmap scaled = null;
        try {
            retriever.setDataSource(context, uri);
            frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) {
                frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            if (frame == null) {
                return null;
            }

            scaled = Bitmap.createScaledBitmap(frame, HASH_SIZE, HASH_SIZE, true);
            int[] pixels = new int[HASH_SIZE * HASH_SIZE];
            scaled.getPixels(pixels, 0, HASH_SIZE, 0, 0, HASH_SIZE, HASH_SIZE);

            int[] lum = new int[pixels.length];
            long sum = 0L;
            for (int i = 0; i < pixels.length; i++) {
                int p = pixels[i];
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                int y = (r * 299 + g * 587 + b * 114) / 1000;
                lum[i] = y;
                sum += y;
            }

            int avg = (int) (sum / lum.length);
            long hash = 0L;
            for (int i = 0; i < lum.length; i++) {
                if (lum[i] >= avg) {
                    hash |= (1L << i);
                }
            }
            return String.format("%016x", hash);
        } catch (Exception e) {
            Log.w(TAG, "compute failed for " + uri, e);
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
            if (scaled != null && !scaled.isRecycled()) {
                scaled.recycle();
            }
            if (frame != null && !frame.isRecycled()) {
                frame.recycle();
            }
        }
    }
}
