package com.fadcam.forensics.domain.fingerprint;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

public final class ForensicsMetadataUtils {

    private ForensicsMetadataUtils() {
    }

    public static long extractDurationMs(Context context, Uri uri) {
        String durationRaw = extractMetadata(context, uri, MediaMetadataRetriever.METADATA_KEY_DURATION);
        if (durationRaw == null) {
            return 0L;
        }
        try {
            return Long.parseLong(durationRaw);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    public static String extractCodecInfo(Context context, Uri uri) {
        return extractMetadata(context, uri, MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
    }

    private static String extractMetadata(Context context, Uri uri, int key) {
        if (context == null || uri == null) {
            return null;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            return retriever.extractMetadata(key);
        } catch (Exception ignored) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }
}
